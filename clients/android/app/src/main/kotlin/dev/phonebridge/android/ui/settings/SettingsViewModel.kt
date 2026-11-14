package dev.phonebridge.android.ui.settings

import android.app.Application
import android.util.Log
import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.phonebridge.android.graph.BridgeGraph
import dev.phonebridge.android.settings.BridgeSettings
import dev.phonebridge.android.ui.GraphAdapters
import dev.phonebridge.android.ui.apps.AppInfo
import dev.phonebridge.android.ui.apps.LaunchableApps
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
 * Settings form state. Connection edits stay in memory (the relay access key is never written to
 * saved instance state) until [save] persists them and re-applies them to the bridge service.
 * Security switches (never-allow apps, hiding one-time codes) are saved immediately: the bridge
 * reads them on every call.
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = BridgeGraph.get(app)
    private val _messages = Channel<String>(Channel.BUFFERED)

    val settings: StateFlow<BridgeSettings> = graph.settings.state
    val deviceId: String = graph.identity.deviceId
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** Packages no agent may touch (Settings > Security). */
    val neverAllowApps: StateFlow<Set<String>> = settings
        .map { GraphAdapters.neverAllowApps(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, GraphAdapters.neverAllowApps(settings.value))

    /** Mask verification codes in text agents read (default on). */
    val redactOneTimeCodes: StateFlow<Boolean> = settings
        .map { GraphAdapters.redactOneTimeCodes(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, GraphAdapters.redactOneTimeCodes(settings.value))

    private val _apps = MutableStateFlow<List<AppInfo>?>(null)
    private val _suggested = MutableStateFlow<Set<String>>(emptySet())

    /** Launchable apps for the never-allow picker; null until [loadApps] finished. */
    val apps: StateFlow<List<AppInfo>?> = _apps.asStateFlow()

    /** Installed banking / payment / password / authenticator apps, suggested for the never-allow list. */
    val suggestedApps: StateFlow<Set<String>> = _suggested.asStateFlow()

    var form by mutableStateOf(SettingsForm.from(settings.value))
        private set

    var saving by mutableStateOf(false)
        private set

    /** Settings the untouched form corresponds to. */
    private var synced: BridgeSettings = settings.value

    init {
        viewModelScope.launch {
            settings.collect { latest ->
                // Follow outside changes (notification, boot) as long as the user hasn't edited anything.
                if (form == SettingsForm.from(synced)) form = SettingsForm.from(latest)
                synced = latest
            }
        }
    }

    @MainThread
    fun edit(transform: (SettingsForm) -> SettingsForm) {
        form = transform(form)
    }

    @MainThread
    fun discard() {
        synced = settings.value
        form = SettingsForm.from(synced)
    }

    @MainThread
    fun save() {
        val edited = form
        if (saving || !edited.validate().isValid) return
        saving = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    graph.settings.update { edited.applyTo(it) }
                    graph.applySettings()
                }
                synced = settings.value
                form = SettingsForm.from(synced)
                _messages.send(savedMessage(synced))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Saving settings failed: ${e.javaClass.name}")
                _messages.send("Couldn't save settings: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                saving = false
            }
        }
    }

    fun setRedactOneTimeCodes(on: Boolean) = saveSecurity("Couldn't change the setting") {
        GraphAdapters.withRedactOneTimeCodes(it, on)
    }

    fun saveNeverAllowApps(packages: Set<String>) = saveSecurity("Couldn't save the never-allow list", "Never-allow list saved") {
        GraphAdapters.withNeverAllowApps(it, packages)
    }

    /** Loads the app list and the sensitive-app suggestions (PackageManager, on IO). */
    fun loadApps() {
        viewModelScope.launch {
            try {
                _apps.value = LaunchableApps.load(getApplication())
                _suggested.value = withContext(Dispatchers.IO) { GraphAdapters.suggestSensitiveApps(getApplication()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading apps failed: ${e.javaClass.name}")
                if (_apps.value == null) _apps.value = emptyList()
            }
        }
    }

    private fun saveSecurity(failure: String, success: String? = null, transform: (BridgeSettings) -> BridgeSettings) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { graph.settings.update(transform) }
                success?.let { _messages.send(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$failure: ${e.javaClass.name}")
                _messages.send("$failure: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun savedMessage(s: BridgeSettings): String = when {
        s.relayEnabled && s.localServerEnabled -> "Saved. Connecting to the relay and starting the local server…"
        s.relayEnabled -> "Saved. Connecting to the relay…"
        s.localServerEnabled -> "Saved. Starting the local server…"
        else -> "Saved. Relay and local server are off."
    }

    private companion object {
        const val TAG = "PhoneBridgeUi"
    }
}
