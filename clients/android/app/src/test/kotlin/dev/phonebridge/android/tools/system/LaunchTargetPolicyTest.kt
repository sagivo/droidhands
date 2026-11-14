package dev.phonebridge.android.tools.system

import dev.phonebridge.core.tools.ToolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchTargetPolicyTest {
    private val resolver = LaunchHandler("android", "com.android.internal.app.ResolverActivity", "Android System")
    private val chrome = LaunchHandler("com.android.chrome", "com.google.android.apps.chrome.IntentDispatcher", "Chrome")
    private val firefox = LaunchHandler("org.mozilla.firefox", "org.mozilla.firefox.App", "Firefox")
    private val bank = LaunchHandler("com.example.bank", "com.example.bank.LinkActivity", "My Bank")

    private fun allowAllBut(vararg blocked: LaunchHandler): (String) -> Boolean {
        val names = blocked.map { it.packageName }.toSet()
        return { it !in names }
    }

    @Test
    fun `system resolver and chooser activities are recognised`() {
        assertTrue(LaunchTargetPolicy.isChooser(resolver, emptyList()))
        assertTrue(LaunchTargetPolicy.isChooser(LaunchHandler("com.android.intentresolver", "x.Y"), emptyList()))
        assertTrue(LaunchTargetPolicy.isChooser(LaunchHandler("com.oem.ui", "com.oem.ui.CustomResolverActivity"), emptyList()))
        assertTrue(LaunchTargetPolicy.isChooser(LaunchHandler("com.oem.ui", "com.oem.ChooserActivity"), emptyList()))
        assertFalse(LaunchTargetPolicy.isChooser(chrome, listOf(chrome, firefox)))
        assertFalse(LaunchTargetPolicy.isChooser(chrome, listOf(chrome)))
    }

    @Test
    fun `an activity that is not one of several candidates is treated as a chooser`() {
        val oemPicker = LaunchHandler("com.oem.picker", "com.oem.picker.Pick")
        assertTrue(LaunchTargetPolicy.isChooser(oemPicker, listOf(chrome, firefox)))
        assertFalse(LaunchTargetPolicy.isChooser(oemPicker, listOf(chrome)))
    }

    @Test
    fun `a concrete allowed handler proceeds`() {
        assertEquals(LaunchDecision.Proceed(chrome), LaunchTargetPolicy.decide(chrome, listOf(chrome, firefox), allowAllBut(bank)))
    }

    @Test
    fun `a concrete blocked handler is refused even when others could open it`() {
        assertEquals(LaunchDecision.Blocked(listOf(bank)), LaunchTargetPolicy.decide(bank, listOf(bank, chrome), allowAllBut(bank)))
    }

    @Test
    fun `chooser with every candidate allowed lets the user pick`() {
        assertEquals(LaunchDecision.Proceed(null), LaunchTargetPolicy.decide(resolver, listOf(chrome, firefox), allowAllBut(bank)))
    }

    @Test
    fun `chooser with one allowed candidate is pinned to it`() {
        assertEquals(LaunchDecision.Pin(chrome), LaunchTargetPolicy.decide(resolver, listOf(bank, chrome), allowAllBut(bank)))
    }

    @Test
    fun `chooser with several allowed and some blocked asks for a package`() {
        val decision = LaunchTargetPolicy.decide(resolver, listOf(bank, chrome, firefox), allowAllBut(bank))
        assertEquals(LaunchDecision.Choose(listOf(chrome, firefox)), decision)
        val e = assertThrows(ToolException::class.java) { LaunchTargetPolicy.requireLaunchable(decision, "this link") }
        assertTrue(e.message!!, e.message!!.contains("Chrome (com.android.chrome)"))
        assertTrue(e.message!!.contains("Firefox (org.mozilla.firefox)"))
        assertTrue(e.message!!.contains("'package'"))
        assertFalse("blocked apps must not be named", e.message!!.contains("bank"))
    }

    @Test
    fun `chooser with every candidate blocked is refused`() {
        val decision = LaunchTargetPolicy.decide(resolver, listOf(bank, chrome), allowAllBut(bank, chrome))
        assertEquals(LaunchDecision.Blocked(listOf(bank, chrome)), decision)
        val e = assertThrows(ToolException::class.java) { LaunchTargetPolicy.requireLaunchable(decision, "this link") }
        assertTrue(e.message!!.contains("blocked by the user for this agent"))
        assertTrue(e.message!!.contains("work around"))
    }

    @Test
    fun `chooser candidates are de-duplicated by package and exclude the resolver itself`() {
        val chromeOther = chrome.copy(activityName = "com.google.android.apps.chrome.Other")
        assertEquals(LaunchDecision.Pin(chrome), LaunchTargetPolicy.decide(resolver, listOf(resolver, bank, chrome, chromeOther), allowAllBut(bank)))
    }

    @Test
    fun `chooser without visible candidates cannot be verified`() {
        val decision = LaunchTargetPolicy.decide(resolver, emptyList()) { true }
        assertEquals(LaunchDecision.Blocked(emptyList()), decision)
        val e = assertThrows(ToolException::class.java) { LaunchTargetPolicy.requireLaunchable(decision, "this intent") }
        assertTrue(e.message!!.contains("could not check"))
    }

    @Test
    fun `proceed and pin do not throw`() {
        LaunchTargetPolicy.requireLaunchable(LaunchDecision.Proceed(null), "x")
        LaunchTargetPolicy.requireLaunchable(LaunchDecision.Pin(chrome), "x")
    }

    @Test
    fun `long candidate lists are capped`() {
        val many = (1..12).map { LaunchHandler("com.example.app$it", null, "App $it") }
        val message = LaunchTargetPolicy.chooseMessage(many, "this link")
        assertTrue(message.contains("App 8 (com.example.app8)"))
        assertFalse(message.contains("App 9 ("))
        assertTrue(message.contains("and 4 more"))
    }
}
