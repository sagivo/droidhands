package com.droidhands.core.auth

import com.droidhands.core.tools.Risk
import com.droidhands.core.tools.Scopes
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Wave-2 token settings: issue metadata, update(), revokeAll(). */
class TokenManagerSettingsTest {
    private val deviceId = DeviceIdentity(ByteArray(32) { it.toByte() }).deviceId
    private var now = 1_700_000_000_000L

    private class CountingStore : TokenStore {
        @Volatile var saved: List<TokenRecord> = emptyList()
        @Volatile var saves = 0
        @Volatile var fail = false

        override fun load() = saved

        override fun save(tokens: List<TokenRecord>) {
            if (fail) throw IOException("disk full")
            saves++
            saved = tokens
        }
    }

    private val store = CountingStore()
    private val tm = TokenManager(store, deviceId, clock = { now })

    private fun issue(name: String = "Claude") = tm.issue(name, setOf(Scopes.DEVICE), ConfirmPolicy.SENSITIVE, null)

    @Test
    fun issuePersistsOriginClientHostAndAppPolicy() {
        val policy = AppPolicy(AppAccessMode.ALLOWLIST, setOf("com.whatsapp"))
        val issued = tm.issue("Claude", Scopes.PRESET_ASSISTANT, ConfirmPolicy.SENSITIVE, null, "pairing", " claude.ai ", policy)
        val stored = store.saved.single()
        assertEquals("pairing", stored.origin)
        assertEquals("claude.ai", stored.clientHost)
        assertEquals(policy, stored.appPolicy)
        assertEquals(issued.record, stored)

        val manual = issue("Manual")
        assertEquals("manual", manual.record.origin)
        assertNull(manual.record.clientHost)
        assertEquals(AppPolicy(), manual.record.appPolicy)
        assertNull(tm.issue("Blank host", emptySet(), ConfirmPolicy.NEVER, null, clientHost = "  ").record.clientHost)
    }

    @Test
    fun maxRiskIsIssuedPersistedAndEditable() {
        assertNull(issue().record.maxRisk, "no cap by default")
        val readOnly = tm.issue("Reader", Scopes.PRESET_READ_ONLY, ConfirmPolicy.SENSITIVE, null, maxRisk = Risk.READ)
        assertEquals(Risk.READ, readOnly.record.maxRisk)
        assertEquals(Risk.READ, store.saved.first { it.id == readOnly.record.id }.maxRisk)

        assertEquals(Risk.ACTION, tm.update(readOnly.record.id) { it.copy(maxRisk = Risk.ACTION) }.maxRisk)
        assertNull(tm.update(readOnly.record.id) { it.copy(maxRisk = null) }.maxRisk)
        assertNull(store.saved.first { it.id == readOnly.record.id }.maxRisk)
    }

    @Test
    fun presetsDeclareTheirRiskCap() {
        assertEquals(Scopes.PRESETS.keys, Scopes.PRESET_MAX_RISK.keys)
        assertEquals(Risk.READ, Scopes.PRESET_MAX_RISK["Read-only"])
        assertNull(Scopes.PRESET_MAX_RISK["Assistant"])
        assertNull(Scopes.PRESET_MAX_RISK["Full control"])
    }

    @Test
    fun issueValidatesScopesAndOrigin() {
        assertFailsWith<IllegalArgumentException> { tm.issue("x", setOf("device", "root"), ConfirmPolicy.NEVER, null) }
        assertFailsWith<IllegalArgumentException> { tm.issue("x", emptySet(), ConfirmPolicy.NEVER, null, origin = " ") }
        assertEquals(0, store.saves)
    }

