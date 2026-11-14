package dev.phonebridge.core.mcp

import dev.phonebridge.core.auth.ActiveHours
import dev.phonebridge.core.auth.AppAccessMode
import dev.phonebridge.core.policy.ActiveWindow
import dev.phonebridge.core.tools.AppAccess
import dev.phonebridge.core.tools.CallContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The agent-facing summary of a token's advanced controls for `bridge_capabilities` (pure).
 *
 * Privacy: the names of blocked and never-allowed apps are not revealed (they would tell the agent
 * which banking or private apps are installed); only allowlisted apps are listed, because the agent
 * may use them anyway.
 */
internal object PolicySummary {
    /**
     * Adds `app_access`, `tool_rules` (rules of the tools in [inScopeTools], BLOCK included so the agent
     * stops trying them), `active_hours`, `one_time_codes_hidden` and `max_risk` (the risk cap's name, or
     * null for none) for [ctx].
     */
    fun put(builder: JsonObjectBuilder, ctx: CallContext, inScopeTools: Set<String>) {
        builder.put("app_access", appAccess(ctx.appAccess))
        builder.putJsonObject("tool_rules") {
            ctx.toolRules.entries.filter { it.key in inScopeTools }.sortedBy { it.key }.forEach { (name, rule) -> put(name, rule.name) }
        }
        builder.put("active_hours", activeHours(ctx.activeHours))
        builder.put("one_time_codes_hidden", ctx.redactOneTimeCodes)
        builder.put("max_risk", ctx.maxRisk?.name)
    }

    fun appAccess(access: AppAccess): JsonObject = buildJsonObject {
        put("mode", access.mode.name)
        val globally = if (access.neverAllow.isEmpty()) "" else " Some apps are never allowed for any agent."
        when (access.mode) {
            AppAccessMode.ALL -> put("summary", "All apps.$globally")
            AppAccessMode.ALLOWLIST -> {
                val allowed = access.packages.filter(access::isAllowed).sorted()
                putJsonArray("allowed_apps") { allowed.forEach { add(JsonPrimitive(it)) } }
                put(
                    "summary",
                    if (allowed.isEmpty()) "No apps: the user has not allowed any app for this agent." else "Only the apps in allowed_apps.",
                )
            }
            AppAccessMode.BLOCKLIST -> {
                put("blocked_apps", access.packages.size)
                put("summary", "All apps except ${access.packages.size} the user blocked for this agent.$globally")
            }
        }
        if (access.isRestricted) {
            put("note", "Tools refuse blocked apps and hide their windows and notifications. Do not try to work around this.")
        }
    }

    fun activeHours(hours: ActiveHours?): JsonObject = buildJsonObject {
        if (hours == null) {
            put("limited", false)
            put("summary", "Always.")
            return@buildJsonObject
        }
        put("limited", true)
        put("start", ActiveWindow.format(hours.startMinute))
        put("end", ActiveWindow.format(hours.endMinute))
        put(
            "summary",
            "Only between ${ActiveWindow.format(hours.startMinute)} and ${ActiveWindow.format(hours.endMinute)}, " +
                "phone-local time. Calls outside this window are refused.",
        )
    }
}
