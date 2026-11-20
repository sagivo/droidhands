import Foundation
import Security

/// PKCE (RFC 7636, S256) for signing in to a DroidHands account: the app keeps [newVerifier] for the
/// attempt it started and sends only its [challenge], so a sign-in code that another app catches on its
/// way back (`droidhands://signed-in?code=…`) is useless without the verifier.
enum Pkce {
    static let method = "S256"

    /// 43 base64url characters (32 random bytes).
    static func newVerifier() -> String {
        Encoding.base64URL(Encoding.randomBytes(32))
    }

    /// base64url(SHA-256(ASCII(verifier))), unpadded.
    static func challenge(for verifier: String) -> String {
        Encoding.base64URL(Encoding.sha256(Data(verifier.utf8)))
    }
}

/// The signed-in person, as the API server describes them.
struct AccountUser: Codable, Equatable, Sendable {
    var id: String
    var name: String
    var email: String
    var image: String?

    init(id: String, name: String = "", email: String = "", image: String? = nil) {
        self.id = id
        self.name = name
        self.email = email
        self.image = image
    }

    var displayName: String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? email : trimmed
    }

    var subtitleEmail: String? {
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedEmail = email.trimmingCharacters(in: .whitespacesAndNewlines)
        return (!trimmedName.isEmpty && !trimmedEmail.isEmpty) ? trimmedEmail : nil
    }
}

/// A signed-in session: [token] is a bearer secret, never logged.
struct AccountSession: Codable, Equatable, Sendable, CustomStringConvertible {
    let token: String
    var user: AccountUser

    var description: String { "AccountSession(token=***, user=\(user.id))" }
}

/// A short-lived token for the relay's hello (`accountToken`, docs/PROTOCOL.md §3.1).
struct RelayToken: Equatable, Sendable, CustomStringConvertible {
    let token: String
    /// Epoch time when the token expires.
    let expiresAt: Date

    var description: String { "RelayToken(token=***, expiresAt=\(expiresAt.timeIntervalSince1970))" }
}

/// What an accounts API call came to.
enum AccountResult<T> {
    case ok(T)
    /// HTTP 401: the session expired, was signed out elsewhere, or the account was deleted.
    case signedOut
    /// Anything else: no network, a server error, a refused code. [error] is the server's code (`invalid_grant`), if any.
    case failed(message: String, error: String? = nil)
}

/// What the app's account gave for one relay connection attempt (`hello.accountToken`, docs/PROTOCOL.md §3.1).
enum RelayAccountToken: Equatable, Sendable, CustomStringConvertible {
    case token(String)
    /// Nobody is signed in (or the session ended): don't connect until they sign in.
    case signedOut
    /// The API server can't be reached right now: retry with backoff.
    case unavailable(String)

    var description: String {
        switch self {
        case .token: return "Token(***)"
        case .signedOut: return "SignedOut"
        case .unavailable(let message): return "Unavailable(\(message))"
        }
    }
}

/// The API server's calls ([AccountClient]); a protocol so account logic can be tested without HTTP.
protocol AccountApi: AnyObject {
    func signInURL(challenge: String) -> URL?
    func exchange(code: String, verifier: String) async -> AccountResult<AccountSession>
    func me(sessionToken: String) async -> AccountResult<AccountUser>
    func relayToken(sessionToken: String) async -> AccountResult<RelayToken>
    func signOut(sessionToken: String) async -> AccountResult<Void>
    func deleteAccount(sessionToken: String) async -> AccountResult<Void>
}

/// The DroidHands API server (sign in with Google, then a session for this app; docs/PROTOCOL.md §7).
final class AccountClient: AccountApi {
    let baseURL: String
    private let session: URLSession

    init(baseURL: String, session: URLSession = .shared) {
        self.baseURL = RelayURLs.normalize(baseURL)
        self.session = session
    }

    func signInURL(challenge: String) -> URL? {
        guard var components = URLComponents(string: "\(baseURL)/app/sign-in") else { return nil }
        components.queryItems = [
            URLQueryItem(name: "code_challenge", value: challenge),
            URLQueryItem(name: "code_challenge_method", value: Pkce.method),
        ]
        return components.url
    }

    func exchange(code: String, verifier: String) async -> AccountResult<AccountSession> {
        let body = JSON.object(JSONObject([("code", .string(code)), ("code_verifier", .string(verifier))]))
        return await call(method: "POST", path: "/app/token", bearer: nil, body: body) { json in
            guard let token = json["sessionToken"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines), !token.isEmpty,
                  let userObj = json["user"]?.objectValue,
                  let user = AccountClient.decodeUser(userObj) else { return nil }
            return AccountSession(token: token, user: user)
        }
    }

