import Foundation

/// A capability exposed to agents as an MCP tool. Names and arguments are the platform-neutral API of
/// docs/TOOLS.md.
protocol Tool {
    var name: String { get }
    var title: String { get }
    var description: String { get }
    /// Scope a token must hold; "" = always allowed.
    var scope: String { get }
    var risk: Risk { get }
    var inputSchema: JSON { get }
    /// Max running time, not counting time spent waiting for the user.
    var timeout: TimeInterval { get }
    /// True if the tool resolves its targets first and then asks with [CallContext.requireConfirmation].
    var confirmsInternally: Bool { get }
    /// Nil if the tool can run now, otherwise why not (a missing permission, for example).
    func unavailableReason() -> String?
    /// One line saying what a call will do; shown in approval prompts.
    func describeCall(_ args: Args) -> String
    func run(_ args: Args, _ ctx: CallContext) async throws -> ToolResult
}

extension Tool {
    var timeout: TimeInterval { 60 }
    var confirmsInternally: Bool { false }
    func unavailableReason() -> String? { nil }
    func describeCall(_ args: Args) -> String { title }
}

/// A failure the agent should read: returned as an `isError` result with [message].
struct ToolError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

struct ToolResult {
    enum Content {
        case text(String)
        case image(base64: String, mimeType: String)
    }

    var content: [Content]
    var isError = false

    static func text(_ s: String) -> ToolResult { ToolResult(content: [.text(s)]) }
    static func error(_ s: String) -> ToolResult { ToolResult(content: [.text(s)], isError: true) }
    static func json(_ j: JSON, footer: String? = nil) -> ToolResult {
        ToolResult(content: [.text(footer.map { j.encoded + "\n" + $0 } ?? j.encoded)])
    }
    static func object(_ pairs: [(String, JSON?)]) -> ToolResult { json(.object(JSONObject(pairs))) }

    var json: JSON {
        let items: [JSON] = content.map {
            switch $0 {
            case .text(let t): return ["type": "text", "text": .string(t)]
            case .image(let data, let mime): return ["type": "image", "data": .string(data), "mimeType": .string(mime)]
            }
        }
        var o = JSONObject()
        o["content"] = .array(items)
        if isError { o["isError"] = true }
        return .object(o)
    }
}

/// A tool's arguments, with readers that turn wrong types into errors the agent can act on.
struct Args {
    let raw: JSONObject

    init(_ raw: JSONObject = JSONObject()) { self.raw = raw }

    func string(_ key: String) throws -> String? {
        switch raw[key] {
        case nil, .null?: return nil
        case .string(let s)?: return s
        case .number(let n)?: return JSON.number(n).encoded
        default: throw ToolError("'\(key)' must be a string.")
        }
    }

    func requiredString(_ key: String) throws -> String {
        guard let s = try string(key)?.trimmingCharacters(in: .whitespacesAndNewlines), !s.isEmpty else {
            throw ToolError("'\(key)' is required.")
        }
        return s
    }

    func bool(_ key: String) throws -> Bool? {
        switch raw[key] {
        case nil, .null?: return nil
        case .bool(let b)?: return b
        case .string(let s)? where ["true", "false"].contains(s.lowercased()): return s.lowercased() == "true"
        default: throw ToolError("'\(key)' must be true or false.")
        }
    }

    func number(_ key: String) throws -> Double? {
        switch raw[key] {
        case nil, .null?: return nil
        case .number(let n)?: return n
        case .string(let s)?:
            if let n = Double(s.trimmingCharacters(in: .whitespaces)) { return n }
            throw ToolError("'\(key)' must be a number.")
        default: throw ToolError("'\(key)' must be a number.")
        }
    }

    func int(_ key: String, in range: ClosedRange<Int>? = nil) throws -> Int? {
        guard let n = try number(key) else { return nil }
        guard n == n.rounded(), abs(n) < 1e12 else { throw ToolError("'\(key)' must be a whole number.") }
        let i = Int(n)
        if let range, !range.contains(i) { throw ToolError("'\(key)' must be between \(range.lowerBound) and \(range.upperBound).") }
        return i
    }

    /// For prompts and the activity log: arguments without long values.
    var preview: String {
        var o = JSONObject()
        for (k, v) in raw.entries {
            if case .string(let s) = v, s.count > 80 { o[k] = .string(String(s.prefix(80)) + "…") } else { o[k] = v }
        }
        return String(JSON.object(o).encoded.prefix(300))
    }
}

/// JSON Schema helpers for tool inputs.
enum Schema {
    static func object(_ properties: [(String, JSON)], required: [String] = []) -> JSON {
        var o = JSONObject()
        o["type"] = "object"
        o["properties"] = .object(JSONObject(properties.map { ($0.0, $0.1) }))
        if !required.isEmpty { o["required"] = .strings(required) }
        o["additionalProperties"] = false
        return .object(o)
    }

    static func string(_ description: String, enum values: [String]? = nil) -> JSON {
        var o = JSONObject([("type", "string"), ("description", .string(description))])
        if let values { o["enum"] = .strings(values) }
        return .object(o)
    }

    static func integer(_ description: String, min: Int? = nil, max: Int? = nil) -> JSON {
        JSON.object(JSONObject([("type", "integer"), ("description", .string(description)),
                                ("minimum", min.map { .int($0) }), ("maximum", max.map { .int($0) })]))
    }

    static func boolean(_ description: String) -> JSON { ["type": "boolean", "description": .string(description)] }

    static let empty = object([])
}

/// What a running tool may ask of the server.
struct CallContext {
    let token: TokenRecord
    /// Asks the user when the token's policy (or a rule) requires it; throws on deny or timeout.
    let requireConfirmation: (String) async throws -> Void
    /// Throws if the call was stopped (pause, revoke) while the tool waited.
    let ensureStillAllowed: () throws -> Void
    /// The call must answer by then (the relay's own timeout comes soon after).
    var answerBy: Date = .distantFuture

    /// How long a tool may still wait for the user, at most [limit] seconds.
    func waitBudget(_ limit: TimeInterval) throws -> TimeInterval {
        let left = min(limit, answerBy.timeIntervalSinceNow - 3)
        guard left >= 5 else { throw ToolError("No time is left to wait for the user in this call; try again.") }
        return left
    }
}

extension CallContext {
    var agentName: String { GrantText.display(token.name) }
}

/// Text an agent controls, shown in an approval prompt: control, bidi and zero-width characters removed (they
/// could make it read differently from what runs), and any cut marked, so nothing runs that the user didn't see.
enum PromptText {
    static func clean(_ text: String, max: Int) -> String {
        let cleaned = RelayPairings.displayText(text, max: Int.max) ?? ""
        guard cleaned.count > max else { return cleaned }
        return String(cleaned.prefix(max)) + "… (+\(cleaned.count - max) more characters)"
    }

    /// “text”, cleaned and marked if cut.
    static func quote(_ text: String?, max: Int = 160) -> String { "“\(clean(text ?? "", max: max))”" }
}
