package dev.phonebridge.android.ui.agents

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class UsageStatsTest {
    private val today = LocalDate.of(2024, 11, 14)

    @Test
    fun lastDaysFillsGapsOldestFirst() {
        val perDay = mapOf("2024-11-14" to 4L, "2024-11-12" to 23L, "2024-10-01" to 99L, "garbage" to 5L, "2024-11-13" to -3L)
        val days = UsageStats.lastDays(perDay, today, days = 3)
        assertEquals(listOf(LocalDate.of(2024, 11, 12), LocalDate.of(2024, 11, 13), today), days.map { it.date })
        assertEquals(listOf(23L, 0L, 4L), days.map { it.count })
        assertEquals(14, UsageStats.lastDays(emptyMap(), today).size)
    }

    @Test
    fun lastDaysCrossesMonthAndYear() {
        val days = UsageStats.lastDays(mapOf("2024-12-31" to 2L), LocalDate.of(2025, 1, 2), days = 3)
        assertEquals(listOf(2L, 0L, 0L), days.map { it.count })
    }

    @Test
    fun topToolsByCountThenName() {
        val top = UsageStats.topTools(mapOf("b" to 5L, "a" to 5L, "c" to 9L, "d" to 1L, "zero" to 0L), limit = 3)
        assertEquals(listOf(ToolCount("c", 9), ToolCount("a", 5), ToolCount("b", 5)), top)
        assertEquals(emptyList<ToolCount>(), UsageStats.topTools(emptyMap()))
    }

    @Test
    fun niceCeilings() {
        assertEquals(1L, UsageStats.niceCeiling(0))
        assertEquals(1L, UsageStats.niceCeiling(1))
        assertEquals(2L, UsageStats.niceCeiling(2))
        assertEquals(5L, UsageStats.niceCeiling(3))
        assertEquals(10L, UsageStats.niceCeiling(7))
        assertEquals(10L, UsageStats.niceCeiling(10))
        assertEquals(20L, UsageStats.niceCeiling(11))
        assertEquals(50L, UsageStats.niceCeiling(23))
        assertEquals(100L, UsageStats.niceCeiling(51))
        assertEquals(2000L, UsageStats.niceCeiling(1234))
    }

    @Test
    fun compactNumbers() {
        assertEquals("0", UsageStats.compact(0))
        assertEquals("999", UsageStats.compact(999))
        assertEquals("1K", UsageStats.compact(1000))
        assertEquals("1.2K", UsageStats.compact(1234))
        assertEquals("12.9K", UsageStats.compact(12_900))
        assertEquals("4.2M", UsageStats.compact(4_200_000))
    }

    @Test
    fun chartDescription() {
        val days = UsageStats.lastDays(mapOf("2024-11-12" to 23L, "2024-11-14" to 1L), today, days = 3)
        assertEquals(
            "Calls per day, last 3 days: 24 calls in total. Busiest day 11-12 with 23 calls. Today 1 call.",
            UsageStats.chartDescription(days) { "${it.monthValue}-${it.dayOfMonth}" },
        )
        assertEquals("Calls per day, last 14 days: no calls.", UsageStats.chartDescription(UsageStats.lastDays(emptyMap(), today)) { "" })
        assertEquals("No usage data.", UsageStats.chartDescription(emptyList()) { "" })
    }

    @Test
    fun percent() {
        assertEquals(0, UsageStats.percent(1, 0))
        assertEquals(33, UsageStats.percent(1, 3))
        assertEquals(100, UsageStats.percent(5, 5))
    }
}
