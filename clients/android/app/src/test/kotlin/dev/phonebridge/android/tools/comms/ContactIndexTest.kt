package dev.phonebridge.android.tools.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ExpiringValueTest {
    private var now = 1_000L
    private val loads = AtomicInteger()
    private val cache = ExpiringValue<String>(ttlMs = 60_000) { now }

    private fun load(): String = "v${loads.incrementAndGet()}"

    @Test
    fun `value is reused until it expires`() {
        assertEquals("v1", cache.get(::load))
        now += 59_999
        assertEquals("v1", cache.get(::load))
        now += 1
        assertEquals("v2", cache.get(::load))
        assertEquals(2, loads.get())
    }

    @Test
    fun `invalidate forces a reload`() {
        assertEquals("v1", cache.get(::load))
        cache.invalidate()
        assertEquals("v2", cache.get(::load))
        assertEquals("v2", cache.get(::load))
    }

    @Test
    fun `a value loaded while an invalidation happened is used once but not kept`() {
        val value = cache.get {
            cache.invalidate() // A contacts change arrives mid-load.
            "stale"
        }
        assertEquals("stale", value)
        assertEquals("v1", cache.get(::load))
    }

    @Test
    fun `dropExpired keeps fresh values and releases stale ones`() {
        assertEquals("v1", cache.get(::load))
        cache.dropExpired()
        assertEquals("v1", cache.get(::load))
        now += 60_000
        cache.dropExpired()
        now -= 60_000 // Even if "now" went back, the dropped value is gone.
        assertEquals("v2", cache.get(::load))
    }

    @Test
    fun `a clock moving backwards reloads`() {
        assertEquals("v1", cache.get(::load))
        now -= 10
        assertEquals("v2", cache.get(::load))
    }

    @Test
    fun `concurrent callers share one load`() {
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val shared = ExpiringValue<String>(ttlMs = 60_000)
        val results = (1..8).map {
            pool.submit<String> {
                start.await()
                shared.get {
                    Thread.sleep(50)
                    load()
                }
            }
        }
        start.countDown()
        assertEquals(setOf("v1"), results.map { it.get(5, TimeUnit.SECONDS) }.toSet())
        assertEquals(1, loads.get())
        pool.shutdown()
    }
}

class ContactNameIndexTest {
    @Test
    fun `index holds keys for display, alternative and nicknames`() {
        val rows = mapOf(
            1L to ContactRow(1, "Tania Cohen", "Cohen, Tania", starred = false),
            2L to ContactRow(2, "Bob", "Bob", starred = true),
            3L to ContactRow(3, "", null, starred = false),
        )
        val index = ContactNameIndex.build(rows, mapOf(1L to listOf("Tanya"), 2L to listOf("Bobby", "  ")))
        assertEquals(3, index.keysOf(1).size)
        assertEquals(2, index.keysOf(2).size) // Alternative equals the name; the blank nickname has no key.
        assertTrue(index.keysOf(3).isEmpty())
        assertTrue(index.keysOf(99).isEmpty())
        assertEquals(rows, index.rows)
    }

    @Test
    fun `prepared keys score like keys built on the fly`() {
        val rows = mapOf(1L to ContactRow(1, "Tania Cohen", null, starred = false))
        val index = ContactNameIndex.build(rows, mapOf(1L to listOf("Tanchik")))
        val query = NameKey.of("Tanya")
        assertEquals(
            NameMatching.bestScore(query, listOf("Tania Cohen", "Tanchik").map(NameKey::of)),
            NameMatching.bestScore(query, index.keysOf(1)),
            0.0,
        )
        assertTrue(NameMatching.bestScore(query, index.keysOf(1)) >= NameMatching.FUZZY_MIN)
    }
}
