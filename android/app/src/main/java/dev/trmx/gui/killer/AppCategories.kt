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
     * Determines if an app is Running (v7 predicate - single source of truth):
     * ALL of:
     * 1. NOT core
     * 2. NOT stopped (ApplicationInfo FLAG_STOPPED is clear)
     * 3. Active by at least one existing signal (Accessibility-observed OR UsageStats within 7-day window OR ActivityManager fallback)
     */
    fun isRunning(
        packageName: String,
        isStopped: Boolean,
        isActive: Boolean,
    ): Boolean {
        val pkg = packageName.trim()
        if (isCore(pkg)) return false
        if (isStopped) return false
        return isActive
    }

    fun isRunning(app: AppInfo): Boolean {
        return isRunning(
            packageName = app.packageName,
            isStopped = app.isStopped,
            isActive = app.isActive,
        )
    }

    fun isRunning(app: AppDiagnosticInfo): Boolean {
        return isRunning(
            packageName = app.packageName,
            isStopped = app.isStopped,
            isActive = app.isActive,
        )
    }

    fun isKillableRunning(app: AppInfo): Boolean = isRunning(app)

    fun isKillableRunning(app: AppDiagnosticInfo): Boolean = isRunning(app)

    fun isKillableRunning(
        packageName: String,
        isStopped: Boolean,
        isActive: Boolean,
    ): Boolean = isRunning(packageName = packageName, isStopped = isStopped, isActive = isActive)

    fun isKillableRunning(packageName: String, isRunning: Boolean): Boolean {
        return isRunning(packageName = packageName, isStopped = false, isActive = isRunning)
    }

    fun isKillableRunning(packageName: String, isSystem: Boolean, isRunning: Boolean): Boolean {
        return isRunning(packageName = packageName, isStopped = false, isActive = isRunning)
    }

    fun isKillableRunning(
        packageName: String,
        isSystem: Boolean,
        isStopped: Boolean,
        isActive: Boolean,
    ): Boolean = isRunning(packageName = packageName, isStopped = isStopped, isActive = isActive)

    /**
     * Determines if an app belongs to the Other tab (v7 predicate):
     * ALL of:
     * 1. NOT core
     * 2. isSystem
     * 3. NOT Running (per the Running predicate)
     * 4. NOT stopped (ApplicationInfo FLAG_STOPPED clear)
     *
     * Stopped apps must appear in NEITHER Running NOR Other (except Core tab if core).
     */
    fun belongsToOtherTab(
        packageName: String,
        isSystem: Boolean,
        isStopped: Boolean,
        isActive: Boolean,
    ): Boolean {
        val pkg = packageName.trim()
        if (isCore(pkg)) return false
        if (!isSystem) return false
        if (isStopped) return false
        if (isRunning(packageName = pkg, isStopped = isStopped, isActive = isActive)) return false
        return true
    }

    fun belongsToOtherTab(
        packageName: String,
        isSystem: Boolean,
        isRunning: Boolean,
    ): Boolean {
        return belongsToOtherTab(
            packageName = packageName,
            isSystem = isSystem,
            isStopped = false,
            isActive = isRunning,
        )
    }

    fun belongsToOtherTab(app: AppInfo): Boolean {
        return belongsToOtherTab(
            packageName = app.packageName,
            isSystem = app.isSystem,
            isStopped = app.isStopped,
            isActive = app.isActive,
        )
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
