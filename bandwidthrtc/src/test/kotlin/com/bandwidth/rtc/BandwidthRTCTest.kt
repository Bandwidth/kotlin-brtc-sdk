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

class BandwidthRTCTest {

    private lateinit var context: Context
    private lateinit var mockSignaling: SignalingClientInterface
    private lateinit var mockPCManager: PeerConnectionManagerInterface
    private lateinit var brtc: BandwidthRTC

    private val authParams = RtcAuthParams(endpointToken = "test-token")

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

    // -------------------------------------------------------------------------
    // connect()
    // -------------------------------------------------------------------------

    @Test
    fun `isConnected is false before connect`() {
        assertFalse(brtc.isConnected)
    }

    @Test
    fun `connect sets isConnected to true`() = runTest {
        coEvery { mockSignaling.setMediaPreferences() } returns SetMediaPreferencesResult(
            endpointId = "ep-1",
            deviceId = "dev-1"
        )

        brtc.connect(authParams)

        assertTrue(brtc.isConnected)
    }

    @Test
    fun `connect calls signaling connect and setMediaPreferences`() = runTest {
        coEvery { mockSignaling.setMediaPreferences() } returns SetMediaPreferencesResult()

        brtc.connect(authParams)

        coVerify { mockSignaling.connect(authParams, null) }
        coVerify { mockSignaling.setMediaPreferences() }
    }

    @Test
    fun `connect invokes onReady with metadata from setMediaPreferences`() = runTest {
        coEvery { mockSignaling.setMediaPreferences() } returns SetMediaPreferencesResult(
            endpointId = "ep-123",
            deviceId = "dev-456"
        )

        var receivedMetadata: ReadyMetadata? = null
        brtc.onReady = { receivedMetadata = it }

        brtc.connect(authParams)

        assertNotNull(receivedMetadata)
        assertEquals("ep-123", receivedMetadata?.endpointId)
        assertEquals("dev-456", receivedMetadata?.deviceId)
    }

    @Test(expected = BandwidthRTCError.AlreadyConnected::class)
    fun `connect throws AlreadyConnected when already connected`() = runTest {
        coEvery { mockSignaling.setMediaPreferences() } returns SetMediaPreferencesResult()

        brtc.connect(authParams)
        brtc.connect(authParams)
    }

    @Test
    fun `connect answers publish SDP offer when provided`() = runTest {
        coEvery { mockSignaling.setMediaPreferences() } returns SetMediaPreferencesResult(
            publishSdpOffer = SdpOffer(peerType = "publish", sdpOffer = "pub-offer-sdp")
        )
        coEvery { mockPCManager.answerInitialOffer(any(), any()) } returns "pub-answer-sdp"

        brtc.connect(authParams)

        coVerify { mockPCManager.answerInitialOffer("pub-offer-sdp", PeerConnectionType.PUBLISH) }
        coVerify { mockSignaling.answerSdp("pub-answer-sdp", "publish") }
    }

    @Test
    fun `connect answers subscribe SDP offer when provided`() = runTest {
        coEvery { mockSignaling.setMediaPreferences() } returns SetMediaPreferencesResult(
            subscribeSdpOffer = SdpOffer(peerType = "subscribe", sdpOffer = "sub-offer-sdp")
        )
        coEvery { mockPCManager.answerInitialOffer(any(), any()) } returns "sub-answer-sdp"

        brtc.connect(authParams)

        coVerify { mockPCManager.answerInitialOffer("sub-offer-sdp", PeerConnectionType.SUBSCRIBE) }
        coVerify { mockSignaling.answerSdp("sub-answer-sdp", "subscribe") }
    }

    @Test
    fun `connect answers both publish and subscribe SDP offers`() = runTest {
        coEvery { mockSignaling.setMediaPreferences() } returns SetMediaPreferencesResult(
            publishSdpOffer = SdpOffer(peerType = "publish", sdpOffer = "pub-offer"),
            subscribeSdpOffer = SdpOffer(peerType = "subscribe", sdpOffer = "sub-offer")
        )
        coEvery { mockPCManager.answerInitialOffer("pub-offer", PeerConnectionType.PUBLISH) } returns "pub-answer"
        coEvery { mockPCManager.answerInitialOffer("sub-offer", PeerConnectionType.SUBSCRIBE) } returns "sub-answer"

        brtc.connect(authParams)

        coVerify { mockSignaling.answerSdp("pub-answer", "publish") }
        coVerify { mockSignaling.answerSdp("sub-answer", "subscribe") }
    }

