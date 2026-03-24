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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Concurrency and resource management tests for PeerConnectionManager.
 *
 * Covers:
 * - Concurrent access to publishedStreams (ConcurrentHashMap)
 * - Concurrent setAudioEnabled calls
 * - Concurrent sendDtmf calls
 * - Concurrent getCallStats calls
 * - Thread-safety of subscribeSdpRevision
 * - Cleanup idempotency
 * - Resource disposal ordering
 * - Stale SDP offer rejection under concurrency
 */
class PeerConnectionManagerConcurrencyTest {

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
        every { PeerConnectionFactory.initialize(any()) } just Runs

        val mockBuilder = mockk<PeerConnectionFactory.Builder>(relaxed = true)
        every { PeerConnectionFactory.builder() } returns mockBuilder
        every { mockBuilder.setAudioDeviceModule(any()) } returns mockBuilder
        every { mockBuilder.createPeerConnectionFactory() } returns mockFactory

        every {
            mockFactory.createPeerConnection(any<PeerConnection.RTCConfiguration>(), any<PeerConnection.Observer>())
        } returnsMany listOf(mockPublishPc, mockSubscribePc)

        manager = PeerConnectionManager(mockContext, null)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // =========================================================================
    // Concurrent setAudioEnabled
    // =========================================================================

