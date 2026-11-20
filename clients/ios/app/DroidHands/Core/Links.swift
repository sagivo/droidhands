import Foundation

/// Validation result for a typed or linked relay URL.
enum RelayURLCheck: Equatable {
    case empty
    case ok(normalized: String)
    /// Plain http:// to a public host: traffic (and keys) would cross the network in the clear.
    case insecure(normalized: String, message: String)
    case invalid(String)
}

/// Relay URL validation and normalization (pure, unit-tested).
enum RelayURLs {
    static let insecureMessage =
        "This address uses plain HTTP, not HTTPS: anyone on the network could read requests and the device secret. Use https:// unless this is a local test."

    /// Trims whitespace and trailing slashes.
    static func normalize(_ raw: String) -> String {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        while s.hasSuffix("/") { s.removeLast() }
        return s
    }

    static func check(_ raw: String) -> RelayURLCheck {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return .empty }
        guard let c = URLComponents(string: trimmed),
              let rawScheme = c.scheme?.lowercased(),
              ["https", "http", "wss", "ws"].contains(rawScheme),
              let host = c.host, !host.isEmpty else {
            return .invalid("Enter a full http:// or https:// address, like https://relay.example.com.")
        }
        if c.user != nil || c.password != nil {
            return .invalid("Relay addresses can't include a username or password.")
        }
        if c.query != nil || c.fragment != nil {
            return .invalid("Use the relay's base address without ?query or #fragment.")
        }
        var canonical = c
        canonical.scheme = (rawScheme == "wss" || rawScheme == "https") ? "https" : "http"
        let normalized = normalize(canonical.string ?? trimmed)
        if canonical.scheme == "http" && !isLocalOrPrivate(host: host) {
            return .insecure(normalized: normalized, message: insecureMessage)
        }
        return .ok(normalized: normalized)
    }

    /// Host (and port, if any) of a URL, e.g. `connect.droidhands.com` or `localhost:8788`.
    static func host(of url: String) -> String? {
        guard let c = URLComponents(string: url.trimmingCharacters(in: .whitespacesAndNewlines)),
              let host = c.host, !host.isEmpty else { return nil }
        if let port = c.port { return "\(host):\(port)" }
        return host
    }

    /// Localhost, loopback, RFC 1918 LAN IPs, Tailscale CGNAT (100.64.0.0/10), and mDNS `.local`.
    static func isLocalOrPrivate(host: String) -> Bool {
        let h = host.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        if ["localhost", "127.0.0.1", "::1"].contains(h) || h.hasSuffix(".local") { return true }
        let parts = h.split(separator: ".").compactMap { Int($0) }
        guard parts.count == 4, parts.allSatisfy({ (0...255).contains($0) }) else { return false }
        if parts[0] == 10 || parts[0] == 127 { return true }
        if parts[0] == 172 && (16...31).contains(parts[1]) { return true }
        if parts[0] == 192 && parts[1] == 168 { return true }
        if parts[0] == 100 && (64...127).contains(parts[1]) { return true }
        return false
    }
}

/// Rules for the build's default relay (mirrors Android's `DefaultRelay.kt`). Pure, unit-tested.
enum DefaultRelay {
    /// Usable, normalized default relay URL, or nil when the build has none (or it's insecure).
    static func url(_ raw: String?) -> String? {
        guard let raw else { return nil }
        if case .ok(let normalized) = RelayURLs.check(raw) { return normalized }
        return nil
    }

    /// Something to connect through is already set up.
    static func somethingConfigured(relayURL: String) -> Bool {
        !relayURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// The default relay is on and is the relay in use (the user didn't pick another).
    static func inUse(relayEnabled: Bool, relayFromDefault: Bool) -> Bool {
        relayEnabled && relayFromDefault
    }

    /// Offer "Connect using DroidHands": the build has a default relay and no relay of the user's is set up.
    static func canOffer(relayEnabled: Bool, relayURL: String, relayFromDefault: Bool, rawDefaultURL: String?) -> Bool {
        guard url(rawDefaultURL) != nil else { return false }
        if inUse(relayEnabled: relayEnabled, relayFromDefault: relayFromDefault) { return false }
        return relayURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || relayFromDefault
    }
}

/// A validated `droidhands://configure?relay=…&key=…` setup link (docs/PROTOCOL.md §5.5), waiting for user confirmation.
struct ConfigureRequest: Equatable, CustomStringConvertible {
    let relayURL: String
    let accessKey: String?
    let insecure: Bool

    var host: String { RelayURLs.host(of: relayURL) ?? relayURL }

    var displayLink: String {
        "droidhands://configure?relay=\(relayURL)" + (accessKey != nil ? "&key=\(ConfigureLinks.mask)" : "")
    }

    var description: String {
        "ConfigureRequest(relayURL=\(relayURL), accessKey=\(accessKey == nil ? "absent" : "***"))"
    }
}

enum ConfigureLinkResult: Equatable {
    case valid(ConfigureRequest)
    case invalid(String)
}

/// Pure parsing of `droidhands://configure?relay=…&key=…` setup links.
enum ConfigureLinks {
    static let scheme = "droidhands"
    static let host = "configure"
    static let mask = "••••••"
    private static let maxKeyLength = 512

