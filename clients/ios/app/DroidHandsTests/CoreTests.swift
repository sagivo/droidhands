import XCTest
@testable import DroidHands

final class EncodingTests: XCTestCase {
    // docs/PROTOCOL.md §1 test vectors: every implementation must pass them.
    func testDeviceIdVector() {
        let secret = Data((0..<32).map { UInt8($0) })
        XCTAssertEqual(Encoding.base64URL(secret), "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")
        XCTAssertEqual(DeviceIdentity(secret: secret)?.deviceId, "mmg42klgyqzwneiskrelxms3j4")
    }

    func testTokenSecretVector() {
        let secret = Data((100..<132).map { UInt8($0) })
        XCTAssertEqual(Encoding.base64URL(secret), "ZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXp7fH1-f4CBgoM")
        XCTAssertEqual(Encoding.hex(Encoding.sha256(secret)), "e3e16780becf601d55e4751e7d722ef91d750bb0cea61b1bb5ea8bc308cdf262")
    }

    func testBase64URLRoundTrip() {
        for n in 0..<40 {
            let d = Encoding.randomBytes(n)
            XCTAssertEqual(Encoding.base64URLDecode(Encoding.base64URL(d)), d)
        }
        XCTAssertNil(Encoding.base64URLDecode("ab+c"))
        XCTAssertNil(Encoding.base64URLDecode("abc="))
    }

    func testConstantTimeEquals() {
        XCTAssertTrue(Encoding.constantTimeEquals("abc", "abc"))
        XCTAssertFalse(Encoding.constantTimeEquals("abc", "abd"))
        XCTAssertFalse(Encoding.constantTimeEquals("abc", "abcd"))
    }

    func testJSONEncodingKeepsOrderAndEscapes() throws {
        let j: JSON = .object(JSONObject([("b", 1), ("a", "x\"\n\u{1}"), ("c", [true, nil, 1.5])]))
        XCTAssertEqual(j.encoded, #"{"b":1,"a":"x\"\n\u0001","c":[true,null,1.5]}"#)
        XCTAssertEqual(try JSON.parse(j.encoded)["a"], "x\"\n\u{1}")
        XCTAssertEqual(try JSON.parse("true"), .bool(true))
        XCTAssertEqual(try JSON.parse("1"), .number(1))
    }
}

final class TokenTests: XCTestCase {
    var dir: URL!
    let identity = DeviceIdentity(secret: Data((0..<32).map { UInt8($0) }))!

