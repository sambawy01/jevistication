import UIKit

/// The Loupe keyboard's colours: Loupe's dark navy and cyan in dark mode, a clean light set in light mode
/// (the field's keyboard appearance decides, else the system's).
struct KeyboardTheme {
    let background: UIColor
    let key: UIColor
    let specialKey: UIColor
    let pressed: UIColor
    let text: UIColor
    let softText: UIColor
    let shadow: UIColor
    let strip: UIColor
    let accent: UIColor
    let ok: UIColor
    let warn: UIColor
    let danger: UIColor

    static let dark = KeyboardTheme(
        background: UIColor(red: 0.043, green: 0.063, blue: 0.125, alpha: 1),
        key: UIColor(red: 0.157, green: 0.184, blue: 0.275, alpha: 1),
        specialKey: UIColor(red: 0.094, green: 0.114, blue: 0.188, alpha: 1),
        pressed: UIColor(red: 0.235, green: 0.275, blue: 0.408, alpha: 1),
        text: UIColor(red: 0.918, green: 0.941, blue: 1, alpha: 1),
        softText: UIColor(red: 0.655, green: 0.706, blue: 0.831, alpha: 1),
        shadow: UIColor.black.withAlphaComponent(0.55),
        strip: UIColor(red: 0.055, green: 0.094, blue: 0.204, alpha: 1),
        accent: UIColor(red: 0.133, green: 0.827, blue: 0.933, alpha: 1),
        ok: UIColor(red: 0.290, green: 0.871, blue: 0.502, alpha: 1),
        warn: UIColor(red: 0.984, green: 0.749, blue: 0.141, alpha: 1),
        danger: UIColor(red: 0.988, green: 0.647, blue: 0.647, alpha: 1))

    static let light = KeyboardTheme(
        background: UIColor(red: 0.82, green: 0.835, blue: 0.86, alpha: 1),
        key: .white,
        specialKey: UIColor(red: 0.671, green: 0.69, blue: 0.733, alpha: 1),
        pressed: UIColor(red: 0.78, green: 0.8, blue: 0.835, alpha: 1),
        text: .black,
        softText: UIColor(red: 0.28, green: 0.3, blue: 0.35, alpha: 1),
        shadow: UIColor.black.withAlphaComponent(0.3),
        strip: UIColor(red: 0.906, green: 0.914, blue: 0.933, alpha: 1),
        accent: UIColor(red: 0.031, green: 0.569, blue: 0.698, alpha: 1),
        ok: UIColor(red: 0.082, green: 0.502, blue: 0.239, alpha: 1),
        warn: UIColor(red: 0.706, green: 0.325, blue: 0.035, alpha: 1),
        danger: UIColor(red: 0.725, green: 0.11, blue: 0.11, alpha: 1))

    static func current(appearance: UIKeyboardAppearance?, traits: UITraitCollection) -> KeyboardTheme {
        switch appearance {
        case .dark: return .dark
        case .light: return .light
        default: return traits.userInterfaceStyle == .light ? .light : .dark
        }
    }
}
