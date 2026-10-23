package dev.trmx.gui.killer

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import dev.trmx.gui.MainActivity
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

object AppKillerEngine {

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

    fun getRunningPackages(context: Context, installedPackages: Set<String>? = null): Set<String> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return emptySet()
        val runningProcesses = am.runningAppProcesses ?: return emptySet()
        val running = mutableSetOf<String>()
        for (proc in runningProcesses) {
            val pkgList = proc.pkgList ?: continue
            for (pkg in pkgList) {
                val trimmed = pkg?.trim() ?: continue
                if (trimmed.isEmpty()) continue
                // Exclude core packages (includes this app itself dev.trmx.gui, com.termux*, and core blocklist)
                if (AppCategories.isCore(trimmed)) continue
                if (installedPackages != null && !installedPackages.contains(trimmed)) continue
                running.add(trimmed)
            }
        }
        return running
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
        val runningPackages = getRunningPackages(context, installedSet)

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
