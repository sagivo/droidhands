package com.droidhands.android.confirm

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayTextTest {
    @Test
    fun removesControlAndBidiCharacters() {
        val rlo = 0x202E.toChar()
        val zwsp = 0x200B.toChar()
        assertEquals("claude.ai", DisplayText.clean("claude${zwsp}.ai", 100))
        assertEquals("evil moc.ai", DisplayText.clean("evil ${rlo}moc.ai", 100))
        assertEquals("ab", DisplayText.clean("a${0x07.toChar()}b", 100))
    }

    @Test
    fun collapsesWhitespaceAndTrims() {
        assertEquals("Work laptop", DisplayText.clean("  Work \n\t laptop  ", 100))
        assertEquals("", DisplayText.clean(null, 10))
        assertEquals("", DisplayText.clean(" \n ", 10))
    }

    @Test
    fun cutsWithAnEllipsis() {
        assertEquals("abcd…", DisplayText.clean("abcdefgh", 5))
        assertEquals("abcde", DisplayText.clean("abcde", 5))
        assertEquals("ab…", DisplayText.clean("ab   cdef", 4))
    }

    @Test
    fun keepsOtherScripts() {
        assertEquals("טניה כהן", DisplayText.clean("טניה כהן", 100))
        assertEquals("みずほ銀行", DisplayText.clean("みずほ銀行", 100))
    }
}
