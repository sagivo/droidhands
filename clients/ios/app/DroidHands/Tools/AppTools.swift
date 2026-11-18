@preconcurrency import CallKit
import MessageUI
import UIKit

struct UrlOpenTool: Tool {
    let name = "url_open"
    let title = "Open link"
    let description = "Opens a link on the iPhone: a web page (https), maps (maps://?q=… or https://maps.apple.com/?q=…), mailto:, sms:, facetime:, or another app's link (its own scheme, or an https link it handles). iPhone decides which app opens it. Only while DroidHands is on screen."
    let scope = Scopes.apps
    let risk = Risk.ACTION
    let inputSchema = Schema.object([("url", Schema.string("The link to open"))], required: ["url"])

    static let refused: Set<String> = ["javascript", "file", "data", "about", "blob", "tel", "telprompt", "shortcuts", "droidhands", "app-settings", "prefs"]

    func describeCall(_ args: Args) -> String {
        let raw = ((try? args.string("url")) ?? nil) ?? ""
        let url = URL(string: raw)
        let place = url?.host.map { "on \(PromptText.clean($0, max: 80))" } ?? "(\(url?.scheme ?? "?"):)"
        return "Open a link \(place): \(PromptText.quote(raw, max: 200))"
    }

    func run(_ args: Args, _ ctx: CallContext) async throws -> ToolResult {
        let raw = try args.requiredString("url")
        guard let url = URL(string: raw), let scheme = url.scheme?.lowercased(), !scheme.isEmpty else {
            throw ToolError("'\(raw.prefix(200))' isn't a link (it needs a scheme like https:).")
        }
        if scheme == "tel" || scheme == "telprompt" { throw ToolError("Use phone_call to call a number.") }
        if scheme == "shortcuts" { throw ToolError("Use shortcut_run to run a shortcut.") }
        if UrlOpenTool.refused.contains(scheme) { throw ToolError("DroidHands doesn't open \(scheme): links.") }
        // user:password@host links read as another site in prompts and logs.
        if url.user != nil || url.password != nil { throw ToolError("DroidHands doesn't open links with a user name or password in them (user@host).") }
        try await Foreground.require("open links")
        let opened = await MainActor.run { () -> Task<Bool, Never> in
            Task { @MainActor in await UIApplication.shared.open(url) }
        }.value
        guard opened else {
            throw ToolError("No app on this iPhone opens \(scheme): links. Check the link, or use an https link instead.")
        }
        return .object([("opened", true), ("url", .string(raw)),
                        ("note", "iPhone opened it in the app it picks for this link. DroidHands can't see that app's screen.")])
    }
}

/// Watches the phone's calls (CallKit): whether a call is ringing, dialing or connected.
final class CallWatcher: NSObject, CXCallObserverDelegate, @unchecked Sendable {
    static let shared = CallWatcher()
    private let observer = CXCallObserver()
    private var waiters: [(CXCall) -> Bool] = []
    private let lock = NSLock()

    override init() {
        super.init()
        observer.setDelegate(self, queue: DispatchQueue(label: "droidhands.calls"))
    }

    var calls: [CXCall] { observer.calls }

    func callObserver(_ callObserver: CXCallObserver, callChanged call: CXCall) {
        lock.withLock { waiters = waiters.filter { !$0(call) } }
    }

    /// Waits up to [timeout] for a call change that [match] accepts; nil on timeout or cancellation.
    func wait(timeout: TimeInterval, _ match: @escaping (CXCall) -> Bool) async -> CXCall? {
        let shot = OneShot<CXCall?>()
        return await withTaskCancellationHandler {
            await shot.wait {
                lock.withLock {
                    waiters.append { call in
                        guard match(call) else { return false }
                        shot.finish(call)
                        return true
                    }
                }
                DispatchQueue.global().asyncAfter(deadline: .now() + timeout) { shot.finish(nil) }
            }
        } onCancel: {
            shot.finish(nil)
        }
    }
}

