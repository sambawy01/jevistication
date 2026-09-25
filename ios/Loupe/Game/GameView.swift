import LoupeKit
import SpriteKit
import SwiftUI

/// Riverflight on the phone: the SpriteKit river between a navy score bar and a clean-room panel.
/// Human mode: drag anywhere on the river to steer, the FIRE button (a second thumb) to shoot, tap to
/// pause. Watch mode: Laya flies through the shared
/// Backend, with its raw probabilities, decisions per second and a scoreboard against the baseline
/// on the same seed.
struct GameView: View {
    @StateObject private var game: GameController
    @State private var scene = RiverScene(size: CGSize(width: 390, height: 446))
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    @AppStorage("game.haptics") private var haptics = true
    @AppStorage("game.reducedMotion") private var reducedMotionSetting = false
    @AppStorage("game.autoFire") private var autoFire = false

    init(mode: GameMode, seed: Int64 = 1) {
        _game = StateObject(wrappedValue: GameController(mode: mode, seed: seed))
    }

    private var reducedMotion: Bool { systemReduceMotion || reducedMotionSetting }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                topBar
                if game.mode == .watch, case .baseline = game.pilot { pilotBanner }
                river
                if game.mode == .human { controlBar }
                panel
            }
            .neonGround()
            .toolbar(.hidden, for: .navigationBar)
        }
        .onAppear(perform: syncSettings)
        .onChange(of: haptics) { syncSettings() }
        .onChange(of: reducedMotionSetting) { syncSettings() }
        .onChange(of: systemReduceMotion) { syncSettings() }
        .onChange(of: autoFire) { syncSettings() }
        .onChange(of: scenePhase) { _, phase in
            // Pause on background (and on the app switcher); the player resumes by choice.
            if phase != .active { game.setPaused(true) }
        }
        .onDisappear { game.close() }
        .onReceive(ModelReadiness.shared.$state) { s in if s.isReady { game.modelBecameReady() } }
    }

    private func syncSettings() {
        game.hapticsEnabled = haptics
        game.reducedMotion = reducedMotion
        game.autoFire = autoFire
        scene.reducedMotion = reducedMotion
    }

    // MARK: Top bar

    private var topBar: some View {
        VStack(spacing: 10) {
            HStack(spacing: 12) {
                Button { dismiss() } label: {
                    Image(systemName: "xmark").font(.system(size: 15, weight: .bold))
                        .frame(width: 34, height: 34)
                        .background(Palette.overlayInk.opacity(0.12), in: Circle())
                }
                .foregroundStyle(Palette.overlayInk)
                .accessibilityLabel("Close game")
                .accessibilityIdentifier("game.close")

                Picker("Mode", selection: Binding(get: { game.mode }, set: { game.switchMode($0) })) {
                    Text("You fly").tag(GameMode.human)
                    Text("Watch Loupe").tag(GameMode.watch)
                }
                .pickerStyle(.segmented)
                .accessibilityIdentifier("game.mode")

                Button { game.setRush(!game.rush) } label: {
                    Text("RUSH").font(Typeface.mono(11, weight: .bold)).tracking(0.8)
                        .padding(.horizontal, 10).frame(height: 34)
                        .background(game.rush ? Palette.amber.opacity(0.28) : Palette.overlayInk.opacity(0.12), in: Capsule())
                        .overlay(Capsule().stroke(game.rush ? Palette.amber : .clear, lineWidth: 1))
                }
                .foregroundStyle(game.rush ? Palette.amber : Palette.overlayInk)
                .accessibilityLabel("Rush mode")
                .accessibilityValue(game.rush ? "on, starts at level \(Difficulty.companion.RUSH_START)" : "off")
                .accessibilityHint("Restarts the river")
                .accessibilityIdentifier("game.rush")

                Button { game.setPaused(!game.paused) } label: {
                    Image(systemName: game.paused ? "play.fill" : "pause.fill").font(.system(size: 14, weight: .bold))
                        .frame(width: 34, height: 34)
                        .background(Palette.overlayInk.opacity(0.12), in: Circle())
                }
                .foregroundStyle(Palette.overlayInk)
                .accessibilityLabel(game.paused ? "Resume" : "Pause")
                .accessibilityIdentifier("game.pause")
            }
            TopStats(store: game.top, watching: game.mode == .watch)
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 10)
        .background(LinearGradient(colors: [Palette.navyTop, Palette.navyBottom], startPoint: .top, endPoint: .bottom)
            .ignoresSafeArea(edges: .top))
    }

    // MARK: River

    private var river: some View {
        GeometryReader { geo in
            ZStack {
                SpriteView(scene: scene, preferredFramesPerSecond: 60, options: [.ignoresSiblingOrder])
                    .onAppear {
                        scene.size = geo.size
                        scene.controller = game
                        syncSettings()
                    }
                    .onChange(of: geo.size) { _, s in scene.size = s }
                    .accessibilityHidden(true)
                // Every river touch lands on this UIKit layer, not on the SKView: it steers (and a
                // quick tap pauses). FIRE is its own view in the control bar below, never over the river.
                RiverTouchSurface(game: game, label: riverLabel)
                if let level = game.levelFlash, !game.paused { LevelFlash(level: level, reducedMotion: reducedMotion) }
                if game.paused { pausedOverlay }
                else if let r = game.results { ResultsCard(results: r, seed: game.seed, onNext: { game.newRiver() }) }
                else { OverOverlay(store: game.top, human: game.mode == .human, onAgain: { game.newRiver() }) }
            }
        }
        .aspectRatio(CGFloat(GameController.columns) / CGFloat(GameController.viewRows), contentMode: .fit)
        .frame(maxWidth: .infinity)
        .layoutPriority(1)
        .clipped()
    }

    private var riverLabel: String {
        game.mode == .human
            ? "River. Drag left or right to steer; steering never fires. Fire with the Fire button below the river. Tap to pause."
            : "River. \(pilotName) is flying. Tap to pause."
    }

    private var pausedOverlay: some View {
        VStack(spacing: 12) {
            Text("Paused").font(Typeface.display(34)).foregroundStyle(Palette.overlayInk)
            Button("Resume") { game.setPaused(false) }
                .buttonStyle(.neonPrimary)
                .accessibilityIdentifier("game.resume")
        }
        .padding(24)
        .background(Palette.navyTop.opacity(0.85), in: RoundedRectangle(cornerRadius: 16))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("game.paused")
    }

    // MARK: Panel

    @ViewBuilder
    private var panel: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                // A compact, stable panel: it redraws only its numbers (5 Hz), never moves or
                // scrolls on its own. The full live run pipeline stays on Now, not here.
                if game.mode == .human { humanPanel } else { watchPanel }
            }
            .padding(16)
        }
        .scrollBounceBehavior(.basedOnSize)
    }

    /// You fly: a compact bar under the river — a one-line hint, and FIRE in the right thumb zone. It
    /// keeps its size while paused (FIRE is hidden then), so the river never moves.
    private var controlBar: some View {
        HStack(spacing: 12) {
            Text("Drag the river to steer · tap to pause")
                .font(.footnote.weight(.medium)).foregroundStyle(Palette.overlayInk.opacity(0.85))
                .lineLimit(2).minimumScaleFactor(0.8)
                .accessibilityLabel("Drag the river to steer; steering never fires. Tap the river to pause. Hold Fire to shoot.")
                .accessibilityIdentifier("game.help")
            Spacer(minLength: 8)
            ZStack {
                if !game.paused, game.results == nil {
                    FireTouchSurface(game: game)
                    FireButton(state: game.fireButton, reducedMotion: reducedMotion, onActivate: { game.fireOnce() })
                }
            }
            .frame(width: FireButtonLayout.touchSize, height: FireButtonLayout.touchSize)
        }
        .padding(.leading, 16).padding(.trailing, 12).padding(.vertical, 2)
        .frame(maxWidth: .infinity)
        .background(Palette.navyBottom)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("game.controls")
    }

    private var humanPanel: some View {
        VStack(alignment: .leading, spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Toggle("Auto-fire", isOn: $autoFire).accessibilityIdentifier("game.autofire")
                    .accessibilityHint("Fires whenever the gun is ready, but holds fire while a fuel depot is ahead")
                Text("Holds fire while a fuel depot is ahead. FIRE always shoots.")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
                    .accessibilityHidden(true)
            }
            settingsToggles
            Text("The safety net still stands behind you: it flies a move that avoids a crash only when yours cannot.")
                .font(.footnote).foregroundStyle(Palette.inkSoft)
        }
        .card()
    }

    private var settingsToggles: some View {
        VStack(spacing: 6) {
            Toggle("Haptics on hits", isOn: $haptics)
            Toggle("Reduced motion", isOn: $reducedMotionSetting)
                .disabled(systemReduceMotion)
            if systemReduceMotion {
                Text("On, following the system Reduce Motion setting.").font(.caption).foregroundStyle(Palette.inkSoft)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .font(.subheadline)
    }

    private var pilotName: String {
        switch game.pilot {
        case .laya: return "The Loupe Decision Model"
        case .you: return "You"
        default: return "The baseline autopilot"
        }
    }

    @ViewBuilder
    private var watchPanel: some View {
        if case .baseline = game.pilot, ModelSettingsService.shared.wasLayaOff(Features.shared.GAME) {
            LayaOffBanner(feature: Features.shared.GAME)
        } else if game.pilot == .opening {
            Label("Opening the decision model… the rule-based pilot flies meanwhile.", systemImage: "hourglass")
                .font(.footnote).foregroundStyle(Palette.inkSoft)
        }
        SpeedPanel(store: game.panel, laya: game.pilot == .laya, seed: game.seed)
    }

    /// Watch mode without Laya: said once, plainly, above the river, with the way to fix it.
    private var pilotBanner: some View {
        let reason: String = { if case .baseline(let r) = game.pilot { return r } else { return "" } }()
        return HStack(spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(Palette.amber).accessibilityHidden(true)
            Text(reason).font(.footnote.weight(.semibold)).foregroundStyle(Palette.overlayInk)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 4)
            if !ModelSettingsService.shared.wasLayaOff(Features.shared.GAME) {
                GetLayaButton(id: "game.getModel").foregroundStyle(Palette.amber)
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.amber.opacity(0.18))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("game.noModel")
    }

}

/// The Play card on Now: the point is the model deciding live, fast, on this phone, with nothing
/// leaving it. The only numbers are ones this iPhone measured (the last finished watch run).
struct PlayCard: View {
    var onPlay: (GameMode) -> Void
    @State private var last = LastRun.read()

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Caption(text: "Riverflight")
                    Text("Watch Loupe fly").font(Typeface.display(26)).foregroundStyle(Palette.ink)
                }
                Spacer()
                Image(systemName: "airplane").font(.system(size: 26, weight: .semibold))
                    .foregroundStyle(Palette.blue).rotationEffect(.degrees(-90))
                    .accessibilityHidden(true)
            }
            Text("The Loupe Decision Model decides every move live, on this iPhone: many times a second, with nothing leaving the phone.")
                .font(.subheadline).foregroundStyle(Palette.inkSoft)
                .fixedSize(horizontal: false, vertical: true)
            if let last {
                Text(Self.lastLine(last))
                    .font(Typeface.mono(12)).monospacedDigit().foregroundStyle(Palette.ink)
                    .accessibilityIdentifier("now.play.last")
            }
            HStack(spacing: 10) {
                Button { onPlay(.watch) } label: {
                    Label("Watch", systemImage: "eye").lineLimit(1).minimumScaleFactor(0.75).frame(maxWidth: .infinity)
                }
                .accessibilityLabel("Watch Loupe fly")
                .buttonStyle(.neonPrimary)
                .accessibilityIdentifier("now.play.watch")
                Button { onPlay(.human) } label: {
                    Label("Play", systemImage: "gamecontroller").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("now.play.human")
            }
        }
        .card()
        .padding(.horizontal, 16)
        .onAppear { last = LastRun.read() }
    }

    /// "Last run on this iPhone: 11.8 decisions/s · P50 24 ms · 0 bytes out".
    static func lastLine(_ n: LastRun.Numbers) -> String {
        var parts = [String(format: "%.1f decisions/s", n.perSecond)]
        if let p = n.p50 { parts.append(String(format: "P50 %.0f ms", p)) }
        parts.append("0 bytes out")
        return "Last run on this iPhone: " + parts.joined(separator: " · ")
    }
}


