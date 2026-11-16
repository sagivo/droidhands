import ObjectiveC
import XCTest

/// The few private XCTest entry points the runner uses, each looked up at run time and checked before it is called
/// (class exists, object answers to the selector, method has the expected type encoding), and each call wrapped so
/// an Objective-C exception can't take the runner down. Every function returns nil when the private route isn't
/// there, and the caller falls back to public API. These are the same routes WebDriverAgent (Appium) relies on.
enum PrivateXCTest {
    /// Process ids of the apps XCTest's accessibility client reports as active (in front), in its order. The
    /// client is `XCUIDevice.shared.accessibilityInterface` (recent Xcode) or `[XCAXClient_iOS sharedClient]`
    /// (older); `activeApplications` gives accessibility elements with a `processIdentifier`. nil when any piece is
    /// missing.
    static func activeApplicationPIDs() -> [Int32]? {
        var result: [Int32]?
        let exception = PBCatchException {
            guard let client = accessibilityClient() else { return }
            let activeSelector = NSSelectorFromString("activeApplications")
            guard client.responds(to: activeSelector),
                  let elements = client.perform(activeSelector)?.takeUnretainedValue() as? [NSObject] else { return }
            var pids: [Int32] = []
            for element in elements {
                guard element.responds(to: NSSelectorFromString("processIdentifier")),
                      let pid = (element.value(forKey: "processIdentifier") as? NSNumber)?.int32Value, pid > 0,
                      !pids.contains(pid) else { continue }
                pids.append(pid)
            }
            result = pids
        }
        if let exception {
            NSLog("[DroidHandsRunner] active application lookup raised %@: %@", exception.name.rawValue, exception.reason ?? "")
            return nil
        }
        return result
    }

    private static func accessibilityClient() -> NSObject? {
        let activeSelector = NSSelectorFromString("activeApplications")
        let interfaceSelector = NSSelectorFromString("accessibilityInterface")
        if XCUIDevice.shared.responds(to: interfaceSelector),
           let interface = XCUIDevice.shared.perform(interfaceSelector)?.takeUnretainedValue() as? NSObject,
           interface.responds(to: activeSelector) {
            return interface
        }
        let sharedSelector = NSSelectorFromString("sharedClient")
        if let clientClass = NSClassFromString("XCAXClient_iOS") as? NSObject.Type,
           clientClass.responds(to: sharedSelector),
           let client = clientClass.perform(sharedSelector)?.takeUnretainedValue() as? NSObject {
            return client
        }
        return nil
    }

    /// The bundle id of a running app by process id: `[XCUIDevice.shared.applicationMonitor
    /// monitoredApplicationWithProcessIdentifier:pid]` (recent Xcode) or `+[XCUIApplication appWithPID:]` /
    /// `applicationWithPID:` (older), then the application's `bundleID`. nil when no route is there.
    static func bundleID(forPID pid: Int32) -> String? {
        typealias ObjectWithPID = @convention(c) (AnyObject, Selector, Int32) -> AnyObject?
        func call(_ target: AnyObject, _ method: Method?, _ selector: Selector) -> String? {
            guard let method, returnType(method) == "@", argumentType(method, 2) == "i",
                  method_getNumberOfArguments(method) == 3 else { return nil }
            let function = unsafeBitCast(method_getImplementation(method), to: ObjectWithPID.self)
            guard let app = function(target, selector, pid) as? NSObject,
                  app.responds(to: NSSelectorFromString("bundleID")),
                  let id = app.value(forKey: "bundleID") as? String, !id.isEmpty else { return nil }
            return id
        }
        var result: String?
        _ = PBCatchException {
            let monitorSelector = NSSelectorFromString("applicationMonitor")
            let monitoredSelector = NSSelectorFromString("monitoredApplicationWithProcessIdentifier:")
            if XCUIDevice.shared.responds(to: monitorSelector),
               let monitor = XCUIDevice.shared.perform(monitorSelector)?.takeUnretainedValue() as? NSObject,
               let id = call(monitor, class_getInstanceMethod(type(of: monitor), monitoredSelector), monitoredSelector) {
                result = id
                return
            }
            let appClass: AnyClass = XCUIApplication.self
            for name in ["appWithPID:", "applicationWithPID:"] {
                let selector = NSSelectorFromString(name)
                if let id = call(appClass, class_getClassMethod(appClass, selector), selector) {
                    result = id
                    return
                }
            }
        }
        return result
    }

    /// The process id of a running app (`-[XCUIApplication processID]`), when XCUIApplication has it.
    static func processID(of app: XCUIApplication) -> Int32? {
        let selector = NSSelectorFromString("processID")
        guard app.responds(to: selector) else { return nil }
        var pid: Int32?
        _ = PBCatchException { pid = (app.value(forKey: "processID") as? NSNumber)?.int32Value }
        guard let pid, pid > 0 else { return nil }
        return pid
    }

    /// XCTest's `hasKeyboardFocus` attribute of an element snapshot, when the snapshot object has it.
    static func hasKeyboardFocus(_ snapshot: XCUIElementSnapshot) -> Bool? {
        guard let object = snapshot as? NSObject else { return nil }
        let selector = NSSelectorFromString("hasKeyboardFocus")
        guard object.responds(to: selector) else { return nil }
        var focused: Bool?
        _ = PBCatchException { focused = (object.value(forKey: "hasKeyboardFocus") as? NSNumber)?.boolValue }
        return focused
    }

    /// Whether the snapshot objects can say which element has keyboard focus at all.
    static func canReadKeyboardFocus(_ snapshot: XCUIElementSnapshot) -> Bool {
        (snapshot as? NSObject)?.responds(to: NSSelectorFromString("hasKeyboardFocus")) ?? false
    }

    /// `-[XCUIDevice isScreenLocked]`, when XCUIDevice has it (Xcode 27's XCTest doesn't; then `/status` leaves
    /// `locked` out).
    static func isScreenLocked() -> Bool? {
        let device = XCUIDevice.shared
        let selector = NSSelectorFromString("isScreenLocked")
        guard device.responds(to: selector) else { return nil }
        var locked: Bool?
        _ = PBCatchException { locked = (device.value(forKey: "isScreenLocked") as? NSNumber)?.boolValue }
        return locked
    }

    /// `-[XCUIDevice pressLockButton]`. false when XCUIDevice doesn't have it or it raised.
    static func pressLockButton() -> Bool {
        let device = XCUIDevice.shared
        let selector = NSSelectorFromString("pressLockButton")
        guard device.responds(to: selector),
              let method = class_getInstanceMethod(type(of: device), selector),
              returnType(method) == "v", method_getNumberOfArguments(method) == 2 else { return false }
        typealias VoidMethod = @convention(c) (AnyObject, Selector) -> Void
        let function = unsafeBitCast(method_getImplementation(method), to: VoidMethod.self)
        let exception = PBCatchException { function(device, selector) }
        return exception == nil
    }

    private static func returnType(_ method: Method) -> String {
        let raw = method_copyReturnType(method)
        defer { free(raw) }
        return String(cString: raw)
    }

    private static func argumentType(_ method: Method, _ index: UInt32) -> String? {
        guard let raw = method_copyArgumentType(method, index) else { return nil }
        defer { free(raw) }
        return String(cString: raw)
    }
}
