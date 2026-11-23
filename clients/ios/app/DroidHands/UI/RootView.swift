import SwiftUI

struct RootView: View {
    @Environment(AppModel.self) private var model

    enum Tab { case home, agents, activity, settings }

    var body: some View {
        @Bindable var model = model
        Group {
            if !model.settings.onboarded {
                OnboardingView()
            } else {
                TabView(selection: $model.tab) {
                    HomeView(openAgents: { model.tab = .agents })
                        .tabItem { Label("Home", systemImage: "house.fill") }.tag(Tab.home)
                    AgentsView()
                        .tabItem { Label("Agents", systemImage: "person.2.fill") }.tag(Tab.agents)
                    ActivityView()
                        .tabItem { Label("Activity", systemImage: "list.bullet.rectangle") }.tag(Tab.activity)
                    SettingsView()
                        .tabItem { Label("Settings", systemImage: "gearshape.fill") }.tag(Tab.settings)
                }
            }
        }
        .promptSheets()
        .overlay(alignment: .bottom) {
            if let message = model.bannerMessage {
                Text(message)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Color(.label).opacity(0.9), in: Capsule())
                    .shadow(radius: 6, y: 2)
                    .padding(.horizontal, 20)
                    .padding(.bottom, 64)
                    .onTapGesture { model.dismissBanner() }
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.2), value: model.bannerMessage)
        .alert(
            model.pendingConfigure.map { "Connect to \($0.host)?" } ?? "Connect to another relay?",
            isPresented: Binding(get: { model.pendingConfigure != nil }, set: { if !$0 { model.pendingConfigure = nil } })
        ) {
            Button("Cancel", role: .cancel) { model.pendingConfigure = nil }
            Button("Connect") {
                if let request = model.pendingConfigure { model.applyConfigure(request) }
                model.pendingConfigure = nil
            }
        } message: {
            if let request = model.pendingConfigure {
                if request.insecure {
                    Text("Warning: \(request.host) uses plain HTTP, not HTTPS. Traffic and keys could be read on the network. Agents paired through your current relay stop reaching this iPhone until you switch back.")
                } else {
                    Text("DroidHands will connect through \(request.host). Agents paired through the current relay stop reaching this iPhone until you switch back.")
                }
            }
        }
    }
}

/// One prompt sheet at a time (approvals first: an agent is waiting mid-task, then pairings), shown by the
/// topmost screen that applies this, so it also appears over the Connect screen and Onboarding.
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
