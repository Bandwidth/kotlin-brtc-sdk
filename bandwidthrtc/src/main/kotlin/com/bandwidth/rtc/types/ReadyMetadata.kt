package com.bandwidth.rtc.types

import kotlinx.serialization.Serializable

@Serializable
data class ReadyMetadata(
    val endpointId: String? = null,
    val deviceId: String? = null,
    val territory: String? = null,
    val region: String? = null
)
