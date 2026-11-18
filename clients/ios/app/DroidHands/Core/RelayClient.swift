import Foundation

/// The device side of the relay tunnel (docs/PROTOCOL.md §3): an outbound WebSocket that carries agents'
/// MCP requests and pairing prompts. Reconnects with backoff, except after `4001`/`4002` (until settings change).
final class RelayClient: NSObject, URLSessionWebSocketDelegate {
    enum State: Equatable {
        case stopped
        case connecting
        case connected(connectURL: String?)
        /// Will retry; [reason] is for the status line.
        case waiting(reason: String, retryAt: Date)
        /// Won't retry until settings change.
        case failed(String)
    }

    struct Config: Equatable {
        var relayURL: String
        var accessKey: String?
        var deviceName: String
        var appVersion: String
    }

    static let protocolVersion = 1
    static let heartbeatFrame = #"{"type":"hb"}"#
    static let maxFrameBytes = 16 * 1024 * 1024 - 1024
    private static let queueKey = DispatchSpecificKey<Void>()

    private let identity: DeviceIdentity
    private let config: Config
    private let server: McpServer
    private let pairings: RelayPairings
    private let queue = DispatchQueue(label: "droidhands.relay")

    private var session: URLSession?
    private var socket: URLSessionWebSocketTask?
    private var generation = 0
    private var attempt = 0
    private var welcomed = false
    private var closeCode: Int?
    private var closeReason: String?
    private var lastErrorMessage: String?
    private var lastErrorCode: String?
    private var timers: [DispatchSourceTimer] = []
    private var running = false
    /// Relay clock minus ours, from `welcome.serverTime`.
    private var clockOffset: TimeInterval = 0

    var onState: ((State) -> Void)?
    private(set) var state: State = .stopped {
        didSet { if state != oldValue { let s = state; onState?(s) } }
    }

    init(identity: DeviceIdentity, config: Config, server: McpServer, pairing: PairingHandler) {
        self.identity = identity
        self.config = config
        self.server = server
        pairings = RelayPairings(handler: pairing)
        super.init()
        queue.setSpecific(key: RelayClient.queueKey, value: ())
        pairings.send = { [weak self] text in
            guard let self else { return false }
            return DispatchQueue.getSpecific(key: RelayClient.queueKey) != nil ? self.send(text) : self.queue.sync { self.send(text) }
        }
        pairings.relayNow = { [weak self] in Date().addingTimeInterval(self?.clockOffset ?? 0) }
    }

    /// `http(s)://host[:port][/prefix]` → `ws(s)://…/v1/device?deviceId=…`.
    static func deviceURL(base: String, deviceId: String) -> URL? {
        guard var c = URLComponents(string: base.trimmingCharacters(in: .whitespacesAndNewlines)),
              let scheme = c.scheme?.lowercased(), let host = c.host, !host.isEmpty else { return nil }
        switch scheme {
        case "http", "ws": c.scheme = "ws"
        case "https", "wss": c.scheme = "wss"
        default: return nil
        }
        var path = c.path
        while path.hasSuffix("/") { path.removeLast() }
        c.path = path + "/v1/device"
        c.queryItems = [URLQueryItem(name: "deviceId", value: deviceId)]
        c.fragment = nil
        return c.url
    }

    /// Exponential backoff with jitter: 1 s, doubling up to 60 s.
    static func backoff(attempt: Int) -> TimeInterval {
        let ceiling = min(60, pow(2, Double(min(attempt, 6))))
        return max(1, ceiling / 2 + Double.random(in: 0...(ceiling / 2)))
    }

    func start() {
        queue.async { [self] in
            guard !running else { return }
            running = true
            attempt = 0
            connect()
        }
    }

    func stop() {
        queue.async { [self] in
            running = false
            teardown(code: .normalClosure)
            pairings.cancelAll()
            state = .stopped
        }
    }

    /// Reconnects now (the app came back to the foreground, or the network changed).
    func reconnectIfWaiting() {
        queue.async { [self] in
            guard running else { return }
            if case .waiting = state {
                generation += 1
                attempt = 0
                connect()
            }
        }
    }

    // MARK: Connection