    @Test
    fun `connect passes RtcOptions to signaling connect`() = runTest {
        val options = RtcOptions(websocketUrl = "wss://custom.example.com")
        coEvery { mockSignaling.setMediaPreferences() } returns SetMediaPreferencesResult()

        brtc.connect(authParams, options)

        coVerify { mockSignaling.connect(authParams, options) }
    }

    // -------------------------------------------------------------------------
    // disconnect()
    // -------------------------------------------------------------------------

    @Test
    fun `disconnect sets isConnected to false`() = runTest {
        coEvery { mockSignaling.setMediaPreferences() } returns SetMediaPreferencesResult()
        brtc.connect(authParams)
        assertTrue(brtc.isConnected)

        brtc.disconnect()

        assertFalse(brtc.isConnected)
    }

    @Test
    fun `disconnect calls cleanup on peerConnectionManager`() = runTest {
        coEvery { mockSignaling.setMediaPreferences() } returns SetMediaPreferencesResult()
        brtc.connect(authParams)

        brtc.disconnect()

        verify { mockPCManager.cleanup() }
    }

    @Test
    fun `disconnect calls disconnect on signaling`() = runTest {
        coEvery { mockSignaling.setMediaPreferences() } returns SetMediaPreferencesResult()
        brtc.connect(authParams)

        brtc.disconnect()

        coVerify { mockSignaling.disconnect() }
    }

    // -------------------------------------------------------------------------
    // publish()
    // -------------------------------------------------------------------------

    @Test(expected = BandwidthRTCError.NotConnected::class)
    fun `publish throws NotConnected when not connected`() = runTest {
        brtc.publish()
    }

    @Test
    fun `publish returns RtcStream with audio media type`() = runTest {
        connectBrtc()

        val mockStream = buildMockMediaStream("stream-abc")
        coEvery { mockPCManager.waitForPublishIceConnected() } just Runs
        every { mockPCManager.addLocalTracks(audio = true) } returns mockStream
        coEvery { mockPCManager.createPublishOffer() } returns "offer"
        coEvery { mockSignaling.offerSdp(any(), any()) } returns OfferSdpResult("answer")
        coEvery { mockPCManager.applyPublishAnswer(any()) } just Runs

        val stream = brtc.publish(audio = true)

        assertEquals("stream-abc", stream.streamId)
        assertTrue(stream.mediaTypes.contains(MediaType.AUDIO))
    }

    @Test
    fun `publish preserves alias on returned RtcStream`() = runTest {
        connectBrtc()

        val mockStream = buildMockMediaStream("stream-xyz")
        coEvery { mockPCManager.waitForPublishIceConnected() } just Runs
        every { mockPCManager.addLocalTracks(any()) } returns mockStream
        coEvery { mockPCManager.createPublishOffer() } returns "offer"
        coEvery { mockSignaling.offerSdp(any(), any()) } returns OfferSdpResult("answer")
        coEvery { mockPCManager.applyPublishAnswer(any()) } just Runs

        val stream = brtc.publish(audio = true, alias = "my-alias")

        assertEquals("my-alias", stream.alias)
    }

    @Test
    fun `publish waits for ICE then sends offer to signaling`() = runTest {
        connectBrtc()

        val mockStream = buildMockMediaStream("s1")
        coEvery { mockPCManager.waitForPublishIceConnected() } just Runs
        every { mockPCManager.addLocalTracks(any()) } returns mockStream
        coEvery { mockPCManager.createPublishOffer() } returns "local-offer"
        coEvery { mockSignaling.offerSdp("local-offer", "publish") } returns OfferSdpResult("remote-answer")
        coEvery { mockPCManager.applyPublishAnswer("remote-answer") } just Runs

        brtc.publish(audio = true)

        coVerify { mockPCManager.waitForPublishIceConnected() }
        coVerify { mockSignaling.offerSdp("local-offer", "publish") }
        coVerify { mockPCManager.applyPublishAnswer("remote-answer") }
    }

