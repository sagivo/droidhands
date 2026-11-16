package com.droidhands.android.tools.notifications

import android.content.Context
import com.droidhands.android.notify.ActionSelector
import com.droidhands.android.notify.NotificationActions
import com.droidhands.android.tools.system.LaunchNotes
import com.droidhands.android.tools.system.Redaction
import com.droidhands.android.tools.system.putIfNotNull
import com.droidhands.core.tools.Risk
import com.droidhands.core.tools.Scopes
import com.droidhands.core.tools.Tool
import com.droidhands.core.tools.ToolException
import com.droidhands.core.tools.ToolResult
import com.droidhands.core.tools.objectSchema
import com.droidhands.core.tools.optInt
import com.droidhands.core.tools.optString
import com.droidhands.core.tools.reqString
import com.droidhands.core.tools.tool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

/** `notification_action` (docs/TOOLS.md "Notifications"): taps an action button. Pre-confirmed. */
internal fun notificationActionTool(context: Context): Tool {
    val described = DescribedActions()
    return tool(
        name = "notification_action",
        title = "Tap a notification action",
        description = "Tap one of a notification's action buttons (e.g. \"Mark as read\", \"Archive\", \"Answer\"), chosen " +
            "by its title (case-insensitive) or by its index from notifications_list (use the index when titles are " +
            "blank). Actions that need typed text (can_reply=true) must use notification_reply instead. Some buttons " +
            "open a screen (e.g. Gmail's Reply; result opens_screen=true): Android lets DroidHands open screens from the " +
            "background only while its accessibility service is on, and the user sees them only when the phone is " +
            "unlocked; the result's note says when that may have stopped it. Continue with screen_read / ui_* tools. " +
            "Refused for apps blocked for this agent. The user may have to approve it on the phone.",
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
    ) { args, ctx ->
        val key = args.reqString("key")
        val title = args.optString("action")
        val index = args.optInt("index")

        val target = NotificationAccess.find(context, key, ctx.appAccess)
        // Select among the titles the agent was shown (codes masked), then compare the real title with the approved one.
        val visible = NotificationRedaction.redact(target.info, Redaction.forCall(ctx))
        if (visible.actions.isEmpty() && visible.contentHiddenByAndroid) throw ToolException(NotificationAccess.HIDDEN_WITHOUT_ACTION)
        val chosen = ActionSelector.select(visible.actions, title, index)
        val realTitle = target.info.actions.firstOrNull { it.index == chosen.index }?.title ?: chosen.title
        described.take(key, title, index)?.let { approved ->
            if (NotificationActions.normalize(approved) != NotificationActions.normalize(realTitle)) {
                // The approved title is unmasked (it was shown only to the user): don't echo it.
                throw ToolException(
                    "The notification changed after the user approved the tap; it now resolves to \"${chosen.title}\". " +
                        "Nothing was tapped. Call notifications_list and try again.",
                )
            }
        }
        if (chosen.requiresInput) {
            throw ToolException("The action \"${chosen.title}\" needs typed text. Use notification_reply with this key and a message instead.")
        }
        val action = target.action(chosen.index) ?: throw ToolException(NotificationAccess.notFound(key))
        val opensScreen = NotificationSender.opensActivity(action)
        NotificationSender.tap(context, action)

        ToolResult.json {
            put("done", true)
            put("action", chosen.title)
            put("app", target.info.appLabel)
            NotificationJson.title(visible)?.let { put("title", it) }
            if (visible.codesHidden) put(Redaction.FLAG, true)
            if (opensScreen == true) put("opens_screen", true)
            // Unknown (Android 11) counts as "may open a screen".
            if (opensScreen != false) putIfNotNull("note", LaunchNotes.afterLaunch(context))
        }
    }
}

/**
 * `Tap "<action>" on <app> notification: <title>` from the live notification, bounded to
 * [NotificationAccess.PEEK_TIMEOUT_MS]; falls back to the raw arguments. Never throws. Shown only
 * to the phone's owner, so titles are not masked.
 */
private fun describeAction(context: Context, args: JsonObject, described: DescribedActions): String {
    val key = runCatching { args.optString("key") }.getOrNull()
    val title = runCatching { args.optString("action") }.getOrNull()
    val index = runCatching { args.optInt("index") }.getOrNull()
    val target = NotificationAccess.peek(context, key)
    val chosen = target?.let { runCatching { ActionSelector.select(it.info.actions, title, index) }.getOrNull() }
    if (key == null || target == null || chosen == null) return NotificationSummaries.actionFallback(key, title, index)
    described.remember(key, title, index, chosen.title)
    return NotificationSummaries.action(chosen.title, target.info.appLabel, target.info.title, chosen.index)
}
