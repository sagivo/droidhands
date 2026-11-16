import XCTest

/// The PhoneBridge runner: one UI test that serves the runner protocol (docs/IOS.md) over HTTP until the process is
/// killed. Start it with scripts/ios-runner.sh, which passes `PB_RUNNER_SECRET` and `PB_RUNNER_PORT` through
/// `TEST_RUNNER_*` environment variables.
final class PhoneBridgeRunnerTests: XCTestCase {
    static let defaultPort: UInt16 = 8101

    /// Set once the server is up: from then on XCTest issues belong to requests and are answered as errors.
    private var serving = false

    override func setUp() {
        super.setUp()
        // A failed tap must not stop the test (which would stop the server); the request reports it instead.
        continueAfterFailure = true
    }

    override func record(_ issue: XCTIssue) {
        guard serving else {
            super.record(issue)
            return
        }
        NSLog("[PhoneBridgeRunner] XCTest issue: %@", issue.compactDescription)
        if Thread.isMainThread { IssueSink.record(issue.compactDescription) }
    }

    func testRunBridge() throws {
        let environment = ProcessInfo.processInfo.environment
        guard let secret = environment["PB_RUNNER_SECRET"], !secret.isEmpty else {
            let reason = "PB_RUNNER_SECRET is not set, so the runner refuses to start: without it anyone who can "
                + "reach this phone could control it. Start the runner with scripts/ios-runner.sh, or pass "
                + "TEST_RUNNER_PB_RUNNER_SECRET to xcodebuild."
            NSLog("[PhoneBridgeRunner] %@", reason)
            XCTFail(reason)
            return
        }
        var port = Self.defaultPort
        if let raw = environment["PB_RUNNER_PORT"], !raw.isEmpty {
            guard let parsed = UInt16(raw), parsed > 0 else {
                let reason = "PB_RUNNER_PORT=\(raw) is not a TCP port (1-65535)."
                NSLog("[PhoneBridgeRunner] %@", reason)
                XCTFail(reason)
                return
            }
            port = parsed
        }

        let bridge = Bridge()
        let server = try HTTPServer(port: port, secret: secret) { request, reply in
            MainThreadJobs.shared.enqueue { reply(bridge.handle(request)) }
        }
        var ready = false
        var failure: String?
        server.start { result in
            DispatchQueue.main.async {
                switch result {
                case .success: ready = true
                case .failure(let error): failure = "The runner could not listen on port \(port): \(error)"
                }
            }
        }
        while !ready && failure == nil {
            _ = RunLoop.current.run(mode: .default, before: Date(timeIntervalSinceNow: 0.5))
        }
        if let failure {
            NSLog("[PhoneBridgeRunner] %@", failure)
            XCTFail(failure)
            return
        }

        serving = true
        NSLog("[PhoneBridgeRunner] PhoneBridge runner %@ listening on port %d", Bridge.runnerVersion, Int(port))
        print("PHONEBRIDGE_RUNNER_READY port=\(port)")
        while failure == nil {
            MainThreadJobs.shared.drain()
            _ = RunLoop.current.run(mode: .default, before: Date(timeIntervalSinceNow: 1))
        }
        serving = false
        server.stop()
        NSLog("[PhoneBridgeRunner] %@", failure ?? "")
        XCTFail(failure ?? "The runner stopped.")
    }
}
