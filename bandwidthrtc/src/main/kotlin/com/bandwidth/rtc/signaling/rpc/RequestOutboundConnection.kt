package com.bandwidth.rtc.signaling.rpc

import com.bandwidth.rtc.types.EndpointType
import kotlinx.serialization.Serializable

@Serializable
data class RequestOutboundConnectionParams(
    val id: String,
    val type: EndpointType
)
