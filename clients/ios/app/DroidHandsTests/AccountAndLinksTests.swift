import XCTest
@testable import DroidHands

final class AccountTests: XCTestCase {
    private let ada = AccountUser(id: "u1", name: "Ada", email: "ada@example.com")
    private var now = Date(timeIntervalSince1970: 1_000_000)

    private final class MemoryStorage: AccountStorage {
        var session: AccountSession?
        var pending: PendingSignIn?
        func loadSession() -> AccountSession? { session }
        func saveSession(_ session: AccountSession) { self.session = session }
        func clearSession() { session = nil }
        func savePending(_ pending: PendingSignIn) { self.pending = pending }
        func takePending() -> PendingSignIn? {
            defer { pending = nil }
            return pending
        }
    }

    private final class FakeApi: AccountApi {
        var challenge: String?
        var exchanged: (code: String, verifier: String)?
        var exchangeResult: AccountResult<AccountSession>
        var relayResult: AccountResult<RelayToken>
        var relayCalls = 0
        var meResult: AccountResult<AccountUser>
        var deleteResult: AccountResult<Void> = .ok(())
        var signedOut = 0

        init(ada: AccountUser, now: Date) {
            exchangeResult = .ok(AccountSession(token: "s.sig", user: ada))
            relayResult = .ok(RelayToken(token: "jwt", expiresAt: now.addingTimeInterval(600)))
            meResult = .ok(ada)
        }

        func signInURL(challenge: String) -> URL? {
            self.challenge = challenge
            return URL(string: "https://api.example.com/app/sign-in?code_challenge=\(challenge)")
        }

        func exchange(code: String, verifier: String) async -> AccountResult<AccountSession> {
            exchanged = (code, verifier)
            return exchangeResult
        }

        func me(sessionToken: String) async -> AccountResult<AccountUser> { meResult }

        func relayToken(sessionToken: String) async -> AccountResult<RelayToken> {
            relayCalls += 1
            return relayResult
        }

        func signOut(sessionToken: String) async -> AccountResult<Void> {
            signedOut += 1
            return .failed(message: "offline")
        }

        func deleteAccount(sessionToken: String) async -> AccountResult<Void> { deleteResult }
    }

    private var storage: MemoryStorage!
    private var api: FakeApi!

    override func setUp() {
        now = Date(timeIntervalSince1970: 1_000_000)
        storage = MemoryStorage()
        api = FakeApi(ada: ada, now: now)
    }

    private func manager() -> AccountManager {
        AccountManager(api: api, storage: storage, clock: { [unowned self] in self.now })
    }

    private func signedIn() async -> AccountManager {
        let m = manager()
        _ = m.startSignIn(useRelay: true)
        _ = await m.completeSignIn("droidhands://signed-in?code=c0de")
        return m
    }

