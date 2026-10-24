package dev.trmx.gui.killer

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import dev.trmx.gui.MainActivity
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

sealed class KillResult {
    data class Killed(val packageName: String) : KillResult()
    data class Skipped(val packageName: String, val reason: String) : KillResult()
}

data class KillProgress(
    val current: Int,
    val total: Int,
    val currentPackage: String,
    val currentLabel: String,
)

data class KillReport(
    val totalTargeted: Int,
    val killed: List<String>,
    val skipped: List<Pair<String, String>>, // (packageName, reason)
)

/**
 * Core engine for TRMX App Killer.
 *
 * Privacy & Security Guarantee:
 * STRICTLY ON-DEVICE ONLY. All UsageStats, Accessibility observations, and package metadata
 * are processed solely in local device memory and local private storage. ZERO network calls,
 * zero HTTP requests, zero uploads, zero analytics, zero ad SDKs, and zero telemetry.
 * No data leaves the device.
 */
object AppKillerEngine {

    /**
     * Recent window for UsageStats detection: 24 hours.
     * Packages active within this window are detected as running.
     */
    const val USAGE_STATS_WINDOW_MS: Long = 24 * 60 * 60 * 1000L // 24 hours

    private val killedTimestamps = ConcurrentHashMap<String, Long>()

    fun isAccessibilityEnabled(context: Context): Boolean {
        if (AppKillerAccessibilityService.isRunning) return true

        val expectedComponentName = ComponentName(context, AppKillerAccessibilityService::class.java)
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (am != null) {
            val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            if (enabledServices != null) {
                for (service in enabledServices) {
                    val serviceInfo = service.resolveInfo?.serviceInfo
                    if (serviceInfo != null &&
                        serviceInfo.packageName == expectedComponentName.packageName &&
                        serviceInfo.name == expectedComponentName.className
                    ) {
                        return true
                    }
                }
            }
        }

        try {
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false

            val expectedShort = expectedComponentName.flattenToString()
            val expectedLong = "${expectedComponentName.packageName}/${expectedComponentName.className}"

            val parts = enabledServicesSetting.split(":")
            for (part in parts) {
                if (part.equals(expectedShort, ignoreCase = true) ||
                    part.equals(expectedLong, ignoreCase = true)
                ) {
                    return true
                }
            }
        } catch (_: Exception) {
        }

        return false
    }

    fun openAccessibilitySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun hasUsageAccessPermission(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    fun openUsageAccessSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }

    /**
     * Queries recent app activity via UsageStatsManager on-device.
     *
     * Privacy guarantee: All UsageStats data is strictly queried and processed on-device
     * solely to determine recently active packages for the Kill screen. No usage data leaves the device.
     */
    fun getActivePackagesFromUsageStats(context: Context, windowMs: Long = USAGE_STATS_WINDOW_MS): Set<String> {
        if (!hasUsageAccessPermission(context)) return emptySet()
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return emptySet()
        val now = System.currentTimeMillis()
        val startTime = now - windowMs

        return try {
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, startTime, now) ?: return emptySet()
            val active = mutableSetOf<String>()
            for (usage in stats) {
                val pkg = usage.packageName ?: continue
                val trimmed = pkg.trim()
                if (trimmed.isEmpty() || AppCategories.isCore(trimmed)) continue

                val lastUsed = usage.lastTimeUsed
                val lastVisible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) usage.lastTimeVisible else 0L
                val mostRecent = maxOf(lastUsed, lastVisible)

                if (mostRecent >= startTime) {
                    val killedAt = getPackageLastKilledTimestamp(trimmed)
                    if (mostRecent > killedAt) {
                        active.add(trimmed)
                    }
                }
            }
            active
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun markPackageKilled(context: Context?, packageName: String) {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return
        killedTimestamps[pkg] = System.currentTimeMillis()
        AppKillerAccessibilityService.removeObservedPackage(context, pkg)
    }

    fun getPackageLastKilledTimestamp(packageName: String): Long {
        return killedTimestamps[packageName.trim()] ?: 0L
    }

