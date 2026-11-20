import SwiftUI
import UserNotifications

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var confirmRevokeAll = false
    @State private var confirmReset = false

    private var connectionSubtitle: String {
        guard model.settings.relayEnabled, !model.settings.relayURL.isEmpty else { return "Off" }
        let host = RelayURLs.host(of: model.settings.relayURL) ?? model.settings.relayURL
        return "\(model.relayState.title) · \(host)"
    }

    var body: some View {
        @Bindable var model = model
        NavigationStack {
            List {
                Section {
                    NavigationLink { ConnectionSettingsView() } label: {
                        TileRow(symbol: "antenna.radiowaves.left.and.right", color: Palette.brand, title: "Connection",
                                subtitle: connectionSubtitle)
                    }
                    NavigationLink { PermissionsView() } label: {
                        TileRow(symbol: "checkmark.shield.fill", color: Palette.ok, title: "Permissions",
                                subtitle: "What DroidHands may use on this iPhone")
                    }
                }
                if model.relayNeedsAccount {
                    AccountSettingsSection()
                }
                Section {
                    Toggle(isOn: $model.accessEnabled) {
                        TileRow(symbol: "pause.fill", color: Palette.warning, title: "Agents can use this iPhone")
                    }
                    Button(role: .destructive) { confirmRevokeAll = true } label: {
                        TileRow(symbol: "xmark.shield.fill", color: Palette.error, title: "Revoke all agents")
                    }
                    .disabled(model.activeAgents.isEmpty)
                } header: {
                    Text("Security")
                } footer: {
                    Text("Agents only get what you allowed when you connected them. Each agent's page has its access, tool rules and activity.")
                }
                Section {
                    LabeledContent("Version", value: "\(AppInfo.version) (\(AppInfo.build))")
                    Button {
                        model.settings.onboarded = false
                    } label: {
                        Label("Run setup again", systemImage: "arrow.counterclockwise")
                    }
                    Link(destination: URL(string: "https://droidhands.com")!) { Label("Website", systemImage: "globe") }
                    Link(destination: URL(string: "https://github.com/sagivo/droidhands/blob/main/docs/PRIVACY.md")!) { Label("Privacy", systemImage: "hand.raised") }
                    Link(destination: URL(string: "https://github.com/sagivo/droidhands")!) { Label("Source code (Apache 2.0)", systemImage: "chevron.left.forwardslash.chevron.right") }
                } header: {
                    Text("About")
                }
                Section {
                    Button("Reset this iPhone's identity", role: .destructive) { confirmReset = true }
                } footer: {
                    Text("Makes a new connection URL and removes every agent. They have to connect again.")
                }
            }
            .navigationTitle("Settings")
            .confirmationDialog("Revoke every agent?", isPresented: $confirmRevokeAll, titleVisibility: .visible) {
                Button("Revoke all", role: .destructive) { model.revokeAll() }
            } message: {
                Text("None of them can use this iPhone until you connect them again.")
            }
            .confirmationDialog("Reset identity?", isPresented: $confirmReset, titleVisibility: .visible) {
                Button("Reset", role: .destructive) { model.resetIdentity() }
            } message: {
                Text("Your connection URL changes and every agent is removed.")
            }
        }
    }
}