    @Test
    fun updateChangesMutableFieldsAndPersists() {
        val issued = issue()
        now += 5_000
        val hours = ActiveHours(8 * 60, 23 * 60)
        val updated = tm.update(issued.record.id) {
            it.copy(
                name = "  Claude work  ",
                scopes = setOf(Scopes.DEVICE, Scopes.MESSAGES_SEND),
                confirmPolicy = ConfirmPolicy.ALL_ACTIONS,
                expiresAt = now + 60_000,
                appPolicy = AppPolicy(AppAccessMode.BLOCKLIST, setOf("com.bank")),
                toolRules = mapOf("sms_send" to ToolRule.BLOCK, "device_status" to ToolRule.ALLOW),
                activeHours = hours,
            )
        }
        assertEquals("Claude work", updated.name)
        assertEquals(setOf(Scopes.DEVICE, Scopes.MESSAGES_SEND), updated.scopes)
        assertEquals(ConfirmPolicy.ALL_ACTIONS, updated.confirmPolicy)
        assertEquals(now + 60_000, updated.expiresAt)
        assertEquals(AppPolicy(AppAccessMode.BLOCKLIST, setOf("com.bank")), updated.appPolicy)
        assertEquals(ToolRule.BLOCK, updated.toolRules["sms_send"])
        assertEquals(hours, updated.activeHours)
        assertEquals(listOf(updated), tm.tokens.value)
        assertEquals(listOf(updated), store.saved)

        // The token keeps working with the new settings.
        val auth = assertIs<AuthResult.Ok>(tm.authenticate(issued.plaintext))
        assertEquals(updated.scopes, auth.token.scopes)
        assertEquals(hours, auth.token.activeHours)

        // Back to "always".
        assertNull(tm.update(issued.record.id) { it.copy(activeHours = null) }.activeHours)
    }

    @Test
    fun updateIgnoresImmutableFields() {
        val issued = tm.issue("Claude", emptySet(), ConfirmPolicy.NEVER, null, origin = "pairing", clientHost = "claude.ai")
        tm.authenticate(issued.plaintext)
        val before = tm.tokens.value.single()
        val updated = tm.update(before.id) {
            it.copy(
                id = "aaaaaaaaaaaa", secretHash = "00", createdAt = 1, origin = "manual", lastUsedAt = 2, revoked = true,
                clientHost = "evil.example", name = "Renamed",
            )
        }
        assertEquals(before.copy(name = "Renamed"), updated)
        assertIs<AuthResult.Ok>(tm.authenticate(issued.plaintext), "secret, id and revocation untouched")
    }

    @Test
    fun updateValidates() {
        val id = issue().record.id
        val invalid: List<(TokenRecord) -> TokenRecord> = listOf(
            { it.copy(name = "   ") },
            { it.copy(scopes = setOf("device", "superuser")) },
            { it.copy(activeHours = ActiveHours(600, 600)) },
            { it.copy(activeHours = ActiveHours(-1, 600)) },
            { it.copy(activeHours = ActiveHours(0, 1440)) },
        )
        val saves = store.saves
        val before = tm.tokens.value
        invalid.forEach { transform -> assertFailsWith<IllegalArgumentException> { tm.update(id, transform) } }
        assertEquals(before, tm.tokens.value)
        assertEquals(saves, store.saves)
        // Edge values are fine, including overnight windows.
        tm.update(id) { it.copy(activeHours = ActiveHours(22 * 60, 6 * 60)) }
        tm.update(id) { it.copy(activeHours = ActiveHours(0, 1439)) }
    }

    @Test
    fun updateUnknownIdThrows() {
        assertFailsWith<NoSuchElementException> { tm.update("nope") { it } }
    }

    @Test
    fun updateWithoutChangesDoesNotSave() {
        val record = issue().record
        val saves = store.saves
        assertSame(record, tm.update(record.id) { it.copy(name = "  ${it.name} ") })
        assertEquals(saves, store.saves)
    }

    @Test
    fun updateStoreFailureLeavesStateUnchanged() {
        val record = issue().record
        store.fail = true
        assertFailsWith<IOException> { tm.update(record.id) { it.copy(name = "New") } }
        assertEquals("Claude", tm.tokens.value.single().name)
    }

    @Test
    fun revokeAllRevokesEveryActiveToken() {
        val a = issue("a")
        val b = issue("b")
        val c = issue("c")
        tm.revoke(c.record.id)
        assertEquals(2, tm.revokeAll())
        assertTrue(tm.tokens.value.all { it.revoked })
        assertTrue(store.saved.all { it.revoked })
        listOf(a, b, c).forEach { assertIs<AuthResult.Denied>(tm.authenticate(it.plaintext)) }

        val saves = store.saves
        assertEquals(0, tm.revokeAll())
        assertEquals(saves, store.saves, "nothing to revoke, nothing saved")
    }

    @Test
    fun revokeAllStoreFailureRevokesNothing() {
        val a = issue("a")
        store.fail = true
        assertFailsWith<IOException> { tm.revokeAll() }
        assertIs<AuthResult.Ok>(tm.authenticate(a.plaintext))
    }
}
