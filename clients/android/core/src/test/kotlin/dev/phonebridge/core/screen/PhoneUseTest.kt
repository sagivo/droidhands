@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phonebridge.core.screen

import dev.phonebridge.core.auth.AppAccessMode
import dev.phonebridge.core.tools.AppAccess
import dev.phonebridge.core.tools.AppBlockedException
import dev.phonebridge.core.tools.CallContext
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.Tool
import dev.phonebridge.core.tools.ToolContent
import dev.phonebridge.core.tools.ToolException
import dev.phonebridge.core.tools.ToolResult
import dev.phonebridge.core.util.OneTimeCodes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PhoneUseTest {
    private val labels = mapOf(MESSAGES to "Messages", "com.apple.springboard" to "Home Screen")

    private fun TestScope.tools(driver: FakeScreenDriver, marker: ScreenshotMarker = ScalingMarker()): List<Tool> =
        phoneUseTools(driver, { labels[it] }, marker, timing = PhoneUseTiming(clock = { testScheduler.currentTime }))

    private fun ctx(token: String = "agent-a", access: AppAccess = AppAccess.UNRESTRICTED, redact: Boolean = false) =
        CallContext(token, "Claude", Scopes.ALL, "local", "r1", access, redactOneTimeCodes = redact) { }

    private suspend fun List<Tool>.call(name: String, ctx: CallContext, vararg args: Pair<String, Any>): ToolResult {
        val json = JsonObject(args.associate { (k, v) -> k to if (v is String) JsonPrimitive(v) else if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v as Number) })
        return first { it.name == name }.call(json, ctx)
    }

    private fun ToolResult.text(): String = content.filterIsInstance<ToolContent.Text>().joinToString("\n") { it.text }

    private fun refOf(text: String, line: String): String =
        Regex("(n\\d+) ${Regex.escape(line)}").find(text)?.groupValues?.get(1) ?: error("no '$line' in:\n$text")

    @Test
    fun catalogMatchesTheDocs() = runTest {
        val tools = tools(FakeScreenDriver(messagesScreen()))
        assertEquals(
            listOf("screen_observe", "screen_read", "screen_find", "screen_screenshot", "ui_wait", "ui_tap", "ui_type", "ui_scroll", "ui_swipe", "ui_global_action"),
            tools.map { it.name },
        )
        tools.forEach { t ->
            val control = t.name.startsWith("ui_") && t.name != "ui_wait"
            assertEquals(if (control) Scopes.SCREEN_CONTROL else Scopes.SCREEN_READ, t.scope, t.name)
            assertEquals(if (control) Risk.ACTION else Risk.READ, t.risk, t.name)
        }
    }

    @Test
    fun observeListsMarkedElementsWithAScreenshot() = runTest {
        val marker = ScalingMarker()
        val result = tools(FakeScreenDriver(messagesScreen()), marker).call("screen_observe", ctx())
        val text = result.text()
        assertIs<ToolContent.Image>(result.content.first())
        assertTrue(text.startsWith("app=com.apple.MobileSMS (Messages) locked=false"), text)
        assertTrue("Screenshot 590x1280 (screen 393x852 points)" in text, text)
        assertTrue(Regex("n\\d+ Button \"Compose\" \\[clickable] \\(343,55,44,44\\)").containsMatchIn(text), text)
        assertTrue(Regex("n\\d+ SearchField \"Search\" \\[clickable,editable]").containsMatchIn(text), text)
        assertTrue("contains \"Alice | See you soon\"" in text, text)
        assertFalse("Carol" in text, "off-screen elements are not listed:\n$text")
        // Boxes are in screenshot pixels (3x), numbered with the ref.
        val compose = refOf(text, "Button \"Compose\"")
        assertTrue(marker.marks.any { it.label == compose.removePrefix("n") && it.box == Rect(1029, 165, 132, 132) }, marker.marks.toString())
    }

    @Test
    fun refsArePerAgentAndReplacedByEachObservation() = runTest {
        val driver = FakeScreenDriver(messagesScreen())
        val tools = tools(driver)
        val first = refOf(tools.call("screen_observe", ctx()).text(), "Button \"Compose\"")
        val second = refOf(tools.call("screen_observe", ctx()).text(), "Button \"Compose\"")
        assertTrue(second.drop(1).toInt() > first.drop(1).toInt(), "refs are never reused: $first then $second")

        val stale = assertFailsWith<ToolException> { tools.call("ui_tap", ctx(), "ref" to first) }
        assertTrue("Call screen_observe again" in stale.message!!, stale.message)

        tools.call("screen_observe", ctx("agent-b"))
        val foreign = assertFailsWith<ToolException> { tools.call("ui_tap", ctx("agent-b"), "ref" to second) }
        assertTrue("refs only work for the agent that read them" in foreign.message!!, foreign.message)
        assertTrue(driver.calls.isEmpty())

        val tapped = tools.call("ui_tap", ctx(), "ref" to second).text()
        assertEquals(listOf("tap 365,77"), driver.calls)
        assertTrue(tapped.startsWith("Tapped $second Button \"Compose\" at (365,77). Foreground app: com.apple.MobileSMS (Messages)."), tapped)
    }

    @Test
    fun actionsReportWhatChanged() = runTest {
        val driver = FakeScreenDriver(messagesScreen())
        val tools = tools(driver)
        driver.onTap = { _, _ -> driver.screen = messagesScreen(extraText = "New Message", overlays = listOf(alert("com.apple.springboard", "OK"))) }
        val changed = tools.call("ui_tap", ctx(), "text" to "compose").text()
        assertTrue("Screen changed: opened \"Heads up (com.apple.springboard)\"; new text: \"New Message\"" in changed, changed)

        driver.onTap = { _, _ -> }
        val same = tools.call("ui_tap", ctx(), "x" to 200, "y" to 600).text()
        assertTrue(same.startsWith("Tapped at (200,600)."), same)
        assertTrue("The screen did not visibly change" in same, same)

        val observed = tools.call("ui_tap", ctx(), "x" to 200, "y" to 600, "observe" to true)
        assertIs<ToolContent.Image>(observed.content[1])
        assertTrue("Actionable elements" in observed.text())
    }

    @Test
    fun blockedForegroundIsRefused() = runTest {
        val tools = tools(FakeScreenDriver(messagesScreen()))
        val blocked = AppAccess(AppAccessMode.BLOCKLIST, setOf(MESSAGES), emptySet())
        val e = assertFailsWith<AppBlockedException> { tools.call("screen_observe", ctx(access = blocked)) }
        assertEquals(MESSAGES, e.packageName)
        assertFailsWith<AppBlockedException> { tools.call("ui_tap", ctx(access = blocked), "x" to 10, "y" to 10) }
    }

    @Test
    fun blockedOverlaysAreLeftOutAndStopScreenshots() = runTest {
        val driver = FakeScreenDriver(messagesScreen(overlays = listOf(alert("com.evil.app", "Open Evil"), alert("com.apple.springboard", "Allow"))))
        val tools = tools(driver)
        val onlyMessages = AppAccess(AppAccessMode.ALLOWLIST, setOf(MESSAGES), emptySet())
        val text = tools.call("screen_observe", ctx(access = onlyMessages)).text()
        assertFalse("Open Evil" in text, text)
        assertTrue("(1 window(s) of apps blocked for this agent are not shown)" in text, text)
        assertTrue("No screenshot: Can't take a screenshot: a window or notification of a blocked app is visible." in text, text)
        // The home screen's system alert stays usable under an allowlist.
        assertTrue(Regex("n\\d+ Button \"Allow\"").containsMatchIn(text), text)
        assertFailsWith<AppBlockedException> { tools.call("screen_screenshot", ctx(access = onlyMessages)) }
        // A touch on the blocked overlay is refused.
        assertFailsWith<AppBlockedException> { tools.call("ui_tap", ctx(access = onlyMessages), "x" to 100, "y" to 400) }
        assertTrue(driver.calls.isEmpty())
    }

    @Test
    fun screenshotPointsMapToScreenPoints() = runTest {
        val driver = FakeScreenDriver(messagesScreen())
        val tools = tools(driver)
        val noShot = assertFailsWith<ToolException> { tools.call("ui_tap", ctx(), "x" to 10, "y" to 10, "space" to "screenshot") }
        assertTrue("needs a screenshot first" in noShot.message!!)

        tools.call("screen_screenshot", ctx())
        tools.call("ui_tap", ctx(), "x" to 295, "y" to 640, "space" to "screenshot")
        assertEquals(listOf("tap 196,426"), driver.calls)
        val outside = assertFailsWith<ToolException> { tools.call("ui_tap", ctx(), "x" to 600, "y" to 10, "space" to "screenshot") }
        assertTrue("outside your last screenshot (590x1280)" in outside.message!!, outside.message)
        // Each agent has its own last screenshot.
        assertFailsWith<ToolException> { tools.call("ui_tap", ctx("agent-b"), "x" to 10, "y" to 10, "space" to "screenshot") }
    }

    @Test
    fun lockedPhoneRefusesActions() = runTest {
        val driver = FakeScreenDriver(messagesScreen(locked = true))
        val tools = tools(driver)
        val e = assertFailsWith<ToolException> { tools.call("ui_tap", ctx(), "x" to 10, "y" to 10) }
        assertEquals(ScreenTexts.LOCKED, e.message)
        assertEquals("The phone is already locked.", tools.call("ui_global_action", ctx(), "action" to "lock_screen").text())
        assertTrue(driver.calls.isEmpty())
    }

    @Test
    fun oneTimeCodesAreMaskedAndBlockScreenshots() = runTest {
        val tools = tools(FakeScreenDriver(messagesScreen(extraText = "Your verification code is 482913")))
        val read = tools.call("screen_read", ctx(redact = true)).text()
        assertTrue(OneTimeCodes.MASK in read, read)
        assertFalse("482913" in read, read)
        val e = assertFailsWith<ToolException> { tools.call("screen_screenshot", ctx(redact = true)) }
        assertEquals(ScreenTexts.CODE_VISIBLE, e.message)
        val observed = tools.call("screen_observe", ctx(redact = true))
        assertTrue(observed.content.none { it is ToolContent.Image })
        assertTrue("No screenshot: ${ScreenTexts.CODE_VISIBLE}" in observed.text())
        // Without the setting, the code is shown and screenshots work.
        assertTrue("482913" in tools.call("screen_read", ctx()).text())
    }

    @Test
    fun unsupportedGlobalActionsSayWhatToDoInstead() = runTest {
        val driver = FakeScreenDriver(messagesScreen())
        val tools = tools(driver)
        val back = assertFailsWith<ToolException> { tools.call("ui_global_action", ctx(), "action" to "back") }
        assertTrue("iPhones have no system Back button" in back.message!!, back.message)
        assertTrue(back.message!!.endsWith("Supported here: home, lock_screen."), back.message)
        val unknown = assertFailsWith<ToolException> { tools.call("ui_global_action", ctx(), "action" to "fly") }
        assertTrue("Unknown action 'fly'" in unknown.message!!)
        tools.call("ui_global_action", ctx(), "action" to "home")
        assertEquals(listOf("global home"), driver.calls)
    }

    @Test
    fun homeWaitsForTheAppToLeaveBeforeReporting() = runTest {
        val driver = FakeScreenDriver(messagesScreen())
        val tools = tools(driver)
        val home = ScreenSnapshot("com.apple.springboard", PHONE, el("Application", "SpringBoard", Rect(0, 0, 393, 852), children = listOf(
            el("Icon", "Messages", Rect(20, 100, 60, 60), actionable = true),
        )))
        // The Home animation: the next two reads still show Messages (one with a status-bar tick), then the Home Screen.
        driver.onGlobal = {
            driver.upcoming += messagesScreen()
            driver.upcoming += messagesScreen(extraText = "17%")
            driver.screen = home
        }
        val text = tools.call("ui_global_action", ctx(), "action" to "home").text()
        assertTrue("Foreground app: com.apple.springboard" in text, text)
    }

    @Test
    fun typeFocusesTheFieldThenTypesAndSubmits() = runTest {
        val driver = FakeScreenDriver(messagesScreen())
        val tools = tools(driver)
        val search = refOf(tools.call("screen_observe", ctx()).text(), "SearchField \"Search\"")
        driver.onType = { t -> driver.screen = messagesScreen(search = t) }
        val text = tools.call("ui_type", ctx(), "ref" to search, "text" to "Bob", "submit" to true).text()
        // The field only shows its placeholder: nothing to clear.
        assertEquals(listOf("tap 196,118", "type Bob", "key return"), driver.calls)
        assertTrue("Field now shows: \"Bob\". Pressed the keyboard's return key." in text, text)

        driver.calls.clear()
        val ref = refOf(tools.call("screen_observe", ctx()).text(), "SearchField \"Search\"")
        tools.call("ui_type", ctx(), "ref" to ref, "text" to "Al")
        assertEquals(listOf("tap 196,118", "clear", "type Al"), driver.calls)
    }

    @Test
    fun scrollSwipesTheLargestListAgainstTheDirection() = runTest {
        val driver = FakeScreenDriver(messagesScreen())
        val text = tools(driver).call("ui_scroll", ctx(), "direction" to "down").text()
        assertEquals(listOf("swipe 196,668->196,318"), driver.calls)
        assertTrue(Regex("Swiped down inside n\\d+ Table").containsMatchIn(text), text)
        assertTrue("The content did not move" in text, text)
    }

    @Test
    fun findAndWaitGiveRefs() = runTest {
        val tools = tools(FakeScreenDriver(messagesScreen()))
        val found = tools.call("screen_find", ctx(), "text" to "lunch").text()
        assertTrue(found.startsWith("Found 1 element(s) (app=com.apple.MobileSMS):\nn"), found)
        val waited = tools.call("ui_wait", ctx(), "text" to "Bob", "package" to MESSAGES).text()
        assertTrue("\"found\":true" in waited && "\"ref\":\"n" in waited, waited)
        val missing = tools.call("ui_wait", ctx(), "text" to "Zed", "timeout_ms" to 2000).text()
        assertTrue("\"found\":false" in missing, missing)
    }

    @Test
    fun driverErrorsReachTheAgent() = runTest {
        val driver = FakeScreenDriver(messagesScreen())
        driver.failWith = ScreenException("The PhoneBridge runner on the iPhone is not running.")
        val e = assertFailsWith<ToolException> { tools(driver).call("ui_tap", ctx(), "x" to 10, "y" to 10) }
        assertEquals("The PhoneBridge runner on the iPhone is not running.", e.message)
    }
}
