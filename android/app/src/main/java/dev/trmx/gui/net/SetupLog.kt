package dev.trmx.gui.net

/*
 * Setup diagnostics log — the crash-net pattern applied to pairing
 * (agreed 2026-10-17 after on-device round 1 ended in an app-side 401
 * that no amount of code-reading could explain).
 *
 * Every setup probe, intent op and stage change is appended to
 * filesDir/setup_log.txt with a timestamp, so one shared log from the
 * device names WHAT the app actually sent and WHAT came back, instead
 * of another night of theories. The full pairing token is NEVER
 * written — only its length and first 4 characters (see describeToken).
 *
 * Pure java.io (no Android imports) so the behaviour is JVM-testable;
 * SetupViewModel wires it to filesDir.
 */

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SetupLog(
    private val file: File,
    private val maxBytes: Long = MAX_BYTES,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    @Synchronized
    fun log(event: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(Date(nowMs()))
            file.appendText("$stamp  $event\n")
            trimToCap()
        }
    }

    @Synchronized
    fun read(): String = runCatching {
        if (file.exists()) file.readText() else ""
    }.getOrDefault("")

    @Synchronized
    fun clear() {
        runCatching { if (file.exists()) file.delete() }
    }

    /** Keep the newest tail once the file grows past the cap. */
    private fun trimToCap() {
        if (file.length() <= maxBytes) return
        val text = file.readText()
        val tail = text.takeLast((maxBytes * 3 / 4).toInt())
        // start on a line boundary so the kept log stays readable
        val start = tail.indexOf('\n')
        file.writeText(if (start >= 0) tail.substring(start + 1) else tail)
    }

    companion object {
        const val MAX_BYTES: Long = 128 * 1024

        /**
         * The ONLY way a token may appear in the log: length + first 4
         * characters. Enough to prove two sides hold the same token
         * family; useless to anyone the log is shared with.
         */
        fun describeToken(token: String): String =
            if (token.isEmpty()) "token len=0"
            else "token len=${token.length} prefix=${token.take(4)}"
    }
}
