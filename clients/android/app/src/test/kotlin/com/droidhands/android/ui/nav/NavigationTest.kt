package com.droidhands.android.ui.nav

import com.droidhands.android.ui.Destination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class NavigationTest {
    @Test
    fun pushBackAndSelect() {
        val home = NavState()
        val connect = home.push(Screen.ConnectAgent)
        assertEquals(Screen.ConnectAgent, connect.top)
        assertSame(connect, connect.push(Screen.ConnectAgent))
        val detail = connect.push(Screen.AgentDetail("abc"))
        assertEquals(listOf(Screen.ConnectAgent, Screen.AgentDetail("abc")), detail.stack)
        assertEquals(connect, detail.back())
        assertEquals(home, connect.back())
        assertNull(home.back())
        assertEquals(NavState(Destination.HOME), NavState(Destination.SETTINGS).back())
        assertEquals(NavState(Destination.AGENTS), detail.select(Destination.AGENTS))
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val state = NavState(Destination.AGENTS, listOf(Screen.AgentDetail("abcdefghijkl"), Screen.AgentApps("abcdefghijkl")))
        assertEquals(state, NavState.decode(state.encode()))
        val pages = NavState(Destination.SETTINGS, listOf(Screen.Permissions, Screen.NeverAllowApps, Screen.ConnectAgent))
        assertEquals(pages, NavState.decode(pages.encode()))
        val app = NavState(Destination.APPS, listOf(Screen.AppDetail("com.spotify.music")))
        assertEquals(listOf("apps", "app/com.spotify.music"), app.encode())
        assertEquals(app, NavState.decode(app.encode()))
    }

    @Test
    fun decodeIsLenient() {
        assertEquals(NavState(), NavState.decode(null))
        assertEquals(NavState(), NavState.decode(emptyList()))
        assertEquals(NavState(Destination.HOME, listOf(Screen.ConnectAgent)), NavState.decode(listOf("nope", "agent/", "connect", "app/", "??")))
        // Wave-1 tab names still work.
        assertEquals(Destination.AGENTS, NavState.decode(listOf("tokens")).tab)
    }

    @Test
    fun pruneDropsDeletedAgents() {
        val state = NavState(Destination.AGENTS, listOf(Screen.AgentDetail("a"), Screen.AgentApps("a"), Screen.Permissions))
        val pruned = state.prune { it !is Screen.AgentDetail && it !is Screen.AgentApps }
        assertEquals(listOf(Screen.Permissions), pruned.stack)
        assertSame(state, state.prune { true })
    }

    @Test
    fun externalRoutes() {
        assertEquals(NavState(Destination.SETTINGS, listOf(Screen.Permissions)), Destination.navStateFor("setup"))
        assertEquals(NavState(Destination.SETTINGS, listOf(Screen.Permissions)), Destination.navStateFor("permissions"))
        assertEquals(NavState(Destination.HOME, listOf(Screen.ConnectAgent)), Destination.navStateFor("connect"))
        assertEquals(NavState(Destination.AGENTS), Destination.navStateFor("Tokens"))
        assertEquals(NavState(Destination.ACTIVITY), Destination.navStateFor("activity"))
        assertEquals(NavState(Destination.APPS), Destination.navStateFor("apps"))
        assertNull(Destination.navStateFor("bogus"))
        assertNull(Destination.navStateFor(null))
    }
}