    @Test
    fun `publish with audio false does not add AUDIO to mediaTypes`() = runTest {
        connectBrtc()

        val mockStream = buildMockMediaStream("s2")
        coEvery { mockPCManager.waitForPublishIceConnected() } just Runs
        every { mockPCManager.addLocalTracks(audio = false) } returns mockStream
        coEvery { mockPCManager.createPublishOffer() } returns "offer"
        coEvery { mockSignaling.offerSdp(any(), any()) } returns OfferSdpResult("answer")
        coEvery { mockPCManager.applyPublishAnswer(any()) } just Runs

        val stream = brtc.publish(audio = false)

        assertFalse(stream.mediaTypes.contains(MediaType.AUDIO))
    }

    // -------------------------------------------------------------------------
    // unpublish()
    // -------------------------------------------------------------------------

    @Test(expected = BandwidthRTCError.NotConnected::class)
    fun `unpublish throws NotConnected when not connected`() = runTest {
        val mockStream = buildMockMediaStream("s1")
        val rtcStream = RtcStream(mediaStream = mockStream, mediaTypes = listOf(MediaType.AUDIO))
        brtc.unpublish(rtcStream)
    }

    @Test
    fun `unpublish removes local tracks and renegotiates`() = runTest {
        connectBrtc()

        val mockStream = buildMockMediaStream("stream-to-unpub")
        val rtcStream = RtcStream(mediaStream = mockStream, mediaTypes = listOf(MediaType.AUDIO))

        coEvery { mockPCManager.createPublishOffer() } returns "unpub-offer"
        coEvery { mockSignaling.offerSdp("unpub-offer", "publish") } returns OfferSdpResult("unpub-answer")
        coEvery { mockPCManager.applyPublishAnswer("unpub-answer") } just Runs

        brtc.unpublish(rtcStream)

        verify { mockPCManager.removeLocalTracks("stream-to-unpub") }
        coVerify { mockPCManager.createPublishOffer() }
        coVerify { mockSignaling.offerSdp("unpub-offer", "publish") }
        coVerify { mockPCManager.applyPublishAnswer("unpub-answer") }
    }

    // -------------------------------------------------------------------------
    // setMicEnabled()
    // -------------------------------------------------------------------------

    @Test
    fun `setMicEnabled delegates to peerConnectionManager`() {
        brtc.setMicEnabled(false)
        verify { mockPCManager.setAudioEnabled(false) }

        brtc.setMicEnabled(true)
        verify { mockPCManager.setAudioEnabled(true) }
    }

    @Test
    fun `setMicEnabled is a no-op when peerConnectionManager is null`() {
        val noPCMgr = BandwidthRTC(context = context, signaling = null, peerConnectionManager = null)
        noPCMgr.setMicEnabled(true) // should not throw
    }

    // -------------------------------------------------------------------------
    // sendDtmf()
    // -------------------------------------------------------------------------

    @Test
    fun `sendDtmf delegates to peerConnectionManager`() {
        brtc.sendDtmf("5")
        verify { mockPCManager.sendDtmf("5") }
    }

    // -------------------------------------------------------------------------
    // getCallStats()
    // -------------------------------------------------------------------------

    @Test
    fun `getCallStats returns empty snapshot when peerConnectionManager is null`() {
        val noPCMgr = BandwidthRTC(context = context, signaling = null, peerConnectionManager = null)

        var result: CallStatsSnapshot? = null
        noPCMgr.getCallStats(null) { result = it }

        assertNotNull(result)
        assertEquals(0, result!!.packetsReceived)
    }

    @Test
    fun `getCallStats delegates to peerConnectionManager`() {
        val snapshot = CallStatsSnapshot(packetsReceived = 100, bytesSent = 5000)
        every { mockPCManager.getCallStats(any(), any(), any(), any()) } answers {
            lastArg<(CallStatsSnapshot) -> Unit>().invoke(snapshot)
        }

        var result: CallStatsSnapshot? = null
        brtc.getCallStats(null) { result = it }

        assertEquals(100, result!!.packetsReceived)
        assertEquals(5000, result!!.bytesSent)
    }

