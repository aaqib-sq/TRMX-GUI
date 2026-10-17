package dev.trmx.gui

import dev.trmx.gui.net.SetupLog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SetupLogTest {

    private fun tempLog(maxBytes: Long = SetupLog.MAX_BYTES): Pair<SetupLog, File> {
        val dir = Files.createTempDirectory("setuplog-test").toFile()
        val f = File(dir, "setup_log.txt")
        return SetupLog(f, maxBytes = maxBytes, nowMs = { 0L }) to f
    }

    @Test
    fun `log lines are timestamped and readable back`() {
        val (log, _) = tempLog()
        log.log("PROBE /v1/system/info -> 200 (12ms)")
        val text = log.read()
        assertTrue(text.contains("PROBE /v1/system/info -> 200 (12ms)"))
        assertTrue("line must start with a timestamp",
                   text.trim().first().isDigit())
    }

    @Test
    fun `describeToken never leaks the full token`() {
        val token = "6uBQ-super-secret-token-value-0123456789"
        val desc = SetupLog.describeToken(token)
        assertTrue(desc.contains("len=${token.length}"))
        assertTrue(desc.contains("prefix=6uBQ"))
        assertFalse(desc.contains("super-secret"))
        assertFalse(desc.contains(token))
        assertTrue(SetupLog.describeToken("").contains("len=0"))
    }

    @Test
    fun `file is capped and keeps the newest lines`() {
        val (log, file) = tempLog(maxBytes = 400)
        repeat(60) { i -> log.log("event number $i with some padding text") }
        assertTrue("file must stay near the cap, was ${file.length()}",
                   file.length() <= 500)
        val text = log.read()
        assertTrue(text.contains("event number 59"))
        assertFalse(text.contains("event number 0 "))
    }

    @Test
    fun `clear empties the log`() {
        val (log, _) = tempLog()
        log.log("something")
        log.clear()
        assertTrue(log.read().isEmpty())
    }
}
