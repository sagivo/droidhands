package dev.phonebridge.android.tools

import android.Manifest
import android.content.Context
import dev.phonebridge.android.tools.support.Perms
import dev.phonebridge.android.tools.system.CalendarInfo
import dev.phonebridge.android.tools.system.CalendarSelection
import dev.phonebridge.android.tools.system.CalendarStore
import dev.phonebridge.android.tools.system.CalendarTime
import dev.phonebridge.android.tools.system.describeOr
import dev.phonebridge.android.tools.system.putIfNotNull
import dev.phonebridge.android.tools.system.quoteForSummary
import dev.phonebridge.android.tools.system.truncate
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.Tool
import dev.phonebridge.core.tools.ToolException
import dev.phonebridge.core.tools.ToolResult
import dev.phonebridge.core.tools.objectSchema
import dev.phonebridge.core.tools.optBool
import dev.phonebridge.core.tools.optInt
import dev.phonebridge.core.tools.optLong
import dev.phonebridge.core.tools.optString
import dev.phonebridge.core.tools.reqString
import dev.phonebridge.core.tools.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId

private const val MAX_TITLE_CHARS = 500
private const val MAX_TEXT_CHARS = 8_000

/** Tools: calendar_list, calendar_events_list, calendar_event_create (docs/TOOLS.md). */
fun calendarTools(context: Context): List<Tool> = listOf(calendarList(context), calendarEventsList(context), calendarEventCreate(context))

private fun calendarList(context: Context) = tool(
    name = "calendar_list",
    title = "List calendars",
    description = "Lists the phone's calendars as [{id,name,account,writable,primary,visible}]. Use an id as " +
        "calendar_event_create.calendar_id; by default events go to the primary writable calendar.",
    scope = Scopes.CALENDAR_READ,
    risk = Risk.READ,
    unavailable = { Perms.unavailableIfMissing(context, Manifest.permission.READ_CALENDAR) },
) { _, _ ->
    Perms.require(context, Manifest.permission.READ_CALENDAR)
    val calendars = withContext(Dispatchers.IO) { CalendarStore(context).calendars() }
    ToolResult.json(
        buildJsonArray {
            for (cal in calendars) {
                add(
                    buildJsonObject {
                        put("id", cal.id)
                        put("name", cal.name)
                        putIfNotNull("account", cal.account)
                        put("writable", cal.writable)
                        put("primary", cal.primary)
                        put("visible", cal.visible)
                    },
                )
            }
        },
    )
}

private fun calendarEventsList(context: Context) = tool(
    name = "calendar_events_list",
    title = "List calendar events",
    description = "Lists events (recurring events expanded) overlapping a time range, sorted by start. 'from' defaults to " +
        "now; give 'to' or 'days' (default 7). ISO-8601 like 2026-11-14T15:00 (phone's time zone unless an offset is " +
        "given) or 2026-11-14 (a date-only 'to' includes that day). 'query' filters title/location/description. Times are " +
        "returned with the phone's UTC offset; all-day events run from local midnight to the next local midnight (end is " +
        "exclusive). Hidden calendars and cancelled events are skipped.",
    scope = Scopes.CALENDAR_READ,
    risk = Risk.READ,
    schema = objectSchema {
        string("from", "Start of the range, ISO-8601 (default: now)")
        string("to", "End of the range, ISO-8601")
        integer("days", "Range length in days when 'to' is not given (default 7)", minimum = 1, maximum = CalendarTime.MAX_RANGE_DAYS)
        string("query", "Only events whose title, location or description contains this text")
        integer("limit", "Max events (default ${CalendarSelection.DEFAULT_LIMIT}, max ${CalendarSelection.MAX_LIMIT})", minimum = 1, maximum = CalendarSelection.MAX_LIMIT.toLong())
    },
    unavailable = { Perms.unavailableIfMissing(context, Manifest.permission.READ_CALENDAR) },
) { args, _ ->
    Perms.require(context, Manifest.permission.READ_CALENDAR)
    val zone = ZoneId.systemDefault()
    val range = CalendarTime.listRange(
        from = args.optString("from")?.let { CalendarTime.parse(it, "from", zone) },
        to = args.optString("to")?.let { CalendarTime.parse(it, "to", zone) },
        days = args.optInt("days"),
        now = Instant.now(),
        zone = zone,
    )
    val limit = (args.optInt("limit") ?: CalendarSelection.DEFAULT_LIMIT).coerceIn(1, CalendarSelection.MAX_LIMIT)
    val rows = withContext(Dispatchers.IO) { CalendarStore(context).instances(range) }
    val (events, more) = CalendarSelection.select(rows, range, args.optString("query"), limit, zone)
    ToolResult.json {
        put("from", CalendarTime.format(range.start, zone))
        put("to", CalendarTime.format(range.end, zone))
        put("count", events.size)
        put(
            "events",
            buildJsonArray {
                for (e in events) {
                    add(
                        buildJsonObject {
                            put("event_id", e.row.eventId)
                            put("title", e.row.title?.takeIf { it.isNotBlank() } ?: "(no title)")
                            put("start", CalendarTime.format(e.start))
                            put("end", CalendarTime.format(e.end))
                            put("all_day", e.row.allDay)
                            e.row.location?.takeIf { it.isNotBlank() }?.let { put("location", it) }
                            e.row.description?.takeIf { it.isNotBlank() }?.let {
                                put("description", truncate(it.trim(), CalendarSelection.MAX_DESCRIPTION_CHARS))
                            }
                            putIfNotNull("calendar", e.row.calendarName)
                            put("calendar_id", e.row.calendarId)
                        },
                    )
                }
            },
        )
        if (more) put("more", "More events match; narrow the range or raise 'limit' (max ${CalendarSelection.MAX_LIMIT}).")
    }
}

