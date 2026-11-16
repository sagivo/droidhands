package com.droidhands.android.ui.apps

import com.droidhands.android.ui.apps.AppTestData.listing
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppDirectoryTest {
    private var now = 1_000_000L
    private var loads = 0
    private var fail = false

    private fun TestScope.directory() = AppDirectory(
        load = {
            loads++
            if (fail) throw IllegalStateException("PackageManager died")
            listOf(listing("com.example.a", "App $loads"))
        },
        scope = this,
        clock = { now },
    )

    @Test
    fun loadsOnceWhileFreshAndAgainWhenForcedOrStale() = runTest {
        val directory = directory()
        assertNull(directory.apps.value)
        directory.reload()
        assertEquals("App 1", directory.apps.value!!.single().label)
        directory.reload()
        assertEquals(1, loads)
        directory.reload(force = true)
        assertEquals(2, loads)
        now += AppDirectory.MAX_AGE_MS
        directory.refresh()
        advanceUntilIdle()
        assertEquals(3, loads)
        assertEquals("App 3", directory.apps.value!!.single().label)
        assertFalse(directory.loading.value)
        // After the plugins change, the next refresh reloads at once.
        directory.invalidate()
        assertEquals(3, loads)
        directory.reload()
        assertEquals(4, loads)
    }

    @Test
    fun aFailedLoadKeepsThePreviousList() = runTest {
        val directory = directory()
        fail = true
        directory.reload()
        assertEquals(emptyList<AppListing>(), directory.apps.value)
        fail = false
        directory.reload(force = true)
        assertEquals("App 2", directory.apps.value!!.single().label)
        fail = true
        directory.reload(force = true)
        assertEquals("App 2", directory.apps.value!!.single().label)
        assertFalse(directory.loading.value)
    }
}
