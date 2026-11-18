import EventKit
import Foundation

private var store: EKEventStore { SharedStores.events }

private func calendarUnavailable(_ p: Permission) -> String? { p.status == .notAsked ? nil : p.unavailableReason }

private func eventJSON(_ e: EKEvent) -> JSON {
    .object(JSONObject([
        ("id", .string(e.eventIdentifier ?? e.calendarItemIdentifier)),
        ("title", .string(e.title ?? "")),
        ("start", .string(ISODates.string(e.startDate, allDay: e.isAllDay))),
        ("end", .string(ISODates.string(e.isAllDay ? e.endDate.addingTimeInterval(-1) : e.endDate, allDay: e.isAllDay))),
        ("all_day", .bool(e.isAllDay)),
        ("location", e.location.flatMap { $0.isEmpty ? nil : .string($0) }),
        ("calendar", .string(e.calendar.title)),
        ("notes", e.notes.flatMap { $0.isEmpty ? nil : .string(String($0.prefix(500))) }),
        ("url", e.url.map { .string($0.absoluteString) }),
    ]))
}

struct CalendarListTool: Tool {
    let name = "calendar_list"
    let title = "List calendars"
    let description = "The calendars on this iPhone: id, name, account, whether events can be added, and which one new events go to by default."
    let scope = Scopes.calendarRead
    let risk = Risk.READ
    let inputSchema = Schema.empty

    func unavailableReason() -> String? { calendarUnavailable(.calendar) }

    func run(_ args: Args, _ ctx: CallContext) async throws -> ToolResult {
        try await Permission.calendar.ensure()
        let defaultId = store.defaultCalendarForNewEvents?.calendarIdentifier
        let list: [JSON] = store.calendars(for: .event).map { c in
            .object(JSONObject([("id", .string(c.calendarIdentifier)), ("name", .string(c.title)), ("account", .string(c.source.title)),
                                ("writable", .bool(c.allowsContentModifications)), ("primary", .bool(c.calendarIdentifier == defaultId))]))
        }
        return .json(.array(list))
    }
}

struct CalendarEventsListTool: Tool {
    let name = "calendar_events_list"
    let title = "List calendar events"
    let description = "Events between two times (default: the next 7 days), optionally filtered by text in the title, location or notes."
    let scope = Scopes.calendarRead
    let risk = Risk.READ
    let inputSchema = Schema.object([
        ("from", Schema.string("ISO-8601 start (default now). Without a zone it is the phone's local time.")),
        ("to", Schema.string("ISO-8601 end")),
        ("days", Schema.integer("Days after 'from' when 'to' is not given (default 7)", min: 1, max: 366)),
        ("query", Schema.string("Text to look for in the title, location or notes")),
        ("limit", Schema.integer("Max events (default 50)", min: 1, max: 500)),
    ])

    func unavailableReason() -> String? { calendarUnavailable(.calendar) }

    func run(_ args: Args, _ ctx: CallContext) async throws -> ToolResult {
        let from = try args.string("from").map { try ISODates.require($0, "from").date } ?? Date()
        let days = try args.int("days", in: 1...366) ?? 7
        let to = try args.string("to").map { try ISODates.require($0, "to").date } ?? from.addingTimeInterval(Double(days) * 86400)
        guard to > from else { throw ToolError("'to' must be after 'from'.") }
        guard to.timeIntervalSince(from) <= 4 * 366 * 86400 else { throw ToolError("Ask for at most four years at a time.") }
        let query = try args.string("query").map(ContactBook.fold)
        let limit = try args.int("limit", in: 1...500) ?? 50
        try await Permission.calendar.ensure()
        var events = store.events(matching: store.predicateForEvents(withStart: from, end: to, calendars: nil)).sorted { $0.startDate < $1.startDate }
        if let query, !query.isEmpty {
            events = events.filter { e in [e.title, e.location, e.notes].compactMap { $0 }.contains { ContactBook.fold($0).contains(query) } }
        }
        let shown = events.prefix(limit)
        let footer = events.count > limit ? "\(events.count - limit) more events not shown; narrow the range or raise 'limit'." : (events.isEmpty ? "No events in that range." : nil)
        return .json(.array(shown.map(eventJSON)), footer: footer)
    }
}

struct CalendarEventCreateTool: Tool {
    let name = "calendar_event_create"
    let title = "Add calendar event"
    let description = "Adds an event to the calendar (by default the one iPhone uses for new events)."
    let scope = Scopes.calendarWrite
    let risk = Risk.ACTION
    let inputSchema = Schema.object([
        ("title", Schema.string("Event title")),
        ("start", Schema.string("ISO-8601 start; without a zone it is the phone's local time. A date alone with all_day.")),
        ("end", Schema.string("ISO-8601 end")),
        ("duration_minutes", Schema.integer("Length when 'end' is not given (default 60)", min: 1, max: 60 * 24 * 14)),
        ("all_day", Schema.boolean("An all-day event")),
        ("location", Schema.string("Where")),
        ("description", Schema.string("Notes")),
        ("calendar_id", Schema.string("From calendar_list (default: the default calendar)")),
    ], required: ["title", "start"])

