package com.bandwidth.rtc.types

import org.webrtc.PeerConnection

data class RtcOptions(
    val websocketUrl: String? = null,
    val iceServers: List<PeerConnection.IceServer>? = null,
    val iceTransportPolicy: PeerConnection.IceTransportsType? = null
)
