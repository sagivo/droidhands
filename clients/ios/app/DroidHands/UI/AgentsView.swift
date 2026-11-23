import Charts
import SwiftUI

struct AgentsView: View {
    @Environment(AppModel.self) private var model
    @State private var showConnect = false

    var body: some View {
        NavigationStack {
            List {
                let now = Date()
                let sorted = model.agents.sorted { a, b in
                    let (sa, sb) = (AgentStatus(a, now: now).order, AgentStatus(b, now: now).order)
                    if sa != sb { return sa < sb }
                    let (la, lb) = (a.lastUsedAt ?? .distantPast, b.lastUsedAt ?? .distantPast)
                    return la != lb ? la > lb : a.createdAt > b.createdAt
                }
                let active = sorted.filter { AgentStatus($0, now: now) == .active }
                let expired = sorted.filter { AgentStatus($0, now: now) == .expired }
                let revoked = sorted.filter { AgentStatus($0, now: now) == .revoked }
                if model.agents.isEmpty {
                    ContentUnavailableView {
                        Label("No agents yet", systemImage: "person.2")
                    } description: {
                        Text("Copy one message to Claude, ChatGPT or any other AI agent. Then tap Allow on this iPhone.")
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
                    } footer: {
                        Text("Tap an agent to see what it did, change its access, or revoke it.")
                    }
                    if !expired.isEmpty {
                        Section {
                            ForEach(expired) { agent in
                                NavigationLink(value: agent.id) { AgentRow(agent: agent) }
                            }
                        } header: {
                            Text("Expired")
                        } footer: {
                            Text("Expired agents can't act. Open one and pick a new expiry to let it back in.")
                        }
                    }
                    if !revoked.isEmpty {
                        Section {
                            ForEach(revoked) { agent in
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
    @Environment(AppModel.self) private var model
    let agent: TokenRecord

    var body: some View {
        HStack(spacing: 12) {
            Monogram(name: agent.name, size: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text(agent.name)
                Text("\(agent.clientLabel) · \(agent.lastUsedAt.map { "Active \($0.relative)" } ?? "Never used")")
                    .font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                Text(details).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            switch AgentStatus(agent) {
            case .active: EmptyView()
            case .expired: StatusPill(text: "Expired", color: Palette.warning)
            case .revoked: StatusPill(text: "Revoked", color: Palette.off)
            }
        }
    }

    private var details: String {
        var parts = [GrantDraft(record: agent).mode == .everything ? "Everything" : "\(AccessSummary.abilities(agent).count) abilities"]
        let today = model.usage[agent.id]?.calls(on: Date()) ?? 0
        if today > 0 { parts.append("\(today.compact) call\(today == 1 ? "" : "s") today") }
        if !agent.toolRules.isEmpty { parts.append("\(agent.toolRules.count) tool rule\(agent.toolRules.count == 1 ? "" : "s")") }
        if let hours = agent.activeHours { parts.append(hours.label) }
        return parts.joined(separator: " · ")
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
            let status = AgentStatus(agent)
            let usage = model.usage[agent.id] ?? TokenUsage()
            List {
                Section {
                    AgentHero(agent: agent, status: status, usage: usage)
                    if status == .revoked {
                        Notice(kind: .warning, title: "Revoked", message: "This agent is locked out. To use it again, connect it again; you can delete this entry.")
                            .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                    } else if status == .expired {
                        Notice(kind: .warning, title: "Expired", message: "This agent expired and can't act. Pick a new expiry under Expires to let it back in.")
                            .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                    }
                }

                AgentUsageSection(usage: usage)

                let entries = model.activity.filter { $0.tokenId == agent.id }
                Section {
                    if entries.isEmpty {
                        Text("No activity from this agent in the log.").foregroundStyle(.secondary)
                    } else {
                        ForEach(entries.prefix(5)) { entry in
                            NavigationLink { ActivityDetailView(entry: entry) } label: { ActivityRow(entry: entry, showAgent: false, smartTime: true) }
                        }
                    }
                } header: {
                    HStack {
                        Text("Recent activity")
                        Spacer()
                        if !entries.isEmpty {
                            Button("See all") { model.openActivity(agent: agent.id) }
                                .font(.subheadline)
                                .textCase(nil)
                        }
                    }
                }

                Section {
                    if !agent.revoked {
                        NavigationLink { RenameView(agent: agent) } label: { LabeledContent("Name", value: agent.name) }
                        NavigationLink { AccessEditView(agent: agent) } label: {
                            LabeledContent("Access", value: GrantDraft(record: agent).mode == .everything ? "Everything" : "\(AccessSummary.abilities(agent).count) abilities")
                        }
                        NavigationLink { ToolRulesView(agent: agent) } label: {
                            LabeledContent("Tool rules", value: agent.toolRules.isEmpty ? "None" : "\(agent.toolRules.count)")
                        }
                    } else {
                        LabeledContent("Access", value: GrantDraft(record: agent).mode == .everything ? "Everything" : "\(AccessSummary.abilities(agent).count) abilities")
                    }
                } header: {
                    Text("Access")
                } footer: {
                    Text(accessFooter(agent))
                }

                if !agent.revoked {
                    ActiveHoursSection(agent: agent)
                    ExpirySection(agent: agent)
                }

                Section {
                    if !agent.revoked {
                        Button("Stop what it's doing") { model.stopCalls(of: agent.id) }
                            .disabled(status != .active)
                        Button("Revoke access", role: .destructive) { confirmRevoke = true }
                    }
                    Button("Delete agent", role: .destructive) { confirmDelete = true }
                }
            }
            .navigationTitle(agent.name)
            .navigationBarTitleDisplayMode(.inline)
            .confirmationDialog("Revoke \(agent.name)?", isPresented: $confirmRevoke, titleVisibility: .visible) {
                Button("Revoke", role: .destructive) {
                    model.revoke(agent.id)
                    model.showBanner("\(agent.name) revoked")
                }
            } message: {
                Text("The agent is locked out immediately. This can't be undone; connect it again to use it.")
            }
            .confirmationDialog("Delete \(agent.name)?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Delete", role: .destructive) {
                    model.delete(agent.id)
                    dismiss()
                }
            } message: {
                Text(status == .active ? "It loses access right away and leaves the list. Its activity stays in the log." : "It leaves the list. Its activity stays in the log.")
            }
        } else {
            ContentUnavailableView("Agent removed", systemImage: "person.crop.circle.badge.xmark")
        }
    }

    private func accessFooter(_ a: TokenRecord) -> String {
        let asks: String
        switch a.confirmPolicy {
        case .NEVER: asks = "Never asks before acting."
        case .SENSITIVE: asks = "Asks before messages, calls and shortcuts."
        case .ALL_ACTIONS: asks = "Asks before every action that changes something."
        }
        return a.revoked ? asks : "\(asks) Changes apply to its next request."
    }
}

/// Name, origin, status and the headline numbers.
private struct AgentHero: View {
    let agent: TokenRecord
    let status: AgentStatus
    let usage: TokenUsage

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 14) {
                Monogram(name: agent.name, size: 52)
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 8) {
                        Text(agent.name).font(.title3.weight(.semibold))
                        StatusPill(text: status.label, color: status == .active ? Palette.ok : status == .expired ? Palette.warning : Palette.off,
                                   dot: status == .active)
                    }
                    Text(origin).font(.subheadline).foregroundStyle(.secondary)
                }
            }
            HStack(spacing: 24) {
                stat(usage.calls(on: Date()), "calls today")
                stat(usage.totalCalls, "calls in all")
            }
            Text([agent.lastUsedAt.map { "Active \($0.relative)" } ?? "Never used", agent.expiryText()].joined(separator: " · "))
                .font(.footnote).foregroundStyle(.secondary)
        }
        .padding(.vertical, 4)
    }

    private var origin: String {
        let since = agent.createdAt.formatted(date: .abbreviated, time: .omitted)
        if agent.origin == "pairing" { return "Connected \(since)\(agent.clientHost.map { " from \($0)" } ?? " by agent sign-in")" }
        return "Token made \(since)"
    }

    private func stat(_ value: Int, _ label: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(value.compact).font(.title2.weight(.semibold)).monospacedDigit()
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
    }
}

/// The last 14 days as bars, totals by result, and the most used tools.
struct AgentUsageSection: View {
    let usage: TokenUsage
    @State private var selected: Date?

