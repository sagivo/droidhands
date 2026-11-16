package dev.phonebridge.core.plugins

import dev.phonebridge.core.tools.ToolNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

/** plugins/plugin.schema.json (editor completion) stays in step with the validator's lists and limits. */
class PluginSchemaTest {
    private val schema: JsonObject by lazy {
        val dir = System.getProperty("phonebridge.pluginsDir")?.let(::File) ?: File("../../../plugins")
        Json.parseToJsonElement(File(dir, "plugin.schema.json").readText()).jsonObject
    }

    private fun JsonObject.at(vararg path: String): JsonObject = path.fold(this) { o, key -> o.getValue(key).jsonObject }

    private fun JsonObject.strings(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun enumsMatchTheValidator() {
        assertEquals(PluginRules.CATEGORIES, schema.at("properties", "category").strings("enum"))
        assertEquals(PluginStatus.entries.map { it.id }, schema.at("properties", "status").strings("enum"))
        val action = schema.at("definitions", "action", "properties")
        assertEquals(PluginRules.ACTION_SCOPES, action.at("scope").strings("enum"))
        assertEquals(listOf("read", "action", "sensitive"), action.at("risk").strings("enum"))
        assertEquals(ParamType.entries.map { it.id }, schema.at("definitions", "param", "properties", "type").strings("enum"))
        val extraTypes = schema.at("definitions", "extra").getValue("anyOf").jsonArray[2].jsonObject.at("properties", "type").strings("enum")
        assertEquals(ExtraType.entries.map { it.id }, extraTypes)
        val recipeTools = ToolNames.ALL.filter { ToolNames.recipeRefusal(it) == null }
        assertEquals(recipeTools, schema.at("definitions", "tool", "properties", "name").strings("enum"))
    }

    @Test
    fun limitsMatchTheValidator() {
        assertEquals(PluginRules.MAX_ACTIONS.toString(), schema.at("properties", "actions").getValue("maxItems").jsonPrimitive.content)
        assertEquals(PluginRules.MAX_NAME.toString(), schema.at("properties", "name").getValue("maxLength").jsonPrimitive.content)
        val action = schema.at("definitions", "action", "properties")
        assertEquals(PluginRules.MAX_TITLE.toString(), action.at("title").getValue("maxLength").jsonPrimitive.content)
        assertEquals(PluginRules.MAX_ACTION_DESCRIPTION.toString(), action.at("description").getValue("maxLength").jsonPrimitive.content)
        assertEquals(PluginRules.MAX_PARAMS.toString(), action.at("params").getValue("maxProperties").jsonPrimitive.content)
        assertEquals(PluginRules.MAX_STEPS.toString(), schema.at("definitions", "recipe", "properties", "steps").getValue("maxItems").jsonPrimitive.content)
        assertEquals(PluginRules.PLUGIN_ID.pattern, schema.at("properties", "id").getValue("pattern").jsonPrimitive.content)
        assertEquals(PluginRules.ACTION_ID.pattern, action.at("id").getValue("pattern").jsonPrimitive.content)
        assertEquals(PluginRules.BUNDLE_ID.pattern, schema.at("properties", "ios", "properties", "bundle_ids", "items").getValue("pattern").jsonPrimitive.content)
        assertEquals(PluginRules.MAX_STEPS.toString(), schema.at("definitions", "iosRecipe", "properties", "steps").getValue("maxItems").jsonPrimitive.content)
        assertEquals(DeviceOs.entries.map { it.id }, schema.at("definitions", "example", "properties", "os").strings("enum"))
    }
}
