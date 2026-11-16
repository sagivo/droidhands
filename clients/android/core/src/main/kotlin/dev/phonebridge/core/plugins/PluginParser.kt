package dev.phonebridge.core.plugins

import dev.phonebridge.core.tools.Risk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.util.regex.PatternSyntaxException

/** One problem in a plugin file. [path] locates it, e.g. `actions[1].android.intent.extras.count` ("" = the whole file). */
data class PluginProblem(val path: String, val message: String) {
    override fun toString(): String = if (path.isEmpty()) message else "$path: $message"
}

/**
 * Turns a plugin file's YAML data ([PluginYaml.load]) into an [AppPlugin] (docs/PLUGINS.md §3-§7), checking
 * shapes, types, unknown keys (typos are errors) and every rule about a single field: formats, lengths,
 * counts and allowed values. Rules that relate several fields (risk floors, templates against parameters,
 * recipe safety, folder name) are [PluginValidator]'s. Pure; one instance per file.
 *
 * Problems that leave the data usable (an unknown key, a name that is too long) are recorded and parsing
 * goes on, so [parse] still returns the plugin and the validator can report more in the same run.
 * Structural problems (a wrong type, a missing required field) make [parse] return null.
 */
internal class PluginParser(private val source: PluginSource) {
    val problems = ArrayList<PluginProblem>()

    /** Structural problems so far: an element could not be built. */
    private var broken = 0

    private fun problem(path: String, message: String) {
        problems += PluginProblem(path, message)
    }

    /** A problem that leaves the element unusable. */
    private fun broken(path: String, message: String) {
        broken++
        problem(path, message)
    }