/// A value delivered once, by whichever of several callers comes first (later ones are ignored).
final class OneShot<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<T, Never>?
    private var value: T?
    private var done = false

    func wait(_ start: () -> Void) async -> T {
        await withCheckedContinuation { c in
            let early: T? = lock.withLock {
                if done { return value }
                continuation = c
                return nil
            }
            if let early { c.resume(returning: early) } else { start() }
        }
    }

    func finish(_ v: T) {
        let c: CheckedContinuation<T, Never>? = lock.withLock {
            if done { return nil }
            done = true
            value = v
            defer { continuation = nil }
            return continuation
        }
        c?.resume(returning: v)
    }
}

struct PhoneCallTool: Tool {
    let name = "phone_call"
    let title = "Make a call"
    let description = "Starts a phone call to a number or a contact. iPhone shows the number and the user taps Call; the result says whether the call started. Only while DroidHands is on screen."
    let scope = Scopes.calls
    let risk = Risk.SENSITIVE
    let confirmsInternally = true
    let timeout: TimeInterval = 90
    let inputSchema = Schema.object([
        ("to", Schema.string("Phone number")),
        ("contact", Schema.string("Contact name (resolved like contacts_search; must match one contact)")),
    ])

    func run(_ args: Args, _ ctx: CallContext) async throws -> ToolResult {
        let (number, who) = try await ContactBook.recipient(args)
        try await ctx.requireConfirmation("Call \(who)")
        try await Foreground.require("start calls")
        guard let url = URL(string: "tel:\(number)") else { throw ToolError("'\(number)' can't be dialed.") }
        try ctx.ensureStillAllowed()
        let opened = await MainActor.run { () -> Task<Bool, Never> in Task { @MainActor in await UIApplication.shared.open(url) } }.value
        guard opened else { throw ToolError("This device can't make phone calls.") }
        let started = await CallWatcher.shared.wait(timeout: 45) { $0.isOutgoing && !$0.hasEnded }
        if started != nil {
            return .object([("calling", .string(who)), ("started", true), ("note", "The user tapped Call and the call is dialing.")])
        }
        return .object([("calling", .string(who)), ("started", false),
                        ("note", "iPhone asked the user to tap Call, and no call started within 45 seconds; they may have cancelled.")])
    }
}

struct CallStatusTool: Tool {
    let name = "call_status"
    let title = "Call status"
    let description = "Whether a call is ringing, dialing or in progress on the iPhone (iPhone doesn't tell apps who is calling)."
    let scope = Scopes.calls
    let risk = Risk.READ
    let inputSchema = Schema.empty

    func run(_ args: Args, _ ctx: CallContext) async throws -> ToolResult {
        let live = CallWatcher.shared.calls.filter { !$0.hasEnded }
        let state: String
        if live.contains(where: { $0.hasConnected }) { state = "in_call" } else if live.contains(where: { !$0.isOutgoing }) { state = "ringing" } else if !live.isEmpty { state = "dialing" } else { state = "idle" }
        return .object([("state", .string(state)), ("calls", .int(live.count)),
                        ("call_waiting", .bool(live.count > 1)), ("on_hold", .bool(live.contains { $0.isOnHold }))])
    }
}

struct SmsSendTool: Tool {
    let name = "sms_send"
    let title = "Send message"
    let description = "Writes a text message (iMessage or SMS, whichever iPhone uses for that number) to a number or contact and shows it to the user, who taps Send. Waits up to 2 minutes and reports whether they sent it. Only while DroidHands is on screen."
    let scope = Scopes.messagesSend
    let risk = Risk.SENSITIVE
    let confirmsInternally = true
    let timeout: TimeInterval = 150
    let inputSchema = Schema.object([
        ("to", Schema.string("Phone number (or an email address used for iMessage)")),
        ("contact", Schema.string("Contact name (resolved like contacts_search; must match one contact)")),
        ("message", Schema.string("The text")),
    ], required: ["message"])

    func unavailableReason() -> String? {
        MFMessageComposeViewController.canSendText() ? nil : "This device can't send messages (no Messages account)."
    }

