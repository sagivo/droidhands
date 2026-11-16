import SwiftUI

/// The app the UI-test runner is attached to. It does nothing: the runner (the DroidHandsRunner UI test) serves the
/// host's requests while its test runs.
@main
struct DroidHandsRunnerHostApp: App {
    var body: some Scene {
        WindowGroup {
            Text("DroidHands runner — keep this running")
                .multilineTextAlignment(.center)
                .padding()
        }
    }
}
