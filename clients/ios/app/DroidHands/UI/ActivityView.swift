import SwiftUI

struct ActivityView: View {
    @Environment(AppModel.self) private var model
    @State private var filter = Filter.all
    @State private var confirmClear = false

    enum Filter: String, CaseIterable { case all = "All", problems = "Refused or failed", signIns = "Sign-ins" }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Picker("Show", selection: $filter) {
                        ForEach(Filter.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets())
                }
                let entries = model.activity.filter(matches)
                if entries.isEmpty {
                    ContentUnavailableView("No activity", systemImage: "list.bullet.rectangle",
                                           description: Text("Every call an agent makes on this iPhone shows up here, allowed or not."))
                        .listRowBackground(Color.clear)
                } else {
                    Section {
                        ForEach(entries.prefix(500)) { entry in
                            NavigationLink { ActivityDetailView(entry: entry) } label: { ActivityRow(entry: entry) }
                        }
                    }
                }
            }
            .navigationTitle("Activity")
            .toolbar {
                if !model.activity.isEmpty {
                    Button("Clear") { confirmClear = true }
                }
            }
            .confirmationDialog("Clear the activity log?", isPresented: $confirmClear, titleVisibility: .visible) {
                Button("Clear", role: .destructive) { model.audit.clear() }
            }
        }
    }

    private func matches(_ e: AuditEntry) -> Bool {
        switch filter {
        case .all: return true
        case .problems: return !e.outcome.isSuccess && e.outcome != .AUTH_FAILED
        case .signIns: return e.outcome == .AUTH_FAILED
        }
    }
}

struct ActivityRow: View {
    let entry: AuditEntry
    var showAgent = true

    var body: some View {
        HStack(spacing: 12) {
            IconTile(symbol: symbol, color: color)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).lineLimit(1)
                Text(subtitle).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 4)
            Text(entry.timestamp.formatted(date: .omitted, time: .shortened)).font(.caption).foregroundStyle(.secondary)
        }
    }

    private var title: String {
        if entry.outcome == .AUTH_FAILED { return "Sign-in refused" }
        let tool = entry.tool.flatMap { name in ToolCatalog.titles[name] } ?? entry.tool ?? entry.method
        return showAgent ? "\(entry.tokenName ?? "Unknown agent") · \(tool)" : tool
    }

    private var subtitle: String {
        entry.outcome.isSuccess ? (entry.argsPreview.flatMap { $0 == "{}" ? nil : $0 } ?? "No arguments") : "\(entry.outcome.label)\(entry.detail.map { ": \($0)" } ?? "")"
    }

    private var color: Color {
        switch entry.outcome {
        case .OK: return Palette.ok
        case .DENIED_SCOPE, .DENIED_POLICY, .DENIED_USER, .PAUSED, .CANCELLED, .UNKNOWN_TOOL: return Palette.warning
        case .AUTH_FAILED, .TOOL_ERROR, .TIMEOUT, .EXCEPTION: return Palette.error
        }
    }

    private var symbol: String {
        switch entry.outcome {
        case .OK: return "checkmark"
        case .AUTH_FAILED: return "lock.fill"
        case .DENIED_USER, .DENIED_SCOPE, .DENIED_POLICY: return "hand.raised.fill"
        case .PAUSED, .CANCELLED: return "pause.fill"
        default: return "exclamationmark"
        }
    }
}

struct ActivityDetailView: View {
    let entry: AuditEntry

    var body: some View {
        List {
            Section {
                LabeledContent("Agent", value: entry.tokenName ?? "Unknown")
                LabeledContent("Tool", value: entry.tool ?? entry.method)
                LabeledContent("Result", value: entry.outcome.label)
                LabeledContent("When", value: entry.timestamp.formatted(date: .abbreviated, time: .standard))
                if let ms = entry.durationMs { LabeledContent("Took", value: ms < 1000 ? "\(ms) ms" : String(format: "%.1f s", Double(ms) / 1000)) }
            }
            if let args = entry.argsPreview {
                Section("Arguments") { Text(args).font(.callout.monospaced()).textSelection(.enabled) }
            }
            if let detail = entry.detail {
                Section("Details") { Text(detail).font(.callout).textSelection(.enabled) }
            }
        }
        .navigationTitle(ToolCatalog.titles[entry.tool ?? ""] ?? "Activity")
        .navigationBarTitleDisplayMode(.inline)
    }
}

extension ToolCatalog {
    static let titles: [String: String] = Dictionary(uniqueKeysWithValues: all().map { ($0.name, $0.title) })
}
