package dev.trmx.gui.killer

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import java.util.Collections
import java.util.HashSet
import java.util.LinkedHashSet

/**
 * Accessibility service for TRMX App Killer.
 *
 * Privacy guarantee:
 * STRICTLY ON-DEVICE ONLY. Accessibility events and observed foreground packages are processed
 * solely in local device memory and local private storage. ZERO network calls, zero uploads,
 * zero analytics, zero ad SDKs, and zero telemetry. No data leaves the device.
 */
class AppKillerAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        initStorage(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null && (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED)) {
            val pkg = event.packageName?.toString()?.trim()
            if (!pkg.isNullOrEmpty() && !AppCategories.isCore(pkg)) {
                recordObservedPackage(this, pkg)
            }
        }
        // Dispatched if active listener is registered (for automated kill loop)
        eventListener?.invoke(event)
    }

    override fun onInterrupt() {
        // Required callback when accessibility service is interrupted
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) {
            instance = null
        }
        eventListener = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
        eventListener = null
    }

    companion object {
        private const val PREFS_NAME = "trmx_app_killer_prefs"
        private const val KEY_OBSERVED_PACKAGES = "observed_packages"

        @Volatile
        var instance: AppKillerAccessibilityService? = null
            private set

        @Volatile
        var eventListener: ((AccessibilityEvent?) -> Unit)? = null

        val isRunning: Boolean
            get() = instance != null

        private val observedPackages = Collections.synchronizedSet(LinkedHashSet<String>())
        @Volatile
        private var storageInitialized = false

        fun initStorage(context: Context) {
            if (storageInitialized) return
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val saved = prefs.getStringSet(KEY_OBSERVED_PACKAGES, null)
                if (saved != null) {
                    observedPackages.addAll(saved)
                }
                storageInitialized = true
            } catch (_: Exception) {
            }
        }

        fun recordObservedPackage(context: Context?, packageName: String) {
            val pkg = packageName.trim()
            if (pkg.isEmpty() || AppCategories.isCore(pkg)) return
            if (observedPackages.add(pkg)) {
                persistStorage(context)
            }
        }

        fun removeObservedPackage(context: Context?, packageName: String) {
            val pkg = packageName.trim()
            if (observedPackages.remove(pkg)) {
                persistStorage(context)
            }
        }

        fun getObservedPackages(context: Context): Set<String> {
            initStorage(context)
            val inst = instance
            if (inst != null) {
                try {
                    val root = inst.rootInActiveWindow
                    val rootPkg = root?.packageName?.toString()?.trim()
                    if (!rootPkg.isNullOrEmpty() && !AppCategories.isCore(rootPkg)) {
                        recordObservedPackage(context, rootPkg)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        inst.windows?.forEach { win ->
                            val p = win.root?.packageName?.toString()?.trim()
                            if (!p.isNullOrEmpty() && !AppCategories.isCore(p)) {
                                recordObservedPackage(context, p)
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            return synchronized(observedPackages) {
                HashSet(observedPackages)
            }
        }

        fun pruneObservedPackages(context: Context?, validPackages: Set<String>) {
            var changed = false
            synchronized(observedPackages) {
                val it = observedPackages.iterator()
                while (it.hasNext()) {
                    val p = it.next()
                    if (!validPackages.contains(p) || AppCategories.isCore(p)) {
                        it.remove()
                        changed = true
                    }
                }
            }
            if (changed) {
                persistStorage(context)
            }
        }

        private fun persistStorage(context: Context?) {
            if (context == null) return
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val copy = synchronized(observedPackages) { HashSet(observedPackages) }
                prefs.edit().putStringSet(KEY_OBSERVED_PACKAGES, copy).apply()
            } catch (_: Exception) {
            }
        }
    }
}
