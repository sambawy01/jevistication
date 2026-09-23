import Foundation

/// Plain-language priorities compiled to explicit rules. Deterministic; no model involved.
/// "nonstop, under £200, not before 7am, 1 checked bag"
struct Priorities: Equatable {
    var maxStops: Int?
    var priceCap: Decimal?
    var priceCurrency: String?        // ISO code if the user named one
    var earliestDeparture: Int?       // minutes after midnight, airport-local
    var latestDeparture: Int?
    var minCheckedBags: Int?
    var refundable: Bool = false
    var preferShortest: Bool = false
    /// Clauses the parser could not read. Shown to the user, never silently dropped.
    var unrecognised: [String] = []
    /// The text as the user wrote it; Laya reads this, not the parsed rules.
    var text: String = ""

    var isEmpty: Bool {
        maxStops == nil && priceCap == nil && earliestDeparture == nil && latestDeparture == nil
            && minCheckedBags == nil && !refundable && !preferShortest
    }
}

enum PriorityParser {
    static func parse(_ text: String) -> Priorities {
        var p = Priorities()
        p.text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let clauses = text.lowercased()
            .replacingOccurrences(of: ";", with: ",")
            .replacingOccurrences(of: " and ", with: ",")
            .split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        for c in clauses {
            if !apply(c, to: &p) { p.unrecognised.append(c) }
        }
        return p
    }

    private static func apply(_ c: String, to p: inout Priorities) -> Bool {
        // Stops
        if c.contains("nonstop") || c.contains("non-stop") || c.contains("direct") || c.contains("no stops") {
            p.maxStops = 0; return true
        }
        if let n = firstInt(in: c, near: ["stop"]), c.contains("stop") {
            p.maxStops = n; return true
        }
        // Price
        if let r = c.range(of: #"(under|below|less than|max|at most|up to|<|cheaper than)\s*([£$€]|gbp|usd|eur)?\s*(\d+(?:\.\d+)?)\s*(gbp|usd|eur|pounds|dollars|euros)?"#, options: .regularExpression) {
            let s = String(c[r])
            if let n = s.range(of: #"\d+(?:\.\d+)?"#, options: .regularExpression) {
                p.priceCap = Decimal(string: String(s[n]), locale: Locale(identifier: "en_US_POSIX"))
                p.priceCurrency = currency(in: s)
                return true
            }
        }
        // Departure window
        if let r = c.range(of: #"(not before|no earlier than|after|from)\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?"#, options: .regularExpression),
           let m = clock(String(c[r])) {
            p.earliestDeparture = m; return true
        }
        if let r = c.range(of: #"(not after|no later than|before|by)\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?"#, options: .regularExpression),
           let m = clock(String(c[r])) {
            p.latestDeparture = m; return true
        }
        // Bags
        if c.contains("bag") || c.contains("luggage") || c.contains("suitcase") {
            if c.contains("no ") || c.contains("hand luggage only") || c.contains("carry-on only") { return true }
            p.minCheckedBags = firstInt(in: c, near: []) ?? 1
            return true
        }
        if c.contains("refundable") { p.refundable = true; return true }
        if c.contains("shortest") || c.contains("fastest") || c.contains("quickest") { p.preferShortest = true; return true }
        if c.contains("cheapest") { return true }   // price order is already the default
        return false
    }

    private static func currency(in s: String) -> String? {
        if s.contains("£") || s.contains("gbp") || s.contains("pound") { return "GBP" }
        if s.contains("€") || s.contains("eur") { return "EUR" }
        if s.contains("$") || s.contains("usd") || s.contains("dollar") { return "USD" }
        return nil
    }

    private static func firstInt(in s: String, near _: [String]) -> Int? {
        let words = ["zero": 0, "one": 1, "two": 2, "three": 3]
        if let r = s.range(of: #"\d+"#, options: .regularExpression) { return Int(s[r]) }
        for (w, n) in words where s.split(separator: " ").contains(Substring(w)) { return n }
        return nil
    }

    /// "not before 7am" → 420, "after 18:30" → 1110, "before 9 pm" → 1260.
    static func clock(_ s: String) -> Int? {
        guard let r = s.range(of: #"(\d{1,2})(?::(\d{2}))?\s*(am|pm)?"#, options: .regularExpression) else { return nil }
        let t = String(s[r])
        let digits = t.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }
        guard var h = digits.first else { return nil }
        let m = digits.count > 1 ? digits[1] : 0
        if t.contains("pm"), h < 12 { h += 12 }
        if t.contains("am"), h == 12 { h = 0 }
        guard (0..<24).contains(h), (0..<60).contains(m) else { return nil }
        return h * 60 + m
    }
}
