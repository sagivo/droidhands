import AuthenticationServices
import UIKit

/// Opens the DroidHands Google sign-in page in iOS's native authentication browser sheet (`ASWebAuthenticationSession`)
/// and delivers the `droidhands://signed-in?…` callback URL back to the app (RFC 8252).
@MainActor
final class AccountBrowser: NSObject, ASWebAuthenticationPresentationContextProviding {
    enum Outcome {
        case callback(URL)
        case cancelled
        case failed(String)
    }

    private var activeSession: ASWebAuthenticationSession?

    func open(url: URL, completion: @escaping (Outcome) -> Void) {
        activeSession?.cancel()
        let session = ASWebAuthenticationSession(url: url, callbackURLScheme: SignInLinks.scheme) { [weak self] callbackURL, error in
            Task { @MainActor in
                self?.activeSession = nil
                if let callbackURL {
                    completion(.callback(callbackURL))
                } else if let err = error as? ASWebAuthenticationSessionError, err.code == .canceledLogin {
                    completion(.cancelled)
                } else {
                    completion(.failed(error?.localizedDescription ?? "Couldn't open the sign-in page."))
                }
            }
        }
        session.presentationContextProvider = self
        session.prefersEphemeralWebBrowserSession = false
        activeSession = session
        if !session.start() {
            activeSession = nil
            completion(.failed("Couldn't open the sign-in page."))
        }
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let active = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
        if let key = active?.windows.first(where: \.isKeyWindow) { return key }
        if let first = active?.windows.first { return first }
        if let active { return UIWindow(windowScene: active) }
        return ASPresentationAnchor()
    }
}
