import Foundation

/// A JSON value. Objects keep their key order, so what agents read comes out in the order it was built.
indirect enum JSON: Equatable, Sendable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSON])
    case object(JSONObject)

    static func == (a: JSON, b: JSON) -> Bool {
        switch (a, b) {
        case (.null, .null): return true
        case (.bool(let x), .bool(let y)): return x == y
        case (.number(let x), .number(let y)): return x == y
        case (.string(let x), .string(let y)): return x == y
        case (.array(let x), .array(let y)): return x == y
        case (.object(let x), .object(let y)): return x == y
        default: return false
        }
    }

    // MARK: Reading

    subscript(key: String) -> JSON? {
        if case .object(let o) = self { return o[key] }
        return nil
    }

    var stringValue: String? { if case .string(let s) = self { return s }; return nil }
    var boolValue: Bool? { if case .bool(let b) = self { return b }; return nil }
    var doubleValue: Double? { if case .number(let n) = self { return n }; return nil }
    var objectValue: JSONObject? { if case .object(let o) = self { return o }; return nil }
    var arrayValue: [JSON]? { if case .array(let a) = self { return a }; return nil }
    var isNull: Bool { self == .null }

    // MARK: Parsing and encoding

    struct ParseError: Error {}

    static func parse(_ text: String) throws -> JSON {
        try parse(Data(text.utf8))
    }

    static func parse(_ data: Data) throws -> JSON {
        let raw: Any
        do {
            raw = try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
        } catch {
            throw ParseError()
        }
        return from(raw)
    }

    /// Converts a JSONSerialization value (or plain Swift values) to [JSON].
    static func from(_ value: Any?) -> JSON {
        switch value {
        case nil, is NSNull: return .null
        case let j as JSON: return j
        case let n as NSNumber:
            if CFGetTypeID(n) == CFBooleanGetTypeID() { return .bool(n.boolValue) }
            return .number(n.doubleValue)
        case let b as Bool: return .bool(b)
        case let s as String: return .string(s)
        case let a as [Any?]: return .array(a.map { from($0) })
        case let d as [String: Any?]:
            var o = JSONObject()
            for key in d.keys.sorted() { o[key] = from(d[key] ?? nil) }
            return .object(o)
        default: return .string(String(describing: value!))
        }
    }

    /// Compact JSON text.
    var encoded: String {
        var out = ""
        write(to: &out)
        return out
    }

    private func write(to out: inout String) {
        switch self {
        case .null: out += "null"
        case .bool(let b): out += b ? "true" : "false"
        case .number(let n): out += JSON.format(n)
        case .string(let s): JSON.quote(s, into: &out)
        case .array(let a):
            out += "["
            for (i, v) in a.enumerated() {
                if i > 0 { out += "," }
                v.write(to: &out)
            }
            out += "]"
        case .object(let o):
            out += "{"
            for (i, (k, v)) in o.entries.enumerated() {
                if i > 0 { out += "," }
                JSON.quote(k, into: &out)
                out += ":"
                v.write(to: &out)
            }
            out += "}"
        }
    }

    private static func format(_ n: Double) -> String {
        guard n.isFinite else { return "null" }
        if n == n.rounded(), abs(n) < 1e15 { return String(Int64(n)) }
        return String(n)
    }

    private static func quote(_ s: String, into out: inout String) {
        out += "\""
        for scalar in s.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            case _ where scalar.value < 0x20 || scalar.value == 0x2028 || scalar.value == 0x2029:
                out += String(format: "\\u%04x", scalar.value)
            default: out.unicodeScalars.append(scalar)
            }
        }
        out += "\""
    }
}

/// An ordered JSON object.
struct JSONObject: Equatable, Sendable {
    private(set) var entries: [(String, JSON)] = []

    init() {}

    init(_ pairs: [(String, JSON?)]) {
        for (k, v) in pairs { if let v { self[k] = v } }
    }

    subscript(key: String) -> JSON? {
        get { entries.first { $0.0 == key }?.1 }
        set {
            if let i = entries.firstIndex(where: { $0.0 == key }) {
                if let newValue { entries[i].1 = newValue } else { entries.remove(at: i) }
            } else if let newValue {
                entries.append((key, newValue))
            }
        }
    }

    var keys: [String] { entries.map(\.0) }
    var isEmpty: Bool { entries.isEmpty }

    static func == (a: JSONObject, b: JSONObject) -> Bool {
        a.entries.count == b.entries.count && a.entries.allSatisfy { k, v in b[k] == v }
    }
}

extension JSON: ExpressibleByStringLiteral, ExpressibleByBooleanLiteral, ExpressibleByIntegerLiteral,
    ExpressibleByFloatLiteral, ExpressibleByArrayLiteral, ExpressibleByDictionaryLiteral, ExpressibleByNilLiteral {
    init(stringLiteral value: String) { self = .string(value) }
    init(booleanLiteral value: Bool) { self = .bool(value) }
    init(integerLiteral value: Int) { self = .number(Double(value)) }
    init(floatLiteral value: Double) { self = .number(value) }
    init(arrayLiteral elements: JSON...) { self = .array(elements) }
    init(dictionaryLiteral elements: (String, JSON)...) { self = .object(JSONObject(elements.map { ($0.0, $0.1) })) }
    init(nilLiteral: ()) { self = .null }
}

extension JSON {
    static func int(_ v: Int) -> JSON { .number(Double(v)) }
    static func int64(_ v: Int64) -> JSON { .number(Double(v)) }
    static func optional(_ s: String?) -> JSON? { s.map { .string($0) } }
    static func strings(_ a: [String]) -> JSON { .array(a.map { .string($0) }) }
}