    /**
     * The plugin, or null after a structural problem. Check [problems] either way: a returned plugin may
     * still have problems.
     */
    fun parse(root: JsonElement): AppPlugin? {
        val o = root as? JsonObject ?: run {
            broken("", "a plugin file must be a map of fields (id, name, description, category, android, actions)")
            return null
        }
        known(o, "", PLUGIN_KEYS)
        val id = text(o, "", "id", required = true)
        if (id != null && !PluginRules.PLUGIN_ID.matches(id)) {
            problem("id", "'$id' is not a valid plugin id: use 2-40 lowercase letters, digits and _, starting with a letter")
        }
        val name = text(o, "", "name", required = true, max = PluginRules.MAX_NAME)
        val description = text(o, "", "description", required = true, max = PluginRules.MAX_PLUGIN_DESCRIPTION)
        val category = text(o, "", "category", required = true)
        if (category != null && category !in PluginRules.CATEGORIES) {
            problem("category", "'$category' is not a category; use one of ${PluginRules.CATEGORIES.joinToString(", ")}")
        }
        val homepage = text(o, "", "homepage")
        if (homepage != null && !isHttpsUrl(homepage)) problem("homepage", "must be an https:// URL")
        val status = text(o, "", "status")?.let { s ->
            PluginStatus.entries.firstOrNull { it.id == s } ?: run {
                problem("status", "'$s' is not a status; use stable, beta or experimental")
                null
            }
        } ?: PluginStatus.EXPERIMENTAL
        val maintainers = textList(o, "", "maintainers")?.also { list ->
            list.forEachIndexed { i, handle ->
                if (!PluginRules.GITHUB_HANDLE.matches(handle)) problem("maintainers[$i]", "'$handle' is not a GitHub handle")
            }
        }.orEmpty()
        val tested = list(o, "", "tested")?.mapIndexedNotNull { i, e -> tested(e, PluginPath.index("tested", i)) }.orEmpty()
        val androidPackages = androidSection(o["android"])
        val iosBundleIds = ((o["ios"] as? JsonObject)?.get("bundle_ids") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()

        val actions = when (val raw = o["actions"]) {
            null, JsonNull -> {
                broken("actions", "required: list ${PluginRules.MIN_ACTIONS} to ${PluginRules.MAX_ACTIONS} actions")
                emptyList()
            }
            !is JsonArray -> {
                broken("actions", "must be a list of actions")
                emptyList()
            }
            else -> {
                if (raw.size < PluginRules.MIN_ACTIONS || raw.size > PluginRules.MAX_ACTIONS) {
                    problem("actions", "must have ${PluginRules.MIN_ACTIONS} to ${PluginRules.MAX_ACTIONS} actions (found ${raw.size})")
                }
                val seen = HashSet<String>()
                raw.mapIndexedNotNull { i, e ->
                    val path = PluginPath.index("actions", i)
                    action(e, path)?.also { a ->
                        if (!seen.add(a.id)) problem("$path.id", "duplicate action id '${a.id}'")
                    }
                }
            }
        }
        if (broken > 0 || id == null || name == null || description == null || category == null) return null
        return AppPlugin(
            id = id,
            name = name,
            description = description,
            category = category,
            homepage = homepage,
            status = status,
            maintainers = maintainers,
            tested = tested,
            androidPackages = androidPackages,
            iosBundleIds = iosBundleIds,
            actions = actions,
            source = source,
        )
    }

    private fun androidSection(e: JsonElement?): List<String> {
        if (e == null || e is JsonNull) return emptyList()
        val o = e as? JsonObject ?: run {
            broken("android", "must be a map with 'packages'")
            return emptyList()
        }
        known(o, "android", setOf("packages"))
        val packages = textList(o, "android", "packages", required = true) ?: return emptyList()
        if (packages.isEmpty()) problem("android.packages", "list at least one package name")
        packages.forEachIndexed { i, p ->
            if (!PluginRules.PACKAGE_NAME.matches(p)) problem("android.packages[$i]", "'$p' is not an Android package name")
        }
        packages.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { problem("android.packages", "'$it' is listed twice") }
        return packages
    }

    private fun tested(e: JsonElement, path: String): TestedRecord? {
        val o = e as? JsonObject ?: run {
            broken(path, "must be a map {app_version, android, date, by}")
            return null
        }
        known(o, path, setOf("app_version", "android", "date", "by"))
        return TestedRecord(
            appVersion = scalarText(o, path, "app_version"),
            android = scalarText(o, path, "android"),
            date = scalarText(o, path, "date"),
            by = scalarText(o, path, "by"),
        )
    }

    // Actions (§4)

    private fun action(e: JsonElement, path: String): PluginAction? {
        val o = e as? JsonObject ?: run {
            broken(path, "each action must be a map with id, title, description, risk and android")
            return null
        }
        val before = broken
        known(o, path, ACTION_KEYS)
        val id = text(o, path, "id", required = true)
        if (id != null && !PluginRules.ACTION_ID.matches(id)) {
            problem("$path.id", "'$id' is not a valid action id: use 1-40 lowercase letters, digits and _, starting with a letter")
        }
        val title = text(o, path, "title", required = true, max = PluginRules.MAX_TITLE)
        val description = text(o, path, "description", required = true, max = PluginRules.MAX_ACTION_DESCRIPTION)
        val keywords = textList(o, path, "keywords")?.also { words ->
            if (words.size > PluginRules.MAX_KEYWORDS) problem("$path.keywords", "at most ${PluginRules.MAX_KEYWORDS} keywords")
            words.forEachIndexed { i, w ->
                if (w.length > PluginRules.MAX_KEYWORD) problem("$path.keywords[$i]", "keywords must be at most ${PluginRules.MAX_KEYWORD} characters")
            }
        }.orEmpty()
        val risk = text(o, path, "risk")?.let { r ->
            RISKS[r] ?: run {
                broken("$path.risk", "'$r' is not a risk; use read, action or sensitive")
                null
            }
        }
        val scope = text(o, path, "scope")?.also { s ->
            if (s !in PluginRules.ACTION_SCOPES) problem("$path.scope", "'$s' is not allowed; use one of ${PluginRules.ACTION_SCOPES.joinToString(", ")}")
        }
        val params = params(o["params"], "$path.params")
        val confirm = text(o, path, "confirm", max = PluginRules.MAX_CONFIRM)
        val completes = bool(o, path, "completes")
        val android = o["android"]?.takeIf { it !is JsonNull }?.let { recipe(it, "$path.android") }
        if (o["android"] == null && o["ios"] == null) problem(path, "has no recipe: add an 'android' section")
        val examples = list(o, path, "examples")?.let { list ->
            if (list.size > PluginRules.MAX_EXAMPLES) problem("$path.examples", "at most ${PluginRules.MAX_EXAMPLES} examples")
            list.mapIndexedNotNull { i, ex -> example(ex, PluginPath.index("$path.examples", i)) }
        }.orEmpty()
        if (broken > before || id == null || title == null || description == null) return null
        return PluginAction(
            id = id,
            title = title,
            description = description,
            keywords = keywords,
            risk = risk,
            scope = scope,
            params = params,
            confirm = confirm,
            completes = completes,
            android = android,
            examples = examples,
        )
    }

    // Parameters (§5)

    private fun params(e: JsonElement?, path: String): List<ParamSpec> {
        if (e == null || e is JsonNull) return emptyList()
        val o = e as? JsonObject ?: run {
            broken(path, "must be a map of parameter name to {description, …}")
            return emptyList()
        }
        if (o.size > PluginRules.MAX_PARAMS) problem(path, "at most ${PluginRules.MAX_PARAMS} parameters (found ${o.size})")
        return o.entries.mapNotNull { (name, spec) ->
            val p = PluginPath.key(path, name)
            if (!PluginRules.PARAM_NAME.matches(name)) {
                problem(p, "'$name' is not a valid parameter name: use lowercase letters, digits and _, starting with a letter")
            }
            param(name, spec, p)
        }
    }

    private fun param(name: String, e: JsonElement, path: String): ParamSpec? {
        val o = e as? JsonObject ?: run {
            broken(path, "must be a map such as {description: …, required: true}")
            return null
        }
        val before = broken
        known(o, path, PARAM_KEYS)
        val type = text(o, path, "type")?.let { t ->
            ParamType.entries.firstOrNull { it.id == t } ?: run {
                broken("$path.type", "'$t' is not a type; use string, integer, number or boolean")
                null
            }
        } ?: ParamType.STRING
        val description = text(o, path, "description", required = true, max = PluginRules.MAX_PARAM_DESCRIPTION)
        val required = bool(o, path, "required") ?: false
        val isString = type == ParamType.STRING
        val isNumeric = type == ParamType.INTEGER || type == ParamType.NUMBER
        for (key in listOf("min_length", "max_length", "pattern")) {
            if (o[key] != null && !isString) problem("$path.$key", "only applies to string parameters")
        }
        for (key in listOf("minimum", "maximum")) {
            if (o[key] != null && !isNumeric) problem("$path.$key", "only applies to integer and number parameters")
        }
        val minLength = int(o, path, "min_length")?.also { if (it < 0) problem("$path.min_length", "must be 0 or more") }
        val maxLength = int(o, path, "max_length")?.also {
            if (it < 1 || it > ParamSpec.MAX_MAX_LENGTH) problem("$path.max_length", "must be 1 to ${ParamSpec.MAX_MAX_LENGTH}")
        }
        if (minLength != null && minLength > (maxLength ?: ParamSpec.DEFAULT_MAX_LENGTH)) {
            problem("$path.min_length", "is larger than max_length")
        }
        val minimum = number(o, path, "minimum")
        val maximum = number(o, path, "maximum")
        if (minimum != null && maximum != null && minimum > maximum) problem("$path.minimum", "is larger than maximum")
        val pattern = text(o, path, "pattern", max = PluginRules.MAX_PATTERN)?.also { regex ->
            try {
                Regex(regex)
            } catch (e: PatternSyntaxException) {
                problem("$path.pattern", "not a valid regular expression: ${e.description}")
            }
        }
        val enum = list(o, path, "enum")?.let { values ->
            if (values.isEmpty()) problem("$path.enum", "list at least one value")
            if (type == ParamType.BOOLEAN) problem("$path.enum", "boolean parameters can't have an enum")
            values.mapIndexedNotNull { i, v -> typedValue(v, type, PluginPath.index("$path.enum", i)) }
        }
        val default = o["default"]?.let { typedValue(it, type, "$path.default") }
        if (default != null && required) problem("$path.default", "a required parameter can't have a default")
        if (broken > before || description == null) return null
        val spec = ParamSpec(
            name = name, description = description, type = type, required = required, enum = enum, default = default,
            minLength = minLength, maxLength = maxLength, minimum = minimum, maximum = maximum, pattern = pattern,
        )
        if (default != null) {
            ParamValues.check(spec, default)?.let { problem("$path.default", "the default is not a valid value: it $it") }
        }
        return spec
    }

    /** [e] as a value of [type] (strings must be strings, integers integral, …), or null after a problem. */
    private fun typedValue(e: JsonElement, type: ParamType, path: String): JsonPrimitive? {
        val p = e as? JsonPrimitive
        if (p == null || p is JsonNull) {
            broken(path, "must be a ${type.id}")
            return null
        }
        val ok = when (type) {
            ParamType.STRING -> p.isString
            ParamType.INTEGER -> !p.isString && p.longOrNull != null
            ParamType.NUMBER -> !p.isString && p.booleanOrNull == null && p.doubleOrNull != null
            ParamType.BOOLEAN -> !p.isString && p.booleanOrNull != null
        }
        if (!ok) {
            val hint = if (type == ParamType.STRING) " (quote it)" else ""
            broken(path, "must be a ${type.id}$hint, found ${describe(p)}")
            return null
        }
        return if (type == ParamType.NUMBER) JsonPrimitive(p.doubleOrNull!!) else p
    }

    // Recipes (§6)

    private fun recipe(e: JsonElement, path: String): Recipe? {
        val o = e as? JsonObject ?: run {
            broken(path, "must be a map with one recipe: intent, url, steps or tool")
            return null
        }
        known(o, path, RECIPE_KEYS)
        val present = RECIPE_KEYS.filter { o[it] != null }
        if (present.size != 1) {
            broken(path, "needs exactly one recipe: intent, url, steps or tool (found ${present.ifEmpty { listOf("none") }.joinToString(", ")})")
            return null
        }
        return when (val kind = present.single()) {
            "intent" -> intent(o.getValue(kind), "$path.intent")
            "url" -> text(o, path, "url", required = true)?.let(::UrlRecipe)
            "steps" -> steps(o.getValue(kind), "$path.steps", nested = false)?.let(::StepsRecipe)
            else -> tool(o.getValue(kind), "$path.tool")
        }
    }

    private fun intent(e: JsonElement, path: String): IntentRecipe? {
        val o = e as? JsonObject ?: run {
            broken(path, "must be a map with at least 'action'")
            return null
        }
        val before = broken
        known(o, path, INTENT_KEYS)
        val action = text(o, path, "action", required = true)
        val type = text(o, path, "type")
        val data = text(o, path, "data")
        val categories = textList(o, path, "categories").orEmpty()
        val className = text(o, path, "class")
        val extras = LinkedHashMap<String, ExtraSpec>()
        when (val raw = o["extras"]) {
            null, JsonNull -> Unit
            !is JsonObject -> broken("$path.extras", "must be a map of extra name to value")
            else -> for ((key, value) in raw) {
                extra(value, PluginPath.key("$path.extras", key))?.let { extras[key] = it }
            }
        }
        if (broken > before || action == null) return null
        return IntentRecipe(action, type, data, categories, className, extras)
    }

    private fun extra(e: JsonElement, path: String): ExtraSpec? = when (e) {
        is JsonObject -> {
            known(e, path, setOf("type", "value"))
            val typeName = text(e, path, "type", required = true)
            val type = typeName?.let { t ->
                ExtraType.entries.firstOrNull { it.id == t } ?: run {
                    broken("$path.type", "'$t' is not an extra type; use string, int, long, double, boolean or string_array")
                    null
                }
            }
            val raw = e["value"]
            when {
                raw == null || raw is JsonNull -> {
                    broken("$path.value", "required")
                    null
                }
                type == null -> null
                type == ExtraType.STRING_ARRAY && raw !is JsonArray -> {
                    broken("$path.value", "a string_array needs a list")
                    null
                }
                type != ExtraType.STRING_ARRAY && raw !is JsonPrimitive -> {
                    broken("$path.value", "must be a single value for type ${type.id}")
                    null
                }
                else -> valueSpec(raw, path = "$path.value", allowList = true)?.let { ExtraSpec(it, type) }
            }
        }
        else -> valueSpec(e, path, allowList = true)?.let { ExtraSpec(it) }
    }

    private fun tool(e: JsonElement, path: String): ToolRecipe? {
        val o = e as? JsonObject ?: run {
            broken(path, "must be a map {name, args}")
            return null
        }
        val before = broken
        known(o, path, setOf("name", "args"))
        val name = text(o, path, "name", required = true)
        val args = LinkedHashMap<String, ValueSpec>()
        when (val raw = o["args"]) {
            null, JsonNull -> Unit
            !is JsonObject -> broken("$path.args", "must be a map of argument name to value")
            else -> for ((key, value) in raw) {
                val p = PluginPath.key("$path.args", key)
                if (!PluginRules.ARG_NAME.matches(key)) problem(p, "'$key' is not a valid argument name")
                valueSpec(value, p, allowList = true)?.let { args[key] = it }
            }
        }
        if (broken > before || name == null) return null
        return ToolRecipe(name, args)
    }

    /** A string (template), number or boolean (literal), or with [allowList] a list of those. */
    private fun valueSpec(e: JsonElement, path: String, allowList: Boolean): ValueSpec? = when {
        e is JsonNull -> {
            broken(path, "needs a value")
            null
        }
        e is JsonPrimitive -> if (e.isString) ValueSpec.Template(e.content) else ValueSpec.Literal(e)
        e is JsonArray && allowList -> {
            val items = e.mapIndexed { i, item ->
                val p = PluginPath.index(path, i)
                if (item !is JsonPrimitive || item is JsonNull) {
                    broken(p, "list items must be single values")
                    null
                } else {
                    valueSpec(item, p, allowList = false)
                }
            }
            if (items.any { it == null }) null else ValueSpec.Items(items.filterNotNull())
        }
        else -> {
            broken(path, "must be text, a number, a boolean${if (allowList) " or a list of those" else ""}")
            null
        }
    }

    // Steps (§6.4)

    /** Steps written in recipe syntax, e.g. an example's expected steps (§7); null after a problem. */
    fun literalSteps(e: JsonElement, path: String): List<Step>? {
        val before = problems.size
        return steps(e, path, nested = false)?.takeIf { problems.size == before }
    }

    private fun steps(e: JsonElement, path: String, nested: Boolean): List<Step>? {
        val list = e as? JsonArray ?: run {
            broken(path, "must be a list of steps")
            return null
        }
        if (list.isEmpty()) problem(path, "list at least one step")
        val before = broken
        val steps = list.mapIndexedNotNull { i, s -> step(s, PluginPath.index(path, i), nested) }
        return if (broken > before) null else steps
    }

    private fun step(e: JsonElement, path: String, nested: Boolean): Step? {
        val o = e as? JsonObject ?: run {
            broken(path, "each step must be a map such as {tap: {text: Send}}")
            return null
        }
        val before = broken
        known(o, path, STEP_KEYS + STEP_MODIFIERS)
        val kinds = STEP_KEYS.filter { o[it] != null }
        if (kinds.size != 1) {
            broken(path, "needs exactly one of ${STEP_KEYS.joinToString(", ")} (found ${kinds.ifEmpty { listOf("none") }.joinToString(", ")})")
            return null
        }
        val kind = kinds.single()
        val p = PluginPath.key(path, kind)
        var value = o.getValue(kind)
        // Selector steps may also carry the step modifiers inside their map: wait_for: {text: x, timeout_ms: 8000}.
        var modifiers = o
        if (kind in SELECTOR_STEPS && value is JsonObject && SELECTOR_STEP_MODIFIERS.any { value.containsKey(it) }) {
            val inner = value
            SELECTOR_STEP_MODIFIERS.filter { inner.containsKey(it) && o.containsKey(it) }.forEach { problem("$p.$it", "is also set on the step") }
            modifiers = JsonObject(o + inner.filterKeys { it in SELECTOR_STEP_MODIFIERS })
            value = JsonObject(inner.filterKeys { it !in SELECTOR_STEP_MODIFIERS })
        }
        val optional = bool(modifiers, path, "optional") ?: false
        val timeout = int(modifiers, path, "timeout_ms")?.also {
            if (it < 1 || it > Step.MAX_TIMEOUT_MS) problem("$path.timeout_ms", "must be 1 to ${Step.MAX_TIMEOUT_MS}")
        }?.toLong()
        if (o["fail"] != null && kind != "expect") problem("$path.fail", "only goes with expect")
        if (o["then"] != null && kind != "if_present") problem("$path.then", "only goes with if_present")
        val action: StepAction? = when (kind) {
            "launch" -> when {
                value is JsonPrimitive && !value.isString && value.booleanOrNull == true -> StepAction.Launch()
                value is JsonObject -> {
                    known(value, p, setOf("fresh"))
                    StepAction.Launch(bool(value, p, "fresh") ?: false)
                }
                else -> {
                    broken(p, "use 'launch: true' or 'launch: {fresh: true}'")
                    null
                }
            }
            "open_url" -> text(o, path, kind, required = true)?.let(StepAction::OpenUrl)
            "intent" -> intent(value, p)?.let(StepAction::StartIntent)
            "wait_for" -> selector(value, p)?.let(StepAction::WaitFor)
            "tap" -> selector(value, p)?.let(StepAction::Tap)
            "long_press" -> selector(value, p)?.let(StepAction::LongPress)
            "type" -> typeText(value, p)
            "scroll" -> scroll(value, p)
            "back" -> if (value is JsonPrimitive && !value.isString && value.booleanOrNull == true) {
                StepAction.Back
            } else {
                broken(p, "use 'back: true'")
                null
            }
            "sleep" -> int(o, path, kind)?.let { ms ->
                if (ms < 0 || ms > StepAction.Sleep.MAX_MS) problem(p, "must be 0 to ${StepAction.Sleep.MAX_MS} ms")
                StepAction.Sleep(ms.toLong())
            }
            "expect" -> selector(value, p)?.let { StepAction.Expect(it, text(o, path, "fail", max = PluginRules.MAX_FAIL_MESSAGE)) }
            else -> {
                val selector = selector(value, p)
                val then = when {
                    nested -> {
                        broken(p, "if_present can't be nested: it works one level deep")
                        null
                    }
                    o["then"] == null -> {
                        broken("$path.then", "if_present needs 'then', the steps to run")
                        null
                    }
                    else -> steps(o.getValue("then"), "$path.then", nested = true)
                }
                if (selector != null && then != null) StepAction.IfPresent(selector, then) else null
            }
        }
        if (broken > before || action == null) return null
        return Step(action, optional, timeout)
    }

    private fun typeText(e: JsonElement, path: String): StepAction.TypeText? {
        val o = e as? JsonObject ?: run {
            broken(path, "must be a map {into, text, clear, submit}")
            return null
        }
        known(o, path, setOf("into", "text", "clear", "submit"))
        val into = o["into"]?.takeIf { it !is JsonNull }?.let { selector(it, "$path.into") }
        val text = text(o, path, "text", required = true, allowEmpty = true) ?: return null
        return StepAction.TypeText(into, text, clear = bool(o, path, "clear") ?: true, submit = bool(o, path, "submit") ?: false)
    }

    private fun scroll(e: JsonElement, path: String): StepAction.Scroll? {
        val o = e as? JsonObject ?: run {
            broken(path, "must be a map {direction, in, until, max}")
            return null
        }
        known(o, path, setOf("direction", "in", "until", "max"))
        val direction = text(o, path, "direction", required = true)?.let { d ->
            ScrollDirection.entries.firstOrNull { it.id == d } ?: run {
                broken("$path.direction", "'$d' is not a direction; use up, down, left or right")
                null
            }
        }
        val within = o["in"]?.takeIf { it !is JsonNull }?.let { selector(it, "$path.in") }
        val until = o["until"]?.takeIf { it !is JsonNull }?.let { selector(it, "$path.until") }
        val max = int(o, path, "max")?.also {
            if (it < 1 || it > StepAction.Scroll.MAX_SCROLLS) problem("$path.max", "must be 1 to ${StepAction.Scroll.MAX_SCROLLS}")
        } ?: StepAction.Scroll.DEFAULT_MAX_SCROLLS
        return direction?.let { StepAction.Scroll(it, within, until, max) }
    }

    private fun selector(e: JsonElement, path: String): Selector? {
        if (e is JsonPrimitive && e.isString) {
            if (e.content.isBlank()) {
                broken(path, "a selector can't be empty")
                return null
            }
            return Selector(textOrDesc = e.content)
        }
        val o = e as? JsonObject ?: run {
            broken(path, "must be text or a map such as {id: send_button} or {text: Send}")
            return null
        }
        val before = broken
        known(o, path, SELECTOR_KEYS)
        val selector = Selector(
            id = text(o, path, "id"),
            text = text(o, path, "text"),
            textContains = text(o, path, "text_contains"),
            desc = text(o, path, "desc"),
            descContains = text(o, path, "desc_contains"),
            className = text(o, path, "class"),
            clickable = bool(o, path, "clickable"),
            index = int(o, path, "index")?.also { if (it < 0 || it > MAX_INDEX) problem("$path.index", "must be 0 to $MAX_INDEX") } ?: 0,
        )
        if (SELECTOR_KEYS.none { it != "index" && o[it] != null }) {
            problem(path, "say what to match: id, text, text_contains, desc, desc_contains, class or clickable")
        }
        return if (broken > before) null else selector
    }

    // Examples (§7)

    private fun example(e: JsonElement, path: String): PluginExample? {
        val o = e as? JsonObject ?: run {
            broken(path, "each example must be a map {params, expect}")
            return null
        }
        known(o, path, setOf("params", "expect"))
        val params = when (val raw = o["params"]) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> {
                raw.entries.forEach { (k, v) ->
                    if (v !is JsonPrimitive) problem(PluginPath.key("$path.params", k), "example parameters must be single values")
                }
                raw
            }
            else -> {
                broken("$path.params", "must be a map of parameter name to value")
                return null
            }
        }
        val expect = o["expect"] as? JsonObject ?: run {
            broken("$path.expect", "required: a map with one of intent, url, tool, steps or error")
            return null
        }
        val ep = "$path.expect"
        known(expect, ep, EXPECT_KEYS)
        val kinds = EXPECT_KEYS.filter { expect[it] != null }
        if (kinds.size != 1) {
            broken(ep, "needs exactly one of ${EXPECT_KEYS.joinToString(", ")} (found ${kinds.ifEmpty { listOf("none") }.joinToString(", ")})")
            return null
        }
        val kind = kinds.single()
        val value = expect.getValue(kind)
        val expectation: ExampleExpectation = when (kind) {
            "intent" -> {
                val fields = value as? JsonObject ?: run {
                    broken("$ep.intent", "must be a map with some of action, package, data, type, categories, class, extras")
                    return null
                }
                known(fields, "$ep.intent", EXPECT_INTENT_KEYS)
                ExampleExpectation.Intent(fields)
            }
            "tool" -> {
                val fields = value as? JsonObject ?: run {
                    broken("$ep.tool", "must be a map {name, args}")
                    return null
                }
                known(fields, "$ep.tool", setOf("name", "args"))
                ExampleExpectation.Tool(fields)
            }
            "steps" -> ExampleExpectation.Steps(
                value as? JsonArray ?: run {
                    broken("$ep.steps", "must be a list of steps")
                    return null
                },
            )
            "url" -> ExampleExpectation.Url(text(expect, ep, "url", required = true) ?: return null)
            else -> ExampleExpectation.Error(text(expect, ep, "error", required = true) ?: return null)
        }
        return PluginExample(params, expectation)
    }

