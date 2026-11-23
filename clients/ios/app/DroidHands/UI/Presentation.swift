import Foundation

/// Activity's filters (Android's `ActivityQuery`): one agent, one outcome group and a search over tool names.
struct ActivityQuery: Equatable {
    /// An agent id, `ActivityQuery.noAgent` for callers without a valid token (failed sign-ins), or nil for all.
    var agent: String?
    var outcome: OutcomeFilter = .all
    var search = ""

    static let noAgent = "~no-token"

    var isFiltered: Bool { agent != nil || outcome != .all || !search.trimmingCharacters(in: .whitespaces).isEmpty }

    func matches(_ e: AuditEntry, ignoringOutcome: Bool = false) -> Bool {
        if let agent, (e.tokenId ?? ActivityQuery.noAgent) != agent { return false }
        if !ignoringOutcome && !outcome.matches(e) { return false }
        let needle = ActivityQuery.normalize(search)
        if needle.isEmpty { return true }
        return [e.tool ?? "", e.method, ActivityText.title(of: e)].contains { ActivityQuery.normalize($0).contains(needle) }
    }

    /// Lowercased, with `_` and `-` read as spaces, so "battery" and "device status" find `device_status`.
    static func normalize(_ s: String) -> String {
        s.lowercased().replacingOccurrences(of: "_", with: " ").replacingOccurrences(of: "-", with: " ")
            .trimmingCharacters(in: .whitespaces)
    }
}

enum OutcomeFilter: String, CaseIterable, Identifiable {
    case all = "All", ok = "OK", errors = "Errors", denied = "Denied"

    var id: String { rawValue }

    func matches(_ e: AuditEntry) -> Bool {
        switch self {
        case .all: return true
        case .ok: return e.outcome.category == .success
        case .errors: return e.outcome.category == .error
        case .denied: return e.outcome.category == .denied
        }
    }
}

/// Words and formats the activity screens share.
enum ActivityText {
    static func title(of e: AuditEntry) -> String {
        if e.outcome == .AUTH_FAILED { return "Sign-in refused" }
        return e.tool.flatMap { ToolCatalog.titles[$0] } ?? e.tool ?? e.method
    }

    static func agent(of e: AuditEntry, agents: [TokenRecord]) -> String {
        guard let id = e.tokenId else { return "Unknown caller" }
        if let live = agents.first(where: { $0.id == id }) { return live.name }
        return e.tokenName.map { "\($0) (deleted)" } ?? "Deleted agent"
    }

