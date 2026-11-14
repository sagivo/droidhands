package dev.phonebridge.android.ui.apps

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.phonebridge.android.BuildConfig
import dev.phonebridge.android.graph.BridgeGraph
import dev.phonebridge.android.ui.GraphAdapters
import dev.phonebridge.core.plugins.PluginDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Apps tab and the app detail page: installed apps and their actions ([AppDirectory]), the global
 * never-allow list ("Block for all agents", saved at once like Settings > Security), and the contributor
 * helpers (integration request link, plugin draft).
 */
class AppsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = BridgeGraph.get(app)
    private val _messages = Channel<String>(Channel.BUFFERED)

    /** Installed apps with their actions; null until the first load finished. */
    val apps: StateFlow<List<AppListing>?> = graph.appDirectory.apps

    val loading: StateFlow<Boolean> = graph.appDirectory.loading

    /** Packages no agent may touch (Settings > Security > Never-allow apps). */
    val neverAllowApps: StateFlow<Set<String>> = graph.settings.state
        .map { GraphAdapters.neverAllowApps(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, GraphAdapters.neverAllowApps(graph.settings.state.value))

    val messages: Flow<String> = _messages.receiveAsFlow()

    /** The build's GitHub repository ("owner/name"), or "" when it names none. */
    val githubRepo: String = BuildConfig.GITHUB_REPO

    /** True while a plugin draft is being built. */
    var draftingFor by mutableStateOf<String?>(null)
        private set

    /** Reloads the app list (cheap when it was loaded moments ago, unless [force]). */
    fun refresh(force: Boolean = false) = graph.appDirectory.refresh(force)

    /** "Block for all agents": adds [packageName] to (or removes it from) the never-allow list. */
    fun setBlocked(packageName: String, label: String, blocked: Boolean) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    graph.settings.update { current ->
                        val apps = GraphAdapters.neverAllowApps(current)
                        GraphAdapters.withNeverAllowApps(current, if (blocked) apps + packageName else apps - packageName)
                    }
                }
                _messages.send(if (blocked) "$label is blocked for every agent." else "Agents may use $label again (their own app access still applies).")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Saving the never-allow list failed: ${e.javaClass.name}")
                _messages.send("Couldn't save the never-allow list: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Builds a plugin.yaml draft for [packageName] from what PhoneBridge discovered about it
     * ([PluginDraft]) and hands it to [share] as an ACTION_SEND chooser intent.
     */
    fun createDraft(packageName: String, share: (Intent) -> Unit) {
        if (draftingFor != null) return
        draftingFor = packageName
        viewModelScope.launch {
            try {
                val draft = withContext(Dispatchers.IO) { buildDraft(packageName) }
                if (draft == null) {
                    _messages.send("This app is no longer installed.")
                    return@launch
                }
                val (label, yaml) = draft
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, AppsPresentation.draftSubject(label))
                    .putExtra(Intent.EXTRA_TEXT, yaml)
                share(Intent.createChooser(send, "Share plugin draft"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Building a plugin draft failed: ${e.javaClass.name}")
                _messages.send("Couldn't build the draft: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                draftingFor = null
            }
        }
    }

    /** (label, YAML) for [packageName], or null if it is not installed. Blocking: PackageManager and plugin I/O. */
    private suspend fun buildDraft(packageName: String): Pair<String, String>? {
        val catalog = graph.appActions
        val app = catalog.platform.installedApps().firstOrNull { it.packageName == packageName } ?: return null
        val discovered = catalog.platform.discoveredActions(packageName)
        val existing = graph.plugins.bundled().pluginsFor(packageName).firstOrNull()
        val category = AppsPresentation.draftCategory(androidCategory(packageName), discovered.map { it.id })
        val input = PluginDraft.Input(app.label, packageName, discovered, category, app.versionName, existing)
        return app.label to PluginDraft.yaml(input)
    }

    @Suppress("DEPRECATION")
    private fun androidCategory(packageName: String): Int = try {
        val pm = getApplication<Application>().packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            pm.getApplicationInfo(packageName, 0)
        }
        info.category
    } catch (e: PackageManager.NameNotFoundException) {
        ApplicationInfo.CATEGORY_UNDEFINED
    } catch (e: RuntimeException) {
        ApplicationInfo.CATEGORY_UNDEFINED
    }

    private companion object {
        const val TAG = "PhoneBridgeUi"
    }
}
