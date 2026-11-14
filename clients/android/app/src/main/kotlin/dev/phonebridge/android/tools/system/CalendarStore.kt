package dev.phonebridge.android.tools.system

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import dev.phonebridge.core.tools.ToolException

/**
 * CalendarContract access. Every method is blocking provider IPC: call on Dispatchers.IO, after
 * checking READ_CALENDAR / WRITE_CALENDAR.
 */
internal class CalendarStore(private val context: Context) {

    fun calendars(): List<CalendarInfo> = guarded {
        context.contentResolver.query(Calendars.CONTENT_URI, CALENDAR_COLUMNS, null, null, "${Calendars._ID} ASC")?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val account = c.stringOrNull(2)
                    val owner = c.stringOrNull(4)
                    add(
                        CalendarInfo(
                            id = id,
                            name = c.stringOrNull(1) ?: account ?: "Calendar $id",
                            account = account,
                            accountType = c.stringOrNull(3),
                            writable = !c.isNull(5) && c.getInt(5) >= Calendars.CAL_ACCESS_CONTRIBUTOR,
                            // The provider derives IS_PRIMARY the same way when a sync adapter left it unset.
                            primary = if (c.isNull(6)) owner != null && owner == account else c.getInt(6) == 1,
                            visible = c.isNull(7) || c.getInt(7) == 1,
                        ),
                    )
                }
            }
        } ?: throw ToolException("The phone's calendar storage is not available.")
    }

    /**
     * Instances overlapping [range], widened by one day on each side: all-day instances are
     * stored at UTC midnight, so exact filtering happens in [CalendarSelection.select].
     * Hidden calendars and cancelled events are skipped.
     */
    fun instances(range: TimeRange): List<InstanceRow> = guarded {
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, range.start.toEpochMilli() - DAY_MS)
            ContentUris.appendId(it, range.end.toEpochMilli() + DAY_MS)
        }.build()
        val selection = "${Instances.VISIBLE}=1 AND (${Instances.STATUS} IS NULL OR ${Instances.STATUS}!=${Instances.STATUS_CANCELED})"
        context.contentResolver.query(uri, INSTANCE_COLUMNS, selection, null, "${Instances.BEGIN} ASC")?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        InstanceRow(
                            eventId = c.getLong(0),
                            title = c.stringOrNull(1),
                            beginMillis = c.getLong(2),
                            endMillis = c.getLong(3),
                            allDay = !c.isNull(4) && c.getInt(4) == 1,
                            location = c.stringOrNull(5),
                            description = c.stringOrNull(6),
                            calendarName = c.stringOrNull(7),
                            calendarId = c.getLong(8),
                        ),
                    )
                }
            }
        } ?: throw ToolException("The phone's calendar storage is not available.")
    }

    /** Inserts a single (non-recurring) event and returns its id. */
    fun insertEvent(calendarId: Long, title: String, plan: EventPlan, location: String?, description: String?): Long = guarded {
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId)
            put(Events.TITLE, title)
            put(Events.DTSTART, plan.dtStartMillis)
            put(Events.DTEND, plan.dtEndMillis)
            put(Events.EVENT_TIMEZONE, plan.timeZoneId)
            put(Events.ALL_DAY, if (plan.allDay) 1 else 0)
            if (plan.allDay) put(Events.EVENT_END_TIMEZONE, plan.timeZoneId)
            if (location != null) put(Events.EVENT_LOCATION, location)
            if (description != null) put(Events.DESCRIPTION, description)
        }
        val uri = context.contentResolver.insert(Events.CONTENT_URI, values)
            ?: throw ToolException("The calendar refused to create the event. Try another calendar_id (see calendar_list).")
        ContentUris.parseId(uri)
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: SecurityException) {
        throw ToolException(
            "Calendar permission not granted on the phone. Ask the user to open the PhoneBridge app > Settings > Permissions & access and grant Calendar.",
        )
    }

    private fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000

        val CALENDAR_COLUMNS = arrayOf(
            Calendars._ID,
            Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.ACCOUNT_NAME,
            Calendars.ACCOUNT_TYPE,
            Calendars.OWNER_ACCOUNT,
            Calendars.CALENDAR_ACCESS_LEVEL,
            Calendars.IS_PRIMARY,
            Calendars.VISIBLE,
        )

        val INSTANCE_COLUMNS = arrayOf(
            Instances.EVENT_ID,
            Instances.TITLE,
            Instances.BEGIN,
            Instances.END,
            Instances.ALL_DAY,
            Instances.EVENT_LOCATION,
            Instances.DESCRIPTION,
            Instances.CALENDAR_DISPLAY_NAME,
            Instances.CALENDAR_ID,
        )
    }
}
