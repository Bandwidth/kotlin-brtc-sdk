package com.bandwidth.rtc

import android.content.Context
import com.bandwidth.rtc.signaling.SignalingClientInterface
import com.bandwidth.rtc.signaling.rpc.OfferSdpResult
import com.bandwidth.rtc.signaling.rpc.SetMediaPreferencesResult
import com.bandwidth.rtc.types.*
import com.bandwidth.rtc.util.LogSink
import com.bandwidth.rtc.util.Logger
import com.bandwidth.rtc.webrtc.PeerConnectionManagerInterface
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.webrtc.MediaStream
import java.util.concurrent.CopyOnWriteArrayList

/** The 10-second trace that runs only while a call stream is active. */
@OptIn(ExperimentalCoroutinesApi::class)
class BandwidthRTCInCallStatsTraceTest {

    private val inCallIntervalMs = 10_000L
    private val fiveMinutesMs = 5 * 60 * 1_000L

    private lateinit var mockSignaling: SignalingClientInterface
    private lateinit var mockPCManager: PeerConnectionManagerInterface
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var brtc: BandwidthRTC

    private lateinit var streamAvailable: (MediaStream, List<MediaType>, TrackMetadata?) -> Unit
    private lateinit var streamUnavailable: (String) -> Unit
    private val handlers = mutableMapOf<String, (String) -> Unit>()
    private val logged = CopyOnWriteArrayList<String>()
    private val logSink = LogSink { _, _, message -> logged.add(message) }

    private val authParams = RtcAuthParams(endpointToken = "test-token")

    @Before
    fun setUp() {
        mockSignaling = mockk(relaxed = true)
        mockPCManager = mockk(relaxed = true)
        scheduler = TestCoroutineScheduler()
        brtc = BandwidthRTC(
            context = mockk<Context>(relaxed = true),
            signaling = mockSignaling,
            peerConnectionManager = mockPCManager,
            scope = CoroutineScope(StandardTestDispatcher(scheduler))
        )
        every { mockSignaling.onEvent(any(), any()) } answers { handlers[firstArg()] = secondArg() }
        coEvery { mockSignaling.setMediaPreferences() } returns SetMediaPreferencesResult()
        coEvery { mockSignaling.offerSdp(any(), any()) } returns OfferSdpResult("answer")
        coEvery { mockPCManager.createPublishOffer() } returns "offer"
        every { mockPCManager.onStreamAvailable = any() } answers { streamAvailable = firstArg() }
        every { mockPCManager.onStreamUnavailable = any() } answers { streamUnavailable = firstArg() }
        every { mockPCManager.getCallStats(any(), any(), any(), any()) } answers {
            arg<(CallStatsSnapshot) -> Unit>(3).invoke(CallStatsSnapshot(bytesReceived = 100, timestamp = 1.0))
        }
        Logger.addSink(logSink)
    }

    @After
    fun tearDown() {
        Logger.removeSink(logSink)
        unmockkAll()
    }

