package dev.phonebridge.android.tools

import android.Manifest
import android.content.Context
import dev.phonebridge.android.tools.comms.CommsArgs
import dev.phonebridge.android.tools.support.ContactLookup
import dev.phonebridge.android.tools.support.Perms
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.Tool
import dev.phonebridge.core.tools.ToolResult
import dev.phonebridge.core.tools.objectSchema
import dev.phonebridge.core.tools.reqString
import dev.phonebridge.core.tools.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray

/** Tools: contacts_search (docs/TOOLS.md). */
fun contactTools(context: Context): List<Tool> = listOf(contactsSearch(context))

private const val SEARCH_DEFAULT_LIMIT = 10
private const val SEARCH_MAX_LIMIT = 50

private fun contactsSearch(context: Context): Tool = tool(
    name = "contacts_search",
    title = "Search contacts",
    description = "Search the phone's contacts by name, part of a name, nickname, phone number or email. Name matching " +
        "tolerates other spellings and scripts (Tania ~ Tanya ~ טניה ~ Таня), accents and word order. Returns " +
        "[{id,name,phones:[{number,type,normalized}],emails,apps:[{package,app,entries}],starred}], best matches first " +
        "(exact and prefix matches, then fuzzy ones). 'apps' lists the apps the contact is on (messaging and calling apps " +
        "that add themselves to the address book), with their entries such as \"Message +1 555…\". SMS and call tools " +
        "accept 'contact' directly; use this " +
        "to look someone up or to disambiguate.",
    scope = Scopes.CONTACTS_READ,
    risk = Risk.READ,
    schema = objectSchema {
        string("query", "Name, part of a name, nickname, phone number (any format) or email address.", required = true)
        integer(
            "limit", "Maximum number of contacts to return (default $SEARCH_DEFAULT_LIMIT, max $SEARCH_MAX_LIMIT).",
            minimum = 1, maximum = SEARCH_MAX_LIMIT.toLong(),
        )
    },
    unavailable = { Perms.unavailableIfMissing(context, Manifest.permission.READ_CONTACTS) },
) { args, ctx ->
    val query = args.reqString("query")
    val limit = CommsArgs.limit(args, SEARCH_DEFAULT_LIMIT, SEARCH_MAX_LIMIT)
    val matches = withContext(Dispatchers.IO) { ContactLookup.search(context, query, limit) }
    // Apps blocked for this agent are never named, not even as "this contact is on it".
    val visible = matches.map { m -> m.copy(apps = m.apps.filter { ctx.appAccess.isAllowed(it.packageName) }) }
    ToolResult.json(JsonArray(visible.map(ContactLookup::toJson)))
}
