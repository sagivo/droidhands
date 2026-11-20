import SwiftUI

/// The design system of docs/DESIGN.md, in SwiftUI terms: inset grouped lists, colored icon tiles, one brand
/// accent (the asset catalog's AccentColor), status pills, and red only for danger.
enum Palette {
    static let ok = Color.green
    static let warning = Color.orange
    static let error = Color.red
    static let off = Color.gray
    static let brand = Color.accentColor
}

/// A white glyph on a colored rounded square, like iOS Settings.
struct IconTile: View {
    let symbol: String
    let color: Color

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: 15, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: 30, height: 30)
            .background(color.gradient, in: RoundedRectangle(cornerRadius: 7, style: .continuous))
    }
}

/// A row with a leading tile, a title and an optional subtitle.
struct TileRow<Trailing: View>: View {
    let symbol: String
    let color: Color
    let title: String
    var subtitle: String?
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        HStack(spacing: 12) {
            IconTile(symbol: symbol, color: color)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                if let subtitle {
                    Text(subtitle).font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
                }
            }
            Spacer(minLength: 8)
            trailing()
        }
    }
}

extension TileRow where Trailing == EmptyView {
    init(symbol: String, color: Color, title: String, subtitle: String? = nil) {
        self.init(symbol: symbol, color: color, title: title, subtitle: subtitle) { EmptyView() }
    }
}

/// The only badge style: a tinted capsule, optionally with a dot.
struct StatusPill: View {
    let text: String
    let color: Color
    var dot = false

    var body: some View {
        HStack(spacing: 5) {
            if dot { Circle().fill(color).frame(width: 7, height: 7) }
            Text(text).font(.caption.weight(.semibold))
        }
        .foregroundStyle(color)
        .padding(.horizontal, 9)
        .padding(.vertical, 4)
        .background(color.opacity(0.12), in: Capsule())
    }
}

/// A two-letter circle for agents or accounts.
struct Monogram: View {
    let name: String
    var size: CGFloat = 36

    var body: some View {
        let letters = name.split(separator: " ").prefix(2).compactMap(\.first).map(String.init).joined().uppercased()
        Text(letters.isEmpty ? "?" : letters)
            .font(.system(size: size * 0.4, weight: .semibold))
            .foregroundStyle(Palette.brand)
            .frame(width: size, height: size)
            .background(Palette.brand.opacity(0.14), in: Circle())
    }
}

/// Monospaced text in a filled box with Copy and Share buttons.
struct CopyableCode: View {
    let text: String
    var label = "Copy"
    var onCopied: (() -> Void)? = nil
    @State private var copied = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(text)
                .font(.system(.callout, design: .monospaced))
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(12)
                .background(Color(.tertiarySystemFill), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            HStack {
                Button {
                    UIPasteboard.general.string = text
                    copied = true
                    onCopied?()
                    Task { try? await Task.sleep(nanoseconds: 1_500_000_000); copied = false }
                } label: {
                    Label(copied ? "Copied" : label, systemImage: copied ? "checkmark" : "doc.on.doc")
                }
                .buttonStyle(.bordered)
                .buttonBorderShape(.capsule)
                ShareLink(item: text) { Label("Share", systemImage: "square.and.arrow.up") }
                    .buttonStyle(.borderless)
                    .simultaneousGesture(TapGesture().onEnded { onCopied?() })
            }
        }
    }
}

/// A callout: info, warning or danger, with an optional action below its text.
struct Notice<Action: View>: View {
    enum Kind { case info, warning, danger }
    let kind: Kind
    let title: String
    var message: String?
    @ViewBuilder var action: () -> Action

    private var color: Color { kind == .info ? Palette.brand : kind == .warning ? Palette.warning : Palette.error }
    private var symbol: String { kind == .info ? "info.circle.fill" : "exclamationmark.triangle.fill" }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: symbol).foregroundStyle(color)
                VStack(alignment: .leading, spacing: 3) {
                    Text(title).font(.subheadline.weight(.semibold))
                    if let message { Text(message).font(.subheadline).foregroundStyle(.secondary) }
                }
                Spacer(minLength: 0)
            }
            action()
        }
        .padding(12)
        .background(color.opacity(0.10), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

extension Notice where Action == EmptyView {
    init(kind: Kind, title: String, message: String? = nil) {
        self.init(kind: kind, title: title, message: message) { EmptyView() }
    }
}

extension RelayClient.State {
    var title: String {
        switch self {
        case .connected: return "Connected"
        case .connecting: return "Connecting…"
        case .waiting: return "Offline"
        case .failed(_, let code) where code == RelayClient.accountRequiredCode: return "Sign in required"
        case .failed: return "Not connected"
        case .stopped: return "Off"
        }
    }

    var detail: String? {
        switch self {
        case .waiting(let reason, _): return "\(reason) Retrying…"
        case .failed(let reason, _): return reason
        default: return nil
        }
    }

    var color: Color {
        switch self {
        case .connected: return Palette.ok
        case .connecting: return Palette.brand
        case .waiting: return Palette.warning
        case .failed(_, let code) where code == RelayClient.accountRequiredCode: return Palette.warning
        case .failed: return Palette.error
        case .stopped: return Palette.off
        }
    }

    var isConnected: Bool { if case .connected = self { return true }; return false }
}

extension Date {
    var relative: String {
        let f = RelativeDateTimeFormatter()
        f.unitsStyle = .short
        return abs(timeIntervalSinceNow) < 30 ? "just now" : f.localizedString(for: self, relativeTo: Date())
    }
}
