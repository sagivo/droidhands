import Foundation

enum AuditOutcome: String, Codable {
    case OK, TOOL_ERROR, DENIED_SCOPE, DENIED_POLICY, DENIED_USER, PAUSED, TIMEOUT, UNKNOWN_TOOL, AUTH_FAILED, CANCELLED, EXCEPTION

    var isSuccess: Bool { self == .OK }

    var label: String {
        switch self {
        case .OK: return "Done"
        case .TOOL_ERROR: return "Failed"
        case .DENIED_SCOPE: return "Outside its access"
        case .DENIED_POLICY: return "Blocked by a rule"
        case .DENIED_USER: return "You denied it"
        case .PAUSED: return "Access paused"
        case .TIMEOUT: return "Timed out"
        case .UNKNOWN_TOOL: return "Unknown tool"
        case .AUTH_FAILED: return "Sign-in failed"
        case .CANCELLED: return "Stopped"
        case .EXCEPTION: return "Error"
        }
    }
}

/// One entry of the activity log. Arguments are kept as a short preview, never the full payload.
struct AuditEntry: Codable, Identifiable, Equatable {
    var id = UUID()
    var timestamp: Date
    var tokenId: String?
    var tokenName: String?
    var method: String
    var tool: String?
    var argsPreview: String?
    var outcome: AuditOutcome
    var durationMs: Int?
    var detail: String?
}

/// The activity log: newest first, at most [limit] entries, saved as JSON. Thread-safe.
final class AuditLog {
    private let lock = NSLock()
    private let file: JSONFile<[AuditEntry]>
    private let limit: Int
    private var entries: [AuditEntry]
    private var saveScheduled = false
    /// Refusals per source in the current minute, so a misbehaving agent can't flood the log.
    private var refusals: [String: (minute: Int, count: Int)] = [:]

    var onChange: (([AuditEntry]) -> Void)?

    init(file: JSONFile<[AuditEntry]>, limit: Int = 2000) {
        self.file = file
        self.limit = limit
        entries = (try? file.load()) ?? []
    }

    var all: [AuditEntry] { lock.withLock { entries } }

    func record(_ entry: AuditEntry) {
        let snapshot: [AuditEntry] = lock.withLock {
            entries.insert(entry, at: 0)
            if entries.count > limit { entries.removeLast(entries.count - limit) }
            return entries
        }
        scheduleSave()
        onChange?(snapshot)
    }

    /// Records a refusal unless [source] already had 20 this minute.
    func recordRefusal(_ entry: AuditEntry, source: String) {
        let minute = Int(entry.timestamp.timeIntervalSince1970 / 60)
        let allowed: Bool = lock.withLock {
            let current = refusals[source]
            let count = current?.minute == minute ? current!.count + 1 : 1
            refusals[source] = (minute, count)
            if refusals.count > 500 { refusals = refusals.filter { $0.value.minute == minute } }
            return count <= 20
        }
        if allowed { record(entry) }
    }

    func clear() {
        lock.withLock { entries = [] }
        scheduleSave()
        onChange?([])
    }

    /// Saves now (the app is leaving the screen and may be suspended or ended).
    func flush() {
        let snapshot: [AuditEntry] = lock.withLock { entries }
        try? file.save(snapshot)
    }

    /// Calls are often bursty; save once per half second at most.
    private func scheduleSave() {
        let schedule: Bool = lock.withLock {
            if saveScheduled { return false }
            saveScheduled = true
            return true
        }
        guard schedule else { return }
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 0.5) { [self] in
            let snapshot: [AuditEntry] = lock.withLock {
                saveScheduled = false
                return entries
            }
            try? file.save(snapshot)
        }
    }
}
