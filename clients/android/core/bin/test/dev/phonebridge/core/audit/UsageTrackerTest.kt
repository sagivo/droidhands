package dev.phonebridge.core.audit

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsageTrackerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val jerusalem = ZoneId.of("Asia/Jerusalem")
    private var zone: ZoneId = jerusalem

    /** 2026-11-14 12:00 in Jerusalem. */
    private var now = ZonedDateTime.of(2026, 11, 14, 12, 0, 0, 0, jerusalem).toInstant().toEpochMilli()

    private fun tracker(file: File? = null, debounceMs: Long = 60_000) = UsageTracker(file, clock = { now }, zone = { zone }, debounceMs = debounceMs)

    private fun entry(
        outcome: AuditOutcome = AuditOutcome.OK,
        tool: String? = "sms_send",
        tokenId: String? = "tok1",
        method: String = "tools/call",
        timestamp: Long = now,
    ) = AuditEntry(timestamp, tokenId, "Claude", method, tool, null, outcome, 5, "relay")

    @Test
    fun classifiesOutcomes() {
        val t = tracker()
        val ok = listOf(AuditOutcome.OK)
        val errors = listOf(AuditOutcome.TOOL_ERROR, AuditOutcome.TIMEOUT, AuditOutcome.EXCEPTION, AuditOutcome.UNKNOWN_TOOL)
        val denied = listOf(
            AuditOutcome.DENIED_SCOPE, AuditOutcome.RATE_LIMITED, AuditOutcome.PAUSED, AuditOutcome.DENIED_BY_USER,
            AuditOutcome.CONFIRM_TIMEOUT, AuditOutcome.DENIED_POLICY, AuditOutcome.DENIED_APP, AuditOutcome.CANCELLED,
        )
        (ok + errors + denied).forEach { t.record(entry(it)) }
        val usage = t.usage.value.getValue("tok1")
        assertEquals(13, usage.totalCalls)
        assertEquals(1, usage.okCalls)
        assertEquals(4, usage.errorCalls)
        assertEquals(8, usage.deniedCalls)
        assertEquals(now, usage.lastCallAt)
        // Every outcome is classified somewhere (or deliberately ignored).
        AuditOutcome.entries.forEach { UsageCounting.category(it) }
    }

    @Test
    fun ignoresAuthFailuresOtherMethodsAndAnonymousEntries() {
        val t = tracker()
        t.record(entry(AuditOutcome.AUTH_FAILED))
        t.record(entry(method = "initialize"))
        t.record(entry(method = "tools/call,ping"))
        t.record(entry(tokenId = null))
        t.record(entry(tokenId = ""))
        assertEquals(emptyMap(), t.usage.value)
    }

    @Test
    fun countsPerToolButNotUnknownToolNames() {
        val t = tracker()
        t.record(entry(tool = "sms_send"))
        t.record(entry(tool = "sms_send", outcome = AuditOutcome.DENIED_BY_USER))
        t.record(entry(tool = "contacts_search"))
        t.record(entry(tool = "made_up_tool", outcome = AuditOutcome.UNKNOWN_TOOL))
        t.record(entry(tool = null, outcome = AuditOutcome.UNKNOWN_TOOL))
        val usage = t.usage.value.getValue("tok1")
        assertEquals(mapOf("sms_send" to 2L, "contacts_search" to 1L), usage.perTool)
        assertEquals(5, usage.totalCalls)
        assertEquals(2, usage.errorCalls)
    }

    @Test
    fun perToolIsBounded() {
        val t = tracker()
        repeat(UsageCounting.MAX_TOOLS + 50) { t.record(entry(tool = "tool_$it")) }
        val usage = t.usage.value.getValue("tok1")
        assertEquals(UsageCounting.MAX_TOOLS, usage.perTool.size)
        assertEquals(UsageCounting.MAX_TOOLS + 50L, usage.totalCalls)
        t.record(entry(tool = "tool_0"))
        assertEquals(2L, t.usage.value.getValue("tok1").perTool["tool_0"])
    }

    @Test
    fun daysFollowTheZone() {
        val t = tracker()
        // 23:30 UTC on Nov 13 is 01:30 on Nov 14 in Jerusalem (UTC+2).
        val lateUtc = ZonedDateTime.of(2026, 11, 13, 23, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        t.record(entry(timestamp = lateUtc))
        zone = ZoneId.of("UTC")
        t.record(entry(timestamp = lateUtc))
        assertEquals(mapOf("2026-11-13" to 1L, "2026-11-14" to 1L), t.usage.value.getValue("tok1").perDay)
    }

    @Test
    fun dayRolloverStartsANewBucket() {
        val t = tracker()
        t.record(entry())
        t.record(entry())
        now += 24 * 3_600_000L
        t.record(entry())
        assertEquals(mapOf("2026-11-14" to 2L, "2026-11-15" to 1L), t.usage.value.getValue("tok1").perDay)
    }

    @Test
    fun keepsOnly31Days() {
        val t = tracker()
        repeat(40) {
            t.record(entry())
            now += 24 * 3_600_000L
        }
        now -= 24 * 3_600_000L // the day of the last record
        val perDay = t.usage.value.getValue("tok1").perDay
        val today = LocalDate.of(2026, 11, 14).plusDays(39)
        assertEquals(31, perDay.size)
        assertEquals(today.minusDays(30).toString(), perDay.keys.first())
        assertEquals(today.toString(), perDay.keys.last())
        assertEquals(40, t.usage.value.getValue("tok1").totalCalls)
    }

    @Test
    fun separateTokensAndForget() {
        val t = tracker()
        t.record(entry(tokenId = "a"))
        t.record(entry(tokenId = "b"))
        t.record(entry(tokenId = "b"))
        assertEquals(1, t.usage.value.getValue("a").totalCalls)
        assertEquals(2, t.usage.value.getValue("b").totalCalls)
        t.forget("a")
        t.forget("unknown")
        assertEquals(setOf("b"), t.usage.value.keys)
    }

    @Test
    fun lastCallAtNeverGoesBackwards() {
        val t = tracker()
        t.record(entry(timestamp = now))
        t.record(entry(timestamp = now - 10_000))
        assertEquals(now, t.usage.value.getValue("tok1").lastCallAt)
    }

    @Test
    fun failingZoneFallsBackToTheSystemZone() {
        val t = UsageTracker(null, clock = { now }, zone = { throw IllegalStateException("no zone") })
        t.record(entry())
        assertEquals(1, t.usage.value.getValue("tok1").totalCalls)
    }

    @Test
    fun recordNeverThrows() {
        var fail = false
        val t = UsageTracker(null, clock = { if (fail) throw IllegalStateException("no clock") else now })
        fail = true
        t.record(entry())
        assertEquals(emptyMap(), t.usage.value)
    }

    @Test
    fun flushPersistsAndReloads() {
        val file = File(tmp.root, "usage/usage.json")
        val t = tracker(file)
        t.record(entry(tool = "sms_send"))
        t.record(entry(tool = "ui_tap", outcome = AuditOutcome.DENIED_APP))
        assertFalse(file.exists(), "debounced: nothing written yet")
        t.flush()
        assertTrue(file.exists())
        val reloaded = tracker(file)
        assertEquals(t.usage.value, reloaded.usage.value)
        assertEquals(1, reloaded.usage.value.getValue("tok1").deniedCalls)

        t.forget("tok1")
        t.flush()
        assertEquals(emptyMap(), tracker(file).usage.value)
    }

    @Test
    fun debouncedWriteHappensWithoutFlush() {
        val file = File(tmp.root, "usage.json")
        val t = tracker(file, debounceMs = 50)
        t.record(entry())
        val deadline = System.currentTimeMillis() + 5_000
        while (!file.exists() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(file.exists(), "written after the debounce delay")
        assertEquals(1, tracker(file).usage.value.getValue("tok1").totalCalls)
    }

    @Test
    fun reloadTrimsOldDays() {
        val file = File(tmp.root, "usage.json")
        val t = tracker(file)
        t.record(entry())
        t.flush()
        now += 40 * 24 * 3_600_000L
        val later = tracker(file).usage.value.getValue("tok1")
        assertEquals(emptyMap(), later.perDay)
        assertEquals(1, later.totalCalls)
    }

    @Test
    fun corruptFileIsQuarantined() {
        val file = File(tmp.root, "usage.json")
        file.writeText("{not json")
        val t = tracker(file)
        assertEquals(emptyMap(), t.usage.value)
        assertTrue(File(tmp.root, "usage.json.corrupt").exists())
        t.record(entry())
        t.flush()
        assertEquals(1, tracker(file).usage.value.getValue("tok1").totalCalls)
    }

    @Test
    fun brokenEntriesAreSkipped() {
        val file = File(tmp.root, "usage.json")
        file.writeText(
            """{"version":1,"future":true,"usage":{""" +
                """"good":{"tokenId":"good","totalCalls":3,"okCalls":3,"perDay":{"2026-11-14":3,"garbage":9}},""" +
                """"bad":{"tokenId":7,"totalCalls":"x"},""" +
                """"mismatch":{"tokenId":"other","totalCalls":1}}}""",
        )
        val usage = tracker(file).usage.value
        assertEquals(setOf("good"), usage.keys)
        assertEquals(3, usage.getValue("good").totalCalls)
        assertEquals(mapOf("2026-11-14" to 3L), usage.getValue("good").perDay)
        assertEquals(emptyMap(), tracker(File(tmp.root, "missing.json")).usage.value)
        File(tmp.root, "blank.json").writeText("  ")
        assertEquals(emptyMap(), tracker(File(tmp.root, "blank.json")).usage.value)
    }

    @Test
    fun failedWriteIsRetriedByTheNextFlush() {
        val dir = File(tmp.root, "blocked")
        dir.writeText("a file where the directory should be")
        val file = File(dir, "usage.json")
        val t = tracker(file)
        t.record(entry())
        t.flush() // fails quietly
        assertFalse(file.exists())
        dir.delete()
        t.flush()
        assertTrue(file.exists())
    }

    @Test
    fun concurrentRecordsAreAllCounted() {
        val t = tracker()
        val pool = Executors.newFixedThreadPool(8)
        repeat(2_000) { i -> pool.execute { t.record(entry(tokenId = "tok${i % 4}")) } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(2_000, t.usage.value.values.sumOf { it.totalCalls })
        assertEquals(4, t.usage.value.size)
    }

    @Test
    fun rejectsNegativeDebounce() {
        assertFailsWith<IllegalArgumentException> { UsageTracker(null, debounceMs = -1) }
    }

    // ---- TrackingAuditLog ----

    @Test
    fun trackingLogFeedsBothAndClearKeepsUsage() {
        val log = InMemoryAuditLog()
        val t = tracker()
        val tracking = TrackingAuditLog(log, t)
        tracking.record(entry())
        tracking.record(entry(AuditOutcome.AUTH_FAILED))
        assertEquals(2, tracking.entries.value.size)
        assertEquals(log.entries.value, tracking.entries.value)
        assertEquals(1, t.usage.value.getValue("tok1").totalCalls)
        tracking.clear()
        assertEquals(emptyList(), log.entries.value)
        assertEquals(1, t.usage.value.getValue("tok1").totalCalls, "usage outlives the log")
    }

    @Test
    fun trackingLogCountsEvenWhenTheDelegateFails() {
        val failing = object : AuditLog {
            override fun record(entry: AuditEntry) = throw IllegalStateException("disk full")
            override val entries = MutableStateFlow(emptyList<AuditEntry>())
            override fun clear() = Unit
        }
        val t = tracker()
        assertFailsWith<IllegalStateException> { TrackingAuditLog(failing, t).record(entry()) }
        assertEquals(1, t.usage.value.getValue("tok1").totalCalls)
        assertNull(t.usage.value["other"])
    }
}
