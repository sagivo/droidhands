package dev.phonebridge.android.tools.notifications

import android.content.Context
import dev.phonebridge.android.notify.ActionSelector
import dev.phonebridge.android.notify.NotificationActions
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.Tool
import dev.phonebridge.core.tools.ToolException
import dev.phonebridge.core.tools.ToolResult
import dev.phonebridge.core.tools.objectSchema
import dev.phonebridge.core.tools.optInt
import dev.phonebridge.core.tools.optString
import dev.phonebridge.core.tools.reqString
import dev.phonebridge.core.tools.tool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

/** `notification_action` (docs/TOOLS.md "Notifications"): taps an action button. Pre-confirmed. */
internal fun notificationActionTool(context: Context): Tool {
    val described = DescribedActions()
    return tool(
        name = "notification_action",
        title = "Tap a notification action",
        description = "Tap one of a notification's action buttons (e.g. \"Mark as read\", \"Archive\", \"Answer\"), chosen " +
            "by its title (case-insensitive) or by its index from notifications_list. Actions that need typed text " +
            "(can_reply=true) must use notification_reply instead. The user may have to approve it on the phone.",
        scope = Scopes.NOTIFICATIONS_ACT,
        risk = Risk.SENSITIVE,
        schema = objectSchema {
            string("key", "Notification key from notifications_list", required = true)
            string("action", "Action button title, case-insensitive, e.g. \"Mark as read\"")
            integer("index", "Action index from notifications_list (alternative to 'action')", minimum = 0)
        },
        timeoutMs = 15_000,
        unavailable = { NotificationAccess.unavailableReason(context) },
        describe = { args -> describeAction(context, args, described) },
    ) { args, _ ->
        val key = args.reqString("key")
        val title = args.optString("action")
        val index = args.optInt("index")

        val target = NotificationAccess.find(context, key)
        val chosen = ActionSelector.select(target.info.actions, title, index)
        described.take(key, title, index)?.let { approved ->
            if (NotificationActions.normalize(approved) != NotificationActions.normalize(chosen.title)) {
                throw ToolException(
                    "The notification changed after the user approved \"$approved\"; it now resolves to \"${chosen.title}\". " +
                        "Nothing was tapped. Call notifications_list and try again.",
                )
            }
        }
        if (chosen.requiresInput) {
            throw ToolException("The action \"${chosen.title}\" needs typed text. Use notification_reply with this key and a message instead.")
        }
        val action = target.action(chosen.index) ?: throw ToolException(NotificationAccess.notFound(key))
        NotificationSender.tap(context, action)

        ToolResult.json {
            put("done", true)
            put("action", chosen.title)
            put("app", target.info.appLabel)
            target.info.title?.let { put("title", it) }
        }
    }
}

/**
 * `Tap "<action>" on <app> notification: <title>` from the live notification, bounded to
 * [NotificationAccess.PEEK_TIMEOUT_MS]; falls back to the raw arguments. Never throws.
 */
private fun describeAction(context: Context, args: JsonObject, described: DescribedActions): String {
    val key = runCatching { args.optString("key") }.getOrNull()
    val title = runCatching { args.optString("action") }.getOrNull()
    val index = runCatching { args.optInt("index") }.getOrNull()
    val target = NotificationAccess.peek(context, key)
    val chosen = target?.let { runCatching { ActionSelector.select(it.info.actions, title, index) }.getOrNull() }
    if (key == null || target == null || chosen == null) return NotificationSummaries.actionFallback(key, title, index)
    described.remember(key, title, index, chosen.title)
    return NotificationSummaries.action(chosen.title, target.info.appLabel, target.info.title)
}
