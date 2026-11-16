package com.droidhands.android.tools.screen

import android.accessibilityservice.AccessibilityService

/** ui_global_action names → `AccessibilityService.GLOBAL_ACTION_*` (all available on API 30+). */
object GlobalActions {
    val BY_NAME: Map<String, Int> = linkedMapOf(
        "back" to AccessibilityService.GLOBAL_ACTION_BACK,
        "home" to AccessibilityService.GLOBAL_ACTION_HOME,
        "recents" to AccessibilityService.GLOBAL_ACTION_RECENTS,
        "notifications" to AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
        "quick_settings" to AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
        "lock_screen" to AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN,
        "power_dialog" to AccessibilityService.GLOBAL_ACTION_POWER_DIALOG,
    )

    val NAMES: List<String> = BY_NAME.keys.toList()

    /** Lenient lookup: case-insensitive, accepts "quick-settings" / "quick settings". */
    fun parse(raw: String): Pair<String, Int>? {
        val name = raw.trim().lowercase().replace('-', '_').replace(' ', '_')
        return BY_NAME[name]?.let { name to it }
    }
}
