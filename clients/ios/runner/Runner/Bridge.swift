import UIKit
import XCTest

/// The runner protocol (docs/IOS.md, "Runner protocol"). Every method runs on the main thread, one request at a
/// time (see `MainThreadJobs`). The runner does no policy: the host checks grants, app access, pause and approvals
/// before it calls.
final class Bridge {
    static let runnerVersion = "0.1.0"
    static let springboard = "com.apple.springboard"
    static let defaultMaxDepth = 60
    static let maxAlertOverlays = 4

    /// The last app other than SpringBoard seen in front (or launched). Used when the private active-application
    /// route is missing and the request names no candidates.
    private var lastForeground: String?

    private typealias Route = (Body) throws -> [String: Any]

    private lazy var routes: [String: (method: String, run: Route)] = [
        "/status": ("GET", { [unowned self] _ in self.status() }),
        "/foreground": ("POST", { [unowned self] in try self.foreground($0) }),
        "/snapshot": ("POST", { [unowned self] in try self.snapshot($0) }),
        "/screenshot": ("POST", { [unowned self] in try self.screenshot($0) }),
        "/tap": ("POST", { [unowned self] in try self.tap($0) }),
        "/swipe": ("POST", { [unowned self] in try self.swipe($0) }),
        "/type": ("POST", { [unowned self] in try self.type($0) }),
        "/clear": ("POST", { [unowned self] _ in try self.clear() }),
        "/key": ("POST", { [unowned self] in try self.key($0) }),
        "/button": ("POST", { [unowned self] in try self.button($0) }),
        "/lock": ("POST", { _ in try Bridge.lock() }),
        "/launch": ("POST", { [unowned self] in try self.launch($0) }),
        "/open_url": ("POST", { [unowned self] in try self.openURL($0) }),
        "/app_state": ("POST", { [unowned self] in try self.appState($0) }),
    ]

    /// Answers one authorized request. Main thread.
    func handle(_ request: HTTPRequest) -> HTTPResponse {
        precondition(Thread.isMainThread)
        let started = Date()
        _ = IssueSink.take()
        var response = HTTPResponse.error(500, "The runner did not produce an answer.")
        let exception = PBCatchException {
            do {
                guard let route = self.routes[request.path] else {
                    throw RunnerError(404, "The runner has no \(request.path) endpoint.")
                }
                guard route.method == request.method else {
                    throw RunnerError(405, "\(request.path) takes \(route.method), not \(request.method).")
                }
                response = .json(200, try route.run(try Body(request.body)))
            } catch let error as RunnerError {
                response = .error(error.status, error.message)
            } catch {
                response = .error(500, "The runner failed: \(error.localizedDescription)")
            }
        }
        if let exception {
            response = .error(500, "The phone's test runner raised \(exception.name.rawValue): \(exception.reason ?? "no reason given").")
        }
        let leftover = IssueSink.take()
        let elapsed = Int(Date().timeIntervalSince(started) * 1000)
        NSLog("[PhoneBridgeRunner] %@ %@ -> %d (%d ms)%@", request.method, request.path, response.status, elapsed,
              leftover.isEmpty ? "" : " issues: \(leftover.joined(separator: " | "))")
        return response
    }

    // MARK: - Endpoints

    private func status() -> [String: Any] {
        var screen = Bridge.screenSize()
        screen["scale"] = Double(UIScreen.main.scale)
        var result: [String: Any] = [
            "runner_version": Bridge.runnerVersion,
            "os": "ios",
            "os_version": UIDevice.current.systemVersion,
            "model": Bridge.modelIdentifier(),
            "name": UIDevice.current.name,
            "screen": screen,
        ]
        if let locked = PrivateXCTest.isScreenLocked() { result["locked"] = locked }
        return result
    }

    private func foreground(_ body: Body) throws -> [String: Any] {
        ["bundle_id": foregroundBundleID(candidates: try body.optionalStrings("candidates") ?? [])]
    }

