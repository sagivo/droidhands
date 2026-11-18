import Contacts
import Foundation

struct ContactMatch {
    let contact: CNContact
    var name: String { ContactBook.displayName(contact) }
}

/// Contact lookup shared by contacts_search, sms_send and phone_call (docs/TOOLS.md "Contact resolution").
enum ContactBook {
    static let keys: [CNKeyDescriptor] = [
        CNContactIdentifierKey, CNContactGivenNameKey, CNContactFamilyNameKey, CNContactMiddleNameKey, CNContactNicknameKey,
        CNContactOrganizationNameKey, CNContactPhoneNumbersKey, CNContactEmailAddressesKey,
        CNContactFormatter.descriptorForRequiredKeys(for: .fullName),
    ].map { $0 as! CNKeyDescriptor }

    static func displayName(_ c: CNContact) -> String {
        let full = CNContactFormatter.string(from: c, style: .fullName)?.trimmingCharacters(in: .whitespaces) ?? ""
        if !full.isEmpty { return full }
        if !c.organizationName.isEmpty { return c.organizationName }
        return c.phoneNumbers.first?.value.stringValue ?? c.emailAddresses.first.map { $0.value as String } ?? "(no name)"
    }

    static func fold(_ s: String) -> String {
        s.folding(options: [.caseInsensitive, .diacriticInsensitive, .widthInsensitive], locale: nil)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func digits(_ s: String) -> String { s.filter(\.isNumber) }

    static func all() throws -> [CNContact] {
        var out: [CNContact] = []
        let request = CNContactFetchRequest(keysToFetch: keys)
        request.sortOrder = .userDefault
        try SharedStores.contacts.enumerateContacts(with: request) { c, _ in out.append(c) }
        return out
    }

    /// Ranked: exact name, name prefix, word prefix, then substring, phone and email matches.
    static func search(_ query: String, limit: Int) throws -> [CNContact] {
        let q = fold(query)
        let qDigits = digits(query)
        guard !q.isEmpty else { return [] }
        var scored: [(Int, CNContact)] = []
        for c in try all() {
            let name = fold(displayName(c))
            let names = [name, fold(c.nickname), fold(c.organizationName)].filter { !$0.isEmpty }
            let words = names.flatMap { $0.split(whereSeparator: { $0.isWhitespace || $0 == "-" }).map(String.init) }
            var score = 0
            if names.contains(q) { score = 100 } else if names.contains(where: { $0.hasPrefix(q) }) { score = 80 } else if words.contains(where: { $0.hasPrefix(q) }) { score = 60 } else if names.contains(where: { $0.contains(q) }) { score = 40 }
            if score == 0, qDigits.count >= 4, c.phoneNumbers.contains(where: { digits($0.value.stringValue).hasSuffix(qDigits) || digits($0.value.stringValue).contains(qDigits) }) { score = 50 }
            if score == 0, q.contains("@") || q.count >= 3, c.emailAddresses.contains(where: { fold($0.value as String).contains(q) }) { score = 30 }
            if score > 0 { scored.append((score, c)) }
        }
        return scored.sorted { $0.0 > $1.0 }.prefix(limit).map(\.1)
    }

    /// Picks one contact only on an exact full-name match, or a unique whole-word match that no other contact
    /// shares as a prefix. Otherwise throws with the candidates, so the agent can ask the user.
    static func resolve(_ name: String) throws -> CNContact {
        let q = fold(name)
        let candidates = try search(name, limit: 50)
        let exact = candidates.filter { fold(displayName($0)) == q || fold($0.nickname) == q }
        if exact.count == 1 { return exact[0] }
        if exact.count > 1 { throw ambiguous(name, exact) }
        let qWords = q.split(separator: " ").map(String.init)
        let wordMatches = candidates.filter { c in
            let words = Set(fold(displayName(c)).split(separator: " ").map(String.init))
            return qWords.allSatisfy(words.contains)
        }
        let prefixMatches = candidates.filter { c in
            let words = fold(displayName(c)).split(separator: " ").map(String.init)
            return qWords.allSatisfy { qw in words.contains { $0.hasPrefix(qw) } }
        }
        if wordMatches.count == 1, prefixMatches.count == 1 { return wordMatches[0] }
        if candidates.isEmpty { throw ToolError("No contact matches '\(name)'. Check the spelling, try another form of the name, or ask the user for the number.") }
        throw ambiguous(name, Array(candidates.prefix(8)))
    }

    private static func ambiguous(_ name: String, _ list: [CNContact]) -> ToolError {
        let names = list.map { c -> String in
            let phone = c.phoneNumbers.first?.value.stringValue
            return phone.map { "\(displayName(c)) (\($0))" } ?? displayName(c)
        }
        return ToolError("Several contacts match '\(name)': \(names.joined(separator: "; ")). Ask the user which one, then pass the full name or the number.")
    }

    static func json(_ c: CNContact) -> JSON {
        let phones: [JSON] = c.phoneNumbers.map { p in
            let label = p.label.map { CNLabeledValue<NSString>.localizedString(forLabel: $0) } ?? "other"
            return .object(JSONObject([("number", .string(p.value.stringValue)), ("type", .string(label)),
                                       ("normalized", .string(normalized(p.value.stringValue)))]))
        }
        return .object(JSONObject([
            ("id", .string(c.identifier)),
            ("name", .string(displayName(c))),
            ("phones", .array(phones)),
            ("emails", .strings(c.emailAddresses.map { $0.value as String })),
            ("organization", c.organizationName.isEmpty ? nil : .string(c.organizationName)),
        ]))
    }

    /// Digits with a leading + kept.
    static func normalized(_ number: String) -> String {
        let t = number.trimmingCharacters(in: .whitespaces)
        return (t.hasPrefix("+") ? "+" : "") + digits(t)
    }

    /// A number the agent passed: digits, an optional leading +, spacing and punctuation. USSD/MMI codes are refused.
    static func checkNumber(_ raw: String) throws -> String {
        let t = raw.trimmingCharacters(in: .whitespaces)
        if t.contains("*") || t.contains("#") { throw ToolError("'\(raw)' looks like a USSD/MMI code, which DroidHands never dials.") }
        let allowed = CharacterSet(charactersIn: "+0123456789 ()-./")
        guard t.unicodeScalars.allSatisfy(allowed.contains), digits(t).count >= 3, !t.dropFirst().contains("+") else {
            throw ToolError("'to' must be a phone number (digits, an optional leading +, spaces or dashes), not '\(raw)'.")
        }
        return normalized(t)
    }

    /// `to` or `contact` → (number, who). A contact with several numbers prefers mobile, then iPhone.
    static func recipient(_ args: Args) async throws -> (number: String, display: String) {
        if let to = try args.string("to"), !to.trimmingCharacters(in: .whitespaces).isEmpty {
            let number = try checkNumber(to)
            return (number, number)
        }
        let name = try args.string("contact")?.trimmingCharacters(in: .whitespaces) ?? ""
        guard !name.isEmpty else { throw ToolError("Pass 'to' (a phone number) or 'contact' (a name).") }
        try await Permission.contacts.ensure()
        let c = try resolve(name)
        let phones = c.phoneNumbers
        guard !phones.isEmpty else { throw ToolError("\(displayName(c)) has no phone number in Contacts.") }
        let preferred = phones.first { $0.label == CNLabelPhoneNumberMobile } ?? phones.first { $0.label == CNLabelPhoneNumberiPhone } ?? phones[0]
        return (normalized(preferred.value.stringValue), "\(displayName(c)) (\(preferred.value.stringValue))")
    }
}

struct ContactsSearchTool: Tool {
    let name = "contacts_search"
    let title = "Search contacts"
    let description = "Finds contacts by name, part of a name, nickname, company, phone number or email. Exact and prefix matches come first."
    let scope = Scopes.contactsRead
    let risk = Risk.READ
    let inputSchema = Schema.object([
        ("query", Schema.string("Name, part of a name, phone number or email")),
        ("limit", Schema.integer("Max results (default 10)", min: 1, max: 50)),
    ], required: ["query"])

    func unavailableReason() -> String? { Permission.contacts.status == .notAsked ? nil : Permission.contacts.unavailableReason }

    func run(_ args: Args, _ ctx: CallContext) async throws -> ToolResult {
        let query = try args.requiredString("query")
        let limit = try args.int("limit", in: 1...50) ?? 10
        try await Permission.contacts.ensure()
        let found = try ContactBook.search(query, limit: limit)
        return .json(.array(found.map(ContactBook.json)), footer: found.isEmpty ? "No contacts match." : nil)
    }
}
