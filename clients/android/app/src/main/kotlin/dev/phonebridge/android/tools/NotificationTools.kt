package dev.phonebridge.android.tools

import android.content.Context
import dev.phonebridge.android.tools.notifications.notificationActionTool
import dev.phonebridge.android.tools.notifications.notificationDismissTool
import dev.phonebridge.android.tools.notifications.notificationReplyTool
import dev.phonebridge.android.tools.notifications.notificationsListTool
import dev.phonebridge.core.tools.Tool

/**
 * Tools: notifications_list, notification_reply, notification_action, notification_dismiss
 * (docs/TOOLS.md "Notifications"). All need notification access (BridgeNotificationListener);
 * replies work while the phone is locked.
 */
fun notificationTools(context: Context): List<Tool> {
    val app = context.applicationContext
    return listOf(
        notificationsListTool(app),
        notificationReplyTool(app),
        notificationActionTool(app),
        notificationDismissTool(app),
    )
}
