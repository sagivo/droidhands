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

/// The app's state and wiring: identity, accounts, tokens, audit log, MCP server, relay connection, and the prompts the
/// user answers. Lives on the main actor; the server and the relay run off it and report back here.
@MainActor
@Observable
final class AppModel {
    let settings = Settings()
    private(set) var identity: DeviceIdentity
    private(set) var tokens: TokenManager
    let audit: AuditLog
    let accounts: AccountManager
    private(set) var server: McpServer!
    private var relay: RelayClient?
    @ObservationIgnored private let accountBrowser = AccountBrowser()

    private(set) var relayState: RelayClient.State = .stopped
    private(set) var agents: [TokenRecord] = []
    private(set) var activity: [AuditEntry] = []
    private(set) var pairings: [PairingPrompt] = []
    private(set) var approvals: [ApprovalPrompt] = []
    private(set) var runningCalls = 0
    private(set) var isInForeground = true
    /// Set when the app went to the background and iOS was about to suspend it, so the relay was told it left.
    private(set) var isAsleep = false
    /// The signed-in DroidHands account, or nil.
    private(set) var accountSession: AccountSession?
    /// Progress / error of the last Google sign-in attempt.
    private(set) var signInState: SignInState = .idle
    /// Signing out or deleting the account is in progress.
    private(set) var accountBusy = false
    /// Transient user-facing message (toast banner).
    var bannerMessage: String?
    /// A `droidhands://configure` or pasted setup link waiting for "Connect to relay …?".
    var pendingConfigure: ConfigureRequest?
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
    @ObservationIgnored private var approveWaitTask: Task<Void, Never>?
    @ObservationIgnored private var bannerTask: Task<Void, Never>?
    @ObservationIgnored private var lastAccountSessionTag: String?

    /// The build's normalized default relay URL, or nil when this build has none.
    var defaultRelayURL: String? { Settings.defaultRelay }

    /// The relay that requires a DroidHands account: the build's default relay when an API server is configured.
    var accountRelayURL: String? { accounts.enabled ? defaultRelayURL : nil }

    /// The default relay needs a DroidHands account (Settings shows the Account section).
    var relayNeedsAccount: Bool { accountRelayURL != nil }

    /// Using the default relay needs a Google sign-in first.
    var signInNeeded: Bool { relayNeedsAccount && accountSession == nil }

    /// The build's default relay is currently turned on and in use.
    var defaultRelayInUse: Bool {
        DefaultRelay.inUse(relayEnabled: settings.relayEnabled, relayFromDefault: settings.relayFromDefault)
    }

    /// Offer "Connect using DroidHands": default relay exists and no custom relay is configured.
    var canOfferDefaultRelay: Bool {
        DefaultRelay.canOffer(relayEnabled: settings.relayEnabled, relayURL: settings.relayURL,
                              relayFromDefault: settings.relayFromDefault, rawDefaultURL: defaultRelayURL)
    }

    /// True if the currently configured relay is the account-backed hosted relay.
    var relayUsesAccount: Bool {
        guard let accountRelay = accountRelayURL else { return false }
        return RelayURLs.normalize(settings.relayURL).caseInsensitiveCompare(accountRelay) == .orderedSame
    }

    /// Live one-tap connector URL from the current relay connection (nil while offline).
    var liveConnectURL: String? {
        guard settings.relayEnabled else { return nil }
        if case .connected(let url) = relayState, let url, !url.isEmpty { return url }
        return nil
    }

