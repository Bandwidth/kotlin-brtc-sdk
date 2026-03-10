package com.bandwidth.rtc.webrtc

import com.bandwidth.rtc.types.CallStatsSnapshot
import com.bandwidth.rtc.types.MediaType
import com.bandwidth.rtc.types.PeerConnectionType
import com.bandwidth.rtc.types.StreamMetadata
import org.webrtc.MediaStream
import org.webrtc.PeerConnection

interface PeerConnectionManagerInterface {
    var onStreamAvailable: ((MediaStream, List<MediaType>) -> Unit)?
    var onStreamUnavailable: ((String) -> Unit)?
    var onSubscribingIceConnectionStateChange: ((PeerConnection.IceConnectionState) -> Unit)?

    fun setupPublishingPeerConnection(): PeerConnection
    fun setupSubscribingPeerConnection(): PeerConnection

    suspend fun waitForPublishIceConnected()
    suspend fun answerInitialOffer(sdpOffer: String, pcType: PeerConnectionType): String
    fun addLocalTracks(audio: Boolean): MediaStream
    fun removeLocalTracks(streamId: String)
    suspend fun createPublishOffer(): String
    suspend fun applyPublishAnswer(localOffer: String, remoteAnswer: String)
    suspend fun handleSubscribeSdpOffer(
        sdpOffer: String,
        sdpRevision: Int?,
        metadata: Map<String, StreamMetadata>?
    ): String

    fun setAudioEnabled(enabled: Boolean)
    fun sendDtmf(tone: String)
    fun cleanup()
    fun getCallStats(
        previousInboundBytes: Int,
        previousOutboundBytes: Int,
        previousTimestamp: Double,
        completion: (CallStatsSnapshot) -> Unit
    )
}
