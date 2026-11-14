package dev.phonebridge.android.ui.setup

import dev.phonebridge.core.tools.Scopes

/** State of one Setup checklist row. */
enum class SetupStatus {
    DONE,

    /** Some permissions of the group granted (e.g. approximate location only). */
    PARTIAL,

    /** Enabled in system settings but not active yet (accessibility service not bound). */
    PENDING,

    MISSING,

    /** Depends on another item first (background location needs location). */
    NEEDS_PREREQUISITE,
}

/**
 * One checklist row. [permissions] is non-empty for runtime-permission rows (requested with
 * RequestMultiplePermissions); special accesses open a system settings screen instead.
 */
data class SetupItem(
    val id: String,
    val title: String,
    val description: String,
    /** What the row enables, e.g. "Contacts · Send messages · Phone calls". */
    val unlocks: String,
    val essential: Boolean,
    val status: SetupStatus,
    val permissions: List<String> = emptyList(),
    /** Android won't show the permission dialog again; the user must use App info. */
    val blocked: Boolean = false,
    /** Show the Android 13+ "Restricted setting" help (sideloaded app, access not granted yet). */
    val restrictedSettingsHelp: Boolean = false,
    /**
     * Show the step-by-step "Allow restricted settings" card instead ([RestrictedSettings.showGuide]):
     * the accessibility row of an app installed from an APK file, accessibility still off.
     */
    val restrictedSettingsGuide: Boolean = false,
)

data class SetupProgress(val done: Int, val total: Int, val essentialMissing: List<SetupItem>) {
    val fraction: Float get() = if (total == 0) 1f else done.toFloat() / total
    val essentialComplete: Boolean get() = essentialMissing.isEmpty()
}

/** Pure checklist logic shared by Home and Setup (unit-tested). */
object SetupLogic {
    // Ids of special-access rows. Runtime rows use PermissionGroup.id.
    const val NOTIFICATIONS = "notifications"
    const val CONTACTS = "contacts"
    const val LOCATION = "location"
    const val BACKGROUND_LOCATION = "background_location"
    const val ACCESSIBILITY = "accessibility"
    const val NOTIFICATION_ACCESS = "notification_access"
    const val BATTERY = "battery"
    const val OVERLAY = "overlay"
    const val DND = "dnd"

    /** Rows needed for the core use case ("send WhatsApp to Tania", approvals, staying reachable). */
    val ESSENTIAL_IDS: Set<String> = setOf(NOTIFICATIONS, CONTACTS, ACCESSIBILITY, NOTIFICATION_ACCESS, BATTERY)

    /** First Android version with "restricted settings" for sideloaded apps (Android 13). */
    const val RESTRICTED_SETTINGS_SDK = 33

    private val SCOPE_SHORT_LABELS: Map<String, String> = mapOf(
        Scopes.DEVICE to "Device controls",
        Scopes.APPS to "Apps & links",
        Scopes.CONTACTS_READ to "Contacts",
        Scopes.MESSAGES_READ to "Read SMS & call log",
        Scopes.MESSAGES_SEND to "Send messages",
        Scopes.CALLS to "Phone calls",
        Scopes.NOTIFICATIONS_READ to "Read notifications",
        Scopes.NOTIFICATIONS_ACT to "Reply to notifications",
        Scopes.CALENDAR_READ to "Read calendar",
        Scopes.CALENDAR_WRITE to "Create events",
        Scopes.LOCATION to "Location",
        Scopes.SCREEN_READ to "Read screen",
        Scopes.SCREEN_CONTROL to "Control apps",
        Scopes.INTENTS to "Raw intents",
    )

    fun shortLabel(scope: String): String = SCOPE_SHORT_LABELS[scope] ?: scope

    /** "Contacts · Send messages · Phone calls" for a permission group's scopes. */
    fun unlocksForScopes(scopes: List<String>): String = scopes.distinct().joinToString(" · ") { shortLabel(it) }

    fun runtimeStatus(granted: Int, total: Int): SetupStatus = when {
        total == 0 || granted >= total -> SetupStatus.DONE
        granted > 0 -> SetupStatus.PARTIAL
        else -> SetupStatus.MISSING
    }

    /**
     * Heuristic for "don't ask again": every missing permission was requested before and Android no
     * longer wants a rationale, which means the dialog won't be shown again.
     */
    fun isPermanentlyDenied(missing: List<String>, asked: Set<String>, showRationale: (String) -> Boolean): Boolean =
        missing.isNotEmpty() && missing.all { it in asked && !showRationale(it) }

    fun progress(items: List<SetupItem>): SetupProgress = SetupProgress(
        done = items.count { it.status == SetupStatus.DONE },
        total = items.size,
        essentialMissing = items.filter { it.essential && it.status != SetupStatus.DONE },
    )

    /** `InstallSourceInfo.PACKAGE_SOURCE_STORE` (API 33): installed by an app store, never restricted. */
    const val PACKAGE_SOURCE_STORE = 2

    /**
     * Android 13+ may grey out the toggle of a sideloaded app ("Restricted setting"). [packageSource]
     * is `InstallSourceInfo.packageSource` when known; store installs are never restricted.
     */
    fun showsRestrictedSettingsHelp(id: String, status: SetupStatus, sdkInt: Int, packageSource: Int? = null): Boolean =
        sdkInt >= RESTRICTED_SETTINGS_SDK && status != SetupStatus.DONE && (id == ACCESSIBILITY || id == NOTIFICATION_ACCESS) &&
            packageSource != PACKAGE_SOURCE_STORE

    /** Label of the row's action button, or null when there is nothing to do. */
    fun actionLabel(item: SetupItem): String? = when {
        item.status == SetupStatus.DONE || item.status == SetupStatus.NEEDS_PREREQUISITE -> null
        item.blocked -> "Open App info"
        item.id == BACKGROUND_LOCATION -> "Allow all the time"
        item.id == LOCATION && item.status == SetupStatus.PARTIAL -> "Allow precise"
        item.permissions.isNotEmpty() -> if (item.status == SetupStatus.PARTIAL) "Allow the rest" else "Allow"
        item.id == ACCESSIBILITY -> "Open Accessibility"
        item.id == BATTERY -> "Allow"
        else -> "Open settings"
    }

    fun statusLabel(status: SetupStatus, blocked: Boolean): String = when {
        status == SetupStatus.DONE -> "Done"
        blocked -> "Blocked, open App info"
        status == SetupStatus.PARTIAL -> "Partly granted"
        status == SetupStatus.PENDING -> "Enabled, connecting"
        status == SetupStatus.NEEDS_PREREQUISITE -> "Needs location first"
        else -> "Not granted"
    }
}
