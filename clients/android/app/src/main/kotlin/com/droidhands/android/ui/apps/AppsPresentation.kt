package com.droidhands.android.ui.apps

import android.content.pm.ApplicationInfo
import com.droidhands.android.Edition
import com.droidhands.android.ui.agents.ToolRules
import com.droidhands.android.update.UpdateLogic
import com.droidhands.core.plugins.Integration
import com.droidhands.core.plugins.PluginStatus
import com.droidhands.core.tools.Risk
import java.net.URLEncoder

/** What kind of support an app has, for the badge color. */
enum class BadgeKind { INTEGRATION, LOCAL, BASIC }

/** An app's badge: "Integration · 4 actions", plus [status] ("Experimental", "Beta") for plugins that aren't stable. */
data class AppBadge(val text: String, val kind: BadgeKind, val status: String? = null)

/** One row of the Apps list. */
data class AppsRow(val app: AppListing, val badge: AppBadge, val blocked: Boolean)

/** A titled group of rows (title null for a single untitled list). */
data class AppsSection(val key: String, val title: String?, val rows: List<AppsRow>)

/** Pure presentation of the Apps tab and the app detail page (unit-tested). */
object AppsPresentation {
    fun actionCount(n: Int): String = if (n == 1) "1 action" else "$n actions"

    /** What agents can still do with an app that has no ready-made actions; the store edition has no screen tools. */
    fun noActionsLine(label: String, phoneUse: Boolean = Edition.PHONE_USE): String =
        if (phoneUse) "Agents can still use $label through the screen tools (read the screen, tap, type)."
        else "Agents can still open $label, but can't act inside it."

    fun badge(app: AppListing): AppBadge {
        val count = actionCount(app.actions.size)
        return when (app.integration) {
            Integration.PLUGIN -> AppBadge("Integration · $count", BadgeKind.INTEGRATION, statusLabel(app.pluginStatus))
            Integration.LOCAL -> AppBadge("Local plugin · $count", BadgeKind.LOCAL)
            Integration.BASIC -> AppBadge("Basic · $count", BadgeKind.BASIC)
        }
    }

    /** "Experimental" / "Beta" for bundled plugins that aren't stable yet; null for stable ones. */
    fun statusLabel(status: PluginStatus?): String? = when (status) {
        PluginStatus.EXPERIMENTAL -> "Experimental"
        PluginStatus.BETA -> "Beta"
        PluginStatus.STABLE, null -> null
    }

    /**
     * Without a query: "Integrations" (bundled and local plugins) above "All apps" (discovered actions only);
     * with one, a single "Results" section. [blocked] are the never-allow packages (shown with a lock).
     */
    fun sections(apps: List<AppListing>, blocked: Set<String>, query: String = ""): List<AppsSection> {
        fun row(app: AppListing) = AppsRow(app, badge(app), app.packageName in blocked)
        if (AppSelection.fold(query).isNotEmpty()) {
            return listOf(AppsSection("results", "Results", apps.filter { matches(it, query) }.map(::row)))
        }
        val (integrated, basic) = apps.partition { it.integration != Integration.BASIC }
        return buildList {
            if (integrated.isNotEmpty()) add(AppsSection("integrations", "Integrations", integrated.map(::row)))
            add(AppsSection("all", if (integrated.isEmpty()) null else "All apps", basic.map(::row)))
        }
    }

    /** The app's name, package or plugin name, or one of its actions' titles, contains [query] (accent- and case-insensitive). */
    fun matches(app: AppListing, query: String): Boolean {
        val needle = AppSelection.fold(query)
        if (needle.isEmpty()) return true
        return AppSelection.fold(app.label).contains(needle) ||
            app.packageName.lowercase().contains(needle) ||
            app.pluginName?.let { AppSelection.fold(it).contains(needle) } == true ||
            app.actions.any { AppSelection.fold(it.title).contains(needle) }
    }

    /** "Agents can use 152 actions in 48 apps." plus how many have integrations. Blocked apps don't count. */
    fun summary(apps: List<AppListing>, blocked: Set<String>): String {
        val open = apps.filter { it.packageName !in blocked }
        val actions = open.sumOf { it.actions.size }
        val integrated = open.count { it.integration != Integration.BASIC }
        val head = "Agents can use ${actionCount(actions)} in ${if (open.size == 1) "1 app" else "${open.size} apps"}"
        return head + when (integrated) {
            0 -> "."
            1 -> ", 1 with an integration."
            else -> ", $integrated with integrations."
        }
    }

