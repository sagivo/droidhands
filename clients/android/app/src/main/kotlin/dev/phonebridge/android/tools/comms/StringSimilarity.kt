package dev.phonebridge.android.tools.comms

import kotlin.math.max
import kotlin.math.min

/** Classic string similarity measures (pure Kotlin). All similarities are in 0..1. */
internal object StringSimilarity {
    private const val WINKLER_PREFIX_SCALE = 0.1
    private const val WINKLER_MAX_PREFIX = 4
    private const val WINKLER_BOOST_THRESHOLD = 0.7

    /** Jaro-Winkler similarity (prefix scale 0.1, up to 4 prefix chars, boost above 0.7). */
    fun jaroWinkler(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val window = max(0, max(a.length, b.length) / 2 - 1)
        val aMatched = BooleanArray(a.length)
        val bMatched = BooleanArray(b.length)
        var matches = 0
        for (i in a.indices) {
            val from = max(0, i - window)
            val to = min(b.length - 1, i + window)
            for (j in from..to) {
                if (bMatched[j] || a[i] != b[j]) continue
                aMatched[i] = true
                bMatched[j] = true
                matches++
                break
            }
        }
        if (matches == 0) return 0.0
        var transpositions = 0
        var k = 0
        for (i in a.indices) {
            if (!aMatched[i]) continue
            while (!bMatched[k]) k++
            if (a[i] != b[k]) transpositions++
            k++
        }
        val m = matches.toDouble()
        val jaro = (m / a.length + m / b.length + (m - transpositions / 2.0) / m) / 3.0
        if (jaro <= WINKLER_BOOST_THRESHOLD) return jaro
        var prefix = 0
        while (prefix < min(WINKLER_MAX_PREFIX, min(a.length, b.length)) && a[prefix] == b[prefix]) prefix++
        return jaro + prefix * WINKLER_PREFIX_SCALE * (1.0 - jaro)
    }

    /** Levenshtein edit distance (insert, delete, substitute; unit costs). */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    /** 1 - distance / longer length. */
    fun levenshteinSimilarity(a: String, b: String): Double {
        val longest = max(a.length, b.length)
        if (longest == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / longest
    }

    /**
     * Average of Jaro-Winkler and normalized Levenshtein. Jaro-Winkler alone is generous to short
     * names that share a first letter ("tania"/"tamar" ≈ 0.79); averaging with the edit distance
     * keeps those apart while real spelling variants stay high ("tania"/"tanja" ≈ 0.85).
     */
    fun similarity(a: String, b: String): Double = (jaroWinkler(a, b) + levenshteinSimilarity(a, b)) / 2.0
}
