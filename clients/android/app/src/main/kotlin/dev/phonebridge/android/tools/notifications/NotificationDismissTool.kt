package dev.phonebridge.android.tools.notifications

import android.content.Context
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.Tool
import dev.phonebridge.core.tools.ToolException
import dev.phonebridge.core.tools.ToolResult
import dev.phonebridge.core.tools.objectSchema
import dev.phonebridge.core.tools.optString
import dev.phonebridge.core.tools.reqString
import dev.phonebridge.core.tools.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.put

/** `notification_dismiss` (docs/TOOLS.md "Notifications"): clears a notification like a swipe. */
internal fun notificationDismissTool(context: Context): Tool = tool(
    name = "notification_dismiss",
    title = "Dismiss a notification",
    description = "Dismiss (clear) a notification, like swiping it away. Ongoing notifications (music, navigation, " +
        "calls) can't be dismissed. Dismissing a group summary also clears the notifications in its group.",
    scope = Scopes.NOTIFICATIONS_ACT,
    risk = Risk.ACTION,
    schema = objectSchema {
        string("key", "Notification key from notifications_list", required = true)
    },
    timeoutMs = 10_000,
    unavailable = { NotificationAccess.unavailableReason(context) },
    describe = { args ->
        val key = runCatching { args.optString("key") }.getOrNull()
        NotificationAccess.peek(context, key)
            ?.let { NotificationSummaries.dismiss(it.info.appLabel, it.info.title) }
            ?: NotificationSummaries.dismissFallback(key)
    },
) { args, _ ->
    val key = args.reqString("key")
    val target = NotificationAccess.find(context, key)
    val app = target.info.appLabel
    if (!target.info.clearable) {
        throw ToolException(
            "This $app notification is ongoing and can't be dismissed; only $app can remove it " +
                "(for example by stopping the music, navigation or call in the app).",
        )
    }
    val listener = NotificationAccess.requireListener(context)
    if (!withContext(Dispatchers.IO) { listener.cancel(key) }) {
        throw ToolException("Android refused to dismiss the notification. ${NotificationAccess.unavailableReason(context) ?: "Retry in a few seconds."}")
    }
    ToolResult.json {
        put("dismissed", true)
        put("app", app)
        target.info.title?.let { put("title", it) }
    }
}
