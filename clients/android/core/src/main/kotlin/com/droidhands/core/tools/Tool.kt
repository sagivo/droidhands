package com.droidhands.core.tools

import kotlinx.serialization.json.JsonObject

/** How dangerous a tool is. Drives on-phone confirmation (see docs/SECURITY.md). */
enum class Risk {
    /** No side effects. */
    READ,

    /** Local, reversible side effects (open an app, change volume, tap on screen). */
    ACTION,

    /** Acts toward other people / the outside world, or is hard to undo (send message, call). */
    SENSITIVE,
}

/**
 * A capability exposed to AI agents as an MCP tool. Names and argument schemas are the
 * platform-neutral public API documented in docs/TOOLS.md.
 */
interface Tool {
    /** Stable snake_case name, e.g. `sms_send`. */
    val name: String

    /** Short human title, e.g. "Send WhatsApp message". */
    val title: String

    /** LLM-facing description: what it does, when to use it, important caveats. */
    val description: String

    /** Scope a token must hold (see [Scopes]). Empty string = always allowed. */
    val scope: String

    val risk: Risk

    /** JSON Schema (type=object) for the arguments. Build with [objectSchema]. */
    val inputSchema: JsonObject

    /** Max execution time, excluding time spent waiting for user confirmation. */
    val timeoutMs: Long get() = 60_000

    /**
     * If true, the tool resolves its targets first and then calls
     * [CallContext.requireConfirmation] with a summary of the *resolved* action before any side
     * effect. If false, the server confirms up front (when policy requires) using [describeCall].
     */
    val confirmsInternally: Boolean get() = false

    /**
     * If true, every call needs on-phone approval, whatever the token's confirmation policy and per-tool
     * rules say (an ALLOW rule included; a BLOCK rule still refuses). [com.droidhands.core.policy.PolicyEngine]
     * enforces it. Used for the actions of unreviewed local plugins (docs/PLUGINS.md §9).
     */
    val alwaysConfirm: Boolean get() = false

    /** Null if the tool can run now, otherwise a human-readable reason (e.g. missing permission). */
    fun unavailableReason(): String? = null

    /** One-line summary of what a call with [args] will do; shown in confirmation prompts. */
    fun describeCall(args: JsonObject): String = title

    /**
     * Executes the tool. Throw [ToolException] for expected failures (bad args, missing contact,
     * permission denied) — it becomes an `isError` result with the message shown to the agent.
     */
    suspend fun call(args: JsonObject, ctx: CallContext): ToolResult
}

/**
 * A tool that stands for other tools, such as `app_action_run` (docs/PLUGINS.md). The server first calls
 * [resolve] to learn which [Dispatch.tool] the call really runs, then puts that tool through the whole
 * `tools/call` pipeline as if the agent had called it directly: its scope, the token's policy (per-tool
 * rules keyed by the resolved tool's name, risk cap, active hours, rate limits), confirmation by its
 * risk, its timeout, stop checks and the audit entry, all under the resolved tool's name and arguments.
 * The dispatching tool's own scope, BLOCK rule and risk cap are checked first, and again by every stop
 * check while the call runs. An ASK rule on the dispatching tool forces approval of the resolved call;
 * an ALLOW rule on it does not skip the approval the resolved tool needs.
 *
 * Dispatch does not nest: resolving to another [DispatchTool] is refused. The server never uses [call].
 */
interface DispatchTool : Tool {
    /**
     * Picks the tool to run for [args]. Must not have side effects or ask for confirmation
     * ([CallContext.requireConfirmation] fails here). Check app access here: throw [AppBlockedException]
     * for a blocked app (audited DENIED_APP), or [ToolException] when the call can't be resolved, with a
     * message that tells the agent what exists.
     */
    suspend fun resolve(args: JsonObject, ctx: CallContext): Dispatch

    override suspend fun call(args: JsonObject, ctx: CallContext): ToolResult =
        throw ToolException("'$name' runs other tools and can only be called through the DroidHands server.")
}

/**
 * What a [DispatchTool] resolved to: run [tool] with [args]. [alias] is another name the call is known
 * by (for example `com.google.android.apps.messaging/send_sms` for a plugin action that runs `sms_send`): a BLOCK
 * rule on it refuses the call too, and the audit entry mentions it.
 */
class Dispatch(val tool: Tool, val args: JsonObject, val alias: String? = null)

/**
 * Expected, agent-visible failure. The message should tell the agent what to do next.
 * Open so the pipeline can tell specific refusals apart (see [AppBlockedException]).
 */
open class ToolException(message: String) : Exception(message)

/**
 * Concise way to declare a tool.
 *
 * ```
 * tool(
 *     name = "flashlight_set", title = "Turn flashlight on/off", scope = Scopes.DEVICE, risk = Risk.ACTION,
 *     description = "...",
 *     schema = objectSchema { boolean("on", "true to turn on", required = true) },
 * ) { args, ctx -> ...; ToolResult.text("Flashlight on") }
 * ```
 */
fun tool(
    name: String,
    title: String,
    description: String,
    scope: String,
    risk: Risk,
    schema: JsonObject = objectSchema { },
    timeoutMs: Long = 60_000,
    confirmsInternally: Boolean = false,
    alwaysConfirm: Boolean = false,
    unavailable: () -> String? = { null },
    describe: (JsonObject) -> String = { title },
    handler: suspend (args: JsonObject, ctx: CallContext) -> ToolResult,
): Tool = object : Tool {
    override val name = name
    override val title = title
    override val description = description
    override val scope = scope
    override val risk = risk
    override val inputSchema = schema
    override val timeoutMs = timeoutMs
    override val confirmsInternally = confirmsInternally
    override val alwaysConfirm = alwaysConfirm
    override fun unavailableReason(): String? = unavailable()
    override fun describeCall(args: JsonObject): String = describe(args)
    override suspend fun call(args: JsonObject, ctx: CallContext): ToolResult = handler(args, ctx)
}
