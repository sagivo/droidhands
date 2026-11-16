package dev.phonebridge.android.ui.grant

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import dev.phonebridge.android.ui.apps.AppBadge
import dev.phonebridge.android.ui.apps.AppListing
import dev.phonebridge.android.ui.apps.AppSelection
import dev.phonebridge.android.ui.apps.AppsPresentation
import dev.phonebridge.core.auth.AppAccessMode
import dev.phonebridge.core.auth.AppPolicy
import dev.phonebridge.core.auth.ConfirmPolicy
import dev.phonebridge.core.auth.GrantDraft
import dev.phonebridge.core.auth.GrantMode
import dev.phonebridge.core.plugins.Integration
import dev.phonebridge.core.tools.Risk

/** One row of the grant's app picker. [listing] is null for a chosen app that is no longer installed. */
data class GrantAppRow(
    val packageName: String,
    val label: String,
    val listing: AppListing?,
    val badge: AppBadge?,
    val selected: Boolean,
    /** On the never-allow list: shown, but can't be chosen. */
    val locked: Boolean,
)

data class GrantAppSection(val key: String, val title: String?, val rows: List<GrantAppRow>)

/** Pure grouping and search for the grant's app picker (unit-tested). */
object GrantApps {
    const val LOCKED = "Never allowed for any agent (Settings > Security)"
    const val NOT_INSTALLED = "Not installed · tap to remove"

    /**
     * Without a query: "Integrations" (apps with a plugin) above "All apps", then "Not installed" for chosen
     * packages that are gone; with one, a single "Results" section (app name, package, plugin or action names,
     * as on the Apps tab). Never-allow apps show locked and unticked. Rows never move when ticked.
     */
    fun sections(apps: List<AppListing>, selected: Set<String>, neverAllow: Set<String>, query: String = ""): List<GrantAppSection> {
        fun row(app: AppListing): GrantAppRow {
            val locked = app.packageName in neverAllow
            return GrantAppRow(app.packageName, app.label, app, AppsPresentation.badge(app), selected = !locked && app.packageName in selected, locked = locked)
        }
        val installed = apps.mapTo(HashSet()) { it.packageName }
        val missing = selected.filter { it !in installed }.sorted().map { GrantAppRow(it, it, null, null, selected = true, locked = false) }
        val needle = AppSelection.fold(query)
        if (needle.isNotEmpty()) {
            val hits = apps.filter { AppsPresentation.matches(it, query) }.map(::row) + missing.filter { needle in it.packageName.lowercase() }
            return listOf(GrantAppSection("results", "Results", hits))
        }
        val (integrated, basic) = apps.partition { it.integration != Integration.BASIC }
        return buildList {
            if (integrated.isNotEmpty()) add(GrantAppSection("integrations", "Integrations", integrated.map(::row)))
            add(GrantAppSection("all", if (integrated.isEmpty()) null else "All apps", basic.map(::row)))
            if (missing.isNotEmpty()) add(GrantAppSection("missing", "Not installed", missing))
        }
    }

    /** Package → label, for summaries ("Allowed: WhatsApp, Gmail +2"). */
    fun labels(apps: List<AppListing>?): Map<String, String> = apps.orEmpty().associate { it.packageName to it.label }
}

/** Keeps a [GrantDraft] across configuration changes and process death (rememberSaveable). */
internal val GrantDraftSaver: Saver<GrantDraft, Any> = listSaver(
    save = { g ->
        listOf(g.mode.name, ArrayList(g.scopes), g.apps.mode.name, ArrayList(g.apps.packages), g.confirmPolicy.name, g.maxRisk?.name ?: "")
    },
    restore = { v ->
        @Suppress("UNCHECKED_CAST")
        GrantDraft(
            mode = GrantMode.valueOf(v[0] as String),
            scopes = (v[1] as List<String>).toSet(),
            apps = AppPolicy(AppAccessMode.valueOf(v[2] as String), (v[3] as List<String>).toSet()),
            confirmPolicy = ConfirmPolicy.valueOf(v[4] as String),
            maxRisk = (v[5] as String).takeIf { it.isNotEmpty() }?.let(Risk::valueOf),
        )
    },
)
