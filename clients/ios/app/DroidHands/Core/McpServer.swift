import Foundation

/// `POST /mcp` as a transport hands it over (docs/PROTOCOL.md §4).
struct TransportRequest: CustomStringConvertible {
    var authorization: String?
    var body: String
    var transport = "relay"
    var userAgent: String?
    var remoteAddress: String?

    var description: String { "TransportRequest(\(transport), \(body.count) bytes, authorization \(authorization == nil ? "absent" : "<redacted>"))" }
}

struct TransportResponse {
    var status: Int
    var body: String?
    var headers: [String: String] = [:]
}

/// An approval the server needs from the user.
struct ConfirmationRequest: Identifiable {
    let id = UUID()
    let agentName: String
    let tokenId: String
    let tool: String
    let risk: Risk
    let summary: String
    let expiresAt: Date
}

enum ConfirmationResult { case approved, denied, timeout }

protocol ConfirmationProvider: AnyObject {
    func confirm(_ request: ConfirmationRequest) async -> ConfirmationResult
}

/// Why running calls are stopped.
enum StopReason {
    case paused, revoked, deleted, stoppedByUser

    var message: String {
        switch self {
        case .paused: return "Stopped: the user paused DroidHands, so agents can't use the phone right now. Don't retry until the user says it is resumed."
        case .revoked: return "Stopped: the user revoked this agent's access."
        case .deleted: return "Stopped: the user removed this agent."
        case .stoppedByUser: return "Stopped by the user on the phone. Don't retry; ask the user what to do."
        }
    }
}

/// Stateless MCP server (Streamable HTTP subset): authenticates every request, then initialize, ping,
/// tools/list and the tools/call pipeline: lookup and scope, pause, per-tool rules and risk cap, approval,
/// the tool with a timeout, and exactly one audit entry per call.
final class McpServer {
    static let protocolVersions = ["2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"]
    static let maxBatch = 64
    /// How long a request may take on the phone, under the relay's 150 s.
    static let answerWithin: TimeInterval = 140

    private let tools: [Tool]
    private let tokens: TokenManager
    private let audit: AuditLog
    private weak var confirmations: ConfirmationProvider?
    private let instructions: String
    private let serverVersion: String
    private let isAccessEnabled: () -> Bool
    private let confirmationTimeout: TimeInterval
    private let inFlight = InFlightCalls()

    /// Told when a call starts and ends (the Home screen's "working now").
    var onActivity: ((_ running: Int) -> Void)?

    init(tools: [Tool], tokens: TokenManager, audit: AuditLog, confirmations: ConfirmationProvider?, instructions: String,
         serverVersion: String, isAccessEnabled: @escaping () -> Bool, confirmationTimeout: TimeInterval = 60) {
        self.tools = tools
        self.tokens = tokens
        self.audit = audit
        self.confirmations = confirmations
        self.instructions = instructions
        self.serverVersion = serverVersion
        self.isAccessEnabled = isAccessEnabled
        self.confirmationTimeout = confirmationTimeout
    }

    func tool(named name: String) -> Tool? { tools.first { $0.name == name } }
    var allTools: [Tool] { tools }

    /// Stops running calls: all of them (pause) or one agent's (revoke, delete, Stop).
    @discardableResult
    func cancelCalls(tokenId: String? = nil, reason: StopReason? = nil) -> Int {
        inFlight.cancel(tokenId: tokenId) { call in reason ?? self.stopReason(call.tokenId) }
    }

    private func stopReason(_ tokenId: String) -> StopReason {
        guard let current = tokens.record(tokenId) else { return .deleted }
        if current.revoked { return .revoked }
        if !isAccessEnabled() { return .paused }
        return .stoppedByUser
    }

    // MARK: HTTP

