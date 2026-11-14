package dev.phonebridge.core.tools

/** Token scopes. See docs/SECURITY.md. */
object Scopes {
    const val DEVICE = "device"
    const val APPS = "apps"
    const val CONTACTS_READ = "contacts.read"
    const val MESSAGES_READ = "messages.read"
    const val MESSAGES_SEND = "messages.send"
    const val CALLS = "calls"
    const val NOTIFICATIONS_READ = "notifications.read"
    const val NOTIFICATIONS_ACT = "notifications.act"
    const val CALENDAR_READ = "calendar.read"
    const val CALENDAR_WRITE = "calendar.write"
    const val LOCATION = "location"
    const val SCREEN_READ = "screen.read"
    const val SCREEN_CONTROL = "screen.control"
    const val INTENTS = "intents"

    /** Scope -> human description (UI). Ordered. */
    val DESCRIPTIONS: Map<String, String> = linkedMapOf(
        DEVICE to "Device status & settings (volume, flashlight, media, alarms, clipboard, speech)",
        APPS to "List and open apps, URLs and chats",
        CONTACTS_READ to "Search contacts",
        MESSAGES_READ to "Read SMS and call history",
        MESSAGES_SEND to "Send SMS and WhatsApp messages",
        CALLS to "Place phone calls",
        NOTIFICATIONS_READ to "Read notifications (includes message previews)",
        NOTIFICATIONS_ACT to "Reply to / act on / dismiss notifications",
        CALENDAR_READ to "Read calendar",
        CALENDAR_WRITE to "Create calendar events",
        LOCATION to "Current location",
        SCREEN_READ to "Read screen content of any app, screenshots",
        SCREEN_CONTROL to "Tap, type, swipe in any app (full control)",
        INTENTS to "Start arbitrary Android activities (advanced)",
    )

    val ALL: Set<String> = DESCRIPTIONS.keys

    val PRESET_READ_ONLY: Set<String> = setOf(
        DEVICE, APPS, CONTACTS_READ, MESSAGES_READ, NOTIFICATIONS_READ, CALENDAR_READ, LOCATION, SCREEN_READ,
    )

    val PRESET_ASSISTANT: Set<String> = PRESET_READ_ONLY + setOf(MESSAGES_SEND, CALLS, NOTIFICATIONS_ACT, CALENDAR_WRITE)

    val PRESET_FULL: Set<String> = ALL

    /** Preset name -> scopes (UI). */
    val PRESETS: Map<String, Set<String>> = linkedMapOf(
        "Read-only" to PRESET_READ_ONLY,
        "Assistant" to PRESET_ASSISTANT,
        "Full control" to PRESET_FULL,
    )

    /**
     * Preset name -> the [dev.phonebridge.core.auth.TokenRecord.maxRisk] to store with it (null = no cap).
     * "Read-only" is capped at [Risk.READ]: its `device` and `apps` scopes also hold ACTION tools (volume,
     * ringer, clipboard, alarms, opening apps and links), which the cap hides and refuses. Same keys as [PRESETS].
     */
    val PRESET_MAX_RISK: Map<String, Risk?> = linkedMapOf(
        "Read-only" to Risk.READ,
        "Assistant" to null,
        "Full control" to null,
    )

    /** True if a token holding [granted] may use a tool requiring [required] ("" = always allowed). */
    fun allows(granted: Set<String>, required: String): Boolean = required.isEmpty() || required in granted
}