    func run(_ args: Args, _ ctx: CallContext) async throws -> ToolResult {
        let message = try args.requiredString("message")
        guard message.count <= 5000 else { throw ToolError("'message' is too long (at most 5000 characters).") }
        let recipient: (number: String, display: String)
        if let to = try args.string("to"), to.contains("@") {
            recipient = (to.trimmingCharacters(in: .whitespaces), to.trimmingCharacters(in: .whitespaces))
        } else {
            recipient = try await ContactBook.recipient(args)
        }
        try await ctx.requireConfirmation("Message \(recipient.display): \(PromptText.quote(message, max: 300))")
        try await Foreground.require("show the message composer")
        try ctx.ensureStillAllowed()
        let result = try await Composer.message(to: recipient.number, body: message, timeout: try ctx.waitBudget(120))
        switch result {
        case .sent:
            return .object([("sent", true), ("to", .string(recipient.display)), ("note", "The user tapped Send.")])
        case .cancelled:
            return .error("The user cancelled the message to \(recipient.display); nothing was sent. Ask them what to change.")
        case .failed:
            return .error("Messages couldn't send it to \(recipient.display).")
        @unknown default:
            return .error("Messages ended without saying whether it was sent.")
        }
    }
}

@MainActor
final class Composer: NSObject, MFMessageComposeViewControllerDelegate {
    private static var active: Composer?
    private var continuation: CheckedContinuation<MessageComposeResult, Error>?
    private weak var controller: MFMessageComposeViewController?

    static func message(to recipient: String, body: String, timeout: TimeInterval) async throws -> MessageComposeResult {
        guard active == nil else { throw ToolError("A message is already waiting for the user to send. Wait for that one first.") }
        // Right after an Allow the approval sheet is still animating away; present once the screen has settled.
        var presenter = Foreground.presenter
        for _ in 0..<30 {
            guard let p = presenter, p.isBeingDismissed || p.isBeingPresented || p.transitionCoordinator != nil else { break }
            try await Task.sleep(nanoseconds: 100_000_000)
            presenter = Foreground.presenter
        }
        guard let presenter else { throw ToolError("DroidHands can't show the composer right now.") }
        let composer = Composer()
        active = composer
        defer { active = nil }
        let vc = MFMessageComposeViewController()
        vc.recipients = [recipient]
        vc.body = body
        vc.messageComposeDelegate = composer
        composer.controller = vc
        return try await withThrowingTaskGroup(of: MessageComposeResult.self) { group in
            group.addTask { @MainActor in
                try await withTaskCancellationHandler {
                    try await withCheckedThrowingContinuation { c in
                        composer.continuation = c
                        presenter.present(vc, animated: true) {
                            if vc.presentingViewController == nil {
                                composer.close(.failure(ToolError("iPhone didn't show the message composer (another screen was in the way). Nothing was sent; try again.")))
                            }
                        }
                    }
                } onCancel: {
                    Task { @MainActor in composer.close(.failure(CancellationError())) }
                }
            }
            group.addTask {
                try await Task.sleep(nanoseconds: UInt64(timeout * 1e9))
                let error = ToolError("The user didn't send the message within \(Int(timeout)) seconds, so DroidHands closed it. Nothing was sent.")
                await composer.close(.failure(error))
                throw error
            }
            defer { group.cancelAll() }
            return try await group.next()!
        }
    }

    nonisolated func messageComposeViewController(_ controller: MFMessageComposeViewController, didFinishWith result: MessageComposeResult) {
        Task { @MainActor in close(.success(result)) }
    }

    func close(_ result: Result<MessageComposeResult, Error>) {
        controller?.dismiss(animated: true)
        continuation?.resume(with: result)
        continuation = nil
    }
}

/// Answers from apps that DroidHands asked to call it back (x-callback-url), by request id.
final class CallbackRouter: @unchecked Sendable {
    static let shared = CallbackRouter()
    struct Answer { let outcome: String; let items: [String: String] }
    private let lock = NSLock()
    private var waiting: [String: OneShot<Answer?>] = [:]

    /// `droidhands://x-callback/<id>/<success|cancel|error>?…`. True if it was one of ours.
    @discardableResult
    func handle(_ url: URL) -> Bool {
        guard url.scheme == "droidhands", url.host == "x-callback" else { return false }
        let parts = url.pathComponents.filter { $0 != "/" }
        guard parts.count == 2 else { return true }
        var items: [String: String] = [:]
        for q in URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? [] { items[q.name] = q.value ?? "" }
        lock.withLock { waiting.removeValue(forKey: parts[0]) }?.finish(Answer(outcome: parts[1], items: items))
        return true
    }

