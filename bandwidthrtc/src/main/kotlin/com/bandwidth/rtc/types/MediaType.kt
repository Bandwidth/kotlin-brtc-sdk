package com.bandwidth.rtc.types

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MediaType {
    @SerialName("AUDIO") AUDIO,
    @SerialName("VIDEO") VIDEO;
}
