package com.droidhands.core.tools

/**
 * The platform-neutral tool catalog's names (docs/TOOLS.md), for code that refers to tools it cannot see,
 * such as the plugin validator checking `tool:` recipes at build time (docs/PLUGINS.md §6.5). Each
 * platform's registry should hold exactly these names; a platform may leave out tools it can't support.
 */
object ToolNames {
    const val BRIDGE_CAPABILITIES = "bridge_capabilities"
    const val APPS_LIST = "apps_list"
    const val APP_ACTIONS_LIST = "app_actions_list"
    const val APP_ACTION_RUN = "app_action_run"
    const val INTENT_START = "intent_start"

    /** Phone use, the screen tools: left out by devices without it, such as the Android store edition (docs/EDITIONS.md). */
    val PHONE_USE: Set<String> = linkedSetOf(
        "screen_observe", "screen_read", "screen_find", "screen_screenshot", "ui_wait", "ui_tap", "ui_type", "ui_scroll", "ui_swipe",
        "ui_global_action",
    )

    /**
     * The tools that read SMS and call history: left out by devices without those permissions, such as the Android
     * store edition (docs/EDITIONS.md).
     */
    val SMS_AND_CALL_LOG_READ: Set<String> = linkedSetOf("sms_list", "call_log_list")

    /** Every catalog tool, in the order of docs/TOOLS.md. */
    val ALL: Set<String> = linkedSetOf(
        // Meta
        BRIDGE_CAPABILITIES,
        // Device
        "device_status", "flashlight_set", "volume_set", "ringer_mode_set", "media_control", "clipboard_set", "speak",
        "alarm_set", "timer_set",
        // Apps
        APPS_LIST, "app_open", "url_open", APP_ACTIONS_LIST, APP_ACTION_RUN,
        // Intents
        INTENT_START,
        // Contacts
        "contacts_search",
        // Messages
        "sms_list", "call_log_list", "sms_send", "phone_call", "call_status", "call_answer", "call_end",
        // Notifications
        "notifications_list", "notification_reply", "notification_action", "notification_dismiss",
        // Calendar
        "calendar_list", "calendar_events_list", "calendar_event_create",
        // Location
        "location_get",
    ) + PHONE_USE

    /**
     * Null if a plugin's `tool:` recipe may run [name]; otherwise why not (docs/PLUGINS.md §9). Refused:
     * unknown names, `intent_start` (raw intents), the generic screen tools (`screen_*`, `ui_*`, which
     * recipes use through `steps`), and the meta and app-action tools (no nesting).
     */
    fun recipeRefusal(name: String): String? = when {
        name !in ALL -> "'$name' is not a DroidHands tool (see docs/TOOLS.md)"
        name == INTENT_START -> "'$name' can't be used by plugins: use an intent recipe, which is pinned to the app"
        name.startsWith("screen_") || name.startsWith("ui_") ->
            "'$name' can't be used by plugins: use a steps recipe, which is confined to the app"
        name == BRIDGE_CAPABILITIES || name == APPS_LIST || name == APP_ACTIONS_LIST || name == APP_ACTION_RUN ->
            "'$name' can't be used by plugins: plugin actions can't list or run other actions"
        else -> null
    }
}
