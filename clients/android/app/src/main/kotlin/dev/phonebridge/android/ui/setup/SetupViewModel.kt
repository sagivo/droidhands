package dev.phonebridge.android.ui.setup

import android.Manifest
import android.app.Activity
import android.app.Application
import android.os.Build
import android.util.Log
import androidx.annotation.MainThread
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.phonebridge.android.tools.support.Perms
import dev.phonebridge.android.ui.UiPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Snapshot of the Setup checklist. */
data class SetupUiState(
    val loaded: Boolean = false,
    val items: List<SetupItem> = emptyList(),
    /** For the onboarding Permissions step: installed from the web and restricted settings not allowed yet. */
    val restrictedSettings: RestrictedSettings.Advice = RestrictedSettings.Advice(),
) {
    val progress: SetupProgress = SetupLogic.progress(items)
}

/**
 * Activity-scoped Setup checklist shared by Home (progress hint) and Setup. Call [refresh] on
 * every ON_RESUME and after permission results: the user changes these in system settings.
 */
class SetupViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = UiPrefs(app)
    private val asked = MutableStateFlow<Set<String>>(emptySet())
    private val askedLoaded = CompletableDeferred<Unit>()
    private val _state = MutableStateFlow(SetupUiState())
    private var refreshJob: Job? = null

    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val stored = prefs.askedPermissions()
                asked.update { it + stored }
            } finally {
                askedLoaded.complete(Unit)
            }
        }
    }

    /**
     * Re-reads every row on [Dispatchers.IO]. [activity] is only used for the rationale check
     * during this refresh and is not retained afterwards.
     */
    @MainThread
    fun refresh(activity: Activity?) {
        val showRationale: (String) -> Boolean = { activity?.shouldShowRequestPermissionRationale(it) == true }
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            askedLoaded.await()
            val askedNow = asked.value
            try {
                val (items, restricted) = withContext(Dispatchers.IO) {
                    SetupChecks.read(getApplication(), askedNow, showRationale) to SetupChecks.installRestricted(getApplication())
                }
                val advice = RestrictedSettings.advice(restricted, Build.VERSION.SDK_INT, items)
                _state.value = SetupUiState(loaded = true, items = items, restrictedSettings = advice)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep the previous snapshot; the next resume retries.
                Log.w(TAG, "Setup refresh failed: ${e.javaClass.name}")
            }
        }
    }

    /** Remember that [permissions] were requested, before launching the system dialog. */
    @MainThread
    fun onPermissionsRequested(permissions: Collection<String>) {
        asked.update { it + permissions }
        viewModelScope.launch { prefs.markAsked(permissions) }
    }

    /** True once per install on Android 13+ when notifications aren't granted yet. */
    suspend fun shouldPromptNotificationsOnFirstLaunch(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val granted = withContext(Dispatchers.IO) { Perms.has(getApplication(), Manifest.permission.POST_NOTIFICATIONS) }
        return !granted && prefs.consumeFirstLaunchNotificationPrompt()
    }

    private companion object {
        const val TAG = "PhoneBridgeUi"
    }
}