    /// Registers [id] (call before opening the other app), then waits for its answer: nil on timeout or cancellation.
    func register(id: String) -> OneShot<Answer?> {
        let shot = OneShot<Answer?>()
        lock.withLock { waiting[id] = shot }
        return shot
    }

    func wait(id: String, _ shot: OneShot<Answer?>, timeout: TimeInterval) async -> Answer? {
        defer { lock.withLock { _ = waiting.removeValue(forKey: id) } }
        return await withTaskCancellationHandler {
            await shot.wait {
                DispatchQueue.global().asyncAfter(deadline: .now() + timeout) { shot.finish(nil) }
            }
        } onCancel: {
            shot.finish(nil)
        }
    }
}

struct ShortcutRunTool: Tool {
    let name = "shortcut_run"
    let title = "Run shortcut"
    let description = "Runs one of the user's shortcuts from the Shortcuts app by its exact name, optionally with text input, and returns its output (what the shortcut's last action outputs). Shortcuts can do what other tools can't: send messages without a tap, control HomeKit, run other apps' actions. The Shortcuts app comes to the front while it runs, then DroidHands returns. Only while DroidHands is on screen."
    let scope = Scopes.intents
    let risk = Risk.SENSITIVE
    let timeout: TimeInterval = 180
    let inputSchema = Schema.object([
        ("name", Schema.string("The shortcut's exact name, as shown in the Shortcuts app")),
        ("input", Schema.string("Text passed to the shortcut as its input")),
        ("wait_seconds", Schema.integer("How long to wait for the shortcut to finish (default 60, at most 120)", min: 5, max: 120)),
    ], required: ["name"])

    func describeCall(_ args: Args) -> String {
        let name = ((try? args.string("name")) ?? nil) ?? "?"
        let input = ((try? args.string("input")) ?? nil).map { " with \(PromptText.quote($0, max: 300))" } ?? ""
        return "Run the shortcut \(PromptText.quote(name, max: 80))\(input)"
    }

    func run(_ args: Args, _ ctx: CallContext) async throws -> ToolResult {
        let name = try args.requiredString("name")
        let input = try args.string("input")
        let requested = TimeInterval(try args.int("wait_seconds", in: 5...120) ?? 60)
        try await Foreground.require("run shortcuts")
        let id = UUID().uuidString.lowercased()
        var c = URLComponents()
        c.scheme = "shortcuts"
        c.host = "x-callback-url"
        c.path = "/run-shortcut"
        var items = [URLQueryItem(name: "name", value: name)]
        if let input {
            items += [URLQueryItem(name: "input", value: "text"), URLQueryItem(name: "text", value: input)]
        }
        items += ["success", "cancel", "error"].map { URLQueryItem(name: "x-\($0)", value: "droidhands://x-callback/\(id)/\($0)") }
        c.queryItems = items
        guard let url = c.url else { throw ToolError("That shortcut name can't be put in a link.") }
        try ctx.ensureStillAllowed()
        let wait = try ctx.waitBudget(requested)
        let shot = CallbackRouter.shared.register(id: id)
        let opened = await MainActor.run { () -> Task<Bool, Never> in Task { @MainActor in await UIApplication.shared.open(url) } }.value
        guard opened else {
            shot.finish(nil)
            throw ToolError("The Shortcuts app isn't on this iPhone (the user can get it from the App Store).")
        }
        guard let answer = await CallbackRouter.shared.wait(id: id, shot, timeout: wait) else {
            try ctx.ensureStillAllowed()
            return .error("The shortcut “\(name)” didn't report back within \(Int(wait)) seconds. It may still be running, or be waiting for the user in the Shortcuts app.")
        }
        switch answer.outcome {
        case "success":
            let result = answer.items["result"]
            return .object([("ran", .string(name)), ("finished", true), ("output", result.map { .string(String($0.prefix(20_000))) } ?? .null)])
        case "cancel":
            return .error("The shortcut “\(name)” was cancelled (by the user, or by a step that stopped it).")
        default:
            let message = answer.items["errorMessage"] ?? answer.items["error"] ?? "no details"
            return .error("The shortcut “\(name)” failed: \(message). If the name is wrong, ask the user for the exact name.")
        }
    }
}
