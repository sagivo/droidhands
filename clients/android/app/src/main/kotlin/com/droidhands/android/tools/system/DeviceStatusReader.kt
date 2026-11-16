package com.droidhands.android.tools.system

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.droidhands.android.access.BridgeAccessibilityService
import com.droidhands.android.notify.BridgeNotificationListener
import com.droidhands.android.tools.support.DeviceState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId

/**
 * Builds the `device_status` snapshot. Every section is read independently and skipped if it
 * fails, so one broken system service never hides the rest. Blocking IPC: call on Dispatchers.IO.
 */
internal class DeviceStatusReader(private val context: Context) {

    fun read(): JsonObject = buildJsonObject {
        section { battery() }
        section { deviceInfo() }
        section {
            put("screen_on", DeviceState.isScreenOn(context))
            put("locked", DeviceState.isLocked(context))
        }
        section { network() }
        section { audio() }
        section { time() }
        section { storage() }
        section { access() }
    }

    private inline fun JsonObjectBuilder.section(block: JsonObjectBuilder.() -> Unit) {
        runCatching { block() }
    }

    private fun JsonObjectBuilder.battery() {
        val bm = context.getSystemService(BatteryManager::class.java)
        // Sticky broadcast: registering a null receiver just returns the last ACTION_BATTERY_CHANGED.
        val sticky: Intent? = ContextCompat.registerReceiver(
            context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val percent = DeviceLogic.batteryPercent(
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
        )
        putIfNotNull("battery_percent", percent)
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = bm?.isCharging
            ?: (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
        put("charging", charging)
        val plugged = when (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            BatteryManager.BATTERY_PLUGGED_DOCK -> "dock"
            else -> null
        }
        putIfNotNull("plugged", plugged)
        context.getSystemService(PowerManager::class.java)?.let { put("power_save", it.isPowerSaveMode) }
    }

    private fun JsonObjectBuilder.deviceInfo() {
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { put("device_name", it) }
        put("android_version", Build.VERSION.RELEASE)
        put("sdk", Build.VERSION.SDK_INT)
    }

    private fun JsonObjectBuilder.network() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val airplane = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        put(
            "network",
            buildJsonObject {
                if (caps == null) {
                    put("connected", false)
                    put("type", "none")
                } else {
                    val transports = TRANSPORTS.filter { (id, _) -> caps.hasTransport(id) }.map { it.second }
                    put("connected", true)
                    put("type", DeviceLogic.primaryNetworkType(transports))
                    put("transports", buildJsonArray { transports.forEach { add(it) } })
                    put("validated", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                    put("metered", !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                    put("vpn", caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                }
                put("airplane_mode", airplane)
            },
        )
    }

    private fun JsonObjectBuilder.audio() {
        val control = AudioControl(context)
        control.ringerMode()?.let { put("ringer_mode", it.arg) }
        context.getSystemService(NotificationManager::class.java)?.let { nm ->
            put(
                "do_not_disturb",
                when (nm.currentInterruptionFilter) {
                    NotificationManager.INTERRUPTION_FILTER_ALL -> "off"
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority_only"
                    NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms_only"
                    NotificationManager.INTERRUPTION_FILTER_NONE -> "total_silence"
                    else -> "unknown"
                },
            )
        }
        put(
            "volumes",
            buildJsonObject {
                for (stream in VolumeStream.entries) runCatching { put(stream.arg, control.volume(stream).percent) }
            },
        )
    }

    private fun JsonObjectBuilder.time() {
        val zone = ZoneId.systemDefault()
        put("timezone", zone.id)
        put("local_time", CalendarTime.format(Instant.now(), zone))
    }

    private fun JsonObjectBuilder.storage() {
        val stat = StatFs(context.filesDir.path)
        put("storage_free_gb", DeviceLogic.bytesToGb(stat.availableBytes))
        put("storage_total_gb", DeviceLogic.bytesToGb(stat.totalBytes))
    }

    private fun JsonObjectBuilder.access() {
        // Implemented by other components; tolerate failures so status always renders.
        val accessibilityEnabled = runCatching { BridgeAccessibilityService.isEnabled(context) }.getOrNull()
        val accessibilityConnected = BridgeAccessibilityService.instance != null
        val foreground = if (accessibilityConnected) runCatching { BridgeAccessibilityService.foregroundPackage }.getOrNull() else null
        foreground?.let { pkg ->
            put("foreground_app", pkg)
            runCatching {
                val pm = context.packageManager
                pm.packageInfoOrNull(pkg)?.applicationInfo?.loadLabel(pm)?.toString()
            }.getOrNull()?.let { put("foreground_app_label", it) }
        }
        put("accessibility_enabled", accessibilityEnabled ?: accessibilityConnected)
        runCatching { BridgeNotificationListener.isEnabled(context) }.getOrNull()?.let { put("notification_access", it) }
        runCatching { AudioControl(context).hasDndAccess() }.getOrNull()?.let { put("dnd_access", it) }
    }

    private companion object {
        val TRANSPORTS: List<Pair<Int, String>> = listOf(
            NetworkCapabilities.TRANSPORT_WIFI to "wifi",
            NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
            NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
            NetworkCapabilities.TRANSPORT_BLUETOOTH to "bluetooth",
            NetworkCapabilities.TRANSPORT_VPN to "vpn",
            NetworkCapabilities.TRANSPORT_USB to "usb",
            NetworkCapabilities.TRANSPORT_WIFI_AWARE to "wifi_aware",
        )
    }
}