    func me(sessionToken: String) async -> AccountResult<AccountUser> {
        await call(method: "GET", path: "/app/me", bearer: sessionToken, body: nil) { json in
            guard let userObj = json["user"]?.objectValue else { return nil }
            return AccountClient.decodeUser(userObj)
        }
    }

    func relayToken(sessionToken: String) async -> AccountResult<RelayToken> {
        await call(method: "POST", path: "/app/relay-token", bearer: sessionToken, body: .object(JSONObject())) { json in
            guard let token = json["token"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines), !token.isEmpty,
                  let expiresAtMs = json["expiresAt"]?.doubleValue else { return nil }
            return RelayToken(token: token, expiresAt: Date(timeIntervalSince1970: expiresAtMs / 1000))
        }
    }

    func signOut(sessionToken: String) async -> AccountResult<Void> {
        await call(method: "POST", path: "/app/sign-out", bearer: sessionToken, body: .object(JSONObject()), allowEmpty: true) { _ in () }
    }

    func deleteAccount(sessionToken: String) async -> AccountResult<Void> {
        await call(method: "POST", path: "/app/delete-account", bearer: sessionToken, body: .object(JSONObject()), allowEmpty: true) { _ in () }
    }

    private func call<T>(
        method: String,
        path: String,
        bearer: String?,
        body: JSON?,
        allowEmpty: Bool = false,
        parse: (JSONObject) -> T?
    ) async -> AccountResult<T> {
        guard let url = URL(string: "\(baseURL)\(path)") else {
            return .failed(message: "Invalid accounts URL")
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 20
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let bearer {
            request.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        }
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = Data(body.encoded.utf8)
        }
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                return .failed(message: "Unexpected answer from the DroidHands server")
            }
            if http.statusCode == 401 { return .signedOut }
            let text = String(data: data, encoding: .utf8) ?? ""
            let object: JSONObject? = text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? nil
                : (try? JSON.parse(text))?.objectValue
            guard (200..<300).contains(http.statusCode) else {
                let error = object?["error"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines)
                let description = object?["error_description"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines)
                let message = (description?.isEmpty == false ? description : nil)
                    ?? "The DroidHands server answered HTTP \(http.statusCode)"
                return .failed(message: message, error: error?.isEmpty == false ? error : nil)
            }
            if allowEmpty && object == nil {
                if let value = parse(JSONObject()) { return .ok(value) }
                return .failed(message: "Unexpected answer from the DroidHands server")
            }
            guard let object, let value = parse(object) else {
                return .failed(message: "Unexpected answer from the DroidHands server")
            }
            return .ok(value)
        } catch {
            return .failed(message: "Couldn't reach the DroidHands server: \(error.localizedDescription)")
        }
    }

    private static func decodeUser(_ obj: JSONObject) -> AccountUser? {
        guard let id = obj["id"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines), !id.isEmpty else { return nil }
        let name = obj["name"]?.stringValue ?? ""
        let email = obj["email"]?.stringValue ?? ""
        let image = obj["image"]?.stringValue
        return AccountUser(id: id, name: name, email: email, image: image)
    }
}

/// A sign-in started in the browser: the PKCE verifier, when it started, and whether to go online with the relay after.
struct PendingSignIn: Codable, Equatable, Sendable, CustomStringConvertible {
    let verifier: String
    let startedAt: Date
    let useRelay: Bool

    var description: String { "PendingSignIn(verifier=***, startedAt=\(startedAt), useRelay=\(useRelay))" }
}

/// Where the account session and pending sign-in live (production: Keychain, never backed up).
protocol AccountStorage: AnyObject {
    func loadSession() -> AccountSession?
    func saveSession(_ session: AccountSession)
    func clearSession()
    func savePending(_ pending: PendingSignIn)
    /// The pending sign-in, removed so its link can be used once.
    func takePending() -> PendingSignIn?
}

/// Stores the account session and pending PKCE verifier in the Keychain, this device only (never synced/backed up).
final class KeychainAccountStore: AccountStorage {
    private static let service = "com.droidhands.account"
    private static let sessionAccount = "session"
    private static let pendingAccount = "pending-sign-in"

