package com.bandwidth.rtc

import android.content.Context
import com.bandwidth.rtc.signaling.SignalingClientInterface
import com.bandwidth.rtc.signaling.rpc.OfferSdpResult
import com.bandwidth.rtc.signaling.rpc.SetMediaPreferencesResult
import com.bandwidth.rtc.types.*
import com.bandwidth.rtc.webrtc.PeerConnectionManagerInterface
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.webrtc.MediaStream

/**
 * Reconnect and republish behaviour after a server-initiated websocket close.
 *
 * The platform only considers an endpoint eligible for calls once it sees RTP on the publishing
 * peer connection, so a reconnect that does not re-publish local media leaves the session alive but
 * permanently unable to place a call.
 */
class BandwidthRTCReconnectTest {

    private lateinit var context: Context
    private lateinit var mockSignaling: SignalingClientInterface
    private lateinit var mockPCManager: PeerConnectionManagerInterface

    private val authParams = RtcAuthParams(endpointToken = "test-token")

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        mockSignaling = mockk(relaxed = true)
        mockPCManager = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // -------------------------------------------------------------------------
    // Replay of published streams
    // -------------------------------------------------------------------------

    @Test
    fun `first connect does not replay anything`() = runTest {
        val handlers = mutableMapOf<String, (String) -> Unit>()
        val brtc = buildBrtc(this, handlers)

        brtc.connect(authParams)
        advanceUntilIdle()

        verify(exactly = 0) { mockPCManager.republishLocalStream(any(), any()) }
        coVerify(exactly = 0) { mockSignaling.offerSdp(any(), any()) }
    }

    @Test
    fun `reconnect with nothing published is a no-op replay`() = runTest {
        val handlers = mutableMapOf<String, (String) -> Unit>()
        val brtc = buildBrtc(this, handlers)
        brtc.connect(authParams)

        handlers["close"]?.invoke("")
        advanceUntilIdle()

        assertTrue(brtc.isConnected)
        verify(exactly = 0) { mockPCManager.republishLocalStream(any(), any()) }
        coVerify(exactly = 0) { mockSignaling.offerSdp(any(), any()) }
    }

    @Test
    fun `reconnect republishes retained streams with a single renegotiation`() = runTest {
        val handlers = mutableMapOf<String, (String) -> Unit>()
        val brtc = buildBrtc(this, handlers)
        brtc.connect(authParams)

        every { mockPCManager.addLocalTracks(any()) } returnsMany
            listOf(buildMockMediaStream("stream-1"), buildMockMediaStream("stream-2"))
        every { mockPCManager.republishLocalStream(any(), any()) } returns buildMockMediaStream("stream-new")

        brtc.publish(audio = true, alias = "first")
        brtc.publish(audio = true, alias = "second")
        clearMocks(mockSignaling, answers = false, recordedCalls = true)

        handlers["close"]?.invoke("")
        advanceUntilIdle()

        verify(exactly = 1) { mockPCManager.republishLocalStream("stream-1", true) }
        verify(exactly = 1) { mockPCManager.republishLocalStream("stream-2", true) }
        // One renegotiation covers both streams, not one per stream.
        coVerify(exactly = 1) { mockSignaling.offerSdp(any(), "publish") }
    }

    @Test
    fun `unpublished streams are not replayed on reconnect`() = runTest {
        val handlers = mutableMapOf<String, (String) -> Unit>()
        val brtc = buildBrtc(this, handlers)
        brtc.connect(authParams)

        every { mockPCManager.addLocalTracks(any()) } returns buildMockMediaStream("stream-1")
        val stream = brtc.publish(audio = true)
        brtc.unpublish(stream)

        handlers["close"]?.invoke("")
        advanceUntilIdle()

        verify(exactly = 0) { mockPCManager.republishLocalStream(any(), any()) }
    }

    // -------------------------------------------------------------------------
    // Reconnect triggering and suppression
    // -------------------------------------------------------------------------

    @Test
    fun `server initiated close reconnects`() = runTest {
        val handlers = mutableMapOf<String, (String) -> Unit>()
        val brtc = buildBrtc(this, handlers)
        brtc.connect(authParams)

        handlers["close"]?.invoke("")
        assertFalse(brtc.isConnected)

        advanceUntilIdle()

        assertTrue(brtc.isConnected)
        coVerify(exactly = 2) { mockSignaling.connect(authParams, null) }
    }

    @Test
    fun `close releases the peer connection manager`() = runTest {
        val handlers = mutableMapOf<String, (String) -> Unit>()
        val brtc = buildBrtc(this, handlers)
        brtc.connect(authParams)

        handlers["close"]?.invoke("")

        verify(exactly = 1) { mockPCManager.cleanup() }
    }

