package com.bandwidth.rtc.types

import org.webrtc.MediaStream

data class RtcStream(
    val mediaStream: MediaStream,
    val mediaTypes: List<MediaType>,
    val alias: String? = null,
    val from: String? = null,
    val fromType: String? = null,
    val autoAccepted: Boolean? = null,
    val tags: String? = null
) {
    // Captured at construction rather than read through to the native object on every access.
    // A reconnect disposes the PeerConnectionFactory that owns mediaStream, so an RtcStream handle
    // the application is still holding from before the reconnect wraps a freed native object -
    // reading mediaStream.id off it would be a use-after-free. The id survives republish anyway
    // (republished streams keep the previous id), so the captured value stays correct.
    val streamId: String = mediaStream.id
}
