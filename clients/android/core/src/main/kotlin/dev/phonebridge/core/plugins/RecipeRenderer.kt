package dev.phonebridge.core.plugins

import dev.phonebridge.core.tools.ToolException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Renders recipes with one call's validated parameters ([ParamValues.validate]) into the platform-neutral
 * [RenderedRecipe] types (docs/PLUGINS.md §6.1). Everything is pinned to [packageName], the app's installed
 * package. Pure.
 *
 * Runtime safety does not rely on the validator alone (discovered actions skip it): call actions are
 * refused and rendered URLs must not use a forbidden scheme.
 */
class RecipeRenderer(private val packageName: String) {
    /** Renders [recipe]. @throws ToolException if the result isn't allowed or a typed value doesn't convert. */
    fun render(recipe: AndroidRecipe, values: JsonObject): RenderedRecipe = when (recipe) {
        is IntentRecipe -> intent(recipe, values)
        is UrlRecipe -> url(recipe.url, values)
        is StepsRecipe -> RenderedSteps(packageName, steps(recipe.steps, values))
        is ToolRecipe -> RenderedToolCall(recipe.name, toolArgs(recipe.args, values))
    }

    fun intent(recipe: IntentRecipe, values: JsonObject): RenderedIntent {
        if (PluginRules.isCallAction(recipe.action)) {
            throw ToolException("This action would place a phone call, which app actions may not do. Use phone_call instead.")
        }
        val data = recipe.data?.let { url(it, values).url }
        val extras = LinkedHashMap<String, ExtraValue>()
        for ((key, spec) in recipe.extras) {
            extra(key, spec, values)?.let { extras[key] = it }
        }
        return RenderedIntent(
            action = recipe.action,
            packageName = packageName,
            data = data,
            type = recipe.type,
            categories = recipe.categories,
            className = recipe.className?.let(::fullClassName),
            extras = extras,
        )
    }

    fun url(template: String, values: JsonObject): RenderedUrl {
        val rendered = parse(template).render(values, urlEncode = true)
        Templates.requireAllowedUrl(rendered)
        return RenderedUrl(rendered, packageName)
    }

    /**
     * `.Main` → `<package>.Main`; full names are kept. The platform always starts the class inside
     * [packageName] (the component's package is pinned), so a class name can't reach another app; the
     * validator additionally requires plugin classes to be named inside the plugin's packages.
     */
    fun fullClassName(className: String): String = if (className.startsWith(".")) packageName + className else className

    fun steps(steps: List<Step>, values: JsonObject): List<RenderedStep> = steps.map { step(it, values) }

    private fun step(step: Step, values: JsonObject): RenderedStep {
        val action = when (val a = step.action) {
            is StepAction.Launch -> RenderedStepAction.Launch(a.fresh)
            is StepAction.OpenUrl -> RenderedStepAction.OpenUrl(url(a.url, values))
            is StepAction.StartIntent -> RenderedStepAction.StartIntent(intent(a.intent, values))
            is StepAction.WaitFor -> RenderedStepAction.WaitFor(selector(a.selector, values))
            is StepAction.Tap -> RenderedStepAction.Tap(selector(a.selector, values))
            is StepAction.LongPress -> RenderedStepAction.LongPress(selector(a.selector, values))
            is StepAction.TypeText -> RenderedStepAction.TypeText(
                into = a.into?.let { selector(it, values) },
                text = parse(a.text).render(values),
                clear = a.clear,
                submit = a.submit,
            )
            is StepAction.Scroll -> RenderedStepAction.Scroll(
                direction = a.direction,
                within = a.within?.let { selector(it, values) },
                until = a.until?.let { selector(it, values) },
                max = a.max,
            )
            StepAction.Back -> RenderedStepAction.Back
            is StepAction.Sleep -> RenderedStepAction.Sleep(a.ms)
            is StepAction.Expect -> RenderedStepAction.Expect(selector(a.selector, values), a.fail ?: DEFAULT_EXPECT_FAIL)
            is StepAction.IfPresent -> RenderedStepAction.IfPresent(selector(a.selector, values), steps(a.then, values))
        }
        return RenderedStep(action, step.optional, step.timeoutMs ?: Step.DEFAULT_TIMEOUT_MS)
    }

    /** Renders the text fields and expands a short [Selector.id] to `<package>:id/<name>`. */
    fun selector(selector: Selector, values: JsonObject): Selector {
        fun r(text: String?) = text?.let { parse(it).render(values) }
        return selector.copy(
            id = selector.id?.let(::fullViewId),
            text = r(selector.text),
            textContains = r(selector.textContains),
            desc = r(selector.desc),
            descContains = r(selector.descContains),
            textOrDesc = r(selector.textOrDesc),
        )
    }

