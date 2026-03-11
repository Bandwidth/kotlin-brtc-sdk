package com.bandwidth.rtc.webrtc

import android.content.Context
import com.bandwidth.rtc.types.BandwidthRTCError
import com.bandwidth.rtc.types.CallStatsSnapshot
import com.bandwidth.rtc.types.MediaType
import com.bandwidth.rtc.types.PeerConnectionType
import com.bandwidth.rtc.types.StreamMetadata
import io.mockk.MockKAnnotations
import io.mockk.answers
import io.mockk.any
import io.mockk.capture
import io.mockk.every
import io.mockk.firstArg
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DtmfSender
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStats
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RTCStatsReport
import org.webrtc.RtpSender
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.concurrent.ConcurrentHashMap

class PeerConnectionManagerTest {

    private lateinit var mockContext: Context
    private lateinit var mockFactory: PeerConnectionFactory
    private lateinit var mockPublishPC: PeerConnection
    private lateinit var mockSubscribePC: PeerConnection
    private lateinit var manager: PeerConnectionManager

    @Before
    fun setUp() {
        MockKAnnotations.init(this)

        // Mock PeerConnectionFactory static initialisation
        mockkStatic(PeerConnectionFactory::class)
        mockkStatic(PeerConnectionFactory.InitializationOptions::class)

        val mockOptionsBuilder = mockk<PeerConnectionFactory.InitializationOptions.Builder>(relaxed = true)
        val mockOptions = mockk<PeerConnectionFactory.InitializationOptions>(relaxed = true)
        every { PeerConnectionFactory.InitializationOptions.builder(any()) } returns mockOptionsBuilder
        every { mockOptionsBuilder.setEnableInternalTracer(any()) } returns mockOptionsBuilder
        every { mockOptionsBuilder.createInitializationOptions() } returns mockOptions
        every { PeerConnectionFactory.initialize(any()) } just runs

        val mockFactoryBuilder = mockk<PeerConnectionFactory.Builder>(relaxed = true)
        mockFactory = mockk(relaxed = true)
        every { PeerConnectionFactory.builder() } returns mockFactoryBuilder
        every { mockFactoryBuilder.createPeerConnectionFactory() } returns mockFactory

        mockContext = mockk(relaxed = true)
        mockPublishPC = mockk(relaxed = true)
        mockSubscribePC = mockk(relaxed = true)

        manager = PeerConnectionManager(mockContext, null)
    }

