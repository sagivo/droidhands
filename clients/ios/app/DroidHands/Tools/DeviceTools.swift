import AVFoundation
import Network
import UIKit

/// Network state for device_status, kept current by one shared monitor.
final class NetworkStatus {
    static let shared = NetworkStatus()
    private let monitor = NWPathMonitor()
    private let lock = NSLock()
    private var path: NWPath?

    private init() {
        monitor.pathUpdateHandler = { [weak self] p in self?.lock.withLock { self?.path = p } }
        monitor.start(queue: DispatchQueue(label: "droidhands.network"))
    }

    var summary: JSON {
        guard let p = lock.withLock({ path }) else { return "unknown" }
        guard p.status == .satisfied else { return ["connected": false] }
        let type: String
        if p.usesInterfaceType(.wifi) { type = "wifi" } else if p.usesInterfaceType(.cellular) { type = "cellular" } else if p.usesInterfaceType(.wiredEthernet) { type = "ethernet" } else { type = "other" }
        return .object(JSONObject([("connected", true), ("type", .string(type)), ("expensive", .bool(p.isExpensive)), ("constrained", .bool(p.isConstrained))]))
    }
}

struct DeviceStatusTool: Tool {
    let name = "device_status"
    let title = "Device status"
    let description = "Battery level and charging, model, iOS version, Low Power Mode, network, time zone and local time, and whether the DroidHands app is on screen (iPhone lets DroidHands open links, show the message composer, use the flashlight or speak only while it is)."
    let scope = Scopes.device
    let risk = Risk.READ
    let inputSchema = Schema.empty

    func run(_ args: Args, _ ctx: CallContext) async throws -> ToolResult {
        let info: JSONObject = await MainActor.run {
            let device = UIDevice.current
            device.isBatteryMonitoringEnabled = true
            let level = device.batteryLevel
            let state: String
            switch device.batteryState {
            case .charging: state = "charging"
            case .full: state = "full"
            case .unplugged: state = "unplugged"
            default: state = "unknown"
            }
            let thermal: String
            switch ProcessInfo.processInfo.thermalState {
            case .nominal: thermal = "nominal"
            case .fair: thermal = "fair"
            case .serious: thermal = "serious"
            case .critical: thermal = "critical"
            @unknown default: thermal = "unknown"
            }
            let now = Date()
            let tz = TimeZone.current
            return JSONObject([
                ("battery_percent", level < 0 ? nil : .int(Int((level * 100).rounded()))),
                ("battery_state", .string(state)),
                ("low_power_mode", .bool(ProcessInfo.processInfo.isLowPowerModeEnabled)),
                ("model", .string(DeviceStatusTool.modelIdentifier)),
                ("device_type", .string(device.model)),
                ("os", "iOS"),
                ("os_version", .string(device.systemVersion)),
                ("network", NetworkStatus.shared.summary),
                ("thermal_state", .string(thermal)),
                ("timezone", .string(tz.identifier)),
                ("local_time", .string(ISODates.string(now, allDay: false))),
                ("locale", .string(Locale.current.identifier)),
                ("app_on_screen", .bool(UIApplication.shared.applicationState == .active)),
            ])
        }
        return .json(.object(info))
    }

    static var modelIdentifier: String {
        var info = utsname()
        uname(&info)
        let machine = withUnsafeBytes(of: &info.machine) { raw in String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self) }
        return ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"].map { "\($0) (Simulator)" } ?? machine
    }
}

struct FlashlightTool: Tool {
    let name = "flashlight_set"
    let title = "Flashlight"
    let description = "Turns the flashlight on or off. Only while DroidHands is on screen."
    let scope = Scopes.device
    let risk = Risk.ACTION
    let inputSchema = Schema.object([("on", Schema.boolean("true to turn it on, false to turn it off"))], required: ["on"])

    func describeCall(_ args: Args) -> String { (try? args.bool("on")) == true ? "Turn the flashlight on" : "Turn the flashlight off" }

    func unavailableReason() -> String? {
        AVCaptureDevice.default(for: .video)?.hasTorch == true ? nil : "This device has no flashlight."
    }

    func run(_ args: Args, _ ctx: CallContext) async throws -> ToolResult {
        guard let on = try args.bool("on") else { throw ToolError("'on' is required.") }
        try await Foreground.require("use the flashlight")
        guard let device = AVCaptureDevice.default(for: .video), device.hasTorch else { throw ToolError("This device has no flashlight.") }
        do {
            try device.lockForConfiguration()
            defer { device.unlockForConfiguration() }
            if on { try device.setTorchModeOn(level: AVCaptureDevice.maxAvailableTorchLevel) } else { device.torchMode = .off }
        } catch {
            throw ToolError("The flashlight is busy (another app may be using the camera): \(error.localizedDescription)")
        }
        return .object([("flashlight", .bool(on))])
    }
}

