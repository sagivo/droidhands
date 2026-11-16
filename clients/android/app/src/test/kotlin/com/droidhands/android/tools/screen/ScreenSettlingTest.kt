package com.droidhands.android.tools.screen

import com.droidhands.android.access.WindowKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSettlingTest {
    @Test
    fun `only status bar captured while an app is in front means the app window is missing`() {
        assertTrue(ScreenSettling.appWindowMissing("dev.example.mail", listOf(WindowKind.SYSTEM)))
        assertTrue(ScreenSettling.appWindowMissing("dev.example.mail", emptyList()))
    }

    @Test
    fun `an application window or a System UI foreground is fine`() {
        assertFalse(ScreenSettling.appWindowMissing("dev.example.mail", listOf(WindowKind.SYSTEM, WindowKind.APPLICATION)))
        assertFalse(ScreenSettling.appWindowMissing("com.android.systemui", listOf(WindowKind.SYSTEM)))
    }
}
