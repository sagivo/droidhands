package com.droidhands.core.plugins

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActionHealthTest {
    private val v1 = InstalledApp("com.app", "App", "1.0", versionCode = 10)
    private val v2 = v1.copy(versionName = "2.0", versionCode = 20)

    private class MemoryStore : ActionHealthStore {
        var text: String? = null

        override fun load() = text

        override fun save(json: String) {
            text = json
        }
    }

    @Test
    fun `failures count up per app version and a success clears them`() {
        val health = ActionHealth(clock = { 5 })
        assertFalse(health.failed(v1, "play", "no screen").failing)
        assertTrue(health.failed(v1, "play", "no screen").failing)
        assertNull(health.status(v2, "play"), "a new app version starts clean")
        health.succeeded(v1, "play")
        assertNull(health.status(v1, "play"))
    }

    @Test
    fun `records survive a restart`() {
        val store = MemoryStore()
        ActionHealth(store).failed(v1, "play", "no screen")
        val record = ActionHealth(store).status(v1, "play")!!
        assertEquals(1, record.failures)
        assertEquals("no screen", record.lastError)
    }

    @Test
    fun `an unreadable store is ignored`() {
        val store = MemoryStore().apply { text = "not json" }
        assertNull(ActionHealth(store).status(v1, "play"))
    }

    @Test
    fun `old records are dropped beyond the limit`() {
        val health = ActionHealth()
        repeat(ActionHealth.MAX_RECORDS + 5) { health.failed(v1, "a$it", "x") }
        assertNull(health.status(v1, "a0"))
        assertEquals(1, health.status(v1, "a${ActionHealth.MAX_RECORDS + 4}")!!.failures)
    }
}

class RecipeProbeTest {
    @Test
    fun `uses the first valid example`() {
        val action = PluginAction(
            "chat", "Chat", "d", risk = com.droidhands.core.tools.Risk.ACTION,
            params = listOf(ParamSpec("phone", "p", required = true, pattern = "^[0-9]+$")),
            examples = listOf(
                PluginExample(buildJsonObject { put("phone", "+1") }, ExampleExpectation.Error("phone")),
                PluginExample(buildJsonObject { put("phone", "15551234567") }, ExampleExpectation.Url("x")),
            ),
        )
        assertEquals(buildJsonObject { put("phone", "15551234567") }, RecipeProbe.sampleValues(action))
    }

    @Test
    fun `falls back to defaults, enums and typed placeholders`() {
        val action = PluginAction(
            "a", "A", "d",
            params = listOf(
                ParamSpec("mode", "m", enum = listOf(JsonPrimitive("walk"), JsonPrimitive("drive"))),
                ParamSpec("n", "n", type = ParamType.INTEGER, minimum = 3.0),
                ParamSpec("on", "o", type = ParamType.BOOLEAN),
                ParamSpec("q", "q", default = JsonPrimitive("coffee")),
                ParamSpec("s", "s", minLength = 3),
            ),
        )
        val values = RecipeProbe.sampleValues(action)
        assertEquals(JsonPrimitive("walk"), values["mode"])
        assertEquals(JsonPrimitive(3L), values["n"])
        assertEquals(JsonPrimitive(true), values["on"])
        assertEquals(JsonPrimitive("coffee"), values["q"])
        assertEquals(JsonPrimitive("xxx"), values["s"])
    }
}