    func testPkceMatchesRfcExample() {
        XCTAssertEqual(Pkce.challenge(for: "dBjftJeZ4CVP-mJ92IqVhlRVWeJjvCnXrnqDbRvqVZn"),
                       "djWC_Af3cAypxPgYos4jSdwmSfNUXiZjRlwgWoeSOPU")
        let verifier = Pkce.newVerifier()
        XCTAssertEqual(verifier.count, 43)
        XCTAssertTrue(verifier.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") })
    }

    func testSignInWithPkce() async {
        let accounts = manager()
        let url = accounts.startSignIn(useRelay: true)
        XCTAssertTrue(url?.absoluteString.hasPrefix("https://api.example.com/app/sign-in") == true)
        let verifier = storage.pending!.verifier
        XCTAssertEqual(Pkce.challenge(for: verifier), api.challenge)

        let outcome = await accounts.completeSignIn("droidhands://signed-in?code=c0de")
        XCTAssertEqual(outcome, .signedIn(useRelay: true))
        XCTAssertEqual(api.exchanged?.code, "c0de")
        XCTAssertEqual(api.exchanged?.verifier, verifier)
        XCTAssertEqual(accounts.session, AccountSession(token: "s.sig", user: ada))
        XCTAssertEqual(storage.session, AccountSession(token: "s.sig", user: ada))
        XCTAssertEqual(accounts.signInState, .idle)
        XCTAssertFalse(String(describing: accounts.session!).contains("s.sig"))
        // The link works once.
        let second = await accounts.completeSignIn("droidhands://signed-in?code=c0de")
        XCTAssertEqual(second, .ignored)
    }

    func testLinksNobodyAskedForAreIgnored() async {
        let accounts = manager()
        let planted = await accounts.completeSignIn("droidhands://signed-in?code=planted")
        XCTAssertEqual(planted, .ignored)
        XCTAssertNil(api.exchanged)
        // Too old.
        _ = accounts.startSignIn(useRelay: false)
        now = now.addingTimeInterval(AccountManager.pendingTTL + 1)
        let late = await accounts.completeSignIn("droidhands://signed-in?code=late")
        XCTAssertEqual(late, .ignored)
        XCTAssertNil(accounts.session)
        // Not a sign-in link at all.
        _ = accounts.startSignIn(useRelay: false)
        let other = await accounts.completeSignIn("droidhands://configure?relay=https://x.example.com")
        XCTAssertEqual(other, .ignored)
    }

    func testCancelledAndRefusedSignIns() async {
        let accounts = manager()
        _ = accounts.startSignIn(useRelay: true)
        let cancelled = await accounts.completeSignIn("droidhands://signed-in?error=access_denied&error_description=User%20cancelled")
        XCTAssertEqual(cancelled, .failed("Sign-in was cancelled."))
        XCTAssertEqual(accounts.signInState, .failed("Sign-in was cancelled."))

        api.exchangeResult = .failed(message: "expired", error: "invalid_grant")
        _ = accounts.startSignIn(useRelay: true)
        XCTAssertEqual(accounts.signInState, .idle)
        let expired = await accounts.completeSignIn("droidhands://signed-in?code=old")
        XCTAssertEqual(expired, .failed("That sign-in expired. Try again."))
        XCTAssertNil(accounts.session)
    }

    func testRelayTokensAreCachedUntilNearExpiry() async {
        let accounts = await signedIn()
        let first = await accounts.relayToken()
        let second = await accounts.relayToken()
        XCTAssertEqual(first, .token("jwt"))
        XCTAssertEqual(second, .token("jwt"))
        XCTAssertEqual(api.relayCalls, 1)

        now = now.addingTimeInterval(600 - AccountManager.tokenMargin)
        api.relayResult = .ok(RelayToken(token: "jwt2", expiresAt: now.addingTimeInterval(600)))
        let refreshed = await accounts.relayToken()
        XCTAssertEqual(refreshed, .token("jwt2"))
        XCTAssertEqual(api.relayCalls, 2)
    }

    func testSessionServerEndedSignsOutHere() async {
        let accounts = await signedIn()
        api.relayResult = .failed(message: "down")
        let offline = await accounts.relayToken()
        XCTAssertEqual(offline, .unavailable("down"))
        XCTAssertNotNil(accounts.session)

        api.relayResult = .signedOut
        let ended = await accounts.relayToken()
        XCTAssertEqual(ended, .signedOut)
        XCTAssertNil(accounts.session)
        XCTAssertNil(storage.session)
        XCTAssertNil(accounts.sessionTag())
    }

    func testRefreshUpdatesNameAndDropsEndedSessions() async {
        let accounts = await signedIn()
        api.meResult = .ok(AccountUser(id: "u1", name: "Ada L.", email: "ada@example.com"))
        await accounts.refresh()
        XCTAssertEqual(accounts.session?.user.name, "Ada L.")
        XCTAssertEqual(storage.session?.user.name, "Ada L.")

        api.meResult = .failed(message: "offline")
        await accounts.refresh()
        XCTAssertNotNil(accounts.session)

        api.meResult = .signedOut
        await accounts.refresh()
        XCTAssertNil(accounts.session)
    }

    func testSignOutWorksOfflineAndDeleteNeedsServer() async {
        let accounts = await signedIn()
        let tag = accounts.sessionTag()
        XCTAssertNotNil(tag)
        XCTAssertFalse(tag!.contains("s.sig"))
        await accounts.signOut()
        XCTAssertEqual(api.signedOut, 1)
        XCTAssertNil(accounts.session)

        let again = await signedIn()
        api.deleteResult = .failed(message: "server error")
        let failMsg = await again.deleteAccount()
        XCTAssertEqual(failMsg, "server error")
        XCTAssertNotNil(again.session)

        api.deleteResult = .ok(())
        let okMsg = await again.deleteAccount()
        XCTAssertNil(okMsg)
        XCTAssertNil(again.session)
    }

    func testWithoutApiNothingNeedsAccount() async {
        storage.session = AccountSession(token: "old", user: ada)
        let accounts = AccountManager(api: nil, storage: storage)
        XCTAssertFalse(accounts.enabled)
        XCTAssertNil(accounts.session)
        XCTAssertNil(accounts.startSignIn(useRelay: true))
        let tok = await accounts.relayToken()
        XCTAssertEqual(tok, .signedOut)
    }

    func testSignInLinks() {
        XCTAssertEqual(SignInLinks.parse("droidhands://signed-in?code=a%20b"),
                       SignInLinks.Answer(code: "a b", error: nil, description: nil))
        XCTAssertEqual(SignInLinks.parse("droidhands://signed-in?error=server_error&error_description=Oops"),
                       SignInLinks.Answer(code: nil, error: "server_error", description: "Oops"))
        XCTAssertNil(SignInLinks.parse("droidhands://signed-in"))
        XCTAssertNil(SignInLinks.parse("https://signed-in?code=x"))
        XCTAssertEqual(SignInLinks.errorText(error: "server_error", description: "Oops"), "Couldn't sign in: Oops")
        XCTAssertEqual(SignInLinks.errorText(error: nil, description: nil), AccountManager.signInFailedMessage)
    }
}

final class AccountClientTests: XCTestCase {
    private final class StubURLProtocol: URLProtocol {
        static var handler: ((URLRequest) throws -> (Int, String))?