    /** `search_src_text` → `<package>:id/search_src_text`; full ids (`pkg:id/name`) are kept. */
    fun fullViewId(id: String): String = if (id.contains(":id/")) id else "$packageName:id/$id"

    private fun extra(key: String, spec: ExtraSpec, values: JsonObject): ExtraValue? {
        val type = spec.type
        return when (val v = spec.value) {
            is ValueSpec.Items -> {
                if (type != null && type != ExtraType.STRING_ARRAY) throw conversion(key, type, "a list")
                ExtraValue.StringArrayValue(v.items.mapNotNull { item -> scalar(item, values)?.let(Templates::text) })
            }
            else -> {
                val value = scalar(v, values) ?: return null // A single placeholder for a missing parameter.
                when (type) {
                    null -> inferred(value)
                    ExtraType.STRING -> ExtraValue.StringValue(Templates.text(value))
                    ExtraType.INT -> ExtraValue.IntValue(
                        wholeNumber(value)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt() ?: throw conversion(key, type, value),
                    )
                    ExtraType.LONG -> ExtraValue.LongValue(wholeNumber(value) ?: throw conversion(key, type, value))
                    ExtraType.DOUBLE -> ExtraValue.DoubleValue(
                        (if (value.isString) value.content.trim().toDoubleOrNull() else value.doubleOrNull)?.takeIf { it.isFinite() }
                            ?: throw conversion(key, type, value),
                    )
                    ExtraType.BOOLEAN -> ExtraValue.BooleanValue(
                        when (if (value.isString) value.content.trim().lowercase() else value.booleanOrNull?.toString()) {
                            "true" -> true
                            "false" -> false
                            else -> throw conversion(key, type, value)
                        },
                    )
                    ExtraType.STRING_ARRAY -> ExtraValue.StringArrayValue(listOf(Templates.text(value)))
                }
            }
        }
    }

    private fun toolArgs(args: Map<String, ValueSpec>, values: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, spec) in args) {
            when (spec) {
                is ValueSpec.Items -> out[key] = JsonArray(spec.items.mapNotNull { scalar(it, values) })
                else -> scalar(spec, values)?.let { out[key] = it }
            }
        }
        return JsonObject(out)
    }

    /**
     * A template or literal as a JSON value: a template that is exactly one placeholder takes the
     * parameter's value and type (null when the parameter has no value); any other template is text.
     */
    private fun scalar(spec: ValueSpec, values: JsonObject): JsonPrimitive? = when (spec) {
        is ValueSpec.Literal -> spec.value
        is ValueSpec.Template -> {
            val template = parse(spec.template)
            val single = template.singleParam
            if (single != null) Templates.valueOf(values, single) else JsonPrimitive(template.render(values))
        }
        is ValueSpec.Items -> throw ToolException("Internal error: nested lists in a recipe.")
    }

    /** An inferred extra: integers become int (long when too large), numbers double, booleans boolean, text string. */
    private fun inferred(value: JsonPrimitive): ExtraValue {
        if (value.isString) return ExtraValue.StringValue(value.content)
        value.booleanOrNull?.let { return ExtraValue.BooleanValue(it) }
        value.longOrNull?.let { n -> return if (n in Int.MIN_VALUE..Int.MAX_VALUE) ExtraValue.IntValue(n.toInt()) else ExtraValue.LongValue(n) }
        value.doubleOrNull?.let { return ExtraValue.DoubleValue(it) }
        return ExtraValue.StringValue(value.content)
    }

    private fun wholeNumber(value: JsonPrimitive): Long? =
        if (value.isString) value.content.trim().toLongOrNull() else value.longOrNull

    private fun conversion(key: String, type: ExtraType, value: Any) =
        ToolException("This action could not build its '$key' value: ${shown(value)} is not a valid ${type.id}. Check the parameters and try again.")

    private fun shown(value: Any): String = when (value) {
        is JsonPrimitive -> if (value.isString) "\"${value.content.take(40)}\"" else value.content
        else -> value.toString()
    }

    private fun parse(template: String): Template = try {
        Template.parse(template)
    } catch (e: TemplateException) {
        throw ToolException("This action's recipe is broken (${e.message}). Use the screen tools instead.")
    }

    companion object {
        const val DEFAULT_EXPECT_FAIL = "The app did not show what this action expected. Check the screen with screen_read."
    }
}
