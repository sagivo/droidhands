package dev.phonebridge.android.tools.comms

import android.content.Context
import android.os.SystemClock
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * E.164 normalization with the platform's libphonenumber (PhoneNumberUtils). National numbers
 * are parsed with the default country: the network country, else the SIM country, else the locale
 * country; when a number is not valid for the first, the others are tried (useful when roaming).
 */
internal object E164Formatter {
    /** Any region works for numbers that already start with '+'. */
    private const val ANY_REGION = "US"

    fun toE164(context: Context, raw: String): String? {
        val cleaned = PhoneText.clean(raw) ?: return null
        val number = PhoneText.internationalize(cleaned)
        // USSD/star codes and short numbers have no E.164 form.
        if ('*' in number || '#' in number || PhoneText.digitCount(number) < 3) return null
        val countries = DefaultCountries.get(context)
        if (number.startsWith('+')) {
            return format(number, countries.firstOrNull() ?: ANY_REGION) ?: PhoneText.plausibleE164(number)
        }
        for (country in countries) format(number, country)?.let { return it }
        return null
    }

    private fun format(number: String, country: String): String? {
        val formatted = try {
            PhoneNumberUtils.formatNumberToE164(number, country)
        } catch (e: RuntimeException) {
            null
        }
        return formatted?.let(PhoneText::plausibleE164)
    }
}

/** Default countries for number parsing, cached briefly (TelephonyManager calls are IPC). */
internal object DefaultCountries {
    private const val TTL_MS = 60_000L

    private class Entry(val atElapsed: Long, val countries: List<String>)

    @Volatile
    private var cached: Entry? = null

    fun get(context: Context): List<String> {
        val now = SystemClock.elapsedRealtime()
        cached?.let { if (now - it.atElapsed < TTL_MS) return it.countries }
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val countries = PhoneText.countryCandidates(
            safe { telephony?.networkCountryIso },
            safe { telephony?.simCountryIso },
            Locale.getDefault().country,
        )
        cached = Entry(now, countries)
        return countries
    }

    private inline fun safe(block: () -> String?): String? = try {
        block()
    } catch (e: RuntimeException) {
        // Some devices without telephony throw instead of returning "".
        null
    }
}
