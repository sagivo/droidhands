// PhoneStateListener (deprecated in API 31) is the only API left that reports the incoming number.
@file:Suppress("DEPRECATION")

package com.droidhands.android.tools.calls

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.telephony.PhoneStateListener
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.droidhands.android.tools.support.Perms
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Best-effort number of the call that is ringing now.
 *
 * Android has no "who is calling" query for ordinary apps: the call log is written only after the
 * call ends, TelephonyCallback (Android 12+) no longer reports numbers, and call screening needs a
 * system role. What still works is the (deprecated) PhoneStateListener: right after registration
 * it reports the current call state together with the incoming number, but only to apps holding
 * READ_CALL_LOG (otherwise the number is empty). One listener is registered per active SIM and
 * removed again immediately. Null whenever the number isn't available (withheld, permission
 * missing, no answer in time).
 */
internal object IncomingNumber {
    const val DEFAULT_TIMEOUT_MS = 1_500L

    /** Main-safe; returns within about [timeoutMs]. */
    suspend fun read(context: Context, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String? = withContext(Dispatchers.IO) {
        if (!canRead(context)) return@withContext null
        val base = context.getSystemService(TelephonyManager::class.java) ?: return@withContext null
        val managers = perSubscription(context, base)
        coroutineScope {
            managers.map { async { ringingNumber(it, timeoutMs) } }.awaitAll().firstNotNullOfOrNull { it }
        }
    }

    /** Whether the permissions the number needs are granted. */
    fun canRead(context: Context): Boolean =
        Perms.has(context, Manifest.permission.READ_PHONE_STATE) && Perms.has(context, Manifest.permission.READ_CALL_LOG)

    /** One TelephonyManager per active SIM (the listener only reports its own subscription). */
    @SuppressLint("MissingPermission") // READ_PHONE_STATE checked in read().
    private fun perSubscription(context: Context, base: TelephonyManager): List<TelephonyManager> {
        val subscriptions = runCatching {
            context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList
        }.getOrNull().orEmpty()
        if (subscriptions.size <= 1) return listOf(base)
        return subscriptions.mapNotNull { runCatching { base.createForSubscriptionId(it.subscriptionId) }.getOrNull() }.ifEmpty { listOf(base) }
    }

    private suspend fun ringingNumber(manager: TelephonyManager, timeoutMs: Long): String? {
        val reported = CompletableDeferred<Pair<Int, String?>>()
        val listener = object : PhoneStateListener(Dispatchers.IO.asExecutor()) {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                reported.complete(state to phoneNumber)
            }
        }
        return try {
            manager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            val (state, number) = withTimeoutOrNull(timeoutMs) { reported.await() } ?: return null
            if (CallStates.fromTelephony(state) == CallState.RINGING) CallStates.cleanNumber(number) else null
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalStateException) {
            // No telephony service (e.g. Wi-Fi-only device).
            null
        } finally {
            runCatching { manager.listen(listener, PhoneStateListener.LISTEN_NONE) }
        }
    }
}
