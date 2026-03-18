package com.bandwidth.rtc.types

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class EndpointType {
    @SerialName("ENDPOINT") ENDPOINT,
    @SerialName("CALL_ID") CALL_ID,
    @SerialName("PHONE_NUMBER") PHONE_NUMBER;
}
