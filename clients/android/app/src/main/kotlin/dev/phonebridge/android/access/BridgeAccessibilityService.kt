package dev.phonebridge.android.access

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityEvent

/**
 * Generic UI automation for any app (docs/TOOLS.md "Screen").
 *
 * STUB — implemented by the UI-automation agent. Companion API is used by other components.
 */
class BridgeAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    companion object {
        /** Connected service instance, or null when accessibility is off / not yet bound. */
        @Volatile
        var instance: BridgeAccessibilityService? = null
            private set

        /** Whether the user enabled this service in system settings (may be enabled but not yet bound). */
        fun isEnabled(context: Context): Boolean = TODO()

        /** Package currently in the foreground (tracked from window events), or null if unknown. */
        val foregroundPackage: String? get() = TODO()
    }
}
