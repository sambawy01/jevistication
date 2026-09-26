import Foundation
import LoupeKit

// Clipboard checks (2026-09-26, owner request "where is the smart copy-paste monitoring feature?").
// Shared by the app (the chip, "Check what I copied") and the Loupe keyboard. No network here, and
// nothing is stored: these are pure functions over what the system said is on the clipboard.

/// What iOS can say about the clipboard **without reading it** (`UIPasteboard.detectPatterns`, no
/// paste prompt). One case per pattern Loupe asks about.
enum ClipboardPattern: String, CaseIterable, Codable, Hashable {
    /// A web address that could be opened as-is ("Paste and Go").
    case probableWebURL
    /// Text that would be a web search ("Paste and Search").
    case probableWebSearch
    /// A number (a code, an amount without a currency).
    case number
    /// A link somewhere in the text (Data Detection, iOS 15+).
    case link
    case phoneNumber
    case emailAddress
    /// An amount with a currency ("EGP 1,500").
    case moneyAmount
}

/// What a clipboard check looks at: a web link, or the domain of an email address.
enum ClipboardKind: String, Codable, Equatable {
    case link, email

    /// "link" / "email address", for sentences.
    var noun: String { self == .link ? "link" : "email address" }
}

/// Which detected patterns earn the chip. Links and email addresses have a verdict (the website name,
/// or the address's domain, through the phishing formula). Phone numbers, numbers and amounts are
/// detected but not offered: Loupe has no check that can say anything true about them yet.
enum ClipboardOffer {
    static func kind(for patterns: Set<ClipboardPattern>) -> ClipboardKind? {
        if patterns.contains(.probableWebURL) || patterns.contains(.link) { return .link }
        if patterns.contains(.emailAddress) { return .email }
        return nil
    }

    /// The chip's question.
    static func question(_ kind: ClipboardKind) -> String {
        kind == .link ? "Check the link you copied?" : "Check the email address you copied?"
    }
}

/// The part of copied text Loupe checks.
struct ClipboardTarget: Equatable {
    let kind: ClipboardKind
    /// What the formula checks: the link, or `https://<the address's domain>/` for an email address.
    let input: LinkInput.Normalized

    /// "name@domain" (a whole address, nothing around it), the first match in text otherwise.
    private static let emailRE = try! NSRegularExpression(
        pattern: "[\\p{L}\\p{N}._%+\\-]{1,64}@([\\p{L}\\p{N}\\-]+(?:\\.[\\p{L}\\p{N}\\-]+)+)")

    /// The link in [text] (the first one in a message), else the domain of the first email address.
    /// A lone email address is read as an address, never as a link with text before an "@".
    static func extract(_ text: String) -> Result<ClipboardTarget, LinkInput.Problem> {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return .failure(.empty) }
        guard trimmed.count <= LinkInput.maxLength else { return .failure(.tooLong) }
        let hasWebLink = !SiteCheck.shared.linksIn(text: LinkInput.defang(trimmed), max: 1).isEmpty
        let looksLikeLink = trimmed.contains("://") || hasWebLink
        if !looksLikeLink, let domain = emailDomain(trimmed) {
            if case .success(let n) = LinkInput.normalize("https://\(domain)/") { return .success(ClipboardTarget(kind: .email, input: n)) }
        }
        switch LinkInput.normalize(trimmed) {
        case .success(let n): return .success(ClipboardTarget(kind: .link, input: n))
        case .failure(let p):
            if let domain = emailDomain(trimmed), case .success(let n) = LinkInput.normalize("https://\(domain)/") {
                return .success(ClipboardTarget(kind: .email, input: n))
            }
            return .failure(p)
        }
    }

    /// The domain of the first email address in [text], lowercased.
    static func emailDomain(_ text: String) -> String? {
        let ns = text as NSString
        guard let m = emailRE.firstMatch(in: text, range: NSRange(location: 0, length: ns.length)), m.numberOfRanges > 1 else { return nil }
        let d = ns.substring(with: m.range(at: 1)).lowercased().trimmingCharacters(in: CharacterSet(charactersIn: ".,;:!?)>\"'"))
        return d.contains(".") ? d : nil
    }
}

/// The words for a clipboard verdict: the chip's banner, Siri's answer and the keyboard's strip.
/// "That link looks like a fake PayPal page" / "No warning signs found".
enum ClipboardWords {
    /// One sentence (Siri speaks it; the banner shows it).
    static func sentence(_ v: LinkVerdict, kind: ClipboardKind) -> String {
        let what = kind == .link ? "That link" : "That email address"
        let reason = v.reasons.first?.text
        switch (v.level, v.brand) {
        case (.dangerous, let b?): return kind == .link ? "\(what) looks like a fake \(b) page." : "\(what) looks like a fake \(b) address."
        case (.dangerous, nil): return "\(what) looks dangerous" + (reason.map { ": \(lowerFirst($0))" } ?? ".")
        case (.suspicious, let b?): return "\(what) might be a fake \(b) " + (kind == .link ? "page. Be careful." : "address. Be careful.")
        case (.suspicious, nil): return "\(what) looks suspicious" + (reason.map { ": \(lowerFirst($0))" } ?? ".")
        case (.safe, _): return "No warning signs found in that \(kind.noun)."
        }
    }

    /// The keyboard strip: "Pasted link: no warning signs" or "⚠ looks like a fake PayPal page".
    static func strip(_ v: LinkVerdict, kind: ClipboardKind, pasted: Bool) -> String {
        let label = (pasted ? "Pasted " : "Copied ") + kind.noun
        switch (v.level, v.brand) {
        case (.safe, _): return "\(label): no warning signs"
        case (.dangerous, let b?): return "⚠ \(label) looks like a fake \(b) " + (kind == .link ? "page" : "address")
        case (.suspicious, let b?): return "⚠ \(label) might be a fake \(b) " + (kind == .link ? "page" : "address")
        case (.dangerous, nil): return "⚠ \(label) looks dangerous · \(v.unicodeHost)"
        case (.suspicious, nil): return "⚠ \(label) looks suspicious · \(v.unicodeHost)"
        }
    }

    private static func lowerFirst(_ s: String) -> String {
        guard let f = s.first else { return s }
        let rest = s.dropFirst()
        // Keep acronyms and brand names ("IP", "PayPal") as they are.
        if rest.first?.isUppercase == true { return s.hasSuffix(".") ? s : s + "." }
        let out = f.lowercased() + rest
        return out.hasSuffix(".") ? out : out + "."
    }
}
