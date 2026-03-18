package com.bandwidth.rtc.signaling

internal interface WebSocketInterface {
    fun connect(url: String, listener: WebSocketEventListener)
    fun send(message: String): Boolean
    fun close(code: Int = 1000, reason: String? = null)
}

internal interface WebSocketEventListener {
    fun onOpen()
    fun onMessage(text: String)
    fun onClosing(code: Int, reason: String)
    fun onClosed(code: Int, reason: String)
    fun onFailure(throwable: Throwable)
}
