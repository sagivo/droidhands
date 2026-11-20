package com.droidhands.android.account

import com.droidhands.core.account.AccountApi
import com.droidhands.core.account.AccountResult
import com.droidhands.core.account.AccountSession
import com.droidhands.core.account.AccountUser
import com.droidhands.core.account.Pkce
import com.droidhands.core.account.RelayToken
import com.droidhands.core.transport.RelayAccountToken
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountManagerTest {
    private val ada = AccountUser("u1", "Ada", "ada@example.com")
    private var now = 1_000_000L

    private class MemoryStorage : AccountStorage {
        var session: AccountSession? = null
        var pending: PendingSignIn? = null
        override fun loadSession() = session
        override fun saveSession(session: AccountSession) { this.session = session }
        override fun clearSession() { session = null }
        override fun savePending(pending: PendingSignIn) { this.pending = pending }
        override fun takePending(): PendingSignIn? = pending.also { pending = null }
    }

    private inner class FakeApi : AccountApi {
        var challenge: String? = null
        var exchanged: Pair<String, String>? = null
        var exchangeResult: AccountResult<AccountSession> = AccountResult.Ok(AccountSession("s.sig", ada))
        var relayResult: AccountResult<RelayToken> = AccountResult.Ok(RelayToken("jwt", now + 600_000))
        var relayCalls = 0
        var meResult: AccountResult<AccountUser> = AccountResult.Ok(ada)
        var deleteResult: AccountResult<Unit> = AccountResult.Ok(Unit)
        var signedOut = 0

        override fun signInUrl(challenge: String): String {
            this.challenge = challenge
            return "https://api.example.com/app/sign-in?code_challenge=$challenge"
        }
        override suspend fun exchange(code: String, verifier: String): AccountResult<AccountSession> {
            exchanged = code to verifier
            return exchangeResult
        }
        override suspend fun me(sessionToken: String) = meResult
        override suspend fun relayToken(sessionToken: String): AccountResult<RelayToken> {
            relayCalls++
            return relayResult
        }
        override suspend fun signOut(sessionToken: String): AccountResult<Unit> {
            signedOut++
            return AccountResult.Failed("offline")
        }
        override suspend fun deleteAccount(sessionToken: String) = deleteResult
    }

    private val storage = MemoryStorage()
    private val api = FakeApi()
    private fun manager() = AccountManager(api, storage, clock = { now })

    private suspend fun signedIn(): AccountManager = manager().also {
        it.startSignIn(useRelay = true)
        it.completeSignIn("droidhands://signed-in?code=c0de")
    }

    @Test
    fun signInWithPkce() = runTest {
        val accounts = manager()
        assertTrue(accounts.startSignIn(useRelay = true)!!.startsWith("https://api.example.com/app/sign-in"))
        val verifier = storage.pending!!.verifier
        assertEquals(Pkce.challenge(verifier), api.challenge)

        assertEquals(SignInOutcome.SignedIn(useRelay = true), accounts.completeSignIn("droidhands://signed-in?code=c0de"))
        assertEquals("c0de" to verifier, api.exchanged)
        assertEquals(AccountSession("s.sig", ada), accounts.session.value)
        assertEquals(AccountSession("s.sig", ada), storage.session)
        assertEquals(SignInState.Idle, accounts.signIn.value)
        // The link works once.
        assertEquals(SignInOutcome.Ignored, accounts.completeSignIn("droidhands://signed-in?code=c0de"))
    }

    @Test
    fun linksNobodyAskedForAreIgnored() = runTest {
        val accounts = manager()
        assertEquals(SignInOutcome.Ignored, accounts.completeSignIn("droidhands://signed-in?code=planted"))
        assertNull(api.exchanged)
        // Too old.
        accounts.startSignIn(useRelay = false)
        now += AccountManager.PENDING_TTL_MS + 1
        assertEquals(SignInOutcome.Ignored, accounts.completeSignIn("droidhands://signed-in?code=late"))
        assertNull(accounts.session.value)
        // Not a sign-in link at all.
        accounts.startSignIn(useRelay = false)
        assertEquals(SignInOutcome.Ignored, accounts.completeSignIn("droidhands://configure?relay=https://x.example.com"))
    }

    @Test
    fun cancelledAndRefusedSignIns() = runTest {
        val accounts = manager()
        accounts.startSignIn(useRelay = true)
        assertEquals(
            SignInOutcome.Failed("Sign-in was cancelled."),
            accounts.completeSignIn("droidhands://signed-in?error=access_denied&error_description=User%20cancelled"),
        )
        assertEquals(SignInState.Failed("Sign-in was cancelled."), accounts.signIn.value)

        api.exchangeResult = AccountResult.Failed("expired", "invalid_grant")
        accounts.startSignIn(useRelay = true)
        assertEquals(SignInState.Idle, accounts.signIn.value)
        assertEquals(SignInOutcome.Failed("That sign-in expired. Try again."), accounts.completeSignIn("droidhands://signed-in?code=old"))
        assertNull(accounts.session.value)
    }

    @Test
    fun relayTokensAreCachedUntilNearExpiry() = runTest {
        val accounts = signedIn()
        assertEquals(RelayAccountToken.Token("jwt"), accounts.relayToken())
        assertEquals(RelayAccountToken.Token("jwt"), accounts.relayToken())
        assertEquals(1, api.relayCalls)
        now += 600_000 - AccountManager.TOKEN_MARGIN_MS
        api.relayResult = AccountResult.Ok(RelayToken("jwt2", now + 600_000))
        assertEquals(RelayAccountToken.Token("jwt2"), accounts.relayToken())
        assertEquals(2, api.relayCalls)
    }

    @Test
    fun aSessionTheServerEndedSignsOutHere() = runTest {
        val accounts = signedIn()
        api.relayResult = AccountResult.Failed("down")
        assertEquals(RelayAccountToken.Unavailable("down"), accounts.relayToken())
        assertTrue(accounts.session.value != null)

        api.relayResult = AccountResult.SignedOut
        assertEquals(RelayAccountToken.SignedOut, accounts.relayToken())
        assertNull(accounts.session.value)
        assertNull(storage.session)
        assertNull(accounts.sessionTag())
    }

    @Test
    fun refreshUpdatesTheNameAndDropsEndedSessions() = runTest {
        val accounts = signedIn()
        api.meResult = AccountResult.Ok(ada.copy(name = "Ada L."))
        accounts.refresh()
        assertEquals("Ada L.", accounts.session.value!!.user.name)
        assertEquals("Ada L.", storage.session!!.user.name)
        api.meResult = AccountResult.Failed("offline")
        accounts.refresh()
        assertTrue(accounts.session.value != null)
        api.meResult = AccountResult.SignedOut
        accounts.refresh()
        assertNull(accounts.session.value)
    }

    @Test
    fun signOutWorksOfflineAndDeleteNeedsTheServer() = runTest {
        val accounts = signedIn()
        val tag = accounts.sessionTag()!!
        assertTrue(!tag.contains("s.sig"))
        accounts.signOut()
        assertEquals(1, api.signedOut)
        assertNull(accounts.session.value)

        val again = signedIn()
        api.deleteResult = AccountResult.Failed("server error")
        assertEquals("server error", again.deleteAccount())
        assertTrue(again.session.value != null)
        api.deleteResult = AccountResult.Ok(Unit)
        assertNull(again.deleteAccount())
        assertNull(again.session.value)
    }

    @Test
    fun withoutAnAccountsServerNothingNeedsAnAccount() = runTest {
        storage.session = AccountSession("old", ada)
        val accounts = AccountManager(null, storage)
        assertTrue(!accounts.enabled)
        assertNull(accounts.session.value)
        assertNull(accounts.startSignIn(useRelay = true))
        assertEquals(RelayAccountToken.SignedOut, accounts.relayToken())
    }

    @Test
    fun signInLinks() {
        assertEquals(SignInLinks.Answer("a b", null, null), SignInLinks.parse("droidhands://signed-in?code=a%20b"))
        assertEquals(SignInLinks.Answer(null, "server_error", "Oops"), SignInLinks.parse("droidhands://signed-in?error=server_error&error_description=Oops"))
        assertNull(SignInLinks.parse("droidhands://signed-in"))
        assertNull(SignInLinks.parse("https://signed-in?code=x"))
        assertEquals("Couldn't sign in: Oops", SignInLinks.errorText("server_error", "Oops"))
        assertEquals(AccountManager.SIGN_IN_FAILED, SignInLinks.errorText(null, null))
    }
}
