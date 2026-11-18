import Contacts
import CoreLocation
import EventKit
import UIKit

/// The iOS permissions tools need, their state, and how to ask for them.
enum Permission: String, CaseIterable, Identifiable {
    case contacts, calendar, reminders, location

    var id: String { rawValue }

    var title: String {
        switch self {
        case .contacts: return "Contacts"
        case .calendar: return "Calendar"
        case .reminders: return "Reminders"
        case .location: return "Location"
        }
    }

    var symbol: String {
        switch self {
        case .contacts: return "person.crop.circle.fill"
        case .calendar: return "calendar"
        case .reminders: return "checklist"
        case .location: return "location.fill"
        }
    }

    var purpose: String {
        switch self {
        case .contacts: return "Find people to call or message"
        case .calendar: return "Read and add events"
        case .reminders: return "Read, add and complete reminders"
        case .location: return "Where the iPhone is"
        }
    }

    enum Status { case granted, notAsked, denied, limited }

    var status: Status {
        switch self {
        case .contacts:
            switch CNContactStore.authorizationStatus(for: .contacts) {
            case .authorized: return .granted
            case .notDetermined: return .notAsked
            case .limited: return .limited
            default: return .denied
            }
        case .calendar: return Permission.eventStatus(EKEventStore.authorizationStatus(for: .event))
        case .reminders: return Permission.eventStatus(EKEventStore.authorizationStatus(for: .reminder))
        case .location:
            switch CLLocationManager().authorizationStatus {
            case .authorizedAlways, .authorizedWhenInUse: return .granted
            case .notDetermined: return .notAsked
            default: return .denied
            }
        }
    }

    private static func eventStatus(_ s: EKAuthorizationStatus) -> Status {
        switch s {
        case .fullAccess: return .granted
        case .writeOnly: return .limited
        case .notDetermined: return .notAsked
        default: return .denied
        }
    }

    /// Shows the system prompt (when iOS still allows one) and returns the new state.
    @MainActor
    func request() async -> Status {
        switch self {
        case .contacts: _ = try? await CNContactStore().requestAccess(for: .contacts)
        case .calendar: _ = try? await SharedStores.events.requestFullAccessToEvents()
        case .reminders: _ = try? await SharedStores.events.requestFullAccessToReminders()
        case .location: await LocationAuthorization.request()
        }
        return status
    }

    /// For tools: nil when granted; asks once if the app is on screen and iOS hasn't asked yet; otherwise
    /// a sentence for the agent.
    func ensure() async throws {
        let current = status
        if current == .granted { return }
        if current == .notAsked, await Foreground.isActive {
            if await request() == .granted { return }
        }
        throw ToolError(missingText)
    }

    var missingText: String {
        switch status {
        case .notAsked:
            return "DroidHands doesn't have \(title) access yet. Ask the user to open DroidHands › Settings › Permissions and allow \(title)."
        case .limited:
            return "DroidHands has only limited \(title) access. Ask the user to allow full access in the iPhone Settings app › DroidHands › \(title)."
        default:
            return "The user turned off \(title) access for DroidHands. Ask them to allow it in the iPhone Settings app › DroidHands › \(title)."
        }
    }

    var unavailableReason: String? { status == .granted ? nil : missingText }
}

enum SharedStores {
    static let events = EKEventStore()
    static let contacts = CNContactStore()
}

/// Requests when-in-use location access and waits for the answer.
@MainActor
final class LocationAuthorization: NSObject, CLLocationManagerDelegate {
    private static var current: LocationAuthorization?
    private let manager = CLLocationManager()
    private var continuation: CheckedContinuation<Void, Never>?

    static func request() async {
        let auth = LocationAuthorization()
        current = auth
        // A stopped call (Stop, Revoke, timeout) stops waiting for the user's answer to the system alert.
        await withTaskCancellationHandler {
            await withCheckedContinuation { c in
                auth.continuation = c
                auth.manager.delegate = auth
                auth.manager.requestWhenInUseAuthorization()
            }
        } onCancel: {
            Task { @MainActor in auth.finish() }
        }
        current = nil
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Task { @MainActor in
            guard manager.authorizationStatus != .notDetermined else { return }
            finish()
        }
    }

    private func finish() {
        continuation?.resume()
        continuation = nil
    }
}

/// iOS lets an app open links, present screens and use the camera's light only while it is on screen.
enum Foreground {
    @MainActor static var isActiveNow: Bool { UIApplication.shared.applicationState == .active }

    static var isActive: Bool { get async { await MainActor.run { isActiveNow } } }

    static func require(_ what: String) async throws {
        if await isActive { return }
        throw ToolError("DroidHands is in the background, and iPhone only lets it \(what) while the app is on screen. Ask the user to open DroidHands and keep it open, then try again.")
    }

    /// The view controller to present system screens (message composer) from.
    @MainActor static var presenter: UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes.flatMap(\.windows).first { $0.isKeyWindow } ?? scenes.first?.windows.first
        var top = window?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}
