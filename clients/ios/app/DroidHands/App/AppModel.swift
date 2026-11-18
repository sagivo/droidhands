import Observation
import UIKit
import UserNotifications

/// A pairing waiting for the user: the agent's request and the grant being chosen.
struct PairingPrompt: Identifiable {
    let request: PairingRequest
    var name: String
    var grant = GrantDraft()
    var id: String { request.id }
}

/// An approval waiting for the user.
struct ApprovalPrompt: Identifiable {
    let request: ConfirmationRequest
    var id: UUID { request.id }
}

/// The app's state and wiring: identity, tokens, audit log, MCP server, relay connection, and the prompts the
/// user answers. Lives on the main actor; the server and the relay run off it and report back here.
@MainActor
@Observable
final class AppModel {
    let settings = Settings()
    private(set) var identity: DeviceIdentity
    private(set) var tokens: TokenManager
    let audit: AuditLog
    private(set) var server: McpServer!
    private var relay: RelayClient?

    private(set) var relayState: RelayClient.State = .stopped
    private(set) var agents: [TokenRecord] = []
    private(set) var activity: [AuditEntry] = []
    private(set) var pairings: [PairingPrompt] = []
    private(set) var approvals: [ApprovalPrompt] = []
    private(set) var runningCalls = 0
    private(set) var isInForeground = true
    /// Set when the app went to the background and iOS was about to suspend it, so the relay was told it left.
    private(set) var isAsleep = false
    /// A `droidhands://configure` link waiting for "Connect to relay …?".
    var pendingRelayChange: (relay: String, key: String?)?
    var startupError: String?
    /// Screens that can show the prompt sheet, outermost first. Only the topmost may present: SwiftUI can't
    /// show a sheet from a view that already has one on top (the Connect screen is open when agents connect).
    private(set) var promptHosts: [UUID] = []

    @ObservationIgnored private var pairingWaiters: [String: CheckedContinuation<PairingDecision, Never>] = [:]
    @ObservationIgnored private var cancelledPairings: Set<String> = []
    @ObservationIgnored private var approvalWaiters: [UUID: CheckedContinuation<ConfirmationResult, Never>] = [:]
    /// Paired tokens no agent has picked up yet, by token id: the pairing that issued it and when to give up on it
    /// (deleted if neither pair_complete nor a use arrives in 15 minutes).
    @ObservationIgnored private var unclaimed: [String: (pairingId: String, deadline: Date)] = [:]
    @ObservationIgnored private var cancelledApprovals: Set<UUID> = []
    @ObservationIgnored private var backgroundTimer: Timer?
    @ObservationIgnored private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    @ObservationIgnored private var accessEnabledFlag = Locked(true)
    @ObservationIgnored private var expiryTimer: Timer?

    var connectURL: String? {
        if case .connected(let url) = relayState, let url { return url }
        return settings.lastConnectURL(for: identity.deviceId)
    }

    var accessEnabled: Bool {
        get { settings.accessEnabled }
        set {
            settings.accessEnabled = newValue
            accessEnabledFlag.value = newValue
            if !newValue { server.cancelCalls(reason: .paused) }
        }
    }

    var activeAgents: [TokenRecord] { agents.filter { $0.isActive(at: Date()) } }

