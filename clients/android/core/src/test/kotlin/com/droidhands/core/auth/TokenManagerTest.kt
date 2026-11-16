package com.droidhands.core.auth

import com.droidhands.core.tools.Scopes
import com.droidhands.core.util.Encoding
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TokenManagerTest {
    private val deviceId = DeviceIdentity(ByteArray(32) { it.toByte() }).deviceId
    private var now = 1_700_000_000_000L

    /** Counts saves and can be told to fail. */
    private class RecordingStore(initial: List<TokenRecord> = emptyList()) : TokenStore {
        @Volatile var saved: List<TokenRecord> = initial
        @Volatile var saves = 0
        @Volatile var fail = false

        override fun load() = saved

        override fun save(tokens: List<TokenRecord>) {
            if (fail) throw IOException("disk full")
            saves++
            saved = tokens
        }
    }

    private fun manager(store: TokenStore = RecordingStore()) = TokenManager(store, deviceId, clock = { now })

    @Test
    fun issuedTokenHasProtocolFormatAndOnlyTheHashIsStored() {
        val store = RecordingStore()
        val tm = manager(store)
        val issued = tm.issue("  Claude laptop  ", setOf(Scopes.DEVICE), ConfirmPolicy.SENSITIVE, ttlMillis = null)

        assertTrue(TokenFormat.REGEX.matches(issued.plaintext), issued.plaintext)
        val parsed = assertNotNull(TokenFormat.parse(issued.plaintext))
        assertEquals(deviceId, parsed.deviceId)
        assertEquals(issued.record.id, parsed.tokenId)
        assertTrue(Regex("^[a-z2-7]{12}$").matches(issued.record.id))
        assertEquals("Claude laptop", issued.record.name)
        assertEquals(Encoding.hex(Encoding.sha256(Encoding.base64UrlDecode(parsed.secret))), issued.record.secretHash)
        assertFalse(store.saved.toString().contains(parsed.secret))
        assertEquals(listOf(issued.record), store.saved)
        assertEquals(now, issued.record.createdAt)
        assertNull(issued.record.expiresAt)
    }

    @Test
    fun authenticatesBearerAndBareTokens() {
        val tm = manager()
        val token = tm.issue("t", setOf(Scopes.DEVICE), ConfirmPolicy.NEVER, null).plaintext
        listOf(token, "Bearer $token", "bearer $token", "BEARER   $token  ", "  Bearer\t$token").forEach {
            val result = tm.authenticate(it)
            assertIs<AuthResult.Ok>(result, it)
            assertEquals(setOf(Scopes.DEVICE), result.token.scopes)
        }
    }

    @Test
    fun rejectsMissingAndMalformed() {
        val tm = manager()
        val token = tm.issue("t", emptySet(), ConfirmPolicy.NEVER, null).plaintext
        listOf(null, "", "   ", "Bearer", "Bearer ", "Basic $token", "Bearer x$token", "$token.", token.replace("pb1.", "pb2.")).forEach {
            assertIs<AuthResult.Denied>(tm.authenticate(it), "input: $it")
        }
    }

    @Test
    fun rejectsTokenForAnotherDevice() {
        val tm = manager()
        val token = tm.issue("t", emptySet(), ConfirmPolicy.NEVER, null).plaintext
        val other = DeviceIdentity.generate().deviceId
        val denied = assertIs<AuthResult.Denied>(tm.authenticate(token.replace(deviceId, other)))
        assertTrue(denied.reason.contains("another device"))
    }

    @Test
    fun rejectsUnknownIdAndTamperedSecret() {
        val tm = manager()
        val token = tm.issue("t", emptySet(), ConfirmPolicy.NEVER, null).plaintext
        val parsed = TokenFormat.parse(token)!!
        val unknownId = TokenFormat.format(deviceId, "aaaaaaaaaaaa", parsed.secret)
        assertIs<AuthResult.Denied>(tm.authenticate(unknownId))

        // Flip the first secret char: still syntactically valid, wrong hash.
        val first = parsed.secret[0]
        val tampered = TokenFormat.format(deviceId, parsed.tokenId, (if (first == 'A') 'B' else 'A') + parsed.secret.drop(1))
        val denied = assertIs<AuthResult.Denied>(tm.authenticate(tampered))
        assertTrue(denied.reason.contains("wrong secret"))

        // A non-canonical last character decodes to the same bytes but is rejected.
        val last = parsed.secret.last()
        val idx = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".indexOf(last)
        val nonCanonical = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"[idx or 1]
        val nc = TokenFormat.format(deviceId, parsed.tokenId, parsed.secret.dropLast(1) + nonCanonical)
        assertIs<AuthResult.Denied>(tm.authenticate(nc))
    }

    @Test
    fun revokedTokensAreRejectedButKept() {
        val store = RecordingStore()
        val tm = manager(store)
        val issued = tm.issue("t", emptySet(), ConfirmPolicy.NEVER, null)
        tm.revoke(issued.record.id)
        val denied = assertIs<AuthResult.Denied>(tm.authenticate(issued.plaintext))
        assertTrue(denied.reason.contains("revoked"))
        assertTrue(tm.tokens.value.single().revoked)
        assertTrue(store.saved.single().revoked)
        val saves = store.saves
        tm.revoke(issued.record.id) // idempotent
        tm.revoke("unknown")
        assertEquals(saves, store.saves)
    }

    @Test
    fun expiredTokensAreRejected() {
        val tm = manager()
        val issued = tm.issue("t", emptySet(), ConfirmPolicy.NEVER, ttlMillis = 10_000)
        assertEquals(now + 10_000, issued.record.expiresAt)
        now += 9_999
        assertIs<AuthResult.Ok>(tm.authenticate(issued.plaintext))
        now += 1
        val denied = assertIs<AuthResult.Denied>(tm.authenticate(issued.plaintext))
        assertTrue(denied.reason.contains("expired"))
    }

    @Test
    fun deleteRemovesToken() {
        val store = RecordingStore()
        val tm = manager(store)
        val a = tm.issue("a", emptySet(), ConfirmPolicy.NEVER, null)
        val b = tm.issue("b", emptySet(), ConfirmPolicy.NEVER, null)
        tm.delete(a.record.id)
        assertEquals(listOf(b.record.id), tm.tokens.value.map { it.id })
        assertEquals(listOf(b.record.id), store.saved.map { it.id })
        assertIs<AuthResult.Denied>(tm.authenticate(a.plaintext))
        tm.delete("nope")
    }

    @Test
    fun tokensAreNewestFirst() {
        val tm = manager()
        val names = listOf("one", "two", "three").map {
            now += 1000
            tm.issue(it, emptySet(), ConfirmPolicy.NEVER, null).record.name
        }
        assertEquals(names.reversed(), tm.tokens.value.map { it.name })
        // Reloading from the store keeps the order and survives duplicates.
        val records = tm.tokens.value
        val reloaded = TokenManager(InMemoryTokenStore(records.reversed() + records.first()), deviceId, clock = { now })
        assertEquals(names.reversed(), reloaded.tokens.value.map { it.name })
    }

    @Test
    fun lastUsedIsPersistedAtMostOncePerMinute() {
        val store = RecordingStore()
        val tm = manager(store)
        val issued = tm.issue("t", emptySet(), ConfirmPolicy.NEVER, null)
        assertEquals(1, store.saves)

        tm.authenticate(issued.plaintext)
        assertEquals(2, store.saves)
        assertEquals(now, store.saved.single().lastUsedAt)
        assertEquals(now, tm.tokens.value.single().lastUsedAt)

        repeat(10) {
            now += 5_000
            tm.authenticate(issued.plaintext)
        }
        assertEquals(2, store.saves, "no save within a minute")
        now += 10_000 // 60 s after the first use
        tm.authenticate(issued.plaintext)
        assertEquals(3, store.saves)
        assertEquals(now, store.saved.single().lastUsedAt)
    }

    @Test
    fun lastUsedSaveFailureDoesNotFailAuthentication() {
        val store = RecordingStore()
        val tm = manager(store)
        val issued = tm.issue("t", emptySet(), ConfirmPolicy.NEVER, null)
        store.fail = true
        assertIs<AuthResult.Ok>(tm.authenticate(issued.plaintext))
    }

    @Test
    fun storeFailurePropagatesAndLeavesStateUnchanged() {
        val store = RecordingStore()
        val tm = manager(store)
        val issued = tm.issue("t", emptySet(), ConfirmPolicy.NEVER, null)
        store.fail = true
        assertFailsWith<IOException> { tm.issue("u", emptySet(), ConfirmPolicy.NEVER, null) }
        assertEquals(1, tm.tokens.value.size)
        assertFailsWith<IOException> { tm.revoke(issued.record.id) }
        assertFalse(tm.tokens.value.single().revoked)
        assertIs<AuthResult.Ok>(tm.authenticate(issued.plaintext), "revoke did not happen")
        assertFailsWith<IOException> { tm.delete(issued.record.id) }
        assertEquals(1, tm.tokens.value.size)
    }

    @Test
    fun validatesArguments() {
        val tm = manager()
        assertFailsWith<IllegalArgumentException> { tm.issue("  ", emptySet(), ConfirmPolicy.NEVER, null) }
        assertFailsWith<IllegalArgumentException> { tm.issue("x", emptySet(), ConfirmPolicy.NEVER, 0) }
        assertFailsWith<IllegalArgumentException> { TokenManager(InMemoryTokenStore(), "not-a-device-id") }
    }

    @Test
    fun concurrentIssueAndAuthenticateAreSafe() {
        val store = RecordingStore()
        val tm = TokenManager(store, deviceId)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val issued = java.util.concurrent.ConcurrentLinkedQueue<IssuedToken>()
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        repeat(40) { i ->
            pool.execute {
                try {
                    start.await()
                    val t = tm.issue("t$i", emptySet(), ConfirmPolicy.NEVER, null)
                    issued += t
                    repeat(5) { assertIs<AuthResult.Ok>(tm.authenticate(t.plaintext)) }
                } catch (e: Throwable) {
                    errors += e
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertTrue(errors.isEmpty(), errors.joinToString())
        assertEquals(40, tm.tokens.value.size)
        assertEquals(40, tm.tokens.value.map { it.id }.toSet().size)
        assertEquals(tm.tokens.value.map { it.id }.toSet(), store.saved.map { it.id }.toSet())
        issued.forEach { assertIs<AuthResult.Ok>(tm.authenticate(it.plaintext)) }
    }
}
