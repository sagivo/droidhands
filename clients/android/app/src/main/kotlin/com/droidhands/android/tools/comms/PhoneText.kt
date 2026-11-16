package com.droidhands.android.tools.comms

import java.util.Locale
import kotlin.math.min

/**
 * Pure phone-number text helpers (no Android APIs; E.164 formatting with the platform's
 * libphonenumber lives in [E164Formatter]).
 */
internal object PhoneText {
    /** Loose comparisons need at least this many trailing digits, like PhoneNumberUtils.compare. */
    const val MIN_MATCH_DIGITS = 7

    /**
     * Fewest digits of a number to call or message. Shorter strings are not phone numbers, and GSM
     * treats a dialed 1-2 digit string as a USSD request (3GPP TS 22.030 §6.5.3.2).
     */
    const val MIN_DIAL_DIGITS = 3

    /** `*` and `#` (also full-width): GSM supplementary-service / USSD syntax. */
    private const val SERVICE_CODE_CHARS = "*#＊＃"

    /** Besides letters and control characters: service-code syntax and pause/wait (post-dial DTMF) markers. */
    private const val NOT_DIALABLE = "$SERVICE_CODE_CHARS,;"

    private const val E164_MIN_DIGITS = 7
    private const val E164_MAX_DIGITS = 15

    /**
     * Strips formatting from a phone number: spaces (including no-break and bidi marks), dashes,
     * dots, slashes and brackets; converts non-ASCII digits (Arabic-Indic, full-width…) to ASCII;
     * drops a `tel:` prefix and anything after a pause/wait/extension marker (`,` `;` `x` `ext`).
     * Keeps a leading `+` and `*`/`#` (fine for matching; call and message targets must pass
     * [cleanDialable] instead).
     *
     * Returns null if the text contains anything else (letters, a `+` in the middle) or no digit.
     */
    fun clean(raw: String): String? {
        var s = raw.trim()
        if (s.startsWith("tel:", ignoreCase = true)) s = s.substring(4)
        val out = StringBuilder(s.length)
        var i = 0
        loop@ while (i < s.length) {
            val start = i
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            when {
                Character.isDigit(cp) -> out.append('0' + Character.digit(cp, 10).coerceAtLeast(0))
                cp == '+'.code || cp == '＋'.code -> if (out.isEmpty()) out.append('+') else return null
                cp == '*'.code || cp == '#'.code -> out.append(cp.toChar())
                cp == ','.code || cp == ';'.code -> break@loop
                (cp == 'x'.code || cp == 'X'.code || s.startsWith("ext", start, ignoreCase = true)) && out.isNotEmpty() -> break@loop
                isSeparator(cp) -> Unit
                else -> return null
            }
        }
        return out.toString().takeIf { text -> text.any { it.isDigit() } }
    }

    /**
     * [raw] [clean]ed if an agent may call or message it (`to`, or a number passed as `contact`), else
     * null. Only plain phone numbers pass: digits with an optional leading `+`, formatted with spaces,
     * dashes, dots, slashes or brackets (invisible bidi marks are ignored). Refused:
     * * `*` and `#`: dialed by an app holding CALL_PHONE, a GSM MMI/USSD code runs without the dialer,
     *   e.g. `**21*<number>#` forwards every incoming call to that number;
     * * letters (so also `tel:`, pause/wait and extension markers), `,`, `;` and control characters;
     * * fewer than [MIN_DIAL_DIGITS] digits.
     */
    fun cleanDialable(raw: String): String? {
        val s = raw.trim()
        if (s.any { it in NOT_DIALABLE || it.isLetter() || Character.isISOControl(it) }) return null
        return clean(s)?.takeIf { digitCount(it) >= MIN_DIAL_DIGITS }
    }

    /** Agent-facing error for a value of argument [arg] that is not [cleanDialable]. */
    fun notDialableMessage(raw: String, arg: String): String {
        if (raw.any { it in SERVICE_CODE_CHARS }) {
            return "'$arg' contains * or #: phone codes (USSD/MMI, e.g. call forwarding or balance checks) can't be " +
                "dialed through DroidHands. Pass a plain phone number, e.g. +14155550123."
        }
        val hint = if (arg == "to") " To use a contact name, pass it as 'contact' instead." else ""
        return "'$arg' must be a phone number, preferably international (e.g. +14155550123): digits with an optional " +
            "leading +, nothing else.$hint"
    }

    private fun isSeparator(cp: Int): Boolean {
        if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) return true
        val type = Character.getType(cp)
        // Cf: LRM/RLM and other invisible format marks that RTL keyboards insert; Pd: all dashes.
        if (type == Character.FORMAT.toInt() || type == Character.DASH_PUNCTUATION.toInt()) return true
        return cp == '.'.code || cp == '('.code || cp == ')'.code || cp == '/'.code || cp == '['.code || cp == ']'.code
    }

    /** ASCII digits of [s] in order (non-ASCII digits converted). */
    fun digits(s: String): String = buildString {
        for (c in s) if (Character.isDigit(c)) append('0' + Character.digit(c, 10).coerceAtLeast(0))
    }

    fun digitCount(s: String): Int = s.count { Character.isDigit(it) }

    /**
     * True if a search query is a phone number rather than a name: no letters, only number
     * characters after [clean], and at least 3 digits.
     */
    fun looksLikePhoneNumber(query: String): Boolean {
        if (query.any { it.isLetter() }) return false
        val cleaned = clean(query) ?: return false
        return digitCount(cleaned) >= 3
    }

    /** Rewrites the `00` international call prefix to `+` ("00972…" → "+972…"). Input should be [clean]ed. */
    fun internationalize(cleaned: String): String =
        if (cleaned.startsWith("00") && cleaned.length > 2) "+" + cleaned.substring(2) else cleaned

    /** [s] if it looks like an E.164 number (`+` and 7..15 digits), else null. */
    fun plausibleE164(s: String): String? {
        if (!s.startsWith('+')) return null
        val rest = s.substring(1)
        if (rest.length !in E164_MIN_DIGITS..E164_MAX_DIGITS || !rest.all { it in '0'..'9' } || rest[0] == '0') return null
        return s
    }

    /**
     * Digits suitable for loose comparison: the full international digits for `+`/`00` numbers,
     * otherwise the national digits without trunk zeros ("054-123 4567" → "541234567").
     */
    fun comparableDigits(raw: String): String {
        val cleaned = clean(raw) ?: return digits(raw)
        val number = internationalize(cleaned)
        val digits = digits(number)
        return if (number.startsWith('+')) digits else digits.trimStart('0')
    }

    /** Valid two-letter ISO 3166 country codes from [isoCodes], uppercase, de-duplicated, in order. */
    fun countryCandidates(vararg isoCodes: String?): List<String> = isoCodes
        .mapNotNull { code -> code?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } } }
        .distinct()

    /**
     * Loose "same phone number" check in the spirit of PhoneNumberUtils.compare: equal digits, or,
     * when both have at least [MIN_MATCH_DIGITS] digits, the shorter is the tail of the longer
     * ("+972 54-123-4567" = "054 1234567" = "541234567"; "+1 212 555 1234" = "(212) 555-1234").
     */
    fun numbersMatch(a: String, b: String): Boolean {
        val da = comparableDigits(a)
        val db = comparableDigits(b)
        if (da.isEmpty() || db.isEmpty()) return false
        if (da == db) return true
        if (min(da.length, db.length) < MIN_MATCH_DIGITS) return false
        return if (da.length >= db.length) da.endsWith(db) else db.endsWith(da)
    }
}
