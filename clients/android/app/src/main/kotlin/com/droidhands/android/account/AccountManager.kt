package com.droidhands.android.account

import com.droidhands.core.account.AccountApi
import com.droidhands.core.account.AccountResult
import com.droidhands.core.account.AccountSession
import com.droidhands.core.account.Pkce
import com.droidhands.core.account.RelayToken
import com.droidhands.core.transport.RelayAccountToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

/** A sign-in started in the browser: the PKCE verifier, when it started, and whether to go online with the relay after. */
data class PendingSignIn(val verifier: String, val startedAt: Long, val useRelay: Boolean) {
    override fun toString(): String = "PendingSignIn(verifier=***, startedAt=$startedAt, useRelay=$useRelay)"
}

/** Where the account is kept (production: [AccountStore], app-private and never backed up). */
interface AccountStorage {
    fun loadSession(): AccountSession?
    fun saveSession(session: AccountSession)
    fun clearSession()
    fun savePending(pending: PendingSignIn)

    /** The pending sign-in, removed so its link can be used once. */
    fun takePending(): PendingSignIn?
}

/** How a sign-in is going, for the "Continue with Google" button. */
sealed interface SignInState {
    data object Idle : SignInState

    /** Back from the browser: trading the code for a session. */
    data object Finishing : SignInState

    data class Failed(val message: String) : SignInState
}

/** What a `droidhands://signed-in` link came to. */
sealed interface SignInOutcome {
    data class SignedIn(val useRelay: Boolean) : SignInOutcome

    /** Not ours: no sign-in was started here (or it was too long ago), or the link is malformed. */
    data object Ignored : SignInOutcome

    data class Failed(val message: String) : SignInOutcome
}

/**
 * The DroidHands account on this phone (sign in with Google through the API server). The build's default
 * relay needs it: [relayToken] gives the relay's hello a short-lived token. Sign-in runs in the browser with PKCE
 * ([startSignIn], then [completeSignIn] with the `droidhands://signed-in` link). A session the server no longer
 * accepts (HTTP 401) is dropped, so the app shows "signed out". Thread-safe.
 *
 * @param api null when this build has no API server: then nothing needs an account.
 */
