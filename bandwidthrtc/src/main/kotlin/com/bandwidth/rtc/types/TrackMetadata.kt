package com.bandwidth.rtc.types

import kotlinx.serialization.Serializable

@Serializable
data class TrackMetadata(
    val from: String? = null,
    val fromType: String? = null,
    val autoAccepted: Boolean? = null,
    val tags: String? = null
)
