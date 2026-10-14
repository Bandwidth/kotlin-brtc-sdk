package com.bandwidth.rtc.signaling

import com.bandwidth.rtc.types.RtcAuthParams
import com.bandwidth.rtc.util.Logger
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Sending the batched logs to the gateway as `deviceDiagnostics` notifications. */
class SignalingClientDiagnosticsTest {

    private val marker = "diag-client-test"
    private lateinit var mockWebSocket: WebSocketInterface
    private lateinit var batcher: DiagnosticsBatcher
    private lateinit var client: SignalingClient
    private val sent = CopyOnWriteArrayList<String>()

    private val authParams = RtcAuthParams(endpointToken = "test-token")

    @Before
    fun setUp() {
        mockWebSocket = mockk(relaxed = true)
        every { mockWebSocket.connect(any(), any()) } answers { secondArg<WebSocketEventListener>().onOpen() }
        every { mockWebSocket.send(any()) } answers { sent.add(firstArg()); true }
        batcher = DiagnosticsBatcher()
        client = SignalingClient(webSocketFactory = { mockWebSocket }, diagnostics = batcher)
    }

    @After
    fun tearDown() {
        batcher.stop()
        unmockkAll()
    }

    private fun diagnosticsMessages() = sent.filter { it.contains("\"deviceDiagnostics\"") }

    private fun waitForDiagnostics(): String {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            diagnosticsMessages().firstOrNull()?.let { return it }
            Thread.sleep(10)
        }
        throw AssertionError("No deviceDiagnostics notification was sent")
    }

    @Test
    fun `a flush sends a deviceDiagnostics notification with the logs`() = runTest {
        batcher.start()
        Logger.info("$marker hello")
        client.diagnosticsFlushIntervalMs = 50
        client.connect(authParams, null)

        val message = Json.parseToJsonElement(waitForDiagnostics()).jsonObject
        client.disconnect()

        assertEquals("2.0", message["jsonrpc"]?.jsonPrimitive?.content)
        assertEquals("deviceDiagnostics", message["method"]?.jsonPrimitive?.content)
        // A notification has no id, so the gateway sends no reply
        assertNull(message["id"])
        val logs: JsonArray = message["params"]!!.jsonObject["logs"]!!.jsonArray
        val entry = logs.map { it.jsonObject }.first { entryMessage(it) == "$marker hello" }
        assertEquals("info", entry["level"]?.jsonPrimitive?.content)
        val timestamp = entry["timestamp"]!!.jsonPrimitive
        assertFalse("timestamp must be a number", timestamp.isString)
        assertTrue(timestamp.content.toLong() > 0)
        assertEquals(listOf("$marker hello"), entry["message"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    private fun entryMessage(entry: JsonObject): String? =
        entry["message"]?.jsonArray?.singleOrNull()?.let { (it as JsonPrimitive).content }

    @Test
    fun `an empty batch is not sent`() = runTest {
        // Never started, so nothing is captured
        client.diagnosticsFlushIntervalMs = 20
        client.connect(authParams, null)

        Thread.sleep(200)
        client.disconnect()

        assertTrue(diagnosticsMessages().isEmpty())
    }

    @Test
    fun `disconnect sends the final batch before the socket closes`() = runTest {
        batcher.start()
        client.connect(authParams, null)
        Logger.info("$marker last words")

        client.disconnect()

        verifyOrder {
            mockWebSocket.send(match { it.contains("\"deviceDiagnostics\"") && it.contains("$marker last words") })
            mockWebSocket.close(any(), any())
        }
    }

    @Test
    fun `a failed final send is attempted once and does not stop disconnect`() = runTest {
        batcher.start()
        every { mockWebSocket.send(match { it.contains("\"deviceDiagnostics\"") }) } returns false
        client.connect(authParams, null)

        client.disconnect()

        verify(exactly = 1) { mockWebSocket.send(match { it.contains("\"deviceDiagnostics\"") }) }
        verify { mockWebSocket.close(any(), any()) }
        assertFalse(client.isConnected)
    }

    @Test
    fun `disconnect without a connection sends no diagnostics`() = runTest {
        batcher.start()
        Logger.info("$marker offline")

        client.disconnect()

        assertTrue(diagnosticsMessages().isEmpty())
    }

    @Test
    fun `logs captured while offline go out with the next connection's batch`() = runTest {
        batcher.start()
        Logger.info("$marker while offline")

        client.diagnosticsFlushIntervalMs = 50
        client.connect(authParams, null)
        val message = waitForDiagnostics()
        client.disconnect()

        assertTrue(message.contains("$marker while offline"))
    }

    @Test
    fun `the flush loop stops after disconnect`() = runTest {
        batcher.start()
        client.diagnosticsFlushIntervalMs = 20
        client.connect(authParams, null)
        client.disconnect()
        val before = diagnosticsMessages().size

        Logger.info("$marker after disconnect")
        Thread.sleep(150)

        assertEquals(before, diagnosticsMessages().size)
    }
}
