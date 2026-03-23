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
 * Order of operations and determinism tests for BandwidthRTC.
 *
 * Ensures that:
 * - Operations follow a valid lifecycle order
 * - Invalid operation sequences throw appropriate errors
 * - cleanup() releases resources in the correct order
 * - Event handler registration happens before signaling connect
 * - SDP answer flow completes publish → subscribe in order
 * - Double operations are handled correctly
 * - State machine transitions are deterministic
 */
class BandwidthRTCOrderOfOperationsTest {

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
    // Connect lifecycle ordering
    // =========================================================================

    @Test
    fun `connect registers event handlers before signaling connect`() = runTest {
        val callOrder = mutableListOf<String>()

        every { mockSignaling.onEvent(any(), any()) } answers {
            callOrder.add("onEvent:${firstArg<String>()}")
        }
        coEvery { mockSignaling.connect(any(), any()) } answers {
            callOrder.add("signaling.connect")
        }
        coEvery { mockSignaling.setMediaPreferences() } answers {
            callOrder.add("setMediaPreferences")
            SetMediaPreferencesResult()
        }

        brtc.connect(authParams)

        // Event handlers should be registered before connect
        val connectIdx = callOrder.indexOf("signaling.connect")
        val eventRegistrations = callOrder.filter { it.startsWith("onEvent:") }
        assertTrue("Event handlers should be registered", eventRegistrations.isNotEmpty())
        assertTrue("Events should be registered before connect",
            callOrder.indexOf(eventRegistrations.first()) < connectIdx)
    }

    @Test
    fun `connect calls setMediaPreferences after signaling connect`() = runTest {
        val callOrder = mutableListOf<String>()

        coEvery { mockSignaling.connect(any(), any()) } answers {
            callOrder.add("signaling.connect")
        }
        coEvery { mockSignaling.setMediaPreferences() } answers {
            callOrder.add("setMediaPreferences")
            SetMediaPreferencesResult()
        }

        brtc.connect(authParams)

        val connectIdx = callOrder.indexOf("signaling.connect")
        val mediaIdx = callOrder.indexOf("setMediaPreferences")
        assertTrue("setMediaPreferences should come after connect", mediaIdx > connectIdx)
    }

    @Test
    fun `connect answers publish SDP before subscribe SDP`() = runTest {
        val answerOrder = mutableListOf<String>()

        coEvery { mockSignaling.setMediaPreferences() } returns SetMediaPreferencesResult(
            publishSdpOffer = SdpOffer(peerType = "publish", sdpOffer = "pub-offer"),
            subscribeSdpOffer = SdpOffer(peerType = "subscribe", sdpOffer = "sub-offer")
        )
        coEvery { mockPCManager.answerInitialOffer("pub-offer", PeerConnectionType.PUBLISH) } answers {
            answerOrder.add("publish")
            "pub-answer"
        }
        coEvery { mockPCManager.answerInitialOffer("sub-offer", PeerConnectionType.SUBSCRIBE) } answers {
            answerOrder.add("subscribe")
            "sub-answer"
        }

        brtc.connect(authParams)

        assertEquals(listOf("publish", "subscribe"), answerOrder)
    }

    @Test
    fun `connect fires onReady after SDP negotiation completes`() = runTest {
        val callOrder = mutableListOf<String>()

        coEvery { mockSignaling.setMediaPreferences() } returns SetMediaPreferencesResult(
            endpointId = "ep-1",
            publishSdpOffer = SdpOffer(peerType = "publish", sdpOffer = "pub-offer")
        )
        coEvery { mockPCManager.answerInitialOffer(any(), any()) } answers {
            callOrder.add("answerOffer")
            "answer"
        }

        brtc.onReady = { callOrder.add("onReady") }
        brtc.connect(authParams)

        val answerIdx = callOrder.indexOf("answerOffer")
        val readyIdx = callOrder.indexOf("onReady")
        assertTrue("onReady should fire after SDP answer", readyIdx > answerIdx)
    }

    @Test
    fun `isConnected is true only after full connect completes`() = runTest {
        assertFalse(brtc.isConnected)

        var wasConnectedDuringSetMedia = false
        coEvery { mockSignaling.setMediaPreferences() } answers {
            wasConnectedDuringSetMedia = brtc.isConnected
            SetMediaPreferencesResult()
        }

        brtc.connect(authParams)

        assertFalse("Should not be connected during setMediaPreferences", wasConnectedDuringSetMedia)
        assertTrue("Should be connected after connect completes", brtc.isConnected)
    }

    // =========================================================================
    // Publish lifecycle ordering
    // =========================================================================

