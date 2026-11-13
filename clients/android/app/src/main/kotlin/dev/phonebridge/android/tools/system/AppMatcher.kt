package dev.phonebridge.android.tools.system

import java.text.Normalizer
import java.util.Locale

/** A launchable app (one entry per package). */
data class AppEntry(
    val packageName: String,
    val label: String,
    val versionName: String?,
    /** Installed on the system image (may have been updated since). */
    val system: Boolean,
    /** Preinstalled system app that was never updated (hidden by `apps_list include_system=false`). */
    val preinstalledOnly: Boolean = false,
)

sealed interface AppMatch {
    data class Found(val app: AppEntry) : AppMatch

    data class Ambiguous(val candidates: List<AppEntry>) : AppMatch

    /** Nothing matched well enough; [suggestions] are the closest labels, best first. */
    data class NotFound(val suggestions: List<AppEntry>) : AppMatch
}

/**
 * Resolves an app *name* (as a user would say it) to an installed app. Tiers, first non-empty
 * wins: exact label (or exact package) > label starts with > label contains > every query word
 * found in the label words or package segments > fuzzy (typo-tolerant edit distance). Matching
 * ignores case, accents and punctuation. More than one app in the winning tier is ambiguous: the
 * caller reports the candidates rather than guessing.
 */
object AppMatcher {
    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
            .replace(NON_WORD, " ")
            .trim()

    fun match(query: String, apps: List<AppEntry>): AppMatch {
        val q = normalize(query)
        if (q.isEmpty()) return AppMatch.NotFound(emptyList())
        val indexed = apps.map { Indexed(it, normalize(it.label)) }

        val tiers: List<(Indexed) -> Boolean> = listOf(
            { it.label == q || it.app.packageName.equals(query.trim(), ignoreCase = true) },
            { it.label.startsWith(q) },
            { it.label.contains(q) },
            { wordsMatch(q, it) },
        )
        for (tier in tiers) {
            val hits = indexed.filter(tier)
            if (hits.isNotEmpty()) return result(hits.map { it.app })
        }

        val scored = indexed.map { it to fuzzyDistance(q, it) }
        val allowed = allowedTypos(q.length)
        val best = scored.minOfOrNull { it.second }
        if (best != null && best <= allowed) return result(scored.filter { it.second == best }.map { it.first.app })
        return AppMatch.NotFound(scored.sortedBy { it.second }.take(5).map { it.first.app })
    }

    private class Indexed(val app: AppEntry, val label: String) {
        val labelWords: List<String> = label.split(' ').filter { it.isNotEmpty() }
        val packageWords: List<String> = app.packageName.lowercase(Locale.ROOT).split('.', '_').filter { it.isNotEmpty() }
    }

    private fun result(hits: List<AppEntry>): AppMatch =
        if (hits.size == 1) AppMatch.Found(hits[0]) else AppMatch.Ambiguous(hits.sortedBy { it.label.lowercase(Locale.ROOT) })

    /** "google maps" matches label "Maps" in package com.google.android.apps.maps. */
    private fun wordsMatch(q: String, entry: Indexed): Boolean {
        val words = q.split(' ').filter { it.isNotEmpty() }
        if (words.size < 2) return false
        val pool = entry.labelWords + entry.packageWords
        return words.all { w -> pool.any { it.startsWith(w) } }
    }

    private fun fuzzyDistance(q: String, entry: Indexed): Int {
        val candidates = buildList {
            add(entry.label)
            add(entry.label.replace(" ", ""))
            addAll(entry.labelWords)
        }
        return candidates.minOf { editDistance(q, it) }
    }

    /** Typos tolerated for a query of [length] characters: none for very short names. */
    fun allowedTypos(length: Int): Int = when {
        length <= 3 -> 0
        length <= 5 -> 1
        length <= 8 -> 2
        else -> 3
    }

    /** Optimal-string-alignment distance: insertions, deletions, substitutions and adjacent transpositions. */
    fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, d[i - 2][j - 2] + 1)
                d[i][j] = v
            }
        }
        return d[a.length][b.length]
    }
}
