package com.bandwidth.rtc.types

import kotlinx.serialization.Serializable

/**
 * Fired once per DTMF tone queued for local playback on a published stream.
 * Reflects local injection into the outbound stream only — no delivery confirmation
 * from the remote party (RFC 4733 has no ack).
 */
@Serializable
data class DtmfSentEvent(
    val tone: String,
    val streamId: String
)
