package com.bandwidth.rtc.signaling.rpc

import com.bandwidth.rtc.types.StreamMetadata
import kotlinx.serialization.Serializable

@Serializable
internal data class SDPOfferNotification(
    val endpointId: String? = null,
    val peerType: String? = null,
    val sdpOffer: String,
    val sdpRevision: Int? = null,
    val streamSourceMetadata: Map<String, StreamMetadata>? = null
)
