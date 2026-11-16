package dev.phonebridge.android.tools.actions

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import dev.phonebridge.android.access.BridgeAccessibilityService
import dev.phonebridge.android.access.EditableChoice
import dev.phonebridge.android.access.NodeCapture
import dev.phonebridge.android.access.NodeQueries
import dev.phonebridge.android.access.UiNode
import dev.phonebridge.android.tools.screen.ScreenAccess
import dev.phonebridge.android.tools.screen.ScreenCapture
import dev.phonebridge.android.tools.screen.ScreenSession
import dev.phonebridge.android.tools.screen.ScrollDirection
import dev.phonebridge.android.tools.screen.ScrollOutcome
import dev.phonebridge.android.tools.screen.UiAutomation
import dev.phonebridge.android.tools.screen.scrollWith
import dev.phonebridge.android.tools.screen.tapNode
import dev.phonebridge.android.tools.screen.typeInto
import dev.phonebridge.android.tools.support.Accessibility
import dev.phonebridge.android.tools.support.ApprovalGuard
import dev.phonebridge.android.tools.system.LaunchGate
import dev.phonebridge.core.plugins.ActionRequest
import dev.phonebridge.core.plugins.ActionResult
import dev.phonebridge.core.plugins.InstalledApp
import dev.phonebridge.core.plugins.PluginRules
import dev.phonebridge.core.plugins.RenderedStep
import dev.phonebridge.core.plugins.RenderedStepAction
import dev.phonebridge.core.plugins.RenderedSteps
import dev.phonebridge.core.plugins.Selector
import dev.phonebridge.core.plugins.Step
import dev.phonebridge.core.tools.CallContext
import dev.phonebridge.core.tools.ToolException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import dev.phonebridge.core.plugins.IntegrationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** A step did not work (element missing, timeout): an `optional` step may ignore it. */
private class StepFailure(message: String) : ToolException(message)

/** An `expect` step's own `fail` message: shown to the agent as written. */
private class RecipeFailure(message: String) : ToolException(message)

/** Another app is in front: never ignored, even for an optional step. */
private class ConfinementFailure(message: String) : ToolException(message)

/**
 * The final, already-worded failure of a step (nested steps report theirs through their `if_present`): the app didn't
 * show what the recipe expects, which [dev.phonebridge.core.plugins.ActionHealth] records for this app version.
 */
private class StoppedAtStep(message: String) : IntegrationException(message)

/**
 * Runs a screen recipe (docs/PLUGINS.md §6.4) with the screen tools' safety checks: the UI mutex
 * ([UiAutomation.exclusive]), "unlock to continue", app access, [UiAutomation.beforeAction] right before every
 * gesture and node action (so a pending approval, a pause or a revoked agent stops it), the
 * [dev.phonebridge.android.tools.screen.ScreenGuard] checks of the shared tap, type and scroll helpers, and the
 * final [UiAutomation.report] (which leaves a blocked app it finds in front).
 *
 * Selectors only see the windows of the recipe's app, and before every tap, long-press, type, scroll and Back the
 * app must be in the foreground ([StepScreens.confinement]); otherwise the recipe stops with an error naming the
 * app in front. Coordinate gestures (a tap's fallback, scroll swipes) must also land on the app's own window, not on a
 * keyboard, notification or other app covering the element. There is no Home or notification-shade step. Every wait is bounded by its step's timeout and the
 * whole recipe by [PluginRules.STEPS_TIMEOUT_MS]. A failing `optional` step is skipped, unless the failure is a
 * refusal (blocked app, stopped call, another app in front) or the safety checks no longer pass.
 */
