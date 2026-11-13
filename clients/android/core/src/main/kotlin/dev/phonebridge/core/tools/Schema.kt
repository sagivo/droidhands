package dev.phonebridge.core.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Builds a JSON Schema `{"type":"object","properties":{...},"required":[...]}`. */
fun objectSchema(block: SchemaBuilder.() -> Unit): JsonObject = SchemaBuilder().apply(block).build()

class SchemaBuilder internal constructor() {
    private val properties = LinkedHashMap<String, JsonObject>()
    private val required = mutableListOf<String>()

    private fun add(name: String, required: Boolean, schema: JsonObject) {
        properties[name] = schema
        if (required) this.required += name
    }

    fun string(name: String, description: String, required: Boolean = false, enum: List<String>? = null) =
        add(name, required, buildJsonObject {
            put("type", "string")
            put("description", description)
            if (enum != null) put("enum", JsonArray(enum.map(::JsonPrimitive)))
        })

    fun integer(name: String, description: String, required: Boolean = false, minimum: Long? = null, maximum: Long? = null) =
        add(name, required, buildJsonObject {
            put("type", "integer")
            put("description", description)
            if (minimum != null) put("minimum", minimum)
            if (maximum != null) put("maximum", maximum)
        })

    fun number(name: String, description: String, required: Boolean = false) =
        add(name, required, buildJsonObject {
            put("type", "number")
            put("description", description)
        })

    fun boolean(name: String, description: String, required: Boolean = false) =
        add(name, required, buildJsonObject {
            put("type", "boolean")
            put("description", description)
        })

    fun stringArray(name: String, description: String, required: Boolean = false, enum: List<String>? = null) =
        add(name, required, buildJsonObject {
            put("type", "array")
            put("description", description)
            put("items", buildJsonObject {
                put("type", "string")
                if (enum != null) put("enum", JsonArray(enum.map(::JsonPrimitive)))
            })
        })

    /** Free-form object (e.g. intent extras). */
    fun obj(name: String, description: String, required: Boolean = false) =
        add(name, required, buildJsonObject {
            put("type", "object")
            put("description", description)
        })

    internal fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(properties))
        if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
    }
}