    static func isConfigureLink(_ url: URL) -> Bool {
        url.scheme?.lowercased() == scheme && url.host?.lowercased() == host
    }

    static func isConfigureLink(_ raw: String?) -> Bool {
        guard let raw, let url = URL(string: raw.trimmingCharacters(in: .whitespacesAndNewlines)) else { return false }
        return isConfigureLink(url)
    }

    static func parse(_ raw: String) -> ConfigureLinkResult {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let components = URLComponents(string: trimmed) else {
            return .invalid("This setup link is malformed.")
        }
        guard components.scheme?.lowercased() == scheme, components.host?.lowercased() == host else {
            return .invalid("This isn't a DroidHands setup link.")
        }
        let params = rawQueryParameters(components.percentEncodedQuery)
        let relay = (params["relay"] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if relay.isEmpty { return .invalid("The setup link doesn't contain a relay address.") }
        let key = params["key"]?.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanKey = (key?.isEmpty == false) ? key : nil
        if let cleanKey {
            let badChars = cleanKey.unicodeScalars.contains { CharacterSet.controlCharacters.contains($0) || CharacterSet.whitespacesAndNewlines.contains($0) }
            if cleanKey.count > maxKeyLength || badChars {
                return .invalid("The access key in the setup link is not valid.")
            }
        }
        switch RelayURLs.check(relay) {
        case .ok(let normalized):
            return .valid(ConfigureRequest(relayURL: normalized, accessKey: cleanKey, insecure: false))
        case .insecure(let normalized, _):
            return .valid(ConfigureRequest(relayURL: normalized, accessKey: cleanKey, insecure: true))
        case .invalid(let message):
            return .invalid("The relay address in the setup link is not valid: \(message)")
        case .empty:
            return .invalid("The setup link doesn't contain a relay address.")
        }
    }

    /// Preserves literal `+` (common in base64 keys) while percent-decoding query params.
    static func rawQueryParameters(_ rawQuery: String?) -> [String: String] {
        guard let rawQuery, !rawQuery.isEmpty else { return [:] }
        var out: [String: String] = [:]
        for pair in rawQuery.split(separator: "&") {
            let s = String(pair)
            guard !s.isEmpty else { continue }
            let parts = s.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            guard let name = String(parts[0]).removingPercentEncoding else { continue }
            let value = parts.count > 1 ? (String(parts[1]).removingPercentEncoding ?? String(parts[1])) : ""
            if out[name] == nil { out[name] = value }
        }
        return out
    }
}

/// Reads a pasted setup link or QR code payload tolerantly (mirrors Android's `SetupQrPayload.kt`).
enum SetupLinkParser {
    private static let maxLength = 4096
    private static let setupPath = "/setup"

    static let notASetupLink =
        "This isn't a DroidHands relay setup link. Use the setup link your relay prints when it starts (its address ends in /setup)."
    static let agentURLMessage =
        "This is the address agents connect to, not a relay setup link. To connect this iPhone, use the setup link your relay prints when it starts."

    static func parse(_ payload: String?) -> ConfigureLinkResult {
        let text = (payload ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return .invalid("The setup link is empty.") }
        if text.count > maxLength { return .invalid(notASetupLink) }
        if text.unicodeScalars.contains(where: { CharacterSet.whitespacesAndNewlines.contains($0) || CharacterSet.controlCharacters.contains($0) }) {
            return .invalid(notASetupLink)
        }
        if let scheme = leadingScheme(text) {
            switch scheme {
            case ConfigureLinks.scheme:
                return ConfigureLinks.parse(text)
            case "intent":
                return fromIntentURI(text)
            case "http", "https":
                return fromWebURL(text)
            default:
                return .invalid(notASetupLink)
            }
        }
        return schemeless(text)
    }

    /// True if [text] is a usable setup link rather than a plain relay URL (e.g. `…/setup#key=…` or `droidhands://configure…`).
    static func isSetupLink(_ text: String) -> Bool {
        guard case .valid = parse(text) else { return false }
        switch RelayURLs.check(text) {
        case .ok, .insecure: return false
        case .empty, .invalid: return true
        }
    }

    private static func schemeless(_ text: String) -> ConfigureLinkResult {
        let authority = text.split(whereSeparator: { "/#?".contains($0) }).first.map(String.init) ?? ""
        let ip = isIPLiteral(authority)
        let hostOnly = authority.split(separator: ":", maxSplits: 1).first.map(String.init) ?? ""
        if !ip && !hostOnly.contains(".") { return .invalid(notASetupLink) }
        return fromWebURL("\(ip ? "http" : "https")://\(text)")
    }

