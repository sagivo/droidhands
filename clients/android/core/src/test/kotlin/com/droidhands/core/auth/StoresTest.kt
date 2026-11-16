package com.droidhands.core.auth

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoresTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val sample = listOf(
        TokenRecord(
            id = "abcdefghijkl", name = "Claude", secretHash = "00".repeat(32), scopes = setOf("device", "apps"),
            confirmPolicy = ConfirmPolicy.ALL_ACTIONS, createdAt = 5, expiresAt = 99, lastUsedAt = 7, revoked = true,
        ),
        TokenRecord(id = "mnopqrstuvwx", name = "Other", secretHash = "11".repeat(32), scopes = emptySet(), createdAt = 3),
    )

    @Test
    fun inMemoryStoreRoundTripsAndCopies() {
        val initial = sample.toMutableList()
        val store = InMemoryTokenStore(initial)
        initial.clear()
        assertEquals(sample, store.load())
        val next = sample.take(1).toMutableList()
        store.save(next)
        next.clear()
        assertEquals(sample.take(1), store.load())
    }

    @Test
    fun fileStoreRoundTrips() {
        val file = File(tmp.root, "nested/dir/tokens.json")
        val store = FileTokenStore(file)
        assertEquals(emptyList(), store.load(), "missing file")
        store.save(sample)
        assertTrue(file.exists())
        assertEquals(sample, FileTokenStore(file).load())
        store.save(emptyList())
        assertEquals(emptyList(), FileTokenStore(file).load())
    }

    @Test
    fun toolRulesForAppActionsRoundTrip() {
        // App actions are ruled by their virtual name, <package>/<action id> (docs/PLUGINS.md §9).
        val rules = mapOf("com.spotify.music/play" to ToolRule.BLOCK, "com.whatsapp/send_message" to ToolRule.ASK, "sms_send" to ToolRule.ALLOW)
        val file = File(tmp.root, "tokens.json")
        FileTokenStore(file).save(listOf(sample[1].copy(toolRules = rules)))
        assertEquals(rules, FileTokenStore(file).load().single().toolRules)

        val manager = TokenManager(FileTokenStore(file), DeviceIdentity(ByteArray(32) { it.toByte() }).deviceId)
        val id = manager.tokens.value.single().id
        val updated = manager.update(id) { it.copy(toolRules = it.toolRules + ("com.google.android.apps.maps/navigate" to ToolRule.BLOCK)) }
        assertEquals(ToolRule.BLOCK, updated.toolRules["com.google.android.apps.maps/navigate"])
        assertEquals(updated.toolRules, FileTokenStore(file).load().single().toolRules)
    }

    @Test
    fun saveLeavesNoTempFiles() {
        val file = File(tmp.root, "tokens.json")
        val store = FileTokenStore(file)
        repeat(5) { store.save(sample) }
        assertEquals(listOf("tokens.json"), tmp.root.list()!!.toList())
    }

    @Test
    fun corruptFileLoadsEmptyAndKeepsBackup() {
        val file = File(tmp.root, "tokens.json")
        file.writeText("{\"version\":1,\"tokens\":[{\"id\":")
        val store = FileTokenStore(file)
        assertEquals(emptyList(), store.load())
        val backup = File(tmp.root, "tokens.json.corrupt")
        assertTrue(backup.exists())
        assertEquals("{\"version\":1,\"tokens\":[{\"id\":", backup.readText())
        // The store keeps working.
        store.save(sample)
        assertEquals(sample, store.load())
    }

    @Test
    fun wrongShapeIsTreatedAsCorrupt() {
        val file = File(tmp.root, "tokens.json")
        file.writeText("""{"version":1,"tokens":[{"id":"x"}]}""")
        assertEquals(emptyList(), FileTokenStore(file).load())
        assertTrue(File(tmp.root, "tokens.json.corrupt").exists())
    }

    @Test
    fun blankFileIsEmpty() {
        val file = File(tmp.root, "tokens.json")
        file.writeText("  \n")
        assertEquals(emptyList(), FileTokenStore(file).load())
        assertFalse(File(tmp.root, "tokens.json.corrupt").exists())
    }

    @Test
    fun acceptsBareArrayAndUnknownFields() {
        val file = File(tmp.root, "tokens.json")
        file.writeText(
            """[{"id":"abcdefghijkl","name":"n","secretHash":"h","scopes":["device"],"createdAt":1,"future":true}]""",
        )
        val loaded = FileTokenStore(file).load()
        assertEquals("abcdefghijkl", loaded.single().id)
        assertEquals(ConfirmPolicy.SENSITIVE, loaded.single().confirmPolicy)
    }

    @Test
    fun failedSaveThrowsAndKeepsOldContent() {
        val file = File(tmp.root, "tokens.json")
        val store = FileTokenStore(file)
        store.save(sample)
        val before = file.readText()
        // Make the target a non-empty directory so the final rename fails.
        val dirTarget = File(tmp.root, "blocked.json").apply { mkdirs(); File(this, "child").writeText("x") }
        assertFailsWith<IOException> { FileTokenStore(dirTarget).save(sample) }
        assertEquals(before, file.readText())
        assertEquals(setOf("tokens.json", "blocked.json"), tmp.root.list()!!.toSet(), "temp file cleaned up")
    }
}
