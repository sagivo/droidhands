package com.droidhands.core.policy

import com.droidhands.core.auth.ActiveHours
import com.droidhands.core.auth.ConfirmPolicy
import com.droidhands.core.auth.TokenRecord
import com.droidhands.core.auth.ToolRule
import com.droidhands.core.tools.Risk
import com.droidhands.core.tools.ToolResult
import com.droidhands.core.tools.tool
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Per-tool rules and active hours (docs/SECURITY.md "Advanced controls"). */
class PolicyRulesTest {
    private val jerusalem = ZoneId.of("Asia/Jerusalem")
    private var zone: ZoneId = jerusalem
    private var now = at(12, 0)

    private fun at(hour: Int, minute: Int, z: ZoneId = jerusalem) =
        ZonedDateTime.of(2026, 11, 14, hour, minute, 0, 0, z).toInstant().toEpochMilli()

    private fun engine(limits: RateLimits = RateLimits()) = PolicyEngine(limits, clock = { now }, zone = { zone })

    private fun token(
        rules: Map<String, ToolRule> = emptyMap(),
        hours: ActiveHours? = null,
        policy: ConfirmPolicy = ConfirmPolicy.SENSITIVE,
    ) = TokenRecord(
        id = "aaaaaaaaaaaa", name = "Claude", secretHash = "", scopes = emptySet(), confirmPolicy = policy, createdAt = 0,
        toolRules = rules, activeHours = hours,
    )

    private fun toolOf(name: String, risk: Risk) = tool(name = name, title = name, description = "d", scope = "", risk = risk) { _, _ ->
        ToolResult.text("ok")
    }

    private val read = toolOf("device_status", Risk.READ)
    private val action = toolOf("app_open", Risk.ACTION)
    private val sensitive = toolOf("sms_send", Risk.SENSITIVE)

    @Test
    fun blockDeniesWithAClearMessage() {
        val decision = assertIs<PolicyDecision.Deny>(engine().evaluate(token(mapOf("sms_send" to ToolRule.BLOCK)), sensitive))
        assertFalse(decision.rateLimited)
        assertTrue(decision.reason.contains("The user blocked the tool 'sms_send' for Claude"), decision.reason)
        assertTrue(decision.reason.contains("DroidHands > Agents > Claude > Tool rules"), decision.reason)
        assertTrue(decision.reason.contains("Do not try to work around this"), decision.reason)
        // Other tools are unaffected.
        assertEquals(PolicyDecision.Allow, engine().evaluate(token(mapOf("sms_send" to ToolRule.BLOCK)), read))
    }

    @Test
    fun askAlwaysConfirmsEvenReadTools() {
        val e = engine()
        assertEquals(PolicyDecision.RequireConfirmation, e.evaluate(token(mapOf("device_status" to ToolRule.ASK)), read))
        assertEquals(
            PolicyDecision.RequireConfirmation,
            e.evaluate(token(mapOf("app_open" to ToolRule.ASK), policy = ConfirmPolicy.NEVER), action),
        )
    }

    @Test
    fun allowNeverConfirmsButIsStillRateLimited() {
        val e = engine(RateLimits(callsPerMinute = 100, sensitiveCallsPerMinute = 1))
        val t = token(mapOf("sms_send" to ToolRule.ALLOW), policy = ConfirmPolicy.ALL_ACTIONS)
        assertEquals(PolicyDecision.Allow, e.evaluate(t, sensitive))
        val limited = assertIs<PolicyDecision.Deny>(e.evaluate(t, sensitive))
        assertTrue(limited.rateLimited)
    }

    @Test
    fun noRuleFallsBackToTheConfirmPolicy() {
        val t = token(mapOf("other_tool" to ToolRule.ASK))
        assertEquals(PolicyDecision.Allow, engine().evaluate(t, read))
        assertEquals(PolicyDecision.RequireConfirmation, engine().evaluate(t, sensitive))
        assertTrue(PolicyEngine.requiresConfirmation(ConfirmPolicy.NEVER, Risk.READ, ToolRule.ASK))
        assertFalse(PolicyEngine.requiresConfirmation(ConfirmPolicy.ALL_ACTIONS, Risk.SENSITIVE, ToolRule.ALLOW))
        assertTrue(PolicyEngine.requiresConfirmation(ConfirmPolicy.SENSITIVE, Risk.SENSITIVE, null))
    }

