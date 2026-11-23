import SwiftUI
import UserNotifications

/// Pages of the first-run wizard, in order (mirrors Android's `OnboardingStep`).
enum OnboardingStep: String, CaseIterable, Identifiable {
    case welcome
    case permissions
    case connect
    case agent

    var id: String { rawValue }

    var title: String {
        switch self {
        case .welcome: return "Welcome"
        case .permissions: return "Permissions"
        case .connect: return "Connect"
        case .agent: return "Add an agent"
        }
    }

    static func steps(defaultRelayInUse: Bool) -> [OnboardingStep] {
        defaultRelayInUse ? [.welcome, .permissions, .agent] : OnboardingStep.allCases
    }

    static func next(after step: OnboardingStep, in steps: [OnboardingStep]) -> OnboardingStep? {
        guard let i = steps.firstIndex(of: step) else { return .agent }
        return i + 1 < steps.count ? steps[i + 1] : nil
    }

    static func previous(before step: OnboardingStep, in steps: [OnboardingStep]) -> OnboardingStep? {
        guard let i = steps.firstIndex(of: step) else { return .agent }
        return i > 0 ? steps[i - 1] : nil
    }
}

/// First launch: Welcome → Permissions → (Connect) → Add your first agent. Every step can be skipped,
/// and the wizard can be reopened from Settings › About.
struct OnboardingView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var phase
    @State private var step: OnboardingStep = .welcome
    @State private var visitedConnect = false
    @State private var showMoreConnect = false
    @State private var showManualToken = false
    @State private var refresh = 0
    @State private var notifications: UNAuthorizationStatus = .notDetermined
    @State private var requestingAll = false

    private var steps: [OnboardingStep] {
        OnboardingStep.steps(defaultRelayInUse: model.defaultRelayInUse && !visitedConnect)
    }

    private var notificationsGranted: Bool {
        notifications == .authorized || notifications == .provisional || notifications == .ephemeral
    }

    private var hasUnasked: Bool {
        Permission.allCases.contains { $0.status == .notAsked } || notifications == .notDetermined
    }

    private var allGranted: Bool {
        Permission.allCases.allSatisfy { $0.status == .granted } && notificationsGranted
    }

    private var connected: Bool { model.relayState.isConnected }
    private var agentAdded: Bool { !model.activeAgents.isEmpty }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                topBar
                Group {
                    switch step {
                    case .welcome: welcome
                    case .permissions: permissions
                    case .connect: connectStep
                    case .agent: agentStep
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                bottomBar
            }
            .background(Color(.systemGroupedBackground))
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(isPresented: $showMoreConnect) {
                MoreConnectOptionsView(onContinue: {
                    showMoreConnect = false
                    if step == .connect { go(.agent) }
                })
            }
            .sheet(isPresented: $showManualToken) { ManualTokenView() }
        }
        .task { await readNotifications() }
        .onChange(of: step) { _, newStep in
            if newStep == .connect { visitedConnect = true }
        }
        .onChange(of: phase) { _, newPhase in
            if newPhase == .active {
                refresh += 1
                Task { await readNotifications() }
            }
        }
    }

    private func go(_ target: OnboardingStep) {
        withAnimation(.easeInOut(duration: 0.2)) { step = target }
    }

    private var topBar: some View {
        let total = steps.count
        let number = (steps.firstIndex(of: step) ?? (steps.firstIndex(of: .agent) ?? 0)) + 1
        return HStack(spacing: 12) {
            HStack(spacing: 6) {
                ForEach(0..<total, id: \.self) { index in
                    Capsule()
                        .fill(index + 1 <= number ? Palette.brand : Palette.brand.opacity(0.16))
                        .frame(height: 4)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Step \(number) of \(total) · \(step.title)")
            Button("Skip") { model.settings.onboarded = true }
                .font(.subheadline.weight(.medium))
                .foregroundStyle(Palette.brand)
        }
        .padding(.horizontal, 20)
        .padding(.top, 12)
        .padding(.bottom, 4)
    }

    private var bottomBar: some View {
        let prev = OnboardingStep.previous(before: step, in: steps)
        let next = OnboardingStep.next(after: step, in: steps)
        return VStack(spacing: 8) {
            switch step {
            case .welcome:
                Button {
                    go(.permissions)
                } label: {
                    Text("Get started").fontWeight(.semibold).frame(maxWidth: .infinity).padding(.vertical, 6)
                }
                .buttonStyle(.borderedProminent)
                .buttonBorderShape(.capsule)
                .controlSize(.large)

            case .permissions:
                if hasUnasked {
                    Button {
                        Task { await requestAll() }
                    } label: {
                        Text(requestingAll ? "Requesting…" : "Allow all")
                            .fontWeight(.semibold).frame(maxWidth: .infinity).padding(.vertical, 6)
                    }
                    .buttonStyle(.borderedProminent)
                    .buttonBorderShape(.capsule)
                    .controlSize(.large)
                    .disabled(requestingAll)

                    HStack(spacing: 12) {
                        if let prev {
                            Button("Back") { go(prev) }
                                .font(.subheadline.weight(.medium))
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button("Continue") { if let next { go(next) } }
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(.secondary)
                    }
                    .padding(.top, 2)
                } else {
                    HStack(spacing: 10) {
                        if let prev {
                            Button("Back") { go(prev) }
                                .buttonStyle(.bordered)
                                .buttonBorderShape(.capsule)
                                .controlSize(.large)
                        }
                        Button {
                            if let next { go(next) }
                        } label: {
                            Text("Continue").fontWeight(.semibold).frame(maxWidth: .infinity).padding(.vertical, 6)
                        }
                        .buttonStyle(.borderedProminent)
                        .buttonBorderShape(.capsule)
                        .controlSize(.large)
                    }
                }

            case .connect:
                HStack(spacing: 10) {
                    if let prev {
                        Button("Back") { go(prev) }
                            .buttonStyle(.bordered)
                            .buttonBorderShape(.capsule)
                            .controlSize(.large)
                    }
                    if connected {
                        Button {
                            if let next { go(next) }
                        } label: {
                            Text("Continue").fontWeight(.semibold).frame(maxWidth: .infinity).padding(.vertical, 6)
                        }
                        .buttonStyle(.borderedProminent)
                        .buttonBorderShape(.capsule)
                        .controlSize(.large)
                    } else {
                        Button {
                            if let next { go(next) }
                        } label: {
                            Text("Skip for now").fontWeight(.semibold).frame(maxWidth: .infinity).padding(.vertical, 6)
                        }
                        .buttonStyle(.bordered)
                        .buttonBorderShape(.capsule)
                        .controlSize(.large)
                    }
                }

            case .agent:
                HStack(spacing: 10) {
                    if let prev {
                        Button("Back") { go(prev) }
                            .buttonStyle(.bordered)
                            .buttonBorderShape(.capsule)
                            .controlSize(.large)
                    }
                    if agentAdded {
                        Button {
                            model.settings.onboarded = true
                        } label: {
                            Text("Done").fontWeight(.semibold).frame(maxWidth: .infinity).padding(.vertical, 6)
                        }
                        .buttonStyle(.borderedProminent)
                        .buttonBorderShape(.capsule)
                        .controlSize(.large)
                    } else {
                        Button {
                            model.settings.onboarded = true
                        } label: {
                            Text("Finish later").fontWeight(.semibold).frame(maxWidth: .infinity).padding(.vertical, 6)
                        }
                        .buttonStyle(.bordered)
                        .buttonBorderShape(.capsule)
                        .controlSize(.large)
                    }
                }
            }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 14)
    }

    // MARK: Step 1 — Welcome

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
                    Point(symbol: "checkmark.shield.fill", color: .green, title: "You approve every agent",
                          text: "Choose what it can access, or revoke it in one tap.")
                    Divider().padding(.leading, 56)
                    Point(symbol: "bell.badge.fill", color: .orange, title: "Ask first when it matters",
                          text: "Confirm messages, calls and sensitive actions before they run.")
                    Divider().padding(.leading, 56)
                    Point(symbol: "iphone", color: .blue, title: "Keep DroidHands open",
                          text: "iPhone lets DroidHands act while the app is on screen.")
                }
                .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 20)
        }
    }

    // MARK: Step 2 — Permissions

    private var permissions: some View {
        List {
            Section {
                OnboardingHeader(
                    symbol: allGranted ? "checkmark.circle.fill" : "lock.fill",
                    color: allGranted ? Palette.ok : Palette.brand,
                    title: allGranted ? "Permissions ready" : "Allow permissions",
                    subtitle: "Choose what agents may use. Change anytime in Settings."
                )
            }
            .listRowBackground(Color.clear)

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

    // MARK: Step 3 — Connect

    private var connectStep: some View {
        List {
            if model.defaultRelayInUse {
                let host = RelayURLs.host(of: model.settings.relayURL) ?? model.settings.relayURL
                Section {
                    OnboardingHeader(
                        symbol: connected ? "checkmark.circle.fill" : "antenna.radiowaves.left.and.right",
                        color: connected ? Palette.ok : Palette.brand,
                        title: connected ? "You're online" : "Connect this iPhone",
                        subtitle: connected
                            ? "Agents reach this iPhone through \(host)."
                            : "Connecting through \(host) so your AI agents can reach this iPhone."
                    )
                }
                .listRowBackground(Color.clear)

                if connected {
                    let secure = model.settings.relayURL.lowercased().hasPrefix("https://")
                    Section {
                        TileRow(
                            symbol: secure ? "lock.fill" : "checkmark.circle.fill",
                            color: Palette.ok,
                            title: "Online through \(host)",
                            subtitle: secure ? "Encrypted connection" : nil
                        ) {
                            StatusPill(text: "Live", color: Palette.ok, dot: true)
                        }
                    }
                } else {
                    Section {
                        TileRow(
                            symbol: "antenna.radiowaves.left.and.right",
                            color: model.relayState.color,
                            title: model.relayState.title,
                            subtitle: model.relayState.detail ?? host
                        )
                    }
                    if model.relayState.isAccountRequired {
                        Section { AccountRequiredNotice() }
                            .listRowInsets(EdgeInsets()).listRowBackground(Color.clear)
                    }
                }

                Section {
                    Notice(kind: .info, title: "Safe by default", message: AccountCopy.defaultRelayNote, symbol: "lock.fill")
                }
                .listRowInsets(EdgeInsets()).listRowBackground(Color.clear)

                Section {
                    Button("More ways to connect") { showMoreConnect = true }
                        .frame(maxWidth: .infinity)
                }
                .listRowBackground(Color.clear)
            } else if connected {
                let host = RelayURLs.host(of: model.settings.relayURL) ?? "your relay"
                let secure = model.settings.relayURL.lowercased().hasPrefix("https://")
                Section {
                    OnboardingHeader(
                        symbol: "checkmark.circle.fill",
                        color: Palette.ok,
                        title: "Connect this iPhone",
                        subtitle: "AI agents can reach this iPhone from anywhere, on Wi-Fi or mobile data."
                    )
                }
                .listRowBackground(Color.clear)

                Section {
                    TileRow(
                        symbol: secure ? "lock.fill" : "checkmark.circle.fill",
                        color: Palette.ok,
                        title: "Connected to \(host)",
                        subtitle: secure ? "Encrypted connection" : nil
                    ) {
                        StatusPill(text: "Live", color: Palette.ok, dot: true)
                    }
                }
            } else if model.canOfferDefaultRelay, let offerURL = model.defaultRelayURL {
                let host = RelayURLs.host(of: offerURL) ?? offerURL
                Section {
                    OnboardingHeader(
                        symbol: "antenna.radiowaves.left.and.right",
                        color: Palette.brand,
                        title: "Connect this iPhone",
                        subtitle: "Let your AI agents reach this iPhone from anywhere, on Wi-Fi or mobile data."
                    )
                }
                .listRowBackground(Color.clear)

                if model.settings.relayEnabled || model.relayState != .stopped {
                    Section {
                        TileRow(
                            symbol: "antenna.radiowaves.left.and.right",
                            color: model.relayState.color,
                            title: model.relayState.title,
                            subtitle: model.relayState.detail ?? host
                        )
                    }
                }
                if model.relayState.isAccountRequired {
                    Section { AccountRequiredNotice() }
                        .listRowInsets(EdgeInsets()).listRowBackground(Color.clear)
                }

                Section {
                    VStack(alignment: .leading, spacing: 12) {
                        HStack(alignment: .top, spacing: 12) {
                            IconTile(symbol: "checkmark.circle.fill", color: .indigo)
                            VStack(alignment: .leading, spacing: 3) {
                                Text("Connect using DroidHands").font(.headline)
                                Text(model.signInNeeded
                                     ? "Sign in with Google for a free DroidHands account on \(host). Works with every agent — switch to your own relay anytime."
                                     : "Connect through \(host), the relay this app comes with. Works with every agent — switch to your own relay anytime.")
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        DefaultRelayButton()
                    }
                    .padding(.vertical, 4)
                }

                Section {
                    Button("Do it yourself") { showMoreConnect = true }
                        .frame(maxWidth: .infinity)
                }
                .listRowBackground(Color.clear)
            } else {
                Section {
                    OnboardingHeader(
                        symbol: "antenna.radiowaves.left.and.right",
                        color: Palette.brand,
                        title: "Connect this iPhone",
                        subtitle: "Your AI agents reach this iPhone through a small relay you host yourself, on Wi-Fi or mobile data."
                    )
                }
                .listRowBackground(Color.clear)

                if model.settings.relayEnabled || model.relayState != .stopped {
                    Section {
                        TileRow(
                            symbol: "antenna.radiowaves.left.and.right",
                            color: model.relayState.color,
                            title: model.relayState.title,
                            subtitle: model.relayState.detail ?? RelayURLs.host(of: model.settings.relayURL)
                        )
                    }
                }

                RelaySetupFormSection(initialURL: model.settings.relayURL, initialKey: model.settings.accessKey ?? "")
            }
        }
        .listStyle(.insetGrouped)
    }

    // MARK: Step 4 — Add your first agent

    private var agentStep: some View {
        List {
            Section {
                OnboardingHeader(
                    symbol: agentAdded ? "checkmark.circle.fill" : "paperplane.fill",
                    color: agentAdded ? Palette.ok : Palette.brand,
                    title: agentAdded ? "You're all set" : "Connect your first agent",
                    subtitle: agentAdded
                        ? "Ask your agent to do something on this iPhone."
                        : "Copy or share the message below with any AI agent, then tap Allow here."
                )
            }
            .listRowBackground(Color.clear)

            ConnectAgentSections(
                onUseToken: { showManualToken = true },
                onOpenMoreOptions: { showMoreConnect = true },
                onOpenConnectionSettings: { go(.connect) }
            )

            if !steps.contains(.connect) && !agentAdded {
                Section {
                    Button("More ways to connect") { showMoreConnect = true }
                        .frame(maxWidth: .infinity)
                }
                .listRowBackground(Color.clear)
            }
        }
        .listStyle(.insetGrouped)
    }

    // MARK: Helpers

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

private struct OnboardingHeader: View {
    let symbol: String
    let color: Color
    let title: String
    let subtitle: String

    var body: some View {
        VStack(spacing: 8) {
            ZStack {
                Circle().fill(color.opacity(0.14)).frame(width: 56, height: 56)
                Image(systemName: symbol).font(.title2.weight(.semibold)).foregroundStyle(color)
            }
            Text(title)
                .font(.title2.weight(.bold))
                .multilineTextAlignment(.center)
            Text(subtitle)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 4)
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
