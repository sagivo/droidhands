package dev.phonebridge.android.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonebridge.android.ui.MainViewModel
import dev.phonebridge.android.ui.components.BottomActionBar
import dev.phonebridge.android.ui.connect.ConnectAgentContent
import dev.phonebridge.android.ui.setup.SetupViewModel
import dev.phonebridge.core.transport.RelayStatus

/** Navigation callbacks of the onboarding wizard. */
class OnboardingActions(
    /** Finished or skipped: the wizard doesn't come back by itself. */
    val onFinish: () -> Unit,
    /** "Use a token instead" (opens the manual token flow above the wizard). */
    val onUseToken: () -> Unit,
)

/**
 * First-run wizard, full screen: Welcome → Permissions → Connect → Add your first agent. Every
 * step can be skipped, and the whole wizard can be re-opened from Settings > About.
 */
@Composable
fun OnboardingScreen(main: MainViewModel, setup: SetupViewModel, actions: OnboardingActions) {
    var stepName by rememberSaveable { mutableStateOf(OnboardingStep.WELCOME.name) }
    val step = OnboardingStep.fromName(stepName)
    val relay by main.relayStatus.collectAsStateWithLifecycle()
    val settings by main.settings.collectAsStateWithLifecycle()
    val connected = relay is RelayStatus.Connected || settings.localServerEnabled
    val go: (OnboardingStep) -> Unit = { stepName = it.name }
    step.previous()?.let { previous -> BackHandler { go(previous) } }

    Scaffold(
        topBar = { WizardTopBar(step, onSkip = actions.onFinish) },
        bottomBar = {
            BottomActionBar {
                step.previous()?.let { previous -> TextButton(onClick = { go(previous) }) { Text("Back") } }
                Spacer(Modifier.weight(1f))
                val next = step.next()
                when {
                    step == OnboardingStep.WELCOME -> Button(onClick = { go(OnboardingStep.PERMISSIONS) }) { Text("Get started") }
                    next != null -> Button(onClick = { go(next) }) { Text(continueLabel(step, connected)) }
                    else -> Button(onClick = actions.onFinish) { Text("Done") }
                }
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        val scroll = rememberScrollState()
        LaunchedEffect(step) { scroll.scrollTo(0) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scroll)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (step) {
                OnboardingStep.WELCOME -> WelcomeStep()
                OnboardingStep.PERMISSIONS -> PermissionsStep(setup)
                OnboardingStep.CONNECT -> ConnectStep(main)
                OnboardingStep.AGENT -> {
                    StepTitle("Add your first agent", "Paste your connection URL into the agent, then tap Allow when this phone asks.")
                    ConnectAgentContent(
                        main = main,
                        onUseToken = actions.onUseToken,
                        onOpenConnectionSettings = { go(OnboardingStep.CONNECT) },
                        onConnected = "Finish" to { _ -> actions.onFinish() },
                    )
                }
            }
        }
    }
}

@Composable
private fun WizardTopBar(step: OnboardingStep, onSkip: () -> Unit) {
    val total = OnboardingStep.entries.size
    Column(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Step ${step.number} of $total · ${step.title}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSkip) { Text("Skip setup") }
        }
        val progress = step.number.toFloat() / total
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Step ${step.number} of $total" },
        )
    }
}

/** Big step title plus one explanatory sentence. */
@Composable
internal fun StepTitle(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun continueLabel(step: OnboardingStep, connected: Boolean): String = when (step) {
    OnboardingStep.CONNECT -> if (connected) "Continue" else "Skip for now"
    else -> "Continue"
}