// MARK: Speed showcase

/// The score bar's numbers. Observes only the 10 Hz top store, so the river's parent never redraws.
struct TopStats: View {
    @ObservedObject var store: HUDStore
    let watching: Bool

    var body: some View {
        let h = store.hud
        HStack(alignment: .firstTextBaseline) {
            stat("SCORE", "\(h.score)", id: "game.score")
            Spacer()
            stat("LEVEL", "\(h.level)", id: "game.level")
            Spacer()
            MascotView(state: h.over ? .empty : (watching ? .scanning : .watching), size: 44)
                .alignmentGuide(.firstTextBaseline) { $0[.bottom] - 6 }
            Spacer()
            fuelGauge(h.fuelPercent)
            Spacer()
            stat("ROWS", "\(h.rows)", id: "game.rows", alignment: .trailing)
        }
    }

    private func stat(_ label: String, _ value: String, id: String, alignment: HorizontalAlignment = .leading) -> some View {
        VStack(alignment: alignment, spacing: 0) {
            Text(label).font(Typeface.mono(10, weight: .medium)).tracking(0.8).foregroundStyle(Palette.overlayInk.opacity(0.65))
            Text(value).font(Typeface.display(30)).monospacedDigit().foregroundStyle(Palette.cyan)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label.capitalized)
        .accessibilityValue(value)
        .accessibilityIdentifier(id)
    }