    private fun advance(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    private fun callStreamStarts(id: String) {
        streamAvailable(mockStream(id), listOf(MediaType.AUDIO), null)
    }

    private fun mockStream(id: String): MediaStream {
        val stream = mockk<MediaStream>(relaxed = true)
        every { stream.id } returns id
        return stream
    }

    private fun logCount(prefix: String) = logged.count { it.startsWith(prefix) }

    @Test
    fun `does not trace before a call stream is available`() = runTest {
        brtc.connect(authParams)

        advance(inCallIntervalMs * 6)

        verify(exactly = 0) { mockPCManager.getCallStats(any(), any(), any(), any()) }
    }

    @Test
    fun `traces every 10 seconds while in a call`() = runTest {
        brtc.connect(authParams)
        callStreamStarts("call-1")

        advance(inCallIntervalMs - 1)
        verify(exactly = 0) { mockPCManager.getCallStats(any(), any(), any(), any()) }

        advance(1)
        verify(exactly = 1) { mockPCManager.getCallStats(any(), any(), any(), any()) }

        advance(inCallIntervalMs)
        verify(exactly = 2) { mockPCManager.getCallStats(any(), any(), any(), any()) }
        assertEquals(2, logCount("In-call stats:"))
    }

    @Test
    fun `passes the previous in-call snapshot so bitrates can be computed`() = runTest {
        brtc.connect(authParams)
        callStreamStarts("call-1")

        advance(inCallIntervalMs * 2)

        verify(exactly = 1) { mockPCManager.getCallStats(0, 0, 0.0, any()) }
        verify(exactly = 1) { mockPCManager.getCallStats(100, 0, 1.0, any()) }
    }

    @Test
    fun `reads a final snapshot and stops when the call ends`() = runTest {
        brtc.connect(authParams)
        callStreamStarts("call-1")
        advance(inCallIntervalMs)

        streamUnavailable("call-1")

        // The final read carries the last periodic snapshot as its baseline
        verifyOrder {
            mockPCManager.getCallStats(0, 0, 0.0, any())
            mockPCManager.getCallStats(100, 0, 1.0, any())
        }
        assertEquals(1, logCount("Final in-call stats (call ended):"))

        advance(inCallIntervalMs * 3)
        verify(exactly = 2) { mockPCManager.getCallStats(any(), any(), any(), any()) }
    }

    @Test
    fun `an unavailable stream that was never a call does not end the trace`() = runTest {
        brtc.connect(authParams)
        callStreamStarts("call-1")

        streamUnavailable("other")
        advance(inCallIntervalMs)

        assertEquals(0, logCount("Final in-call stats"))
        assertEquals(1, logCount("In-call stats:"))
    }

    @Test
    fun `with two streams only the last one to end stops the trace`() = runTest {
        brtc.connect(authParams)
        callStreamStarts("call-1")
        callStreamStarts("call-2")

        streamUnavailable("call-1")
        advance(inCallIntervalMs)
        assertEquals(0, logCount("Final in-call stats"))
        assertEquals(1, logCount("In-call stats:"))

        streamUnavailable("call-2")
        assertEquals(1, logCount("Final in-call stats (call ended):"))

        advance(inCallIntervalMs * 3)
        assertEquals(1, logCount("In-call stats:"))
    }

    @Test
    fun `a second stream joining a running call does not restart the trace`() = runTest {
        brtc.connect(authParams)
        callStreamStarts("call-1")
        advance(inCallIntervalMs / 2)
        callStreamStarts("call-2")

        advance(inCallIntervalMs / 2)

        // One timer, still on the first stream's schedule
        assertEquals(1, logCount("In-call stats:"))
    }

    @Test
    fun `a call after a call restarts the trace`() = runTest {
        brtc.connect(authParams)
        callStreamStarts("call-1")
        streamUnavailable("call-1")
        clearMocks(mockPCManager, answers = false, recordedCalls = true)

        callStreamStarts("call-2")
        advance(inCallIntervalMs)

        // The new call does not inherit the earlier call's baseline
        verify(exactly = 1) { mockPCManager.getCallStats(0, 0, 0.0, any()) }
    }

    @Test
    fun `disconnect during a call reads a final snapshot before the media is released`() = runTest {
        brtc.connect(authParams)
        callStreamStarts("call-1")
        advance(inCallIntervalMs)

        brtc.disconnect()

        verifyOrder {
            mockPCManager.getCallStats(0, 0, 0.0, any())
            mockPCManager.getCallStats(100, 0, 1.0, any())
            mockPCManager.cleanup()
        }
        // disconnect() runs the same teardown as the 5-minute trace, so it shares its reason
        assertEquals(1, logCount("Final in-call stats (session cleanup):"))

        advance(inCallIntervalMs * 3)
        assertEquals(1, logCount("In-call stats:"))
    }

    @Test
    fun `disconnect without a call reads no in-call snapshot`() = runTest {
        brtc.connect(authParams)

        brtc.disconnect()

        assertEquals(0, logCount("Final in-call stats"))
    }

    @Test
    fun `a socket close during a call reads a final snapshot before the media is released`() = runTest {
        brtc.connect(authParams)
        callStreamStarts("call-1")

        handlers["close"]?.invoke("1011")

        verifyOrder {
            mockPCManager.getCallStats(any(), any(), any(), any())
            mockPCManager.cleanup()
        }
        assertEquals(1, logCount("Final in-call stats (socket closed):"))
    }

    @Test
    fun `disconnect after a socket close ends the in-call trace once`() = runTest {
        brtc.connect(authParams)
        callStreamStarts("call-1")

        handlers["close"]?.invoke("1011")
        brtc.disconnect()

        assertEquals(1, logCount("Final in-call stats"))
    }

    @Test
    fun `a reconnect attempt that fails after a call stream appeared ends the trace as reconnect`() = runTest {
        every { mockPCManager.addLocalTracks(any()) } returns mockStream("local-1")
        every { mockPCManager.republishLocalStream(any(), any()) } returns mockStream("local-1")
        // The first reconnect attempt sees the call's track arrive, then fails while republishing
        var setupCalls = 0
        coEvery { mockSignaling.setMediaPreferences() } coAnswers {
            if (++setupCalls == 2) {
                callStreamStarts("call-1")
            }
            SetMediaPreferencesResult()
        }
        coEvery { mockSignaling.offerSdp(any(), any()) } returns OfferSdpResult("answer") andThenThrows
            RuntimeException("offer failed") andThen OfferSdpResult("answer")
        brtc.connect(authParams)
        brtc.publish(audio = true)

        handlers["close"]?.invoke("1001")
        advance(10_000)

        assertEquals(1, logCount("Final in-call stats (reconnect):"))
        // The reconnect that follows the failed attempt succeeds, with no call in it
        assertEquals(true, brtc.isConnected)
        advance(inCallIntervalMs * 3)
        assertEquals(0, logCount("In-call stats:"))
    }

    @Test
    fun `a call stream reported before the reconnect rebuilds the peer connections ends as reconnect`() = runTest {
        brtc.connect(authParams)
        handlers["close"]?.invoke("1001")

        // A late callback from the closed session, before the reconnect attempt runs. The old peer
        // connections are already gone, so the trace ends without a final read.
        callStreamStarts("stale-call")
        advance(10_000)

        assertEquals(1, logCount("Final in-call stats (reconnect)"))
        advance(inCallIntervalMs * 3)
        assertEquals(0, logCount("In-call stats:"))
    }

    @Test
    fun `ticks restart when the call's track returns after a reconnect`() = runTest {
        brtc.connect(authParams)
        callStreamStarts("call-1")
        handlers["close"]?.invoke("1001")
        advance(10_000)
        assertEquals(true, brtc.isConnected)
        assertEquals(1, logCount("Final in-call stats (socket closed):"))
        logged.clear()

        callStreamStarts("call-1")
        advance(inCallIntervalMs)

        assertEquals(1, logCount("In-call stats:"))
    }

    @Test
    fun `does not trace when the in-call interval is zero`() = runTest {
        brtc.inCallStatsTraceIntervalMs = 0
        brtc.connect(authParams)
        callStreamStarts("call-1")

        advance(inCallIntervalMs * 6)
        streamUnavailable("call-1")

        verify(exactly = 0) { mockPCManager.getCallStats(any(), any(), any(), any()) }
    }

    @Test
    fun `a read that never completes does not stall the trace or become a baseline`() = runTest {
        every { mockPCManager.getCallStats(any(), any(), any(), any()) } just Runs
        brtc.connect(authParams)
        callStreamStarts("call-1")

        // The second tick comes one interval after the first read timed out
        advance(inCallIntervalMs + 2_000)
        advance(inCallIntervalMs + 2_000)

        verify(exactly = 2) { mockPCManager.getCallStats(0, 0, 0.0, any()) }
    }

    @Test
    fun `the 5-minute trace is unaffected by the in-call trace`() = runTest {
        brtc.connect(authParams)
        callStreamStarts("call-1")

        advance(fiveMinutesMs)

        // Every 10 seconds for 5 minutes, plus one 5-minute read, each with its own baseline
        assertEquals(30, logCount("In-call stats:"))
        assertEquals(1, logCount("Call stats:"))
        verify(exactly = 2) { mockPCManager.getCallStats(0, 0, 0.0, any()) }
    }

    @Test
    fun `the 5-minute trace ticks without a call and is not changed by a call ending`() = runTest {
        brtc.connect(authParams)
        callStreamStarts("call-1")
        streamUnavailable("call-1")

        advance(fiveMinutesMs)

        assertEquals(1, logCount("Call stats:"))
        assertEquals(0, logCount("In-call stats:"))
    }
}