    private struct Day: Identifiable {
        let date: Date
        let calls: Int
        var id: Date { date }
    }

    var body: some View {
        Section {
            if usage.totalCalls == 0 {
                Text("No calls yet. Usage appears here once the agent uses this iPhone.").foregroundStyle(.secondary)
            } else {
                let days = lastDays(14)
                let total = days.reduce(0) { $0 + $1.calls }
                VStack(alignment: .leading, spacing: 10) {
                    if let selected, let day = days.first(where: { Calendar.current.isDate($0.date, inSameDayAs: selected) }) {
                        Text("\(day.date.formatted(.dateTime.month(.abbreviated).day())): \(day.calls) call\(day.calls == 1 ? "" : "s")")
                            .font(.subheadline.weight(.semibold))
                    } else {
                        Text("Last 14 days: \(total) call\(total == 1 ? "" : "s")").font(.subheadline.weight(.semibold))
                    }
                    Chart(days) { day in
                        BarMark(x: .value("Day", day.date, unit: .day), y: .value("Calls", day.calls))
                            .foregroundStyle(Palette.brand.opacity(selected == nil || Calendar.current.isDate(day.date, inSameDayAs: selected!) ? 1 : 0.35))
                            .cornerRadius(3)
                    }
                    .chartXSelection(value: $selected)
                    .chartXAxis {
                        AxisMarks(values: [days.first!.date]) { _ in
                            AxisValueLabel(anchor: .topLeading) { Text(days.first!.date.formatted(.dateTime.month(.abbreviated).day())) }
                        }
                        AxisMarks(values: [days.last!.date]) { _ in
                            AxisValueLabel(anchor: .topTrailing) { Text("Today").fixedSize() }
                        }
                    }
                    .chartYAxis { AxisMarks(position: .trailing, values: .automatic(desiredCount: 2)) }
                    .frame(height: 120)
                    .accessibilityLabel("Calls per day over the last 14 days, \(total) in all")
                    HStack(spacing: 16) {
                        totalLabel(usage.okCalls, "OK", Palette.ok)
                        totalLabel(usage.errorCalls, "Errors", Palette.error)
                        totalLabel(usage.deniedCalls, "Denied", Palette.warning)
                    }
                    .font(.footnote)
                }
                .padding(.vertical, 4)
            }
        } header: {
            Text("Usage")
        }
        let top = usage.perTool.sorted { $0.value != $1.value ? $0.value > $1.value : $0.key < $1.key }.prefix(5)
        if !top.isEmpty {
            Section("Most used tools") {
                ForEach(Array(top), id: \.key) { name, count in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(ToolCatalog.titles[name] ?? name)
                            Text(name).font(.caption.monospaced()).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Text("\(count.compact) · \(usage.totalCalls > 0 ? count * 100 / usage.totalCalls : 0)%")
                            .font(.subheadline).foregroundStyle(.secondary).monospacedDigit()
                    }
                }
            }
        }
    }

