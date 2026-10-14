package com.bandwidth.rtc

import android.content.Context
import com.bandwidth.rtc.signaling.SignalingClientInterface
import com.bandwidth.rtc.signaling.rpc.SetMediaPreferencesResult
import com.bandwidth.rtc.types.*
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

/** The periodic call stats trace that runs while a session is connected. */
@OptIn(ExperimentalCoroutinesApi::class)
class BandwidthRTCCallStatsTraceTest {

    private val traceIntervalMs = 5 * 60 * 1_000L
    private val readTimeoutMs = 2_000L

    private lateinit var mockSignaling: SignalingClientInterface
    private lateinit var mockPCManager: PeerConnectionManagerInterface
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var brtc: BandwidthRTC

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
        coEvery { mockSignaling.setMediaPreferences() } returns SetMediaPreferencesResult()
        every { mockPCManager.getCallStats(any(), any(), any(), any()) } answers {
            arg<(CallStatsSnapshot) -> Unit>(3).invoke(CallStatsSnapshot(bytesReceived = 100, timestamp = 1.0))
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `collects stats every 5 minutes while connected`() = runTest {
        brtc.connect(authParams)

        scheduler.advanceTimeBy(traceIntervalMs - 1)
        scheduler.runCurrent()
        verify(exactly = 0) { mockPCManager.getCallStats(any(), any(), any(), any()) }

        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        verify(exactly = 1) { mockPCManager.getCallStats(any(), any(), any(), any()) }

        scheduler.advanceTimeBy(traceIntervalMs)
        scheduler.runCurrent()
        verify(exactly = 2) { mockPCManager.getCallStats(any(), any(), any(), any()) }
    }

    @Test
    fun `passes the previous traced snapshot so bitrates can be computed`() = runTest {
        brtc.connect(authParams)

        scheduler.advanceTimeBy(traceIntervalMs * 2)
        scheduler.runCurrent()

        verify(exactly = 1) { mockPCManager.getCallStats(0, 0, 0.0, any()) }
        verify(exactly = 1) { mockPCManager.getCallStats(100, 0, 1.0, any()) }
    }

    @Test
    fun `does not fire onRemoteAudioLevel`() = runTest {
        var fired = false
        brtc.onRemoteAudioLevel = { fired = true }
        brtc.connect(authParams)

        scheduler.advanceTimeBy(traceIntervalMs)
        scheduler.runCurrent()

        assertEquals(false, fired)
    }

    @Test
    fun `stops tracing after disconnect`() = runTest {
        brtc.connect(authParams)
        brtc.disconnect()
        // The only read after disconnect() is the final snapshot
        verify(exactly = 1) { mockPCManager.getCallStats(any(), any(), any(), any()) }

        scheduler.advanceTimeBy(traceIntervalMs * 2)
        scheduler.runCurrent()

        verify(exactly = 1) { mockPCManager.getCallStats(any(), any(), any(), any()) }
    }

    @Test
    fun `reads a final snapshot on disconnect before the media is released`() = runTest {
        brtc.connect(authParams)
        scheduler.advanceTimeBy(traceIntervalMs)
        scheduler.runCurrent()

        brtc.disconnect()

        // The final read carries the last periodic snapshot as its baseline
        verifyOrder {
            mockPCManager.getCallStats(0, 0, 0.0, any())
            mockPCManager.getCallStats(100, 0, 1.0, any())
            mockPCManager.cleanup()
        }
    }

    @Test
    fun `reads a final snapshot when the socket closes without disconnect`() = runTest {
        val closeHandler = slot<(String) -> Unit>()
        every { mockSignaling.onEvent("close", capture(closeHandler)) } just Runs
        brtc.connect(authParams)

        closeHandler.captured.invoke("1011")

        verifyOrder {
            mockPCManager.getCallStats(any(), any(), any(), any())
            mockPCManager.cleanup()
        }
        scheduler.advanceTimeBy(traceIntervalMs * 2)
        scheduler.runCurrent()
        verify(exactly = 1) { mockPCManager.getCallStats(any(), any(), any(), any()) }
    }

    @Test
    fun `reads the final snapshot once when disconnect follows a socket close`() = runTest {
        val closeHandler = slot<(String) -> Unit>()
        every { mockSignaling.onEvent("close", capture(closeHandler)) } just Runs
        brtc.connect(authParams)

        closeHandler.captured.invoke("1011")
        brtc.disconnect()

        verify(exactly = 1) { mockPCManager.getCallStats(any(), any(), any(), any()) }
    }

    @Test
    fun `reads no final snapshot when no trace started`() = runTest {
        brtc.disconnect()

        verify(exactly = 0) { mockPCManager.getCallStats(any(), any(), any(), any()) }
    }

    @Test
    fun `a read that never completes does not stall disconnect or become a baseline`() = runTest {
        every { mockPCManager.getCallStats(any(), any(), any(), any()) } just Runs
        brtc.connect(authParams)

        // The second tick comes one interval after the first read timed out
        scheduler.advanceTimeBy(traceIntervalMs + readTimeoutMs)
        scheduler.runCurrent()
        scheduler.advanceTimeBy(traceIntervalMs + readTimeoutMs)
        scheduler.runCurrent()

        // Both periodic reads timed out, so neither is passed on as a baseline
        verify(exactly = 2) { mockPCManager.getCallStats(0, 0, 0.0, any()) }
        brtc.disconnect()
        coVerify { mockSignaling.disconnect() }
    }

    @Test
    fun `a new session starts without the previous session's baseline`() = runTest {
        brtc.connect(authParams)
        scheduler.advanceTimeBy(traceIntervalMs)
        scheduler.runCurrent()
        brtc.disconnect()
        clearMocks(mockPCManager, answers = false, recordedCalls = true)

        brtc.connect(authParams)
        scheduler.advanceTimeBy(traceIntervalMs)
        scheduler.runCurrent()

        verify(exactly = 1) { mockPCManager.getCallStats(0, 0, 0.0, any()) }
    }
}
