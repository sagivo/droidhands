package com.droidhands.android.ui.agents

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
import com.droidhands.android.ui.apps.ActionNames
import com.droidhands.android.ui.apps.AppListing
import com.droidhands.core.audit.AuditEntry
import com.droidhands.core.audit.TokenUsage
import com.droidhands.core.auth.GrantDraft
import com.droidhands.core.auth.TokenRecord
import com.droidhands.core.tools.Tool
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

    val tokens: StateFlow<List<TokenRecord>> = graph.tokenManager.tokens
    val usage: StateFlow<Map<String, TokenUsage>> = GraphAdapters.usage(graph)
    val auditEntries: StateFlow<List<AuditEntry>> = graph.audit.entries
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** Global never-allow list (Settings > Security); locked in the grant's app picker. */
    val neverAllowApps: StateFlow<Set<String>> = graph.settings.state
        .map { GraphAdapters.neverAllowApps(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, GraphAdapters.neverAllowApps(graph.settings.state.value))

    /** Every registered tool (for per-tool rules); empty until loaded. */
    val tools: StateFlow<List<ToolInfo>> = _tools.asStateFlow()

    /** Installed apps and their actions (per-action rules, the grant's app picker); null until loaded, see [loadAppActions]. */
    val appActions: StateFlow<List<AppListing>?> = graph.appDirectory.apps

    /** True while the app actions are being (re)loaded. */
    val appActionsLoading: StateFlow<Boolean> = graph.appDirectory.loading

    private val registryTools = MutableStateFlow<List<Tool>>(emptyList())

    /** Readable tool names ("Create calendar event", "Spotify – Play music") for usage and activity rows. */
    val actionNames: StateFlow<ActionNames.Index> = GraphAdapters.actionNames(graph, registryTools, viewModelScope)

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

    /** Id of the agent whose Access page is open. */
    var grantId by mutableStateOf<String?>(null)
        private set

    /** The Access page's edits (the grant editor); null when it isn't open. */
    var grant by mutableStateOf<GrantDraft?>(null)
        private set

    /** The saved grant [grant] started from (older settings get their own rows in the editor). */
    var grantOriginal by mutableStateOf<GrantDraft?>(null)
        private set

    var savingGrant by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            val loaded = GraphAdapters.registryTools(graph)
            registryTools.value = loaded
            _tools.value = loaded.map(ToolInfo::of)
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

    /** Opens agent [id]'s Access page, pre-filled with its current grant; re-opening the same agent keeps unsaved edits. */
    @MainThread
    fun openGrant(id: String) {
        if (grantId == id && grant != null) return
        val record = tokens.value.firstOrNull { it.id == id }
        grantId = id
        grantOriginal = record?.let(GrantDraft::from)
        grant = grantOriginal
    }

    @MainThread
    fun editGrant(edited: GrantDraft) {
        if (grant != null) grant = edited
    }

    /** True if saving the Access page would change the agent. */
    fun isGrantDirty(): Boolean {
        val g = grant ?: return false
        val record = tokens.value.firstOrNull { it.id == grantId } ?: return false
        return g.differsFrom(record)
    }

    @MainThread
    fun closeGrant() {
        grantId = null
        grant = null
        grantOriginal = null
    }

    /**
     * Saves the Access page: the grant's scopes, apps, approvals and risk cap, applied to the current record
     * (nothing else changes). New requests from the agent follow it at once. [onSaved] runs on success.
     */
    @MainThread
    fun saveGrant(onSaved: () -> Unit) {
        val id = grantId ?: return
        val edited = grant ?: return
        if (savingGrant || !edited.isValid) return
        savingGrant = true
        viewModelScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) { graph.tokenManager.update(id) { edited.applyTo(it) } }
                _messages.send("Saved. ${updated.name}'s next requests follow its new access.")
                closeGrant()
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: NoSuchElementException) {
                _messages.send("This agent was deleted.")
            } catch (e: Exception) {
                Log.w(TAG, "Saving access failed: ${e.javaClass.name}")
                _messages.send("Couldn't save: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                savingGrant = false
            }
        }
    }

    /** Locks the agent out and stops its calls that are still running. */
    fun revoke(record: TokenRecord) = io("Couldn't revoke \"${record.name}\"", "\"${record.name}\" revoked") {
        GraphAdapters.revokeAgent(graph, record.id)
    }

    /** Removes the agent (and its usage counters) and stops its calls that are still running. */
    fun delete(record: TokenRecord) = io("Couldn't delete \"${record.name}\"", "\"${record.name}\" deleted") {
        GraphAdapters.deleteAgent(graph, record.id)
    }

    /** Loads the installed apps' actions in the background (a no-op when they were loaded moments ago). */
    fun loadAppActions() = graph.appDirectory.refresh()

    private fun io(failure: String, success: String, block: suspend () -> Unit) {
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
        const val TAG = "DroidHandsUi"
    }
}
