import Foundation
import LoupeKit

/// Browsing protection's three levels. They map the shared phishing formula's levels one to one
/// (danger → dangerous, caution → suspicious, safe → safe); the lowest is never *shown* as "safe"
/// (the owner's wording, formula v1.1): it reads "No warning signs found".
enum ProtectionLevel: String, Codable, CaseIterable {
    case safe, suspicious, dangerous

    init(formula level: String) {
        switch level {
        case "danger": self = .dangerous
        case "caution": self = .suspicious
        default: self = .safe
        }
    }

    var title: String {
        switch self {
        case .safe: return SiteCheckResult.companion.LOWEST_LEVEL_TITLE
        case .suspicious: return "Suspicious"
        case .dangerous: return "Dangerous"
        }
    }

    var flagged: Bool { self != .safe }
    var rank: Int { self == .dangerous ? 2 : self == .suspicious ? 1 : 0 }
}

/// Where a verdict came from.
enum ProtectionOrigin: String, Codable {
    case safari, shared, manual
    /// Clipboard checks (2026-09-26): the in-app chip, "Check what I copied" (Siri, Shortcuts, the widget).
    case clipboard
    /// The Loupe keyboard's strip (on the device only).
    case keyboard

    var title: String {
        switch self {
        case .safari: return "Safari"
        case .shared: return "Shared link"
        case .manual: return "Check a link"
        case .clipboard: return "Clipboard"
        case .keyboard: return "Loupe keyboard"
        }
    }
}

/// Reading what the user pasted or typed into a web address the formula can check.
enum LinkInput {
    enum Problem: Error, Equatable {
        case empty
        case notWeb(String)
        case noHost
        case tooLong

        var message: String {
            switch self {
            case .empty: return "Paste or type a link first."
            case .notWeb(let scheme): return "\"\(scheme):\" is not a web link. Loupe checks web addresses (http and https)."
            case .noHost: return "That does not look like a web address. Try something like example.com or a full link."
            case .tooLong: return "That text is too long to be one link."
            }
        }
    }

    struct Normalized: Equatable {
        /// What the formula checks (a scheme added when there was none).
        let url: String
        /// The website name as sent to DNS (IDNA ASCII, punycode for international names).
        let host: String
        /// The website name as people read it (international letters decoded).
        let unicodeHost: String
        let registrable: String?
        /// Scheme, website name and path only: no query or fragment (they can hold tokens).
        let displayURL: String
        let addedScheme: Bool

        /// "xn--pypal-4ve.com" beside "pаypal.com": the name is written with international letters.
        var isInternational: Bool { host != unicodeHost || host.split(separator: ".").contains { $0.hasPrefix("xn--") } }
    }

    static let maxLength = 4096
    private static let otherSchemes: Set<String> = ["mailto", "tel", "sms", "javascript", "data", "blob", "file", "ftp", "about", "facetime"]

    static func normalize(_ raw: String) -> Result<Normalized, Problem> {
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return .failure(.empty) }
        guard text.count <= maxLength else { return .failure(.tooLong) }
        // A pasted message: take its first web link ("Your parcel is waiting: https://…").
        if text.contains(where: \.isWhitespace) {
            if let first = SiteCheck.shared.linksIn(text: defang(text), max: 1).first {
                text = first
            } else if let token = text.split(whereSeparator: \.isWhitespace).map(String.init).first(where: { $0.contains(".") }) {
                text = token
            } else {
                return .failure(.noHost)
            }
        }
        text = defang(text)
        // Wrapping quotes or brackets and trailing sentence punctuation, in any order ("<https://x>.", "\"x.com\",").
        while true {
            let next = String(text.trimmingCharacters(in: CharacterSet(charactersIn: "<>\"'`()[]{}“”‘’").union(.whitespaces))
                .reversed().drop { ".,;:!?".contains($0) }.reversed())
            if next == text { break }
            text = next
        }
        guard !text.isEmpty else { return .failure(.empty) }
        var added = false
        if let r = text.range(of: "://") {
            let scheme = text[..<r.lowerBound].lowercased()
            guard scheme == "http" || scheme == "https" else { return .failure(.notWeb(scheme)) }
        } else if let colon = text.firstIndex(of: ":"), otherSchemes.contains(text[..<colon].lowercased()) {
            return .failure(.notWeb(text[..<colon].lowercased()))
        } else {
            text = "https://" + text.drop { $0 == "/" }
            added = true
        }
        guard let u = ParsedUrl.companion.parse(url: text), !u.host.isEmpty else { return .failure(.noHost) }
        let host = u.host
        let plausible = host.contains(".") || host == "localhost" || Hosts.shared.isIp(host: host)
        guard plausible, !host.contains(where: { $0.isWhitespace }), host.count <= 253 else { return .failure(.noHost) }
        let path = u.path == "/" ? "" : u.path
        let display = String("\(u.scheme)://\(u.unicodeHost)\(path)".prefix(200))
        return .success(Normalized(url: text, host: host, unicodeHost: u.unicodeHost, registrable: u.registrable,
                                   displayURL: display, addedScheme: added))
    }

    /// Security write-ups "defang" links: hxxp://, example[.]com, example(.)com.
    static func defang(_ s: String) -> String {
        var t = s
        for (a, b) in [("hxxps://", "https://"), ("hxxp://", "http://"), ("[.]", "."), ("(.)", "."), ("[dot]", ".")] {
            t = t.replacingOccurrences(of: a, with: b, options: .caseInsensitive)
        }
        return t
    }
}

