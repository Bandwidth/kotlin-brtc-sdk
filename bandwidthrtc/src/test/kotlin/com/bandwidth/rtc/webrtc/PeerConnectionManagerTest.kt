package com.bandwidth.rtc.webrtc

import android.content.Context
import com.bandwidth.rtc.types.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.webrtc.*

class PeerConnectionManagerTest {

    private lateinit var mockContext: Context
    private lateinit var mockFactory: PeerConnectionFactory
    private lateinit var mockPublishPc: PeerConnection
    private lateinit var mockSubscribePc: PeerConnection
    private lateinit var manager: PeerConnectionManager

    @Before
    fun setUp() {
        mockContext = mockk(relaxed = true)
        mockFactory = mockk(relaxed = true)
        mockPublishPc = mockk(relaxed = true)
        mockSubscribePc = mockk(relaxed = true)

        mockkStatic(PeerConnectionFactory::class)
        // Prevent native factory initialization from running (only needed on first test;
        // after that the companion flag is true and this block is skipped automatically)
        every { PeerConnectionFactory.initialize(any()) } just Runs

        val mockBuilder = mockk<PeerConnectionFactory.Builder>(relaxed = true)
        every { PeerConnectionFactory.builder() } returns mockBuilder
        every { mockBuilder.setAudioDeviceModule(any()) } returns mockBuilder
        every { mockBuilder.createPeerConnectionFactory() } returns mockFactory

        // First createPeerConnection call → publish PC, second → subscribe PC
        every {
            mockFactory.createPeerConnection(any<PeerConnection.RTCConfiguration>(), any<PeerConnection.Observer>())
        } returnsMany listOf(mockPublishPc, mockSubscribePc)

        manager = PeerConnectionManager(mockContext, null)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // -------------------------------------------------------------------------
    // setupPublishingPeerConnection()
    // -------------------------------------------------------------------------

    @Test
    fun `setupPublishingPeerConnection returns the created peer connection`() {
        val pc = manager.setupPublishingPeerConnection()

        assertEquals(mockPublishPc, pc)
        verify { mockFactory.createPeerConnection(any<PeerConnection.RTCConfiguration>(), any<PeerConnection.Observer>()) }
    }

    @Test(expected = BandwidthRTCError.ConnectionFailed::class)
    fun `setupPublishingPeerConnection throws ConnectionFailed when factory returns null`() {
        every {
            mockFactory.createPeerConnection(any<PeerConnection.RTCConfiguration>(), any<PeerConnection.Observer>())
        } returns null

        manager.setupPublishingPeerConnection()
    }

    // -------------------------------------------------------------------------
    // setupSubscribingPeerConnection()
    // -------------------------------------------------------------------------

    @Test
    fun `setupSubscribingPeerConnection returns the created peer connection`() {
        manager.setupPublishingPeerConnection()
        val pc = manager.setupSubscribingPeerConnection()

        assertEquals(mockSubscribePc, pc)
    }

    @Test(expected = BandwidthRTCError.ConnectionFailed::class)
    fun `setupSubscribingPeerConnection throws ConnectionFailed when factory returns null`() {
        manager.setupPublishingPeerConnection()
        every {
            mockFactory.createPeerConnection(any<PeerConnection.RTCConfiguration>(), any<PeerConnection.Observer>())
        } returns null

        manager.setupSubscribingPeerConnection()
    }

    // -------------------------------------------------------------------------
    // waitForPublishIceConnected()
    // -------------------------------------------------------------------------

    @Test
    fun `waitForPublishIceConnected returns immediately when already connected`() = runTest {
        setPublishIceConnected(true)

        // Should complete without looping
        manager.waitForPublishIceConnected()
    }

    // -------------------------------------------------------------------------
    // answerInitialOffer()
    // -------------------------------------------------------------------------

    @Test(expected = BandwidthRTCError.SdpNegotiationFailed::class)
    fun `answerInitialOffer throws SdpNegotiationFailed when PUBLISH PC not set up`() = runTest {
        manager.answerInitialOffer("offer", PeerConnectionType.PUBLISH)
    }

    @Test(expected = BandwidthRTCError.SdpNegotiationFailed::class)
    fun `answerInitialOffer throws SdpNegotiationFailed when SUBSCRIBE PC not set up`() = runTest {
        manager.answerInitialOffer("offer", PeerConnectionType.SUBSCRIBE)
    }

    @Test
    fun `answerInitialOffer for PUBLISH returns answer SDP`() = runTest {
        manager.setupPublishingPeerConnection()

        val mockAnswer = buildRealSdp("pub-answer-sdp")
        stubSdpAnswerFlow(mockPublishPc, mockAnswer)

        val result = manager.answerInitialOffer("pub-offer", PeerConnectionType.PUBLISH)

        assertEquals("pub-answer-sdp", result)
    }

    @Test
    fun `answerInitialOffer for SUBSCRIBE returns answer SDP`() = runTest {
        manager.setupPublishingPeerConnection()
        manager.setupSubscribingPeerConnection()

        val mockAnswer = buildRealSdp("sub-answer-sdp")
        stubSdpAnswerFlow(mockSubscribePc, mockAnswer)

        val result = manager.answerInitialOffer("sub-offer", PeerConnectionType.SUBSCRIBE)

        assertEquals("sub-answer-sdp", result)
    }

    @Test
    fun `answerInitialOffer throws on setRemoteDescription failure`() = runTest {
        manager.setupPublishingPeerConnection()

        every { mockPublishPc.setRemoteDescription(any(), any()) } answers {
            firstArg<SdpObserver>().onSetFailure("remote desc error")
        }

        try {
            manager.answerInitialOffer("offer", PeerConnectionType.PUBLISH)
            fail("Expected SdpNegotiationFailed")
        } catch (e: BandwidthRTCError.SdpNegotiationFailed) {
            assertTrue(e.message!!.contains("remote desc error"))
        }
    }

    @Test
    fun `answerInitialOffer throws on createAnswer failure`() = runTest {
        manager.setupPublishingPeerConnection()

        every { mockPublishPc.setRemoteDescription(any(), any()) } answers {
            firstArg<SdpObserver>().onSetSuccess()
        }
        every { mockPublishPc.createAnswer(any(), any()) } answers {
            firstArg<SdpObserver>().onCreateFailure("create answer error")
        }

        try {
            manager.answerInitialOffer("offer", PeerConnectionType.PUBLISH)
            fail("Expected SdpNegotiationFailed")
        } catch (e: BandwidthRTCError.SdpNegotiationFailed) {
            assertTrue(e.message!!.contains("create answer error"))
        }
    }

    @Test
    fun `answerInitialOffer throws when createAnswer returns null SDP`() = runTest {
        manager.setupPublishingPeerConnection()

        every { mockPublishPc.setRemoteDescription(any(), any()) } answers {
            firstArg<SdpObserver>().onSetSuccess()
        }
        every { mockPublishPc.createAnswer(any(), any()) } answers {
            firstArg<SdpObserver>().onCreateSuccess(null)
        }

        try {
            manager.answerInitialOffer("offer", PeerConnectionType.PUBLISH)
            fail("Expected SdpNegotiationFailed")
        } catch (e: BandwidthRTCError.SdpNegotiationFailed) {
            assertTrue(e.message!!.contains("No SDP answer"))
        }
    }

    @Test
    fun `answerInitialOffer throws on setLocalDescription failure`() = runTest {
        manager.setupPublishingPeerConnection()

        val mockAnswer = buildRealSdp("answer")
        every { mockPublishPc.setRemoteDescription(any(), any()) } answers {
            firstArg<SdpObserver>().onSetSuccess()
        }
        every { mockPublishPc.createAnswer(any(), any()) } answers {
            firstArg<SdpObserver>().onCreateSuccess(mockAnswer)
        }
        every { mockPublishPc.setLocalDescription(any(), any()) } answers {
            firstArg<SdpObserver>().onSetFailure("local desc error")
        }

        try {
            manager.answerInitialOffer("offer", PeerConnectionType.PUBLISH)
            fail("Expected SdpNegotiationFailed")
        } catch (e: BandwidthRTCError.SdpNegotiationFailed) {
            assertTrue(e.message!!.contains("local desc error"))
        }
    }

    // -------------------------------------------------------------------------
    // addLocalTracks()
    // -------------------------------------------------------------------------

    @Test(expected = BandwidthRTCError.PublishFailed::class)
    fun `addLocalTracks throws PublishFailed when publishing PC not set up`() {
        manager.addLocalTracks(audio = true)
    }

    @Test
    fun `addLocalTracks returns a MediaStream`() {
        manager.setupPublishingPeerConnection()

        val mockStream = mockk<MediaStream>(relaxed = true)
        every { mockFactory.createLocalMediaStream(any()) } returns mockStream

        val result = manager.addLocalTracks(audio = false)

        assertEquals(mockStream, result)
    }

    @Test
    fun `addLocalTracks with audio true creates and adds an audio track`() {
        manager.setupPublishingPeerConnection()

        val mockStream = mockk<MediaStream>(relaxed = true)
        val mockAudioSource = mockk<AudioSource>(relaxed = true)
        val mockAudioTrack = mockk<AudioTrack>(relaxed = true)
        every { mockFactory.createLocalMediaStream(any()) } returns mockStream
        every { mockFactory.createAudioSource(any()) } returns mockAudioSource
        every { mockFactory.createAudioTrack(any(), mockAudioSource) } returns mockAudioTrack

        manager.addLocalTracks(audio = true)

        verify { mockFactory.createAudioSource(any()) }
        verify { mockFactory.createAudioTrack(any(), mockAudioSource) }
        verify { mockStream.addTrack(mockAudioTrack) }
        verify { mockPublishPc.addTrack(mockAudioTrack, any()) }
    }

    @Test
    fun `addLocalTracks with audio false skips audio source and track creation`() {
        manager.setupPublishingPeerConnection()

        val mockStream = mockk<MediaStream>(relaxed = true)
        every { mockFactory.createLocalMediaStream(any()) } returns mockStream

        manager.addLocalTracks(audio = false)

        verify(exactly = 0) { mockFactory.createAudioSource(any()) }
        verify(exactly = 0) { mockFactory.createAudioTrack(any(), any()) }
    }

    // -------------------------------------------------------------------------
    // createPublishOffer()
    // -------------------------------------------------------------------------

    @Test(expected = BandwidthRTCError.PublishFailed::class)
    fun `createPublishOffer throws PublishFailed when publishing PC not set up`() = runTest {
        manager.createPublishOffer()
    }

    @Test
    fun `createPublishOffer returns offer SDP`() = runTest {
        manager.setupPublishingPeerConnection()

        val mockOffer = buildRealSdp("offer-sdp-content")
        every { mockPublishPc.createOffer(any(), any()) } answers {
            firstArg<SdpObserver>().onCreateSuccess(mockOffer)
        }
        every { mockPublishPc.setLocalDescription(any(), any()) } answers {
            firstArg<SdpObserver>().onSetSuccess()
        }

        val result = manager.createPublishOffer()

        assertEquals("offer-sdp-content", result)
    }

    @Test
    fun `createPublishOffer throws on createOffer failure`() = runTest {
        manager.setupPublishingPeerConnection()

        every { mockPublishPc.createOffer(any(), any()) } answers {
            firstArg<SdpObserver>().onCreateFailure("offer failed")
        }

        try {
            manager.createPublishOffer()
            fail("Expected SdpNegotiationFailed")
        } catch (e: BandwidthRTCError.SdpNegotiationFailed) {
            assertTrue(e.message!!.contains("offer failed"))
        }
    }

    @Test
    fun `createPublishOffer throws when createOffer returns null SDP`() = runTest {
        manager.setupPublishingPeerConnection()

        every { mockPublishPc.createOffer(any(), any()) } answers {
            firstArg<SdpObserver>().onCreateSuccess(null)
        }

        try {
            manager.createPublishOffer()
            fail("Expected SdpNegotiationFailed")
        } catch (e: BandwidthRTCError.SdpNegotiationFailed) {
            assertTrue(e.message!!.contains("No SDP offer"))
        }
    }

    // -------------------------------------------------------------------------
    // applyPublishAnswer()
    // -------------------------------------------------------------------------

    @Test(expected = BandwidthRTCError.PublishFailed::class)
    fun `applyPublishAnswer throws PublishFailed when publishing PC not set up`() = runTest {
        manager.applyPublishAnswer("answer")
    }

    @Test
    fun `applyPublishAnswer calls setRemoteDescription on publishing PC`() = runTest {
        manager.setupPublishingPeerConnection()

        every { mockPublishPc.setRemoteDescription(any(), any()) } answers {
            firstArg<SdpObserver>().onSetSuccess()
        }

        manager.applyPublishAnswer("remote-answer")

        verify { mockPublishPc.setRemoteDescription(any(), any()) }
    }

    @Test
    fun `applyPublishAnswer throws on setRemoteDescription failure`() = runTest {
        manager.setupPublishingPeerConnection()

        every { mockPublishPc.setRemoteDescription(any(), any()) } answers {
            firstArg<SdpObserver>().onSetFailure("apply answer error")
        }

        try {
            manager.applyPublishAnswer("remote-answer")
            fail("Expected SdpNegotiationFailed")
        } catch (e: BandwidthRTCError.SdpNegotiationFailed) {
            assertTrue(e.message!!.contains("apply answer error"))
        }
    }

    // -------------------------------------------------------------------------
    // handleSubscribeSdpOffer()
    // -------------------------------------------------------------------------

    @Test(expected = BandwidthRTCError.SdpNegotiationFailed::class)
    fun `handleSubscribeSdpOffer throws when subscribe PC not set up`() = runTest {
        manager.handleSubscribeSdpOffer("offer", sdpRevision = 1, metadata = null)
    }

    @Test
    fun `handleSubscribeSdpOffer returns answer SDP and updates revision`() = runTest {
        manager.setupPublishingPeerConnection()
        manager.setupSubscribingPeerConnection()

        val mockAnswer = buildRealSdp("sub-answer")
        stubSdpAnswerFlow(mockSubscribePc, mockAnswer)

        assertEquals(0, manager.subscribeSdpRevision)

        val result = manager.handleSubscribeSdpOffer("offer", sdpRevision = 1, metadata = null)

        assertEquals("sub-answer", result)
        assertEquals(1, manager.subscribeSdpRevision)
    }

    @Test
    fun `handleSubscribeSdpOffer uses incremented revision when sdpRevision is null`() = runTest {
        manager.setupPublishingPeerConnection()
        manager.setupSubscribingPeerConnection()

        val mockAnswer = buildRealSdp("sub-answer")
        stubSdpAnswerFlow(mockSubscribePc, mockAnswer)

        manager.handleSubscribeSdpOffer("offer", sdpRevision = null, metadata = null)

        // null revision → subscribeSdpRevision + 1, which is 0 + 1 = 1
        assertEquals(1, manager.subscribeSdpRevision)
    }

    @Test
    fun `handleSubscribeSdpOffer accepts first offer regardless of revision being 0`() = runTest {
        manager.setupPublishingPeerConnection()
        manager.setupSubscribingPeerConnection()

        val mockAnswer = buildRealSdp("sub-answer")
        stubSdpAnswerFlow(mockSubscribePc, mockAnswer)

        // Even with a high revision on first call (subscribeSdpRevision == 0), it should be accepted
        val result = manager.handleSubscribeSdpOffer("offer", sdpRevision = 5, metadata = null)
        assertEquals("sub-answer", result)
        assertEquals(5, manager.subscribeSdpRevision)
    }

    @Test
    fun `handleSubscribeSdpOffer rejects stale offer after first is accepted`() = runTest {
        manager.setupPublishingPeerConnection()
        manager.setupSubscribingPeerConnection()

        val mockAnswer = buildRealSdp("sub-answer")
        stubSdpAnswerFlow(mockSubscribePc, mockAnswer)

        // Accept first offer at revision 5
        manager.handleSubscribeSdpOffer("offer1", sdpRevision = 5, metadata = null)
        assertEquals(5, manager.subscribeSdpRevision)

        // Attempt to apply a stale offer (revision <= 5)
        try {
            manager.handleSubscribeSdpOffer("offer2", sdpRevision = 3, metadata = null)
            fail("Expected SdpNegotiationFailed for stale offer")
        } catch (e: BandwidthRTCError.SdpNegotiationFailed) {
            assertTrue(e.message!!.contains("Stale"))
        }

        // Revision should remain 5
        assertEquals(5, manager.subscribeSdpRevision)
    }

    @Test
    fun `handleSubscribeSdpOffer rejects offer with same revision as current`() = runTest {
        manager.setupPublishingPeerConnection()
        manager.setupSubscribingPeerConnection()

        val mockAnswer = buildRealSdp("sub-answer")
        stubSdpAnswerFlow(mockSubscribePc, mockAnswer)

        manager.handleSubscribeSdpOffer("offer1", sdpRevision = 3, metadata = null)

        try {
            manager.handleSubscribeSdpOffer("offer2", sdpRevision = 3, metadata = null)
            fail("Expected SdpNegotiationFailed")
        } catch (e: BandwidthRTCError.SdpNegotiationFailed) {
            // expected
        }
    }

    @Test
    fun `handleSubscribeSdpOffer accepts higher revision after first`() = runTest {
        manager.setupPublishingPeerConnection()
        manager.setupSubscribingPeerConnection()

        val mockAnswer = buildRealSdp("sub-answer")
        stubSdpAnswerFlow(mockSubscribePc, mockAnswer)

        manager.handleSubscribeSdpOffer("offer1", sdpRevision = 3, metadata = null)
        manager.handleSubscribeSdpOffer("offer2", sdpRevision = 4, metadata = null)

        assertEquals(4, manager.subscribeSdpRevision)
    }

    // -------------------------------------------------------------------------
    // removeLocalTracks()
    // -------------------------------------------------------------------------

    @Test
    fun `removeLocalTracks is a no-op when stream ID not found`() {
        manager.setupPublishingPeerConnection()
        manager.removeLocalTracks("nonexistent-stream-id") // should not throw
    }

    @Test
    fun `removeLocalTracks removes senders and disposes tracks`() {
        manager.setupPublishingPeerConnection()

        val mockAudioTrack = mockk<AudioTrack>(relaxed = true)
        val mockAudioSource = mockk<AudioSource>(relaxed = true)
        val mockSender = mockk<RtpSender>(relaxed = true)

        // MediaStream.audioTracks is a public final Java field (not a method), so we use a real
        // MediaStream instance and add tracks directly to the list.
        val streamId = "remove-test-stream"
        val realStream = MediaStream(0L)
        realStream.audioTracks.add(mockAudioTrack)

        every { mockAudioTrack.id() } returns "track-1"
        every { mockSender.track() } returns mockAudioTrack
        every { mockPublishPc.senders } returns listOf(mockSender)

        injectPublishedStream(streamId, realStream)
        injectPublishedAudioSource(streamId, mockAudioSource)

        manager.removeLocalTracks(streamId)

        verify { mockPublishPc.removeTrack(mockSender) }
        verify { mockAudioTrack.setEnabled(false) }
        verify { mockAudioTrack.dispose() }
        verify { mockAudioSource.dispose() }
    }

    // -------------------------------------------------------------------------
    // setAudioEnabled()
    // -------------------------------------------------------------------------

    @Test
    fun `setAudioEnabled disables all published audio tracks`() {
        manager.setupPublishingPeerConnection()

        val mockTrack = mockk<AudioTrack>(relaxed = true)
        val realStream = MediaStream(0L)
        realStream.audioTracks.add(mockTrack)
        injectPublishedStream("s1", realStream)

        manager.setAudioEnabled(false)

        verify { mockTrack.setEnabled(false) }
    }

    @Test
    fun `setAudioEnabled enables all published audio tracks`() {
        manager.setupPublishingPeerConnection()

        val mockTrack = mockk<AudioTrack>(relaxed = true)
        val realStream = MediaStream(0L)
        realStream.audioTracks.add(mockTrack)
        injectPublishedStream("s1", realStream)

        manager.setAudioEnabled(true)

        verify { mockTrack.setEnabled(true) }
    }

    // -------------------------------------------------------------------------
    // sendDtmf()
    // -------------------------------------------------------------------------

    @Test
    fun `sendDtmf is a no-op when publishing PC not set up`() {
        manager.sendDtmf("5") // should not throw
    }

    @Test
    fun `sendDtmf inserts DTMF on audio sender`() {
        manager.setupPublishingPeerConnection()

        val mockTrack = mockk<MediaStreamTrack>(relaxed = true)
        val mockDtmf = mockk<DtmfSender>(relaxed = true)
        val mockSender = mockk<RtpSender>(relaxed = true)
        every { mockTrack.kind() } returns "audio"
        every { mockSender.track() } returns mockTrack
        every { mockSender.dtmf() } returns mockDtmf
        every { mockPublishPc.senders } returns listOf(mockSender)

        manager.sendDtmf("3")

        verify { mockDtmf.insertDtmf("3", 100, 50) }
    }

    @Test
    fun `sendDtmf is a no-op when no audio sender found`() {
        manager.setupPublishingPeerConnection()

        val mockTrack = mockk<MediaStreamTrack>(relaxed = true)
        val mockSender = mockk<RtpSender>(relaxed = true)
        every { mockTrack.kind() } returns "video"
        every { mockSender.track() } returns mockTrack
        every { mockPublishPc.senders } returns listOf(mockSender)

        manager.sendDtmf("9") // should not throw
    }

    // -------------------------------------------------------------------------
    // cleanup()
    // -------------------------------------------------------------------------

    @Test
    fun `cleanup closes both peer connections`() {
        manager.setupPublishingPeerConnection()
        manager.setupSubscribingPeerConnection()

        manager.cleanup()

        verify { mockPublishPc.close() }
        verify { mockSubscribePc.close() }
    }

    @Test
    fun `cleanup disposes the factory`() {
        manager.cleanup()

        verify { mockFactory.dispose() }
    }

    @Test
    fun `cleanup resets subscribeSdpRevision to zero`() = runTest {
        manager.setupPublishingPeerConnection()
        manager.setupSubscribingPeerConnection()

        val mockAnswer = buildRealSdp("answer")
        stubSdpAnswerFlow(mockSubscribePc, mockAnswer)
        manager.handleSubscribeSdpOffer("offer", sdpRevision = 7, metadata = null)
        assertEquals(7, manager.subscribeSdpRevision)

        manager.cleanup()

        assertEquals(0, manager.subscribeSdpRevision)
    }

    @Test
    fun `cleanup disposes published audio tracks and sources`() {
        manager.setupPublishingPeerConnection()

        val mockTrack = mockk<AudioTrack>(relaxed = true)
        val mockSource = mockk<AudioSource>(relaxed = true)
        val realStream = MediaStream(0L)
        realStream.audioTracks.add(mockTrack)
        injectPublishedStream("s1", realStream)
        injectPublishedAudioSource("s1", mockSource)

        manager.cleanup()

        verify { mockTrack.setEnabled(false) }
        verify { mockTrack.dispose() }
        verify { mockSource.dispose() }
    }

    // -------------------------------------------------------------------------
    // getCallStats()
    // -------------------------------------------------------------------------

    @Test
    fun `getCallStats completes immediately when neither PC is set up`() {
        var called = false
        manager.getCallStats(0, 0, 0.0) { called = true }
        assertTrue(called)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Use a real SessionDescription — its `description` field is public final and can't be mocked. */
    private fun buildRealSdp(description: String): SessionDescription =
        SessionDescription(SessionDescription.Type.ANSWER, description)

    /** Stubs setRemoteDescription → onSetSuccess, createAnswer → onCreateSuccess(sdp), setLocalDescription → onSetSuccess. */
    private fun stubSdpAnswerFlow(pc: PeerConnection, answerSdp: SessionDescription) {
        every { pc.setRemoteDescription(any(), any()) } answers {
            firstArg<SdpObserver>().onSetSuccess()
        }
        every { pc.createAnswer(any(), any()) } answers {
            firstArg<SdpObserver>().onCreateSuccess(answerSdp)
        }
        every { pc.setLocalDescription(any(), any()) } answers {
            firstArg<SdpObserver>().onSetSuccess()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun injectPublishedStream(streamId: String, stream: MediaStream) {
        val field = PeerConnectionManager::class.java.getDeclaredField("publishedStreams")
        field.isAccessible = true
        (field.get(manager) as java.util.concurrent.ConcurrentHashMap<String, MediaStream>)[streamId] = stream
    }

    @Suppress("UNCHECKED_CAST")
    private fun injectPublishedAudioSource(streamId: String, source: AudioSource) {
        val field = PeerConnectionManager::class.java.getDeclaredField("publishedAudioSources")
        field.isAccessible = true
        (field.get(manager) as java.util.concurrent.ConcurrentHashMap<String, AudioSource>)[streamId] = source
    }

    private fun setPublishIceConnected(value: Boolean) {
        val field = PeerConnectionManager::class.java.getDeclaredField("publishIceConnected")
        field.isAccessible = true
        field.set(manager, value)
    }
}
