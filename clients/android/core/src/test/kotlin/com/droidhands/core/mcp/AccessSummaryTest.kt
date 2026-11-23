package com.droidhands.core.mcp

import com.droidhands.core.auth.Ability
import com.droidhands.core.auth.AppPolicy
import com.droidhands.core.tools.AppAccess
import com.droidhands.core.tools.Scopes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Devices without phone use (the Android store edition) describe access without the screen abilities. */
class AccessSummaryTest {
    private fun facts(scopes: Set<String>, phoneUse: Boolean) =
        AccessSummary.Facts("Claude", scopes, AppAccess.of(AppPolicy(), emptySet()), null, null, emptyMap(), phoneUse)

    @Test
    fun withPhoneUseEveryAbilityCounts() {
        val f = facts(Scopes.ALL, phoneUse = true)
        assertEquals(Ability.entries, f.abilities)
        assertTrue("use apps (open them, run their actions, tap and type)" in AccessSummary.json(f).toString())
    }

    @Test
    fun withoutPhoneUseTheScreenAbilitiesAreNotOffered() {
        val all = facts(Scopes.ALL - Scopes.PHONE_USE, phoneUse = false)
        assertEquals(Ability.entries - Ability.SEE_SCREEN, all.abilities, "apps without screen.control is the whole of Use apps here")
        assertTrue(all.partial.isEmpty() && all.missing.isEmpty())
        val text = AccessSummary.json(all).toString()
        assertTrue("see the screen" !in text && "tap and type" !in text, text)
        assertTrue("use apps (open them and run their actions)" in text, text)

        val none = facts(emptySet(), phoneUse = false)
        assertTrue(Ability.SEE_SCREEN !in none.missing)
        assertEquals(Ability.offered(false), none.missing)
    }
}