    func describeCall(_ args: Args) -> String {
        "Add \(PromptText.quote((try? args.string("title")) ?? nil, max: 120)) on \(PromptText.clean(((try? args.string("start")) ?? nil) ?? "", max: 40))"
    }

    func unavailableReason() -> String? {
        let s = Permission.calendar.status
        return s == .notAsked || s == .limited ? nil : Permission.calendar.unavailableReason
    }

    func run(_ args: Args, _ ctx: CallContext) async throws -> ToolResult {
        let title = try args.requiredString("title")
        let start = try ISODates.require(try args.requiredString("start"), "start")
        let allDay = try args.bool("all_day") ?? start.dateOnly
        let end: Date
        if let e = try args.string("end") {
            let parsed = try ISODates.require(e, "end").date
            end = allDay && parsed <= start.date ? Calendar.current.date(byAdding: .day, value: 1, to: start.date)! : parsed
        } else if allDay {
            end = Calendar.current.date(byAdding: .day, value: 1, to: Calendar.current.startOfDay(for: start.date))!
        } else {
            end = start.date.addingTimeInterval(Double(try args.int("duration_minutes", in: 1...20160) ?? 60) * 60)
        }
        guard end > start.date || allDay else { throw ToolError("'end' must be after 'start'.") }
        if Permission.calendar.status != .limited { try await Permission.calendar.ensure() }
        let event = EKEvent(eventStore: store)
        event.title = title
        event.startDate = allDay ? Calendar.current.startOfDay(for: start.date) : start.date
        event.endDate = end
        event.isAllDay = allDay
        event.location = try args.string("location")
        event.notes = try args.string("description")
        if let id = try args.string("calendar_id") {
            guard let cal = store.calendar(withIdentifier: id), cal.allowsContentModifications else {
                throw ToolError("No writable calendar has the id '\(id)'. Call calendar_list.")
            }
            event.calendar = cal
        } else {
            guard let cal = store.defaultCalendarForNewEvents else { throw ToolError("This iPhone has no calendar to add events to.") }
            event.calendar = cal
        }
        try ctx.ensureStillAllowed()
        do {
            try store.save(event, span: .thisEvent, commit: true)
        } catch {
            throw ToolError("iPhone didn't save the event: \(error.localizedDescription)")
        }
        return .json(eventJSON(event))
    }
}

// MARK: Reminders

private func reminderJSON(_ r: EKReminder) -> JSON {
    let due = r.dueDateComponents.flatMap { c -> String? in
        guard let d = Calendar.current.date(from: c) else { return nil }
        return ISODates.string(d, allDay: c.hour == nil)
    }
    let priority: String
    switch r.priority {
    case 1...4: priority = "high"
    case 5: priority = "medium"
    case 6...9: priority = "low"
    default: priority = "none"
    }
    return .object(JSONObject([
        ("id", .string(r.calendarItemIdentifier)),
        ("title", .string(r.title ?? "")),
        ("due", due.map { .string($0) }),
        ("completed", .bool(r.isCompleted)),
        ("priority", .string(priority)),
        ("list", .string(r.calendar.title)),
        ("notes", r.notes.flatMap { $0.isEmpty ? nil : .string(String($0.prefix(500))) }),
    ]))
}

private func fetchReminders(_ predicate: NSPredicate) async -> [EKReminder] {
    await withCheckedContinuation { c in store.fetchReminders(matching: predicate) { c.resume(returning: $0 ?? []) } }
}

private func reminderList(named name: String?) throws -> EKCalendar? {
    guard let name, !name.isEmpty else { return nil }
    let lists = store.calendars(for: .reminder)
    if let match = lists.first(where: { ContactBook.fold($0.title) == ContactBook.fold(name) }) { return match }
    throw ToolError("No reminders list is called '\(name)'. Lists: \(lists.map(\.title).joined(separator: ", ")).")
}

struct RemindersListTool: Tool {
    let name = "reminders_list"
    let title = "List reminders"
    let description = "Reminders from the Reminders app: open ones by default, soonest due first. Each has an id for reminder_complete."
    let scope = Scopes.calendarRead
    let risk = Risk.READ
    let inputSchema = Schema.object([
        ("list", Schema.string("Only this list (by name)")),
        ("query", Schema.string("Text in the title or notes")),
        ("include_completed", Schema.boolean("Also completed ones (default false)")),
        ("limit", Schema.integer("Max reminders (default 50)", min: 1, max: 500)),
    ])

    func unavailableReason() -> String? { calendarUnavailable(.reminders) }