struct ClipboardTool: Tool {
    let name = "clipboard_set"
    let title = "Copy to clipboard"
    let description = "Puts text on the iPhone's clipboard, so the user can paste it anywhere."
    let scope = Scopes.device
    let risk = Risk.ACTION
    let inputSchema = Schema.object([("text", Schema.string("The text to copy"))], required: ["text"])

    func describeCall(_ args: Args) -> String { "Copy \(PromptText.quote((try? args.string("text")) ?? nil, max: 300)) to the clipboard" }

    func run(_ args: Args, _ ctx: CallContext) async throws -> ToolResult {
        guard let text = try args.string("text") else { throw ToolError("'text' is required.") }
        guard text.count <= 100_000 else { throw ToolError("'text' is too long (at most 100000 characters).") }
        await MainActor.run { UIPasteboard.general.string = text }
        return .object([("copied", true), ("characters", .int(text.count))])
    }
}

struct SpeakTool: Tool {
    let name = "speak"
    let title = "Speak out loud"
    let description = "Says text out loud on the iPhone with text-to-speech. Only while DroidHands is on screen."
    let scope = Scopes.device
    let risk = Risk.ACTION
    let timeout: TimeInterval = 120
    let inputSchema = Schema.object([
        ("text", Schema.string("What to say (at most 4000 characters)")),
        ("language", Schema.string("BCP-47 language, e.g. en-US or he-IL (default: the phone's language)")),
    ], required: ["text"])

    func describeCall(_ args: Args) -> String { "Say \(PromptText.quote((try? args.string("text")) ?? nil, max: 300)) out loud" }

    func run(_ args: Args, _ ctx: CallContext) async throws -> ToolResult {
        let text = try args.requiredString("text")
        guard text.count <= 4000 else { throw ToolError("'text' is too long (at most 4000 characters).") }
        let language = try args.string("language")
        if let language, AVSpeechSynthesisVoice(language: language) == nil {
            throw ToolError("There is no voice for '\(language)' on this iPhone.")
        }
        try await Foreground.require("speak")
        try await Speaker.shared.say(text, language: language)
        return .object([("spoken", true), ("characters", .int(text.count))])
    }
}

@MainActor
final class Speaker: NSObject, AVSpeechSynthesizerDelegate {
    static let shared = Speaker()
    private let synth = AVSpeechSynthesizer()
    private var continuation: CheckedContinuation<Void, Never>?

    override init() {
        super.init()
        synth.delegate = self
    }

    func say(_ text: String, language: String?) async throws {
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
        try? AVAudioSession.sharedInstance().setActive(true)
        if synth.isSpeaking { synth.stopSpeaking(at: .immediate) }
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: language ?? AVSpeechSynthesisVoice.currentLanguageCode())
        await withTaskCancellationHandler {
            await withCheckedContinuation { c in
                continuation?.resume()
                continuation = c
                synth.speak(utterance)
            }
        } onCancel: {
            Task { @MainActor in self.synth.stopSpeaking(at: .immediate) }
        }
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    nonisolated func speechSynthesizer(_ s: AVSpeechSynthesizer, didFinish u: AVSpeechUtterance) { Task { @MainActor in finish() } }
    nonisolated func speechSynthesizer(_ s: AVSpeechSynthesizer, didCancel u: AVSpeechUtterance) { Task { @MainActor in finish() } }

    private func finish() {
        continuation?.resume()
        continuation = nil
    }
}

/// ISO-8601 in and out. Accepts a date-time with or without a zone (local time), or a date.
enum ISODates {
    static func parse(_ text: String) -> (date: Date, dateOnly: Bool)? {
        let s = text.trimmingCharacters(in: .whitespaces)
        let withZone = ISO8601DateFormatter()
        withZone.formatOptions = [.withInternetDateTime]
        if let d = withZone.date(from: s) { return (d, false) }
        withZone.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = withZone.date(from: s) { return (d, false) }
        for format in ["yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm"] {
            if let d = local(format).date(from: s) { return (d, false) }
        }
        if let d = local("yyyy-MM-dd").date(from: s) { return (d, true) }
        return nil
    }

    static func require(_ text: String, _ key: String) throws -> (date: Date, dateOnly: Bool) {
        guard let r = parse(text) else {
            throw ToolError("'\(key)' must be an ISO-8601 date or date-time, like 2026-03-14T15:00:00 (local time), 2026-03-14T15:00:00Z or 2026-03-14.")
        }
        return r
    }

    /// Local time with its offset (2026-03-14T15:00:00+02:00), or just the date.
    static func string(_ date: Date, allDay: Bool) -> String {
        if allDay { return local("yyyy-MM-dd").string(from: date) }
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        f.timeZone = .current
        return f.string(from: date)
    }

    private static func local(_ format: String) -> DateFormatter {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = .current
        f.dateFormat = format
        return f
    }
}