    @Test
    fun `getCallStats fires onRemoteAudioLevel with samples derived from audioLevel`() {
        val snapshot = CallStatsSnapshot(audioLevel = 0.75)
        every { mockPCManager.getCallStats(any(), any(), any(), any()) } answers {
            lastArg<(CallStatsSnapshot) -> Unit>().invoke(snapshot)
        }

        var capturedSamples: FloatArray? = null
        brtc.onRemoteAudioLevel = { capturedSamples = it }

        brtc.getCallStats(null) {}

        assertNotNull(capturedSamples)
        assertEquals(9600, capturedSamples!!.size)
        assertEquals(0.75f, capturedSamples!![0], 0.001f)
    }

    @Test
    fun `getCallStats passes previous snapshot bytes to peerConnectionManager`() {
        val previous = CallStatsSnapshot(bytesReceived = 1000, bytesSent = 2000, timestamp = 1.0)
        every { mockPCManager.getCallStats(any(), any(), any(), any()) } answers {
            lastArg<(CallStatsSnapshot) -> Unit>().invoke(CallStatsSnapshot())
        }

        brtc.getCallStats(previous) {}

        verify {
            mockPCManager.getCallStats(
                previousInboundBytes = 1000,
                previousOutboundBytes = 2000,
                previousTimestamp = 1.0,
                completion = any()
            )
        }
    }

    // -------------------------------------------------------------------------
    // requestOutboundConnection()
    // -------------------------------------------------------------------------

    @Test(expected = BandwidthRTCError.NotConnected::class)
    fun `requestOutboundConnection throws NotConnected when not connected`() = runTest {
        brtc.requestOutboundConnection("123", EndpointType.PHONE_NUMBER)
    }

    @Test
    fun `requestOutboundConnection delegates to signaling`() = runTest {
        connectBrtc()
        coEvery { mockSignaling.requestOutboundConnection("123", EndpointType.PHONE_NUMBER) } returns
            OutboundConnectionResult(accepted = true)

        val result = brtc.requestOutboundConnection("123", EndpointType.PHONE_NUMBER)

        assertTrue(result.accepted)
        coVerify { mockSignaling.requestOutboundConnection("123", EndpointType.PHONE_NUMBER) }
    }

    // -------------------------------------------------------------------------
    // hangupConnection()
    // -------------------------------------------------------------------------

    @Test(expected = BandwidthRTCError.NotConnected::class)
    fun `hangupConnection throws NotConnected when not connected`() = runTest {
        brtc.hangupConnection("ep-1", EndpointType.ENDPOINT)
    }

    @Test
    fun `hangupConnection delegates to signaling`() = runTest {
        connectBrtc()
        coEvery { mockSignaling.hangupConnection("ep-1", EndpointType.ENDPOINT) } returns
            HangupResult(result = "ok")

        val result = brtc.hangupConnection("ep-1", EndpointType.ENDPOINT)

        assertEquals("ok", result.result)
        coVerify { mockSignaling.hangupConnection("ep-1", EndpointType.ENDPOINT) }
    }

    // -------------------------------------------------------------------------
    // Event handlers
    // -------------------------------------------------------------------------

    @Test
    fun `ready event fires onReady callback with parsed metadata`() = runTest {
        val eventHandlers = captureEventHandlers()

        var received: ReadyMetadata? = null
        brtc.onReady = { received = it }

        eventHandlers["ready"]?.invoke("""{"endpointId":"ep-evt","deviceId":"dev-evt"}""")

        assertNotNull(received)
        assertEquals("ep-evt", received?.endpointId)
        assertEquals("dev-evt", received?.deviceId)
    }

    @Test
    fun `ready event with empty data fires onReady with default metadata`() = runTest {
        val eventHandlers = captureEventHandlers()

        var called = false
        brtc.onReady = { called = true }

        eventHandlers["ready"]?.invoke("")

        assertTrue(called)
    }

    @Test
    fun `ready event with malformed JSON fires onReady with default metadata`() = runTest {
        val eventHandlers = captureEventHandlers()

        var called = false
        brtc.onReady = { called = true }

        eventHandlers["ready"]?.invoke("not-json")

        assertTrue(called)
    }

    @Test
    fun `close event sets isConnected to false`() = runTest {
        val eventHandlers = captureEventHandlers()

        assertTrue(brtc.isConnected)

        eventHandlers["close"]?.invoke("")

        assertFalse(brtc.isConnected)
    }

