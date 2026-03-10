package com.bandwidth.rtc.types

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

    // Derived / extra
    var roundTripTime: Double = 0.0,
    var codec: String = "unknown",
    var inboundBitrate: Double = 0.0,
    var outboundBitrate: Double = 0.0,
    var timestamp: Double = 0.0
)
