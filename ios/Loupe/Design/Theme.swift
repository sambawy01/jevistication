import SwiftUI
import UIKit

// MARK: - Loupe dark neon (owner decision 2026-09-24)
//
// The one design system of the iPhone app: deep navy / near-black grounds in three layers, elevated cards with a
// thin cyan-blue border, a neon signal palette, and glow only on what is live or active (a running card, the
// focused item, particles, the mascot's eyes, chart peaks). Every colour, font and effect in the app comes from
// this file; views never write a colour literal (`scripts/check-colors.sh` style grep in docs/BUILD.md).
//
// Contrast (WCAG 2.x, measured on `card` #0E1834, the darkest surface text sits on is `ground` #070B18):
//   ink #EAF0FF 16.1:1 · inkSoft #A7B4D4 8.3:1 · blue #6B95FF 5.9:1 · cyan #22D3EE 10.2:1
//   okText #4ADE80 10.9:1 · warnText #FBBF24 11.0:1 · dangerText #FCA5A5 9.5:1 · onAccent #041026 on blue 5.6:1
// Reduce Motion: glows stay (static), nothing pulses, sweeps or rolls; counters still change.

enum Palette {
    // Grounds, back to front.
    static let ground = Color(hex: 0x070B18)
    static let groundMid = Color(hex: 0x0B1530)
    static let groundHigh = Color(hex: 0x13235A)
    // Surfaces.
    static let card = Color(hex: 0x0E1834)
    static let cardHigh = Color(hex: 0x14224A)
    // Text.
    static let ink = Color(hex: 0xEAF0FF)
    static let inkSoft = Color(hex: 0xA7B4D4)
    /// Text on a filled accent (primary buttons, active chips).
    static let onAccent = Color(hex: 0x041026)
    // Signals. `blue` is the text-safe accent; `blueBright` fills, strokes and glows.
    static let blue = Color(hex: 0x6B95FF)
    static let blueBright = Color(hex: 0x2F6BFF)
    static let cyan = Color(hex: 0x22D3EE)
    static let mint = Color(hex: 0x10B981)
    static let amber = Color(hex: 0xF59E0B)
    static let red = Color(hex: 0xEF4444)
    // The hero band's gradient (Now), now a deep glow rather than a light-page navy.
    static let navyTop = Color(hex: 0x0B1530)
    static let navyBottom = Color(hex: 0x13235A)
    // Lines.
    static let hairline = Color(hex: 0x22D3EE).opacity(0.16)
    static let border = Color(hex: 0x3B82F6).opacity(0.28)
    static let borderActive = Color(hex: 0x22D3EE).opacity(0.75)
    // Status text and tints, text-safe on the dark surfaces.
    static let okText = Color(hex: 0x4ADE80)
    static let okSoft = Color(hex: 0x10B981).opacity(0.16)
    static let warnText = Color(hex: 0xFBBF24)
    static let warnSoft = Color(hex: 0xF59E0B).opacity(0.16)
    static let dangerText = Color(hex: 0xFCA5A5)
    static let dangerSoft = Color(hex: 0xEF4444).opacity(0.18)
    static let track = Color(hex: 0x1A2A55)
    static let accentSoft = Color(hex: 0x2F6BFF).opacity(0.18)
    /// A dim veil over content (a full-screen image viewer, a paused game).
    static let scrim = Color(hex: 0x03060F).opacity(0.92)
    /// Light glyphs over the game's water and over images.
    static let overlayInk = Color(hex: 0xEAF0FF)

    /// The gate colours of the live run view (accepted, uncertain, flagged, skipped) and reading.
    static func gate(_ g: String) -> Color {
        switch g {
        case "accepted": return mint
        case "uncertain": return amber
        case "flagged": return red
        case "skipped": return inkSoft
        default: return cyan
        }
    }

    /// Who answered (the share bars).
    static func source(_ s: String) -> Color {
        switch s {
        case "laya", "multilingual": return cyan
        case "english": return blueBright
        case "rule": return blue
        case "baseline": return amber
        default: return mint
        }
    }

