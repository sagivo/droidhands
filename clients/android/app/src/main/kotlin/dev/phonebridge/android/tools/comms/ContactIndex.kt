package dev.phonebridge.android.tools.comms

import java.util.concurrent.atomic.AtomicLong

/** One row of ContactsContract.Contacts, as needed for search and ranking. */
internal class ContactRow(val id: Long, val name: String, val alternative: String?, val starred: Boolean)

/**
 * Every contact with its names (display, alternative and nicknames) already prepared for matching:
 * building [NameKey]s is the expensive part of a name search. Immutable, so it can be shared
 * between concurrent searches.
 */
internal class ContactNameIndex(val rows: Map<Long, ContactRow>, private val keys: Map<Long, List<NameKey>>) {
    fun keysOf(id: Long): List<NameKey> = keys[id].orEmpty()

    companion object {
        /** Builds the index from provider rows and nicknames by contact id (pure). */
        fun build(rows: Map<Long, ContactRow>, nicknames: Map<Long, List<String>>): ContactNameIndex {
            val keys = HashMap<Long, List<NameKey>>(rows.size * 2)
            for (row in rows.values) {
                val names = buildList {
                    add(row.name)
                    row.alternative?.takeIf { it != row.name }?.let(::add)
                    nicknames[row.id]?.let(::addAll)
                }
                keys[row.id] = names.map(NameKey::of).filterNot { it.isEmpty }
            }
            return ContactNameIndex(rows, keys)
        }
    }
}

/**
 * A single value kept for at most [ttlMs] and dropped early by [invalidate]. Thread-safe: one
 * caller loads while concurrent callers wait for its result. A value loaded while an invalidation
 * happened is returned to that caller but not kept. Pure (injectable [clock]).
 */
internal class ExpiringValue<T : Any>(private val ttlMs: Long, private val clock: () -> Long = System::currentTimeMillis) {
    private class Entry<T>(val value: T, val loadedAt: Long, val generation: Long)

    private val generation = AtomicLong()
    private val lock = Any()

    @Volatile
    private var entry: Entry<T>? = null

    /** The kept value if still fresh, else the result of [load] (kept for next time). */
    fun get(load: () -> T): T {
        fresh()?.let { return it }
        synchronized(lock) {
            fresh()?.let { return it }
            val startedGeneration = generation.get()
            val startedAt = clock()
            val value = load()
            if (generation.get() == startedGeneration) entry = Entry(value, startedAt, startedGeneration)
            return value
        }
    }

    /** Drops the kept value (and any value being loaded right now). Safe from any thread. */
    fun invalidate() {
        generation.incrementAndGet()
        entry = null
    }

    /** Releases the kept value if it is no longer fresh, so it doesn't stay in memory until the next [get]. */
    fun dropExpired() {
        synchronized(lock) {
            if (fresh() == null) entry = null
        }
    }

    private fun fresh(): T? {
        val current = entry ?: return null
        val age = clock() - current.loadedAt
        return current.value.takeIf { current.generation == generation.get() && age in 0 until ttlMs }
    }
}
