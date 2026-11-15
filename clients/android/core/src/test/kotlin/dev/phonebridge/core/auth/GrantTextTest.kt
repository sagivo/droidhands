package dev.phonebridge.core.auth

import dev.phonebridge.core.tools.AppAccess
import dev.phonebridge.core.tools.AppBlockedException
import dev.phonebridge.core.tools.Scopes
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GrantTextTest {
    @Test
    fun missingScopeNamesTheAbilityAndWhereToAddIt() {
        val text = GrantText.missingScope("Claude", Scopes.CALLS, "phone_call")
        assertTrue(text.startsWith("Claude isn't allowed to make or answer calls ('phone_call' needs that)."), text)
        assertTrue("The user can allow it in PhoneBridge > Agents > Claude > Access." in text, text)
        assertTrue("Do not try to work around this" in text, text)
    }

    @Test
    fun everyScopeHasAPlainPhrase() {
        for (scope in Scopes.ALL) {
            val text = GrantText.missingScope("A", scope, "t")
            assertFalse("scope" in text.substringBefore("."), "jargon for $scope: $text")
        }
        assertTrue("it needs the 'x.y' scope" in GrantText.missingScope("A", "x.y", "t"))
    }

    @Test
    fun appOutsideTheGrant() {
        val text = GrantText.appNotAllowed("Claude", "com.whatsapp", "WhatsApp", globally = false)
        assertTrue(text.startsWith("Claude isn't allowed to use WhatsApp (com.whatsapp). The user can allow it in PhoneBridge > Agents > Claude > Access."), text)
        assertTrue("work around" in text)
    }

    @Test
    fun neverAllowedAppIsNotPresentedAsSomethingToAllow() {
        val text = GrantText.appNotAllowed("Claude", "com.bank", "Bank", globally = true)
        assertTrue(text.startsWith("Bank (com.bank) is on PhoneBridge's never-allow list, so no agent may use it."), text)
        assertFalse("Agents > Claude > Access" in text)
        assertTrue("let them do it themselves" in text)
    }

    @Test
    fun blankNamesAndLabels() {
        assertEquals("PhoneBridge > Agents > this agent > Access", GrantText.accessPath("  "))
        assertTrue(GrantText.appNotAllowed("", "com.x", "com.x", globally = false).startsWith("This agent isn't allowed to use com.x."))
    }

    @Test
    fun appAccessRequireUsesTheAgentName() {
        val access = AppAccess.of(AppPolicy(AppAccessMode.ALLOWLIST, setOf("com.gmail")), setOf("com.bank"), "Claude")
        val e = assertFailsWith<AppBlockedException> { access.require("com.whatsapp", "WhatsApp") }
        assertTrue(e.message!!.startsWith("Claude isn't allowed to use WhatsApp (com.whatsapp)."), e.message)
        val bank = assertFailsWith<AppBlockedException> { access.require("com.bank", "Bank") }
        assertTrue("never-allow list" in bank.message!!)
    }
}
