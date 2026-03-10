package com.bandwidth.rtc.types

import kotlinx.serialization.Serializable

@Serializable
data class OutboundConnectionResult(
    val accepted: Boolean = false
)

@Serializable
data class HangupResult(
    val result: String? = null
)
