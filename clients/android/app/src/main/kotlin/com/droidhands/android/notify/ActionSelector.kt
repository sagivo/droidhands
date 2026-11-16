package com.droidhands.android.notify

import com.droidhands.core.tools.ToolException

/** Picks the action a tool call refers to (pure). Failures are agent-readable [ToolException]s. */
object ActionSelector {
    const val NO_REPLY_ACTION =
        "This notification has no reply action: open the app and reply with phone use (screen_observe, ui_type, ui_tap)."

    const val NO_ACTIONS =
        "This notification has no action buttons. Use notification_dismiss to clear it, or open the app with app_open."

    /** The inline-reply action: prefers `SEMANTIC_ACTION_REPLY`, else the first that can reply. */
    fun replyAction(actions: List<NotificationActionInfo>): NotificationActionInfo? =
        actions.firstOrNull { it.canReply && it.semanticReply } ?: actions.firstOrNull { it.canReply }

    /**
     * Resolves [title] (case-insensitive: exact match first, then a unique "contains" match) and/or
     * [index]. When both are given they must agree.
     */
    fun select(actions: List<NotificationActionInfo>, title: String?, index: Int?): NotificationActionInfo {
        val wanted = title?.trim()?.takeIf { it.isNotEmpty() }
        if (wanted == null && index == null) {
            throw ToolException("Pass 'action' (the button title) or 'index' (from notifications_list). ${available(actions)}")
        }
        if (actions.isEmpty()) throw ToolException(NO_ACTIONS)

        val byIndex = index?.let { i ->
            actions.firstOrNull { it.index == i } ?: throw ToolException("No action with index $i. ${available(actions)}")
        }
        val byTitle = wanted?.let { byTitle(actions, it) }
        if (byIndex != null && byTitle != null && byIndex.index != byTitle.index) {
            throw ToolException(
                "'action' \"$wanted\" and 'index' $index refer to different actions; pass only one. ${available(actions)}",
            )
        }
        return byIndex ?: byTitle!!
    }

    private fun byTitle(actions: List<NotificationActionInfo>, title: String): NotificationActionInfo {
        val wanted = NotificationActions.normalize(title)
        actions.firstOrNull { NotificationActions.normalize(it.title) == wanted }?.let { return it }
        val partial = actions.filter { NotificationActions.normalize(it.title).contains(wanted) }
        return when {
            partial.isEmpty() -> throw ToolException("No action titled \"$title\". ${available(actions)}")
            partial.map { NotificationActions.normalize(it.title) }.distinct().size == 1 -> partial.first()
            else -> throw ToolException(
                "\"$title\" matches several actions: ${partial.joinToString { describe(it) }}. Pass the exact title or the index.",
            )
        }
    }

    /** "Available actions: 0 "Reply" (reply), 1 "Mark as read"." for error messages. */
    fun available(actions: List<NotificationActionInfo>): String =
        if (actions.isEmpty()) "This notification has no actions." else "Available actions: ${actions.joinToString { describe(it) }}."

    private fun describe(action: NotificationActionInfo): String =
        "${action.index} \"${action.title}\"" + if (action.canReply) " (reply)" else ""
}
