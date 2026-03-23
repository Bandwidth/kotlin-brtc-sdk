package com.bandwidth.rtc.types

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the SDK type system and error hierarchy.
 *
 * Covers:
 * - BandwidthRTCError sealed class hierarchy
 * - Error message formatting
 * - CallStatsSnapshot defaults and mutability
 * - RtcAuthParams data class
 * - RtcOptions data class
 * - ReadyMetadata serialization defaults
 * - ConnectionResult types
 * - EndpointType enum values
 * - MediaType enum values
 * - PeerConnectionType enum values
 * - AudioProcessingOptions defaults
 */
class TypeSystemTest {

    // =========================================================================
    // BandwidthRTCError hierarchy
    // =========================================================================

    @Test
    fun `InvalidToken has correct message`() {
        val error = BandwidthRTCError.InvalidToken()
        assertEquals("Invalid or expired endpoint token", error.message)
    }

    @Test
    fun `ConnectionFailed includes detail`() {
        val error = BandwidthRTCError.ConnectionFailed("timeout")
        assertTrue(error.message!!.contains("timeout"))
        assertTrue(error.message!!.contains("Connection failed"))
    }

    @Test
    fun `SignalingError includes detail`() {
        val error = BandwidthRTCError.SignalingError("send failed")
        assertTrue(error.message!!.contains("send failed"))
        assertTrue(error.message!!.contains("Signaling error"))
    }

    @Test
    fun `WebSocketDisconnected has correct message`() {
        val error = BandwidthRTCError.WebSocketDisconnected()
        assertTrue(error.message!!.contains("WebSocket disconnected"))
    }

    @Test
    fun `SdpNegotiationFailed includes detail`() {
        val error = BandwidthRTCError.SdpNegotiationFailed("no answer")
        assertTrue(error.message!!.contains("no answer"))
        assertTrue(error.message!!.contains("SDP negotiation failed"))
    }

    @Test
    fun `MediaAccessDenied has correct message`() {
        val error = BandwidthRTCError.MediaAccessDenied()
        assertTrue(error.message!!.contains("denied"))
    }

    @Test
    fun `AlreadyConnected has correct message`() {
        val error = BandwidthRTCError.AlreadyConnected()
        assertTrue(error.message!!.contains("Already connected"))
    }

    @Test
    fun `NotConnected has correct message`() {
        val error = BandwidthRTCError.NotConnected()
        assertTrue(error.message!!.contains("Not connected"))
    }

    @Test
    fun `PublishFailed includes detail`() {
        val error = BandwidthRTCError.PublishFailed("no PC")
        assertTrue(error.message!!.contains("no PC"))
        assertTrue(error.message!!.contains("Publish failed"))
    }

    @Test
    fun `RpcError includes code and message`() {
        val error = BandwidthRTCError.RpcError(500, "internal error")
        assertEquals(500, error.code)
        assertEquals("internal error", error.rpcMessage)
        assertTrue(error.message!!.contains("500"))
        assertTrue(error.message!!.contains("internal error"))
    }

    @Test
    fun `NotSupported includes detail`() {
        val error = BandwidthRTCError.NotSupported("video")
        assertTrue(error.message!!.contains("video"))
    }

    @Test
    fun `NoActiveCall has correct message`() {
        val error = BandwidthRTCError.NoActiveCall()
        assertTrue(error.message!!.contains("No active call"))
    }

    @Test
    fun `all error types are instances of BandwidthRTCError`() {
        val errors: List<BandwidthRTCError> = listOf(
            BandwidthRTCError.InvalidToken(),
            BandwidthRTCError.ConnectionFailed("test"),
            BandwidthRTCError.SignalingError("test"),
            BandwidthRTCError.WebSocketDisconnected(),
            BandwidthRTCError.SdpNegotiationFailed("test"),
            BandwidthRTCError.MediaAccessDenied(),
            BandwidthRTCError.AlreadyConnected(),
            BandwidthRTCError.NotConnected(),
            BandwidthRTCError.PublishFailed("test"),
            BandwidthRTCError.RpcError(0, "test"),
            BandwidthRTCError.NotSupported("test"),
            BandwidthRTCError.NoActiveCall()
        )

        assertEquals(12, errors.size)
        errors.forEach { error ->
            assertTrue("${error::class.simpleName} should be Exception",
                error is Exception)
            assertNotNull("${error::class.simpleName} should have a message",
                error.message)
        }
    }

    @Test
    fun `errors can be caught as Exception`() {
        try {
            throw BandwidthRTCError.NotConnected()
        } catch (e: Exception) {
            assertTrue(e is BandwidthRTCError)
            assertTrue(e is BandwidthRTCError.NotConnected)
        }
    }

    @Test
    fun `errors can be matched with when expression`() {
        val error: BandwidthRTCError = BandwidthRTCError.InvalidToken()

        val result = when (error) {
            is BandwidthRTCError.InvalidToken -> "invalid_token"
            is BandwidthRTCError.ConnectionFailed -> "connection_failed"
            is BandwidthRTCError.NotConnected -> "not_connected"
            else -> "other"
        }

        assertEquals("invalid_token", result)
    }

    // =========================================================================
    // CallStatsSnapshot
    // =========================================================================

