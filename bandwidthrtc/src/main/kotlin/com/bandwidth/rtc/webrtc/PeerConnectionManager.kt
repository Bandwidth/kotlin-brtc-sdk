package com.bandwidth.rtc.webrtc

import android.content.Context
import com.bandwidth.rtc.types.*
import com.bandwidth.rtc.util.Logger
import kotlinx.coroutines.delay
import org.webrtc.*
import org.webrtc.audio.AudioDeviceModule
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import org.webrtc.MediaStreamTrack

private const val PUBLISH_ICE_CONNECT_TIMEOUT_MS = 10_000L

/** How long [PeerConnectionManager.cleanup] waits for in-flight native calls before disposing anyway. */
private const val NATIVE_DRAIN_TIMEOUT_MS = 2_000L

class PeerConnectionManager(
    private val context: Context,
    private val options: RtcOptions?,
    private val audioDeviceModule: AudioDeviceModule? = null
) : PeerConnectionManagerInterface {

    private val log = Logger

    companion object {
        private var factoryInitialized = false
        private val VALID_DTMF_TONES = "0123456789ABCDabcd#*".toSet()
    }

    private val factory: PeerConnectionFactory

    private var publishingPC: PeerConnection? = null
    private var subscribingPC: PeerConnection? = null

    private var publishHeartbeatDC: DataChannel? = null
    private var publishDiagnosticsDC: DataChannel? = null
    private var subscribeHeartbeatDC: DataChannel? = null
    private var subscribeDiagnosticsDC: DataChannel? = null

    private val publishedStreams = ConcurrentHashMap<String, MediaStream>()
    private val publishedAudioSources = ConcurrentHashMap<String, AudioSource>()
    private val publishedAudioTracks = ConcurrentHashMap<String, AudioTrack>()
    private val subscribedTrackMetadata = ConcurrentHashMap<String, TrackMetadata>()
    var subscribeSdpRevision: Long = 0
        private set

    override var onStreamAvailable: ((MediaStream, List<MediaType>, TrackMetadata?) -> Unit)? = null
    override var onStreamUnavailable: ((String) -> Unit)? = null
    override var onSubscribingIceConnectionStateChange: ((PeerConnection.IceConnectionState) -> Unit)? = null
    override var onDtmfSent: ((DtmfSentEvent) -> Unit)? = null

    @Volatile
    private var publishIceConnected = false

    // Disposal is no longer something only the application asks for: a gateway-initiated websocket
    // close tears the session down mid-call, on whatever thread delivered that event, so an
    // application call like getCallStats() or sendDtmf() can land inside the disposal window.
    // Using a handle after dispose() is a JNI crash the application cannot catch, so every entry
    // point that dereferences one goes through [useNative].
    private val nativeLock = Object()

    @Volatile
    private var disposed = false

    /** Number of calls currently inside [useNative], i.e. holding a live reference to a handle. */
    private var activeNativeCalls = 0

    /**
     * Makes one SDP call with its peer connection guaranteed alive, failing the negotiation if the
     * manager has already been cleaned up.
     *
     * Only the calls issued from our own coroutine's thread need this. The nested
     * setLocalDescription() calls do not: they run inside a WebRTC observer callback on the
     * signaling thread, and dispose() blocks on that same thread, so WebRTC itself serializes them
     * against teardown.
     */
    private fun <T> Continuation<T>.withLiveNative(block: () -> Unit) {
        useNative(block) ?: resumeWithException(
            BandwidthRTCError.SdpNegotiationFailed("Peer connection manager has been cleaned up")
        )
    }

    /**
     * Runs [block] with the native handles guaranteed alive, or returns null if the manager has
     * already been cleaned up.
     *
     * The registration count, not the lock, is what holds disposal off: [nativeLock] is held only
     * long enough to register and deregister, never across [block]. That matters because WebRTC's
     * proxy calls (dispose, insertDtmf, createPeerConnection) block on its signaling thread, and
     * that same thread delivers the observer callbacks that reach application code - an
     * application handler calling back into the SDK while a lock was held across one of those
     * would deadlock.
     */
    private inline fun <T> useNative(block: () -> T): T? {
        synchronized(nativeLock) {
            if (disposed) return null
            activeNativeCalls++
        }
        try {
            return block()
        } finally {
            synchronized(nativeLock) {
                activeNativeCalls--
                nativeLock.notifyAll()
            }
        }
    }

    init {
        if (!factoryInitialized) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            factoryInitialized = true
        }

        val builder = PeerConnectionFactory.builder()
        if (audioDeviceModule != null) {
            builder.setAudioDeviceModule(audioDeviceModule)
        }
        factory = builder.createPeerConnectionFactory()
    }

    private fun createRtcConfiguration(): PeerConnection.RTCConfiguration {
        val iceServers = options?.iceServers ?: emptyList()
        val config = PeerConnection.RTCConfiguration(iceServers)
        config.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        config.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        config.iceTransportsType = options?.iceTransportPolicy ?: PeerConnection.IceTransportsType.ALL
        return config
    }

    override fun setupPublishingPeerConnection(): PeerConnection = useNative {
        val config = createRtcConfiguration()

        val pc = factory.createPeerConnection(
            config,
            PeerConnectionObserver(PeerConnectionType.PUBLISH)
        ) ?: throw BandwidthRTCError.ConnectionFailed("Failed to create publishing peer connection")

        this.publishingPC = pc
        log.debug("Publishing peer connection created")
        pc
    } ?: throw BandwidthRTCError.ConnectionFailed("Peer connection manager has been cleaned up")

    override fun setupSubscribingPeerConnection(): PeerConnection = useNative {
        val config = createRtcConfiguration()

        val pc = factory.createPeerConnection(
            config,
            PeerConnectionObserver(PeerConnectionType.SUBSCRIBE)
        ) ?: throw BandwidthRTCError.ConnectionFailed("Failed to create subscribing peer connection")

        this.subscribingPC = pc
        log.debug("Subscribing peer connection created")
        pc
    } ?: throw BandwidthRTCError.ConnectionFailed("Peer connection manager has been cleaned up")

    override suspend fun waitForPublishIceConnected() {
        if (publishIceConnected) {
            log.debug("Publish ICE already connected, skipping wait")
            return
        }
        val deadline = System.currentTimeMillis() + PUBLISH_ICE_CONNECT_TIMEOUT_MS
        while (!publishIceConnected) {
            if (disposed) {
                throw BandwidthRTCError.PublishFailed("Peer connection manager was cleaned up while waiting for publish ICE")
            }
            if (System.currentTimeMillis() >= deadline) {
                throw BandwidthRTCError.PublishFailed(
                    "Publish peer connection did not reach connected within ${PUBLISH_ICE_CONNECT_TIMEOUT_MS}ms"
                )
            }
            delay(50)
        }
    }

    /**
     * The SDP negotiation methods below suspend on WebRTC's own observer callbacks, so they cannot
     * register with [useNative] for the whole call the way the synchronous entry points do - that
     * would hold disposal off across a suspension of unbounded length. Each individual native call
     * is registered instead, via [withLiveNative]: the calls themselves are synchronous, so a
     * teardown landing during a suspension is caught by the next one rather than crashing in it.
     *
     * The entry checks on [disposed] are only a fast path. They are not the guarantee, because the
     * session coroutine driving a negotiation is not always cancelled by a teardown - the
     * "sdpOffer" signaling event handler launches an untracked coroutine, so a renegotiation
     * arriving as the socket closes can be mid-suspension when cleanup() runs.
     */
    override suspend fun answerInitialOffer(sdpOffer: String, pcType: PeerConnectionType): String {
        if (disposed) throw BandwidthRTCError.SdpNegotiationFailed("Peer connection manager has been cleaned up")
        val pc = when (pcType) {
            PeerConnectionType.PUBLISH -> publishingPC
            PeerConnectionType.SUBSCRIBE -> subscribingPC
        } ?: throw BandwidthRTCError.SdpNegotiationFailed("$pcType peer connection not available")

        val offer = SessionDescription(SessionDescription.Type.OFFER, sdpOffer)

        suspendCoroutine { continuation ->
            continuation.withLiveNative {
                pc.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() = continuation.resume(Unit)
                    override fun onSetFailure(error: String?) =
                        continuation.resumeWithException(BandwidthRTCError.SdpNegotiationFailed(error ?: "setRemoteDescription failed"))
                    override fun onCreateSuccess(sdp: SessionDescription?) {}
                    override fun onCreateFailure(error: String?) {}
                }, offer)
            }
        }

        val answerConstraints = MediaConstraints()
        val answerSdp = suspendCoroutine { continuation ->
            continuation.withLiveNative {
                pc.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription?) {
                        if (sdp == null) {
                            continuation.resumeWithException(
                                BandwidthRTCError.SdpNegotiationFailed("No SDP answer generated")
                            )
                            return
                        }
                        pc.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() = continuation.resume(sdp.description)
                            override fun onSetFailure(error: String?) =
                                continuation.resumeWithException(BandwidthRTCError.SdpNegotiationFailed(error ?: "setLocalDescription failed"))
                            override fun onCreateSuccess(sdp: SessionDescription?) {}
                            override fun onCreateFailure(error: String?) {}
                        }, sdp)
                    }
                    override fun onCreateFailure(error: String?) =
                        continuation.resumeWithException(BandwidthRTCError.SdpNegotiationFailed(error ?: "createAnswer failed"))
                    override fun onSetSuccess() {}
                    override fun onSetFailure(error: String?) {}
                }, answerConstraints)
            }
        }

        return answerSdp
    }

    override fun addLocalTracks(audio: Boolean): MediaStream =
        useNative { createLocalStream(audio = audio, streamId = UUID.randomUUID().toString()) }
            ?: throw BandwidthRTCError.PublishFailed("Peer connection manager has been cleaned up")

    /**
     * Build a local stream under a caller-chosen id.
     *
     * Re-acquiring after a reconnect reuses the previous stream id so the RtcStream handle the
     * application is already holding keeps working: unpublish() matches on stream id, as does any
     * application state keyed by it. The JavaScript and Swift SDKs keep the same stream object
     * across a reconnect, so preserving the id here keeps all three consistent.
     */
    private fun createLocalStream(audio: Boolean, streamId: String): MediaStream {
        val pc = publishingPC ?: throw BandwidthRTCError.PublishFailed("Publishing peer connection not set up")

        val stream = factory.createLocalMediaStream(streamId)

        if (audio) {
            val ap = options?.audioProcessing ?: AudioProcessingOptions()
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", ap.enableSoftwareEchoCancellation.toString()))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", ap.enableSoftwareNoiseSuppression.toString()))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", ap.enableAutoGainControl.toString()))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", ap.enableHighpassFilter.toString()))
            }
            val audioSource = factory.createAudioSource(audioConstraints)
            publishedAudioSources[streamId] = audioSource
            val audioTrack = factory.createAudioTrack("audio-$streamId", audioSource)
            publishedAudioTracks[streamId] = audioTrack
            stream.addTrack(audioTrack)
            pc.addTrack(audioTrack, listOf(streamId))
            log.debug("Added audio track to publishing PC")
        } else {
            log.debug("addLocalTracks called with audio=false")
        }

        publishedStreams[streamId] = stream
        return stream
    }

    /**
     * Re-attach a previously published stream to the current publishing peer connection.
     *
     * A track that ended while the session was down produces a sender that never sends RTP, which
     * leaves the endpoint ineligible on the platform even though the SDP looks correct, so anything
     * that is not still live is re-acquired from scratch instead of being re-attached dead.
     *
     * The caller is responsible for renegotiating once all streams have been re-attached.
     */
    override fun republishLocalStream(streamId: String, audio: Boolean): MediaStream = useNative {
        val pc = publishingPC ?: throw BandwidthRTCError.PublishFailed("Publishing peer connection not set up")

        val stream = publishedStreams[streamId]
        val track = publishedAudioTracks[streamId]
        val live = stream != null && (!audio || track?.state() == MediaStreamTrack.State.LIVE)

        if (!live) {
            log.info("Re-acquiring local tracks for stream $streamId (previous tracks are gone)")
            removeLocalTracks(streamId)
            return@useNative createLocalStream(audio = audio, streamId = streamId)
        }

        track?.let { pc.addTrack(it, listOf(streamId)) }
        log.debug("Re-attached live local tracks for stream $streamId")
        stream!!
    } ?: throw BandwidthRTCError.PublishFailed("Peer connection manager has been cleaned up")

    override suspend fun createPublishOffer(): String {
        if (disposed) throw BandwidthRTCError.PublishFailed("Peer connection manager has been cleaned up")
        val pc = publishingPC
            ?: throw BandwidthRTCError.PublishFailed("Publishing peer connection not available")

        val offerConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        val offerSdp = suspendCoroutine { continuation ->
            continuation.withLiveNative {
                pc.createOffer(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription?) {
                        if (sdp == null) {
                            continuation.resumeWithException(
                                BandwidthRTCError.SdpNegotiationFailed("No SDP offer generated")
                            )
                            return
                        }
                        pc.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() = continuation.resume(sdp.description)
                            override fun onSetFailure(error: String?) =
                                continuation.resumeWithException(BandwidthRTCError.SdpNegotiationFailed(error ?: "setLocalDescription failed"))
                            override fun onCreateSuccess(sdp: SessionDescription?) {}
                            override fun onCreateFailure(error: String?) {}
                        }, sdp)
                    }
                    override fun onCreateFailure(error: String?) =
                        continuation.resumeWithException(BandwidthRTCError.SdpNegotiationFailed(error ?: "createOffer failed"))
                    override fun onSetSuccess() {}
                    override fun onSetFailure(error: String?) {}
                }, offerConstraints)
            }
        }

        log.debug("Publish SDP offer created")
        return offerSdp
    }

    override suspend fun applyPublishAnswer(remoteAnswer: String) {
        if (disposed) throw BandwidthRTCError.PublishFailed("Peer connection manager has been cleaned up")
        val pc = publishingPC
            ?: throw BandwidthRTCError.PublishFailed("Publishing peer connection not available")

        val answer = SessionDescription(SessionDescription.Type.ANSWER, remoteAnswer)

        suspendCoroutine { continuation ->
            continuation.withLiveNative {
                pc.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() = continuation.resume(Unit)
                    override fun onSetFailure(error: String?) =
                        continuation.resumeWithException(BandwidthRTCError.SdpNegotiationFailed(error ?: "setRemoteDescription failed"))
                    override fun onCreateSuccess(sdp: SessionDescription?) {}
                    override fun onCreateFailure(error: String?) {}
                }, answer)
            }
        }

        log.debug("Publish SDP answer applied")
    }

    override suspend fun handleSubscribeSdpOffer(
        sdpOffer: String,
        sdpRevision: Long?,
        metadata: Map<String, TrackMetadata>?
    ): String {
        if (disposed) throw BandwidthRTCError.SdpNegotiationFailed("Peer connection manager has been cleaned up")
        val effectiveRevision = sdpRevision ?: (subscribeSdpRevision + 1)

        if (effectiveRevision <= subscribeSdpRevision && subscribeSdpRevision != 0L) {
            log.warn("Rejecting stale SDP offer (revision $effectiveRevision <= $subscribeSdpRevision)")
            throw BandwidthRTCError.SdpNegotiationFailed("Stale SDP offer")
        }

        if (subscribeSdpRevision == 0L) {
            log.debug("Accepting first subscribe SDP offer (revision 0→$effectiveRevision)")
        }

        val pc = subscribingPC
            ?: throw BandwidthRTCError.SdpNegotiationFailed("Subscribing peer connection not available")

        log.debug("[subscribe] Handling offer (revision=$effectiveRevision)")

        metadata?.let { subscribedTrackMetadata.putAll(it) }

        val offer = SessionDescription(SessionDescription.Type.OFFER, sdpOffer)

        log.debug("[subscribe] setRemoteDescription...")
        suspendCoroutine { continuation ->
            continuation.withLiveNative {
                pc.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        log.debug("[subscribe] setRemoteDescription SUCCESS")
                        continuation.resume(Unit)
                    }
                    override fun onSetFailure(error: String?) {
                        log.error("[subscribe] setRemoteDescription FAILED: $error")
                        continuation.resumeWithException(BandwidthRTCError.SdpNegotiationFailed(error ?: "setRemoteDescription failed"))
                    }
                    override fun onCreateSuccess(sdp: SessionDescription?) {}
                    override fun onCreateFailure(error: String?) {}
                }, offer)
            }
        }

        log.debug("[subscribe] createAnswer...")
        val answerConstraints = MediaConstraints()

        val answerSdp = suspendCoroutine { continuation ->
            continuation.withLiveNative {
                pc.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription?) {
                        if (sdp == null) {
                            log.error("[subscribe] createAnswer returned null")
                            continuation.resumeWithException(
                                BandwidthRTCError.SdpNegotiationFailed("No SDP answer generated")
                            )
                            return
                        }

                        log.debug("[subscribe] setLocalDescription...")
                        pc.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                log.debug("[subscribe] setLocalDescription SUCCESS")
                                continuation.resume(sdp.description)
                            }
                            override fun onSetFailure(error: String?) {
                                log.error("[subscribe] setLocalDescription FAILED: $error")
                                continuation.resumeWithException(BandwidthRTCError.SdpNegotiationFailed(error ?: "setLocalDescription failed"))
                            }
                            override fun onCreateSuccess(sdp: SessionDescription?) {}
                            override fun onCreateFailure(error: String?) {}
                        }, sdp)
                    }
                    override fun onCreateFailure(error: String?) {
                        log.error("[subscribe] createAnswer FAILED: $error")
                        continuation.resumeWithException(BandwidthRTCError.SdpNegotiationFailed(error ?: "createAnswer failed"))
                    }
                    override fun onSetSuccess() {}
                    override fun onSetFailure(error: String?) {}
                }, answerConstraints)
            }
        }

        subscribeSdpRevision = effectiveRevision
        log.debug("[subscribe] Complete (revision=$effectiveRevision)")
        return answerSdp
    }

    override fun removeLocalTracks(streamId: String) {
        useNative {
            val pc = publishingPC ?: return@useNative
            val stream = publishedStreams[streamId]
            if (stream == null) {
                log.warn("removeLocalTracks: stream $streamId not found")
                return@useNative
            }

            for (track in stream.audioTracks) {
                val matchingSenders = pc.senders.filter { it.track()?.id() == track.id() }
                for (sender in matchingSenders) {
                    pc.removeTrack(sender)
                    log.debug("Removed sender for track ${track.id()}")
                }
                track.setEnabled(false)
                track.dispose()
            }

            publishedAudioSources.remove(streamId)?.dispose()
            publishedAudioTracks.remove(streamId)
            publishedStreams.remove(streamId)
            log.debug("Removed local tracks for stream $streamId")
        }
    }

    override fun setAudioEnabled(enabled: Boolean) {
        useNative {
            for ((_, stream) in publishedStreams) {
                for (track in stream.audioTracks) {
                    track.setEnabled(enabled)
                }
            }
        }
    }

    override fun sendDtmf(tone: String, duration: Int, interToneGap: Int) {
        useNative {
            val pc = publishingPC ?: return@useNative

            for (sender in pc.senders) {
                val track = sender.track()
                if (track?.kind() == "audio") {
                    val dtmfSender = sender.dtmf() ?: continue
                    if (!dtmfSender.canInsertDtmf()) {
                        log.warn("DTMF sender not ready — tone dropped: $tone")
                        continue
                    }
                    if (dtmfSender.insertDtmf(tone, duration, interToneGap)) {
                        log.debug("Sent DTMF: $tone")
                        val streamId = publishedStreams.entries.find { (_, stream) ->
                            stream.audioTracks.any { it.id() == track.id() }
                        }?.key
                        // insertDtmf is fire-and-forget with no completion signal from WebRTC, so this
                        // reports tones as queued rather than as actually played.
                        if (streamId != null) {
                            for (character in tone) {
                                if (VALID_DTMF_TONES.contains(character)) {
                                    onDtmfSent?.invoke(DtmfSentEvent(tone = character.toString(), streamId = streamId))
                                }
                            }
                        }
                    } else {
                        log.warn("insertDtmf failed for tone: $tone")
                    }
                    return@useNative
                }
            }
            log.warn("No audio sender found for DTMF")
        }
    }

    override fun getCallStats(
        previousInboundBytes: Int,
        previousOutboundBytes: Int,
        previousTimestamp: Double,
        completion: (CallStatsSnapshot) -> Unit
    ) {
        val snapshot = CallStatsSnapshot()
        var pendingCount = 0
        val lock = Object()

        fun checkDone() {
            synchronized(lock) {
                pendingCount--
                if (pendingCount == 0) {
                    snapshot.timestamp = System.currentTimeMillis() / 1000.0
                    val timeDelta = snapshot.timestamp - previousTimestamp
                    if (timeDelta > 0 && previousTimestamp > 0) {
                        val inDelta = maxOf(0, snapshot.bytesReceived - previousInboundBytes)
                        val outDelta = maxOf(0, snapshot.bytesSent - previousOutboundBytes)
                        snapshot.inboundBitrate = (inDelta * 8.0) / timeDelta
                        snapshot.outboundBitrate = (outDelta * 8.0) / timeDelta
                    }
                    completion(snapshot)
                }
            }
        }

        // Only the two getStats() calls dereference a peer connection; the collector callbacks
        // below read the report they are handed, which is plain Java data, so they stay safe even
        // if a teardown lands while they are in flight. Registering the launches is enough.
        useNative {
            val subPC = subscribingPC
            if (subPC != null) {
                synchronized(lock) { pendingCount++ }
                subPC.getStats(RTCStatsCollectorCallback { report ->
                    var codecId: String? = null
                    for ((_, stat) in report.statsMap) {
                        if (stat.type == "inbound-rtp") {
                            val kind = stat.members["kind"] as? String
                            if (kind == "audio") {
                                snapshot.packetsReceived = (stat.members["packetsReceived"] as? Number)?.toInt() ?: 0
                                snapshot.packetsLost = (stat.members["packetsLost"] as? Number)?.toInt() ?: 0
                                snapshot.bytesReceived = (stat.members["bytesReceived"] as? Number)?.toInt() ?: 0
                                snapshot.jitter = (stat.members["jitter"] as? Number)?.toDouble() ?: 0.0
                                snapshot.audioLevel = (stat.members["audioLevel"] as? Number)?.toDouble() ?: 0.0
                                codecId = stat.members["codecId"] as? String
                            }
                        }
                        if (stat.type == "candidate-pair") {
                            val state = stat.members["state"] as? String
                            if (state == "succeeded") {
                                snapshot.roundTripTime = (stat.members["currentRoundTripTime"] as? Number)?.toDouble() ?: 0.0
                            }
                        }
                    }
                    if (codecId != null) {
                        val codecStat = report.statsMap[codecId]
                        if (codecStat != null) {
                            val mimeType = codecStat.members["mimeType"] as? String
                            if (mimeType != null) {
                                snapshot.codec = mimeType.removePrefix("audio/")
                            }
                        }
                    }
                    checkDone()
                })
            }

            val pubPC = publishingPC
            if (pubPC != null) {
                synchronized(lock) { pendingCount++ }
                pubPC.getStats(RTCStatsCollectorCallback { report ->
                    for ((_, stat) in report.statsMap) {
                        if (stat.type == "outbound-rtp") {
                            val kind = stat.members["kind"] as? String
                            if (kind == "audio") {
                                snapshot.packetsSent = (stat.members["packetsSent"] as? Number)?.toInt() ?: 0
                                snapshot.bytesSent = (stat.members["bytesSent"] as? Number)?.toInt() ?: 0
                            }
                        }
                    }
                    checkDone()
                })
            }
        }

        // Outside useNative: nothing left to dereference, and completion() is application code -
        // running it while registered would hold disposal off for as long as the application takes.
        synchronized(lock) {
            if (pendingCount == 0) {
                completion(snapshot)
            }
        }
    }

    override fun cleanup() {
        synchronized(nativeLock) {
            if (disposed) {
                log.debug("cleanup() ignored - peer connection manager is already cleaned up")
                return
            }
            // Flipping this first is what makes the rest safe: every later caller bails out of
            // useNative() instead of reaching for a handle this call is about to free.
            disposed = true

            // Wait for calls that registered before the flag flipped. wait() releases the lock,
            // so they can deregister. Disposing anyway after the timeout is the deliberate
            // ceiling - a native call wedged for two seconds is already broken, and blocking
            // teardown on it forever is worse than the crash risk of proceeding.
            val deadline = System.currentTimeMillis() + NATIVE_DRAIN_TIMEOUT_MS
            while (activeNativeCalls > 0) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    log.warn("Disposing with $activeNativeCalls native call(s) still in flight")
                    break
                }
                nativeLock.wait(remaining)
            }
        }

        // Everything below runs with no lock held - see [useNative].

        // 1. Remove senders from PCs before disposing tracks
        publishingPC?.let { pc ->
            for (sender in pc.senders) {
                try { pc.removeTrack(sender) } catch (_: Exception) {}
            }
        }

        // 2. Dispose audio tracks and sources
        for ((streamId, stream) in publishedStreams) {
            for (track in stream.audioTracks) {
                track.setEnabled(false)
                track.dispose()
            }
            publishedAudioSources[streamId]?.dispose()
        }
        publishedStreams.clear()
        publishedAudioSources.clear()
        publishedAudioTracks.clear()

        // 3. Close data channels
        listOfNotNull(publishHeartbeatDC, publishDiagnosticsDC, subscribeHeartbeatDC, subscribeDiagnosticsDC)
            .forEach { dc ->
                log.debug("Closing data channel: ${dc.label()}")
                dc.close()
                dc.dispose()
            }
        publishHeartbeatDC = null
        publishDiagnosticsDC = null
        subscribeHeartbeatDC = null
        subscribeDiagnosticsDC = null

        // 4. Close and dispose peer connections
        publishingPC?.close()
        publishingPC?.dispose()
        subscribingPC?.close()
        subscribingPC?.dispose()
        publishingPC = null
        subscribingPC = null

        // 5. Dispose factory and reset initialization flag so the next
        //    PeerConnectionManager can re-initialize the native WebRTC library
        factory.dispose()
        factoryInitialized = false

        subscribeSdpRevision = 0
        publishIceConnected = false
        log.info("Peer connections cleaned up")
    }

    private inner class PeerConnectionObserver(
        private val pcType: PeerConnectionType
    ) : PeerConnection.Observer {

        override fun onSignalingChange(state: PeerConnection.SignalingState?) {
            log.debug("Signaling state [$pcType]: $state")
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            log.debug("ICE connection state [$pcType]: $state")

            if (pcType == PeerConnectionType.PUBLISH) {
                if (state == PeerConnection.IceConnectionState.CONNECTED ||
                    state == PeerConnection.IceConnectionState.COMPLETED
                ) {
                    publishIceConnected = true
                }
            } else if (pcType == PeerConnectionType.SUBSCRIBE) {
                state?.let { onSubscribingIceConnectionStateChange?.invoke(it) }
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {
            log.debug("ICE receiving change [$pcType]: $receiving")
        }

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            log.debug("ICE gathering state [$pcType]: $state")
        }

        override fun onIceCandidate(candidate: IceCandidate?) {
            log.debug("ICE candidate generated (bundled in SDP)")
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
            log.debug("ICE candidates removed")
        }

        override fun onAddStream(stream: MediaStream?) {
            if (stream == null) return
            log.info("Stream added on $pcType PC: ${stream.id} (audio=${stream.audioTracks.size}, video=${stream.videoTracks.size})")

            for (track in stream.audioTracks) {
                log.debug("  Audio track: ${track.id()}, enabled=${track.enabled()}, state=${track.state()}")
            }

            // onAddTrack handles subscribe stream notifications — skip here to avoid
            // firing onStreamAvailable twice for the same stream.
            if (pcType == PeerConnectionType.SUBSCRIBE) return

            val mediaTypes = mutableListOf<MediaType>()
            if (stream.audioTracks.isNotEmpty()) mediaTypes.add(MediaType.AUDIO)

            onStreamAvailable?.invoke(stream, mediaTypes, null)
        }

        override fun onRemoveStream(stream: MediaStream?) {
            if (stream == null) return
            log.info("Stream removed: ${stream.id}")
            onStreamUnavailable?.invoke(stream.id)
        }

        override fun onDataChannel(dataChannel: DataChannel?) {
            if (dataChannel == null) return
            log.debug("Data channel opened on $pcType PC: ${dataChannel.label()} (id=${dataChannel.id()})")

            dataChannel.registerObserver(DataChannelObserver(dataChannel))

            when (dataChannel.label()) {
                "__heartbeat__" -> {
                    if (pcType == PeerConnectionType.PUBLISH) publishHeartbeatDC = dataChannel
                    else subscribeHeartbeatDC = dataChannel
                }
                "__diagnostics__" -> {
                    if (pcType == PeerConnectionType.PUBLISH) publishDiagnosticsDC = dataChannel
                    else subscribeDiagnosticsDC = dataChannel
                }
            }
        }

        override fun onRenegotiationNeeded() {
            log.debug("Negotiation needed [$pcType]")
        }

        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            if (pcType != PeerConnectionType.SUBSCRIBE) return
            val track = receiver?.track() ?: return
            if (track.kind() != MediaStreamTrack.AUDIO_TRACK_KIND) return

            val stream = streams?.firstOrNull() ?: return
            log.info("Track added on SUBSCRIBE PC: trackId=${track.id()}, streamId=${stream.id}, enabled=${track.enabled()}")

            val trackId = track.id()
            val metadata = subscribedTrackMetadata.remove(trackId)
            if (metadata != null) {
                log.debug("Passing metadata for stream ${stream.id} from track $trackId")
            }

            onStreamAvailable?.invoke(stream, listOf(MediaType.AUDIO), metadata)
        }
    }

    private inner class DataChannelObserver(
        private val dataChannel: DataChannel
    ) : DataChannel.Observer {

        override fun onBufferedAmountChange(previousAmount: Long) {}

        override fun onStateChange() {
            log.debug("Data channel '${dataChannel.label()}' state: ${dataChannel.state()}")
        }

        override fun onMessage(buffer: DataChannel.Buffer?) {
            if (buffer == null) return
            val data = ByteArray(buffer.data.remaining())
            buffer.data.get(data)
            val message = String(data, Charsets.UTF_8)

            if (dataChannel.label() == "__heartbeat__" && message == "PING") {
                val pong = DataChannel.Buffer(ByteBuffer.wrap("PONG".toByteArray(Charsets.UTF_8)), false)
                dataChannel.send(pong)
                log.debug("Heartbeat PONG sent")
            }
        }
    }
}