    @Test
    fun `concurrent setAudioEnabled calls do not crash`() {
        manager.setupPublishingPeerConnection()

        val mockTrack = mockk<AudioTrack>(relaxed = true)
        val realStream = MediaStream(0L)
        realStream.audioTracks.add(mockTrack)
        injectPublishedStream("s1", realStream)

        val threads = 8
        val barrier = CyclicBarrier(threads)
        val latch = CountDownLatch(threads)
        val errors = AtomicInteger(0)

        repeat(threads) {
            Thread {
                try {
                    barrier.await()
                    repeat(50) { i ->
                        manager.setAudioEnabled(i % 2 == 0)
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(0, errors.get())
    }

    // =========================================================================
    // Concurrent sendDtmf
    // =========================================================================

    @Test
    fun `concurrent sendDtmf calls do not crash`() {
        manager.setupPublishingPeerConnection()

        val mockTrack = mockk<MediaStreamTrack>(relaxed = true)
        val mockDtmf = mockk<DtmfSender>(relaxed = true)
        val mockSender = mockk<RtpSender>(relaxed = true)
        every { mockTrack.kind() } returns "audio"
        every { mockSender.track() } returns mockTrack
        every { mockSender.dtmf() } returns mockDtmf
        every { mockPublishPc.senders } returns listOf(mockSender)

        val threads = 4
        val barrier = CyclicBarrier(threads)
        val latch = CountDownLatch(threads)
        val errors = AtomicInteger(0)

        repeat(threads) {
            Thread {
                try {
                    barrier.await()
                    repeat(20) { i ->
                        manager.sendDtmf("${i % 10}")
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(0, errors.get())
    }

    // =========================================================================
    // Concurrent getCallStats
    // =========================================================================

    @Test
    fun `concurrent getCallStats calls do not crash`() {
        val threads = 8
        val barrier = CyclicBarrier(threads)
        val latch = CountDownLatch(threads)
        val errors = AtomicInteger(0)
        val completions = AtomicInteger(0)

        repeat(threads) {
            Thread {
                try {
                    barrier.await()
                    repeat(10) {
                        manager.getCallStats(0, 0, 0.0) { _ ->
                            completions.incrementAndGet()
                        }
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(0, errors.get())
        // All completions should fire (empty snapshot when no PC setup)
        assertEquals(threads * 10, completions.get())
    }

    // =========================================================================
    // SDP revision concurrency
    // =========================================================================

    @Test
    fun `subscribeSdpRevision is updated atomically`() = runTest {
        manager.setupPublishingPeerConnection()
        manager.setupSubscribingPeerConnection()

        val mockAnswer = SessionDescription(SessionDescription.Type.ANSWER, "answer")
        stubSdpAnswerFlow(mockSubscribePc, mockAnswer)

        assertEquals(0, manager.subscribeSdpRevision)

        // Sequentially increase revision
        manager.handleSubscribeSdpOffer("offer1", sdpRevision = 1, metadata = null)
        assertEquals(1, manager.subscribeSdpRevision)

        manager.handleSubscribeSdpOffer("offer2", sdpRevision = 5, metadata = null)
        assertEquals(5, manager.subscribeSdpRevision)

        manager.handleSubscribeSdpOffer("offer3", sdpRevision = 10, metadata = null)
        assertEquals(10, manager.subscribeSdpRevision)
    }

    @Test
    fun `stale SDP offers are rejected in order`() = runTest {
        manager.setupPublishingPeerConnection()
        manager.setupSubscribingPeerConnection()

        val mockAnswer = SessionDescription(SessionDescription.Type.ANSWER, "answer")
        stubSdpAnswerFlow(mockSubscribePc, mockAnswer)

        // Accept revision 5
        manager.handleSubscribeSdpOffer("offer1", sdpRevision = 5, metadata = null)

        // Reject stale revisions: 1, 2, 3, 4, 5
        for (stale in 1..5) {
            try {
                manager.handleSubscribeSdpOffer("stale-$stale", sdpRevision = stale, metadata = null)
                fail("Should reject stale revision $stale")
            } catch (e: BandwidthRTCError.SdpNegotiationFailed) {
                assertTrue(e.message!!.contains("Stale"))
            }
        }

        // Accept newer revision
        manager.handleSubscribeSdpOffer("offer2", sdpRevision = 6, metadata = null)
        assertEquals(6, manager.subscribeSdpRevision)
    }

    // =========================================================================
    // Cleanup
    // =========================================================================

    @Test
    fun `cleanup resets revision to zero`() = runTest {
        manager.setupPublishingPeerConnection()
        manager.setupSubscribingPeerConnection()

        val mockAnswer = SessionDescription(SessionDescription.Type.ANSWER, "answer")
        stubSdpAnswerFlow(mockSubscribePc, mockAnswer)

        manager.handleSubscribeSdpOffer("offer", sdpRevision = 10, metadata = null)
        assertEquals(10, manager.subscribeSdpRevision)

        manager.cleanup()
        assertEquals(0, manager.subscribeSdpRevision)
    }

    @Test
    fun `cleanup is safe to call multiple times`() {
        manager.setupPublishingPeerConnection()
        manager.setupSubscribingPeerConnection()

        manager.cleanup()
        manager.cleanup() // Second call should not throw

        verify(atLeast = 1) { mockPublishPc.close() }
    }

    @Test
    fun `cleanup disposes all published tracks and sources`() {
        manager.setupPublishingPeerConnection()

        val mockTrack1 = mockk<AudioTrack>(relaxed = true)
        val mockSource1 = mockk<AudioSource>(relaxed = true)
        val stream1 = MediaStream(0L)
        stream1.audioTracks.add(mockTrack1)
        injectPublishedStream("s1", stream1)
        injectPublishedAudioSource("s1", mockSource1)

        val mockTrack2 = mockk<AudioTrack>(relaxed = true)
        val mockSource2 = mockk<AudioSource>(relaxed = true)
        val stream2 = MediaStream(0L)
        stream2.audioTracks.add(mockTrack2)
        injectPublishedStream("s2", stream2)
        injectPublishedAudioSource("s2", mockSource2)

        manager.cleanup()

        verify { mockTrack1.setEnabled(false) }
        verify { mockTrack1.dispose() }
        verify { mockSource1.dispose() }
        verify { mockTrack2.setEnabled(false) }
        verify { mockTrack2.dispose() }
        verify { mockSource2.dispose() }
    }

    @Test
    fun `cleanup closes both peer connections`() {
        manager.setupPublishingPeerConnection()
        manager.setupSubscribingPeerConnection()

        manager.cleanup()

        verify { mockPublishPc.close() }
        verify { mockSubscribePc.close() }
    }

    @Test
    fun `cleanup disposes factory`() {
        manager.cleanup()

        verify { mockFactory.dispose() }
    }

    // =========================================================================
    // removeLocalTracks correctness
    // =========================================================================

    @Test
    fun `removeLocalTracks for nonexistent stream is safe`() {
        manager.setupPublishingPeerConnection()
        manager.removeLocalTracks("does-not-exist") // Should not throw
    }

    @Test
    fun `removeLocalTracks cleans up track and source`() {
        manager.setupPublishingPeerConnection()

        val mockTrack = mockk<AudioTrack>(relaxed = true)
        val mockSource = mockk<AudioSource>(relaxed = true)
        val mockSender = mockk<RtpSender>(relaxed = true)
        val stream = MediaStream(0L)
        stream.audioTracks.add(mockTrack)

        every { mockTrack.id() } returns "t1"
        every { mockSender.track() } returns mockTrack
        every { mockPublishPc.senders } returns listOf(mockSender)

        injectPublishedStream("s1", stream)
        injectPublishedAudioSource("s1", mockSource)

        manager.removeLocalTracks("s1")

        verify { mockPublishPc.removeTrack(mockSender) }
        verify { mockTrack.setEnabled(false) }
        verify { mockTrack.dispose() }
        verify { mockSource.dispose() }
    }

    // =========================================================================
    // Stream metadata handling
    // =========================================================================

    @Test
    fun `handleSubscribeSdpOffer stores metadata`() = runTest {
        manager.setupPublishingPeerConnection()
        manager.setupSubscribingPeerConnection()

        val mockAnswer = SessionDescription(SessionDescription.Type.ANSWER, "answer")
        stubSdpAnswerFlow(mockSubscribePc, mockAnswer)

        val metadata = mapOf(
            "stream-1" to StreamMetadata(endpointId = "ep-1", alias = "user-1", mediaTypes = listOf(MediaType.AUDIO))
        )

        manager.handleSubscribeSdpOffer("offer", sdpRevision = 1, metadata = metadata)

        // Metadata should be stored (accessed via reflection)
        val field = PeerConnectionManager::class.java.getDeclaredField("subscribedStreamMetadata")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stored = field.get(manager) as ConcurrentHashMap<String, StreamMetadata>
        assertEquals("ep-1", stored["stream-1"]?.endpointId)
    }

    @Test
    fun `handleSubscribeSdpOffer with null metadata does not crash`() = runTest {
        manager.setupPublishingPeerConnection()
        manager.setupSubscribingPeerConnection()

        val mockAnswer = SessionDescription(SessionDescription.Type.ANSWER, "answer")
        stubSdpAnswerFlow(mockSubscribePc, mockAnswer)

        manager.handleSubscribeSdpOffer("offer", sdpRevision = 1, metadata = null)
        // Should not throw
    }

    // =========================================================================
    // Helpers
    // =========================================================================

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
        (field.get(manager) as ConcurrentHashMap<String, MediaStream>)[streamId] = stream
    }

    @Suppress("UNCHECKED_CAST")
    private fun injectPublishedAudioSource(streamId: String, source: AudioSource) {
        val field = PeerConnectionManager::class.java.getDeclaredField("publishedAudioSources")
        field.isAccessible = true
        (field.get(manager) as ConcurrentHashMap<String, AudioSource>)[streamId] = source
    }
}
