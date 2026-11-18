import CoreLocation
import Foundation

struct LocationTool: Tool {
    let name = "location_get"
    let title = "Current location"
    let description = "Where the iPhone is: latitude, longitude, accuracy in meters, and optionally the street address. iPhone gives DroidHands the location only while the app is on screen."
    let scope = Scopes.location
    let risk = Risk.READ
    let inputSchema = Schema.object([
        ("accuracy", Schema.string("high (GPS, slower) or balanced (default)", enum: ["high", "balanced"])),
        ("timeout_seconds", Schema.integer("How long to wait for a fix (default 20)", min: 1, max: 60)),
        ("address", Schema.boolean("Also look up the street address")),
    ])

    func unavailableReason() -> String? { Permission.location.status == .notAsked ? nil : Permission.location.unavailableReason }

    func run(_ args: Args, _ ctx: CallContext) async throws -> ToolResult {
        let high = try args.string("accuracy") == "high"
        let timeout = try args.int("timeout_seconds", in: 1...60) ?? 20
        let wantAddress = try args.bool("address") ?? false
        try await Permission.location.ensure()
        try await Foreground.require("read the location")
        let location = try await LocationFix.get(high: high, timeout: TimeInterval(timeout))
        var out = JSONObject([
            ("latitude", .number(location.coordinate.latitude)),
            ("longitude", .number(location.coordinate.longitude)),
            ("accuracy_m", .int(Int(location.horizontalAccuracy.rounded()))),
            ("time", .string(ISODates.string(location.timestamp, allDay: false))),
            ("altitude_m", location.verticalAccuracy >= 0 ? .int(Int(location.altitude.rounded())) : nil),
        ])
        if wantAddress {
            if let mark = try? await CLGeocoder().reverseGeocodeLocation(location).first {
                let parts = [mark.name, mark.thoroughfare.map { t in mark.subThoroughfare.map { "\($0) \(t)" } ?? t }, mark.locality,
                             mark.administrativeArea, mark.postalCode, mark.country].compactMap { $0 }
                var seen = Set<String>()
                out["address"] = .string(parts.filter { seen.insert($0).inserted }.joined(separator: ", "))
            } else {
                out["address_note"] = "The address lookup failed (no network?)."
            }
        }
        return .json(.object(out))
    }
}

/// One location fix through CLLocationManager.requestLocation().
@MainActor
final class LocationFix: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var continuation: CheckedContinuation<CLLocation, Error>?
    private static var active: Set<LocationFix> = []

    static func get(high: Bool, timeout: TimeInterval) async throws -> CLLocation {
        let fix = LocationFix()
        active.insert(fix)
        defer { active.remove(fix) }
        fix.manager.desiredAccuracy = high ? kCLLocationAccuracyBest : kCLLocationAccuracyHundredMeters
        fix.manager.delegate = fix
        return try await withThrowingTaskGroup(of: CLLocation.self) { group in
            group.addTask { @MainActor in
                try await withTaskCancellationHandler {
                    try await withCheckedThrowingContinuation { c in
                        if let early = fix.early {
                            c.resume(with: early)
                            return
                        }
                        fix.continuation = c
                        fix.manager.requestLocation()
                    }
                } onCancel: {
                    Task { @MainActor in fix.finish(.failure(CancellationError())) }
                }
            }
            group.addTask {
                try await Task.sleep(nanoseconds: UInt64(timeout * 1e9))
                throw ToolError("No location fix within \(Int(timeout)) seconds. The iPhone may be indoors; try again with a longer timeout_seconds.")
            }
            defer { group.cancelAll() }
            let first = try await group.next()!
            return first
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        Task { @MainActor in
            guard let last = locations.last else { return }
            finish(.success(last))
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in finish(.failure(ToolError("iPhone couldn't get a location: \(error.localizedDescription)"))) }
    }

    /// A result that arrived before the continuation existed (cancelled right away).
    private var early: Result<CLLocation, Error>?

    private func finish(_ result: Result<CLLocation, Error>) {
        manager.stopUpdatingLocation()
        guard let continuation else {
            early = result
            return
        }
        continuation.resume(with: result)
        self.continuation = nil
    }
}
