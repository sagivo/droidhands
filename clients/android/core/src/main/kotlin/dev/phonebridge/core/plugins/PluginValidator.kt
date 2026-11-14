package dev.phonebridge.core.plugins

import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.ToolNames

/**
 * The rules of docs/PLUGINS.md that relate several fields of a parsed plugin (single-field rules are
 * [PluginParser]'s): folder name, packages, `stable` requirements, templates against declared parameters,
 * URL schemes, intent safety, `tool` recipes, step limits and the risk floors of §9. Pure.
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
        if (plugin.status == PluginStatus.STABLE) {
            if (plugin.tested.isEmpty()) problem("tested", "a stable plugin needs at least one 'tested' record from a real device")
            plugin.actions.forEachIndexed { i, a ->
                if (a.android != null && a.examples.isEmpty()) problem("actions[$i].examples", "every action of a stable plugin needs examples")
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

        fun run() {
            action.confirm?.let { template("$path.confirm", it) }
            when (val recipe = action.android) {
                null -> Unit
                is ToolRecipe -> toolRecipe(recipe)
                else -> {
                    if (action.risk == null) problem("$path.risk", "required: read, action or sensitive")
                    when (recipe) {
                        is IntentRecipe -> intent(recipe, "$path.android.intent")
                        is UrlRecipe -> url("$path.android.url", recipe.url)
                        is StepsRecipe -> steps(recipe)
                        is ToolRecipe -> Unit
                    }
                }
            }
            riskFloors()
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
            val recipe = action.android
            if (risk == Risk.READ && recipe is StepsRecipe && touches(recipe.steps)) {
                problem("$path.risk", "can't be read: the steps tap, type or scroll")
            }
        }

        private fun touches(steps: List<Step>): Boolean = steps.any { s ->
            when (val a = s.action) {
                is StepAction.Tap, is StepAction.LongPress, is StepAction.TypeText, is StepAction.Scroll -> true
                is StepAction.IfPresent -> touches(a.then)
                else -> false
            }
        }

        private fun toolRecipe(recipe: ToolRecipe) {
            val p = "$path.android.tool"
            if (action.risk != null) problem("$path.risk", "remove it: a tool recipe takes the built-in tool's risk")
            if (action.scope != null) problem("$path.scope", "remove it: a tool recipe takes the built-in tool's scope")
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

        private fun steps(recipe: StepsRecipe) {
            val p = "$path.android.steps"
            val total = countSteps(recipe.steps)
            if (total > PluginRules.MAX_STEPS) problem(p, "at most ${PluginRules.MAX_STEPS} steps in total, nested ones included (found $total)")
            recipe.steps.forEachIndexed { i, s -> step(s, PluginPath.index(p, i)) }
        }

        private fun countSteps(steps: List<Step>): Int =
            steps.sumOf { s -> 1 + ((s.action as? StepAction.IfPresent)?.then?.let(::countSteps) ?: 0) }

        private fun step(step: Step, p: String) {
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
                    a.then.forEachIndexed { i, s -> step(s, PluginPath.index("$p.then", i)) }
                }
                is StepAction.Launch, StepAction.Back, is StepAction.Sleep -> Unit
            }
        }

        private fun selector(s: Selector, p: String) {
            s.textOrDesc?.let { template(p, it) }
            s.text?.let { template("$p.text", it) }
            s.textContains?.let { template("$p.text_contains", it) }
            s.desc?.let { template("$p.desc", it) }
            s.descContains?.let { template("$p.desc_contains", it) }
            s.className?.let { literal("$p.class", it) }
            s.id?.let { id ->
                if (!literal("$p.id", id)) return@let
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
