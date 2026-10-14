package com.bandwidth.rtc.signaling

import com.bandwidth.rtc.signaling.rpc.DiagnosticsLogEntry
import com.bandwidth.rtc.util.LogSink
import com.bandwidth.rtc.util.Logger

// Bounds memory when signaling is down for a long time and nothing can flush the buffer. The
// oldest entries go first, so the most recent activity is what survives.
internal const val DIAGNOSTICS_MAX_BUFFERED_ENTRIES = 5_000

/**
 * Collects every SDK log line, at all levels and regardless of the configured log level, until the
 * owner flushes them to the gateway. Safe to call from any thread.
 */
internal class DiagnosticsBatcher(private val maxEntries: Int = DIAGNOSTICS_MAX_BUFFERED_ENTRIES) {

    private val lock = Any()
    private val entries = ArrayDeque<DiagnosticsLogEntry>()

    private val sink = LogSink { level, timestampMs, message ->
        add(DiagnosticsLogEntry(level = level.name.lowercase(), timestamp = timestampMs, message = listOf(message)))
    }

    /** Starts capturing. Idempotent. */
    fun start() {
        Logger.addSink(sink)
    }

    /** Stops capturing. Idempotent. Already buffered entries stay for the next [flush]. */
    fun stop() {
        Logger.removeSink(sink)
    }

    /**
     * Hands the buffered entries to [send], which returns whether the message was accepted. An empty
     * buffer is not sent. A failed send puts the entries back and logs an error; that log only
     * lands in the buffer, it never triggers another send.
     */
    fun flush(send: (List<DiagnosticsLogEntry>) -> Boolean) {
        val batch = synchronized(lock) {
            entries.toList().also { entries.clear() }
        }
        if (batch.isEmpty()) {
            return
        }
        val failure = try {
            if (send(batch)) {
                return
            }
            "message was not accepted"
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
        requeue(batch)
        Logger.error("Error sending diagnostics: $failure")
    }

    private fun add(entry: DiagnosticsLogEntry) {
        synchronized(lock) {
            entries.addLast(entry)
            trimLocked()
        }
    }

    private fun requeue(batch: List<DiagnosticsLogEntry>) {
        synchronized(lock) {
            entries.addAll(0, batch)
            trimLocked()
        }
    }

    private fun trimLocked() {
        while (entries.size > maxEntries) {
            entries.removeFirst()
        }
    }
}
