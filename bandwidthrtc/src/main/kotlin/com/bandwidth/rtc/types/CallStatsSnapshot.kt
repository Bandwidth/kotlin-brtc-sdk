package com.bandwidth.rtc.types

/**
 * Point-in-time call statistics. A field that is not reported yet is 0, so 0 does not always
 * mean a perfect value. The RTCP fields (`remote*`, `rtcpRoundTripTime`) stay 0 until the first
 * receiver report arrives.
 */
data class CallStatsSnapshot(
    // Inbound (subscribe PC)
    var packetsReceived: Int = 0,
    var packetsLost: Int = 0,
    var bytesReceived: Int = 0,
    var jitter: Double = 0.0,
    var audioLevel: Double = 0.0,

    // Outbound (publish PC)
    var packetsSent: Int = 0,
    var bytesSent: Int = 0,

    // RTCP receiver report (from remote-inbound-rtp on publish PC)
    var remoteFractionLost: Double = 0.0,
    var remoteJitter: Double = 0.0,
    var rtcpRoundTripTime: Double = 0.0,

    // Derived / extra
    var roundTripTime: Double = 0.0,
    var codec: String = "unknown",
    var inboundBitrate: Double = 0.0,
    var outboundBitrate: Double = 0.0,
    var timestamp: Double = 0.0
)
