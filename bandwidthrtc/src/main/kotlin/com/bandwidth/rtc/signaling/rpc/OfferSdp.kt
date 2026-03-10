package com.bandwidth.rtc.signaling.rpc

import kotlinx.serialization.Serializable

@Serializable
internal data class OfferSdpParams(
    val sdpOffer: String,
    val peerType: String
)

@Serializable
internal data class OfferSdpResult(
    val sdpAnswer: String
)