    /**
     * Determines active running packages using a union of two on-device signals:
     * 1. Window events observed by AppKillerAccessibilityService (persisted locally across screen-off).
     * 2. Recent usage stats within the 24-hour window (UsageStatsManager).
     *
     * Core packages are strictly excluded.
     */
    fun getActivePackages(context: Context, installedPackages: Set<String>? = null): Set<String> {
        val active = mutableSetOf<String>()

        // Signal 1: Accessibility service observed foreground packages
        active.addAll(AppKillerAccessibilityService.getObservedPackages(context))

        // Signal 2: UsageStats recent activity (24-hour window)
        active.addAll(getActivePackagesFromUsageStats(context, USAGE_STATS_WINDOW_MS))

        // Fallback: ActivityManager running processes (for older Android / emulators)
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val runningProcesses = am?.runningAppProcesses
            if (runningProcesses != null) {
                for (proc in runningProcesses) {
                    val pkgList = proc.pkgList ?: continue
                    for (pkg in pkgList) {
                        val trimmed = pkg?.trim() ?: continue
                        if (trimmed.isNotEmpty()) {
                            active.add(trimmed)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        // Filter: exclude core packages and uninstalled packages
        val iterator = active.iterator()
        while (iterator.hasNext()) {
            val pkg = iterator.next()
            if (AppCategories.isCore(pkg) || (installedPackages != null && !installedPackages.contains(pkg))) {
                iterator.remove()
            }
        }

        return active
    }

    fun getRunningPackages(context: Context, installedPackages: Set<String>? = null): Set<String> {
        return getActivePackages(context, installedPackages)
    }

    fun loadInstalledApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        }

        val installedSet = packages.mapNotNull { it.packageName }.toSet()
        // Prune any uninstalled packages from accessibility cache
        AppKillerAccessibilityService.pruneObservedPackages(context, installedSet)

        val runningPackages = getActivePackages(context, installedSet)

        val result = ArrayList<AppInfo>(packages.size)
        for (pkgInfo in packages) {
            val pkg = pkgInfo.packageName ?: continue
            val appInfo = pkgInfo.applicationInfo

            val category = AppCategories.categoryFor(pkg)

            val label = try {
                appInfo?.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            } ?: pkg

            val isSystem = appInfo != null && (
                (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            )

            val isRunning = runningPackages.contains(pkg)

            result.add(
                AppInfo(
                    packageName = pkg,
                    label = label,
                    category = category,
                    isSystem = isSystem,
                    isRunning = isRunning,
                ),
            )
        }

        result.sortBy { it.label.lowercase() }
        return result
    }

    fun isNodeOrAncestorClickable(node: AccessibilityNodeInfo): Boolean {
        var p: AccessibilityNodeInfo? = node
        while (p != null) {
            if (p.isClickable) return true
            p = p.parent
        }
        return false
    }

    fun isNodeAndAncestorsEnabled(node: AccessibilityNodeInfo): Boolean {
        var p: AccessibilityNodeInfo? = node
        while (p != null) {
            if (!p.isEnabled) return false
            if (p.isClickable) {
                return p.isEnabled
            }
            p = p.parent
        }
        return node.isEnabled
    }

    fun hasCancelButton(root: AccessibilityNodeInfo): Boolean {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeFirst()
            val text = node.text?.toString()?.trim()
            val viewId = node.viewIdResourceName
            if ((text != null && text.equals("Cancel", ignoreCase = true)) ||
                (viewId != null && (viewId.endsWith(":id/button2") || viewId.contains("button2", ignoreCase = true)))
            ) {
                return true
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) stack.add(child)
            }
        }
        return false
    }

    fun findForceStopNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)

        var fallbackCandidate: AccessibilityNodeInfo? = null

        while (stack.isNotEmpty()) {
            val node = stack.removeFirst()
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()
            val viewId = node.viewIdResourceName

            val isForceStopText = (text != null && text.matches(Regex("(?i)^force[\\s_-]*stop[\\s.!]*(now)?$"))) ||
                (desc != null && desc.matches(Regex("(?i)^force[\\s_-]*stop[\\s.!]*(now)?$")))
            val isForceStopId = viewId != null && (
                viewId.contains("force_stop", ignoreCase = true) ||
                viewId.contains("btn_force_stop", ignoreCase = true)
            )

            if (isForceStopText || isForceStopId) {
                val enabled = isNodeAndAncestorsEnabled(node)
                val clickable = isNodeOrAncestorClickable(node)
                if (enabled && clickable) {
                    return node
                }
                if (fallbackCandidate == null) {
                    fallbackCandidate = node
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    stack.add(child)
                }
            }
        }

