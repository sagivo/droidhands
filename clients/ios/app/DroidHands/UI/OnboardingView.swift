import SwiftUI
import UserNotifications

/// First launch: a concise intro, then one-tap permissions. Both steps can be skipped.
struct OnboardingView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var phase
    @State private var page = 0
    @State private var refresh = 0
    @State private var notifications: UNAuthorizationStatus = .notDetermined
    @State private var requestingAll = false

    private var notificationsGranted: Bool {
        notifications == .authorized || notifications == .provisional || notifications == .ephemeral
    }

    private var hasUnasked: Bool {
        Permission.allCases.contains { $0.status == .notAsked } || notifications == .notDetermined
    }

    private var allGranted: Bool {
        Permission.allCases.allSatisfy { $0.status == .granted } && notificationsGranted
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar
            TabView(selection: $page) {
                welcome.tag(0)
                permissions.tag(1)
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .animation(.default, value: page)
            bottomBar
        }
        .background(Color(.systemGroupedBackground))
        .task { await readNotifications() }
        .onChange(of: phase) { _, newPhase in
            if newPhase == .active {
                refresh += 1
                Task { await readNotifications() }
            }
        }
    }

    private var topBar: some View {
        HStack(spacing: 12) {
            HStack(spacing: 6) {
                ForEach(0..<2, id: \.self) { index in
                    Capsule()
                        .fill(index <= page ? Palette.brand : Palette.brand.opacity(0.16))
                        .frame(height: 4)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Step \(page + 1) of 2")
            Button("Skip") { model.settings.onboarded = true }
                .font(.subheadline.weight(.medium))
                .foregroundStyle(Palette.brand)
        }
        .padding(.horizontal, 20)
        .padding(.top, 12)
        .padding(.bottom, 4)
    }

    private var bottomBar: some View {
        VStack(spacing: 8) {
            if page == 0 {
                Button {
                    page = 1
                } label: {
                    Text("Continue").frame(maxWidth: .infinity).padding(.vertical, 6)
                }
                .buttonStyle(.borderedProminent)
                .buttonBorderShape(.capsule)
                .controlSize(.large)
            } else if hasUnasked {
                Button {
                    Task { await requestAll() }
                } label: {
                    Text(requestingAll ? "Requesting…" : "Allow all").frame(maxWidth: .infinity).padding(.vertical, 6)
                }
                .buttonStyle(.borderedProminent)
                .buttonBorderShape(.capsule)
                .controlSize(.large)
                .disabled(requestingAll)

                Button("Get started") { model.settings.onboarded = true }
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.secondary)
                    .padding(.top, 2)
            } else {
                Button {
                    model.settings.onboarded = true
                } label: {
                    Text("Get started").frame(maxWidth: .infinity).padding(.vertical, 6)
                }
                .buttonStyle(.borderedProminent)
                .buttonBorderShape(.capsule)
                .controlSize(.large)
            }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 14)
    }

    private var welcome: some View {
        ScrollView {
            VStack(spacing: 24) {
                VStack(spacing: 12) {
                    Image("Logo")
                        .resizable()
                        .frame(width: 72, height: 72)
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .padding(.top, 24)
                    Text("Let your AI agents use your iPhone")
                        .font(.title.weight(.bold))
                        .multilineTextAlignment(.center)
                    Text("Connect Claude, ChatGPT, Grok or any AI agent to your calendar, reminders and contacts.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .padding(.horizontal, 8)

                VStack(spacing: 0) {
                    Point(symbol: "checkmark.shield.fill", color: .green, title: "You approve every agent", text: "Choose what it can access, or revoke it anytime.")
                    Divider().padding(.leading, 56)
                    Point(symbol: "lock.fill", color: Palette.brand, title: "Private by design", text: "No account. Requests go straight to this iPhone.")
                    Divider().padding(.leading, 56)
                    Point(symbol: "iphone", color: .blue, title: "Keep DroidHands open", text: "iPhone lets DroidHands act while the app is on screen.")
                }
                .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 20)
        }
    }

    private var permissions: some View {
        List {
            Section {
                VStack(spacing: 6) {
                    Text(allGranted ? "Permissions ready" : "Allow permissions")
                        .font(.title2.weight(.bold))
                    Text("Choose what agents may use. Change anytime in Settings.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 4)
                .listRowBackground(Color.clear)
            }
            Section {
                ForEach(Permission.allCases) { p in
                    HStack {
                        TileRow(symbol: p.symbol, color: color(p), title: p.title, subtitle: p.purpose)
                        Spacer()
                        switch p.status {
                        case .granted:
                            Image(systemName: "checkmark.circle.fill").foregroundStyle(Palette.ok)
                        case .notAsked:
                            Button("Allow") { Task { _ = await p.request(); refresh += 1 } }
                                .buttonStyle(.bordered).buttonBorderShape(.capsule)
                                .disabled(requestingAll)
                        case .limited, .denied:
                            Button("Settings") { openURL(URL(string: UIApplication.openSettingsURLString)!) }
                                .buttonStyle(.bordered).buttonBorderShape(.capsule)
                        }
                    }
                }
                HStack {
                    TileRow(symbol: "bell.badge.fill", color: .red, title: "Notifications", subtitle: "When an agent waits for you")
                    Spacer()
                    if notificationsGranted {
                        Image(systemName: "checkmark.circle.fill").foregroundStyle(Palette.ok)
                    } else if notifications == .notDetermined {
                        Button("Allow") { Task { await requestNotifications() } }
                            .buttonStyle(.bordered).buttonBorderShape(.capsule)
                            .disabled(requestingAll)
                    } else {
                        Button("Settings") { openURL(URL(string: UIApplication.openSettingsURLString)!) }
                            .buttonStyle(.bordered).buttonBorderShape(.capsule)
                    }
                }
            }
            .id(refresh)
        }
        .listStyle(.insetGrouped)
    }

    private func color(_ p: Permission) -> Color {
        switch p {
        case .contacts: return .gray
        case .calendar: return .red
        case .reminders: return .orange
        case .location: return .blue
        }
    }

    private func requestAll() async {
        requestingAll = true
        defer { requestingAll = false }
        for p in Permission.allCases where p.status == .notAsked {
            _ = await p.request()
            refresh += 1
        }
        if notifications == .notDetermined {
            await requestNotifications()
        }
    }

    private func requestNotifications() async {
        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
        await readNotifications()
    }

    private func readNotifications() async {
        notifications = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
    }
}

private struct Point: View {
    let symbol: String
    let color: Color
    let title: String
    let text: String

    var body: some View {
        TileRow(symbol: symbol, color: color, title: title, subtitle: text)
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
    }
}