        override class func canInit(with request: URLRequest) -> Bool { true }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

        override func startLoading() {
            guard let handler = Self.handler else { return }
            do {
                let (status, body) = try handler(request)
                let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil,
                                               headerFields: ["Content-Type": "application/json"])!
                client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
                client?.urlProtocol(self, didLoad: Data(body.utf8))
                client?.urlProtocolDidFinishLoading(self)
            } catch {
                client?.urlProtocol(self, didFailWithError: error)
            }
        }

        override func stopLoading() {}
    }

    private func makeClient() -> AccountClient {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [StubURLProtocol.self]
        return AccountClient(baseURL: "https://api.example.com/", session: URLSession(configuration: cfg))
    }

    private static func bodyString(_ request: URLRequest) -> String {
        if let data = request.httpBody { return String(data: data, encoding: .utf8) ?? "" }
        guard let stream = request.httpBodyStream else { return "" }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buf = [UInt8](repeating: 0, count: 1024)
        while stream.hasBytesAvailable {
            let n = stream.read(&buf, maxLength: buf.count)
            if n > 0 { data.append(buf, count: n) } else { break }
        }
        return String(data: data, encoding: .utf8) ?? ""
    }

    func testSignInURLCarriesChallenge() {
        let client = makeClient()
        let url = client.signInURL(challenge: "abc")!
        let c = URLComponents(url: url, resolvingAgainstBaseURL: false)!
        XCTAssertEqual(c.path, "/app/sign-in")
        XCTAssertEqual(c.queryItems?.first(where: { $0.name == "code_challenge" })?.value, "abc")
        XCTAssertEqual(c.queryItems?.first(where: { $0.name == "code_challenge_method" })?.value, "S256")
    }

