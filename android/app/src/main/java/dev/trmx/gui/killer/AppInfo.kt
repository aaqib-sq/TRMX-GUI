package dev.trmx.gui.killer

data class AppInfo(
    val packageName: String,
    val label: String,
    val category: AppCategory,
    val isSystem: Boolean,
    val isRunning: Boolean = false,
)

data class AppDiagnosticInfo(
    val packageName: String,
    val label: String,
    val lastTimeUsedMs: Long?,
    val lastTimeUsedFormatted: String,
    val isAccessibilityObserved: Boolean,
    val isStopped: Boolean,
    val inRunning: Boolean,
    val notRunningReason: String?,
)

data class KillerDiagnosticData(
    val isAccessibilityEnabled: Boolean,
    val isUsageAccessGranted: Boolean,
    val totalInstalledApps: Int,
    val usageStatsCount: Int,
    val accessibilityObservedCount: Int,
    val activityManagerFallbackCount: Int,
    val runningCount: Int,
    val apps: List<AppDiagnosticInfo>,
)
