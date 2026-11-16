package com.droidhands.android.tools.comms

import java.text.Normalizer

/** How well a Latin spelling lines up with an abjad (Hebrew/Arabic) word. See [Transliteration.align]. */
internal enum class AbjadFit {
    /** The Latin spelling and the abjad word match completely. */
    FULL,

    /** The Latin spelling matches the beginning of the abjad word ("tan" ~ "טניה"). */
    LATIN_PREFIX,

    /** The abjad word matches the beginning of the Latin spelling ("טנ" ~ "tania"). */
    SLOTS_PREFIX,
    NONE,
}

/**
 * One letter of an abjad word. A consonant slot matches one of [options] (already in the
 * [Phonetic] spelling, e.g. `ח` → `h`/`k`); a vowel *carrier* (`א`, `ע`, `ה`, `ا`, `ع`, `ة`…) matches
 * any run of vowels, including none, and also `h` when [allowH] is set.
 */
internal class AbjadSlot(val options: List<String>, val allowH: Boolean = false) {
    val carrier: Boolean get() = options.isEmpty()

    fun acceptsInCarrier(c: Char): Boolean = c in VOWELS || (allowH && c == 'h')

    private companion object {
        const val VOWELS = "aeiou"
    }
}

/**
 * Script folding for contact-name matching (pure Kotlin, no Android APIs).
 *
 * * [fold] canonicalizes raw text: NFKD (NFD plus compatibility folding of ligatures, full-width
 *   and presentation forms), combining marks removed (accents, Hebrew niqqud, Arabic harakat),
 *   lowercase, apostrophe-like characters unified to `'`, anything else that is not a letter or
 *   digit turned into a single space.
 * * [toLatin] spells one folded token in Latin letters with small built-in tables for Hebrew,
 *   Arabic (plus common Persian/Urdu letters), Cyrillic and Greek. Other scripts pass through.
 * * [abjadSlots] + [align] compare Hebrew/Arabic words, which usually omit short vowels, with
 *   Latin spellings that write them: "טניה" (t-n-y-h) lines up with "tania".
 */
internal object Transliteration {
    private const val TATWEEL = 0x0640
    private val APOSTROPHES = setOf('\'', '’', '‘', 'ʼ', 'ʹ', '`', '׳', '´')
    private val SPACES = Regex(" {2,}")
    private const val VOWELS = "aeiou"

