package dev.phonebridge.core.plugins

import dev.phonebridge.core.tools.ToolException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RecipeRendererTest {
    private val renderer = RecipeRenderer("com.example.app")
    private val values = buildJsonObject {
        put("text", "hi there")
        put("hour", 7L)
        put("big", 5_000_000_000L)
        put("ratio", 0.5)
        put("loud", true)
    }

    @Test
    fun intentsArePinnedAndExtrasTyped() {
        val recipe = IntentRecipe(
            action = "android.intent.action.SEND",
            type = "text/plain",
            data = "https://x.com/s?q={{text}}",
            categories = listOf("android.intent.category.DEFAULT"),
            className = ".ShareActivity",
            extras = linkedMapOf(
                "android.intent.extra.TEXT" to ExtraSpec(ValueSpec.Template("Say: {{text}}")),
                "hour" to ExtraSpec(ValueSpec.Template("{{hour}}")),
                "big" to ExtraSpec(ValueSpec.Template("{{ big }}")),
                "ratio" to ExtraSpec(ValueSpec.Template("{{ratio}}")),
                "loud" to ExtraSpec(ValueSpec.Template("{{loud}}")),
                "hour_text" to ExtraSpec(ValueSpec.Template("{{hour}}"), ExtraType.STRING),
                "hour_long" to ExtraSpec(ValueSpec.Template("{{hour}}"), ExtraType.LONG),
                "text_int" to ExtraSpec(ValueSpec.Template("4{{hour}}"), ExtraType.INT),
                "show_ui" to ExtraSpec(ValueSpec.Literal(JsonPrimitive(true))),
                "count" to ExtraSpec(ValueSpec.Literal(JsonPrimitive(3L))),
                "scale" to ExtraSpec(ValueSpec.Literal(JsonPrimitive(1.5))),
                "flag" to ExtraSpec(ValueSpec.Template("TRUE"), ExtraType.BOOLEAN),
                "dbl" to ExtraSpec(ValueSpec.Template("{{hour}}"), ExtraType.DOUBLE),
                "names" to ExtraSpec(ValueSpec.Items(listOf(ValueSpec.Template("a"), ValueSpec.Template("{{text}}"), ValueSpec.Template("{{missing}}")))),
                "missing" to ExtraSpec(ValueSpec.Template("{{missing}}")),
                "missing_typed" to ExtraSpec(ValueSpec.Template("{{missing}}"), ExtraType.INT),
                "partly" to ExtraSpec(ValueSpec.Template("x{{missing}}")),
            ),
        )
        val intent = renderer.intent(recipe, values)
        assertEquals("com.example.app", intent.packageName)
        assertEquals("com.example.app.ShareActivity", intent.className)
        assertEquals("https://x.com/s?q=hi%20there", intent.data)
        assertEquals("text/plain", intent.type)
        assertEquals(listOf("android.intent.category.DEFAULT"), intent.categories)
        assertEquals(
            linkedMapOf(
                "android.intent.extra.TEXT" to ExtraValue.StringValue("Say: hi there"),
                "hour" to ExtraValue.IntValue(7),
                "big" to ExtraValue.LongValue(5_000_000_000L),
                "ratio" to ExtraValue.DoubleValue(0.5),
                "loud" to ExtraValue.BooleanValue(true),
                "hour_text" to ExtraValue.StringValue("7"),
                "hour_long" to ExtraValue.LongValue(7),
                "text_int" to ExtraValue.IntValue(47),
                "show_ui" to ExtraValue.BooleanValue(true),
                "count" to ExtraValue.IntValue(3),
                "scale" to ExtraValue.DoubleValue(1.5),
                "flag" to ExtraValue.BooleanValue(true),
                "dbl" to ExtraValue.DoubleValue(7.0),
                // An item that is a single placeholder for a missing parameter is left out.
                "names" to ExtraValue.StringArrayValue(listOf("a", "hi there")),
                // "missing" and "missing_typed" are left out entirely.
                "partly" to ExtraValue.StringValue("x"),
            ),
            intent.extras,
        )
        assertEquals(intent.extras.keys.toList(), intent.extras.keys.sortedBy { k -> recipe.extras.keys.indexOf(k) })
    }

    @Test
    fun fullClassNamesAreKeptButTheComponentStaysInThePackage() {
        val intent = renderer.intent(IntentRecipe("android.intent.action.VIEW", className = "com.example.app.ui.Main"), values)
        assertEquals("com.example.app.ui.Main", intent.className)
        assertEquals("com.example.app", intent.packageName)
    }

    @Test
    fun typedExtrasThatDoNotConvertFail() {
        for ((type, template) in listOf(ExtraType.INT to "{{text}}", ExtraType.INT to "{{big}}", ExtraType.BOOLEAN to "yes", ExtraType.DOUBLE to "x")) {
            val recipe = IntentRecipe("a.B", extras = mapOf("v" to ExtraSpec(ValueSpec.Template(template), type)))
            val e = assertFailsWith<ToolException>("$type $template") { renderer.intent(recipe, values) }
            assertTrue("could not build its 'v' value" in e.message!!, e.message)
        }
    }

    @Test
    fun callActionsAreRefusedAtRunTime() {
        for (action in listOf("android.intent.action.CALL", "android.intent.action.CALL_PRIVILEGED", "android.intent.action.CALL_EMERGENCY", "CALL")) {
            assertFailsWith<ToolException>(action) { renderer.intent(IntentRecipe(action, data = "tel:123"), values) }
        }
        renderer.intent(IntentRecipe("android.intent.action.DIAL", data = "tel:123"), values)
    }

    @Test
    fun urlsAreEncodedPinnedAndChecked() {
        val url = renderer.render(UrlRecipe("spotify:search:{{text}}"), values)
        assertEquals(RenderedUrl("spotify:search:hi%20there", "com.example.app"), url)
        assertFailsWith<ToolException> { renderer.render(UrlRecipe("javascript:{{text}}"), values) }
        assertFailsWith<ToolException> { renderer.render(UrlRecipe("{{text}}"), values) }
    }

    @Test
    fun stepsRenderTextsIdsAndDefaults() {
        val steps = listOf(
            Step(StepAction.Launch(fresh = true)),
            Step(StepAction.OpenUrl("app://search/{{text}}")),
            Step(StepAction.StartIntent(IntentRecipe("a.VIEW", className = ".X"))),
            Step(StepAction.TypeText(Selector(id = "search_src_text"), "{{text}}", submit = true), timeoutMs = 8000),
            Step(StepAction.WaitFor(Selector(textContains = "{{text}}"))),
            Step(StepAction.IfPresent(Selector(textOrDesc = "Not now"), listOf(Step(StepAction.Tap(Selector(textOrDesc = "Not now"))))), optional = true),
            Step(StepAction.Tap(Selector(id = "android:id/button1"))),
            Step(StepAction.LongPress(Selector(id = "com.example.app:id/row", index = 2))),
            Step(StepAction.Scroll(ScrollDirection.DOWN, until = Selector(desc = "{{hour}} items"))),
            Step(StepAction.Back),
            Step(StepAction.Sleep(500)),
            Step(StepAction.Expect(Selector(id = "now_playing"))),
            Step(StepAction.Expect(Selector(id = "x"), fail = "Nope")),
        )
        val rendered = renderer.render(StepsRecipe(steps), values) as RenderedSteps
        assertEquals("com.example.app", rendered.packageName)
        assertEquals(
            listOf(
                RenderedStep(RenderedStepAction.Launch(fresh = true)),
                RenderedStep(RenderedStepAction.OpenUrl(RenderedUrl("app://search/hi%20there", "com.example.app"))),
                RenderedStep(RenderedStepAction.StartIntent(RenderedIntent("a.VIEW", "com.example.app", className = "com.example.app.X"))),
                RenderedStep(
                    RenderedStepAction.TypeText(Selector(id = "com.example.app:id/search_src_text"), "hi there", submit = true),
                    timeoutMs = 8000,
                ),
                RenderedStep(RenderedStepAction.WaitFor(Selector(textContains = "hi there"))),
                RenderedStep(
                    RenderedStepAction.IfPresent(
                        Selector(textOrDesc = "Not now"),
                        listOf(RenderedStep(RenderedStepAction.Tap(Selector(textOrDesc = "Not now")))),
                    ),
                    optional = true,
                ),
                RenderedStep(RenderedStepAction.Tap(Selector(id = "android:id/button1"))),
                RenderedStep(RenderedStepAction.LongPress(Selector(id = "com.example.app:id/row", index = 2))),
                RenderedStep(RenderedStepAction.Scroll(ScrollDirection.DOWN, until = Selector(desc = "7 items"))),
                RenderedStep(RenderedStepAction.Back),
                RenderedStep(RenderedStepAction.Sleep(500)),
                RenderedStep(RenderedStepAction.Expect(Selector(id = "com.example.app:id/now_playing"), RecipeRenderer.DEFAULT_EXPECT_FAIL)),
                RenderedStep(RenderedStepAction.Expect(Selector(id = "com.example.app:id/x"), "Nope")),
            ),
            rendered.steps,
        )
    }

    @Test
    fun iosRecipesArePinnedToTheBundleIdAndKeepIdsAsWritten() {
        val ios = RecipeRenderer("com.example.ios", DeviceOs.IOS)
        assertEquals(RenderedUrl("example://search?q=hi%20there", "com.example.ios"), ios.render(UrlRecipe("example://search?q={{text}}"), values))
        assertFailsWith<ToolException> { ios.render(UrlRecipe("javascript:{{text}}"), values) }
        val steps = listOf(
            Step(StepAction.Launch()),
            Step(StepAction.OpenUrl("example://x/{{text}}")),
            Step(StepAction.TypeText(Selector(id = "SearchField"), "{{text}}", submit = true)),
            Step(StepAction.Tap(Selector(id = "Player:Play", className = "Button"))),
            Step(StepAction.Expect(Selector(id = "com.example.app:id/x"))),
        )
        val rendered = ios.render(StepsRecipe(steps), values) as RenderedSteps
        assertEquals(
            RenderedSteps(
                "com.example.ios",
                listOf(
                    RenderedStep(RenderedStepAction.Launch()),
                    RenderedStep(RenderedStepAction.OpenUrl(RenderedUrl("example://x/hi%20there", "com.example.ios"))),
                    RenderedStep(RenderedStepAction.TypeText(Selector(id = "SearchField"), "hi there", submit = true)),
                    RenderedStep(RenderedStepAction.Tap(Selector(id = "Player:Play", className = "Button"))),
                    RenderedStep(RenderedStepAction.Expect(Selector(id = "com.example.app:id/x"), RecipeRenderer.DEFAULT_EXPECT_FAIL)),
                ),
            ),
            rendered,
        )
        assertEquals("SearchField", ios.fullViewId("SearchField"))
        val call = ios.render(ToolRecipe("url_open", linkedMapOf("url" to ValueSpec.Template("https://x/{{text}}"))), values) as RenderedToolCall
        assertEquals("""{"url":"https://x/hi there"}""", call.args.toString())
    }

    @Test
    fun iosRefusesIntentsAndBackEvenWithoutTheValidator() {
        val ios = RecipeRenderer("com.example.ios", DeviceOs.IOS)
        val intent = assertFailsWith<ToolException> { ios.render(IntentRecipe("android.intent.action.VIEW"), values) }
        assertTrue("iPhone doesn't have" in intent.message!!, intent.message)
        assertFailsWith<ToolException> { ios.render(StepsRecipe(listOf(Step(StepAction.StartIntent(IntentRecipe("a.VIEW"))))), values) }
        val back = assertFailsWith<ToolException> { ios.render(StepsRecipe(listOf(Step(StepAction.Back))), values) }
        assertTrue("Back" in back.message!!, back.message)
    }

    @Test
    fun toolArgsTakeParameterTypesAndDropMissingOnes() {
        val recipe = ToolRecipe(
            "alarm_set",
            linkedMapOf(
                "hour" to ValueSpec.Template("{{hour}}"),
                "label" to ValueSpec.Template("Wake up: {{text}}"),
                "loud" to ValueSpec.Template("{{loud}}"),
                "minute" to ValueSpec.Literal(JsonPrimitive(0L)),
                "days" to ValueSpec.Items(listOf(ValueSpec.Template("mon"), ValueSpec.Template("{{missing}}"))),
                "note" to ValueSpec.Template("{{missing}}"),
            ),
        )
        val call = renderer.render(recipe, values) as RenderedToolCall
        assertEquals("alarm_set", call.name)
        assertEquals("""{"hour":7,"label":"Wake up: hi there","loud":true,"minute":0,"days":["mon"]}""", call.args.toString())
    }

    @Test
    fun brokenTemplatesFailCleanlyAtRunTime() {
        val e = assertFailsWith<ToolException> { renderer.render(UrlRecipe("https://x/{{"), JsonObject(emptyMap())) }
        assertTrue("recipe is broken" in e.message!!, e.message)
    }
}
