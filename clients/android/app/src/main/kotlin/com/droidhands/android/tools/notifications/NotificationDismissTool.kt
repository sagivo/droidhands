package com.droidhands.android.tools.notifications

import android.content.Context
import com.droidhands.android.tools.system.Redaction
import com.droidhands.core.tools.Risk
import com.droidhands.core.tools.Scopes
import com.droidhands.core.tools.Tool
import com.droidhands.core.tools.ToolException
import com.droidhands.core.tools.ToolResult
import com.droidhands.core.tools.objectSchema
import com.droidhands.core.tools.optString
import com.droidhands.core.tools.reqString
import com.droidhands.core.tools.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.put

/** `notification_dismiss` (docs/TOOLS.md "Notifications"): clears a notification like a swipe. */
internal fun notificationDismissTool(context: Context): Tool = tool(
    name = "notification_dismiss",
    title = "Dismiss a notification",
    description = "Dismiss (clear) a notification, like swiping it away. Ongoing notifications (music, navigation, " +
        "calls) can't be dismissed. Dismissing a group summary also clears the notifications in its group. Refused for " +
        "apps blocked for this agent.",
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
) { args, ctx ->
    val key = args.reqString("key")
    val target = NotificationAccess.find(context, key, ctx.appAccess)
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
    val visible = NotificationRedaction.redact(target.info, Redaction.forCall(ctx))
    ToolResult.json {
        put("dismissed", true)
        put("app", app)
        NotificationJson.title(visible)?.let { put("title", it) }
        if (visible.codesHidden) put(Redaction.FLAG, true)
    }
}
