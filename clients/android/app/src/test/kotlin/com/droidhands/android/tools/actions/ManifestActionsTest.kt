package com.droidhands.android.tools.actions

import com.droidhands.core.plugins.IntentRecipe
import com.droidhands.core.plugins.ToolRecipe
import com.droidhands.core.plugins.ValueSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `manifests/notes-manifest.bin` is a real compiled manifest (aapt2 link, API 35) of a made-up app `com.example.notes`:
 * a launcher; `.LinkActivity` (label `@string/search_label`) opening `https://notes.example.com/n/…`,
 * `https://notes.example.com/share` and `notes:` links; `.compose.ComposeActivity` handling SENDTO `mailto:` and
 * `smsto:`; an unexported `.Internal` (`internal:`), a permission-guarded `.Guarded` (`guarded:`), and a disabled
 * `<activity-alias .Browser>` for any `http:` link.
 */
class ManifestActionsTest {
    private val manifest: DeclaredManifest by lazy {
        val bytes = javaClass.classLoader!!.getResourceAsStream("manifests/notes-manifest.bin")!!.use { it.readBytes() }
        ManifestXml.parse(BinaryXml.decode(bytes)) { id -> if (id == 0x7f010000) "Search notes" else null }
    }

    @Test
    fun `decodes activities, aliases and filters from a compiled manifest`() {
        assertEquals("com.example.notes", manifest.packageName)
        assertEquals(
            listOf(
                "com.example.notes.MainActivity", "com.example.notes.LinkActivity", "com.example.notes.compose.ComposeActivity",
                "com.example.notes.Internal", "com.example.notes.Guarded", "com.example.notes.Browser",
            ),
            manifest.activities.map { it.className },
        )
        val link = manifest.activities[1]
        assertEquals("Search notes", link.label)
        assertEquals(true, link.exported)
        assertEquals(listOf("https"), link.filters[0].schemes)
        assertEquals(listOf("notes.example.com"), link.filters[0].hosts)
        assertEquals(listOf("/n/*", "/share"), link.filters[0].paths)
        assertEquals(listOf("mailto", "smsto"), manifest.activities[2].filters[0].schemes)
        assertEquals(false, manifest.activities[3].exported)
        assertEquals("com.example.PRIVATE", manifest.activities[4].permission)
        // No android:exported but a filter: exported, as Android decides it.
        assertNull(manifest.activities[4].exported)
        assertTrue(manifest.activities[4].effectivelyExported)
        assertFalse(manifest.activities[5].enabled)
    }

    @Test
    fun `open_link lists the links the startable activities declare and runs url_open pinned to the app`() {
        val actions = DeclaredActions.build("Notes", "com.example.notes", manifest) { true }
        val open = actions.single { it.id == DeclaredActions.OPEN_LINK }
        assertTrue(open.description, open.description.contains("https://notes.example.com/n/*, https://notes.example.com/share, notes:*"))
        // Unexported, guarded and disabled activities don't count.
        assertFalse(open.description.contains("internal"))
        assertFalse(open.description.contains("guarded"))
        assertFalse(open.description.contains("any http"))
        val recipe = open.android as ToolRecipe
        assertEquals("url_open", recipe.name)
        assertEquals(ValueSpec.Template("com.example.notes"), recipe.args["package"])
        assertNull(open.risk)
    }

    @Test
    fun `compose actions come from SENDTO schemes`() {
        val actions = DeclaredActions.build("Notes", "com.example.notes", manifest) { true }
        val mail = actions.single { it.id == "compose_mailto" }
        assertEquals("Write an email", mail.title)
        assertEquals(listOf("to", "subject", "text"), mail.params.map { it.name })
        val recipe = mail.android as IntentRecipe
        assertEquals("mailto:{{to}}", recipe.data)
        assertEquals("com.example.notes.compose.ComposeActivity", recipe.className)
        val sms = actions.single { it.id == "compose_smsto" }
        assertTrue((sms.android as IntentRecipe).extras.containsKey(DeclaredActions.EXTRA_SMS_BODY))
    }

    @Test
    fun `activities Android won't let other apps start are left out`() {
        val actions = DeclaredActions.build("Notes", "com.example.notes", manifest) { it.endsWith("ComposeActivity") }
        assertEquals(listOf("compose_mailto", "compose_smsto"), actions.map { it.id })
    }

    @Test
    fun `a browser opens any web link`() {
        val browser = DeclaredActivity(
            "com.example.browser.Main",
            exported = true,
            filters = listOf(DeclaredFilter(listOf("android.intent.action.VIEW"), listOf(DeclaredActions.CATEGORY_BROWSABLE), listOf("http", "https"))),
        )
        val forms = DeclaredActions.linkPatterns(listOf(browser))
        assertTrue(forms.anyWebLink)
        assertTrue(DeclaredActions.openLink("Browser", "com.example.browser", listOf(browser))!!.description.contains("any http(s) web link"))
    }

    @Test
    fun `forbidden schemes are never offered`() {
        val bad = DeclaredActivity(
            "a.B",
            exported = true,
            filters = listOf(DeclaredFilter(listOf("android.intent.action.VIEW"), listOf(DeclaredActions.CATEGORY_DEFAULT), listOf("file", "content", "javascript"))),
        )
        assertNull(DeclaredActions.openLink("X", "a", listOf(bad)))
    }

    @Test
    fun `malformed binary XML is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { BinaryXml.decode(ByteArray(16)) }
        assertThrows(IllegalArgumentException::class.java) { BinaryXml.decode("<manifest/>".toByteArray()) }
    }
}
