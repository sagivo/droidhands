package com.droidhands.android.tools.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StringSimilarityTest {
    @Test
    fun jaroWinklerReferenceValues() {
        assertEquals(0.961, StringSimilarity.jaroWinkler("martha", "marhta"), 1e-3)
        assertEquals(0.840, StringSimilarity.jaroWinkler("dwayne", "duane"), 1e-3)
        assertEquals(0.813, StringSimilarity.jaroWinkler("dixon", "dicksonx"), 1e-3)
        assertEquals(1.0, StringSimilarity.jaroWinkler("same", "same"), 0.0)
        assertEquals(0.0, StringSimilarity.jaroWinkler("", "abc"), 0.0)
        assertEquals(0.0, StringSimilarity.jaroWinkler("abc", "xyz"), 0.0)
    }

    @Test
    fun levenshteinDistance() {
        assertEquals(3, StringSimilarity.levenshtein("kitten", "sitting"))
        assertEquals(0, StringSimilarity.levenshtein("abc", "abc"))
        assertEquals(3, StringSimilarity.levenshtein("", "abc"))
        assertEquals(1, StringSimilarity.levenshtein("tania", "tanja"))
        assertEquals(1.0, StringSimilarity.levenshteinSimilarity("", ""), 0.0)
        assertEquals(0.8, StringSimilarity.levenshteinSimilarity("tania", "tanja"), 1e-9)
    }

    @Test
    fun blendedSimilaritySeparatesShortLookalikes() {
        assertTrue(StringSimilarity.similarity("tania", "tamar") < NameMatching.FUZZY_MIN)
        assertTrue(StringSimilarity.similarity("tania", "tanja") >= NameMatching.FUZZY_MIN)
        assertTrue(StringSimilarity.similarity("daniela", "daniel") > 0.9)
    }
}
