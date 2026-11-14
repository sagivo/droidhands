package dev.phonebridge.android.tools.comms

import android.content.Context
import android.provider.Telephony.Sms
import dev.phonebridge.android.tools.system.Redaction
import dev.phonebridge.android.tools.system.Redactor
import dev.phonebridge.core.tools.ToolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Reads Telephony.Sms for sms_list. Caller has checked READ_SMS. */
internal class SmsReader(private val context: Context) {
    class Request(
        /** Telephony.Sms TYPE values, null = every box. */
        val types: List<Int>?,
        val filter: AddressFilter?,
        /** Body must contain this (SQL LIKE, case-insensitive for ASCII). */
        val text: String?,
        val sinceMillis: Long?,
        val limit: Int,
        /** Masks one-time codes in bodies (docs/SECURITY.md "Hide one-time codes"); null = show bodies as stored. */
        val redactor: Redactor? = null,
    )

    /**
     * Newest first. Box, age and text are filtered in SQL; the address filter runs here because
     * stored addresses come in arbitrary formats ("+972 54-…", "054…"). With a [Request.redactor],
     * bodies are masked (flagged `codes_hidden`) and the text filter must also match the masked
     * body, so it can't be used to guess a hidden code.
     */
    suspend fun list(request: Request): JsonArray = withContext(Dispatchers.IO) {
        val selection = ArrayList<String>()
        val args = ArrayList<String>()
        request.types?.let { types ->
            selection += "${Sms.TYPE} IN (${SqlText.placeholders(types.size)})"
            types.mapTo(args) { it.toString() }
        }
        request.sinceMillis?.let {
            selection += "${Sms.DATE} >= ?"
            args += it.toString()
        }
        request.text?.let {
            selection += "${Sms.BODY} LIKE ? ESCAPE '${SqlText.LIKE_ESCAPE}'"
            args += SqlText.containsPattern(it)
        }
        val names = ContactNameCache(context)
        val messages = ArrayList<JsonObject>()
        val cursor = withProviderAccess(SMS_ACCESS_DENIED) {
            context.contentResolver.query(
                Sms.CONTENT_URI, PROJECTION, selection.joinToString(" AND ").ifEmpty { null },
                args.toTypedArray().takeIf { it.isNotEmpty() }, "${Sms.DATE} DESC",
            )
        } ?: throw ToolException("The SMS store is not available on this device.")
        cursor.use { c ->
            var scanned = 0
            while (messages.size < request.limit && c.moveToNext()) {
                if (++scanned % CANCEL_CHECK_EVERY == 0) ensureActive()
                val address = c.stringOrNull(COL_ADDRESS)
                if (request.filter != null && !request.filter.matches(address)) continue
                val body = Redaction.apply(c.stringOrNull(COL_BODY).orEmpty(), request.redactor)
                if (body.changed && !Redaction.visibleMatch(body.text, request.text)) continue
                messages += buildJsonObject {
                    put("id", c.getLong(COL_ID))
                    put("thread_id", c.longOr(COL_THREAD, -1))
                    put("address", address.orEmpty())
                    names.nameFor(address)?.let { put("contact_name", it) }
                    put("body", body.text)
                    if (body.changed) put(Redaction.FLAG, true)
                    put("time", IsoTime.format(c.longOr(COL_DATE, 0)))
                    put("box", SmsBoxes.name(c.intOr(COL_TYPE, -1)))
                    put("read", c.intOr(COL_READ, 0) != 0)
                }
            }
        }
        JsonArray(messages)
    }

    private companion object {
        val PROJECTION = arrayOf(Sms._ID, Sms.THREAD_ID, Sms.ADDRESS, Sms.BODY, Sms.DATE, Sms.TYPE, Sms.READ)
        const val COL_ID = 0
        const val COL_THREAD = 1
        const val COL_ADDRESS = 2
        const val COL_BODY = 3
        const val COL_DATE = 4
        const val COL_TYPE = 5
        const val COL_READ = 6
        const val CANCEL_CHECK_EVERY = 256
        const val SMS_ACCESS_DENIED =
            "Android denied access to SMS. Ask the user to open the PhoneBridge app > Setup and grant the SMS permission."
    }
}
