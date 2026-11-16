package dev.phonebridge.android.service

import dev.phonebridge.android.graph.LocalServerStatus
import dev.phonebridge.core.audit.AuditEntry
import dev.phonebridge.core.audit.AuditOutcome
import dev.phonebridge.core.transport.RelayStatus
import java.net.URI

/** Rendered content of the BridgeService notification. */
data class StatusNotificationContent(
    val title: String,
    /** Collapsed, single line. */
    val text: String,
    /** Expanded (BigTextStyle), one fact per line. */
    val bigText: String,
    val paused: Boolean,
)

/**
 * Pure formatting of bridge status for the persistent notification and for `bridge_capabilities`.
 * No Android dependencies, so it is unit-tested on the JVM.
 */
object StatusText {
    /** Short machine-friendly relay state for agents, e.g. "connected", "error: …". */
    fun relayState(enabled: Boolean, status: RelayStatus): String = when {
        !enabled && status is RelayStatus.Stopped -> "disabled"
        status is RelayStatus.Stopped -> "stopped"
        status is RelayStatus.Connecting -> "connecting"
        status is RelayStatus.Connected -> "connected"
        status is RelayStatus.Error -> "error: ${status.message}" + retrySuffix(status)
        else -> status.toString()
    }

    /** Short local-server state for agents, e.g. "running: http://127.0.0.1:8765/mcp". */
    fun localState(enabled: Boolean, status: LocalServerStatus): String = when (status) {
        is LocalServerStatus.Stopped -> if (enabled) "stopped" else "disabled"
        is LocalServerStatus.Running -> "running: " + status.urls.joinToString(", ")
        is LocalServerStatus.Error -> "error: ${status.message}"
        is LocalServerStatus.WaitingForTailscale -> "waiting for Tailscale"
    }

    /** Human line for the notification, e.g. "Relay: connected to relay.example.com". */
    fun relayLine(status: RelayStatus): String = when (status) {
        is RelayStatus.Stopped -> "Relay: stopped"
        is RelayStatus.Connecting -> "Relay: connecting…"
        is RelayStatus.Connected -> hostOf(status.mcpUrl)?.let { "Relay: connected to $it" } ?: "Relay: connected"
        is RelayStatus.Error -> "Relay: ${status.message}" + retrySuffix(status)
    }

    /** Human line for the notification, e.g. "Local: 127.0.0.1:8765, 192.168.1.20:8765". */
    fun localLine(status: LocalServerStatus): String = when (status) {
        is LocalServerStatus.Stopped -> "Local: stopped"
        is LocalServerStatus.Running ->
            "Local: " + status.urls.joinToString(", ") { it.removePrefix("http://").removeSuffix("/mcp") }
        is LocalServerStatus.Error -> "Local: ${status.message}"
        is LocalServerStatus.WaitingForTailscale -> "Tailscale: waiting for Tailscale to connect"
    }

    /**
     * The entry worth showing as "last activity": the newest tool call or rejected request (auth
     * failure), ignoring protocol chatter such as `initialize`/`tools/list`; else the newest entry.
     * [entries] is newest first, as in [dev.phonebridge.core.audit.AuditLog.entries].
     */
    fun lastNotableEntry(entries: List<AuditEntry>): AuditEntry? =
        entries.firstOrNull { it.tool != null || it.outcome == AuditOutcome.AUTH_FAILED } ?: entries.firstOrNull()

    /** e.g. "Last: sms_send by Claude, 3 min ago" or "Last: rejected request (invalid token), just now". */
    fun lastActivityLine(entry: AuditEntry, now: Long): String {
        val age = relativeTime(now - entry.timestamp)
        if (entry.outcome == AuditOutcome.AUTH_FAILED) return "Last: rejected request (invalid token), $age"
        val what = entry.tool ?: entry.method
        val who = entry.tokenName?.takeIf { it.isNotBlank() } ?: "unknown agent"
        val outcome = outcomeLabel(entry.outcome)?.let { " ($it)" }.orEmpty()
        return "Last: $what by $who$outcome, $age"
    }

    /** Null for success; otherwise a short lowercase label. */
    fun outcomeLabel(outcome: AuditOutcome): String? = when (outcome) {
        AuditOutcome.OK -> null
        AuditOutcome.TOOL_ERROR -> "failed"
        AuditOutcome.UNKNOWN_TOOL -> "unknown tool"
        AuditOutcome.DENIED_SCOPE -> "not allowed"
        AuditOutcome.DENIED_POLICY -> "blocked by rule"
        AuditOutcome.DENIED_APP -> "app blocked"
        AuditOutcome.CANCELLED -> "stopped"
        AuditOutcome.RATE_LIMITED -> "rate limited"
        AuditOutcome.PAUSED -> "blocked: paused"
        AuditOutcome.DENIED_BY_USER -> "denied by you"
        AuditOutcome.CONFIRM_TIMEOUT -> "not approved in time"
        AuditOutcome.AUTH_FAILED -> "invalid token"
        AuditOutcome.TIMEOUT -> "timed out"
        AuditOutcome.EXCEPTION -> "error"
    }

    /** Coarse relative time: "just now", "5 min ago", "3 h ago", "2 d ago". Future (clock skew) = "just now". */
    fun relativeTime(deltaMs: Long): String {
        val seconds = deltaMs / 1000
        return when {
            seconds < 45 -> "just now"
            seconds < 3600 -> "${maxOf(1, seconds / 60)} min ago"
            seconds < 86_400 -> "${seconds / 3600} h ago"
            else -> "${seconds / 86_400} d ago"
        }
    }

    /**
     * Builds the notification content. Only enabled transports are listed (a disabled one is just
     * noise); [relay]/[local] are null when that transport is disabled in settings.
     */
    fun notification(
        paused: Boolean,
        relay: RelayStatus?,
        local: LocalServerStatus?,
        lastEntry: AuditEntry?,
        now: Long,
    ): StatusNotificationContent {
        val transportLines = listOfNotNull(relay?.let(::relayLine), local?.let(::localLine))
        val activity = lastEntry?.let { lastActivityLine(it, now) }
        val title = if (paused) "PhoneBridge paused" else "PhoneBridge active"
        val collapsed = when {
            paused -> "Agents can't use this phone until you tap Resume."
            transportLines.isNotEmpty() -> transportLines.joinToString(" · ")
            else -> "No connection enabled."
        }
        val expanded = buildList {
            if (paused) add("Agents can't use this phone until you tap Resume.")
            addAll(transportLines)
            if (transportLines.isEmpty()) add("No connection enabled.")
            activity?.let(::add)
        }.joinToString("\n")
        return StatusNotificationContent(title, collapsed, expanded, paused)
    }

    private fun retrySuffix(status: RelayStatus.Error): String {
        if (status.fatal) return " (check settings)"
        val retryInMs = status.retryInMs ?: return ""
        return " (retry in ${formatDelay(retryInMs)})"
    }

    private fun formatDelay(ms: Long): String {
        val s = (ms + 999) / 1000
        return if (s < 60) "$s s" else "${(s + 59) / 60} min"
    }

    private fun hostOf(url: String): String? = try {
        URI(url).host?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }
}