    @After
    fun tearDown() {
        unmockkAll()
        resetFactoryInitialized()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun resetFactoryInitialized() {
        try {
            val companion = PeerConnectionManager.Companion
            val field = companion.javaClass.getDeclaredField("factoryInitialized")
            field.isAccessible = true
            field.set(companion, false)
        } catch (_: Exception) {
            // Best-effort; tests are still isolated because mocks are rebuilt each setUp
        }
    }

    /** Sets up publishingPC and returns the captured PeerConnection.Observer. */
    private fun setupPublishPC(): PeerConnection.Observer {
        val observerSlot = slot<PeerConnection.Observer>()
        every {
            mockFactory.createPeerConnection(any<PeerConnection.RTCConfiguration>(), capture(observerSlot))
        } returns mockPublishPC
        manager.setupPublishingPeerConnection()
        return observerSlot.captured
    }

    /** Sets up subscribingPC and returns the captured PeerConnection.Observer. */
    private fun setupSubscribePC(): PeerConnection.Observer {
        val observerSlot = slot<PeerConnection.Observer>()
        every {
            mockFactory.createPeerConnection(any<PeerConnection.RTCConfiguration>(), capture(observerSlot))
        } returns mockSubscribePC
        manager.setupSubscribingPeerConnection()
        return observerSlot.captured
    }

    private fun setupBothPCs(): Pair<PeerConnection.Observer, PeerConnection.Observer> =
        setupPublishPC() to setupSubscribePC()

    private fun mockSetRemoteSuccess(pc: PeerConnection) {
        val slot = slot<SdpObserver>()
        every { pc.setRemoteDescription(capture(slot), any()) } answers { slot.captured.onSetSuccess() }
    }

    private fun mockCreateAnswerSuccess(pc: PeerConnection, sdpString: String) {
        val mockSdp = mockk<SessionDescription>()
        every { mockSdp.description } returns sdpString
        val setLocalSlot = slot<SdpObserver>()
        every { pc.setLocalDescription(capture(setLocalSlot), any()) } answers { setLocalSlot.captured.onSetSuccess() }
        val createSlot = slot<SdpObserver>()
        every { pc.createAnswer(capture(createSlot), any()) } answers { createSlot.captured.onCreateSuccess(mockSdp) }
    }

    private fun mockCreateOfferSuccess(pc: PeerConnection, sdpString: String) {
        val mockSdp = mockk<SessionDescription>()
        every { mockSdp.description } returns sdpString
        val setLocalSlot = slot<SdpObserver>()
        every { pc.setLocalDescription(capture(setLocalSlot), any()) } answers { setLocalSlot.captured.onSetSuccess() }
        val createSlot = slot<SdpObserver>()
        every { pc.createOffer(capture(createSlot), any()) } answers { createSlot.captured.onCreateSuccess(mockSdp) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun publishedStreams(): ConcurrentHashMap<String, MediaStream> {
        val field = PeerConnectionManager::class.java.getDeclaredField("publishedStreams")
        field.isAccessible = true
        return field.get(manager) as ConcurrentHashMap<String, MediaStream>
    }

    private fun addStreamDirectly(streamId: String, stream: MediaStream) {
        publishedStreams()[streamId] = stream
    }

    private fun emptyStatsReport(): RTCStatsReport =
        mockk<RTCStatsReport>(relaxed = true).also { every { it.statsMap } returns emptyMap() }

    // ── Construction ──────────────────────────────────────────────────────────

    @Test
    fun `PeerConnectionManager initializes without throwing`() {
        assertNotNull(manager)
    }

    @Test
    fun `subscribeSdpRevision is zero on construction`() {
        assertEquals(0, manager.subscribeSdpRevision)
    }

    @Test
    fun `all public callbacks are null on construction`() {
        assertNull(manager.onStreamAvailable)
        assertNull(manager.onStreamUnavailable)
        assertNull(manager.onSubscribingIceConnectionStateChange)
        assertNull(manager.onPublishingIceConnectionStateChange)
    }

    // ── setupPublishingPeerConnection ─────────────────────────────────────────

    @Test
    fun `setupPublishingPeerConnection returns the created PeerConnection`() {
        every { mockFactory.createPeerConnection(any<PeerConnection.RTCConfiguration>(), any()) } returns mockPublishPC

        val result = manager.setupPublishingPeerConnection()

        assertSame(mockPublishPC, result)
    }

    @Test(expected = BandwidthRTCError.ConnectionFailed::class)
    fun `setupPublishingPeerConnection throws ConnectionFailed when factory returns null`() {
        every { mockFactory.createPeerConnection(any<PeerConnection.RTCConfiguration>(), any()) } returns null

        manager.setupPublishingPeerConnection()
    }

    // ── setupSubscribingPeerConnection ────────────────────────────────────────

    @Test
    fun `setupSubscribingPeerConnection returns the created PeerConnection`() {
        every { mockFactory.createPeerConnection(any<PeerConnection.RTCConfiguration>(), any()) } returns mockSubscribePC

        val result = manager.setupSubscribingPeerConnection()

        assertSame(mockSubscribePC, result)
    }

    @Test(expected = BandwidthRTCError.ConnectionFailed::class)
    fun `setupSubscribingPeerConnection throws ConnectionFailed when factory returns null`() {
        every { mockFactory.createPeerConnection(any<PeerConnection.RTCConfiguration>(), any()) } returns null

        manager.setupSubscribingPeerConnection()
    }

    // ── waitForPublishIceConnected ────────────────────────────────────────────

    @Test
    fun `waitForPublishIceConnected returns immediately when ICE CONNECTED`() = runTest {
        val publishObserver = setupPublishPC()
        publishObserver.onIceConnectionChange(PeerConnection.IceConnectionState.CONNECTED)

        manager.waitForPublishIceConnected() // must not suspend indefinitely
    }

    @Test
    fun `waitForPublishIceConnected returns immediately when ICE COMPLETED`() = runTest {
        val publishObserver = setupPublishPC()
        publishObserver.onIceConnectionChange(PeerConnection.IceConnectionState.COMPLETED)

        manager.waitForPublishIceConnected()
    }

    // ── addLocalTracks ────────────────────────────────────────────────────────

    @Test
    fun `addLocalTracks with audio creates stream adds audio track and returns it`() {
        val mockStream = mockk<MediaStream>(relaxed = true)
        val mockAudioSource = mockk<AudioSource>(relaxed = true)
        val mockAudioTrack = mockk<AudioTrack>(relaxed = true)
        setupPublishPC()
        every { mockFactory.createLocalMediaStream(any()) } returns mockStream
        every { mockFactory.createAudioSource(any()) } returns mockAudioSource
        every { mockFactory.createAudioTrack(any(), any()) } returns mockAudioTrack

        val result = manager.addLocalTracks(audio = true)

        assertSame(mockStream, result)
        verify { mockStream.addTrack(mockAudioTrack) }
        verify { mockPublishPC.addTrack(mockAudioTrack, any()) }
    }

    @Test
    fun `addLocalTracks without audio returns stream and skips audio track`() {
        val mockStream = mockk<MediaStream>(relaxed = true)
        setupPublishPC()
        every { mockFactory.createLocalMediaStream(any()) } returns mockStream

        val result = manager.addLocalTracks(audio = false)

        assertSame(mockStream, result)
        verify(exactly = 0) { mockStream.addTrack(any<AudioTrack>()) }
    }

    @Test(expected = BandwidthRTCError.PublishFailed::class)
    fun `addLocalTracks throws PublishFailed when peer connection not set up`() {
        manager.addLocalTracks(audio = true)
    }

    @Test
    fun `addLocalTracks stores stream in publishedStreams`() {
        val mockStream = mockk<MediaStream>(relaxed = true)
        setupPublishPC()
        every { mockFactory.createLocalMediaStream(any()) } returns mockStream

        manager.addLocalTracks(audio = false)

        assertTrue(publishedStreams().containsValue(mockStream))
    }

    // ── removeLocalTracks ─────────────────────────────────────────────────────

    @Test
    fun `removeLocalTracks removes matching sender and disables audio track`() {
        setupPublishPC()
        val mockStream = mockk<MediaStream>(relaxed = true)
        val mockAudioTrack = mockk<AudioTrack>(relaxed = true)
        val mockSender = mockk<RtpSender>(relaxed = true)
        every { mockAudioTrack.id() } returns "audio-1"
        every { mockStream.audioTracks } returns mutableListOf(mockAudioTrack)
        every { mockSender.track() } returns mockAudioTrack
        every { mockPublishPC.senders } returns listOf(mockSender)
        addStreamDirectly("stream-1", mockStream)

        manager.removeLocalTracks("stream-1")

        verify { mockPublishPC.removeTrack(mockSender) }
        verify { mockAudioTrack.setEnabled(false) }
        assertFalse(publishedStreams().containsKey("stream-1"))
    }

    @Test
    fun `removeLocalTracks with unknown streamId does nothing`() {
        setupPublishPC()
        manager.removeLocalTracks("nonexistent-id") // must not throw
    }

    @Test
    fun `removeLocalTracks when no publishing PC does nothing`() {
        manager.removeLocalTracks("stream-id") // must not throw
    }

    // ── createPublishOffer ────────────────────────────────────────────────────

    @Test
    fun `createPublishOffer returns SDP offer string`() = runTest {
        setupPublishPC()
        mockCreateOfferSuccess(mockPublishPC, "v=0\r\noffer-sdp")

        val result = manager.createPublishOffer()

        assertEquals("v=0\r\noffer-sdp", result)
    }

    @Test
    fun `createPublishOffer throws PublishFailed when peer connection not available`() = runTest {
        try {
            manager.createPublishOffer()
            fail("Expected BandwidthRTCError.PublishFailed")
        } catch (e: BandwidthRTCError.PublishFailed) {
            // expected
        }
    }

    @Test
    fun `createPublishOffer throws SdpNegotiationFailed when createOffer callback fails`() = runTest {
        setupPublishPC()
        val slot = slot<SdpObserver>()
        every { mockPublishPC.createOffer(capture(slot), any()) } answers {
            slot.captured.onCreateFailure("network error")
        }

        try {
            manager.createPublishOffer()
            fail("Expected BandwidthRTCError.SdpNegotiationFailed")
        } catch (e: BandwidthRTCError.SdpNegotiationFailed) {
            assertTrue(e.message!!.contains("network error"))
        }
    }

    @Test
    fun `createPublishOffer throws SdpNegotiationFailed when createOffer returns null SDP`() = runTest {
        setupPublishPC()
        val slot = slot<SdpObserver>()
        every { mockPublishPC.createOffer(capture(slot), any()) } answers {
            slot.captured.onCreateSuccess(null)
        }

        try {
            manager.createPublishOffer()
            fail("Expected BandwidthRTCError.SdpNegotiationFailed")
        } catch (e: BandwidthRTCError.SdpNegotiationFailed) {
            assertTrue(e.message!!.contains("No SDP offer generated"))
        }
    }

    // ── applyPublishAnswer ────────────────────────────────────────────────────

    @Test
    fun `applyPublishAnswer calls setRemoteDescription on publish PC`() = runTest {
        setupPublishPC()
        mockSetRemoteSuccess(mockPublishPC)

        manager.applyPublishAnswer("v=0\r\nanswer-sdp")

        verify { mockPublishPC.setRemoteDescription(any(), any()) }
    }

    @Test
    fun `applyPublishAnswer throws PublishFailed when peer connection not available`() = runTest {
        try {
            manager.applyPublishAnswer("answer-sdp")
            fail("Expected BandwidthRTCError.PublishFailed")
        } catch (e: BandwidthRTCError.PublishFailed) {
            // expected
        }
    }

    @Test
    fun `applyPublishAnswer throws SdpNegotiationFailed when setRemoteDescription fails`() = runTest {
        setupPublishPC()
        val slot = slot<SdpObserver>()
        every { mockPublishPC.setRemoteDescription(capture(slot), any()) } answers {
            slot.captured.onSetFailure("remote description failure")
        }

        try {
            manager.applyPublishAnswer("answer-sdp")
            fail("Expected BandwidthRTCError.SdpNegotiationFailed")
        } catch (e: BandwidthRTCError.SdpNegotiationFailed) {
            assertTrue(e.message!!.contains("remote description failure"))
        }
    }

    // ── answerInitialOffer ────────────────────────────────────────────────────

    @Test
    fun `answerInitialOffer for PUBLISH returns SDP answer`() = runTest {
        setupPublishPC()
        mockSetRemoteSuccess(mockPublishPC)
        mockCreateAnswerSuccess(mockPublishPC, "publish-answer-sdp")

        val result = manager.answerInitialOffer("offer-sdp", PeerConnectionType.PUBLISH)

        assertEquals("publish-answer-sdp", result)
    }

    @Test
    fun `answerInitialOffer for SUBSCRIBE returns SDP answer`() = runTest {
        setupBothPCs()
        mockSetRemoteSuccess(mockSubscribePC)
        mockCreateAnswerSuccess(mockSubscribePC, "subscribe-answer-sdp")

        val result = manager.answerInitialOffer("offer-sdp", PeerConnectionType.SUBSCRIBE)

        assertEquals("subscribe-answer-sdp", result)
    }

    @Test
    fun `answerInitialOffer throws SdpNegotiationFailed when PUBLISH PC not available`() = runTest {
        try {
            manager.answerInitialOffer("offer-sdp", PeerConnectionType.PUBLISH)
            fail("Expected BandwidthRTCError.SdpNegotiationFailed")
        } catch (e: BandwidthRTCError.SdpNegotiationFailed) {
            // expected
        }
    }

    @Test
    fun `answerInitialOffer throws SdpNegotiationFailed when SUBSCRIBE PC not available`() = runTest {
        setupPublishPC() // only publish is set up
        try {
            manager.answerInitialOffer("offer-sdp", PeerConnectionType.SUBSCRIBE)
            fail("Expected BandwidthRTCError.SdpNegotiationFailed")
        } catch (e: BandwidthRTCError.SdpNegotiationFailed) {
            // expected
        }
    }

    @Test
    fun `answerInitialOffer throws SdpNegotiationFailed when setRemoteDescription fails`() = runTest {
        setupPublishPC()
        val slot = slot<SdpObserver>()
        every { mockPublishPC.setRemoteDescription(capture(slot), any()) } answers {
            slot.captured.onSetFailure("remote error")
        }

        try {
            manager.answerInitialOffer("offer-sdp", PeerConnectionType.PUBLISH)
            fail("Expected BandwidthRTCError.SdpNegotiationFailed")
        } catch (e: BandwidthRTCError.SdpNegotiationFailed) {
            assertTrue(e.message!!.contains("remote error"))
        }
    }

    @Test
    fun `answerInitialOffer throws SdpNegotiationFailed when createAnswer returns null SDP`() = runTest {
        setupPublishPC()
        mockSetRemoteSuccess(mockPublishPC)
        val slot = slot<SdpObserver>()
        every { mockPublishPC.createAnswer(capture(slot), any()) } answers {
            slot.captured.onCreateSuccess(null)
        }

        try {
            manager.answerInitialOffer("offer-sdp", PeerConnectionType.PUBLISH)
            fail("Expected BandwidthRTCError.SdpNegotiationFailed")
        } catch (e: BandwidthRTCError.SdpNegotiationFailed) {
            assertTrue(e.message!!.contains("No SDP answer generated"))
        }
    }

    // ── handleSubscribeSdpOffer ───────────────────────────────────────────────

    @Test
    fun `handleSubscribeSdpOffer returns SDP answer`() = runTest {
        setupBothPCs()
        mockSetRemoteSuccess(mockSubscribePC)
        mockCreateAnswerSuccess(mockSubscribePC, "renegotiation-answer-sdp")

        val result = manager.handleSubscribeSdpOffer("offer-sdp", 1, null)

        assertEquals("renegotiation-answer-sdp", result)
    }

    @Test
    fun `handleSubscribeSdpOffer updates subscribeSdpRevision`() = runTest {
        setupBothPCs()
        mockSetRemoteSuccess(mockSubscribePC)
        mockCreateAnswerSuccess(mockSubscribePC, "answer")

        manager.handleSubscribeSdpOffer("offer-sdp", 7, null)

        assertEquals(7, manager.subscribeSdpRevision)
    }

    @Test
    fun `handleSubscribeSdpOffer with null revision auto-increments from zero`() = runTest {
        setupBothPCs()
        mockSetRemoteSuccess(mockSubscribePC)
        mockCreateAnswerSuccess(mockSubscribePC, "answer")

        manager.handleSubscribeSdpOffer("offer-sdp", null, null)

        assertEquals(1, manager.subscribeSdpRevision)
    }

    @Test
    fun `handleSubscribeSdpOffer rejects stale offer revision`() = runTest {
        setupBothPCs()
        mockSetRemoteSuccess(mockSubscribePC)
        mockCreateAnswerSuccess(mockSubscribePC, "answer")
        manager.handleSubscribeSdpOffer("offer-sdp", 5, null) // revision now at 5

        try {
            manager.handleSubscribeSdpOffer("offer-sdp", 3, null) // stale
            fail("Expected BandwidthRTCError.SdpNegotiationFailed")
        } catch (e: BandwidthRTCError.SdpNegotiationFailed) {
            assertTrue(e.message!!.contains("Stale SDP offer"))
        }
    }

    @Test
    fun `handleSubscribeSdpOffer throws SdpNegotiationFailed when subscribe PC not available`() = runTest {
        try {
            manager.handleSubscribeSdpOffer("offer-sdp", 1, null)
            fail("Expected BandwidthRTCError.SdpNegotiationFailed")
        } catch (e: BandwidthRTCError.SdpNegotiationFailed) {
            // expected
        }
    }

    @Test
    fun `handleSubscribeSdpOffer stores provided stream metadata`() = runTest {
        setupBothPCs()
        mockSetRemoteSuccess(mockSubscribePC)
        mockCreateAnswerSuccess(mockSubscribePC, "answer")
        val metadata = mapOf(
            "ep-1" to StreamMetadata(endpointId = "ep-1", mediaTypes = listOf(MediaType.AUDIO))
        )

        manager.handleSubscribeSdpOffer("offer-sdp", 1, metadata)

        val field = PeerConnectionManager::class.java.getDeclaredField("subscribedStreamMetadata")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stored = field.get(manager) as ConcurrentHashMap<String, StreamMetadata>
        assertTrue(stored.containsKey("ep-1"))
    }

    // ── setAudioEnabled ───────────────────────────────────────────────────────

    @Test
    fun `setAudioEnabled true enables all audio tracks in published streams`() {
        val mockStream = mockk<MediaStream>(relaxed = true)
        val mockAudioTrack = mockk<AudioTrack>(relaxed = true)
        every { mockStream.audioTracks } returns mutableListOf(mockAudioTrack)
        addStreamDirectly("stream-1", mockStream)

        manager.setAudioEnabled(true)

        verify { mockAudioTrack.setEnabled(true) }
    }

    @Test
    fun `setAudioEnabled false disables all audio tracks in published streams`() {
        val mockStream = mockk<MediaStream>(relaxed = true)
        val mockAudioTrack = mockk<AudioTrack>(relaxed = true)
        every { mockStream.audioTracks } returns mutableListOf(mockAudioTrack)
        addStreamDirectly("stream-1", mockStream)

        manager.setAudioEnabled(false)

        verify { mockAudioTrack.setEnabled(false) }
    }

    @Test
    fun `setAudioEnabled with no published streams does not throw`() {
        manager.setAudioEnabled(true) // must not throw
    }

    @Test
    fun `setAudioEnabled affects all published streams`() {
        val mockStream1 = mockk<MediaStream>(relaxed = true)
        val mockStream2 = mockk<MediaStream>(relaxed = true)
        val mockTrack1 = mockk<AudioTrack>(relaxed = true)
        val mockTrack2 = mockk<AudioTrack>(relaxed = true)
        every { mockStream1.audioTracks } returns mutableListOf(mockTrack1)
        every { mockStream2.audioTracks } returns mutableListOf(mockTrack2)
        addStreamDirectly("stream-1", mockStream1)
        addStreamDirectly("stream-2", mockStream2)

        manager.setAudioEnabled(false)

        verify { mockTrack1.setEnabled(false) }
        verify { mockTrack2.setEnabled(false) }
    }

    // ── sendDtmf ──────────────────────────────────────────────────────────────

    @Test
    fun `sendDtmf inserts tone on the first audio sender with a DtmfSender`() {
        setupPublishPC()
        val mockDtmfSender = mockk<DtmfSender>(relaxed = true)
        val mockSender = mockk<RtpSender>(relaxed = true)
        val mockTrack = mockk<AudioTrack>(relaxed = true)
        every { mockSender.track() } returns mockTrack
        every { mockTrack.kind() } returns "audio"
        every { mockSender.dtmf() } returns mockDtmfSender
        every { mockPublishPC.senders } returns listOf(mockSender)

        manager.sendDtmf("5")

        verify { mockDtmfSender.insertDtmf("5", 100, 50) }
    }

    @Test
    fun `sendDtmf when no publishing PC does not throw`() {
        manager.sendDtmf("5") // must not throw
    }

    @Test
    fun `sendDtmf when no senders does not throw`() {
        setupPublishPC()
        every { mockPublishPC.senders } returns emptyList()

        manager.sendDtmf("5") // must not throw
    }

    @Test
    fun `sendDtmf skips senders where dtmf returns null`() {
        setupPublishPC()
        val mockSender = mockk<RtpSender>(relaxed = true)
        val mockTrack = mockk<AudioTrack>(relaxed = true)
        every { mockSender.track() } returns mockTrack
        every { mockTrack.kind() } returns "audio"
        every { mockSender.dtmf() } returns null
        every { mockPublishPC.senders } returns listOf(mockSender)

        manager.sendDtmf("5") // must not throw
    }

    // ── cleanup ───────────────────────────────────────────────────────────────

    @Test
    fun `cleanup closes both peer connections`() {
        setupBothPCs()

        manager.cleanup()

        verify { mockPublishPC.close() }
        verify { mockSubscribePC.close() }
    }

    @Test
    fun `cleanup resets subscribeSdpRevision to zero`() = runTest {
        setupBothPCs()
        mockSetRemoteSuccess(mockSubscribePC)
        mockCreateAnswerSuccess(mockSubscribePC, "answer")
        manager.handleSubscribeSdpOffer("offer-sdp", 4, null)
        assertEquals(4, manager.subscribeSdpRevision)

        manager.cleanup()

        assertEquals(0, manager.subscribeSdpRevision)
    }

    @Test
    fun `cleanup clears all published streams`() {
        val mockStream = mockk<MediaStream>(relaxed = true)
        every { mockStream.audioTracks } returns mutableListOf()
        addStreamDirectly("stream-1", mockStream)
        assertTrue(publishedStreams().isNotEmpty())

        manager.cleanup()

        assertTrue(publishedStreams().isEmpty())
    }

    @Test
    fun `cleanup disables published audio tracks before clearing`() {
        val mockStream = mockk<MediaStream>(relaxed = true)
        val mockAudioTrack = mockk<AudioTrack>(relaxed = true)
        every { mockStream.audioTracks } returns mutableListOf(mockAudioTrack)
        addStreamDirectly("stream-1", mockStream)

        manager.cleanup()

        verify { mockAudioTrack.setEnabled(false) }
    }

    @Test
    fun `cleanup closes data channels received via onDataChannel`() {
        val mockHeartbeatDC = mockk<DataChannel>(relaxed = true)
        val mockDiagnosticsDC = mockk<DataChannel>(relaxed = true)
        every { mockHeartbeatDC.label() } returns "__heartbeat__"
        every { mockDiagnosticsDC.label() } returns "__diagnostics__"
        val publishObserver = setupPublishPC()
        publishObserver.onDataChannel(mockHeartbeatDC)
        publishObserver.onDataChannel(mockDiagnosticsDC)

        manager.cleanup()

        verify { mockHeartbeatDC.close() }
        verify { mockDiagnosticsDC.close() }
    }

    @Test
    fun `cleanup when no peer connections does not throw`() {
        manager.cleanup() // must not throw
    }

    // ── getCallStats ──────────────────────────────────────────────────────────

    @Test
    fun `getCallStats invokes completion immediately when no peer connections`() {
        var called = false
        manager.getCallStats(0, 0, 0.0) { called = true }
        assertTrue(called)
    }

    @Test
    fun `getCallStats returns non-null snapshot when no peer connections`() {
        var snapshot: CallStatsSnapshot? = null
        manager.getCallStats(0, 0, 0.0) { snapshot = it }
        assertNotNull(snapshot)
    }

    @Test
    fun `getCallStats invokes completion after collecting stats from both PCs`() {
        setupBothPCs()
        val report = emptyStatsReport()
        every { mockSubscribePC.getStats(any<RTCStatsCollectorCallback>()) } answers {
            firstArg<RTCStatsCollectorCallback>().onStatsDelivered(report)
        }
        every { mockPublishPC.getStats(any<RTCStatsCollectorCallback>()) } answers {
            firstArg<RTCStatsCollectorCallback>().onStatsDelivered(report)
        }

        var called = false
        manager.getCallStats(0, 0, 0.0) { called = true }

        assertTrue(called)
    }

    @Test
    fun `getCallStats populates inbound stats from subscribe PC`() {
        setupBothPCs()
        val inboundStat = mockk<RTCStats>()
        every { inboundStat.type } returns "inbound-rtp"
        every { inboundStat.members } returns mapOf(
            "kind" to "audio",
            "packetsReceived" to 500,
            "packetsLost" to 10,
            "bytesReceived" to 50_000,
            "jitter" to 0.02,
            "audioLevel" to 0.8
        )
        val subReport = mockk<RTCStatsReport>(relaxed = true)
        every { subReport.statsMap } returns mapOf("stat-1" to inboundStat)
        every { mockSubscribePC.getStats(any<RTCStatsCollectorCallback>()) } answers {
            firstArg<RTCStatsCollectorCallback>().onStatsDelivered(subReport)
        }
        every { mockPublishPC.getStats(any<RTCStatsCollectorCallback>()) } answers {
            firstArg<RTCStatsCollectorCallback>().onStatsDelivered(emptyStatsReport())
        }

        var snapshot: CallStatsSnapshot? = null
        manager.getCallStats(0, 0, 0.0) { snapshot = it }

        assertNotNull(snapshot)
        assertEquals(500, snapshot!!.packetsReceived)
        assertEquals(10, snapshot!!.packetsLost)
        assertEquals(50_000, snapshot!!.bytesReceived)
    }

    @Test
    fun `getCallStats populates outbound stats from publish PC`() {
        setupBothPCs()
        val outboundStat = mockk<RTCStats>()
        every { outboundStat.type } returns "outbound-rtp"
        every { outboundStat.members } returns mapOf(
            "kind" to "audio",
            "packetsSent" to 300,
            "bytesSent" to 30_000
        )
        val pubReport = mockk<RTCStatsReport>(relaxed = true)
        every { pubReport.statsMap } returns mapOf("stat-1" to outboundStat)
        every { mockSubscribePC.getStats(any<RTCStatsCollectorCallback>()) } answers {
            firstArg<RTCStatsCollectorCallback>().onStatsDelivered(emptyStatsReport())
        }
        every { mockPublishPC.getStats(any<RTCStatsCollectorCallback>()) } answers {
            firstArg<RTCStatsCollectorCallback>().onStatsDelivered(pubReport)
        }

        var snapshot: CallStatsSnapshot? = null
        manager.getCallStats(0, 0, 0.0) { snapshot = it }

        assertNotNull(snapshot)
        assertEquals(300, snapshot!!.packetsSent)
        assertEquals(30_000, snapshot!!.bytesSent)
    }

    @Test
    fun `getCallStats calculates bitrates when previousTimestamp is provided`() {
        setupBothPCs()
        val inboundStat = mockk<RTCStats>()
        every { inboundStat.type } returns "inbound-rtp"
        every { inboundStat.members } returns mapOf(
            "kind" to "audio",
            "packetsReceived" to 0,
            "packetsLost" to 0,
            "bytesReceived" to 10_800, // delta = 10_800 - 800 = 10_000 bytes
            "jitter" to 0.0,
            "audioLevel" to 0.0
        )
        val subReport = mockk<RTCStatsReport>(relaxed = true)
        every { subReport.statsMap } returns mapOf("stat-1" to inboundStat)
        every { mockSubscribePC.getStats(any<RTCStatsCollectorCallback>()) } answers {
            firstArg<RTCStatsCollectorCallback>().onStatsDelivered(subReport)
        }
        every { mockPublishPC.getStats(any<RTCStatsCollectorCallback>()) } answers {
            firstArg<RTCStatsCollectorCallback>().onStatsDelivered(emptyStatsReport())
        }

        var snapshot: CallStatsSnapshot? = null
        // previous: 800 in, 0 out; timestamp 1 second ago
        val previousTs = System.currentTimeMillis() / 1000.0 - 1.0
        manager.getCallStats(800, 0, previousTs) { snapshot = it }

        assertNotNull(snapshot)
        // inboundBitrate = (10_000 bytes * 8 bits) / ~1 second = ~80_000 bps
        assertTrue("Expected positive inbound bitrate", snapshot!!.inboundBitrate > 0)
    }

    // ── Callback / Observer integration ──────────────────────────────────────

    @Test
    fun `onStreamAvailable is called when subscribe observer fires onAddStream`() {
        val receivedStreams = mutableListOf<MediaStream>()
        manager.onStreamAvailable = { stream, _ -> receivedStreams.add(stream) }
        val mockStream = mockk<MediaStream>(relaxed = true)
        every { mockStream.audioTracks } returns mutableListOf(mockk(relaxed = true))
        every { mockStream.videoTracks } returns mutableListOf()
        val (_, subscribeObserver) = setupBothPCs()

        subscribeObserver.onAddStream(mockStream)

        assertEquals(1, receivedStreams.size)
        assertSame(mockStream, receivedStreams[0])
    }

    @Test
    fun `onStreamAvailable includes AUDIO in mediaTypes when stream has audio tracks`() {
        var receivedTypes: List<MediaType>? = null
        manager.onStreamAvailable = { _, types -> receivedTypes = types }
        val mockStream = mockk<MediaStream>(relaxed = true)
        every { mockStream.audioTracks } returns mutableListOf(mockk(relaxed = true))
        every { mockStream.videoTracks } returns mutableListOf()
        val (_, subscribeObserver) = setupBothPCs()

        subscribeObserver.onAddStream(mockStream)

        assertNotNull(receivedTypes)
        assertTrue(receivedTypes!!.contains(MediaType.AUDIO))
    }

    @Test
    fun `onStreamUnavailable is called with the stream ID when stream is removed`() {
        val removedIds = mutableListOf<String>()
        manager.onStreamUnavailable = { removedIds.add(it) }
        val mockStream = mockk<MediaStream>(relaxed = true)
        every { mockStream.id } returns "stream-abc"
        val (_, subscribeObserver) = setupBothPCs()

        subscribeObserver.onRemoveStream(mockStream)

        assertEquals(listOf("stream-abc"), removedIds)
    }

    @Test
    fun `onSubscribingIceConnectionStateChange is notified on subscribe ICE state changes`() {
        val states = mutableListOf<PeerConnection.IceConnectionState>()
        manager.onSubscribingIceConnectionStateChange = { states.add(it) }
        val (_, subscribeObserver) = setupBothPCs()

        subscribeObserver.onIceConnectionChange(PeerConnection.IceConnectionState.CONNECTED)
        subscribeObserver.onIceConnectionChange(PeerConnection.IceConnectionState.DISCONNECTED)

        assertEquals(
            listOf(PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.DISCONNECTED),
            states
        )
    }

    @Test
    fun `onPublishingIceConnectionStateChange is notified on publish ICE state changes`() {
        val states = mutableListOf<PeerConnection.IceConnectionState>()
        manager.onPublishingIceConnectionStateChange = { states.add(it) }
        val publishObserver = setupPublishPC()

        publishObserver.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)

        assertEquals(listOf(PeerConnection.IceConnectionState.FAILED), states)
    }

    @Test
    fun `subscribe ICE state changes do not trigger publishing ICE callback`() {
        val publishStates = mutableListOf<PeerConnection.IceConnectionState>()
        manager.onPublishingIceConnectionStateChange = { publishStates.add(it) }
        val (_, subscribeObserver) = setupBothPCs()

        subscribeObserver.onIceConnectionChange(PeerConnection.IceConnectionState.CONNECTED)

        assertTrue("Publish ICE callback must not fire for subscribe state changes", publishStates.isEmpty())
    }

    @Test
    fun `publish ICE state changes do not trigger subscribing ICE callback`() {
        val subscribeStates = mutableListOf<PeerConnection.IceConnectionState>()
        manager.onSubscribingIceConnectionStateChange = { subscribeStates.add(it) }
        val publishObserver = setupPublishPC()

        publishObserver.onIceConnectionChange(PeerConnection.IceConnectionState.CONNECTED)

        assertTrue("Subscribe ICE callback must not fire for publish state changes", subscribeStates.isEmpty())
    }

    @Test
    fun `onDataChannel assigns heartbeat and diagnostics data channels for publish PC`() {
        val mockHeartbeatDC = mockk<DataChannel>(relaxed = true)
        val mockDiagnosticsDC = mockk<DataChannel>(relaxed = true)
        every { mockHeartbeatDC.label() } returns "__heartbeat__"
        every { mockDiagnosticsDC.label() } returns "__diagnostics__"
        val publishObserver = setupPublishPC()

        publishObserver.onDataChannel(mockHeartbeatDC)
        publishObserver.onDataChannel(mockDiagnosticsDC)

        // Verify channels are closed on cleanup (proving they were stored)
        manager.cleanup()
        verify { mockHeartbeatDC.close() }
        verify { mockDiagnosticsDC.close() }
    }

    @Test
    fun `onAddStream with null stream does not invoke onStreamAvailable`() {
        var called = false
        manager.onStreamAvailable = { _, _ -> called = true }
        val (_, subscribeObserver) = setupBothPCs()

        subscribeObserver.onAddStream(null)

        assertFalse(called)
    }

    @Test
    fun `onRemoveStream with null stream does not invoke onStreamUnavailable`() {
        var called = false
        manager.onStreamUnavailable = { called = true }
        val (_, subscribeObserver) = setupBothPCs()

        subscribeObserver.onRemoveStream(null)

        assertFalse(called)
    }
}