    override func setUp() {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    func manager() throws -> TokenManager {
        try TokenManager(store: JSONFile(name: "t.json", directory: dir), deviceId: identity.deviceId)
    }

    func testIssueAuthenticateRevoke() throws {
        let m = try manager()
        let (record, token) = try m.issue(name: " Claude ", scopes: Scopes.all, confirmPolicy: .NEVER)
        XCTAssertEqual(record.name, "Claude")
        XCTAssertNotNil(TokenFormat.parse(token))
        XCTAssertTrue(token.range(of: #"^pb1\.[a-z2-7]{26}\.[a-z2-7]{12}\.[A-Za-z0-9_-]{43}$"#, options: .regularExpression) != nil)
        guard case .ok(let ok) = m.authenticate("Bearer \(token)") else { return XCTFail("should authenticate") }
        XCTAssertNotNil(ok.lastUsedAt)
        let wrong = token.dropLast() + (token.hasSuffix("x") ? "y" : "x")
        guard case .denied = m.authenticate("Bearer \(wrong)") else { return XCTFail("wrong secret") }
        try m.revoke(record.id)
        guard case .denied = m.authenticate(token) else { return XCTFail("revoked") }
        // Persisted, and the secret never is.
        let reloaded = try manager()
        XCTAssertEqual(reloaded.all.first?.revoked, true)
        let raw = try String(contentsOf: dir.appendingPathComponent("t.json"))
        XCTAssertFalse(raw.contains(TokenFormat.parse(token)!.secret))
    }

    func testOtherDeviceTokenRefused() throws {
        let m = try manager()
        _ = try m.issue(name: "A", scopes: [], confirmPolicy: .NEVER)
        let other = TokenFormat.format(deviceId: String(repeating: "a", count: 26), tokenId: String(repeating: "b", count: 12), secret: String(repeating: "c", count: 43))
        guard case .denied(let reason) = m.authenticate(other) else { return XCTFail() }
        XCTAssertEqual(reason, "token is for another device")
    }

    func testGrantDraftRoundTrip() throws {
        let m = try manager()
        var g = GrantDraft()
        g.mode = .chosen
        g.abilities = [.location, .calls]
        g.askBeforeSensitive = true
        let (r, _) = try m.issue(name: "X", scopes: g.scopes, confirmPolicy: g.confirmPolicy)
        XCTAssertEqual(GrantDraft(record: r), g)
        XCTAssertEqual(r.scopes, [Scopes.location, Scopes.calls])
        XCTAssertEqual(GrantDraft(record: GrantDraft().applied(to: r)).mode, .everything)
    }
}

final class McpServerTests: XCTestCase {
    final class AlwaysDeny: ConfirmationProvider {
        var asked: [String] = []
        func confirm(_ request: ConfirmationRequest) async -> ConfirmationResult { asked.append(request.summary); return .denied }
    }

    struct Echo: Tool {
        let name = "echo"
        let title = "Echo"
        let description = "Echoes"
        let scope = Scopes.device
        let risk = Risk.SENSITIVE
        let inputSchema = Schema.object([("text", Schema.string("t"))])
        func run(_ args: Args, _ ctx: CallContext) async throws -> ToolResult { .text(try args.string("text") ?? "") }
    }

    var dir: URL!
    var tokens: TokenManager!
    var audit: AuditLog!
    var confirm = AlwaysDeny()
    var paused = false

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        tokens = try TokenManager(store: JSONFile(name: "t.json", directory: dir), deviceId: DeviceIdentity.generate().deviceId)
        audit = AuditLog(file: JSONFile(name: "a.json", directory: dir))
    }

    func server() -> McpServer {
        McpServer(tools: [Echo(), CallStatusTool()], tokens: tokens, audit: audit, confirmations: confirm, instructions: "Hi",
                  serverVersion: "1", isAccessEnabled: { [unowned self] in !self.paused })
    }

    func post(_ s: McpServer, _ token: String?, _ body: String) async -> (Int, JSON?) {
        let r = await s.handle(TransportRequest(authorization: token.map { "Bearer \($0)" }, body: body))
        return (r.status, r.body.flatMap { try? JSON.parse($0) })
    }

    func testUnauthorized() async {
        let (status, body) = await post(server(), nil, #"{"jsonrpc":"2.0","id":1,"method":"ping"}"#)
        XCTAssertEqual(status, 401)
        XCTAssertEqual(body?["error"]?["code"], .number(-32001))
        XCTAssertEqual(audit.all.first?.outcome, .AUTH_FAILED)
    }

    func testInitializeListAndScopes() async throws {
        let (_, full) = try tokens.issue(name: "Full", scopes: Scopes.all, confirmPolicy: .NEVER)
        let (_, narrow) = try tokens.issue(name: "Narrow", scopes: [Scopes.location], confirmPolicy: .NEVER)
        let s = server()
        let (_, initBody) = await post(s, full, #"{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}"#)
        XCTAssertEqual(initBody?["result"]?["protocolVersion"], "2025-06-18")
        XCTAssertTrue(initBody?["result"]?["instructions"]?.stringValue?.contains("Your access on this phone") ?? false)

        let (_, list) = await post(s, narrow, #"{"jsonrpc":"2.0","id":2,"method":"tools/list"}"#)
        XCTAssertEqual(list?["result"]?["tools"]?.arrayValue?.count, 0)

        let (_, refused) = await post(s, narrow, #"{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo","arguments":{"text":"x"}}}"#)
        XCTAssertEqual(refused?["error"]?["code"], .number(-32602))
        XCTAssertTrue(refused?["error"]?["message"]?.stringValue?.contains("DroidHands > Agents > Narrow > Access") ?? false)

        let (_, ok) = await post(s, full, #"{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"echo","arguments":{"text":"hello"}}}"#)
        XCTAssertEqual(ok?["result"]?["content"]?.arrayValue?.first?["text"], "hello")
        XCTAssertEqual(audit.all.first?.outcome, .OK)
    }

    func testApprovalDeniedAndPaused() async throws {
        let (_, token) = try tokens.issue(name: "Asker", scopes: Scopes.all, confirmPolicy: .SENSITIVE)
        let s = server()
        let (_, denied) = await post(s, token, #"{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"echo","arguments":{"text":"x"}}}"#)
        XCTAssertEqual(denied?["result"]?["isError"], true)
        XCTAssertEqual(confirm.asked, ["Echo"])
        XCTAssertEqual(audit.all.first?.outcome, .DENIED_USER)
        // READ tools don't ask.
        let (_, read) = await post(s, token, #"{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"call_status"}}"#)
        XCTAssertNil(read?["result"]?["isError"])
        paused = true
        let (_, p) = await post(s, token, #"{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"call_status"}}"#)
        XCTAssertTrue(p?["result"]?["content"]?.arrayValue?.first?["text"]?.stringValue?.contains("paused") ?? false)
    }

    func testBlockRuleHidesTool() async throws {
        let (r, token) = try tokens.issue(name: "B", scopes: Scopes.all, confirmPolicy: .NEVER)
        try tokens.update(r.id) { $0.toolRules["echo"] = .BLOCK }
        let s = server()
        let (_, list) = await post(s, token, #"{"jsonrpc":"2.0","id":1,"method":"tools/list"}"#)
        XCTAssertEqual(list?["result"]?["tools"]?.arrayValue?.compactMap { $0["name"]?.stringValue }, ["call_status"])
        let (_, call) = await post(s, token, #"{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo"}}"#)
        XCTAssertEqual(call?["result"]?["isError"], true)
    }

    /// Ignores cancellation, like a tool waiting on a system callback.
    struct Stubborn: Tool {
        let name = "stubborn"
        let title = "Stubborn"
        let description = "Waits"
        let scope = Scopes.device
        let risk = Risk.READ
        let timeout: TimeInterval = 1
        let inputSchema = Schema.empty
        func run(_ args: Args, _ ctx: CallContext) async throws -> ToolResult {
            await withCheckedContinuation { c in DispatchQueue.global().asyncAfter(deadline: .now() + 3) { c.resume() } }
            return .text("late result")
        }
    }

    func testStuckToolTimesOutAndRevokeStopsIt() async throws {
        let (r, token) = try tokens.issue(name: "S", scopes: Scopes.all, confirmPolicy: .NEVER)
        let s = McpServer(tools: [Stubborn()], tokens: tokens, audit: audit, confirmations: confirm, instructions: "",
                          serverVersion: "1", isAccessEnabled: { true })
        let call = #"{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"stubborn"}}"#
        var started = Date()
        let (_, timedOut) = await post(s, token, call)
        XCTAssertLessThan(Date().timeIntervalSince(started), 2.5, "answers at the tool's timeout, not when it finally returns")
        XCTAssertEqual(timedOut?["result"]?["isError"], true)
        XCTAssertEqual(audit.all.first?.outcome, .TIMEOUT)

        started = Date()
        async let answer = post(s, token, call)
        try await Task.sleep(nanoseconds: 300_000_000)
        try tokens.revoke(r.id)
        s.cancelCalls(tokenId: r.id, reason: .revoked)
        let (_, revoked) = await answer
        XCTAssertLessThan(Date().timeIntervalSince(started), 1.5)
        XCTAssertTrue(revoked?["result"]?["content"]?.arrayValue?.first?["text"]?.stringValue?.contains("revoked") ?? false)
        XCTAssertEqual(audit.all.first?.outcome, .CANCELLED)
    }

    func testPromptTextIsCleanedAndMarked() {
        XCTAssertEqual(PromptText.clean("evil\u{202E}gnp.exe\nnext", max: 100), "evilgnp.exe next")
        XCTAssertEqual(PromptText.clean(String(repeating: "a", count: 12), max: 10), "aaaaaaaaaa… (+2 more characters)")
        let url = UrlOpenTool().describeCall(Args(JSONObject([("url", "https://maps.apple.com.evil.example/x")])))
        XCTAssertTrue(url.contains("on maps.apple.com.evil.example"))
    }

    func testProtocolEdges() async throws {
        let (_, token) = try tokens.issue(name: "E", scopes: Scopes.all, confirmPolicy: .NEVER)
        let s = server()
        let (parseStatus, parse) = await post(s, token, "{nope")
        XCTAssertEqual(parseStatus, 400)
        XCTAssertEqual(parse?["error"]?["code"], .number(-32700))
        let (noteStatus, _) = await post(s, token, #"{"jsonrpc":"2.0","method":"notifications/initialized"}"#)
        XCTAssertEqual(noteStatus, 202)
        let (_, unknown) = await post(s, token, #"{"jsonrpc":"2.0","id":1,"method":"nope"}"#)
        XCTAssertEqual(unknown?["error"]?["code"], .number(-32601))
        let (_, batch) = await post(s, token, #"[{"jsonrpc":"2.0","id":1,"method":"ping"},{"jsonrpc":"2.0","method":"notifications/x"},{"jsonrpc":"2.0","id":2,"method":"ping"}]"#)
        XCTAssertEqual(batch?.arrayValue?.compactMap { $0["id"] }, [1, 2])
    }
}

final class RelayTests: XCTestCase {
    func testDeviceURL() {
        let id = "mmg42klgyqzwneiskrelxms3j4"
        XCTAssertEqual(RelayClient.deviceURL(base: "https://connect.droidhands.com/", deviceId: id)?.absoluteString,
                       "wss://connect.droidhands.com/v1/device?deviceId=\(id)")
        XCTAssertEqual(RelayClient.deviceURL(base: "http://localhost:8787/prefix", deviceId: id)?.absoluteString,
                       "ws://localhost:8787/prefix/v1/device?deviceId=\(id)")
        XCTAssertNil(RelayClient.deviceURL(base: "ftp://x", deviceId: id))
    }

    func testRequestAndResponseFrames() throws {
        let frame = try JSON.parse(#"{"type":"request","id":"r1","method":"POST","path":"/mcp","headers":{"Authorization":"Bearer x","user-agent":"ua"},"body":"{}","remoteAddress":"1.2.3.4"}"#).objectValue!
        let r = RelayClient.transportRequest(frame)
        XCTAssertEqual(r.authorization, "Bearer x")
        XCTAssertEqual(r.userAgent, "ua")
        XCTAssertEqual(r.remoteAddress, "1.2.3.4")
        let out = try JSON.parse(RelayClient.responseFrame(id: "r1", TransportResponse(status: 202, body: nil)))
        XCTAssertEqual(out["status"], 202)
        XCTAssertEqual(out["body"], .null)
    }

    func testPairingParseAndSanitize() throws {
        let frame = try JSON.parse(#"{"type":"pair_request","id":"p_1","clientName":"Cl‮aude\n","redirectHost":"claude.ai","displayCode":"K7Q2","expiresAt":1731500180000,"flow":"device"}"#).objectValue!
        let r = RelayPairings.parse(frame, relayNow: Date(timeIntervalSince1970: 1731500000))
        XCTAssertEqual(r?.clientName, "Claude")
        XCTAssertEqual(r?.viaAgent, true)
        XCTAssertEqual(r?.expiresAt, Date(timeIntervalSince1970: 1731500180))
        var bad = frame
        bad["displayCode"] = "k7q1"
        XCTAssertNil(RelayPairings.parse(bad, relayNow: Date()))
        XCTAssertEqual(try JSON.parse(RelayPairings.response(id: "p_1", .denied("weird")))["reason"], "denied")
    }

    final class Handler: PairingHandler {
        var finished: [(String?, Bool)] = []
        func onPairRequest(_ request: PairingRequest) async -> PairingDecision { .approved(accessToken: "pb1.x", tokenId: "tok") }
        func onPairFinished(id: String, tokenId: String?, completed: Bool) { finished.append((tokenId, completed)) }
    }

    func testPairingApprovedAndUndeliverable() async throws {
        let handler = Handler()
        let pairings = RelayPairings(handler: handler)
        var sent: [String] = []
        pairings.send = { sent.append($0); return true }
        let frame = try JSON.parse(#"{"type":"pair_request","id":"p_2","clientName":"A","displayCode":"ABCD"}"#).objectValue!
        pairings.onRequest(frame)
        try await waitUntil { !sent.isEmpty }
        XCTAssertEqual(try JSON.parse(sent.first ?? "")["accessToken"], "pb1.x")

        // A response that can't be sent abandons the token.
        pairings.send = { _ in false }
        pairings.onRequest(try JSON.parse(#"{"type":"pair_request","id":"p_3","clientName":"A","displayCode":"ABCD"}"#).objectValue!)
        try await waitUntil { !handler.finished.isEmpty }
        XCTAssertEqual(handler.finished.first?.0, "tok")
        XCTAssertEqual(handler.finished.first?.1, false)
    }

    /// Polls [condition] for up to 5 s (the prompt runs in its own task).
    private func waitUntil(_ condition: () -> Bool) async throws {
        for _ in 0..<100 where !condition() { try await Task.sleep(nanoseconds: 50_000_000) }
    }

    func testContactNumberChecks() throws {
        XCTAssertEqual(try ContactBook.checkNumber("+1 (555) 010-2030"), "+15550102030")
        XCTAssertThrowsError(try ContactBook.checkNumber("*#06#"))
        XCTAssertThrowsError(try ContactBook.checkNumber("call mom"))
    }

    func testISODates() {
        XCTAssertEqual(ISODates.parse("2026-03-14")?.dateOnly, true)
        XCTAssertEqual(ISODates.parse("2026-03-14T15:00:00Z")?.date, Date(timeIntervalSince1970: 1773500400))
        XCTAssertNotNil(ISODates.parse("2026-03-14T15:00"))
        XCTAssertNil(ISODates.parse("tomorrow"))
    }
}
