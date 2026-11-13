package dev.phonebridge.fixture.mail

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Email(val id: String, val from: String, val subject: String, val body: String, val time: Long) {
    val snippet: String get() = body.replace('\n', ' ').take(100)

    fun toJson(): JSONObject =
        JSONObject().put("id", id).put("from", from).put("subject", subject).put("body", body).put("time", time)

    companion object {
        fun fromJson(o: JSONObject) =
            Email(o.getString("id"), o.optString("from"), o.optString("subject"), o.optString("body"), o.optLong("time"))
    }
}

/** "Re: <subject>", without stacking prefixes. */
fun replySubject(subject: String): String = if (subject.startsWith("Re:", ignoreCase = true)) subject else "Re: $subject"

/**
 * The inbox: a JSON array in files/inbox.json, newest first, seeded with a few emails on first use
 * (`adb shell pm clear dev.phonebridge.fixture.mail` resets it to the seed). Archived emails are removed.
 */
object MailStore {
    private const val FILE_NAME = "inbox.json"

    /** Called (on the main thread) whenever the inbox changes; set by the visible [InboxActivity]. */
    var onChanged: (() -> Unit)? = null

    @Synchronized
    fun all(context: Context): List<Email> = load(context)

    @Synchronized
    fun find(context: Context, id: String): Email? = load(context).firstOrNull { it.id == id }

    @Synchronized
    fun add(context: Context, from: String, subject: String, body: String): Email {
        val email = Email("m-" + UUID.randomUUID().toString().take(8), from, subject, body, System.currentTimeMillis())
        save(context, listOf(email) + load(context))
        onChanged?.invoke()
        return email
    }

    @Synchronized
    fun remove(context: Context, id: String): Email? {
        val emails = load(context)
        val hit = emails.firstOrNull { it.id == id } ?: return null
        save(context, emails.filter { it.id != id })
        onChanged?.invoke()
        return hit
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    private fun load(context: Context): List<Email> {
        val file = file(context)
        if (!file.exists()) return seed().also { save(context, it) }
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { Email.fromJson(array.getJSONObject(it)) }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    private fun save(context: Context, emails: List<Email>) {
        val array = JSONArray()
        emails.forEach { array.put(it.toJson()) }
        val file = file(context)
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(file)
    }

    private fun seed(): List<Email> {
        val now = System.currentTimeMillis()
        return listOf(
            Email(
                "seed-1", "dana@example.com", "Lunch on Thursday?",
                "Hi! Are you free for lunch on Thursday at 12:30? There is a new place near the office.\n\nDana",
                now - 3_600_000,
            ),
            Email(
                "seed-2", "tom@example.org", "Project update",
                "The beta build is ready for testing. Please send feedback by Friday.\n\nThanks,\nTom",
                now - 7_200_000,
            ),
            Email(
                "seed-3", "billing@example-shop.com", "Your receipt #10432",
                "Thanks for your order! Total: USD 42.00. Your items ship tomorrow.",
                now - 86_400_000,
            ),
        )
    }
}
