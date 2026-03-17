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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.webrtc.PeerConnection

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

    // MARK: - Public Callbacks

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

    // MARK: - Internal Components

    internal var signaling: SignalingClientInterface? = null
    internal var peerConnectionManager: PeerConnectionManagerInterface? = null
    private var options: RtcOptions? = null

    /** Custom audio device — owns mic capture and remote audio playout. */
    var mixingDevice: MixingAudioDevice? = null
        private set

    // MARK: - State

    var isConnected: Boolean = false
        private set

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        Logger.level = logLevel
    }

    /** Internal constructor for testing — injects mock signaling and peer connection manager. */
    @Suppress("unused")
    internal constructor(
        context: Context,
        logLevel: LogLevel = LogLevel.WARN,
        signaling: SignalingClientInterface?,
        peerConnectionManager: PeerConnectionManagerInterface?
    ) : this(context, logLevel) {
        this.signaling = signaling
        this.peerConnectionManager = peerConnectionManager
    }

    // MARK: - Connection

    /** Connect to the BRTC platform using a JWT endpoint token. */
    suspend fun connect(authParams: RtcAuthParams, options: RtcOptions? = null) {
        Logger.info("BandwidthRTC connect() called")
        if (isConnected) throw BandwidthRTCError.AlreadyConnected()

        this.options = options

        // Use injected signaling or create new
        val sig: SignalingClientInterface = signaling ?: SignalingClient().also { signaling = it }

        // Register event handlers before connecting
        registerEventHandlers(sig)

        // Connect WebSocket
        Logger.info("Connecting signaling...")
        sig.connect(authParams = authParams, options = options)

        // Use injected peer connection manager or create new
        val pcMgr: PeerConnectionManagerInterface
        if (peerConnectionManager != null) {
            pcMgr = peerConnectionManager!!
        } else {
            // Create the custom audio device
            Logger.info("Initializing mixing device...")
            val mixing = MixingAudioDevice(context, options?.audioProcessing ?: AudioProcessingOptions())
            mixing.onLocalAudioLevel = { samples -> onLocalAudioLevel?.invoke(samples) }
            mixing.onRemoteAudioLevel = { samples -> onRemoteAudioLevel?.invoke(samples) }
            this.mixingDevice = mixing

            // Set up peer connections with the custom audio device module
            Logger.info("Initializing peer connection manager...")
            val newPCMgr = PeerConnectionManager(context, options, mixing.audioDeviceModule)
            this.peerConnectionManager = newPCMgr
            newPCMgr.setupPublishingPeerConnection()
            newPCMgr.setupSubscribingPeerConnection()
            pcMgr = newPCMgr
        }

        // Wire up peer connection callbacks
        pcMgr.onStreamAvailable = { stream, mediaTypes ->
            val rtcStream = RtcStream(mediaStream = stream, mediaTypes = mediaTypes)
            Logger.info("onStreamAvailable: ${rtcStream.streamId}")
            onStreamAvailable?.invoke(rtcStream)
        }
        pcMgr.onStreamUnavailable = { streamId ->
            Logger.info("onStreamUnavailable: $streamId")
            onStreamUnavailable?.invoke(streamId)
        }
        pcMgr.onSubscribingIceConnectionStateChange = { state ->
            Logger.info("onSubscribingIceConnectionStateChange: $state")
            if (state == PeerConnection.IceConnectionState.DISCONNECTED ||
                state == PeerConnection.IceConnectionState.FAILED
            ) {
                Logger.info("Subscribe ICE disconnected/failed — remote side likely hung up")
                onRemoteDisconnected?.invoke()
            }
        }

        // Send setMediaPreferences to initiate the signaling flow
        Logger.info("Sending setMediaPreferences...")
        val mediaResult = sig.setMediaPreferences()
        Logger.debug("setMediaPreferences result: endpoint=${mediaResult.endpointId}, hasPublishOffer=${mediaResult.publishSdpOffer != null}, hasSubscribeOffer=${mediaResult.subscribeSdpOffer != null}")

        // Answer BOTH initial SDP offers immediately (no tracks)
        mediaResult.publishSdpOffer?.sdpOffer?.let { publishOffer ->
            Logger.debug("Answering initial publish SDP offer (no tracks)...")
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
        cleanupSession()
        Logger.info("Disconnected from BRTC")
    }

    // MARK: - Private: Session Cleanup

    private suspend fun cleanupSession() {
        Logger.info("Cleaning up session...")
        peerConnectionManager?.cleanup()
        peerConnectionManager = null
        mixingDevice?.release()
        mixingDevice = null
        signaling?.disconnect()
        signaling = null
        isConnected = false
    }

    // MARK: - Publishing

    /** Publish local audio. Adds local tracks, then creates a client-initiated offer sent via offerSdp. */
    suspend fun publish(audio: Boolean = true, alias: String? = null): RtcStream {
        Logger.info("BandwidthRTC publish() called audio=$audio alias=$alias")
        val pcManager = peerConnectionManager
        val signalingClient = signaling
        if (!isConnected || pcManager == null || signalingClient == null) {
            throw BandwidthRTCError.NotConnected()
        }

        // 1. Wait for the publish PC's initial ICE handshake to complete
        Logger.debug("Waiting for publish PC ICE to connect...")
        pcManager.waitForPublishIceConnected()
        Logger.debug("Publish PC ICE connected — proceeding with publish")

        // 2. Add local audio track to the publishing peer connection
        val mediaStream = pcManager.addLocalTracks(audio = audio)

        // 3. Create a client-initiated offer with the newly added tracks
        val localOffer = pcManager.createPublishOffer()
        Logger.debug("Created publish offer with local tracks")

        // 4. Send the offer to the server via offerSdp — server returns an SDP answer
        val result = signalingClient.offerSdp(sdpOffer = localOffer, peerType = "publish")
        Logger.debug("Server answered publish offer")

        // 5. Apply the server's answer
        pcManager.applyPublishAnswer(remoteAnswer = result.sdpAnswer)
        Logger.debug("Publish SDP exchange complete")

        val mediaTypes = mutableListOf<MediaType>()
        if (audio) mediaTypes.add(MediaType.AUDIO)

        val stream = RtcStream(mediaStream = mediaStream, mediaTypes = mediaTypes, alias = alias)
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

        pcManager.removeLocalTracks(streamId = stream.streamId)

        val localOffer = pcManager.createPublishOffer()
        val result = signalingClient.offerSdp(sdpOffer = localOffer, peerType = "publish")
        pcManager.applyPublishAnswer(remoteAnswer = result.sdpAnswer)

        Logger.info("Unpublished stream ${stream.streamId}")
    }

    // MARK: - Media Control

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
    fun sendDtmf(tone: String) {
        Logger.info("BandwidthRTC sendDtmf($tone)")
        peerConnectionManager?.sendDtmf(tone)
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
            // Synthesize remote audio samples from the stats audioLevel so the
            // onRemoteAudioLevel callback (and waveform visualization) gets driven.
            // Android's WebRTC SDK has no playout-tap callback, so we use the
            // inbound-rtp audioLevel stat (0.0–1.0 linear amplitude) instead.
            val level = snapshot.audioLevel.toFloat()
            val samples = FloatArray(9600) { level }
            onRemoteAudioLevel?.invoke(samples)
            completion(snapshot)
        }
    }

    // MARK: - Call Control (Low-Level)

    /** Request an outbound connection to a phone number, endpoint, or call ID. */
    suspend fun requestOutboundConnection(id: String, type: EndpointType): OutboundConnectionResult {
        Logger.info("BandwidthRTC requestOutboundConnection($id, $type)")
        val sig = signaling
        if (sig == null || !isConnected) throw BandwidthRTCError.NotConnected()
        return sig.requestOutboundConnection(id = id, type = type)
    }

    /** Hang up a connection. */
    suspend fun hangupConnection(endpoint: String, type: EndpointType): HangupResult {
        Logger.info("BandwidthRTC hangupConnection($endpoint, $type)")
        val sig = signaling
        if (sig == null || !isConnected) throw BandwidthRTCError.NotConnected()
        return sig.hangupConnection(endpoint = endpoint, type = type)
    }

    // MARK: - Configuration

    /** Set the SDK log level. */
    fun setLogLevel(level: LogLevel) {
        Logger.level = level
    }

    // MARK: - Private: Event Handlers

    private fun registerEventHandlers(signaling: SignalingClientInterface) {
        // Handle incoming SDP offers for subscribing
        signaling.onEvent("sdpOffer") { data ->
            Logger.info("Signaling event: sdpOffer")
            scope.launch {
                handleSubscribeSdpOffer(data)
            }
        }

        // Handle ready event
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

        // Handle established event
        signaling.onEvent("established") {
            Logger.info("Signaling event: established")
            Logger.debug("Connection established")
        }

        // Handle disconnect
        signaling.onEvent("close") {
            Logger.info("Signaling event: close")
            Logger.warn("WebSocket closed")
            isConnected = false
        }
    }

    private suspend fun handleSubscribeSdpOffer(data: String) {
        Logger.debug(">>> Subscribe SDP offer received (${data.length} chars)")

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
                metadata = notification.streamSourceMetadata
            )

            sig.answerSdp(sdpAnswer = answerSdp, peerType = "subscribe")

            Logger.debug("<<< Subscribe SDP answer sent (revision=${notification.sdpRevision})")
        } catch (e: Exception) {
            Logger.error("Failed to handle subscribe SDP offer: ${e.message}")
        }
    }
}
