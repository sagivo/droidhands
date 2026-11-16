package com.droidhands.core.util

/**
 * Finds tokens that could be one-time codes (pure). Whether one *is* a code depends on nearby keywords
 * ([OneTimeCodes]); this only decides what is code-shaped and drops what is clearly something else:
 * parts of phone numbers, dates, times, decimals, prices, amounts with units, URLs, e-mail addresses,
 * handles and order numbers (`#123456`, `No. 123456`).
 *
 * Code shapes: 4-10 digits (`482913`, also non-ASCII digits), two digit groups (`123 456`, `123-456`,
 * `1234 5678`), a letter prefix (`G-482913`), mixed letters and digits (`A7K9Q2`, `X7K-9QT`), and
 * 4-8 uppercase letters (`KRBTF`, weak: see [Candidate.weak]).
 */
internal object OtpCandidates {
    enum class Kind { NUMERIC, GROUPED, PREFIXED, ALNUM, LETTERS }

    /**
     * [start, end) of the token in the text. [value] is the secret part, normalized (ASCII digits,
     * uppercase letters, no separators) to find the same code elsewhere. [weak] tokens (uppercase
     * words, numbers that look like years or phone numbers) count only right after a keyword.
     */
    class Candidate(val start: Int, val end: Int, val kind: Kind, val value: String, val weak: Boolean) {
        override fun toString() = "Candidate($start..$end $kind $value${if (weak) " weak" else ""})"
    }

    private val PATTERN = Regex(
        """(?<![A-Za-z\p{Nd}_])(?:""" +
            """([A-Z]{1,4}-\p{Nd}{4,10})""" + // 1: G-482913
            """|(\p{Nd}{3,4}[ \-]\p{Nd}{3,4})""" + // 2: 123 456, 1234-5678
            """|([A-Za-z0-9]{3,4}-[A-Za-z0-9]{3,4})""" + // 3: X7K-9QT
            """|([A-Za-z\p{Nd}]{4,10})""" + // 4: 482913, A7K9Q2, KRBTF
            """)(?![A-Za-z\p{Nd}_])""",
    )

    /** Digits followed by a unit or ordinal suffix: `10min`, `24h`, `3rd`, `500mb`. */
    private val UNIT_SUFFIX = Regex("""^\p{Nd}+(?:st|nd|rd|th|am|pm|h|hr|hrs|min|mins|s|sec|secs|ms|m|km|kg|g|mb|gb|kb|k|x|d|px|mm|cm|ml|l)$""", RegexOption.IGNORE_CASE)

    /** Uppercase words that follow "code:"/"PIN is" without being codes. */
    private val LETTER_STOPWORDS = setOf(
        "CODE", "CODES", "STOP", "HELP", "INFO", "FREE", "VALID", "INVALID", "EXPIRED", "EXPIRES", "CORRECT", "WRONG",
        "REQUIRED", "NEEDED", "BLOCKED", "LOCKED", "ACTIVE", "READY", "SENT", "HERE", "BELOW", "ABOVE", "NULL", "NONE",
        "TRUE", "FALSE", "YOUR", "THIS", "THAT", "WILL", "NEVER", "ONLY", "SAFE", "SECRET", "PRIVATE", "CHANGED", "RESET",
        "PASSWORD", "VERIFY", "LOGIN", "SIGN", "SMS", "TEXT", "CALL", "REPLY", "OPEN", "LINK", "WITH", "FROM", "SHARE",
    )

    /** Words before a number that make it an amount or a reference rather than a code. */
    private val WORDS_BEFORE = setOf(
        "usd", "eur", "gbp", "ils", "nis", "inr", "rs", "rub", "cny", "rmb", "jpy", "brl", "mxn", "aed", "sar", "chf",
        "cad", "aud", "kr", "sek", "nok", "dkk", "pln", "zł", "czk", "huf", "руб", "грн", "ש״ח", "שח",
        "no", "nr", "nº", "num", "ref", "order", "invoice", "flight", "room", "gate", "seat", "unit", "suite", "apt", "box",
    )

    /** Words after a number that make it an amount, a duration or a count. */
    private val WORDS_AFTER = setOf(
        "usd", "eur", "gbp", "ils", "nis", "inr", "rs", "rub", "cny", "rmb", "jpy", "brl", "mxn", "aed", "sar", "chf", "cad",
        "aud", "pln", "zł", "dollars", "dollar", "euros", "euro", "pounds", "shekels", "shekel", "rupees", "rubles", "yuan",
        "руб", "рублей", "рубля", "грн", "ש״ח", "שח", "שקל", "שקלים", "درهم", "ريال", "جنيه", "دينار",
        "min", "mins", "minute", "minutes", "minuto", "minutos", "minuti", "minuten", "мин", "минут", "минуты", "минуту",
        "דקות", "דקה", "دقائق", "دقيقة", "hours", "hour", "hrs", "hr", "horas", "heures", "stunden", "ore", "часов", "часа",
        "שעות", "ساعات", "seconds", "secs", "sec", "segundos", "secondes", "sekunden", "secondi", "секунд", "שניות", "ثانية",
        "days", "day", "días", "dias", "jours", "tage", "giorni", "дней", "дня", "ימים", "أيام", "km", "kg", "mb", "gb",
        "points", "pts", "pontos", "puntos", "punkte", "баллов", "נקודות", "miles", "steps", "items", "pcs", "units",
    )

