package dev.phonebridge.core.plugins

import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.ToolNames

/**
 * The rules of docs/PLUGINS.md that relate several fields of a parsed plugin (single-field rules are
 * [PluginParser]'s): folder name, packages and bundle ids, `stable` requirements, templates against declared
 * parameters, URL schemes, intent safety, `tool` recipes, step limits, examples against recipes and the risk floors
 * of §9. Each action's `android` and `ios` recipes are checked alike, with the iOS differences of §6.6. Pure.
 */
object PluginValidator {
    /**
     * Problems with [plugin]. [folder] is the folder the file is in: the plugin id must equal it, except
     * for folders starting with `_` (such as `_template`) and when null (a file imported on the phone).
     * [toolNames] are the built-in tools `tool` recipes may name (before [ToolNames.recipeRefusal]).
     */
    fun validate(plugin: AppPlugin, folder: String?, toolNames: Set<String> = ToolNames.ALL): List<PluginProblem> {
        val problems = ArrayList<PluginProblem>()
        fun problem(path: String, message: String) {
            problems += PluginProblem(path, message)
        }

        if (folder != null && !folder.startsWith("_") && plugin.id != folder) {
            problem("id", "'${plugin.id}' must equal the folder name '$folder' (rename one of them)")
        }
        val hasAndroidActions = plugin.actions.any { it.android != null }
        if (hasAndroidActions && plugin.androidPackages.isEmpty()) {
            problem("android.packages", "required: the package names of the app, e.g. [com.spotify.music]")
        }
        plugin.androidPackages.forEachIndexed { i, p ->
            if (PluginRules.isBridgePackage(p)) problem("android.packages[$i]", "plugins can't target PhoneBridge itself")
        }
        val hasIosActions = plugin.actions.any { it.ios != null }
        if (hasIosActions && plugin.iosBundleIds.isEmpty()) {
            problem("ios.bundle_ids", "required: the bundle ids of the iOS app, e.g. [com.spotify.client]")
        }
        if (!hasIosActions && plugin.iosBundleIds.isNotEmpty()) {
            problem("ios.bundle_ids", "no action has an 'ios' recipe: add one, or remove the 'ios' section")
        }
        plugin.iosBundleIds.forEachIndexed { i, id ->
            if (PluginRules.isBridgePackage(id.lowercase())) problem("ios.bundle_ids[$i]", "plugins can't target PhoneBridge itself")
        }
        if (plugin.status == PluginStatus.STABLE) {
            if (plugin.tested.isEmpty()) problem("tested", "a stable plugin needs at least one 'tested' record from a real device")
            plugin.actions.forEachIndexed { i, a ->
                if (a.android != null && a.examples.isEmpty()) problem("actions[$i].examples", "every action of a stable plugin needs examples")
                if (a.ios != null && a.examples.none { it.os == DeviceOs.IOS }) {
                    problem("actions[$i].examples", "every ios recipe of a stable plugin needs examples with os: ios")
                }
            }
        }
        plugin.actions.forEachIndexed { i, action ->
            ActionChecks(plugin, action, "actions[$i]", toolNames, ::problem).run()
        }
        return problems
    }

