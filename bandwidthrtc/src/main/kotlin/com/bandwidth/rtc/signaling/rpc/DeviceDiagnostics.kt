package com.bandwidth.rtc.signaling.rpc

import kotlinx.serialization.Serializable

@Serializable
internal data class DiagnosticsLogEntry(
    val level: String,
    val timestamp: Long,
    val message: List<String>
)

@Serializable
internal data class DeviceDiagnosticsParams(
    val logs: List<DiagnosticsLogEntry>
)
