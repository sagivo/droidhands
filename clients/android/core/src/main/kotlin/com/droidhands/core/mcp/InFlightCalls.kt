package com.droidhands.core.mcp

import com.droidhands.core.tools.ToolException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/** Why DroidHands stopped a `tools/call` after it began. [message] is for the agent, [detail] for the audit log. */
internal enum class StopReason(val message: String, val detail: String) {
    PAUSED(
        "The user paused agent access on the phone, so DroidHands stopped this call before it finished. " +
            "Ask the user to resume access in the DroidHands app before retrying.",
        "access paused",
    ),
    REVOKED(
        "The user revoked this agent's access on the phone, so DroidHands stopped this call before it finished. Do not retry.",
        "agent revoked",
    ),
    DELETED(
        "The user removed this agent from DroidHands, so DroidHands stopped this call before it finished. Do not retry.",
        "agent removed",
    ),
    EXPIRED(
        "This agent's DroidHands token expired during the call, so DroidHands stopped it before it finished. " +
            "Ask the user to connect this agent again.",
        "token expired",
    ),
    TOOL_WITHDRAWN(
        "The user changed this agent's permissions on the phone and it may no longer use this tool, so DroidHands " +
            "stopped this call before it finished. Do not try to work around it.",
        "tool no longer allowed for this agent",
    ),
    STOPPED(
        "This call was stopped on the phone before it finished. Check whether the action happened before retrying.",
        "stopped",
    ),
    STOPPED_BY_USER(
        "The user stopped this call on the phone before it finished. Check whether the action happened, and do not " +
            "retry unless the user asks again.",
        "stopped by the user",
    ),
}

/** Thrown by [com.droidhands.core.tools.CallContext.ensureStillAllowed] (and the confirmer) once a call must stop. */
internal class CallStoppedException(val reason: StopReason) : ToolException(reason.message)

/** One `tools/call` in progress, shared by [InFlightCalls] and [ToolCalls]. */
internal class InFlightCall(val tokenId: String) {
    /** Set by [InFlightCalls.cancel] before it cancels the call. */
    @Volatile var stopReason: StopReason? = null

    /** The transport stopped waiting for the result (e.g. the connection closed); the call itself goes on. */
    @Volatile var abandoned = false

    /** The tool itself was started (policy and any up-front confirmation passed). */
    @Volatile var toolStarted = false

    /** What [CallActivity] is told about this call; null before it passed the policy checks and once it ended. Guarded by this. */
    var activity: ActiveCall? = null
}

/**
 * The `tools/call` executions in progress, by token. Thread-safe.
 *
 * [run] executes each call in a job of its own, on the caller's dispatcher but not as a child of the
 * caller, so a transport that stops waiting (relay socket dropped or restarted, local client gone,
 * server stopped) never cancels a tool that may already be acting: the call finishes and is audited,
 * and only its response is lost. [cancel] stops calls on purpose (pause, revoke, delete).
 */
internal class InFlightCalls {
    private val calls = ConcurrentHashMap<InFlightCall, Job>()

    /** Calls still running, abandoned ones included. */
    val size: Int get() = calls.size

    /**
     * Runs [block] as [call] and returns its response. If [cancel] stopped the call, returns [stopped]'s
     * response instead. If the caller is cancelled, marks [call] abandoned and rethrows at once; the call
     * keeps running (and stays cancellable through [cancel]).
     */
    suspend fun run(call: InFlightCall, stopped: (StopReason) -> JsonObject, block: suspend () -> JsonObject): JsonObject {
        val response = CompletableDeferred<JsonObject>()
        // ATOMIC: the block always starts, so even a call cancelled at once is audited by ToolCalls.
        val job = CoroutineScope(currentCoroutineContext().minusKey(Job)).launch(start = CoroutineStart.ATOMIC) {
            try {
                response.complete(block())
            } catch (e: Throwable) {
                response.completeExceptionally(e)
            }
        }
        calls[call] = job
        job.invokeOnCompletion { calls.remove(call) }
        return try {
            response.await()
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                call.abandoned = true
                throw e
            }
            stopped(call.stopReason ?: StopReason.STOPPED)
        }
    }

    /**
     * Cancels the running calls of [tokenId] (all calls when null), first recording on each call the
     * [reason] for it. Returns how many were cancelled.
     */
    fun cancel(tokenId: String?, reason: (InFlightCall) -> StopReason): Int {
        var count = 0
        for ((call, job) in calls) {
            if (tokenId != null && call.tokenId != tokenId) continue
            if (!job.isActive) continue
            if (call.stopReason == null) call.stopReason = reason(call)
            job.cancel()
            count++
        }
        return count
    }
}
