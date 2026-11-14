package dev.phonebridge.android.ui.agents

import android.app.Application
import android.util.Log
import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.phonebridge.android.graph.BridgeGraph
import dev.phonebridge.android.ui.GraphAdapters
import dev.phonebridge.android.ui.apps.AppInfo
import dev.phonebridge.android.ui.apps.LaunchableApps
import dev.phonebridge.core.audit.AuditEntry
import dev.phonebridge.core.audit.TokenUsage
import dev.phonebridge.core.auth.TokenRecord
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

/**
 * Agents list and the agent detail page. The detail page edits one agent at a time through
 * [draft] (Compose state, main thread), which follows outside changes to the agent until the user
 * edits something. Token-store writes run on [Dispatchers.IO].
 */
class AgentsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = BridgeGraph.get(app)
    private val _messages = Channel<String>(Channel.BUFFERED)
    private val _tools = MutableStateFlow<List<ToolInfo>>(emptyList())
    private val _apps = MutableStateFlow<List<AppInfo>?>(null)

    val tokens: StateFlow<List<TokenRecord>> = graph.tokenManager.tokens
    val usage: StateFlow<Map<String, TokenUsage>> = GraphAdapters.usage(graph)
    val auditEntries: StateFlow<List<AuditEntry>> = graph.audit.entries
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** Global never-allow list (Settings > Security); locked in the per-agent app picker. */
    val neverAllowApps: StateFlow<Set<String>> = graph.settings.state
        .map { GraphAdapters.neverAllowApps(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, GraphAdapters.neverAllowApps(graph.settings.state.value))

    /** Every registered tool (for per-tool rules); empty until loaded. */
    val tools: StateFlow<List<ToolInfo>> = _tools.asStateFlow()

    /** Launchable apps for the app picker; null until [loadApps] finished. */
    val apps: StateFlow<List<AppInfo>?> = _apps.asStateFlow()

    /** Id of the agent shown on the detail page. */
    var editingId by mutableStateOf<String?>(null)
        private set

    /** Edits of the agent on the detail page; null when no agent is open (or it was deleted). */
    var draft by mutableStateOf<AgentDraft?>(null)
        private set

    var saving by mutableStateOf(false)
        private set

    /** Record the untouched [draft] corresponds to. */
    private var synced: TokenRecord? = null

    init {
        viewModelScope.launch {
            // Building the registry instantiates every tool: keep it off the main thread.
            val loaded = try {
                withContext(Dispatchers.IO) { graph.registry.all().map(ToolInfo::of) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading tools failed: ${e.javaClass.name}")
                emptyList()
            }
            _tools.value = loaded
        }
        viewModelScope.launch {
            tokens.collect { list ->
                val id = editingId ?: return@collect
                val latest = list.firstOrNull { it.id == id }
                if (latest == null) {
                    draft = null
                    synced = null
                    return@collect
                }
                // Follow outside changes (e.g. revoked from Home) as long as the user hasn't edited anything.
                val previous = synced
                val current = draft
                val untouched = current == null || previous == null || current == AgentDraft.from(previous)
                if (untouched) draft = AgentDraft.from(latest)
                synced = latest
            }
        }
    }

    /** Shows agent [id] on the detail page; re-opening the same agent keeps unsaved edits. */
    @MainThread
    fun open(id: String) {
        if (editingId == id && draft != null) return
        val record = tokens.value.firstOrNull { it.id == id }
        editingId = id
        synced = record
        draft = record?.let(AgentDraft::from)
    }

    @MainThread
    fun edit(transform: (AgentDraft) -> AgentDraft) {
        draft = draft?.let(transform)
    }

    @MainThread
    fun discard() {
        draft = synced?.let(AgentDraft::from)
    }

    fun isDirty(): Boolean {
        val d = draft ?: return false
        val s = synced ?: return false
        return d.differsFrom(s)
    }

    @MainThread
    fun save() {
        val id = editingId ?: return
        val edited = draft ?: return
        if (saving || !edited.canSave) return
        saving = true
        viewModelScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    graph.tokenManager.update(id) { edited.applyTo(it, System.currentTimeMillis()) }
                }
                if (editingId == id) {
                    synced = updated
                    draft = AgentDraft.from(updated)
                }
                _messages.send("Saved \"${updated.name}\"")
            } catch (e: CancellationException) {
                throw e
            } catch (e: NoSuchElementException) {
                _messages.send("This agent was deleted.")
            } catch (e: Exception) {
                Log.w(TAG, "Saving agent failed: ${e.javaClass.name}")
                _messages.send("Couldn't save: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                saving = false
            }
        }
    }

    fun revoke(record: TokenRecord) = io("Couldn't revoke \"${record.name}\"", "\"${record.name}\" revoked") {
        graph.tokenManager.revoke(record.id)
    }

    fun delete(record: TokenRecord) = io("Couldn't delete \"${record.name}\"", "\"${record.name}\" deleted") {
        graph.tokenManager.delete(record.id)
        GraphAdapters.forgetUsage(graph, record.id)
    }

    /** Loads (or refreshes) the launchable apps for the picker. */
    fun loadApps() {
        viewModelScope.launch {
            try {
                _apps.value = LaunchableApps.load(getApplication())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading apps failed: ${e.javaClass.name}")
                if (_apps.value == null) _apps.value = emptyList()
            }
        }
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

    private companion object {
        const val TAG = "PhoneBridgeUi"
    }
}