private fun calendarEventCreate(context: Context) = tool(
    name = "calendar_event_create",
    title = "Create calendar event",
    description = "Creates an event. 'start' is ISO-8601: 2026-11-14T15:00 (phone's time zone), with an offset " +
        "(2026-11-14T15:00+02:00), or a date (2026-11-14, which makes an all-day event unless all_day=false). Give 'end' " +
        "or 'duration_minutes' (default 60; all-day events default to one day, and a date-only 'end' is the last day). " +
        "Goes to the primary writable calendar unless calendar_id is given (see calendar_list). No reminders are added.",
    scope = Scopes.CALENDAR_WRITE,
    risk = Risk.ACTION,
    schema = objectSchema {
        string("title", "Event title", required = true)
        string("start", "Start, ISO-8601 date-time or date", required = true)
        string("end", "End, ISO-8601 date-time or date")
        integer("duration_minutes", "Length in minutes when 'end' is not given (default 60)", minimum = 1, maximum = CalendarTime.MAX_DURATION_MINUTES.toLong())
        boolean("all_day", "All-day event (default: true if 'start' is a date)")
        string("location", "Location")
        string("description", "Notes")
        integer("calendar_id", "Target calendar id from calendar_list (default: primary writable calendar)")
    },
    unavailable = { Perms.unavailableIfMissing(context, Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR) },
    describe = { args ->
        describeOr("Create a calendar event") {
            buildString {
                append("Create calendar event ").append(quoteForSummary(args.reqString("title"), 60))
                append(" at ").append(args.reqString("start"))
                args.optString("end")?.let { append(" until ").append(it) }
                    ?: args.optInt("duration_minutes")?.let { append(" (").append(it).append(" min)") }
            }
        }
    },
) { args, _ ->
    Perms.require(context, Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    val zone = ZoneId.systemDefault()
    val title = args.reqString("title").trim()
    if (title.length > MAX_TITLE_CHARS) throw ToolException("Argument 'title' is too long (max $MAX_TITLE_CHARS characters).")
    val location = args.optString("location")?.trim()?.takeIf { it.isNotEmpty() }?.also { checkLength("location", it) }
    val description = args.optString("description")?.trim()?.takeIf { it.isNotEmpty() }?.also { checkLength("description", it) }
    val plan = CalendarTime.planEvent(
        start = CalendarTime.parse(args.reqString("start"), "start", zone),
        end = args.optString("end")?.let { CalendarTime.parse(it, "end", zone) },
        durationMinutes = args.optInt("duration_minutes"),
        allDay = args.optBool("all_day"),
        zone = zone,
    )
    val requestedId = args.optLong("calendar_id")
    val store = CalendarStore(context)
    val (calendar, eventId) = withContext(Dispatchers.IO) {
        val target = chooseCalendar(store.calendars(), requestedId)
        target to store.insertEvent(target.id, title, plan, location, description)
    }
    ToolResult.json {
        put("event_id", eventId)
        put("title", title)
        put("start", plan.start)
        put("end", plan.end)
        put("all_day", plan.allDay)
        putIfNotNull("location", location)
        description?.let { put("description", truncate(it, CalendarSelection.MAX_DESCRIPTION_CHARS)) }
        put("calendar", calendar.name)
        put("calendar_id", calendar.id)
    }
}

private fun checkLength(name: String, value: String) {
    if (value.length > MAX_TEXT_CHARS) throw ToolException("Argument '$name' is too long (max $MAX_TEXT_CHARS characters).")
}

/** The requested calendar if it exists and is writable, else the default one. */
private fun chooseCalendar(calendars: List<CalendarInfo>, requestedId: Long?): CalendarInfo {
    val writable = calendars.filter { it.writable }
    fun options() = writable.take(10).joinToString("; ") { "${it.id}: ${it.name}${it.account?.let { a -> " ($a)" } ?: ""}" }
    if (requestedId != null) {
        val cal = calendars.firstOrNull { it.id == requestedId }
            ?: throw ToolException("No calendar with id $requestedId. Writable calendars: ${options().ifEmpty { "none" }}.")
        if (!cal.writable) throw ToolException("Calendar '${cal.name}' ($requestedId) is read-only. Writable calendars: ${options().ifEmpty { "none" }}.")
        return cal
    }
    return CalendarSelection.defaultCalendar(calendars) ?: throw ToolException(
        "The phone has no writable calendar. Ask the user to add an account with a calendar (for example a Google account) " +
            "or create a local calendar in their calendar app.",
    )
}
