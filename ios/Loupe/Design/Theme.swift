import SwiftUI

/// Visual system from prototype v8: the blue-white "clean room".
enum Palette {
    static let ground = Color(hex: 0xDDE6F5)
    static let card = Color.white
    static let ink = Color(hex: 0x0A1222)
    static let inkSoft = Color(hex: 0x0A1222).opacity(0.62)
    static let blue = Color(hex: 0x1F55E0)
    static let blueBright = Color(hex: 0x2F6BFF)
    static let cyan = Color(hex: 0x06B6D4)
    static let mint = Color(hex: 0x10B981)
    static let amber = Color(hex: 0xF59E0B)
    static let red = Color(hex: 0xEF4444)
    static let navyTop = Color(hex: 0x0B1B4D)
    static let navyBottom = Color(hex: 0x16307F)
    static let hairline = Color(hex: 0x0A1222).opacity(0.08)
}

extension Color {
    init(hex: UInt32) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255,
                  opacity: 1)
    }
}

/// Rajdhani for display numerals/titles, SF for body, JetBrains Mono for data.
enum Typeface {
    static func display(_ size: CGFloat, bold: Bool = true) -> Font {
        .custom(bold ? "Rajdhani-Bold" : "Rajdhani-SemiBold", size: size, relativeTo: .title)
    }
    static func mono(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        .custom("JetBrains Mono", size: size, relativeTo: .body).weight(weight)
    }
}

struct CardStyle: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(16)
            .background(Palette.card, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Palette.hairline))
            .shadow(color: Palette.ink.opacity(0.06), radius: 10, y: 4)
    }
}

extension View {
    func card() -> some View { modifier(CardStyle()) }
}

/// Section caption in the prototype's quiet small-caps style.
struct Caption: View {
    let text: String
    var body: some View {
        Text(text.uppercased())
            .font(Typeface.mono(11, weight: .medium))
            .tracking(0.8)
            .foregroundStyle(Palette.inkSoft)
    }
}
