package dev.phonebridge.core.plugins

import dev.phonebridge.core.tools.ToolException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Runs a plugin's examples (docs/PLUGINS.md §7) without a phone: each example's params go through
 * [ParamValues.validate] and [RecipeRenderer] for the example's OS (the action's `android` recipe rendered for the
 * first of `android.packages`, or with `os: ios` its `ios` recipe for the first of `ios.bundle_ids`), and the result
 * is compared with `expect`. Pure.
 *
 * Comparison: `url` is the full rendered URL. `intent` compares only the listed keys; within `extras`, only
 * the listed extras, where `null` means the extra must be absent, and values must match in type (`5` is an
 * int, `"5"` a string). `class` may be written relative (`.Main`). `tool` compares the name and all
 * arguments. `steps` compares every rendered step in order, written in recipe syntax with the rendered
 * values (short view ids allowed). `error` passes when validation or rendering fails with a message
 * containing it (ignoring case).
 */
object PluginExamples {
    /** Problems with [plugin]'s examples (empty when all pass). */
    fun run(plugin: AppPlugin): List<PluginProblem> {
        // Examples without a recipe or app id for their OS are the validator's to report.
        val renderers = DeviceOs.entries.mapNotNull { os ->
            plugin.appIds(os).firstOrNull()?.let { os to RecipeRenderer(it, os) }
        }.toMap()
        val problems = ArrayList<PluginProblem>()
        plugin.actions.forEachIndexed { a, action ->
            action.examples.forEachIndexed { e, example ->
                val recipe = action.recipeFor(example.os) ?: return@forEachIndexed
                val renderer = renderers[example.os] ?: return@forEachIndexed
                val path = "actions[$a].examples[$e]"
                runOne(plugin, action, recipe, example, renderer)?.let { problems += PluginProblem(path, it) }
            }
        }
        return problems
    }

    /** Null when the example passes, else what went wrong. */
    private fun runOne(plugin: AppPlugin, action: PluginAction, recipe: Recipe, example: PluginExample, renderer: RecipeRenderer): String? {
        val expect = example.expect
        val rendered = try {
            renderer.render(recipe, ParamValues.validate(action.params, example.params))
        } catch (e: ToolException) {
            val message = e.message.orEmpty()
            return when {
                expect !is ExampleExpectation.Error -> "failed: $message"
                message.contains(expect.substring, ignoreCase = true) -> null
                else -> "expected an error containing '${expect.substring}', got: $message"
            }
        }
        return when (expect) {
            is ExampleExpectation.Error -> "expected an error containing '${expect.substring}', but the action rendered fine"
            is ExampleExpectation.Url -> {
                val url = (rendered as? RenderedUrl)?.url ?: return mismatchedKind("url", recipe)
                if (url == expect.url) null else "url: expected ${expect.url}\n  got      $url"
            }
            is ExampleExpectation.Intent -> intent(rendered as? RenderedIntent ?: return mismatchedKind("intent", recipe), expect.fields, renderer)
            is ExampleExpectation.Tool -> tool(rendered as? RenderedToolCall ?: return mismatchedKind("tool", recipe), expect.fields)
            is ExampleExpectation.Steps ->
                steps(plugin, rendered as? RenderedSteps ?: return mismatchedKind("steps", recipe), expect.steps, renderer, example.os)
        }
    }

    private fun mismatchedKind(expected: String, recipe: Recipe) =
        "expects $expected, but the action's recipe is ${recipe.kind}"

