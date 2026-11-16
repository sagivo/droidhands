package dev.phonebridge.core.plugins

import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// The app plugin model (docs/PLUGINS.md). Built by PluginParser from YAML and checked by PluginValidator;
// discovered actions (§8) are built by the platform from what installed apps declare. Immutable.

/** Where a plugin comes from. */
enum class PluginSource {
    /** Reviewed in a pull request and shipped inside the app (`plugins/<id>/plugin.yaml`). */
    BUNDLED,

    /** Imported on the phone for testing (§10): unreviewed, so every action always needs approval. */
    LOCAL,
}

/** A plugin's maturity (§3). Agents are told when an integration is experimental. */
enum class PluginStatus {
    STABLE,
    BETA,
    EXPERIMENTAL,
    ;

    /** The YAML spelling, e.g. `experimental`. */
    val id: String get() = name.lowercase()
}

/** One app's plugin: `plugins/<id>/plugin.yaml` (§3). */
data class AppPlugin(
    /** `^[a-z][a-z0-9_]{1,39}$`, equal to the folder name. */
    val id: String,
    val name: String,
    val description: String,
    /** One of [PluginRules.CATEGORIES]. */
    val category: String,
    val homepage: String? = null,
    val status: PluginStatus = PluginStatus.EXPERIMENTAL,
    val maintainers: List<String> = emptyList(),
    val tested: List<TestedRecord> = emptyList(),
    /** Android packages this plugin applies to; the first installed one is used. */
    val androidPackages: List<String> = emptyList(),
    /** iOS bundle ids this plugin applies to (`ios.bundle_ids`); the first installed one is used. */
    val iosBundleIds: List<String> = emptyList(),
    val actions: List<PluginAction>,
    val source: PluginSource = PluginSource.BUNDLED,
) {
    /** The app ids this plugin applies to on [os]: [androidPackages] or [iosBundleIds]. */
    fun appIds(os: DeviceOs): List<String> = when (os) {
        DeviceOs.ANDROID -> androidPackages
        DeviceOs.IOS -> iosBundleIds
    }
}

/** Someone ran every action of a plugin on a device (§3 `tested`). */
data class TestedRecord(
    val appVersion: String? = null,
    val android: String? = null,
    val date: String? = null,
    val by: String? = null,
    /** The iOS version, for records from an iPhone. */
    val ios: String? = null,
)

/**
 * One action an agent can run in an app (§4). Plugin actions and discovered actions (§8) share this type;
 * the agent sees it as `<package>/<id>`.
 */
data class PluginAction(
    /** `^[a-z][a-z0-9_]{0,39}$`, unique within its app. */
    val id: String,
    val title: String,
    val description: String,
    val keywords: List<String> = emptyList(),
    /** Null only for [ToolRecipe] actions, which take the built-in tool's risk. */
    val risk: Risk? = null,
    /** The token scope required; null means [Scopes.APPS] (or, for [ToolRecipe], the built-in tool's). */
    val scope: String? = null,
    /** In declaration order. */
    val params: List<ParamSpec> = emptyList(),
    /** Approval prompt template (§4); null for the default "<App>: <title> (k=v, ...)". */
    val confirm: String? = null,
    /** Null for the default by recipe (see [completesByDefault]). */
    val completes: Boolean? = null,
    /** Null: no Android recipe, the action is skipped on Android (§4). */
    val android: Recipe? = null,
    /** Null: no iOS recipe, the action is skipped on iOS (§4). Never an [IntentRecipe]. */
    val ios: Recipe? = null,
    val examples: List<PluginExample> = emptyList(),
) {
    /** The scope a token needs for a non-[ToolRecipe] action. */
    val effectiveScope: String get() = scope ?: Scopes.APPS

    /** Whether the action finishes the task by itself (§4 `completes`), by its Android recipe if it has one. */
    val completesTask: Boolean get() = completes ?: completesByDefault(android ?: ios)

    /** Whether the action finishes the task by itself when it runs on [os] (§4 `completes`). */
    fun completesOn(os: DeviceOs): Boolean = completes ?: completesByDefault(recipeFor(os))

    /** The recipe that runs this action on [os], or null if it has none there (the action is skipped on that OS). */
    fun recipeFor(os: DeviceOs): Recipe? = when (os) {
        DeviceOs.ANDROID -> android
        DeviceOs.IOS -> ios
    }

    companion object {
        /** True for `tool` and `steps` recipes, false for `intent` and `url` (§4). */
        fun completesByDefault(recipe: Recipe?): Boolean = recipe is ToolRecipe || recipe is StepsRecipe
    }
}

