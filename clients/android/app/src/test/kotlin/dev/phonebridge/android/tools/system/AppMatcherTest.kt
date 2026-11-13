package dev.phonebridge.android.tools.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMatcherTest {
    private fun app(pkg: String, label: String) = AppEntry(pkg, label, "1.0", system = false)

    private val apps = listOf(
        app("com.whatsapp", "WhatsApp"),
        app("com.whatsapp.w4b", "WhatsApp Business"),
        app("com.google.android.apps.maps", "Maps"),
        app("com.android.chrome", "Chrome"),
        app("com.chrome.beta", "Chrome Beta"),
        app("com.spotify.music", "Spotify"),
        app("com.google.android.youtube", "YouTube"),
        app("com.google.android.apps.youtube.music", "YouTube Music"),
        app("org.telegram.messenger", "Telegram"),
        app("com.example.cafe", "Café Finder"),
        app("com.android.settings", "Settings"),
    )

    private fun found(query: String): String = (AppMatcher.match(query, apps) as AppMatch.Found).app.packageName

    @Test
    fun `exact label wins over prefix matches`() {
        assertEquals("com.whatsapp", found("whatsapp"))
        assertEquals("com.android.chrome", found("CHROME"))
        assertEquals("com.google.android.youtube", found("youtube"))
    }

    @Test
    fun `exact package name matches`() {
        assertEquals("com.whatsapp.w4b", found("com.whatsapp.w4b"))
    }

    @Test
    fun `prefix and contains tiers`() {
        assertEquals("com.whatsapp.w4b", found("whatsapp bus"))
        assertEquals("com.spotify.music", found("spot"))
        assertEquals("com.whatsapp.w4b", found("business"))
    }

    @Test
    fun `accents and punctuation are ignored`() {
        assertEquals("com.example.cafe", found("cafe finder"))
        assertEquals("com.example.cafe", found("Café-Finder"))
    }

    @Test
    fun `query words can match package segments`() {
        assertEquals("com.google.android.apps.maps", found("google maps"))
    }

    @Test
    fun `typos are tolerated`() {
        assertEquals("org.telegram.messenger", found("telegarm"))
        assertEquals("com.spotify.music", found("spotfy"))
        assertEquals("com.android.settings", found("setings"))
    }

    @Test
    fun `ambiguous prefix returns all candidates`() {
        val m = AppMatcher.match("chrom", apps) as AppMatch.Ambiguous
        assertEquals(listOf("Chrome", "Chrome Beta"), m.candidates.map { it.label })
    }

    @Test
    fun `duplicate exact labels are ambiguous`() {
        val dupes = apps + app("com.samsung.android.settings", "Settings")
        val m = AppMatcher.match("settings", dupes) as AppMatch.Ambiguous
        assertEquals(2, m.candidates.size)
    }

    @Test
    fun `no match gives suggestions and short queries are not fuzzed`() {
        val m = AppMatcher.match("instagram", apps)
        assertTrue(m is AppMatch.NotFound)
        assertTrue((m as AppMatch.NotFound).suggestions.isNotEmpty())
        assertTrue(AppMatcher.match("xyz", apps) is AppMatch.NotFound)
        assertTrue(AppMatcher.match("  ", apps) is AppMatch.NotFound)
    }

    @Test
    fun `edit distance counts transpositions once`() {
        assertEquals(0, AppMatcher.editDistance("abc", "abc"))
        assertEquals(1, AppMatcher.editDistance("abc", "acb"))
        assertEquals(1, AppMatcher.editDistance("abc", "abcd"))
        assertEquals(3, AppMatcher.editDistance("", "abc"))
        assertEquals(2, AppMatcher.editDistance("kitten", "sitten") + AppMatcher.editDistance("sitten", "sittin"))
    }
}