    init() {
        let identity: DeviceIdentity
        var error: String?
        do {
            identity = try IdentityStore.loadOrCreate()
        } catch let e {
            // Without a stable identity agents can't stay paired; run with a temporary one and say so.
            identity = DeviceIdentity.generate()
            error = "DroidHands couldn't read its identity from the Keychain (\(e.localizedDescription)). Restart the iPhone and try again."
        }
        self.identity = identity
        audit = AuditLog(file: JSONFile(name: "activity.json"))
        do {
            tokens = try TokenManager(store: JSONFile(name: "agents.json"), deviceId: identity.deviceId)
        } catch let e {
            error = "DroidHands couldn't read its list of agents (\(e.localizedDescription))."
            tokens = try! TokenManager(store: JSONFile(name: "agents-recovered.json"), deviceId: identity.deviceId)
        }
        startupError = error
        accessEnabledFlag.value = settings.accessEnabled
        wire()
        expiryTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.expirePrompts() }
        }
    }

    private func wire() {
        agents = tokens.all
        activity = audit.all
        tokens.onChange = { [weak self] list in Task { @MainActor in self?.agents = list } }
        audit.onChange = { [weak self] list in Task { @MainActor in self?.activity = list } }
        let flag = accessEnabledFlag
        server = McpServer(tools: ToolCatalog.all(), tokens: tokens, audit: audit, confirmations: self,
                           instructions: AgentInstructions.text, serverVersion: AppInfo.version,
                           isAccessEnabled: { flag.value })
        server.onActivity = { [weak self] n in Task { @MainActor in self?.runningCalls = n } }
    }

    // MARK: Connection

    func start() {
        guard relay == nil else { return }
        let config = RelayClient.Config(relayURL: settings.relayURL, accessKey: settings.accessKey,
                                        deviceName: UIDevice.current.name, appVersion: AppInfo.version)
        let client = RelayClient(identity: identity, config: config, server: server, pairing: self)
        let deviceId = identity.deviceId
        client.onState = { [weak self] state in
            Task { @MainActor in
                guard let self else { return }
                self.relayState = state
                if case .connected(let url?) = state { self.settings.setLastConnectURL(url, for: deviceId) }
            }
        }
        relay = client
        isAsleep = false
        client.start()
    }

    func stop() {
        relay?.stop()
        relay = nil
        relayState = .stopped
    }

    func restart() {
        stop()
        start()
    }

    func applyRelay(url: String, key: String?) {
        settings.relayURL = url
        settings.accessKey = key
        restart()
    }

    // MARK: Lifecycle

    /// iOS keeps an app running briefly after it leaves the screen. DroidHands stays connected for that time,
    /// then says goodbye to the relay so agents get a clear "offline" at once instead of a timeout.
    func didEnterBackground() {
        isInForeground = false
        audit.flush()
        guard backgroundTask == .invalid else { return }
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "DroidHands relay") { [weak self] in
            // iOS calls this on the main thread and expects the task to end before it returns.
            MainActor.assumeIsolated {
                guard let self else { return }
                self.backgroundTimer?.invalidate()
                self.backgroundTimer = nil
                if !self.isInForeground { self.stop(); self.isAsleep = true }
                self.endBackgroundTask()
            }
        }
        // Say goodbye while there is still time for the close frame to go out.
        backgroundTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, !self.isInForeground else { return }
                if UIApplication.shared.backgroundTimeRemaining < 6 { self.sleep() }
            }
        }
    }

    func didBecomeActive() {
        isInForeground = true
        backgroundTimer?.invalidate()
        backgroundTimer = nil
        endBackgroundTask()
        if relay == nil || isAsleep { start() } else { relay?.reconnectIfWaiting() }
        UNUserNotificationCenter.current().removeAllDeliveredNotifications()
    }

    private func sleep() {
        backgroundTimer?.invalidate()
        backgroundTimer = nil
        guard !isInForeground else { return endBackgroundTask() }
        stop()
        isAsleep = true
        // Give the WebSocket close a moment to be written before iOS suspends the app.
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in
            MainActor.assumeIsolated { if self?.isInForeground == false { self?.endBackgroundTask() } }
        }
    }

    private func endBackgroundTask() {
        if backgroundTask != .invalid {
            UIApplication.shared.endBackgroundTask(backgroundTask)
            backgroundTask = .invalid
        }
    }

    func handle(url: URL) {
        if CallbackRouter.shared.handle(url) { return }
        guard url.scheme == "droidhands", url.host == "configure",
              let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems,
              let relay = items.first(where: { $0.name == "relay" })?.value, Settings.isValidRelay(relay),
              Settings.isEncryptedOrLocal(relay) else { return }
        pendingRelayChange = (relay, items.first { $0.name == "key" }?.value)
    }

    // MARK: Agents

    func issueManualToken(name: String, grant: GrantDraft) throws -> String {
        let (_, plaintext) = try tokens.issue(name: name, scopes: grant.scopes, confirmPolicy: grant.confirmPolicy, origin: "manual")
        return plaintext
    }

    func update(_ id: String, _ transform: (inout TokenRecord) -> Void) {
        do {
            let updated = try tokens.update(id, transform)
            // Calls that are no longer allowed stop at their next check; stop running ones now if access shrank.
            if !updated.isActive(at: Date()) { server.cancelCalls(tokenId: id) }
        } catch {
            startupError = error.localizedDescription
        }
    }

    func revoke(_ id: String) {
        try? tokens.revoke(id)
        server.cancelCalls(tokenId: id, reason: .revoked)
    }

    func delete(_ id: String) {
        try? tokens.delete(id)
        server.cancelCalls(tokenId: id, reason: .deleted)
    }

    func revokeAll() {
        _ = try? tokens.revokeAll()
        server.cancelCalls(reason: .revoked)
    }

    func stopCalls(of id: String) { server.cancelCalls(tokenId: id, reason: .stoppedByUser) }

    /// A new identity: a new connection URL; every agent is removed and must pair again.
    func resetIdentity() {
        stop()
        server.cancelCalls(reason: .deleted)
        try? tokens.deleteAll()
        if let fresh = try? IdentityStore.reset() {
            identity = fresh
            if let manager = try? TokenManager(store: JSONFile(name: "agents.json"), deviceId: fresh.deviceId) { tokens = manager }
            wire()
        }
        start()
    }

    // MARK: Prompts

    func addPromptHost(_ id: UUID) { if !promptHosts.contains(id) { promptHosts.append(id) } }
    func removePromptHost(_ id: UUID) { promptHosts.removeAll { $0 == id } }

    func approve(_ prompt: PairingPrompt) {
        guard let waiter = pairingWaiters.removeValue(forKey: prompt.id) else { return dismissPairing(prompt.id) }
        let r = prompt.request
        do {
            let (record, token) = try tokens.issue(name: prompt.name, scopes: prompt.grant.scopes, confirmPolicy: prompt.grant.confirmPolicy,
                                                   origin: "pairing", clientHost: r.viaAgent ? nil : r.redirectHost,
                                                   declaredName: prompt.name == r.clientName ? nil : r.clientName)
            unclaimed[record.id] = (prompt.id, Date().addingTimeInterval(15 * 60))
            waiter.resume(returning: .approved(accessToken: token, tokenId: record.id))
        } catch {
            waiter.resume(returning: .denied("busy"))
        }
        dismissPairing(prompt.id)
    }

    func deny(_ prompt: PairingPrompt) {
        pairingWaiters.removeValue(forKey: prompt.id)?.resume(returning: .denied("denied"))
        dismissPairing(prompt.id)
    }

    func updatePairing(_ prompt: PairingPrompt) {
        if let i = pairings.firstIndex(where: { $0.id == prompt.id }) { pairings[i] = prompt }
    }

    private func dismissPairing(_ id: String) {
        pairings.removeAll { $0.id == id }
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["pair-\(id)"])
    }

    func answer(_ prompt: ApprovalPrompt, approved: Bool) {
        approvalWaiters.removeValue(forKey: prompt.id)?.resume(returning: approved ? .approved : .denied)
        approvals.removeAll { $0.id == prompt.id }
    }

    private func expirePrompts() {
        let now = Date()
        for p in pairings where p.request.expiresAt <= now {
            pairingWaiters.removeValue(forKey: p.id)?.resume(returning: .denied("timeout"))
            dismissPairing(p.id)
        }
        for a in approvals where a.request.expiresAt <= now {
            approvalWaiters.removeValue(forKey: a.id)?.resume(returning: .timeout)
            approvals.removeAll { $0.id == a.id }
        }
        for (id, entry) in unclaimed where entry.deadline <= now {
            unclaimed.removeValue(forKey: id)
            if let r = tokens.record(id), r.lastUsedAt == nil { try? tokens.delete(id) }
        }
    }

    fileprivate func addPairing(_ request: PairingRequest, _ waiter: CheckedContinuation<PairingDecision, Never>) {
        if cancelledPairings.remove(request.id) != nil { return waiter.resume(returning: .denied("timeout")) }
        pairingWaiters[request.id] = waiter
        pairings.append(PairingPrompt(request: request, name: request.clientName))
        if !isInForeground {
            notify(id: "pair-\(request.id)", title: "\(request.clientName) wants to use this iPhone",
                   body: "Open DroidHands to allow or deny. Code \(request.displayCode).")
        }
    }

    fileprivate func cancelPairing(_ id: String) {
        if let waiter = pairingWaiters.removeValue(forKey: id) {
            waiter.resume(returning: .denied("timeout"))
        } else {
            cancelledPairings.insert(id)
        }
        dismissPairing(id)
    }

    /// `pair_complete` / `pair_abandoned`. Only a token this phone issued for that very pairing, and that no agent
    /// has used yet, is deleted: a relay can't remove other agents by naming their token ids.
    fileprivate func finishPairing(id: String, tokenId: String?, completed: Bool) {
        guard let tokenId, let entry = unclaimed[tokenId], entry.pairingId == id else { return }
        unclaimed.removeValue(forKey: tokenId)
        if !completed, let record = tokens.record(tokenId), record.lastUsedAt == nil { try? tokens.delete(tokenId) }
    }

    fileprivate func addApproval(_ request: ConfirmationRequest, _ waiter: CheckedContinuation<ConfirmationResult, Never>) {
        if cancelledApprovals.remove(request.id) != nil { return waiter.resume(returning: .denied) }
        approvalWaiters[request.id] = waiter
        approvals.append(ApprovalPrompt(request: request))
        if !isInForeground {
            notify(id: "approve-\(request.id)", title: "\(request.agentName) is waiting for you", body: request.summary)
        }
    }

    fileprivate func cancelApproval(_ id: UUID) {
        if let waiter = approvalWaiters.removeValue(forKey: id) {
            waiter.resume(returning: .denied)
        } else {
            cancelledApprovals.insert(id) // Cancelled before its prompt was added.
        }
        approvals.removeAll { $0.id == id }
    }

    private func notify(id: String, title: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.interruptionLevel = .timeSensitive
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: id, content: content, trigger: nil))
    }
}

