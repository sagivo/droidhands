import SwiftUI

/// The app the UI-test runner is attached to. It does nothing: the runner (the PhoneBridgeRunner UI test) serves the
/// host's requests while its test runs.
@main
struct PhoneBridgeRunnerHostApp: App {
    var body: some Scene {
        WindowGroup {
            Text("PhoneBridge runner — keep this running")
                .multilineTextAlignment(.center)
                .padding()
        }
    }
}