    func loadSession() -> AccountSession? {
        guard let data = read(account: Self.sessionAccount) else { return nil }
        guard let session = try? JSONFile<AccountSession>.decoder.decode(AccountSession.self, from: data),
              !session.token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !session.user.id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            clearSession()
            return nil
        }
        return session
    }

    func saveSession(_ session: AccountSession) {
        guard let data = try? JSONFile<AccountSession>.encoder.encode(session) else { return }
        write(data, account: Self.sessionAccount)
    }

    func clearSession() {
        delete(account: Self.sessionAccount)
    }

    func savePending(_ pending: PendingSignIn) {
        guard let data = try? JSONFile<PendingSignIn>.encoder.encode(pending) else { return }
        write(data, account: Self.pendingAccount)
    }

    func takePending() -> PendingSignIn? {
        defer { delete(account: Self.pendingAccount) }
        guard let data = read(account: Self.pendingAccount) else { return nil }
        return try? JSONFile<PendingSignIn>.decoder.decode(PendingSignIn.self, from: data)
    }

    private func baseQuery(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
            kSecAttrAccount as String: account,
        ]
    }

    private func read(account: String) -> Data? {
        var query = baseQuery(account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess else { return nil }
        return item as? Data
    }

    private func write(_ data: Data, account: String) {
        SecItemDelete(baseQuery(account: account) as CFDictionary)
        var item = baseQuery(account: account)
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(item as CFDictionary, nil)
    }

    private func delete(account: String) {
        SecItemDelete(baseQuery(account: account) as CFDictionary)
    }
}

/// How a sign-in is going, for the "Continue with Google" button.
enum SignInState: Equatable, Sendable {
    case idle
    /// Back from the browser: trading the code for a session.
    case finishing
    case failed(String)
}

/// What a `droidhands://signed-in` link came to.
enum SignInOutcome: Equatable, Sendable {
    case signedIn(useRelay: Bool)
    /// Not ours: no sign-in was started here (or it was too long ago), or the link is malformed.
    case ignored
    case failed(String)
}

/// `droidhands://signed-in?code=…` or `?error=…&error_description=…`, the API server's answer. Pure.
enum SignInLinks {
    static let scheme = "droidhands"
    static let host = "signed-in"

    struct Answer: Equatable {
        let code: String?
        let error: String?
        let description: String?
    }

    static func isSignInLink(_ raw: String) -> Bool {
        guard let url = URL(string: raw.trimmingCharacters(in: .whitespacesAndNewlines)) else { return false }
        return isSignInLink(url)
    }

    static func isSignInLink(_ url: URL) -> Bool {
        url.scheme?.lowercased() == scheme && url.host?.lowercased() == host
    }

    /// The answer in [raw], or nil when it isn't a sign-in link or has neither a code nor an error.
    static func parse(_ raw: String) -> Answer? {
        guard let url = URL(string: raw.trimmingCharacters(in: .whitespacesAndNewlines)) else { return nil }
        return parse(url)
    }

    static func parse(_ url: URL) -> Answer? {
        guard isSignInLink(url),
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let items = components.queryItems else { return nil }
        var params: [String: String] = [:]
        for item in items {
            if let value = item.value { params[item.name] = value }
        }
        let code = params["code"]?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        let error = params["error"]?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        if code == nil && error == nil { return nil }
        let desc = params["error_description"]?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty.map { String($0.prefix(200)) }
        return Answer(code: code, error: error, description: desc)
    }

    /// What to tell the person about a sign-in that ended with [error].
    static func errorText(error: String?, description: String?) -> String {
        if error == "access_denied" { return "Sign-in was cancelled." }
        if let description, !description.isEmpty { return "Couldn't sign in: \(description)" }
        return AccountManager.signInFailedMessage
    }
}

/// Manages the DroidHands account on this iPhone: PKCE sign-in, session persistence, cached short-lived relay tokens,
/// refresh, sign-out and deletion. Thread-safe.
final class AccountManager {
    /// A sign-in link is accepted this long after the sign-in started.
    static let pendingTTL: TimeInterval = 15 * 60
    /// A relay token this close to expiring is replaced.
    static let tokenMargin: TimeInterval = 60
    static let signInFailedMessage = "Sign-in didn't work. Try again."

    private let api: AccountApi?
    private let storage: AccountStorage
    private let clock: () -> Date
    private let lock = NSLock()
    private var _session: AccountSession?
    private var _signInState: SignInState = .idle
    private var cachedToken: (session: AccountSession, token: RelayToken)?

    var onSessionChange: ((AccountSession?) -> Void)?
    var onSignInStateChange: ((SignInState) -> Void)?

    /// This build signs in to an API server.
    var enabled: Bool { api != nil }

    var session: AccountSession? { lock.withLock { _session } }
    var signInState: SignInState { lock.withLock { _signInState } }

    init(api: AccountApi?, storage: AccountStorage, clock: @escaping () -> Date = Date.init) {
        self.api = api
        self.storage = storage
        self.clock = clock
        _session = api == nil ? nil : storage.loadSession()
    }

    /// A non-secret tag of the current session (nil when signed out). Changes with every sign-in.
    func sessionTag() -> String? {
        session.map { Self.tag(of: $0.token) }
    }

