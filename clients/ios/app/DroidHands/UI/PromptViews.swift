import SwiftUI

/// "<Agent> wants to use this iPhone": who, from where, the code to compare, and the access to give.
struct PairingSheet: View {
    @Environment(AppModel.self) private var model
    let prompt: PairingPrompt
    @State private var name: String
    @State private var grant: GrantDraft
    @State private var armed = false

    init(prompt: PairingPrompt) {
        self.prompt = prompt
        _name = State(initialValue: prompt.name)
        _grant = State(initialValue: prompt.grant)
    }

    private var r: PairingRequest { prompt.request }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(spacing: 12) {
                        Monogram(name: r.clientName, size: 56)
                        Text("\(r.clientName) wants to use this iPhone").font(.title3.weight(.semibold)).multilineTextAlignment(.center)
                        Text(r.viaAgent ? "The agent asked to sign in from its chat. Allow it only if the agent showed you this code:"
                                        : "Allow it only if \(r.redirectHost) shows this code:")
                            .font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
                        Text(r.displayCode)
                            .font(.system(size: 34, weight: .bold, design: .monospaced))
                            .tracking(6)
                            .padding(.horizontal, 20).padding(.vertical, 8)
                            .background(Color(.tertiarySystemFill), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        TimelineView(.periodic(from: .now, by: 1)) { _ in
                            let left = max(0, Int(r.expiresAt.timeIntervalSinceNow))
                            Text("Expires in \(left / 60):\(String(format: "%02d", left % 60))").font(.footnote).foregroundStyle(.secondary).monospacedDigit()
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
                }
                Section {
                    TextField("Name", text: $name)
                } header: {
                    Text("Name")
                } footer: {
                    Text("How this agent appears in DroidHands.")
                }
                GrantEditor(grant: $grant)
                AIDisclosureSection(agent: AIDisclosure.agentLabel(name: r.clientName, host: r.viaAgent ? nil : r.redirectHost),
                                    scopes: grant.scopes, button: "Allow")
                Section {
                    LabeledContent(r.viaAgent ? "Signed in" : "Website", value: r.viaAgent ? "In the agent's chat" : r.redirectHost)
                    if let ip = r.remoteAddress { LabeledContent("Requested from", value: ip) }
                } header: {
                    Text("Details")
                } footer: {
                    Text("Only allow it if you started this yourself.")
                }
            }
            .navigationTitle("Connect agent")
            .navigationBarTitleDisplayMode(.inline)
            .safeAreaInset(edge: .bottom) {
                HStack(spacing: 10) {
                    Button(role: .cancel) { model.deny(prompt) } label: {
                        Text("Deny").frame(maxWidth: .infinity).padding(.vertical, 6)
                    }
                    .buttonStyle(.bordered)
                    Button {
                        var p = prompt
                        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
                        p.name = trimmed.isEmpty ? r.clientName : trimmed
                        p.grant = grant
                        model.approve(p)
                    } label: {
                        Text("Allow").frame(maxWidth: .infinity).padding(.vertical, 6)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(grant.error != nil || !armed)
                }
                .buttonBorderShape(.capsule)
                .controlSize(.large)
                .padding()
                .background(.bar)
            }
            .task { await PromptArming.wait(); armed = true }
        }
    }
}

/// Allow buttons ignore taps until the prompt has been on screen briefly, so a tap meant for what was there
/// before can't approve it (Android arms them after 700 ms too).
enum PromptArming {
    static func wait() async { try? await Task.sleep(nanoseconds: 700_000_000) }
}

/// Everything, or only chosen abilities, plus "ask me first". Shared by pairing, manual tokens and an agent's Access page.
struct GrantEditor: View {
    @Binding var grant: GrantDraft

