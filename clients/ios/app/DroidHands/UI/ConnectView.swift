import SwiftUI

/// How to add DroidHands to each agent: one action, then at most two short steps; the phone's "Allow" follows.
enum AgentClient: String, CaseIterable, Identifiable {
    case claude = "Claude", chatgpt = "ChatGPT", grok = "Grok", muse = "Muse", claudeCode = "Claude Code", other = "Other agent"
    var id: String { rawValue }

    static let connectorName = "DroidHands"

    struct Guide {
        var openLabel: String?
        var openURL: URL?
        var copyFirst = false
        var message: String?
        var command: String?
        var steps: [String]
    }

    func guide(_ url: String) -> Guide {
        switch self {
        case .claude:
            return Guide(openLabel: "Add to Claude", openURL: AgentClient.claudeLink(url), steps: ["Claude opens with DroidHands filled in. Tap Add, then Connect."])
        case .chatgpt:
            return Guide(openLabel: "Copy URL and open ChatGPT", openURL: URL(string: "https://chatgpt.com/plugins"), copyFirst: true,
                         steps: ["Stay in the browser if it offers the ChatGPT app. Tap Add, then Add custom MCP server.",
                                 "Name it DroidHands, paste the URL, keep OAuth, tick I understand, then Create as a plugin."])
        case .grok:
            return Guide(openLabel: "Copy URL and open Grok", openURL: URL(string: "https://grok.com/connectors"), copyFirst: true,
                         steps: ["Tap New Connector, then Custom. Paste the URL and tap Connect. Needs a paid Grok plan."])
        case .muse:
            return Guide(message: AgentClient.message(url),
                         steps: ["Send the message in Muse, then tap Connect on the card it shows.",
                                 "If Muse asks for a Client ID, type droidhands. Tap Add."])
        case .claudeCode:
            return Guide(command: "claude mcp add --transport http droidhands \(url)",
                         steps: ["Run this on your computer, then type /mcp in Claude Code and sign in."])
        case .other:
            return Guide(message: AgentClient.message(url), steps: ["Send this to your agent, in its chat or on the computer it runs on."])
        }
    }

    static func claudeLink(_ url: String) -> URL? {
        var c = URLComponents(string: "https://claude.ai/customize/connectors")!
        c.queryItems = [URLQueryItem(name: "modal", value: "add-custom-connector"),
                        URLQueryItem(name: "connectorName", value: connectorName),
                        URLQueryItem(name: "connectorUrl", value: url)]
        return c.url
    }

    static func message(_ url: String) -> String {
        "Connect to my phone with DroidHands: add \(url) as a custom connector (remote MCP server, OAuth sign-in; if it needs a client ID, use droidhands with no secret) and connect to it. No connector settings? Open that link: it shows how to sign in yourself."
    }
}

struct ConnectView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var client: AgentClient = .claude
    @State private var showToken = false

    var body: some View {
        NavigationStack {
            List {
                if let url = model.connectURL {
                    Section {
                        Picker("Agent", selection: $client) {
                            ForEach(AgentClient.allCases) { Text($0.rawValue).tag($0) }
                        }
                    } footer: {
                        Text("Keep DroidHands open: when the agent connects, you tap Allow here.")
                    }
                    let guide = client.guide(url)
                    Section {
                        if let label = guide.openLabel, let link = guide.openURL {
                            Button {
                                if guide.copyFirst { UIPasteboard.general.string = url }
                                openURL(link)
                            } label: {
                                Label(label, systemImage: "arrow.up.forward.app")
                                    .frame(maxWidth: .infinity).padding(.vertical, 4)
                            }
                            .buttonStyle(.borderedProminent)
                            .buttonBorderShape(.capsule)
                            .listRowBackground(Color.clear)
                            .listRowInsets(EdgeInsets())
                        }
                        if let message = guide.message { CopyableCode(text: message, label: "Copy message") }
                        if let command = guide.command { CopyableCode(text: command, label: "Copy command") }
                        ForEach(Array(guide.steps.enumerated()), id: \.offset) { i, step in
                            HStack(alignment: .top, spacing: 10) {
                                Text("\(i + 1)").font(.footnote.weight(.bold)).foregroundStyle(.white)
                                    .frame(width: 22, height: 22).background(Palette.brand, in: Circle())
                                Text(step).font(.subheadline)
                            }
                        }
                        HStack(alignment: .top, spacing: 10) {
                            Image(systemName: "hand.tap.fill").foregroundStyle(Palette.brand).frame(width: 22)
                            Text("Tap Allow when DroidHands asks.").font(.subheadline)
                        }
                    } header: {
                        Text(client.rawValue)
                    }
                    Section {
                        CopyableCode(text: url, label: "Copy URL")
                    } header: {
                        Text("Connection URL")
                    }
                    Section {
                        Button("Use a token instead") { showToken = true }
                    } footer: {
                        Text("For MCP clients that can't sign in: make a token here and paste it as a bearer token.")
                    }
                } else {
                    Section {
                        Notice(kind: .warning, title: "Not connected yet", message: model.relayState.detail ?? "Waiting for the relay to give this iPhone its connection URL. Check the internet connection.")
                    }
                    .listRowInsets(EdgeInsets()).listRowBackground(Color.clear)
                }
            }
            .navigationTitle("Connect an AI agent")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .sheet(isPresented: $showToken) { ManualTokenView() }
        }
    }
}

/// Makes a token by hand; its secret is shown once.
struct ManualTokenView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var grant = GrantDraft()
    @State private var token: String?
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                if let token {
                    Section {
                        CopyableCode(text: token, label: "Copy token")
                    } header: {
                        Text("Token for \(name)")
                    } footer: {
                        Text("Shown only now. Send it as “Authorization: Bearer <token>” to \(model.connectURL.map { URL(string: $0)?.host ?? $0 } ?? "the relay")'s /mcp. Revoke it in Agents any time.")
                    }
                    if let url = model.connectURL {
                        Section("Claude Code") {
                            CopyableCode(text: "claude mcp add --transport http droidhands \(url) --header \"Authorization: Bearer \(token)\"", label: "Copy command")
                        }
                    }
                } else {
                    Section("Name") { TextField("e.g. My laptop", text: $name) }
                    GrantEditor(grant: $grant)
                    if let error { Section { Text(error).foregroundStyle(Palette.error) } }
                }
            }
            .navigationTitle("Token")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(token == nil ? "Cancel" : "Done") { dismiss() } }
                if token == nil {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Create") {
                            do { token = try model.issueManualToken(name: name, grant: grant) } catch { self.error = error.localizedDescription }
                        }
                        .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || grant.error != nil)
                    }
                }
            }
        }
    }
}