    private func connect() {
        teardown(code: .goingAway)
        guard let url = RelayClient.deviceURL(base: config.relayURL, deviceId: identity.deviceId) else {
            state = .failed("The relay address isn't a valid http(s) URL. Fix it in Settings › Connection.")
            return
        }
        generation += 1
        let gen = generation
        welcomed = false
        closeCode = nil
        closeReason = nil
        lastErrorMessage = nil
        lastErrorCode = nil
        state = .connecting

        let configuration = URLSessionConfiguration.default
        configuration.waitsForConnectivity = false
        configuration.timeoutIntervalForRequest = 30
        let delegateQueue = OperationQueue()
        delegateQueue.underlyingQueue = queue
        delegateQueue.maxConcurrentOperationCount = 1
        let session = URLSession(configuration: configuration, delegate: self, delegateQueue: delegateQueue)
        let socket = session.webSocketTask(with: url)
        socket.maximumMessageSize = 16 * 1024 * 1024
        self.session = session
        self.socket = socket
        socket.resume()

        var hello = JSONObject([
            ("type", "hello"), ("protocolVersion", .int(RelayClient.protocolVersion)),
            ("deviceId", .string(identity.deviceId)), ("deviceSecret", .string(identity.secretEncoded)),
            ("platform", "ios"), ("appVersion", .string(config.appVersion)), ("deviceName", .string(config.deviceName)),
        ])
        if let key = config.accessKey?.trimmingCharacters(in: .whitespaces), !key.isEmpty { hello["accessKey"] = .string(key) }
        send(JSON.object(hello).encoded)
        receive(gen)

        // The relay closes a socket that hasn't said hello within 10 s; give the welcome 15 s.
        schedule(after: 15, gen: gen) { [self] in
            if !welcomed { failed(gen, "The relay didn't answer.") }
        }
    }

    private func receive(_ gen: Int) {
        socket?.receive { [weak self] result in
            guard let self else { return }
            self.queue.async {
                guard gen == self.generation else { return }
                switch result {
                case .success(let message):
                    switch message {
                    case .string(let text): self.handle(text, gen: gen)
                    case .data(let data): if let text = String(data: data, encoding: .utf8) { self.handle(text, gen: gen) }
                    @unknown default: break
                    }
                    self.receive(gen)
                case .failure(let error):
                    self.failed(gen, error.localizedDescription)
                }
            }
        }
    }

    private func handle(_ text: String, gen: Int) {
        guard case .object(let frame)? = try? JSON.parse(text), let type = frame["type"]?.stringValue else { return }
        switch type {
        case "welcome":
            welcomed = true
            attempt = 0
            if let serverTime = frame["serverTime"]?.doubleValue { clockOffset = serverTime / 1000 - Date().timeIntervalSince1970 }
            state = .connected(connectURL: frame["connectUrl"]?.stringValue)
            startKeepalive(gen)
        case "request":
            guard let id = frame["id"]?.stringValue else { return }
            let request = RelayClient.transportRequest(frame)
            Task { [weak self] in
                guard let self else { return }
                let response = await self.server.handle(request)
                self.queue.async {
                    // A request whose connection dropped still ran and was audited; its answer has nowhere to go.
                    guard gen == self.generation else { return }
                    var frame = RelayClient.responseFrame(id: id, response)
                    if frame.utf8.count > RelayClient.maxFrameBytes {
                        frame = RelayClient.responseFrame(id: id, TransportResponse(status: 200, body: JsonRpc.errorBody(
                            id: (try? JSON.parse(request.body))?["id"] ?? .null, code: JsonRpc.internalError,
                            message: "The answer is larger than the relay accepts (16 MiB). Ask for less, for example with a lower limit."),
                            headers: ["content-type": "application/json"]))
                    }
                    self.send(frame)
                }
            }
        case "ping":
            var pong = JSONObject([("type", "pong")])
            pong["t"] = frame["t"]
            send(JSON.object(pong).encoded)
        case "pair_request": pairings.onRequest(frame)
        case "pair_cancel": pairings.onCancel(frame)
        case "pair_complete": pairings.onFinished(frame, completed: true)
        case "pair_abandoned": pairings.onFinished(frame, completed: false)
        case "error":
            lastErrorMessage = frame["message"]?.stringValue
            lastErrorCode = frame["code"]?.stringValue
        default: break // hb_ack, pong and future frame types.
        }
    }

