import SwiftUI

/// Self-hosted relay setup form: accepts a plain relay URL or a pasted setup link (`…/setup#key=…` or
/// `droidhands://configure…`), with optional access key and Show/Hide toggle.
struct RelaySetupFormSection: View {
    @Environment(AppModel.self) private var model
    @State private var url: String
    @State private var key: String
    @State private var showKey = false
    var title: String = "Your own relay"
    var intro: String? =
        "Your relay prints its setup link, with a QR code, when it starts. Open the setup page on this iPhone (its address ends in /setup) and tap \"Open in DroidHands\", or paste the setup link or relay address here:"
    var footer: String? = "Run your own open-source relay with Docker or Node.js (see github.com/sagivo/droidhands)."

    init(initialURL: String = "", initialKey: String = "", title: String = "Your own relay",
         intro: String? = "Your relay prints its setup link, with a QR code, when it starts. Open the setup page on this iPhone (its address ends in /setup) and tap \"Open in DroidHands\", or paste the setup link or relay address here:",
         footer: String? = "Run your own open-source relay with Docker or Node.js (see github.com/sagivo/droidhands).") {
        _url = State(initialValue: initialURL)
        _key = State(initialValue: initialKey)
        self.title = title
        self.intro = intro
        self.footer = footer
    }

    private var check: RelayURLCheck { RelayURLs.check(url) }
    private var isSetupLink: Bool { SetupLinkParser.isSetupLink(url) }
    private var usable: Bool {
        if isSetupLink { return true }
        switch check {
        case .ok, .insecure: return true
        case .empty, .invalid: return false
        }
    }

    var body: some View {
        Section {
            if let intro {
                Text(intro)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            TextField(text: $url, prompt: Text(verbatim: "https://relay.example.com")) {
                Text("Relay URL")
            }
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            if isSetupLink {
                Text("A setup link: tap Connect to review it.")
                    .font(.footnote)
                    .foregroundStyle(Palette.brand)
            } else if case .invalid(let message) = check {
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(Palette.error)
            } else if case .insecure(_, let message) = check {
                Notice(kind: .danger, title: "Plain HTTP", message: message)
            }
            HStack {
                if showKey {
                    TextField("Access key (if your relay has one)", text: $key)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } else {
                    SecureField("Access key (if your relay has one)", text: $key)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
                if !key.isEmpty {
                    Button(showKey ? "Hide" : "Show") { showKey.toggle() }
                        .font(.subheadline.weight(.medium))
                }
            }
            Button {
                if isSetupLink {
                    model.offerSetupLink(url)
                } else {
                    let trimmedKey = key.trimmingCharacters(in: .whitespacesAndNewlines)
                    model.connectRelay(url: url, key: trimmedKey.isEmpty ? nil : trimmedKey)
                }
            } label: {
                Text("Connect")
                    .fontWeight(.semibold)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 4)
            }
            .buttonStyle(.borderedProminent)
            .buttonBorderShape(.capsule)
            .disabled(!usable)
        } header: {
            Text(title)
        } footer: {
            if let footer { Text(footer) }
        }
    }
}

/// "More ways to connect": self-hosted relay setup. Opened from "Do it yourself" / "More ways to connect"
/// in onboarding and on the Connect screen.
struct MoreConnectOptionsView: View {
    @Environment(AppModel.self) private var model
    var onContinue: (() -> Void)? = nil

    private var ownRelayConnected: Bool {
        !model.defaultRelayInUse && model.relayState.isConnected
    }

    private var initialURL: String {
        model.defaultRelayInUse ? "" : model.settings.relayURL
    }

    private var initialKey: String {
        model.defaultRelayInUse ? "" : (model.settings.accessKey ?? "")
    }

    var body: some View {
        List {
            Section {
                Text("Connect without the DroidHands relay: host your own open-source relay on a computer, a server, or your tailnet.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: 6, leading: 4, bottom: 8, trailing: 4))
            }

            if model.settings.relayEnabled || model.relayState != .stopped {
                Section("Status") {
                    TileRow(
                        symbol: "antenna.radiowaves.left.and.right",
                        color: model.relayState.color,
                        title: model.relayState.title,
                        subtitle: model.relayState.detail ?? RelayURLs.host(of: model.settings.relayURL)
                    ) {
                        if model.relayState.isConnected {
                            StatusPill(text: "Live", color: Palette.ok, dot: true)
                        }
                    }
                }
            }

            RelaySetupFormSection(initialURL: initialURL, initialKey: initialKey)
        }
        .listStyle(.insetGrouped)
        .toolbar(.visible, for: .navigationBar)
        .navigationTitle("More ways to connect")
        .navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom) {
            if let onContinue, ownRelayConnected {
                Button {
                    onContinue()
                } label: {
                    Text("Continue")
                        .fontWeight(.semibold)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                }
                .buttonStyle(.borderedProminent)
                .buttonBorderShape(.capsule)
                .controlSize(.large)
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
                .background(.bar)
            }
        }
    }
}
