package com.droidhands.android.tools.system

import com.droidhands.android.tools.system.LocationLogic.Accuracy
import com.droidhands.android.tools.system.LocationLogic.FUSED
import com.droidhands.android.tools.system.LocationLogic.GPS
import com.droidhands.android.tools.system.LocationLogic.NETWORK
import com.droidhands.android.tools.system.LocationLogic.PASSIVE
import com.droidhands.core.tools.ToolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class LocationLogicTest {
    private val all = setOf(FUSED, GPS, NETWORK, PASSIVE)

    @Test
    fun `fused first on API 31+, then accuracy preference`() {
        assertEquals(listOf(FUSED, GPS, NETWORK), LocationLogic.providerOrder(Accuracy.HIGH, 34, all, hasFine = true))
        assertEquals(listOf(FUSED, NETWORK, GPS), LocationLogic.providerOrder(Accuracy.BALANCED, 34, all, hasFine = true))
    }

    @Test
    fun `API 30 has no fused provider and gps needs fine location`() {
        assertEquals(listOf(GPS, NETWORK), LocationLogic.providerOrder(Accuracy.HIGH, 30, all, hasFine = true))
        assertEquals(listOf(NETWORK), LocationLogic.providerOrder(Accuracy.HIGH, 30, all, hasFine = false))
        assertEquals(listOf(FUSED, NETWORK, GPS), LocationLogic.providerOrder(Accuracy.BALANCED, 31, all, hasFine = false))
    }

    @Test
    fun `disabled providers are skipped`() {
        assertEquals(listOf(GPS), LocationLogic.providerOrder(Accuracy.BALANCED, 34, setOf(GPS, PASSIVE), hasFine = true))
        assertEquals(emptyList<String>(), LocationLogic.providerOrder(Accuracy.BALANCED, 34, setOf(PASSIVE), hasFine = true))
    }

    @Test
    fun `attempt budget leaves time for later providers`() {
        assertEquals(12_000L, LocationLogic.attemptTimeoutMs(18_000, 3))
        assertEquals(18_000L, LocationLogic.attemptTimeoutMs(18_000, 1))
        assertEquals(0L, LocationLogic.attemptTimeoutMs(-5, 1))
    }

    @Test
    fun `timeout and accuracy arguments`() {
        assertEquals(20, LocationLogic.timeoutSeconds(null))
        assertEquals(60, LocationLogic.timeoutSeconds(600))
        assertEquals(1, LocationLogic.timeoutSeconds(0))
        assertEquals(Accuracy.BALANCED, Accuracy.parse(null))
        assertEquals(Accuracy.HIGH, Accuracy.parse("High"))
        assertEquals(Accuracy.HIGH, Accuracy.parse("precise"))
        assertThrows(ToolException::class.java) { Accuracy.parse("ultra") }
    }

    private data class Fix(val name: String, val nanos: Long, val acc: Float?)

    @Test
    fun `freshest fix wins, accuracy breaks ties`() {
        val fixes = listOf(Fix("old", 1, 5f), Fix("new-coarse", 9, 500f), Fix("new-fine", 9, 10f), Fix("mid", 5, null))
        assertEquals("new-fine", LocationLogic.freshest(fixes, { it.nanos }, { it.acc })?.name)
        assertNull(LocationLogic.freshest(emptyList<Fix>(), { it.nanos }, { it.acc }))
    }

    @Test
    fun `address formatting`() {
        assertEquals("1600 Amphitheatre Pkwy, Mountain View, CA 94043, USA", LocationLogic.formatAddress(listOf("1600 Amphitheatre Pkwy, Mountain View, CA 94043, USA")))
        assertEquals("Line 1, Line 2", LocationLogic.formatAddress(listOf(" Line 1 ", null, "", "Line 2")))
        assertEquals(
            "12 Herzl St, Tel Aviv, Tel Aviv District 61000, Israel",
            LocationLogic.formatAddress(emptyList(), "Herzl St", "12", "Tel Aviv", "Tel Aviv District", "61000", "Israel"),
        )
        assertEquals("Paris, France", LocationLogic.formatAddress(emptyList(), locality = "Paris", country = "France"))
        assertNull(LocationLogic.formatAddress(emptyList()))
    }
}

class DeviceLogicTest {
    @Test
    fun `battery percent prefers the capacity property`() {
        assertEquals(87, DeviceLogic.batteryPercent(87, 50, 100))
        assertEquals(50, DeviceLogic.batteryPercent(Int.MIN_VALUE, 50, 100))
        assertEquals(67, DeviceLogic.batteryPercent(null, 2, 3))
        assertNull(DeviceLogic.batteryPercent(null, -1, -1))
        assertNull(DeviceLogic.batteryPercent(null, null, null))
    }

    @Test
    fun `storage in decimal gigabytes`() {
        assertEquals(12.3, DeviceLogic.bytesToGb(12_345_678_901), 0.0)
        assertEquals(128.0, DeviceLogic.bytesToGb(128_000_000_000), 0.0)
        assertEquals(0.0, DeviceLogic.bytesToGb(0), 0.0)
    }

    @Test
    fun `primary network type`() {
        assertEquals("wifi", DeviceLogic.primaryNetworkType(listOf("vpn", "wifi")))
        assertEquals("cellular", DeviceLogic.primaryNetworkType(listOf("cellular")))
        assertEquals("vpn", DeviceLogic.primaryNetworkType(listOf("vpn")))
        assertEquals("none", DeviceLogic.primaryNetworkType(emptyList()))
    }
}

class TextAndJsonTest {
    @Test
    fun `truncate never splits surrogate pairs`() {
        assertEquals("abc", truncate("abc", 3))
        assertEquals("ab…", truncate("abcd", 3))
        val emoji = "a😀bc" // a, U+1F600, b, c
        assertEquals("a…", truncate(emoji, 3))
    }

    @Test
    fun `summary quoting collapses whitespace`() {
        assertEquals("\"hello world\"", quoteForSummary("  hello \n  world "))
        assertEquals("\"abcd…\"", quoteForSummary("abcdefgh", 5))
    }

    @Test
    fun `package names and enum args`() {
        assertEquals(true, isValidPackageName("com.whatsapp.w4b"))
        assertEquals(false, isValidPackageName("whatsapp"))
        assertEquals(false, isValidPackageName("com..x"))
        assertEquals(false, isValidPackageName("com.1x"))
        assertEquals("play_pause", normalizeEnumArg(" Play - Pause "))
        assertEquals("fallback", describeOr("fallback") { throw ToolException("bad") })
    }
}
