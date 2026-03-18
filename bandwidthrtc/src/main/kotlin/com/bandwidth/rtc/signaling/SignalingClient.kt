package com.bandwidth.rtc.signaling

import com.bandwidth.rtc.signaling.rpc.*
import com.bandwidth.rtc.types.*
import com.bandwidth.rtc.util.Logger
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private const val DEFAULT_GATEWAY_URL =
    "wss://gateway.pv.prod.global.aws.bandwidth.com/prod/gateway-service/api/v1/endpoints"

private const val SDK_VERSION = "0.1.0"

private const val PING_INTERVAL_MS = 60_000L

internal class SignalingClient(
    private val webSocketFactory: () -> WebSocketInterface = { OkHttpWebSocket() }
) : SignalingClientInterface {

    private val log = Logger
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private var webSocket: WebSocketInterface? = null
    private var pingJob: Job? = null
    private var scope: CoroutineScope? = null

    private val pendingRequests = ConcurrentHashMap<String, Continuation<JsonElement?>>()
    private var nextRequestId = 1

    private val eventHandlers = ConcurrentHashMap<String, (String) -> Unit>()

    var isConnected = false
        private set

    override suspend fun connect(authParams: RtcAuthParams, options: RtcOptions?) {
        log.info("SignalingClient.connect() called")
        if (isConnected) throw BandwidthRTCError.AlreadyConnected()

        val baseUrl = options?.websocketUrl ?: DEFAULT_GATEWAY_URL
        val uniqueId = UUID.randomUUID().toString()

        val separator = if (baseUrl.contains("?")) "&" else "?"
        val url = buildString {
            append(baseUrl)
            append(separator)
            append("client=android")
            append("&sdkVersion=$SDK_VERSION")
            append("&uniqueId=$uniqueId")
            append("&endpointToken=${authParams.endpointToken}")
        }

        log.debug("Signaling Gateway URL: $baseUrl (uniqueId=$uniqueId)")

        val ws = webSocketFactory()
        this.webSocket = ws

        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        suspendCoroutine { continuation ->
            log.debug("Connecting WebSocket...")
            ws.connect(url, object : WebSocketEventListener {
                override fun onOpen() {
                    isConnected = true
                    log.info("WebSocket connection opened successfully")
                    continuation.resume(Unit)
                }

                override fun onMessage(text: String) {
                    handleMessage(text)
                }

                override fun onClosing(code: Int, reason: String) {
                    log.info("WebSocket closing: code=$code, reason=$reason")
                }

                override fun onClosed(code: Int, reason: String) {
                    log.info("WebSocket closed: code=$code, reason=$reason")
                    handleDisconnect()
                }

                override fun onFailure(throwable: Throwable) {
                    log.error("WebSocket failure: ${throwable.message}")
                    if (!isConnected) {
                        continuation.resumeWithException(
                            BandwidthRTCError.ConnectionFailed(throwable.message ?: "Unknown error")
                        )
                    } else {
                        handleDisconnect()
                    }
                }
            })
        }

        startPingLoop()
        log.info("SignalingClient connected and ping loop started")
    }

    override suspend fun disconnect() {
        log.info("SignalingClient.disconnect() called")

        try {
            sendNotification("leave", json.encodeToJsonElement(EmptyParams()))
            log.debug("Leave notification sent")
        } catch (e: Exception) {
            log.warn("Failed to send leave notification: ${e.message}")
        }

        pingJob?.cancel()
        pingJob = null
        scope?.cancel()
        scope = null

        webSocket?.close()
        webSocket = null
        isConnected = false

        log.debug("Failing ${pendingRequests.size} pending requests due to disconnect")
        for ((_, continuation) in pendingRequests) {
            continuation.resumeWithException(BandwidthRTCError.WebSocketDisconnected())
        }
        pendingRequests.clear()
        eventHandlers.clear()
        nextRequestId = 1
        log.info("SignalingClient disconnected")
    }

    override fun onEvent(method: String, handler: (String) -> Unit) {
        log.debug("Registering event handler for: $method")
        eventHandlers[method] = handler
    }

    override fun removeEventHandler(method: String) {
        log.debug("Removing event handler for: $method")
        eventHandlers.remove(method)
    }

    override suspend fun setMediaPreferences(): SetMediaPreferencesResult {
        log.debug("SignalingClient.setMediaPreferences()")
        val params = json.encodeToJsonElement(SetMediaPreferencesParams())
        val result = call("setMediaPreferences", params)
            ?: return SetMediaPreferencesResult().also { log.warn("setMediaPreferences returned null result") }
        return json.decodeFromJsonElement(SetMediaPreferencesResult.serializer(), result)
    }

    override suspend fun offerSdp(sdpOffer: String, peerType: String): OfferSdpResult {
        log.debug("SignalingClient.offerSdp() peerType=$peerType")
        val params = json.encodeToJsonElement(OfferSdpParams(sdpOffer = sdpOffer, peerType = peerType))
        val result = call("offerSdp", params)
            ?: throw BandwidthRTCError.SdpNegotiationFailed("No result from offerSdp")
        return json.decodeFromJsonElement(OfferSdpResult.serializer(), result)
    }

    override suspend fun answerSdp(sdpAnswer: String, peerType: String) {
        log.debug("SignalingClient.answerSdp() peerType=$peerType")
        val params = json.encodeToJsonElement(AnswerSdpParams(peerType = peerType, sdpAnswer = sdpAnswer))
        call("answerSdp", params)
    }

    override suspend fun requestOutboundConnection(id: String, type: EndpointType): OutboundConnectionResult {
        log.info("SignalingClient.requestOutboundConnection(id=$id, type=$type)")
        val params = json.encodeToJsonElement(RequestOutboundConnectionParams(id = id, type = type))
        val result = call("requestOutboundConnection", params)
            ?: return OutboundConnectionResult(accepted = false).also { log.warn("requestOutboundConnection returned null") }
        return json.decodeFromJsonElement(OutboundConnectionResult.serializer(), result)
    }

    override suspend fun hangupConnection(endpoint: String, type: EndpointType): HangupResult {
        log.info("SignalingClient.hangupConnection(endpoint=$endpoint, type=$type)")
        val params = json.encodeToJsonElement(HangupConnectionParams(endpoint = endpoint, type = type))
        val result = call("hangupConnection", params)
            ?: return HangupResult().also { log.warn("hangupConnection returned null") }
        return json.decodeFromJsonElement(HangupResult.serializer(), result)
    }

    private suspend fun call(method: String, params: JsonElement): JsonElement? {
        val ws = webSocket
        if (ws == null || !isConnected) {
            log.error("Cannot call RPC method '$method': Not connected")
            throw BandwidthRTCError.NotConnected()
        }

        val id = generateRequestId()
        val request = JsonRpcRequest(id = id, method = method, params = params)
        val message = json.encodeToString(request)

        log.debug(">>> RPC call: $method (id=$id)")

        return suspendCoroutine { continuation ->
            pendingRequests[id] = continuation

            if (!ws.send(message)) {
                log.error("Failed to send RPC message for id=$id")
                pendingRequests.remove(id)
                continuation.resumeWithException(
                    BandwidthRTCError.SignalingError("Failed to send message")
                )
            }
        }
    }

    private fun sendNotification(method: String, params: JsonElement) {
        val ws = webSocket ?: return
        if (!isConnected) return

        val notification = JsonRpcNotification(method = method, params = params)
        val message = json.encodeToString(notification)

        log.debug(">>> RPC notify: $method")
        ws.send(message)
    }

    @Synchronized
    private fun generateRequestId(): String {
        val id = nextRequestId
        nextRequestId++
        return id.toString()
    }

    private fun handleMessage(text: String) {
        log.debug("<<< WS received: ${if (text.length > 500) text.take(500) + "..." else text}")

        val incoming = try {
            json.decodeFromString(JsonRpcIncoming.serializer(), text)
        } catch (e: Exception) {
            log.warn("Failed to decode incoming JSON-RPC message: ${e.message}")
            return
        }

        if (incoming.isResponse) {
            handleResponse(incoming)
        } else if (incoming.isNotification) {
            handleNotification(incoming, text)
        } else {
            log.warn("Received unknown JSON-RPC message type: $text")
        }
    }

    private fun handleResponse(response: JsonRpcIncoming) {
        val id = response.id ?: return

        val continuation = pendingRequests.remove(id)
        if (continuation == null) {
            log.warn("Received response for unknown request id=$id")
            return
        }

        val error = response.error
        if (error != null) {
            log.error("RPC error response (id=$id): code=${error.code}, message=${error.message}")
            if (error.code == 403 || error.message.lowercase().contains("invalid token")) {
                continuation.resumeWithException(BandwidthRTCError.InvalidToken())
            } else {
                continuation.resumeWithException(BandwidthRTCError.RpcError(error.code, error.message))
            }
        } else {
            log.debug("<<< RPC response (id=$id) SUCCESS")
            continuation.resume(response.result)
        }
    }

    private fun handleNotification(notification: JsonRpcIncoming, @Suppress("UNUSED_PARAMETER") rawText: String) {
        val method = notification.method ?: return
        log.debug("<<< Server notification: $method")

        val handler = eventHandlers[method]
        if (handler != null) {
            val paramsText = notification.params?.toString() ?: ""
            handler(paramsText)
        } else {
            log.warn("No handler registered for notification: $method")
        }
    }

    private fun handleDisconnect() {
        val wasConnected = isConnected
        isConnected = false

        log.info("SignalingClient.handleDisconnect() wasConnected=$wasConnected")

        if (pendingRequests.isNotEmpty()) {
            log.debug("Failing ${pendingRequests.size} pending requests due to disconnect")
            for ((_, continuation) in pendingRequests) {
                continuation.resumeWithException(BandwidthRTCError.WebSocketDisconnected())
            }
            pendingRequests.clear()
        }

        if (wasConnected) {
            eventHandlers["close"]?.invoke("")
        }
    }

    private fun startPingLoop() {
        pingJob = scope?.launch {
            while (isActive) {
                delay(PING_INTERVAL_MS)
                log.debug("Sending periodic ping...")
                sendNotification("ping", json.encodeToJsonElement(EmptyParams()))
            }
        }
    }
}

@kotlinx.serialization.Serializable
private class EmptyParams
