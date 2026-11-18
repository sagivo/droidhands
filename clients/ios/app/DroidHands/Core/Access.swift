import Foundation

/// How dangerous a tool is (docs/TOOLS.md).
enum Risk: String, Codable, Comparable, CaseIterable {
    case READ, ACTION, SENSITIVE

    private var rank: Int { [.READ: 0, .ACTION: 1, .SENSITIVE: 2][self]! }
    static func < (a: Risk, b: Risk) -> Bool { a.rank < b.rank }
}

/// Which tool risks need on-phone approval for a token.
enum ConfirmPolicy: String, Codable {
    case NEVER, SENSITIVE, ALL_ACTIONS

    func needsApproval(_ risk: Risk) -> Bool {
        switch self {
        case .NEVER: return false
        case .SENSITIVE: return risk == .SENSITIVE
        case .ALL_ACTIONS: return risk != .READ
        }
    }
}

enum ToolRule: String, Codable, CaseIterable {
    /// Never ask.
    case ALLOW
    /// Always ask on the phone.
    case ASK
    /// Hidden from tools/list and refused.
    case BLOCK
}

/// Token scopes (docs/SECURITY.md). The names are shared with Android; iPhone offers the ones it has tools for.
enum Scopes {
    static let device = "device"
    static let apps = "apps"
    static let contactsRead = "contacts.read"
    static let calendarRead = "calendar.read"
    static let calendarWrite = "calendar.write"
    static let location = "location"
    static let messagesSend = "messages.send"
    static let calls = "calls"
    static let intents = "intents"

    /// Every scope an iPhone token can hold.
    static let all: Set<String> = Set(Ability.allCases.flatMap(\.scopes))

    static func allows(_ granted: Set<String>, _ required: String) -> Bool { required.isEmpty || granted.contains(required) }
}

/// What an agent may do, in plain words, as offered on the grant screen. Each ability is a fixed set of scopes;
/// together they cover every iPhone scope once.
enum Ability: String, CaseIterable, Identifiable {
    case readContactsCalendar, location, useApps, sendMessages, calls, changeCalendar, device, shortcuts

    var id: String { rawValue }

    var label: String {
        switch self {
        case .readContactsCalendar: return "Read contacts, calendar & reminders"
        case .location: return "Location"
        case .useApps: return "Open links and apps"
        case .sendMessages: return "Send messages"
        case .calls: return "Make calls"
        case .changeCalendar: return "Change calendar & reminders"
        case .device: return "Device"
        case .shortcuts: return "Run your shortcuts"
        }
    }

    var detail: String {
        switch self {
        case .readContactsCalendar: return "Look up contacts, read calendar events and reminders."
        case .location: return "Where the iPhone is right now."
        case .useApps: return "Open web pages, maps and other apps' links."
        case .sendMessages: return "Write a message for you. You still tap Send."
        case .calls: return "Start a call. iPhone asks you to tap Call."
        case .changeCalendar: return "Add calendar events, add and complete reminders."
        case .device: return "Battery and status, flashlight, the clipboard, speaking out loud."
        case .shortcuts: return "Run a shortcut from the Shortcuts app by name. A shortcut can do anything it was built to do."
        }
    }

    /// Completes "<agent> may / isn't allowed to …" in agent-facing text.
    var phrase: String {
        switch self {
        case .readContactsCalendar: return "read contacts, the calendar and reminders"
        case .location: return "see the phone's location"
        case .useApps: return "open links and apps"
        case .sendMessages: return "send messages"
        case .calls: return "make calls"
        case .changeCalendar: return "add calendar events and change reminders"
        case .device: return "use device features (status, flashlight, clipboard, speech)"
        case .shortcuts: return "run the user's shortcuts"
        }
    }

    var symbol: String {
        switch self {
        case .readContactsCalendar: return "person.crop.circle"
        case .location: return "location.fill"
        case .useApps: return "link"
        case .sendMessages: return "message.fill"
        case .calls: return "phone.fill"
        case .changeCalendar: return "calendar.badge.plus"
        case .device: return "iphone"
        case .shortcuts: return "square.2.layers.3d"
        }
    }

