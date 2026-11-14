package dev.phonebridge.android.tools.comms

import android.content.Context
import android.provider.CallLog.Calls
import dev.phonebridge.core.tools.ToolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Reads CallLog.Calls for call_log_list. Caller has checked READ_CALL_LOG. */
internal class CallLogReader(private val context: Context) {
    class Request(
        /** CallLog.Calls TYPE values, null = every type. */
        val types: List<Int>?,
        val filter: AddressFilter?,
        val sinceMillis: Long?,
        val limit: Int,
    )

    /** Newest first. Type and age are filtered in SQL, the number here (stored formats vary). */
    suspend fun list(request: Request): JsonArray = withContext(Dispatchers.IO) {
        val selection = ArrayList<String>()
        val args = ArrayList<String>()
        request.types?.let { types ->
            selection += "${Calls.TYPE} IN (${SqlText.placeholders(types.size)})"
            types.mapTo(args) { it.toString() }
        }
        request.sinceMillis?.let {
            selection += "${Calls.DATE} >= ?"
            args += it.toString()
        }
        val names = ContactNameCache(context)
        val calls = ArrayList<JsonObject>()
        // No "LIMIT" in the sort order: Android 11+ providers reject it; we stop reading instead.
        val cursor = withProviderAccess(CALL_LOG_ACCESS_DENIED) {
            context.contentResolver.query(
                Calls.CONTENT_URI, PROJECTION, selection.joinToString(" AND ").ifEmpty { null },
                args.toTypedArray().takeIf { it.isNotEmpty() }, "${Calls.DATE} DESC",
            )
        } ?: throw ToolException("The call log is not available on this device.")
        cursor.use { c ->
            var scanned = 0
            while (calls.size < request.limit && c.moveToNext()) {
                if (++scanned % CANCEL_CHECK_EVERY == 0) ensureActive()
                val stored = c.stringOrNull(COL_NUMBER)?.trim().orEmpty()
                if (request.filter != null && !request.filter.matches(stored)) continue
                val hidden = CallTypes.hiddenNumberName(c.intOr(COL_PRESENTATION, Calls.PRESENTATION_ALLOWED))
                val number = if (hidden != null && PhoneText.digitCount(stored) < 3) hidden else stored.ifEmpty { "unknown" }
                calls += buildJsonObject {
                    put("number", number)
                    (c.stringOrNull(COL_NAME)?.takeIf { it.isNotBlank() } ?: names.nameFor(stored))?.let { put("name", it) }
                    put("type", CallTypes.name(c.intOr(COL_TYPE, -1)))
                    put("time", IsoTime.format(c.longOr(COL_DATE, 0)))
                    put("duration_seconds", c.longOr(COL_DURATION, 0))
                }
            }
        }
        JsonArray(calls)
    }

    private companion object {
        val PROJECTION = arrayOf(Calls.NUMBER, Calls.CACHED_NAME, Calls.TYPE, Calls.DATE, Calls.DURATION, Calls.NUMBER_PRESENTATION)
        const val COL_NUMBER = 0
        const val COL_NAME = 1
        const val COL_TYPE = 2
        const val COL_DATE = 3
        const val COL_DURATION = 4
        const val COL_PRESENTATION = 5
        const val CANCEL_CHECK_EVERY = 256
        const val CALL_LOG_ACCESS_DENIED =
            "Android denied access to the call log. Ask the user to open the PhoneBridge app > Settings > Permissions & access and grant Phone & call log access."
    }
}
