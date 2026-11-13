package dev.phonebridge.core.audit

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AuditLogsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun entry(i: Int, outcome: AuditOutcome = AuditOutcome.OK) = AuditEntry(
        timestamp = i.toLong(), tokenId = "tok", tokenName = "Token", method = "tools/call", tool = "echo",
        argsPreview = """{"i":$i}""", outcome = outcome, durationMs = 3, transport = "local", detail = null,
    )

    @Test
    fun inMemoryIsMostRecentFirstAndBounded() {
        val log = InMemoryAuditLog(capacity = 3)
        (1..5).forEach { log.record(entry(it)) }
        assertEquals(listOf(5L, 4L, 3L), log.entries.value.map { it.timestamp })
        log.clear()
        assertEquals(emptyList(), log.entries.value)
        assertFailsWith<IllegalArgumentException> { InMemoryAuditLog(0) }
    }

    @Test
    fun inMemoryIsThreadSafe() {
        val log = InMemoryAuditLog(capacity = 10_000)
        val pool = Executors.newFixedThreadPool(8)
        repeat(2000) { i -> pool.execute { log.record(entry(i)) } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(2000, log.entries.value.size)
    }

    @Test
    fun filePersistsAndReloads() {
        val file = File(tmp.root, "audit/audit.jsonl")
        val log = FileAuditLog(file, capacity = 10)
        (1..4).forEach { log.record(entry(it, if (it % 2 == 0) AuditOutcome.DENIED_BY_USER else AuditOutcome.OK)) }
        assertEquals(4, file.readLines().size)

        val reloaded = FileAuditLog(file, capacity = 10)
        assertEquals(log.entries.value, reloaded.entries.value)
        assertEquals(listOf(4L, 3L, 2L, 1L), reloaded.entries.value.map { it.timestamp })
        assertEquals(AuditOutcome.DENIED_BY_USER, reloaded.entries.value.first().outcome)
    }

    @Test
    fun reloadKeepsOnlyCapacity() {
        val file = File(tmp.root, "audit.jsonl")
        val log = FileAuditLog(file, capacity = 100)
        (1..30).forEach { log.record(entry(it)) }
        val small = FileAuditLog(file, capacity = 5)
        assertEquals(listOf(30L, 29L, 28L, 27L, 26L), small.entries.value.map { it.timestamp })
    }

    @Test
    fun compactsWhenFileExceedsTwiceCapacity() {
        val file = File(tmp.root, "audit.jsonl")
        val log = FileAuditLog(file, capacity = 5)
        (1..10).forEach { log.record(entry(it)) }
        assertEquals(10, file.readLines().size, "not yet over 2x")
        log.record(entry(11))
        assertEquals(5, file.readLines().size, "compacted to capacity")
        assertEquals(listOf(11L, 10L, 9L, 8L, 7L), FileAuditLog(file, capacity = 5).entries.value.map { it.timestamp })
        repeat(100) { log.record(entry(100 + it)) }
        assertTrue(file.readLines().size <= 10)
    }

    @Test
    fun toleratesCorruptAndTornLines() {
        val file = File(tmp.root, "audit.jsonl")
        val log = FileAuditLog(file, capacity = 10)
        log.record(entry(1))
        file.appendText("garbage line\n{\"timestamp\":2,\"outcome\":\"NOT_AN_OUTCOME\",\"method\":\"x\",\"transport\":\"t\"}\n")
        log.record(entry(3))
        file.appendText("{\"timestamp\":4,\"tokenId\":") // torn by a crash, no newline

        val reloaded = FileAuditLog(file, capacity = 10)
        assertEquals(listOf(3L, 1L), reloaded.entries.value.map { it.timestamp })
        reloaded.record(entry(5))
        assertEquals(listOf(5L, 3L, 1L), FileAuditLog(file, capacity = 10).entries.value.map { it.timestamp })
    }

    @Test
    fun recordNeverThrowsWhenFileIsUnwritable() {
        val notADir = File(tmp.root, "plain-file").apply { writeText("x") }
        val log = FileAuditLog(File(notADir, "audit.jsonl"), capacity = 2) // parent is a file: every write fails
        repeat(10) { log.record(entry(it)) }
        assertEquals(listOf(9L, 8L), log.entries.value.map { it.timestamp })
        log.clear()
        assertEquals(emptyList(), log.entries.value)
    }

    @Test
    fun clearTruncatesFile() {
        val file = File(tmp.root, "audit.jsonl")
        val log = FileAuditLog(file, capacity = 10)
        (1..3).forEach { log.record(entry(it)) }
        log.clear()
        assertEquals(emptyList(), log.entries.value)
        assertEquals(emptyList(), FileAuditLog(file, capacity = 10).entries.value)
        log.record(entry(9))
        assertEquals(listOf(9L), FileAuditLog(file, capacity = 10).entries.value.map { it.timestamp })
    }
}
