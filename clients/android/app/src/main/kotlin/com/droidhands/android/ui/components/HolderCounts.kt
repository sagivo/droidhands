package com.droidhands.android.ui.components

/**
 * Counts the holders of a per-key protection (FLAG_SECURE, touch filtering…) so the protection is
 * applied when the first holder arrives and lifted only when the last one leaves: two parts of a
 * screen can need it at once (a new token and an approval card). Pure; not thread-safe (main thread).
 * Pass a [java.util.WeakHashMap] for keys such as windows that must not be kept alive.
 */
internal class HolderCounts<K : Any>(private val counts: MutableMap<K, Int> = HashMap()) {
    /** Adds a holder of [key]; true if it is the first, i.e. the protection must be applied now. */
    fun acquire(key: K): Boolean {
        val count = (counts[key] ?: 0) + 1
        counts[key] = count
        return count == 1
    }

    /** Removes a holder of [key]; true if it was the last, i.e. the protection must be lifted now. */
    fun release(key: K): Boolean {
        val count = counts[key] ?: return false
        if (count > 1) {
            counts[key] = count - 1
            return false
        }
        counts.remove(key)
        return true
    }
}