    /// UIKit twins for SpriteKit / UIKit drawing (the game, rendered stills).
    enum UI {
        static let ground = UIColor(hex: 0x070B18)
        static let water = UIColor(hex: 0x0B1530)
        static let waterLine = UIColor(hex: 0x1C3170)
        static let land = UIColor(hex: 0x13235A)
        static let landShade = UIColor(hex: 0x0E1A44)
        static let shore = UIColor(hex: 0x22D3EE)
        static let bullet = UIColor(hex: 0x7DE8F7)
        static let bridge = UIColor(hex: 0x2A3B6E)
        static let amber = UIColor(hex: 0xF59E0B)
        static let red = UIColor(hex: 0xEF4444)
        static let cyan = UIColor(hex: 0x06B6D4)
        static let blue = UIColor(hex: 0x2F6BFF)
        static let navy = UIColor(hex: 0x0B1B4D)
        static let mint = UIColor(hex: 0x10B981)
        static let ink = UIColor(hex: 0x0A1222)
        static let snow = UIColor(hex: 0xDDE6F5)
        static let paper = UIColor(hex: 0xFFFFFF)
        static let text = UIColor(hex: 0xEAF0FF)
        static let card = UIColor(hex: 0x0E1834)
        static let tabActive = UIColor(hex: 0x22D3EE)
        static let tabIdle = UIColor(hex: 0x8190B5)
    }
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

extension UIColor {
    convenience init(hex: UInt32, alpha: CGFloat = 1) {
        self.init(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
                  blue: CGFloat(hex & 0xFF) / 255, alpha: alpha)
    }
}

/// Rajdhani for display numerals/titles, SF for body, JetBrains Mono for data. All scale with Dynamic Type.
enum Typeface {
    static func display(_ size: CGFloat, bold: Bool = true) -> Font {
        .custom(bold ? "Rajdhani-Bold" : "Rajdhani-SemiBold", size: size, relativeTo: .title)
    }
    static func mono(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        .custom("JetBrains Mono", size: size, relativeTo: .body).weight(weight)
    }
}

/// Reduce Motion, also forced by DEBUG `-LoupeReduceMotion` (UI tests cannot switch the system setting).
enum Motion {
    static let forced: Bool = {
        #if DEBUG
        return ProcessInfo.processInfo.arguments.contains("-LoupeReduceMotion")
        #else
        return false
        #endif
    }()
    static func reduced(_ env: Bool) -> Bool { env || forced }
}

/// Effect tokens: radii, glow strengths, motion timings.
enum Effects {
    static let radius: CGFloat = 16
    static let glowSoft: CGFloat = 6
    static let glowStrong: CGFloat = 14
    static let borderSpin: Double = 3.2          // seconds per turn of a working card's border
    static let sweep: Animation = .easeOut(duration: 0.9)
    static let roll: Animation = .spring(response: 0.45, dampingFraction: 0.9)
}

// MARK: - App-wide chrome

enum NeonChrome {
    /// Tab bar, navigation bars, segmented controls and switches in the dark neon look. Called once at launch.
    @MainActor static func install() {
        let nav = UINavigationBarAppearance()
        nav.configureWithOpaqueBackground()
        nav.backgroundColor = Palette.UI.ground
        nav.shadowColor = Palette.UI.cyan.withAlphaComponent(0.18)
        nav.titleTextAttributes = [.foregroundColor: Palette.UI.text]
        nav.largeTitleTextAttributes = [.foregroundColor: Palette.UI.text]
        UINavigationBar.appearance().standardAppearance = nav
        UINavigationBar.appearance().scrollEdgeAppearance = nav
        UINavigationBar.appearance().compactAppearance = nav

        let tab = UITabBarAppearance()
        tab.configureWithOpaqueBackground()
        tab.backgroundColor = Palette.UI.ground
        tab.shadowColor = Palette.UI.cyan.withAlphaComponent(0.22)
        for item in [tab.stackedLayoutAppearance, tab.inlineLayoutAppearance, tab.compactInlineLayoutAppearance] {
            item.normal.iconColor = Palette.UI.tabIdle
            item.normal.titleTextAttributes = [.foregroundColor: Palette.UI.tabIdle]
            item.selected.iconColor = Palette.UI.tabActive
            item.selected.titleTextAttributes = [.foregroundColor: Palette.UI.tabActive]
        }
        UITabBar.appearance().standardAppearance = tab
        UITabBar.appearance().scrollEdgeAppearance = tab

        UISegmentedControl.appearance().selectedSegmentTintColor = Palette.UI.blue
        UISegmentedControl.appearance().setTitleTextAttributes([.foregroundColor: Palette.UI.text], for: .normal)
        UISegmentedControl.appearance().setTitleTextAttributes([.foregroundColor: Palette.UI.paper], for: .selected)
        UISwitch.appearance().onTintColor = Palette.UI.cyan
    }
}

// MARK: - Cards

struct CardStyle: ViewModifier {
    var active = false
    func body(content: Content) -> some View {
        content
            .padding(16)
            .background(
                LinearGradient(colors: [Palette.cardHigh, Palette.card], startPoint: .top, endPoint: .bottom),
                in: RoundedRectangle(cornerRadius: Effects.radius, style: .continuous))
            .overlay {
                if active {
                    WorkingBorder()
                } else {
                    RoundedRectangle(cornerRadius: Effects.radius, style: .continuous).stroke(Palette.border, lineWidth: 1)
                }
            }
            .shadow(color: active ? Palette.cyan.opacity(0.28) : .clear, radius: active ? Effects.glowStrong : 0)
    }
}

extension View {
    /// An elevated card; `active` gives a working card its animated neon border and glow.
    func card(active: Bool = false) -> some View { modifier(CardStyle(active: active)) }

