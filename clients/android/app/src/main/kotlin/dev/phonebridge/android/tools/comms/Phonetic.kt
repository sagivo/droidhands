package dev.phonebridge.android.tools.comms

/**
 * A deliberately simple "sounds alike" spelling for Latin name tokens, so common spelling
 * variants collapse to the same key: Tanya/Tania/Tanja, Philip/Filip/Phillip, Catherine/Katherine,
 * Sarah/Sara, Jenny/Jennie, Mikhail/Mihail, Tsvi/Tzvi/Zvi.
 *
 * It is applied to both sides of a comparison, so it only needs to be consistent, not linguistically
 * exact. Input is a lowercase Latin token (see [Transliteration.toLatin]); characters of other
 * scripts pass through unchanged.
 */
internal object Phonetic {
    private const val VOWELS = "aeiou"

    /** Ordered: longer spellings must be rewritten before their substrings ("sch" before "ch"). */
    private val REWRITES = listOf(
        "sch" to "sh", "ph" to "f", "ck" to "k", "chr" to "kr", "chl" to "kl", "ch" to "h", "kh" to "h",
        "th" to "t", "dh" to "d", "gh" to "g", "tz" to "z", "ts" to "z", "x" to "ks", "q" to "k", "w" to "v",
        "oo" to "u", "ou" to "u", "ee" to "i",
    )

    fun key(latin: String): String {
        if (latin.isEmpty()) return latin
        var s = latin
        for ((from, to) in REWRITES) s = s.replace(from, to)
        s = s.replace('y', 'i')
        s = softenCAndJ(s)
        // Yevgeny/Evgeny, Yelena/Elena.
        if (s.length > 2 && s.startsWith("ie")) s = s.substring(1)
        s = dropSilentH(s)
        // Jennie/Jenny/Jenni, Marie/Mari.
        if (s.length > 2 && s.endsWith("ie")) s = s.dropLast(1)
        return collapseDoubles(s)
    }

    /** `c` → `s` before e/i, else `k`; `j` between a consonant and a vowel → `i` (Tanja, Sonja). */
    private fun softenCAndJ(s: String): String {
        val out = StringBuilder(s.length)
        for (i in s.indices) {
            val c = s[i]
            val prev = s.getOrNull(i - 1)
            val next = s.getOrNull(i + 1)
            out.append(
                when (c) {
                    'c' -> if (next == 'e' || next == 'i') 's' else 'k'
                    'j' -> if (prev != null && isConsonant(prev) && next != null && next in VOWELS) 'i' else 'j'
                    else -> c
                },
            )
        }
        return out.toString()
    }

    /** Drops `h` after a vowel when a consonant or the end follows: Sarah → sara, John → jon. */
    private fun dropSilentH(s: String): String {
        val out = StringBuilder(s.length)
        for (i in s.indices) {
            val c = s[i]
            if (c == 'h' && i > 0 && s[i - 1] in VOWELS) {
                val next = s.getOrNull(i + 1)
                if (next == null || isConsonant(next)) continue
            }
            out.append(c)
        }
        return out.toString()
    }

    private fun collapseDoubles(s: String): String {
        val out = StringBuilder(s.length)
        for (c in s) if (out.isEmpty() || out[out.length - 1] != c) out.append(c)
        return out.toString()
    }

    private fun isConsonant(c: Char): Boolean = c in 'a'..'z' && c !in VOWELS
}
