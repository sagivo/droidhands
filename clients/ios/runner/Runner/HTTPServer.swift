import CryptoKit
import Foundation
import Network

/// One parsed HTTP/1.1 request.
struct HTTPRequest {
    let method: String
    /// Path without the query string.
    let path: String
    /// Header names are lowercased.
    let headers: [String: String]
    let body: Data
}

struct HTTPResponse {
    let status: Int
    let body: Data
    var contentType = "application/json"

    static func json(_ status: Int, _ object: Any) -> HTTPResponse {
        let data = (try? JSONSerialization.data(withJSONObject: object, options: []))
            ?? Data(#"{"error":"The runner could not encode its answer."}"#.utf8)
        return HTTPResponse(status: status, body: data)
    }

    static func error(_ status: Int, _ message: String) -> HTTPResponse {
        json(status, ["error": message])
    }
}

/// A small HTTP/1.1 server on Network.framework: Content-Length bodies (no chunked uploads), keep-alive, one
/// request at a time per connection. Every request must carry `Authorization: Bearer <secret>`; requests that don't
/// get 401 without reaching `handler`. `handler` may answer from any thread.
final class HTTPServer {
    static let maxHeaderBytes = 32 * 1024
    static let maxBodyBytes = 2 * 1024 * 1024

    typealias Handler = (HTTPRequest, @escaping (HTTPResponse) -> Void) -> Void

    private let listener: NWListener
    private let queue = DispatchQueue(label: "dev.phonebridge.runner.http")
    private let secretDigest: SHA256.Digest
    private let handler: Handler
    private var connections: [ObjectIdentifier: HTTPConnection] = [:]

    init(port: UInt16, secret: String, handler: @escaping Handler) throws {
        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        guard let nwPort = NWEndpoint.Port(rawValue: port) else {
            throw RunnerError(500, "Port \(port) is not a valid TCP port.")
        }
        listener = try NWListener(using: parameters, on: nwPort)
        secretDigest = SHA256.hash(data: Data(secret.utf8))
        self.handler = handler
    }

    /// Starts listening; `onState` reports `.ready` or the failure (on a background queue).
    func start(onState: @escaping (Result<Void, Error>) -> Void) {
        listener.stateUpdateHandler = { state in
            switch state {
            case .ready: onState(.success(()))
            case .failed(let error): onState(.failure(error))
            case .waiting(let error): NSLog("[PhoneBridgeRunner] listener waiting: %@", "\(error)")
            default: break
            }
        }
        listener.newConnectionHandler = { [weak self] connection in
            self?.accept(connection)
        }
        listener.start(queue: queue)
    }

    func stop() {
        listener.cancel()
    }

    /// Constant-time check of the `Authorization` header: both sides are hashed first so neither the content nor
    /// the length of the secret leaks through timing.
    func isAuthorized(_ headers: [String: String]) -> Bool {
        guard let value = headers["authorization"] else { return false }
        let prefix = "Bearer "
        guard value.count > prefix.count, value.lowercased().hasPrefix(prefix.lowercased()) else { return false }
        let presented = SHA256.hash(data: Data(value.dropFirst(prefix.count).utf8))
        var difference: UInt8 = 0
        for (a, b) in zip(presented, secretDigest) { difference |= a ^ b }
        return difference == 0
    }

    private func accept(_ nw: NWConnection) {
        let connection = HTTPConnection(nw, server: self, queue: queue)
        let id = ObjectIdentifier(connection)
        connections[id] = connection
        connection.onClose = { [weak self] in self?.connections[id] = nil }
        connection.start()
    }

    fileprivate func dispatch(_ request: HTTPRequest, reply: @escaping (HTTPResponse) -> Void) {
        guard isAuthorized(request.headers) else {
            reply(.error(401, "Missing or wrong runner secret."))
            return
        }
        handler(request, reply)
    }
}

private final class HTTPConnection {
    private let nw: NWConnection
    private weak var server: HTTPServer?
    private let queue: DispatchQueue
    private var buffer = Data()
    private var busy = false
    private var closed = false
    var onClose: (() -> Void)?

    init(_ nw: NWConnection, server: HTTPServer, queue: DispatchQueue) {
        self.nw = nw
        self.server = server
        self.queue = queue
    }

