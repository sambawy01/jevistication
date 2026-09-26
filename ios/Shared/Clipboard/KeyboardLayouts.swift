import Foundation

/// The Loupe keyboard's keys (2026-09-26): English (QWERTY) and Arabic letters, numbers and symbols
/// for each, as data. Compiled into the app too, so LoupeTests checks the layouts.
enum KeyboardLanguage: String, Codable, CaseIterable {
    case english = "en"
    case arabic = "ar"

    /// The language key's label: the *other* language.
    var switchLabel: String { self == .english ? "ع" : "EN" }
    var spaceLabel: String { self == .english ? "space" : "مسافة" }
    var isRTL: Bool { self == .arabic }
}

enum KeyboardPage: Equatable {
    case letters, numbers, symbols
}

enum KeyAction: Equatable {
    /// Types the text (a letter, a digit, punctuation).
    case text(String)
    case shift
    case delete
    case space
    case returnKey
    /// iOS's next-keyboard key (shown only when iOS says it is needed).
    case nextKeyboard
    /// English ⇄ Arabic inside the Loupe keyboard.
    case language
    case page(KeyboardPage)
}

struct KeySpec: Equatable {
    let action: KeyAction
    let label: String
    /// Width in key units (a letter is 1).
    var width: Double = 1
    /// Long-press alternatives (أ إ آ on ا …).
    var alternates: [String] = []

    var isCharacter: Bool { if case .text = action { return true } else { return false } }

    static func t(_ s: String, _ alternates: [String] = []) -> KeySpec { KeySpec(action: .text(s), label: s, alternates: alternates) }
}

enum KeyboardLayouts {
    /// Every row, top to bottom. [shifted] upper-cases English letters; [nextKeyboard] adds the globe
    /// key (iOS asks for it when the phone has no other way to change keyboards).
    static func rows(_ language: KeyboardLanguage, page: KeyboardPage, shifted: Bool, nextKeyboard: Bool,
                     returnLabel: String = "return") -> [[KeySpec]] {
        var rows: [[KeySpec]]
        switch (language, page) {
        case (.english, .letters): rows = english(shifted: shifted)
        case (.arabic, .letters): rows = arabic
        case (_, .numbers): rows = numbers(language)
        case (_, .symbols): rows = symbols(language)
        }
        rows.append(bottomRow(language, page: page, nextKeyboard: nextKeyboard, returnLabel: returnLabel))
        return rows
    }

    private static func letters(_ s: String, upper: Bool) -> [KeySpec] {
        s.map { c in let l = upper ? String(c).uppercased() : String(c); return KeySpec.t(l) }
    }

    static func english(shifted: Bool) -> [[KeySpec]] {
        [
            letters("qwertyuiop", upper: shifted),
            letters("asdfghjkl", upper: shifted),
            [KeySpec(action: .shift, label: "shift", width: 1.4)] + letters("zxcvbnm", upper: shifted) + [KeySpec(action: .delete, label: "delete", width: 1.4)],
        ]
    }

    /// Arabic as on the iPhone's own Arabic keyboard: ض on the Q key, the 28 letters plus ة ء ى, and the
    /// hamza forms by holding a key (ا → أ إ آ, و → ؤ, ى → ئ, ء → أ ؤ ئ إ, ه → ة).
    static let arabic: [[KeySpec]] = [
        ["ض", "ص", "ق", "ف", "غ", "ع", "ه", "خ", "ح", "ج"].map { KeySpec.t($0, $0 == "ه" ? ["ة"] : []) },
        ["ش", "س", "ي", "ب", "ل", "ا", "ت", "ن", "م", "ك", "ة"].map { KeySpec.t($0, $0 == "ا" ? ["أ", "إ", "آ", "ٱ"] : $0 == "ي" ? ["ئ", "ى"] : []) },
        ["ء", "ظ", "ط", "ذ", "د", "ز", "ر", "و", "ى", "ث"].map {
            KeySpec.t($0, $0 == "و" ? ["ؤ"] : $0 == "ى" ? ["ئ"] : $0 == "ء" ? ["أ", "ؤ", "ئ", "إ"] : [])
        } + [KeySpec(action: .delete, label: "delete", width: 1)],
    ]

    static func numbers(_ language: KeyboardLanguage) -> [[KeySpec]] {
        let western = Array("1234567890").map(String.init)
        let arabicDigits = ["١", "٢", "٣", "٤", "٥", "٦", "٧", "٨", "٩", "٠"]
        let digits: [KeySpec] = language == .arabic
            ? zip(arabicDigits, western).map { KeySpec.t($0.0, [$0.1]) }
            : zip(western, arabicDigits).map { KeySpec.t($0.0, [$0.1]) }
        let second = language == .arabic ? ["-", "/", ":", "؛", "(", ")", "$", "&", "@", "\""] : ["-", "/", ":", ";", "(", ")", "$", "&", "@", "\""]
        let third = language == .arabic ? [".", "،", "؟", "!", "'"] : [".", ",", "?", "!", "'"]
        return [
            digits,
            second.map { KeySpec.t($0) },
            [KeySpec(action: .page(.symbols), label: "#+=", width: 1.4)] + third.map { KeySpec(action: .text($0), label: $0, width: 1.3) }
                + [KeySpec(action: .delete, label: "delete", width: 1.4)],
        ]
    }

    static func symbols(_ language: KeyboardLanguage) -> [[KeySpec]] {
        let third = language == .arabic ? [".", "،", "؟", "!", "'"] : [".", ",", "?", "!", "'"]
        return [
            ["[", "]", "{", "}", "#", "%", "^", "*", "+", "="].map { KeySpec.t($0) },
            ["_", "\\", "|", "~", "<", ">", "€", "£", "¥", "•"].map { KeySpec.t($0) },
            [KeySpec(action: .page(.numbers), label: language == .arabic ? "١٢٣" : "123", width: 1.4)]
                + third.map { KeySpec(action: .text($0), label: $0, width: 1.3) } + [KeySpec(action: .delete, label: "delete", width: 1.4)],
        ]
    }

    static func bottomRow(_ language: KeyboardLanguage, page: KeyboardPage, nextKeyboard: Bool, returnLabel: String) -> [KeySpec] {
        let pageKey = page == .letters
            ? KeySpec(action: .page(.numbers), label: language == .arabic ? "١٢٣" : "123", width: 1.3)
            : KeySpec(action: .page(.letters), label: language == .arabic ? "أ ب ج" : "ABC", width: 1.3)
        var row = [pageKey]
        if nextKeyboard { row.append(KeySpec(action: .nextKeyboard, label: "next keyboard", width: 1.1)) }
        row.append(KeySpec(action: .language, label: language.switchLabel, width: 1.1))
        row.append(KeySpec(action: .space, label: language.spaceLabel, width: nextKeyboard ? 4.4 : 5.5))
        row.append(KeySpec(action: .returnKey, label: returnLabel, width: 2.1))
        return row
    }

    /// The return key's words for the field's return key type.
    static func returnLabel(_ type: String, language: KeyboardLanguage) -> String {
        let en: [String: String] = ["go": "go", "search": "search", "send": "send", "done": "done", "next": "next", "join": "join", "route": "route"]
        let ar: [String: String] = ["go": "انتقال", "search": "بحث", "send": "إرسال", "done": "تم", "next": "التالي", "join": "انضمام", "route": "توجيه"]
        return (language == .arabic ? ar[type] : en[type]) ?? (language == .arabic ? "رجوع" : "return")
    }
}
