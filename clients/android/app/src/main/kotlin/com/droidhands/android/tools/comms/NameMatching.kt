package com.droidhands.android.tools.comms

import kotlin.math.max
import kotlin.math.min

/**
 * A name prepared for matching (build once per name with [of]). Each token is kept in three
 * spellings: as written ([literal], folded by [Transliteration.fold]), in Latin letters ([latin]) and
 * as a [Phonetic] key ([phonetic]). Hebrew/Arabic tokens also get abjad [slots].
 */
internal class NameKey private constructor(
    val literal: List<String>,
    val latin: List<String>,
    val phonetic: List<String>,
    val slots: List<List<AbjadSlot>?>,
) {
    val isEmpty: Boolean get() = literal.isEmpty()
    val hasNonLatin: Boolean = latin != literal
    val hasAbjad: Boolean = slots.any { it != null }

    companion object {
        /** Names with more tokens than this are compared on their first tokens only. */
        private const val MAX_TOKENS = 8

        fun of(raw: String): NameKey {
            val literal = ArrayList<String>()
            val latin = ArrayList<String>()
            val phonetic = ArrayList<String>()
            val slots = ArrayList<List<AbjadSlot>?>()
            for (token in Transliteration.fold(raw).split(' ')) {
                if (literal.size == MAX_TOKENS) break
                val plain = token.replace("'", "")
                if (plain.isEmpty()) continue
                val latinToken = Transliteration.toLatin(token).ifEmpty { plain }
                literal += plain
                latin += latinToken
                phonetic += Phonetic.key(latinToken).ifEmpty { latinToken }
                slots += Transliteration.abjadSlots(token)
            }
            return NameKey(literal, latin, phonetic, slots)
        }
    }
}

/**
 * Contact-name matching: normalization, transliteration and scoring (pure Kotlin, unit-tested).
 *
 * A query is compared with a name at four levels, from strict to loose, and at each level it gets
 * the strongest structural tier that applies:
 *
 * | tier | meaning | literal | Latin | phonetic | abjad |
 * |---|---|---|---|---|---|
 * | exact | same tokens, same order (or same letters ignoring spaces) | 1.00 | 0.97 | 0.95 | 0.94 |
 * | prefix | the query's whole tokens start the name ("Tania" → "Tania Cohen") | 0.90 | 0.88 | 0.87 | 0.86 |
 * | tokens | every query token equals a name token, any order ("Cohen Tania", "Cohen") | 0.87 | 0.86 | 0.85 | 0.84 |
 * | partial prefix | as prefix, but the last query token only starts a name token ("Tan", "Tania C") | 0.83 | 0.82 | 0.81 | 0.80 |
 * | token prefix | every query token starts a name token ("T Coh", "Coh") | 0.81 | 0.80 | 0.79 | 0.78 |
 * | contained | every name token is in a longer query ("Tania Cohen" → "Tania") | 0.78 | 0.77 | 0.76 | 0.75 |
 *
 * Only whole-token tiers can reach [AUTO_PICK]: a query token that is just the start of a name token
 * ("Dan" → "Danielle") may abbreviate that name or be a different, unsaved name, so such a match is
 * listed as a candidate but never picked on its own. Partial tiers stay below [AUTO_PICK] minus
 * [ContactRanking.STARRED_BOOST] and at or above [RecipientSelection.RUNNER_UP_MAX], so a contact that
 * merely starts with the query ("Dan" → "Dana Levi") also keeps a whole-token match ("Dan" → "Dan
 * Katz") from being picked automatically.
 *
 * Literal compares the names as written (accents and punctuation removed); Latin after
 * transliterating Hebrew/Arabic/Cyrillic/Greek; phonetic after [Phonetic] folding (Tanya ~ Tania);
 * abjad aligns Hebrew/Arabic words with Latin spellings ("טניה" ~ "Tania"). If no tier applies, a
 * token-aware fuzzy similarity of the phonetic keys is used: accepted from [FUZZY_MIN], capped at
 * [FUZZY_CAP] so a fuzzy match alone never reaches [AUTO_PICK].
 */
internal object NameMatching {
    const val EXACT = 1.0

