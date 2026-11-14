package dev.phonebridge.android.tools.actions

import dev.phonebridge.core.plugins.ExtraValue
import dev.phonebridge.core.plugins.IntentRecipe
import dev.phonebridge.core.plugins.RecipeRenderer
import dev.phonebridge.core.plugins.RenderedIntent
import dev.phonebridge.core.plugins.RenderedUrl
import dev.phonebridge.core.plugins.UrlRecipe
import dev.phonebridge.core.tools.ToolException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentPlansTest {
    private val pkg = "com.spotify.music"

    @Test
    fun intentRecipeMapsField() {
        val rendered = RenderedIntent(
            action = "android.media.action.MEDIA_PLAY_FROM_SEARCH",
            packageName = pkg,
            data = "spotify:search:x",
            type = "text/plain",
            categories = listOf("android.intent.category.DEFAULT"),
            className = "$pkg.MainActivity",
            extras = linkedMapOf("query" to ExtraValue.StringValue("lofi"), "count" to ExtraValue.IntValue(3)),
        )
        assertEquals(
            IntentPlan(
                action = "android.media.action.MEDIA_PLAY_FROM_SEARCH",
                packageName = pkg,
                data = "spotify:search:x",
                type = "text/plain",
                categories = listOf("android.intent.category.DEFAULT"),
                className = "$pkg.MainActivity",
                extras = mapOf("query" to ExtraValue.StringValue("lofi"), "count" to ExtraValue.IntValue(3)),
                taskMode = TaskMode.STANDARD,
            ),
            IntentPlans.of(rendered),
        )
    }

    @Test
    fun launcherIntentsResumeTheAppsTask() {
        val plan = IntentPlans.of(RenderedIntent("android.intent.action.MAIN", pkg, categories = listOf("android.intent.category.LAUNCHER"), className = "$pkg.Main"))
        assertEquals(TaskMode.RESUME, plan.taskMode)
        assertEquals(TaskMode.FRESH, IntentPlans.launcher(pkg, "$pkg.Main", fresh = true).taskMode)
        assertEquals(
            IntentPlan("android.intent.action.MAIN", pkg, categories = listOf("android.intent.category.LAUNCHER"), className = "$pkg.Main", taskMode = TaskMode.RESUME),
            IntentPlans.launcher(pkg, "$pkg.Main", fresh = false),
        )
    }

    @Test
    fun linksAreBrowsableViewsOfTheAppOnly() {
        assertEquals(
            IntentPlan(
                action = "android.intent.action.VIEW",
                packageName = pkg,
                data = "spotify:search:daft%20punk",
                categories = listOf("android.intent.category.BROWSABLE"),
                retryWithoutBrowsable = true,
            ),
            IntentPlans.of(RenderedUrl("spotify:search:daft%20punk", pkg)),
        )
    }

    @Test
    fun refusesCallsPhoneBridgeAndForbiddenSchemes() {
        assertThrows(ToolException::class.java) { IntentPlans.of(RenderedIntent("android.intent.action.CALL", pkg, data = "tel:123")) }
        assertThrows(ToolException::class.java) { IntentPlans.of(RenderedIntent("CALL_PRIVILEGED", pkg)) }
        assertThrows(ToolException::class.java) { IntentPlans.of(RenderedIntent("android.intent.action.VIEW", "dev.phonebridge.android")) }
        assertThrows(ToolException::class.java) { IntentPlans.of(RenderedUrl("https://x", "dev.phonebridge.android.debug")) }
        assertThrows(ToolException::class.java) { IntentPlans.of(RenderedIntent("android.intent.action.VIEW", pkg, data = "content://contacts/1")) }
        assertThrows(ToolException::class.java) { IntentPlans.of(RenderedUrl("intent://x#Intent;end", pkg)) }
        assertThrows(ToolException::class.java) { IntentPlans.of(RenderedUrl("javascript:alert(1)", pkg)) }
        // The E2E fixture apps are not PhoneBridge.
        IntentPlans.of(RenderedIntent("android.intent.action.MAIN", "dev.phonebridge.fixture.mail"))
    }

    @Test
    fun renderedRecipesBecomePlansPinnedToTheInstalledPackage() {
        val renderer = RecipeRenderer(pkg)
        val values = JsonObject(mapOf("query" to JsonPrimitive("Eiffel Tower")))
        val url = renderer.render(UrlRecipe("https://maps.example.com/?q={{query}}"), values) as RenderedUrl
        val plan = IntentPlans.of(url)
        assertEquals(pkg, plan.packageName)
        assertEquals("https://maps.example.com/?q=Eiffel%20Tower", plan.data)
        val intent = renderer.render(IntentRecipe(action = "android.intent.action.VIEW", className = ".Map", data = "geo:0,0?q={{query}}"), values) as RenderedIntent
        val intentPlan = IntentPlans.of(intent)
        assertEquals("$pkg.Map", intentPlan.className)
        assertEquals("geo:0,0?q=Eiffel%20Tower", intentPlan.data)
        assertTrue(intentPlan.extras.isEmpty())
    }
}
