package com.bandwidth.rtc.util

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

enum class LogLevel(val priority: Int) {
    OFF(0),
    ERROR(1),
    WARN(2),
    INFO(3),
    DEBUG(4),
    TRACE(5);
}

/** Receives every log call regardless of the configured level. Must not log: that would recurse. */
internal fun interface LogSink {
    fun onLog(level: LogLevel, timestampMs: Long, message: String)
}

internal object Logger {
    private const val TAG = "BRTC"

    var level: LogLevel = LogLevel.WARN
    var logCallerInfo: Boolean = false

    private val sinks = CopyOnWriteArrayList<LogSink>()

    fun addSink(sink: LogSink) {
        sinks.addIfAbsent(sink)
    }

    fun removeSink(sink: LogSink) {
        sinks.remove(sink)
    }

    // Sinks see every call, so the configured level only filters what reaches logcat.
    private fun capture(level: LogLevel, message: String) {
        if (sinks.isEmpty()) {
            return
        }
        val timestampMs = System.currentTimeMillis()
        for (sink in sinks) {
            sink.onLog(level, timestampMs, message)
        }
    }

    private fun formatMessage(message: String): String {
        if (!logCallerInfo) return message
        val stackTrace = Thread.currentThread().stackTrace
        val caller = stackTrace.firstOrNull {
            val className = it.className
            !className.contains("Logger") &&
                    !className.contains("java.lang.Thread") &&
                    !className.contains("dalvik.system.VMStack")
        }
        return if (caller != null) {
            "[${caller.className.substringAfterLast('.')}.${caller.methodName}] $message"
        } else {
            message
        }
    }

    fun debug(message: String) {
        capture(LogLevel.DEBUG, message)
        if (level >= LogLevel.DEBUG) Log.d(TAG, formatMessage(message))
    }

    fun trace(message: String) {
        capture(LogLevel.TRACE, message)
        if (level >= LogLevel.TRACE) Log.v(TAG, formatMessage(message))
    }

    fun info(message: String) {
        capture(LogLevel.INFO, message)
        if (level >= LogLevel.INFO) Log.i(TAG, formatMessage(message))
    }

    fun warn(message: String) {
        capture(LogLevel.WARN, message)
        if (level >= LogLevel.WARN) Log.w(TAG, formatMessage(message))
    }

    fun error(message: String) {
        capture(LogLevel.ERROR, message)
        if (level >= LogLevel.ERROR) Log.e(TAG, formatMessage(message))
    }
}
