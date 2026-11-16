package com.droidhands.android.tools.system

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Pure conversions used by `device_status`. */
object DeviceLogic {
    /**
     * Battery percent from BatteryManager.BATTERY_PROPERTY_CAPACITY, falling back to the sticky
     * ACTION_BATTERY_CHANGED level/scale. Null if neither is usable.
     */
    fun batteryPercent(capacityProperty: Int?, level: Int?, scale: Int?): Int? = when {
        capacityProperty != null && capacityProperty in 0..100 -> capacityProperty
        level != null && scale != null && level >= 0 && scale > 0 -> (level * 100.0 / scale).roundToInt().coerceIn(0, 100)
        else -> null
    }

    /** Bytes to decimal gigabytes (as Android's Settings shows storage), one decimal. */
    fun bytesToGb(bytes: Long): Double = (bytes / 1e8).roundToLong() / 10.0

    /** The transport an agent cares about most, from transport names such as "wifi", "cellular", "vpn". */
    fun primaryNetworkType(transports: Collection<String>): String =
        listOf("wifi", "ethernet", "cellular", "bluetooth", "usb", "satellite", "thread").firstOrNull { it in transports }
            ?: if ("vpn" in transports) "vpn" else if (transports.isEmpty()) "none" else transports.first()
}
