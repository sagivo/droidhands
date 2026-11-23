package com.droidhands.android.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.droidhands.android.Edition
import com.droidhands.android.tools.support.Launcher
import com.droidhands.android.tools.system.AppEntry
import com.droidhands.android.tools.system.AppPick
import com.droidhands.android.tools.system.AppVisibility
import com.droidhands.android.tools.system.InstalledApps
import com.droidhands.android.tools.system.LaunchGate
import com.droidhands.android.tools.system.LaunchNotes
import com.droidhands.android.tools.system.UrlPolicy
import com.droidhands.android.tools.system.describeOr
import com.droidhands.android.tools.system.isValidPackageName
import com.droidhands.android.tools.system.packageInfoOrNull
import com.droidhands.android.tools.system.putIfNotNull
import com.droidhands.android.tools.system.resolveActivityCompat
import com.droidhands.android.tools.system.truncate
import com.droidhands.core.plugins.AppActionCatalog
import com.droidhands.core.plugins.AppSummary
import com.droidhands.core.plugins.InstalledApp
import com.droidhands.core.tools.AppAccess
import com.droidhands.core.tools.CallContext
import com.droidhands.core.tools.Risk
import com.droidhands.core.tools.Scopes
import com.droidhands.core.tools.Tool
import com.droidhands.core.tools.ToolContent
import com.droidhands.core.tools.ToolException
import com.droidhands.core.tools.ToolResult
import com.droidhands.core.tools.objectSchema
import com.droidhands.core.tools.optBool
import com.droidhands.core.tools.optString
import com.droidhands.core.tools.tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private const val MAX_LISTED_CANDIDATES = 10

private const val APP_ACCESS_NOTE =
    "The user may block some apps for this agent: blocked apps are not listed and can't be opened (don't try to work around it)."

private const val HIDDEN_APPS_FOOTER = "Some apps are hidden by this agent's app access settings."

/**
 * Tools: apps_list, app_open, url_open (docs/TOOLS.md). All honour the agent's app access (docs/SECURITY.md).
 * [actions] gives apps_list each app's integration and the ids of the app actions the agent may run (docs/PLUGINS.md).
 */
fun appTools(context: Context, actions: AppActionCatalog): List<Tool> = listOf(appsList(context, actions), appOpen(context), urlOpen(context))

private fun appsList(context: Context, actions: AppActionCatalog) = tool(
    name = "apps_list",
    title = "List installed apps",
    description = "Lists launchable apps as [{package,label,version,system,integration,actions}], sorted by label. 'actions' " +
        "are the app's ready-made actions you may run with app_action_run (app_actions_list shows their parameters); " +
        "'integration' is plugin (a reviewed plugin), local (an unreviewed plugin imported on the phone) or basic (what the " +
        "app declares). 'query' filters by label or package (case-insensitive substring). include_system=false hides " +
        "preinstalled system apps that were never updated. $APP_ACCESS_NOTE",
    scope = Scopes.APPS,
    risk = Risk.READ,
    schema = objectSchema {
        string("query", "Substring of the app label or package name")
        boolean("include_system", "Include preinstalled system apps (default true)")
    },
) { args, ctx ->
    val query = args.optString("query")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val includeSystem = args.optBool("include_system") ?: true
    val matching = InstalledApps.launchable(context).filter { app ->
        app.packageName != context.packageName &&
            (includeSystem || !app.preinstalledOnly) &&
            (query == null || app.label.lowercase().contains(query) || app.packageName.lowercase().contains(query))
    }
    val apps = AppVisibility.visible(matching) { LaunchGate.isAllowed(context, ctx.appAccess, it) }
    val summaries = actionSummaries(actions, apps, ctx)
    val result = ToolResult.json(
        buildJsonArray {
            for (app in apps) {
                add(
                    buildJsonObject {
                        put("package", app.packageName)
                        put("label", app.label)
                        putIfNotNull("version", app.versionName)
                        put("system", app.system)
                        summaries[app.packageName]?.let { summary ->
                            put("integration", summary.integration.id)
                            putJsonArray("actions") { summary.actionIds.forEach { add(JsonPrimitive(it)) } }
                        }
                    },
                )
            }
        },
    )
    // Say that the list is incomplete (never which apps), so the agent doesn't conclude an app isn't installed.
    if (apps.size < matching.size) result.copy(content = result.content + ToolContent.Text(HIDDEN_APPS_FOOTER)) else result
}

