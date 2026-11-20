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

/// Shared "Connect an AI agent" sections used by both `ConnectView` and onboarding's Add-an-agent step.
struct ConnectAgentSections: View {
    @Environment(AppModel.self) private var model
    let onUseToken: () -> Void
    let onOpenMoreOptions: () -> Void
    var onOpenConnectionSettings: (() -> Void)? = nil
    var onViewAgent: ((TokenRecord) -> Void)? = nil

    @State private var baseline: Set<String> = []
    @State private var initializedBaseline = false
    @State private var started = false
    @State private var addAnother = false

    private var newlyConnected: TokenRecord? {
        guard initializedBaseline else { return nil }
        return model.activeAgents.first { !baseline.contains($0.id) }
    }

    var body: some View {
        Group {
            if let agent = newlyConnected {
                Section {
                    HStack(spacing: 12) {
                        IconTile(symbol: "checkmark.circle.fill", color: Palette.ok)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("\(agent.name) connected").font(.headline)
                            Text("Try asking it \"What's my battery level?\"")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                        Spacer(minLength: 4)
                        if let onViewAgent {
                            Button("View") { onViewAgent(agent) }
                                .buttonStyle(.borderedProminent)
                                .buttonBorderShape(.capsule)
                                .controlSize(.small)
                        }
                    }
                    .padding(.vertical, 4)
                }
            }

            if let url = model.liveConnectURL {
                if let agent = newlyConnected, !addAnother {
                    Section {
                        Button("Add another agent") {
                            addAnother = true
                            started = false
                            baseline.insert(agent.id)
                        }
                        .frame(maxWidth: .infinity)
                    }
                } else {
                    let waiting = started && newlyConnected == nil && model.pairings.isEmpty
                    Section {
                        CopyableCode(text: AgentPrompt.signIn(url), label: "Copy message") {
                            started = true
                        }
                        HStack(alignment: .top, spacing: 10) {
                            Text("1").font(.footnote.weight(.bold)).foregroundStyle(.white)
                                .frame(width: 22, height: 22).background(Palette.brand, in: Circle())
                            Text("Send it to your agent (Claude, ChatGPT, Grok or any other) in a chat, or on the computer it runs on.")
                                .font(.subheadline)
                        }
                        HStack(alignment: .top, spacing: 10) {
                            if waiting {
                                ProgressView()
                                    .frame(width: 22, height: 22)
                            } else {
                                Text("2").font(.footnote.weight(.bold)).foregroundStyle(.white)
                                    .frame(width: 22, height: 22).background(Palette.brand, in: Circle())
                            }
                            Text(waiting ? "Waiting for your agent… then tap Allow when this iPhone asks." : "Tap Allow when this iPhone asks.")
                                .font(.subheadline)
                        }
                        Button("Agent can't sign in? Use a token instead", action: onUseToken)
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
                }
            } else if model.relayState.isConnected {
                Section {
                    Notice(kind: .warning, title: "One-tap connect isn't available",
                           message: "Your relay is an older version without one-tap connect. Update the relay, or use a token below.")
                }
                .listRowInsets(EdgeInsets()).listRowBackground(Color.clear)
                Section {
                    Button("Use a token instead", action: onUseToken)
                }
            } else if model.settings.relayEnabled {
                Section {
                    TileRow(
                        symbol: "antenna.radiowaves.left.and.right",
                        color: model.relayState.color,
                        title: model.relayState.title,
                        subtitle: model.relayState.detail ?? RelayURLs.host(of: model.settings.relayURL)
                    )
                    if let onOpenConnectionSettings {
                        Button("Connection settings", action: onOpenConnectionSettings)
                    }
                } footer: {
                    Text("Your connection URL appears here as soon as this iPhone is connected to the relay.")
                }
                if model.relayState.isAccountRequired {
                    Section {
                        AccountRequiredNotice()
                    }
                    .listRowInsets(EdgeInsets()).listRowBackground(Color.clear)
                }
            } else if model.canOfferDefaultRelay, let offerURL = model.defaultRelayURL {
                let host = RelayURLs.host(of: offerURL) ?? offerURL
                Section {
                    VStack(alignment: .leading, spacing: 12) {
                        Text(model.signInNeeded
                             ? "Sign in with Google for a free DroidHands account on \(host). Works with every agent on Wi-Fi or mobile data."
                             : "Connect through \(host), the relay this app comes with. Works with every agent on Wi-Fi or mobile data.")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        DefaultRelayButton()
                    }
                    .padding(.vertical, 4)
                } header: {
                    Text("Connect using DroidHands")
                } footer: {
                    Text(AccountCopy.defaultRelayNote)
                }
                Section {
                    Button("Do it yourself", action: onOpenMoreOptions)
                        .frame(maxWidth: .infinity)
                }
                .listRowBackground(Color.clear)
            } else {
                RelaySetupFormSection(
                    title: "First, connect this iPhone to your relay",
                    intro: "Agents reach this iPhone through your self-hosted relay. Paste its URL, or open the relay's setup page (/setup) on this iPhone and tap \"Open in DroidHands\"."
                )
            }
        }
        .onAppear {
            if !initializedBaseline {
                baseline = Set(model.agents.map(\.id))
                initializedBaseline = true
            }
        }
    }
}

struct ConnectView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    var onOpenAgent: ((String) -> Void)? = nil
    @State private var showToken = false
    @State private var showMoreOptions = false

    var body: some View {
        NavigationStack {
            List {
                ConnectAgentSections(
                    onUseToken: { showToken = true },
                    onOpenMoreOptions: { showMoreOptions = true },
                    onOpenConnectionSettings: { showMoreOptions = true },
                    onViewAgent: onOpenAgent.map { open in
                        { record in
                            dismiss()
                            open(record.id)
                        }
                    }
                )
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Connect an AI agent")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .navigationDestination(isPresented: $showMoreOptions) {
                MoreConnectOptionsView(onContinue: { showMoreOptions = false })
            }
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
