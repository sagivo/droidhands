package com.droidhands.android.ui.apps

import android.content.pm.ApplicationInfo
import com.droidhands.android.ui.apps.AppTestData.action
import com.droidhands.android.ui.apps.AppTestData.listing
import com.droidhands.core.plugins.Integration
import com.droidhands.core.plugins.PluginStatus
import com.droidhands.core.tools.Risk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppsPresentationTest {
    private val spotify = listing(
        "com.spotify.music", "Spotify", Integration.PLUGIN,
        action("com.spotify.music", "play", "Play music"), action("com.spotify.music", "search", "Search"),
        status = PluginStatus.EXPERIMENTAL,
    )
    private val maps = listing("com.google.android.apps.maps", "Maps", Integration.PLUGIN, action("com.google.android.apps.maps", "navigate", "Navigate"), status = PluginStatus.STABLE)
    private val notes = listing("com.example.notes", "Notes", Integration.LOCAL, action("com.example.notes", "new_note", "New note", unreviewed = true))
    private val cafe = listing("com.example.cafe", "Café", Integration.BASIC, action("com.example.cafe", "open", "Open the app"))
    private val calc = listing("com.example.calc", "Calculator", Integration.BASIC)
    private val all = listOf(calc, cafe, maps, notes, spotify)

    @Test
    fun badgesSayWhatKindOfSupportAnAppHas() {
        assertEquals(AppBadge("Integration · 2 actions", BadgeKind.INTEGRATION, "Experimental"), AppsPresentation.badge(spotify))
        assertEquals(AppBadge("Integration · 1 action", BadgeKind.INTEGRATION, null), AppsPresentation.badge(maps))
        assertEquals(AppBadge("Local plugin · 1 action", BadgeKind.LOCAL), AppsPresentation.badge(notes))
        assertEquals(AppBadge("Basic · 1 action", BadgeKind.BASIC), AppsPresentation.badge(cafe))
        assertEquals("Basic · 0 actions", AppsPresentation.badge(calc).text)
        assertEquals("Beta", AppsPresentation.statusLabel(PluginStatus.BETA))
    }

    @Test
    fun integrationsComeFirstAndSearchCoversActions() {
        val sections = AppsPresentation.sections(all, blocked = setOf("com.example.cafe"))
        assertEquals(listOf("Integrations", "All apps"), sections.map { it.title })
        assertEquals(listOf("Maps", "Notes", "Spotify"), sections[0].rows.map { it.app.label })
        assertEquals(listOf("Calculator", "Café"), sections[1].rows.map { it.app.label })
        assertTrue(sections[1].rows.first { it.app.label == "Café" }.blocked)

        fun search(q: String) = AppsPresentation.sections(all, emptySet(), q).single().rows.map { it.app.label }
        assertEquals(listOf("Spotify"), search("play"))
        assertEquals(listOf("Café"), search("CAFE"))
        assertEquals(listOf("Maps"), search("apps.maps"))
        assertEquals(emptyList<String>(), search("nothing like it"))
        assertEquals("Results", AppsPresentation.sections(all, emptySet(), "x").single().title)

        val basicOnly = AppsPresentation.sections(listOf(calc, cafe), emptySet())
        assertEquals(listOf<String?>(null), basicOnly.map { it.title })
    }

    @Test
    fun summaryCountsWhatAgentsCanUse() {
        assertEquals("Agents can use 5 actions in 5 apps, 3 with integrations.", AppsPresentation.summary(all, emptySet()))
        assertEquals(
            "Agents can use 3 actions in 3 apps, 1 with an integration.",
            AppsPresentation.summary(all, setOf("com.google.android.apps.maps", "com.example.notes")),
        )
        assertEquals("Agents can use 0 actions in 1 app.", AppsPresentation.summary(listOf(calc), emptySet()))
    }

    @Test
    fun notesExplainIntegrationsAndApprovals() {
        assertTrue(AppsPresentation.integrationNote(spotify).contains("experimental"))
        assertFalse(AppsPresentation.integrationNote(maps).contains("experimental"))
        assertTrue(AppsPresentation.integrationNote(notes).contains("asks for your approval"))
        assertTrue(AppsPresentation.integrationNote(cafe).contains("DroidHands found"))

        val plain = action("p.q", "play")
        assertNull(AppsPresentation.approvalNote(plain))
        assertFalse(AppsPresentation.needsApproval(plain))
        val sensitive = action("p.q", "order", risk = Risk.SENSITIVE)
        // Inside an agent's access nothing asks, unless the user turned on "Ask me before it sends messages, calls or pays".
        assertFalse(AppsPresentation.needsApproval(sensitive))
        assertEquals(AppsPresentation.ASKS_IF_SWITCH_ON, AppsPresentation.approvalNote(sensitive))
        val local = action("p.q", "x", unreviewed = true)
        assertTrue(AppsPresentation.needsApproval(local))
        assertTrue(AppsPresentation.approvalNote(local)!!.contains("unreviewed"))
        val viaTool = action("com.whatsapp", "send_message", risk = Risk.SENSITIVE, builtInTool = "sms_send")
        assertTrue(AppsPresentation.approvalNote(viaTool)!!.contains("sms_send tool"))
        assertEquals("Sensitive", AppsPresentation.riskLabel(Risk.SENSITIVE))
    }

    @Test
    fun parametersReadAsOneLine() {
        assertEquals("query (required): What to play", AppsPresentation.paramText(ActionParamInfo("query", "What to play", "string", true)))
        assertEquals("minutes (integer): Timer length", AppsPresentation.paramText(ActionParamInfo("minutes", "Timer length", "integer", false)))
        assertEquals("note: A note", AppsPresentation.paramText(ActionParamInfo("note", "A note", "string", false)))
    }

    @Test
    fun integrationRequestsPrefillTheIssueForm() {
        assertEquals(
            "https://github.com/droidhands/droidhands/issues/new?template=app-integration.yml&title=App%20integration%3A%20Caf%C3%A9%20%26%20Co" +
                "&app=Caf%C3%A9%20%26%20Co&package=com.example.cafe",
            AppsPresentation.integrationRequestUrl("droidhands/droidhands", "Café & Co", "com.example.cafe"),
        )
        assertNull(AppsPresentation.integrationRequestUrl("", "X", "com.x"))
        assertNull(AppsPresentation.integrationRequestUrl("not a repo", "X", "com.x"))
        assertNull(AppsPresentation.integrationRequestUrl("../evil/x", "X", "com.x"))
    }

    @Test
    fun draftCategoriesComeFromAndroidOrFromWhatTheAppHandles() {
        assertEquals("music", AppsPresentation.draftCategory(ApplicationInfo.CATEGORY_AUDIO, emptyList()))
        assertEquals("maps", AppsPresentation.draftCategory(ApplicationInfo.CATEGORY_MAPS, emptyList()))
        assertEquals("productivity", AppsPresentation.draftCategory(ApplicationInfo.CATEGORY_PRODUCTIVITY, listOf("play_from_search")))
        assertEquals("music", AppsPresentation.draftCategory(ApplicationInfo.CATEGORY_UNDEFINED, listOf("open", "play_from_search")))
        assertEquals("maps", AppsPresentation.draftCategory(ApplicationInfo.CATEGORY_GAME, listOf("show_on_map")))
        assertEquals("other", AppsPresentation.draftCategory(ApplicationInfo.CATEGORY_UNDEFINED, listOf("open")))
        assertEquals("DroidHands plugin draft for Spotify (plugin.yaml)", AppsPresentation.draftSubject("Spotify"))
    }

    @Test
    fun noActionsLineFollowsTheEdition() {
        assertTrue(AppsPresentation.noActionsLine("Maps", phoneUse = true).contains("screen tools"))
        assertEquals("Agents can still open Maps, but can't act inside it.", AppsPresentation.noActionsLine("Maps", phoneUse = false))
    }
}