    func handle(_ request: TransportRequest) async -> TransportResponse {
        let token: TokenRecord
        switch tokens.authenticate(request.authorization) {
        case .ok(let t): token = t
        case .denied(let reason):
            recordAuthFailure(request, reason: reason)
            return TransportResponse(status: 401, body: JsonRpc.errorBody(id: .null, code: JsonRpc.unauthorized, message: "Unauthorized"),
                                     headers: ["content-type": "application/json", "www-authenticate": "Bearer realm=\"droidhands\""])
        }
        let root: JSON
        do {
            root = try JSON.parse(request.body)
        } catch {
            return json(400, JsonRpc.error(id: .null, code: JsonRpc.parseError, message: "Parse error: the body is not valid JSON"))
        }
        if case .array(let batch) = root {
            if batch.isEmpty { return json(200, JsonRpc.error(id: .null, code: JsonRpc.invalidRequest, message: "Invalid Request: empty batch")) }
            if batch.count > McpServer.maxBatch {
                return json(200, JsonRpc.error(id: .null, code: JsonRpc.invalidRequest, message: "Invalid Request: batch too large (max \(McpServer.maxBatch) messages)"))
            }
            // Concurrently, so several slow approvals in one batch don't add up past the relay's timeout.
            let responses = await withTaskGroup(of: (Int, JSON?).self) { group in
                for (i, message) in batch.enumerated() { group.addTask { (i, await self.handleMessage(message, token: token, request: request)) } }
                var out: [(Int, JSON?)] = []
                for await r in group { out.append(r) }
                return out.sorted { $0.0 < $1.0 }.compactMap(\.1)
            }
            return responses.isEmpty ? TransportResponse(status: 202, body: nil) : json(200, .array(responses))
        }
        guard let response = await handleMessage(root, token: token, request: request) else { return TransportResponse(status: 202, body: nil) }
        return json(200, response)
    }

    private func json(_ status: Int, _ body: JSON) -> TransportResponse {
        TransportResponse(status: status, body: body.encoded, headers: ["content-type": "application/json"])
    }

    private func handleMessage(_ message: JSON, token: TokenRecord, request: TransportRequest) async -> JSON? {
        guard case .object(let o) = message, o["jsonrpc"] == "2.0" else {
            return JsonRpc.error(id: .null, code: JsonRpc.invalidRequest, message: "Invalid Request: expected a JSON-RPC 2.0 object")
        }
        let id = o["id"]
        guard let method = o["method"]?.stringValue else {
            // A response from the client (we never send requests), or garbage.
            if id != nil, o["result"] != nil || o["error"] != nil { return nil }
            return JsonRpc.error(id: id ?? .null, code: JsonRpc.invalidRequest, message: "Invalid Request: \"method\" is missing")
        }
        guard let id else { return nil } // A notification.
        switch id {
        case .string, .number: break
        default: return JsonRpc.error(id: .null, code: JsonRpc.invalidRequest, message: "Invalid Request: \"id\" must be a string or a number")
        }
        let params: JSONObject
        switch o["params"] {
        case nil, .null?: params = JSONObject()
        case .object(let p)?: params = p
        default: return JsonRpc.error(id: id, code: JsonRpc.invalidParams, message: "Invalid params: \"params\" must be an object")
        }
        switch method {
        case "initialize": return JsonRpc.result(id: id, initializeResult(params, token: token))
        case "ping": return JsonRpc.result(id: id, .object(JSONObject()))
        case "tools/list": return JsonRpc.result(id: id, toolsList(token))
        case "tools/call": return await callTool(id: id, params: params, token: token, transport: request.transport)
        case "resources/list": return JsonRpc.result(id: id, ["resources": []])
        case "resources/templates/list": return JsonRpc.result(id: id, ["resourceTemplates": []])
        case "prompts/list": return JsonRpc.result(id: id, ["prompts": []])
        default: return JsonRpc.error(id: id, code: JsonRpc.methodNotFound, message: "Method not found: \(method.prefix(100))")
        }
    }

    private func initializeResult(_ params: JSONObject, token: TokenRecord) -> JSON {
        let requested = params["protocolVersion"]?.stringValue
        let version = requested.flatMap { McpServer.protocolVersions.contains($0) ? $0 : nil } ?? McpServer.protocolVersions[0]
        return .object(JSONObject([
            ("protocolVersion", .string(version)),
            ("capabilities", ["tools": ["listChanged": false]]),
            ("serverInfo", ["name": "droidhands-ios", "version": .string(serverVersion), "title": "DroidHands"]),
            ("instructions", .string(instructions + "\n\n" + AccessSummary.instructions(token))),
        ]))
    }

    static func isVisible(_ tool: Tool, to token: TokenRecord) -> Bool {
        Scopes.allows(token.scopes, tool.scope) && token.toolRules[tool.name] != .BLOCK && !exceedsCap(token.maxRisk, tool.risk)
    }

    static func exceedsCap(_ cap: Risk?, _ risk: Risk) -> Bool { cap.map { risk > $0 } ?? false }

