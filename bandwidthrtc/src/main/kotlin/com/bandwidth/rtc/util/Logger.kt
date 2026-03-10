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

    fun debug(message: String) {
        if (level >= LogLevel.DEBUG) Log.d(TAG, message)
    }

    fun trace(message: String) {
        if (level >= LogLevel.TRACE) Log.v(TAG, message)
    }

    fun info(message: String) {
        if (level >= LogLevel.INFO) Log.i(TAG, message)
    }

    fun warn(message: String) {
        if (level >= LogLevel.WARN) Log.w(TAG, message)
    }

    fun error(message: String) {
        if (level >= LogLevel.ERROR) Log.e(TAG, message)
    }
}
