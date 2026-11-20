package com.droidhands.android.account

import android.content.Context
import android.util.Log
import com.droidhands.core.account.AccountSession
import com.droidhands.core.account.AccountUser
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Keeps the account session and a pending sign-in in app-private SharedPreferences (`bridge_account`; no
 * SharedPreferences leave the phone, see data_extraction_rules.xml). The session token is a secret: never logged.
 */
internal class AccountStore(context: Context) : AccountStorage {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun loadSession(): AccountSession? {
        val token = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        val user = prefs.getString(KEY_USER, null)?.let {
            try {
                json.decodeFromString(AccountUser.serializer(), it)
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        if (user == null) {
            Log.w(TAG, "Stored account is unreadable; signing out")
            clearSession()
            return null
        }
        return AccountSession(token, user)
    }

    override fun saveSession(session: AccountSession) {
        if (!prefs.edit().putString(KEY_TOKEN, session.token).putString(KEY_USER, json.encodeToString(AccountUser.serializer(), session.user)).commit()) {
            Log.e(TAG, "Could not save the account")
        }
    }

    override fun clearSession() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_USER).commit()
    }

    override fun savePending(pending: PendingSignIn) {
        prefs.edit()
            .putString(KEY_PENDING_VERIFIER, pending.verifier)
            .putLong(KEY_PENDING_STARTED, pending.startedAt)
            .putBoolean(KEY_PENDING_RELAY, pending.useRelay)
            .commit()
    }

    override fun takePending(): PendingSignIn? {
        val verifier = prefs.getString(KEY_PENDING_VERIFIER, null)
        val pending = verifier?.let {
            PendingSignIn(it, prefs.getLong(KEY_PENDING_STARTED, 0L), prefs.getBoolean(KEY_PENDING_RELAY, false))
        }
        prefs.edit().remove(KEY_PENDING_VERIFIER).remove(KEY_PENDING_STARTED).remove(KEY_PENDING_RELAY).commit()
        return pending
    }

    private companion object {
        const val TAG = "DroidHands/Account"
        const val PREFS_NAME = "bridge_account"
        const val KEY_TOKEN = "session_token"
        const val KEY_USER = "user"
        const val KEY_PENDING_VERIFIER = "pending_verifier"
        const val KEY_PENDING_STARTED = "pending_started_at"
        const val KEY_PENDING_RELAY = "pending_use_relay"
        val json = Json { ignoreUnknownKeys = true }
    }
}
