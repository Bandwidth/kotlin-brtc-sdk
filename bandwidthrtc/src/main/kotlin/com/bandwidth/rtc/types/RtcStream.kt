package com.bandwidth.rtc.types

import org.webrtc.MediaStream

data class RtcStream(
    val mediaStream: MediaStream,
    val mediaTypes: List<MediaType>,
    val alias: String? = null
) {
    val streamId: String
        get() = mediaStream.id
}
