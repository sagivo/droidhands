package dev.phonebridge.android.tools.support

import android.Manifest
import android.content.Context
import dev.phonebridge.core.tools.Scopes

/** A runtime permission group shown in the Setup screen. */
data class PermissionGroup(
    val id: String,
    val title: String,
    val rationale: String,
    /** Requested together with ActivityResultContracts.RequestMultiplePermissions. */
    val permissions: List<String>,
    val scopes: List<String>,
) {
    fun isGranted(context: Context) = permissions.all { Perms.has(context, it) }
}

object PermissionCatalog {
    val NOTIFICATIONS = PermissionGroup(
        "notifications", "Notifications",
        "Show the bridge status and Allow/Deny prompts for agent actions.",
        listOf(Manifest.permission.POST_NOTIFICATIONS), emptyList(),
    )

    val RUNTIME: List<PermissionGroup> = listOf(
        NOTIFICATIONS,
        PermissionGroup(
            "contacts", "Contacts", "Find people by name (\"send WhatsApp to Tania\").",
            listOf(Manifest.permission.READ_CONTACTS), listOf(Scopes.CONTACTS_READ, Scopes.MESSAGES_SEND, Scopes.CALLS),
        ),
        PermissionGroup(
            "sms", "SMS", "Read and send text messages.",
            listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS), listOf(Scopes.MESSAGES_READ, Scopes.MESSAGES_SEND),
        ),
        PermissionGroup(
            "phone", "Phone & call log", "Place calls and read call history.",
            listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_PHONE_STATE),
            listOf(Scopes.CALLS, Scopes.MESSAGES_READ),
        ),
        PermissionGroup(
            "calendar", "Calendar", "Read and create events.",
            listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), listOf(Scopes.CALENDAR_READ, Scopes.CALENDAR_WRITE),
        ),
        PermissionGroup(
            "location", "Location", "Tell agents where the phone is.",
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), listOf(Scopes.LOCATION),
        ),
    )

    /**
     * Must be requested AFTER [RUNTIME] location is granted, on its own (Android 11+ sends the user to
     * Settings > "Allow all the time"). Needed because agent calls arrive while the app is in background.
     */
    val BACKGROUND_LOCATION = PermissionGroup(
        "background_location", "Location while in background", "Required for location requests while the app is not open.",
        listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), listOf(Scopes.LOCATION),
    )
}
