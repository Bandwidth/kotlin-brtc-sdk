package com.bandwidth.rtc.types

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ConnectStatus {
    @SerialName("INITIATED") INITIATED,
    @SerialName("COMPLETED") COMPLETED,
    @SerialName("TIMED_OUT") TIMED_OUT,
    @SerialName("DENIED") DENIED,
    @SerialName("CANCELED") CANCELED,
    @SerialName("FAILED") FAILED;
}