    private func toolsList(_ token: TokenRecord) -> JSON {
        let visible = tools.filter { McpServer.isVisible($0, to: token) }
        return ["tools": .array(visible.map { tool in
            let unavailable = tool.unavailableReason()
            return .object(JSONObject([
                ("name", .string(tool.name)),
                ("title", .string(tool.title)),
                ("description", .string(unavailable.map { "\(tool.description)\n\nCurrently unavailable: \($0)" } ?? tool.description)),
                ("inputSchema", tool.inputSchema),
                ("annotations", .object(JSONObject([
                    ("title", .string(tool.title)),
                    ("readOnlyHint", .bool(tool.risk == .READ)),
                    ("destructiveHint", .bool(tool.risk == .SENSITIVE)),
                    ("idempotentHint", .bool(tool.risk == .READ)),
                    ("openWorldHint", .bool(tool.risk == .SENSITIVE)),
                ]))),
            ]))
        })]
    }

    // MARK: tools/call

    private func callTool(id: JSON, params: JSONObject, token: TokenRecord, transport: String) async -> JSON {
        let started = Date()
        let name = params["name"]?.stringValue ?? ""

        func entry(_ outcome: AuditOutcome, _ args: Args?, _ detail: String?) -> AuditEntry {
            AuditEntry(timestamp: started, tokenId: token.id, tokenName: token.name, method: "tools/call",
                       tool: name.isEmpty ? nil : String(name.prefix(100)), argsPreview: args?.preview, outcome: outcome,
                       durationMs: Int(Date().timeIntervalSince(started) * 1000), detail: detail.map { String($0.prefix(300)) })
        }
        func refuse(_ outcome: AuditOutcome, _ args: Args?, _ detail: String?) {
            audit.recordRefusal(entry(outcome, args, detail), source: "token:\(token.id)")
        }
        func result(_ r: ToolResult) -> JSON { JsonRpc.result(id: id, r.json) }

        guard !name.isEmpty else {
            refuse(.UNKNOWN_TOOL, nil, "missing tool name")
            return JsonRpc.error(id: id, code: JsonRpc.invalidParams, message: "Invalid params: \"name\" (the tool name) is required")
        }
        let args: Args
        switch params["arguments"] {
        case nil, .null?: args = Args()
        case .object(let o)?: args = Args(o)
        default:
            refuse(.TOOL_ERROR, nil, "arguments is not an object")
            return JsonRpc.error(id: id, code: JsonRpc.invalidParams, message: "Invalid params: \"arguments\" must be a JSON object")
        }
        guard let tool = tool(named: name) else {
            refuse(.UNKNOWN_TOOL, args, nil)
            return JsonRpc.error(id: id, code: JsonRpc.invalidParams, message: "Unknown tool '\(name.prefix(100))'. Call tools/list to see the tools available to this token.")
        }
        guard Scopes.allows(token.scopes, tool.scope) else {
            refuse(.DENIED_SCOPE, args, "missing scope \(tool.scope)")
            return JsonRpc.error(id: id, code: JsonRpc.invalidParams, message: GrantText.missingScope(agent: token.name, scope: tool.scope, tool: name))
        }
        guard isAccessEnabled() else {
            refuse(.PAUSED, args, nil)
            return result(.error(StopReason.paused.message.replacingOccurrences(of: "Stopped: the", with: "The")))
        }
        let rule = token.toolRules[name]
        if rule == .BLOCK {
            refuse(.DENIED_POLICY, args, "blocked by a tool rule")
            return result(.error(GrantText.toolBlocked(agent: token.name, tool: name)))
        }
        if McpServer.exceedsCap(token.maxRisk, tool.risk) {
            refuse(.DENIED_POLICY, args, "above the agent's risk cap")
            return result(.error(GrantText.aboveRiskCap(agent: token.name, tool: name)))
        }
        if let hours = token.activeHours, let refusal = ActiveWindow.refusal(hours, now: started) {
            refuse(.DENIED_POLICY, args, "outside its active hours")
            return result(.error(refusal))
        }
        if let reason = tool.unavailableReason() {
            audit.record(entry(.TOOL_ERROR, args, reason))
            return result(.error("\(tool.title) is unavailable: \(reason)"))
        }
        let needsApproval = rule == .ASK || (rule != .ALLOW && token.confirmPolicy.needsApproval(tool.risk))

        let call = InFlightCall(tokenId: token.id)
        let deadline = PausableDeadline(seconds: tool.timeout)
        let stillAllowed: () throws -> Void = { [weak self] in
            if call.stopReason != nil || Task.isCancelled { throw CancellationError() }
            guard let self else { throw CancellationError() }
            guard self.isAccessEnabled() else { call.stopReason = .paused; throw CancellationError() }
            guard let current = self.tokens.record(token.id) else { call.stopReason = .deleted; throw CancellationError() }
            guard current.isActive(at: Date()) else { call.stopReason = .revoked; throw CancellationError() }
            guard McpServer.isVisible(tool, to: current) else { call.stopReason = .revoked; throw CancellationError() }
        }
        let ask: (String) async throws -> Void = { [weak self] summary in
            guard let self else { throw CancellationError() }
            deadline.pause()
            defer { deadline.resume() }
            try stillAllowed()
            switch await self.askUser(token: token, tool: tool, summary: summary) {
            case .approved: try stillAllowed()
            case .denied: throw Denied.user
            case .timeout: throw Denied.timeout
            }
        }
        // The relay gives up after 150 s (docs/PROTOCOL.md §3.3); tools that wait for the user stop before that.
        let ctx = CallContext(token: token, requireConfirmation: { summary in
            if needsApproval { try await ask(summary) }
        }, ensureStillAllowed: stillAllowed, answerBy: started.addingTimeInterval(McpServer.answerWithin))

        let outcome: (ToolResult, AuditOutcome, String?) = await inFlight.run(call) { [self] in
            do {
                if needsApproval && !tool.confirmsInternally { try await ask(tool.describeCall(args)) }
                try stillAllowed()
                onActivity?(inFlight.count)
                let r = try await deadline.race { try await tool.run(args, ctx) }
                // A tool that finished despite being stopped must not hand its result to a revoked or paused agent.
                try stillAllowed()
                return (r, r.isError ? .TOOL_ERROR : .OK, r.isError ? r.firstText : nil)
            } catch Denied.user {
                return (.error("Denied by the user on the phone. Don't retry; ask the user what they want instead."), .DENIED_USER, nil)
            } catch Denied.timeout {
                return (.error("Nobody approved this on the phone within \(Int(confirmationTimeout)) seconds, so nothing was done. Ask the user to watch the phone, then try again."), .TIMEOUT, "approval timed out")
            } catch is PausableDeadline.Expired {
                return (.error("\(tool.title) took longer than \(Int(tool.timeout)) seconds and was stopped. It may have partly happened; check before retrying."), .TIMEOUT, nil)
            } catch is CancellationError {
                let reason = call.stopReason ?? .stoppedByUser
                return (.error(reason.message), .CANCELLED, "\(reason)")
            } catch let e as ToolError {
                return (.error(e.message), .TOOL_ERROR, e.message)
            } catch {
                return (.error("\(tool.title) failed: \(error.localizedDescription)"), .EXCEPTION, error.localizedDescription)
            }
        }
        audit.record(entry(outcome.1, args, outcome.2))
        onActivity?(inFlight.count)
        return result(outcome.0)
    }