internal class StepRunner private constructor(
    private val context: Context,
    private val service: BridgeAccessibilityService,
    private val access: ScreenAccess,
    private val ctx: CallContext,
    private val app: InstalledApp,
    private val total: Int,
    private val deadline: Long,
) {
    private val pkg = app.packageName
    private var ran = 0
    private val skipped = ArrayList<String>()

    /** The app's readable roots at the last capture, for error messages. */
    private var lastRoots: List<UiNode> = emptyList()

    private suspend fun runAll(steps: List<RenderedStep>, parent: String? = null) {
        steps.forEachIndexed { i, step -> runStep(if (parent == null) "${i + 1}" else "$parent.${i + 1}", step) }
    }

    private suspend fun runStep(number: String, step: RenderedStep) {
        val what = StepScreens.describe(step.action)
        if (remaining() <= 0) throw StoppedAtStep(timeUp(number, what))
        try {
            perform(number, step)
            ran++
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolException) {
            val stepFailure = e is StepFailure || e is RecipeFailure || e is StoppedAtStep || e.javaClass == ToolException::class.java
            // Refusals (blocked app, stopped call, another app in front) are never ignored or reworded.
            if (!stepFailure) throw e
            if (step.optional) {
                // A pause, a revoked agent or a waiting approval still stops the recipe.
                UiAutomation.beforeAction(access)
                skipped += number
                return
            }
            if (e is StoppedAtStep) throw e
            throw StoppedAtStep(failure(number, what, e))
        }
    }

    private suspend fun perform(number: String, step: RenderedStep) {
        val timeout = minOf(step.timeoutMs, remaining()).coerceAtLeast(1)
        when (val a = step.action) {
            is RenderedStepAction.Launch -> open(IntentPlans.launcher(pkg, launcherClass(), a.fresh), timeout)
            is RenderedStepAction.OpenUrl -> open(IntentPlans.of(a.url), timeout)
            is RenderedStepAction.StartIntent -> open(IntentPlans.of(a.intent), timeout)
            is RenderedStepAction.WaitFor -> waitFor(a.selector, timeout) ?: throw notFound(a.selector, timeout)
            is RenderedStepAction.Tap -> tap(number, a.selector, timeout, longPress = false)
            is RenderedStepAction.LongPress -> tap(number, a.selector, timeout, longPress = true)
            is RenderedStepAction.TypeText -> type(number, a, timeout)
            is RenderedStepAction.Scroll -> scroll(number, a, timeout)
            RenderedStepAction.Back -> back(number)
            is RenderedStepAction.Sleep -> {
                delay(minOf(a.ms, remaining()))
                ctx.ensureStillAllowed()
            }
            is RenderedStepAction.Expect -> waitFor(a.selector, timeout) ?: throw RecipeFailure(a.fail)
            is RenderedStepAction.IfPresent -> if (isPresent(a.selector, step.timeoutMs)) runAll(a.then, number)
        }
    }

    // ---- steps -------------------------------------------------------------------------------

    /** Starts [plan] in the app and waits until the app is in the foreground. */
    private suspend fun open(plan: IntentPlan, timeoutMs: Long) {
        if (plan.packageName != pkg) throw ToolException(MISPINNED)
        UiAutomation.beforeAction(access)
        BridgeAccessibilityService.expectUiChange()
        ActionIntents.start(context, plan, ctx, app.label)
        val until = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            val seen = BridgeAccessibilityService.lastEventAt.value
            val foreground = UiAutomation.foregroundPackage(service)
            if (foreground == pkg) break
            if (SystemClock.uptimeMillis() >= until) {
                throw StepFailure("${app.label} did not come to the foreground within ${seconds(timeoutMs)} (foreground app: ${foreground ?: "unknown"})")
            }
            pause(seen)
        }
        settle()
    }

    private suspend fun launcherClass(): String =
        withContext(Dispatchers.IO) { ActionIntents.launcherClass(context, pkg) }
            ?: throw StepFailure("${app.label} has no screen that can be opened from the launcher")

    private suspend fun tap(number: String, selector: Selector, timeoutMs: Long, longPress: Boolean) {
        val node = waitFor(selector, timeoutMs) ?: throw notFound(selector, timeoutMs)
        confine(number, (if (longPress) "long_press " else "tap ") + SelectorMatcher.describe(selector))
        UiAutomation.requireUnlocked(context)
        val live = live(node) ?: throw StepFailure("${SelectorMatcher.describe(selector)} disappeared before it could be tapped")
        tapNode(context, service, access, live, longPress, confineTo = pkg)
        settle()
    }

    private suspend fun type(number: String, a: RenderedStepAction.TypeText, timeoutMs: Long) {
        val field = field(a.into, timeoutMs)
        confine(number, "type into " + (a.into?.let(SelectorMatcher::describe) ?: "the focused field"))
        UiAutomation.requireUnlocked(context)
        val typed = typeInto(context, access, field, a.text, append = !a.clear, submit = a.submit)
        if (a.submit && typed.submitted != true) {
            throw StepFailure("the text was typed, but the field has no keyboard action key to submit it")
        }
        settle()
    }

    /** The field to type into: [into] (or a text field inside it), else the app's focused field, else its only one. */
    private suspend fun field(into: Selector?, timeoutMs: Long): ScreenSession.Live {
        if (into != null) {
            val node = waitFor(into, timeoutMs) ?: throw notFound(into, timeoutMs)
            val target = if (node.editable) node else NodeQueries.editableWithin(node)
            target ?: throw StepFailure("${SelectorMatcher.describe(into)} is not a text field")
            return live(target) ?: throw StepFailure("the text field ${SelectorMatcher.describe(into)} disappeared")
        }
        val until = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            val seen = BridgeAccessibilityService.lastEventAt.value
            focusedField()?.let { return it }
            when (val choice = NodeQueries.chooseEditable(null, appRoots())) {
                is EditableChoice.Found -> live(choice.node)?.let { return it }
                is EditableChoice.Ambiguous -> throw StepFailure(
                    "no text field has input focus and ${app.label} shows ${choice.candidates.size} of them; the recipe must " +
                        "say which one with 'into'",
                )
                EditableChoice.None -> Unit
            }
            if (SystemClock.uptimeMillis() >= until) throw StepFailure("${app.label} showed no text field within ${seconds(timeoutMs)}")
            pause(seen)
        }
    }

    /** The app's field with input focus, if any. */
    private suspend fun focusedField(): ScreenSession.Live? = withContext(Dispatchers.IO) {
        val node = try {
            service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let(NodeCapture::captureShallow)
        } catch (e: RuntimeException) {
            null
        }
        node?.takeIf { it.editable && it.packageName == pkg }?.let { n ->
            val shown = access.redact(n)
            ScreenSession.Live(ScreenSession.refs(access).register(shown), shown, shown)
        }
    }

    private suspend fun scroll(number: String, a: RenderedStepAction.Scroll, timeoutMs: Long) {
        val direction = ScrollDirection.valueOf(a.direction.name)
        val until = a.until ?: return run { scrollOnce(number, direction, a.within, timeoutMs) }
        var scrolls = 0
        while (true) {
            if (SelectorMatcher.find(appRoots(), until) != null) return
            if (scrolls >= a.max || remaining() <= 0) {
                throw StepFailure("scrolled $scrolls times, but ${SelectorMatcher.describe(until)} did not show up")
            }
            val outcome = scrollOnce(number, direction, a.within, timeoutMs)
            scrolls++
            if (outcome.atEnd) {
                if (SelectorMatcher.find(appRoots(), until) != null) return
                throw StepFailure("reached the end of the list without finding ${SelectorMatcher.describe(until)}")
            }
        }
    }

    private suspend fun scrollOnce(number: String, direction: ScrollDirection, within: Selector?, timeoutMs: Long): ScrollOutcome {
        val container = if (within != null) {
            val node = waitFor(within, timeoutMs) ?: throw notFound(within, timeoutMs)
            val target = if (NodeQueries.isScrollContainer(node)) node else node.ancestors().firstOrNull(NodeQueries::isScrollContainer) ?: node
            live(target) ?: throw StepFailure("${SelectorMatcher.describe(within)} disappeared before it could be scrolled")
        } else {
            NodeQueries.largestScrollable(appRoots())?.let { live(it) }
        }
        confine(number, "scroll ${direction.name.lowercase()}")
        UiAutomation.requireUnlocked(context)
        val outcome = scrollWith(context, service, access, direction, container, confineTo = pkg)
        settle()
        return outcome
    }

    private suspend fun back(number: String) {
        confine(number, "back")
        UiAutomation.beforeAction(access)
        if (!service.performGlobal(AccessibilityService.GLOBAL_ACTION_BACK)) throw StepFailure("Android did not press Back")
        settle()
    }

    /**
     * `if_present`: whether [selector] is on screen now, once the screen has settled. A `timeout_ms` other than the
     * default makes it wait up to that long.
     */
    private suspend fun isPresent(selector: Selector, timeoutMs: Long): Boolean {
        settle()
        if (timeoutMs != Step.DEFAULT_TIMEOUT_MS) return waitFor(selector, minOf(timeoutMs, remaining())) != null
        return SelectorMatcher.find(appRoots(), selector) != null
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** Polls the app's windows until [selector] matches, at most [timeoutMs] (and never past the recipe's deadline). */
    private suspend fun waitFor(selector: Selector, timeoutMs: Long): UiNode? {
        val until = minOf(SystemClock.uptimeMillis() + timeoutMs, deadline)
        while (true) {
            val seen = BridgeAccessibilityService.lastEventAt.value
            SelectorMatcher.find(appRoots(), selector)?.let { return it }
            if (SystemClock.uptimeMillis() >= until) return null
            pause(seen)
        }
    }

    /** The recipe's app's readable windows now (the agent's app access applies; nothing is masked for matching). */
    private suspend fun appRoots(): List<UiNode> {
        if (BridgeAccessibilityService.instance == null) throw ToolException(Accessibility.NOT_ENABLED)
        val state = ScreenCapture.capture(context, service, access.visibility)
        return StepScreens.appRoots(state.windows, pkg).also { lastRoots = it }
    }

    /** [node] re-read from the live UI and registered among the agent's element refs, or null when it is gone. */
    private suspend fun live(node: UiNode): ScreenSession.Live? = withContext(Dispatchers.IO) {
        ScreenSession.refresh(node, access)?.let { fresh -> ScreenSession.Live(ScreenSession.refs(access).register(node), node, fresh) }
    }

    /** Throws unless the recipe's app is in the foreground (see [StepScreens.confinement]). */
    private suspend fun confine(number: String, what: String) {
        val foreground = UiAutomation.foregroundPackage(service)
        val decision = StepScreens.confinement(foreground, pkg)
        if (decision is Confinement.Outside) {
            val label = decision.foreground?.let { withContext(Dispatchers.IO) { access.label(it) } }
            throw ConfinementFailure(
                StepScreens.confinementMessage("step $number ($what)", app.label, decision, label, own = foreground == context.packageName),
            )
        }
    }

    private suspend fun pause(sinceEvent: Long) {
        BridgeAccessibilityService.awaitEvent(sinceEvent, POLL_MS)
        delay(MIN_GAP_MS)
        ctx.ensureStillAllowed()
    }

    private suspend fun settle() = BridgeAccessibilityService.awaitIdle(minDelayMs = SETTLE_MIN_MS)

    private fun remaining(): Long = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)

    private fun notFound(selector: Selector, timeoutMs: Long) =
        StepFailure("${SelectorMatcher.describe(selector)} did not show up in ${app.label} within ${seconds(timeoutMs)}. ${screenTail()}")

    /** What the app shows (one-time codes masked if the user hides them), for errors. */
    private fun screenTail(): String {
        val texts = NodeQueries.topTexts(lastRoots).let { t -> access.redactor?.let(t::map) ?: t }
        return if (texts.isEmpty()) "${app.label} shows no readable text." else "${app.label} shows: " + texts.joinToString(" | ")
    }

    private fun failure(number: String, what: String, e: ToolException): String {
        val reason = e.message.orEmpty().trim().trimEnd('.')
        val head = if (e is RecipeFailure) "$reason (step $number of $total: $what)." else "Step $number of $total ($what) failed: $reason."
        val progress = if (ran == 0) {
            ""
        } else {
            " $ran earlier ${if (ran == 1) "step" else "steps"} ran, so ${app.label} may be partway through the task: check with " +
                "screen_observe before retrying."
        }
        return head + progress
    }

    private fun timeUp(number: String, what: String) =
        "${app.label}'s screen recipe ran out of time (${PluginRules.STEPS_TIMEOUT_MS / 1000} s) before step $number of $total ($what). " +
            "$ran steps ran: check the screen with screen_observe."

    private fun summary(): String = buildString {
        append("Ran $ran ${if (ran == 1) "step" else "steps"} in ${app.label}")
        if (skipped.isNotEmpty()) append(" (optional ${if (skipped.size == 1) "step" else "steps"} ${skipped.joinToString(", ")} skipped)")
        append('.')
    }

    companion object {
        private const val POLL_MS = 250L
        private const val MIN_GAP_MS = 100L
        private const val SETTLE_MIN_MS = 150L

        /** Leaves time under the action's own timeout to say which step was running. */
        private const val DEADLINE_MARGIN_MS = 3_000L

        /** How long a recipe waits for an approval screen to close before its first step. */
        private const val APPROVAL_CLOSE_WAIT_MS = 3_000L

        private const val MISPINNED = "Internal error: a step of this recipe is not pinned to its app, so it was not run."

        /** Runs [recipe] for [request] (its app is [ActionRequest.app]); see the class documentation. */
        suspend fun run(context: Context, request: ActionRequest, recipe: RenderedSteps, ctx: CallContext): ActionResult {
            val startedAt = SystemClock.uptimeMillis()
            val app = request.app
            val service = UiAutomation.service()
            val access = withContext(Dispatchers.IO) {
                LaunchGate.requireApp(context, ctx.appAccess, app.packageName)
                ScreenAccess.of(context, ctx)
            }
            UiAutomation.awaitUsable(context, ctx, "use ${app.label} (${request.action.title})", nothingDone = "Nothing was done.")
            return UiAutomation.exclusive(access) {
                // Right after the user approved this action, the approval screen may still be closing: wait for it
                // (never act during it: every step's beforeAction still refuses while an approval is pending or shown).
                withTimeoutOrNull(APPROVAL_CLOSE_WAIT_MS) { ApprovalGuard.active.first { !it } }
                UiAutomation.requireUnlocked(context)
                if (StepScreens.startsByOpening(recipe.steps)) {
                    // The first step brings the app to the front, so whatever is in front now is never touched.
                    withContext(Dispatchers.IO) { service.rootNodesOrStuck() }
                } else {
                    UiAutomation.requireOperable(service, access)
                }
                val deadline = startedAt + PluginRules.STEPS_TIMEOUT_MS - DEADLINE_MARGIN_MS
                val runner = StepRunner(context, service, access, ctx, app, StepScreens.count(recipe.steps), deadline)
                runner.runAll(recipe.steps)
                val note = UiAutomation.report(service, access, runner.summary())
                ActionResult(
                    fields = buildJsonObject {
                        put("steps_run", runner.ran)
                        if (runner.skipped.isNotEmpty()) putJsonArray("optional_steps_skipped") { runner.skipped.forEach { add(JsonPrimitive(it)) } }
                    },
                    note = note,
                )
            }
        }

        private fun seconds(ms: Long): String = "${(ms + 999) / 1000} s"
    }
}