/**
 * Each of [apps]' integration and runnable action ids ([AppActionCatalog.summarize]). Empty if the scan fails:
 * apps_list still lists the apps.
 */
private suspend fun actionSummaries(actions: AppActionCatalog, apps: List<AppEntry>, ctx: CallContext): Map<String, AppSummary> = try {
    actions.summarize(apps.map { InstalledApp(it.packageName, it.label, it.versionName, it.system) }, ctx)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w("DroidHands/Apps", "Could not list app actions", e)
    emptyMap()
}

private fun appOpen(context: Context) = tool(
    name = "app_open",
    title = "Open an app",
    description = "Opens an installed app by 'package' (exact) or 'name' (the label as the user says it; tolerant of case, " +
        "accents and small typos). If several apps match, nothing is opened and the error lists the candidates: ask the " +
        "user, then call again with 'package'. " + (if (Edition.PHONE_USE) "Use screen_read afterwards to see what is on screen. " else "") +
        APP_ACCESS_NOTE,
    scope = Scopes.APPS,
    risk = Risk.ACTION,
    schema = objectSchema {
        string("package", "Package name, e.g. com.whatsapp")
        string("name", "App name as shown on the home screen, e.g. WhatsApp")
    },
    describe = { args -> describeOr("Open an app") { "Open app: ${args.optString("package") ?: args.optString("name") ?: "?"}" } },
) { args, ctx ->
    val target = resolveApp(context, args, ctx.appAccess)
    val launch = withContext(Dispatchers.IO) { context.packageManager.getLaunchIntentForPackage(target.packageName) }
        ?: throw ToolException("${target.label} (${target.packageName}) has no screen that can be opened from the launcher.")
    // Same as tapping the home-screen icon: resume the app's existing task if it has one.
    launch.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    Launcher.startActivity(context, launch)
    ToolResult.json {
        put("opened", target.label)
        put("package", target.packageName)
        putIfNotNull("note", LaunchNotes.afterLaunch(context))
    }
}

/**
 * The app named by `package` or `name` that the agent may open; ToolException (with candidates)
 * when absent, ambiguous or blocked by [access].
 */
private suspend fun resolveApp(context: Context, args: JsonObject, access: AppAccess): AppEntry {
    val packageName = args.optString("package")?.trim()?.takeIf { it.isNotEmpty() }
    val name = args.optString("name")?.trim()?.takeIf { it.isNotEmpty() }
    if (packageName != null) {
        if (!isValidPackageName(packageName)) throw ToolException("'$packageName' is not a valid package name. Pass the app's label as 'name' instead.")
        val label = withContext(Dispatchers.IO) {
            // Checked before the lookup, so a blocked app is refused the same way whether it is installed or not.
            LaunchGate.requireApp(context, access, packageName)
            val pm = context.packageManager
            pm.packageInfoOrNull(packageName)?.applicationInfo?.let { runCatching { it.loadLabel(pm).toString() }.getOrNull() ?: packageName }
        } ?: throw ToolException("No app with package '$packageName' is installed. Use apps_list (or app_open with 'name') to find it.")
        return AppEntry(packageName, label, null, false)
    }
    if (name == null) throw ToolException("Pass 'package' (e.g. com.whatsapp) or 'name' (e.g. WhatsApp). apps_list shows installed apps.")
    val apps = InstalledApps.launchable(context)
    return when (val pick = AppVisibility.pick(name, apps) { LaunchGate.isAllowed(context, access, it) }) {
        is AppPick.Found -> pick.app
        is AppPick.Blocked -> withContext(Dispatchers.IO) {
            LaunchGate.requireApp(context, access, pick.app.packageName)
            // Only reached if the access settings changed between the two checks.
            throw ToolException("${pick.app.label} is not available to this agent.")
        }
        is AppPick.Ambiguous -> throw ToolException(ambiguousMessage(name, pick))
        is AppPick.NotFound -> throw ToolException(
            "No installed app matches '$name'." +
                (if (pick.suggestions.isNotEmpty()) " Closest: ${candidateList(pick.suggestions)}." else "") +
                " Use apps_list to browse installed apps.",
        )
    }
}

