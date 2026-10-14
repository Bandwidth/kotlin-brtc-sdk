package com.bandwidth.rtc.types

/**
 * Point-in-time call statistics. A field that is not reported yet is 0, so 0 does not always
 * mean a perfect value. The RTCP fields (`remote*`, `rtcpRoundTripTime`) stay 0 until the first
 * receiver report arrives.
 *
 * The flat counters are the totals across the session. [publishPeer] and [subscribePeer] hold the
 * same audio RTP counters split by the peer connection that reported them.
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
    var timestamp: Double = 0.0,

    // Per-peer audio RTP counters
    /** Peer connection that sends local media. */
    var publishPeer: PeerCallStats = PeerCallStats(),
    /** Peer connection that receives remote media. */
    var subscribePeer: PeerCallStats = PeerCallStats()
)

/**
 * Audio RTP counters read from one peer connection's own stats report. The direction a peer
 * connection does not use normally stays 0.
 */
data class PeerCallStats(
    val packetsSent: Int = 0,
    val bytesSent: Int = 0,
    val packetsReceived: Int = 0,
    val bytesReceived: Int = 0
)
