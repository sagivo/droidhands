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

    /** `Reply in WhatsApp to Tania Cohen: "See you at 8"`. */
    fun reply(app: String, conversation: String?, message: String): String {
        val target = conversation?.let { " to ${label(it)}" } ?: ""
        return "Reply in ${label(app)}$target: \"$message\""
    }

    /** `Tap "Mark as read" on WhatsApp notification: Tania Cohen`. */
    fun action(actionTitle: String, app: String, title: String?): String =
        "Tap \"${label(actionTitle)}\" on ${label(app)} notification" + suffix(title)

    /** Used when the notification can't be looked up quickly: built from the raw arguments. */
    fun actionFallback(key: String?, action: String?, index: Int?): String {
        val what = action?.let { "\"${label(it)}\"" } ?: index?.let { "action #$it" } ?: "an action"
        return "Tap $what on notification ${key?.let { label(it) } ?: "(no key)"}"
    }

    /** `Dismiss Gmail notification: Invoice #42`. */
    fun dismiss(app: String, title: String?): String = "Dismiss ${label(app)} notification" + suffix(title)

    fun dismissFallback(key: String?): String = "Dismiss notification ${key?.let { label(it) } ?: "(no key)"}"

    /**
     * Extra text appended to a `notifications_list` result: how many matches were cut by the
     * limit, or a hint when `package` looks like an app name instead of a package. Null if none.
     */
    fun listFooter(query: NotificationQuery, page: FilteredNotifications): String? {
        val pkg = query.packageName
        return when {
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
    }

    private fun suffix(title: String?): String = title?.takeIf { it.isNotBlank() }?.let { ": ${label(it)}" } ?: ""

    private fun label(value: String): String = NotificationText.ellipsize(value, MAX_LABEL)
}