class AccountManager(
    private val api: AccountApi?,
    private val storage: AccountStorage,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val _session = MutableStateFlow(if (api == null) null else storage.loadSession())
    private val _signIn = MutableStateFlow<SignInState>(SignInState.Idle)
    private val tokenLock = Mutex()
    private var cachedToken: Pair<AccountSession, RelayToken>? = null

    /** This build signs in to an API server. */
    val enabled: Boolean get() = api != null

    /** The signed-in session, or null. */
    val session: StateFlow<AccountSession?> = _session.asStateFlow()

    val signIn: StateFlow<SignInState> = _signIn.asStateFlow()

    /**
     * A non-secret tag of the current session (null when signed out). It changes with every sign-in, so a relay
     * connection made for one session is redone for the next.
     */
    fun sessionTag(): String? = _session.value?.let { tagOf(it.token) }

    /**
     * Starts signing in: remembers a new verifier and returns the page to open in the browser (null without an
     * API server). [useRelay]: go online with the default relay once signed in.
     */
    fun startSignIn(useRelay: Boolean): String? {
        val api = api ?: return null
        val verifier = Pkce.newVerifier()
        storage.savePending(PendingSignIn(verifier, clock(), useRelay))
        _signIn.value = SignInState.Idle
        return api.signInUrl(Pkce.challenge(verifier))
    }

    /** Finishes the sign-in that [link] (`droidhands://signed-in?…`) answers. */
    suspend fun completeSignIn(link: String): SignInOutcome {
        val api = api ?: return SignInOutcome.Ignored
        val answer = SignInLinks.parse(link) ?: return SignInOutcome.Ignored
        val pending = storage.takePending()
        val age = pending?.let { clock() - it.startedAt }
        if (pending == null || age == null || age !in 0..PENDING_TTL_MS) {
            log("Ignored a sign-in link: no sign-in was started on this phone in the last few minutes")
            return SignInOutcome.Ignored
        }
        if (answer.code == null) return failed(SignInLinks.errorText(answer.error, answer.description))
        _signIn.value = SignInState.Finishing
        return when (val result = api.exchange(answer.code, pending.verifier)) {
            is AccountResult.Ok -> {
                storage.saveSession(result.value)
                tokenLock.withLock { cachedToken = null }
                _session.value = result.value
                _signIn.value = SignInState.Idle
                SignInOutcome.SignedIn(pending.useRelay)
            }
            AccountResult.SignedOut -> failed(SIGN_IN_FAILED)
            is AccountResult.Failed -> failed(
                if (result.error == "invalid_grant") "That sign-in expired. Try again." else "Couldn't sign in: ${result.message}",
            )
        }
    }

    /** Forgets a failed sign-in's message. */
    fun clearSignInError() {
        if (_signIn.value is SignInState.Failed) _signIn.value = SignInState.Idle
    }

    /** A token for the relay's hello, reused until a minute before it expires. */
    suspend fun relayToken(): RelayAccountToken {
        val api = api ?: return RelayAccountToken.SignedOut
        tokenLock.lock()
        try {
            val session = _session.value ?: return RelayAccountToken.SignedOut
            cachedToken?.let { (forSession, token) ->
                if (forSession == session && token.expiresAt - clock() > TOKEN_MARGIN_MS) return RelayAccountToken.Token(token.token)
            }
            return fetchRelayToken(api, session)
        } finally {
            tokenLock.unlock()
        }
    }

    private suspend fun fetchRelayToken(api: AccountApi, session: AccountSession): RelayAccountToken =
        when (val result = api.relayToken(session.token)) {
            is AccountResult.Ok -> {
                cachedToken = session to result.value
                RelayAccountToken.Token(result.value.token)
            }
            AccountResult.SignedOut -> {
                drop(session)
                RelayAccountToken.SignedOut
            }
            is AccountResult.Failed -> RelayAccountToken.Unavailable(result.message)
        }

    /** Re-reads the name and email; drops the session if the server no longer accepts it. */
    suspend fun refresh() {
        val api = api ?: return
        val session = _session.value ?: return
        when (val result = api.me(session.token)) {
            is AccountResult.Ok -> if (result.value != session.user && _session.value == session) {
                val updated = session.copy(user = result.value)
                storage.saveSession(updated)
                _session.value = updated
            }
            AccountResult.SignedOut -> drop(session)
            is AccountResult.Failed -> Unit // Offline: keep what we know.
        }
    }

    /** Signs out here, and on the server when it can be reached. */
    suspend fun signOut() {
        val session = _session.value ?: return
        val result = api?.signOut(session.token)
        if (result is AccountResult.Failed) log("Sign-out didn't reach the DroidHands server: ${result.message}")
        drop(session)
    }

    /** Deletes the account on the server, then signs out here. Null on success, else why it failed. */
    suspend fun deleteAccount(): String? {
        val api = api ?: return null
        val session = _session.value ?: return null
        return when (val result = api.deleteAccount(session.token)) {
            is AccountResult.Ok, AccountResult.SignedOut -> {
                drop(session)
                null
            }
            is AccountResult.Failed -> result.message
        }
    }

    private fun drop(session: AccountSession) {
        if (_session.compareAndSet(session, null)) storage.clearSession()
    }

    private fun failed(message: String): SignInOutcome {
        _signIn.value = SignInState.Failed(message)
        return SignInOutcome.Failed(message)
    }

    companion object {
        /** A sign-in link is accepted this long after the sign-in started. */
        const val PENDING_TTL_MS = 15 * 60_000L

        /** A relay token this close to expiring is replaced. */
        const val TOKEN_MARGIN_MS = 60_000L

        const val SIGN_IN_FAILED = "Sign-in didn't work. Try again."

        private fun tagOf(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    }
}

/** `droidhands://signed-in?code=…` or `?error=…&error_description=…`, the API server's answer. Pure. */
object SignInLinks {
    const val SCHEME = "droidhands"
    const val HOST = "signed-in"

    data class Answer(val code: String?, val error: String?, val description: String?)

    fun isSignInLink(link: String): Boolean = runCatching {
        val uri = URI(link)
        uri.scheme.equals(SCHEME, ignoreCase = true) && uri.host.equals(HOST, ignoreCase = true)
    }.getOrDefault(false)

    /** The answer in [link], or null when it isn't a sign-in link or has neither a code nor an error. */
    fun parse(link: String): Answer? {
        if (!isSignInLink(link)) return null
        val query = runCatching { URI(link).rawQuery }.getOrNull().orEmpty()
        val params = query.split('&').filter { it.isNotEmpty() }.associate { part ->
            val name = part.substringBefore('=')
            val value = part.substringAfter('=', "")
            decode(name) to decode(value)
        }
        val code = params["code"]?.takeIf { it.isNotBlank() }
        val error = params["error"]?.takeIf { it.isNotBlank() }
        if (code == null && error == null) return null
        return Answer(code, error, params["error_description"]?.takeIf { it.isNotBlank() }?.take(200))
    }

    /** What to tell the person about a sign-in that ended with [error]. */
    fun errorText(error: String?, description: String?): String = when (error) {
        "access_denied" -> "Sign-in was cancelled."
        else -> description?.let { "Couldn't sign in: $it" } ?: AccountManager.SIGN_IN_FAILED
    }

    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, Charsets.UTF_8) }.getOrDefault(value)
}
