import SwiftUI

struct RootView: View {
    @Environment(AppModel.self) private var model
    @State private var tab = Tab.home

    enum Tab { case home, agents, activity, settings }

    var body: some View {
        if !model.settings.onboarded {
            OnboardingView()
        } else {
            TabView(selection: $tab) {
                HomeView(openAgents: { tab = .agents })
                    .tabItem { Label("Home", systemImage: "house.fill") }.tag(Tab.home)
                AgentsView()
                    .tabItem { Label("Agents", systemImage: "person.2.fill") }.tag(Tab.agents)
                ActivityView()
                    .tabItem { Label("Activity", systemImage: "list.bullet.rectangle") }.tag(Tab.activity)
                SettingsView()
                    .tabItem { Label("Settings", systemImage: "gearshape.fill") }.tag(Tab.settings)
            }
            .promptSheets()
            .alert("Connect to another relay?", isPresented: Binding(get: { model.pendingRelayChange != nil }, set: { if !$0 { model.pendingRelayChange = nil } })) {
                Button("Cancel", role: .cancel) { model.pendingRelayChange = nil }
                Button("Connect") {
                    if let change = model.pendingRelayChange { model.applyRelay(url: change.relay, key: change.key) }
                    model.pendingRelayChange = nil
                }
            } message: {
                Text("DroidHands will connect through \(URL(string: model.pendingRelayChange?.relay ?? "")?.host ?? "this relay"). Agents paired through the current relay stop reaching this iPhone until you switch back.")
            }
        }
    }
}

/// One prompt sheet at a time (approvals first: an agent is waiting mid-task, then pairings), shown by the
/// topmost screen that applies this, so it also appears over the Connect screen.
private struct PromptSheets: ViewModifier {
    @Environment(AppModel.self) private var model
    @State private var hostId = UUID()

    enum Prompt: Identifiable {
        case approval(ApprovalPrompt)
        case pairing(PairingPrompt)

        var id: String {
            switch self {
            case .approval(let a): return "a-\(a.id)"
            case .pairing(let p): return "p-\(p.id)"
            }
        }
    }

    private var current: Prompt? {
        guard model.promptHosts.last == hostId else { return nil }
        if let a = model.approvals.first { return .approval(a) }
        return model.pairings.first.map { .pairing($0) }
    }

    func body(content: Content) -> some View {
        content
            .onAppear { model.addPromptHost(hostId) }
            .onDisappear { model.removePromptHost(hostId) }
            .sheet(item: Binding(get: { current }, set: { _ in })) { prompt in
                switch prompt {
                case .approval(let a): ApprovalSheet(prompt: a).interactiveDismissDisabled()
                case .pairing(let p): PairingSheet(prompt: p).interactiveDismissDisabled()
                }
            }
    }
}

extension View {
    func promptSheets() -> some View { modifier(PromptSheets()) }
}
