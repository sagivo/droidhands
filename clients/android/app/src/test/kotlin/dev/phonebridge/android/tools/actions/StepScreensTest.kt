package dev.phonebridge.android.tools.actions

import dev.phonebridge.android.access.UiNode
import dev.phonebridge.android.access.WindowKind
import dev.phonebridge.android.access.WindowSnapshot
import dev.phonebridge.android.access.appWindow
import dev.phonebridge.android.access.button
import dev.phonebridge.android.access.group
import dev.phonebridge.android.access.label
import dev.phonebridge.android.access.rect
import dev.phonebridge.core.plugins.RecipeRenderer
import dev.phonebridge.core.plugins.RenderedStep
import dev.phonebridge.core.plugins.RenderedStepAction
import dev.phonebridge.core.plugins.RenderedUrl
import dev.phonebridge.core.plugins.ScrollDirection
import dev.phonebridge.core.plugins.Selector
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StepScreensTest {
    private val pkg = "dev.phonebridge.fixture.mail"

    private val to = UiNode(viewId = "$pkg:id/to", className = "android.widget.EditText", packageName = pkg, editable = true, hint = "To")
    private val subject = UiNode(viewId = "$pkg:id/subject", className = "android.widget.EditText", packageName = pkg, editable = true)
    private val send = button(desc = "Send", id = "$pkg:id/send", pkg = pkg)
    private val title = label("Compose", pkg = pkg, id = "$pkg:id/compose_title")
    private val hidden = UiNode(text = "Secret", viewId = "$pkg:id/hidden", packageName = pkg, visible = false)
    private val ok = button(text = "OK", id = "android:id/button1", pkg = pkg)
    private val first = label("Re: Lunch on Thursday?", pkg = pkg)
    private val second = label("Re: Project update", pkg = pkg)
    private val root = group(title, send, to, subject, hidden, ok, first, second, pkg = pkg)
    private val roots = listOf(root)

    private fun find(selector: Selector): UiNode? = SelectorMatcher.find(roots, selector)

    @Test
    fun matchesByIdTextAndDescription() {
        assertSame(send, find(Selector(id = "$pkg:id/send")))
        assertSame(send, find(Selector(desc = "send")))
        assertSame(send, find(Selector(textOrDesc = "Send")))
        assertSame(send, find(Selector(descContains = "EN")))
        assertSame(title, find(Selector(text = "  compose ")))
        assertSame(title, find(Selector(textOrDesc = "Compose")))
        assertSame(first, find(Selector(textContains = "re:")))
        assertSame(second, find(Selector(textContains = "re:", index = 1)))
        assertNull(find(Selector(textContains = "re:", index = 2)))
        assertSame(ok, find(Selector(id = "android:id/button1")))
        assertNull("ids are full after rendering", find(Selector(id = "send")))
        assertNull("only visible elements match", find(Selector(text = "Secret")))
        assertNull(find(Selector(text = "Comp")))
    }

    @Test
    fun matchesByClassAndClickableAndEveryCriterion() {
        assertSame(to, find(Selector(className = "EditText")))
        assertSame(subject, find(Selector(className = "edittext", index = 1)))
        assertSame(to, find(Selector(className = "android.widget.EditText")))
        assertNull(find(Selector(className = "widget.EditText")))
        assertSame(send, find(Selector(clickable = true)))
        assertSame(title, find(Selector(clickable = false, text = "Compose")))
        assertNull(find(Selector(id = "$pkg:id/send", text = "Send")))
        assertSame(send, find(Selector(id = "$pkg:id/send", clickable = true)))
    }

    @Test
    fun emptyCriteriaMatchNothing() {
        assertNull("a selector with only an index", find(Selector(index = 0)))
        assertNull("a text that rendered empty", find(Selector(text = "")))
        assertNull(find(Selector(textContains = " ")))
        assertNull(find(Selector(id = "$pkg:id/send", descContains = "")))
    }

    @Test
    fun renderedShortIdsMatchTheInstalledPackagesViews() {
        val rendered = RecipeRenderer(pkg).selector(Selector(id = "send"), JsonObject(emptyMap()))
        assertSame(send, find(rendered))
        val templated = RecipeRenderer(pkg).selector(Selector(textContains = "{{who}}"), JsonObject(mapOf("who" to JsonPrimitive("project"))))
        assertSame(second, find(templated))
        val missing = RecipeRenderer(pkg).selector(Selector(textContains = "{{who}}"), JsonObject(emptyMap()))
        assertNull("a missing optional value never matches everything", find(missing))
    }

    @Test
    fun onlyTheAppsOwnWindowsAreSearched() {
        val other = group(button(text = "Send", id = "com.other:id/send", pkg = "com.other"), pkg = "com.other")
        val keyboard = WindowSnapshot(null, WindowKind.INPUT_METHOD, "com.google.android.inputmethod.latin", rect(0, 1600, 1080, 2400), false, label("Send", pkg = "com.google.android.inputmethod.latin"))
        val own = WindowSnapshot(null, WindowKind.APPLICATION, "dev.phonebridge.android", rect(0, 0, 1, 1), false, null, hidden = true)
        val windows = listOf(appWindow(other, pkg = "com.other"), appWindow(root, pkg = pkg, primary = false), keyboard, own)
        val appRoots = StepScreens.appRoots(windows, pkg)
        assertEquals(listOf(root), appRoots)
        assertSame(send, SelectorMatcher.find(appRoots, Selector(textOrDesc = "Send")))
        assertTrue(StepScreens.appRoots(windows, "com.missing").isEmpty())
    }

    @Test
    fun confinement() {
        assertEquals(Confinement.Inside, StepScreens.confinement(pkg, pkg))
        assertEquals(Confinement.Outside("com.other"), StepScreens.confinement("com.other", pkg))
        assertEquals(Confinement.Outside(null), StepScreens.confinement(null, pkg))

        val message = StepScreens.confinementMessage("step 3 (tap {desc: \"Send\"})", "Fixture Mail", Confinement.Outside("com.other"), "Other", own = false)
        assertTrue(message, message.startsWith("Stopped before step 3 (tap {desc: \"Send\"}): Other (com.other) is in the foreground instead of Fixture Mail"))
        assertTrue(message.contains("Nothing was done there"))
        val own = StepScreens.confinementMessage("step 1 (back)", "Mail", Confinement.Outside("dev.phonebridge.android"), "PhoneBridge", own = true)
        assertTrue(own, own.contains("PhoneBridge's own screen is in the foreground"))
        val unknown = StepScreens.confinementMessage("step 1 (back)", "Mail", Confinement.Outside(null), null, own = false)
        assertTrue(unknown, unknown.contains("could not tell which app"))
    }

    @Test
    fun countsDescribesAndStarts() {
        val tap = RenderedStep(RenderedStepAction.Tap(Selector(id = "$pkg:id/send")))
        val nested = RenderedStep(RenderedStepAction.IfPresent(Selector(text = "Not now"), listOf(tap, tap)))
        val steps = listOf(RenderedStep(RenderedStepAction.Launch(fresh = true)), nested, tap)
        assertEquals(5, StepScreens.count(steps))
        assertTrue(StepScreens.startsByOpening(steps))
        assertTrue(StepScreens.startsByOpening(listOf(RenderedStep(RenderedStepAction.OpenUrl(RenderedUrl("https://x", pkg))))))
        assertFalse(StepScreens.startsByOpening(listOf(tap)))
        assertFalse(StepScreens.startsByOpening(emptyList()))

        assertEquals("launch {fresh: true}", StepScreens.describe(RenderedStepAction.Launch(fresh = true)))
        assertEquals("tap {id: send}", StepScreens.describe(tap.action))
        assertEquals("if_present {text: \"Not now\"}", StepScreens.describe(nested.action))
        assertEquals("type into {id: to}", StepScreens.describe(RenderedStepAction.TypeText(Selector(id = "$pkg:id/to"), "secret text")))
        assertEquals("type into the focused field", StepScreens.describe(RenderedStepAction.TypeText(null, "x")))
        assertEquals("open_url https:…", StepScreens.describe(RenderedStepAction.OpenUrl(RenderedUrl("https://example.com/?q=private", pkg))))
        assertEquals(
            "scroll down until {\"Settings\", index: 1}",
            StepScreens.describe(RenderedStepAction.Scroll(ScrollDirection.DOWN, until = Selector(textOrDesc = "Settings", index = 1))),
        )
    }
}