    func start() {
        nw.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed, .cancelled: self?.close()
            default: break
            }
        }
        nw.start(queue: queue)
        receive()
    }

    private func receive() {
        nw.receive(minimumIncompleteLength: 1, maximumLength: 256 * 1024) { [weak self] data, _, isComplete, error in
            guard let self, !self.closed else { return }
            if let data, !data.isEmpty { self.buffer.append(data) }
            self.processBuffer()
            if error != nil || isComplete {
                if !self.busy { self.close() }
                return
            }
            if !self.busy { self.receive() }
        }
    }

    /// Parses one request from the buffer if it is complete and hands it to the server.
    private func processBuffer() {
        guard !busy, !closed else { return }
        let separator = Data("\r\n\r\n".utf8)
        guard let headerEnd = buffer.range(of: separator) else {
            if buffer.count > HTTPServer.maxHeaderBytes { fail(431, "The request headers are too large.") }
            return
        }
        guard let head = String(data: buffer[buffer.startIndex..<headerEnd.lowerBound], encoding: .utf8) else {
            fail(400, "The request headers are not UTF-8.")
            return
        }
        var lines = head.components(separatedBy: "\r\n")
        let requestLine = lines.removeFirst().split(separator: " ", omittingEmptySubsequences: true)
        guard requestLine.count == 3, requestLine[2].hasPrefix("HTTP/1.") else {
            fail(400, "Not an HTTP/1.1 request.")
            return
        }
        var headers: [String: String] = [:]
        for line in lines {
            guard let colon = line.firstIndex(of: ":") else { continue }
            let name = line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()
            let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            headers[name] = value
        }
        if let encoding = headers["transfer-encoding"], encoding.lowercased() != "identity" {
            fail(411, "Send the body with a Content-Length header; chunked bodies are not supported.")
            return
        }
        let length: Int
        if let raw = headers["content-length"] {
            guard let parsed = Int(raw), parsed >= 0 else {
                fail(400, "Content-Length is not a number.")
                return
            }
            length = parsed
        } else {
            length = 0
        }
        guard length <= HTTPServer.maxBodyBytes else {
            fail(413, "The request body is larger than \(HTTPServer.maxBodyBytes / 1024 / 1024) MB.")
            return
        }
        let bodyStart = headerEnd.upperBound
        guard buffer.count - (bodyStart - buffer.startIndex) >= length else { return }  // wait for more
        let body = Data(buffer[bodyStart..<(bodyStart + length)])
        buffer = Data(buffer[(bodyStart + length)...])

        let target = String(requestLine[1])
        let path = target.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? target
        let version = String(requestLine[2])
        let connectionHeader = headers["connection"]?.lowercased() ?? ""
        let keepAlive = version == "HTTP/1.1" ? !connectionHeader.contains("close") : connectionHeader.contains("keep-alive")
        let request = HTTPRequest(method: String(requestLine[0]).uppercased(), path: path, headers: headers, body: body)

        busy = true
        guard let server else {
            close()
            return
        }
        server.dispatch(request) { [weak self] response in
            guard let self else { return }
            self.queue.async { self.send(response, keepAlive: keepAlive) }
        }
    }

    private func send(_ response: HTTPResponse, keepAlive: Bool) {
        guard !closed else { return }
        var head = "HTTP/1.1 \(response.status) \(Self.reason(response.status))\r\n"
        head += "Content-Type: \(response.contentType)\r\n"
        head += "Content-Length: \(response.body.count)\r\n"
        head += "Cache-Control: no-store\r\n"
        head += "Connection: \(keepAlive ? "keep-alive" : "close")\r\n\r\n"
        var data = Data(head.utf8)
        data.append(response.body)
        nw.send(content: data, completion: .contentProcessed { [weak self] error in
            guard let self else { return }
            self.busy = false
            if error != nil || !keepAlive {
                self.close()
                return
            }
            self.processBuffer()
            if !self.busy { self.receive() }
        })
    }

    private func fail(_ status: Int, _ message: String) {
        busy = true
        send(.error(status, message), keepAlive: false)
    }

    private func close() {
        guard !closed else { return }
        closed = true
        nw.cancel()
        onClose?()
        onClose = nil
    }

    private static func reason(_ status: Int) -> String {
        switch status {
        case 200: return "OK"
        case 400: return "Bad Request"
        case 401: return "Unauthorized"
        case 404: return "Not Found"
        case 405: return "Method Not Allowed"
        case 409: return "Conflict"
        case 411: return "Length Required"
        case 413: return "Payload Too Large"
        case 431: return "Request Header Fields Too Large"
        case 500: return "Internal Server Error"
        case 501: return "Not Implemented"
        case 503: return "Service Unavailable"
        default: return "Status"
        }
    }
}

/// An error that becomes `{"error": message}` with `status`.
struct RunnerError: Error {
    let status: Int
    let message: String

    init(_ status: Int, _ message: String) {
        self.status = status
        self.message = message
    }
}
