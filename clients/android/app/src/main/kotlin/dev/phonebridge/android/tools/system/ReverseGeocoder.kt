package dev.phonebridge.android.tools.system

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** Best-effort reverse geocoding to a one-line address. Never throws; null when unavailable. */
internal object ReverseGeocoder {
    /**
     * Pre-33 lookups are blocking and not interruptible, so they run detached here and are simply
     * abandoned on timeout instead of holding up the tool call.
     */
    private val legacyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun lookup(context: Context, latitude: Double, longitude: Double, timeoutMs: Long): String? {
        if (timeoutMs <= 0 || !Geocoder.isPresent()) return null
        val result = CompletableDeferred<Address?>()
        val started = runCatching {
            val geocoder = Geocoder(context, Locale.getDefault())
            if (Build.VERSION.SDK_INT >= 33) {
                geocoder.getFromLocation(
                    latitude, longitude, 1,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            result.complete(addresses.firstOrNull())
                        }

                        override fun onError(errorMessage: String?) {
                            result.complete(null)
                        }
                    },
                )
            } else {
                legacyScope.launch {
                    @Suppress("DEPRECATION")
                    result.complete(runCatching { geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull() }.getOrNull())
                }
            }
        }.isSuccess
        if (!started) return null
        val address = withTimeoutOrNull(timeoutMs) { result.await() } ?: return null
        return runCatching { format(address) }.getOrNull()
    }

    private fun format(a: Address): String? = LocationLogic.formatAddress(
        lines = (0..a.maxAddressLineIndex).map { a.getAddressLine(it) },
        street = a.thoroughfare,
        number = a.subThoroughfare,
        locality = a.locality,
        adminArea = a.adminArea,
        postalCode = a.postalCode,
        country = a.countryName,
    )
}