    func run(_ args: Args, _ ctx: CallContext) async throws -> ToolResult {
        let query = try args.string("query").map(ContactBook.fold)
        let includeCompleted = try args.bool("include_completed") ?? false
        let limit = try args.int("limit", in: 1...500) ?? 50
        try await Permission.reminders.ensure()
        let lists = try reminderList(named: try args.string("list")).map { [$0] }
        let predicate = includeCompleted ? store.predicateForReminders(in: lists)
            : store.predicateForIncompleteReminders(withDueDateStarting: nil, ending: nil, calendars: lists)
        var items = await fetchReminders(predicate)
        if let query, !query.isEmpty {
            items = items.filter { r in [r.title, r.notes].compactMap { $0 }.contains { ContactBook.fold($0).contains(query) } }
        }
        let far = Date.distantFuture
        items.sort { (a, b) in
            let da = a.dueDateComponents.flatMap { Calendar.current.date(from: $0) } ?? far
            let db = b.dueDateComponents.flatMap { Calendar.current.date(from: $0) } ?? far
            return da != db ? da < db : (a.title ?? "") < (b.title ?? "")
        }
        let footer = items.count > limit ? "\(items.count - limit) more not shown." : (items.isEmpty ? "No reminders match." : nil)
        return .json(.array(items.prefix(limit).map(reminderJSON)), footer: footer)
    }
}

struct ReminderCreateTool: Tool {
    let name = "reminder_create"
    let title = "Add reminder"
    let description = "Adds a reminder to the Reminders app, optionally with a due time (which also sets an alert)."
    let scope = Scopes.calendarWrite
    let risk = Risk.ACTION
    let inputSchema = Schema.object([
        ("title", Schema.string("What to remember")),
        ("due", Schema.string("ISO-8601 due date-time (alerts then), or a date alone. Without a zone it is the phone's local time.")),
        ("notes", Schema.string("Notes")),
        ("list", Schema.string("List name (default: the default list)")),
        ("priority", Schema.string("Priority", enum: ["none", "low", "medium", "high"])),
    ], required: ["title"])

    func describeCall(_ args: Args) -> String { "Add the reminder \(PromptText.quote((try? args.string("title")) ?? nil, max: 160))" }
    func unavailableReason() -> String? { calendarUnavailable(.reminders) }

    func run(_ args: Args, _ ctx: CallContext) async throws -> ToolResult {
        let title = try args.requiredString("title")
        let due = try args.string("due").map { try ISODates.require($0, "due") }
        let priority = try args.string("priority") ?? "none"
        guard ["none", "low", "medium", "high"].contains(priority) else { throw ToolError("'priority' must be none, low, medium or high.") }
        try await Permission.reminders.ensure()
        let reminder = EKReminder(eventStore: store)
        reminder.title = title
        reminder.notes = try args.string("notes")
        reminder.priority = ["none": 0, "high": 1, "medium": 5, "low": 9][priority]!
        guard let list = try reminderList(named: try args.string("list")) ?? store.defaultCalendarForNewReminders() else {
            throw ToolError("This iPhone has no reminders list to add to.")
        }
        reminder.calendar = list
        if let due {
            let units: Set<Calendar.Component> = due.dateOnly ? [.year, .month, .day] : [.year, .month, .day, .hour, .minute, .second]
            reminder.dueDateComponents = Calendar.current.dateComponents(units, from: due.date)
            if !due.dateOnly { reminder.addAlarm(EKAlarm(absoluteDate: due.date)) }
        }
        try ctx.ensureStillAllowed()
        do {
            try store.save(reminder, commit: true)
        } catch {
            throw ToolError("iPhone didn't save the reminder: \(error.localizedDescription)")
        }
        return .json(reminderJSON(reminder))
    }
}

struct ReminderCompleteTool: Tool {
    let name = "reminder_complete"
    let title = "Complete reminder"
    let description = "Marks a reminder done (or not done again). Get its id from reminders_list."
    let scope = Scopes.calendarWrite
    let risk = Risk.ACTION
    let inputSchema = Schema.object([
        ("id", Schema.string("The reminder's id from reminders_list")),
        ("completed", Schema.boolean("false to mark it not done (default true)")),
    ], required: ["id"])

    func unavailableReason() -> String? { calendarUnavailable(.reminders) }

    func run(_ args: Args, _ ctx: CallContext) async throws -> ToolResult {
        let id = try args.requiredString("id")
        let completed = try args.bool("completed") ?? true
        try await Permission.reminders.ensure()
        guard let reminder = store.calendarItem(withIdentifier: id) as? EKReminder else {
            throw ToolError("No reminder has the id '\(id)'. Call reminders_list for current ids.")
        }
        reminder.isCompleted = completed
        try ctx.ensureStillAllowed()
        do {
            try store.save(reminder, commit: true)
        } catch {
            throw ToolError("iPhone didn't save the reminder: \(error.localizedDescription)")
        }
        return .json(reminderJSON(reminder))
    }
}
