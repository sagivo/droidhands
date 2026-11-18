import SwiftUI
import UserNotifications

/// First launch: what DroidHands is, what it can and can't do on iPhone, then the permissions worth giving now.
struct OnboardingView: View {
    @Environment(AppModel.self) private var model
    @State private var page = 0

    var body: some View {
        VStack(spacing: 0) {
            TabView(selection: $page) {
                welcome.tag(0)
                permissions.tag(1)
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .animation(.default, value: page)

            Button {
                if page == 0 { page = 1 } else { model.settings.onboarded = true }
            } label: {
                Text(page == 0 ? "Continue" : "Get started").frame(maxWidth: .infinity).padding(.vertical, 6)
            }
            .buttonStyle(.borderedProminent)
            .buttonBorderShape(.capsule)
            .controlSize(.large)
            .padding()
        }
        .background(Color(.systemGroupedBackground))
    }

    private var welcome: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                Image("Logo")
                    .resizable().frame(width: 72, height: 72)
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .padding(.top, 40)
                Text("Let your AI agents use your iPhone").font(.largeTitle.weight(.bold))
                Text("Connect Claude, ChatGPT or any agent that speaks MCP. Ask it to add a meeting, remind you of something, look someone up, start a message or run one of your shortcuts.")
                    .foregroundStyle(.secondary)
                VStack(alignment: .leading, spacing: 16) {
                    Point(symbol: "hand.tap.fill", title: "You decide once", text: "When an agent connects, you choose what it may do. You can pause or revoke it any time.")
                    Point(symbol: "lock.shield.fill", title: "Private by design", text: "No account. Requests go from your agent to this iPhone through a relay that stores nothing.")
                    Point(symbol: "iphone", title: "Keep DroidHands open", text: "iPhone lets DroidHands act only while the app is on screen.")
                }
            }
            .padding(24)
        }
    }

    private var permissions: some View {
        List {
            Section {
                Text("Allow what you want agents to use. You can change this later in Settings.").foregroundStyle(.secondary)
                    .listRowBackground(Color.clear)
            }
            PermissionsRows()
        }
    }
}

private struct Point: View {
    let symbol: String
    let title: String
    let text: String

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: symbol).font(.title3).foregroundStyle(Palette.brand).frame(width: 30)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline)
                Text(text).font(.subheadline).foregroundStyle(.secondary)
            }
        }
    }
}

private struct PermissionsRows: View {
    @State private var refresh = 0

    var body: some View {
        Section {
            ForEach(Permission.allCases) { p in
                HStack {
                    TileRow(symbol: p.symbol, color: Palette.brand, title: p.title, subtitle: p.purpose)
                    Spacer()
                    if p.status == .granted {
                        Image(systemName: "checkmark.circle.fill").foregroundStyle(Palette.ok)
                    } else if p.status == .notAsked {
                        Button("Allow") { Task { _ = await p.request(); refresh += 1 } }
                            .buttonStyle(.bordered).buttonBorderShape(.capsule)
                    } else {
                        Text("Off").foregroundStyle(.secondary)
                    }
                }
            }
            HStack {
                TileRow(symbol: "bell.badge.fill", color: Palette.brand, title: "Notifications", subtitle: "When an agent waits for you")
                Spacer()
                Button("Allow") {
                    Task { _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) }
                }
                .buttonStyle(.bordered).buttonBorderShape(.capsule)
            }
        }
        .id(refresh)
    }
}
