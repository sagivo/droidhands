package com.droidhands.android.tools.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransliterationTest {
    /** Same preparation as [NameKey]: fold, transliterate, phonetic key. */
    private fun fit(latin: String, abjad: String): AbjadFit = Transliteration.align(
        Phonetic.key(Transliteration.toLatin(Transliteration.fold(latin))),
        Transliteration.abjadSlots(Transliteration.fold(abjad))!!,
    )

    @Test
    fun foldNormalizesMarksApostrophesDigitsAndSeparators() {
        assertEquals("o'brien", Transliteration.fold("O’Brien"))
        assertEquals("jean luc", Transliteration.fold("Jean--Luc"))
        assertEquals("0541234567", Transliteration.fold("٠٥٤١٢٣٤٥٦٧"))
        assertEquals("ג'ני", Transliteration.fold("ג׳ני"))
        assertEquals("عبد الله", Transliteration.fold("عبـــد الله"))
        assertEquals("νικοσ", Transliteration.fold("Νίκος"))
    }

    @Test
    fun cyrillicToLatin() {
        assertEquals("tanya", Transliteration.toLatin("таня"))
        assertEquals("sergei", Transliteration.toLatin(Transliteration.fold("Сергей")))
        assertEquals("aleksandr", Transliteration.toLatin("александр"))
        assertEquals("zhenya", Transliteration.toLatin("женя"))
        assertEquals("olga", Transliteration.toLatin("ольга"))
    }

    @Test
    fun greekToLatin() {
        assertEquals("giorgos", Transliteration.toLatin(Transliteration.fold("Γιώργος")))
        assertEquals("konstantinos", Transliteration.toLatin(Transliteration.fold("Κωνσταντίνος")))
        assertEquals("bampis", Transliteration.toLatin(Transliteration.fold("Μπάμπης")))
        assertEquals("evangelos", Transliteration.toLatin(Transliteration.fold("Ευάγγελος")))
    }

    @Test
    fun abjadBestGuess() {
        assertEquals("tnia", Transliteration.toLatin("טניה"))
        assertEquals("jni", Transliteration.toLatin("ג'ני"))
        assertEquals("mhmd", Transliteration.toLatin("محمد"))
        assertEquals("latin", Transliteration.toLatin("latin"))
    }

    @Test
    fun abjadSlotsOnlyForHebrewAndArabic() {
        assertNull(Transliteration.abjadSlots("tania"))
        assertNull(Transliteration.abjadSlots("таня"))
        assertEquals(4, Transliteration.abjadSlots("טניה")!!.size)
        // Double yod is one letter; the doubled lam of "Allah" reads as one.
        assertEquals(3, Transliteration.abjadSlots("חיים")!!.size)
        assertEquals(3, Transliteration.abjadSlots("الله")!!.size)
    }

    @Test
    fun alignment() {
        assertEquals(AbjadFit.FULL, fit("Tania", "טניה"))
        assertEquals(AbjadFit.FULL, fit("Tanya", "טניה"))
        assertEquals(AbjadFit.LATIN_PREFIX, fit("Tan", "טניה"))
        assertEquals(AbjadFit.SLOTS_PREFIX, fit("Tania", "טנ"))
        assertEquals(AbjadFit.NONE, fit("Tania", "טוני"))
        assertEquals(AbjadFit.FULL, fit("Toni", "טוני"))
        assertEquals(AbjadFit.FULL, fit("Cohen", "כהן"))
        assertEquals(AbjadFit.FULL, fit("Kohen", "כהן"))
        assertEquals(AbjadFit.FULL, fit("Abdullah", "عبدالله"))
        // Hebrew writes final vowels with a letter, so "Dana" is not fully "דן" (Dan).
        assertEquals(AbjadFit.SLOTS_PREFIX, fit("Dana", "דן"))
        assertEquals(AbjadFit.FULL, fit("Dana", "דנה"))
    }
}