    @Test
    fun sameDayWindow() {
        val t = token(hours = ActiveHours(8 * 60, 23 * 60))
        val e = engine()
        for ((time, allowed) in listOf(
            at(7, 59) to false, at(8, 0) to true, at(12, 0) to true, at(22, 59) to true, at(23, 0) to false, at(0, 30) to false,
        )) {
            now = time
            val decision = e.evaluate(t, read)
            if (allowed) assertEquals(PolicyDecision.Allow, decision, "$time") else assertIs<PolicyDecision.Deny>(decision, "$time")
        }
    }

    @Test
    fun overnightWindow() {
        val t = token(hours = ActiveHours(22 * 60, 6 * 60))
        val e = engine()
        for ((time, allowed) in listOf(
            at(21, 59) to false, at(22, 0) to true, at(23, 30) to true, at(0, 0) to true, at(5, 59) to true, at(6, 0) to false, at(12, 0) to false,
        )) {
            now = time
            val decision = e.evaluate(t, read)
            if (allowed) assertEquals(PolicyDecision.Allow, decision, "$time") else assertIs<PolicyDecision.Deny>(decision, "$time")
        }
    }

    @Test
    fun windowUsesThePhoneZone() {
        val t = token(hours = ActiveHours(8 * 60, 23 * 60))
        now = at(7, 0, ZoneId.of("UTC")) // 09:00 in Jerusalem (UTC+2), 07:00 in UTC.
        assertEquals(PolicyDecision.Allow, engine().evaluate(t, read))
        zone = ZoneId.of("UTC")
        val denied = assertIs<PolicyDecision.Deny>(engine().evaluate(t, read))
        assertTrue(denied.reason.contains("it is 07:00 there now"), denied.reason)
    }

    @Test
    fun outsideHoursMessageGivesTheWindow() {
        now = at(23, 41)
        val denied = assertIs<PolicyDecision.Deny>(engine().evaluate(token(hours = ActiveHours(8 * 60 + 5, 23 * 60)), read))
        assertFalse(denied.rateLimited)
        assertTrue(denied.reason.contains("between 08:05 and 23:00"), denied.reason)
        assertTrue(denied.reason.contains("phone's local time"), denied.reason)
        assertTrue(denied.reason.contains("23:41"), denied.reason)
        assertTrue(denied.reason.contains("active hours"), denied.reason)
    }

    @Test
    fun invalidWindowFailsClosed() {
        for (hours in listOf(ActiveHours(600, 600), ActiveHours(-5, 600), ActiveHours(0, 2000))) {
            val denied = assertIs<PolicyDecision.Deny>(engine().evaluate(token(hours = hours), read), "$hours")
            assertTrue(denied.reason.contains("not a valid time window"), denied.reason)
        }
    }

    @Test
    fun orderIsBlockThenHoursThenRateLimitThenConfirmation() {
        now = at(3, 0)
        val hours = ActiveHours(8 * 60, 23 * 60)
        // BLOCK wins over active hours.
        val blocked = assertIs<PolicyDecision.Deny>(engine().evaluate(token(mapOf("sms_send" to ToolRule.BLOCK), hours), sensitive))
        assertTrue(blocked.reason.contains("blocked"), blocked.reason)

        // Refusals by rule or hours do not use up the rate limit.
        val e = engine(RateLimits(callsPerMinute = 1, sensitiveCallsPerMinute = 1))
        repeat(5) { e.evaluate(token(hours = hours), read) }
        repeat(5) { e.evaluate(token(mapOf("device_status" to ToolRule.BLOCK)), read) }
        assertEquals(0, e.trackedTokens())
        now = at(12, 0)
        // Rate limit comes before confirmation: an ASK tool over the limit is refused, not prompted.
        val t = token(mapOf("device_status" to ToolRule.ASK), hours)
        assertEquals(PolicyDecision.RequireConfirmation, e.evaluate(t, read))
        assertTrue(assertIs<PolicyDecision.Deny>(e.evaluate(t, read)).rateLimited)
    }

    @Test
    fun activeWindowHelpers() {
        assertEquals("00:00", ActiveWindow.format(0))
        assertEquals("08:05", ActiveWindow.format(485))
        assertEquals("23:59", ActiveWindow.format(1439))
        assertEquals("22:00-06:00", ActiveWindow.format(ActiveHours(1320, 360)))
        assertEquals(9 * 60 + 30, ActiveWindow.minuteOfDay(at(9, 30), jerusalem))
        assertTrue(ActiveWindow.isValid(ActiveHours(0, 1439)))
        assertFalse(ActiveWindow.isValid(ActiveHours(1439, 1439)))
        assertFalse(ActiveWindow.contains(ActiveHours(5, 5), 5))
        assertTrue(ActiveWindow.contains(ActiveHours(1439, 0), 1439))
        assertFalse(ActiveWindow.contains(ActiveHours(1439, 0), 0))
    }
}
