package dev.phonebridge.android.ui.tokens

import android.app.Application
import android.util.Log
import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.phonebridge.android.graph.BridgeGraph
import dev.phonebridge.core.auth.IssuedToken
import dev.phonebridge.core.auth.TokenRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which part of the Tokens flow is visible. */
sealed interface TokenScreen {
    data object List : TokenScreen
    data object Editor : TokenScreen

    /** Holds the only in-memory copy of the plaintext. Not saved to instance state, by design. */
    class Created(val token: IssuedToken) : TokenScreen {
        override fun toString() = "Created(${token.record.id})"
    }
}

/**
 * Tokens list, the "New token" form and the one-time display of a new token. The plaintext lives
 * only in [screen] while [TokenScreen.Created] is shown (it survives rotation but not process
 * death), and is dropped by [finish]. TokenManager calls persist to disk, so they run on IO.
 */
class TokensViewModel(app: Application) : AndroidViewModel(app) {
    private val tokenManager = BridgeGraph.get(app).tokenManager
    private val _screen = MutableStateFlow<TokenScreen>(TokenScreen.List)
    private val _messages = Channel<String>(Channel.BUFFERED)

    val tokens: StateFlow<List<TokenRecord>> = tokenManager.tokens
    val screen: StateFlow<TokenScreen> = _screen.asStateFlow()
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** The "New token" form (Compose state, main thread only). */
    var draft by mutableStateOf(TokenDraft())
        private set

    var creating by mutableStateOf(false)
        private set

    @MainThread
    fun startCreate() {
        draft = TokenDraft(name = TokenPresentation.defaultName(tokens.value.map { it.name }))
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
                val issued = withContext(Dispatchers.IO) {
                    tokenManager.issue(d.trimmedName, d.scopes, d.policy, d.expiry.ttlMillis)
                }
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

    /** Leaves the "Token created" screen and forgets the plaintext. */
    @MainThread
    fun finish() {
        _screen.value = TokenScreen.List
    }

    fun revoke(record: TokenRecord) = io("Couldn't revoke \"${record.name}\"", "\"${record.name}\" revoked") {
        tokenManager.revoke(record.id)
    }

    fun delete(record: TokenRecord) = io("Couldn't delete \"${record.name}\"", "\"${record.name}\" deleted") {
        tokenManager.delete(record.id)
    }

    private fun io(failure: String, success: String, block: () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                _messages.send(success)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$failure: ${e.javaClass.name}")
                _messages.send("$failure: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    override fun onCleared() {
        _screen.value = TokenScreen.List
    }

    private companion object {
        const val TAG = "PhoneBridgeUi"
    }
}