    @Test
    fun `no reconnect after application initiated disconnect`() = runTest {
        val handlers = mutableMapOf<String, (String) -> Unit>()
        val brtc = buildBrtc(this, handlers)
        brtc.connect(authParams)
        brtc.disconnect()

        handlers["close"]?.invoke("")
        advanceUntilIdle()

        assertFalse(brtc.isConnected)
        coVerify(exactly = 1) { mockSignaling.connect(any(), any()) }
    }

    @Test
    fun `reconnect stops on invalid token and reports the error`() = runTest {
        val handlers = mutableMapOf<String, (String) -> Unit>()
        val brtc = buildBrtc(this, handlers)
        brtc.connect(authParams)

        var reported: Throwable? = null
        brtc.onError = { reported = it }
        coEvery { mockSignaling.connect(any(), any()) } throws BandwidthRTCError.InvalidToken()

        handlers["close"]?.invoke("")
        advanceUntilIdle()

        // One failed attempt only - a bad token will not become valid on a retry.
        coVerify(exactly = 2) { mockSignaling.connect(any(), any()) }
        assertTrue(reported is BandwidthRTCError.InvalidToken)
    }

    @Test
    fun `reconnect stops when the endpoint is already connected`() = runTest {
        val handlers = mutableMapOf<String, (String) -> Unit>()
        val brtc = buildBrtc(this, handlers)
        brtc.connect(authParams)

        var reported: Throwable? = null
        brtc.onError = { reported = it }
        coEvery { mockSignaling.connect(any(), any()) } throws
            BandwidthRTCError.RpcError(409, "Endpoint already connected")

        handlers["close"]?.invoke("")
        advanceUntilIdle()

        coVerify(exactly = 2) { mockSignaling.connect(any(), any()) }
        assertEquals(409, (reported as BandwidthRTCError.RpcError).code)
    }

    @Test
    fun `exhausted reconnect reports an error instead of failing silently`() = runTest {
        val handlers = mutableMapOf<String, (String) -> Unit>()
        val brtc = buildBrtc(this, handlers)
        brtc.connect(authParams)

        var reported: Throwable? = null
        brtc.onError = { reported = it }
        coEvery { mockSignaling.connect(any(), any()) } throws
            BandwidthRTCError.ConnectionFailed("network down")

        handlers["close"]?.invoke("")
        advanceUntilIdle()

        assertFalse(brtc.isConnected)
        assertTrue(reported is BandwidthRTCError.ConnectionFailed)
    }

    @Test
    fun `republish failure surfaces an error`() = runTest {
        val handlers = mutableMapOf<String, (String) -> Unit>()
        val brtc = buildBrtc(this, handlers)
        brtc.connect(authParams)

        every { mockPCManager.addLocalTracks(any()) } returns buildMockMediaStream("stream-1")
        brtc.publish(audio = true)

        var reported: Throwable? = null
        brtc.onError = { reported = it }
        every { mockPCManager.republishLocalStream(any(), any()) } throws
            BandwidthRTCError.PublishFailed("no publishing peer connection")

        handlers["close"]?.invoke("")
        advanceUntilIdle()

        assertTrue(reported is BandwidthRTCError.PublishFailed)
    }

    @Test
    fun `repeated reconnects do not leak peer connection managers`() = runTest {
        val handlers = mutableMapOf<String, (String) -> Unit>()
        val brtc = buildBrtc(this, handlers)
        brtc.connect(authParams)

        repeat(3) {
            handlers["close"]?.invoke("")
            advanceUntilIdle()
        }

        // Every close releases the manager it was holding before a new session is built.
        verify(exactly = 3) { mockPCManager.cleanup() }
        assertTrue(brtc.isConnected)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Builds an instance wired to the mocks, capturing the signaling event handlers it registers. */
    private fun buildBrtc(
        scope: CoroutineScope,
        handlers: MutableMap<String, (String) -> Unit>
    ): BandwidthRTC {
        every { mockSignaling.onEvent(any(), any()) } answers { handlers[firstArg()] = secondArg() }
        coEvery { mockSignaling.setMediaPreferences() } returns SetMediaPreferencesResult()
        coEvery { mockSignaling.offerSdp(any(), any()) } returns OfferSdpResult("answer")
        coEvery { mockPCManager.createPublishOffer() } returns "offer"
        return BandwidthRTC(
            context = context,
            signaling = mockSignaling,
            peerConnectionManager = mockPCManager,
            scope = scope
        )
    }

    private fun buildMockMediaStream(id: String): MediaStream {
        val stream = mockk<MediaStream>(relaxed = true)
        every { stream.id } returns id
        return stream
    }
}
