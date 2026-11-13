package dev.phonebridge.android.tools.system

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import dev.phonebridge.core.tools.ToolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * One-shot location through the platform LocationManager (no Play services dependency).
 * Callers must hold ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION.
 */
internal class LocationReader(context: Context) {
    private val lm: LocationManager =
        context.getSystemService(LocationManager::class.java) ?: throw ToolException("Location is not supported on this phone.")

    /** [fresh] is false when no provider produced a fix in time and the freshest cached fix is returned. */
    data class Fix(val location: Location, val fresh: Boolean)

    val isLocationEnabled: Boolean get() = lm.isLocationEnabled

    /**
     * Asks the enabled providers in [LocationLogic.providerOrder] for a current fix within
     * [timeoutMs] in total, then falls back to the freshest last-known location. Null if none.
     */
    suspend fun locate(accuracy: LocationLogic.Accuracy, timeoutMs: Long, hasFine: Boolean): Fix? {
        val enabled = withContext(Dispatchers.IO) { lm.getProviders(true).toSet() }
        val order = LocationLogic.providerOrder(accuracy, Build.VERSION.SDK_INT, enabled, hasFine)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        for ((i, provider) in order.withIndex()) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) break
            current(provider, LocationLogic.attemptTimeoutMs(remaining, order.size - i))?.let { return Fix(it, fresh = true) }
        }
        return withContext(Dispatchers.IO) { lastKnown(enabled) }?.let { Fix(it, fresh = false) }
    }

    /** getCurrentLocation (API 30) with the request cancelled through its CancellationSignal on timeout. */
    @SuppressLint("MissingPermission")
    private suspend fun current(provider: String, timeoutMs: Long): Location? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            val once = AtomicBoolean()
            cont.invokeOnCancellation { signal.cancel() }
            try {
                lm.getCurrentLocation(provider, signal, Dispatchers.IO.asExecutor()) { location ->
                    if (once.compareAndSet(false, true) && cont.isActive) cont.resume(location)
                }
            } catch (e: SecurityException) {
                // e.g. gps without fine location on API 30; let the next provider try.
                if (once.compareAndSet(false, true)) cont.resume(null)
            } catch (e: IllegalArgumentException) {
                // Provider disappeared between listing and the request.
                if (once.compareAndSet(false, true)) cont.resume(null)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(providers: Set<String>): Location? {
        val fixes = (providers + LocationLogic.PASSIVE).mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
        return LocationLogic.freshest(fixes, { it.elapsedRealtimeNanos }, { if (it.hasAccuracy()) it.accuracy else null })
    }

    companion object {
        /** Seconds since the fix was taken, by the monotonic clock (immune to wall-clock changes). */
        fun ageSeconds(location: Location): Long =
            ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000_000L).coerceAtLeast(0)

        fun isMock(location: Location): Boolean =
            if (Build.VERSION.SDK_INT >= 31) location.isMock else @Suppress("DEPRECATION") location.isFromMockProvider
    }
}
