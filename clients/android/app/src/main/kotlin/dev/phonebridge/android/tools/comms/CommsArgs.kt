package dev.phonebridge.android.tools.comms

import dev.phonebridge.android.tools.support.Recipient
import dev.phonebridge.core.tools.ToolException
import dev.phonebridge.core.tools.optDouble
import dev.phonebridge.core.tools.optInt
import dev.phonebridge.core.tools.optString
import kotlinx.serialization.json.JsonObject

/** Argument parsing shared by the contacts/SMS/call tools (pure). */
internal object CommsArgs {
    private const val MILLIS_PER_HOUR = 3_600_000.0

    /** Ten years: anything larger is treated as "no limit". */
    private const val MAX_SINCE_HOURS = 24.0 * 365 * 10

    /** `limit`, defaulting to [default] and clamped to 1..[max] (lenient: agents often overshoot). */
    fun limit(args: JsonObject, default: Int, max: Int): Int = (args.optInt("limit") ?: default).coerceIn(1, max)

    /** Lowercased value of [key], one of [allowed], or [default] when absent. */
    fun choice(args: JsonObject, key: String, allowed: List<String>, default: String): String {
        val value = args.optString(key)?.trim()?.lowercase() ?: return default
        if (value !in allowed) throw ToolException("Argument '$key' must be one of ${allowed.joinToString(", ")}, got '$value'.")
        return value
    }

    /** Epoch-millis cutoff for `since_hours` (fractions allowed), or null when absent. */
    fun sinceMillis(args: JsonObject, now: Long = System.currentTimeMillis()): Long? {
        val hours = args.optDouble("since_hours") ?: return null
        if (hours.isNaN() || hours <= 0.0) throw ToolException("Argument 'since_hours' must be a positive number of hours.")
        if (hours >= MAX_SINCE_HOURS) return null
        return now - (hours * MILLIS_PER_HOUR).toLong()
    }

    /** Trimmed `address`/`contact` pair; at most one may be given. */
    fun addressOrContact(args: JsonObject): Pair<String?, String?> {
        val address = args.optString("address")?.trim()?.takeIf { it.isNotEmpty() }
        val contact = args.optString("contact")?.trim()?.takeIf { it.isNotEmpty() }
        if (address != null && contact != null) {
            throw ToolException("Pass either 'address' (phone number or sender) or 'contact' (name), not both.")
        }
        return address to contact
    }

    /** "Tania Cohen (+972541234567)", or just the number when there is no name. */
    fun label(recipient: Recipient): String =
        if (recipient.displayName == recipient.number) recipient.number else "${recipient.displayName} (${recipient.number})"
}