    // Field helpers

    /** Reports keys of [o] not in [allowed], suggesting the closest allowed key for typos. */
    private fun known(o: JsonObject, path: String, allowed: Collection<String>) {
        for (key in o.keys) {
            if (key in allowed) continue
            val suggestion = allowed.minByOrNull { editDistance(it, key) }?.takeIf { editDistance(it, key) <= 2 }
            val hint = suggestion?.let { " (did you mean '$it'?)" } ?: " (allowed: ${allowed.joinToString(", ")})"
            problem(PluginPath.key(path, key), "unknown field$hint")
        }
    }

    private fun at(path: String, key: String) = PluginPath.key(path, key)

    /**
     * A string field, trimmed unless [allowEmpty]. Null when absent (structural when [required]), not text
     * or empty. Too long is a problem, but the value is kept.
     */
    private fun text(o: JsonObject, path: String, key: String, required: Boolean = false, max: Int? = null, allowEmpty: Boolean = false): String? {
        val raw = o[key]
        if (raw == null || raw is JsonNull) {
            if (required) broken(at(path, key), "required")
            return null
        }
        val p = raw as? JsonPrimitive
        if (p == null || !p.isString) {
            broken(at(path, key), "must be text${if (p != null) " (quote it)" else ""}, found ${describe(raw)}")
            return null
        }
        val value = if (allowEmpty) p.content else p.content.trim()
        if (value.isEmpty() && !allowEmpty) {
            broken(at(path, key), "must not be empty")
            return null
        }
        if (max != null && value.length > max) problem(at(path, key), "is ${value.length} characters long; the limit is $max")
        return value
    }

