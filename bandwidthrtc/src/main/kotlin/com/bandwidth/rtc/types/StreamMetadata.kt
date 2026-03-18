package com.bandwidth.rtc.types

import kotlinx.serialization.Serializable

@Serializable
data class StreamMetadata(
    val endpointId: String? = null,
    val alias: String? = null,
    val mediaTypes: List<MediaType>? = null
)
