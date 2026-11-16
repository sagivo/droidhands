package com.droidhands.android.ui.setup

import com.droidhands.core.tools.Scopes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupLogicTest {
    private fun item(id: String, status: SetupStatus, essential: Boolean = id in SetupLogic.ESSENTIAL_IDS) =
        SetupItem(id, id, "", "", essential, status)

    @Test
    fun runtimeStatus() {
        assertEquals(SetupStatus.DONE, SetupLogic.runtimeStatus(2, 2))
        assertEquals(SetupStatus.PARTIAL, SetupLogic.runtimeStatus(1, 2))
        assertEquals(SetupStatus.MISSING, SetupLogic.runtimeStatus(0, 2))
        assertEquals(SetupStatus.DONE, SetupLogic.runtimeStatus(0, 0))
    }

    @Test
    fun permanentDenialHeuristic() {
        val perms = listOf("a", "b")
        assertFalse("never asked", SetupLogic.isPermanentlyDenied(perms, emptySet()) { false })
        assertFalse("rationale shown -> can ask again", SetupLogic.isPermanentlyDenied(perms, setOf("a", "b")) { it == "a" })
        assertTrue(SetupLogic.isPermanentlyDenied(perms, setOf("a", "b", "c")) { false })
        assertFalse("partly unasked", SetupLogic.isPermanentlyDenied(perms, setOf("a")) { false })
        assertFalse("nothing missing", SetupLogic.isPermanentlyDenied(emptyList(), setOf("a")) { false })
    }

    @Test
    fun progress() {
        val items = listOf(
            item(SetupLogic.NOTIFICATIONS, SetupStatus.DONE),
            item(SetupLogic.ACCESSIBILITY, SetupStatus.PENDING),
            item(SetupLogic.CONTACTS, SetupStatus.PARTIAL),
            item(SetupLogic.DND, SetupStatus.MISSING),
            item(SetupLogic.OVERLAY, SetupStatus.DONE),
        )
        val p = SetupLogic.progress(items)
        assertEquals(2, p.done)
        assertEquals(5, p.total)
        assertEquals(listOf(SetupLogic.ACCESSIBILITY, SetupLogic.CONTACTS), p.essentialMissing.map { it.id })
        assertFalse(p.essentialComplete)
        assertEquals(0.4f, p.fraction, 0.0001f)
        assertTrue(SetupLogic.progress(emptyList()).essentialComplete)
    }

    @Test
    fun unlocksText() {
        assertEquals(
            "Contacts · Send messages · Phone calls",
            SetupLogic.unlocksForScopes(listOf(Scopes.CONTACTS_READ, Scopes.MESSAGES_SEND, Scopes.CALLS, Scopes.CALLS)),
        )
        assertEquals("custom.scope", SetupLogic.shortLabel("custom.scope"))
        Scopes.ALL.forEach { assertTrue(it, SetupLogic.shortLabel(it) != it) }
    }

    @Test
    fun restrictedSettingsHelp() {
        assertTrue(SetupLogic.showsRestrictedSettingsHelp(SetupLogic.ACCESSIBILITY, SetupStatus.MISSING, 33))
        assertTrue(SetupLogic.showsRestrictedSettingsHelp(SetupLogic.NOTIFICATION_ACCESS, SetupStatus.MISSING, 35))
        assertFalse(SetupLogic.showsRestrictedSettingsHelp(SetupLogic.ACCESSIBILITY, SetupStatus.MISSING, 32))
        assertFalse(SetupLogic.showsRestrictedSettingsHelp(SetupLogic.ACCESSIBILITY, SetupStatus.DONE, 34))
        assertFalse(SetupLogic.showsRestrictedSettingsHelp(SetupLogic.BATTERY, SetupStatus.MISSING, 34))
        // Store installs are never restricted; unknown or file installs may be.
        assertFalse(SetupLogic.showsRestrictedSettingsHelp(SetupLogic.ACCESSIBILITY, SetupStatus.MISSING, 34, SetupLogic.PACKAGE_SOURCE_STORE))
        assertTrue(SetupLogic.showsRestrictedSettingsHelp(SetupLogic.ACCESSIBILITY, SetupStatus.MISSING, 34, 4))
    }

    @Test
    fun statusLabels() {
        SetupStatus.entries.forEach { assertTrue(SetupLogic.statusLabel(it, blocked = false).isNotBlank()) }
        assertEquals("Done", SetupLogic.statusLabel(SetupStatus.DONE, blocked = true))
        assertTrue(SetupLogic.statusLabel(SetupStatus.MISSING, blocked = true).contains("App info"))
    }

    @Test
    fun actionLabels() {
        fun label(id: String, status: SetupStatus, perms: List<String> = emptyList(), blocked: Boolean = false) =
            SetupLogic.actionLabel(SetupItem(id, id, "", "", false, status, perms, blocked))
        assertEquals(null, label(SetupLogic.CONTACTS, SetupStatus.DONE, listOf("p")))
        assertEquals(null, label(SetupLogic.BACKGROUND_LOCATION, SetupStatus.NEEDS_PREREQUISITE, listOf("p")))
        assertEquals("Allow", label(SetupLogic.CONTACTS, SetupStatus.MISSING, listOf("p")))
        assertEquals("Open App info", label(SetupLogic.CONTACTS, SetupStatus.MISSING, listOf("p"), blocked = true))
        assertEquals("Allow precise", label(SetupLogic.LOCATION, SetupStatus.PARTIAL, listOf("p", "q")))
        assertEquals("Allow the rest", label("phone", SetupStatus.PARTIAL, listOf("p", "q")))
        assertEquals("Allow all the time", label(SetupLogic.BACKGROUND_LOCATION, SetupStatus.MISSING, listOf("p")))
        assertEquals("Open settings", label(SetupLogic.NOTIFICATIONS, SetupStatus.MISSING))
        assertEquals("Open Accessibility", label(SetupLogic.ACCESSIBILITY, SetupStatus.PENDING))
        assertEquals("Allow", label(SetupLogic.BATTERY, SetupStatus.MISSING))
        assertEquals("Open settings", label(SetupLogic.DND, SetupStatus.MISSING))
    }
}
