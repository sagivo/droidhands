package dev.phonebridge.core.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

// Lenient argument accessors: LLMs sometimes send numbers/booleans as strings ("5", "true").
// Absent and JSON null are treated the same. Wrong types throw ToolException with a clear message.

private fun JsonObject.prim(key: String): JsonPrimitive? = when (val v = this[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> v
    else -> throw ToolException("Argument '$key' must be a scalar value, got ${v::class.simpleName}")
}

fun JsonObject.optString(key: String): String? = prim(key)?.content?.takeIf { it.isNotEmpty() }

fun JsonObject.reqString(key: String): String =
    optString(key)?.takeIf { it.isNotBlank() } ?: throw ToolException("Missing required argument '$key'")

fun JsonObject.optLong(key: String): Long? {
    val p = prim(key) ?: return null
    return p.longOrNull ?: p.doubleOrNull?.takeIf { it == Math.floor(it) }?.toLong()
        ?: p.content.trim().toLongOrNull()
        ?: throw ToolException("Argument '$key' must be an integer, got '${p.content}'")
}

fun JsonObject.optInt(key: String): Int? = optLong(key)?.let {
    if (it < Int.MIN_VALUE || it > Int.MAX_VALUE) throw ToolException("Argument '$key' is out of range")
    it.toInt()
}

fun JsonObject.reqInt(key: String): Int = optInt(key) ?: throw ToolException("Missing required argument '$key'")

fun JsonObject.optDouble(key: String): Double? {
    val p = prim(key) ?: return null
    return p.doubleOrNull ?: p.content.trim().toDoubleOrNull()
        ?: throw ToolException("Argument '$key' must be a number, got '${p.content}'")
}

fun JsonObject.reqDouble(key: String): Double = optDouble(key) ?: throw ToolException("Missing required argument '$key'")

fun JsonObject.optBool(key: String): Boolean? {
    val p = prim(key) ?: return null
    return p.booleanOrNull ?: when (p.content.trim().lowercase()) {
        "true", "yes", "1" -> true
        "false", "no", "0" -> false
        else -> throw ToolException("Argument '$key' must be a boolean, got '${p.content}'")
    }
}

fun JsonObject.reqBool(key: String): Boolean = optBool(key) ?: throw ToolException("Missing required argument '$key'")

fun JsonObject.optStringList(key: String): List<String>? = when (val v = this[key]) {
    null, JsonNull -> null
    is JsonArray -> v.map { (it as? JsonPrimitive)?.content ?: throw ToolException("Argument '$key' must be an array of strings") }
    is JsonPrimitive -> v.content.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    else -> throw ToolException("Argument '$key' must be an array of strings")
}

fun JsonObject.optObject(key: String): JsonObject? = when (val v = this[key]) {
    null, JsonNull -> null
    is JsonObject -> v
    else -> throw ToolException("Argument '$key' must be an object")
}
