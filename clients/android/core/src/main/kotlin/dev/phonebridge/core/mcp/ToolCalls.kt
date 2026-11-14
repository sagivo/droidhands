package dev.phonebridge.core.mcp

import dev.phonebridge.core.audit.AuditEntry
import dev.phonebridge.core.audit.AuditOutcome
import dev.phonebridge.core.auth.TokenRecord
import dev.phonebridge.core.confirm.ConfirmationProvider
import dev.phonebridge.core.confirm.ConfirmationRequest
import dev.phonebridge.core.confirm.ConfirmationResult
import dev.phonebridge.core.policy.GlobalSecurity
import dev.phonebridge.core.policy.PolicyDecision
import dev.phonebridge.core.policy.PolicyEngine
import dev.phonebridge.core.tools.AppAccess
import dev.phonebridge.core.tools.AppBlockedException
import dev.phonebridge.core.tools.CallContext
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.Tool
import dev.phonebridge.core.tools.ToolContent
import dev.phonebridge.core.tools.ToolException
import dev.phonebridge.core.tools.ToolRegistry
import dev.phonebridge.core.tools.ToolResult
import dev.phonebridge.core.util.Encoding
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

/**
 * The `tools/call` pipeline (docs/ARCHITECTURE.md "Request lifecycle"): lookup and scope check,
 * kill switch, device-wide security settings, policy (tool rules, risk cap, active hours, rate limits),
 * confirmation, execution with timeout, result encoding, audit.
 *
 * Timeout model: [Tool.timeoutMs] covers the tool's own work only. Pre-confirmation happens
 * before the budget starts. For internally-confirming tools the budget is paused while
 * [CallContext.requireConfirmation] waits for the user, so a slow approval never kills the tool.
 * Each prompt has its own limit, the server's confirmation timeout.
 *
 * The tool gets a [CallContext] with the token's [AppAccess] (its app policy plus the global
 * never-allow list) and the global one-time-code setting. An [AppBlockedException] from the tool is
 * audited as DENIED_APP; policy refusals as DENIED_POLICY (or RATE_LIMITED).
 *
 * Stopping: the kill switch and the token's current record (from [currentToken]: not deleted, revoked or
 * expired, and still allowed this tool) are checked again before each prompt, after each answer and right
 * before the tool starts; tools check them through [CallContext.ensureStillAllowed] after their own waits
 * and before each side effect. A call stopped that way, or cancelled through [InFlightCalls.cancel], is
 * audited CANCELLED with the reason and whether the tool had started.
 *
 * Every call ends with exactly one audit entry, cancelled ones included. Requests refused before
 * anything ran (missing name, bad arguments, unknown tool, missing scope, paused, policy and rate-limit
 * refusals) go through [AuditThrottle.recordRefusal], so they cannot flood the log.
 */
