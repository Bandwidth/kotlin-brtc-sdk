package com.bandwidth.rtc.signaling.rpc

import com.bandwidth.rtc.types.EndpointType
import kotlinx.serialization.Serializable

@Serializable
data class HangupConnectionParams(
    val endpoint: String,
    val type: EndpointType
)