extension AppModel: PairingHandler {
    nonisolated func onPairRequest(_ request: PairingRequest) async -> PairingDecision {
        await withTaskCancellationHandler {
            await withCheckedContinuation { c in
                Task { @MainActor in self.addPairing(request, c) }
            }
        } onCancel: {
            Task { @MainActor in self.cancelPairing(request.id) }
        }
    }

    nonisolated func onPairFinished(id: String, tokenId: String?, completed: Bool) {
        Task { @MainActor in self.finishPairing(id: id, tokenId: tokenId, completed: completed) }
    }
}

extension AppModel: ConfirmationProvider {
    nonisolated func confirm(_ request: ConfirmationRequest) async -> ConfirmationResult {
        await withTaskCancellationHandler {
            await withCheckedContinuation { c in
                Task { @MainActor in self.addApproval(request, c) }
            }
        } onCancel: {
            Task { @MainActor in self.cancelApproval(request.id) }
        }
    }
}

/// A value read off the main actor (the server's pause check).
final class Locked<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var _value: T
    init(_ value: T) { _value = value }
    var value: T {
        get { lock.withLock { _value } }
        set { lock.withLock { _value = newValue } }
    }
}

enum AppInfo {
    static var version: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0" }
    static var build: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0" }
}

/// The app's settings, in UserDefaults (nothing secret: the device secret is in the Keychain).
@Observable
final class Settings {
    @ObservationIgnored private let defaults = UserDefaults.standard

