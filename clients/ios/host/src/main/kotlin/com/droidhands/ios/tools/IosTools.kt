package com.droidhands.ios.tools

import com.droidhands.core.plugins.AppActionCatalog
import com.droidhands.core.plugins.AppResolution
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
import com.droidhands.ios.apps.AppMatcher
import com.droidhands.ios.apps.CheckedUrl
import com.droidhands.ios.apps.IosAppActions
import com.droidhands.ios.apps.IosLinks
import com.droidhands.ios.apps.LinkOpener
import com.droidhands.ios.apps.LinkOpener.Companion.screen
import com.droidhands.ios.inventory.IosApp
import com.droidhands.ios.runner.IosDevice
import com.droidhands.ios.runner.RunnerScreenDriver
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val MAX_LISTED_CANDIDATES = 10

private const val APP_ACCESS_NOTE =
    "The user may block some apps for this agent: blocked apps are not listed and can't be opened (don't try to work around it)."

private const val HIDDEN_APPS_FOOTER = "Some apps are hidden by this agent's app access settings."

private val BUNDLE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9-]*(\\.[A-Za-z0-9][A-Za-z0-9-]*)+$")

/**
 * The iPhone's `apps_list`, `app_open`, `url_open` and `device_status` (docs/TOOLS.md): same names, arguments and
 * result shapes as on Android, with `package` holding the bundle id. All honour the agent's app access.
 */
fun iosTools(apps: IosAppActions, device: IosDevice, links: LinkOpener, catalog: AppActionCatalog, inventorySource: String): List<Tool> =
    listOf(appsList(apps, catalog), appOpen(apps, device), urlOpen(apps, links), deviceStatus(apps, device, inventorySource))

