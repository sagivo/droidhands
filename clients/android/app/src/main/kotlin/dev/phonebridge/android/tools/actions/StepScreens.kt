package dev.phonebridge.android.tools.actions

import dev.phonebridge.android.access.NodeQueries
import dev.phonebridge.android.access.TextNorm
import dev.phonebridge.android.access.UiNode
import dev.phonebridge.android.access.WindowSnapshot
import dev.phonebridge.core.plugins.RenderedStep
import dev.phonebridge.core.plugins.RenderedStepAction
import dev.phonebridge.core.plugins.Selector

/**
 * Matches plugin [Selector]s (docs/PLUGINS.md §6.4) against captured trees (pure). Selectors are rendered:
 * text fields hold the call's values and ids are full (`com.app:id/send`). Only visible elements match.
 * Text comparisons ignore case and surrounding whitespace; a text criterion that renders empty (a missing
 * optional parameter) matches nothing, so a recipe never taps an arbitrary element.
 */
object SelectorMatcher {
    /** Every visible element of [roots] matching [selector], in reading order. */
    fun matches(roots: List<UiNode>, selector: Selector): List<UiNode> =
        NodeQueries.visibleNodes(roots).filter { matches(it, selector) }.toList()

    /** The [Selector.index]th match, or null. */
    fun find(roots: List<UiNode>, selector: Selector): UiNode? = matches(roots, selector).getOrNull(selector.index)

    /** True if [node] meets every criterion of [selector] (which must have at least one besides `index`). */
    fun matches(node: UiNode, selector: Selector): Boolean {
        val text = TextNorm.key(node.text)
        val desc = TextNorm.key(node.description)
        val checks = listOfNotNull(
            selector.id?.let { node.viewId == it },
            selector.text?.let { value -> textCheck(value) { it == text } },
            selector.textContains?.let { value -> textCheck(value) { text.contains(it) } },
            selector.desc?.let { value -> textCheck(value) { it == desc } },
            selector.descContains?.let { value -> textCheck(value) { desc.contains(it) } },
            selector.textOrDesc?.let { value -> textCheck(value) { it == text || it == desc } },
            selector.className?.let { value -> textCheck(value) { classMatches(node, it) } },
            selector.clickable?.let { node.clickable == it },
        )
        return checks.isNotEmpty() && checks.all { it }
    }

    /** [test] on [value]'s matching key; false when the key is empty. */
    private inline fun textCheck(value: String, test: (String) -> Boolean): Boolean {
        val key = TextNorm.key(value)
        return key.isNotEmpty() && test(key)
    }

    /** `EditText` matches `android.widget.EditText`; a dotted name must match the full class name. [key] is lowercase. */
    private fun classMatches(node: UiNode, key: String): Boolean {
        val full = node.className?.lowercase() ?: return false
        return if (key.contains('.')) full == key else node.simpleClassName.lowercase() == key
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

/** Where a screen recipe may act, decided before every tap, long-press, type, scroll and Back (pure). */
sealed interface Confinement {
    /** The recipe's app is in the foreground. */
    data object Inside : Confinement

    /** Another app ([foreground], null if unknown) is in front: the recipe stops instead of touching it. */
    data class Outside(val foreground: String?) : Confinement
}

/** The windows a screen recipe sees and its confinement to one app (docs/PLUGINS.md §6.4), pure. */
object StepScreens {
    /** Roots of [packageName]'s readable windows (its activities and dialogs; keyboards and other apps left out). */
    fun appRoots(windows: List<WindowSnapshot>, packageName: String): List<UiNode> =
        windows.filter { !it.hidden && it.packageName == packageName }.mapNotNull { it.root }

    /** [Confinement.Inside] only when [foreground] (the primary window's app) is [packageName]. */
    fun confinement(foreground: String?, packageName: String): Confinement =
        if (foreground != null && foreground == packageName) Confinement.Inside else Confinement.Outside(foreground)

    /**
     * The error when a step can't act because [outside] holds the foreground. [what] is the step ("tap {id: send}"),
     * [foregroundLabel] the foreground app's name, [own] true when it is PhoneBridge itself.
     */
    fun confinementMessage(what: String, appLabel: String, outside: Confinement.Outside, foregroundLabel: String?, own: Boolean): String {
        val who = when {
            own -> "PhoneBridge's own screen"
            outside.foreground == null -> "another screen (PhoneBridge could not tell which app)"
            else -> "${foregroundLabel ?: outside.foreground} (${outside.foreground})"
        }
        return "Stopped before $what: $who is in the foreground instead of $appLabel, and app actions only act inside their " +
            "own app. Nothing was done there. Check the screen with screen_read; ask the user if something is in the way."
    }

    /** Steps in total, nested `if_present` steps included. */
    fun count(steps: List<RenderedStep>): Int =
        steps.sumOf { s -> 1 + ((s.action as? RenderedStepAction.IfPresent)?.then?.let(::count) ?: 0) }

    /** True if the first step brings the app to the front (launch, open_url or intent). */
    fun startsByOpening(steps: List<RenderedStep>): Boolean = when (steps.firstOrNull()?.action) {
        is RenderedStepAction.Launch, is RenderedStepAction.OpenUrl, is RenderedStepAction.StartIntent -> true
        else -> false
    }

    /** A step in recipe words for messages, e.g. `tap {desc: "Send"}` (typed text and URLs left out). */
    fun describe(action: RenderedStepAction): String = when (action) {
        is RenderedStepAction.Launch -> if (action.fresh) "launch {fresh: true}" else "launch"
        is RenderedStepAction.OpenUrl -> "open_url ${action.url.url.substringBefore(':')}:…"
        is RenderedStepAction.StartIntent -> "intent ${action.intent.action}"
        is RenderedStepAction.WaitFor -> "wait_for ${SelectorMatcher.describe(action.selector)}"
        is RenderedStepAction.Tap -> "tap ${SelectorMatcher.describe(action.selector)}"
        is RenderedStepAction.LongPress -> "long_press ${SelectorMatcher.describe(action.selector)}"
        is RenderedStepAction.TypeText -> "type into ${action.into?.let(SelectorMatcher::describe) ?: "the focused field"}"
        is RenderedStepAction.Scroll -> "scroll ${action.direction.id}" + (action.until?.let { " until ${SelectorMatcher.describe(it)}" } ?: "")
        RenderedStepAction.Back -> "back"
        is RenderedStepAction.Sleep -> "sleep ${action.ms}"
        is RenderedStepAction.Expect -> "expect ${SelectorMatcher.describe(action.selector)}"
        is RenderedStepAction.IfPresent -> "if_present ${SelectorMatcher.describe(action.selector)}"
    }
}
