# Google Calendar

* `create_event` is an alias of the built-in `calendar_event_create` tool (scope `calendar.write`). It writes
  to the phone's calendar storage directly, which Google Calendar syncs; it does not open the app.
* `new_event` is the documented "insert an event" intent
  ([Calendar provider: intents](https://developer.android.com/identity/providers/calendar-provider#intents)):
  `ACTION_INSERT` with the extras `title`, `beginTime`/`endTime` (UTC epoch milliseconds), `allDay`,
  `eventLocation` and `description`. The documentation also sets the data to `Events.CONTENT_URI`, a
  `content:` URI, which plugin recipes may not use. The recipe sets that URI's MIME type,
  `vnd.android.cursor.dir/event`, instead: calendar apps declare `INSERT` filters on that MIME type with no
  scheme (see the AOSP Calendar manifest, `EditEventActivity`), so a type-only intent matches them.

Not tested on a device yet (`status: experimental`).