private fun ambiguousMessage(name: String, pick: AppPick.Ambiguous): String {
    val only = pick.candidates.singleOrNull()
    return when {
        only != null -> "'$name' matches ${only.label} (${only.packageName}) and apps the user has blocked for this agent. " +
            "If ${only.label} is the app the user means, call app_open with package '${only.packageName}'."
        pick.someBlocked -> "Several apps you may use match '$name': ${candidateList(pick.candidates)} (other matches are " +
            "blocked for this agent). Ask the user which one, then call app_open with its 'package'."
        else -> "Several apps match '$name': ${candidateList(pick.candidates)}. Ask the user which one, then call app_open with its 'package'."
    }
}

private fun candidateList(apps: List<AppEntry>): String =
    apps.take(MAX_LISTED_CANDIDATES).joinToString("; ") { "${it.label} (${it.packageName})" } +
        if (apps.size > MAX_LISTED_CANDIDATES) "; and ${apps.size - MAX_LISTED_CANDIDATES} more" else ""

private fun urlOpen(context: Context) = tool(
    name = "url_open",
    title = "Open a URL or deep link",
    description = "Opens a link on the phone with the app that handles it: https:// pages (browser or the matching app), " +
        "geo:, mailto:, tel: (opens the dialer, does not call), market:, whatsapp:, tg: and other app deep links. " +
        "'package' forces a specific app. intent:, file:, content:, javascript: and data: links are rejected. Refused when " +
        "the app that would open the link is blocked for this agent; if several apps could open it and some are blocked, " +
        "the error lists the allowed ones to pass as 'package'.",
    scope = Scopes.APPS,
    risk = Risk.ACTION,
    schema = objectSchema {
        string("url", "The URL or deep link, e.g. https://maps.google.com/?q=coffee or geo:0,0?q=coffee", required = true)
        string("package", "Open with this app only, e.g. com.android.chrome")
    },
    describe = { args -> describeOr("Open a link") { "Open link: ${truncate(UrlPolicy.check(args.optString("url")).url, 200)}" } },
) { args, ctx ->
    val checked = UrlPolicy.check(args.optString("url"))
    val packageName = args.optString("package")?.trim()?.takeIf { it.isNotEmpty() }?.also {
        if (!isValidPackageName(it)) throw ToolException("'$it' is not a valid package name.")
    }
    // Before resolving, so the answer doesn't depend on whether the blocked app could open the link.
    if (packageName != null) withContext(Dispatchers.IO) { LaunchGate.requireApp(context, ctx.appAccess, packageName) }
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(checked.url)).apply { packageName?.let(::setPackage) }
    val (intent, resolved) = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        // Prefer handlers that declare themselves safe to open from links (as browsers do); not
        // every deep-link handler declares BROWSABLE, so fall back to any handler.
        val browsable = Intent(view).addCategory(Intent.CATEGORY_BROWSABLE)
        pm.resolveActivityCompat(browsable, PackageManager.MATCH_DEFAULT_ONLY)?.let { return@withContext browsable to it }
        pm.resolveActivityCompat(view, PackageManager.MATCH_DEFAULT_ONLY)?.let { return@withContext view to it }
        throw ToolException(
            if (packageName != null) {
                "$packageName cannot open this link (or is not installed). Retry without 'package'."
            } else {
                "No app on the phone can open '${checked.scheme}:' links. The app for this link may not be installed."
            },
        )
    }
    val handler = LaunchGate.check(context, intent, resolved, ctx.appAccess, "this link")
    Launcher.startActivity(context, intent)
    ToolResult.json {
        put("opened", checked.url)
        handler?.let { put("app", it.packageName) }
        if (handler == null) put("chooser", true)
        putIfNotNull("note", LaunchNotes.afterLaunch(context, chooser = handler == null))
    }
}
