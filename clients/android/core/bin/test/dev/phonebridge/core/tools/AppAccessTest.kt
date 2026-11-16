package dev.phonebridge.core.tools

import dev.phonebridge.core.auth.AppAccessMode
import dev.phonebridge.core.auth.AppPolicy
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppAccessTest {
    private val whatsapp = "com.whatsapp"
    private val bank = "com.bank.app"
    private val maps = "com.google.android.apps.maps"
    private val bridge = "dev.phonebridge.android"
    private val never = setOf(bank, bridge)

    @Test
    fun matrix() {
        // mode, token packages -> (package -> allowed)
        val cases = listOf(
            Triple(AppAccessMode.ALL, setOf(whatsapp), mapOf(whatsapp to true, maps to true, bank to false, bridge to false)),
            Triple(AppAccessMode.ALLOWLIST, setOf(whatsapp, bank), mapOf(whatsapp to true, maps to false, bank to false, bridge to false)),
            Triple(AppAccessMode.ALLOWLIST, emptySet(), mapOf(whatsapp to false, maps to false, bank to false)),
            Triple(AppAccessMode.BLOCKLIST, setOf(whatsapp), mapOf(whatsapp to false, maps to true, bank to false, bridge to false)),
            Triple(AppAccessMode.BLOCKLIST, emptySet(), mapOf(whatsapp to true, maps to true, bank to false)),
        )
        for ((mode, packages, expected) in cases) {
            val access = AppAccess(mode, packages, never)
            for ((pkg, allowed) in expected) {
                assertEquals(allowed, access.isAllowed(pkg), "$mode $packages $pkg")
                if (allowed) access.require(pkg) else assertFailsWith<AppBlockedException>("$mode $pkg") { access.require(pkg) }
            }
        }
    }

    @Test
    fun neverAllowWinsEvenWhenAllowlisted() {
        val access = AppAccess.of(AppPolicy(AppAccessMode.ALLOWLIST, setOf(bank)), setOf(bank))
        assertFalse(access.isAllowed(bank))
    }

    @Test
    fun packagesAreComparedExactly() {
        val access = AppAccess(AppAccessMode.ALLOWLIST, setOf(whatsapp), emptySet())
        assertFalse(access.isAllowed("com.whatsapp.w4b"))
        assertFalse(access.isAllowed("COM.WHATSAPP"))
        assertFalse(access.isAllowed(" com.whatsapp"))
        assertFalse(access.isAllowed(""))
    }

    @Test
    fun isRestricted() {
        assertFalse(AppAccess.UNRESTRICTED.isRestricted)
        assertFalse(AppAccess(AppAccessMode.ALL, setOf(whatsapp), emptySet()).isRestricted)
        assertTrue(AppAccess(AppAccessMode.ALL, emptySet(), setOf(bridge)).isRestricted)
        assertTrue(AppAccess(AppAccessMode.ALLOWLIST, setOf(whatsapp), emptySet()).isRestricted)
        assertTrue(AppAccess(AppAccessMode.BLOCKLIST, emptySet(), emptySet()).isRestricted)
    }

    @Test
    fun unrestrictedAllowsEverything() {
        assertTrue(AppAccess.UNRESTRICTED.isAllowed(bank))
        AppAccess.UNRESTRICTED.require(bridge)
    }

    @Test
    fun requireMessageNamesTheAppAndForbidsWorkarounds() {
        val access = AppAccess(AppAccessMode.BLOCKLIST, setOf(whatsapp), setOf(bank))
        val e = assertFailsWith<AppBlockedException> { access.require(whatsapp, "WhatsApp") }
        assertIs<ToolException>(e)
        assertEquals(whatsapp, e.packageName)
        val message = e.message!!
        assertTrue(message.contains("'WhatsApp' ($whatsapp)"), message)
        assertTrue(message.contains("blocked"), message)
        assertTrue(message.contains("for this agent"), message)
        assertTrue(message.contains("Do not try to work around"), message)
        assertTrue(message.contains("allow it for this agent"), message)

        val global = assertFailsWith<AppBlockedException> { access.require(bank, " ") }.message!!
        assertTrue(global.contains("'$bank'"), global)
        assertTrue(global.contains("for this agent"), global)
        assertTrue(global.contains("never-allow"), global)
        assertTrue(global.contains("Do not try to work around"), global)
    }

    @Test
    fun copiesItsInputs() {
        val packages = mutableSetOf(whatsapp)
        val access = AppAccess(AppAccessMode.ALLOWLIST, packages, emptySet())
        packages.clear()
        assertTrue(access.isAllowed(whatsapp))
    }
}
