package dev.phonebridge.core.plugins

import kotlinx.serialization.json.JsonObject

// Recipes after rendering with one call's parameters (RecipeRenderer): platform-neutral and ready to run.
// Every rendered intent, link and step sequence is pinned to [packageName], the app's installed package (on iOS, its
// bundle id).

/** A rendered [Recipe]. */
sealed interface RenderedRecipe

/**
 * An activity start pinned to [packageName] (docs/PLUGINS.md §6.2). Never a call action; the platform
 * adds no URI-permission flags. [className] is a full class name (relative names already resolved).
 */
data class RenderedIntent(
    val action: String,
    val packageName: String,
    val data: String? = null,
    val type: String? = null,
    val categories: List<String> = emptyList(),
    val className: String? = null,
    /** In declaration order. Extras whose value was a missing optional parameter are left out. */
    val extras: Map<String, ExtraValue> = emptyMap(),
) : RenderedRecipe

/**
 * A link that only [packageName] may open; no browser fallback (§6.3). On Android an ACTION_VIEW (with
 * CATEGORY_BROWSABLE) pinned to the package; on iOS [packageName] is the bundle id, and the platform opens the link only
 * if that app declares its scheme (§6.6).
 */
data class RenderedUrl(val url: String, val packageName: String) : RenderedRecipe

/** A screen recipe confined to [packageName] (§6.4). */
data class RenderedSteps(val packageName: String, val steps: List<RenderedStep>) : RenderedRecipe

/** A built-in tool call (§6.5): the server runs [name] with [args] as if the agent had called it. */
data class RenderedToolCall(val name: String, val args: JsonObject) : RenderedRecipe

/** A typed intent extra value. */
sealed interface ExtraValue {
    data class StringValue(val value: String) : ExtraValue

    data class IntValue(val value: Int) : ExtraValue

    data class LongValue(val value: Long) : ExtraValue

    data class DoubleValue(val value: Double) : ExtraValue

    data class BooleanValue(val value: Boolean) : ExtraValue

    data class StringArrayValue(val values: List<String>) : ExtraValue
}

/** A rendered [Step]: [timeoutMs] is the effective timeout. */
data class RenderedStep(val action: RenderedStepAction, val optional: Boolean = false, val timeoutMs: Long = Step.DEFAULT_TIMEOUT_MS)

/** A rendered [StepAction]. Selectors carry rendered text and full view ids (iOS: accessibility identifiers as written). */
sealed interface RenderedStepAction {
    data class Launch(val fresh: Boolean = false) : RenderedStepAction

    data class OpenUrl(val url: RenderedUrl) : RenderedStepAction

    data class StartIntent(val intent: RenderedIntent) : RenderedStepAction

    data class WaitFor(val selector: Selector) : RenderedStepAction

    data class Tap(val selector: Selector) : RenderedStepAction

    data class LongPress(val selector: Selector) : RenderedStepAction

    data class TypeText(val into: Selector?, val text: String, val clear: Boolean = true, val submit: Boolean = false) : RenderedStepAction

    data class Scroll(
        val direction: ScrollDirection,
        val within: Selector? = null,
        val until: Selector? = null,
        val max: Int = StepAction.Scroll.DEFAULT_MAX_SCROLLS,
    ) : RenderedStepAction

    data object Back : RenderedStepAction

    data class Sleep(val ms: Long) : RenderedStepAction

    /** [fail] is the message for the agent when [selector] doesn't show up. */
    data class Expect(val selector: Selector, val fail: String) : RenderedStepAction

    data class IfPresent(val selector: Selector, val then: List<RenderedStep>) : RenderedStepAction
}