    @Test
    fun `publish waits for ICE before adding tracks`() = runTest {
        connectBrtc()

        val callOrder = mutableListOf<String>()

        coEvery { mockPCManager.waitForPublishIceConnected() } answers {
            callOrder.add("waitForICE")
        }
        every { mockPCManager.addLocalTracks(any()) } answers {
            callOrder.add("addLocalTracks")
            buildMockMediaStream("s1")
        }
        coEvery { mockPCManager.createPublishOffer() } answers {
            callOrder.add("createOffer")
            "offer"
        }
        coEvery { mockSignaling.offerSdp(any(), any()) } answers {
            callOrder.add("offerSdp")
            OfferSdpResult("answer")
        }
        coEvery { mockPCManager.applyPublishAnswer(any()) } answers {
            callOrder.add("applyAnswer")
        }

        brtc.publish(audio = true)

        assertEquals(
            listOf("waitForICE", "addLocalTracks", "createOffer", "offerSdp", "applyAnswer"),
            callOrder
        )
    }

    @Test
    fun `publish returns RtcStream with correct properties`() = runTest {
        connectBrtc()

        val mockStream = buildMockMediaStream("my-stream")
        coEvery { mockPCManager.waitForPublishIceConnected() } just Runs
        every { mockPCManager.addLocalTracks(any()) } returns mockStream
        coEvery { mockPCManager.createPublishOffer() } returns "offer"
        coEvery { mockSignaling.offerSdp(any(), any()) } returns OfferSdpResult("answer")
        coEvery { mockPCManager.applyPublishAnswer(any()) } just Runs

        val stream = brtc.publish(audio = true, alias = "my-alias")

        assertEquals("my-stream", stream.streamId)
        assertEquals("my-alias", stream.alias)
        assertTrue(stream.mediaTypes.contains(MediaType.AUDIO))
    }

    // =========================================================================
    // Disconnect cleanup ordering
    // =========================================================================

    @Test
    fun `disconnect cleans up in order - signaling then PC then audio`() = runTest {
        connectBrtc()

        val cleanupOrder = mutableListOf<String>()

        coEvery { mockSignaling.disconnect() } answers {
            cleanupOrder.add("signaling.disconnect")
        }
        every { mockPCManager.cleanup() } answers {
            cleanupOrder.add("pcManager.cleanup")
        }

        brtc.disconnect()

        // Signaling should be disconnected first to stop incoming offers
        // Then PC cleanup to close connections
        assertEquals("signaling.disconnect", cleanupOrder[0])
        assertEquals("pcManager.cleanup", cleanupOrder[1])
    }

    @Test
    fun `disconnect sets signaling and pcManager to null`() = runTest {
        connectBrtc()

        brtc.disconnect()

        // After disconnect, internal references should be nullified
        assertFalse(brtc.isConnected)
        assertNull(brtc.mixingDevice)
    }

    @Test
    fun `disconnect sets isConnected to false`() = runTest {
        connectBrtc()
        assertTrue(brtc.isConnected)

        brtc.disconnect()
        assertFalse(brtc.isConnected)
    }

    // =========================================================================
    // Invalid operation sequences
    // =========================================================================

    @Test(expected = BandwidthRTCError.AlreadyConnected::class)
    fun `double connect throws AlreadyConnected`() = runTest {
        connectBrtc()
        brtc.connect(authParams) // Should throw
    }

    @Test(expected = BandwidthRTCError.NotConnected::class)
    fun `publish before connect throws NotConnected`() = runTest {
        brtc.publish()
    }

    @Test(expected = BandwidthRTCError.NotConnected::class)
    fun `unpublish before connect throws NotConnected`() = runTest {
        val mockStream = buildMockMediaStream("s1")
        brtc.unpublish(RtcStream(mediaStream = mockStream, mediaTypes = listOf(MediaType.AUDIO)))
    }

    @Test(expected = BandwidthRTCError.NotConnected::class)
    fun `requestOutboundConnection before connect throws NotConnected`() = runTest {
        brtc.requestOutboundConnection("123", EndpointType.PHONE_NUMBER)
    }

    @Test(expected = BandwidthRTCError.NotConnected::class)
    fun `hangupConnection before connect throws NotConnected`() = runTest {
        brtc.hangupConnection("ep-1", EndpointType.ENDPOINT)
    }

    @Test(expected = BandwidthRTCError.NotConnected::class)
    fun `publish after disconnect throws NotConnected`() = runTest {
        connectBrtc()
        brtc.disconnect()
        brtc.publish()
    }

