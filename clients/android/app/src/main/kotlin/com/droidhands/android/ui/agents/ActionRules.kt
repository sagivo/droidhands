package com.droidhands.android.ui.agents

import com.droidhands.android.ui.apps.ActionInfo
import com.droidhands.android.ui.apps.AppListing
import com.droidhands.android.ui.apps.AppSelection
import com.droidhands.core.auth.ConfirmPolicy
import com.droidhands.core.auth.ToolRule
import com.droidhands.core.policy.PolicyEngine
import com.droidhands.core.tools.AppAccess
import com.droidhands.core.tools.Risk
import com.droidhands.core.tools.Scopes
import com.droidhands.core.tools.ToolNames

/** One app's actions in the per-action rules list; [ruleCount] of them have a rule. */
data class ActionRuleGroup(val packageName: String, val label: String, val actions: List<ActionInfo>, val ruleCount: Int)

/**
 * Pure logic of the app-action part of the per-tool rules editor (unit-tested). App actions are rules on
 * their tool name `<package>/<action id>` (docs/PLUGINS.md §9), the same map as the built-in tools' rules.
 */
object ActionRules {
    /**
     * Whether an agent with [scopes], risk cap [maxRisk] and [rules] can run app actions at all: the `apps`
     * scope, no BLOCK rule on `app_action_run`, and a cap that allows actions (as `AppActionCatalog.mayRunActions`).
     */
    fun canRunActions(scopes: Set<String>, maxRisk: Risk?, rules: Map<String, ToolRule>): Boolean =
        Scopes.allows(scopes, Scopes.APPS) && rules[ToolNames.APP_ACTION_RUN] != ToolRule.BLOCK &&
            !PolicyEngine.exceedsMaxRisk(maxRisk, Risk.ACTION)

    /**
     * The actions the agent could run, grouped by app (in [apps]' order): apps it may use under [access]
     * (its app access and the never-allow list), actions within its scopes and risk cap. With a [query], apps
     * whose name or package matches keep every action; otherwise only actions whose title or id match.
     */
    fun groups(
        apps: List<AppListing>,
        scopes: Set<String>,
        maxRisk: Risk?,
        rules: Map<String, ToolRule>,
        access: AppAccess,
        query: String = "",
    ): List<ActionRuleGroup> {
        if (!canRunActions(scopes, maxRisk, rules)) return emptyList()
        val needle = AppSelection.fold(query)
        return apps.mapNotNull { app ->
            if (!access.isAllowed(app.packageName)) return@mapNotNull null
            val usable = app.actions.filter { Scopes.allows(scopes, it.scope) && !PolicyEngine.exceedsMaxRisk(maxRisk, it.risk) }
            val shown = when {
                needle.isEmpty() -> usable
                AppSelection.fold(app.label).contains(needle) || app.packageName.lowercase().contains(needle) -> usable
                else -> usable.filter { AppSelection.fold(it.title).contains(needle) || it.id.contains(needle.replace(' ', '_')) }
            }
            if (shown.isEmpty()) return@mapNotNull null
            ActionRuleGroup(app.packageName, app.label, shown, shown.count { rules[it.toolName] != null })
        }
    }

    /** Every tool name in [groups], for [ToolRules.summary]. */
    fun names(groups: List<ActionRuleGroup>): Set<String> = groups.flatMapTo(HashSet()) { g -> g.actions.map { it.toolName } }

    /**
     * The choices that mean something for [action]: an action that runs a built-in tool (a `tool` recipe) is
     * approved by that tool's rule, so only Default and Block apply to its own name (docs/PLUGINS.md §6.5).
     */
    fun choices(action: ActionInfo): List<RuleChoice> =
        if (action.builtInTool != null) listOf(RuleChoice.DEFAULT, RuleChoice.BLOCK) else RuleChoice.entries

    /** What [choice] means for [action] under [policy]. */
    fun meaning(choice: RuleChoice, action: ActionInfo, policy: ConfirmPolicy): String = when {
        choice == RuleChoice.BLOCK -> ToolRules.choiceMeaning(choice, action.risk, policy)
        action.unreviewed -> "Always asks you first (unreviewed plugin)"
        action.builtInTool != null -> "Follows the ${action.builtInTool} rule"
        else -> ToolRules.choiceMeaning(choice, action.risk, policy)
    }

    /** "3 actions · 1 rule" for an app's header row. */
    fun groupSubtitle(group: ActionRuleGroup): String {
        val actions = if (group.actions.size == 1) "1 action" else "${group.actions.size} actions"
        return when (group.ruleCount) {
            0 -> actions
            1 -> "$actions · 1 rule"
            else -> "$actions · ${group.ruleCount} rules"
        }
    }
}
