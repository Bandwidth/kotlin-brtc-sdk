package com.bandwidth.rtc.util

import android.util.Log

enum class LogLevel(val priority: Int) {
    OFF(0),
    ERROR(1),
    WARN(2),
    INFO(3),
    DEBUG(4),
    TRACE(5);
}

internal object Logger {
    private const val TAG = "BRTC"

    var level: LogLevel = LogLevel.WARN
    var logCallerInfo: Boolean = false

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
        if (level >= LogLevel.DEBUG) Log.d(TAG, formatMessage(message))
    }

    fun trace(message: String) {
        if (level >= LogLevel.TRACE) Log.v(TAG, formatMessage(message))
    }

    fun info(message: String) {
        if (level >= LogLevel.INFO) Log.i(TAG, formatMessage(message))
    }

    fun warn(message: String) {
        if (level >= LogLevel.WARN) Log.w(TAG, formatMessage(message))
    }

    fun error(message: String) {
        if (level >= LogLevel.ERROR) Log.e(TAG, formatMessage(message))
    }
}
