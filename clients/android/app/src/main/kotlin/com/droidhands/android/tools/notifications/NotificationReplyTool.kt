package com.droidhands.android.tools.notifications

import android.app.Notification
import android.content.Context
import com.droidhands.android.notify.ActionSelector
import com.droidhands.android.notify.MappedNotification
import com.droidhands.android.notify.NotificationText
import com.droidhands.android.tools.system.Redaction
import com.droidhands.core.tools.AppAccess
import com.droidhands.core.tools.Risk
import com.droidhands.core.tools.Scopes
import com.droidhands.core.tools.Tool
import com.droidhands.core.tools.ToolException
import com.droidhands.core.tools.ToolResult
import com.droidhands.core.tools.objectSchema
import com.droidhands.core.tools.optString
import com.droidhands.core.tools.reqString
import com.droidhands.core.tools.tool
import kotlinx.serialization.json.put

/** Upper bound for a reply; far above any chat message, it only rejects runaway input. */
private const val MAX_REPLY_LENGTH = 10_000

/** `notification_reply` (docs/TOOLS.md "Notifications"): inline reply through RemoteInput. */
internal fun notificationReplyTool(context: Context): Tool = tool(
    name = "notification_reply",
    title = "Reply to a notification",
    description = "Reply to a message notification through its inline reply action, exactly as if the user typed into " +
        "the notification. Works for any app with a reply button (WhatsApp, Telegram, Signal, Messages, Slack…) and " +
        "works while the phone is locked. Get `key` from notifications_list and check that the notification has an " +
        "action with can_reply=true (its title may be blank when Android hid the content; that is fine). The reply " +
        "goes to that notification's conversation. Refused for apps blocked for this agent. The user may have to " +
        "approve it on the phone.",
    scope = Scopes.NOTIFICATIONS_ACT,
    risk = Risk.SENSITIVE,
    schema = objectSchema {
        string("key", "Notification key from notifications_list", required = true)
        string("message", "Text to send as the reply", required = true)
    },
    timeoutMs = 20_000,
    confirmsInternally = true,
    unavailable = { NotificationAccess.unavailableReason(context) },
    describe = { args ->
        val message = runCatching { args.optString("message") }.getOrNull()
        "Reply to a notification" + (message?.let { ": \"${NotificationText.ellipsize(it, 200)}\"" } ?: "")
    },
) { args, ctx ->
    val key = args.reqString("key")
    val message = args.reqString("message")
    if (message.length > MAX_REPLY_LENGTH) {
        throw ToolException("The message is too long (${message.length} chars, max $MAX_REPLY_LENGTH). Shorten it or split it.")
    }

    val target = NotificationAccess.find(context, key, ctx.appAccess)
    val hidden = target.info.contentHiddenByAndroid
    val replyInfo = ActionSelector.replyAction(target.info.actions)
        ?: throw ToolException(if (hidden) NotificationAccess.HIDDEN_WITHOUT_ACTION else ActionSelector.NO_REPLY_ACTION)
    val app = target.info.appLabel
    // The prompt is shown to the phone's owner, so it uses the unmasked title.
    ctx.requireConfirmation(NotificationSummaries.reply(app, target.info.title.takeUnless { hidden }, message, conversationHidden = hidden))

    // Approval can take up to a minute; prefer the PendingIntent the app has posted since.
    val action = currentReplyAction(context, target, ctx.appAccess)
        ?: target.action(replyInfo.index)
        ?: throw ToolException(ActionSelector.NO_REPLY_ACTION)
    NotificationSender.reply(context, action, message)

    val visible = NotificationRedaction.redact(target.info, Redaction.forCall(ctx))
    ToolResult.json {
        put("replied", true)
        put("app", app)
        if (hidden) {
            put(NotificationJson.CONTENT_HIDDEN, true)
        } else {
            NotificationJson.title(visible)?.let { put("conversation", it) }
        }
        if (visible.codesHidden) put(Redaction.FLAG, true)
    }
}

/**
 * The reply action of the notification as it is now, if it still exists and still shows the
 * same conversation. Null otherwise: the caller then uses the action the user approved, whose
 * `PendingIntent` usually stays valid after the notification is gone.
 */
private suspend fun currentReplyAction(context: Context, original: MappedNotification, access: AppAccess): Notification.Action? {
    val current = try {
        NotificationAccess.find(context, original.info.key, access)
    } catch (e: ToolException) {
        return null
    }
    if (current.info.title != original.info.title || current.info.packageName != original.info.packageName) return null
    val info = ActionSelector.replyAction(current.info.actions) ?: return null
    return current.action(info.index)
}
