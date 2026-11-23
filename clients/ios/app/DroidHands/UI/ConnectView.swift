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
    /// No agent was connected when the page opened: the one that connects now is the first, so confetti.
    @State private var firstAgent = false

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
                            withAnimation { started = true }
                        }
                        if waiting {
                            HStack(alignment: .center, spacing: 12) {
                                ProgressView()
                                    .tint(Palette.brand)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("Waiting for connection…")
                                        .font(.subheadline.weight(.semibold))
                                        .foregroundStyle(Palette.brand)
                                    Text("An approval prompt will appear here as soon as your agent connects.")
                                        .font(.footnote)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer(minLength: 0)
                            }
                            .padding(12)
                            .background(Palette.brand.opacity(0.10), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                            .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
                        }
                        HStack(alignment: .top, spacing: 10) {
                            Text("1").font(.footnote.weight(.bold)).foregroundStyle(.white)
                                .frame(width: 22, height: 22).background(Palette.brand, in: Circle())
                            Text("Send it to your agent (Claude, ChatGPT, Grok or any other) in a chat, or on the computer it runs on.")
                                .font(.subheadline)
                        }
                        HStack(alignment: .top, spacing: 10) {
                            Text("2").font(.footnote.weight(.bold)).foregroundStyle(.white)
                                .frame(width: 22, height: 22).background(Palette.brand, in: Circle())
                            Text("Tap Allow when this iPhone asks.")
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
                firstAgent = model.activeAgents.isEmpty
                initializedBaseline = true
            }
        }
        .onChange(of: newlyConnected?.id) { _, id in
            let key = "firstAgentCelebrated"
            guard id != nil, firstAgent, !UserDefaults.standard.bool(forKey: key) else { return }
            UserDefaults.standard.set(true, forKey: key)
            Confetti.burst()
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
    /// "Claude", or "Claude 2", "Claude 3"… when taken.
    private var defaultName: String {
        let taken = Set(model.agents.map { $0.name.lowercased() })
        if !taken.contains("claude") { return "Claude" }
        return (2...).lazy.map { "Claude \($0)" }.first { !taken.contains($0.lowercased()) }!
    }

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var grant = GrantDraft()
    @State private var expiry = ExpiryChoice.never
    @State private var token: String?
    @State private var error: String?
    @State private var copied = false
    @State private var confirmLeave = false

    var body: some View {
        NavigationStack {
            Form {
                if let token {
                    Section {
                        CopyableCode(text: token, label: "Copy token") { copied = true }
                    } header: {
                        Text("Token for \(name)")
                    } footer: {
                        Text("Shown only now. Send it as “Authorization: Bearer <token>” to \(model.connectURL.map { URL(string: $0)?.host ?? $0 } ?? "the relay")'s /mcp. Revoke it in Agents any time.")
                    }
                    if let url = model.connectURL {
                        Section {
                            CopyableCode(text: AgentPrompt.withToken(url, token: token), label: "Copy message") { copied = true }
                        } header: {
                            Text("Send this to your agent")
                        } footer: {
                            Text("It has the token: send it only to this agent.")
                        }
                    }
                } else {
                    Section("Name") { TextField("e.g. My laptop", text: $name) }
                    GrantEditor(grant: $grant)
                    Section {
                        Picker("Expires", selection: $expiry) {
                            ForEach(ExpiryChoice.allCases) { Text($0 == .never ? "Never" : "In \($0.rawValue)").tag($0) }
                        }
                    } footer: {
                        Text("After that the agent can't act until you pick a new expiry on its page.")
                    }
                    if let error { Section { Text(error).foregroundStyle(Palette.error) } }
                }
            }
            .navigationTitle(token == nil ? "New token" : "Token created")
            .navigationBarTitleDisplayMode(.inline)
            .interactiveDismissDisabled(token != nil && !copied)
            .onAppear { if name.isEmpty { name = defaultName } }
            .confirmationDialog("Leave without copying?", isPresented: $confirmLeave, titleVisibility: .visible) {
                Button("Leave", role: .destructive) { dismiss() }
                Button("Stay", role: .cancel) {}
            } message: {
                Text("The token can't be shown again; you'd have to create a new one.")
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(token == nil ? "Cancel" : "Done") {
                        if token != nil && !copied { confirmLeave = true } else { dismiss() }
                    }
                }
                if token == nil {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Create") {
                            do { token = try model.issueManualToken(name: name, grant: grant, expiresIn: expiry.interval) } catch { self.error = error.localizedDescription }
                        }
                        .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || grant.error != nil)
                    }
                }
            }
        }
        .promptSheets()
    }
}

/// A short confetti burst over the whole window, from the top edge. The view ignores touches, so the screen
/// below stays usable, and removes itself when done. Nothing happens with Reduce Motion on.
@MainActor
enum Confetti {
    static func burst() {
        guard !UIAccessibility.isReduceMotionEnabled,
              let window = UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene })
                .flatMap(\.windows)
                .first(where: \.isKeyWindow)
        else { return }
        let view = UIView(frame: window.bounds)
        view.isUserInteractionEnabled = false
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.accessibilityElementsHidden = true
        window.addSubview(view)

        let emitter = CAEmitterLayer()
        emitter.emitterPosition = CGPoint(x: window.bounds.midX, y: -10)
        emitter.emitterShape = .line
        emitter.emitterSize = CGSize(width: window.bounds.width, height: 1)
        let colors: [UIColor] = [.systemBlue, .systemTeal, .systemYellow, .systemPink, .systemPurple, .systemGreen]
        emitter.emitterCells = colors.flatMap { color in [piece(color, size: CGSize(width: 10, height: 6)), piece(color, size: CGSize(width: 7, height: 7), round: true)] }
        emitter.beginTime = CACurrentMediaTime()
        view.layer.addSublayer(emitter)
        UINotificationFeedbackGenerator().notificationOccurred(.success)

        // Stop emitting after a short burst, let the pieces fall, then fade away.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { emitter.birthRate = 0 }
        DispatchQueue.main.asyncAfter(deadline: .now() + 3.0) {
            UIView.animate(withDuration: 0.6, animations: { view.alpha = 0 }, completion: { _ in view.removeFromSuperview() })
        }
    }

    private static func piece(_ color: UIColor, size: CGSize, round: Bool = false) -> CAEmitterCell {
        let cell = CAEmitterCell()
        cell.contents = UIGraphicsImageRenderer(size: size).image { ctx in
            color.setFill()
            let rect = CGRect(origin: .zero, size: size)
            if round { ctx.cgContext.fillEllipse(in: rect) } else { ctx.fill(rect) }
        }.cgImage
        cell.birthRate = 6
        cell.lifetime = 6
        cell.velocity = 180
        cell.velocityRange = 80
        cell.emissionLongitude = .pi
        cell.emissionRange = .pi / 5
        cell.yAcceleration = 220
        cell.spin = 3
        cell.spinRange = 6
        cell.scaleRange = 0.4
        return cell
    }
}