    /// Neon glow for live and active things. Static under Reduce Motion (it never pulses anyway).
    func neonGlow(_ color: Color = Palette.cyan, radius: CGFloat = Effects.glowSoft, on: Bool = true) -> some View {
        shadow(color: on ? color.opacity(0.75) : .clear, radius: on ? radius : 0)
            .shadow(color: on ? color.opacity(0.35) : .clear, radius: on ? radius * 2 : 0)
    }

    /// A screen's ground: the deep navy gradient behind everything.
    func neonGround() -> some View {
        background(
            LinearGradient(colors: [Palette.groundMid, Palette.ground, Palette.ground], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea())
    }

    /// A List or Form on the neon ground (rows sit on `card` through `NeonSection`).
    func neonList() -> some View {
        scrollContentBackground(.hidden).neonGround()
    }
}

/// A working card's border: a cyan-blue sweep that turns while the job runs; a still gradient under Reduce Motion.
struct WorkingBorder: View {
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    private var reduceMotion: Bool { Motion.reduced(systemReduceMotion) }
    var radius: CGFloat = Effects.radius

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        if reduceMotion {
            shape.stroke(AngularGradient(colors: [Palette.cyan, Palette.blueBright, Palette.cyan], center: .center), lineWidth: 1.5)
        } else {
            TimelineView(.animation(minimumInterval: 1 / 30)) { ctx in
                let a = ctx.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: Effects.borderSpin) / Effects.borderSpin * 360
                shape.stroke(
                    AngularGradient(colors: [Palette.blueBright.opacity(0.35), Palette.cyan, Palette.blueBright.opacity(0.35), Palette.blueBright.opacity(0.35)],
                                    center: .center, angle: .degrees(a)),
                    lineWidth: 1.5)
                    // Rendered by Metal: a turning angular gradient drawn by Core Graphics costs the main thread
                    // several ms a frame (measured on the Sources scan, 2026-09-25).
                    .drawingGroup()
            }
        }
    }
}

/// A List/Form section on a dark card row background (SwiftUI's Section with the neon row surface).
struct NeonSection<Content: View, Header: View, Footer: View>: View {
    let content: Content
    let header: Header
    let footer: Footer

    init(@ViewBuilder content: () -> Content, @ViewBuilder header: () -> Header, @ViewBuilder footer: () -> Footer) {
        self.content = content(); self.header = header(); self.footer = footer()
    }

    var body: some View {
        Section {
            content.listRowBackground(Palette.card)
        } header: {
            header
        } footer: {
            footer
        }
    }
}

extension NeonSection where Header == EmptyView, Footer == EmptyView {
    init(@ViewBuilder content: () -> Content) { self.init(content: content, header: { EmptyView() }, footer: { EmptyView() }) }
}

extension NeonSection where Footer == EmptyView {
    init(@ViewBuilder content: () -> Content, @ViewBuilder header: () -> Header) {
        self.init(content: content, header: header, footer: { EmptyView() })
    }
}

extension NeonSection where Header == EmptyView {
    init(@ViewBuilder content: () -> Content, @ViewBuilder footer: () -> Footer) {
        self.init(content: content, header: { EmptyView() }, footer: footer)
    }
}

