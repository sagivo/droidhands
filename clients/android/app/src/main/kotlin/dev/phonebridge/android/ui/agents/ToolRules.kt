package dev.phonebridge.android.ui.agents

import dev.phonebridge.android.ui.tokens.TokenPresentation
import dev.phonebridge.core.auth.ConfirmPolicy
import dev.phonebridge.core.auth.ToolRule
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Tool

/** UI snapshot of a registered [Tool] (decoupled from the live registry). */
data class ToolInfo(val name: String, val title: String, val scope: String, val risk: Risk) {
    companion object {
        fun of(tool: Tool) = ToolInfo(tool.name, tool.title, tool.scope, tool.risk)
    }
}

/** The four choices per tool; [DEFAULT] means "no rule" (the confirmation policy decides). */
enum class RuleChoice(val label: String) {
    DEFAULT("Default"),
    ALLOW("Allow"),
    ASK("Ask"),
    BLOCK("Block"),
    ;

    val rule: ToolRule?
        get() = when (this) {
            DEFAULT -> null
            ALLOW -> ToolRule.ALLOW
            ASK -> ToolRule.ASK
            BLOCK -> ToolRule.BLOCK
        }

    companion object {
        fun of(rule: ToolRule?): RuleChoice = when (rule) {
            null -> DEFAULT
            ToolRule.ALLOW -> ALLOW
            ToolRule.ASK -> ASK
            ToolRule.BLOCK -> BLOCK
        }
    }
}

/** Tools of one scope, for a grouped list. */
data class ToolGroup(val scope: String, val tools: List<ToolInfo>)

/** Pure logic of the per-tool rules editor (unit-tested). */
object ToolRules {
    /**
     * Tools the agent can see with [scopes] and risk cap [maxRisk] (null = none), grouped by scope in
     * catalog order, tools by name. Always-allowed tools (empty scope, e.g. `bridge_capabilities`) are
     * left out: there is nothing useful to configure.
     */
    fun groups(tools: List<ToolInfo>, scopes: Set<String>, maxRisk: Risk? = null): List<ToolGroup> {
        val visible = tools.filter { it.scope.isNotEmpty() && it.scope in scopes && (maxRisk == null || it.risk <= maxRisk) }
        val order = TokenPresentation.orderedScopes(visible.mapTo(LinkedHashSet()) { it.scope })
        return order.map { scope -> ToolGroup(scope, visible.filter { it.scope == scope }.sortedBy { it.name }) }
    }

    /** Whether the token's [policy] asks before running a tool of [risk] when no rule is set. */
    fun policyAsks(risk: Risk, policy: ConfirmPolicy): Boolean = when (risk) {
        Risk.READ -> false
        Risk.ACTION -> policy == ConfirmPolicy.ALL_ACTIONS
        Risk.SENSITIVE -> policy != ConfirmPolicy.NEVER
    }

    /** What "Default" currently means for this tool, e.g. "Default: asks you first". */
    fun defaultMeaning(risk: Risk, policy: ConfirmPolicy): String =
        if (policyAsks(risk, policy)) "Default: asks you first" else "Default: runs without asking"

    /** Explanation of a non-default choice. */
    fun choiceMeaning(choice: RuleChoice, risk: Risk, policy: ConfirmPolicy): String = when (choice) {
        RuleChoice.DEFAULT -> defaultMeaning(risk, policy)
        RuleChoice.ALLOW -> "Never asks (still rate-limited)"
        RuleChoice.ASK -> "Always asks you first"
        RuleChoice.BLOCK -> "Hidden from the agent and refused"
    }

    /** Rules with [tool] set to [choice] ([RuleChoice.DEFAULT] removes the rule). */
    fun withChoice(rules: Map<String, ToolRule>, tool: String, choice: RuleChoice): Map<String, ToolRule> {
        val rule = choice.rule
        return if (rule == null) rules - tool else rules + (tool to rule)
    }

    /** "2 blocked · 1 always asks · 3 never ask", or "No rules" — counts only [visibleTools] if given. */
    fun summary(rules: Map<String, ToolRule>, visibleTools: Set<String>? = null): String {
        val counted = if (visibleTools == null) rules else rules.filterKeys { it in visibleTools }
        if (counted.isEmpty()) return "No rules: the approval setting decides"
        val blocked = counted.count { it.value == ToolRule.BLOCK }
        val ask = counted.count { it.value == ToolRule.ASK }
        val allow = counted.count { it.value == ToolRule.ALLOW }
        return listOfNotNull(
            blocked.takeIf { it > 0 }?.let { "$it blocked" },
            ask.takeIf { it > 0 }?.let { "$it always ${if (it == 1) "asks" else "ask"}" },
            allow.takeIf { it > 0 }?.let { "$it never ${if (it == 1) "asks" else "ask"}" },
        ).joinToString(" · ")
    }

    fun riskLabel(risk: Risk): String = when (risk) {
        Risk.READ -> "Read"
        Risk.ACTION -> "Action"
        Risk.SENSITIVE -> "Sensitive"
    }
}