private fun appsList(apps: IosAppActions, catalog: AppActionCatalog) = tool(
    name = "apps_list",
    title = "List installed apps",
    description = "Lists the iPhone's apps as [{package,label,version,system,integration,actions}], sorted by label ('package' is " +
        "the bundle id). 'actions' are the app's ready-made actions you may run with app_action_run (app_actions_list shows " +
        "their parameters and what else the app declares); 'integration' is plugin (a reviewed plugin), local (an unreviewed " +
        "plugin) or basic (what the app declares). 'query' filters by label or bundle id (case-insensitive substring). " +
        "include_system=false hides apps that come with iOS. $APP_ACCESS_NOTE",
    scope = Scopes.APPS,
    risk = Risk.READ,
    schema = objectSchema {
        string("query", "Substring of the app name or bundle id")
        boolean("include_system", "Include apps that come with iOS (default true)")
    },
) { args, ctx ->
    val query = args.optString("query")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val includeSystem = args.optBool("include_system") ?: true
    val matching = apps.installedApps().filter { app ->
        (includeSystem || !app.system) &&
            (query == null || app.label.lowercase().contains(query) || app.packageName.lowercase().contains(query))
    }
    val visible = matching.filter { ctx.appAccess.isAllowed(it.packageName) }
    val summaries = actionSummaries(catalog, visible, ctx)
    val result = ToolResult.json(
        buildJsonArray {
            for (app in visible) {
                add(
                    buildJsonObject {
                        put("package", app.packageName)
                        put("label", app.label)
                        app.versionName?.let { put("version", it) }
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
    if (visible.size < matching.size) result.copy(content = result.content + ToolContent.Text(HIDDEN_APPS_FOOTER)) else result
}

/** Each app's integration and runnable action ids; empty if that fails (apps_list still lists the apps). */
private suspend fun actionSummaries(catalog: AppActionCatalog, apps: List<InstalledApp>, ctx: CallContext): Map<String, AppSummary> = try {
    catalog.summarize(apps, ctx)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    emptyMap()
}

private fun appOpen(apps: IosAppActions, device: IosDevice) = tool(
    name = "app_open",
    title = "Open an app",
    description = "Opens an installed app by 'package' (its exact bundle id, e.g. com.spotify.client) or 'name' (the name under " +
        "its icon as the user says it; tolerant of case, accents and small typos), like tapping its icon: it comes back where it " +
        "was if it was running. If several apps match, nothing is opened and the error lists the candidates: ask the user, then " +
        "call again with 'package'. Use screen_observe afterwards to see the screen. $APP_ACCESS_NOTE",
    scope = Scopes.APPS,
    risk = Risk.ACTION,
    schema = objectSchema {
        string("package", "Bundle id, e.g. com.spotify.client")
        string("name", "App name as shown on the Home Screen, e.g. Spotify")
    },
    describe = { args -> "Open app: ${args.optString("package") ?: args.optString("name") ?: "?"}" },
) { args, ctx ->
    val target = resolveApp(apps, args.optString("package"), args.optString("name"), ctx.appAccess)
    ctx.ensureStillAllowed()
    screen { device.launchApp(target.bundleId) }
    val front = runCatching { device.foreground(listOf(target.bundleId)) }.getOrNull()
    ToolResult.json {
        put("opened", target.name)
        put("package", target.bundleId)
        if (front != null && front != target.bundleId) {
            put(
                "note",
                if (front == RunnerScreenDriver.SPRINGBOARD) {
                    "The Home Screen is still in front: iOS may be showing an alert or the iPhone may be locked. Use screen_observe."
                } else {
                    "Another app ($front) is in front: iOS may be showing something over ${target.name}. Use screen_observe."
                },
            )
        }
    }
}

/** The installed app named by [packageArg] or [nameArg] that the agent may open; ToolException otherwise. */
private suspend fun resolveApp(apps: IosAppActions, packageArg: String?, nameArg: String?, access: AppAccess): IosApp {
    val bundleId = packageArg?.trim()?.takeIf { it.isNotEmpty() }
    val name = nameArg?.trim()?.takeIf { it.isNotEmpty() }
    if (bundleId != null) {
        if (!BUNDLE_ID.matches(bundleId)) throw ToolException("'$bundleId' is not a valid bundle id. Pass the app's name as 'name' instead.")
        // Checked before the lookup, so a blocked app is refused the same way whether it is installed or not.
        access.require(bundleId)
        return apps.app(bundleId)
            ?: throw ToolException("No app with bundle id '$bundleId' is installed. Use apps_list (or app_open with 'name') to find it.")
    }
    if (name == null) throw ToolException("Pass 'package' (e.g. com.spotify.client) or 'name' (e.g. Spotify). apps_list shows installed apps.")
    val installed = apps.apps()
    fun ios(app: InstalledApp) = installed.first { it.bundleId == app.packageName }
    return when (val r = AppMatcher.resolve(name, installed.map(IosAppActions::installed), access::isAllowed)) {
        is AppResolution.Found -> ios(r.app)
        is AppResolution.Blocked -> {
            access.require(r.app.packageName, r.app.label)
            throw ToolException("${r.app.label} is not available to this agent.")
        }
        is AppResolution.Ambiguous -> throw ToolException(ambiguousMessage(name, r))
        is AppResolution.NotFound -> throw ToolException(
            "No installed app matches '$name'." +
                (if (r.suggestions.isNotEmpty()) " Closest: ${candidateList(r.suggestions)}." else "") +
                " Use apps_list to browse installed apps.",
        )
    }
}

private fun ambiguousMessage(name: String, r: AppResolution.Ambiguous): String {
    val only = r.candidates.singleOrNull()
    return when {
        only != null -> "'$name' matches ${only.label} (${only.packageName}) and apps the user has blocked for this agent. " +
            "If ${only.label} is the app the user means, call app_open with package '${only.packageName}'."
        r.someBlocked -> "Several apps you may use match '$name': ${candidateList(r.candidates)} (other matches are " +
            "blocked for this agent). Ask the user which one, then call app_open with its 'package'."
        else -> "Several apps match '$name': ${candidateList(r.candidates)}. Ask the user which one, then call app_open with its 'package'."
    }
}

private fun candidateList(apps: List<InstalledApp>): String =
    apps.take(MAX_LISTED_CANDIDATES).joinToString("; ") { "${it.label} (${it.packageName})" } +
        if (apps.size > MAX_LISTED_CANDIDATES) "; and ${apps.size - MAX_LISTED_CANDIDATES} more" else ""

private fun urlOpen(apps: IosAppActions, links: LinkOpener) = tool(
    name = "url_open",
    title = "Open a URL or deep link",
    description = "Opens a link on the iPhone: https:// pages (Safari, or the app whose site it is), mailto:, tel: (shows the call " +
        "prompt, does not call), maps: and other apps' own links (spotify:, …). iOS picks the app for a link; the result says " +
        "which app came to the front. 'package' (a bundle id) opens the link only if that installed app declares it (its own " +
        "scheme, or https on its own domains; app_actions_list shows them as open_link). javascript:, file:, data: and intent: " +
        "links are rejected. Refused when the app that would open the link is blocked for this agent.",
    scope = Scopes.APPS,
    risk = Risk.ACTION,
    schema = objectSchema {
        string("url", "The URL or deep link, e.g. https://maps.apple.com/?q=coffee or spotify:search:jazz", required = true)
        string("package", "Open only if this app declares the link, e.g. com.spotify.client")
    },
    describe = { args -> "Open link: ${runCatching { IosLinks.check(args.optString("url")).url }.getOrElse { args.optString("url") ?: "?" }.take(200)}" },
) { args, ctx ->
    val link = IosLinks.check(args.optString("url"))
    val bundleId = args.optString("package")?.trim()?.takeIf { it.isNotEmpty() }?.also {
        if (!BUNDLE_ID.matches(it)) throw ToolException("'$it' is not a valid bundle id.")
        // Before the lookup, so the answer doesn't depend on whether the blocked app is installed.
        ctx.appAccess.require(it)
    }
    val installed = apps.apps()
    val pinned = bundleId?.let { id ->
        installed.firstOrNull { it.bundleId == id }
            ?: throw ToolException("No app with bundle id '$id' is installed. Retry without 'package' to let iOS pick the app.")
    }
    if (pinned != null && apps.limits.infoPlist != null) {
        throw ToolException("Can't tell whether ${pinned.name} opens this link: ${apps.limits.infoPlist} Retry without 'package'.")
    }
    val opened = links.open(link, pinned, installed, ctx.appAccess, ctx)
    ToolResult.json {
        put("opened", link.url)
        opened.foreground?.let { front ->
            put("app", front)
            installed.firstOrNull { it.bundleId == front }?.let { put("app_label", it.name) }
        }
        urlNote(link, pinned, opened, installed)?.let { put("note", it) }
    }
}

/** What the agent should know about where the link went, or null when it opened where expected. */
private fun urlNote(link: CheckedUrl, pinned: IosApp?, opened: LinkOpener.Opened, installed: List<IosApp>): String? {
    val front = opened.foreground ?: return "The runner couldn't tell which app is in front; check with screen_observe."
    val frontName = installed.firstOrNull { it.bundleId == front }?.name ?: front
    return when {
        pinned != null && front != pinned.bundleId ->
            "iOS opened the link in $frontName, not in ${pinned.name}: the installed ${pinned.name} didn't take this link."
        front == RunnerScreenDriver.SPRINGBOARD && opened.handlers.isEmpty() && link.scheme != "https" ->
            "Nothing opened: no installed app declares '${link.scheme}:' links."
        front == RunnerScreenDriver.SPRINGBOARD -> "The Home Screen is in front: iOS opened nothing for this link, or is asking something. Use screen_observe."
        !opened.changed && opened.handlers.isEmpty() && link.scheme != "https" && link.scheme != "http" ->
            "The screen didn't switch apps: no installed app declares '${link.scheme}:' links, so iOS may have ignored it. Use screen_observe."
        else -> null
    }
}

private fun deviceStatus(apps: IosAppActions, device: IosDevice, inventorySource: String) = tool(
    name = "device_status",
    title = "Device status",
    description = "Snapshot of the iPhone: its name, model and iOS version, screen size, whether it is locked, the app in front, " +
        "and how DroidHands reaches it. Battery, network, volumes and storage aren't reported on iPhone.",
    scope = Scopes.DEVICE,
    risk = Risk.READ,
) { _, _ ->
    val status = screen { device.status() }
    val front = runCatching { device.foreground() }.getOrNull()
    val frontLabel = front?.let { id -> runCatching { apps.app(id)?.name }.getOrNull() }
    ToolResult.json {
        put("platform", "ios")
        status.name?.let { put("device_name", it) }
        status.model?.let { put("model", it) }
        status.osVersion?.let { put("ios_version", it) }
        status.screen?.let { s ->
            putJsonObject("screen") {
                put("width", s.width)
                put("height", s.height)
                status.scale?.let { put("scale", it) }
            }
        }
        status.locked?.let { put("locked", it) }
        front?.let { id ->
            put("foreground_app", id)
            frontLabel?.let { put("foreground_app_label", it) }
        }
        putJsonObject("bridge") {
            put("runner", "connected")
            status.runnerVersion?.let { put("runner_version", it) }
            put("app_inventory", inventorySource)
        }
        put("not_reported", "Battery, network, ringer, volumes and storage: iOS doesn't let the DroidHands runner read them.")
    }
}