    static var defaultRelay: String {
        let v = (Bundle.main.object(forInfoDictionaryKey: "DroidHandsDefaultRelayURL") as? String)?.trimmingCharacters(in: .whitespaces) ?? ""
        return isValidRelay(v) ? v : "https://connect.droidhands.com"
    }

    static func isValidRelay(_ s: String) -> Bool {
        guard let c = URLComponents(string: s.trimmingCharacters(in: .whitespaces)), let scheme = c.scheme?.lowercased(),
              ["https", "http", "wss", "ws"].contains(scheme), let host = c.host, !host.isEmpty else { return false }
        return true
    }

    /// Links from outside may only name https relays (or one on this device): the hello frame carries the
    /// device secret, which must not cross a network in the clear.
    static func isEncryptedOrLocal(_ s: String) -> Bool {
        guard let c = URLComponents(string: s.trimmingCharacters(in: .whitespaces)), let scheme = c.scheme?.lowercased() else { return false }
        return scheme == "https" || scheme == "wss" || ["localhost", "127.0.0.1", "::1"].contains(c.host ?? "")
    }

    var relayURL: String {
        didSet { defaults.set(relayURL, forKey: "relayURL") }
    }

    var accessKey: String? {
        didSet { defaults.set(accessKey, forKey: "accessKey") }
    }

    var accessEnabled: Bool {
        didSet { defaults.set(accessEnabled, forKey: "accessEnabled") }
    }

    var onboarded: Bool {
        didSet { defaults.set(onboarded, forKey: "onboarded") }
    }

    init() {
        relayURL = defaults.string(forKey: "relayURL") ?? Settings.defaultRelay
        accessKey = defaults.string(forKey: "accessKey")
        accessEnabled = defaults.object(forKey: "accessEnabled") as? Bool ?? true
        onboarded = defaults.bool(forKey: "onboarded")
    }

    /// The last connection URL the relay announced for this identity, so it shows while offline.
    func lastConnectURL(for deviceId: String) -> String? { defaults.string(forKey: "connectURL.\(deviceId)") }
    func setLastConnectURL(_ url: String, for deviceId: String) { defaults.set(url, forKey: "connectURL.\(deviceId)") }
}