/// What the online checks did for one verdict: what left the phone, to whom, and what was matched
/// on the phone. Nothing is added here that did not happen.
struct OnlineDisclosure: Codable, Equatable {
    struct Sent: Codable, Equatable {
        /// Always a domain ("example.com"), never an address, page or anything about the user.
        let what: String
        let to: String
    }

    var sent: [Sent] = []
    /// Lists downloaded to this phone and matched here ("Phishing.Database list · list date …").
    var matchedOnDevice: [String] = []
    /// The "Online · source · fetched …" labels of the sources that answered.
    var onlineNotes: [String] = []
    /// A source that may send something only in a narrow case (Safe Browsing's 4-byte prefixes).
    var conditional: [String] = []

    static let none = OnlineDisclosure()

    /// "0 bytes out" or "Sent: the domain example.com only, to …".
    var privacyLine: String { privacyLine(subject: "link") }

    /// [subject]: "link" (Check a link, the share sheet) or "site" (Safari).
    func privacyLine(subject: String) -> String {
        if sent.isEmpty {
            return conditional.isEmpty ? "0 bytes out: nothing about this \(subject) left your iPhone." : "Nothing about this \(subject) left your iPhone. " + conditional.joined(separator: " ")
        }
        let domains = Array(Set(sent.map(\.what))).sorted()
        var seen = Set<String>()
        let to = sent.map(\.to).filter { seen.insert($0).inserted }
        return "Sent: the domain \(domains.joined(separator: ", ")) only, to \(to.joined(separator: ", "))." +
            (conditional.isEmpty ? "" : " " + conditional.joined(separator: " "))
    }
}

/// One verdict as the Protection screens, the Spotted log and the extensions keep it.
struct LinkVerdict: Codable, Equatable, Identifiable {
    struct Reason: Codable, Equatable {
        let code: String
        let text: String
        let weight: Int
        /// "Online · <source> · fetched <when>" for a reason that came from an online check.
        let online: String?
    }

    var id = UUID()
    var checkedAt: Date
    var origin: ProtectionOrigin
    var displayURL: String
    var host: String
    var unicodeHost: String
    var registrable: String?
    var level: ProtectionLevel
    var score: Int
    /// The reasons that carry weight, strongest first.
    var reasons: [Reason]
    var reassuring: [String] = []
    var otherFacts: [String] = []
    var notCounted: [String] = []
    /// The brand a look-alike imitates, when a reason names one.
    var brand: String?
    /// The mechanical checks that ran on this phone.
    var onDevice: [String]
    var disclosure: OnlineDisclosure

    var title: String { level.title }
    var reasonsTitle: String { level.flagged ? "Warning signs" : "Small things noticed" }
    var privacyLine: String { disclosure.privacyLine }
    /// The website name the user reads, with the ASCII form when they differ.
    var siteLine: String { unicodeHost == host ? host : "\(unicodeHost) (written as \(host))" }
}

/// The verdict for one link or website name: LoupeKit's shared phishing formula (the same one mail
/// triage and Loupe Station use), with whatever online facts the caller has.
enum LinkChecker {
    /// The checks that always run on this phone, whatever the switches say.
    static var onDeviceChecks: [String] {
        [
            "Look-alike names of \(Brands.shared.BRANDS.count) well-known brands (swapped, added or look-alike letters)",
            "International letters: punycode names, mixed alphabets and look-alike characters",
            "Link shorteners, text before an @ and heavily encoded addresses",
            "Domain endings often used by throw-away scam sites",
            "Free hosting and site-builder addresses, bare IP numbers, long chains of subdomains",
        ]
    }

    static func verdict(_ input: LinkInput.Normalized, online: OnlineContext?, disclosure: OnlineDisclosure = .none,
                        origin: ProtectionOrigin, now: Date = Date()) -> LinkVerdict {
        let check = SiteCheck.shared.checkUrl(url: input.url, online: online)
        let v = check.verdict
        let reasons = v.reasons.filter { $0.weight > 0 }.map { r in
            LinkVerdict.Reason(code: r.code, text: r.text, weight: Int(r.weight),
                               online: r.source == "online" ? OnlineSignals.shared.label(params: r.params) : nil)
        }
        let brand = v.reasons.first { $0.weight > 0 && $0.params["brand"] != nil }?.params["brand"]
        return LinkVerdict(checkedAt: now, origin: origin, displayURL: input.displayURL, host: input.host,
                           unicodeHost: input.unicodeHost, registrable: input.registrable,
                           level: ProtectionLevel(formula: v.level), score: Int(v.score), reasons: reasons,
                           reassuring: check.reassuringFacts, otherFacts: check.otherFacts, notCounted: check.notCountedLines,
                           brand: brand, onDevice: onDeviceChecks + disclosure.matchedOnDevice, disclosure: disclosure)
    }
}
