import SwiftUI
import UserNotifications

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var confirmRevokeAll = false
    @State private var confirmReset = false

    var body: some View {
        @Bindable var model = model
        NavigationStack {
            List {
                Section {
                    NavigationLink { ConnectionSettingsView() } label: {
                        TileRow(symbol: "antenna.radiowaves.left.and.right", color: Palette.brand, title: "Connection",
                                subtitle: "\(model.relayState.title) · \(URL(string: model.settings.relayURL)?.host ?? model.settings.relayURL)")
                    }
                    NavigationLink { PermissionsView() } label: {
                        TileRow(symbol: "checkmark.shield.fill", color: Palette.ok, title: "Permissions",
                                subtitle: "What DroidHands may use on this iPhone")
                    }
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

    private var changed: Bool {
        relay.trimmingCharacters(in: .whitespaces) != model.settings.relayURL || key.trimmingCharacters(in: .whitespaces) != (model.settings.accessKey ?? "")
    }

    var body: some View {
        Form {
            Section {
                LabeledContent("Status") { StatusPill(text: model.relayState.title, color: model.relayState.color, dot: true) }
                if let detail = model.relayState.detail { Text(detail).font(.subheadline).foregroundStyle(.secondary) }
                Button("Reconnect") { model.restart() }
            }
            Section {
                TextField("https://relay.example.com", text: $relay)
                    .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                SecureField("Access key (only if your relay needs one)", text: $key)
            } header: {
                Text("Relay")
            } footer: {
                Text("DroidHands connects out to this relay, which passes your agents' requests to this iPhone. It never stores them. The default is \(URL(string: Settings.defaultRelay)?.host ?? Settings.defaultRelay); you can run your own (see the source code).")
            }
            Section {
                Button("Save and reconnect") {
                    model.applyRelay(url: relay.trimmingCharacters(in: .whitespaces), key: key.trimmingCharacters(in: .whitespaces).isEmpty ? nil : key.trimmingCharacters(in: .whitespaces))
                }
                .disabled(!changed || !Settings.isValidRelay(relay))
                if model.settings.relayURL != Settings.defaultRelay {
                    Button("Use the default relay") {
                        relay = Settings.defaultRelay
                        key = ""
                        model.applyRelay(url: Settings.defaultRelay, key: nil)
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
