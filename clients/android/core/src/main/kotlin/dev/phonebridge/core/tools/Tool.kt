package dev.phonebridge.core.tools

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
    /** Stable snake_case name, e.g. `whatsapp_send`. */
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

/** Expected, agent-visible failure. The message should tell the agent what to do next. */
class ToolException(message: String) : Exception(message)

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
    override fun unavailableReason(): String? = unavailable()
    override fun describeCall(args: JsonObject): String = describe(args)
    override suspend fun call(args: JsonObject, ctx: CallContext): ToolResult = handler(args, ctx)
}