    fun fold(raw: String): String {
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFKD)
        val out = StringBuilder(decomposed.length)
        var i = 0
        while (i < decomposed.length) {
            val cp = decomposed.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            when {
                type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt() -> Unit
                cp == TATWEEL -> Unit
                cp < 0x10000 && cp.toChar() in APOSTROPHES -> out.append('\'')
                Character.isDigit(cp) -> out.append('0' + Character.digit(cp, 10).coerceAtLeast(0))
                Character.isLetter(cp) -> {
                    val lower = Character.toLowerCase(cp)
                    // Final sigma is just a positional form of sigma.
                    out.appendCodePoint(if (lower == 'ς'.code) 'σ'.code else lower)
                }
                else -> out.append(' ')
            }
        }
        return out.toString().trim().replace(SPACES, " ")
    }

    fun isAbjad(c: Char): Boolean =
        c in '֐'..'׿' || c in '؀'..'ۿ' || c in 'ݐ'..'ݿ' ||
            c in 'ࢠ'..'ࣿ' || c in 'יִ'..'﷿' || c in 'ﹰ'..'﻿'

    /** Best-guess Latin spelling of a [fold]ed token (apostrophes dropped, except as Hebrew geresh). */
    fun toLatin(token: String): String {
        if (token.any(::isAbjad)) return abjadGuess(parseAbjad(token))
        val out = StringBuilder(token.length + 4)
        var i = 0
        while (i < token.length) {
            val c = token[i]
            if (c in 'Ͱ'..'Ͽ') {
                val digraph = if (i + 1 < token.length) greekDigraph(token.substring(i, i + 2), atStart = i == 0) else null
                if (digraph != null) {
                    out.append(digraph)
                    i += 2
                    continue
                }
                out.append(GREEK[c] ?: c.toString())
            } else if (c != '\'') {
                out.append(CYRILLIC[c] ?: LATIN_EXTRA[c] ?: c.toString())
            }
            i++
        }
        return out.toString()
    }

    /** Slots for a Hebrew/Arabic token, or null if the token has no abjad letters. */
    fun abjadSlots(token: String): List<AbjadSlot>? {
        if (!token.any(::isAbjad)) return null
        return parseAbjad(token).map { AbjadSlot(it.letter.options, it.letter.allowH) }
    }

    /**
     * Aligns a [Phonetic] Latin spelling with abjad [slots]. Between two slots any run of vowels may
     * appear in the Latin spelling (the abjad word does not write them); before the first and after
     * the last slot only what a vowel carrier allows, because those scripts write initial and final
     * vowels with a letter.
     */
    fun align(latin: String, slots: List<AbjadSlot>): AbjadFit {
        val n = latin.length
        val m = slots.size
        if (n == 0 || m == 0) return AbjadFit.NONE
        // reach[i][j]: the first i Latin chars can be produced by the first j slots.
        val reach = Array(n + 1) { BooleanArray(m + 1) }
        reach[0][0] = true
        for (i in 0..n) {
            for (j in 0..m) {
                if (!reach[i][j]) continue
                if (j in 1 until m && i < n && latin[i] in VOWELS) reach[i + 1][j] = true
                if (j == m) continue
                val slot = slots[j]
                if (slot.carrier) {
                    var k = i
                    reach[k][j + 1] = true
                    while (k < n && slot.acceptsInCarrier(latin[k])) {
                        k++
                        reach[k][j + 1] = true
                    }
                } else {
                    for (option in slot.options) if (latin.startsWith(option, i)) reach[i + option.length][j + 1] = true
                }
            }
        }
        return when {
            reach[n][m] -> AbjadFit.FULL
            (1 until m).any { reach[n][it] } -> AbjadFit.LATIN_PREFIX
            (1 until n).any { reach[it][m] } -> AbjadFit.SLOTS_PREFIX
            else -> AbjadFit.NONE
        }
    }

    // --- Abjad (Hebrew / Arabic) ------------------------------------------------------------------

    /**
     * One abjad letter. [options] are [Phonetic] spellings (empty = vowel carrier); [guess],
     * [initial] and [final] are natural Latin spellings used for the best-guess transliteration.
     */
    private class Letter(
        val options: List<String>,
        val guess: String,
        val initial: String = guess,
        val final: String = guess,
        val allowH: Boolean = false,
        /** Variant when followed by a geresh/apostrophe, e.g. ג׳ = "j". */
        val geresh: Letter? = null,
    )

    private class AbjadUnit(val key: Char, val letter: Letter)

    private val CARRIER = emptyList<String>()

    private fun consonant(guess: String, vararg options: String, initial: String = guess, geresh: Letter? = null) =
        Letter(options.toList(), guess, initial = initial, geresh = geresh)

    private fun carrier(guess: String, initial: String = guess, final: String = guess, allowH: Boolean = false) =
        Letter(CARRIER, guess, initial, final, allowH)

    private val HEBREW_ALEF = carrier("a")
    private val HEBREW_AYIN = carrier("a")
    private val HEBREW_HE = carrier("h", final = "a", allowH = true)
    private val HEBREW_VAV = Letter(listOf("v", "o", "u"), "o", initial = "v")
    private val HEBREW_YOD = Letter(listOf("i", "e", "j"), "i", initial = "y")
    private val HEBREW_KAF = consonant("ch", "k", "h", initial = "k")
    private val HEBREW_PE = consonant("f", "p", "f", initial = "p")
    private val HEBREW_TSADI = consonant("tz", "z", "s", geresh = consonant("ch", "h", "sh"))
    private val DOUBLE_VAV = Letter(listOf("v", "u", "o"), "v")
    private val DOUBLE_YOD = Letter(listOf("i", "e", "j"), "y")

    private val ARABIC_WAW = Letter(listOf("v", "u", "o"), "ou", initial = "w")
    private val ARABIC_YEH = Letter(listOf("i", "e", "j"), "i", initial = "y")

    private val ABJAD: Map<Char, Letter> = buildMap {
        // Hebrew
        put('א', HEBREW_ALEF)
        put('ב', consonant("v", "b", "v", initial = "b"))
        put('ג', consonant("g", "g", geresh = consonant("j", "j", "g")))
        put('ד', consonant("d", "d", geresh = consonant("th", "t", "d")))
        put('ה', HEBREW_HE)
        put('ו', HEBREW_VAV)
        put('ז', consonant("z", "z", "s", geresh = consonant("zh", "zh", "j")))
        put('ח', consonant("ch", "h", "k", ""))
        put('ט', consonant("t", "t"))
        put('י', HEBREW_YOD)
        put('כ', HEBREW_KAF)
        put('ך', HEBREW_KAF)
        put('ל', consonant("l", "l"))
        put('מ', consonant("m", "m"))
        put('ם', consonant("m", "m"))
        put('נ', consonant("n", "n"))
        put('ן', consonant("n", "n"))
        put('ס', consonant("s", "s"))
        put('ע', HEBREW_AYIN)
        put('פ', HEBREW_PE)
        put('ף', HEBREW_PE)
        put('צ', HEBREW_TSADI)
        put('ץ', HEBREW_TSADI)
        put('ק', consonant("k", "k"))
        put('ר', consonant("r", "r"))
        put('ש', consonant("sh", "sh", "s"))
        put('ת', consonant("t", "t", "s", geresh = consonant("th", "t")))
        // Arabic (hamza seats are already reduced to their base letter by fold())
        for (c in "اٱءىةع") put(c, carrier("a"))
        put('ه', carrier("h", final = "h", allowH = true))
        put('ب', consonant("b", "b"))
        put('ت', consonant("t", "t"))
        put('ث', consonant("th", "t", "s"))
        put('ج', consonant("j", "j", "g", "dj"))
        put('ح', consonant("h", "h", ""))
        put('خ', consonant("kh", "h", "k"))
        put('د', consonant("d", "d"))
        put('ذ', consonant("dh", "d", "z"))
        put('ر', consonant("r", "r"))
        put('ز', consonant("z", "z"))
        put('س', consonant("s", "s"))
        put('ش', consonant("sh", "sh"))
        put('ص', consonant("s", "s"))
        put('ض', consonant("d", "d"))
        put('ط', consonant("t", "t"))
        put('ظ', consonant("z", "z", "d"))
        put('غ', consonant("gh", "g"))
        put('ف', consonant("f", "f"))
        put('ق', consonant("q", "k", "g"))
        put('ك', consonant("k", "k"))
        put('ل', consonant("l", "l"))
        put('م', consonant("m", "m"))
        put('ن', consonant("n", "n"))
        put('و', ARABIC_WAW)
        put('ي', ARABIC_YEH)
        // Persian / Urdu
        put('پ', consonant("p", "p"))
        put('چ', consonant("ch", "h", "sh"))
        put('ژ', consonant("zh", "zh", "j"))
        put('گ', consonant("g", "g"))
        put('ک', consonant("k", "k"))
        put('ی', ARABIC_YEH)
        put('ٹ', consonant("t", "t"))
        put('ڈ', consonant("d", "d"))
        put('ڑ', consonant("r", "r"))
        put('ں', consonant("n", "n"))
        put('ھ', carrier("h", allowH = true))
        put('ہ', carrier("h", allowH = true))
        put('ۀ', carrier("a"))
        put('ے', carrier("e"))
    }

    /** Final forms share a key with their base letter so doubled letters can be merged. */
    private val BASE_FORM = mapOf('ך' to 'כ', 'ם' to 'מ', 'ן' to 'נ', 'ף' to 'פ', 'ץ' to 'צ')

    private fun parseAbjad(rawToken: String): List<AbjadUnit> {
        val token = rawToken.replace("װ", "וו").replace("ײ", "יי").replace("ױ", "וי")
        val units = ArrayList<AbjadUnit>(token.length)
        var i = 0
        while (i < token.length) {
            val c = token[i]
            val next = token.getOrNull(i + 1)
            when {
                c == '\'' -> i++
                c == 'ו' && next == 'ו' -> { units += AbjadUnit('ו', DOUBLE_VAV); i += 2 }
                c == 'י' && next == 'י' -> { units += AbjadUnit('י', DOUBLE_YOD); i += 2 }
                else -> {
                    val base = ABJAD[c] ?: unknownLetter(c)
                    val gereshVariant = base.geresh
                    val letter = if (next == '\'' && gereshVariant != null) gereshVariant.also { i++ } else base
                    units += AbjadUnit(BASE_FORM[c] ?: c, letter)
                    i++
                }
            }
        }
        // Doubled consonants (rare, usually a missing shadda/dagesh) read as one, like Latin "ll" ~ "l".
        return units.filterIndexed { index, unit ->
            index == 0 || unit.letter.options.isEmpty() || unit.key != units[index - 1].key
        }
    }

    /** Non-abjad character inside an abjad token (digit, stray Latin letter): matches itself. */
    private fun unknownLetter(c: Char): Letter {
        val latin = CYRILLIC[c] ?: LATIN_EXTRA[c] ?: c.toString()
        return Letter(listOf(Phonetic.key(latin)), latin)
    }

    private fun abjadGuess(units: List<AbjadUnit>): String = buildString {
        units.forEachIndexed { index, unit ->
            append(
                when (index) {
                    0 -> unit.letter.initial
                    units.lastIndex -> unit.letter.final
                    else -> unit.letter.guess
                },
            )
        }
    }

    // --- Alphabets ----------------------------------------------------------------------------------

    private val CYRILLIC: Map<Char, String> = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ж' to "zh", 'з' to "z",
        'и' to "i", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r",
        'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh",
        'щ' to "shch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
        // Ukrainian, Belarusian, Serbian, Macedonian (й, ё, ї, ў… lose their marks in fold()).
        'і' to "i", 'є' to "ye", 'ґ' to "g", 'ђ' to "dj", 'ј' to "j", 'љ' to "lj", 'њ' to "nj", 'ћ' to "c",
        'џ' to "dz", 'ѕ' to "dz",
    )

    private val GREEK: Map<Char, String> = mapOf(
        'α' to "a", 'β' to "v", 'γ' to "g", 'δ' to "d", 'ε' to "e", 'ζ' to "z", 'η' to "i", 'θ' to "th",
        'ι' to "i", 'κ' to "k", 'λ' to "l", 'μ' to "m", 'ν' to "n", 'ξ' to "x", 'ο' to "o", 'π' to "p",
        'ρ' to "r", 'σ' to "s", 'ς' to "s", 'τ' to "t", 'υ' to "y", 'φ' to "f", 'χ' to "ch", 'ψ' to "ps",
        'ω' to "o",
    )

    private fun greekDigraph(pair: String, atStart: Boolean): String? = when (pair) {
        "ου" -> "ou"
        "αι" -> "ai"
        "ει" -> "ei"
        "οι" -> "oi"
        "αυ" -> "av"
        "ευ" -> "ev"
        "γγ" -> "ng"
        "μπ" -> if (atStart) "b" else "mp"
        "ντ" -> if (atStart) "d" else "nt"
        "γκ" -> if (atStart) "g" else "ng"
        else -> null
    }

    /** Latin letters that NFKD does not decompose. */
    private val LATIN_EXTRA: Map<Char, String> = mapOf(
        'ß' to "ss", 'æ' to "ae", 'œ' to "oe", 'ø' to "o", 'đ' to "d", 'ð' to "d", 'þ' to "th", 'ł' to "l",
        'ı' to "i", 'ŋ' to "ng", 'ħ' to "h", 'ŧ' to "t",
    )
}
