package dev.phonebridge.android.notify

import android.app.Notification
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person

/**
 * A live notification together with its agent-facing [info] and the platform action behind each
 * action index. Always built from the current `StatusBarNotification`, so the `PendingIntent`s are
 * the ones the app last posted.
 */
class MappedNotification internal constructor(
    val sbn: StatusBarNotification,
    val info: NotificationInfo,
    private val targets: List<IndexedAction<Notification.Action>>,
) {
    /** The platform action for an index from [NotificationInfo.actions], or null. */
    fun action(index: Int): Notification.Action? = targets.firstOrNull { it.info.index == index }?.payload
}

/** Converts `StatusBarNotification`s into [MappedNotification]s. Call off the main thread. */
object NotificationMapper {
    private const val TAG = "PBNotifications"

    /**
     * Never throws: if an app's extras can't be read (e.g. a custom Parcelable we can't unparcel),
     * the result carries only the key, package, app and time.
     */
    fun map(context: Context, sbn: StatusBarNotification): MappedNotification {
        val app = AppLabels.of(context, sbn.packageName)
        return try {
            mapFull(sbn, app)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not read a notification from ${sbn.packageName}: ${e.javaClass.simpleName}")
            MappedNotification(sbn, baseInfo(sbn, app), emptyList())
        }
    }

    private fun baseInfo(sbn: StatusBarNotification, app: String): NotificationInfo {
        val n = sbn.notification
        return NotificationInfo(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = app,
            postTime = sbn.postTime,
            category = n?.category,
            ongoing = sbn.isOngoing || (n != null && (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0),
            clearable = sbn.isClearable,
            groupKey = sbn.groupKey ?: sbn.key,
            isGroupSummary = n != null && (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
        )
    }

    private fun mapFull(sbn: StatusBarNotification, app: String): MappedNotification {
        val n = sbn.notification
        val extras: Bundle = n.extras ?: Bundle.EMPTY
        val style = try {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        } catch (e: RuntimeException) {
            null
        }

        val title = NotificationText.title(
            conversationTitle = NotificationText.clean(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE))
                ?: NotificationText.clean(style?.conversationTitle),
            title = NotificationText.clean(extras.getCharSequence(Notification.EXTRA_TITLE)),
            bigTitle = NotificationText.clean(extras.getCharSequence(Notification.EXTRA_TITLE_BIG)),
        )
        val text = NotificationText.body(
            bigText = NotificationText.clean(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)),
            text = NotificationText.clean(extras.getCharSequence(Notification.EXTRA_TEXT)),
            lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.mapNotNull { NotificationText.clean(it) }.orEmpty(),
        )
        val targets = mapActions(n)
        val messages = style?.let(::mapMessages).orEmpty()

        // Texts stay whole here: redaction and query matching need them; NotificationJson truncates.
        val info = baseInfo(sbn, app).copy(
            title = title,
            text = text,
            messages = messages,
            conversation = style != null || extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false),
            actions = targets.map { it.info },
            contentHiddenByAndroid = HiddenContent.isHidden(text, messages.map { it.text }, SystemPlaceholders.current()),
        )
        return MappedNotification(sbn, info, targets)
    }

    private fun mapMessages(style: NotificationCompat.MessagingStyle): List<NotificationMessage> {
        val user = style.user.toRef()
        return style.messages.mapNotNull { m ->
            val body = NotificationText.clean(m.text)
                ?: m.dataMimeType?.let(NotificationText::attachmentPlaceholder)
                ?: return@mapNotNull null
            val sender = m.person
            val fromMe = NotificationText.isFromUser(sender?.toRef(), user)
            NotificationMessage(
                sender = if (fromMe) null else NotificationText.clean(sender?.name),
                text = body,
                time = m.timestamp,
                fromMe = fromMe,
            )
        }.takeLast(NotificationText.MAX_MESSAGES)
    }

    private fun Person.toRef() = PersonRef(key = key, name = NotificationText.clean(name))

    /** Standard buttons first, then WearableExtender actions (de-duplicated by [NotificationActions.merge]). */
    private fun mapActions(n: Notification): List<IndexedAction<Notification.Action>> {
        val standard = n.actions.orEmpty().mapNotNull(::candidate)
        val wearable = try {
            Notification.WearableExtender(n).actions.orEmpty().mapNotNull(::candidate)
        } catch (e: RuntimeException) {
            emptyList()
        }
        return NotificationActions.merge(standard, wearable)
    }

    private fun candidate(action: Notification.Action?): ActionCandidate<Notification.Action>? {
        val pendingIntent = action?.actionIntent ?: return null // nothing to trigger
        val inputs = action.remoteInputs.orEmpty()
        // Since Android 12 the system silently drops fill-in extras for immutable PendingIntents,
        // so such an action can't deliver typed text even if it declares a RemoteInput.
        val acceptsFillIn = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !pendingIntent.isImmutable
        return ActionCandidate(
            title = NotificationText.clean(action.title)?.let { NotificationText.ellipsize(it, NotificationText.MAX_ACTION_TITLE) } ?: "",
            canReply = acceptsFillIn && inputs.any { it.allowFreeFormInput },
            requiresInput = inputs.isNotEmpty(),
            semanticReply = action.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY,
            identity = pendingIntent,
            payload = action,
        )
    }
}
