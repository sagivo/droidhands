import Foundation

/// Aggregated usage of one agent, kept beyond the activity log (clearing the log keeps it). Mirrors Android's
/// `TokenUsage`: only `tools/call` entries with an agent count, and failed sign-ins never do.
struct TokenUsage: Codable, Equatable {
    var totalCalls = 0
    var okCalls = 0
    /// TOOL_ERROR, TIMEOUT, EXCEPTION, UNKNOWN_TOOL.
    var errorCalls = 0
    /// Refused or stopped: everything else.
    var deniedCalls = 0
    var lastCallAt: Date?
    /// Tool name → calls.
    var perTool: [String: Int] = [:]
    /// Local day "yyyy-MM-dd" → calls; the last 31 days only.
    var perDay: [String: Int] = [:]

    static let keptDays = 31

    func calls(on day: Date) -> Int { perDay[UsageStore.dayKey(day)] ?? 0 }
}

enum OutcomeCategory { case success, error, denied }

extension AuditOutcome {
    var category: OutcomeCategory {
        switch self {
        case .OK: return .success
        case .TOOL_ERROR, .UNKNOWN_TOOL, .TIMEOUT, .EXCEPTION: return .error
        default: return .denied
        }
    }
}

/// Per-agent usage counters fed from audit entries, saved to a JSON file. Thread-safe.
final class UsageStore {
    private let lock = NSLock()
    private let file: JSONFile<[String: TokenUsage]>
    private var usage: [String: TokenUsage]
    private var saveScheduled = false

    var onChange: (([String: TokenUsage]) -> Void)?

    init(file: JSONFile<[String: TokenUsage]>) {
        self.file = file
        usage = (try? file.load()) ?? [:]
    }

    var all: [String: TokenUsage] { lock.withLock { usage } }

    func record(_ entry: AuditEntry) {
        guard entry.method == "tools/call", let id = entry.tokenId, entry.outcome != .AUTH_FAILED else { return }
        let snapshot: [String: TokenUsage] = lock.withLock {
            var u = usage[id] ?? TokenUsage()
            u.totalCalls += 1
            switch entry.outcome.category {
            case .success: u.okCalls += 1
            case .error: u.errorCalls += 1
            case .denied: u.deniedCalls += 1
            }
            u.lastCallAt = max(u.lastCallAt ?? entry.timestamp, entry.timestamp)
            if let tool = entry.tool, entry.outcome != .UNKNOWN_TOOL, u.perTool[tool] != nil || u.perTool.count < 200 {
                u.perTool[tool, default: 0] += 1
            }
            u.perDay[UsageStore.dayKey(entry.timestamp), default: 0] += 1
            let oldest = UsageStore.dayKey(Calendar.current.date(byAdding: .day, value: -(TokenUsage.keptDays - 1), to: Date()) ?? Date())
            u.perDay = u.perDay.filter { $0.key >= oldest }
            usage[id] = u
            return usage
        }
        scheduleSave()
        onChange?(snapshot)
    }

    func remove(_ tokenId: String) {
        let snapshot: [String: TokenUsage] = lock.withLock {
            usage[tokenId] = nil
            return usage
        }
        scheduleSave()
        onChange?(snapshot)
    }

    func clear() {
        lock.withLock { usage = [:] }
        scheduleSave()
        onChange?([:])
    }

    func flush() {
        let snapshot: [String: TokenUsage] = lock.withLock { usage }
        try? file.save(snapshot)
    }

    private func scheduleSave() {
        let schedule: Bool = lock.withLock {
            if saveScheduled { return false }
            saveScheduled = true
            return true
        }
        guard schedule else { return }
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 0.5) { [self] in
            let snapshot: [String: TokenUsage] = lock.withLock {
                saveScheduled = false
                return usage
            }
            try? file.save(snapshot)
        }
    }

    static func dayKey(_ date: Date) -> String {
        let c = Calendar.current.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }
}
