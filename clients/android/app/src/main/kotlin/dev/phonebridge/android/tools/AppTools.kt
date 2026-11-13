package dev.phonebridge.android.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import dev.phonebridge.android.tools.support.Launcher
import dev.phonebridge.android.tools.system.AppEntry
import dev.phonebridge.android.tools.system.AppMatch
import dev.phonebridge.android.tools.system.AppMatcher
import dev.phonebridge.android.tools.system.InstalledApps
import dev.phonebridge.android.tools.system.LaunchNotes
import dev.phonebridge.android.tools.system.UrlPolicy
import dev.phonebridge.android.tools.system.describeOr
import dev.phonebridge.android.tools.system.isValidPackageName
import dev.phonebridge.android.tools.system.packageInfoOrNull
import dev.phonebridge.android.tools.system.putIfNotNull
import dev.phonebridge.android.tools.system.resolveActivityCompat
import dev.phonebridge.android.tools.system.truncate
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.Tool
import dev.phonebridge.core.tools.ToolException
import dev.phonebridge.core.tools.ToolResult
import dev.phonebridge.core.tools.objectSchema
import dev.phonebridge.core.tools.optBool
import dev.phonebridge.core.tools.optString
import dev.phonebridge.core.tools.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val MAX_LISTED_CANDIDATES = 10

/** Tools: apps_list, app_open, url_open (docs/TOOLS.md). */
fun appTools(context: Context): List<Tool> = listOf(appsList(context), appOpen(context), urlOpen(context))

private fun appsList(context: Context) = tool(
    name = "apps_list",
    title = "List installed apps",
    description = "Lists launchable apps as [{package,label,version,system}], sorted by label. 'query' filters by label or " +
        "package (case-insensitive substring). include_system=false hides preinstalled system apps that were never updated.",
    scope = Scopes.APPS,
    risk = Risk.READ,
    schema = objectSchema {
        string("query", "Substring of the app label or package name")
        boolean("include_system", "Include preinstalled system apps (default true)")
    },
) { args, _ ->
    val query = args.optString("query")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val includeSystem = args.optBool("include_system") ?: true
    val apps = InstalledApps.launchable(context).filter { app ->
        (includeSystem || !app.preinstalledOnly) &&
            (query == null || app.label.lowercase().contains(query) || app.packageName.lowercase().contains(query))
    }
    ToolResult.json(
        buildJsonArray {
            for (app in apps) {
                add(
                    buildJsonObject {
                        put("package", app.packageName)
                        put("label", app.label)
                        putIfNotNull("version", app.versionName)
                        put("system", app.system)
                    },
                )
            }
        },
    )
}

private fun appOpen(context: Context) = tool(
    name = "app_open",
    title = "Open an app",
    description = "Opens an installed app by 'package' (exact) or 'name' (the label as the user says it; tolerant of case, " +
        "accents and small typos). If several apps match, nothing is opened and the error lists the candidates: ask the " +
        "user, then call again with 'package'. Use screen_read afterwards to see what is on screen.",
    scope = Scopes.APPS,
    risk = Risk.ACTION,
    schema = objectSchema {
        string("package", "Package name, e.g. com.whatsapp")
        string("name", "App name as shown on the home screen, e.g. WhatsApp")
    },
    describe = { args -> describeOr("Open an app") { "Open app: ${args.optString("package") ?: args.optString("name") ?: "?"}" } },
) { args, _ ->
    val target = resolveApp(context, args)
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

/** The app named by `package` or `name`; ToolException (with candidates) when absent or ambiguous. */
private suspend fun resolveApp(context: Context, args: JsonObject): AppEntry {
    val packageName = args.optString("package")?.trim()?.takeIf { it.isNotEmpty() }
    val name = args.optString("name")?.trim()?.takeIf { it.isNotEmpty() }
    if (packageName != null) {
        if (!isValidPackageName(packageName)) throw ToolException("'$packageName' is not a valid package name. Pass the app's label as 'name' instead.")
        val label = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.packageInfoOrNull(packageName)?.applicationInfo?.let { runCatching { it.loadLabel(pm).toString() }.getOrNull() ?: packageName }
        } ?: throw ToolException("No app with package '$packageName' is installed. Use apps_list (or app_open with 'name') to find it.")
        return AppEntry(packageName, label, null, false)
    }
    if (name == null) throw ToolException("Pass 'package' (e.g. com.whatsapp) or 'name' (e.g. WhatsApp). apps_list shows installed apps.")
    return when (val match = AppMatcher.match(name, InstalledApps.launchable(context))) {
        is AppMatch.Found -> match.app
        is AppMatch.Ambiguous -> throw ToolException(
            "Several apps match '$name': ${candidateList(match.candidates)}. Ask the user which one, then call app_open with its 'package'.",
        )
        is AppMatch.NotFound -> throw ToolException(
            "No installed app matches '$name'." +
                (if (match.suggestions.isNotEmpty()) " Closest: ${candidateList(match.suggestions)}." else "") +
                " Use apps_list to browse installed apps.",
        )
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
        "'package' forces a specific app. intent:, file:, content:, javascript: and data: links are rejected.",
    scope = Scopes.APPS,
    risk = Risk.ACTION,
    schema = objectSchema {
        string("url", "The URL or deep link, e.g. https://maps.google.com/?q=coffee or geo:0,0?q=coffee", required = true)
        string("package", "Open with this app only, e.g. com.android.chrome")
    },
    describe = { args -> describeOr("Open a link") { "Open link: ${truncate(UrlPolicy.check(args.optString("url")).url, 200)}" } },
) { args, _ ->
    val checked = UrlPolicy.check(args.optString("url"))
    val packageName = args.optString("package")?.trim()?.takeIf { it.isNotEmpty() }?.also {
        if (!isValidPackageName(it)) throw ToolException("'$it' is not a valid package name.")
    }
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(checked.url)).apply { packageName?.let(::setPackage) }
    val (intent, handler) = withContext(Dispatchers.IO) {
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
    Launcher.startActivity(context, intent)
    ToolResult.json {
        put("opened", checked.url)
        handler.activityInfo?.packageName?.takeIf { it != "android" }?.let { put("app", it) }
        putIfNotNull("note", LaunchNotes.afterLaunch(context))
    }
}
