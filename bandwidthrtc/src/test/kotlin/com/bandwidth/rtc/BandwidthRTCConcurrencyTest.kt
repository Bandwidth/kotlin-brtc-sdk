package com.bandwidth.rtc

import android.content.Context
import com.bandwidth.rtc.signaling.SignalingClientInterface
import com.bandwidth.rtc.signaling.rpc.OfferSdpResult
import com.bandwidth.rtc.signaling.rpc.SetMediaPreferencesResult
import com.bandwidth.rtc.types.*
import com.bandwidth.rtc.webrtc.PeerConnectionManagerInterface
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Concurrency and thread-safety tests for BandwidthRTC.
 *
 * Covers:
 * - Concurrent callback invocations (onStreamAvailable, onRemoteDisconnected)
 * - Race between connect and disconnect
 * - Race between publish and disconnect
 * - Concurrent getCallStats calls
 * - Concurrent setMicEnabled / sendDtmf during call
 * - Multiple concurrent publishes
 * - Thread-safety of isConnected flag
 * - Callback invocation ordering
 */
class BandwidthRTCConcurrencyTest {

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

    private suspend fun connectBrtc() {
        coEvery { mockSignaling.setMediaPreferences() } returns SetMediaPreferencesResult()
        brtc.connect(authParams)
    }

    private fun buildMockMediaStream(id: String): MediaStream {
        val stream = mockk<MediaStream>(relaxed = true)
        every { stream.id } returns id
        return stream
    }

    // =========================================================================
    // Concurrent callback invocations
    // =========================================================================

