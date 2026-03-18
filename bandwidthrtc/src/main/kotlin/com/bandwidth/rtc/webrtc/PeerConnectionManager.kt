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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class PeerConnectionManager(
    private val context: Context,
    private val options: RtcOptions?,
    private val audioDeviceModule: AudioDeviceModule? = null
) : PeerConnectionManagerInterface {

    private val log = Logger

    companion object {
        private var factoryInitialized = false
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
    private val subscribedStreamMetadata = ConcurrentHashMap<String, StreamMetadata>()
    var subscribeSdpRevision: Int = 0
        private set

    override var onStreamAvailable: ((MediaStream, List<MediaType>) -> Unit)? = null
    override var onStreamUnavailable: ((String) -> Unit)? = null
    override var onSubscribingIceConnectionStateChange: ((PeerConnection.IceConnectionState) -> Unit)? = null

    @Volatile
    private var publishIceConnected = false

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

    override fun setupPublishingPeerConnection(): PeerConnection {
        val config = createRtcConfiguration()

        val pc = factory.createPeerConnection(
            config,
            PeerConnectionObserver(PeerConnectionType.PUBLISH)
        ) ?: throw BandwidthRTCError.ConnectionFailed("Failed to create publishing peer connection")

        this.publishingPC = pc
        log.debug("Publishing peer connection created")
        return pc
    }

    override fun setupSubscribingPeerConnection(): PeerConnection {
        val config = createRtcConfiguration()

        val pc = factory.createPeerConnection(
            config,
            PeerConnectionObserver(PeerConnectionType.SUBSCRIBE)
        ) ?: throw BandwidthRTCError.ConnectionFailed("Failed to create subscribing peer connection")

        this.subscribingPC = pc
        log.debug("Subscribing peer connection created")
        return pc
    }

    override suspend fun waitForPublishIceConnected() {
        if (publishIceConnected) {
            log.debug("Publish ICE already connected, skipping wait")
            return
        }
        while (!publishIceConnected) {
            delay(50)
        }
    }

    override suspend fun answerInitialOffer(sdpOffer: String, pcType: PeerConnectionType): String {
        val pc = when (pcType) {
            PeerConnectionType.PUBLISH -> publishingPC
            PeerConnectionType.SUBSCRIBE -> subscribingPC
        } ?: throw BandwidthRTCError.SdpNegotiationFailed("$pcType peer connection not available")

        val offer = SessionDescription(SessionDescription.Type.OFFER, sdpOffer)

        suspendCoroutine { continuation ->
            pc.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() = continuation.resume(Unit)
                override fun onSetFailure(error: String?) =
                    continuation.resumeWithException(BandwidthRTCError.SdpNegotiationFailed(error ?: "setRemoteDescription failed"))
                override fun onCreateSuccess(sdp: SessionDescription?) {}
                override fun onCreateFailure(error: String?) {}
            }, offer)
        }

        val answerConstraints = MediaConstraints()
        val answerSdp = suspendCoroutine { continuation ->
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

        return answerSdp
    }

    override fun addLocalTracks(audio: Boolean): MediaStream {
        val pc = publishingPC ?: throw BandwidthRTCError.PublishFailed("Publishing peer connection not set up")

        val streamId = UUID.randomUUID().toString()
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
            stream.addTrack(audioTrack)
            pc.addTrack(audioTrack, listOf(streamId))
            log.debug("Added audio track to publishing PC")
        } else {
            log.debug("addLocalTracks called with audio=false")
        }

        publishedStreams[streamId] = stream
        return stream
    }

    override suspend fun createPublishOffer(): String {
        val pc = publishingPC
            ?: throw BandwidthRTCError.PublishFailed("Publishing peer connection not available")

        val offerConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        val offerSdp = suspendCoroutine { continuation ->
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

        log.debug("Publish SDP offer created")
        return offerSdp
    }

    override suspend fun applyPublishAnswer(remoteAnswer: String) {
        val pc = publishingPC
            ?: throw BandwidthRTCError.PublishFailed("Publishing peer connection not available")

        val answer = SessionDescription(SessionDescription.Type.ANSWER, remoteAnswer)

        suspendCoroutine { continuation ->
            pc.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() = continuation.resume(Unit)
                override fun onSetFailure(error: String?) =
                    continuation.resumeWithException(BandwidthRTCError.SdpNegotiationFailed(error ?: "setRemoteDescription failed"))
                override fun onCreateSuccess(sdp: SessionDescription?) {}
                override fun onCreateFailure(error: String?) {}
            }, answer)
        }

        log.debug("Publish SDP answer applied")
    }

    override suspend fun handleSubscribeSdpOffer(
        sdpOffer: String,
        sdpRevision: Int?,
        metadata: Map<String, StreamMetadata>?
    ): String {
        val effectiveRevision = sdpRevision ?: (subscribeSdpRevision + 1)

        if (effectiveRevision <= subscribeSdpRevision && subscribeSdpRevision != 0) {
            log.warn("Rejecting stale SDP offer (revision $effectiveRevision <= $subscribeSdpRevision)")
            throw BandwidthRTCError.SdpNegotiationFailed("Stale SDP offer")
        }

        if (subscribeSdpRevision == 0) {
            log.debug("Accepting first subscribe SDP offer (revision 0→$effectiveRevision)")
        }

        val pc = subscribingPC
            ?: throw BandwidthRTCError.SdpNegotiationFailed("Subscribing peer connection not available")

        log.debug("[subscribe] Handling offer (revision=$effectiveRevision)")

        metadata?.let { subscribedStreamMetadata.putAll(it) }

        val offer = SessionDescription(SessionDescription.Type.OFFER, sdpOffer)

        log.debug("[subscribe] setRemoteDescription...")
        suspendCoroutine { continuation ->
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

        log.debug("[subscribe] createAnswer...")
        val answerConstraints = MediaConstraints()

        val answerSdp = suspendCoroutine { continuation ->
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

        subscribeSdpRevision = effectiveRevision
        log.debug("[subscribe] Complete (revision=$effectiveRevision)")
        return answerSdp
    }

    override fun removeLocalTracks(streamId: String) {
        val pc = publishingPC ?: return
        val stream = publishedStreams[streamId]
        if (stream == null) {
            log.warn("removeLocalTracks: stream $streamId not found")
            return
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
        publishedStreams.remove(streamId)
        log.debug("Removed local tracks for stream $streamId")
    }

    override fun setAudioEnabled(enabled: Boolean) {
        for ((_, stream) in publishedStreams) {
            for (track in stream.audioTracks) {
                track.setEnabled(enabled)
            }
        }
    }

    override fun sendDtmf(tone: String) {
        val pc = publishingPC ?: return

        for (sender in pc.senders) {
            if (sender.track()?.kind() == "audio") {
                val dtmfSender = sender.dtmf()
                if (dtmfSender != null) {
                    dtmfSender.insertDtmf(tone, 100, 50)
                    log.debug("Sent DTMF: $tone")
                    return
                }
            }
        }
        log.warn("No audio sender found for DTMF")
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

        synchronized(lock) {
            if (pendingCount == 0) {
                completion(snapshot)
            }
        }
    }

    override fun cleanup() {
        for ((streamId, stream) in publishedStreams) {
            for (track in stream.audioTracks) {
                track.setEnabled(false)
                track.dispose()
            }
            publishedAudioSources[streamId]?.dispose()
        }
        publishedStreams.clear()
        publishedAudioSources.clear()
        subscribedStreamMetadata.clear()

        listOfNotNull(publishHeartbeatDC, publishDiagnosticsDC, subscribeHeartbeatDC, subscribeDiagnosticsDC)
            .forEach { dc ->
                log.debug("Closing data channel: ${dc.label()}")
                dc.close()
            }
        publishHeartbeatDC = null
        publishDiagnosticsDC = null
        subscribeHeartbeatDC = null
        subscribeDiagnosticsDC = null

        publishingPC?.close()
        subscribingPC?.close()
        publishingPC = null
        subscribingPC = null

        factory.dispose()

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

            val mediaTypes = mutableListOf<MediaType>()
            if (stream.audioTracks.isNotEmpty()) mediaTypes.add(MediaType.AUDIO)

            onStreamAvailable?.invoke(stream, mediaTypes)
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

            onStreamAvailable?.invoke(stream, listOf(MediaType.AUDIO))
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