    /// Connector URL to show on Home (live URL, or cached URL for the active relay while reconnecting).
    var connectURL: String? {
        guard settings.relayEnabled, !settings.relayURL.isEmpty else { return nil }
        if let live = liveConnectURL { return live }
        return settings.lastConnectURL(for: identity.deviceId, relay: settings.relayURL)
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
        let api: AccountApi? = Settings.apiURL.map { AccountClient(baseURL: $0) }
        accounts = AccountManager(api: api, storage: KeychainAccountStore())
        accountSession = accounts.session
        signInState = accounts.signInState
        lastAccountSessionTag = accounts.sessionTag()
        startupError = error
        accessEnabledFlag.value = settings.accessEnabled
        wire()
        accounts.onSessionChange = { [weak self] session in
            Task { @MainActor in
                guard let self else { return }
                self.accountSession = session
                let nextTag = self.accounts.sessionTag()
                if nextTag != self.lastAccountSessionTag {
                    self.lastAccountSessionTag = nextTag
                    if self.settings.relayEnabled && self.relayUsesAccount {
                        self.restart()
                    }
                }
            }
        }
        accounts.onSignInStateChange = { [weak self] state in
            Task { @MainActor in self?.signInState = state }
        }
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

    // MARK: Connection & Accounts

    /// First run of a build with a default relay: turns it on unless already decided, a setup link is waiting,
    /// or the default relay needs a sign-in first (in which case onboarding / Connect offers "Continue with Google").
    func applyDefaultRelayIfNeeded() {
        guard !settings.defaultRelayDecided, pendingConfigure == nil, !signInNeeded else { return }
        guard let defaultURL = defaultRelayURL, !DefaultRelay.somethingConfigured(relayURL: settings.relayURL) else {
            settings.defaultRelayDecided = true
            return
        }
        settings.relayEnabled = true
        settings.relayURL = defaultURL
        settings.accessKey = nil
        settings.relayFromDefault = true
        settings.defaultRelayDecided = true
        restart()
    }

    /// "Go online" / "Use the default relay": turns on the build's default relay.
    func useDefaultRelay() {
        guard let defaultURL = defaultRelayURL else { return }
        settings.relayEnabled = true
        settings.relayURL = defaultURL
        settings.accessKey = nil
        settings.relayFromDefault = true
        settings.defaultRelayDecided = true
        restart()
    }

    /// Saves a custom relay URL (and optional access key), turns the relay on and connects.
    func connectRelay(url: String, key: String?) {
        let normalized = RelayURLs.normalize(url)
        let cleanKey = key?.trimmingCharacters(in: .whitespacesAndNewlines)
        settings.relayEnabled = true
        settings.relayURL = normalized
        settings.accessKey = (cleanKey?.isEmpty == false) ? cleanKey : nil
        settings.relayFromDefault = false
        settings.defaultRelayDecided = true
        restart()
    }

    /// Accepts a confirmed `droidhands://configure` or pasted setup link.
    func applyConfigure(_ request: ConfigureRequest) {
        let sameRelay = RelayURLs.normalize(settings.relayURL).caseInsensitiveCompare(request.relayURL) == .orderedSame
        let key = request.accessKey ?? (sameRelay ? settings.accessKey : nil)
        settings.relayEnabled = true
        settings.relayURL = request.relayURL
        settings.accessKey = key
        settings.relayFromDefault = false
        settings.defaultRelayDecided = true
        restart()
        showBanner("Connecting to \(request.host)…")
    }

    /// Turns off the relay connection without deleting its address.
    func disableRelay() {
        settings.relayEnabled = false
        stop()
    }

    /// Starts Google sign-in via `ASWebAuthenticationSession`. [useRelay]: go online with the default relay after.
    func startSignIn(useRelay: Bool = true) {
        accounts.clearSignInError()
        guard let url = accounts.startSignIn(useRelay: useRelay) else { return }
        accountBrowser.open(url: url) { [weak self] outcome in
            guard let self else { return }
            switch outcome {
            case .callback(let callbackURL):
                Task { await self.completeSignIn(url: callbackURL) }
            case .cancelled:
                break
            case .failed(let message):
                self.showBanner(message)
            }
        }
    }

    /// Finishes a sign-in from its `droidhands://signed-in` callback URL.
    func completeSignIn(url: URL) async {
        let outcome = await accounts.completeSignIn(url)
        guard case .signedIn(let useRelay) = outcome else { return }
        if useRelay, defaultRelayURL != nil {
            useDefaultRelay()
        } else if settings.relayEnabled && relayUsesAccount {
            restart()
        }
    }

    /// Re-reads the account profile; drops the session if signed out elsewhere.
    func refreshAccount() {
        Task { await accounts.refresh() }
    }

    /// Signs out of the DroidHands account and turns the hosted relay off.
    func signOut() async {
        guard !accountBusy else { return }
        accountBusy = true
        defer { accountBusy = false }
        await accounts.signOut()
        afterSignOut()
        showBanner("Signed out")
    }

    /// Deletes the DroidHands account for good, then signs out.
    func deleteAccount() async {
        guard !accountBusy else { return }
        accountBusy = true
        defer { accountBusy = false }
        if let failure = await accounts.deleteAccount() {
            showBanner("Couldn't delete your account: \(failure)")
        } else {
            afterSignOut()
            showBanner("Account deleted")
        }
    }

    private func afterSignOut() {
        if settings.relayEnabled && relayUsesAccount {
            settings.relayEnabled = false
            stop()
        }
    }

    func start() {
        guard settings.relayEnabled, !settings.relayURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            stop()
            return
        }
        guard relay == nil else { return }
        let relayURL = settings.relayURL
        let config = RelayClient.Config(relayURL: relayURL, accessKey: settings.accessKey,
                                        deviceName: UIDevice.current.name, appVersion: AppInfo.version)
        let tokenProvider: (() async -> RelayAccountToken)? = relayUsesAccount
            ? { [accounts] in await accounts.relayToken() }
            : nil
        let client = RelayClient(identity: identity, config: config, server: server, pairing: self, accountToken: tokenProvider)
        let deviceId = identity.deviceId
        client.onState = { [weak self] state in
            Task { @MainActor in
                guard let self else { return }
                self.relayState = state
                if case .connected(let url?) = state {
                    self.settings.setLastConnectURL(url, for: deviceId, relay: relayURL)
                }
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

    func showBanner(_ message: String) {
        bannerTask?.cancel()
        bannerMessage = message
        bannerTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            guard !Task.isCancelled else { return }
            self?.bannerMessage = nil
        }
    }

    func dismissBanner() {
        bannerTask?.cancel()
        bannerMessage = nil
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
        applyDefaultRelayIfNeeded()
        refreshAccount()
        if settings.relayEnabled {
            if relay == nil || isAsleep { start() } else { relay?.reconnectIfWaiting() }
        }
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

    // MARK: Deep links

    func handle(url: URL) {
        if CallbackRouter.shared.handle(url) { return }
        if SignInLinks.isSignInLink(url) {
            Task { await completeSignIn(url: url) }
            return
        }
        if let code = ApproveLinks.code(of: url) {
            openApproveLink(code: code)
            return
        }
        if ConfigureLinks.isConfigureLink(url) {
            offerConfigure(ConfigureLinks.parse(url.absoluteString))
            return
        }
    }

    /// Parses a pasted setup link (`…/setup#key=…`, `droidhands://configure…`) and asks to confirm.
    func offerSetupLink(_ raw: String) {
        offerConfigure(SetupLinkParser.parse(raw))
    }

    private func offerConfigure(_ result: ConfigureLinkResult) {
        switch result {
        case .valid(let request):
            pendingConfigure = request
        case .invalid(let message):
            showBanner(message)
        }
    }

    /// A `droidhands://approve[?code=K7Q2]` link from the page an agent sent: brings the matching prompt
    /// to the front, waiting briefly since a cold start may still be reconnecting when the link arrives.
    func openApproveLink(code: String) {
        approveWaitTask?.cancel()
        approveWaitTask = Task { [weak self] in
            let deadline = Date().addingTimeInterval(8)
            while let self, !Task.isCancelled {
                if let match = ApproveLinks.pick(from: self.pairings.map(\.request), code: code, now: Date()) {
                    if let idx = self.pairings.firstIndex(where: { $0.id == match.id }), idx != 0 {
                        let prompt = self.pairings.remove(at: idx)
                        self.pairings.insert(prompt, at: 0)
                    }
                    return
                }
                if Date() >= deadline { break }
                try? await Task.sleep(nanoseconds: 200_000_000)
            }
            guard let self, !Task.isCancelled else { return }
            self.showBanner(
                code.isEmpty
                    ? "No agent is waiting for approval. Ask your agent to try again."
                    : "No request with code \(code) is waiting. Ask your agent to try again."
            )
        }
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

/// The app's settings, in UserDefaults (nothing secret: the device secret and account session are in the Keychain).
@Observable
final class Settings {
    @ObservationIgnored private let defaults = UserDefaults.standard

    static var defaultRelay: String? {
        #if DEBUG
        if let env = ProcessInfo.processInfo.environment["DH_DEFAULT_RELAY_URL"] {
            return DefaultRelay.url(env)
        }
        #endif
        let v = Bundle.main.object(forInfoDictionaryKey: "DroidHandsDefaultRelayURL") as? String
        return DefaultRelay.url(v)
    }

    static var apiURL: String? {
        #if DEBUG
        if let env = ProcessInfo.processInfo.environment["DH_API_URL"] {
            return parseAPIURL(env)
        }
        #endif
        let v = Bundle.main.object(forInfoDictionaryKey: "DroidHandsAPIURL") as? String
        return parseAPIURL(v)
    }

    private static func parseAPIURL(_ raw: String?) -> String? {
        guard let raw else { return nil }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty,
              let c = URLComponents(string: trimmed),
              let scheme = c.scheme?.lowercased(), ["https", "http"].contains(scheme),
              let host = c.host, !host.isEmpty else { return nil }
        return RelayURLs.normalize(trimmed)
    }

    static func isValidRelay(_ s: String) -> Bool {
        switch RelayURLs.check(s) {
        case .ok, .insecure: return true
        case .empty, .invalid: return false
        }
    }

    /// Links from outside may only name https relays (or local/private ones).
    static func isEncryptedOrLocal(_ s: String) -> Bool {
        if case .ok = RelayURLs.check(s) { return true }
        return false
    }

    var relayEnabled: Bool {
        didSet { defaults.set(relayEnabled, forKey: "relayEnabled") }
    }

    var relayURL: String {
        didSet { defaults.set(relayURL, forKey: "relayURL") }
    }

    var accessKey: String? {
        didSet { defaults.set(accessKey, forKey: "accessKey") }
    }

    var relayFromDefault: Bool {
        didSet { defaults.set(relayFromDefault, forKey: "relayFromDefault") }
    }

    var defaultRelayDecided: Bool {
        didSet { defaults.set(defaultRelayDecided, forKey: "defaultRelayDecided") }
    }

    var accessEnabled: Bool {
        didSet { defaults.set(accessEnabled, forKey: "accessEnabled") }
    }

    var onboarded: Bool {
        didSet { defaults.set(onboarded, forKey: "onboarded") }
    }

    init() {
        if defaults.object(forKey: "relayEnabled") == nil {
            if let existing = defaults.string(forKey: "relayURL")?.trimmingCharacters(in: .whitespacesAndNewlines), !existing.isEmpty {
                let normalized = RelayURLs.normalize(existing)
                relayEnabled = true
                relayURL = normalized
                relayFromDefault = (Settings.defaultRelay != nil && normalized.caseInsensitiveCompare(Settings.defaultRelay!) == .orderedSame)
                defaultRelayDecided = true
            } else {
                relayEnabled = false
                relayURL = ""
                relayFromDefault = false
                defaultRelayDecided = false
            }
        } else {
            relayEnabled = defaults.bool(forKey: "relayEnabled")
            relayURL = defaults.string(forKey: "relayURL") ?? ""
            relayFromDefault = defaults.bool(forKey: "relayFromDefault")
            defaultRelayDecided = defaults.bool(forKey: "defaultRelayDecided")
        }
        accessKey = defaults.string(forKey: "accessKey")
        accessEnabled = defaults.object(forKey: "accessEnabled") as? Bool ?? true
        onboarded = defaults.bool(forKey: "onboarded")
    }

    /// The last connection URL the relay announced for this identity and relay, so it shows while reconnecting.
    func lastConnectURL(for deviceId: String, relay: String) -> String? {
        defaults.string(forKey: connectURLKey(deviceId: deviceId, relay: relay))
    }

    func setLastConnectURL(_ url: String, for deviceId: String, relay: String) {
        defaults.set(url, forKey: connectURLKey(deviceId: deviceId, relay: relay))
    }

    private func connectURLKey(deviceId: String, relay: String) -> String {
        "connectURL.\(deviceId).\(RelayURLs.normalize(relay))"
    }
}
