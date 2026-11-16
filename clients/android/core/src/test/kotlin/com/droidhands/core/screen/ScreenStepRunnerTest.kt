@file:OptIn(ExperimentalCoroutinesApi::class)

package com.droidhands.core.screen

import com.droidhands.core.plugins.InstalledApp
import com.droidhands.core.plugins.IntegrationException
import com.droidhands.core.plugins.RenderedStep
import com.droidhands.core.plugins.RenderedStepAction
import com.droidhands.core.plugins.RenderedSteps
import com.droidhands.core.plugins.Selector
import com.droidhands.core.tools.CallContext
import com.droidhands.core.tools.Scopes
import com.droidhands.core.tools.ToolException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenStepRunnerTest {
    private val chat = InstalledApp("com.example.chat", "Chat")
    private val ctx = CallContext("agent-a", "Claude", Scopes.ALL, "local", "r1") { }

    private val home = ScreenSnapshot("com.apple.springboard", PHONE, el("Application", "Home", Rect(0, 0, 393, 852)))

    private val chatList = ScreenSnapshot(
        chat.packageName, PHONE,
        el("Application", "Chat", Rect(0, 0, 393, 852), children = listOf(
            el("Button", "New chat", Rect(300, 55, 80, 44), actionable = true),
            el("StaticText", "Chats", Rect(16, 55, 200, 30)),
        )),
    )

    private val compose = ScreenSnapshot(
        chat.packageName, PHONE,
        el("Application", "Chat", Rect(0, 0, 393, 852), children = listOf(
            el("TextField", "To", Rect(16, 120, 361, 40), identifier = "to", placeholder = "Name", value = "Name", editable = true, actionable = true),
            el("Button", "Send", Rect(300, 800, 80, 40), actionable = true),
        )),
    )

    private val otherApp = ScreenSnapshot("com.other", PHONE, el("Application", "Other", Rect(0, 0, 393, 852)))

    private fun TestScope.runner(driver: FakeScreenDriver) =
        ScreenStepRunner(driver, { if (it == chat.packageName) "Chat" else null }, timing = PhoneUseTiming(clock = { testScheduler.currentTime }))

    private fun steps(vararg s: RenderedStep) = RenderedSteps(chat.packageName, s.toList())

    private fun driver(): FakeScreenDriver = FakeScreenDriver(home).apply {
        onLaunch = { screen = chatList }
        onTap = { _, y -> if (screen === chatList && y < 100) screen = compose }
    }

    @Test
    fun runsARecipeInsideTheApp() = runTest {
        val driver = driver()
        val result = runner(driver).run(
            steps(
                RenderedStep(RenderedStepAction.Launch()),
                RenderedStep(RenderedStepAction.Tap(Selector(textOrDesc = "new chat"))),
                RenderedStep(RenderedStepAction.TypeText(Selector(id = "com.example.chat:id/to"), "Alice", submit = true)),
                RenderedStep(RenderedStepAction.Tap(Selector(text = "Missing")), optional = true, timeoutMs = 1_000),
            ),
            chat, ctx,
        )
        assertEquals(listOf("launch com.example.chat", "tap 340,77", "tap 196,140", "type Alice", "key return"), driver.calls)
        assertEquals(3, result.fields["steps_run"]!!.jsonPrimitive.int)
        assertEquals(JsonArray(listOf(JsonPrimitive("4"))), result.fields["optional_steps_skipped"])
        assertTrue(result.note!!.startsWith("Ran 3 steps in Chat (optional step 4 skipped). Foreground app: com.example.chat (Chat)."), result.note)
    }

    @Test
    fun stopsWhenAnotherAppTakesTheForeground() = runTest {
        val driver = driver()
        driver.onTap = { _, _ -> driver.screen = otherApp }
        val e = assertFailsWith<ToolException> {
            runner(driver).run(
                steps(
                    RenderedStep(RenderedStepAction.Launch()),
                    RenderedStep(RenderedStepAction.Tap(Selector(textOrDesc = "New chat"))),
                    RenderedStep(RenderedStepAction.Tap(Selector(textOrDesc = "Send")), optional = true),
                ),
                chat, ctx,
            )
        }
        // Not the app's fault: not recorded as a broken recipe, and never skipped as optional.
        assertFalse(e is IntegrationException, e.toString())
        assertTrue(e.message!!.startsWith("Stopped before step 3 (tap {\"Send\"}): com.other is in the foreground instead of Chat"), e.message)
        assertEquals(listOf("launch com.example.chat", "tap 340,77"), driver.calls)
    }

    @Test
    fun aMissingElementNamesTheStep() = runTest {
        val driver = driver()
        val e = assertFailsWith<IntegrationException> {
            runner(driver).run(steps(RenderedStep(RenderedStepAction.Launch()), RenderedStep(RenderedStepAction.Tap(Selector(desc = "Nope")))), chat, ctx)
        }
        assertEquals(
            "Step 2 of 2 (tap {desc: \"Nope\"}) failed: {desc: \"Nope\"} did not show up in Chat within 5 s. Chat shows: Chat | New chat | Chats. " +
                "1 earlier step ran, so Chat may be partway through the task: check with screen_observe before retrying.",
            e.message,
        )
    }

    @Test
    fun backAndIntentsSayWhyTheyCantRun() = runTest {
        val e = assertFailsWith<IntegrationException> {
            runner(driver()).run(steps(RenderedStep(RenderedStepAction.Launch()), RenderedStep(RenderedStepAction.Back)), chat, ctx)
        }
        assertTrue("ios has no system Back action; the recipe must tap the app's own back button instead" in e.message!!, e.message)
    }

    @Test
    fun refusesWhileLocked() = runTest {
        val driver = FakeScreenDriver(ScreenSnapshot(chat.packageName, PHONE, null, locked = true))
        val e = assertFailsWith<ToolException> { runner(driver).run(steps(RenderedStep(RenderedStepAction.Launch())), chat, ctx) }
        assertTrue("unlock" in e.message!!, e.message)
        assertTrue(driver.calls.isEmpty())
    }
}
