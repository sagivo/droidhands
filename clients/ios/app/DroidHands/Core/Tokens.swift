import Foundation

/// Persisted token metadata. The secret itself is never stored, only its SHA-256 (hex).
struct TokenRecord: Codable, Identifiable, Equatable {
    var id: String
    var name: String
    var secretHash: String
    var scopes: Set<String>
    var confirmPolicy: ConfirmPolicy = .NEVER
    var createdAt: Date
    var expiresAt: Date?
    var lastUsedAt: Date?
    var revoked = false
    /// "manual" (made in the app) or "pairing" (one-tap OAuth pairing, docs/PROTOCOL.md §5).
    var origin = "manual"
    /// For paired agents: the OAuth redirect host, e.g. "claude.ai".
    var clientHost: String?
    /// The client's own name when the phone shows another one.
    var declaredName: String?
    var toolRules: [String: ToolRule] = [:]
    /// Phone-local hours when the agent may act (nil = any time).
    var activeHours: ActiveHours?
    /// Highest tool risk this agent may use (nil = no cap).
    var maxRisk: Risk?

    func isExpired(at now: Date) -> Bool { expiresAt.map { now >= $0 } ?? false }
    func isActive(at now: Date) -> Bool { !revoked && !isExpired(at: now) }
}

/// `pb1.<deviceId>.<tokenId>.<secret>` (docs/PROTOCOL.md §2).
enum TokenFormat {
    struct Parsed: Equatable { let deviceId: String, tokenId: String, secret: String }

    static func format(deviceId: String, tokenId: String, secret: String) -> String { "pb1.\(deviceId).\(tokenId).\(secret)" }

    static func parse(_ raw: String) -> Parsed? {
        let parts = raw.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 4, parts[0] == "pb1",
              DeviceIdentity.isValidDeviceId(parts[1]),
              parts[2].count == 12, parts[2].allSatisfy(isBase32Char),
              parts[3].count == 43, parts[3].allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") })
        else { return nil }
        return Parsed(deviceId: parts[1], tokenId: parts[2], secret: parts[3])
    }

    /// `Bearer <token>` (any case) or a bare token.
    static func fromAuthorization(_ header: String?) -> String? {
        guard let h = header?.trimmingCharacters(in: .whitespaces), !h.isEmpty else { return nil }
        if h.lowercased().hasPrefix("bearer ") { return String(h.dropFirst(7)).trimmingCharacters(in: .whitespaces) }
        return h
    }

    private static func isBase32Char(_ c: Character) -> Bool { ("a"..."z").contains(c) || ("2"..."7").contains(c) }
}

enum AuthResult {
    case ok(TokenRecord)
    /// The reason is for the local audit log only.
    case denied(String)
}

/// Issues and verifies agent tokens. Thread-safe; saves before publishing a change, so memory and disk never
/// silently disagree.
final class TokenManager {
    private let lock = NSLock()
    private let store: JSONFile<[TokenRecord]>
    private let deviceId: String
    private let now: () -> Date
    private var records: [TokenRecord]

    /// Called after every change (on the thread that made it).
    var onChange: (([TokenRecord]) -> Void)?

    init(store: JSONFile<[TokenRecord]>, deviceId: String, now: @escaping () -> Date = Date.init) throws {
        self.store = store
        self.deviceId = deviceId
        self.now = now
        records = try (store.load() ?? []).sorted { $0.createdAt > $1.createdAt }
    }

    /// All tokens (revoked and expired included, for display), newest first.
    var all: [TokenRecord] { lock.withLock { records } }

    func record(_ id: String) -> TokenRecord? { lock.withLock { records.first { $0.id == id } } }