    var body: some View {
        Section {
            Picker("Access", selection: $grant.mode) {
                Text("Everything").tag(GrantDraft.Mode.everything)
                Text("Only what I choose").tag(GrantDraft.Mode.chosen)
            }
            .pickerStyle(.segmented)
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets())
            if grant.mode == .chosen {
                ForEach(Ability.allCases) { ability in
                    Toggle(isOn: Binding(get: { grant.abilities.contains(ability) }, set: { on in
                        if on { grant.abilities.insert(ability) } else { grant.abilities.remove(ability) }
                    })) {
                        TileRow(symbol: ability.symbol, color: ability.color, title: ability.label, subtitle: ability.detail)
                    }
                }
            }
        } header: {
            Text("What it can do")
        } footer: {
            if let error = grant.error {
                Text(error).foregroundStyle(Palette.error)
            } else if grant.mode == .everything {
                Text("Everything DroidHands can do on iPhone: contacts, calendar and reminders, location, links, messages and calls (you tap Send or Call), device features and your shortcuts.")
            }
        }
        Section {
            Toggle("Ask me before it sends messages, calls or runs shortcuts", isOn: $grant.askBeforeSensitive)
        } footer: {
            Text(grant.askBeforeSensitive ? "DroidHands asks you on this iPhone before each of these." : "Inside its access the agent acts without asking. You can pause or revoke it any time.")
        }
    }
}

/// "Shared with a third-party AI": what reaches the agent and that the named button is the consent (5.1.2(i)).
struct AIDisclosureSection: View {
    let agent: String
    let scopes: Set<String>
    let button: String

    var body: some View {
        Section {
            Label {
                Text(AIDisclosure.text(agent: agent, scopes: scopes, button: button)).font(.subheadline)
            } icon: {
                Image(systemName: "sparkles").foregroundStyle(.purple)
            }
        } header: {
            Text("Shared with a third-party AI")
        }
    }
}

extension Ability {
    var color: Color {
        switch self {
        case .readContactsCalendar: return .blue
        case .location: return .teal
        case .useApps: return .indigo
        case .sendMessages: return .green
        case .calls: return .green
        case .changeCalendar: return .red
        case .device: return .gray
        case .shortcuts: return .purple
        }
    }
}

/// An agent is waiting for the user to allow one action.
struct ApprovalSheet: View {
    @Environment(AppModel.self) private var model
    let prompt: ApprovalPrompt
    @State private var armed = false

    var body: some View {
        let r = prompt.request
        VStack(spacing: 18) {
            Capsule().fill(.secondary.opacity(0.4)).frame(width: 36, height: 5).padding(.top, 8)
            Monogram(name: r.agentName, size: 56)
            Text("\(r.agentName) wants to").font(.headline).foregroundStyle(.secondary)
            Text(r.summary)
                .font(.title3.weight(.semibold))
                .multilineTextAlignment(.center)
                .padding(.horizontal)
            HStack(spacing: 6) {
                StatusPill(text: ToolCatalog.titles[r.tool] ?? r.tool, color: r.risk == .SENSITIVE ? Palette.warning : Palette.brand)
                StatusPill(text: riskLabel(r.risk), color: r.risk == .SENSITIVE ? Palette.warning : r.risk == .ACTION ? Palette.brand : Palette.ok)
            }
            TimelineView(.periodic(from: .now, by: 1)) { _ in
                Text("Expires in \(max(0, Int(r.expiresAt.timeIntervalSinceNow))) s").font(.footnote).foregroundStyle(.secondary).monospacedDigit()
            }
            Spacer()
            Text("Only allow actions you asked your agent to do. Text it read may try to trick it.")
                .font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                .padding(.horizontal)
            HStack(spacing: 10) {
                Button(role: .cancel) { model.answer(prompt, approved: false) } label: {
                    Text("Deny").frame(maxWidth: .infinity).padding(.vertical, 6)
                }
                .buttonStyle(.bordered)
                Button { model.answer(prompt, approved: true) } label: {
                    Text("Allow").frame(maxWidth: .infinity).padding(.vertical, 6)
                }
                .buttonStyle(.borderedProminent)
                .disabled(!armed)
            }
            .buttonBorderShape(.capsule)
            .controlSize(.large)
            .padding(.horizontal)
        }
        .padding(.bottom)
        .presentationDetents([.medium, .large])
        .task { await PromptArming.wait(); armed = true }
    }

    private func riskLabel(_ risk: Risk) -> String {
        switch risk {
        case .READ: return "Read only"
        case .ACTION: return "Changes something"
        case .SENSITIVE: return "Sensitive"
        }
    }
}
