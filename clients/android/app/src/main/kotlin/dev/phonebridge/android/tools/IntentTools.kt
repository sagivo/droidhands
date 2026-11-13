package dev.phonebridge.android.tools

import android.content.Context
import android.content.pm.PackageManager
import dev.phonebridge.android.tools.support.Launcher
import dev.phonebridge.android.tools.system.IntentFactory
import dev.phonebridge.android.tools.system.IntentSpecParser
import dev.phonebridge.android.tools.system.LaunchNotes
import dev.phonebridge.android.tools.system.putIfNotNull
import dev.phonebridge.android.tools.system.resolveActivityCompat
import dev.phonebridge.android.tools.system.truncate
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.Tool
import dev.phonebridge.core.tools.ToolException
import dev.phonebridge.core.tools.ToolResult
import dev.phonebridge.core.tools.objectSchema
import dev.phonebridge.core.tools.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.put

/** Tools: intent_start (docs/TOOLS.md). */
fun intentTools(context: Context): List<Tool> = listOf(intentStart(context))

private fun intentStart(context: Context) = tool(
    name = "intent_start",
    title = "Start an Android activity",
    description = "Advanced: starts any app screen with a raw Android intent, for things no other tool covers (e.g. " +
        "action android.settings.WIFI_SETTINGS, or VIEW with data and package). Short names are expanded: action " +
        "\"VIEW\" -> android.intent.action.VIEW, category \"BROWSABLE\" -> android.intent.category.BROWSABLE. class_name " +
        "may be relative to package (\".MainActivity\"). extras: string, number, boolean or array-of-strings values. " +
        "Cannot target PhoneBridge itself and never grants file/URI access. Prefer app_open/url_open when they fit.",
    scope = Scopes.INTENTS,
    risk = Risk.SENSITIVE,
    schema = objectSchema {
        string("action", "Intent action, e.g. android.intent.action.VIEW or VIEW", required = true)
        string("data", "Data URI, e.g. https://example.com or tel:123")
        string("mime_type", "MIME type, e.g. text/plain")
        string("package", "Target app package")
        string("class_name", "Activity class (needs package); may start with '.'")
        stringArray("categories", "Intent categories")
        obj("extras", "Extras as an object of string/number/boolean/array-of-strings values")
    },
    describe = { args ->
        runCatching { IntentSpecParser.describe(IntentSpecParser.parse(args, context.packageName)) }
            .getOrElse { "Start Android activity with arguments ${truncate(args.toString(), 500)}" }
    },
) { args, _ ->
    val spec = IntentSpecParser.parse(args, context.packageName)
    val intent = IntentFactory.build(spec)
    val resolved = withContext(Dispatchers.IO) {
        // Same matching startActivity uses (implicit intents implicitly carry CATEGORY_DEFAULT).
        context.packageManager.resolveActivityCompat(intent, PackageManager.MATCH_DEFAULT_ONLY)
    } ?: throw ToolException(
        "No activity on the phone matches this intent. Check action/data/mime_type/package/class_name " +
            "(apps_list shows installed packages).",
    )
    val target = resolved.activityInfo
    // An implicit intent must not land on PhoneBridge's own (possibly non-exported) screens either.
    if (target?.packageName == context.packageName) throw ToolException("intent_start cannot target PhoneBridge itself.")
    // Only explicit components can resolve to a non-exported activity (implicit resolution skips them).
    if (spec.className != null && target != null && !target.exported) {
        throw ToolException("${target.packageName}/${target.name} is private to its app (not exported), so it cannot be started from outside.")
    }
    Launcher.startActivity(context, intent)
    ToolResult.json {
        put("started", true)
        put("action", spec.action)
        target?.takeIf { it.packageName != "android" }?.let {
            put("package", it.packageName)
            put("activity", it.name)
        }
        if (target?.packageName == "android") put("chooser", true)
        putIfNotNull("note", LaunchNotes.afterLaunch(context))
    }
}