    private func fuelGauge(_ fuel: Int) -> some View {
        let low = Double(fuel) < Rules.shared.FUEL_LOW
        return VStack(spacing: 4) {
            Text("FUEL").font(Typeface.mono(10, weight: .medium)).tracking(0.8).foregroundStyle(Palette.overlayInk.opacity(0.65))
            ZStack(alignment: .leading) {
                Capsule().fill(Palette.overlayInk.opacity(0.15))
                Capsule().fill(low ? Palette.amber : Palette.mint)
                    .frame(width: 110 * CGFloat(fuel) / 100)
            }
            .frame(width: 110, height: 8)
            Text("\(fuel)%").font(Typeface.mono(11)).foregroundStyle(Palette.overlayInk.opacity(0.85))
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Fuel")
        .accessibilityValue(low ? "\(fuel) percent, low" : "\(fuel) percent")
        .accessibilityIdentifier("game.fuel")
    }
}

/// The run-over note over the river (human, or the baseline between rivers).
struct OverOverlay: View {
    @ObservedObject var store: HUDStore
    let human: Bool
    var onAgain: () -> Void

    var body: some View {
        let h = store.hud
        if h.over {
            VStack(spacing: 8) {
                Text(h.death ?? "Run over").font(Typeface.display(28)).foregroundStyle(Palette.overlayInk)
                Text("Score \(h.score) · \(h.rows) rows").font(Typeface.mono(13)).foregroundStyle(Palette.overlayInk.opacity(0.85))
                if human {
                    Button("Fly again", action: onAgain).buttonStyle(.neonPrimary)
                        .accessibilityIdentifier("game.again")
                } else {
                    Text("Next river in a moment…").font(.footnote).foregroundStyle(Palette.overlayInk.opacity(0.75))
                }
            }
            .padding(20)
            .background(Palette.navyTop.opacity(0.85), in: RoundedRectangle(cornerRadius: 16))
        }
    }
}

/// Watch mode's panel: a compact, fixed-layout speed readout. Numbers change in place (5 Hz);
/// nothing slides, scrolls or animates on its own. The run's pipeline stays on Now.
struct SpeedPanel: View {
    @ObservedObject var store: HUDStore
    let laya: Bool
    let seed: Int64

