package dev.phonebridge.android.tools.notifications

import dev.phonebridge.android.notify.NotificationActionInfo
import dev.phonebridge.android.notify.NotificationInfo
import dev.phonebridge.android.notify.NotificationMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.ZoneOffset

class NotificationJsonTest {
    private fun encode(e: JsonElement) = Json.encodeToString(JsonElement.serializer(), e)

    @Test
    fun `full chat notification`() {
        val info = NotificationInfo(
            key = "0|com.whatsapp|1|abc|10123",
            packageName = "com.whatsapp",
            appLabel = "WhatsApp",
            title = "Tania Cohen",
            text = "See you at 8?",
            messages = listOf(
                NotificationMessage("Tania Cohen", "Dinner tonight?", 1_731_499_980_000L),
                NotificationMessage(null, "Sure", 1_731_500_000_000L, fromMe = true),
                NotificationMessage(null, "[image]", 0),
            ),
            conversation = true,
            postTime = 1_731_500_000_123L,
            category = "msg",
            actions = listOf(
                NotificationActionInfo(0, "Reply", canReply = true, semanticReply = true),
                NotificationActionInfo(1, "Mark as read", canReply = false),
            ),
        )
        val expected = """{"key":"0|com.whatsapp|1|abc|10123","package":"com.whatsapp","app":"WhatsApp",""" +
            """"title":"Tania Cohen","text":"See you at 8?","messages":[""" +
            """{"sender":"Tania Cohen","text":"Dinner tonight?","time":"2024-11-13T12:13:00Z"},""" +
            """{"sender":"me","text":"Sure","time":"2024-11-13T12:13:20Z"},""" +
            """{"text":"[image]"}],""" +
            """"time":"2024-11-13T12:13:20Z","conversation":true,"category":"msg",""" +
            """"actions":[{"index":0,"title":"Reply","can_reply":true},{"index":1,"title":"Mark as read","can_reply":false}]}"""
        assertEquals(expected, encode(NotificationJson.notification(info, ZoneOffset.UTC)))
    }

    @Test
    fun `minimal notification omits absent fields`() {
        val info = NotificationInfo(key = "k", packageName = "p", appLabel = "P", postTime = 0)
        val json = encode(NotificationJson.notification(info, ZoneOffset.UTC))
        assertEquals("""{"key":"k","package":"p","app":"P","time":"1970-01-01T00:00:00Z","conversation":false,"actions":[]}""", json)
        assertFalse(json.contains("null"))
    }

    @Test
    fun `ongoing flag appears only when true`() {
        val info = NotificationInfo(key = "k", packageName = "p", appLabel = "P", postTime = 0, ongoing = true)
        assertEquals(true, encode(NotificationJson.notification(info, ZoneOffset.UTC)).contains("\"ongoing\":true"))
    }

    @Test
    fun `list keeps order`() {
        val items = listOf("a", "b").map { NotificationInfo(key = it, packageName = "p", appLabel = "P", postTime = 0) }
        val json = encode(NotificationJson.list(items, ZoneOffset.UTC))
        assertEquals(true, json.startsWith("[{\"key\":\"a\""))
        assertEquals(true, json.indexOf("\"key\":\"a\"") < json.indexOf("\"key\":\"b\""))
    }
}
