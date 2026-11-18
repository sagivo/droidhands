import Foundation

/// A one-tap pairing request forwarded by the relay (docs/PROTOCOL.md §5.3).
struct PairingRequest: Identifiable, Equatable {
    let id: String
    /// The OAuth client's registered name, e.g. "Claude".
    let clientName: String
    /// Host of the OAuth redirect URI, e.g. "claude.ai"; "agent sign-in" for [viaAgent].
    let redirectHost: String
    let displayCode: String
    let remoteAddress: String?
    let userAgent: String?
    /// On the phone's clock.
    let expiresAt: Date
    /// An agent sign-in (`flow: device`): the agent showed the code in its chat.
    let viaAgent: Bool
}

enum PairingDecision {
    case approved(accessToken: String, tokenId: String)
    /// "denied", "timeout" or "busy".
    case denied(String)
}

/// Implemented by the app (approval UI and token issuing).
protocol PairingHandler: AnyObject {
    /// Shows the prompt and waits for the user. Cancelled on `pair_cancel`, at `expiresAt`, or when the
    /// connection drops; the implementation then removes its prompt.
    func onPairRequest(_ request: PairingRequest) async -> PairingDecision
    /// `pair_complete` (completed) or `pair_abandoned`; on abandon, delete [tokenId] because no agent got it.
    func onPairFinished(id: String, tokenId: String?, completed: Bool)
}

/// The pairing frames of one relay client.
final class RelayPairings {
    static let maxPending = 5
    static let alphabet = Set("ABCDEFGHJKLMNPQRSTUVWXYZ23456789")

    private weak var handler: PairingHandler?
    private let lock = NSLock()
    private var pending: [String: Task<Void, Never>] = [:]

    var send: (String) -> Bool = { _ in false }
    var relayNow: () -> Date = Date.init

    init(handler: PairingHandler?) { self.handler = handler }

    func onRequest(_ frame: JSONObject) {
        guard let request = RelayPairings.parse(frame, relayNow: relayNow()) else {
            if let id = frame["id"]?.stringValue, !id.isEmpty, id.count <= 200 { _ = send(RelayPairings.response(id: id, .denied("denied"))) }
            return
        }
        guard let handler else { _ = send(RelayPairings.response(id: request.id, .denied("busy"))); return }
        let remaining = min(request.expiresAt.timeIntervalSince(relayNow()), 600)
        guard remaining > 0 else { return }
        // Relay time → phone time.
        let local = PairingRequest(id: request.id, clientName: request.clientName, redirectHost: request.redirectHost,
                                   displayCode: request.displayCode, remoteAddress: request.remoteAddress, userAgent: request.userAgent,
                                   expiresAt: Date().addingTimeInterval(remaining), viaAgent: request.viaAgent)
        enum Admission { case duplicate, busy, started }
        let admission: Admission = lock.withLock {
            if pending[request.id] != nil { return .duplicate } // The relay re-sent it after a reconnect.
            if pending.count >= RelayPairings.maxPending { return .busy }
            pending[request.id] = Task { [weak self] in await self?.prompt(handler, local, timeout: remaining) }
            return .started
        }
        if admission == .busy { _ = send(RelayPairings.response(id: request.id, .denied("busy"))) }
    }

    func onCancel(_ frame: JSONObject) {
        guard let id = frame["id"]?.stringValue else { return }
        lock.withLock { pending.removeValue(forKey: id) }?.cancel()
    }

    func onFinished(_ frame: JSONObject, completed: Bool) {
        guard let id = frame["id"]?.stringValue else { return }
        let tokenId = frame["tokenId"]?.stringValue.flatMap { $0.isEmpty ? nil : $0 }
        if !completed { lock.withLock { pending.removeValue(forKey: id) }?.cancel() }
        handler?.onPairFinished(id: id, tokenId: tokenId, completed: completed)
    }

    func cancelAll() {
        let tasks = lock.withLock { () -> [Task<Void, Never>] in
            defer { pending.removeAll() }
            return Array(pending.values)
        }
        tasks.forEach { $0.cancel() }
    }

    private func prompt(_ handler: PairingHandler, _ request: PairingRequest, timeout: TimeInterval) async {
        let decision: PairingDecision? = await withTaskGroup(of: PairingDecision?.self) { group in
            group.addTask { await handler.onPairRequest(request) }
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(timeout * 1e9))
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
        let stillWanted = !Task.isCancelled && lock.withLock { pending.removeValue(forKey: request.id) } != nil
        guard let decision else { return } // Expired: the relay reports that itself.
        let delivered = stillWanted && send(RelayPairings.response(id: request.id, decision))
        if !delivered, case .approved(_, let tokenId) = decision {
            handler.onPairFinished(id: request.id, tokenId: tokenId, completed: false)
        }
    }

    static func parse(_ frame: JSONObject, relayNow: Date) -> PairingRequest? {
        guard let id = frame["id"]?.stringValue, !id.isEmpty, id.count <= 200 else { return nil }
        let code = displayText(frame["displayCode"]?.stringValue, max: 16) ?? ""
        guard !code.isEmpty, code.allSatisfy({ alphabet.contains($0) }) else { return nil }
        let expires = frame["expiresAt"]?.doubleValue.map { Date(timeIntervalSince1970: $0 / 1000) } ?? relayNow.addingTimeInterval(180)
        return PairingRequest(id: id,
                              clientName: displayText(frame["clientName"]?.stringValue, max: 60) ?? "Unknown app",
                              redirectHost: displayText(frame["redirectHost"]?.stringValue, max: 255) ?? "unknown",
                              displayCode: code,
                              remoteAddress: displayText(frame["remoteAddress"]?.stringValue, max: 300),
                              userAgent: displayText(frame["userAgent"]?.stringValue, max: 300),
                              expiresAt: expires,
                              viaAgent: frame["flow"]?.stringValue == "device")
    }

    static func response(id: String, _ decision: PairingDecision) -> String {
        switch decision {
        case .approved(let token, let tokenId):
            return JSON.object(JSONObject([("type", "pair_response"), ("id", .string(id)), ("approved", true),
                                           ("accessToken", .string(token)), ("tokenId", .string(tokenId))])).encoded
        case .denied(let reason):
            let r = ["denied", "timeout", "busy"].contains(reason) ? reason : "denied"
            return JSON.object(JSONObject([("type", "pair_response"), ("id", .string(id)), ("approved", false), ("reason", .string(r))])).encoded
        }
    }

    /// Strips control and format characters (bidi overrides and zero-width characters could make a prompt
    /// lie about a name), collapses whitespace and caps the length. Nil if nothing is left.
    static func displayText(_ raw: String?, max: Int) -> String? {
        guard let raw else { return nil }
        var out = String.UnicodeScalarView()
        for scalar in raw.unicodeScalars {
            switch scalar.properties.generalCategory {
            case .control, .format, .lineSeparator, .paragraphSeparator, .unassigned, .surrogate, .privateUse:
                if scalar == "\n" || scalar == "\t" || scalar == "\r" || scalar.properties.generalCategory == .lineSeparator
                    || scalar.properties.generalCategory == .paragraphSeparator { out.append(" ") }
            default: out.append(scalar)
            }
        }
        let collapsed = String(out).split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
        guard !collapsed.isEmpty else { return nil }
        return String(collapsed.prefix(max))
    }
}
