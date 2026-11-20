import SwiftUI

enum AccountCopy {
    /// Why the DroidHands relay asks for an account, in one line.
    static let accountReason =
        "The DroidHands relay needs a free DroidHands account. Sign in with Google: it takes a few seconds."

    /// What the default relay can and can't do (docs/SECURITY.md, "Relay operator").
    static let defaultRelayNote =
        "The relay forwards your agents' requests to this iPhone over HTTPS. It can't do anything by itself: every agent " +
        "still needs your Allow here, and you can switch to your own relay at any time."
}

/// The 4-color Google "G" on a crisp white circle so it reads cleanly against the brand button.
struct GoogleLogoView: View {
    var body: some View {
        ZStack {
            Circle()
                .fill(Color.white)
                .frame(width: 22, height: 22)
            Image("GoogleLogo")
                .resizable()
                .scaledToFit()
                .frame(width: 15, height: 15)
        }
        .accessibilityHidden(true)
    }
}

/// "Continue with Google", spinning while the sign-in finishes, with the last attempt's error below it.
struct SignInWithGoogleButton: View {
    @Environment(AppModel.self) private var model
    var useRelay: Bool = true

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Button {
                model.startSignIn(useRelay: useRelay)
            } label: {
                HStack(spacing: 10) {
                    if model.signInState == .finishing {
                        ProgressView()
                            .tint(.white)
                    } else {
                        GoogleLogoView()
                    }
                    Text("Continue with Google")
                        .fontWeight(.semibold)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 6)
            }
            .buttonStyle(.borderedProminent)
            .buttonBorderShape(.capsule)
            .controlSize(.large)
            .disabled(model.signInState == .finishing)

            if case .failed(let message) = model.signInState {
                Notice(kind: .danger, title: "Sign-in failed", message: message)
            }
        }
    }
}

/// The button that puts this iPhone online with the default relay: "Continue with Google" while sign-in
/// is needed (coming back signed in goes online by itself), else "Go online".
struct DefaultRelayButton: View {
    @Environment(AppModel.self) private var model
    var label: String = "Go online"

    var body: some View {
        if model.signInNeeded {
            SignInWithGoogleButton(useRelay: true)
        } else {
            Button {
                model.useDefaultRelay()
            } label: {
                Text(label)
                    .fontWeight(.semibold)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
            }
            .buttonStyle(.borderedProminent)
            .buttonBorderShape(.capsule)
            .controlSize(.large)
        }
    }
}

/// Shown when the relay refused this iPhone because nobody is signed in (or the session ended).
struct AccountRequiredNotice: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if model.relayState.isAccountRequired {
            Notice(kind: .warning, title: "Sign in to go online", message: AccountCopy.accountReason) {
                SignInWithGoogleButton(useRelay: true)
            }
        }
    }
}

/// Settings › Account (shown when the default relay needs a DroidHands account).
struct AccountSettingsSection: View {
    @Environment(AppModel.self) private var model
    @State private var confirmSignOut = false
    @State private var confirmDelete = false

    var body: some View {
        if let session = model.accountSession {
            let user = session.user
            Section {
                HStack(spacing: 12) {
                    Monogram(name: user.displayName, size: 34)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(user.displayName)
                        if let email = user.subtitleEmail {
                            Text(email).font(.subheadline).foregroundStyle(.secondary)
                        }
                    }
                }
                Button("Sign out") { confirmSignOut = true }
                    .disabled(model.accountBusy)
                Button("Delete account", role: .destructive) { confirmDelete = true }
                    .disabled(model.accountBusy)
            } header: {
                Text("Account")
            } footer: {
                Text("Signed in with Google. The DroidHands relay uses your account to let this iPhone connect.")
            }
            .confirmationDialog("Sign out?", isPresented: $confirmSignOut, titleVisibility: .visible) {
                Button("Sign out") { Task { await model.signOut() } }
            } message: {
                Text("Agents can't reach this iPhone through the DroidHands relay until you sign in again. Your own relay keeps working.")
            }
            .confirmationDialog("Delete your account?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Delete account", role: .destructive) { Task { await model.deleteAccount() } }
            } message: {
                Text("Your DroidHands account (your name and email) is deleted for good, and this iPhone signs out. Agents you added stay on this iPhone, but can't reach it through the DroidHands relay.")
            }
        } else {
            Section {
                HStack(spacing: 12) {
                    IconTile(symbol: "person.fill", color: Palette.off)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Not signed in")
                        if case .failed(let message) = model.signInState {
                            Text(message).font(.subheadline).foregroundStyle(Palette.error)
                        }
                    }
                    Spacer()
                    Button("Sign in") {
                        model.startSignIn(useRelay: model.canOfferDefaultRelay)
                    }
                    .buttonStyle(.bordered)
                    .buttonBorderShape(.capsule)
                    .disabled(model.signInState == .finishing)
                }
            } header: {
                Text("Account")
            } footer: {
                Text(AccountCopy.accountReason)
            }
        }
    }
}
