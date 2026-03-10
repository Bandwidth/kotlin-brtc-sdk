package com.bandwidth.rtc.signaling.rpc

import kotlinx.serialization.Serializable

@Serializable
internal data class AnswerSdpParams(
    val peerType: String,
    val sdpAnswer: String
)
