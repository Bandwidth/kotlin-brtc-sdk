package com.bandwidth.rtc

import android.content.Context
import com.bandwidth.rtc.signaling.SignalingClientInterface
import com.bandwidth.rtc.signaling.rpc.OfferSdpResult
import com.bandwidth.rtc.signaling.rpc.SdpOffer
import com.bandwidth.rtc.signaling.rpc.SetMediaPreferencesResult
import com.bandwidth.rtc.types.*
import com.bandwidth.rtc.webrtc.PeerConnectionManagerInterface
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.webrtc.MediaStream
import org.webrtc.PeerConnection

/**
 * Resource sharing and lifecycle management tests for BandwidthRTC.
 *
 * Covers:
 * - Internal reference lifecycle (signaling, pcManager, mixingDevice)
 * - Callback reference cleanup
 * - Error during connect does not leave partial state
 * - Multiple publish/unpublish cycles
 * - Resource state after error conditions
 * - Cleanup order verification
 * - No resource leaks after connect+disconnect cycles
 * - MixingDevice lifecycle
 */
class BandwidthRTCResourceLifecycleTest {

    private lateinit var context: Context
    private lateinit var mockSignaling: SignalingClientInterface
    private lateinit var mockPCManager: PeerConnectionManagerInterface
    private lateinit var brtc: BandwidthRTC

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        mockSignaling = mockk(relaxed = true)
        mockPCManager = mockk(relaxed = true)
        brtc = BandwidthRTC(
            context = context,
            signaling = mockSignaling,
            peerConnectionManager = mockPCManager
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private val authParams = RtcAuthParams(endpointToken = "test-token")

    private fun buildMockMediaStream(id: String): MediaStream {
        val stream = mockk<MediaStream>(relaxed = true)
        every { stream.id } returns id
        return stream
    }

    private suspend fun connectBrtc() {
        coEvery { mockSignaling.setMediaPreferences() } returns SetMediaPreferencesResult()
        brtc.connect(authParams)
    }

    // =========================================================================
    // Internal reference lifecycle
    // =========================================================================

    @Test
    fun `signaling is set during connect`() = runTest {
        connectBrtc()
        assertNotNull("Signaling should be set after connect", brtc.signaling)
    }

    @Test
    fun `signaling is null after disconnect`() = runTest {
        connectBrtc()
        brtc.disconnect()
        assertNull("Signaling should be null after disconnect", brtc.signaling)
    }

    @Test
    fun `peerConnectionManager is set during connect`() = runTest {
        connectBrtc()
        assertNotNull("PCManager should be set after connect", brtc.peerConnectionManager)
    }

    @Test
    fun `peerConnectionManager is null after disconnect`() = runTest {
        connectBrtc()
        brtc.disconnect()
        assertNull("PCManager should be null after disconnect", brtc.peerConnectionManager)
    }

    @Test
    fun `mixingDevice is null after disconnect`() = runTest {
        connectBrtc()
        brtc.disconnect()
        assertNull("MixingDevice should be null after disconnect", brtc.mixingDevice)
    }

    // =========================================================================
    // Error during connect does not leave partial state
    // =========================================================================

    @Test
    fun `failed connect due to signaling error leaves disconnected state`() = runTest {
        coEvery { mockSignaling.connect(any(), any()) } throws
            BandwidthRTCError.ConnectionFailed("test failure")

        try {
            brtc.connect(authParams)
            fail("Should have thrown")
        } catch (e: BandwidthRTCError.ConnectionFailed) {
            // Expected
        }

        assertFalse("Should not be connected after failed connect", brtc.isConnected)
    }

    @Test
    fun `failed connect due to setMediaPreferences error leaves partially set up`() = runTest {
        coEvery { mockSignaling.setMediaPreferences() } throws
            BandwidthRTCError.RpcError(500, "server error")

        try {
            brtc.connect(authParams)
            fail("Should have thrown")
        } catch (e: BandwidthRTCError.RpcError) {
            // Expected
        }

        // isConnected should not be true since connect didn't complete
        assertFalse("Should not be connected after failed setMediaPreferences",
            brtc.isConnected)
    }

    @Test
    fun `failed connect due to SDP negotiation leaves not fully connected`() = runTest {
        coEvery { mockSignaling.setMediaPreferences() } returns SetMediaPreferencesResult(
            publishSdpOffer = SdpOffer(peerType = "publish", sdpOffer = "pub-offer")
        )
        coEvery { mockPCManager.answerInitialOffer(any(), any()) } throws
            BandwidthRTCError.SdpNegotiationFailed("SDP error")

        try {
            brtc.connect(authParams)
            fail("Should have thrown")
        } catch (e: BandwidthRTCError.SdpNegotiationFailed) {
            // Expected
        }

        assertFalse(brtc.isConnected)
    }

    // =========================================================================
    // Multiple publish/unpublish cycles
    // =========================================================================

    @Test
    fun `publish and unpublish can be called multiple times`() = runTest {
        connectBrtc()

        coEvery { mockPCManager.waitForPublishIceConnected() } just Runs
        coEvery { mockPCManager.createPublishOffer() } returns "offer"
        coEvery { mockSignaling.offerSdp(any(), any()) } returns OfferSdpResult("answer")
        coEvery { mockPCManager.applyPublishAnswer(any()) } just Runs

        repeat(3) { i ->
            val mockStream = buildMockMediaStream("stream-$i")
            every { mockPCManager.addLocalTracks(any()) } returns mockStream

            val stream = brtc.publish(audio = true)
            assertEquals("stream-$i", stream.streamId)

            brtc.unpublish(stream)
        }

        verify(exactly = 3) { mockPCManager.addLocalTracks(any()) }
        verify(exactly = 3) { mockPCManager.removeLocalTracks(any()) }
    }

    @Test
    fun `publish with alias preserves alias in returned stream`() = runTest {
        connectBrtc()

        coEvery { mockPCManager.waitForPublishIceConnected() } just Runs
        every { mockPCManager.addLocalTracks(any()) } returns buildMockMediaStream("s1")
        coEvery { mockPCManager.createPublishOffer() } returns "offer"
        coEvery { mockSignaling.offerSdp(any(), any()) } returns OfferSdpResult("answer")
        coEvery { mockPCManager.applyPublishAnswer(any()) } just Runs

        val stream = brtc.publish(audio = true, alias = "my-mic")
        assertEquals("my-mic", stream.alias)
    }

    // =========================================================================
    // Cleanup verification
    // =========================================================================

    @Test
    fun `disconnect calls cleanup on PCManager exactly once`() = runTest {
        connectBrtc()

        brtc.disconnect()

        verify(exactly = 1) { mockPCManager.cleanup() }
    }

    @Test
    fun `disconnect calls disconnect on signaling exactly once`() = runTest {
        connectBrtc()

        brtc.disconnect()

        coVerify(exactly = 1) { mockSignaling.disconnect() }
    }

    @Test
    fun `double disconnect does not call cleanup twice`() = runTest {
        connectBrtc()

        brtc.disconnect()
        brtc.disconnect()

        // Second disconnect should be a no-op since signaling/pcManager are already null
        verify(exactly = 1) { mockPCManager.cleanup() }
        coVerify(exactly = 1) { mockSignaling.disconnect() }
    }

    // =========================================================================
    // Callback lifecycle
    // =========================================================================

    @Test
    fun `callbacks survive disconnect - user can keep their references`() = runTest {
        var streamAvailableCalled = false
        brtc.onStreamAvailable = { streamAvailableCalled = true }

        connectBrtc()
        brtc.disconnect()

        // The user's callback reference should still be set
        assertNotNull(brtc.onStreamAvailable)
    }

    @Test
    fun `callbacks are wired to PCManager during connect`() = runTest {
        connectBrtc()

        verify { mockPCManager.onStreamAvailable = any() }
        verify { mockPCManager.onStreamUnavailable = any() }
        verify { mockPCManager.onSubscribingIceConnectionStateChange = any() }
    }

    @Test
    fun `onLocalAudioLevel callback can be set before connect`() {
        var called = false
        brtc.onLocalAudioLevel = { called = true }
        assertNotNull(brtc.onLocalAudioLevel)
    }

    @Test
    fun `onRemoteAudioLevel fires from getCallStats`() = runTest {
        connectBrtc()

        val snapshot = CallStatsSnapshot(audioLevel = 0.75)
        every { mockPCManager.getCallStats(any(), any(), any(), any()) } answers {
            lastArg<(CallStatsSnapshot) -> Unit>().invoke(snapshot)
        }

        var receivedSamples: FloatArray? = null
        brtc.onRemoteAudioLevel = { receivedSamples = it }

        brtc.getCallStats(null) {}

        assertNotNull(receivedSamples)
        assertEquals(9600, receivedSamples!!.size)
    }

    @Test
    fun `onRemoteAudioLevel not called when callback is null`() = runTest {
        connectBrtc()

        val snapshot = CallStatsSnapshot(audioLevel = 0.5)
        every { mockPCManager.getCallStats(any(), any(), any(), any()) } answers {
            lastArg<(CallStatsSnapshot) -> Unit>().invoke(snapshot)
        }

        brtc.onRemoteAudioLevel = null

        // Should not crash
        brtc.getCallStats(null) {}
    }

    // =========================================================================
    // PCManager interaction after disconnect
    // =========================================================================

    @Test
    fun `setMicEnabled after disconnect is no-op`() = runTest {
        connectBrtc()
        brtc.disconnect()

        // pcManager is null, should not throw
        brtc.setMicEnabled(true)
        brtc.setMicEnabled(false)
    }

    @Test
    fun `sendDtmf after disconnect is no-op`() = runTest {
        connectBrtc()
        brtc.disconnect()

        brtc.sendDtmf("5") // Should not throw
    }

    @Test
    fun `setSpeakerphoneOn after disconnect is no-op`() = runTest {
        connectBrtc()
        brtc.disconnect()

        brtc.setSpeakerphoneOn(true) // Should not throw
    }

    @Test
    fun `getCallStats after disconnect returns empty snapshot`() = runTest {
        connectBrtc()
        brtc.disconnect()

        var result: CallStatsSnapshot? = null
        brtc.getCallStats(null) { result = it }

        assertNotNull(result)
        assertEquals(0, result!!.packetsReceived)
    }

    // =========================================================================
    // Multiple BandwidthRTC instances
    // =========================================================================

    @Test
    fun `multiple instances have independent state`() = runTest {
        val sig1 = mockk<SignalingClientInterface>(relaxed = true)
        val sig2 = mockk<SignalingClientInterface>(relaxed = true)
        val pcm1 = mockk<PeerConnectionManagerInterface>(relaxed = true)
        val pcm2 = mockk<PeerConnectionManagerInterface>(relaxed = true)

        val brtc1 = BandwidthRTC(context = context, signaling = sig1, peerConnectionManager = pcm1)
        val brtc2 = BandwidthRTC(context = context, signaling = sig2, peerConnectionManager = pcm2)

        coEvery { sig1.setMediaPreferences() } returns SetMediaPreferencesResult()

        brtc1.connect(authParams)

        assertTrue(brtc1.isConnected)
        assertFalse(brtc2.isConnected)
    }

    @Test
    fun `multiple instances have independent callbacks`() {
        val brtc1 = BandwidthRTC(context = context, signaling = mockSignaling, peerConnectionManager = mockPCManager)
        val brtc2 = BandwidthRTC(context = context, signaling = mockk(relaxed = true), peerConnectionManager = mockk(relaxed = true))

        var stream1: RtcStream? = null
        var stream2: RtcStream? = null

        brtc1.onStreamAvailable = { stream1 = it }
        brtc2.onStreamAvailable = { stream2 = it }

        assertNotNull(brtc1.onStreamAvailable)
        assertNotNull(brtc2.onStreamAvailable)
        assertNull(stream1)
        assertNull(stream2)
    }

    // =========================================================================
    // Connect-disconnect cycles
    // =========================================================================

    @Test
    fun `multiple connect-disconnect cycles with fresh instances`() = runTest {
        repeat(5) {
            val sig = mockk<SignalingClientInterface>(relaxed = true)
            val pcm = mockk<PeerConnectionManagerInterface>(relaxed = true)
            coEvery { sig.setMediaPreferences() } returns SetMediaPreferencesResult()

            val instance = BandwidthRTC(context = context, signaling = sig, peerConnectionManager = pcm)

            assertFalse(instance.isConnected)
            instance.connect(authParams)
            assertTrue(instance.isConnected)
            instance.disconnect()
            assertFalse(instance.isConnected)

            verify { pcm.cleanup() }
            coVerify { sig.disconnect() }

            unmockkAll()
        }
    }

    // =========================================================================
    // setLogLevel
    // =========================================================================

    @Test
    fun `setLogLevel does not crash for any level`() {
        for (level in com.bandwidth.rtc.util.LogLevel.entries) {
            brtc.setLogLevel(level)
        }
    }
}
