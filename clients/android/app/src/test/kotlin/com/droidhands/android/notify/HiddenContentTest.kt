package com.droidhands.android.notify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenContentTest {
    private val placeholders = setOf(HiddenContent.ENGLISH_PLACEHOLDER, "Contenu sensible de la notification masqué")

    @Test
    fun `android 15 placeholder is detected in any case and with trailing punctuation`() {
        assertTrue(HiddenContent.isPlaceholder("Sensitive notification content hidden", placeholders))
        assertTrue(HiddenContent.isPlaceholder("  sensitive notification content hidden. ", placeholders))
        assertTrue(HiddenContent.isPlaceholder("Sensitive notification content hidden…", placeholders))
    }

    @Test
    fun `localized placeholder from the framework is detected`() {
        assertTrue(HiddenContent.isPlaceholder("Contenu sensible de la notification masqué", placeholders))
    }

    @Test
    fun `ordinary texts are not placeholders`() {
        assertFalse(HiddenContent.isPlaceholder("Your code is 123456", placeholders))
        assertFalse(HiddenContent.isPlaceholder("Sensitive notification content hidden from Bob, see attachment", placeholders))
        assertFalse(HiddenContent.isPlaceholder("", placeholders))
        assertFalse(HiddenContent.isPlaceholder(null, placeholders))
        assertFalse(HiddenContent.isPlaceholder("Sensitive notification content hidden", emptySet()))
    }

    @Test
    fun `hidden when the body or any message is the placeholder`() {
        assertTrue(HiddenContent.isHidden(HiddenContent.ENGLISH_PLACEHOLDER, emptyList(), placeholders))
        assertTrue(HiddenContent.isHidden(null, listOf("hi", HiddenContent.ENGLISH_PLACEHOLDER), placeholders))
        assertFalse(HiddenContent.isHidden("Dinner at 7?", listOf("Dinner at 7?"), placeholders))
    }
}
