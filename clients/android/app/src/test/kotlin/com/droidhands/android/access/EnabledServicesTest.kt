package com.droidhands.android.access

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnabledServicesTest {
    private val pkg = "com.droidhands.android"
    private val cls = "com.droidhands.android.access.BridgeAccessibilityService"

    @Test
    fun `short and full flattened forms`() {
        assertTrue(EnabledServices.contains("$pkg/.access.BridgeAccessibilityService", pkg, cls))
        assertTrue(EnabledServices.contains("$pkg/$cls", pkg, cls))
    }

    @Test
    fun `colon separated lists, whitespace and case`() {
        val setting = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService: " +
            "COM.DroidHands.Android/.access.bridgeaccessibilityservice :"
        assertTrue(EnabledServices.contains(setting, pkg, cls))
        assertEquals(2, EnabledServices.parse(setting).size)
    }

    @Test
    fun `absent, empty and malformed settings`() {
        assertFalse(EnabledServices.contains(null, pkg, cls))
        assertFalse(EnabledServices.contains("", pkg, cls))
        assertFalse(EnabledServices.contains("garbage:/:$pkg/:/x", pkg, cls))
        assertFalse(EnabledServices.contains("$pkg/.access.OtherService", pkg, cls))
        assertFalse(EnabledServices.contains("other.pkg/$cls", pkg, cls))
        assertEquals(listOf("a.b" to "a.b.C"), EnabledServices.parse("a.b/.C"))
    }
}