    func testExchangeAndErrors() async {
        let client = makeClient()
        var capturedPath: String?
        var capturedBody: String?
        StubURLProtocol.handler = { req in
            capturedPath = req.url?.path
            capturedBody = Self.bodyString(req)
            return (200, #"{"sessionToken":"s.sig","user":{"id":"u1","name":"Ada","email":"ada@example.com","image":null,"extra":1}}"#)
        }
        let result = await client.exchange(code: "c0de", verifier: "v3rifier")
        guard case .ok(let session) = result else { return XCTFail("expected ok") }
        XCTAssertEqual(session.token, "s.sig")
        XCTAssertEqual(session.user, AccountUser(id: "u1", name: "Ada", email: "ada@example.com", image: nil))
        XCTAssertEqual(capturedPath, "/app/token")
        XCTAssertEqual(capturedBody, #"{"code":"c0de","code_verifier":"v3rifier"}"#)

        StubURLProtocol.handler = { _ in
            (400, #"{"error":"invalid_grant","error_description":"The sign-in code expired."}"#)
        }
        guard case .failed(let msg, let err) = await client.exchange(code: "old", verifier: "v") else { return XCTFail() }
        XCTAssertEqual(err, "invalid_grant")
        XCTAssertEqual(msg, "The sign-in code expired.")
    }

    func testBearerCallsSignedOutAndNoContent() async {
        let client = makeClient()
        var capturedAuth: String?
        StubURLProtocol.handler = { req in
            capturedAuth = req.value(forHTTPHeaderField: "Authorization")
            return (200, #"{"token":"jwt","expiresAt":1700000000000}"#)
        }
        guard case .ok(let token) = await client.relayToken(sessionToken: "s") else { return XCTFail() }
        XCTAssertEqual(token.token, "jwt")
        XCTAssertEqual(token.expiresAt, Date(timeIntervalSince1970: 1_700_000_000))
        XCTAssertEqual(capturedAuth, "Bearer s")

        StubURLProtocol.handler = { _ in (401, #"{"error":"unauthorized"}"#) }
        guard case .signedOut = await client.me(sessionToken: "s") else { return XCTFail() }

        StubURLProtocol.handler = { _ in (204, "") }
        guard case .ok = await client.signOut(sessionToken: "s") else { return XCTFail() }
        guard case .ok = await client.deleteAccount(sessionToken: "s") else { return XCTFail() }
    }
}

final class LinksAndRelayAccountTests: XCTestCase {
    private func pairing(id: String, code: String, expiresAt: Date) -> PairingRequest {
        PairingRequest(id: id, clientName: "Agent", redirectHost: "agent sign-in", displayCode: code,
                       remoteAddress: nil, userAgent: nil, expiresAt: expiresAt, viaAgent: true)
    }

    func testApproveLinksCodeParsing() {
        XCTAssertEqual(ApproveLinks.code(of: "droidhands://approve?code=k7q2"), "K7Q2")
        XCTAssertEqual(ApproveLinks.code(of: "droidhands://approve"), "")
        XCTAssertEqual(ApproveLinks.code(of: "droidhands://approve?code=%3Cb%3E"), "")
        XCTAssertNil(ApproveLinks.code(of: "droidhands://configure?relay=x"))
        XCTAssertNil(ApproveLinks.code(of: "https://connect.droidhands.com/approve?code=K7Q2"))
        XCTAssertNil(ApproveLinks.code(of: nil as String?))
    }

    func testApproveLinksPicksLiveMatchingOrNewest() {
        let now = Date(timeIntervalSince1970: 5)
        let pairings = [
            pairing(id: "a", code: "AAAA", expiresAt: Date(timeIntervalSince1970: 10)),
            pairing(id: "b", code: "BBBB", expiresAt: Date(timeIntervalSince1970: 10)),
            pairing(id: "old", code: "CCCC", expiresAt: Date(timeIntervalSince1970: 1)),
        ]
        XCTAssertEqual(ApproveLinks.pick(from: pairings, code: "AAAA", now: now)?.id, "a")
        XCTAssertEqual(ApproveLinks.pick(from: pairings, code: "", now: now)?.id, "b")
        // Never falls back to another agent's prompt or an expired one.
        XCTAssertNil(ApproveLinks.pick(from: pairings, code: "CCCC", now: now))
        XCTAssertNil(ApproveLinks.pick(from: pairings, code: "ZZZZ", now: now))
    }

    func testConfigureAndSetupLinkParsing() {
        let base64Key = "abc+def/123="
        guard case .valid(let req) = ConfigureLinks.parse("droidhands://configure?relay=https%3A%2F%2Frelay.example.com%2F&key=abc+def%2F123%3D") else {
            return XCTFail("expected valid configure link")
        }
        XCTAssertEqual(req.relayURL, "https://relay.example.com")
        XCTAssertEqual(req.accessKey, base64Key)
        XCTAssertFalse(req.insecure)
        XCTAssertFalse(req.description.contains(base64Key))

        // Setup page with fragment key
        guard case .valid(let setup) = SetupLinkParser.parse("https://relay.example.com/setup#key=secret+1") else {
            return XCTFail("expected valid setup page link")
        }
        XCTAssertEqual(setup.relayURL, "https://relay.example.com")
        XCTAssertEqual(setup.accessKey, "secret+1")
        XCTAssertTrue(SetupLinkParser.isSetupLink("https://relay.example.com/setup#key=secret+1"))
        XCTAssertFalse(SetupLinkParser.isSetupLink("https://relay.example.com"))

        // Agent /mcp URL refused clearly
        guard case .invalid(let msg) = SetupLinkParser.parse("https://relay.example.com/d/abc/mcp") else {
            return XCTFail("expected /mcp rejection")
        }
        XCTAssertEqual(msg, SetupLinkParser.agentURLMessage)

        // Plain HTTP to public host marked insecure, local IP allowed
        guard case .insecure = RelayURLs.check("http://relay.example.com") else { return XCTFail() }
        guard case .ok(let local) = RelayURLs.check("http://192.168.1.5:8788/") else { return XCTFail() }
        XCTAssertEqual(local, "http://192.168.1.5:8788")
    }

    func testDefaultRelayAndOnboardingSteps() {
        let def = "https://connect.droidhands.com"
        XCTAssertEqual(DefaultRelay.url("https://connect.droidhands.com/"), def)
        XCTAssertNil(DefaultRelay.url("http://connect.droidhands.com"))
        XCTAssertTrue(DefaultRelay.canOffer(relayEnabled: false, relayURL: "", relayFromDefault: false, rawDefaultURL: def))
        XCTAssertFalse(DefaultRelay.canOffer(relayEnabled: true, relayURL: def, relayFromDefault: true, rawDefaultURL: def))
        XCTAssertFalse(DefaultRelay.canOffer(relayEnabled: false, relayURL: "https://mine.example.com", relayFromDefault: false, rawDefaultURL: def))

        XCTAssertEqual(OnboardingStep.steps(defaultRelayInUse: false), [.welcome, .permissions, .connect, .agent])
        XCTAssertEqual(OnboardingStep.steps(defaultRelayInUse: true), [.welcome, .permissions, .agent])
        XCTAssertEqual(OnboardingStep.next(after: .permissions, in: [.welcome, .permissions, .connect, .agent]), .connect)
        XCTAssertEqual(OnboardingStep.previous(before: .agent, in: [.welcome, .permissions, .agent]), .permissions)
    }

    func testRelayHelloFrameAndSignedOutFatal() async throws {
        let identity = DeviceIdentity(secret: Data((0..<32).map { UInt8($0) }))!
        let config = RelayClient.Config(relayURL: "https://connect.droidhands.com", accessKey: nil, deviceName: "iPhone", appVersion: "0.1.0")
        let withToken = try JSON.parse(RelayClient.helloFrame(identity: identity, config: config, accountToken: "jwt-123"))
        XCTAssertEqual(withToken["accountToken"], "jwt-123")
        let withoutToken = try JSON.parse(RelayClient.helloFrame(identity: identity, config: config, accountToken: nil))
        XCTAssertNil(withoutToken["accountToken"])

        // Signed out is fatal immediately with account_required, without connecting.
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let tokens = try TokenManager(store: JSONFile(name: "t.json", directory: dir), deviceId: identity.deviceId)
        let audit = AuditLog(file: JSONFile(name: "a.json", directory: dir))
        let server = McpServer(tools: [], tokens: tokens, audit: audit, confirmations: nil, instructions: "", serverVersion: "1", isAccessEnabled: { true })
        final class NoopPairing: PairingHandler {
            func onPairRequest(_ request: PairingRequest) async -> PairingDecision { .denied("denied") }
            func onPairFinished(id: String, tokenId: String?, completed: Bool) {}
        }
        let pairing = NoopPairing()
        let exp = expectation(description: "signed out fatal")
        let client = RelayClient(identity: identity, config: config, server: server, pairing: pairing, accountToken: { .signedOut })
        client.onState = { state in
            if state.isAccountRequired { exp.fulfill() }
        }
        client.start()
        await fulfillment(of: [exp], timeout: 5)
        XCTAssertTrue(client.state.isAccountRequired)
        client.stop()
    }
}
