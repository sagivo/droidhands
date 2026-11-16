package dev.phonebridge.android.ui.agents

import dev.phonebridge.core.audit.TokenUsage
import dev.phonebridge.core.auth.ActiveHours
import dev.phonebridge.core.auth.AppAccessMode
import dev.phonebridge.core.auth.AppPolicy
import dev.phonebridge.core.auth.TokenRecord
import dev.phonebridge.core.auth.ToolRule
import dev.phonebridge.core.tools.Scopes
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class AgentPresentationTest {
    private val now = 1_731_500_000_000L
    private val hour = 3_600_000L

    private fun record(
        id: String,
        createdAt: Long = now - 10 * hour,
        lastUsedAt: Long? = null,
        revoked: Boolean = false,
        expiresAt: Long? = null,
        origin: String = "manual",
        clientHost: String? = null,
        scopes: Set<String> = Scopes.PRESET_ASSISTANT,
    ) = TokenRecord(
        id = id, name = id, secretHash = "00", scopes = scopes, createdAt = createdAt, lastUsedAt = lastUsedAt,
        revoked = revoked, expiresAt = expiresAt, origin = origin, clientHost = clientHost,
    )

    @Test
    fun clientLabels() {
        assertEquals("claude.ai", AgentPresentation.clientLabel(record("a", origin = "pairing", clientHost = "claude.ai")))
        assertEquals("Connected agent", AgentPresentation.clientLabel(record("a", origin = "pairing")))
        assertEquals("Token", AgentPresentation.clientLabel(record("a")))
    }

    @Test
    fun lastActiveCombinesRecordAndUsage() {
        val r = record("a", lastUsedAt = now - 2 * hour)
        assertEquals(now - 2 * hour, AgentPresentation.lastActive(r, null))
        assertEquals(now - hour, AgentPresentation.lastActive(r, TokenUsage("a", lastCallAt = now - hour)))
        assertEquals(null, AgentPresentation.lastActive(record("b"), null))
        assertEquals("Never used", AgentPresentation.lastActiveText(null, now))
        assertEquals("Active 1 h ago", AgentPresentation.lastActiveText(now - hour, now))
    }

    @Test
    fun callsToday() {
        val usage = TokenUsage("a", perDay = mapOf("2024-11-13" to 7L, "2024-11-12" to 3L))
        assertEquals(7L, AgentPresentation.callsOn(usage, LocalDate.of(2024, 11, 13)))
        assertEquals(0L, AgentPresentation.callsOn(usage, LocalDate.of(2024, 11, 14)))
        assertEquals(0L, AgentPresentation.callsOn(null, LocalDate.of(2024, 11, 14)))
        assertEquals("No calls today", AgentPresentation.callsTodayText(0))
        assertEquals("1 call today", AgentPresentation.callsTodayText(1))
        assertEquals("12 calls today", AgentPresentation.callsTodayText(12))
    }

    @Test
    fun sortsActiveByRecencyThenExpiredThenRevoked() {
        val revoked = record("revoked", lastUsedAt = now - hour, revoked = true)
        val expired = record("expired", lastUsedAt = now - hour, expiresAt = now - 1)
        val idle = record("idle", createdAt = now - hour)
        val older = record("older", createdAt = now - 5 * hour)
        val busy = record("busy", lastUsedAt = now - 3 * hour)
        val viaUsage = record("viaUsage")
        val usage = mapOf("viaUsage" to TokenUsage("viaUsage", lastCallAt = now - 60_000))
        val sorted = AgentPresentation.sorted(listOf(revoked, idle, expired, older, busy, viaUsage), usage, now)
        assertEquals(listOf("viaUsage", "busy", "idle", "older", "expired", "revoked"), sorted.map { it.id })
    }

    @Test
    fun restrictions() {
        val plain = record("a")
        assertEquals(emptyList<String>(), AgentPresentation.restrictions(plain) { "x" })
        val restricted = plain.copy(
            appPolicy = AppPolicy(AppAccessMode.ALLOWLIST, setOf("com.whatsapp", "com.google.android.gm", "x")),
            toolRules = mapOf("sms_send" to ToolRule.BLOCK),
            activeHours = ActiveHours(480, 1380),
        )
        // Apps are part of the grant summary, not listed again here.
        assertEquals(listOf("1 tool rule", "08-23"), AgentPresentation.restrictions(restricted) { "08-23" })
        val blocked = plain.copy(appPolicy = AppPolicy(AppAccessMode.BLOCKLIST, setOf("com.bank")))
        assertEquals(emptyList<String>(), AgentPresentation.restrictions(blocked) { "" })
    }

    @Test
    fun countsAndTexts() {
        val list = listOf(record("a"), record("b", revoked = true), record("c", expiresAt = now - 1), record("d", expiresAt = now + 1))
        assertEquals(2, AgentPresentation.activeCount(list, now))
        assertEquals("No active agents to revoke", AgentPresentation.revokedAllText(0))
        assertEquals("Revoked 1 agent", AgentPresentation.revokedAllText(1))
        assertEquals("Revoked 4 agents", AgentPresentation.revokedAllText(4))
    }
}
