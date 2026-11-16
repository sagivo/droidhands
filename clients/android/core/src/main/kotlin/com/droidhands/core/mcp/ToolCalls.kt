package com.droidhands.core.mcp

import com.droidhands.core.audit.AuditEntry
import com.droidhands.core.audit.AuditOutcome
import com.droidhands.core.auth.GrantText
import com.droidhands.core.auth.TokenRecord
import com.droidhands.core.auth.ToolRule
import com.droidhands.core.confirm.ConfirmationProvider
import com.droidhands.core.confirm.ConfirmationRequest
import com.droidhands.core.confirm.ConfirmationResult
import com.droidhands.core.policy.GlobalSecurity
import com.droidhands.core.policy.PolicyDecision
import com.droidhands.core.policy.PolicyEngine
import com.droidhands.core.tools.AppAccess
import com.droidhands.core.tools.AppBlockedException
import com.droidhands.core.tools.CallContext
import com.droidhands.core.tools.Dispatch
import com.droidhands.core.tools.DispatchTool
import com.droidhands.core.tools.Scopes
import com.droidhands.core.tools.Tool
import com.droidhands.core.tools.ToolContent
import com.droidhands.core.tools.ToolException
import com.droidhands.core.tools.ToolRegistry
import com.droidhands.core.tools.ToolResult
import com.droidhands.core.util.Encoding
import com.droidhands.core.util.OneTimeCodes
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
import java.util.concurrent.atomic.AtomicLong
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
 * Dispatch: a [DispatchTool] (`app_action_run`) passes its own scope check, then the kill switch, the
 * security settings and its own BLOCK rule, risk cap and active hours ([PolicyEngine.precheck]); then
 * [DispatchTool.resolve] picks the tool that really runs (a blocked app is audited DENIED_APP, an
 * unresolvable call TOOL_ERROR, both as refusals). From there the resolved tool goes through the
 * pipeline above exactly as if it had been called by name: its scope, policy and rate limits, rules
 * (also on [Dispatch.alias]), confirmation by its risk, its timeout and stop checks (which also require
 * the dispatching tool to stay allowed). An ASK rule on the dispatching tool makes every call it runs need
 * approval (one prompt, by the resolved tool's own means); an ALLOW rule on it never skips the resolved
 * tool's approval. Its audit entry carries the resolved tool's name and arguments and notes
 * "via <dispatching tool>".
 *
 * Activity: a call that passed the policy checks is reported to [CallActivity] (who, which tool, its approval
 * state and the tool's own [CallContext.reportActivity] line, never its arguments) until its audit entry.
 *
 * Every call ends with exactly one audit entry, cancelled ones included. Requests refused before
 * anything ran (missing name, bad arguments, unknown tool, missing scope, paused, policy and rate-limit
 * refusals, unresolvable dispatches) go through [AuditThrottle.recordRefusal], so they cannot flood the log.
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
    private val activity: CallActivity = CallActivity.NONE,
) {
    private val random = SecureRandom()
    private val activityIds = AtomicLong()

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
            // Outside the agent's grant: refused at once, never turned into a prompt.
            return JsonRpc.error(id, JsonRpc.INVALID_PARAMS, GrantText.missingScope(token.name, tool.scope, name))
        }

        // What actually runs, and what the audit entry is about: the named tool, or what a DispatchTool resolved to.
        var target = Target(tool, args)
        val outcome = try {
            if (tool is DispatchTool) {
                when (val resolution = resolve(tool, args, token, transport, handle)) {
                    is Resolution.Refused -> resolution.outcome
                    is Resolution.Ready -> {
                        target = resolution.target
                        val inner = target.tool
                        if (Scopes.allows(token.scopes, inner.scope)) {
                            run(target, token, transport, handle)
                        } else {
                            Outcome(
                                ToolResult.error(GrantText.missingScope(token.name, inner.scope, target.alias ?: inner.name)),
                                AuditOutcome.DENIED_SCOPE, "missing scope ${inner.scope}", refused = true,
                            )
                        }
                    }
                }
            } else {
                run(target, token, transport, handle)
            }
        } catch (e: CancellationException) {
            // Cancelled through InFlightCalls.cancel (pause, revoke, delete): still exactly one entry.
            val detail = stoppedDetail(handle.stopReason ?: StopReason.STOPPED, handle.toolStarted)
            record(AuditOutcome.CANCELLED, target.tool.name, target.args, joinDetails(detail, target.note))
            finishActivity(handle, AuditOutcome.CANCELLED)
            throw e
        } catch (e: Exception) {
            // A bug in the pipeline itself (tool failures are handled inside run()).
            Outcome(ToolResult.error("Internal error: ${ToolText.describe(e)}"), AuditOutcome.EXCEPTION, ToolText.describe(e))
        }
        val detail = joinDetails(outcome.detail, target.note)
        if (outcome.refused) {
            refuse(outcome.audit, target.tool.name, target.args, detail)
        } else {
            record(outcome.audit, target.tool.name, target.args, detail)
        }
        finishActivity(handle, outcome.audit)
        return JsonRpc.result(id, encodeResult(outcome.result))
    }

    /** [refused]: refused before anything ran (throttled in the audit log). */
    private class Outcome(val result: ToolResult, val audit: AuditOutcome, val detail: String? = null, val refused: Boolean = false)

    /**
     * The tool a call runs, with its arguments. For a [DispatchTool] call: the resolved tool, [via] the
     * dispatching tool and [alias] the resolution's other name (see [Dispatch]).
     */
    private class Target(val tool: Tool, val args: JsonObject, val via: DispatchTool? = null, val alias: String? = null) {
        /** Audit note for a dispatched call, e.g. "via app_action_run (com.whatsapp/send_message)". */
        val note: String?
            get() = via?.let { v -> "via ${v.name}" + (alias?.takeIf { it != tool.name }?.let { " ($it)" } ?: "") }
    }

    private sealed interface Resolution {
        class Ready(val target: Target) : Resolution

        /** Nothing ran; audited under the dispatching tool's name. */
        class Refused(val outcome: Outcome) : Resolution
    }

    /**
     * Resolves a [DispatchTool] call: kill switch, security settings, the dispatching tool's own policy
     * (BLOCK rule, risk cap, active hours; rate limits are counted once, for the resolved tool), then
     * [DispatchTool.resolve] with the call's app access. Never nests.
     */
    private suspend fun resolve(tool: DispatchTool, args: JsonObject, token: TokenRecord, transport: String, handle: InFlightCall): Resolution {
        if (!accessEnabled()) return Resolution.Refused(Outcome(ToolResult.error(PAUSED_MESSAGE), AuditOutcome.PAUSED, refused = true))
        val security = try {
            globalSecurity()
        } catch (e: Exception) {
            return Resolution.Refused(
                Outcome(ToolResult.error(SECURITY_UNAVAILABLE_MESSAGE), AuditOutcome.EXCEPTION, "security settings: ${ToolText.describe(e)}"),
            )
        }
        policy.precheck(token, tool)?.let { deny ->
            return Resolution.Refused(Outcome(ToolResult.error(deny.reason), AuditOutcome.DENIED_POLICY, deny.reason, refused = true))
        }
        val outer = Target(tool, args)
        val ctx = callContext(token, transport, security, stillAllowed = {
            currentCoroutineContext().ensureActive()
            stopReason(handle, token, outer)?.let { throw CallStoppedException(it) }
        }) { throw ToolException("'${tool.name}' can't ask for approval while choosing what to run.") }
        val dispatch = try {
            withTimeoutOrNull(tool.timeoutMs) { tool.resolve(args, ctx) }
                ?: return Resolution.Refused(
                    Outcome(ToolResult.error(timeoutMessage(tool)), AuditOutcome.TIMEOUT, "resolving timed out after ${ToolText.shortSeconds(tool.timeoutMs)}"),
                )
        } catch (e: CallStoppedException) {
            return Resolution.Refused(stopped(e.reason))
        } catch (e: AppBlockedException) {
            val message = e.message?.takeIf { it.isNotBlank() } ?: "${tool.title} was refused: the app is blocked for this agent."
            return Resolution.Refused(Outcome(ToolResult.error(message), AuditOutcome.DENIED_APP, "blocked app ${e.packageName}", refused = true))
        } catch (e: ToolException) {
            val message = e.message?.takeIf { it.isNotBlank() } ?: "${tool.title} failed."
            return Resolution.Refused(Outcome(ToolResult.error(message), AuditOutcome.TOOL_ERROR, message, refused = true))
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive() // The call was cancelled: propagate.
            return Resolution.Refused(Outcome(ToolResult.error("Internal error: ${ToolText.describe(e)}"), AuditOutcome.EXCEPTION, ToolText.describe(e)))
        } catch (e: Exception) {
            return Resolution.Refused(Outcome(ToolResult.error("Internal error: ${ToolText.describe(e)}"), AuditOutcome.EXCEPTION, ToolText.describe(e)))
        }
        if (dispatch.tool is DispatchTool) {
            val detail = "'${tool.name}' resolved to '${dispatch.tool.name}', which also dispatches; nested dispatch is refused"
            return Resolution.Refused(Outcome(ToolResult.error("Internal error: $detail."), AuditOutcome.EXCEPTION, detail))
        }
        return Resolution.Ready(Target(dispatch.tool, dispatch.args, via = tool, alias = dispatch.alias))
    }

    private fun accessEnabled(): Boolean = try {
        isAccessEnabled()
    } catch (e: Exception) {
        false // Fail closed.
    }

    /**
     * Null while [handle] may go on; otherwise why it must stop (see the class KDoc). A dispatched call
     * stops when the agent loses either tool, or gets a BLOCK rule on the [Target.alias].
     */
    private fun stopReason(handle: InFlightCall, token: TokenRecord, target: Target): StopReason? {
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
        if (!McpServer.isVisible(current, target.tool)) return StopReason.TOOL_WITHDRAWN
        target.via?.let { if (!McpServer.isVisible(current, it)) return StopReason.TOOL_WITHDRAWN }
        target.alias?.let { if (current.toolRules[it] == ToolRule.BLOCK) return StopReason.TOOL_WITHDRAWN }
        return null
    }

    private fun stopped(reason: StopReason) =
        Outcome(ToolResult.error(reason.message), AuditOutcome.CANCELLED, stoppedDetail(reason, toolStarted = false))

    /** The [CallContext] for one call of [token]; [stillAllowed] and [confirmer] as described there. */
    private fun callContext(
        token: TokenRecord,
        transport: String,
        security: GlobalSecurity,
        stillAllowed: suspend () -> Unit,
        activity: (String) -> Unit = {},
        confirmer: suspend (String) -> Unit,
    ) = CallContext(
        tokenId = token.id,
        tokenName = token.name,
        scopes = token.scopes,
        transport = transport,
        requestId = newId(10),
        appAccess = AppAccess.of(token.appPolicy, security.neverAllowApps, token.name),
        redactOneTimeCodes = security.redactOneTimeCodes,
        toolRules = token.toolRules,
        activeHours = token.activeHours,
        maxRisk = token.maxRisk,
        confirmPolicy = token.confirmPolicy,
        stillAllowed = stillAllowed,
        activity = activity,
        confirmer = confirmer,
    )

    private suspend fun run(target: Target, token: TokenRecord, transport: String, handle: InFlightCall): Outcome {
        val tool = target.tool
        val args = target.args
        if (!accessEnabled()) return Outcome(ToolResult.error(PAUSED_MESSAGE), AuditOutcome.PAUSED, refused = true)

        val security = try {
            globalSecurity()
        } catch (e: Exception) {
            // Fail closed: without the never-allow list no app-facing call is safe.
            return Outcome(ToolResult.error(SECURITY_UNAVAILABLE_MESSAGE), AuditOutcome.EXCEPTION, "security settings: ${ToolText.describe(e)}")
        }

        target.alias?.let { alias ->
            if (token.toolRules[alias] == ToolRule.BLOCK) {
                val reason = PolicyEngine.blockedMessage(alias, token.name)
                return Outcome(ToolResult.error(reason), AuditOutcome.DENIED_POLICY, reason, refused = true)
            }
        }
        val decision = policy.evaluate(token, tool)
        if (decision is PolicyDecision.Deny) {
            val audit = if (decision.rateLimited) AuditOutcome.RATE_LIMITED else AuditOutcome.DENIED_POLICY
            return Outcome(ToolResult.error(decision.reason), audit, decision.reason, refused = true)
        }
        // ASK on the dispatching tool asks before every call it runs; ALLOW on it never skips the resolved tool's approval.
        val viaAsks = target.via?.let { token.toolRules[it.name] == ToolRule.ASK } == true
        val needsConfirmation = decision == PolicyDecision.RequireConfirmation || viaAsks

        val state = CallState()
        startActivity(handle, token, tool)

        /** Null while the call may go on; otherwise why not (the first reason is kept for the audit entry). */
        fun checkStop(): StopReason? = stopReason(handle, token, target)?.also { state.stop.compareAndSet(null, it) }

        if (needsConfirmation && !tool.confirmsInternally) {
            checkStop()?.let { return stopped(it) }
            val answer = ask(token, tool, ToolText.confirmationSummary(tool, args), transport, handle)
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
                ask(token, tool, text, transport, handle)
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
        val report: (String) -> Unit = { line ->
            activityLabel(line, security.redactOneTimeCodes)?.let { label -> updateActivity(handle) { it.copy(label = label) } }
        }
        val ctx = callContext(token, transport, security, stillAllowed, report, confirmer)

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

    /** [handle] is about to be run; from now on it is reported to [activity] until [finishActivity]. */
    private fun startActivity(handle: InFlightCall, token: TokenRecord, tool: Tool) {
        val call = ActiveCall(activityIds.incrementAndGet(), token.id, token.name, tool.name, tool.title, tool.risk, clock())
        synchronized(handle) {
            handle.activity = call
            notifyActivity { activity.onStarted(call) }
        }
    }

    /** Applies [change] to [handle]'s activity, if it is still reported. Listeners are told under the lock, in order. */
    private fun updateActivity(handle: InFlightCall, change: (ActiveCall) -> ActiveCall) {
        synchronized(handle) {
            val current = handle.activity ?: return
            val updated = change(current).takeIf { it != current } ?: return
            handle.activity = updated
            notifyActivity { activity.onUpdated(updated) }
        }
    }

    private fun finishActivity(handle: InFlightCall, outcome: AuditOutcome) {
        synchronized(handle) {
            val call = handle.activity ?: return
            handle.activity = null
            notifyActivity { activity.onFinished(call, outcome) }
        }
    }

    private inline fun notifyActivity(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // Never let the activity display fail a call.
        }
    }

    /** Prompts the user, bounded by the confirmation timeout. Never throws except on cancellation. */
    private suspend fun ask(token: TokenRecord, tool: Tool, summary: String, transport: String, handle: InFlightCall): Answer {
        updateActivity(handle) { it.copy(awaitingApproval = true) }
        try {
            return askUser(token, tool, summary, transport)
        } finally {
            updateActivity(handle) { it.copy(awaitingApproval = false) }
        }
    }

    private suspend fun askUser(token: TokenRecord, tool: Tool, summary: String, transport: String): Answer {
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
            "Ask the user to check that DroidHands can show notifications, then retry.",
        AuditOutcome.EXCEPTION,
        "confirmation failed: ${ToolText.describe(e)}",
    )

    private fun timeoutMessage(tool: Tool) =
        "Timed out after ${ToolText.shortSeconds(tool.timeoutMs)}. The action may or may not have completed; check before retrying."

    private fun newId(bytes: Int): String = Encoding.base32(ByteArray(bytes).also(random::nextBytes))

    companion object {
        const val PAUSED_MESSAGE = "Agent access is paused on the phone. Ask the user to resume it in the DroidHands app."
        const val DENIED_MESSAGE = "The user denied this action on the phone. Do not retry unless the user asks again."
        const val SECURITY_UNAVAILABLE_MESSAGE =
            "DroidHands could not read the phone's security settings, so it refused this call. " +
                "Ask the user to open the DroidHands app (restarting it if needed), then retry."

        /** Added to the audit detail of a call whose transport stopped waiting for its result. */
        internal const val ABANDONED_NOTE = "result not delivered: the agent's connection closed first"

        /** Tool names are cut to this length in audit entries and errors (unknown names are agent input). */
        private const val MAX_TOOL_NAME_CHARS = 100

        /** [CallContext.reportActivity] lines are cut to this length. */
        internal const val MAX_ACTIVITY_LABEL_CHARS = 120

        /** A reported activity line as shown: trimmed, cut, one-time codes masked when [redact]; null when blank. */
        internal fun activityLabel(label: String, redact: Boolean): String? {
            val trimmed = label.trim().takeIf { it.isNotEmpty() } ?: return null
            return ToolText.truncate(if (redact) OneTimeCodes.redact(trimmed) else trimmed, MAX_ACTIVITY_LABEL_CHARS)
        }

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

        private fun joinDetails(vararg parts: String?): String? = parts.filterNotNull().joinToString("; ").ifEmpty { null }
    }
}
