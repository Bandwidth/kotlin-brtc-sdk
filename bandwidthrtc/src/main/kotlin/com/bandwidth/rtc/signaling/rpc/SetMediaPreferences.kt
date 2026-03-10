package com.bandwidth.rtc.signaling.rpc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class SetMediaPreferencesParams(
    @SerialName("protocol") val protocol_: String = "WEBRTC"
)

@Serializable
internal data class SdpOffer(
    val peerType: String? = null,
    val sdpOffer: String
)

@Serializable
internal data class SetMediaPreferencesResult(
    val endpointId: String? = null,
    val deviceId: String? = null,
    val publishSdpOffer: SdpOffer? = null,
    val subscribeSdpOffer: SdpOffer? = null
)
