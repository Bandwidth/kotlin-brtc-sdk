package com.bandwidth.rtc.signaling

import com.bandwidth.rtc.signaling.rpc.OfferSdpResult
import com.bandwidth.rtc.signaling.rpc.SetMediaPreferencesResult
import com.bandwidth.rtc.types.EndpointType
import com.bandwidth.rtc.types.HangupResult
import com.bandwidth.rtc.types.OutboundConnectionResult
import com.bandwidth.rtc.types.RtcAuthParams
import com.bandwidth.rtc.types.RtcOptions

interface SignalingClientInterface {
    suspend fun connect(authParams: RtcAuthParams, options: RtcOptions?)
    suspend fun disconnect()
    fun onEvent(method: String, handler: (String) -> Unit)
    fun removeEventHandler(method: String)
    suspend fun setMediaPreferences(): SetMediaPreferencesResult
    suspend fun offerSdp(sdpOffer: String, peerType: String): OfferSdpResult
    suspend fun answerSdp(sdpAnswer: String, peerType: String)
    suspend fun requestOutboundConnection(id: String, type: EndpointType): OutboundConnectionResult
    suspend fun hangupConnection(endpoint: String, type: EndpointType): HangupResult
}
