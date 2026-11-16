package dev.phonebridge.core.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Parameters for checking, on the phone, that an action's intent or link still reaches the installed app (pure): the
 * platform renders the recipe with them and asks the OS whether an activity of the app accepts the result, without
 * starting anything. A plugin written for one app version then stops being offered on versions that dropped the link.
 */
object RecipeProbe {
    /**
     * Values for [action]'s parameters: those of its first example that validates (the most realistic), else each
     * parameter's default, first enum value, minimum, or a placeholder of the right type.
     */
    fun sampleValues(action: PluginAction): JsonObject {
        for (example in action.examples) {
            if (example.expect is ExampleExpectation.Error) continue
            runCatching { ParamValues.validate(action.params, example.params) }.getOrNull()?.let { return it }
        }
        val values = LinkedHashMap<String, JsonPrimitive>()
        for (p in action.params) {
            values[p.name] = p.default ?: p.enum?.firstOrNull() ?: when (p.type) {
                ParamType.INTEGER -> JsonPrimitive((p.minimum ?: 1.0).toLong())
                ParamType.NUMBER -> JsonPrimitive(p.minimum ?: 1.0)
                ParamType.BOOLEAN -> JsonPrimitive(true)
                ParamType.STRING -> JsonPrimitive("x".repeat((p.minLength ?: 1).coerceIn(1, 20)))
            }
        }
        return JsonObject(values)
    }
}
