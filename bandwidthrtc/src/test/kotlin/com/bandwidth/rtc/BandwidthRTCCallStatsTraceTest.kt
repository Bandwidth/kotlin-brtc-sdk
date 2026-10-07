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

        scheduler.advanceTimeBy(traceIntervalMs * 2)
        scheduler.runCurrent()

        verify(exactly = 0) { mockPCManager.getCallStats(any(), any(), any(), any()) }
    }
}
