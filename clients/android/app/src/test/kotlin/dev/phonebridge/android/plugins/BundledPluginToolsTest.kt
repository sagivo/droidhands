package dev.phonebridge.android.plugins

import android.content.Context
import android.content.ContextWrapper
import dev.phonebridge.android.tools.AndroidToolCatalog
import dev.phonebridge.core.plugins.ActionRequest
import dev.phonebridge.core.plugins.ActionResult
import dev.phonebridge.core.plugins.AppActionCatalog
import dev.phonebridge.core.plugins.AppActionsPlatform
import dev.phonebridge.core.plugins.AppPlugin
import dev.phonebridge.core.plugins.AppResolution
import dev.phonebridge.core.plugins.InstalledApp
import dev.phonebridge.core.plugins.ParamSpec
import dev.phonebridge.core.plugins.ParamType
import dev.phonebridge.core.plugins.PluginAction
import dev.phonebridge.core.plugins.PluginCatalog
import dev.phonebridge.core.plugins.PluginFile
import dev.phonebridge.core.plugins.Template
import dev.phonebridge.core.plugins.ToolRecipe
import dev.phonebridge.core.plugins.ValueSpec
import dev.phonebridge.core.tools.AppAccess
import dev.phonebridge.core.tools.CallContext
import dev.phonebridge.core.tools.Tool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Every `tool:` recipe of the bundled plugins (docs/PLUGINS.md §6.5) calls a tool of the Android registry with
 * arguments that tool really takes, of the type it expects, and with every argument it requires. The core
 * validator only knows tool names ([dev.phonebridge.core.tools.ToolNames]); tools ignore unknown arguments, so a
 * misspelled one would otherwise be dropped silently on the phone.
 */
class BundledPluginToolsTest {
    private val pluginsDir: File = System.getProperty("phonebridge.pluginsDir")?.let(::File)
        ?: File("../../../plugins").takeIf { it.isDirectory }
        ?: fail("Set the phonebridge.pluginsDir system property to the repository's plugins/ folder") as Nothing

    private object NoApps : AppActionsPlatform {
        override suspend fun installedApps(): List<InstalledApp> = emptyList()

        override suspend fun resolveApp(query: String, access: AppAccess): AppResolution = AppResolution.NotFound()

        override suspend fun discoveredActions(packageName: String): List<PluginAction> = emptyList()

        override fun unavailableReason(packageName: String, action: PluginAction): String? = null

        override suspend fun execute(request: ActionRequest, ctx: CallContext): ActionResult = ActionResult()
    }

    /** Tool factories only keep the context; nothing is called on it while building. */
    private val context: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    private val tools: Map<String, Tool> =
        AndroidToolCatalog.build(context, AppActionCatalog({ PluginCatalog.EMPTY }, NoApps) { null }).associateBy { it.name }

    private fun bundledPlugins(): List<AppPlugin> {
        val files = pluginsDir.listFiles { f -> f.isDirectory && !f.name.startsWith("_") }.orEmpty()
            .mapNotNull { dir -> File(dir, "plugin.yaml").takeIf { it.isFile }?.let { PluginFile("plugins/${dir.name}/plugin.yaml", it.readText()) } }
        val load = PluginCatalog.load(files, runExamples = false)
        assertTrue(load.errors.joinToString("\n"), load.errors.isEmpty())
        return load.catalog.plugins
    }

    @Test
    fun toolRecipesMatchTheToolsTheyCall() {
        val problems = ArrayList<String>()
        var checked = 0
        for (plugin in bundledPlugins()) {
            for (action in plugin.actions) {
                val recipe = action.android as? ToolRecipe ?: continue
                checked++
                problems += recipeProblems(recipe, action.params).map { "${plugin.id}/${action.id} (${recipe.name}): $it" }
            }
        }
        assertTrue("no tool recipes found in $pluginsDir", checked > 0)
        if (problems.isNotEmpty()) fail("Bundled plugin tool recipes don't match the Android tools:\n" + problems.joinToString("\n"))
    }