/** Parameter value types (§5). */
enum class ParamType {
    STRING,
    INTEGER,
    NUMBER,
    BOOLEAN,
    ;

    /** The YAML / JSON Schema spelling, e.g. `integer`. */
    val id: String get() = name.lowercase()
}

/** One parameter of an action (§5). Constraints apply to [ParamType.STRING] (lengths, pattern) or numbers. */
data class ParamSpec(
    /** `^[a-z][a-z0-9_]{0,39}$`. */
    val name: String,
    val description: String,
    val type: ParamType = ParamType.STRING,
    val required: Boolean = false,
    /** Allowed values (strings or numbers), or null for any. */
    val enum: List<JsonPrimitive>? = null,
    /** Used when the agent leaves the parameter out. */
    val default: JsonPrimitive? = null,
    val minLength: Int? = null,
    /** Null means [DEFAULT_MAX_LENGTH] for strings. */
    val maxLength: Int? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    /** A regular expression the whole string must match. */
    val pattern: String? = null,
) {
    /** The maximum string length that applies. */
    val effectiveMaxLength: Int get() = maxLength ?: DEFAULT_MAX_LENGTH

    companion object {
        const val DEFAULT_MAX_LENGTH = 2000
        const val MAX_MAX_LENGTH = 10_000
    }
}

/**
 * How an action runs: exactly one recipe per OS section (§6). Android takes all four kinds; iOS takes `url`, `steps`
 * and `tool` (there are no intents on iOS).
 */
sealed interface Recipe {
    /** The YAML key, e.g. `intent`. */
    val kind: String
}

/** `intent`: start an activity in the app, always pinned to its package (§6.2). */
data class IntentRecipe(
    /** Literal. Call actions are refused. */
    val action: String,
    /** Literal MIME type. */
    val type: String? = null,
    /** URL-context template. */
    val data: String? = null,
    /** Literal. */
    val categories: List<String> = emptyList(),
    /** Literal; `.Relative` to the package, or a full class name in one of the plugin's packages. */
    val className: String? = null,
    /** In declaration order; keys are literal. */
    val extras: Map<String, ExtraSpec> = emptyMap(),
) : Recipe {
    override val kind: String get() = "intent"
}

/**
 * `url`: open a link in the app (§6.3, §6.6): on Android with ACTION_VIEW pinned to its package, on iOS only when the
 * installed app declares the link's scheme. [url] is a URL-context template.
 */
data class UrlRecipe(val url: String) : Recipe {
    override val kind: String get() = "url"
}

/** `steps`: a screen recipe confined to the app (§6.4). */
data class StepsRecipe(val steps: List<Step>) : Recipe {
    override val kind: String get() = "steps"
}

/** `tool`: run a built-in PhoneBridge tool as if the agent had called it (§6.5). */
data class ToolRecipe(
    val name: String,
    /** Argument name to value; keys are literal. */
    val args: Map<String, ValueSpec> = emptyMap(),
) : Recipe {
    override val kind: String get() = "tool"
}

/** Intent extra types (§6.2). */
enum class ExtraType {
    STRING,
    INT,
    LONG,
    DOUBLE,
    BOOLEAN,
    STRING_ARRAY,
    ;

    /** The YAML spelling, e.g. `string_array`. */
    val id: String get() = name.lowercase()
}

/** One intent extra: [value], converted to [type] when given (`{type: int, value: "{{hour}}"}`), else inferred. */
data class ExtraSpec(val value: ValueSpec, val type: ExtraType? = null)

/** An extra or tool-argument value before rendering (§6.1). */
sealed interface ValueSpec {
    /** A string template. When it is exactly one placeholder, the value takes the parameter's type. */
    data class Template(val template: String) : ValueSpec

    /** A YAML number or boolean literal, kept as is. */
    data class Literal(val value: JsonPrimitive) : ValueSpec

