package dev.phonebridge.android.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonebridge.android.ui.MainViewModel
import dev.phonebridge.android.ui.components.BottomActionBar
import dev.phonebridge.android.ui.components.ButtonSize
import dev.phonebridge.android.ui.components.IconBadge
import dev.phonebridge.android.ui.components.PrimaryButton
import dev.phonebridge.android.ui.components.SecondaryButton
import dev.phonebridge.android.ui.components.TertiaryButton
import dev.phonebridge.android.ui.connect.ConnectAgentContent
import dev.phonebridge.android.ui.setup.SetupViewModel
import dev.phonebridge.android.ui.theme.TINT_ALPHA
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
 * step can be skipped, and the whole wizard can be re-opened from Settings > About. Laid out like
 * the Revolut / Linear onboarding: segmented progress and Skip on top, a hero header per step, and
 * Back · Continue in a sticky bar.
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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { WizardTopBar(step, onSkip = actions.onFinish) },
        bottomBar = {
            BottomActionBar {
                step.previous()?.let { previous -> TertiaryButton("Back", onClick = { go(previous) }) }
                val next = step.next()
                val wide = Modifier.weight(1f)
                when {
                    step == OnboardingStep.WELCOME ->
                        PrimaryButton("Get started", onClick = { go(OnboardingStep.PERMISSIONS) }, size = ButtonSize.LARGE, fullWidth = true)
                    // Not online yet: moving on is skipping, so it doesn't get the primary look.
                    next != null && step == OnboardingStep.CONNECT && !connected ->
                        SecondaryButton(continueLabel(step, connected), onClick = { go(next) }, modifier = wide, size = ButtonSize.LARGE)
                    next != null ->
                        PrimaryButton(continueLabel(step, connected), onClick = { go(next) }, modifier = wide, size = ButtonSize.LARGE)
                    else -> PrimaryButton("Done", onClick = actions.onFinish, modifier = wide, size = ButtonSize.LARGE)
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
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (step) {
                OnboardingStep.WELCOME -> WelcomeStep()
                OnboardingStep.PERMISSIONS -> PermissionsStep(setup)
                OnboardingStep.CONNECT -> ConnectStep(main)
                OnboardingStep.AGENT -> {
                    StepHeader(
                        Icons.Filled.Face,
                        "Add your first agent",
                        "Paste your connection URL into the agent, then tap Allow when this phone asks.",
                    )
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

/** "Step 2 of 4 · Permissions" with Skip, over a segmented brand-colored progress bar. */
@Composable
private fun WizardTopBar(step: OnboardingStep, onSkip: () -> Unit) {
    val total = OnboardingStep.entries.size
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Step ${step.number} of $total · ${step.title}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TertiaryButton("Skip", onClick = onSkip, modifier = Modifier.semantics { contentDescription = "Skip setup" })
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(end = 12.dp, top = 4.dp)
                .clearAndSetSemantics { contentDescription = "Step ${step.number} of $total" },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OnboardingStep.entries.forEach { entry ->
                val color = MaterialTheme.colorScheme.primary
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(if (entry.number <= step.number) color else color.copy(alpha = TINT_ALPHA), CircleShape),
                )
            }
        }
    }
}

/**
 * The hero of a wizard step: a big tinted icon, the step title and one sentence below it, centered.
 * [color] tints the icon (brand by default; green once a step is done).
 */
@Composable
internal fun StepHeader(icon: ImageVector, title: String, text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconBadge(icon, color, size = 64.dp)
        Spacer(Modifier.height(4.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun continueLabel(step: OnboardingStep, connected: Boolean): String = when (step) {
    OnboardingStep.CONNECT -> if (connected) "Continue" else "Skip for now"
    else -> "Continue"
}