    var scopes: Set<String> {
        switch self {
        case .readContactsCalendar: return [Scopes.contactsRead, Scopes.calendarRead]
        case .location: return [Scopes.location]
        case .useApps: return [Scopes.apps]
        case .sendMessages: return [Scopes.messagesSend]
        case .calls: return [Scopes.calls]
        case .changeCalendar: return [Scopes.calendarWrite]
        case .device: return [Scopes.device]
        case .shortcuts: return [Scopes.intents]
        }
    }

    static func of(scope: String) -> Ability? { allCases.first { $0.scopes.contains(scope) } }
    static func scopes(_ abilities: some Sequence<Ability>) -> Set<String> { Set(abilities.flatMap(\.scopes)) }
    static func fullIn(_ scopes: Set<String>) -> [Ability] { allCases.filter { $0.scopes.isSubset(of: scopes) } }

    /// What "Only what I choose" starts with: looking things up and opening links, nothing that reaches other people.
    static let defaultChosen: Set<Ability> = [.readContactsCalendar, .location, .useApps, .device]
}

/// The grant screen's state for one agent (docs/SECURITY.md "Access"): Everything, or chosen abilities, plus
/// whether sensitive actions ask first.
struct GrantDraft: Equatable {
    enum Mode { case everything, chosen }

    var mode: Mode = .everything
    var abilities: Set<Ability> = Ability.defaultChosen
    var askBeforeSensitive = false

    var scopes: Set<String> { mode == .everything ? Scopes.all : Ability.scopes(abilities) }
    var confirmPolicy: ConfirmPolicy { askBeforeSensitive ? .SENSITIVE : .NEVER }
    var error: String? { mode == .chosen && abilities.isEmpty ? "Choose at least one thing it can do." : nil }

    init() {}

    init(record: TokenRecord) {
        mode = Scopes.all.isSubset(of: record.scopes) && record.maxRisk == nil ? .everything : .chosen
        abilities = Set(Ability.fullIn(record.scopes))
        askBeforeSensitive = record.confirmPolicy != .NEVER
    }

    func applied(to record: TokenRecord) -> TokenRecord {
        var r = record
        r.scopes = scopes
        r.confirmPolicy = confirmPolicy
        if mode == .everything { r.maxRisk = nil }
        return r
    }
}

/// Agent-facing refusals for requests outside an agent's grant. They are refused at once, never turned into a
/// prompt; the text names what is missing and where the user can add it.
enum GrantText {
    static func display(_ name: String) -> String {
        let t = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? "this agent" : t
    }

    static func accessPath(_ name: String) -> String { "DroidHands > Agents > \(display(name)) > Access" }

    private static func subject(_ name: String) -> String {
        let d = display(name)
        return d.prefix(1).uppercased() + d.dropFirst()
    }

    static let noWorkaround = "Do not try to work around this (for example with other tools); tell the user what to allow instead."

    static func missingScope(agent: String, scope: String, tool: String) -> String {
        if let ability = Ability.of(scope: scope) {
            return "\(subject(agent)) isn't allowed to \(ability.phrase) ('\(tool)' needs that). The user can allow it in \(accessPath(agent)). \(noWorkaround)"
        }
        return "\(subject(agent)) isn't allowed to use '\(tool)' (it needs the '\(scope)' scope). The user can allow it in \(accessPath(agent)). \(noWorkaround)"
    }

    static func toolBlocked(agent: String, tool: String) -> String {
        "The user blocked the tool '\(tool)' for \(display(agent)). The user can change this in DroidHands > Agents > \(display(agent)) > Tool rules. \(noWorkaround)"
    }

    static func aboveRiskCap(agent: String, tool: String) -> String {
        "\(subject(agent)) may only use read-only tools, so '\(tool)' is not available. The user can change this in \(accessPath(agent)). \(noWorkaround)"
    }
}