    /** Lowest score that may be picked automatically as a message/call target (see [RecipientSelection]). */
    const val AUTO_PICK = 0.85

    /** Lowest accepted fuzzy similarity. */
    const val FUZZY_MIN = 0.72

    /** Highest score a fuzzy-only match can get. */
    const val FUZZY_CAP = 0.83

    /** Shortest phonetic query (letters) for which fuzzy matching is attempted. */
    private const val FUZZY_MIN_QUERY_LENGTH = 3

    /** Strongest first; the [Level] score arrays follow this order. */
    private enum class Tier { EXACT, PREFIX, TOKENS, PARTIAL_PREFIX, TOKEN_PREFIX, CONTAINED }

    private enum class Level(private val scores: DoubleArray) {
        LITERAL(doubleArrayOf(1.00, 0.90, 0.87, 0.83, 0.81, 0.78)),
        LATIN(doubleArrayOf(0.97, 0.88, 0.86, 0.82, 0.80, 0.77)),
        PHONETIC(doubleArrayOf(0.95, 0.87, 0.85, 0.81, 0.79, 0.76)),
        ABJAD(doubleArrayOf(0.94, 0.86, 0.84, 0.80, 0.78, 0.75)),
        ;

        fun score(tier: Tier): Double = scores[tier.ordinal]
    }

    /** Relation of a query token to a name token. */
    private enum class Rel { NONE, PREFIX, EQUAL }

    /** Lowercase, accents/diacritics removed, punctuation removed, single spaces ("José O'Brien" → "jose obrien"). */
    fun normalize(text: String): String = NameKey.of(text).literal.joinToString(" ")

    /** Latin spelling of [text] ("Таня" → "tanya", "טניה" → "tnia"). */
    fun transliterate(text: String): String = NameKey.of(text).latin.joinToString(" ")

    /** Phonetic key of [text] ("Tanya" → "tania", "Philippe" → "filipe"). */
    fun phonetic(text: String): String = NameKey.of(text).phonetic.joinToString(" ")

    fun score(query: String, name: String): Double = score(NameKey.of(query), NameKey.of(name))

    /** Best [score] of [query] against any of [names] (display names, nicknames…). */
    fun bestScore(query: NameKey, names: Iterable<NameKey>): Double {
        var best = 0.0
        for (name in names) {
            best = max(best, score(query, name))
            if (best >= EXACT) break
        }
        return best
    }

    /** 0 when the name does not match at all, otherwise in [FUZZY_MIN]..1 (1 = exact). */
    fun score(query: NameKey, name: NameKey): Double {
        if (query.isEmpty || name.isEmpty) return 0.0
        var best = 0.0
        fun consider(level: Level, tier: Tier?) {
            if (tier != null) best = max(best, level.score(tier))
        }
        consider(Level.LITERAL, stringTier(query.literal, name.literal))
        if (best >= EXACT) return EXACT
        if (query.hasNonLatin || name.hasNonLatin) consider(Level.LATIN, stringTier(query.latin, name.latin))
        consider(Level.PHONETIC, stringTier(query.phonetic, name.phonetic))
        if (query.hasAbjad || name.hasAbjad) consider(Level.ABJAD, abjadTier(query, name))
        if (best < FUZZY_CAP) {
            val fuzzy = fuzzy(query, name)
            if (fuzzy >= FUZZY_MIN) best = max(best, min(fuzzy, FUZZY_CAP))
        }
        return best
    }

    private fun stringTier(query: List<String>, name: List<String>): Tier? {
        if (query.joinToString("") == name.joinToString("")) return Tier.EXACT
        return tier(query.size, name.size) { q, n ->
            when {
                query[q] == name[n] -> Rel.EQUAL
                name[n].startsWith(query[q]) -> Rel.PREFIX
                else -> Rel.NONE
            }
        }
    }

