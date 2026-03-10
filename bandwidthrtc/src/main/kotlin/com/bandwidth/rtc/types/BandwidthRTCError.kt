package com.bandwidth.rtc.types

sealed class BandwidthRTCError(message: String) : Exception(message) {
    class InvalidToken : BandwidthRTCError("Invalid or expired endpoint token")
    class ConnectionFailed(detail: String) : BandwidthRTCError("Connection failed: $detail")
    class SignalingError(detail: String) : BandwidthRTCError("Signaling error: $detail")
    class WebSocketDisconnected : BandwidthRTCError("WebSocket disconnected unexpectedly")
    class SdpNegotiationFailed(detail: String) : BandwidthRTCError("SDP negotiation failed: $detail")
    class MediaAccessDenied : BandwidthRTCError("Camera or microphone access denied")
    class AlreadyConnected : BandwidthRTCError("Already connected to BRTC")
    class NotConnected : BandwidthRTCError("Not connected to BRTC")
    class PublishFailed(detail: String) : BandwidthRTCError("Publish failed: $detail")
    class RpcError(val code: Int, val rpcMessage: String) : BandwidthRTCError("RPC error ($code): $rpcMessage")
    class NotSupported(detail: String) : BandwidthRTCError("Operation not supported: $detail")
    class NoActiveCall : BandwidthRTCError("No active call to answer or end")
}