    @Test
    fun `subscribe ICE DISCONNECTED state fires onRemoteDisconnected`() = runTest {
        val iceHandler = captureIceHandler()

        var disconnected = false
        brtc.onRemoteDisconnected = { disconnected = true }

        iceHandler?.invoke(PeerConnection.IceConnectionState.DISCONNECTED)

        assertTrue(disconnected)
    }

    @Test
    fun `subscribe ICE FAILED state fires onRemoteDisconnected`() = runTest {
        val iceHandler = captureIceHandler()

        var disconnected = false
        brtc.onRemoteDisconnected = { disconnected = true }

        iceHandler?.invoke(PeerConnection.IceConnectionState.FAILED)

        assertTrue(disconnected)
    }

    @Test
    fun `subscribe ICE CONNECTED state does not fire onRemoteDisconnected`() = runTest {
        val iceHandler = captureIceHandler()

        var disconnected = false
        brtc.onRemoteDisconnected = { disconnected = true }

        iceHandler?.invoke(PeerConnection.IceConnectionState.CONNECTED)

        assertFalse(disconnected)
    }

    @Test
    fun `stream available callback wraps MediaStream in RtcStream`() = runTest {
        val streamHandler = captureStreamAvailableHandler()
        val mockStream = buildMockMediaStream("remote-stream")

        var receivedStream: RtcStream? = null
        brtc.onStreamAvailable = { receivedStream = it }

        streamHandler?.invoke(mockStream, listOf(MediaType.AUDIO))

        assertNotNull(receivedStream)
        assertEquals("remote-stream", receivedStream?.streamId)
        assertTrue(receivedStream?.mediaTypes?.contains(MediaType.AUDIO) == true)
    }

    @Test
    fun `stream unavailable callback forwards stream ID`() = runTest {
        val unavailableHandler = captureStreamUnavailableHandler()

        var removedId: String? = null
        brtc.onStreamUnavailable = { removedId = it }

        unavailableHandler?.invoke("stream-gone")

        assertEquals("stream-gone", removedId)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private suspend fun connectBrtc() {
        coEvery { mockSignaling.setMediaPreferences() } returns SetMediaPreferencesResult()
        brtc.connect(authParams)
    }

    private fun buildMockMediaStream(id: String): MediaStream {
        val stream = mockk<MediaStream>(relaxed = true)
        every { stream.id } returns id
        return stream
    }

    /** Connects brtc and captures all event handlers registered with the signaling mock. */
    private suspend fun captureEventHandlers(): MutableMap<String, (String) -> Unit> {
        val handlers = mutableMapOf<String, (String) -> Unit>()
        every { mockSignaling.onEvent(any(), any()) } answers {
            handlers[firstArg()] = secondArg()
        }
        coEvery { mockSignaling.setMediaPreferences() } returns SetMediaPreferencesResult()
        brtc.connect(authParams)
        return handlers
    }

    private suspend fun captureIceHandler(): ((PeerConnection.IceConnectionState) -> Unit)? {
        var iceHandler: ((PeerConnection.IceConnectionState) -> Unit)? = null
        every { mockPCManager.onSubscribingIceConnectionStateChange = any() } answers {
            iceHandler = firstArg()
        }
        every { mockPCManager.onSubscribingIceConnectionStateChange } returns null
        coEvery { mockSignaling.setMediaPreferences() } returns SetMediaPreferencesResult()
        brtc.connect(authParams)
        return iceHandler
    }

    private suspend fun captureStreamAvailableHandler(): ((MediaStream, List<MediaType>) -> Unit)? {
        var handler: ((MediaStream, List<MediaType>) -> Unit)? = null
        every { mockPCManager.onStreamAvailable = any() } answers { handler = firstArg() }
        every { mockPCManager.onStreamAvailable } returns null
        coEvery { mockSignaling.setMediaPreferences() } returns SetMediaPreferencesResult()
        brtc.connect(authParams)
        return handler
    }

    private suspend fun captureStreamUnavailableHandler(): ((String) -> Unit)? {
        var handler: ((String) -> Unit)? = null
        every { mockPCManager.onStreamUnavailable = any() } answers { handler = firstArg() }
        every { mockPCManager.onStreamUnavailable } returns null
        coEvery { mockSignaling.setMediaPreferences() } returns SetMediaPreferencesResult()
        brtc.connect(authParams)
        return handler
    }
}