    private func lastDays(_ n: Int) -> [Day] {
        let cal = Calendar.current
        let today = cal.startOfDay(for: Date())
        return (0..<n).reversed().compactMap { offset in
            cal.date(byAdding: .day, value: -offset, to: today).map { Day(date: $0, calls: usage.calls(on: $0)) }
        }
    }

    private func totalLabel(_ n: Int, _ label: String, _ color: Color) -> some View {
        HStack(spacing: 5) {
            Circle().fill(color).frame(width: 7, height: 7)
            Text("\(label) \(n.compact)").foregroundStyle(.secondary).monospacedDigit()
        }
    }
}

/// "Only at certain times": a daily window in the phone's local time. Applies as soon as it's valid.
private struct ActiveHoursSection: View {
    @Environment(AppModel.self) private var model
    let agent: TokenRecord
    @State private var hours = ActiveHours.default

    var body: some View {
        let enabled = Binding(get: { agent.activeHours != nil }, set: { on in
            hours = agent.activeHours ?? .default
            model.update(agent.id) { $0.activeHours = on ? hours : nil }
        })
        Section {
            Toggle(isOn: enabled) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Only at certain times")
                    Text(agent.activeHours.map { $0.label + (ActiveWindow.isOvernight($0) ? " (overnight)" : "") } ?? "Any time")
                        .font(.subheadline).foregroundStyle(.secondary)
                }
            }
            if agent.activeHours != nil {
                DatePicker("From", selection: minuteBinding(\.startMinute), displayedComponents: .hourAndMinute)
                DatePicker("Until", selection: minuteBinding(\.endMinute), displayedComponents: .hourAndMinute)
            }
        } header: {
            Text("Active hours")
        } footer: {
            if agent.activeHours != nil, !ActiveWindow.isValid(hours) {
                Text("Start and end can't be the same time. Turn active hours off to allow any time.").foregroundStyle(Palette.error)
            } else if let h = agent.activeHours {
                let length = ActiveWindow.length(h)
                Text("\(length / 60) h\(length % 60 > 0 ? " \(length % 60) min" : "") a day\(ActiveWindow.isOvernight(h) ? ", across midnight" : ""). Outside these hours every request from this agent is refused (this iPhone's local time).")
            } else {
                Text("Outside these hours every request from this agent is refused (this iPhone's local time).")
            }
        }
        .onAppear { hours = agent.activeHours ?? .default }
    }

    private func minuteBinding(_ key: WritableKeyPath<ActiveHours, Int>) -> Binding<Date> {
        Binding(get: { ActiveHours.date(hours[keyPath: key]) }, set: { date in
            hours[keyPath: key] = ActiveWindow.minuteOfDay(date)
            if ActiveWindow.isValid(hours) { model.update(agent.id) { $0.activeHours = hours } }
        })
    }
}

