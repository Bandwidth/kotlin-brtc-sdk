package com.bandwidth.rtc.types

import kotlinx.serialization.Serializable

@Serializable
data class ReadyMetadata(
    val endpointId: String? = null,
    val deviceId: String? = null,
    val territory: String? = null,
    val region: String? = null,
    val connectStatus: ConnectStatus? = null,
    val accountId: String? = null,
    val sessionId: String? = null,
    val from: String? = null,
    val fromType: String? = null,
    val fromTags: String? = null,
    val to: String? = null,
    val toType: String? = null,
    val toTags: String? = null
)
