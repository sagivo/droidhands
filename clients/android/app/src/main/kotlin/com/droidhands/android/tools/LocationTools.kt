package com.droidhands.android.tools

import android.Manifest
import android.content.Context
import android.location.LocationManager
import com.droidhands.android.Edition
import com.droidhands.android.tools.support.Perms
import com.droidhands.android.tools.system.CalendarTime
import com.droidhands.android.tools.system.LaunchNotes
import com.droidhands.android.tools.system.LocationLogic
import com.droidhands.android.tools.system.LocationReader
import com.droidhands.android.tools.system.ReverseGeocoder
import com.droidhands.core.tools.Risk
import com.droidhands.core.tools.Scopes
import com.droidhands.core.tools.Tool
import com.droidhands.core.tools.ToolException
import com.droidhands.core.tools.ToolResult
import com.droidhands.core.tools.objectSchema
import com.droidhands.core.tools.optBool
import com.droidhands.core.tools.optInt
import com.droidhands.core.tools.optString
import com.droidhands.core.tools.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToLong

private const val GEOCODE_TIMEOUT_MS = 10_000L

private val NO_LOCATION_PERMISSION =
    "Location permission not granted on the phone. Ask the user to open the DroidHands app > Settings > Permissions & access and grant Location" +
        if (Edition.BACKGROUND_LOCATION) " (then \"Allow all the time\", because agent requests arrive while the app is in the background)." else "."

private const val LOCATION_OFF =
    "Location is turned off on the phone. Ask the user to turn on Location (quick settings or Android Settings > Location)."

private const val NO_BACKGROUND =
    "DroidHands does not have background location (\"Allow all the time\"), so Android may withhold the location while " +
        "the DroidHands app is not open. Ask the user to grant it in the DroidHands app > Settings > Permissions & access > Location while in background."

/**
 * The store edition has no background location (docs/EDITIONS.md), so Android gives DroidHands the location only
 * while its own screen is open; the bridge's foreground service doesn't count.
 */
private const val ONLY_WHILE_OPEN =
    "Android shares the phone's location with DroidHands only while the DroidHands app is open on the screen, and it " +
        "isn't open now. Ask the user to open the DroidHands app, then call location_get again."

/** Tools: location_get (docs/TOOLS.md). */
fun locationTools(context: Context): List<Tool> = listOf(locationGet(context))

private fun locationGet(context: Context) = tool(
    name = "location_get",
    title = "Get current location",
    description = "Current location of the phone: {latitude,longitude,accuracy_m,time,age_seconds,provider,address?}. " +
        "accuracy=high prefers GPS (slower, outdoors); balanced (default) uses Wi-Fi/cell. Waits up to timeout_seconds " +
        "(default 20, max 60) for a fresh fix, else returns the last known location with its age. address=true adds a " +
        "best-effort street address." +
        if (Edition.BACKGROUND_LOCATION) "" else " Works only while the DroidHands app is open on the phone's screen.",
    scope = Scopes.LOCATION,
    risk = Risk.READ,
    schema = objectSchema {
        string("accuracy", "high or balanced (default balanced)", enum = LocationLogic.Accuracy.entries.map { it.arg })
        integer("timeout_seconds", "Max seconds to wait for a fresh fix (default 20, max 60)", minimum = 1, maximum = LocationLogic.MAX_TIMEOUT_SECONDS.toLong())
        boolean("address", "Also reverse-geocode to a street address")
    },
    // Fix (<= 60 s) + geocoding (<= 10 s) + slack. READ tools never wait for confirmation.
    timeoutMs = 75_000,
    unavailable = { locationUnavailableReason(context) },
) { args, _ ->
    val hasFine = Perms.has(context, Manifest.permission.ACCESS_FINE_LOCATION)
    if (!hasFine && !Perms.has(context, Manifest.permission.ACCESS_COARSE_LOCATION)) throw ToolException(NO_LOCATION_PERMISSION)
    // Without background location in the manifest, waiting for a fix in the background would only time out.
    if (!Edition.BACKGROUND_LOCATION && !LaunchNotes.isAppInForeground()) throw ToolException(ONLY_WHILE_OPEN)
    // In the store edition the check above stands in for background location.
    val hasBackground = !Edition.BACKGROUND_LOCATION || Perms.has(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    val accuracy = LocationLogic.Accuracy.parse(args.optString("accuracy"))
    val timeoutSeconds = LocationLogic.timeoutSeconds(args.optInt("timeout_seconds"))
    val wantAddress = args.optBool("address") ?: false

    val reader = LocationReader(context)
    if (!withContext(Dispatchers.IO) { reader.isLocationEnabled }) throw ToolException(LOCATION_OFF)
    val fix = reader.locate(accuracy, timeoutSeconds * 1000L, hasFine) ?: throw ToolException(
        "Could not get a location within $timeoutSeconds s and the phone has no recent location. " +
            if (!hasBackground && !LaunchNotes.isAppInForeground()) NO_BACKGROUND
            else "Retry with a longer timeout_seconds or accuracy=balanced; indoors GPS may not get a fix.",
    )

    val loc = fix.location
    val address = if (wantAddress) ReverseGeocoder.lookup(context, loc.latitude, loc.longitude, GEOCODE_TIMEOUT_MS) else null
    val ageSeconds = LocationReader.ageSeconds(loc)
    val notes = buildList {
        if (!fix.fresh) add("No fresh fix within $timeoutSeconds s; this is the last known location (${ageSeconds}s old).")
        if (!hasFine) add("Only approximate location is granted (about 1-3 km). For precise location the user must allow \"Precise\" in Setup.")
        if (!hasBackground) add(NO_BACKGROUND)
        if (wantAddress && address == null) add("The address lookup was unavailable or timed out.")
    }
    ToolResult.json {
        put("latitude", round(loc.latitude, 6))
        put("longitude", round(loc.longitude, 6))
        if (loc.hasAccuracy()) put("accuracy_m", round(loc.accuracy.toDouble(), 1))
        put("time", CalendarTime.format(Instant.ofEpochMilli(loc.time), ZoneId.systemDefault()))
        put("age_seconds", ageSeconds)
        put("provider", loc.provider ?: "unknown")
        if (!hasFine) put("approximate", true)
        if (LocationReader.isMock(loc)) put("mock", true)
        address?.let { put("address", it) }
        if (notes.isNotEmpty()) put("note", notes.joinToString(" "))
    }
}

private fun locationUnavailableReason(context: Context): String? {
    if (!Perms.has(context, Manifest.permission.ACCESS_FINE_LOCATION) && !Perms.has(context, Manifest.permission.ACCESS_COARSE_LOCATION)) {
        return NO_LOCATION_PERMISSION
    }
    val enabled = runCatching { context.getSystemService(LocationManager::class.java)?.isLocationEnabled }.getOrNull()
    return if (enabled == false) LOCATION_OFF else null
}

private fun round(value: Double, decimals: Int): Double {
    val factor = Math.pow(10.0, decimals.toDouble())
    return (value * factor).roundToLong() / factor
}
