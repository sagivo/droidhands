import Foundation

/// Runs XCUITest work on the main thread, one job at a time, only from the top of the test's run loop.
///
/// XCUITest spins the main run loop while it waits (for idle, for a snapshot, for an event to be synthesized), so a
/// block sent with `DispatchQueue.main.async` could start in the middle of another request's tap or snapshot. Jobs
/// therefore wait in this queue; `DispatchQueue.main.async {}` only wakes the run loop, and `drain()` runs the jobs
/// from `RunnerTests.testRunBridge`'s loop, never from inside an XCUITest call.
final class MainThreadJobs {
    static let shared = MainThreadJobs()

    private let lock = NSLock()
    private var pending: [() -> Void] = []

    func enqueue(_ job: @escaping () -> Void) {
        lock.lock()
        pending.append(job)
        lock.unlock()
        DispatchQueue.main.async {}  // wake the run loop
    }

    /// Runs every queued job (including ones queued meanwhile). Main thread only.
    func drain() {
        precondition(Thread.isMainThread)
        while true {
            lock.lock()
            let job = pending.isEmpty ? nil : pending.removeFirst()
            lock.unlock()
            guard let job else { return }
            autoreleasepool { job() }
        }
    }
}

/// Collects the XCTest issues (failed interactions) recorded while a request runs, so the request can answer with an
/// error instead of failing the long-running test. Main thread only.
enum IssueSink {
    private static var issues: [String] = []

    static func record(_ description: String) {
        issues.append(description)
    }

    /// Returns and forgets what was recorded.
    static func take() -> [String] {
        defer { issues.removeAll() }
        return issues
    }
}
