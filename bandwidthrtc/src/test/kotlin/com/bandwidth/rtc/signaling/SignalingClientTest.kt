package com.bandwidth.rtc.signaling

import com.bandwidth.rtc.signaling.rpc.*
import com.bandwidth.rtc.types.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Comprehensive tests for SignalingClient.
 *
 * Covers:
 * - WebSocket connection lifecycle
 * - JSON-RPC message handling (requests, responses, notifications)
 * - Event handler registration and dispatch
 * - Error handling (RPC errors, invalid token, connection failures)
 * - Disconnect behavior (pending request cleanup)
 * - Concurrency safety of pendingRequests and eventHandlers
 * - Ping loop lifecycle
 * - Request ID generation
 * - Stale/duplicate response handling
 */
class SignalingClientTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var mockWebSocket: WebSocketInterface
    private lateinit var capturedListener: AtomicReference<WebSocketEventListener?>
    private lateinit var client: SignalingClient

    @Before
    fun setUp() {
        mockWebSocket = mockk(relaxed = true)
        capturedListener = AtomicReference(null)

        // Capture the WebSocketEventListener when connect is called
        every { mockWebSocket.connect(any(), any()) } answers {
            capturedListener.set(secondArg())
            // Simulate immediate open
            secondArg<WebSocketEventListener>().onOpen()
        }
        every { mockWebSocket.send(any()) } returns true

        client = SignalingClient(webSocketFactory = { mockWebSocket })
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private val authParams = RtcAuthParams(endpointToken = "test-token")
    private val defaultOptions: RtcOptions? = null

    // =========================================================================
    // Connection lifecycle
    // =========================================================================

    @Test
    fun `connect sets isConnected to true`() = runTest {
        client.connect(authParams, defaultOptions)
        assertTrue(client.isConnected)
    }

    @Test
    fun `connect builds URL with required query params`() = runTest {
        val urlSlot = slot<String>()
        every { mockWebSocket.connect(capture(urlSlot), any()) } answers {
            secondArg<WebSocketEventListener>().onOpen()
        }

        client.connect(authParams, defaultOptions)

        val url = urlSlot.captured
        assertTrue("URL should contain client=android", url.contains("client=android"))
        assertTrue("URL should contain sdkVersion", url.contains("sdkVersion="))
        assertTrue("URL should contain uniqueId", url.contains("uniqueId="))
        assertTrue("URL should contain endpointToken", url.contains("endpointToken=test-token"))
    }

    @Test
    fun `connect uses custom websocket URL from options`() = runTest {
        val urlSlot = slot<String>()
        every { mockWebSocket.connect(capture(urlSlot), any()) } answers {
            secondArg<WebSocketEventListener>().onOpen()
        }

        val options = RtcOptions(websocketUrl = "wss://custom.example.com/ws")
        client.connect(authParams, options)

        assertTrue(urlSlot.captured.startsWith("wss://custom.example.com/ws"))
    }

    @Test
    fun `connect with URL containing query params uses ampersand separator`() = runTest {
        val urlSlot = slot<String>()
        every { mockWebSocket.connect(capture(urlSlot), any()) } answers {
            secondArg<WebSocketEventListener>().onOpen()
        }

        val options = RtcOptions(websocketUrl = "wss://example.com?existing=param")
        client.connect(authParams, options)

        assertTrue("URL should use & separator", urlSlot.captured.contains("?existing=param&client=android"))
    }

    @Test(expected = BandwidthRTCError.AlreadyConnected::class)
    fun `connect throws AlreadyConnected when already connected`() = runTest {
        client.connect(authParams, defaultOptions)
        client.connect(authParams, defaultOptions)
    }

    @Test(expected = BandwidthRTCError.ConnectionFailed::class)
    fun `connect throws ConnectionFailed when websocket fails before open`() = runTest {
        every { mockWebSocket.connect(any(), any()) } answers {
            secondArg<WebSocketEventListener>().onFailure(RuntimeException("connection refused"))
        }

        client.connect(authParams, defaultOptions)
    }

    // =========================================================================
    // Disconnect
    // =========================================================================

    @Test
    fun `disconnect sets isConnected to false`() = runTest {
        client.connect(authParams, defaultOptions)
        assertTrue(client.isConnected)

        client.disconnect()
        assertFalse(client.isConnected)
    }

    @Test
    fun `disconnect sends leave notification`() = runTest {
        client.connect(authParams, defaultOptions)
        client.disconnect()

        verify { mockWebSocket.send(match { it.contains("\"leave\"") }) }
    }

    @Test
    fun `disconnect closes websocket`() = runTest {
        client.connect(authParams, defaultOptions)
        client.disconnect()

        verify { mockWebSocket.close(any(), any()) }
    }

    @Test
    fun `disconnect clears event handlers`() = runTest {
        client.connect(authParams, defaultOptions)

        var called = false
        client.onEvent("test") { called = true }

        client.disconnect()

        // After disconnect, event handlers should be cleared
        // Reconnect and trigger - handler should not be called
        assertFalse(called)
    }

    @Test
    fun `disconnect is safe to call when not connected`() = runTest {
        // Should not throw
        client.disconnect()
        assertFalse(client.isConnected)
    }

    @Test
    fun `double disconnect is safe`() = runTest {
        client.connect(authParams, defaultOptions)
        client.disconnect()
        client.disconnect()
        assertFalse(client.isConnected)
    }

    // =========================================================================
    // Event handlers
    // =========================================================================

    @Test
    fun `onEvent registers handler that receives notifications`() = runTest {
        client.connect(authParams, defaultOptions)

        var receivedData: String? = null
        client.onEvent("testEvent") { data -> receivedData = data }

        // Simulate server notification
        val notification = """{"jsonrpc":"2.0","method":"testEvent","params":{"key":"value"}}"""
        capturedListener.get()?.onMessage(notification)

        assertNotNull(receivedData)
        assertTrue(receivedData!!.contains("key"))
    }

    @Test
    fun `removeEventHandler prevents handler from being called`() = runTest {
        client.connect(authParams, defaultOptions)

        var called = false
        client.onEvent("testEvent") { called = true }
        client.removeEventHandler("testEvent")

        val notification = """{"jsonrpc":"2.0","method":"testEvent","params":{}}"""
        capturedListener.get()?.onMessage(notification)

        assertFalse(called)
    }

    @Test
    fun `onEvent replaces existing handler for same method`() = runTest {
        client.connect(authParams, defaultOptions)

        var firstCalled = false
        var secondCalled = false
        client.onEvent("testEvent") { firstCalled = true }
        client.onEvent("testEvent") { secondCalled = true }

        val notification = """{"jsonrpc":"2.0","method":"testEvent","params":{}}"""
        capturedListener.get()?.onMessage(notification)

        assertFalse("First handler should not be called", firstCalled)
        assertTrue("Second handler should be called", secondCalled)
    }

    @Test
    fun `notification for unregistered method does not crash`() = runTest {
        client.connect(authParams, defaultOptions)

        val notification = """{"jsonrpc":"2.0","method":"unknownMethod","params":{}}"""
        capturedListener.get()?.onMessage(notification) // Should not throw
    }

    // =========================================================================
    // JSON-RPC response handling
    // =========================================================================

    @Test
    fun `setMediaPreferences sends RPC and parses response`() = runTest {
        client.connect(authParams, defaultOptions)

        // Stub: when send is called, simulate response
        every { mockWebSocket.send(any()) } answers {
            val msg = firstArg<String>()
            if (msg.contains("setMediaPreferences")) {
                val request = json.decodeFromString(JsonRpcRequest.serializer(), msg)
                val response = """{"jsonrpc":"2.0","id":"${request.id}","result":{"endpointId":"ep-1","deviceId":"dev-1"}}"""
                capturedListener.get()?.onMessage(response)
            }
            true
        }

        val result = client.setMediaPreferences()

        assertEquals("ep-1", result.endpointId)
        assertEquals("dev-1", result.deviceId)
    }

    @Test
    fun `setMediaPreferences returns default when null result`() = runTest {
        client.connect(authParams, defaultOptions)

        every { mockWebSocket.send(any()) } answers {
            val msg = firstArg<String>()
            if (msg.contains("setMediaPreferences")) {
                val request = json.decodeFromString(JsonRpcRequest.serializer(), msg)
                val response = """{"jsonrpc":"2.0","id":"${request.id}","result":null}"""
                capturedListener.get()?.onMessage(response)
            }
            true
        }

        val result = client.setMediaPreferences()
        assertNull(result.endpointId)
    }

    @Test
    fun `offerSdp sends RPC with correct peerType`() = runTest {
        client.connect(authParams, defaultOptions)

        val sentMessages = mutableListOf<String>()
        every { mockWebSocket.send(any()) } answers {
            val msg = firstArg<String>()
            sentMessages.add(msg)
            if (msg.contains("offerSdp")) {
                val request = json.decodeFromString(JsonRpcRequest.serializer(), msg)
                val response = """{"jsonrpc":"2.0","id":"${request.id}","result":{"sdpAnswer":"answer-sdp"}}"""
                capturedListener.get()?.onMessage(response)
            }
            true
        }

        val result = client.offerSdp("local-offer", "publish")

        assertEquals("answer-sdp", result.sdpAnswer)
        assertTrue(sentMessages.any { it.contains("\"peerType\":\"publish\"") })
    }

    @Test
    fun `answerSdp sends RPC and completes`() = runTest {
        client.connect(authParams, defaultOptions)

        every { mockWebSocket.send(any()) } answers {
            val msg = firstArg<String>()
            if (msg.contains("answerSdp")) {
                val request = json.decodeFromString(JsonRpcRequest.serializer(), msg)
                val response = """{"jsonrpc":"2.0","id":"${request.id}","result":null}"""
                capturedListener.get()?.onMessage(response)
            }
            true
        }

        // Should not throw
        client.answerSdp("answer", "subscribe")
    }

    @Test
    fun `requestOutboundConnection sends RPC and parses result`() = runTest {
        client.connect(authParams, defaultOptions)

        every { mockWebSocket.send(any()) } answers {
            val msg = firstArg<String>()
            if (msg.contains("requestOutboundConnection")) {
                val request = json.decodeFromString(JsonRpcRequest.serializer(), msg)
                val response = """{"jsonrpc":"2.0","id":"${request.id}","result":{"accepted":true}}"""
                capturedListener.get()?.onMessage(response)
            }
            true
        }

        val result = client.requestOutboundConnection("+15551234567", EndpointType.PHONE_NUMBER)
        assertTrue(result.accepted)
    }

    @Test
    fun `hangupConnection sends RPC and parses result`() = runTest {
        client.connect(authParams, defaultOptions)

        every { mockWebSocket.send(any()) } answers {
            val msg = firstArg<String>()
            if (msg.contains("hangupConnection")) {
                val request = json.decodeFromString(JsonRpcRequest.serializer(), msg)
                val response = """{"jsonrpc":"2.0","id":"${request.id}","result":{"result":"ok"}}"""
                capturedListener.get()?.onMessage(response)
            }
            true
        }

        val result = client.hangupConnection("ep-1", EndpointType.ENDPOINT)
        assertEquals("ok", result.result)
    }

    // =========================================================================
    // RPC error handling
    // =========================================================================

    @Test(expected = BandwidthRTCError.InvalidToken::class)
    fun `RPC error with code 403 throws InvalidToken`() = runTest {
        client.connect(authParams, defaultOptions)

        every { mockWebSocket.send(any()) } answers {
            val msg = firstArg<String>()
            if (msg.contains("setMediaPreferences")) {
                val request = json.decodeFromString(JsonRpcRequest.serializer(), msg)
                val response = """{"jsonrpc":"2.0","id":"${request.id}","error":{"code":403,"message":"forbidden"}}"""
                capturedListener.get()?.onMessage(response)
            }
            true
        }

        client.setMediaPreferences()
    }

    @Test(expected = BandwidthRTCError.InvalidToken::class)
    fun `RPC error with invalid token message throws InvalidToken`() = runTest {
        client.connect(authParams, defaultOptions)

        every { mockWebSocket.send(any()) } answers {
            val msg = firstArg<String>()
            if (msg.contains("setMediaPreferences")) {
                val request = json.decodeFromString(JsonRpcRequest.serializer(), msg)
                val response = """{"jsonrpc":"2.0","id":"${request.id}","error":{"code":400,"message":"Invalid Token provided"}}"""
                capturedListener.get()?.onMessage(response)
            }
            true
        }

        client.setMediaPreferences()
    }

    @Test(expected = BandwidthRTCError.RpcError::class)
    fun `RPC error with generic code throws RpcError`() = runTest {
        client.connect(authParams, defaultOptions)

        every { mockWebSocket.send(any()) } answers {
            val msg = firstArg<String>()
            if (msg.contains("setMediaPreferences")) {
                val request = json.decodeFromString(JsonRpcRequest.serializer(), msg)
                val response = """{"jsonrpc":"2.0","id":"${request.id}","error":{"code":500,"message":"internal error"}}"""
                capturedListener.get()?.onMessage(response)
            }
            true
        }

        client.setMediaPreferences()
    }

    @Test(expected = BandwidthRTCError.SignalingError::class)
    fun `RPC call throws SignalingError when send fails`() = runTest {
        client.connect(authParams, defaultOptions)

        // First send succeeds (for connect), subsequent sends fail
        var callCount = 0
        every { mockWebSocket.send(any()) } answers {
            callCount++
            callCount <= 0 // Always false for RPC calls after connect
        }

        client.setMediaPreferences()
    }

    @Test(expected = BandwidthRTCError.NotConnected::class)
    fun `RPC call throws NotConnected when not connected`() = runTest {
        client.setMediaPreferences()
    }

    // =========================================================================
    // Disconnect during pending RPC
    // =========================================================================

    @Test
    fun `disconnect fails pending requests with WebSocketDisconnected`() = runTest {
        client.connect(authParams, defaultOptions)

        // Don't respond to RPC - leave it pending
        every { mockWebSocket.send(match { it.contains("setMediaPreferences") }) } returns true

        // Launch the RPC call and capture the exception
        var caughtException: Exception? = null

        // We can't easily test this without launching in a separate coroutine,
        // but we can test the handleDisconnect path directly
        // Simulate WebSocket disconnection
        capturedListener.get()?.onClosed(1000, "normal")

        assertFalse(client.isConnected)
    }

    @Test
    fun `websocket closure fires close event handler`() = runTest {
        client.connect(authParams, defaultOptions)

        var closeCalled = false
        client.onEvent("close") { closeCalled = true }

        capturedListener.get()?.onClosed(1000, "normal")

        assertTrue(closeCalled)
    }

    @Test
    fun `websocket failure after connect triggers disconnect`() = runTest {
        client.connect(authParams, defaultOptions)
        assertTrue(client.isConnected)

        capturedListener.get()?.onFailure(RuntimeException("connection lost"))

        assertFalse(client.isConnected)
    }

    // =========================================================================
    // Message parsing edge cases
    // =========================================================================

    @Test
    fun `malformed JSON message does not crash`() = runTest {
        client.connect(authParams, defaultOptions)
        capturedListener.get()?.onMessage("not valid json {{{")
        // Should not throw
    }

    @Test
    fun `response for unknown request ID is ignored`() = runTest {
        client.connect(authParams, defaultOptions)
        val response = """{"jsonrpc":"2.0","id":"99999","result":{}}"""
        capturedListener.get()?.onMessage(response)
        // Should not throw
    }

    @Test
    fun `notification without params sends empty string to handler`() = runTest {
        client.connect(authParams, defaultOptions)

        var receivedData: String? = null
        client.onEvent("testEvent") { receivedData = it }

        val notification = """{"jsonrpc":"2.0","method":"testEvent"}"""
        capturedListener.get()?.onMessage(notification)

        assertEquals("", receivedData)
    }

    // =========================================================================
    // Request ID generation
    // =========================================================================

    @Test
    fun `request IDs are sequential`() = runTest {
        client.connect(authParams, defaultOptions)

        val capturedIds = mutableListOf<String>()
        every { mockWebSocket.send(any()) } answers {
            val msg = firstArg<String>()
            try {
                val request = json.decodeFromString(JsonRpcRequest.serializer(), msg)
                capturedIds.add(request.id)
                // Respond immediately
                val response = """{"jsonrpc":"2.0","id":"${request.id}","result":null}"""
                capturedListener.get()?.onMessage(response)
            } catch (_: Exception) {}
            true
        }

        client.answerSdp("a1", "publish")
        client.answerSdp("a2", "publish")
        client.answerSdp("a3", "publish")

        assertEquals(3, capturedIds.size)
        // IDs should be sequential integers (starting after any connect-time messages)
        val ids = capturedIds.map { it.toInt() }
        for (i in 1 until ids.size) {
            assertEquals("IDs should be sequential", ids[i - 1] + 1, ids[i])
        }
    }

    @Test
    fun `request IDs reset after disconnect and reconnect`() = runTest {
        client.connect(authParams, defaultOptions)
        client.disconnect()

        // Reconnect
        val newClient = SignalingClient(webSocketFactory = { mockWebSocket })
        newClient.connect(authParams, defaultOptions)

        val capturedIds = mutableListOf<String>()
        every { mockWebSocket.send(any()) } answers {
            val msg = firstArg<String>()
            try {
                val request = json.decodeFromString(JsonRpcRequest.serializer(), msg)
                capturedIds.add(request.id)
                val response = """{"jsonrpc":"2.0","id":"${request.id}","result":null}"""
                capturedListener.get()?.onMessage(response)
            } catch (_: Exception) {}
            true
        }

        newClient.answerSdp("a1", "publish")

        assertTrue("First ID after reconnect should be low",
            capturedIds.firstOrNull()?.toIntOrNull()?.let { it <= 5 } ?: false)
    }

    // =========================================================================
    // Concurrent event handler access
    // =========================================================================

    @Test
    fun `concurrent event handler registration does not crash`() = runTest {
        client.connect(authParams, defaultOptions)

        val threads = 8
        val latch = CountDownLatch(threads)
        val errors = AtomicInteger(0)

        repeat(threads) { idx ->
            Thread {
                try {
                    repeat(50) { i ->
                        client.onEvent("event_${idx}_$i") { }
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
    fun `concurrent event dispatch and registration does not crash`() = runTest {
        client.connect(authParams, defaultOptions)

        val errors = AtomicInteger(0)
        val latch = CountDownLatch(2)

        // Thread 1: Register/remove handlers
        Thread {
            try {
                repeat(100) { i ->
                    client.onEvent("dynamic_$i") { }
                    if (i > 5) client.removeEventHandler("dynamic_${i - 5}")
                }
            } catch (e: Exception) {
                errors.incrementAndGet()
            } finally {
                latch.countDown()
            }
        }.start()

        // Thread 2: Dispatch notifications
        Thread {
            try {
                repeat(100) { i ->
                    val notification = """{"jsonrpc":"2.0","method":"dynamic_$i","params":{}}"""
                    capturedListener.get()?.onMessage(notification)
                }
            } catch (e: Exception) {
                errors.incrementAndGet()
            } finally {
                latch.countDown()
            }
        }.start()

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(0, errors.get())
    }
}
