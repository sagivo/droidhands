package dev.phonebridge.android.tools.comms

import android.Manifest
import android.content.Context
import dev.phonebridge.android.tools.support.Perms

/**
 * Contact names for SMS/call-log addresses via PhoneLookup, memoized per number. Disabled (always
 * null) without READ_CONTACTS. Not thread-safe: create one per tool call. Blocking.
 */
internal class ContactNameCache(context: Context) {
    private val repository = if (Perms.has(context, Manifest.permission.READ_CONTACTS)) ContactsRepository(context) else null
    private val names = HashMap<String, String?>()

    fun nameFor(address: String?): String? {
        val repo = repository ?: return null
        if (address.isNullOrBlank() || PhoneText.digitCount(address) < MIN_DIGITS) return null
        val key = PhoneText.comparableDigits(address)
        if (key in names) return names[key]
        val name = try {
            repo.lookupName(address)
        } catch (e: RuntimeException) {
            // Names are optional decoration; never fail the listing for them.
            null
        }
        names[key] = name
        return name
    }

    private companion object {
        const val MIN_DIGITS = 3
    }
}
