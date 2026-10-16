package dev.trmx.gui

import android.app.Application

/** App entry point: installs the crash net before anything else runs. */
class TrmxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
