package com.bandwidth.rtc.signaling.rpc

import kotlinx.serialization.Serializable

@Serializable
data class OfferSdpParams(
    val sdpOffer: String,
    val peerType: String
)

@Serializable
data class OfferSdpResult(
    val sdpAnswer: String
)