    /// "Today", "Yesterday", "Mon, Nov 23", or with the year for other years.
    static func dayHeader(_ day: Date, now: Date = Date()) -> String {
        let cal = Calendar.current
        if cal.isDateInToday(day) { return "Today" }
        if cal.isDateInYesterday(day) { return "Yesterday" }
        if cal.isDate(day, equalTo: now, toGranularity: .year) {
            return day.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day())
        }
        return day.formatted(.dateTime.year().month(.abbreviated).day())
    }

    /// Time only today, "Nov 23, 9:41 AM" this year, with the year before.
    static func smartTime(_ date: Date, now: Date = Date()) -> String {
        let cal = Calendar.current
        if cal.isDateInToday(date) { return date.formatted(date: .omitted, time: .shortened) }
        if cal.isDate(date, equalTo: now, toGranularity: .year) {
            return date.formatted(.dateTime.month(.abbreviated).day().hour().minute())
        }
        return date.formatted(date: .abbreviated, time: .shortened)
    }

    static func duration(_ ms: Int) -> String {
        if ms < 1000 { return "\(ms) ms" }
        if ms < 60_000 { return String(format: "%.1f s", Double(ms) / 1000) }
        return "\(ms / 60_000) min \((ms % 60_000) / 1000) s"
    }

    /// Entries grouped by local day, newest first.
    static func byDay(_ entries: [AuditEntry]) -> [(day: Date, entries: [AuditEntry])] {
        let cal = Calendar.current
        var groups: [(day: Date, entries: [AuditEntry])] = []
        for e in entries {
            let day = cal.startOfDay(for: e.timestamp)
            if groups.last?.day == day { groups[groups.count - 1].entries.append(e) } else { groups.append((day, [e])) }
        }
        return groups
    }

    struct Argument: Identifiable {
        let name: String
        let value: String
        var id: String { name }
    }

    /// The arguments as readable rows ("Duration: 5 min", dates in the user's format), or nil when they
    /// don't parse (cut off at 300 characters) or are empty; the screen then shows the raw text.
    static func arguments(_ preview: String?) -> [Argument]? {
        guard let preview, !preview.isEmpty, case .object(let o)? = try? JSON.parse(preview), !o.entries.isEmpty else { return nil }
        return o.entries.map { key, value in
            let unit = units.first { key.hasSuffix($0.suffix) }
            let base = unit.map { String(key.dropLast($0.suffix.count)) }.flatMap { $0.isEmpty ? nil : $0 } ?? key
            let text: String
            switch value {
            case .null: text = "–"
            case .bool(let b): text = b ? "Yes" : "No"
            case .string(let s): text = date(s) ?? s
            case .number: text = unit.map { "\(value.encoded) \($0.label)" } ?? value.encoded
            default: text = value.encoded
            }
            return Argument(name: humanize(base), value: text)
        }
    }

    /// "start_time" → "Start time".
    static func humanize(_ key: String) -> String {
        let words = key.replacingOccurrences(of: "_", with: " ").replacingOccurrences(of: "-", with: " ")
        return words.prefix(1).uppercased() + words.dropFirst()
    }

    /// A plain-text report of one entry, for "Copy details".
    static func report(_ e: AuditEntry, agents: [TokenRecord]) -> String {
        var lines = [title(of: e), "Result: \(e.outcome.label)"]
        if let detail = e.detail { lines.append("Reason: \(detail)") }
        lines.append("Agent: \(agent(of: e, agents: agents))")
        lines.append("Time: \(e.timestamp.formatted(date: .complete, time: .standard))")
        if let ms = e.durationMs { lines.append("Response time: \(duration(ms))") }
        lines.append(e.tool.map { "Tool: \($0)" } ?? "Method: \(e.method)")
        if let args = e.argsPreview, args != "{}" { lines.append("Arguments: \(args)") }
        return lines.joined(separator: "\n")
    }

    private static let units: [(suffix: String, label: String)] = [("_minutes", "min"), ("_seconds", "s"), ("_ms", "ms")]

    private static func date(_ s: String) -> String? {
        if s.count == 10, let d = dayParser.date(from: s) { return d.formatted(date: .abbreviated, time: .omitted) }
        guard s.count >= 16, s.dropFirst(10).first == "T" else { return nil }
        if let d = isoParser.date(from: s) ?? isoFractionParser.date(from: s) ?? localParser.date(from: String(s.prefix(19))) ?? localShortParser.date(from: String(s.prefix(16))) {
            return d.formatted(date: .abbreviated, time: .shortened)
        }
        return nil
    }

    private static let dayParser: DateFormatter = parser("yyyy-MM-dd")
    private static let localParser: DateFormatter = parser("yyyy-MM-dd'T'HH:mm:ss")
    private static let localShortParser: DateFormatter = parser("yyyy-MM-dd'T'HH:mm")
    private static let isoParser = ISO8601DateFormatter()
    private static let isoFractionParser: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    private static func parser(_ format: String) -> DateFormatter {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = format
        return f
    }
}

/// An agent's state, from its record.
enum AgentStatus {
    case active, expired, revoked

    init(_ a: TokenRecord, now: Date = Date()) {
        self = a.revoked ? .revoked : a.isExpired(at: now) ? .expired : .active
    }

    var label: String {
        switch self {
        case .active: return "Active"
        case .expired: return "Expired"
        case .revoked: return "Revoked"
        }
    }

    var order: Int {
        switch self {
        case .active: return 0
        case .expired: return 1
        case .revoked: return 2
        }
    }
}

/// How long a new or renewed token lasts (Android's expiry chips).
enum ExpiryChoice: String, CaseIterable, Identifiable {
    case never = "Never", day = "1 day", week = "7 days", month = "30 days", quarter = "90 days"

    var id: String { rawValue }

    var interval: TimeInterval? {
        switch self {
        case .never: return nil
        case .day: return 86_400
        case .week: return 7 * 86_400
        case .month: return 30 * 86_400
        case .quarter: return 90 * 86_400
        }
    }
}

extension TokenRecord {
    /// "claude.ai", "Agent sign-in" or "Token".
    var clientLabel: String {
        if origin == "pairing" { return clientHost ?? "Connected agent" }
        return "Token"
    }

    /// "Never expires", "Expires in 3 days", "Expired 2 days ago".
    func expiryText(now: Date = Date()) -> String {
        guard let expiresAt else { return "Never expires" }
        return expiresAt <= now ? "Expired \(expiresAt.relative)" : "Expires \(expiresAt.relative)"
    }
}

extension ActiveHours {
    /// "8:00 AM–11:00 PM" in the user's 12/24-hour setting.
    var label: String { "\(ActiveHours.time(startMinute))–\(ActiveHours.time(endMinute))" }

    static func time(_ minute: Int) -> String {
        let date = Calendar.current.date(bySettingHour: minute / 60, minute: minute % 60, second: 0, of: Date()) ?? Date()
        return date.formatted(date: .omitted, time: .shortened)
    }

    static func date(_ minute: Int) -> Date {
        Calendar.current.date(bySettingHour: minute / 60, minute: minute % 60, second: 0, of: Date()) ?? Date()
    }
}

extension Int {
    /// 1.2K, 3.4M.
    var compact: String { formatted(.number.notation(.compactName)) }
}
