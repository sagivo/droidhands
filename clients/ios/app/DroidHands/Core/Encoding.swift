import CryptoKit
import Foundation
import Security

/// The encodings of docs/PROTOCOL.md §1.
enum Encoding {
    private static let base32Alphabet = Array("abcdefghijklmnopqrstuvwxyz234567")

    /// RFC 4648 base32, lowercase, no padding.
    static func base32(_ data: Data) -> String {
        var out = ""
        var buffer: UInt32 = 0
        var bits = 0
        for byte in data {
            buffer = (buffer << 8) | UInt32(byte)
            bits += 8
            while bits >= 5 {
                out.append(base32Alphabet[Int((buffer >> UInt32(bits - 5)) & 31)])
                bits -= 5
            }
        }
        if bits > 0 { out.append(base32Alphabet[Int((buffer << UInt32(5 - bits)) & 31)]) }
        return out
    }

    /// RFC 4648 §5 base64url, no padding.
    static func base64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// Decodes unpadded base64url; nil for anything else.
    static func base64URLDecode(_ text: String) -> Data? {
        guard text.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }) else { return nil }
        var s = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        let rem = s.count % 4
        if rem == 1 { return nil }
        if rem > 0 { s += String(repeating: "=", count: 4 - rem) }
        return Data(base64Encoded: s)
    }

    static func sha256(_ data: Data) -> Data { Data(SHA256.hash(data: data)) }

    static func hex(_ data: Data) -> String { data.map { String(format: "%02x", $0) }.joined() }

    /// Compares without stopping at the first difference.
    static func constantTimeEquals(_ a: String, _ b: String) -> Bool {
        let x = Array(a.utf8), y = Array(b.utf8)
        var diff = UInt8(x.count == y.count ? 0 : 1)
        for i in 0..<max(x.count, y.count) {
            diff |= (i < x.count ? x[i] : 0) ^ (i < y.count ? y[i] : 0)
        }
        return diff == 0
    }

    static func randomBytes(_ count: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        let status = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        precondition(status == errSecSuccess, "No secure random bytes")
        return Data(bytes)
    }
}

/// The device's long-term identity (docs/PROTOCOL.md §1): a 32-byte secret and the self-certifying id
/// `base32(SHA-256(secret)[0..16])`.
struct DeviceIdentity {
    static let secretBytes = 32
    private let secret: Data

    let deviceId: String

    init?(secret: Data) {
        guard secret.count == DeviceIdentity.secretBytes else { return nil }
        self.secret = secret
        deviceId = DeviceIdentity.deriveId(secret)
    }

    /// base64url (43 chars). Sent only to the relay in `hello`.
    var secretEncoded: String { Encoding.base64URL(secret) }

    static func deriveId(_ secret: Data) -> String {
        Encoding.base32(Encoding.sha256(secret).prefix(16))
    }

    static func isValidDeviceId(_ id: String) -> Bool {
        id.count == 26 && id.allSatisfy { ("a"..."z").contains($0) || ("2"..."7").contains($0) }
    }

    static func generate() -> DeviceIdentity { DeviceIdentity(secret: Encoding.randomBytes(secretBytes))! }
}

/// The device secret lives in the Keychain, readable after the first unlock and never synced or backed up to
/// another device (a restored backup gets a new identity, so agents pair again).
enum IdentityStore {
    private static let service = "com.droidhands.identity"
    private static let account = "device-secret"

    static func loadOrCreate() throws -> DeviceIdentity {
        if let existing = try load() { return existing }
        let identity = DeviceIdentity.generate()
        try save(identity)
        return identity
    }

    static func load() throws -> DeviceIdentity? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = item as? Data else { throw KeychainError(status: status) }
        guard let text = String(data: data, encoding: .utf8), let secret = Encoding.base64URLDecode(text),
              let identity = DeviceIdentity(secret: secret) else { return nil }
        return identity
    }

    static func save(_ identity: DeviceIdentity) throws {
        SecItemDelete(baseQuery as CFDictionary)
        var item = baseQuery
        item[kSecValueData as String] = Data(identity.secretEncoded.utf8)
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(item as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainError(status: status) }
    }

    /// A new identity: a new connection URL, and every agent must pair again.
    static func reset() throws -> DeviceIdentity {
        let identity = DeviceIdentity.generate()
        try save(identity)
        return identity
    }

    private static var baseQuery: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }
}

struct KeychainError: LocalizedError {
    let status: OSStatus
    var errorDescription: String? { "Keychain error \(status)" }
}