internal class ToolCalls(
    private val registry: ToolRegistry,
    private val policy: PolicyEngine,
    private val confirmations: ConfirmationProvider,
    private val audit: AuditThrottle,
    private val isAccessEnabled: () -> Boolean,
    private val confirmationTimeoutMs: Long,
    private val clock: () -> Long,
    private val globalSecurity: () -> GlobalSecurity = { GlobalSecurity() },
    /** The token's current record by id (null once deleted), read fresh for every stop check. */
    private val currentToken: (String) -> TokenRecord?,
) {
    private val random = SecureRandom()

    /**
     * Handles one `tools/call` request and returns the full JSON-RPC response. [handle] ties the call to
     * [InFlightCalls] (stop reason, abandoned by the transport).
     */
    suspend fun call(
        id: JsonElement,
        params: JsonObject,
        token: TokenRecord,
        transport: String,
        handle: InFlightCall = InFlightCall(token.id),
    ): JsonObject {
        val started = clock()
        val name = (params["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

        fun entry(outcome: AuditOutcome, tool: String?, args: JsonElement?, detail: String?): AuditEntry {
            val notes = listOfNotNull(detail, ABANDONED_NOTE.takeIf { handle.abandoned })
            return AuditEntry(
                timestamp = started,
                tokenId = token.id,
                tokenName = token.name,
                method = "tools/call",
                tool = tool?.let { ToolText.truncate(it, MAX_TOOL_NAME_CHARS) },
                argsPreview = args?.let(ToolText::argsPreview),
                outcome = outcome,
                durationMs = (clock() - started).coerceAtLeast(0),
                transport = transport,
                detail = notes.joinToString("; ").ifEmpty { null }?.let { ToolText.truncate(it, ToolText.PREVIEW_CHARS) },
            )
        }

        fun record(outcome: AuditOutcome, tool: String?, args: JsonElement?, detail: String?) =
            audit.record(entry(outcome, tool, args, detail))

        fun refuse(outcome: AuditOutcome, tool: String?, args: JsonElement?, detail: String?) =
            audit.recordRefusal(entry(outcome, tool, args, detail), source = "token:${token.id}")

        if (name.isEmpty()) {
            refuse(AuditOutcome.UNKNOWN_TOOL, null, null, "missing tool name")
            return JsonRpc.error(id, JsonRpc.INVALID_PARAMS, "Invalid params: \"name\" (the tool name) is required")
        }
        val args = when (val raw = params["arguments"]) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> raw
            else -> {
                refuse(AuditOutcome.TOOL_ERROR, name, raw, "arguments is not an object")
                return JsonRpc.error(id, JsonRpc.INVALID_PARAMS, "Invalid params: \"arguments\" must be a JSON object")
            }
        }
        val tool = registry.get(name)
        if (tool == null) {
            refuse(AuditOutcome.UNKNOWN_TOOL, name, args, null)
            val shown = ToolText.truncate(name, MAX_TOOL_NAME_CHARS)
            return JsonRpc.error(id, JsonRpc.INVALID_PARAMS, "Unknown tool '$shown'. Call tools/list to see the tools available to this token.")
        }
        if (!Scopes.allows(token.scopes, tool.scope)) {
            refuse(AuditOutcome.DENIED_SCOPE, name, args, "missing scope ${tool.scope}")
            return JsonRpc.error(
                id, JsonRpc.INVALID_PARAMS,
                "Tool '$name' is not available to this token: it needs the '${tool.scope}' scope. " +
                    "Ask the user to grant it to this token in the PhoneBridge app.",
            )
        }

        val outcome = try {
            run(tool, args, token, transport, handle)
        } catch (e: CancellationException) {
            // Cancelled through InFlightCalls.cancel (pause, revoke, delete): still exactly one entry.
            record(AuditOutcome.CANCELLED, name, args, stoppedDetail(handle.stopReason ?: StopReason.STOPPED, handle.toolStarted))
            throw e
        } catch (e: Exception) {
            // A bug in the pipeline itself (tool failures are handled inside run()).
            Outcome(ToolResult.error("Internal error: ${ToolText.describe(e)}"), AuditOutcome.EXCEPTION, ToolText.describe(e))
        }
        if (outcome.refused) refuse(outcome.audit, name, args, outcome.detail) else record(outcome.audit, name, args, outcome.detail)
        return JsonRpc.result(id, encodeResult(outcome.result))
    }

    /** [refused]: refused before anything ran (throttled in the audit log). */
    private class Outcome(val result: ToolResult, val audit: AuditOutcome, val detail: String? = null, val refused: Boolean = false)

    private fun accessEnabled(): Boolean = try {
        isAccessEnabled()
    } catch (e: Exception) {
        false // Fail closed.
    }

    /** Null while [handle] may go on; otherwise why it must stop (see the class KDoc). */
    private fun stopReason(handle: InFlightCall, token: TokenRecord, tool: Tool): StopReason? {
        handle.stopReason?.let { return it }
        if (!accessEnabled()) return StopReason.PAUSED
        val current = try {
            currentToken(token.id)
        } catch (e: Exception) {
            return StopReason.STOPPED // Fail closed.
        } ?: return StopReason.DELETED
        if (current.revoked) return StopReason.REVOKED
        val expiresAt = current.expiresAt
        if (expiresAt != null && clock() >= expiresAt) return StopReason.EXPIRED
        if (!McpServer.isVisible(current, tool)) return StopReason.TOOL_WITHDRAWN
        return null
    }

    private fun stopped(reason: StopReason) =
        Outcome(ToolResult.error(reason.message), AuditOutcome.CANCELLED, stoppedDetail(reason, toolStarted = false))

    private suspend fun run(tool: Tool, args: JsonObject, token: TokenRecord, transport: String, handle: InFlightCall): Outcome {
        if (!accessEnabled()) return Outcome(ToolResult.error(PAUSED_MESSAGE), AuditOutcome.PAUSED, refused = true)

        val security = try {
            globalSecurity()
        } catch (e: Exception) {
            // Fail closed: without the never-allow list no app-facing call is safe.
            return Outcome(ToolResult.error(SECURITY_UNAVAILABLE_MESSAGE), AuditOutcome.EXCEPTION, "security settings: ${ToolText.describe(e)}")
        }

        val decision = policy.evaluate(token, tool)
        if (decision is PolicyDecision.Deny) {
            val audit = if (decision.rateLimited) AuditOutcome.RATE_LIMITED else AuditOutcome.DENIED_POLICY
            return Outcome(ToolResult.error(decision.reason), audit, decision.reason, refused = true)
        }
        val needsConfirmation = decision == PolicyDecision.RequireConfirmation

        val state = CallState()

        /** Null while the call may go on; otherwise why not (the first reason is kept for the audit entry). */
        fun checkStop(): StopReason? = stopReason(handle, token, tool)?.also { state.stop.compareAndSet(null, it) }

        if (needsConfirmation && !tool.confirmsInternally) {
            checkStop()?.let { return stopped(it) }
            val answer = ask(token, tool, ToolText.confirmationSummary(tool, args), transport)
            if (answer !is Answer.Approved) {
                // Pausing also denies open prompts: report the pause rather than a denial by the user.
                checkStop()?.let { return stopped(it) }
                return Outcome(ToolResult.error(answer.message), answer.audit, answer.detail)
            }
        }
        checkStop()?.let { return stopped(it) }
        currentCoroutineContext().ensureActive()

        val deadline = PausableDeadline(tool.timeoutMs)
        val stillAllowed: suspend () -> Unit = {
            currentCoroutineContext().ensureActive()
            checkStop()?.let { throw CallStoppedException(it) }
        }
        val confirmer: suspend (String) -> Unit = confirmer@{ summary ->
            state.confirmCalls.incrementAndGet()
            stillAllowed()
            if (!tool.confirmsInternally || !needsConfirmation) return@confirmer
            if (deadline.isExpired) throw ToolException(timeoutMessage(tool))
            val text = summary.takeIf { it.isNotBlank() } ?: ToolText.confirmationSummary(tool, args)
            deadline.pause()
            val answer = try {
                ask(token, tool, text, transport)
            } finally {
                deadline.resume()
            }
            if (answer is Answer.Approved) {
                state.approved.set(true)
                stillAllowed() // Paused or revoked while the prompt was open.
            } else {
                checkStop()?.let { throw CallStoppedException(it) }
                state.failure.compareAndSet(null, answer)
                throw ToolException(answer.message)
            }
        }
        val ctx = CallContext(
            tokenId = token.id,
            tokenName = token.name,
            scopes = token.scopes,
            transport = transport,
            requestId = newId(10),
            appAccess = AppAccess.of(token.appPolicy, security.neverAllowApps),
            redactOneTimeCodes = security.redactOneTimeCodes,
            toolRules = token.toolRules,
            activeHours = token.activeHours,
            maxRisk = token.maxRisk,
            stillAllowed = stillAllowed,
            confirmer = confirmer,
        )

        handle.toolStarted = true
        val execution = execute(tool, args, ctx, deadline)
        return toOutcome(tool, execution, state, needsConfirmation)
    }

    /** What happened inside one call: internal confirmations and the first stop reason seen. */
    private class CallState {
        val confirmCalls = AtomicInteger()
        val approved = AtomicBoolean()
        val failure = AtomicReference<Answer?>(null)
        val stop = AtomicReference<StopReason?>(null)
    }

    private fun toOutcome(tool: Tool, execution: Execution, state: CallState, needsConfirmation: Boolean): Outcome {
        val details = ArrayList<String>(2)
        if (needsConfirmation && (!tool.confirmsInternally || state.approved.get())) details += "approved by user"
        if (tool.confirmsInternally && needsConfirmation && state.confirmCalls.get() == 0) details += "tool skipped confirmation"
        val failure = state.failure.get()
        val stop = state.stop.get()

        val outcome = when (execution) {
            is Execution.Completed -> {
                val result = execution.result
                when {
                    !result.isError && stop != null -> {
                        details += "tool reported success after it was told to stop (${stop.detail})"
                        Outcome(result, AuditOutcome.OK)
                    }
                    !result.isError && failure != null -> {
                        details += "tool reported success after the confirmation failed"
                        Outcome(result, AuditOutcome.OK)
                    }
                    !result.isError -> Outcome(result, AuditOutcome.OK)
                    stop != null -> Outcome(result, AuditOutcome.CANCELLED, stoppedDetail(stop, toolStarted = true))
                    failure != null -> Outcome(result, failure.audit, failure.detail)
                    else -> Outcome(result, AuditOutcome.TOOL_ERROR, firstText(result))
                }
            }
            is Execution.Failed -> when {
                stop != null -> Outcome(ToolResult.error(execution.message), AuditOutcome.CANCELLED, stoppedDetail(stop, toolStarted = true))
                failure != null -> Outcome(ToolResult.error(execution.message), failure.audit, failure.detail)
                else -> Outcome(ToolResult.error(execution.message), AuditOutcome.TOOL_ERROR, execution.message)
            }
            is Execution.AppBlocked ->
                Outcome(ToolResult.error(execution.message), AuditOutcome.DENIED_APP, "blocked app ${execution.packageName}")
            is Execution.InnerTimeout -> Outcome(ToolResult.error(execution.message), AuditOutcome.TIMEOUT, execution.message)
            is Execution.Crashed -> Outcome(ToolResult.error("Internal error: ${execution.description}"), AuditOutcome.EXCEPTION, execution.description)
            Execution.TimedOut -> Outcome(ToolResult.error(timeoutMessage(tool)), AuditOutcome.TIMEOUT, "timed out after ${ToolText.shortSeconds(tool.timeoutMs)}")
        }
        val allDetails = listOfNotNull(outcome.detail) + details
        return Outcome(outcome.result, outcome.audit, allDetails.joinToString("; ").ifEmpty { null })
    }

    private sealed interface Execution {
        class Completed(val result: ToolResult) : Execution
        class Failed(val message: String) : Execution
        class AppBlocked(val message: String, val packageName: String) : Execution
        class InnerTimeout(val message: String) : Execution
        class Crashed(val description: String) : Execution
        data object TimedOut : Execution
    }

    /**
     * Runs the tool in its own job so that a tool stuck in non-cancellable blocking code cannot
     * hold the response past its deadline: on timeout the job is cancelled and abandoned. The job
     * is also cancelled if the call itself is cancelled.
     */
    private suspend fun execute(tool: Tool, args: JsonObject, ctx: CallContext, deadline: PausableDeadline): Execution = coroutineScope {
        val toolScope = CoroutineScope(coroutineContext + Job() + CoroutineName("tool:${tool.name}"))
        val work = toolScope.async { invoke(tool, args, ctx) }
        deadline.start(this)
        try {
            val result = select<Execution> {
                work.onAwait { it }
                deadline.expired.onAwait { Execution.TimedOut }
            }
            if (result === Execution.TimedOut) work.cancel()
            result
        } catch (e: CancellationException) {
            work.cancel()
            currentCoroutineContext().ensureActive() // The call was cancelled: propagate.
            Execution.Crashed("tool was cancelled")
        } finally {
            deadline.stop()
        }
    }

    private suspend fun invoke(tool: Tool, args: JsonObject, ctx: CallContext): Execution = try {
        Execution.Completed(tool.call(args, ctx))
    } catch (e: AppBlockedException) {
        Execution.AppBlocked(e.message?.takeIf { it.isNotBlank() } ?: "${tool.title} was refused: the app is blocked for this agent.", e.packageName)
    } catch (e: ToolException) {
        Execution.Failed(e.message?.takeIf { it.isNotBlank() } ?: "${tool.title} failed.")
    } catch (e: CancellationException) {
        // Our own cancellation (timeout or call cancelled) propagates. A CancellationException
        // escaping while we are still active is the tool's own bug (e.g. an inner withTimeout).
        if (!currentCoroutineContext().isActive) throw e
        if (e is TimeoutCancellationException) {
            Execution.InnerTimeout("Timed out inside the tool: ${e.message ?: "operation took too long"}. Try again later.")
        } else {
            Execution.Crashed(ToolText.describe(e))
        }
    } catch (e: Throwable) {
        Execution.Crashed(ToolText.describe(e))
    }

    private sealed interface Answer {
        val message: String
        val audit: AuditOutcome
        val detail: String?

        data object Approved : Answer {
            override val message = ""
            override val audit = AuditOutcome.OK
            override val detail: String? = null
        }

        class Rejected(override val message: String, override val audit: AuditOutcome, override val detail: String? = null) : Answer
    }

    /** Prompts the user, bounded by the confirmation timeout. Never throws except on cancellation. */
    private suspend fun ask(token: TokenRecord, tool: Tool, summary: String, transport: String): Answer {
        val request = ConfirmationRequest(
            id = newId(16),
            tokenName = token.name,
            toolName = tool.name,
            toolTitle = tool.title,
            summary = summary,
            risk = tool.risk,
            transport = transport,
        )
        val result = try {
            withTimeoutOrNull(confirmationTimeoutMs) { confirmations.requestConfirmation(request) } ?: ConfirmationResult.TIMED_OUT
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            return confirmationUnavailable(e)
        } catch (e: Exception) {
            return confirmationUnavailable(e)
        }
        return when (result) {
            ConfirmationResult.APPROVED -> Answer.Approved
            ConfirmationResult.DENIED -> Answer.Rejected(DENIED_MESSAGE, AuditOutcome.DENIED_BY_USER)
            ConfirmationResult.TIMED_OUT -> Answer.Rejected(
                "The user did not approve this action on the phone within ${ToolText.seconds(confirmationTimeoutMs)}.",
                AuditOutcome.CONFIRM_TIMEOUT,
            )
        }
    }

    private fun confirmationUnavailable(e: Throwable) = Answer.Rejected(
        "Could not ask the user to approve this action on the phone (${ToolText.describe(e)}). " +
            "Ask the user to check that PhoneBridge can show notifications, then retry.",
        AuditOutcome.EXCEPTION,
        "confirmation failed: ${ToolText.describe(e)}",
    )

    private fun timeoutMessage(tool: Tool) =
        "Timed out after ${ToolText.shortSeconds(tool.timeoutMs)}. The action may or may not have completed; check before retrying."

    private fun newId(bytes: Int): String = Encoding.base32(ByteArray(bytes).also(random::nextBytes))

    companion object {
        const val PAUSED_MESSAGE = "Agent access is paused on the phone. Ask the user to resume it in the PhoneBridge app."
        const val DENIED_MESSAGE = "The user denied this action on the phone. Do not retry unless the user asks again."
        const val SECURITY_UNAVAILABLE_MESSAGE =
            "PhoneBridge could not read the phone's security settings, so it refused this call. " +
                "Ask the user to open the PhoneBridge app (restarting it if needed), then retry."

        /** Added to the audit detail of a call whose transport stopped waiting for its result. */
        internal const val ABANDONED_NOTE = "result not delivered: the agent's connection closed first"

        /** Tool names are cut to this length in audit entries and errors (unknown names are agent input). */
        private const val MAX_TOOL_NAME_CHARS = 100

        /** Audit detail of a stopped call: why, and whether the tool had started (it may then have partly acted). */
        internal fun stoppedDetail(reason: StopReason, toolStarted: Boolean): String =
            if (toolStarted) {
                "stopped while running (${reason.detail}); the action may have partly completed"
            } else {
                "stopped before it ran (${reason.detail})"
            }

        /** MCP `CallToolResult`. */
        fun encodeResult(result: ToolResult): JsonObject = buildJsonObject {
            putJsonArray("content") {
                for (c in result.content) when (c) {
                    is ToolContent.Text -> addJsonObject {
                        put("type", "text")
                        put("text", c.text)
                    }
                    is ToolContent.Image -> addJsonObject {
                        put("type", "image")
                        put("data", c.base64Data)
                        put("mimeType", c.mimeType)
                    }
                }
            }
            put("isError", result.isError)
        }

        private fun firstText(result: ToolResult): String? =
            result.content.firstNotNullOfOrNull { (it as? ToolContent.Text)?.text }
    }
}
