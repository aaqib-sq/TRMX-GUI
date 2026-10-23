package dev.trmx.gui.killer

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class AppKillerAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Dispatched if active listener is registered
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
        @Volatile
        var instance: AppKillerAccessibilityService? = null
            private set

        @Volatile
        var eventListener: ((AccessibilityEvent?) -> Unit)? = null

        val isRunning: Boolean
            get() = instance != null
    }
}