    private fun intent(actual: RenderedIntent, fields: JsonObject, renderer: RecipeRenderer): String? {
        val problems = ArrayList<String>()
        fun compareText(key: String, got: String?) {
            val want = fields[key] ?: return
            val wantText = (want as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            if (want !is JsonNull && (want !is JsonPrimitive || !want.isString)) {
                problems += "$key: write the expected value as text"
            } else if (wantText != got) {
                problems += "$key: expected ${wantText ?: "none"}, got ${got ?: "none"}"
            }
        }
        compareText("action", actual.action)
        compareText("package", actual.packageName)
        compareText("data", actual.data)
        compareText("type", actual.type)
        fields["class"]?.let { want ->
            val wantText = (want as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(renderer::fullClassName)
            if (wantText != actual.className) problems += "class: expected ${wantText ?: "none"}, got ${actual.className ?: "none"}"
        }
        fields["categories"]?.let { want ->
            val wantList = (want as? JsonArray)?.map { (it as? JsonPrimitive)?.content }
            if (wantList != actual.categories) problems += "categories: expected $want, got ${actual.categories}"
        }
        fields["extras"]?.let { want ->
            val wantExtras = want as? JsonObject ?: run {
                problems += "extras: must be a map"
                return@let
            }
            for ((key, value) in wantExtras) {
                val got = actual.extras[key]
                when {
                    value is JsonNull -> if (got != null) problems += "extras.$key: expected no extra, got ${show(got)}"
                    got == null -> problems += "extras.$key: expected ${show(value)}, but the intent has no such extra"
                    !extraMatches(value, got) -> problems += "extras.$key: expected ${show(value)}, got ${show(got)}"
                }
            }
        }
        return problems.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun extraMatches(want: JsonElement, got: ExtraValue): Boolean = when (got) {
        is ExtraValue.StringValue -> want is JsonPrimitive && want.isString && want.content == got.value
        is ExtraValue.BooleanValue -> want is JsonPrimitive && !want.isString && want.booleanOrNull == got.value
        is ExtraValue.IntValue -> numberMatches(want, got.value.toString())
        is ExtraValue.LongValue -> numberMatches(want, got.value.toString())
        is ExtraValue.DoubleValue -> numberMatches(want, got.value.toString())
        is ExtraValue.StringArrayValue ->
            want is JsonArray && want.size == got.values.size &&
                want.zip(got.values).all { (w, g) -> w is JsonPrimitive && w.content == g }
    }

    private fun numberMatches(want: JsonElement, got: String): Boolean {
        if (want !is JsonPrimitive || want.isString || want.booleanOrNull != null) return false
        val x = want.content.toBigDecimalOrNull() ?: return false
        val y = got.toBigDecimalOrNull() ?: return false
        return x.compareTo(y) == 0
    }

    private fun show(value: Any): String = when (value) {
        is JsonPrimitive -> if (value.isString) "\"${value.content}\" (text)" else "${value.content} (${if (value.booleanOrNull != null) "boolean" else "number"})"
        is JsonElement -> value.toString()
        is ExtraValue.StringValue -> "\"${value.value}\" (text)"
        is ExtraValue.IntValue -> "${value.value} (int)"
        is ExtraValue.LongValue -> "${value.value} (long)"
        is ExtraValue.DoubleValue -> "${value.value} (double)"
        is ExtraValue.BooleanValue -> "${value.value} (boolean)"
        is ExtraValue.StringArrayValue -> value.values.toString()
        else -> value.toString()
    }

    private fun tool(actual: RenderedToolCall, fields: JsonObject): String? {
        val problems = ArrayList<String>()
        fields["name"]?.let { want ->
            val name = (want as? JsonPrimitive)?.content
            if (name != actual.name) problems += "name: expected $name, got ${actual.name}"
        }
        fields["args"]?.let { want ->
            if (!jsonMatches(want, actual.args)) problems += "args: expected $want, got ${actual.args}"
        }
        return problems.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    /** Structural equality where text must match text and numbers compare by value. */
    private fun jsonMatches(want: JsonElement, got: JsonElement): Boolean = when {
        want is JsonObject && got is JsonObject -> want.keys == got.keys && want.all { (k, v) -> jsonMatches(v, got.getValue(k)) }
        want is JsonArray && got is JsonArray -> want.size == got.size && want.zip(got).all { (w, g) -> jsonMatches(w, g) }
        want is JsonNull || got is JsonNull -> want is JsonNull && got is JsonNull
        want is JsonPrimitive && got is JsonPrimitive -> when {
            want.isString || got.isString -> want.isString && got.isString && want.content == got.content
            want.booleanOrNull != null || got.booleanOrNull != null -> want.content == got.content
            else -> numberMatches(want, got.content)
        }
        else -> false
    }

    private fun steps(plugin: AppPlugin, actual: RenderedSteps, want: List<JsonElement>, renderer: RecipeRenderer, os: DeviceOs): String? {
        // The expected steps are written in recipe syntax; read them literally (no placeholders) and render
        // them with no parameters, which only expands short ids and fills defaults.
        val parser = PluginParser(plugin.source)
        val expected = parser.literalSteps(JsonArray(want), "expect.steps", os) ?: return "expect.steps: ${parser.problems.joinToString("; ")}"
        val rendered = try {
            renderer.steps(expected, JsonObject(emptyMap()))
        } catch (e: ToolException) {
            return "expect.steps: ${e.message}"
        }
        if (rendered == actual.steps) return null
        val index = rendered.indices.firstOrNull { it >= actual.steps.size || rendered[it] != actual.steps[it] } ?: rendered.size
        val wantStep = rendered.getOrNull(index)?.toString() ?: "no more steps"
        val gotStep = actual.steps.getOrNull(index)?.toString() ?: "no more steps"
        return "steps[$index]: expected $wantStep\n  got $gotStep"
    }
}