    @Test
    fun theCheckCatchesAMisspelledArgument() {
        val params = listOf(ParamSpec("hour", "Hour", ParamType.INTEGER, required = true), ParamSpec("minute", "Minute", ParamType.INTEGER, required = true))
        val good = ToolRecipe("alarm_set", mapOf("hour" to ValueSpec.Template("{{hour}}"), "minute" to ValueSpec.Template("{{minute}}")))
        assertTrue(recipeProblems(good, params).toString(), recipeProblems(good, params).isEmpty())
        val typo = ToolRecipe("alarm_set", mapOf("hours" to ValueSpec.Template("{{hour}}"), "minute" to ValueSpec.Template("{{minute}}")))
        val problems = recipeProblems(typo, params)
        assertTrue(problems.toString(), problems.any { "no argument 'hours'" in it })
        assertTrue(problems.toString(), problems.any { "'hour' is required" in it })
        val wrongType = ToolRecipe("alarm_set", mapOf("hour" to ValueSpec.Template("at {{hour}}"), "minute" to ValueSpec.Template("{{minute}}")))
        assertTrue(recipeProblems(wrongType, params).any { "'hour' takes integer" in it })
    }

    /** What is wrong with [recipe] for the tool it names, given the action's [params]; empty when nothing is. */
    private fun recipeProblems(recipe: ToolRecipe, params: List<ParamSpec>): List<String> {
        val tool = tools[recipe.name] ?: return listOf("no such tool in the Android registry")
        val properties = tool.inputSchema["properties"]?.jsonObject ?: JsonObject(emptyMap())
        val required = tool.inputSchema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        val byName = params.associateBy { it.name }
        val problems = ArrayList<String>()
        for ((name, value) in recipe.args) {
            val property = properties[name]?.jsonObject
            if (property == null) {
                problems += "the tool has no argument '$name' (it takes: ${properties.keys.joinToString()})"
                continue
            }
            val expected = property["type"]?.jsonPrimitive?.content ?: continue
            val actual = valueType(value, byName) ?: continue
            if (!compatible(expected, actual)) problems += "'$name' takes $expected, but the recipe passes $actual"
            val allowed = property["enum"]?.jsonArray?.map { it.jsonPrimitive.content }
            if (allowed != null && value is ValueSpec.Literal && value.value.content !in allowed) {
                problems += "'$name' must be one of ${allowed.joinToString()}, got '${value.value.content}'"
            }
        }
        for (name in required) {
            val value = recipe.args[name]
            if (value == null) {
                problems += "'$name' is required by the tool but the recipe doesn't pass it"
                continue
            }
            // A whole-placeholder argument for a missing optional parameter is left out (§6.1), so the tool would refuse.
            val param = (value as? ValueSpec.Template)?.let { Template.parse(it.template).singleParam }?.let(byName::get)
            if (param != null && !param.required && param.default == null) {
                problems += "'$name' is required by the tool, but parameter '${param.name}' is optional without a default"
            }
        }
        return problems
    }

    /** The JSON type [value] renders to (§6.1), or null when it can't be told. */
    private fun valueType(value: ValueSpec, params: Map<String, ParamSpec>): String? = when (value) {
        is ValueSpec.Literal -> when {
            value.value.isString -> "string"
            value.value.booleanOrNull != null -> "boolean"
            value.value.content.toLongOrNull() != null -> "integer"
            value.value.doubleOrNull != null -> "number"
            else -> null
        }
        is ValueSpec.Items -> "array"
        is ValueSpec.Template -> when (val single = Template.parse(value.template).singleParam) {
            null -> "string"
            else -> params[single]?.type?.id
        }
    }

    private fun compatible(expected: String, actual: String): Boolean =
        expected == actual || (expected == "number" && actual == "integer")
}
