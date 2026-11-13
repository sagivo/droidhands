package dev.phonebridge.android.tools.notifications

import dev.phonebridge.android.notify.NotificationInfo
import dev.phonebridge.android.notify.NotificationText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZoneId

/**
 * Agent-facing JSON for notifications (docs/TOOLS.md `notifications_list`). Compact: absent
 * fields are omitted rather than null, `ongoing` appears only when true. Pure; unit-tested.
 */
object NotificationJson {
    /** Sender shown for messages the phone's user sent. */
    const val ME = "me"

    fun list(items: List<NotificationInfo>, zone: ZoneId = ZoneId.systemDefault()): JsonArray =
        buildJsonArray { items.forEach { add(notification(it, zone)) } }

    fun notification(info: NotificationInfo, zone: ZoneId = ZoneId.systemDefault()): JsonObject = buildJsonObject {
        put("key", info.key)
        put("package", info.packageName)
        put("app", info.appLabel)
        info.title?.let { put("title", it) }
        info.text?.let { put("text", it) }
        if (info.messages.isNotEmpty()) {
            put(
                "messages",
                buildJsonArray {
                    info.messages.forEach { m ->
                        add(
                            buildJsonObject {
                                (if (m.fromMe) ME else m.sender)?.let { put("sender", it) }
                                put("text", m.text)
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
}