    /** A scalar shown as text (YAML may read `8.9` or `14` as numbers). */
    private fun scalarText(o: JsonObject, path: String, key: String): String? {
        val raw = o[key] ?: return null
        if (raw is JsonNull) return null
        val p = raw as? JsonPrimitive ?: run {
            broken(at(path, key), "must be a single value")
            return null
        }
        return p.content.trim().takeIf { it.isNotEmpty() }
    }

    private fun list(o: JsonObject, path: String, key: String): JsonArray? {
        val raw = o[key]
        if (raw == null || raw is JsonNull) return null
        return raw as? JsonArray ?: run {
            broken(at(path, key), "must be a list")
            null
        }
    }

    /** A list of non-blank strings (lists are written [a, b]); null when absent or after a structural problem. */
    private fun textList(o: JsonObject, path: String, key: String, required: Boolean = false): List<String>? {
        val raw = o[key]
        if (raw == null || raw is JsonNull) {
            if (required) broken(at(path, key), "required")
            return null
        }
        val array = raw as? JsonArray ?: run {
            broken(at(path, key), "must be a list such as [a, b]")
            return null
        }
        val before = broken
        val values = array.mapIndexed { i, e ->
            val p = e as? JsonPrimitive
            if (p == null || !p.isString || p.content.isBlank()) {
                broken(PluginPath.index(at(path, key), i), "must be text")
                ""
            } else {
                p.content.trim()
            }
        }
        return if (broken > before) null else values
    }

