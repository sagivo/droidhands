package dev.phonebridge.core.mcp

import dev.phonebridge.core.auth.ToolRule
import dev.phonebridge.core.policy.PolicyEngine
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.Tool
import dev.phonebridge.core.tools.ToolRegistry
import dev.phonebridge.core.tools.ToolResult
import dev.phonebridge.core.tools.tool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Name of the meta tool created by [capabilitiesTool]. */
const val CAPABILITIES_TOOL_NAME = "bridge_capabilities"

/**
 * The `bridge_capabilities` meta tool (scope "", Risk.READ): returns the calling token's name, scopes,
 * its grant in plain words (`access`, see [AccessSummary]) and advanced controls (app access summary,
 * per-tool rules, active hours, whether one-time codes are hidden, risk cap; see [PolicySummary]), plus every tool visible to that token (in scope, not BLOCKed
 * and within its risk cap) with `available` and `unavailable_reason`, and [extraStatus] (platform status
 * such as accessibility/notification access/locked) merged in.
 *
 * Output: `{"token":{"name","scopes","access":{…},"app_access":{…},"tool_rules":{name:rule},"active_hours":{…},
 * "one_time_codes_hidden","max_risk"},"status":{…extraStatus},"tools":[{"name","title","risk","scope","rule"?,
 * "available","unavailable_reason"?}]}`. [extraStatus] values may be strings, numbers, booleans,
 * null, lists/arrays, maps (nested) or [JsonElement]s; anything else is rendered with
 * `toString()`. An exception from [extraStatus] is reported as `status.error` instead of failing.
 */
fun capabilitiesTool(
    registry: ToolRegistry,
    extraStatus: () -> Map<String, Any?> = { emptyMap() },
): Tool = tool(
    name = CAPABILITIES_TOOL_NAME,
    title = "PhoneBridge capabilities",
    description = "Describes what this PhoneBridge connection can do right now: the token's name and scopes, " +
        "the access the user gave this agent (what it may and may not do, which apps it may use, whether actions ask the user " +
        "first, where the user can change it), per-tool rules, active hours, whether one-time codes are hidden, " +
        "the phone's status (for example whether accessibility or notification access is enabled, or the phone is locked), " +
        "and every tool this token can use with whether it is available now and why not. " +
        "Call it when unsure which tools work or after a tool reports it is unavailable.",
    scope = "",
    risk = Risk.READ,
) { _, ctx ->
    val status = try {
        StatusJson.toJsonObject(extraStatus())
    } catch (e: Exception) {
        buildJsonObject { put("error", "Could not read phone status (${ToolText.describe(e)})") }
    }
    // Tools above the token's risk cap are hidden like out-of-scope ones (PolicyEngine refuses them).
    val inScope = registry.all().filter { Scopes.allows(ctx.scopes, it.scope) && !PolicyEngine.exceedsMaxRisk(ctx.maxRisk, it.risk) }
    ToolResult.json {
        putJsonObject("token") {
            put("name", ctx.tokenName)
            putJsonArray("scopes") { ctx.scopes.sorted().forEach { add(JsonPrimitive(it)) } }
            PolicySummary.put(this, ctx, inScope.mapTo(HashSet()) { it.name })
        }
        put("status", status)
        putJsonArray("tools") {
            for (t in inScope) {
                val rule = ctx.toolRules[t.name]
                if (rule == ToolRule.BLOCK) continue
                val reason = ToolText.unavailableReason(t)
                addJsonObject {
                    put("name", t.name)
                    put("title", t.title)
                    put("risk", t.risk.name)
                    put("scope", t.scope)
                    if (rule != null) put("rule", rule.name)
                    put("available", reason == null)
                    if (reason != null) put("unavailable_reason", reason)
                }
            }
        }
    }
}

/** Converts loosely-typed platform status values to JSON (pure). */
internal object StatusJson {
    private const val MAX_DEPTH = 8

    fun toJsonObject(map: Map<String, Any?>): JsonObject = JsonObject(map.mapValues { (_, v) -> toJson(v, 0) })

    fun toJson(value: Any?, depth: Int): JsonElement {
        if (depth > MAX_DEPTH) return JsonPrimitive(value.toString())
        return when (value) {
            null -> JsonNull
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Double -> if (value.isFinite()) JsonPrimitive(value) else JsonNull
            is Float -> if (value.isFinite()) JsonPrimitive(value) else JsonNull
            is Number -> JsonPrimitive(value)
            is Char -> JsonPrimitive(value.toString())
            is Enum<*> -> JsonPrimitive(value.name)
            is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v, depth + 1) })
            is Iterable<*> -> JsonArray(value.map { toJson(it, depth + 1) })
            is Array<*> -> JsonArray(value.map { toJson(it, depth + 1) })
            is IntArray -> JsonArray(value.map { JsonPrimitive(it) })
            is LongArray -> JsonArray(value.map { JsonPrimitive(it) })
            is BooleanArray -> JsonArray(value.map { JsonPrimitive(it) })
            is DoubleArray -> JsonArray(value.map { toJson(it, depth + 1) })
            else -> JsonPrimitive(value.toString())
        }
    }
}
