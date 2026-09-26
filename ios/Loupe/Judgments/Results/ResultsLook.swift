import SwiftUI

/// The dashboard's colour tokens. Answers are categorical (a fixed order by option, never by rank, so a filter
/// never repaints them); the negative / "none of these" option is a muted slate so the answer that matters
/// stands out; rule answers are their option's hue, dimmed (same answer, came from a rule); unsure is amber and
/// could-not-judge red (the app's status colours, always with a label). Validated against the card surface:
/// all pass 3:1 contrast; adjacent pairs that are close for colour-blind readers always carry a text label.
enum ResultsLook {
    static let options: [Color] = [
        Palette.cyan, Color(hex: 0xA78BFA), Color(hex: 0x34D399), Color(hex: 0xFB923C),
        Color(hex: 0x60A5FA), Color(hex: 0xF472B6), Color(hex: 0xA3E635), Color(hex: 0xE879F9),
    ]
    static let muted = Color(hex: 0x94A3B8)
    static let unsure = Palette.amber
    static let unusable = Palette.red

    static func option(_ i: Int, muted mutedIndex: Int?) -> Color {
        if i == mutedIndex { return muted }
        // Skip the muted slot so the remaining options keep their fixed order of hues.
        let slot = mutedIndex.map { i > $0 ? i - 1 : i } ?? i
        return options[((slot % options.count) + options.count) % options.count]
    }

    static func bucket(_ b: ResultBucket, muted: Int?) -> Color {
        switch b {
        case .unusable: return unusable
        case .unsure: return unsure
        case .answer(let i): return option(i, muted: muted)
        case .rule(let i): return option(i, muted: muted).opacity(0.5)
        }
    }

    static func answerer(_ a: Answerer) -> Color {
        switch a {
        case .rule: return Palette.blue
        case .model: return Palette.cyan
        case .you: return Palette.okText
        case .waiting: return unsure
        case .failed: return unusable
        }
    }

    static func answererSymbol(_ a: Answerer) -> String {
        switch a {
        case .rule: return "ruler"
        case .model: return "cpu"
        case .you: return "person.fill.checkmark"
        case .waiting: return "questionmark.circle"
        case .failed: return "exclamationmark.triangle"
        }
    }

    static func kindSymbol(_ kind: String) -> String {
        switch kind {
        case "image": return "photo"
        case "PDF": return "doc.richtext"
        case "email": return "envelope"
        case "calendar event": return "calendar"
        case "contact": return "person.crop.circle"
        case "CSV": return "tablecells"
        case "HTML": return "chevron.left.forwardslash.chevron.right"
        default: return "doc.text"
        }
    }

    /// The glyph tile's source id (Send to Loupe files wear the Files glyph).
    static func glyphSource(_ id: String) -> String { id == "shared" ? "files" : id }
}
