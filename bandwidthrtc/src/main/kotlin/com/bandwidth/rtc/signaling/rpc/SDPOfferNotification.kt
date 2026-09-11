package com.bandwidth.rtc.signaling.rpc

import com.bandwidth.rtc.types.TrackMetadata
import kotlinx.serialization.Serializable

@Serializable
data class SDPOfferNotification(
    val endpointId: String? = null,
    val peerType: String? = null,
    val sdpOffer: String,
    // A signaling-side revision id, not a small sequence counter - the gateway sends values
    // large enough to overflow Int (observed: millisecond timestamps), so this must stay Long.
    val sdpRevision: Long? = null,
    val trackMetadata: Map<String, TrackMetadata>? = null
)
