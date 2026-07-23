package com.bandwidth.rtc.signaling.rpc

import com.bandwidth.rtc.types.TrackMetadata
import kotlinx.serialization.Serializable

@Serializable
data class SDPOfferNotification(
    val endpointId: String? = null,
    val peerType: String? = null,
    val sdpOffer: String,
    val sdpRevision: Int? = null,
    val trackMetadata: Map<String, TrackMetadata>? = null
)
