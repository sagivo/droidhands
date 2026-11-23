import SwiftUI

struct ActivityView: View {
    @Environment(AppModel.self) private var model
    @State private var confirmClear = false

    var body: some View {
        @Bindable var model = model
        let query = model.activityQuery
        let scoped = model.activity.filter { query.matches($0, ignoringOutcome: true) }
        let entries = scoped.filter(query.outcome.matches)
        NavigationStack {
            List {
                if !model.activity.isEmpty {
                    Section {
                        Picker("Show", selection: $model.activityQuery.outcome) {
                            ForEach(OutcomeFilter.allCases) { f in
                                let n = f == .all ? scoped.count : scoped.filter(f.matches).count
                                Text(n > 0 && f != .all ? "\(f.rawValue) \(n)" : f.rawValue).tag(f)
                            }
                        }
                        .pickerStyle(.segmented)
                        .listRowBackground(Color.clear)
                        .listRowInsets(EdgeInsets())
                        if let agent = query.agent {
                            HStack {
                                Label(agentOptions.first { $0.id == agent }?.name ?? "Deleted agent", systemImage: "person.fill")
                                Spacer()
                                Button("Show all", systemImage: "xmark.circle.fill") { model.activityQuery.agent = nil }
                                    .labelStyle(.iconOnly)
                                    .foregroundStyle(.secondary)
                                    .accessibilityLabel("Show every agent")
                            }
                            .font(.subheadline)
                        }
                    }
                }
                if model.activity.isEmpty {
                    ContentUnavailableView("No activity yet", systemImage: "list.bullet.rectangle",
                                           description: Text("Every request from an agent (allowed, denied or failed) shows up here. The log stays on this iPhone."))
                        .listRowBackground(Color.clear)
                } else if entries.isEmpty {
                    ContentUnavailableView {
                        Label("Nothing matches", systemImage: "line.3.horizontal.decrease.circle")
                    } description: {
                        Text("No requests match these filters.")
                    } actions: {
                        Button("Clear filters") { model.activityQuery = ActivityQuery() }
                    }
                    .listRowBackground(Color.clear)
                } else {
                    ForEach(ActivityText.byDay(Array(entries.prefix(500))), id: \.day) { group in
                        Section {
                            ForEach(group.entries) { entry in
                                NavigationLink { ActivityDetailView(entry: entry) } label: { ActivityRow(entry: entry) }
                            }
                        } header: {
                            HStack {
                                Text(ActivityText.dayHeader(group.day))
                                Spacer()
                                Text("\(group.entries.count)")
                            }
                        }
                    }
                }
            }
            .navigationTitle("Activity")
            .searchable(text: $model.activityQuery.search, prompt: "Search by tool")
            .toolbar {
                if !model.activity.isEmpty {
                    ToolbarItem(placement: .topBarTrailing) {
                        Menu {
                            Picker("Agent", selection: $model.activityQuery.agent) {
                                Text("All agents").tag(String?.none)
                                ForEach(agentOptions, id: \.id) { Text($0.name).tag(String?.some($0.id)) }
                            }
                            Section {
                                Button("Clear activity log", systemImage: "trash", role: .destructive) { confirmClear = true }
                            }
                        } label: {
                            Label("Filter", systemImage: query.agent == nil ? "line.3.horizontal.decrease.circle" : "line.3.horizontal.decrease.circle.fill")
                        }
                    }
                }
            }
            .confirmationDialog("Clear activity log?", isPresented: $confirmClear, titleVisibility: .visible) {
                Button("Clear", role: .destructive) { model.clearActivity() }
            } message: {
                Text("All \(model.activity.count) entries are deleted from this iPhone. Usage totals per agent are kept. This can't be undone.")
            }
        }
    }

    /// Current agents in list order, then deleted ones seen in the log, then callers without a valid token.
    private var agentOptions: [(id: String, name: String)] {
        var options = model.agents.map { (id: $0.id, name: $0.name) }
        var seen = Set(options.map(\.id))
        var unknown = false
        for e in model.activity {
            guard let id = e.tokenId else { unknown = true; continue }
            if seen.insert(id).inserted { options.append((id, e.tokenName.map { "\($0) (deleted)" } ?? "Deleted agent")) }
        }
        if unknown || model.activityQuery.agent == ActivityQuery.noAgent { options.append((ActivityQuery.noAgent, "Unknown callers")) }
        return options
    }
}