    private enum Denied: Error { case user, timeout }

    private func askUser(token: TokenRecord, tool: Tool, summary: String) async -> ConfirmationResult {
        guard let confirmations else { return .denied }
        let request = ConfirmationRequest(agentName: GrantText.display(token.name), tokenId: token.id, tool: tool.name, risk: tool.risk,
                                          summary: PromptText.clean(summary, max: 400), expiresAt: Date().addingTimeInterval(confirmationTimeout))
        return await withTaskGroup(of: ConfirmationResult.self) { group in
            group.addTask { await confirmations.confirm(request) }
            group.addTask { [confirmationTimeout] in
                try? await Task.sleep(nanoseconds: UInt64(confirmationTimeout * 1e9))
                return .timeout
            }
            let first = await group.next() ?? .timeout
            group.cancelAll()
            return first
        }
    }

    private func recordAuthFailure(_ request: TransportRequest, reason: String) {
        let known = TokenFormat.fromAuthorization(request.authorization).flatMap(TokenFormat.parse).flatMap { tokens.record($0.tokenId) }
        var detail = reason
        if let ip = request.remoteAddress { detail += "; from \(ip)" }
        if let ua = request.userAgent { detail += "; ua \(ua.prefix(80))" }
        let method = (try? JSON.parse(request.body))?["method"]?.stringValue ?? "unknown"
        let entry = AuditEntry(timestamp: Date(), tokenId: known?.id, tokenName: known?.name, method: String(method.prefix(100)), tool: nil,
                               argsPreview: nil, outcome: .AUTH_FAILED, durationMs: nil, detail: String(detail.prefix(300)))
        audit.recordRefusal(entry, source: known.map { "token:\($0.id)" } ?? "from:\(request.remoteAddress ?? "unknown")")
    }
}

extension ToolResult {
    var firstText: String? {
        for c in content { if case .text(let t) = c { return String(t.prefix(300)) } }
        return nil
    }
}