    private class ActionChecks(
        val plugin: AppPlugin,
        val action: PluginAction,
        val path: String,
        val toolNames: Set<String>,
        val problem: (String, String) -> Unit,
    ) {
        private val declared = action.params.map { it.name }.toSet()

        /** The recipes the action has, with their OS. */
        private val recipes: List<Pair<DeviceOs, Recipe>> =
            DeviceOs.entries.mapNotNull { os -> action.recipeFor(os)?.let { os to it } }

        fun run() {
            action.confirm?.let { template("$path.confirm", it) }
            val tools = recipes.count { it.second is ToolRecipe }
            if (tools in 1 until recipes.size) {
                problem(
                    path,
                    "the android and ios recipes must both be tool recipes or neither: a tool recipe takes the built-in " +
                        "tool's risk and scope, other recipes declare their own (make them two actions)",
                )
            } else if (tools > 0) {
                if (action.risk != null) problem("$path.risk", "remove it: a tool recipe takes the built-in tool's risk")
                if (action.scope != null) problem("$path.scope", "remove it: a tool recipe takes the built-in tool's scope")
            } else if (recipes.isNotEmpty() && action.risk == null) {
                problem("$path.risk", "required: read, action or sensitive")
            }
            for ((os, recipe) in recipes) {
                val p = "$path.${os.id}"
                when (recipe) {
                    is ToolRecipe -> toolRecipe(recipe, "$p.tool")
                    is IntentRecipe -> intent(recipe, "$p.intent")
                    is UrlRecipe -> url("$p.url", recipe.url)
                    is StepsRecipe -> steps(recipe, "$p.steps", os)
                }
            }
            examples()
            riskFloors()
        }

        /** Each example must test a recipe the action has, on an OS the plugin lists apps for. */
        private fun examples() {
            action.examples.forEachIndexed { i, example ->
                if (action.recipeFor(example.os) != null) return@forEachIndexed
                val p = PluginPath.index("$path.examples", i)
                if (example.os == DeviceOs.IOS) {
                    problem("$p.os", "the action has no 'ios' recipe to test")
                } else {
                    problem(p, "the action has no 'android' recipe to test; add 'os: ios' to test its ios recipe")
                }
            }
        }

        private fun riskFloors() {
            val risk = action.risk ?: return
            if (risk == Risk.SENSITIVE) return
            val scope = action.effectiveScope
            if (scope == Scopes.MESSAGES_SEND || scope == Scopes.CALLS) {
                problem("$path.risk", "must be sensitive for scope $scope")
                return
            }
            val word = listOfNotNull("id" to action.id, "title" to action.title, action.confirm?.let { "confirm" to it })
                .firstNotNullOfOrNull { (field, text) -> SensitiveWords.find(text)?.let { field to it } }
            if (word != null) {
                problem(
                    "$path.risk",
                    "must be sensitive: the ${word.first} says '${word.second}' (sends, posts, pays, buys, orders, books, calls, " +
                        "deletes or transfers; docs/PLUGINS.md §9). If the word means something harmless here, rephrase it.",
                )
            }
            if (risk != Risk.READ) return
            val touching = recipes.map { it.second }.firstOrNull { it is StepsRecipe && touches(it.steps) }
            if (touching != null) {
                problem("$path.risk", "can't be read: the steps tap, type, scroll, start an intent or open a link")
            }
            val opening = recipes.map { it.second }.firstOrNull { it is IntentRecipe || it is UrlRecipe }
            if (opening != null) {
                problem("$path.risk", "can't be read: ${opening.kind} recipes open the app, which is at least an action")
            }
        }

        private fun touches(steps: List<Step>): Boolean = steps.any { s ->
            when (val a = s.action) {
                is StepAction.Tap, is StepAction.LongPress, is StepAction.TypeText, is StepAction.Scroll,
                is StepAction.StartIntent, is StepAction.OpenUrl -> true
                is StepAction.IfPresent -> touches(a.then)
                else -> false
            }
        }

        private fun toolRecipe(recipe: ToolRecipe, p: String) {
            val refusal = if (recipe.name in toolNames) ToolNames.recipeRefusal(recipe.name) else "'${recipe.name}' is not a PhoneBridge tool (see docs/TOOLS.md)"
            refusal?.let { problem("$p.name", it) }
            for ((key, value) in recipe.args) value(PluginPath.key("$p.args", key), value)
        }

        private fun intent(recipe: IntentRecipe, p: String) {
            literal("$p.action", recipe.action)
            if (PluginRules.isCallAction(recipe.action)) problem("$p.action", "call actions are not allowed; use the phone_call tool")
            recipe.type?.let { literal("$p.type", it) }
            recipe.categories.forEachIndexed { i, c -> literal("$p.categories[$i]", c) }
            recipe.className?.let { c ->
                if (literal("$p.class", c) && !c.startsWith(".") && plugin.androidPackages.none { c.startsWith("$it.") }) {
                    problem("$p.class", "'$c' is outside the plugin's packages; use .Relative or a class name in one of them")
                }
            }
            recipe.data?.let { url("$p.data", it) }
            for ((key, extra) in recipe.extras) {
                val kp = PluginPath.key("$p.extras", key)
                literal(kp, key, what = "extra names")
                value(if (extra.type != null) "$kp.value" else kp, extra.value)
            }
        }

        private fun steps(recipe: StepsRecipe, p: String, os: DeviceOs) {
            val total = countSteps(recipe.steps)
            if (total > PluginRules.MAX_STEPS) problem(p, "at most ${PluginRules.MAX_STEPS} steps in total, nested ones included (found $total)")
            recipe.steps.forEachIndexed { i, s -> step(s, PluginPath.index(p, i), os) }
        }

        private fun countSteps(steps: List<Step>): Int =
            steps.sumOf { s -> 1 + ((s.action as? StepAction.IfPresent)?.then?.let(::countSteps) ?: 0) }

        private fun step(step: Step, p: String, os: DeviceOs) {
            fun selector(s: Selector, at: String) = selector(s, at, os)
            when (val a = step.action) {
                is StepAction.OpenUrl -> url("$p.open_url", a.url)
                is StepAction.StartIntent -> intent(a.intent, "$p.intent")
                is StepAction.WaitFor -> selector(a.selector, "$p.wait_for")
                is StepAction.Tap -> selector(a.selector, "$p.tap")
                is StepAction.LongPress -> selector(a.selector, "$p.long_press")
                is StepAction.TypeText -> {
                    a.into?.let { selector(it, "$p.type.into") }
                    template("$p.type.text", a.text)
                }
                is StepAction.Scroll -> {
                    a.within?.let { selector(it, "$p.scroll.in") }
                    a.until?.let { selector(it, "$p.scroll.until") }
                }
                is StepAction.Expect -> {
                    selector(a.selector, "$p.expect")
                    a.fail?.let { literal("$p.fail", it) }
                }
                is StepAction.IfPresent -> {
                    selector(a.selector, "$p.if_present")
                    a.then.forEachIndexed { i, s -> step(s, PluginPath.index("$p.then", i), os) }
                }
                is StepAction.Launch, StepAction.Back, is StepAction.Sleep -> Unit
            }
        }

        /** On iOS an `id` is an accessibility identifier, used as written; on Android a short or full view id. */
        private fun selector(s: Selector, p: String, os: DeviceOs) {
            s.textOrDesc?.let { template(p, it) }
            s.text?.let { template("$p.text", it) }
            s.textContains?.let { template("$p.text_contains", it) }
            s.desc?.let { template("$p.desc", it) }
            s.descContains?.let { template("$p.desc_contains", it) }
            s.className?.let { literal("$p.class", it) }
            s.id?.let { id ->
                if (!literal("$p.id", id) || os == DeviceOs.IOS) return@let
                val owner = id.substringBefore(":id/", missingDelimiterValue = "")
                if (id.contains(":id/") && owner != "android" && owner !in plugin.androidPackages) {
                    problem("$p.id", "'$id' belongs to another app; use a short id or one of the plugin's packages")
                } else if (!id.contains(":id/") && id.contains(':')) {
                    problem("$p.id", "'$id' is not a view id; use a short id (search_src_text) or a full one (com.example:id/search_src_text)")
                }
            }
        }

        private fun value(p: String, value: ValueSpec) {
            when (value) {
                is ValueSpec.Template -> template(p, value.template)
                is ValueSpec.Literal -> Unit
                is ValueSpec.Items -> value.items.forEachIndexed { i, item -> value(PluginPath.index(p, i), item) }
            }
        }

        /** Parses [text] as a template whose placeholders must be declared parameters. Returns it, or null after a problem. */
        private fun template(p: String, text: String): Template? {
            val template = try {
                Template.parse(text)
            } catch (e: TemplateException) {
                problem(p, e.message ?: "bad template")
                return null
            }
            val undeclared = template.paramNames.filter { it !in declared }
            if (undeclared.isNotEmpty()) {
                val names = undeclared.joinToString(", ") { "{{$it}}" }
                val known = if (declared.isEmpty()) "the action has no params" else "params: ${declared.joinToString(", ")}"
                problem(p, "$names ${if (undeclared.size > 1) "are" else "is"} not declared in params ($known)")
                return null
            }
            return template
        }

        private fun url(p: String, text: String) {
            template(p, text) ?: return
            Templates.urlTemplateProblem(text)?.let { problem(p, it) }
        }

        /** True if [text] has no template braces. */
        private fun literal(p: String, text: String, what: String = "this field"): Boolean {
            if (text.contains("{{") || text.contains("}}")) {
                problem(p, "templates are not allowed in $what; it must be literal")
                return false
            }
            return true
        }
    }
}
