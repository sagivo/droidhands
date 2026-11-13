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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings form state. Edits stay in memory (the relay access key is never written to saved
 * instance state) until [save] persists them and re-applies them to the bridge service.
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = BridgeGraph.get(app)
    private val _messages = Channel<String>(Channel.BUFFERED)

    val settings: StateFlow<BridgeSettings> = graph.settings.state
    val deviceId: String = graph.identity.deviceId
    val messages: Flow<String> = _messages.receiveAsFlow()

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
