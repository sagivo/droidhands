package dev.phonebridge.android.tools.notifications

import dev.phonebridge.android.notify.FilteredNotifications
import dev.phonebridge.android.notify.NotificationQuery
import dev.phonebridge.android.notify.NotificationText

/**
 * One-line summaries shown in on-phone confirmation prompts (pure). Notification titles are
 * shortened; the reply text is shown in full because it is exactly what will be sent.
 */
object NotificationSummaries {
    private const val MAX_LABEL = 80

    /**
     * `Reply in WhatsApp to Tania Cohen: "See you at 8"`. With [conversationHidden] (Android hid the
     * notification's content) the prompt says so, because the user can't tell the recipient from it.
     */
    fun reply(app: String, conversation: String?, message: String, conversationHidden: Boolean = false): String {
        val target = when {
            conversationHidden -> " to the sender of a notification whose content Android hides from PhoneBridge"
            conversation != null -> " to ${label(conversation)}"
            else -> ""
        }
        return "Reply in ${label(app)}$target: \"$message\""
    }

    /**
     * `Tap "Mark as read" on WhatsApp notification: Tania Cohen`. Blank titles (Android 15 hides
     * them on sensitive notifications) are described by [index] instead.
     */
    fun action(actionTitle: String, app: String, title: String?, index: Int? = null): String {
        val what = if (actionTitle.isBlank()) "button #${index ?: "?"} (Android hid its title)" else "\"${label(actionTitle)}\""
        return "Tap $what on ${label(app)} notification" + suffix(title)
    }

    /** Used when the notification can't be looked up quickly: built from the raw arguments. */
    fun actionFallback(key: String?, action: String?, index: Int?): String {
        val what = action?.let { "\"${label(it)}\"" } ?: index?.let { "action #$it" } ?: "an action"
        return "Tap $what on notification ${key?.let { label(it) } ?: "(no key)"}"
    }

    /** `Dismiss Gmail notification: Invoice #42`. */
    fun dismiss(app: String, title: String?): String = "Dismiss ${label(app)} notification" + suffix(title)

    fun dismissFallback(key: String?): String = "Dismiss notification ${key?.let { label(it) } ?: "(no key)"}"

    /** Footer hint when Android hid the content of listed notifications (see [NotificationJson.CONTENT_HIDDEN]). */
    const val CONTENT_HIDDEN_HINT =
        "Android hid the content of notification(s) marked ${NotificationJson.CONTENT_HIDDEN}: read SMS with sms_list and " +
            "check or answer calls with call_status / call_answer. notification_reply still works on them; choose " +
            "actions by index because their titles may be blank."

    /** Footer when [hiddenByAccess]: some notifications were left out because their app is blocked for the agent. */
    const val HIDDEN_BY_ACCESS = "Notifications from apps blocked for this agent are not shown."

    /**
     * Extra text appended to a `notifications_list` result, or null if there is nothing to say: how
     * many matches were cut by the limit, or a hint when `package` looks like an app name instead
     * of a package; plus [CONTENT_HIDDEN_HINT] when a listed item's content was hidden by Android,
     * and [HIDDEN_BY_ACCESS] when [hiddenByAccess].
     */
    fun listFooter(query: NotificationQuery, page: FilteredNotifications, hiddenByAccess: Boolean = false): String? {
        val pkg = query.packageName
        val main = when {
            page.omitted > 0 -> {
                val how = if (query.limit < NotificationQuery.MAX_LIMIT) {
                    "Raise limit (max ${NotificationQuery.MAX_LIMIT}) or narrow with package/query."
                } else {
                    "Narrow with package/query."
                }
                "${page.omitted} more matching notification(s) not shown. $how"
            }
            page.items.isEmpty() && pkg != null && '.' !in pkg ->
                "No notifications for package \"$pkg\". 'package' must be an exact package name such as com.whatsapp, " +
                    "not an app name; omit it to list all apps."
            else -> null
        }
        val notes = listOfNotNull(
            main,
            CONTENT_HIDDEN_HINT.takeIf { page.items.any { it.contentHiddenByAndroid } },
            HIDDEN_BY_ACCESS.takeIf { hiddenByAccess },
        )
        return notes.joinToString(" ").ifEmpty { null }
    }

    private fun suffix(title: String?): String = title?.takeIf { it.isNotBlank() }?.let { ": ${label(it)}" } ?: ""

    private fun label(value: String): String = NotificationText.ellipsize(value, MAX_LABEL)
}