    private func snapshot(_ body: Body) throws -> [String: Any] {
        let maxDepth = min(max(try body.optionalInt("max_depth") ?? Bridge.defaultMaxDepth, 0), 500)
        let bundleID = try body.optionalString("bundle_id") ?? foregroundBundleID(candidates: [])
        let app = XCUIApplication(bundleIdentifier: bundleID)
        var root: Any = NSNull()
        var keyboard = false
        let state = app.state
        if state != .notRunning && state != .unknown {
            let tree: XCUIElementSnapshot
            do {
                tree = try app.snapshot()
            } catch {
                throw RunnerError(503, "The screen of \(bundleID) couldn't be read right now (the app may be busy). Try again in a moment.")
            }
            root = Elements.json(tree, maxDepth: maxDepth)
            keyboard = Elements.first(in: tree, where: { $0.elementType == .keyboard }) != nil
        }
        let overlays = bundleID == Bridge.springboard ? [] : springboardAlerts(maxDepth: maxDepth)
        return [
            "bundle_id": bundleID,
            "screen": Bridge.screenSize(),
            "keyboard": keyboard,
            "root": root,
            "overlays": overlays,
        ]
    }

    private func screenshot(_ body: Body) throws -> [String: Any] {
        let format = (try body.optionalString("format") ?? "png").lowercased()
        guard ["png", "jpeg", "jpg"].contains(format) else {
            throw RunnerError(400, "format must be \"png\" or \"jpeg\".")
        }
        let quality = min(max(try body.optionalDouble("quality") ?? 0.8, 0.05), 1)
        let shot = XCUIScreen.main.screenshot()
        let image = shot.image
        let data: Data?
        let mime: String
        if format == "png" {
            data = shot.pngRepresentation
            mime = "image/png"
        } else {
            data = image.jpegData(compressionQuality: CGFloat(quality))
            mime = "image/jpeg"
        }
        guard let data, !data.isEmpty else { throw RunnerError(500, "The phone returned an empty screenshot.") }
        let width = image.cgImage?.width ?? Int(image.size.width * image.scale)
        let height = image.cgImage?.height ?? Int(image.size.height * image.scale)
        return ["mime": mime, "width": width, "height": height, "data": data.base64EncodedString()]
    }

    private func tap(_ body: Body) throws -> [String: Any] {
        let x = try body.double("x"), y = try body.double("y")
        let point = Bridge.screenCoordinate(x: x, y: y)
        let durationMs = max(try body.optionalDouble("duration_ms") ?? 0, 0)
        // System alerts (permission prompts) ignore taps synthesized at a screen point; XCTest delivers them when it
        // taps the alert's button element, so a tap that lands on one goes to the element.
        let alertButton = durationMs > 0 ? nil : Bridge.alertButton(at: CGPoint(x: x, y: y))
        try interact(durationMs > 0 ? "The long press" : "The tap") {
            if let alertButton {
                alertButton.tap()
            } else if durationMs > 0 {
                point.press(forDuration: durationMs / 1000)
            } else {
                point.tap()
            }
        }
        return [:]
    }

    private func swipe(_ body: Body) throws -> [String: Any] {
        let fromX = try body.double("from_x"), fromY = try body.double("from_y")
        let toX = try body.double("to_x"), toY = try body.double("to_y")
        let seconds = max(try body.optionalDouble("duration_ms") ?? 300, 10) / 1000
        let from = Bridge.screenCoordinate(x: fromX, y: fromY)
        let to = Bridge.screenCoordinate(x: toX, y: toY)
        let velocity = XCUIGestureVelocity(CGFloat(max(hypot(toX - fromX, toY - fromY) / seconds, 1)))
        try interact("The swipe") {
            from.press(forDuration: 0.05, thenDragTo: to, withVelocity: velocity, thenHoldForDuration: 0)
        }
        return [:]
    }

    private func type(_ body: Body) throws -> [String: Any] {
        guard let text = body.fields["text"] as? String else { throw RunnerError(400, "text is required.") }
        guard !text.isEmpty else { return [:] }
        let target = try focusedInput()
        try typeKeys(text, into: target, what: "Typing")
        return [:]
    }

