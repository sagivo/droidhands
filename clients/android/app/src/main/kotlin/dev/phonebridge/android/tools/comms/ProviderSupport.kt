package dev.phonebridge.android.tools.comms

import android.database.Cursor
import dev.phonebridge.core.tools.ToolException

/** Stay well below SQLite's bound-parameter limit (999 on older devices) in `IN (...)` clauses. */
private const val MAX_IN_ARGS = 500

/** Calls [block] with "?,?,…" placeholders and string args for each chunk of [ids]. */
internal inline fun forEachIdChunk(ids: Collection<Long>, block: (placeholders: String, args: Array<String>) -> Unit) {
    for (chunk in ids.chunked(MAX_IN_ARGS)) {
        block(SqlText.placeholders(chunk.size), Array(chunk.size) { chunk[it].toString() })
    }
}

internal fun Cursor.stringOrNull(index: Int): String? = if (index < 0 || isNull(index)) null else getString(index)

internal fun Cursor.longOr(index: Int, default: Long): Long = if (index < 0 || isNull(index)) default else getLong(index)

internal fun Cursor.intOr(index: Int, default: Int): Int = if (index < 0 || isNull(index)) default else getInt(index)

internal const val CONTACTS_ACCESS_DENIED =
    "Android denied access to the contacts. Ask the user to open the PhoneBridge app > Setup and grant the Contacts permission."

/** Turns a provider SecurityException (permission revoked mid-call) into an agent-facing error. */
internal inline fun <T> withProviderAccess(deniedMessage: String, block: () -> T): T =
    try {
        block()
    } catch (e: SecurityException) {
        throw ToolException(deniedMessage)
    }
