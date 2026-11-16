// Adapted from T3 Code (MIT, Copyright (c) 2026 T3 Tools Inc.),
// apps/mobile/modules/t3-agent-notifications/android/src/main/java/expo/modules/t3agentnotifications/AgentActivityPresentation.kt
package com.droidhands.android.live

import com.droidhands.android.confirm.DisplayText
import com.droidhands.android.service.StatusText
import com.droidhands.core.audit.AuditOutcome
import com.droidhands.core.mcp.ActiveCall

/** [heading] for the status line of one call, [chip] for the Android 16 status-bar chip (at most 7 characters). */
enum class ActivityPhase(val heading: String, val chip: String) {
    WORKING("Working", "Agent"),
    APPROVAL("Approval needed", "Approve"),
    DONE("Done", "Done"),
    FAILED("Failed", "Failed"),
    STOPPED("Stopped", "Stopped"),
    ;

    val finished: Boolean get() = this == DONE || this == FAILED || this == STOPPED
}

/** Everything the activity notification shows; built by [AgentActivityPresentation.content]. */
data class ActivityContent(
    val phase: ActivityPhase,
    val title: String,
    /** Collapsed, one line. */
    val text: String,
    /** Expanded, one line per call or agent. */
    val bigText: String,
    /** Status-bar chip of a promoted notification (Android 16+); null when not promoted. */
    val chip: String?,
    /** Shown on a secure lock screen instead: no agent names, no actions. */
    val publicTitle: String,
    /** Agents whose calls Stop ends; empty: no Stop and no Pause all (the work is over). */
    val stopTokenIds: List<String>,
    val ongoing: Boolean,
    /** Ask Android 16+ to promote it to a Live Update. */
    val requestPromotion: Boolean,
    /** The system removes it after this long, should the app not (e.g. the process was killed). */
    val timeoutMs: Long,
)

/**
 * Pure text and flags of the activity notification for an [ActivitySnapshot] (unit-tested). Who and what
 * only: the agent's name and, per call, the line its tool reported (e.g. "Sending WhatsApp to Tania Cohen",
 * never message text) or else the tool's title. Agent names and lines are agent-controlled, so they are
 * cleaned like approval prompt text ([DisplayText]).
 *
 * Android 16 Live Updates must stay ongoing and uncolorized to be promoted, so on [PROMOTION_SDK]+ even
 * the short-lived outcome stays ongoing (as T3 Code does for finished cards) and keeps its chip.
 */
object AgentActivityPresentation {
    /** Build.VERSION_CODES.BAKLAVA (Android 16), the first release with Live Updates. */
    const val PROMOTION_SDK = 36

    /** Removal backstop while calls run: longer than any tool's timeout plus its approval. */
    const val RUNNING_TIMEOUT_MS = 15 * 60 * 1000L

    private const val NAME_CHARS = 40
    private const val LINE_CHARS = 120
    private const val MAX_LINES = 5

    /** Extra time before the system removes an outcome the app failed to remove. */
    private const val TIMEOUT_GRACE_MS = 2_000L

    fun content(snapshot: ActivitySnapshot, sdk: Int, lingerMs: Long = AgentActivityState.LINGER_MS): ActivityContent {
        val promoted = sdk >= PROMOTION_SDK
        return when (snapshot) {
            is ActivitySnapshot.Running -> running(snapshot.calls, promoted)
            is ActivitySnapshot.Finished -> finished(snapshot.calls, promoted, lingerMs)
        }
    }

    private fun running(calls: List<ActiveCall>, promoted: Boolean): ActivityContent {
        val agents = calls.groupBy { it.tokenId }.values.toList()
        val waiting = agents.count { group -> group.any { it.awaitingApproval } }
        val phase = if (waiting > 0) ActivityPhase.APPROVAL else ActivityPhase.WORKING
        val title: String
        val text: String
        val bigText: String
        if (agents.size == 1) {
            val name = agentName(calls.first())
            val hero = calls.firstOrNull { it.awaitingApproval } ?: calls.first()
            title = if (waiting > 0) "$name needs your approval" else "$name is using your phone"
            text = line(hero) + if (calls.size > 1) " (+${calls.size - 1} more)" else ""
            bigText = if (calls.size == 1) line(hero) else lines(calls) { statusLine(it) }
        } else {
            title = "${agents.size} agents are using your phone"
            text = when (waiting) {
                0 -> agents.joinToString(", ") { agentName(it.first()) }
                1 -> "1 needs your approval"
                else -> "$waiting need your approval"
            }
            bigText = lines(agents) { group ->
                val hero = group.firstOrNull { it.awaitingApproval } ?: group.first()
                "${agentName(hero)}: ${statusLine(hero)}" + if (group.size > 1) " (+${group.size - 1} more)" else ""
            }
        }
        return ActivityContent(
            phase = phase,
            title = title,
            text = text,
            bigText = bigText,
            chip = when {
                !promoted -> null
                agents.size > 1 && phase == ActivityPhase.WORKING -> "Agents"
                else -> phase.chip
            },
            publicTitle = when {
                waiting > 0 -> "An agent needs your approval"
                agents.size > 1 -> "Agents are using your phone"
                else -> "An agent is using your phone"
            },
            stopTokenIds = agents.map { it.first().tokenId },
            ongoing = true,
            requestPromotion = promoted,
            timeoutMs = RUNNING_TIMEOUT_MS,
        )
    }