    private static func fromIntentURI(_ text: String) -> ConfigureLinkResult {
        let parts = text.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false)
        let fragment = parts.count > 1 ? String(parts[1]) : ""
        let targetsUs = fragment.split(separator: ";").contains { $0.lowercased() == "scheme=\(ConfigureLinks.scheme)" }
        guard targetsUs else { return .invalid(notASetupLink) }
        let beforeFragment = String(parts[0])
        guard let colon = beforeFragment.firstIndex(of: ":") else { return .invalid(notASetupLink) }
        let rest = String(beforeFragment[beforeFragment.index(after: colon)...])
        return ConfigureLinks.parse("\(ConfigureLinks.scheme):\(rest)")
    }

    private static func fromWebURL(_ text: String) -> ConfigureLinkResult {
        guard let c = URLComponents(string: text),
              let scheme = c.scheme?.lowercased(),
              let host = c.host, !host.isEmpty,
              c.user == nil, c.password == nil else {
            return .invalid(notASetupLink)
        }
        var path = c.percentEncodedPath
        while path.hasSuffix("/") { path.removeLast() }
        let base: String
        if path.isEmpty {
            base = ""
        } else if path.lowercased().hasSuffix(setupPath) {
            base = String(path.dropLast(setupPath.count))
        } else if path.lowercased().hasSuffix("/mcp") {
            return .invalid(agentURLMessage)
        } else {
            return .invalid(notASetupLink)
        }
        let authority = c.port.map { "\(host):\($0)" } ?? host
        let origin = "\(scheme)://\(authority)\(base)"
        let key = ConfigureLinks.rawQueryParameters(c.percentEncodedFragment)["key"]
            ?? ConfigureLinks.rawQueryParameters(c.percentEncodedQuery)["key"]
        var out = URLComponents()
        out.scheme = ConfigureLinks.scheme
        out.host = ConfigureLinks.host
        var items = [URLQueryItem(name: "relay", value: origin)]
        if let key, !key.isEmpty { items.append(URLQueryItem(name: "key", value: key)) }
        out.queryItems = items
        guard let link = out.string else { return .invalid(notASetupLink) }
        return ConfigureLinks.parse(link)
    }

    private static func leadingScheme(_ text: String) -> String? {
        guard let colon = text.firstIndex(of: ":") else { return nil }
        let candidate = String(text[..<colon])
        guard let first = candidate.first, first.isASCII && first.isLetter else { return nil }
        guard candidate.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || "+.-".contains($0)) }) else { return nil }
        // Distinguish `host:8080/path` from `scheme:...`
        let after = text[text.index(after: colon)...]
        if let firstAfter = after.first, firstAfter.isNumber {
            let digits = after.prefix(while: { $0.isNumber })
            let rest = after.dropFirst(digits.count)
            if rest.isEmpty || "/#?".contains(rest.first!) { return nil }
        }
        return candidate.lowercased()
    }

    static func isIPLiteral(_ hostAndPort: String) -> Bool {
        if hostAndPort.hasPrefix("[") { return hostAndPort.contains("]") }
        let host = hostAndPort.split(separator: ":", maxSplits: 1).first.map(String.init) ?? ""
        let octets = host.split(separator: ".", omittingEmptySubsequences: false)
        guard octets.count == 4 else { return false }
        return octets.allSatisfy { part in
            guard (1...3).contains(part.count), let n = Int(part), (0...255).contains(n) else { return false }
            return part.allSatisfy(\.isNumber)
        }
    }
}

/// `droidhands://approve[?code=K7Q2]`, the button on the relay's `/approve` page (docs/PROTOCOL.md §5.6):
/// an agent signing in sends the person that page, and the link opens the matching connection prompt.
/// It only opens the prompt; the person still taps Allow there. Pure (unit-tested).
enum ApproveLinks {
    static let scheme = "droidhands"
    static let host = "approve"

    /// The link's code, uppercased; "" for a link without one (or invalid code); nil if not an approve link.
    static func code(of url: URL) -> String? {
        guard url.scheme?.lowercased() == scheme, url.host?.lowercased() == host else { return nil }
        let raw = URLComponents(url: url, resolvingAgainstBaseURL: false)?
            .queryItems?.first(where: { $0.name == "code" })?.value ?? ""
        let upper = raw.uppercased()
        return isValidCode(upper) ? upper : ""
    }

    static func code(of raw: String?) -> String? {
        guard let raw, let url = URL(string: raw.trimmingCharacters(in: .whitespacesAndNewlines)) else { return nil }
        return code(of: url)
    }

    /// The open request the link is for: the one showing [code], else (no code) the newest.
    static func pick(from pairings: [PairingRequest], code: String, now: Date = Date()) -> PairingRequest? {
        let live = pairings.filter { $0.expiresAt > now }
        if code.isEmpty { return live.last }
        return live.last { $0.displayCode.caseInsensitiveCompare(code) == .orderedSame }
    }

    private static func isValidCode(_ code: String) -> Bool {
        code.count == 4 && code.allSatisfy { $0.isASCII && ($0.isUppercase || $0.isNumber) }
    }
}
