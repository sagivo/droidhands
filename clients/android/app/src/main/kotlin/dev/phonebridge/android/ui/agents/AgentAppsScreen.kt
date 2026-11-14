package dev.phonebridge.android.ui.agents

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonebridge.android.ui.apps.AppPickerList
import dev.phonebridge.android.ui.components.Notice
import dev.phonebridge.android.ui.components.ScreenScaffold
import dev.phonebridge.android.ui.components.padded
import dev.phonebridge.core.auth.AppAccessMode

/**
 * Picks the apps of an agent's allowlist or blocklist. Toggles edit the agent's draft directly;
 * the detail page's Save bar persists them. Never-allow apps are shown locked.
 */
@Composable
fun AgentAppsScreen(vm: AgentsViewModel, tokenId: String, onBack: () -> Unit) {
    LaunchedEffect(tokenId) {
        vm.open(tokenId)
        vm.loadApps()
    }
    val apps by vm.apps.collectAsStateWithLifecycle()
    val neverAllow by vm.neverAllowApps.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val draft = vm.draft?.takeIf { vm.editingId == tokenId }
    val name = vm.tokens.collectAsStateWithLifecycle().value.firstOrNull { it.id == tokenId }?.name ?: "Agent"
    val allowlist = draft?.appPolicy?.mode == AppAccessMode.ALLOWLIST

    ScreenScaffold(
        title = if (allowlist) "Apps $name may use" else "Apps blocked for $name",
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        actions = { TextButton(onClick = onBack) { Text("Done") } },
    ) { padding ->
        if (draft == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@ScreenScaffold
        }
        AppPickerList(
            apps = apps,
            selected = draft.appPolicy.packages,
            query = query,
            onQueryChange = { query = it },
            onToggle = { pkg -> vm.edit { it.toggleApp(pkg) } },
            contentPadding = padding.padded(horizontal = 0.dp),
            locked = neverAllow,
            lockedSelected = !allowlist,
            lockedReason = "Blocked for every agent (Settings > Security)",
            header = {
                item(key = "intro") {
                    Notice(
                        if (allowlist) {
                            "$name can see and operate only the apps you check. Everything else is hidden from it. " +
                                "Remember to save on the previous page."
                        } else {
                            "$name can use every app except the ones you check. Remember to save on the previous page."
                        },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            },
        )
    }
}