    private fun finished(calls: List<Pair<ActiveCall, AuditOutcome>>, promoted: Boolean, lingerMs: Long): ActivityContent {
        val outcomes = calls.map { it.second }
        val failedCount = outcomes.count { it != AuditOutcome.OK && it != AuditOutcome.CANCELLED }
        val phase = when {
            outcomes.all { it == AuditOutcome.OK } -> ActivityPhase.DONE
            failedCount > 0 -> ActivityPhase.FAILED
            else -> ActivityPhase.STOPPED
        }
        val agents = calls.groupBy { it.first.tokenId }.values.toList()
        val name = agentName(calls.first().first)
        val title = when {
            agents.size > 1 && failedCount > 0 -> "Agents finished, $failedCount failed"
            agents.size > 1 && phase == ActivityPhase.STOPPED -> "Agents stopped"
            agents.size > 1 -> "Agents finished"
            phase == ActivityPhase.DONE -> "$name finished"
            phase == ActivityPhase.FAILED -> "$name couldn't finish"
            else -> "$name was stopped"
        }
        val text = if (calls.size == 1) {
            outcomeLine(calls.first())
        } else if (agents.size > 1) {
            agents.joinToString(", ") { agentName(it.first().first) }
        } else {
            "${calls.size} actions" + if (failedCount > 0) ", $failedCount failed" else ""
        }
        return ActivityContent(
            phase = phase,
            title = title,
            text = text,
            bigText = if (calls.size == 1) text else lines(calls) { (call, outcome) ->
                (if (agents.size > 1) "${agentName(call)}: " else "") + outcomeLine(call to outcome)
            },
            chip = phase.chip.takeIf { promoted },
            publicTitle = "An agent finished",
            stopTokenIds = emptyList(),
            // Promotion requires ongoing; the outcome is removed after lingerMs anyway.
            ongoing = promoted,
            requestPromotion = promoted,
            timeoutMs = lingerMs + TIMEOUT_GRACE_MS,
        )
    }

    /** The agent's display name, e.g. "Claude Code". */
    fun agentName(call: ActiveCall): String = DisplayText.clean(call.agentName, NAME_CHARS).ifEmpty { "An agent" }

    /** What [call] does: its tool's own line, else the tool's title. */
    fun line(call: ActiveCall): String =
        DisplayText.clean(call.label, LINE_CHARS).ifEmpty { DisplayText.clean(call.toolTitle, LINE_CHARS) }.ifEmpty { call.toolName }

    /** e.g. "Approval needed: Sending WhatsApp to Tania Cohen". */
    private fun statusLine(call: ActiveCall): String =
        if (call.awaitingApproval) "${ActivityPhase.APPROVAL.heading}: ${line(call)}" else line(call)

    /** e.g. "Done: Sending WhatsApp to Tania Cohen", "Failed: Send SMS (denied by you)". */
    private fun outcomeLine(entry: Pair<ActiveCall, AuditOutcome>): String {
        val (call, outcome) = entry
        val heading = when (outcome) {
            AuditOutcome.OK -> ActivityPhase.DONE.heading
            AuditOutcome.CANCELLED -> ActivityPhase.STOPPED.heading
            else -> ActivityPhase.FAILED.heading
        }
        val reason = StatusText.outcomeLabel(outcome)?.takeIf { outcome != AuditOutcome.CANCELLED && outcome != AuditOutcome.TOOL_ERROR }
        return "$heading: ${line(call)}" + (reason?.let { " ($it)" } ?: "")
    }

    /** At most [MAX_LINES] lines, then "+N more". */
    private fun <T> lines(items: List<T>, render: (T) -> String): String {
        val shown = items.take(MAX_LINES).map(render)
        val rest = items.size - shown.size
        return (shown + listOfNotNull("+$rest more".takeIf { rest > 0 })).joinToString("\n")
    }
}
