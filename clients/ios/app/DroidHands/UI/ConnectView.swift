import SwiftUI

/// The one message that adds DroidHands to any agent; the agent-specific steps live at `instructionsURL`.
enum AgentPrompt {
    static let connectorName = "DroidHands"
    static let instructionsURL = "https://droidhands.com/connect.md"

    /// For a URL the agent signs in to: the person only taps Allow.
    static func signIn(_ url: String) -> String {
        "\(intro(url)) No password or token is needed: I'll tap Allow in the DroidHands app on my phone."
    }

    /// For a token made on the phone, sent as a bearer token.
    static func withToken(_ url: String, token: String) -> String {
        "\(intro(url)) Use this token as the bearer token: \(token)"
    }

    private static func intro(_ url: String) -> String {
        "Add a new connector named \(connectorName) with the URL \(url) and follow the instructions at \(instructionsURL)."
    }
}

struct ConnectView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var showToken = false

    var body: some View {
        NavigationStack {
            List {
                if let url = model.connectURL {
                    Section {
                        CopyableCode(text: AgentPrompt.signIn(url), label: "Copy message")
                        HStack(alignment: .top, spacing: 10) {
                            Text("1").font(.footnote.weight(.bold)).foregroundStyle(.white)
                                .frame(width: 22, height: 22).background(Palette.brand, in: Circle())
                            Text("Send it to your agent (Claude, ChatGPT, Grok or any other) in a chat, or on the computer it runs on.").font(.subheadline)
                        }
                        HStack(alignment: .top, spacing: 10) {
                            Image(systemName: "hand.tap.fill").foregroundStyle(Palette.brand).frame(width: 22)
                            Text("Tap Allow when DroidHands asks.").font(.subheadline)
                        }
                    } header: {
                        Text("Send this to your AI agent")
                    } footer: {
                        Text("Keep DroidHands open: when the agent connects, you tap Allow here.")
                    }
                    Section {
                        CopyableCode(text: url, label: "Copy URL")
                    } header: {
                        Text("Connection URL")
                    }
                    Section {
                        Button("Use a token instead") { showToken = true }
                    } footer: {
                        Text("For agents that can't sign in: make a token here, and DroidHands gives you a message with it.")
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
        .promptSheets()
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
                        Section {
                            CopyableCode(text: AgentPrompt.withToken(url, token: token), label: "Copy message")
                        } header: {
                            Text("Send this to your agent")
                        } footer: {
                            Text("It has the token: send it only to this agent.")
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
        .promptSheets()
    }
}
