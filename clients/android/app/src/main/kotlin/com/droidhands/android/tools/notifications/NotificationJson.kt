package com.droidhands.android.tools.notifications

import com.droidhands.android.notify.NotificationInfo
import com.droidhands.android.notify.NotificationText
import com.droidhands.android.tools.system.Redaction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZoneId

/**
 * Agent-facing JSON for notifications (docs/TOOLS.md `notifications_list`). Compact: absent
 * fields are omitted rather than null; `ongoing`, `codes_hidden` and `content_hidden_by_android`
 * appear only when true. Long texts are truncated here, with a note saying so. Pure; unit-tested.
 */
object NotificationJson {
    /** Sender shown for messages the phone's user sent. */
    const val ME = "me"

    /** Set when Android 15+ hid the notification's content from DroidHands ([com.droidhands.android.notify.HiddenContent]). */
    const val CONTENT_HIDDEN = "content_hidden_by_android"

    fun list(items: List<NotificationInfo>, zone: ZoneId = ZoneId.systemDefault()): JsonArray =
        buildJsonArray { items.forEach { add(notification(it, zone)) } }

    fun notification(info: NotificationInfo, zone: ZoneId = ZoneId.systemDefault()): JsonObject = buildJsonObject {
        put("key", info.key)
        put("package", info.packageName)
        put("app", info.appLabel)
        info.title?.let { put("title", NotificationText.truncate(it, NotificationText.MAX_TITLE)) }
        info.text?.let { put("text", NotificationText.truncate(it, NotificationText.MAX_TEXT)) }
        if (info.messages.isNotEmpty()) {
            put(
                "messages",
                buildJsonArray {
                    info.messages.forEach { m ->
                        add(
                            buildJsonObject {
                                (if (m.fromMe) ME else m.sender)?.let { put("sender", it) }
                                put("text", NotificationText.truncate(m.text, NotificationText.MAX_MESSAGE_TEXT))
                                if (m.time > 0) put("time", NotificationText.isoTime(m.time, zone))
                            },
                        )
                    }
                },
            )
        }
        put("time", NotificationText.isoTime(info.postTime, zone))
        put("conversation", info.conversation)
        info.category?.let { put("category", it) }
        if (info.ongoing) put("ongoing", true)
        if (info.codesHidden) put(Redaction.FLAG, true)
        if (info.contentHiddenByAndroid) put(CONTENT_HIDDEN, true)
        put(
            "actions",
            buildJsonArray {
                info.actions.forEach { a ->
                    add(
                        buildJsonObject {
                            put("index", a.index)
                            put("title", a.title)
                            put("can_reply", a.canReply)
                        },
                    )
                }
            },
        )
    }

    /** A notification title as returned by the act tools (truncated like in [notification]). */
    fun title(info: NotificationInfo): String? = info.title?.let { NotificationText.truncate(it, NotificationText.MAX_TITLE) }
}