enum JsonRpc {
    static let parseError = -32700
    static let invalidRequest = -32600
    static let methodNotFound = -32601
    static let invalidParams = -32602
    static let internalError = -32603
    static let unauthorized = -32001

    static func result(id: JSON, _ result: JSON) -> JSON { .object(JSONObject([("jsonrpc", "2.0"), ("id", id), ("result", result)])) }

    static func error(id: JSON, code: Int, message: String) -> JSON {
        .object(JSONObject([("jsonrpc", "2.0"), ("id", id), ("error", ["code": .int(code), "message": .string(message)])]))
    }

    static func errorBody(id: JSON, code: Int, message: String) -> String { error(id: id, code: code, message: message).encoded }
}

/// One running tools/call.
final class InFlightCall {
    let tokenId: String
    private let reason = Locked<StopReason?>(nil)
    var stopReason: StopReason? {
        get { reason.value }
        set { reason.value = newValue }
    }
    var task: Task<(ToolResult, AuditOutcome, String?), Never>?
    init(tokenId: String) { self.tokenId = tokenId }
}

/// Calls in progress, so Pause, Revoke and Stop can end them.
final class InFlightCalls {
    private let lock = NSLock()
    private var calls: [ObjectIdentifier: InFlightCall] = [:]

    var count: Int { lock.withLock { calls.count } }

    func run(_ call: InFlightCall, _ body: @escaping () async -> (ToolResult, AuditOutcome, String?)) async -> (ToolResult, AuditOutcome, String?) {
        let task = Task { await body() }
        lock.withLock {
            call.task = task
            calls[ObjectIdentifier(call)] = call
        }
        defer { _ = lock.withLock { calls.removeValue(forKey: ObjectIdentifier(call)) } }
        // The tool keeps running if the relay connection drops; it finishes and is audited.
        return await task.value
    }

    func cancel(tokenId: String?, reason: (InFlightCall) -> StopReason) -> Int {
        let targets = lock.withLock { calls.values.filter { tokenId == nil || $0.tokenId == tokenId } }
        for call in targets {
            if call.stopReason == nil { call.stopReason = reason(call) }
            call.task?.cancel()
        }
        return targets.count
    }
}

/// A tool's time budget, paused while the user is being asked.
final class PausableDeadline {
    struct Expired: Error {}

    private let lock = NSLock()
    private var remaining: TimeInterval
    private var runningSince: Date? = Date()

    init(seconds: TimeInterval) { remaining = seconds }

    func pause() {
        lock.withLock {
            if let since = runningSince { remaining -= Date().timeIntervalSince(since); runningSince = nil }
        }
    }

    func resume() { lock.withLock { if runningSince == nil { runningSince = Date() } } }

    private var left: TimeInterval {
        lock.withLock { runningSince.map { remaining - Date().timeIntervalSince($0) } ?? .infinity }
    }

    /// Runs [body], throwing [Expired] if the budget runs out first, or CancellationError when the caller is
    /// cancelled. Never waits for a body that ignores cancellation: the call answers and the body is left to end.
    func race<T>(_ body: @escaping () async throws -> T) async throws -> T {
        let box = RaceBox<T>()
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (c: CheckedContinuation<T, Error>) in
                box.set(c)
                box.work = Task {
                    do { box.finish(.success(try await body())) } catch { box.finish(.failure(error)) }
                }
                Task { [self] in
                    while !box.isDone {
                        let l = left
                        if l <= 0 {
                            box.finish(.failure(Expired()))
                            return
                        }
                        try? await Task.sleep(nanoseconds: UInt64(min(l, 0.25) * 1e9))
                    }
                }
            }
        } onCancel: {
            box.finish(.failure(CancellationError()))
        }
    }
}

/// One continuation, resumed once by whichever finishes first; cancels the body when it isn't the winner.
private final class RaceBox<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<T, Error>?
    private var early: Result<T, Error>?
    private var done = false
    var work: Task<Void, Never>?

    var isDone: Bool { lock.withLock { done } }

    func set(_ c: CheckedContinuation<T, Error>) {
        let pending: Result<T, Error>? = lock.withLock {
            if let early { return early }
            continuation = c
            return nil
        }
        if let pending { c.resume(with: pending) }
    }

    func finish(_ result: Result<T, Error>) {
        let c: CheckedContinuation<T, Error>? = lock.withLock {
            if done { return nil }
            done = true
            guard let c = continuation else {
                early = result
                return nil
            }
            continuation = nil
            return c
        }
        if case .failure = result { work?.cancel() }
        c?.resume(with: result)
    }
}