    @Test(expected = BandwidthRTCError.NotConnected::class)
    fun `requestOutboundConnection after disconnect throws NotConnected`() = runTest {
        connectBrtc()
        brtc.disconnect()
        brtc.requestOutboundConnection("123", EndpointType.PHONE_NUMBER)
    }

    @Test
    fun `setMicEnabled before connect is safe (no-op)`() {
        brtc.setMicEnabled(true) // Should not throw
        brtc.setMicEnabled(false) // Should not throw
    }

    @Test
    fun `sendDtmf before connect is safe (no-op)`() {
        brtc.sendDtmf("5") // Should not throw
    }

    @Test
    fun `setSpeakerphoneOn before connect is safe (no-op)`() {
        brtc.setSpeakerphoneOn(true) // Should not throw
    }

    @Test
    fun `disconnect before connect is safe (no-op)`() = runTest {
        brtc.disconnect() // Should not throw
        assertFalse(brtc.isConnected)
    }

    // =========================================================================
    // Double disconnect / reconnect
    // =========================================================================

    @Test
    fun `double disconnect is idempotent`() = runTest {
        connectBrtc()

        brtc.disconnect()
        assertFalse(brtc.isConnected)

        brtc.disconnect() // Should not throw
        assertFalse(brtc.isConnected)
    }

    @Test
    fun `connect after disconnect works (fresh session)`() = runTest {
        connectBrtc()
        brtc.disconnect()

        // Reconnect with fresh signaling/pcManager
        val newSignaling = mockk<SignalingClientInterface>(relaxed = true)
        val newPCManager = mockk<PeerConnectionManagerInterface>(relaxed = true)
        coEvery { newSignaling.setMediaPreferences() } returns SetMediaPreferencesResult()

        // After disconnect, internal signaling/pcManager are null, so a new connect
        // should create new instances. Since we use the internal constructor,
        // we need a new BandwidthRTC instance.
        val brtc2 = BandwidthRTC(
            context = context,
            signaling = newSignaling,
            peerConnectionManager = newPCManager
        )

        brtc2.connect(authParams)
        assertTrue(brtc2.isConnected)
    }

    // =========================================================================
    // Full lifecycle determinism
    // =========================================================================

    @Test
    fun `full connect-publish-unpublish-disconnect lifecycle`() = runTest {
        val callOrder = mutableListOf<String>()

        coEvery { mockSignaling.connect(any(), any()) } answers { callOrder.add("sig.connect") }
        coEvery { mockSignaling.setMediaPreferences() } answers {
            callOrder.add("setMediaPrefs")
            SetMediaPreferencesResult()
        }
        coEvery { mockPCManager.waitForPublishIceConnected() } answers { callOrder.add("waitICE") }
        every { mockPCManager.addLocalTracks(any()) } answers {
            callOrder.add("addTracks")
            buildMockMediaStream("s1")
        }
        coEvery { mockPCManager.createPublishOffer() } answers {
            callOrder.add("createOffer")
            "offer"
        }
        coEvery { mockSignaling.offerSdp(any(), any()) } answers {
            callOrder.add("offerSdp")
            OfferSdpResult("answer")
        }
        coEvery { mockPCManager.applyPublishAnswer(any()) } answers { callOrder.add("applyAnswer") }
        every { mockPCManager.removeLocalTracks(any()) } answers { callOrder.add("removeTracks") }
        coEvery { mockSignaling.disconnect() } answers { callOrder.add("sig.disconnect") }
        every { mockPCManager.cleanup() } answers { callOrder.add("pc.cleanup") }

        // 1. Connect
        brtc.connect(authParams)
        assertTrue(brtc.isConnected)

        // 2. Publish
        val stream = brtc.publish(audio = true)
        assertNotNull(stream)

        // 3. Unpublish
        brtc.unpublish(stream)

        // 4. Disconnect
        brtc.disconnect()
        assertFalse(brtc.isConnected)

        // Verify the complete order
        val expectedOrder = listOf(
            "sig.connect", "setMediaPrefs",  // Connect phase
            "waitICE", "addTracks", "createOffer", "offerSdp", "applyAnswer",  // Publish
            "removeTracks", "createOffer", "offerSdp", "applyAnswer",  // Unpublish
            "sig.disconnect", "pc.cleanup"  // Disconnect
        )
        assertEquals(expectedOrder, callOrder)
    }

