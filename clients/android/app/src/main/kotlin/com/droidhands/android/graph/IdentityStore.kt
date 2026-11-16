package com.droidhands.android.graph

import android.content.Context
import android.util.Log
import com.droidhands.core.auth.DeviceIdentity

/**
 * Persists the device identity secret in app-private SharedPreferences (`bridge_identity`, excluded
 * from backups by data_extraction_rules.xml). Generated once on first run.
 */
internal object IdentityStore {
    private const val TAG = "DroidHands/Identity"
    private const val PREFS_NAME = "bridge_identity"
    private const val KEY_SECRET = "device_secret"

    /** Performs disk I/O on first call; call from a background thread when possible. */
    fun loadOrCreate(context: Context): DeviceIdentity {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_SECRET, null)
        if (stored != null) {
            try {
                return DeviceIdentity.fromEncoded(stored)
            } catch (e: IllegalArgumentException) {
                // Never log the value itself.
                Log.e(TAG, "Stored device identity is unreadable; generating a new one (existing tokens stop working)")
            }
        }
        val identity = DeviceIdentity.generate()
        // commit(): the identity must be durable before any token is issued for it.
        if (!prefs.edit().putString(KEY_SECRET, identity.secretEncoded).commit()) {
            Log.e(TAG, "Could not persist the device identity")
        }
        return identity
    }
}