    static func transportRequest(_ frame: JSONObject) -> TransportRequest {
        var headers: [String: String] = [:]
        for (k, v) in frame["headers"]?.objectValue?.entries ?? [] { if let s = v.stringValue { headers[k.lowercased()] = s } }
        return TransportRequest(authorization: headers["authorization"], body: frame["body"]?.stringValue ?? "", transport: "relay",
                                userAgent: headers["user-agent"], remoteAddress: frame["remoteAddress"]?.stringValue)
    }

    static func responseFrame(id: String, _ r: TransportResponse) -> String {
        var headers = JSONObject()
        for (k, v) in r.headers.sorted(by: { $0.key < $1.key }) { headers[k.lowercased()] = .string(v) }
        return JSON.object(JSONObject([("type", "response"), ("id", .string(id)), ("status", .int(r.status)),
                                       ("headers", .object(headers)), ("body", r.body.map { .string($0) } ?? .null)])).encoded
    }

    private func startKeepalive(_ gen: Int) {
        repeating(every: 30, gen: gen) { [self] in send(RelayClient.heartbeatFrame) }
        repeating(every: 20, gen: gen) { [self] in
            socket?.sendPing { [weak self] error in
                guard let self, let error else { return }
                self.queue.async { self.failed(gen, error.localizedDescription) }
            }
        }
    }

    @discardableResult
    private func send(_ text: String) -> Bool {
        guard let socket else { return false }
        let gen = generation
        socket.send(.string(text)) { [weak self] error in
            guard let self, let error else { return }
            self.queue.async { self.failed(gen, error.localizedDescription) }
        }
        return true
    }

    private func failed(_ gen: Int, _ message: String) {
        guard gen == generation, running else { return }
        var code = closeCode ?? socket.map { $0.closeCode.rawValue }.flatMap { $0 == 0 ? nil : $0 }
        // The error frame before the close says the same, in case the close code itself was lost.
        switch lastErrorCode {
        case "unauthorized", "access_key": code = code ?? 4001
        case "protocol_version": code = code ?? 4002
        default: break
        }
        let relayMessage = lastErrorMessage ?? closeReason
        teardown(code: .goingAway)
        pairings.cancelAll()
        switch code {
        case 4001:
            state = .failed(relayMessage ?? "The relay refused this phone (wrong access key?). Check Settings › Connection.")
            return
        case 4002:
            state = .failed(relayMessage ?? "The relay needs a newer DroidHands. Update the app.")
            return
        default: break
        }
        let delay = RelayClient.backoff(attempt: attempt)
        attempt += 1
        let reason: String
        switch code {
        case 4009: reason = "Another connection of this phone replaced this one."
        case 4013: reason = relayMessage ?? "The relay is full."
        default: reason = welcomed ? "Connection lost." : (relayMessage ?? RelayClient.friendly(message))
        }
        state = .waiting(reason: reason, retryAt: Date().addingTimeInterval(delay))
        generation += 1
        let next = generation
        schedule(after: delay, gen: next) { [self] in connect() }
    }

    private static func friendly(_ message: String) -> String {
        let m = message.lowercased()
        if m.contains("offline") || m.contains("not connected") { return "No internet connection." }
        if m.contains("could not be found") || m.contains("hostname") { return "Can't find the relay." }
        if m.contains("timed out") { return "The relay didn't answer." }
        return "Can't reach the relay."
    }

    private func teardown(code: URLSessionWebSocketTask.CloseCode) {
        timers.forEach { $0.cancel() }
        timers = []
        socket?.cancel(with: code, reason: nil)
        socket = nil
        session?.invalidateAndCancel()
        session = nil
    }

    private func schedule(after seconds: TimeInterval, gen: Int, _ work: @escaping () -> Void) {
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + seconds)
        t.setEventHandler { [weak self] in
            guard let self, gen == self.generation, self.running else { return }
            work()
        }
        t.resume()
        timers.append(t)
    }

    private func repeating(every seconds: TimeInterval, gen: Int, _ work: @escaping () -> Void) {
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + seconds, repeating: seconds)
        t.setEventHandler { [weak self] in
            guard let self, gen == self.generation, self.running else { return }
            work()
        }
        t.resume()
        timers.append(t)
    }

    // MARK: URLSessionWebSocketDelegate (called on [queue])

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        guard webSocketTask === socket else { return }
        self.closeCode = closeCode.rawValue
        closeReason = reason.flatMap { String(data: $0, encoding: .utf8) }.flatMap { $0.isEmpty ? nil : $0 }
        failed(generation, "closed")
    }
}