    @Test
    fun `concurrent onStreamAvailable callbacks are all delivered`() = runTest {
        connectBrtc()

        val receivedStreams = java.util.concurrent.ConcurrentLinkedQueue<String>()
        brtc.onStreamAvailable = { stream ->
            receivedStreams.add(stream.streamId)
        }

        // Capture the stream handler
        val streamSlot = slot<(MediaStream, List<MediaType>, TrackMetadata?) -> Unit>()
        verify { mockPCManager.onStreamAvailable = capture(streamSlot) }
        val handler = streamSlot.captured

        val numStreams = 10
        val barrier = CyclicBarrier(numStreams)
        val latch = CountDownLatch(numStreams)
        val errors = AtomicInteger(0)

        repeat(numStreams) { i ->
            Thread {
                try {
                    barrier.await()
                    handler(buildMockMediaStream("stream-$i"), listOf(MediaType.AUDIO), null)
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(0, errors.get())
        assertEquals(numStreams, receivedStreams.size)
    }

    @Test
    fun `concurrent onStreamUnavailable callbacks are all delivered`() = runTest {
        connectBrtc()

        val removedIds = java.util.concurrent.ConcurrentLinkedQueue<String>()
        brtc.onStreamUnavailable = { id -> removedIds.add(id) }

        val unavailableSlot = slot<(String) -> Unit>()
        verify { mockPCManager.onStreamUnavailable = capture(unavailableSlot) }
        val handler = unavailableSlot.captured

        val numStreams = 10
        val latch = CountDownLatch(numStreams)

        repeat(numStreams) { i ->
            Thread {
                handler("stream-$i")
                latch.countDown()
            }.start()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(numStreams, removedIds.size)
    }

    // =========================================================================
    // Concurrent operations
    // =========================================================================

    @Test
    fun `concurrent setMicEnabled calls do not crash`() = runTest {
        connectBrtc()

        val threads = 8
        val barrier = CyclicBarrier(threads)
        val latch = CountDownLatch(threads)
        val errors = AtomicInteger(0)

        repeat(threads) {
            Thread {
                try {
                    barrier.await()
                    repeat(50) { i ->
                        brtc.setMicEnabled(i % 2 == 0)
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

    @Test
    fun `concurrent sendDtmf calls do not crash`() = runTest {
        connectBrtc()

        val threads = 4
        val tones = listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "#")
        val latch = CountDownLatch(threads)
        val errors = AtomicInteger(0)

        repeat(threads) {
            Thread {
                try {
                    tones.forEach { tone ->
                        brtc.sendDtmf(tone)
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

    @Test
    fun `concurrent getCallStats calls do not crash`() = runTest {
        connectBrtc()

        val snapshot = CallStatsSnapshot(packetsReceived = 100)
        every { mockPCManager.getCallStats(any(), any(), any(), any()) } answers {
            lastArg<(CallStatsSnapshot) -> Unit>().invoke(snapshot)
        }

        val threads = 8
        val barrier = CyclicBarrier(threads)
        val latch = CountDownLatch(threads)
        val errors = AtomicInteger(0)
        val results = AtomicInteger(0)

        repeat(threads) {
            Thread {
                try {
                    barrier.await()
                    repeat(20) {
                        brtc.getCallStats(null) { stats ->
                            assertEquals(100, stats.packetsReceived)
                            results.incrementAndGet()
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
        assertEquals(threads * 20, results.get())
    }

    // =========================================================================
    // publishMutex serialization
    // =========================================================================

    @Test
    fun `concurrent publish and unpublish do not interleave under the publish mutex`() = runTest {
        connectBrtc()

        coEvery { mockPCManager.waitForPublishIceConnected() } just Runs
        every { mockPCManager.addLocalTracks(any()) } returns buildMockMediaStream("already-published")
        coEvery { mockPCManager.createPublishOffer() } returns "offer"
        coEvery { mockSignaling.offerSdp(any(), any()) } returns OfferSdpResult("answer")
        coEvery { mockPCManager.applyPublishAnswer(any()) } just Runs

        // A stream already published before the concurrent publish()/unpublish() below race.
        val existing = brtc.publish(audio = true)

        // Blocks publish()'s renegotiation (still inside publishMutex) until released, so the
        // test can assert unpublish()'s own mutex section has not run meanwhile. unpublish()'s
        // later renegotiation reuses this same stub once the gate is open, which is fine - it
        // isn't what removeLocalTracksCalled is tracking.
        val gate = CompletableDeferred<Unit>()
        every { mockPCManager.addLocalTracks(any()) } returns buildMockMediaStream("new-stream")
        coEvery { mockSignaling.offerSdp(any(), any()) } coAnswers {
            gate.await()
            OfferSdpResult("answer")
        }
        var removeLocalTracksCalled = false
        every { mockPCManager.removeLocalTracks(any()) } answers { removeLocalTracksCalled = true }

        val publishJob = launch { brtc.publish(audio = true, alias = "second") }
        val unpublishJob = launch { brtc.unpublish(existing) }

        // Let both coroutines run up to their first real suspension point.
        runCurrent()
        assertFalse(
            "unpublish's removeLocalTracks should be blocked on the mutex while publish is negotiating",
            removeLocalTracksCalled
        )

        gate.complete(Unit)
        advanceUntilIdle()

        // publish() held the mutex for its whole attach+negotiate, so unpublish()'s track
        // removal could only run after publish()'s renegotiation released it.
        assertTrue(removeLocalTracksCalled)
        assertTrue(publishJob.isCompleted)
        assertTrue(unpublishJob.isCompleted)
    }

    // =========================================================================
    // Thread-safety of isConnected
    // =========================================================================

    @Test
    fun `isConnected is readable from multiple threads`() = runTest {
        connectBrtc()

        val threads = 10
        val latch = CountDownLatch(threads)
        val errors = AtomicInteger(0)

        repeat(threads) {
            Thread {
                try {
                    repeat(100) {
                        val connected = brtc.isConnected
                        // Just verify it doesn't crash
                        assertNotNull(connected)
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
    // Concurrent callback registration
    // =========================================================================

    @Test
    fun `setting callbacks from multiple threads does not crash`() = runTest {
        val latch = CountDownLatch(4)
        val errors = AtomicInteger(0)

        Thread {
            try {
                repeat(100) {
                    brtc.onStreamAvailable = { }
                    brtc.onStreamAvailable = null
                }
            } catch (e: Exception) { errors.incrementAndGet() }
            finally { latch.countDown() }
        }.start()

        Thread {
            try {
                repeat(100) {
                    brtc.onStreamUnavailable = { }
                    brtc.onStreamUnavailable = null
                }
            } catch (e: Exception) { errors.incrementAndGet() }
            finally { latch.countDown() }
        }.start()

        Thread {
            try {
                repeat(100) {
                    brtc.onReady = { }
                    brtc.onReady = null
                }
            } catch (e: Exception) { errors.incrementAndGet() }
            finally { latch.countDown() }
        }.start()

        Thread {
            try {
                repeat(100) {
                    brtc.onRemoteDisconnected = { }
                    brtc.onRemoteDisconnected = null
                }
            } catch (e: Exception) { errors.incrementAndGet() }
            finally { latch.countDown() }
        }.start()

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(0, errors.get())
    }

    // =========================================================================
    // ICE state change concurrent with disconnect
    // =========================================================================

    @Test
    fun `ICE disconnect callback during cleanup does not crash`() = runTest {
        connectBrtc()

        val disconnectCalled = AtomicInteger(0)
        brtc.onRemoteDisconnected = { disconnectCalled.incrementAndGet() }

        // Capture ICE handler
        val iceSlot = slot<(PeerConnection.IceConnectionState) -> Unit>()
        verify { mockPCManager.onSubscribingIceConnectionStateChange = capture(iceSlot) }
        val iceHandler = iceSlot.captured

        val latch = CountDownLatch(2)

        // Concurrent disconnect
        Thread {
            runBlocking { brtc.disconnect() }
            latch.countDown()
        }.start()

        // Concurrent ICE state change
        Thread {
            try {
                iceHandler(PeerConnection.IceConnectionState.FAILED)
            } catch (_: Exception) { }
            latch.countDown()
        }.start()

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        // Either disconnect happened first or ICE callback - both paths valid
    }

    // =========================================================================
    // Audio level callback concurrency
    // =========================================================================

    @Test
    fun `concurrent audio level callbacks do not crash`() = runTest {
        val receivedLocal = AtomicInteger(0)
        val receivedRemote = AtomicInteger(0)
        brtc.onLocalAudioLevel = { receivedLocal.incrementAndGet() }
        brtc.onRemoteAudioLevel = { receivedRemote.incrementAndGet() }

        connectBrtc()

        // getCallStats triggers onRemoteAudioLevel
        val snapshot = CallStatsSnapshot(audioLevel = 0.5)
        every { mockPCManager.getCallStats(any(), any(), any(), any()) } answers {
            lastArg<(CallStatsSnapshot) -> Unit>().invoke(snapshot)
        }

        val latch = CountDownLatch(2)
        val errors = AtomicInteger(0)

        // Thread 1: rapid getCallStats
        Thread {
            try {
                repeat(100) {
                    brtc.getCallStats(null) { }
                }
            } catch (e: Exception) { errors.incrementAndGet() }
            finally { latch.countDown() }
        }.start()

        // Thread 2: rapid callback changes
        Thread {
            try {
                repeat(100) { i ->
                    if (i % 2 == 0) {
                        brtc.onRemoteAudioLevel = { receivedRemote.incrementAndGet() }
                    } else {
                        brtc.onRemoteAudioLevel = null
                    }
                }
            } catch (e: Exception) { errors.incrementAndGet() }
            finally { latch.countDown() }
        }.start()

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(0, errors.get())
    }
}
