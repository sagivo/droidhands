package dev.phonebridge.core.screen

import dev.phonebridge.core.plugins.ActionResult
import dev.phonebridge.core.plugins.InstalledApp
import dev.phonebridge.core.plugins.IntegrationException
import dev.phonebridge.core.plugins.PluginRules
import dev.phonebridge.core.plugins.RenderedStep
import dev.phonebridge.core.plugins.RenderedStepAction
import dev.phonebridge.core.plugins.RenderedSteps
import dev.phonebridge.core.plugins.ScrollDirection
import dev.phonebridge.core.plugins.Selector
import dev.phonebridge.core.plugins.Step
import dev.phonebridge.core.tools.CallContext
import dev.phonebridge.core.tools.ToolException
import dev.phonebridge.core.util.OneTimeCodes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Runs screen recipes (docs/PLUGINS.md §6.4) on any [ScreenDriver], confined to the action's app, with the screen
 * tools' safety checks: the device's gesture lock (shared with [phoneUseTools] on the same driver), app access,
 * [CallContext.ensureStillAllowed] right before every gesture, and the final report (which leaves a blocked app it
 * finds in front).
 *
 * Selectors only see the app's own elements (its tree when it is in front, and overlays it owns), and before every
 * tap, long-press, type, scroll and back the app must be in the foreground; otherwise the recipe stops with an error
 * naming the app in front. Touches must not land on another process's overlay (a system alert) covering the element.
 * Every wait is bounded by its step's timeout and the whole recipe by [PluginRules.STEPS_TIMEOUT_MS]. A failing
 * `optional` step is skipped, unless the failure is a refusal (blocked app, stopped call, another app in front).
 */
class ScreenStepRunner(
    private val driver: ScreenDriver,
    private val appLabel: suspend (String) -> String? = { null },
    private val systemSurfaces: Set<String> = defaultSystemSurfaces(driver.os),
    private val timing: PhoneUseTiming = PhoneUseTiming(),
) {
    /**
     * Runs [steps] for [app]. Throws [dev.phonebridge.core.plugins.IntegrationException] naming the failed step when the
     * app doesn't do what the recipe expects, [ToolException] for refusals.
     */
    suspend fun run(steps: RenderedSteps, app: InstalledApp, ctx: CallContext): ActionResult {
        val startedAt = timing.clock()
        if (steps.packageName != app.packageName) throw ToolException(MISPINNED)
        ctx.appAccess.require(app.packageName, app.label)
        val ops = ScreenOps(driver, appLabel, systemSurfaces, timing)
        return ops.exclusive(ctx) {
            val first = ops.capture(ctx, mask = false)
            if (first.locked) throw ToolException("${ScreenTexts.LOCKED} Nothing was done.")
            // When the first step brings the app to the front, whatever is in front now is never touched.
            if (!StepText.startsByOpening(steps.steps)) first.blockedForeground?.let { ops.refuse(ctx, it) }
            val deadline = startedAt + PluginRules.STEPS_TIMEOUT_MS - DEADLINE_MARGIN_MS
            val run = StepRun(ops, ctx, app, StepText.count(steps.steps), deadline)
            run.runAll(steps.steps)
            val note = ops.report(ctx, run.summary(), null, ops.settled(ctx))
            ActionResult(
                fields = buildJsonObject {
                    put("steps_run", run.ran)
                    if (run.skipped.isNotEmpty()) putJsonArray("optional_steps_skipped") { run.skipped.forEach { add(JsonPrimitive(it)) } }
                },
                note = note,
            )
        }
    }

    private companion object {
        /** Leaves time under the action's own timeout to say which step was running. */
        const val DEADLINE_MARGIN_MS = 3_000L
    }
}

private const val MISPINNED = "Internal error: a step of this recipe is not pinned to its app, so it was not run."

/** A step did not work (element missing, timeout): an `optional` step may ignore it. */
private class StepFailure(message: String) : ToolException(message)

/** An `expect` step's own `fail` message: shown to the agent as written. */
private class RecipeFailure(message: String) : ToolException(message)

/** Another app is in front or covers the element, or the phone got locked: never ignored, even for an optional step. */
private class ConfinementFailure(message: String) : ToolException(message)

/** The final, already-worded failure of a step: the app didn't show what the recipe expects. */
private class StoppedAtStep(message: String) : IntegrationException(message)