struct ConnectionSettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var relay = ""
    @State private var key = ""
    @State private var showKey = false

    private var check: RelayURLCheck { RelayURLs.check(relay) }
    private var isSetupLink: Bool { SetupLinkParser.isSetupLink(relay) }
    private var usable: Bool {
        if isSetupLink { return true }
        switch check {
        case .ok, .insecure: return true
        case .empty, .invalid: return false
        }
    }

    private var changed: Bool {
        let trimmedRelay = RelayURLs.normalize(relay)
        let trimmedKey = key.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmedRelay != model.settings.relayURL || trimmedKey != (model.settings.accessKey ?? "") || !model.settings.relayEnabled
    }

    var body: some View {
        Form {
            Section {
                LabeledContent("Status") { StatusPill(text: model.relayState.title, color: model.relayState.color, dot: true) }
                if let detail = model.relayState.detail { Text(detail).font(.subheadline).foregroundStyle(.secondary) }
                if model.settings.relayEnabled {
                    Button("Reconnect") { model.restart() }
                    Button("Disconnect relay", role: .destructive) { model.disableRelay() }
                }
            }
            if model.relayState.isAccountRequired {
                Section { AccountRequiredNotice() }
                    .listRowInsets(EdgeInsets()).listRowBackground(Color.clear)
            }
            if model.canOfferDefaultRelay, let defaultURL = model.defaultRelayURL {
                let host = RelayURLs.host(of: defaultURL) ?? defaultURL
                Section {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(model.signInNeeded
                             ? "Sign in with Google for a free DroidHands account on \(host)."
                             : "Use \(host), the relay this app comes with.")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        DefaultRelayButton(label: "Use the default relay")
                    }
                    .padding(.vertical, 4)
                } header: {
                    Text("Connect using DroidHands")
                } footer: {
                    Text(AccountCopy.defaultRelayNote)
                }
            }
            Section {
                TextField(text: $relay, prompt: Text(verbatim: "https://relay.example.com")) {
                    Text("Relay URL")
                }
                    .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                if isSetupLink {
                    Text("A setup link: tap Save and reconnect to review it.")
                        .font(.footnote)
                        .foregroundStyle(Palette.brand)
                } else if case .invalid(let message) = check {
                    Text(message).font(.footnote).foregroundStyle(Palette.error)
                } else if case .insecure(_, let message) = check {
                    Notice(kind: .danger, title: "Plain HTTP", message: message)
                }
                HStack {
                    if showKey {
                        TextField("Access key (only if your relay needs one)", text: $key)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                    } else {
                        SecureField("Access key (only if your relay needs one)", text: $key)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                    }
                    if !key.isEmpty {
                        Button(showKey ? "Hide" : "Show") { showKey.toggle() }
                            .font(.subheadline.weight(.medium))
                    }
                }
            } header: {
                Text("Relay")
            } footer: {
                let defaultHost = model.defaultRelayURL.flatMap(RelayURLs.host(of:)) ?? "connect.droidhands.com"
                Text("DroidHands connects out to this relay, which passes your agents' requests to this iPhone. It never stores them. The default is \(defaultHost); you can run your own (see the source code).")
            }
            Section {
                Button("Save and reconnect") {
                    if isSetupLink {
                        model.offerSetupLink(relay)
                    } else {
                        let trimmedKey = key.trimmingCharacters(in: .whitespacesAndNewlines)
                        model.connectRelay(url: relay, key: trimmedKey.isEmpty ? nil : trimmedKey)
                    }
                }
                .disabled(!changed || !usable)
                if let defaultURL = model.defaultRelayURL, !model.defaultRelayInUse && !model.canOfferDefaultRelay {
                    Button(model.signInNeeded ? "Switch to DroidHands relay (Sign in with Google)" : "Use the default relay") {
                        if model.signInNeeded {
                            model.startSignIn(useRelay: true)
                        } else {
                            relay = defaultURL
                            key = ""
                            model.useDefaultRelay()
                        }
                    }
                }
            }
            Section {
                LabeledContent("Device ID", value: model.identity.deviceId).font(.footnote.monospaced())
            } footer: {
                Text("Derived from a secret that never leaves this iPhone's Keychain.")
            }
        }
        .navigationTitle("Connection")
        .onAppear {
            relay = model.settings.relayURL
            key = model.settings.accessKey ?? ""
        }
        .onChange(of: model.settings.relayURL) { _, updated in
            relay = updated
            key = model.settings.accessKey ?? ""
        }
    }
}

struct PermissionsView: View {
    @State private var refresh = 0
    @State private var notifications: UNAuthorizationStatus = .notDetermined
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var phase

    var body: some View {
        List {
            Section {
                ForEach(Permission.allCases) { p in
                    HStack {
                        TileRow(symbol: p.symbol, color: color(p), title: p.title, subtitle: p.purpose)
                        Spacer()
                        switch p.status {
                        case .granted: StatusPill(text: "On", color: Palette.ok)
                        case .notAsked:
                            Button("Allow") { Task { _ = await p.request(); refresh += 1 } }
                                .buttonStyle(.bordered).buttonBorderShape(.capsule)
                        case .limited, .denied:
                            Button("Settings") { openURL(URL(string: UIApplication.openSettingsURLString)!) }
                                .buttonStyle(.bordered).buttonBorderShape(.capsule)
                        }
                    }
                }
                HStack {
                    TileRow(symbol: "bell.badge.fill", color: .red, title: "Notifications", subtitle: "Tell you when an agent waits for you")
                    Spacer()
                    if notifications == .authorized || notifications == .provisional {
                        StatusPill(text: "On", color: Palette.ok)
                    } else if notifications == .notDetermined {
                        Button("Allow") {
                            Task {
                                _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
                                await readNotifications()
                            }
                        }
                        .buttonStyle(.bordered).buttonBorderShape(.capsule)
                    } else {
                        Button("Settings") { openURL(URL(string: UIApplication.openSettingsURLString)!) }
                            .buttonStyle(.bordered).buttonBorderShape(.capsule)
                    }
                }
            } footer: {
                Text("Agents only use what you allowed here and in their access. Messages and calls need no permission: you tap Send or Call each time.")
            }
            .id(refresh)
        }
        .navigationTitle("Permissions")
        .task { await readNotifications() }
        .onChange(of: phase) { _, p in if p == .active { refresh += 1; Task { await readNotifications() } } }
    }

    private func color(_ p: Permission) -> Color {
        switch p {
        case .contacts: return .gray
        case .calendar: return .red
        case .reminders: return .orange
        case .location: return .blue
        }
    }

    private func readNotifications() async {
        notifications = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
    }
}
