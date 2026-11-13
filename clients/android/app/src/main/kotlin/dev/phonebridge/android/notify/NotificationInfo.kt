package dev.phonebridge.android.notify

// Android-free snapshot of active notifications, as exposed to agents (docs/TOOLS.md
// "Notifications"). Built from StatusBarNotification by NotificationMapper; filtered, selected and
// serialized by pure code that is unit-tested on the JVM.

/** One active notification. Texts are already cleaned and truncated. */
data class NotificationInfo(
    /** `StatusBarNotification.key`: the handle agents pass to the notification_* tools. */
    val key: String,
    val packageName: String,
    /** User-visible app label (falls back to the package name). */
    val appLabel: String,
    val title: String? = null,
    val text: String? = null,
    /** Most recent MessagingStyle messages, oldest first. */
    val messages: List<NotificationMessage> = emptyList(),
    /** True for MessagingStyle (chat) notifications. */
    val conversation: Boolean = false,
    /** `StatusBarNotification.postTime`, epoch millis. */
    val postTime: Long,
    val category: String? = null,
    /** Ongoing event or foreground-service notification (music, navigation, calls, downloads). */
    val ongoing: Boolean = false,
    /** Whether the user (and so the listener) may dismiss it. */
    val clearable: Boolean = true,
    /** `StatusBarNotification.groupKey`; unique per notification when it isn't grouped. */
    val groupKey: String = key,
    /** `FLAG_GROUP_SUMMARY`: the collapsed header of a bundle of notifications. */
    val isGroupSummary: Boolean = false,
    /** Combined standard + wearable actions with stable indices (see [NotificationActions.merge]). */
    val actions: List<NotificationActionInfo> = emptyList(),
)

/** A MessagingStyle message. */
data class NotificationMessage(
    /** Sender display name; null when unknown or when [fromMe]. */
    val sender: String?,
    val text: String,
    /** Epoch millis; 0 when unknown. */
    val time: Long,
    /** Sent by the phone's user (MessagingStyle "user" person). */
    val fromMe: Boolean = false,
)

/** One action button of a notification. */
data class NotificationActionInfo(
    /** Stable index within [NotificationInfo.actions]. */
    val index: Int,
    val title: String,
    /** Has a free-form RemoteInput whose PendingIntent can carry the typed text. */
    val canReply: Boolean,
    /** Has any RemoteInput, so tapping it without text makes no sense. */
    val requiresInput: Boolean = canReply,
    /** Declared `SEMANTIC_ACTION_REPLY`. */
    val semanticReply: Boolean = false,
    /** Came from the WearableExtender rather than the notification's own action buttons. */
    val fromWearable: Boolean = false,
)
