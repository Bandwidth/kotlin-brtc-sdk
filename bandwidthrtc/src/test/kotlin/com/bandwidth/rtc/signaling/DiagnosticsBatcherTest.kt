package com.bandwidth.rtc.signaling

import com.bandwidth.rtc.signaling.rpc.DiagnosticsLogEntry
import com.bandwidth.rtc.util.LogLevel
import com.bandwidth.rtc.util.Logger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The log buffer behind the gateway's `deviceDiagnostics` notification.
 *
 * Logger is process-wide, so every assertion looks only at entries with this test's marker; logs
 * from anything else running in the JVM must not matter.
 */
class DiagnosticsBatcherTest {

    private val marker = "diag-test"
    private val originalLevel = Logger.level
    private lateinit var batcher: DiagnosticsBatcher

    @Before
    fun setUp() {
        batcher = DiagnosticsBatcher()
    }

    @After
    fun tearDown() {
        batcher.stop()
        Logger.level = originalLevel
    }

    private fun flushed(target: DiagnosticsBatcher = batcher): List<DiagnosticsLogEntry> {
        var batch: List<DiagnosticsLogEntry> = emptyList()
        target.flush { batch = it; true }
        return batch.filter { it.message.single().startsWith(marker) }
    }

    @Test
    fun `captures every level even when logging is off`() {
        Logger.level = LogLevel.OFF
        batcher.start()

        Logger.error("$marker error")
        Logger.warn("$marker warn")
        Logger.info("$marker info")
        Logger.debug("$marker debug")
        Logger.trace("$marker trace")

        val entries = flushed()
        assertEquals(listOf("error", "warn", "info", "debug", "trace"), entries.map { it.level })
        assertEquals(
            listOf("$marker error", "$marker warn", "$marker info", "$marker debug", "$marker trace"),
            entries.map { it.message.single() }
        )
    }

    @Test
    fun `stamps each entry with the epoch time in milliseconds`() {
        batcher.start()
        val before = System.currentTimeMillis()

        Logger.info("$marker timed")

        val after = System.currentTimeMillis()
        val timestamp = flushed().single().timestamp
        assertTrue(timestamp in before..after)
    }

    @Test
    fun `captures nothing before start or after stop`() {
        Logger.info("$marker before start")
        batcher.start()
        Logger.info("$marker while started")
        batcher.stop()
        Logger.info("$marker after stop")

        assertEquals(listOf("$marker while started"), flushed().map { it.message.single() })
    }

    @Test
    fun `start is idempotent`() {
        batcher.start()
        batcher.start()

        Logger.info("$marker once")

        assertEquals(1, flushed().size)
    }

    @Test
    fun `a flush empties the buffer`() {
        batcher.start()
        Logger.info("$marker one")
        assertEquals(1, flushed().size)

        assertEquals(0, flushed().size)
    }

    @Test
    fun `an empty buffer is not sent`() {
        var sends = 0

        batcher.flush { sends++; true }

        assertEquals(0, sends)
    }

    @Test
    fun `drops the oldest entries past the cap`() {
        val capped = DiagnosticsBatcher(maxEntries = 3)
        capped.start()
        try {
            for (i in 1..5) {
                Logger.info("$marker $i")
            }

            assertEquals(listOf("$marker 3", "$marker 4", "$marker 5"), flushed(capped).map { it.message.single() })
        } finally {
            capped.stop()
        }
    }

    @Test
    fun `a failed send is logged without sending again and the entries are kept for the next flush`() {
        batcher.start()
        Logger.info("$marker kept")
        var sends = 0

        batcher.flush { sends++; false }

        assertEquals(1, sends)
        val retried = mutableListOf<DiagnosticsLogEntry>()
        batcher.flush { retried.addAll(it); true }
        assertTrue(retried.any { it.message.single() == "$marker kept" })
        // The error about the failed send is in the buffer, not a reason to send
        assertTrue(retried.any { it.level == "error" && it.message.single().startsWith("Error sending diagnostics") })
    }

    @Test
    fun `a send that throws is handled like a failed send`() {
        batcher.start()
        Logger.info("$marker kept")
        var sends = 0

        batcher.flush { sends++; throw IllegalStateException("socket gone") }

        assertEquals(1, sends)
        assertFalse(flushed().isEmpty())
    }
}
