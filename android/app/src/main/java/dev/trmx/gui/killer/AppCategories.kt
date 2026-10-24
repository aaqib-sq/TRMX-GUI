package dev.trmx.gui.killer

enum class AppCategory {
    // Critical system packages that can NEVER be stopped under any circumstances.
    CORE,
    // Unknown or non-core apps (default excluded, user must explicitly check).
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

    /**
     * Determines if an app belongs to the Running tab (currently/recently active AND killable):
     * active AND NOT core. That is all.
     * System or installed does not matter, and no hardcoded list filters it.
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

    fun isKillableRunning(packageName: String, isRunning: Boolean): Boolean {
        val pkg = packageName.trim()
        if (isCore(pkg)) return false
        return isRunning
    }

    fun isKillableRunning(packageName: String, isSystem: Boolean, isRunning: Boolean): Boolean {
        return isKillableRunning(packageName, isRunning)
    }

    /**
     * Determines if an app belongs to the Other tab:
     * Non-core SYSTEM apps that are NOT running (not active).
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
            else -> AppCategory.UNKNOWN
        }
    }
}