    var body: some View {
        let h = store.hud
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Caption(text: laya ? "On this iPhone · level \(h.level)" : "Rule-based pilot · level \(h.level)")
                    .lineLimit(1).minimumScaleFactor(0.8)
                Spacer(minLength: 6)
                if laya { Pill(text: "0 bytes out", color: Palette.mint, symbol: "wifi.slash").fixedSize() }
            }
            HStack(alignment: .lastTextBaseline, spacing: 8) {
                Text(String(format: "%.1f", h.decisionsPerSecond))
                    .font(Typeface.display(56)).monospacedDigit()
                    .foregroundStyle(laya ? Palette.cyan : Palette.inkSoft)
                    .frame(minWidth: 120, alignment: .leading)
                VStack(alignment: .leading, spacing: 2) {
                    Text("DECISIONS/S").font(Typeface.mono(11, weight: .medium)).tracking(0.8).foregroundStyle(Palette.inkSoft)
                    Text("game asks \(String(format: "%.0f", h.askedPerSecond))/s")
                        .font(Typeface.mono(12)).monospacedDigit()
                        .foregroundStyle(h.askedPerSecond > h.decisionsPerSecond + 0.5 ? Palette.warnText : Palette.inkSoft)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Decisions per second")
            .accessibilityValue(String(format: "%.1f, the game asks for %.0f", h.decisionsPerSecond, h.askedPerSecond))
            .accessibilityIdentifier("game.dps")
            HStack(spacing: 16) {
                metric("P50", h.latencyP50.map { String(format: "%.0f ms", $0) } ?? "—", label: "Latency P50 on this iPhone")
                metric("P95", h.latencyP95.map { String(format: "%.0f ms", $0) } ?? "—", label: "Latency P95 on this iPhone")
                metric("ON TIME", h.onTimePercent.map { String(format: "%.0f%%", $0) } ?? "—", label: "Decisions on time", id: "game.speed.ontime")
                metric("AVOIDED", "\(h.collisionsAvoided)/\(h.collisionChoices)", label: "Predicted collisions avoided, of decisions where one was on offer", id: "game.speed.avoided")
            }
            if laya {
                Sparkline(values: h.sparkline).frame(height: 40).accessibilityHidden(true)
            }
            Text("asked \(h.requested) · answered \(h.modelDecisions + h.mechanical + h.failures) · dropped \(h.dropped) · late \(h.lateAnswers) · peak \(String(format: "%.1f", h.maxSustained))/s · \(h.fps) fps")
                .font(Typeface.mono(11)).monospacedDigit()
                .foregroundStyle(h.dropped > 0 ? Palette.warnText : Palette.inkSoft)
                .lineLimit(1).minimumScaleFactor(0.7)
                .accessibilityLabel("Asked \(h.requested), answered \(h.modelDecisions + h.mechanical + h.failures), dropped \(h.dropped), late \(h.lateAnswers), \(h.fps) frames per second")
                .accessibilityIdentifier("game.speed.dropped")
            Text("model \(h.modelDecisions) · mechanical \(h.mechanical) · failed \(h.failures) · safety net \(h.takeovers)×")
                .font(Typeface.mono(11)).monospacedDigit().foregroundStyle(Palette.inkSoft)
                .lineLimit(1).minimumScaleFactor(0.7)
                .accessibilityIdentifier("game.fps")
            if laya {
                HStack {
                    Text("Same river, seed \(seed)").font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
                    Spacer()
                    Text("Loupe \(h.rows) rows · rules \(h.baselineRows ?? 0) rows\(h.baselineOver ? " (down)" : "")")
                        .font(Typeface.mono(11)).monospacedDigit().foregroundStyle(Palette.inkSoft).lineLimit(1).minimumScaleFactor(0.7)
                }
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("game.scoreboard")
            }
            bars(h)
        }
        .card()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("game.speed")
    }

