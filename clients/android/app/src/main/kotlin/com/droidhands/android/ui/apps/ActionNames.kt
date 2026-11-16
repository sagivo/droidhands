package com.droidhands.android.ui.apps

import com.droidhands.core.plugins.PluginRules
import java.util.Locale

/** A tool name as people read it: [title] ("Spotify – Play music"), and [raw] as the audit log and usage store it. */
data class ToolName(val title: String, val raw: String, val packageName: String? = null) {
    /** True for an app action (`<package>/<action id>`, docs/PLUGINS.md §9). */
    val isAppAction: Boolean get() = packageName != null
}

/**
 * Readable names for app actions in the activity log, the agent usage card and the rules editor (pure,
 * unit-tested). App actions are audited under `<package>/<action id>` (e.g. `com.spotify.music/play`);
 * [display] turns that into "<app> – <action title>" with what [Index] knows about installed apps, and
 * falls back to the package and a readable id for apps that are gone. Built-in tool names stay as they are.
 */
object ActionNames {
    /** Between the app and the action: "Spotify – Play music". */
    const val SEPARATOR = " – "

    /** The installed apps' labels and action titles, by package and by tool name. */
    class Index(apps: List<AppListing>) {
        private val labels: Map<String, String> = apps.associate { it.packageName to it.label }
        private val titles: Map<String, String> = apps.flatMap { app -> app.actions.map { it.toolName to it.title } }.toMap()

        fun label(packageName: String): String? = labels[packageName]

        fun title(toolName: String): String? = titles[toolName]

        companion object {
            val EMPTY = Index(emptyList())
        }
    }

    /** `com.spotify.music/play` → (`com.spotify.music`, `play`); null for anything else (built-in tools, methods). */
    fun parse(name: String): Pair<String, String>? {
        val slash = name.indexOf('/')
        if (slash <= 0 || slash != name.lastIndexOf('/')) return null
        val pkg = name.substring(0, slash)
        val id = name.substring(slash + 1)
        if (!PluginRules.PACKAGE_NAME.matches(pkg) || !PluginRules.ACTION_ID.matches(id)) return null
        return pkg to id
    }

    /** How [name] reads with what [index] knows. */
    fun display(name: String, index: Index = Index.EMPTY): ToolName {
        val (pkg, id) = parse(name) ?: return ToolName(name, name)
        val label = index.label(pkg) ?: pkg
        val title = index.title(name) ?: humanize(id)
        return ToolName(label + SEPARATOR + title, name, pkg)
    }

    /** `play_from_search` → `Play from search`. */
    fun humanize(id: String): String = id.replace('_', ' ').trim().replaceFirstChar { it.titlecase(Locale.ROOT) }
}