    @Test
    fun `CallStatsSnapshot has sensible defaults`() {
        val stats = CallStatsSnapshot()
        assertEquals(0, stats.packetsReceived)
        assertEquals(0, stats.packetsLost)
        assertEquals(0, stats.bytesReceived)
        assertEquals(0.0, stats.jitter, 0.0)
        assertEquals(0.0, stats.audioLevel, 0.0)
        assertEquals(0, stats.packetsSent)
        assertEquals(0, stats.bytesSent)
        assertEquals(0.0, stats.roundTripTime, 0.0)
        assertEquals("unknown", stats.codec)
        assertEquals(0.0, stats.inboundBitrate, 0.0)
        assertEquals(0.0, stats.outboundBitrate, 0.0)
        assertEquals(0.0, stats.timestamp, 0.0)
    }

    @Test
    fun `CallStatsSnapshot fields are mutable`() {
        val stats = CallStatsSnapshot()
        stats.packetsReceived = 100
        stats.packetsLost = 5
        stats.bytesReceived = 50000
        stats.jitter = 0.01
        stats.audioLevel = 0.75
        stats.packetsSent = 200
        stats.bytesSent = 80000
        stats.roundTripTime = 0.05
        stats.codec = "opus"
        stats.inboundBitrate = 32000.0
        stats.outboundBitrate = 48000.0
        stats.timestamp = 1234567890.0

        assertEquals(100, stats.packetsReceived)
        assertEquals(5, stats.packetsLost)
        assertEquals("opus", stats.codec)
    }

    @Test
    fun `CallStatsSnapshot copy works correctly`() {
        val original = CallStatsSnapshot(packetsReceived = 100, codec = "opus")
        val copy = original.copy(packetsReceived = 200)

        assertEquals(200, copy.packetsReceived)
        assertEquals("opus", copy.codec) // Preserved from original
    }

    @Test
    fun `CallStatsSnapshot data class equality`() {
        val a = CallStatsSnapshot(packetsReceived = 100, codec = "opus")
        val b = CallStatsSnapshot(packetsReceived = 100, codec = "opus")
        assertEquals(a, b)
    }

    @Test
    fun `CallStatsSnapshot with constructor parameters`() {
        val stats = CallStatsSnapshot(
            packetsReceived = 42,
            packetsLost = 3,
            codec = "pcmu",
            roundTripTime = 0.123
        )
        assertEquals(42, stats.packetsReceived)
        assertEquals(3, stats.packetsLost)
        assertEquals("pcmu", stats.codec)
        assertEquals(0.123, stats.roundTripTime, 0.001)
    }

    // =========================================================================
    // ReadyMetadata
    // =========================================================================

    @Test
    fun `ReadyMetadata has null defaults`() {
        val metadata = ReadyMetadata()
        assertNull(metadata.endpointId)
        assertNull(metadata.deviceId)
        assertNull(metadata.territory)
        assertNull(metadata.region)
    }

    @Test
    fun `ReadyMetadata with all fields set`() {
        val metadata = ReadyMetadata(
            endpointId = "ep-1",
            deviceId = "dev-1",
            territory = "US",
            region = "us-east-1"
        )
        assertEquals("ep-1", metadata.endpointId)
        assertEquals("dev-1", metadata.deviceId)
        assertEquals("US", metadata.territory)
        assertEquals("us-east-1", metadata.region)
    }

    // =========================================================================
    // ConnectionResult types
    // =========================================================================

    @Test
    fun `OutboundConnectionResult defaults to not accepted`() {
        val result = OutboundConnectionResult()
        assertFalse(result.accepted)
    }

    @Test
    fun `OutboundConnectionResult accepted`() {
        val result = OutboundConnectionResult(accepted = true)
        assertTrue(result.accepted)
    }

    @Test
    fun `HangupResult defaults to null result`() {
        val result = HangupResult()
        assertNull(result.result)
    }

    @Test
    fun `HangupResult with result`() {
        val result = HangupResult(result = "ok")
        assertEquals("ok", result.result)
    }

    // =========================================================================
    // Enums
    // =========================================================================

    @Test
    fun `EndpointType has all expected values`() {
        val values = EndpointType.entries
        assertTrue(values.any { it.name == "ENDPOINT" })
        assertTrue(values.any { it.name == "CALL_ID" })
        assertTrue(values.any { it.name == "PHONE_NUMBER" })
    }

    @Test
    fun `MediaType has AUDIO`() {
        assertTrue(MediaType.entries.any { it.name == "AUDIO" })
    }

    @Test
    fun `PeerConnectionType has PUBLISH and SUBSCRIBE`() {
        val values = PeerConnectionType.entries
        assertTrue(values.any { it.name == "PUBLISH" })
        assertTrue(values.any { it.name == "SUBSCRIBE" })
    }

    // =========================================================================
    // RtcAuthParams
    // =========================================================================

    @Test
    fun `RtcAuthParams stores token`() {
        val params = RtcAuthParams(endpointToken = "my-jwt-token")
        assertEquals("my-jwt-token", params.endpointToken)
    }

    @Test
    fun `RtcAuthParams data class equality`() {
        val a = RtcAuthParams(endpointToken = "token-1")
        val b = RtcAuthParams(endpointToken = "token-1")
        assertEquals(a, b)
    }

    @Test
    fun `RtcAuthParams data class inequality`() {
        val a = RtcAuthParams(endpointToken = "token-1")
        val b = RtcAuthParams(endpointToken = "token-2")
        assertNotEquals(a, b)
    }

    // =========================================================================
    // RtcOptions
    // =========================================================================

    @Test
    fun `RtcOptions defaults`() {
        val options = RtcOptions()
        assertNull(options.websocketUrl)
    }

    @Test
    fun `RtcOptions with custom websocket URL`() {
        val options = RtcOptions(websocketUrl = "wss://custom.example.com")
        assertEquals("wss://custom.example.com", options.websocketUrl)
    }
}
