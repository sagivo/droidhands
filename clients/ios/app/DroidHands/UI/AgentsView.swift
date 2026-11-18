import SwiftUI

struct AgentsView: View {
    @Environment(AppModel.self) private var model
    @State private var showConnect = false

    var body: some View {
        NavigationStack {
            List {
                let active = model.agents.filter { $0.isActive(at: Date()) }
                let inactive = model.agents.filter { !$0.isActive(at: Date()) }
                if model.agents.isEmpty {
                    ContentUnavailableView {
                        Label("No agents yet", systemImage: "person.2")
                    } description: {
                        Text("Connect Claude, ChatGPT or another AI agent, then tap Allow here.")
                    } actions: {
                        Button("Connect an AI agent") { showConnect = true }.buttonStyle(.borderedProminent).buttonBorderShape(.capsule)
                    }
                    .listRowBackground(Color.clear)
                } else {
                    Section {
                        ForEach(active) { agent in
                            NavigationLink(value: agent.id) { AgentRow(agent: agent) }
                        }
                        Button { showConnect = true } label: {
                            TileRow(symbol: "plus", color: Palette.brand, title: "Connect an AI agent")
                        }
                        .foregroundStyle(Palette.brand)
                    }
                    if !inactive.isEmpty {
                        Section {
                            ForEach(inactive) { agent in
                                NavigationLink(value: agent.id) { AgentRow(agent: agent) }
                            }
                        } header: {
                            Text("Revoked")
                        } footer: {
                            Text("Revoked agents can't connect. Delete them to remove them from the list.")
                        }
                    }
                }
            }
            .navigationTitle("Agents")
            .navigationDestination(for: String.self) { AgentDetailView(id: $0) }
            .sheet(isPresented: $showConnect) { ConnectView() }
        }
    }
}

struct AgentRow: View {
    let agent: TokenRecord

    var body: some View {
        HStack(spacing: 12) {
            Monogram(name: agent.name)
            VStack(alignment: .leading, spacing: 2) {
                Text(agent.name)
                Text(subtitle).font(.subheadline).foregroundStyle(.secondary)
            }
            Spacer()
            if agent.revoked { StatusPill(text: "Revoked", color: Palette.off) }
        }
    }

    private var subtitle: String {
        let access = GrantDraft(record: agent).mode == .everything ? "Everything" : "\(AccessSummary.abilities(agent).count) abilities"
        let used = agent.lastUsedAt.map { "used \($0.relative)" } ?? "not used yet"
        return "\(access) · \(used)"
    }
}

struct AgentDetailView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let id: String
    @State private var confirmRevoke = false
    @State private var confirmDelete = false

    var body: some View {
        if let agent = model.agents.first(where: { $0.id == id }) {
            List {
                Section {
                    HStack(spacing: 14) {
                        Monogram(name: agent.name, size: 52)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(agent.name).font(.title3.weight(.semibold))
                            Text(origin(agent)).font(.subheadline).foregroundStyle(.secondary)
                        }
                    }
                    .padding(.vertical, 4)
                }
                Section {
                    NavigationLink { RenameView(agent: agent) } label: { LabeledContent("Name", value: agent.name) }
                    NavigationLink { AccessEditView(agent: agent) } label: {
                        LabeledContent("Access", value: GrantDraft(record: agent).mode == .everything ? "Everything" : "Chosen")
                    }
                    NavigationLink { ToolRulesView(agent: agent) } label: {
                        LabeledContent("Tool rules", value: agent.toolRules.isEmpty ? "None" : "\(agent.toolRules.count)")
                    }
                } footer: {
                    Text("Asks before sensitive actions: \(agent.confirmPolicy == .NEVER ? "no" : "yes"). Last used \(agent.lastUsedAt?.relative ?? "never").")
                }
                let entries = model.activity.filter { $0.tokenId == agent.id }
                Section {
                    if entries.isEmpty {
                        Text("Nothing yet.").foregroundStyle(.secondary)
                    } else {
                        ForEach(entries.prefix(20)) { ActivityRow(entry: $0, showAgent: false) }
                    }
                } header: {
                    Text("Recent activity")
                }
                Section {
                    if !agent.revoked {
                        Button("Stop what it's doing") { model.stopCalls(of: agent.id) }
                        Button("Revoke access", role: .destructive) { confirmRevoke = true }
                    }
                    Button("Delete", role: .destructive) { confirmDelete = true }
                }
            }
            .navigationTitle(agent.name)
            .navigationBarTitleDisplayMode(.inline)
            .confirmationDialog("Revoke \(agent.name)?", isPresented: $confirmRevoke, titleVisibility: .visible) {
                Button("Revoke", role: .destructive) { model.revoke(agent.id) }
            } message: {
                Text("It can't use this iPhone any more. To use it again, connect it again.")
            }
            .confirmationDialog("Delete \(agent.name)?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Delete", role: .destructive) {
                    model.delete(agent.id)
                    dismiss()
                }
            } message: {
                Text("It loses access and leaves the list. Its activity stays in the log.")
            }
        } else {
            ContentUnavailableView("Agent removed", systemImage: "person.crop.circle.badge.xmark")
        }
    }

    private func origin(_ a: TokenRecord) -> String {
        let since = a.createdAt.formatted(date: .abbreviated, time: .omitted)
        if a.origin == "pairing" { return "Connected \(since)\(a.clientHost.map { " from \($0)" } ?? " by agent sign-in")" }
        return "Token made \(since)"
    }
}

struct RenameView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let agent: TokenRecord
    @State private var name = ""

    var body: some View {
        Form { TextField("Name", text: $name) }
            .navigationTitle("Name")
            .onAppear { name = agent.name }
            .toolbar {
                Button("Save") {
                    model.update(agent.id) { $0.name = name }
                    dismiss()
                }
                .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
            }
    }
}

struct AccessEditView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let agent: TokenRecord
    @State private var grant = GrantDraft()

    var body: some View {
        Form { GrantEditor(grant: $grant) }
            .navigationTitle("Access")
            .onAppear { grant = GrantDraft(record: agent) }
            .toolbar {
                Button("Save") {
                    model.update(agent.id) { $0 = grant.applied(to: $0) }
                    dismiss()
                }
                .disabled(grant.error != nil || grant == GrantDraft(record: agent))
            }
    }
}

struct ToolRulesView: View {
    @Environment(AppModel.self) private var model
    let agent: TokenRecord

    var body: some View {
        let current = model.agents.first { $0.id == agent.id } ?? agent
        List {
            Section {
                ForEach(model.server.allTools.filter { Scopes.allows(current.scopes, $0.scope) && !$0.scope.isEmpty }, id: \.name) { tool in
                    Picker(selection: Binding(get: { current.toolRules[tool.name] }, set: { rule in
                        model.update(agent.id) { $0.toolRules[tool.name] = rule }
                    })) {
                        Text("Default").tag(ToolRule?.none)
                        Text("Ask first").tag(ToolRule?.some(.ASK))
                        Text("Never ask").tag(ToolRule?.some(.ALLOW))
                        Text("Block").tag(ToolRule?.some(.BLOCK))
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(tool.title)
                            Text(tool.name).font(.caption.monospaced()).foregroundStyle(.secondary)
                        }
                    }
                }
            } footer: {
                Text("Default follows the agent's access. Ask first shows an Allow prompt for each call; Block hides the tool from this agent.")
            }
        }
        .navigationTitle("Tool rules")
    }
}
