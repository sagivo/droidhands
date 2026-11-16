package com.droidhands.android.tools.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses a stand-in redactor: the real one ([com.droidhands.core.util.OneTimeCodes]) is tested in core. */
class RedactionTest {
    private val masker: Redactor = { it.replace(Regex("\\b\\d{6}\\b"), "[code hidden]") }

    @Test
    fun `no redactor leaves text alone`() {
        assertEquals(Redacted("Your code is 123456", changed = false), Redaction.apply("Your code is 123456", null))
    }

    @Test
    fun `masked text is flagged as changed`() {
        assertEquals(Redacted("Your code is [code hidden]", changed = true), Redaction.apply("Your code is 123456", masker))
    }

    @Test
    fun `text without codes is unchanged`() {
        assertEquals(Redacted("See you at 8", changed = false), Redaction.apply("See you at 8", masker))
        assertEquals(Redacted("", changed = false), Redaction.apply("", masker))
    }

    @Test
    fun `visible match is case-insensitive and accepts no query`() {
        assertTrue(Redaction.visibleMatch("Your CODE is [code hidden]", "code"))
        assertTrue(Redaction.visibleMatch("anything", null))
        assertTrue(Redaction.visibleMatch("anything", ""))
    }

    @Test
    fun `a query cannot probe the hidden digits`() {
        val visible = Redaction.apply("Your code is 123456", masker).text
        assertFalse(Redaction.visibleMatch(visible, "is 1"))
        assertFalse(Redaction.visibleMatch(visible, "123"))
        assertTrue(Redaction.visibleMatch(visible, "your code"))
    }
}
