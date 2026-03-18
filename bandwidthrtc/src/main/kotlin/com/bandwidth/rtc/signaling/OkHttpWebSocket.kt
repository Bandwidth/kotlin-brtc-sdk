package com.bandwidth.rtc.signaling

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

internal class OkHttpWebSocket(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(60, TimeUnit.SECONDS)
        .build()
) : WebSocketInterface {

    private var webSocket: WebSocket? = null

    override fun connect(url: String, listener: WebSocketEventListener) {
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                listener.onMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosing(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onFailure(t)
            }
        })
    }

    override fun send(message: String): Boolean {
        return webSocket?.send(message) ?: false
    }

    override fun close(code: Int, reason: String?) {
        webSocket?.close(code, reason)
        webSocket = null
    }
}