    /// Starts signing in: remembers a new PKCE verifier and returns the URL to open (nil without an API server).
    func startSignIn(useRelay: Bool) -> URL? {
        guard let api else { return nil }
        let verifier = Pkce.newVerifier()
        storage.savePending(PendingSignIn(verifier: verifier, startedAt: clock(), useRelay: useRelay))
        setSignInState(.idle)
        return api.signInURL(challenge: Pkce.challenge(for: verifier))
    }

    /// Finishes the sign-in that `droidhands://signed-in?…` answers.
    func completeSignIn(_ raw: String) async -> SignInOutcome {
        guard let url = URL(string: raw.trimmingCharacters(in: .whitespacesAndNewlines)) else { return .ignored }
        return await completeSignIn(url)
    }

    func completeSignIn(_ url: URL) async -> SignInOutcome {
        guard let api, let answer = SignInLinks.parse(url) else { return .ignored }
        guard let pending = storage.takePending() else { return .ignored }
        let age = clock().timeIntervalSince(pending.startedAt)
        guard age >= 0 && age <= Self.pendingTTL else { return .ignored }
        guard let code = answer.code else {
            return fail(SignInLinks.errorText(error: answer.error, description: answer.description))
        }
        setSignInState(.finishing)
        switch await api.exchange(code: code, verifier: pending.verifier) {
        case .ok(let session):
            storage.saveSession(session)
            lock.withLock {
                cachedToken = nil
                _session = session
            }
            notifySession(session)
            setSignInState(.idle)
            return .signedIn(useRelay: pending.useRelay)
        case .signedOut:
            return fail(Self.signInFailedMessage)
        case .failed(let message, let error):
            let text = error == "invalid_grant" ? "That sign-in expired. Try again." : "Couldn't sign in: \(message)"
            return fail(text)
        }
    }

    func clearSignInError() {
        let shouldClear = lock.withLock {
            if case .failed = _signInState {
                _signInState = .idle
                return true
            }
            return false
        }
        if shouldClear { onSignInStateChange?(.idle) }
    }

    /// A short-lived token for the relay's hello, reused until a minute before it expires.
    func relayToken() async -> RelayAccountToken {
        guard let api else { return .signedOut }
        guard let current = session else { return .signedOut }
        if let cached = lock.withLock({ cachedToken }),
           cached.session == current,
           cached.token.expiresAt.timeIntervalSince(clock()) > Self.tokenMargin {
            return .token(cached.token.token)
        }
        switch await api.relayToken(sessionToken: current.token) {
        case .ok(let relayToken):
            lock.withLock {
                if _session == current { cachedToken = (current, relayToken) }
            }
            return .token(relayToken.token)
        case .signedOut:
            drop(current)
            return .signedOut
        case .failed(let message, _):
            return .unavailable(message)
        }
    }

    /// Re-reads the name and email; drops the session if the server no longer accepts it.
    func refresh() async {
        guard let api, let current = session else { return }
        switch await api.me(sessionToken: current.token) {
        case .ok(let user):
            guard user != current.user else { return }
            let updated = AccountSession(token: current.token, user: user)
            let changed = lock.withLock { () -> Bool in
                guard _session == current else { return false }
                _session = updated
                return true
            }
            if changed {
                storage.saveSession(updated)
                notifySession(updated)
            }
        case .signedOut:
            drop(current)
        case .failed:
            break // Offline: keep what we know.
        }
    }

    /// Signs out here, and on the server when it can be reached.
    func signOut() async {
        guard let current = session else { return }
        _ = await api?.signOut(sessionToken: current.token)
        drop(current)
    }

    /// Deletes the account on the server, then signs out here. Nil on success, else why it failed.
    func deleteAccount() async -> String? {
        guard let api, let current = session else { return nil }
        switch await api.deleteAccount(sessionToken: current.token) {
        case .ok, .signedOut:
            drop(current)
            return nil
        case .failed(let message, _):
            return message
        }
    }

    private func drop(_ target: AccountSession) {
        let dropped = lock.withLock { () -> Bool in
            guard _session == target else { return false }
            _session = nil
            cachedToken = nil
            return true
        }
        if dropped {
            storage.clearSession()
            notifySession(nil)
        }
    }

    private func fail(_ message: String) -> SignInOutcome {
        setSignInState(.failed(message))
        return .failed(message)
    }

    private func setSignInState(_ state: SignInState) {
        let changed = lock.withLock { () -> Bool in
            guard _signInState != state else { return false }
            _signInState = state
            return true
        }
        if changed { onSignInStateChange?(state) }
    }

    private func notifySession(_ session: AccountSession?) {
        onSessionChange?(session)
    }

    private static func tag(of token: String) -> String {
        Encoding.hex(Encoding.sha256(Data(token.utf8)).prefix(8))
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
