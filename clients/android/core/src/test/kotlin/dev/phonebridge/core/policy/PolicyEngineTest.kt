package dev.phonebridge.core.policy

import dev.phonebridge.core.auth.ConfirmPolicy
import dev.phonebridge.core.auth.TokenRecord
import dev.phonebridge.core.auth.ToolRule
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.ToolResult
import dev.phonebridge.core.tools.tool
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PolicyEngineTest {
    private var now = 1_000_000L

    private fun token(id: String = "aaaaaaaaaaaa", policy: ConfirmPolicy = ConfirmPolicy.SENSITIVE) =
        TokenRecord(id = id, name = id, secretHash = "", scopes = emptySet(), confirmPolicy = policy, createdAt = 0)

    private fun toolOf(risk: Risk) = tool(name = "t_${risk.name.lowercase()}", title = "T", description = "d", scope = "", risk = risk) { _, _ ->
        ToolResult.text("ok")
    }

    private val read = toolOf(Risk.READ)
    private val action = toolOf(Risk.ACTION)
    private val sensitive = toolOf(Risk.SENSITIVE)

    @Test
    fun confirmationMatrix() {
        val engine = PolicyEngine(clock = { now })
        val expected = mapOf(
            ConfirmPolicy.NEVER to listOf(false, false, false),
            ConfirmPolicy.SENSITIVE to listOf(false, false, true),
            ConfirmPolicy.ALL_ACTIONS to listOf(false, true, true),
        )
        for ((policy, needs) in expected) {
            listOf(read, action, sensitive).zip(needs).forEach { (t, confirm) ->
                val decision = engine.evaluate(token("tok${policy.ordinal}xxxxxxxx", policy), t)
                val want = if (confirm) PolicyDecision.RequireConfirmation else PolicyDecision.Allow
                assertEquals(want, decision, "$policy / ${t.risk}")
                assertEquals(confirm, PolicyEngine.requiresConfirmation(policy, t.risk))
            }
        }
    }

    @Test
    fun maxRiskRefusesHigherRiskToolsWhateverTheRules() {
        val engine = PolicyEngine(RateLimits(callsPerMinute = 3, sensitiveCallsPerMinute = 3), clock = { now })
        val readOnly = token("readonlyxxxx").copy(maxRisk = Risk.READ, toolRules = mapOf(action.name to ToolRule.ALLOW))
        assertIs<PolicyDecision.Allow>(engine.evaluate(readOnly, read))
        for (t in listOf(action, sensitive)) {
            val denied = assertIs<PolicyDecision.Deny>(engine.evaluate(readOnly, t), t.name)
            assertFalse(denied.rateLimited)
            assertTrue(denied.reason.contains("read-only tools"), denied.reason)
            assertTrue(denied.reason.contains("'${t.name}'"), denied.reason)
        }
        // Refused calls do not use up the rate limit.
        repeat(2) { assertIs<PolicyDecision.Allow>(engine.evaluate(readOnly, read)) }

        val noSending = token("nosendingxxx", ConfirmPolicy.NEVER).copy(maxRisk = Risk.ACTION)
        assertIs<PolicyDecision.Allow>(engine.evaluate(noSending, action))
        assertTrue(assertIs<PolicyDecision.Deny>(engine.evaluate(noSending, sensitive)).reason.contains("do not act toward other people"))
        assertIs<PolicyDecision.Allow>(engine.evaluate(token("uncappedxxxx", ConfirmPolicy.NEVER).copy(maxRisk = Risk.SENSITIVE), sensitive))

        assertFalse(PolicyEngine.exceedsMaxRisk(null, Risk.SENSITIVE))
        assertTrue(PolicyEngine.exceedsMaxRisk(Risk.READ, Risk.ACTION))
        assertFalse(PolicyEngine.exceedsMaxRisk(Risk.ACTION, Risk.ACTION))
    }

    @Test
    fun totalRateLimitWithSlidingWindow() {
        val engine = PolicyEngine(RateLimits(callsPerMinute = 3, sensitiveCallsPerMinute = 10), clock = { now })
        val t = token()
        repeat(3) {
            assertIs<PolicyDecision.Allow>(engine.evaluate(t, read))
            now += 10_000
        }
        // now = start + 30 s; window full.
        val denied = assertIs<PolicyDecision.Deny>(engine.evaluate(t, read))
        assertTrue(denied.rateLimited)
        assertTrue(denied.reason.contains("Slow down"), denied.reason)
        assertTrue(denied.reason.contains("retry in 30 s"), denied.reason)
        // Denied calls are not recorded: once the first call leaves the window, one slot frees up.
        now += 30_000
        assertIs<PolicyDecision.Allow>(engine.evaluate(t, read))
        assertIs<PolicyDecision.Deny>(engine.evaluate(t, read))
    }

    @Test
    fun sensitiveRateLimitIsSeparate() {
        val engine = PolicyEngine(RateLimits(callsPerMinute = 100, sensitiveCallsPerMinute = 2), clock = { now })
        val t = token(policy = ConfirmPolicy.NEVER)
        assertIs<PolicyDecision.Allow>(engine.evaluate(t, sensitive))
        assertIs<PolicyDecision.Allow>(engine.evaluate(t, sensitive))
        val denied = assertIs<PolicyDecision.Deny>(engine.evaluate(t, sensitive))
        assertTrue(denied.rateLimited)
        assertTrue(denied.reason.contains("sensitive"), denied.reason)
        // Non-sensitive calls are still allowed.
        assertIs<PolicyDecision.Allow>(engine.evaluate(t, action))
        now += PolicyEngine.WINDOW_MS
        assertIs<PolicyDecision.Allow>(engine.evaluate(t, sensitive))
    }

    @Test
    fun sensitiveCallsCountTowardTheTotal() {
        val engine = PolicyEngine(RateLimits(callsPerMinute = 2, sensitiveCallsPerMinute = 5), clock = { now })
        val t = token(policy = ConfirmPolicy.NEVER)
        engine.evaluate(t, sensitive)
        engine.evaluate(t, sensitive)
        assertIs<PolicyDecision.Deny>(engine.evaluate(t, read))
    }

    @Test
    fun limitsArePerToken() {
        val engine = PolicyEngine(RateLimits(callsPerMinute = 1, sensitiveCallsPerMinute = 1), clock = { now })
        assertIs<PolicyDecision.Allow>(engine.evaluate(token("aaaaaaaaaaaa"), read))
        assertIs<PolicyDecision.Deny>(engine.evaluate(token("aaaaaaaaaaaa"), read))
        assertIs<PolicyDecision.Allow>(engine.evaluate(token("bbbbbbbbbbbb"), read))
    }

    @Test
    fun rateLimitedSensitiveCallReturnsDenyNotConfirmation() {
        val engine = PolicyEngine(RateLimits(callsPerMinute = 10, sensitiveCallsPerMinute = 1), clock = { now })
        val t = token(policy = ConfirmPolicy.SENSITIVE)
        assertEquals(PolicyDecision.RequireConfirmation, engine.evaluate(t, sensitive))
        assertIs<PolicyDecision.Deny>(engine.evaluate(t, sensitive))
    }

    @Test
    fun idleTokensAreForgotten() {
        val engine = PolicyEngine(clock = { now })
        repeat(500) { engine.evaluate(token("t%011d".format(it)), read) }
        assertEquals(500, engine.trackedTokens())
        now += PolicyEngine.WINDOW_MS + 1
        engine.evaluate(token("zzzzzzzzzzzz"), read)
        assertEquals(1, engine.trackedTokens())
    }

    @Test
    fun clockGoingBackwardsDoesNotLockOut() {
        val engine = PolicyEngine(RateLimits(callsPerMinute = 1, sensitiveCallsPerMinute = 1), clock = { now })
        val t = token()
        engine.evaluate(t, read)
        now -= 3_600_000
        assertIs<PolicyDecision.Allow>(engine.evaluate(t, read))
    }

    @Test
    fun retryAfterIsClamped() {
        assertEquals(1, PolicyEngine.retryAfterSeconds(oldest = 0, now = 60_000))
        assertEquals(60, PolicyEngine.retryAfterSeconds(oldest = 0, now = 0))
        assertEquals(15, PolicyEngine.retryAfterSeconds(oldest = 0, now = 45_500))
    }

    @Test
    fun rejectsNonPositiveLimits() {
        assertFailsWith<IllegalArgumentException> { PolicyEngine(RateLimits(callsPerMinute = 0)) }
        assertFailsWith<IllegalArgumentException> { PolicyEngine(RateLimits(sensitiveCallsPerMinute = -1)) }
    }

    @Test
    fun concurrentCallsNeverExceedTheLimit() {
        val engine = PolicyEngine(RateLimits(callsPerMinute = 50, sensitiveCallsPerMinute = 50))
        val allowed = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        repeat(400) {
            pool.execute { if (engine.evaluate(token(), read) !is PolicyDecision.Deny) allowed.incrementAndGet() }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(50, allowed.get())
    }
}
