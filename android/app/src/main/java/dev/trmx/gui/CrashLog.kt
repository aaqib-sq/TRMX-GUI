package dev.trmx.gui

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/**
 * Last-resort crash net (device round 1, 2026-10-16): the app crashed
 * on a real phone in states that code review alone could not pin down,
 * and Android shows the user nothing but "this app has a bug". This
 * records the stack trace of any uncaught exception to
 * filesDir/last_crash.txt before the process dies; the next launch
 * shows it on a plain screen (MainActivity's CrashGate) so the exact
 * fault can be screenshotted and fixed instead of guessed at.
 * Recording never swallows the crash — the previous default handler
 * still runs afterwards.
 */
object CrashLog {

    private const val FILE_NAME = "last_crash.txt"

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(appContext, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, error: Throwable) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.versionCode})"
        }.getOrElse { "?" }
        val text = buildString {
            append("TRMX crash report\n")
            append("time: ").append(Date()).append('\n')
            append("app: ").append(version).append('\n')
            append("device: ").append(android.os.Build.MANUFACTURER)
                .append(' ').append(android.os.Build.MODEL)
                .append(" · Android ").append(android.os.Build.VERSION.RELEASE)
                .append('\n')
            append("----\n").append(sw.toString())
        }
        File(context.filesDir, FILE_NAME).writeText(text)
    }

    /** The saved report, or null when the last run did not crash. */
    fun read(context: Context): String? = runCatching {
        val f = File(context.applicationContext.filesDir, FILE_NAME)
        if (f.exists()) f.readText().take(12_000) else null
    }.getOrNull()

    fun clear(context: Context) {
        runCatching {
            File(context.applicationContext.filesDir, FILE_NAME).delete()
        }
    }
}