/** One run of a recipe for [app]. */
private class StepRun(
    private val ops: ScreenOps,
    private val ctx: CallContext,
    private val app: InstalledApp,
    private val total: Int,
    private val deadline: Long,
) {
    private val pkg = app.packageName
    private val driver get() = ops.driver
    private val timing get() = ops.timing
    var ran = 0
        private set
    val skipped = ArrayList<String>()

    /** The last capture, for error messages. */
    private var last: Capture? = null

    /** The step running now (number and words), for refusals raised while waiting. */
    private var current: Pair<String, String> = "1" to ""

    suspend fun runAll(steps: List<RenderedStep>, parent: String? = null) {
        steps.forEachIndexed { i, step -> runStep(if (parent == null) "${i + 1}" else "$parent.${i + 1}", step) }
    }

    private suspend fun runStep(number: String, step: RenderedStep) {
        val what = StepText.describe(step.action)
        if (remaining() <= 0) throw StoppedAtStep(timeUp(number, what))
        current = number to what
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
                // A pause or a revoked agent still stops the recipe.
                ctx.ensureStillAllowed()
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
            is RenderedStepAction.Launch -> open(timeout) { driver.launchApp(pkg) }
            is RenderedStepAction.OpenUrl -> {
                if (a.url.packageName != pkg) throw ToolException(MISPINNED)
                open(timeout) { driver.openUrl(a.url.url) }
            }
            is RenderedStepAction.StartIntent -> throw StepFailure("intents are Android only, so this step can't run on ${driver.os.id}")
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

    // ---- steps -----------------------------------------------------------------------------------

    /** Runs [start] (launch or open a link) and waits until the app is in the foreground. */
    private suspend fun open(timeoutMs: Long, start: suspend () -> Unit) {
        ops.act(ctx) { start() }
        val until = timing.clock() + timeoutMs
        while (true) {
            val foreground = capture().foreground
            if (foreground == pkg) break
            if (timing.clock() >= until) {
                throw StepFailure("${app.label} did not come to the foreground within ${seconds(timeoutMs)} (foreground app: ${foreground ?: "unknown"})")
            }
            pause()
        }
        settle()
    }

    private suspend fun tap(number: String, selector: Selector, timeoutMs: Long, longPress: Boolean) {
        val (node, capture) = waitFor(selector, timeoutMs) ?: throw notFound(selector, timeoutMs)
        confine(number, (if (longPress) "long_press " else "tap ") + ElementSelector.describe(selector), capture)
        if (!node.el.enabled) throw StepFailure("${ElementSelector.describe(selector)} is disabled right now")
        touch(capture, node, ElementSelector.describe(selector), longPress)
        settle()
    }

    /** Taps the center of [node]'s visible part, which must be on the app's own window. */
    private suspend fun touch(capture: Capture, node: Node, what: String, longPress: Boolean = false) {
        val area = node.visibleBounds
        if (area.isEmpty) throw StepFailure("$what is not visible on screen")
        checkConfined(capture, area.centerX, area.centerY)
        ops.checkPoint(ctx, capture, area.centerX, area.centerY)
        ops.act(ctx) { driver.tap(area.centerX.toDouble(), area.centerY.toDouble(), if (longPress) ScreenOps.LONG_PRESS_MS else 0) }
    }

    private suspend fun type(number: String, a: RenderedStepAction.TypeText, timeoutMs: Long) {
        val (field, capture) = field(a.into, timeoutMs)
        val what = a.into?.let(ElementSelector::describe) ?: "the focused field"
        confine(number, "type into $what", capture)
        if (!field.el.focused) {
            touch(capture, field, "the text field $what")
            delay(timing.focusMs)
        }
        val value = field.el.value
        if (a.clear && (field.el.secure || (!value.isNullOrEmpty() && value != field.el.placeholder))) ops.act(ctx) { driver.clearText() }
        if (a.text.isNotEmpty()) ops.act(ctx) { driver.typeText(a.text) }
        if (a.submit) {
            ctx.ensureStillAllowed()
            try {
                driver.pressKey("return")
            } catch (e: ScreenException) {
                throw StepFailure("the text was typed, but pressing the keyboard's return key failed: ${e.message?.trimEnd('.')}")
            }
        }
        settle()
    }

    /** The field to type into: [into] (or a text field inside it), else the app's focused field, else its only one. */
    private suspend fun field(into: Selector?, timeoutMs: Long): Pair<Node, Capture> {
        if (into != null) {
            val (node, capture) = waitFor(into, timeoutMs) ?: throw notFound(into, timeoutMs)
            val target = if (node.el.editable) node else Queries.editableWithin(node)
            return (target ?: throw StepFailure("${ElementSelector.describe(into)} is not a text field")) to capture
        }
        val until = timing.clock() + timeoutMs
        while (true) {
            val capture = capture()
            when (val choice = Queries.chooseEditable(appNodes(capture).toList())) {
                is EditableChoice.Found -> return choice.node to capture
                is EditableChoice.Ambiguous -> throw StepFailure(
                    "no text field has focus and ${app.label} shows ${choice.candidates.size} of them; the recipe must say " +
                        "which one with 'into'",
                )
                EditableChoice.None -> Unit
            }
            if (timing.clock() >= until) throw StepFailure("${app.label} showed no text field within ${seconds(timeoutMs)}")
            pause()
        }
    }

    private suspend fun scroll(number: String, a: RenderedStepAction.Scroll, timeoutMs: Long) {
        val until = a.until
        if (until == null) {
            scrollOnce(number, a.direction, a.within, timeoutMs)
            return
        }
        var scrolls = 0
        var capture = capture()
        while (true) {
            if (ElementSelector.find(appRoots(capture), until, pkg) != null) return
            if (scrolls >= a.max || remaining() <= 0) {
                throw StepFailure("scrolled $scrolls times, but ${ElementSelector.describe(until)} did not show up")
            }
            val (atEnd, after) = scrollOnce(number, a.direction, a.within, timeoutMs)
            scrolls++
            capture = after
            if (atEnd) {
                if (ElementSelector.find(appRoots(after), until, pkg) != null) return
                throw StepFailure("reached the end of the list without finding ${ElementSelector.describe(until)}")
            }
        }
    }

    /** One swipe in [within] (or the app's largest scrollable); returns whether nothing moved, and the capture after. */
    private suspend fun scrollOnce(number: String, direction: ScrollDirection, within: Selector?, timeoutMs: Long): Pair<Boolean, Capture> {
        val (container, capture) = if (within != null) {
            val (node, c) = waitFor(within, timeoutMs) ?: throw notFound(within, timeoutMs)
            val target = if (node.el.scrollable) node else node.ancestors().firstOrNull { it.el.scrollable } ?: node
            (ElementSelector.describe(within) to target) to c
        } else {
            val c = capture()
            Queries.largestScrollable(appNodes(c))?.let { "the app's list" to it } to c
        }
        confine(number, "scroll ${direction.id}", capture)
        val before = Queries.topTexts(appNodes(capture), CONTENT_TEXTS, CONTENT_TEXT_LENGTH)
        ops.scrollWith(ctx, capture, direction, container) { x, y -> checkConfined(capture, x, y) }
        settle()
        val after = capture()
        return (Queries.topTexts(appNodes(after), CONTENT_TEXTS, CONTENT_TEXT_LENGTH) == before) to after
    }

    private suspend fun back(number: String) {
        if ("back" !in driver.globalActions) {
            throw StepFailure("${driver.os.id} has no system Back action; the recipe must tap the app's own back button instead")
        }
        confine(number, "back", capture())
        ops.act(ctx) { driver.globalAction("back") }
        settle()
    }

    /** `if_present`: whether [selector] is on screen now; a `timeout_ms` other than the default waits up to that long. */
    private suspend fun isPresent(selector: Selector, timeoutMs: Long): Boolean {
        settle()
        if (timeoutMs != Step.DEFAULT_TIMEOUT_MS) return waitFor(selector, minOf(timeoutMs, remaining())) != null
        return ElementSelector.find(appRoots(capture()), selector, pkg) != null
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** Polls the app's elements until [selector] matches, at most [timeoutMs] (and never past the recipe's deadline). */
    private suspend fun waitFor(selector: Selector, timeoutMs: Long): Pair<Node, Capture>? {
        val until = minOf(timing.clock() + timeoutMs, deadline)
        while (true) {
            val capture = capture()
            ElementSelector.find(appRoots(capture), selector, pkg)?.let { return it to capture }
            if (timing.clock() >= until) return null
            pause()
        }
    }

    /** What the agent may see now, unmasked (selectors match the real text; errors mask it again). */
    private suspend fun capture(): Capture = ops.capture(ctx, mask = false).also { last = it }

    /** The recipe's app's windows: its tree when it is in front, and overlays it owns. */
    private fun appRoots(capture: Capture): List<Node> = capture.windows.filter { it.owner == pkg }.map { it.root }

    private fun appNodes(capture: Capture): Sequence<Node> = appRoots(capture).asSequence().flatMap { it.walk() }.filter { it.visible }

    /** Throws unless the phone is unlocked and the recipe's app is in the foreground of [capture]. */
    private suspend fun confine(number: String, what: String, capture: Capture) {
        if (capture.locked) throw ConfinementFailure("Stopped before step $number ($what): ${ScreenTexts.LOCKED}")
        val foreground = capture.foreground
        if (foreground != pkg) {
            val who = foreground?.let { fg -> ops.label(fg)?.takeIf { it != fg }?.let { "$it ($fg)" } ?: fg }
                ?: "another screen (PhoneBridge could not tell which app)"
            throw ConfinementFailure(
                "Stopped before step $number ($what): $who is in the foreground instead of ${app.label}, and app actions only " +
                    "act inside their own app. Nothing was done there. Check the screen with screen_observe; ask the user if " +
                    "something is in the way.",
            )
        }
    }

    /** Throws unless a touch at ([x], [y]) lands on the app itself, not on another process's overlay (a system alert). */
    private fun checkConfined(capture: Capture, x: Int, y: Int) {
        if (capture.snapshot.overlays.any { it.owner != pkg && it.root.bounds.contains(x, y) }) {
            throw ConfinementFailure(
                "Stopped before touching ($x,$y): another window (a system alert or another app) covers the element. Nothing was done there.",
            )
        }
    }

    private suspend fun pause() {
        delay(timing.pollMs)
        ctx.ensureStillAllowed()
    }

    private suspend fun settle() = delay(timing.settleMs)

    private fun remaining(): Long = (deadline - timing.clock()).coerceAtLeast(0)

    /**
     * The failure when [selector] did not show up. When another app took the foreground, that is not the recipe's fault:
     * the refusal names the app in front (and is never skipped, even for an optional step).
     */
    private suspend fun notFound(selector: Selector, timeoutMs: Long): StepFailure {
        last?.let { if (it.foreground != pkg) confine(current.first, current.second, it) }
        return StepFailure("${ElementSelector.describe(selector)} did not show up in ${app.label} within ${seconds(timeoutMs)}. ${screenTail()}")
    }

    /** What the app shows (one-time codes masked if the user hides them), for errors. */
    private fun screenTail(): String {
        val texts = last?.let { Queries.topTexts(appNodes(it)) }.orEmpty().let { t -> if (ctx.redactOneTimeCodes) t.map(OneTimeCodes::redact) else t }
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

    fun summary(): String = buildString {
        append("Ran $ran ${if (ran == 1) "step" else "steps"} in ${app.label}")
        if (skipped.isNotEmpty()) append(" (optional ${if (skipped.size == 1) "step" else "steps"} ${skipped.joinToString(", ")} skipped)")
        append('.')
    }

    private companion object {
        const val CONTENT_TEXTS = 80
        const val CONTENT_TEXT_LENGTH = 80

        fun seconds(ms: Long): String = "${(ms + 999) / 1000} s"
    }
}

/**
 * Matches plugin [Selector]s (docs/PLUGINS.md §6.4) against [UiElement]s (pure). Only visible elements match; text
 * comparisons ignore case and surrounding whitespace; a text criterion that renders empty matches nothing, so a recipe
 * never taps an arbitrary element. `text`/`text_contains` look at the label or value, `desc`/`desc_contains` at the
 * label, `id` at the developer identifier (exactly, or the short name of a rendered `<app>:id/<name>`), `class_name`
 * at the type, `clickable` at [UiElement.actionable].
 */
internal object ElementSelector {
    fun matches(roots: List<Node>, selector: Selector, appId: String): List<Node> =
        roots.asSequence().flatMap { it.walk() }.filter { it.visible && matches(it.el, selector, appId) }.toList()

    /** The [Selector.index]th match, or null. */
    fun find(roots: List<Node>, selector: Selector, appId: String): Node? = matches(roots, selector, appId).getOrNull(selector.index)

    fun matches(e: UiElement, selector: Selector, appId: String): Boolean {
        val label = TextNorm.key(e.label)
        val value = TextNorm.key(if (e.secure) null else e.value)
        val checks = listOfNotNull(
            selector.id?.let { idMatches(e.identifier, it, appId) },
            selector.text?.let { v -> textCheck(v) { it == label || it == value } },
            selector.textContains?.let { v -> textCheck(v) { label.contains(it) || value.contains(it) } },
            selector.desc?.let { v -> textCheck(v) { it == label } },
            selector.descContains?.let { v -> textCheck(v) { label.contains(it) } },
            selector.textOrDesc?.let { v -> textCheck(v) { it == label || it == value } },
            selector.className?.let { v -> textCheck(v) { classMatches(e.type, it) } },
            selector.clickable?.let { e.actionable == it },
        )
        return checks.isNotEmpty() && checks.all { it }
    }

    private fun idMatches(identifier: String?, id: String, appId: String): Boolean {
        if (identifier.isNullOrEmpty()) return false
        val prefix = "$appId:id/"
        return identifier == id || (id.startsWith(prefix) && identifier == id.removePrefix(prefix))
    }

    /** `Button` matches type `Button`; `android.widget.Button` and `XCUIElementTypeButton` too. [key] is lowercase. */
    private fun classMatches(type: String, key: String): Boolean =
        key.substringAfterLast('.').removePrefix("xcuielementtype") == type.lowercase().removePrefix("xcuielementtype")

    private inline fun textCheck(value: String, test: (String) -> Boolean): Boolean {
        val key = TextNorm.key(value)
        return key.isNotEmpty() && test(key)
    }

    /** A short description for messages, e.g. `{id: send, desc: "Send"}`. */
    fun describe(selector: Selector): String {
        val parts = buildList {
            selector.id?.let { add("id: ${it.substringAfter(":id/")}") }
            selector.textOrDesc?.let { add("\"${short(it)}\"") }
            selector.text?.let { add("text: \"${short(it)}\"") }
            selector.textContains?.let { add("text_contains: \"${short(it)}\"") }
            selector.desc?.let { add("desc: \"${short(it)}\"") }
            selector.descContains?.let { add("desc_contains: \"${short(it)}\"") }
            selector.className?.let { add("class: $it") }
            selector.clickable?.let { add("clickable: $it") }
            if (selector.index > 0) add("index: ${selector.index}")
        }
        return "{${parts.joinToString(", ")}}"
    }

    private fun short(text: String) = TextNorm.truncate(TextNorm.clean(text), 40)
}

/** Recipe steps in words, and counts (pure). */
internal object StepText {
    /** Steps in total, nested `if_present` steps included. */
    fun count(steps: List<RenderedStep>): Int =
        steps.sumOf { s -> 1 + ((s.action as? RenderedStepAction.IfPresent)?.then?.let(::count) ?: 0) }

    /** True if the first step brings the app to the front. */
    fun startsByOpening(steps: List<RenderedStep>): Boolean = when (steps.firstOrNull()?.action) {
        is RenderedStepAction.Launch, is RenderedStepAction.OpenUrl, is RenderedStepAction.StartIntent -> true
        else -> false
    }

    /** A step in recipe words for messages, e.g. `tap {desc: "Send"}` (typed text and URLs left out). */
    fun describe(action: RenderedStepAction): String = when (action) {
        is RenderedStepAction.Launch -> if (action.fresh) "launch {fresh: true}" else "launch"
        is RenderedStepAction.OpenUrl -> "open_url ${action.url.url.substringBefore(':')}:…"
        is RenderedStepAction.StartIntent -> "intent ${action.intent.action}"
        is RenderedStepAction.WaitFor -> "wait_for ${ElementSelector.describe(action.selector)}"
        is RenderedStepAction.Tap -> "tap ${ElementSelector.describe(action.selector)}"
        is RenderedStepAction.LongPress -> "long_press ${ElementSelector.describe(action.selector)}"
        is RenderedStepAction.TypeText -> "type into ${action.into?.let(ElementSelector::describe) ?: "the focused field"}"
        is RenderedStepAction.Scroll -> "scroll ${action.direction.id}" + (action.until?.let { " until ${ElementSelector.describe(it)}" } ?: "")
        RenderedStepAction.Back -> "back"
        is RenderedStepAction.Sleep -> "sleep ${action.ms}"
        is RenderedStepAction.Expect -> "expect ${ElementSelector.describe(action.selector)}"
        is RenderedStepAction.IfPresent -> "if_present ${ElementSelector.describe(action.selector)}"
    }
}