/// When the agent stops working; a new choice counts from now.
private struct ExpirySection: View {
    @Environment(AppModel.self) private var model
    let agent: TokenRecord

    var body: some View {
        Section {
            Menu {
                ForEach(ExpiryChoice.allCases) { choice in
                    Button(choice == .never ? "Never" : "In \(choice.rawValue)") {
                        model.update(agent.id) { $0.expiresAt = choice.interval.map { Date().addingTimeInterval($0) } }
                        model.showBanner(choice == .never ? "\(agent.name) never expires" : "\(agent.name) expires in \(choice.rawValue)")
                    }
                }
            } label: {
                LabeledContent("Expires") {
                    Text(agent.expiresAt.map { $0.formatted(date: .abbreviated, time: .shortened) } ?? "Never")
                }
                .foregroundStyle(.primary)
            }
        } footer: {
            Text("\(agent.expiryText()). A new choice counts from now.")
        }
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
                    let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
                    model.update(agent.id) { $0.name = trimmed }
                    dismiss()
                }
                .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || name.trimmingCharacters(in: .whitespacesAndNewlines) == agent.name)
            }
    }
}

struct AccessEditView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let agent: TokenRecord
    @State private var grant = GrantDraft()

    var body: some View {
        Form {
            GrantEditor(grant: $grant)
            AIDisclosureSection(agent: AIDisclosure.agentLabel(name: agent.name, host: agent.clientHost), scopes: grant.scopes, button: "Save")
        }
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
                ForEach(model.server.allTools.filter { Scopes.allows(current.scopes, $0.scope) && !$0.scope.isEmpty && !McpServer.exceedsCap(current.maxRisk, $0.risk) }, id: \.name) { tool in
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
                Text("Default follows the agent's access (\(current.confirmPolicy == .NEVER ? "runs without asking" : "asks before sensitive actions")). Ask first shows an Allow prompt for each call; Never ask is still rate-limited; Block hides the tool from this agent and refuses it.")
            }
        }
        .navigationTitle("Tool rules")
    }
}
