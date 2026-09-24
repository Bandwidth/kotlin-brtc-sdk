package com.bandwidth.rtc

import android.content.Context
import com.bandwidth.rtc.signaling.SignalingClientInterface
import com.bandwidth.rtc.signaling.rpc.OfferSdpResult
import com.bandwidth.rtc.signaling.rpc.SetMediaPreferencesResult
import com.bandwidth.rtc.types.*
import com.bandwidth.rtc.webrtc.PeerConnectionManagerInterface
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
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

        handlers["close"]?.invoke("1001")
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

        handlers["close"]?.invoke("1001")
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

        handlers["close"]?.invoke("1001")
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

        handlers["close"]?.invoke("1001")
        assertFalse(brtc.isConnected)

        advanceUntilIdle()

        assertTrue(brtc.isConnected)
        coVerify(exactly = 2) { mockSignaling.connect(authParams, null) }
    }

    @Test
    fun `close code 1001 is the only one that reconnects`() = runTest {
        val handlers = mutableMapOf<String, (String) -> Unit>()
        val brtc = buildBrtc(this, handlers)
        brtc.connect(authParams)

        handlers["close"]?.invoke("1001")
        advanceUntilIdle()

        coVerify(exactly = 2) { mockSignaling.connect(authParams, null) }
    }

    @Test
    fun `close code 1000 does not reconnect and surfaces an error`() = runTest {
        assertNonRetryableCloseCode(this, "1000")
    }

    @Test
    fun `close code 4409 (superseded by a newer connection) does not reconnect and surfaces an error`() = runTest {
        assertNonRetryableCloseCode(this, "4409")
    }

    @Test
    fun `close code 1011 (internal gateway error) does not reconnect and surfaces an error`() = runTest {
        assertNonRetryableCloseCode(this, "1011")
    }

    @Test
    fun `unrecognized close code does not reconnect and surfaces an error`() = runTest {
        assertNonRetryableCloseCode(this, "9999")
    }

    @Test
    fun `missing close code does not reconnect and surfaces an error`() = runTest {
        assertNonRetryableCloseCode(this, "")
    }

    /** Builds a fresh instance (and mocks) per call so verification counts aren't cross-contaminated. */
    private suspend fun assertNonRetryableCloseCode(scope: TestScope, code: String) {
        val ctx = mockk<Context>(relaxed = true)
        val sig = mockk<SignalingClientInterface>(relaxed = true)
        val pcm = mockk<PeerConnectionManagerInterface>(relaxed = true)
        val handlers = mutableMapOf<String, (String) -> Unit>()
        every { sig.onEvent(any(), any()) } answers { handlers[firstArg()] = secondArg() }
        coEvery { sig.setMediaPreferences() } returns SetMediaPreferencesResult()

        val brtc = BandwidthRTC(context = ctx, signaling = sig, peerConnectionManager = pcm, scope = scope)
        brtc.connect(authParams)

        var reported: Throwable? = null
        brtc.onError = { reported = it }

        handlers["close"]?.invoke(code)
        scope.advanceUntilIdle()

        assertFalse("code $code should leave the session disconnected", brtc.isConnected)
        assertNotNull("code $code should surface an error", reported)
        // Only the initial connect() - no reconnect attempt was made.
        coVerify(exactly = 1) { sig.connect(authParams, null) }
    }

    @Test
    fun `abnormal closure with no close frame (1006) does not reconnect`() = runTest {
        // OkHttp's onFailure carries no close code; SignalingClient synthesizes 1006 for it
        // (see CLOSE_CODE_ABNORMAL), matching the browser/rpc-websockets convention for an
        // abnormal closure so this SDK treats a network drop the same non-retryable way as JS.
        val handlers = mutableMapOf<String, (String) -> Unit>()
        val brtc = buildBrtc(this, handlers)
        brtc.connect(authParams)

        var reported: Throwable? = null
        brtc.onError = { reported = it }

        handlers["close"]?.invoke("1006")
        advanceUntilIdle()

        assertFalse(brtc.isConnected)
        assertNotNull(reported)
        coVerify(exactly = 1) { mockSignaling.connect(authParams, null) }
    }

    @Test
    fun `non-retryable close does not surface an error when disconnect was application initiated`() = runTest {
        val handlers = mutableMapOf<String, (String) -> Unit>()
        val brtc = buildBrtc(this, handlers)
        brtc.connect(authParams)
        brtc.disconnect()

        var reported: Throwable? = null
        brtc.onError = { reported = it }

        // A close racing disconnect() would already be ignored (this.signaling was replaced with
        // null), but assert the outcome directly rather than relying on that timing.
        handlers["close"]?.invoke("1000")
        advanceUntilIdle()

        assertNull(reported)
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

        handlers["close"]?.invoke("1001")
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

        handlers["close"]?.invoke("1001")
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

        handlers["close"]?.invoke("1001")
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

        handlers["close"]?.invoke("1001")
        advanceUntilIdle()

        assertTrue(reported is BandwidthRTCError.PublishFailed)
    }

    @Test
    fun `repeated reconnects do not leak peer connection managers`() = runTest {
        val handlers = mutableMapOf<String, (String) -> Unit>()
        val brtc = buildBrtc(this, handlers)
        brtc.connect(authParams)

        repeat(3) {
            handlers["close"]?.invoke("1001")
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
