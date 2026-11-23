import SwiftUI

struct HomeView: View {
    @Environment(AppModel.self) private var model
    let openAgents: () -> Void
    @State private var showConnect = false
    @State private var showMoreConnect = false

    var body: some View {
        @Bindable var model = model
        NavigationStack {
            List {
                if let error = model.startupError {
                    Section { Notice(kind: .danger, title: "Something needs attention", message: error) }
                        .listRowInsets(EdgeInsets()).listRowBackground(Color.clear)
                }
                if model.relayState.isAccountRequired {
                    Section { AccountRequiredNotice() }
                        .listRowInsets(EdgeInsets()).listRowBackground(Color.clear)
                }
                Section {
                    StatusHero(state: model.relayState, accessEnabled: model.accessEnabled, running: model.runningCalls)
                    Toggle(isOn: $model.accessEnabled) {
                        TileRow(symbol: model.accessEnabled ? "hand.raised.fill" : "pause.fill",
                                color: model.accessEnabled ? Palette.ok : Palette.warning,
                                title: "Agents can use this iPhone",
                                subtitle: model.accessEnabled ? "Turn off to pause every agent at once." : "Paused: agents are refused until you turn this on.")
                    }
                } footer: {
                    Text("iPhone lets DroidHands work only while the app is open. Keep it on screen while your agent works; it stays connected for a few seconds after you leave it.")
                }

                if !model.settings.relayEnabled && model.canOfferDefaultRelay, let offerURL = model.defaultRelayURL {
                    let host = RelayURLs.host(of: offerURL) ?? offerURL
                    Section {
                        VStack(alignment: .leading, spacing: 12) {
                            Text(model.signInNeeded
                                 ? "Sign in with Google for a free DroidHands account on \(host) so your AI agents can reach this iPhone."
                                 : "Connect through \(host) so your AI agents can reach this iPhone.")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                            DefaultRelayButton()
                            Button("Do it yourself") { showMoreConnect = true }
                                .font(.subheadline.weight(.medium))
                                .frame(maxWidth: .infinity)
                        }
                        .padding(.vertical, 4)
                    } header: {
                        Text("Connect using DroidHands")
                    }
                }

                Section {
                    Button { showConnect = true } label: {
                        TileRow(symbol: "plus", color: Palette.brand, title: "Connect an AI agent", subtitle: "Claude, ChatGPT or any other AI agent")
                    }
                    .foregroundStyle(.primary)
                    if !model.activeAgents.isEmpty {
                        Button(action: openAgents) {
                            TileRow(symbol: "person.2.fill", color: .blue, title: "\(model.activeAgents.count) agent\(model.activeAgents.count == 1 ? "" : "s") connected",
                                    subtitle: model.activeAgents.prefix(3).map(\.name).joined(separator: ", "))
                        }
                        .foregroundStyle(.primary)
                    }
                }

                if let url = model.connectURL {
                    Section {
                        CopyableCode(text: url, label: "Copy URL")
                    } header: {
                        Text("Your connection URL")
                    } footer: {
                        Text("Add it to an agent as a remote MCP server (custom connector), then tap Allow here. Anyone can see this URL; only agents you allow get in.")
                    }
                }

                let today = TodaySummary(activity: model.activity, usage: model.usage)
                if !model.activeAgents.isEmpty || today.actions > 0 {
                    Section {
                        Button(action: openAgents) { TodayTiles(summary: today) }
                            .buttonStyle(.plain)
                    } header: {
                        Text("Today")
                    } footer: {
                        Text(today.sentence)
                    }
                }

                Section {
                    let recent = model.activity.prefix(5)
                    if recent.isEmpty {
                        Text("Nothing yet. Every request from an agent will show up here.").foregroundStyle(.secondary)
                    } else {
                        ForEach(recent) { entry in
                            Button { model.openActivity() } label: { ActivityRow(entry: entry, smartTime: true) }
                                .foregroundStyle(.primary)
                        }
                    }
                } header: {
                    HStack {
                        Text("Recent activity")
                        Spacer()
                        if !model.activity.isEmpty {
                            Button("See all") { model.openActivity() }
                                .font(.subheadline)
                                .textCase(nil)
                        }
                    }
                }
            }
            .navigationTitle("DroidHands")
            .navigationDestination(isPresented: $showMoreConnect) {
                MoreConnectOptionsView(onContinue: { showMoreConnect = false })
            }
            .sheet(isPresented: $showConnect) { ConnectView(onOpenAgent: { _ in openAgents() }) }
        }
    }
}

