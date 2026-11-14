package dev.phonebridge.android.tools.comms

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log

/**
 * Process-wide [ContactNameIndex], so repeated name searches (contacts_search, then sms_send to
 * the same person…) don't re-read and re-prepare every contact. Kept for [TTL_MS] at most and
 * dropped as soon as the contacts provider reports a change. Without a change observer (it could
 * not be registered) nothing is cached. Thread-safe; [get] blocks on provider IPC when it loads.
 */
internal object ContactIndexCache {
    const val TTL_MS = 60_000L

    private const val TAG = "PhoneBridge/Contacts"

    /** An expired index is released a little after its TTL (contact data shouldn't linger in memory). */
    private const val RELEASE_SLACK_MS = 1_000L

    private val index = ExpiringValue<ContactNameIndex>(TTL_MS)

    @Volatile
    private var observing = false

    private val observer = object : ContentObserver(null) {
        // Called on a binder thread for any change under Contacts (names, nicknames, starring, deletions…).
        override fun onChange(selfChange: Boolean) = index.invalidate()
    }

    private val releaser by lazy { Handler(Looper.getMainLooper()) }

    /** The cached index, or [load]'s result (cached when changes can be observed). */
    fun get(context: Context, load: () -> ContactNameIndex): ContactNameIndex {
        if (!ensureObserving(context)) return load()
        return index.get {
            load().also { releaser.postDelayed(index::dropExpired, TTL_MS + RELEASE_SLACK_MS) }
        }
    }

    private fun ensureObserving(context: Context): Boolean {
        if (observing) return true
        synchronized(this) {
            if (observing) return true
            try {
                context.applicationContext.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
                // Anything cached before this point might have missed a change.
                index.invalidate()
                observing = true
            } catch (e: SecurityException) {
                Log.w(TAG, "Can't observe contact changes; contact search runs uncached")
            }
            return observing
        }
    }
}