    /** What the app's kind of support means, for the top of its detail page. */
    fun integrationNote(app: AppListing): String = when (app.integration) {
        Integration.PLUGIN -> {
            val name = app.pluginName ?: app.label
            "Actions from the $name plugin, written for this app and reviewed by the DroidHands project." +
                if (app.pluginStatus == PluginStatus.EXPERIMENTAL) {
                    " It is marked experimental: agents are told to double-check the result."
                } else {
                    ""
                }
        }
        Integration.LOCAL ->
            "Actions from a local plugin you imported (Settings > Developer). It is not reviewed, so each of its actions " +
                "asks for your approval."
        Integration.BASIC ->
            "DroidHands found these actions in what the app declares (sharing, shortcuts and the like). A plugin can add " +
                "more, such as searching or playing inside the app."
    }

    /** The risk chip: Read, Action or Sensitive, as on the tool rules. */
    fun riskLabel(risk: Risk): String = ToolRules.riskLabel(risk)

    /**
     * Whether the action asks the user first whatever the agent's access says: only actions of unreviewed local
     * plugins. Everything else runs without asking for agents allowed to use the app (docs/SECURITY.md "Access").
     */
    fun needsApproval(action: ActionInfo): Boolean = action.unreviewed

    /** One line about approval and what really runs, or null when there is nothing to add. */
    fun approvalNote(action: ActionInfo): String? = when {
        action.unreviewed -> "Always asks you first: it comes from an unreviewed local plugin."
        action.builtInTool != null ->
            "Runs the ${action.builtInTool} tool, so that tool's rules apply too." +
                if (action.risk == Risk.SENSITIVE) " $ASKS_IF_SWITCH_ON" else ""
        action.risk == Risk.SENSITIVE -> ASKS_IF_SWITCH_ON
        else -> null
    }

    /** Sensitive actions ask only agents whose access has the "ask me before…" switch on. */
    const val ASKS_IF_SWITCH_ON = "Sensitive: asks you first only for agents set to \"Ask me before it sends messages, calls or pays\"."

    /** "query (required): What to play". */
    fun paramText(p: ActionParamInfo): String =
        p.name + listOfNotNull(p.type.takeIf { it != "string" }, "required".takeIf { p.required }).joinToString(", ").let {
            if (it.isEmpty()) "" else " ($it)"
        } + ": " + p.description

    /** The note for actions that open a screen where someone still has to finish. */
    const val OPENS_SCREEN = "Opens the right screen; someone still finishes it there."

    /**
     * The GitHub "new issue" link for an integration request ([repo] is the build's `GITHUB_REPO`), with the
     * issue form's fields prefilled (`.github/ISSUE_TEMPLATE/app-integration.yml`); null when the build names
     * no (valid) repository.
     */
    fun integrationRequestUrl(repo: String, label: String, packageName: String): String? {
        if (!UpdateLogic.isValidRepo(repo)) return null
        val query = listOf(
            "template" to "app-integration.yml",
            "title" to "App integration: $label",
            "app" to label,
            "package" to packageName,
        ).joinToString("&") { (k, v) -> "$k=${encode(v)}" }
        return "https://github.com/$repo/issues/new?$query"
    }

    /** Subject of the shared plugin draft. */
    fun draftSubject(label: String): String = "DroidHands plugin draft for $label (plugin.yaml)"

    /**
     * A plugin category (docs/PLUGINS.md §3) for the draft: from the app's Android category when it has one,
     * else from what it handles (media search: music; geo links: maps), else `other`.
     */
    fun draftCategory(androidCategory: Int, actionIds: Collection<String>): String = when (androidCategory) {
        ApplicationInfo.CATEGORY_AUDIO -> "music"
        ApplicationInfo.CATEGORY_VIDEO -> "video"
        ApplicationInfo.CATEGORY_IMAGE -> "photos"
        ApplicationInfo.CATEGORY_SOCIAL -> "social"
        ApplicationInfo.CATEGORY_NEWS -> "news"
        ApplicationInfo.CATEGORY_MAPS -> "maps"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "productivity"
        else -> when {
            "play_from_search" in actionIds -> "music"
            "show_on_map" in actionIds -> "maps"
            else -> "other"
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