    private fun abjadTier(query: NameKey, name: NameKey): Tier? = tier(query.literal.size, name.literal.size) { q, n ->
        val querySlots = query.slots[q]
        val nameSlots = name.slots[n]
        when {
            querySlots == null && nameSlots == null -> relation(query.phonetic[q], name.phonetic[n])
            querySlots != null && nameSlots != null -> relation(query.literal[q], name.literal[n])
            // Latin query, Hebrew/Arabic name.
            nameSlots != null -> when (Transliteration.align(query.phonetic[q], nameSlots)) {
                AbjadFit.FULL -> Rel.EQUAL
                AbjadFit.LATIN_PREFIX -> Rel.PREFIX
                else -> Rel.NONE
            }
            // Hebrew/Arabic query, Latin name.
            querySlots != null -> when (Transliteration.align(name.phonetic[n], querySlots)) {
                AbjadFit.FULL -> Rel.EQUAL
                AbjadFit.SLOTS_PREFIX -> Rel.PREFIX
                else -> Rel.NONE
            }
            else -> Rel.NONE
        }
    }

    private fun relation(query: String, name: String): Rel = when {
        query == name -> Rel.EQUAL
        name.startsWith(query) -> Rel.PREFIX
        else -> Rel.NONE
    }

    /** Structural tier from a token relation; see the table in the class KDoc. */
    private fun tier(queryCount: Int, nameCount: Int, relationOf: (q: Int, n: Int) -> Rel): Tier? {
        if (queryCount == 0 || nameCount == 0) return null
        val rel = Array(queryCount) { q -> Array(nameCount) { n -> relationOf(q, n) } }
        if (queryCount <= nameCount) {
            val last = queryCount - 1
            val leadingEqual = (0 until last).all { rel[it][it] == Rel.EQUAL }
            if (leadingEqual && rel[last][last] == Rel.EQUAL) return if (queryCount == nameCount) Tier.EXACT else Tier.PREFIX
            if (assignable(queryCount, nameCount) { q, n -> rel[q][n] == Rel.EQUAL }) return Tier.TOKENS
            if (leadingEqual && rel[last][last] == Rel.PREFIX) return Tier.PARTIAL_PREFIX
            if (assignable(queryCount, nameCount) { q, n -> rel[q][n] != Rel.NONE }) return Tier.TOKEN_PREFIX
            return null
        }
        return if (assignable(nameCount, queryCount) { n, q -> rel[q][n] == Rel.EQUAL }) Tier.CONTAINED else null
    }

    /** True if every one of [from] items can be paired with a distinct one of [to] items accepted by [ok]. */
    private fun assignable(from: Int, to: Int, ok: (Int, Int) -> Boolean): Boolean {
        val used = BooleanArray(to)
        fun place(i: Int): Boolean {
            if (i == from) return true
            for (j in 0 until to) {
                if (used[j] || !ok(i, j)) continue
                used[j] = true
                if (place(i + 1)) return true
                used[j] = false
            }
            return false
        }
        return place(0)
    }

    /**
     * Token-aware similarity of the phonetic keys: each query token is paired greedily with its most
     * similar unused name token, weighted by token length (unpaired query tokens count as 0). The
     * whole names without spaces are compared too; the better of the two wins.
     */
    private fun fuzzy(query: NameKey, name: NameKey): Double {
        val queryJoined = query.phonetic.joinToString("")
        if (queryJoined.length < FUZZY_MIN_QUERY_LENGTH) return 0.0
        val whole = StringSimilarity.similarity(queryJoined, name.phonetic.joinToString(""))
        val q = query.phonetic
        val n = name.phonetic
        val sims = Array(q.size) { i -> DoubleArray(n.size) { j -> StringSimilarity.similarity(q[i], n[j]) } }
        val queryUsed = BooleanArray(q.size)
        val nameUsed = BooleanArray(n.size)
        var weighted = 0.0
        repeat(min(q.size, n.size)) {
            var bestI = -1
            var bestJ = -1
            for (i in q.indices) {
                if (queryUsed[i]) continue
                for (j in n.indices) {
                    if (!nameUsed[j] && (bestI < 0 || sims[i][j] > sims[bestI][bestJ])) {
                        bestI = i
                        bestJ = j
                    }
                }
            }
            queryUsed[bestI] = true
            nameUsed[bestJ] = true
            weighted += sims[bestI][bestJ] * q[bestI].length
        }
        val tokens = weighted / queryJoined.length
        return max(whole, tokens)
    }
}