    private func clear() throws -> [String: Any] {
        let target = try focusedInput()
        guard let field = target.element else {
            throw RunnerError(409, "A keyboard is up, but the runner can't tell which field has focus, so it can't clear it. Tap the field first.")
        }
        var remaining = Bridge.textLength(field)
        if remaining > 0 {
            try typeKeys(Bridge.deletes(remaining), into: target, what: "Clearing the field")
            remaining = currentTextLength(target) ?? 0
        }
        if remaining > 0 {
            // The cursor wasn't at the end. Tapping the right end of the field moves it there (or hits the field's
            // clear button, which also does the job), then delete what is left.
            let frame = field.frame
            let point = Bridge.screenCoordinate(x: Double(frame.maxX - min(4, frame.width / 2)), y: Double(frame.midY))
            try interact("Tapping the end of the field") { point.tap() }
            remaining = currentTextLength(target) ?? 0
            if remaining > 0 {
                try typeKeys(Bridge.deletes(remaining), into: target, what: "Clearing the field")
                remaining = currentTextLength(target) ?? 0
            }
        }
        guard remaining == 0 else {
            throw RunnerError(500, "The field still holds \(remaining) characters after clearing.")
        }
        return [:]
    }

    private func key(_ body: Body) throws -> [String: Any] {
        let text: String
        switch try body.string("key") {
        case "return": text = "\n"
        case "delete": text = XCUIKeyboardKey.delete.rawValue
        default: throw RunnerError(400, "key must be \"return\" or \"delete\".")
        }
        try typeKeys(text, into: try focusedInput(), what: "The key press")
        return [:]
    }

    private func button(_ body: Body) throws -> [String: Any] {
        let name = try body.string("name")
        switch name {
        case "home":
            try interact("Pressing Home") { XCUIDevice.shared.press(.home) }
        case "volume_up", "volume_down":
            #if targetEnvironment(simulator)
            throw RunnerError(501, "The simulator has no volume buttons.")
            #else
            let button: XCUIDevice.Button = name == "volume_up" ? .volumeUp : .volumeDown
            try interact("Pressing the volume button") { XCUIDevice.shared.press(button) }
            #endif
        default:
            throw RunnerError(400, "name must be \"home\", \"volume_up\" or \"volume_down\".")
        }
        return [:]
    }

    private static func lock() throws -> [String: Any] {
        _ = IssueSink.take()
        guard PrivateXCTest.pressLockButton() else {
            throw RunnerError(501, "This version of iOS testing tools doesn't let the runner lock the phone.")
        }
        if let issue = IssueSink.take().first { throw RunnerError(500, "Locking the phone failed: \(issue)") }
        return [:]
    }

    private func launch(_ body: Body) throws -> [String: Any] {
        let bundleID = try body.string("bundle_id")
        let app = XCUIApplication(bundleIdentifier: bundleID)
        try interact("Opening \(bundleID)") { app.activate() }
        if bundleID != Bridge.springboard { lastForeground = bundleID }
        return ["state": Bridge.stateName(app.state)]
    }

    private func openURL(_ body: Body) throws -> [String: Any] {
        let raw = try body.string("url")
        guard let url = URL(string: raw), let scheme = url.scheme, !scheme.isEmpty else {
            throw RunnerError(400, "url is not a valid URL.")
        }
        try interact("Opening the link") { XCUIDevice.shared.system.open(url) }
        return [:]
    }

    private func appState(_ body: Body) throws -> [String: Any] {
        guard let ids = try body.optionalStrings("bundle_ids") else { throw RunnerError(400, "bundle_ids is required.") }
        var states: [String: String] = [:]
        for id in ids { states[id] = Bridge.stateName(XCUIApplication(bundleIdentifier: id).state) }
        return ["states": states]
    }

    // MARK: - Foreground app

    /// The app in front. First XCTest's own list of active applications (private, checked at run time), named by
    /// process id or, failing that, matched against `candidates`, the last app seen in front and SpringBoard. When
    /// that route is missing or names only SpringBoard, the first of `candidates` (then the last app seen in front)
    /// whose state is `runningForeground`; otherwise SpringBoard (the Home Screen).
    func foregroundBundleID(candidates: [String]) -> String {
        var known = candidates
        if let last = lastForeground, !known.contains(last) { known.append(last) }
        let fromPrivateRoute = activeBundleIDs(known: known).map { active in
            candidates.first(where: { active.contains($0) })
                ?? active.first(where: { $0 != Bridge.springboard })
                ?? Bridge.springboard
        }
        var result = fromPrivateRoute ?? Bridge.springboard
        if result == Bridge.springboard {
            // No private route, or it named only SpringBoard (an app it couldn't name may be in front): ask the
            // known apps for their state.
            result = known.first(where: {
                $0 != Bridge.springboard && XCUIApplication(bundleIdentifier: $0).state == .runningForeground
            }) ?? Bridge.springboard
        }
        if result != Bridge.springboard { lastForeground = result }
        return result
    }