struct ActivityRow: View {
    @Environment(AppModel.self) private var model
    let entry: AuditEntry
    var showAgent = true
    var smartTime = false

    var body: some View {
        HStack(spacing: 12) {
            IconTile(symbol: entry.outcome.symbol, color: entry.outcome.color)
            VStack(alignment: .leading, spacing: 2) {
                Text(ActivityText.title(of: entry)).lineLimit(1)
                if !meta.isEmpty {
                    Text(meta).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                }
                if !entry.outcome.isSuccess, let detail = entry.detail {
                    Text(detail).font(.footnote).foregroundStyle(.secondary).lineLimit(2)
                }
            }
            Spacer(minLength: 4)
            Text(smartTime ? ActivityText.smartTime(entry.timestamp) : entry.timestamp.formatted(date: .omitted, time: .shortened))
                .font(.caption).foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
    }

    private var meta: String {
        var parts: [String] = []
        if !entry.outcome.isSuccess { parts.append(entry.outcome.label) }
        if showAgent { parts.append(ActivityText.agent(of: entry, agents: model.agents)) }
        return parts.joined(separator: " · ")
    }
}

extension AuditOutcome {
    var color: Color {
        switch category {
        case .success: return Palette.ok
        case .denied: return Palette.warning
        case .error: return Palette.error
        }
    }

    var symbol: String {
        switch self {
        case .OK: return "checkmark"
        case .AUTH_FAILED: return "lock.fill"
        case .DENIED_USER, .DENIED_SCOPE, .DENIED_POLICY: return "hand.raised.fill"
        case .PAUSED, .CANCELLED: return "pause.fill"
        default: return "exclamationmark"
        }
    }
}

struct ActivityDetailView: View {
    @Environment(AppModel.self) private var model
    let entry: AuditEntry

    var body: some View {
        List {
            Section {
                HStack(spacing: 14) {
                    ZStack {
                        Circle().fill(entry.outcome.color.opacity(0.14)).frame(width: 48, height: 48)
                        Image(systemName: entry.outcome.symbol).font(.title3.weight(.semibold)).foregroundStyle(entry.outcome.color)
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        Text(ActivityText.title(of: entry)).font(.title3.weight(.semibold))
                        StatusPill(text: entry.outcome.label, color: entry.outcome.color)
                    }
                }
                .padding(.vertical, 4)
                if let detail = entry.detail {
                    if entry.outcome.isSuccess {
                        Text(detail).font(.callout).textSelection(.enabled)
                    } else {
                        Notice(kind: entry.outcome.category == .error ? .danger : .warning,
                               title: entry.outcome.category == .error ? "What went wrong" : "Why it was refused",
                               message: "\(detail)\n\nThis is the message the agent received.")
                            .textSelection(.enabled)
                            .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                    }
                }
            }
            if let args = ActivityText.arguments(entry.argsPreview) {
                Section("Request") {
                    ForEach(args) { arg in LabeledContent(arg.name) { Text(arg.value).textSelection(.enabled) } }
                }
            } else if let raw = entry.argsPreview, raw != "{}" {
                Section("Request") { Text(raw).font(.callout.monospaced()).textSelection(.enabled) }
            }
            Section("Details") {
                LabeledContent("Agent", value: ActivityText.agent(of: entry, agents: model.agents))
                LabeledContent("Time", value: entry.timestamp.formatted(date: .abbreviated, time: .standard))
                if let ms = entry.durationMs { LabeledContent("Response time", value: ActivityText.duration(ms)) }
                LabeledContent(entry.tool == nil ? "Method" : "Tool") {
                    Text(entry.tool ?? entry.method).font(.callout.monospaced()).textSelection(.enabled)
                }
            }
        }
        .navigationTitle("Request")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ShareLink(item: ActivityText.report(entry, agents: model.agents)) { Label("Share details", systemImage: "square.and.arrow.up") }
        }
    }
}

extension ToolCatalog {
    static let titles: [String: String] = Dictionary(uniqueKeysWithValues: all().map { ($0.name, $0.title) })
}
