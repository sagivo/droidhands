package dev.phonebridge.core.plugins

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [ActionText.recipeSummary] and the approval prompts of local plugins: they show what the recipe will really do. */
class ActionTextTest {
    private val pkg = "com.chat"

    private fun step(action: RenderedStepAction) = RenderedStep(action)

    @Test
    fun screenRecipesListTheStepsThatAct() {
        val steps = RenderedSteps(
            pkg,
            listOf(
                step(RenderedStepAction.Launch()),
                step(RenderedStepAction.Tap(Selector(desc = "Search"))),
                step(RenderedStepAction.TypeText(Selector(id = "$pkg:id/query"), "Tania", submit = true)),
                step(RenderedStepAction.WaitFor(Selector(text = "Tania"))),
                step(
                    RenderedStepAction.IfPresent(
                        Selector(text = "Not now"),
                        listOf(step(RenderedStepAction.Tap(Selector(textOrDesc = "Not now")))),
                    ),
                ),
                step(RenderedStepAction.LongPress(Selector(id = "$pkg:id/message"))),
                step(RenderedStepAction.TypeText(null, "x".repeat(100))),
                step(RenderedStepAction.OpenUrl(RenderedUrl("https://chat.example/t", pkg))),
                step(RenderedStepAction.Back),
            ),
        )
        assertEquals(
            "in the app: taps \"Search\", types \"Tania\" and submits, taps \"Not now\", long-presses message, " +
                "types \"${"x".repeat(59)}…\", opens https://chat.example/t",
            ActionText.recipeSummary(steps),
        )
        val passive = RenderedSteps(pkg, listOf(step(RenderedStepAction.Launch()), step(RenderedStepAction.Sleep(500))))
        assertEquals("runs 2 screen steps in the app", ActionText.recipeSummary(passive))
    }

    @Test
    fun intentsShowTheirExtraValues() {
        val intent = RenderedIntent(
            "android.intent.action.SEND",
            pkg,
            extras = mapOf(
                "android.intent.extra.TEXT" to ExtraValue.StringValue("Send money to " + "y".repeat(60)),
                "count" to ExtraValue.IntValue(3),
            ),
        )
        assertEquals(
            "starts android.intent.action.SEND with android.intent.extra.TEXT=\"Send money to ${"y".repeat(24)}…, count=3",
            ActionText.recipeSummary(intent),
        )
        val inSteps = RenderedSteps(pkg, listOf(step(RenderedStepAction.StartIntent(RenderedIntent("a.VIEW", pkg, data = "chat:x")))))
        assertEquals("in the app: starts a.VIEW chat:x", ActionText.recipeSummary(inSteps))
    }

    @Test
    fun localPromptsPutTheRecipeFirstAndCapTheLength() {
        val yaml = """
            id: chat
            name: Chat
            description: A chat app.
            category: messaging
            android:
              packages: [com.chat]
            actions:
              - id: greet
                title: Say hello
                description: Opens the app.
                risk: action
                params:
                  text: {description: Text, required: true}
                confirm: Say hello
                android:
                  steps:
                    - launch: true
                    - type: {text: "{{text}}", submit: true}
        """.trimIndent()
        val plugin = PluginLoader.load(yaml, source = PluginSource.LOCAL).let { it.plugin ?: error(it.errors.joinToString("\n")) }
        val target = AppAction(InstalledApp(pkg, "Chat"), plugin.actions.single(), plugin)
        assertEquals(
            "In the app: types \"hi there\" and submits. Plugin says: Say hello",
            ActionText.confirmation(target, buildJsonObject { put("text", "hi there") }),
        )
        val long = ActionText.confirmation(target, buildJsonObject { put("text", "z".repeat(2000)) })
        assertTrue(long.length <= 400, long)
        assertTrue(long.startsWith("In the app: types \"zzz"), long)
    }
}