    /// Bundle ids of the active applications from the private route, or nil when it isn't available.
    private func activeBundleIDs(known: [String]) -> [String]? {
        guard let pids = PrivateXCTest.activeApplicationPIDs(), !pids.isEmpty else { return nil }
        var ids: [String] = []
        var unnamed: [Int32] = []
        for pid in pids {
            if let id = PrivateXCTest.bundleID(forPID: pid) {
                if !ids.contains(id) { ids.append(id) }
            } else {
                unnamed.append(pid)
            }
        }
        if !unnamed.isEmpty {
            for id in known + [Bridge.springboard] where !ids.contains(id) {
                if let pid = PrivateXCTest.processID(of: XCUIApplication(bundleIdentifier: id)), unnamed.contains(pid) {
                    ids.append(id)
                }
            }
        }
        return ids.isEmpty ? nil : ids
    }

    /// SpringBoard alerts (permission prompts and other system dialogs) drawn over the app in front.
    /// The button of a SpringBoard alert under [point] (screen points), or nil.
    private static func alertButton(at point: CGPoint) -> XCUIElement? {
        var found: XCUIElement?
        _ = PBCatchException {
            let alerts = XCUIApplication(bundleIdentifier: springboard).alerts
            for a in 0..<min(alerts.count, maxAlertOverlays) {
                let buttons = alerts.element(boundBy: a).buttons
                for b in 0..<buttons.count where buttons.element(boundBy: b).frame.contains(point) {
                    found = buttons.element(boundBy: b)
                    return
                }
            }
        }
        return found
    }

    private func springboardAlerts(maxDepth: Int) -> [[String: Any]] {
        let alerts = XCUIApplication(bundleIdentifier: Bridge.springboard).alerts
        let count = alerts.count
        guard count > 0 else { return [] }
        return (0..<min(count, Bridge.maxAlertOverlays)).compactMap { index in
            guard let tree = try? alerts.element(boundBy: index).snapshot() else { return nil }
            return ["owner": Bridge.springboard, "root": Elements.json(tree, maxDepth: maxDepth)]
        }
    }

    // MARK: - Keyboard focus

    private struct FocusTarget {
        let app: XCUIApplication
        /// The focused element, when XCTest reports keyboard focus; nil when only a keyboard is known to be up.
        let element: XCUIElementSnapshot?
    }

    /// The element with keyboard focus in the app in front, or in a SpringBoard dialog over it. 409 when there is
    /// none.
    private func focusedInput() throws -> FocusTarget {
        let front = foregroundBundleID(candidates: [])
        var ids = [front]
        if front != Bridge.springboard { ids.append(Bridge.springboard) }
        var withKeyboard: FocusTarget?
        for id in ids {
            let app = XCUIApplication(bundleIdentifier: id)
            guard let tree = try? app.snapshot() else { continue }
            if let focused = Bridge.focusedElement(in: tree) { return FocusTarget(app: app, element: focused) }
            if withKeyboard == nil, Elements.first(in: tree, where: { $0.elementType == .keyboard }) != nil {
                withKeyboard = FocusTarget(app: app, element: nil)
            }
        }
        if let withKeyboard { return withKeyboard }
        throw RunnerError(409, "No text field has keyboard focus. Tap the field first, then type.")
    }

    private static func focusedElement(in tree: XCUIElementSnapshot) -> XCUIElementSnapshot? {
        Elements.first(in: tree, where: { $0.elementType != .application && Elements.isFocused($0) })
    }

    private func currentTextLength(_ target: FocusTarget) -> Int? {
        guard let tree = try? target.app.snapshot(), let field = Bridge.focusedElement(in: tree) else { return nil }
        return Bridge.textLength(field)
    }

    private static func textLength(_ field: XCUIElementSnapshot) -> Int {
        guard let value = Elements.valueString(field.value), !value.isEmpty else { return 0 }
        if let placeholder = field.placeholderValue, value == placeholder { return 0 }
        return value.count
    }

    private static func deletes(_ count: Int) -> String {
        String(repeating: XCUIKeyboardKey.delete.rawValue, count: count)
    }

