import SwiftUI
import UserNotifications

@main
struct DroidHandsApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @State private var model = AppModel()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .onOpenURL { model.handle(url: $0) }
                .onChange(of: phase, initial: true) { _, phase in
                    switch phase {
                    case .active: model.didBecomeActive()
                    case .background: model.didEnterBackground()
                    default: break
                    }
                }
                #if DEBUG
                .task { DebugHooks.run(model) }
                #endif
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        _ = CallWatcher.shared
        _ = NetworkStatus.shared
        return true
    }

    /// The prompts are already on screen when the app is in front; don't show a banner too.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions { [] }
}

#if DEBUG
/// Test hooks for scripts/ios-app.sh (Debug builds only, never in the App Store build).
enum DebugHooks {
    @MainActor static func run(_ model: AppModel) {
        let env = ProcessInfo.processInfo.environment
        if let relay = env["DH_RELAY_URL"], model.settings.relayURL != RelayURLs.normalize(relay) || !model.settings.relayEnabled {
            model.settings.onboarded = true
            model.connectRelay(url: relay, key: env["DH_ACCESS_KEY"])
        }
        if env["DH_SKIP_ONBOARDING"] == "1" { model.settings.onboarded = true }
        if env["DH_RESET_ONBOARDING"] == "1" { model.settings.onboarded = false }
        // Writes a Full-access token to Documents/test-token.txt (read it with `simctl get_app_container`).
        if let name = env["DH_TEST_TOKEN_NAME"], let token = try? model.issueManualToken(name: name, grant: GrantDraft()) {
            let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("test-token.txt")
            try? token.write(to: url, atomically: true, encoding: .utf8)
        }
        if env["DH_AUTO_APPROVE_PAIRING"] == "1" {
            Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { _ in
                Task { @MainActor in model.pairings.first.map(model.approve) }
            }
        }
    }
}
#endif
