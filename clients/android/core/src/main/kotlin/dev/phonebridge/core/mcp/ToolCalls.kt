package dev.phonebridge.core.mcp

import dev.phonebridge.core.audit.AuditEntry
import dev.phonebridge.core.audit.AuditLog
import dev.phonebridge.core.audit.AuditOutcome
import dev.phonebridge.core.auth.TokenRecord
import dev.phonebridge.core.confirm.ConfirmationProvider
import dev.phonebridge.core.confirm.ConfirmationRequest
import dev.phonebridge.core.confirm.ConfirmationResult
import dev.phonebridge.core.policy.PolicyDecision
import dev.phonebridge.core.policy.PolicyEngine
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
 * kill switch, policy, confirmation, execution with timeout, result encoding, audit.
 *
 * Timeout model: [Tool.timeoutMs] covers the tool's own work only. Pre-confirmation happens
 * before the budget starts. For internally-confirming tools the budget is paused while
 * [CallContext.requireConfirmation] waits for the user, so a slow approval never kills the tool.
 * Each prompt has its own limit, the server's confirmation timeout.
 *
 * Every call ends with exactly one audit entry.
 */
internal class ToolCalls(
    private val registry: ToolRegistry,
    private val policy: PolicyEngine,
    private val confirmations: ConfirmationProvider,
    private val audit: AuditLog,
    private val isAccessEnabled: () -> Boolean,
    private val confirmationTimeoutMs: Long,
    private val clock: () -> Long,
) {
    private val random = SecureRandom()

    /** Handles one `tools/call` request and returns the full JSON-RPC response. */
    suspend fun call(id: JsonElement, params: JsonObject, token: TokenRecord, transport: String): JsonObject {
        val started = clock()
        val name = (params["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

        fun record(outcome: AuditOutcome, tool: String?, args: JsonElement?, detail: String?) = safeRecord(
            AuditEntry(
                timestamp = started,
                tokenId = token.id,
                tokenName = token.name,
                method = "tools/call",
                tool = tool,
                argsPreview = args?.let(ToolText::argsPreview),
                outcome = outcome,
                durationMs = (clock() - started).coerceAtLeast(0),
                transport = transport,
                detail = detail?.let { ToolText.truncate(it, ToolText.PREVIEW_CHARS) },
            ),
        )

        if (name.isEmpty()) {
            record(AuditOutcome.UNKNOWN_TOOL, null, null, "missing tool name")
            return JsonRpc.error(id, JsonRpc.INVALID_PARAMS, "Invalid params: \"name\" (the tool name) is required")
        }
        val args = when (val raw = params["arguments"]) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> raw
            else -> {
                record(AuditOutcome.TOOL_ERROR, name, raw, "arguments is not an object")
                return JsonRpc.error(id, JsonRpc.INVALID_PARAMS, "Invalid params: \"arguments\" must be a JSON object")
            }
        }
        val tool = registry.get(name)
        if (tool == null) {
            record(AuditOutcome.UNKNOWN_TOOL, name, args, null)
            return JsonRpc.error(id, JsonRpc.INVALID_PARAMS, "Unknown tool '$name'. Call tools/list to see the tools available to this token.")
        }
        if (!Scopes.allows(token.scopes, tool.scope)) {
            record(AuditOutcome.DENIED_SCOPE, name, args, "missing scope ${tool.scope}")
            return JsonRpc.error(
                id, JsonRpc.INVALID_PARAMS,
                "Tool '$name' is not available to this token: it needs the '${tool.scope}' scope. " +
                    "Ask the user to grant it to this token in the PhoneBridge app.",
            )
        }

        val outcome = try {
            run(tool, args, token, transport)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A bug in the pipeline itself (tool failures are handled inside run()).
            Outcome(ToolResult.error("Internal error: ${ToolText.describe(e)}"), AuditOutcome.EXCEPTION, ToolText.describe(e))
        }
        record(outcome.audit, name, args, outcome.detail)
        return JsonRpc.result(id, encodeResult(outcome.result))
    }

    private class Outcome(val result: ToolResult, val audit: AuditOutcome, val detail: String? = null)

    private suspend fun run(tool: Tool, args: JsonObject, token: TokenRecord, transport: String): Outcome {
        val enabled = try {
            isAccessEnabled()
        } catch (e: Exception) {
            false // Fail closed.
        }
        if (!enabled) return Outcome(ToolResult.error(PAUSED_MESSAGE), AuditOutcome.PAUSED)

        val decision = policy.evaluate(token, tool)
        if (decision is PolicyDecision.Deny) {
            // PolicyEngine only denies for rate limits today; any other policy denial is closest to a scope denial.
            val audit = if (decision.rateLimited) AuditOutcome.RATE_LIMITED else AuditOutcome.DENIED_SCOPE
            return Outcome(ToolResult.error(decision.reason), audit, decision.reason)
        }
        val needsConfirmation = decision == PolicyDecision.RequireConfirmation

        if (needsConfirmation && !tool.confirmsInternally) {
            val answer = ask(token, tool, ToolText.confirmationSummary(tool, args), transport)
            if (answer !is Answer.Approved) return Outcome(ToolResult.error(answer.message), answer.audit, answer.detail)
        }

        val confirm = InternalConfirmations()
        val deadline = PausableDeadline(tool.timeoutMs)
        val confirmer: suspend (String) -> Unit = confirmer@{ summary ->
            confirm.calls.incrementAndGet()
            currentCoroutineContext().ensureActive()
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
                confirm.approved.set(true)
            } else {
                confirm.failure.compareAndSet(null, answer)
                throw ToolException(answer.message)
            }
        }
        val ctx = CallContext(
            tokenId = token.id,
            tokenName = token.name,
            scopes = token.scopes,
            transport = transport,
            requestId = newId(10),
            confirmer = confirmer,
        )

        val execution = execute(tool, args, ctx, deadline)
        return toOutcome(tool, execution, confirm, needsConfirmation)
    }

    private class InternalConfirmations {
        val calls = AtomicInteger()
        val approved = AtomicBoolean()
        val failure = AtomicReference<Answer?>(null)
    }

    private fun toOutcome(tool: Tool, execution: Execution, confirm: InternalConfirmations, needsConfirmation: Boolean): Outcome {
        val details = ArrayList<String>(2)
        if (needsConfirmation && (!tool.confirmsInternally || confirm.approved.get())) details += "approved by user"
        if (tool.confirmsInternally && needsConfirmation && confirm.calls.get() == 0) details += "tool skipped confirmation"
        val failure = confirm.failure.get()

        val outcome = when (execution) {
            is Execution.Completed -> {
                val result = execution.result
                when {
                    !result.isError && failure != null -> {
                        details += "tool reported success after the confirmation failed"
                        Outcome(result, AuditOutcome.OK)
                    }
                    !result.isError -> Outcome(result, AuditOutcome.OK)
                    failure != null -> Outcome(result, failure.audit, failure.detail)
                    else -> Outcome(result, AuditOutcome.TOOL_ERROR, firstText(result))
                }
            }
            is Execution.Failed -> when {
                failure != null -> Outcome(ToolResult.error(execution.message), failure.audit, failure.detail)
                else -> Outcome(ToolResult.error(execution.message), AuditOutcome.TOOL_ERROR, execution.message)
            }
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
        class InnerTimeout(val message: String) : Execution
        class Crashed(val description: String) : Execution
        data object TimedOut : Execution
    }

    /**
     * Runs the tool in its own job so that a tool stuck in non-cancellable blocking code cannot
     * hold the response past its deadline: on timeout the job is cancelled and abandoned. The job
     * is also cancelled if the request itself is cancelled.
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
            currentCoroutineContext().ensureActive() // The request was cancelled: propagate.
            Execution.Crashed("tool was cancelled")
        } finally {
            deadline.stop()
        }
    }

    private suspend fun invoke(tool: Tool, args: JsonObject, ctx: CallContext): Execution = try {
        Execution.Completed(tool.call(args, ctx))
    } catch (e: ToolException) {
        Execution.Failed(e.message?.takeIf { it.isNotBlank() } ?: "${tool.title} failed.")
    } catch (e: CancellationException) {
        // Our own cancellation (timeout or request cancelled) propagates. A CancellationException
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

    private fun safeRecord(entry: AuditEntry) {
        try {
            audit.record(entry)
        } catch (e: Exception) {
            // Auditing must never break a call.
        }
    }

    private fun newId(bytes: Int): String = Encoding.base32(ByteArray(bytes).also(random::nextBytes))

    companion object {
        const val PAUSED_MESSAGE = "Agent access is paused on the phone. Ask the user to resume it in the PhoneBridge app."
        const val DENIED_MESSAGE = "The user denied this action on the phone. Do not retry unless the user asks again."

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