    private func typeKeys(_ text: String, into target: FocusTarget, what: String) throws {
        _ = IssueSink.take()
        target.app.typeText(text)
        if let issue = IssueSink.take().first {
            if issue.lowercased().contains("focus") {
                throw RunnerError(409, "No text field has keyboard focus. Tap the field first, then type.")
            }
            throw RunnerError(500, "\(what) failed on the phone: \(issue)")
        }
    }

    // MARK: - Helpers

    /// Runs an XCUITest interaction; an issue XCTest records during it becomes a 500 with XCTest's words.
    private func interact(_ what: String, _ action: () -> Void) throws {
        _ = IssueSink.take()
        action()
        if let issue = IssueSink.take().first { throw RunnerError(500, "\(what) failed on the phone: \(issue)") }
    }

    /// A point on the screen, in points from the top-left corner. Coordinates go through SpringBoard, whose frame is
    /// the whole screen and which is always running (and rarely busy, so XCTest's wait for idle is short).
    private static func screenCoordinate(x: Double, y: Double) -> XCUICoordinate {
        XCUIApplication(bundleIdentifier: springboard)
            .coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0))
            .withOffset(CGVector(dx: x, dy: y))
    }

    /// The screen in points, from SpringBoard's frame: this process's own `UIScreen.main` is wrong when iOS runs the
    /// runner app in a compatibility size (320×480 without a launch screen), so it is only the fallback.
    private static func screenSize() -> [String: Double] {
        var frame = CGRect.zero
        _ = PBCatchException { frame = XCUIApplication(bundleIdentifier: springboard).frame }
        let bounds = frame.width > 0 && frame.height > 0 ? frame : UIScreen.main.bounds
        return ["width": Double(bounds.width), "height": Double(bounds.height)]
    }

    private static func stateName(_ state: XCUIApplication.State) -> String {
        switch state {
        case .notRunning: return "not_running"
        case .runningBackground, .runningBackgroundSuspended: return "background"
        case .runningForeground: return "foreground"
        case .unknown: return "unknown"
        @unknown default: return "unknown"
        }
    }

    /// `iPhone16,1`-style identifier (the simulated model on a simulator).
    private static func modelIdentifier() -> String {
        if let simulated = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"], !simulated.isEmpty {
            return simulated
        }
        var info = utsname()
        uname(&info)
        let machine = withUnsafeBytes(of: &info.machine) { raw in
            String(decoding: raw.prefix(while: { $0 != 0 }), as: UTF8.self)
        }
        return machine.isEmpty ? UIDevice.current.model : machine
    }
}

/// The JSON body of a request (an empty body is `{}`).
struct Body {
    let fields: [String: Any]

    init(_ data: Data) throws {
        if data.isEmpty || data.allSatisfy({ $0 == 0x20 || $0 == 0x0A || $0 == 0x0D || $0 == 0x09 }) {
            fields = [:]
            return
        }
        guard let object = try? JSONSerialization.jsonObject(with: data), let dictionary = object as? [String: Any] else {
            throw RunnerError(400, "The request body must be a JSON object.")
        }
        fields = dictionary
    }

    private func present(_ key: String) -> Any? {
        guard let value = fields[key], !(value is NSNull) else { return nil }
        return value
    }

    func string(_ key: String) throws -> String {
        guard let value = try optionalString(key), !value.isEmpty else { throw RunnerError(400, "\(key) is required.") }
        return value
    }

    func optionalString(_ key: String) throws -> String? {
        guard let value = present(key) else { return nil }
        guard let string = value as? String else { throw RunnerError(400, "\(key) must be a string.") }
        return string.isEmpty ? nil : string
    }

    func double(_ key: String) throws -> Double {
        guard let value = try optionalDouble(key) else { throw RunnerError(400, "\(key) is required.") }
        return value
    }

    func optionalDouble(_ key: String) throws -> Double? {
        guard let value = present(key) else { return nil }
        guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(), number.doubleValue.isFinite else {
            throw RunnerError(400, "\(key) must be a number.")
        }
        return number.doubleValue
    }

    func optionalInt(_ key: String) throws -> Int? {
        try optionalDouble(key).map { Int(min(max($0, -1_000_000), 1_000_000)) }
    }

    func optionalStrings(_ key: String) throws -> [String]? {
        guard let value = present(key) else { return nil }
        guard let strings = value as? [String] else { throw RunnerError(400, "\(key) must be a list of strings.") }
        return strings
    }
}