    /** A YAML list (a `string_array` extra, or a JSON array tool argument). */
    data class Items(val items: List<ValueSpec>) : ValueSpec
}

/** One step of a [StepsRecipe] (§6.4). [timeoutMs] null means [DEFAULT_TIMEOUT_MS]. */
data class Step(val action: StepAction, val optional: Boolean = false, val timeoutMs: Long? = null) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L
        const val MAX_TIMEOUT_MS = 15_000L
    }
}

/** What a [Step] does (§6.4). Strings marked as templates are rendered with the call's parameters. */
sealed interface StepAction {
    /** Opens the app's launcher screen; [fresh] clears its back stack first. */
    data class Launch(val fresh: Boolean = false) : StepAction

    /** Like the `url` recipe; [url] is a URL-context template. */
    data class OpenUrl(val url: String) : StepAction

    /** Like the `intent` recipe. Android only. */
    data class StartIntent(val intent: IntentRecipe) : StepAction

    data class WaitFor(val selector: Selector) : StepAction

    data class Tap(val selector: Selector) : StepAction

    data class LongPress(val selector: Selector) : StepAction

    /** Sets the text of [into] (null: the focused field); [text] is a template. */
    data class TypeText(val into: Selector?, val text: String, val clear: Boolean = true, val submit: Boolean = false) : StepAction

    data class Scroll(
        val direction: ScrollDirection,
        val within: Selector? = null,
        val until: Selector? = null,
        val max: Int = DEFAULT_MAX_SCROLLS,
    ) : StepAction {
        companion object {
            const val DEFAULT_MAX_SCROLLS = 5
            const val MAX_SCROLLS = 20
        }
    }

    /** Presses Back. Android only: iOS has no Back button. */
    data object Back : StepAction

    /** Waits [ms] (at most [MAX_MS]). */
    data class Sleep(val ms: Long) : StepAction {
        companion object {
            const val MAX_MS = 3_000L
        }
    }

    /** Fails the action with [fail] (null: a default message) unless [selector] shows up. */
    data class Expect(val selector: Selector, val fail: String? = null) : StepAction

    /** Runs [then] only if [selector] is on screen now. One level deep. */
    data class IfPresent(val selector: Selector, val then: List<Step>) : StepAction
}

enum class ScrollDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT,
    ;

    val id: String get() = name.lowercase()
}

/**
 * Matches screen elements in the plugin's app (§6.4). Text matches ignore case. [text], [textContains],
 * [desc], [descContains] and [textOrDesc] are templates in a recipe and rendered values after rendering.
 * On Android [id] is short (`search_src_text`) in a recipe and full (`com.spotify.music:id/search_src_text`) after;
 * on iOS it is an accessibility identifier, the same before and after (§6.6).
 */
data class Selector(
    val id: String? = null,
    val text: String? = null,
    val textContains: String? = null,
    val desc: String? = null,
    val descContains: String? = null,
    /** Simple class name, e.g. `EditText`; on iOS the element type, e.g. `Button`. */
    val className: String? = null,
    val clickable: Boolean? = null,
    /** Picks the nth match. */
    val index: Int = 0,
    /** The plain-string form (`tap: Send`): the element's text or description equals it. */
    val textOrDesc: String? = null,
)

/**
 * A test case run without a phone (§7). [params] go through validation and rendering of the action's recipe for [os]
 * (`os: ios` in YAML; Android by default); [expect] is compared.
 */
data class PluginExample(val params: JsonObject, val expect: ExampleExpectation, val os: DeviceOs = DeviceOs.ANDROID)

/** What an example expects (§7): exactly one of these. */
sealed interface ExampleExpectation {
    /** Only the listed keys of `{action, package, data, type, categories, class, extras}` are compared. Android only. */
    data class Intent(val fields: JsonObject) : ExampleExpectation

    data class Url(val url: String) : ExampleExpectation

    /** `{name, args}`. */
    data class Tool(val fields: JsonObject) : ExampleExpectation

    /** The rendered steps, as YAML step syntax. */
    data class Steps(val steps: List<JsonElement>) : ExampleExpectation

    /** Validation or rendering must fail with a message containing [substring] (case-insensitive). */
    data class Error(val substring: String) : ExampleExpectation
}
