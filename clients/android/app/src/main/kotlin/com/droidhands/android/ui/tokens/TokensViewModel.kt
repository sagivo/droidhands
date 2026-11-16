package com.droidhands.android.ui.tokens

import android.app.Application
import android.util.Log
import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.droidhands.android.graph.BridgeGraph
import com.droidhands.android.ui.GraphAdapters
import com.droidhands.android.ui.apps.AppListing
import com.droidhands.core.auth.IssuedToken
import com.droidhands.core.auth.TokenRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which part of the manual token flow is visible; [List] means the flow is closed. */
sealed interface TokenScreen {
    data object List : TokenScreen
    data object Editor : TokenScreen

    /** Holds the only in-memory copy of the plaintext. Not saved to instance state, by design. */
    class Created(val token: IssuedToken) : TokenScreen {
        override fun toString() = "Created(${token.record.id})"
    }
}

/**
 * The manual token path ("Use a token instead"): the "New token" form and the one-time display of
 * a new token. The plaintext lives only in [screen] while [TokenScreen.Created] is shown (it
 * survives rotation but not process death), and is dropped by [finish]. Issuing persists to disk,
 * so it runs on IO. Revoking and deleting live with the agents (`AgentsViewModel`).
 */
class TokensViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = BridgeGraph.get(app)
    private val tokenManager = graph.tokenManager
    private val _screen = MutableStateFlow<TokenScreen>(TokenScreen.List)
    private val _messages = Channel<String>(Channel.BUFFERED)

    val tokens: StateFlow<List<TokenRecord>> = tokenManager.tokens
    val screen: StateFlow<TokenScreen> = _screen.asStateFlow()
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** Installed apps for the grant's app picker; null until loaded ([startCreate] loads them). */
    val apps: StateFlow<List<AppListing>?> = graph.appDirectory.apps

    /** Global never-allow list (shown locked in the picker, counted on the grant screen). */
    val neverAllowApps: StateFlow<Set<String>> = graph.settings.state
        .map { GraphAdapters.neverAllowApps(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, GraphAdapters.neverAllowApps(graph.settings.state.value))

    /** The "New token" form (Compose state, main thread only). */
    var draft by mutableStateOf(TokenDraft())
        private set

    var creating by mutableStateOf(false)
        private set

    @MainThread
    fun startCreate() {
        draft = TokenDraft(name = TokenPresentation.defaultName(tokens.value.map { it.name }))
        graph.appDirectory.refresh()
        _screen.value = TokenScreen.Editor
    }

    @MainThread
    fun updateDraft(transform: (TokenDraft) -> TokenDraft) {
        draft = transform(draft)
    }

    @MainThread
    fun cancelCreate() {
        if (_screen.value == TokenScreen.Editor) _screen.value = TokenScreen.List
    }

    @MainThread
    fun create() {
        val d = draft
        if (!d.canCreate || creating) return
        creating = true
        viewModelScope.launch {
            try {
                val issued = withContext(Dispatchers.IO) { issue(d) }
                _screen.value = TokenScreen.Created(issued)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Token creation failed: ${e.javaClass.name}")
                _messages.send("Couldn't create the token: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                creating = false
            }
        }
    }

    /** Issues the token with the draft's whole grant (scopes, apps, approvals) in one save. Blocking (disk). */
    private fun issue(d: TokenDraft): IssuedToken {
        val g = d.grant.settings()
        return tokenManager.issue(d.trimmedName, g.scopes, g.confirmPolicy, d.expiry.ttlMillis, appPolicy = g.appPolicy, maxRisk = g.maxRisk)
    }

    /** Leaves the "Token created" screen and forgets the plaintext. */
    @MainThread
    fun finish() {
        _screen.value = TokenScreen.List
    }

    override fun onCleared() {
        _screen.value = TokenScreen.List
    }

    private companion object {
        const val TAG = "DroidHandsUi"
    }
}
