import SwiftUI

struct HomeView: View {
    @Environment(AppModel.self) private var model
    let openAgents: () -> Void
    @State private var showConnect = false

    var body: some View {
        @Bindable var model = model
        NavigationStack {
            List {
                if let error = model.startupError {
                    Section { Notice(kind: .danger, title: "Something needs attention", message: error) }
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

                let today = model.activity.filter { Calendar.current.isDateInToday($0.timestamp) && $0.method == "tools/call" }
                if !today.isEmpty {
                    Section {
                        ForEach(today.prefix(4)) { ActivityRow(entry: $0) }
                    } header: {
                        Text("Today · \(today.count) action\(today.count == 1 ? "" : "s")")
                    }
                }
            }
            .navigationTitle("DroidHands")
            .sheet(isPresented: $showConnect) { ConnectView() }
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
        case .failed: return "xmark.octagon.fill"
        case .stopped: return "power"
        }
    }
    private var title: String { accessEnabled ? state.title : "Paused" }
    private var subtitle: String {
        if !accessEnabled { return "Agents are refused until you resume." }
        if running > 0 { return "An agent is working now." }
        return state.detail ?? (state.isConnected ? "Agents you allowed can reach this iPhone." : "Waiting for the relay.")
    }
}
