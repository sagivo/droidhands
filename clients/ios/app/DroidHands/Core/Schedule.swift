import Foundation

/// A daily window in phone-local time when an agent may act. `startMinute > endMinute` runs overnight;
/// start == end is invalid (mirrors Android's `ActiveHours`).
struct ActiveHours: Codable, Equatable {
    var startMinute: Int
    var endMinute: Int

    static let `default` = ActiveHours(startMinute: 8 * 60, endMinute: 23 * 60)
}

/// Pure helpers for `ActiveHours` (Android's `ActiveWindow`).
enum ActiveWindow {
    static let minutesPerDay = 24 * 60

    static func isValid(_ h: ActiveHours) -> Bool {
        (0..<minutesPerDay).contains(h.startMinute) && (0..<minutesPerDay).contains(h.endMinute) && h.startMinute != h.endMinute
    }

    /// Start inclusive, end exclusive; invalid windows contain nothing (fail closed).
    static func contains(_ h: ActiveHours, minute: Int) -> Bool {
        guard isValid(h) else { return false }
        return h.startMinute < h.endMinute ? minute >= h.startMinute && minute < h.endMinute
                                           : minute >= h.startMinute || minute < h.endMinute
    }

    static func isOvernight(_ h: ActiveHours) -> Bool { h.startMinute > h.endMinute }

    /// Length of the window in minutes.
    static func length(_ h: ActiveHours) -> Int {
        ((h.endMinute - h.startMinute) % minutesPerDay + minutesPerDay) % minutesPerDay
    }

    static func minuteOfDay(_ date: Date, calendar: Calendar = .current) -> Int {
        let c = calendar.dateComponents([.hour, .minute], from: date)
        return (c.hour ?? 0) * 60 + (c.minute ?? 0)
    }

    /// "08:05" for 485 (agent-facing text; the UI uses the user's 12/24-hour setting).
    static func format(_ minute: Int) -> String {
        let m = ((minute % minutesPerDay) + minutesPerDay) % minutesPerDay
        return String(format: "%02d:%02d", m / 60, m % 60)
    }

    /// The refusal for a call at `now`, or nil inside the window.
    static func refusal(_ h: ActiveHours, now: Date) -> String? {
        guard isValid(h) else {
            return "This agent's active hours in DroidHands are not a valid time window, so it may not act right now. Ask the user to fix or turn off this agent's active hours in the DroidHands app."
        }
        let minute = minuteOfDay(now)
        if contains(h, minute: minute) { return nil }
        return "This agent may only act between \(format(h.startMinute)) and \(format(h.endMinute)) (the phone's local time; it is \(format(minute)) there now). Try again within that window, or ask the user to change this agent's active hours in the DroidHands app."
    }

    static func summary(_ h: ActiveHours?) -> String {
        guard let h else { return "Always." }
        return "Only between \(format(h.startMinute)) and \(format(h.endMinute)), phone-local time. Calls outside this window are refused."
    }
}