extension NeonSection where Header == Text, Footer == EmptyView {
    init(_ title: LocalizedStringKey, @ViewBuilder content: () -> Content) {
        self.init(content: content, header: { Text(title) }, footer: { EmptyView() })
    }
    @_disfavoredOverload
    init<S: StringProtocol>(_ title: S, @ViewBuilder content: () -> Content) {
        self.init(content: content, header: { Text(title) }, footer: { EmptyView() })
    }
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

// MARK: - Buttons

/// The primary action: a cyan-to-blue fill with dark text (5.6:1) and a soft glow.
struct NeonPrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body.weight(.semibold))
            .foregroundStyle(Palette.onAccent)
            .padding(.horizontal, 16).padding(.vertical, 10)
            .background(
                LinearGradient(colors: [Palette.cyan, Palette.blue], startPoint: .leading, endPoint: .trailing)
                    .opacity(enabled ? 1 : 0.4),
                in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .neonGlow(Palette.cyan, radius: configuration.isPressed ? 2 : 5, on: enabled)
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
    }
}

extension ButtonStyle where Self == NeonPrimaryButtonStyle {
    static var neonPrimary: NeonPrimaryButtonStyle { NeonPrimaryButtonStyle() }
}

// MARK: - Icons and charts

/// An SF Symbol in the neon style: hierarchical rendering, glowing when active.
struct NeonIcon: View {
    let name: String
    var color: Color = Palette.cyan
    var size: CGFloat = 18
    var active = false
    var body: some View {
        Image(systemName: name)
            .symbolRenderingMode(.hierarchical)
            .font(.system(size: size, weight: .semibold))
            .foregroundStyle(color)
            .neonGlow(color, radius: 4, on: active)
            .accessibilityHidden(true)
    }
}

/// A number that rolls to its new value (Reduce Motion: it just changes).
struct RollingNumber: View {
    let value: Int
    var font: Font = Typeface.display(28)
    var color: Color = Palette.ink
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    private var reduceMotion: Bool { Motion.reduced(systemReduceMotion) }

    var body: some View {
        Text("\(value)")
            .font(font)
            .monospacedDigit()
            .foregroundStyle(color)
            .contentTransition(reduceMotion ? .identity : .numericText(value: Double(value)))
            .animation(reduceMotion ? nil : Effects.roll, value: value)
    }
}

/// A bar that sweeps to its fraction, glowing at the tip while live.
struct SweepBar: View {
    let fraction: Double
    var color: Color = Palette.cyan
    var height: CGFloat = 8
    var live = false
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    private var reduceMotion: Bool { Motion.reduced(systemReduceMotion) }
    @State private var shown: Double = 0

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(Palette.track)
                Capsule().fill(LinearGradient(colors: [color.opacity(0.7), color], startPoint: .leading, endPoint: .trailing))
                    .frame(width: max(0, min(1, shown)) * g.size.width)
                    .neonGlow(color, radius: 4, on: live && shown > 0)
            }
        }
        .frame(height: height)
        .onAppear { set(fraction) }
        .onChange(of: fraction) { _, f in set(f) }
    }

    private func set(_ f: Double) {
        if reduceMotion { shown = f } else { withAnimation(Effects.sweep) { shown = f } }
    }
}

/// A donut of named parts that sweeps in; parts are (colour, value).
struct Donut: View {
    let parts: [(Color, Double)]
    var lineWidth: CGFloat = 12
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    private var reduceMotion: Bool { Motion.reduced(systemReduceMotion) }
    @State private var progress: Double = 0

    var body: some View {
        let total = max(parts.map(\.1).reduce(0, +), 0.0001)
        ZStack {
            Circle().stroke(Palette.track, lineWidth: lineWidth)
            ForEach(Array(parts.enumerated()), id: \.offset) { i, p in
                let start = parts.prefix(i).map(\.1).reduce(0, +) / total
                let end = start + p.1 / total
                Circle()
                    .trim(from: start * progress, to: end * progress)
                    .stroke(p.0, style: StrokeStyle(lineWidth: lineWidth, lineCap: .butt))
                    .rotationEffect(.degrees(-90))
                    .neonGlow(p.0, radius: 3, on: p.1 > 0)
            }
        }
        .onAppear { if reduceMotion { progress = 1 } else { withAnimation(Effects.sweep) { progress = 1 } } }
        .accessibilityHidden(true)
    }
}