struct StatusHero: View {
    let state: RelayClient.State
    let accessEnabled: Bool
    let running: Int

    var body: some View {
        HStack(spacing: 14) {
            ZStack {
                Circle().fill(color.opacity(0.14)).frame(width: 52, height: 52)
                Image(systemName: symbol).font(.title2.weight(.semibold)).foregroundStyle(color)
            }
            VStack(alignment: .leading, spacing: 3) {
                HStack {
                    Text(title).font(.title3.weight(.semibold))
                    if state.isConnected && accessEnabled { StatusPill(text: running > 0 ? "Working" : "Live", color: Palette.ok, dot: true) }
                }
                Text(subtitle).font(.subheadline).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 6)
    }

    private var color: Color { accessEnabled ? state.color : Palette.warning }
    private var symbol: String {
        if !accessEnabled { return "pause.circle.fill" }
        switch state {
        case .connected: return "checkmark.circle.fill"
        case .connecting: return "arrow.triangle.2.circlepath"
        case .waiting: return "wifi.exclamationmark"
        case .failed(_, let code) where code == RelayClient.accountRequiredCode: return "person.crop.circle.badge.exclamationmark"
        case .failed: return "xmark.octagon.fill"
        case .stopped: return "power"
        }
    }
    private var title: String { accessEnabled ? state.title : "Paused" }
    private var subtitle: String {
        if !accessEnabled { return "Agents are refused until you resume." }
        if running > 0 { return "An agent is working now." }
        if case .stopped = state { return "Connect to DroidHands or your own relay to go online." }
        return state.detail ?? (state.isConnected ? "Agents you allowed can reach this iPhone." : "Waiting for the relay.")
    }
}

/// Today's numbers on Home: actions, agents that acted, and how many were denied or failed.
struct TodaySummary {
    var actions = 0
    var agents = 0
    var denied = 0
    var failed = 0

    init(activity: [AuditEntry], usage: [String: TokenUsage], now: Date = Date()) {
        let today = activity.filter { $0.method == "tools/call" && $0.tokenId != nil && Calendar.current.isDate($0.timestamp, inSameDayAs: now) }
        var perAgent: [String: Int] = [:]
        for e in today { perAgent[e.tokenId!, default: 0] += 1 }
        // The log may have been cleared today; the usage counters still know.
        for (id, u) in usage where u.calls(on: now) > (perAgent[id] ?? 0) { perAgent[id] = u.calls(on: now) }
        actions = perAgent.values.reduce(0, +)
        agents = perAgent.filter { $0.value > 0 }.count
        denied = today.filter { $0.outcome.category == .denied }.count
        failed = today.filter { $0.outcome.category == .error }.count
    }

    /// "23 actions by 2 agents · 1 denied · 1 failed".
    var sentence: String {
        guard actions > 0 else { return "No agent activity today." }
        var s = "\(actions) action\(actions == 1 ? "" : "s") by \(agents) agent\(agents == 1 ? "" : "s")"
        if denied > 0 { s += " · \(denied) denied" }
        if failed > 0 { s += " · \(failed) failed" }
        return s
    }
}

private struct TodayTiles: View {
    let summary: TodaySummary

    var body: some View {
        HStack(spacing: 0) {
            tile(summary.actions, "actions", .primary)
            Divider()
            tile(summary.agents, summary.agents == 1 ? "agent" : "agents", .primary)
            Divider()
            tile(summary.denied + summary.failed, "denied or failed", summary.denied + summary.failed > 0 ? Palette.error : .primary)
        }
        .padding(.vertical, 6)
        .contentShape(Rectangle())
    }

    private func tile(_ value: Int, _ label: String, _ color: Color) -> some View {
        VStack(spacing: 2) {
            Text(value.compact).font(.title2.weight(.semibold)).foregroundStyle(color).monospacedDigit()
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
    }
}