    /// Makes a token. The returned plaintext is the only copy of the secret.
    func issue(name: String, scopes: Set<String>, confirmPolicy: ConfirmPolicy, origin: String = "manual",
               clientHost: String? = nil, declaredName: String? = nil, expiresIn: TimeInterval? = nil) throws -> (TokenRecord, String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw ToolError("The agent needs a name.") }
        let secretBytes = Encoding.randomBytes(32)
        let secret = Encoding.base64URL(secretBytes)
        let hash = Encoding.hex(Encoding.sha256(secretBytes))
        return try lock.withLock {
            let ids = Set(records.map(\.id))
            var id: String
            repeat { id = String(Encoding.base32(Encoding.randomBytes(8)).prefix(12)) } while ids.contains(id)
            let created = now()
            let record = TokenRecord(id: id, name: String(trimmed.prefix(60)), secretHash: hash, scopes: scopes.intersection(Scopes.all),
                                     confirmPolicy: confirmPolicy, createdAt: created, expiresAt: expiresIn.map { created.addingTimeInterval($0) },
                                     origin: origin, clientHost: clientHost, declaredName: declaredName)
            try commit([record] + records)
            return (record, TokenFormat.format(deviceId: deviceId, tokenId: id, secret: secret))
        }
    }

    func authenticate(_ authorization: String?) -> AuthResult {
        guard let raw = TokenFormat.fromAuthorization(authorization) else { return .denied("missing token") }
        guard let parsed = TokenFormat.parse(raw) else { return .denied("malformed token") }
        guard parsed.deviceId == deviceId else { return .denied("token is for another device") }
        guard let secretBytes = Encoding.base64URLDecode(parsed.secret) else { return .denied("malformed token secret") }
        // Hash before the lookup so unknown ids and wrong secrets take similar time.
        let presented = Encoding.hex(Encoding.sha256(secretBytes))
        return lock.withLock {
            guard let index = records.firstIndex(where: { $0.id == parsed.tokenId }) else { return .denied("unknown token id \(parsed.tokenId)") }
            let record = records[index]
            guard Encoding.constantTimeEquals(presented, record.secretHash) else { return .denied("wrong secret for token \(record.id)") }
            if record.revoked { return .denied("token \(record.id) is revoked") }
            let t = now()
            if record.isExpired(at: t) { return .denied("token \(record.id) expired") }
            // Last use, saved at most once a minute; a failing save must not fail authentication.
            if record.lastUsedAt.map({ t.timeIntervalSince($0) >= 60 || t < $0 }) ?? true {
                var next = records
                next[index].lastUsedAt = t
                try? store.save(next)
                records = next
                notify()
                return .ok(next[index])
            }
            return .ok(record)
        }
    }

    /// Edits a token's settings; provenance fields can't change.
    @discardableResult
    func update(_ id: String, _ transform: (inout TokenRecord) -> Void) throws -> TokenRecord {
        try lock.withLock {
            guard let index = records.firstIndex(where: { $0.id == id }) else { throw ToolError("Unknown agent") }
            let existing = records[index]
            var edited = existing
            transform(&edited)
            edited.id = existing.id
            edited.secretHash = existing.secretHash
            edited.createdAt = existing.createdAt
            edited.origin = existing.origin
            edited.clientHost = existing.clientHost
            edited.revoked = existing.revoked
            edited.name = edited.name.trimmingCharacters(in: .whitespacesAndNewlines)
            edited.scopes = edited.scopes.intersection(Scopes.all)
            guard !edited.name.isEmpty else { throw ToolError("The agent needs a name.") }
            if edited == existing { return existing }
            var next = records
            next[index] = edited
            try commit(next)
            return edited
        }
    }

    func revoke(_ id: String) throws {
        try lock.withLock {
            guard records.contains(where: { $0.id == id && !$0.revoked }) else { return }
            try commit(records.map { var r = $0; if r.id == id { r.revoked = true }; return r })
        }
    }

    @discardableResult
    func revokeAll() throws -> Int {
        try lock.withLock {
            let count = records.filter { !$0.revoked }.count
            guard count > 0 else { return 0 }
            try commit(records.map { var r = $0; r.revoked = true; return r })
            return count
        }
    }

    func delete(_ id: String) throws {
        try lock.withLock {
            guard records.contains(where: { $0.id == id }) else { return }
            try commit(records.filter { $0.id != id })
        }
    }

    func deleteAll() throws {
        try lock.withLock { try commit([]) }
    }

    /// Must hold the lock.
    private func commit(_ next: [TokenRecord]) throws {
        try store.save(next)
        records = next
        notify()
    }

    private func notify() {
        let snapshot = records
        onChange?(snapshot)
    }
}

/// A JSON file in Application Support, written atomically and encrypted until the first unlock after a restart.
struct JSONFile<Value: Codable> {
    let url: URL

    init(name: String, directory: URL = JSONFile.defaultDirectory) {
        url = directory.appendingPathComponent(name)
    }

    static var defaultDirectory: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("DroidHands", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var d = dir
        try? d.setResourceValues(values)
        return dir
    }

    func load() throws -> Value? {
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        let data = try Data(contentsOf: url)
        return try JSONFile.decoder.decode(Value.self, from: data)
    }

    func save(_ value: Value) throws {
        let data = try JSONFile.encoder.encode(value)
        try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    func delete() { try? FileManager.default.removeItem(at: url) }

    static var encoder: JSONEncoder {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .millisecondsSince1970
        return e
    }

    static var decoder: JSONDecoder {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .millisecondsSince1970
        return d
    }
}
