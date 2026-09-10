package com.bandwidth.rtc

import android.content.Context
import com.bandwidth.rtc.media.MixingAudioDevice
import com.bandwidth.rtc.signaling.SignalingClient
import com.bandwidth.rtc.signaling.SignalingClientInterface
import com.bandwidth.rtc.signaling.rpc.SDPOfferNotification
import com.bandwidth.rtc.types.*
import com.bandwidth.rtc.util.LogLevel
import com.bandwidth.rtc.util.Logger
import com.bandwidth.rtc.webrtc.PeerConnectionManager
import com.bandwidth.rtc.webrtc.PeerConnectionManagerInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.webrtc.PeerConnection
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

private const val RECONNECT_INITIAL_DELAY_MS = 1_000L
private const val RECONNECT_MAX_DELAY_MS = 30_000L
private const val RECONNECT_JITTER_MS = 500L
private const val RECONNECT_MAX_ATTEMPTS = 8

/**
 * Main entry point for the Bandwidth BRTC SDK.
 *
 * Usage:
 * ```kotlin
 * val brtc = BandwidthRTC(context)
 * brtc.onStreamAvailable = { stream -> /* Handle remote audio streams */ }
 * brtc.connect(RtcAuthParams(endpointToken = jwt))
 * val localStream = brtc.publish(audio = true)
 * ```
 */
class BandwidthRTC(
    private val context: Context,
    logLevel: LogLevel = LogLevel.WARN
) {

    /** Called when a new remote stream becomes available. */
    var onStreamAvailable: ((RtcStream) -> Unit)? = null

    /** Called when a remote stream is removed. */
    var onStreamUnavailable: ((String) -> Unit)? = null

    /** Called when the BRTC platform signals readiness. */
    var onReady: ((ReadyMetadata) -> Unit)? = null

    /** Called when the remote side disconnects (subscribe ICE disconnected/failed). */
    var onRemoteDisconnected: (() -> Unit)? = null

    /** Called with Float32 audio samples for visualization after each mic capture chunk. */
    var onLocalAudioLevel: ((FloatArray) -> Unit)? = null

    /** Called with Float32 audio samples for visualization after each remote audio playout chunk. */
    var onRemoteAudioLevel: ((FloatArray) -> Unit)? = null

    /** Called once per DTMF tone queued for local playback on a published stream (see `sendDtmf`). */
    var onDtmfSent: ((DtmfSentEvent) -> Unit)? = null

    /**
     * Called when the session fails in a way the SDK cannot recover from on its own - reconnect
     * attempts exhausted, a handshake rejection that will not resolve on retry, or a failure to
     * republish local media after reconnecting. The session is not usable until [connect] succeeds
     * again, so applications should surface this rather than keep showing a connected state.
     */
    var onError: ((Throwable) -> Unit)? = null

    internal var signaling: SignalingClientInterface? = null
    internal var peerConnectionManager: PeerConnectionManagerInterface? = null
    private var injectedSignaling: SignalingClientInterface? = null
    private var injectedPeerConnectionManager: PeerConnectionManagerInterface? = null
    private var options: RtcOptions? = null
    private var authParams: RtcAuthParams? = null

    /** Streams published through this instance, retained so they can be re-published after a reconnect. */
    private val publishRecords = CopyOnWriteArrayList<PublishRecord>()

    @Volatile
    private var userInitiatedDisconnect = false
    private var reconnectJob: Job? = null

    private class PublishRecord(val audio: Boolean, val alias: String?, @Volatile var stream: RtcStream)

    /** Custom audio device — owns mic capture and remote audio playout. */
    var mixingDevice: MixingAudioDevice? = null
        private set

    var isConnected: Boolean = false
        private set

    /** True while an outbound or inbound call is active. Guards against processing stale SDP offers after hangup. */
    var hasActiveCall: Boolean = false
        internal set

    private val json = Json { ignoreUnknownKeys = true }
    private var scope: CoroutineScope = CoroutineScope(Dispatchers.IO)

    init {
        Logger.level = logLevel
    }

    @Suppress("unused")
    internal constructor(
        context: Context,
        logLevel: LogLevel = LogLevel.WARN,
        signaling: SignalingClientInterface?,
        peerConnectionManager: PeerConnectionManagerInterface?,
        scope: CoroutineScope? = null
    ) : this(context, logLevel) {
        this.signaling = signaling
        this.peerConnectionManager = peerConnectionManager
        this.injectedSignaling = signaling
        this.injectedPeerConnectionManager = peerConnectionManager
        scope?.let { this.scope = it }
    }

    /** Connect to the BRTC platform using a JWT endpoint token. */
    suspend fun connect(authParams: RtcAuthParams, options: RtcOptions? = null) {
        Logger.info("BandwidthRTC connect() called")
        if (isConnected) throw BandwidthRTCError.AlreadyConnected()

        this.authParams = authParams
        this.options = options
        userInitiatedDisconnect = false

        try {
            establishSession()
        } catch (e: Exception) {
            // A partial failure here would otherwise leave `signaling` (and its open
            // WebSocket/ping loop) behind: isConnected stays false, so every retry
            // reuses that dead client, which immediately throws AlreadyConnected.
            Logger.error("connect() failed, tearing down partial session: ${e.message}")
            cleanupSession()
            throw e
        }
    }

    /**
     * Builds a full session (signaling, peer connections, initial SDP) from the stored auth
     * params. Used by both [connect] and the reconnect loop, which is why failure here does not
     * itself tear down the session - [connect] does that around its own call, but a reconnect
     * attempt failing is expected and handled by retrying with backoff instead.
     */
    private suspend fun establishSession() {
        val authParams = this.authParams ?: throw BandwidthRTCError.NotConnected()
        val options = this.options

        val sig: SignalingClientInterface = signaling
            ?: injectedSignaling?.also { signaling = it }
            ?: SignalingClient().also { signaling = it }

        registerEventHandlers(sig)

        Logger.info("Connecting signaling...")
        sig.connect(authParams = authParams, options = options)

        val existingPCMgr = peerConnectionManager ?: injectedPeerConnectionManager?.also { peerConnectionManager = it }
        val pcMgr: PeerConnectionManagerInterface
        if (existingPCMgr != null) {
            pcMgr = existingPCMgr
        } else {
            Logger.info("Initializing mixing device...")
            val mixing = MixingAudioDevice(context, options?.audioProcessing ?: AudioProcessingOptions())
            mixing.onLocalAudioLevel = { samples -> onLocalAudioLevel?.invoke(samples) }
            mixing.onRemoteAudioLevel = { samples -> onRemoteAudioLevel?.invoke(samples) }
            this.mixingDevice = mixing

            Logger.info("Initializing peer connection manager...")
            val newPCMgr = PeerConnectionManager(context, options, mixing.audioDeviceModule)
            this.peerConnectionManager = newPCMgr
            newPCMgr.setupPublishingPeerConnection()
            newPCMgr.setupSubscribingPeerConnection()
            pcMgr = newPCMgr
        }

        pcMgr.onStreamAvailable = { stream, mediaTypes, metadata ->
            val rtcStream = RtcStream(
                mediaStream = stream,
                mediaTypes = mediaTypes,
                from = metadata?.from,
                fromType = metadata?.fromType,
                autoAccepted = metadata?.autoAccepted,
                tags = metadata?.tags
            )
            Logger.info("onStreamAvailable: ${rtcStream.streamId}")
            onStreamAvailable?.invoke(rtcStream)
        }
        pcMgr.onStreamUnavailable = { streamId ->
            Logger.info("onStreamUnavailable: $streamId")
            onStreamUnavailable?.invoke(streamId)
        }
        pcMgr.onDtmfSent = { event -> onDtmfSent?.invoke(event) }
        pcMgr.onSubscribingIceConnectionStateChange = { state ->
            Logger.info("Subscribe ICE state changed: $state")
            if (state == PeerConnection.IceConnectionState.DISCONNECTED ||
                state == PeerConnection.IceConnectionState.FAILED
            ) {
                Logger.info("Subscribe ICE disconnected/failed — remote side likely hung up, clearing active call")
                hasActiveCall = false
                onRemoteDisconnected?.invoke()
            }
        }

        Logger.info("Sending setMediaPreferences...")
        val autoAccept = options?.autoAccept ?: true
        val mediaResult = sig.setMediaPreferences(autoAccept = autoAccept)
        Logger.debug("setMediaPreferences result: endpoint=${mediaResult.endpointId}, hasPublishOffer=${mediaResult.publishSdpOffer != null}, hasSubscribeOffer=${mediaResult.subscribeSdpOffer != null}")

        mediaResult.publishSdpOffer?.sdpOffer?.let { publishOffer ->
            Logger.debug("Answering initial publish SDP offer...")
            val publishAnswer = pcMgr.answerInitialOffer(sdpOffer = publishOffer, pcType = PeerConnectionType.PUBLISH)
            sig.answerSdp(sdpAnswer = publishAnswer, peerType = "publish")
            Logger.debug("Initial publish SDP answer sent")
        }

        mediaResult.subscribeSdpOffer?.sdpOffer?.let { subscribeOffer ->
            Logger.debug("Answering initial subscribe SDP offer...")
            val subscribeAnswer = pcMgr.answerInitialOffer(sdpOffer = subscribeOffer, pcType = PeerConnectionType.SUBSCRIBE)
            sig.answerSdp(sdpAnswer = subscribeAnswer, peerType = "subscribe")
            Logger.debug("Initial subscribe SDP answer sent")
        }

        isConnected = true
        hasActiveCall = true
        Logger.info("Connected to BRTC (endpoint=${mediaResult.endpointId ?: "unknown"})")

        val readyMetadata = ReadyMetadata(
            endpointId = mediaResult.endpointId,
            deviceId = mediaResult.deviceId
        )
        onReady?.invoke(readyMetadata)
    }

    /** Disconnect from the BRTC platform. */
    suspend fun disconnect() {
        Logger.info("BandwidthRTC disconnect() called")
        userInitiatedDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        publishRecords.clear()
        cleanupSession()
        Logger.info("Disconnected from BRTC")
    }

    private suspend fun cleanupSession() {
        Logger.info("Cleaning up session...")

        // 1. Disconnect signaling first to stop incoming SDP offers during teardown
        signaling?.disconnect()
        signaling = null

        // 2. Clean up peer connections and the audio device they use
        releaseMedia()

        isConnected = false
        hasActiveCall = false
    }

    /**
     * Releases the media resources of a session that is over. Peer connections go first because they
     * may still reference the audio device while closing.
     */
    private fun releaseMedia() {
        peerConnectionManager?.cleanup()
        peerConnectionManager = null
        mixingDevice?.release()
        mixingDevice = null
    }

    /** Publish local audio. Adds local tracks, then creates a client-initiated offer sent via offerSdp. */
    suspend fun publish(audio: Boolean = true, alias: String? = null): RtcStream {
        Logger.info("BandwidthRTC publish() called audio=$audio alias=$alias")
        val pcManager = peerConnectionManager
        val signalingClient = signaling
        if (!isConnected || pcManager == null || signalingClient == null) {
            throw BandwidthRTCError.NotConnected()
        }

        Logger.debug("Waiting for publish PC ICE to connect...")
        pcManager.waitForPublishIceConnected()
        Logger.debug("Publish PC ICE connected — proceeding with publish")

        val mediaStream = pcManager.addLocalTracks(audio = audio)

        val localOffer = pcManager.createPublishOffer()
        Logger.debug("Created publish offer with local tracks")

        val result = signalingClient.offerSdp(sdpOffer = localOffer, peerType = "publish")
        Logger.debug("Server answered publish offer")

        pcManager.applyPublishAnswer(remoteAnswer = result.sdpAnswer)
        Logger.debug("Publish SDP exchange complete")

        val mediaTypes = mutableListOf<MediaType>()
        if (audio) mediaTypes.add(MediaType.AUDIO)

        val stream = RtcStream(mediaStream = mediaStream, mediaTypes = mediaTypes, alias = alias)
        publishRecords.add(PublishRecord(audio = audio, alias = alias, stream = stream))
        Logger.info("Published stream ${stream.streamId}")
        return stream
    }

    /** Unpublish a previously published stream. */
    suspend fun unpublish(stream: RtcStream) {
        Logger.info("BandwidthRTC unpublish() called: ${stream.streamId}")
        val pcManager = peerConnectionManager
        val signalingClient = signaling
        if (!isConnected || pcManager == null || signalingClient == null) {
            throw BandwidthRTCError.NotConnected()
        }

        publishRecords.removeIf { it.stream.streamId == stream.streamId }
        pcManager.removeLocalTracks(streamId = stream.streamId)

        val localOffer = pcManager.createPublishOffer()
        val result = signalingClient.offerSdp(sdpOffer = localOffer, peerType = "publish")
        pcManager.applyPublishAnswer(remoteAnswer = result.sdpAnswer)

        Logger.info("Unpublished stream ${stream.streamId}")
    }

    /** Enable or disable the microphone for all published streams. */
    fun setMicEnabled(enabled: Boolean) {
        Logger.info("BandwidthRTC setMicEnabled($enabled)")
        peerConnectionManager?.setAudioEnabled(enabled)
    }

    /** Route audio to the speakerphone or earpiece. */
    fun setSpeakerphoneOn(enabled: Boolean) {
        Logger.info("BandwidthRTC setSpeakerphoneOn($enabled)")
        mixingDevice?.setSpeakerphoneOn(enabled)
    }

    /** Send DTMF tones. */
    fun sendDtmf(tone: String, duration: Int = 100, interToneGap: Int = 50) {
        Logger.info("BandwidthRTC sendDtmf($tone, duration=$duration, interToneGap=$interToneGap)")
        peerConnectionManager?.sendDtmf(tone, duration, interToneGap)
    }

    /** Get a snapshot of current call statistics. */
    fun getCallStats(
        previousSnapshot: CallStatsSnapshot?,
        completion: (CallStatsSnapshot) -> Unit
    ) {
        val pcManager = peerConnectionManager
        if (pcManager == null) {
            completion(CallStatsSnapshot())
            return
        }

        pcManager.getCallStats(
            previousInboundBytes = previousSnapshot?.bytesReceived ?: 0,
            previousOutboundBytes = previousSnapshot?.bytesSent ?: 0,
            previousTimestamp = previousSnapshot?.timestamp ?: 0.0,
        ) { snapshot ->
            val level = snapshot.audioLevel.toFloat()
            val samples = FloatArray(9600) { level }
            onRemoteAudioLevel?.invoke(samples)
            completion(snapshot)
        }
    }

    /** Request an outbound connection to a phone number, endpoint, or call ID. */
    suspend fun requestOutboundConnection(id: String, type: EndpointType): OutboundConnectionResult {
        Logger.info("BandwidthRTC requestOutboundConnection($id, $type)")
        val sig = signaling
        if (sig == null || !isConnected) throw BandwidthRTCError.NotConnected()
        hasActiveCall = true
        return sig.requestOutboundConnection(id = id, type = type)
    }

    /** Hang up a connection. */
    suspend fun hangupConnection(endpoint: String, type: EndpointType): HangupResult {
        Logger.info("hangupConnection called (endpoint=$endpoint, type=$type)")
        val sig = signaling
        if (sig == null || !isConnected) throw BandwidthRTCError.NotConnected()
        val result = sig.hangupConnection(endpoint = endpoint, type = type)
        Logger.info("hangupConnection succeeded (result=${result.result}) — clearing active call")
        hasActiveCall = false
        return result
    }

    /** Accept an inbound call that was parked (not auto-accepted). */
    suspend fun acceptStream() {
        Logger.info("BandwidthRTC acceptStream() called")
        val sig = signaling
        if (sig == null || !isConnected) throw BandwidthRTCError.NotConnected()
        sig.acceptStream()
    }

    /** Decline an inbound call that was parked (not auto-accepted). */
    suspend fun declineStream() {
        Logger.info("BandwidthRTC declineStream() called")
        val sig = signaling
        if (sig == null || !isConnected) throw BandwidthRTCError.NotConnected()
        sig.declineStream()
    }

    /** Set the SDK log level. */
    fun setLogLevel(level: LogLevel) {
        Logger.level = level
    }

    private fun registerEventHandlers(signaling: SignalingClientInterface) {
        signaling.onEvent("sdpOffer") { data ->
            Logger.info("Signaling event: sdpOffer")
            scope.launch {
                handleSubscribeSdpOffer(data)
            }
        }

        signaling.onEvent("ready") { data ->
            Logger.info("Signaling event: ready")
            val metadata: ReadyMetadata = if (data.isEmpty()) {
                ReadyMetadata()
            } else {
                try {
                    json.decodeFromString(ReadyMetadata.serializer(), data)
                } catch (e: Exception) {
                    ReadyMetadata()
                }
            }
            Logger.debug("Ready event: endpoint=${metadata.endpointId}")
            onReady?.invoke(metadata)
        }

        signaling.onEvent("established") {
            Logger.info("Signaling event: established")
        }

        val deadSignaling = signaling
        signaling.onEvent("close") {
            // A late close from a client we have already replaced must not tear down the new session.
            if (this.signaling !== deadSignaling) {
                Logger.info("Ignoring close from a replaced signaling client")
                return@onEvent
            }

            Logger.info("Signaling event: close")
            Logger.warn("WebSocket closed")
            isConnected = false
            hasActiveCall = false

            // The peer connections belong to the session that just died. Release them here or they
            // dangle for the lifetime of the instance and pile up across reconnects.
            releaseMedia()
            this.signaling = null

            if (reconnectJob?.isActive != true) reconnectJob = scope.launch {
                runCatching { deadSignaling.disconnect() }
                scheduleReconnect()
            }
        }
    }

    /**
     * Reconnects with bounded exponential backoff after a server-initiated close.
     *
     * Nothing is attempted after an application-initiated [disconnect], and retrying stops early on
     * a handshake rejection that would just recur.
     */
    private suspend fun scheduleReconnect() {
        if (userInitiatedDisconnect) {
            Logger.info("Not reconnecting - disconnect was application initiated")
            return
        }
        if (authParams == null) return
        if (isConnected) return

        var delayMs = RECONNECT_INITIAL_DELAY_MS
        var lastError: Throwable? = null

        for (attempt in 1..RECONNECT_MAX_ATTEMPTS) {
            delay(delayMs + Random.nextLong(RECONNECT_JITTER_MS))
            if (userInitiatedDisconnect || isConnected) return

            Logger.info("Reconnect attempt $attempt/$RECONNECT_MAX_ATTEMPTS")
            try {
                establishSession()
                republishStreams()
                Logger.info("Reconnected successfully")
                return
            } catch (e: Exception) {
                lastError = e
                Logger.error("Reconnect attempt $attempt failed: ${e.message}")
                discardSession()
                if (isFatalHandshakeError(e)) {
                    Logger.error("Aborting reconnect - handshake error will not resolve on retry")
                    break
                }
            }
            delayMs = minOf(delayMs * 2, RECONNECT_MAX_DELAY_MS)
        }

        Logger.error("Reconnect gave up - session is dead")
        onError?.invoke(lastError ?: BandwidthRTCError.WebSocketDisconnected())
    }

    /** Handshake failures that recur on every attempt, so retrying only delays the error. */
    private fun isFatalHandshakeError(e: Throwable): Boolean = when (e) {
        is BandwidthRTCError.InvalidToken -> true
        is BandwidthRTCError.RpcError -> e.code == 403 || e.code == 409
        else -> false
    }

    /** Tears down a half-built session so the next reconnect attempt starts from nothing. */
    private suspend fun discardSession() {
        runCatching { signaling?.disconnect() }
        signaling = null
        releaseMedia()
        isConnected = false
        hasActiveCall = false
    }

    /**
     * Re-attaches every retained published stream to the new publishing peer connection, then
     * renegotiates once for all of them. Without this the platform never sees RTP from us again and
     * the endpoint stays ineligible for calls. A first connect retains nothing, so this is a no-op.
     */
    private suspend fun republishStreams() {
        if (publishRecords.isEmpty()) return

        val pcManager = peerConnectionManager ?: throw BandwidthRTCError.PublishFailed("No peer connection manager")
        val signalingClient = signaling ?: throw BandwidthRTCError.NotConnected()

        Logger.info("Republishing ${publishRecords.size} stream(s)")
        pcManager.waitForPublishIceConnected()

        for (record in publishRecords) {
            val mediaStream = pcManager.republishLocalStream(streamId = record.stream.streamId, audio = record.audio)
            record.stream = RtcStream(
                mediaStream = mediaStream,
                mediaTypes = record.stream.mediaTypes,
                alias = record.alias
            )
        }

        // A single renegotiation covers every republished stream.
        val localOffer = pcManager.createPublishOffer()
        val result = signalingClient.offerSdp(sdpOffer = localOffer, peerType = "publish")
        pcManager.applyPublishAnswer(remoteAnswer = result.sdpAnswer)
        Logger.info("Republish complete")
    }

    private suspend fun handleSubscribeSdpOffer(data: String) {
        Logger.debug("Subscribe SDP offer received (${data.length} chars)")

        if (!hasActiveCall) {
            Logger.info("Ignoring SDP offer — no active call (post-hangup)")
            return
        }

        val pcManager = peerConnectionManager
        val sig = signaling
        if (pcManager == null || sig == null) {
            Logger.error("Subscribe SDP offer received but pcManager or signaling is null")
            return
        }

        try {
            val notification = try {
                json.decodeFromString(SDPOfferNotification.serializer(), data)
            } catch (e: Exception) {
                Logger.error("Failed to decode SDPOfferNotification: ${e.message}")
                Logger.error("Raw data preview: ${data.take(500)}")
                return
            }

            Logger.debug("Subscribe SDP offer: revision=${notification.sdpRevision}, peerType=${notification.peerType}, endpointId=${notification.endpointId}")

            val answerSdp = pcManager.handleSubscribeSdpOffer(
                sdpOffer = notification.sdpOffer,
                sdpRevision = notification.sdpRevision,
                metadata = notification.trackMetadata
            )

            sig.answerSdp(sdpAnswer = answerSdp, peerType = "subscribe")

            Logger.debug("Subscribe SDP answer sent (revision=${notification.sdpRevision})")
        } catch (e: Exception) {
            Logger.error("Failed to handle subscribe SDP offer: ${e.message}")
        }
    }
}
