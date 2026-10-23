package dev.trmx.gui.killer

enum class AppCategory {
    // Critical system packages that can NEVER be stopped under any circumstances.
    CORE,
    // Safe vendor applications suggested for pre-selection (user can uncheck).
    SAFE,
    // Unknown or non-preselected apps (default excluded, user must explicitly check).
    UNKNOWN,
}

object AppCategories {

    // Hardcoded blocklist of core system packages.
    // Enforced in code: can never be killed, even if somehow requested.
    val CORE_PACKAGES: Set<String> = setOf(
        // TRMX & Termux bridge (killing these kills the app and execution)
        "dev.trmx.gui",
        "com.termux",
        "com.termux.api",
        "com.termux.gui",
        "com.termux.window",
        "com.termux.styling",

        // Android core framework and SystemUI
        "android",
        "com.android.systemui",
        "com.google.android.systemui",

        // Settings apps
        "com.android.settings",
        "com.google.android.settings",
        "com.samsung.android.settings",

        // Phone and telecom services (critical for device cellular function)
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.providers.telephony",

        // Package installers and permissions
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",

        // Google Play core services
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.vending",

        // Keyboards / Input methods (stopping breaks typing/unlocking)
        "com.android.inputmethod.latin",
        "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard",

        // Home launchers
        "com.android.launcher",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.sec.android.app.launcher",

        // Bluetooth, NFC, KeyChain, Settings provider, Shell, Network Stack
        "com.android.bluetooth",
        "com.android.nfc",
        "com.android.keychain",
        "com.android.providers.settings",
        "com.android.shell",
        "com.google.android.ext.services",
        "com.android.networkstack",
        "com.google.android.networkstack",
    )

    // Vendor apps safe to stop (pre-selected hints; user can uncheck).
    val SUGGESTED_PACKAGES: Set<String> = setOf(
        // Samsung vendor apps
        "com.sec.android.app.samsungapps",        // Galaxy Store
        "com.samsung.android.themestore",          // Theme Store
        "com.samsung.android.game.gamehome",       // Game Launcher
        "com.samsung.android.game.gametools",      // Game Tools
        "com.samsung.android.game.gos",            // Game Optimizing Service
        "com.sec.android.app.voicenote",           // Samsung Voice Recorder
        "com.samsung.android.dialer",              // Samsung Dialer UI
        "com.samsung.android.voc",                 // Samsung Members
        "com.samsung.android.app.spage",           // Samsung Free / Bixby Home
        "com.samsung.android.bixby.agent",         // Bixby Voice / Agent
        "com.samsung.android.bixby.wakeup",        // Bixby Wakeup
        "com.samsung.android.app.tips",            // Samsung Tips
        "com.samsung.android.weather",             // Samsung Weather
        "com.sec.android.app.soundalive",          // SoundAlive equalizer
        "com.samsung.android.oneconnect",          // SmartThings
        "com.sec.android.easyMover",               // Smart Switch
        "com.samsung.android.calendar",            // Samsung Calendar
        "com.sec.android.app.myfiles",             // Samsung My Files
        "com.sec.android.app.popupcalculator",     // Samsung Calculator
        "com.sec.android.app.clockpackage",        // Samsung Clock

        // Google non-core vendor apps
        "com.google.android.apps.recorder",        // Google Recorder
        "com.google.android.dialer",               // Google Dialer UI
        "com.google.android.apps.tips",            // Google Pixel Tips
        "com.google.android.apps.wellbeing",       // Digital Wellbeing
        "com.google.android.apps.tachyon",         // Google Meet / Duo
        "com.google.android.youtube",              // YouTube
    )

    val SAFE_PACKAGES: Set<String> = SUGGESTED_PACKAGES

    fun isSuggested(packageName: String): Boolean {
        val pkg = packageName.trim()
        return SUGGESTED_PACKAGES.contains(pkg)
    }

    /**
     * Determines if an app belongs to the Running tab (currently/recently active AND killable):
     * (installed AND active) OR (system AND in the safe suggestion list AND active).
     *
     * Core packages can NEVER be killable running under any circumstances.
     */
    fun isKillableRunning(app: AppInfo): Boolean {
        return isKillableRunning(
            packageName = app.packageName,
            isSystem = app.isSystem,
            isRunning = app.isRunning,
        )
    }

    fun isKillableRunning(packageName: String, isSystem: Boolean, isRunning: Boolean): Boolean {
        val pkg = packageName.trim()
        if (isCore(pkg)) return false
        if (!isRunning) return false
        val userInstalled = !isSystem
        val inSuggestionList = isSuggested(pkg)
        return userInstalled || inSuggestionList
    }

    /**
     * Determines if an app belongs to the Other tab:
     * Non-core SYSTEM apps only. Contains non-running system apps, plus running system apps
     * that are NOT in the safe suggestion list (unknown system components).
     *
     * Installed third-party packages NEVER appear here, running or not.
     */
    fun belongsToOtherTab(app: AppInfo): Boolean {
        return belongsToOtherTab(
            packageName = app.packageName,
            isSystem = app.isSystem,
            isRunning = app.isRunning,
        )
    }

    fun belongsToOtherTab(packageName: String, isSystem: Boolean, isRunning: Boolean): Boolean {
        val pkg = packageName.trim()
        if (isCore(pkg)) return false
        if (!isSystem) return false
        return !isKillableRunning(pkg, isSystem = true, isRunning = isRunning)
    }

    fun isCore(packageName: String): Boolean {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return true
        if (pkg == "dev.trmx.gui") return true
        if (pkg.startsWith("com.termux")) return true
        if (pkg == "android") return true
        if (CORE_PACKAGES.contains(pkg)) return true

        // Protection for OEM variants of critical components
        if (pkg.endsWith(".systemui")) return true
        if (pkg.endsWith(".settings") && (pkg.startsWith("com.android.") || pkg.startsWith("com.google.") || pkg.startsWith("com.samsung."))) return true
        if (pkg.endsWith(".phone") || pkg.endsWith(".telecom") || pkg.endsWith(".telephony") || pkg.endsWith(".incallui")) return true
        return false
    }

    fun isKillable(packageName: String): Boolean = !isCore(packageName)

    fun categoryFor(packageName: String): AppCategory {
        val pkg = packageName.trim()
        return when {
            isCore(pkg) -> AppCategory.CORE
            SAFE_PACKAGES.contains(pkg) -> AppCategory.SAFE
            else -> AppCategory.UNKNOWN
        }
    }
}
