package com.droidhands.android.ui.onboarding

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
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
import com.droidhands.android.graph.DefaultRelay
import com.droidhands.android.ui.MainViewModel
import com.droidhands.android.ui.components.BottomActionBar
import com.droidhands.android.ui.components.ButtonSize
import com.droidhands.android.ui.components.IconBadge
import com.droidhands.android.ui.components.PrimaryButton
import com.droidhands.android.ui.components.SecondaryButton
import com.droidhands.android.ui.components.TertiaryButton
import com.droidhands.android.ui.connect.ConnectAgentContent
import com.droidhands.android.ui.setup.SetupViewModel
import com.droidhands.android.ui.theme.TINT_ALPHA
import com.droidhands.android.ui.theme.statusColors
import com.droidhands.core.transport.RelayStatus

/** Navigation callbacks of the onboarding wizard. */
class OnboardingActions(
    /** Finished or skipped: the wizard doesn't come back by itself. */
    val onFinish: () -> Unit,
    /** "Use a token instead" (opens the manual token flow above the wizard). */
    val onUseToken: () -> Unit,
)

/**
 * First-run wizard, full screen: Welcome → Permissions → (Connect) → Add your first agent. Every
 * step can be skipped, and the whole wizard can be re-opened from Settings > About.
 */
@Composable
fun OnboardingScreen(main: MainViewModel, setup: SetupViewModel, actions: OnboardingActions) {
    var stepName by rememberSaveable { mutableStateOf(OnboardingStep.WELCOME.name) }
    val step = OnboardingStep.fromName(stepName)
    val relay by main.relayStatus.collectAsStateWithLifecycle()
    val settings by main.settings.collectAsStateWithLifecycle()
    val setupState by setup.state.collectAsStateWithLifecycle()
    val connected = relay is RelayStatus.Connected || settings.localServerEnabled
    val permissionsReady = setupState.loaded && OnboardingFlow.essentialsDone(setupState.items)
    val tokens by main.tokens.collectAsStateWithLifecycle()
    // The wizard only shows before the first agent, so any agent now is the one just added.
    val agentAdded = tokens.any { !it.revoked }
    val steps = OnboardingFlow.steps(defaultRelayInUse = DefaultRelay.inUse(settings))
    val go: (OnboardingStep) -> Unit = { stepName = it.name }
    val previousStep = OnboardingFlow.previous(step, steps)
    previousStep?.let { previous -> BackHandler { go(previous) } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { WizardTopBar(step, steps, onSkip = actions.onFinish) },
        bottomBar = {
            BottomActionBar {
                previousStep?.let { previous -> TertiaryButton("Back", onClick = { go(previous) }) }
                val next = OnboardingFlow.next(step, steps)
                val wide = Modifier.weight(1f)
                when {
                    step == OnboardingStep.WELCOME ->
                        PrimaryButton("Get started", onClick = { go(OnboardingStep.PERMISSIONS) }, size = ButtonSize.LARGE, fullWidth = true)
                    // Keep one primary action per screen: while permissions aren't ready, the next Allow row is primary.
                    next != null && step == OnboardingStep.PERMISSIONS && !permissionsReady ->
                        SecondaryButton("Continue", onClick = { go(next) }, modifier = wide, size = ButtonSize.LARGE)
                    // Not online yet: moving on is skipping, so it doesn't get the primary look.
                    next != null && step == OnboardingStep.CONNECT && !connected ->
                        SecondaryButton(continueLabel(step, connected), onClick = { go(next) }, modifier = wide, size = ButtonSize.LARGE)
                    next != null ->
                        PrimaryButton(continueLabel(step, connected), onClick = { go(next) }, modifier = wide, size = ButtonSize.LARGE)
                    // No agent yet: sending the message is the main action, finishing is the way out.
                    !agentAdded -> SecondaryButton("Finish later", onClick = actions.onFinish, modifier = wide, size = ButtonSize.LARGE)
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
                OnboardingStep.CONNECT -> ConnectStep(main, expandMoreInitially = OnboardingStep.CONNECT !in steps)
                OnboardingStep.AGENT -> {
                    if (agentAdded) {
                        StepHeader(Icons.Filled.CheckCircle, "You're all set", "Ask your agent to do something on this phone.", color = statusColors().ok)
                    } else {
                        StepHeader(
                            Icons.AutoMirrored.Filled.Send,
                            "Connect your first agent",
                            "Copy or share the message below with any AI agent, then tap Allow here.",
                        )
                    }
                    ConnectAgentContent(
                        main = main,
                        onUseToken = actions.onUseToken,
                        onOpenConnectionSettings = { go(OnboardingStep.CONNECT) },
                        onConnected = null,
                    )
                    if (OnboardingStep.CONNECT !in steps && !agentAdded) {
                        // The Connect step was left out (the phone went online by itself); its other routes stay one tap away.
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            TertiaryButton("More ways to connect", onClick = { go(OnboardingStep.CONNECT) })
                        }
                    }
                }
            }
        }
    }
}

/** Slim segmented progress bar with Skip on the right. */
@Composable
private fun WizardTopBar(step: OnboardingStep, steps: List<OnboardingStep>, onSkip: () -> Unit) {
    val total = steps.size
    // A step outside the walk (Connect, opened from the agent step) shows the agent step's place.
    val number = (steps.indexOf(step).takeIf { it >= 0 } ?: steps.indexOf(OnboardingStep.AGENT)) + 1
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .clearAndSetSemantics { contentDescription = "Step $number of $total · ${step.title}" },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            steps.forEachIndexed { index, _ ->
                val color = MaterialTheme.colorScheme.primary
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(if (index + 1 <= number) color else color.copy(alpha = TINT_ALPHA), CircleShape),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        TertiaryButton("Skip", onClick = onSkip, modifier = Modifier.semantics { contentDescription = "Skip setup" })
    }
}

/**
 * The hero of a wizard step: a tinted icon badge, the step title and one sentence below it, centered.
 * [color] tints the icon (brand by default; green once a step is done).
 */
@Composable
internal fun StepHeader(icon: ImageVector, title: String, text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconBadge(icon, color, size = 56.dp)
        Spacer(Modifier.height(2.dp))
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