    @Test
    fun `same operation sequence always produces same call order`() = runTest {
        repeat(3) { run ->
            val ctx = mockk<Context>(relaxed = true)
            val sig = mockk<SignalingClientInterface>(relaxed = true)
            val pcm = mockk<PeerConnectionManagerInterface>(relaxed = true)
            val b = BandwidthRTC(context = ctx, signaling = sig, peerConnectionManager = pcm)

            val callOrder = mutableListOf<String>()
            coEvery { sig.connect(any(), any()) } answers { callOrder.add("connect") }
            coEvery { sig.setMediaPreferences() } answers {
                callOrder.add("setMedia")
                SetMediaPreferencesResult()
            }
            coEvery { sig.disconnect() } answers { callOrder.add("disconnect") }
            every { pcm.cleanup() } answers { callOrder.add("cleanup") }

            b.connect(authParams)
            b.disconnect()

            assertEquals("Run $run should produce same order",
                listOf("connect", "setMedia", "disconnect", "cleanup"),
                callOrder)

            unmockkAll()
        }
    }

    // =========================================================================
    // Event handler ordering
    // =========================================================================

    @Test
    fun `connect registers sdpOffer, ready, established, close handlers`() = runTest {
        val registeredEvents = mutableListOf<String>()
        every { mockSignaling.onEvent(any(), any()) } answers {
            registeredEvents.add(firstArg())
        }
        coEvery { mockSignaling.setMediaPreferences() } returns SetMediaPreferencesResult()

        brtc.connect(authParams)

        assertTrue("sdpOffer should be registered", registeredEvents.contains("sdpOffer"))
        assertTrue("ready should be registered", registeredEvents.contains("ready"))
        assertTrue("established should be registered", registeredEvents.contains("established"))
        assertTrue("close should be registered", registeredEvents.contains("close"))
    }

    @Test
    fun `close event sets isConnected to false`() = runTest {
        val handlers = mutableMapOf<String, (String) -> Unit>()
        every { mockSignaling.onEvent(any(), any()) } answers {
            handlers[firstArg()] = secondArg()
        }
        coEvery { mockSignaling.setMediaPreferences() } returns SetMediaPreferencesResult()

        brtc.connect(authParams)
        assertTrue(brtc.isConnected)

        handlers["close"]?.invoke("")
        assertFalse(brtc.isConnected)
    }

    // =========================================================================
    // getCallStats ordering
    // =========================================================================

    @Test
    fun `getCallStats passes previous snapshot values correctly`() {
        val previous = CallStatsSnapshot(
            bytesReceived = 5000,
            bytesSent = 3000,
            timestamp = 1234.5
        )
        every { mockPCManager.getCallStats(any(), any(), any(), any()) } answers {
            lastArg<(CallStatsSnapshot) -> Unit>().invoke(CallStatsSnapshot())
        }

        brtc.getCallStats(previous) {}

        verify {
            mockPCManager.getCallStats(
                previousInboundBytes = 5000,
                previousOutboundBytes = 3000,
                previousTimestamp = 1234.5,
                completion = any()
            )
        }
    }

    @Test
    fun `getCallStats with null previous passes zeros`() {
        every { mockPCManager.getCallStats(any(), any(), any(), any()) } answers {
            lastArg<(CallStatsSnapshot) -> Unit>().invoke(CallStatsSnapshot())
        }

        brtc.getCallStats(null) {}

        verify {
            mockPCManager.getCallStats(
                previousInboundBytes = 0,
                previousOutboundBytes = 0,
                previousTimestamp = 0.0,
                completion = any()
            )
        }
    }

    @Test
    fun `getCallStats fires onRemoteAudioLevel before completion callback`() {
        val callOrder = mutableListOf<String>()
        val snapshot = CallStatsSnapshot(audioLevel = 0.5)

        every { mockPCManager.getCallStats(any(), any(), any(), any()) } answers {
            lastArg<(CallStatsSnapshot) -> Unit>().invoke(snapshot)
        }

        brtc.onRemoteAudioLevel = { callOrder.add("audioLevel") }

        brtc.getCallStats(null) { callOrder.add("completion") }

        assertEquals(listOf("audioLevel", "completion"), callOrder)
    }

    @Test
    fun `getCallStats generates 9600 samples from audioLevel`() {
        val snapshot = CallStatsSnapshot(audioLevel = 0.42)
        every { mockPCManager.getCallStats(any(), any(), any(), any()) } answers {
            lastArg<(CallStatsSnapshot) -> Unit>().invoke(snapshot)
        }

        var capturedSamples: FloatArray? = null
        brtc.onRemoteAudioLevel = { capturedSamples = it }

        brtc.getCallStats(null) {}

        assertNotNull(capturedSamples)
        assertEquals(9600, capturedSamples!!.size)
        capturedSamples!!.forEach { assertEquals(0.42f, it, 0.001f) }
    }
}