        return fallbackCandidate
    }

    fun performClick(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null) {
            if (target.isClickable) {
                return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            target = target.parent
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun findConfirmationDialogButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)

        val dialogPresent = hasCancelButton(root)
        var candidate: AccessibilityNodeInfo? = null

        while (stack.isNotEmpty()) {
            val node = stack.removeFirst()
            val text = node.text?.toString()?.trim()
            val viewId = node.viewIdResourceName

            val isPositiveId = viewId != null && (
                viewId.endsWith(":id/button1") ||
                viewId.contains("button1", ignoreCase = true) ||
                viewId.contains("positive", ignoreCase = true)
            )

            val isPositiveText = text != null && (
                text.equals("OK", ignoreCase = true) ||
                (dialogPresent && text.matches(Regex("(?i)^force[\\s_-]*stop$")))
            )

            if (isPositiveId || isPositiveText) {
                if (isNodeAndAncestorsEnabled(node) && isNodeOrAncestorClickable(node)) {
                    return node
                }
                if (candidate == null) {
                    candidate = node
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    stack.add(child)
                }
            }
        }

        return candidate
    }

    suspend fun executeKillList(
        context: Context,
        packagesToKill: List<String>,
        appLabels: Map<String, String>,
        onProgress: (KillProgress) -> Unit,
    ): KillReport {
        val eligible = mutableListOf<String>()
        val skipped = mutableListOf<Pair<String, String>>()
        val killed = mutableListOf<String>()

        // 1. Enforce CORE blocklist in engine
        for (pkg in packagesToKill) {
            if (AppCategories.isCore(pkg)) {
                skipped.add(pkg to "Core system package (protected)")
            } else {
                eligible.add(pkg)
            }
        }

        // 2. Verify accessibility service instance
        val service = AppKillerAccessibilityService.instance
        if (service == null) {
            for (pkg in eligible) {
                skipped.add(pkg to "Accessibility service not running")
            }
            return KillReport(
                totalTargeted = packagesToKill.size,
                killed = killed,
                skipped = skipped,
            )
        }

        val total = eligible.size
        for ((index, pkg) in eligible.withIndex()) {
            if (!coroutineContext.isActive) {
                for (remIndex in index until eligible.size) {
                    skipped.add(eligible[remIndex] to "Cancelled by user")
                }
                break
            }

            val label = appLabels[pkg] ?: pkg
            onProgress(KillProgress(current = index + 1, total = total, currentPackage = pkg, currentLabel = label))

            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", pkg, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                skipped.add(pkg to "Failed to open settings: ${e.message ?: "Unknown error"}")
                continue
            }

            var forceStopFound = false
            var forceStopClicked = false
            var forceStopDisabled = false
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < 3500) {
                if (!coroutineContext.isActive) break
                delay(100)

                val root = service.rootInActiveWindow ?: continue
                val forceStopNode = findForceStopNode(root)
                if (forceStopNode != null) {
                    forceStopFound = true
                    if (!isNodeAndAncestorsEnabled(forceStopNode)) {
                        forceStopDisabled = true
                        break
                    }
                    val clicked = performClick(forceStopNode)
                    if (clicked) {
                        forceStopClicked = true
                        break
                    }
                }
            }

            if (forceStopDisabled) {
                markPackageKilled(context, pkg)
                skipped.add(pkg to "Already stopped (Force Stop disabled)")
                continue
            }

            if (!forceStopFound) {
                skipped.add(pkg to "Force Stop button not found (timed out)")
                continue
            }

            if (forceStopClicked) {
                val dialogStart = System.currentTimeMillis()
                delay(150)
                while (System.currentTimeMillis() - dialogStart < 1500) {
                    if (!coroutineContext.isActive) break
                    val root = service.rootInActiveWindow ?: run {
                        delay(100)
                        null
                    } ?: continue

                    val confirmBtn = findConfirmationDialogButton(root)
                    if (confirmBtn != null && isNodeAndAncestorsEnabled(confirmBtn)) {
                        performClick(confirmBtn)
                        delay(250)
                        break
                    }
                    delay(100)
                }
                markPackageKilled(context, pkg)
                killed.add(pkg)
                delay(150)
            }
        }

        try {
            val returnIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(returnIntent)
        } catch (_: Exception) {
        }

        return KillReport(
            totalTargeted = packagesToKill.size,
            killed = killed,
            skipped = skipped,
        )
    }
}