    /** CJK characters that, right after a number, make it a price, a date or a count (`1500元`, `2024年`). */
    private const val CJK_UNITS = "元円圆块塊角分秒次个個件号號位天年月日点點时時人"

    private const val URL_CHARS_BEFORE = "/\\@#_=&?%~№"

    /** Every code-shaped token in [text], minus the guarded ones. */
    fun find(text: String): List<Candidate> {
        val out = ArrayList<Candidate>()
        for (m in PATTERN.findAll(text)) {
            val start = m.range.first
            val end = m.range.last + 1
            val candidate = when {
                m.groups[1] != null -> prefixed(m.value, start, end)
                m.groups[2] != null -> Candidate(start, end, Kind.GROUPED, normalize(m.value), weak = false)
                m.groups[3] != null -> dashed(m.value, start, end)
                else -> token(m.value, start, end)
            } ?: continue
            if (!isGuarded(text, start, end)) out += candidate
        }
        return out
    }

    /** Uppercase ASCII letters and ASCII digits (any Unicode digit mapped), separators dropped. */
    fun normalize(raw: CharSequence): String = buildString(raw.length) {
        for (ch in raw) {
            when {
                Character.isDigit(ch) -> append('0' + Character.digit(ch, 10))
                ch in 'a'..'z' -> append(ch.uppercaseChar())
                ch in 'A'..'Z' -> append(ch)
            }
        }
    }

    private fun prefixed(raw: String, start: Int, end: Int): Candidate =
        Candidate(start, end, Kind.PREFIXED, normalize(raw.substringAfter('-')), weak = false)

    private fun dashed(raw: String, start: Int, end: Int): Candidate? {
        if (raw.none { it in '0'..'9' } || raw.none { it.isLetter() }) return null
        return Candidate(start, end, Kind.ALNUM, normalize(raw), weak = false)
    }

    private fun token(raw: String, start: Int, end: Int): Candidate? {
        val hasDigit = raw.any(Character::isDigit)
        val hasLetter = raw.any { it in 'A'..'Z' || it in 'a'..'z' }
        val value = normalize(raw)
        return when {
            hasDigit && !hasLetter -> {
                val year = value.length == 4 && value.toInt() in 1900..2099
                Candidate(start, end, Kind.NUMERIC, value, weak = year || value.length >= 9)
            }
            hasDigit -> if (UNIT_SUFFIX.matches(raw)) null else Candidate(start, end, Kind.ALNUM, value, weak = false)
            raw.length <= 8 && raw.all { it in 'A'..'Z' } && raw !in LETTER_STOPWORDS -> Candidate(start, end, Kind.LETTERS, value, weak = true)
            else -> null
        }
    }

    /** True if the token at [start, end) is part of a number, URL, price, amount or reference. */
    private fun isGuarded(text: String, start: Int, end: Int): Boolean {
        val prev = text.getOrNull(start - 1)
        val prev2 = text.getOrNull(start - 2)
        val next = text.getOrNull(end)
        val next2 = text.getOrNull(end + 1)
        val next3 = text.getOrNull(end + 2)
        return when {
            prev == '+' -> true // +972…
            prev != null && prev in URL_CHARS_BEFORE -> true // URL path/query, e-mail, handle, #order
            prev == '.' && prev2 != null && prev2.isLetterOrDigit() -> true // domain or decimal
            prev != null && prev in "-./:, " && prev2 != null && Character.isDigit(prev2) -> true // 054-1234567, 12.05.2024, +1 415 555
            next == '@' -> true // e-mail local part
            next != null && next in "-./:," && next2 != null && Character.isDigit(next2) -> true // dates, times, decimals
            next == '.' && next2 != null && next2 in 'a'..'z' -> true // 482913.com
            next == '/' && next2 != null && next2.isLetterOrDigit() -> true // paths
            next == ' ' && next2 != null && Character.isDigit(next2) && next3 != null && Character.isDigit(next3) -> true // 054 123 4567
            next != null && next in CJK_UNITS -> true
            else -> amountBefore(text, start) || amountAfter(text, end)
        }
    }

    private fun amountBefore(text: String, start: Int): Boolean {
        var i = start - 1
        var spaces = 0
        while (i >= 0 && text[i] == ' ' && spaces < 2) {
            i--
            spaces++
        }
        if (i < 0) return false
        if (Character.getType(text[i]) == Character.CURRENCY_SYMBOL.toInt() || text[i] == '№' || text[i] == '#') return true
        var wordEnd = i + 1
        if (text[i] == '.') {
            wordEnd = i
            i--
        }
        val wordStart = generateSequence(i) { it - 1 }.takeWhile { it >= 0 && (text[it].isLetter() || text[it] == '״') }.lastOrNull() ?: return false
        if (wordEnd - wordStart > 10) return false
        return text.substring(wordStart, wordEnd).lowercase() in WORDS_BEFORE
    }

    private fun amountAfter(text: String, end: Int): Boolean {
        var i = end
        var spaces = 0
        while (i < text.length && text[i] == ' ' && spaces < 2) {
            i++
            spaces++
        }
        if (i >= text.length) return false
        if (Character.getType(text[i]) == Character.CURRENCY_SYMBOL.toInt() || text[i] == '%') return true
        var j = i
        while (j < text.length && j - i < 12 && (text[j].isLetter() || text[j] == '״')) j++
        if (j == i || (j < text.length && text[j].isLetter())) return false
        return text.substring(i, j).lowercase() in WORDS_AFTER
    }
}