    private fun bool(o: JsonObject, path: String, key: String): Boolean? {
        val raw = o[key]
        if (raw == null || raw is JsonNull) return null
        val value = (raw as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        if (value == null) broken(at(path, key), "must be true or false, found ${describe(raw)}")
        return value
    }

    private fun int(o: JsonObject, path: String, key: String): Int? {
        val raw = o[key]
        if (raw == null || raw is JsonNull) return null
        val value = (raw as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }
        if (value == null) broken(at(path, key), "must be a whole number, found ${describe(raw)}")
        return value?.toInt()
    }

    private fun number(o: JsonObject, path: String, key: String): Double? {
        val raw = o[key]
        if (raw == null || raw is JsonNull) return null
        val value = (raw as? JsonPrimitive)?.takeIf { !it.isString && it.booleanOrNull == null }?.doubleOrNull
        if (value == null) broken(at(path, key), "must be a number, found ${describe(raw)}")
        return value
    }

    private fun isHttpsUrl(url: String): Boolean =
        url.startsWith("https://") && url.length > "https://".length && url.none { it.isWhitespace() }

    companion object {
        private val PLUGIN_KEYS = setOf(
            "id", "name", "description", "category", "homepage", "status", "maintainers", "tested", "android", "ios", "actions",
        )
        private val ACTION_KEYS = setOf(
            "id", "title", "description", "keywords", "risk", "scope", "params", "confirm", "completes", "android", "ios",
            "examples",
        )
        private val PARAM_KEYS = setOf(
            "type", "description", "required", "enum", "default", "min_length", "max_length", "minimum", "maximum", "pattern",
        )
        private val RECIPE_KEYS = listOf("intent", "url", "steps", "tool")
        private val INTENT_KEYS = setOf("action", "type", "data", "categories", "class", "extras")
        private val STEP_KEYS = listOf(
            "launch", "open_url", "intent", "wait_for", "tap", "long_press", "type", "scroll", "back", "sleep", "expect",
            "if_present",
        )
        private val STEP_MODIFIERS = listOf("optional", "timeout_ms", "fail", "then")
        private val SELECTOR_STEPS = setOf("wait_for", "tap", "long_press", "expect", "if_present")
        private val SELECTOR_STEP_MODIFIERS = setOf("optional", "timeout_ms")
        private val SELECTOR_KEYS = listOf("id", "text", "text_contains", "desc", "desc_contains", "class", "clickable", "index")
        private val EXPECT_KEYS = listOf("intent", "url", "tool", "steps", "error")
        private val EXPECT_INTENT_KEYS = setOf("action", "package", "data", "type", "categories", "class", "extras")
        private val RISKS = mapOf("read" to Risk.READ, "action" to Risk.ACTION, "sensitive" to Risk.SENSITIVE)
        private const val MAX_INDEX = 50

        fun describe(e: JsonElement): String = when {
            e is JsonNull -> "nothing"
            e is JsonObject -> "a map"
            e is JsonArray -> "a list"
            e is JsonPrimitive && e.isString -> "text \"${e.content.take(40)}\""
            e is JsonPrimitive && e.booleanOrNull != null -> "${e.content} (a boolean)"
            else -> "${(e as JsonPrimitive).content} (a number)"
        }

        /** Levenshtein distance, for "did you mean" hints on short keys. */
        fun editDistance(a: String, b: String): Int {
            if (kotlin.math.abs(a.length - b.length) > 2) return 3
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1)
                cur[0] = i
                for (j in 1..b.length) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                }
                prev = cur
            }
            return prev[b.length]
        }
    }
}