    private func metric(_ title: String, _ value: String, label: String, id: String? = nil) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title).font(Typeface.mono(10, weight: .medium)).tracking(0.8).foregroundStyle(Palette.inkSoft)
            Text(value).font(Typeface.mono(16, weight: .semibold)).monospacedDigit().foregroundStyle(Palette.ink)
                .lineLimit(1).minimumScaleFactor(0.7)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
        .accessibilityValue(value)
        .accessibilityIdentifier(id ?? "")
    }

    /// The chosen move, and the model's shares when it chose: its raw answer with its measured word
    /// bias divided out (what it chose by; `PilotDecision.adjusted`). Not calibrated probabilities.
    private func bars(_ h: HUDSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(h.source).font(.caption).foregroundStyle(Palette.inkSoft).lineLimit(1)
                .accessibilityIdentifier("game.source")
            ForEach(h.bars) { bar in
                HStack(spacing: 8) {
                    Text(bar.label)
                        .font(.system(size: 12, weight: bar.chosen ? .bold : .regular))
                        .foregroundStyle(bar.excluded == nil ? Palette.ink : Palette.inkSoft.opacity(0.6))
                        .frame(width: 138, alignment: .leading)
                        .lineLimit(1)
                    Capsule().fill(Palette.hairline)
                        .overlay(alignment: .leading) {
                            Capsule().fill(bar.chosen ? Palette.blue : Palette.cyan.opacity(0.7))
                                .scaleEffect(x: bar.raw ?? (bar.chosen ? 1 : 0), y: 1, anchor: .leading)
                        }
                        .frame(height: 8)
                    Text(bar.excluded ?? bar.raw.map { String(format: "%.2f", $0) } ?? (bar.chosen ? "chosen" : ""))
                        .font(Typeface.mono(11)).monospacedDigit()
                        .foregroundStyle(Palette.inkSoft)
                        .frame(width: 56, alignment: .trailing)
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(bar.label)
                .accessibilityValue(Self.barValue(bar))
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("game.bars")
    }

    static func barValue(_ bar: ActionBar) -> String {
        var parts: [String] = []
        if let raw = bar.raw { parts.append("model's share \(Int((raw * 100).rounded())) percent") }
        if let ex = bar.excluded { parts.append(ex.contains("crash") ? "not offered, would crash" : "not offered, would shoot the last fuel") }
        if bar.chosen { parts.append("chosen") }
        return parts.isEmpty ? "not chosen" : parts.joined(separator: ", ")
    }
}

/// Recent latencies, oldest left, with a glow line.
struct Sparkline: View {
    let values: [Double]

    var body: some View {
        Canvas { ctx, size in
            guard values.count > 1 else { return }
            let top = max(values.max() ?? 1, 1) * 1.15
            let dx = size.width / CGFloat(values.count - 1)
            var path = Path()
            for (i, v) in values.enumerated() {
                let p = CGPoint(x: CGFloat(i) * dx, y: size.height * (1 - CGFloat(v / top)))
                if i == 0 { path.move(to: p) } else { path.addLine(to: p) }
            }
            var fill = path
            fill.addLine(to: CGPoint(x: size.width, y: size.height))
            fill.addLine(to: CGPoint(x: 0, y: size.height))
            fill.closeSubpath()
            ctx.fill(fill, with: .linearGradient(Gradient(colors: [Palette.cyan.opacity(0.28), .clear]),
                                                 startPoint: .zero, endPoint: CGPoint(x: 0, y: size.height)))
            ctx.stroke(path, with: .color(Palette.cyan), lineWidth: 1.8)
        }
        .background(Palette.track.opacity(0.35), in: RoundedRectangle(cornerRadius: 6))
    }
}

/// "LEVEL N" over the river as it speeds up. Reduce Motion: a plain label, no zoom.
struct LevelFlash: View {
    let level: Int
    let reducedMotion: Bool
    @State private var shown = false

    var body: some View {
        VStack(spacing: 2) {
            Text("LEVEL \(level)").font(Typeface.display(44)).foregroundStyle(Palette.cyan)
                .shadow(color: Palette.cyan.opacity(0.8), radius: 14)
            Text("FASTER").font(Typeface.mono(13, weight: .bold)).tracking(2).foregroundStyle(Palette.overlayInk)
        }
        .scaleEffect(reducedMotion || shown ? 1 : 1.6)
        .opacity(shown ? 1 : 0)
        .onAppear { withAnimation(reducedMotion ? .linear(duration: 0.15) : .spring(duration: 0.35)) { shown = true } }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .id(level)
    }
}

/// The end of a watch run. Headline: this run's decisions, measured on this iPhone. Secondary, but
/// never hidden: how far the model flew against the rule-based pilot on the same river.
struct ResultsCard: View {
    let results: RunResults
    let seed: Int64
    var onNext: () -> Void

    var body: some View {
        let r = results
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Caption(text: "Run results · on this iPhone · seed \(seed)\(r.rush ? " · rush" : "")")
                Text(r.death ?? "Run over").font(Typeface.display(26)).foregroundStyle(Palette.overlayInk)
                HStack(spacing: 16) {
                    big("\(r.totalDecisions)", "DECISIONS", id: "game.results.total")
                    big(String(format: "%.1f", r.averagePerSecond), "PER SECOND", id: "game.results.rate")
                }
                HStack(spacing: 14) {
                    small("P50", r.p50.map { String(format: "%.0f ms", $0) } ?? "—")
                    small("P95", r.p95.map { String(format: "%.0f ms", $0) } ?? "—")
                    small("ON TIME", r.onTimePercent.map { String(format: "%.0f%%", $0) } ?? "—")
                    small("PEAK", String(format: "%.1f/s", r.maxSustained))
                        .accessibilityIdentifier("game.results.max")
                }
                HStack(spacing: 14) {
                    small("COLLISIONS AVOIDED", "\(r.collisionsAvoided) of \(r.collisionChoices)")
                        .accessibilityIdentifier("game.results.avoided")
                    small("SAFETY NET", "\(r.takeovers)×")
                        .accessibilityIdentifier("game.results.takeovers")
                    small("OUT", "0 bytes")
                }
                Text("Collisions avoided: decisions where one way was predicted to crash and another was safe, and the model flew a safe one.")
                    .font(.caption2).foregroundStyle(Palette.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
                Text("Distance on the same river: the decision model \(r.layaRows) rows (level \(r.layaLevel)), the rule-based pilot \(r.baselineRows) rows\(r.baselineAlive ? ", still flying" : "") (level \(r.baselineLevel)).")
                    .font(.footnote).foregroundStyle(Palette.overlayInk.opacity(0.85))
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("game.results.distance")
                Text("Same safety net for both. Over 20 test rivers the rule-based pilot went further on average (543 rows to 502).")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
                Button("Next river", action: onNext).buttonStyle(.neonPrimary).frame(maxWidth: .infinity)
                    .accessibilityIdentifier("game.results.next")
            }
            .padding(18)
        }
        .background(Palette.card.opacity(0.96), in: RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(Palette.borderActive, lineWidth: 1))
        .shadow(color: Palette.cyan.opacity(0.25), radius: 18)
        .padding(12)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("game.results")
    }

    private func big(_ v: String, _ label: String, id: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(v).font(Typeface.display(40)).monospacedDigit().foregroundStyle(Palette.cyan)
                .shadow(color: Palette.cyan.opacity(0.5), radius: 10)
            Text(label).font(Typeface.mono(10, weight: .medium)).tracking(0.8).foregroundStyle(Palette.inkSoft)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label.capitalized)
        .accessibilityValue(v)
        .accessibilityIdentifier(id)
    }

    private func small(_ label: String, _ v: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(label).font(Typeface.mono(10, weight: .medium)).tracking(0.8).foregroundStyle(Palette.inkSoft)
            Text(v).font(Typeface.mono(15, weight: .semibold)).foregroundStyle(Palette.overlayInk)
        }
        .accessibilityElement(children: .combine)
    }
}
