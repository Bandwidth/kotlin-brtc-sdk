package com.bandwidth.rtc.webrtc

import android.content.Context
import com.bandwidth.rtc.types.*
import io.mockk.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
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
        every { mockDtmf.canInsertDtmf() } returns true
        every { mockDtmf.insertDtmf(any(), any(), any()) } returns true
        every { mockPublishPc.senders } returns listOf(mockSender)

        manager.sendDtmf("3")

        verify { mockDtmf.insertDtmf("3", 100, 50) }
    }

    @Test
    fun `sendDtmf passes custom duration and interToneGap`() {
        manager.setupPublishingPeerConnection()

        val mockTrack = mockk<MediaStreamTrack>(relaxed = true)
        val mockDtmf = mockk<DtmfSender>(relaxed = true)
        val mockSender = mockk<RtpSender>(relaxed = true)
        every { mockTrack.kind() } returns "audio"
        every { mockSender.track() } returns mockTrack
        every { mockSender.dtmf() } returns mockDtmf
        every { mockDtmf.canInsertDtmf() } returns true
        every { mockDtmf.insertDtmf(any(), any(), any()) } returns true
        every { mockPublishPc.senders } returns listOf(mockSender)

        manager.sendDtmf("5", duration = 300, interToneGap = 80)

        verify { mockDtmf.insertDtmf("5", 300, 80) }
    }

    @Test
    fun `sendDtmf is a no-op when DTMF sender not ready`() {
        manager.setupPublishingPeerConnection()

        val mockTrack = mockk<MediaStreamTrack>(relaxed = true)
        val mockDtmf = mockk<DtmfSender>(relaxed = true)
        val mockSender = mockk<RtpSender>(relaxed = true)
        every { mockTrack.kind() } returns "audio"
        every { mockSender.track() } returns mockTrack
        every { mockSender.dtmf() } returns mockDtmf
        every { mockDtmf.canInsertDtmf() } returns false
        every { mockPublishPc.senders } returns listOf(mockSender)

        manager.sendDtmf("5")

        verify(exactly = 0) { mockDtmf.insertDtmf(any(), any(), any()) }
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

    @Test
    fun `sendDtmf fires onDtmfSent once per valid character with the published stream id`() {
        manager.setupPublishingPeerConnection()

        val mockTrack = mockk<AudioTrack>(relaxed = true)
        every { mockTrack.kind() } returns "audio"
        every { mockTrack.id() } returns "track-1"

        val mockDtmf = mockk<DtmfSender>(relaxed = true)
        val mockSender = mockk<RtpSender>(relaxed = true)
        every { mockSender.track() } returns mockTrack
        every { mockSender.dtmf() } returns mockDtmf
        every { mockDtmf.canInsertDtmf() } returns true
        every { mockDtmf.insertDtmf(any(), any(), any()) } returns true
        every { mockPublishPc.senders } returns listOf(mockSender)

        val realStream = MediaStream(0L)
        realStream.audioTracks.add(mockTrack)
        injectPublishedStream("stream-1", realStream)

        val events = mutableListOf<DtmfSentEvent>()
        manager.onDtmfSent = { events.add(it) }

        manager.sendDtmf("1#x2") // 'x' is not a valid DTMF character and should be skipped

        assertEquals(listOf("1", "#", "2"), events.map { it.tone })
        assertTrue(events.all { it.streamId == "stream-1" })
    }

    @Test
    fun `sendDtmf does not fire onDtmfSent when the track has no published stream`() {
        manager.setupPublishingPeerConnection()

        val mockTrack = mockk<MediaStreamTrack>(relaxed = true)
        val mockDtmf = mockk<DtmfSender>(relaxed = true)
        val mockSender = mockk<RtpSender>(relaxed = true)
        every { mockTrack.kind() } returns "audio"
        every { mockSender.track() } returns mockTrack
        every { mockSender.dtmf() } returns mockDtmf
        every { mockDtmf.canInsertDtmf() } returns true
        every { mockDtmf.insertDtmf(any(), any(), any()) } returns true
        every { mockPublishPc.senders } returns listOf(mockSender)

        var fired = false
        manager.onDtmfSent = { fired = true }

        manager.sendDtmf("3")

        assertFalse(fired)
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

    // -------------------------------------------------------------------------
    // republishLocalStream()
    // -------------------------------------------------------------------------

    @Test(expected = BandwidthRTCError.PublishFailed::class)
    fun `republishLocalStream throws PublishFailed when publishing PC not set up`() {
        manager.republishLocalStream("stream-1", audio = true)
    }

    @Test
    fun `republishLocalStream re-attaches a still live track`() {
        manager.setupPublishingPeerConnection()

        val liveTrack = mockk<AudioTrack>(relaxed = true)
        every { liveTrack.state() } returns MediaStreamTrack.State.LIVE
        val realStream = MediaStream(0L)
        realStream.audioTracks.add(liveTrack)

        injectPublishedStream("live-stream", realStream)
        injectPublishedAudioTrack("live-stream", liveTrack)

        val result = manager.republishLocalStream("live-stream", audio = true)

        assertEquals(realStream, result)
        verify { mockPublishPc.addTrack(liveTrack, listOf("live-stream")) }
        verify(exactly = 0) { mockFactory.createAudioTrack(any(), any()) }
    }

    @Test
    fun `republishLocalStream re-acquires a track that ended while disconnected`() {
        manager.setupPublishingPeerConnection()

        val endedTrack = mockk<AudioTrack>(relaxed = true)
        every { endedTrack.state() } returns MediaStreamTrack.State.ENDED
        every { endedTrack.id() } returns "ended-track"
        val realStream = MediaStream(0L)
        realStream.audioTracks.add(endedTrack)

        injectPublishedStream("dead-stream", realStream)
        injectPublishedAudioTrack("dead-stream", endedTrack)
        every { mockPublishPc.senders } returns emptyList()

        val freshStream = mockk<MediaStream>(relaxed = true)
        val freshSource = mockk<AudioSource>(relaxed = true)
        val freshTrack = mockk<AudioTrack>(relaxed = true)
        every { mockFactory.createLocalMediaStream(any()) } returns freshStream
        every { mockFactory.createAudioSource(any()) } returns freshSource
        every { mockFactory.createAudioTrack(any(), freshSource) } returns freshTrack

        val result = manager.republishLocalStream("dead-stream", audio = true)

        // A re-attached ended track produces a sender that never sends RTP, so it must be replaced.
        assertEquals(freshStream, result)
        verify { endedTrack.dispose() }
        verify { mockPublishPc.addTrack(freshTrack, any()) }
        verify(exactly = 0) { mockPublishPc.addTrack(endedTrack, any()) }
    }

    @Test
    fun `republishLocalStream acquires new tracks when nothing was retained`() {
        manager.setupPublishingPeerConnection()

        val freshStream = mockk<MediaStream>(relaxed = true)
        every { mockFactory.createLocalMediaStream(any()) } returns freshStream

        val result = manager.republishLocalStream("unknown-stream", audio = false)

        assertEquals(freshStream, result)
    }

    // -------------------------------------------------------------------------
    // cleanup() vs. concurrent access
    // -------------------------------------------------------------------------

    @Test
    fun `cleanup disposes native objects only once when called twice`() {
        manager.setupPublishingPeerConnection()
        manager.setupSubscribingPeerConnection()

        // disconnect() and a gateway-initiated close can both reach cleanup(); disposing the same
        // native object twice is a JNI double free, not a harmless no-op.
        manager.cleanup()
        manager.cleanup()

        verify(exactly = 1) { mockPublishPc.dispose() }
        verify(exactly = 1) { mockSubscribePc.dispose() }
        verify(exactly = 1) { mockFactory.dispose() }
    }

    @Test
    fun `application entry points stop touching peer connections after cleanup`() {
        manager.setupPublishingPeerConnection()
        manager.setupSubscribingPeerConnection()
        manager.cleanup()

        // An application polling stats or sending DTMF does not know the session just died, so
        // these must degrade to no-ops rather than dereference freed peer connections.
        var snapshot: CallStatsSnapshot? = null
        manager.getCallStats(0, 0, 0.0) { snapshot = it }
        manager.sendDtmf("5")
        manager.setAudioEnabled(false)
        manager.removeLocalTracks("any-stream")

        assertNotNull(snapshot)
        verify(exactly = 0) { mockPublishPc.getStats(any()) }
        verify(exactly = 0) { mockSubscribePc.getStats(any()) }
    }

    @Test
    fun `cleanup waits for an in-flight native call before disposing`() {
        manager.setupPublishingPeerConnection()

        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val inFlight = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)

        val mockTrack = mockk<MediaStreamTrack>(relaxed = true)
        val mockDtmf = mockk<DtmfSender>(relaxed = true)
        val mockSender = mockk<RtpSender>(relaxed = true)
        every { mockTrack.kind() } returns "audio"
        every { mockSender.track() } returns mockTrack
        every { mockSender.dtmf() } returns mockDtmf
        every { mockDtmf.canInsertDtmf() } returns true
        every { mockPublishPc.senders } returns listOf(mockSender)
        every { mockDtmf.insertDtmf(any(), any(), any()) } answers {
            order.add("dtmf-start")
            inFlight.countDown()
            release.await()
            order.add("dtmf-end")
            true
        }
        every { mockFactory.dispose() } answers { order.add("dispose") }

        val dtmfThread = Thread { manager.sendDtmf("7") }.apply { start() }
        inFlight.await()
        val cleanupThread = Thread { manager.cleanup() }.apply { start() }

        // cleanup() must still be blocked draining: an undrained one would be finished by now,
        // microseconds after it started. The 2s drain timeout leaves ample room for this wait.
        cleanupThread.join(300)
        assertTrue("cleanup() disposed while a native call was in flight", cleanupThread.isAlive)

        release.countDown()
        dtmfThread.join()
        cleanupThread.join()

        // Disposing the factory out from under a call that is already inside WebRTC is the crash
        // this guard exists to prevent, so the ordering is the whole point.
        assertEquals(listOf("dtmf-start", "dtmf-end", "dispose"), order.toList())
    }

    @Test
    fun `subscribe negotiation stops short when cleanup lands mid-suspension`() = runTest {
        manager.setupPublishingPeerConnection()
        manager.setupSubscribingPeerConnection()

        val observer = slot<SdpObserver>()
        every { mockSubscribePc.setRemoteDescription(capture(observer), any()) } just Runs
        // Resumes immediately so the negotiation terminates either way: if the guard ever
        // regresses, this test has to fail rather than hang on a suspension that never resumes
        // (a non-cancellable one cannot be unstuck, in a test or in production).
        every { mockSubscribePc.createAnswer(any(), any()) } answers {
            firstArg<SdpObserver>().onCreateFailure("createAnswer should not have been reached")
        }

        var error: Throwable? = null
        val job = launch {
            error = runCatching {
                manager.handleSubscribeSdpOffer("offer", sdpRevision = 1, metadata = null)
            }.exceptionOrNull()
        }
        runCurrent()

        // The "sdpOffer" signaling handler launches an untracked coroutine, so a gateway close can
        // dispose the subscribing PC while a renegotiation sits here, suspended on WebRTC's
        // callback - the entry check passed long before disposal happened.
        manager.cleanup()
        observer.captured.onSetSuccess()
        job.join()

        verify(exactly = 0) { mockSubscribePc.createAnswer(any(), any()) }
        assertTrue("expected SdpNegotiationFailed, got $error", error is BandwidthRTCError.SdpNegotiationFailed)
    }

    @Test(expected = BandwidthRTCError.PublishFailed::class)
    fun `createPublishOffer fails fast after cleanup`() = runTest {
        manager.setupPublishingPeerConnection()
        manager.cleanup()

        manager.createPublishOffer()
    }

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

    @Suppress("UNCHECKED_CAST")
    private fun injectPublishedAudioTrack(streamId: String, track: AudioTrack) {
        val field = PeerConnectionManager::class.java.getDeclaredField("publishedAudioTracks")
        field.isAccessible = true
        (field.get(manager) as java.util.concurrent.ConcurrentHashMap<String, AudioTrack>)[streamId] = track
    }

    private fun setPublishIceConnected(value: Boolean) {
        val field = PeerConnectionManager::class.java.getDeclaredField("publishIceConnected")
        field.isAccessible = true
        field.set(manager, value)
    }
}
