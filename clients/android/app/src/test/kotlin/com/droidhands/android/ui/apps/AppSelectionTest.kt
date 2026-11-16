package com.droidhands.android.ui.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSelectionTest {
    private val apps = listOf(
        AppInfo("com.bank.app", "Bank"),
        AppInfo("com.android.chrome", "Chrome"),
        AppInfo("com.cafe.order", "Café Order"),
        AppInfo("com.whatsapp", "WhatsApp"),
    )

    @Test
    fun sectionsWithSuggestionsAndStaleSelections() {
        val sections = AppSelection.sections(apps, selected = setOf("com.whatsapp", "com.removed"), suggested = setOf("com.bank.app"))
        assertEquals(listOf("suggested", "all", "missing"), sections.map { it.key })
        assertEquals(listOf("Bank"), sections[0].rows.map { it.app.label })
        assertEquals(listOf("Chrome", "Café Order", "WhatsApp"), sections[1].rows.map { it.app.label })
        assertEquals(listOf(true), sections[1].rows.filter { it.app.packageName == "com.whatsapp" }.map { it.selected })
        val missing = sections[2].rows.single()
        assertEquals("com.removed", missing.app.packageName)
        assertFalse(missing.installed)
        assertTrue(missing.selected)
    }

    @Test
    fun withoutSuggestionsTheListIsUntitled() {
        val sections = AppSelection.sections(apps, selected = emptySet())
        assertEquals(1, sections.size)
        assertNull(sections.single().title)
        assertEquals(4, sections.single().rows.size)
    }

    @Test
    fun lockedRowsShowTheGlobalBlock() {
        val allow = AppSelection.sections(apps, selected = setOf("com.bank.app"), locked = setOf("com.bank.app"), lockedSelected = false)
        val bank = allow.single().rows.first { it.app.packageName == "com.bank.app" }
        assertTrue(bank.locked)
        assertFalse(bank.selected)
        val block = AppSelection.sections(apps, selected = emptySet(), locked = setOf("com.bank.app"), lockedSelected = true)
        assertTrue(block.single().rows.first { it.app.packageName == "com.bank.app" }.selected)
    }

    @Test
    fun searchMatchesLabelsWithoutAccentsAndPackages() {
        val byLabel = AppSelection.sections(apps, emptySet(), query = "cafe")
        assertEquals(listOf("Café Order"), byLabel.single().rows.map { it.app.label })
        val byPackage = AppSelection.sections(apps, emptySet(), query = "android.chr")
        assertEquals(listOf("Chrome"), byPackage.single().rows.map { it.app.label })
        val stale = AppSelection.sections(apps, setOf("com.gone.app"), query = "gone")
        assertFalse(stale.single().rows.single().installed)
        assertTrue(AppSelection.matches(apps[0], "  "))
    }

    @Test
    fun summaries() {
        assertEquals("None", AppSelection.summary(emptySet(), apps))
        assertEquals("Bank, WhatsApp", AppSelection.summary(setOf("com.whatsapp", "com.bank.app"), apps))
        assertEquals("Bank, Café Order, Chrome +2", AppSelection.summary(apps.map { it.packageName }.toSet() + "x.unknown", apps))
        assertEquals("cafe", AppSelection.fold(" Café "))
    }
}
