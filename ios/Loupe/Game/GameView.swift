import LoupeKit
import SpriteKit
import SwiftUI

/// Riverflight on the phone: the SpriteKit river between a navy score bar and a clean-room panel.
/// Human mode: drag to steer, hold to fire, tap to pause. Watch mode: Laya flies through the shared
/// Backend, with its raw probabilities, decisions per second and a scoreboard against the baseline
/// on the same seed.
struct GameView: View {
    @StateObject private var game: GameController
    @State private var scene = RiverScene(size: CGSize(width: 390, height: 446))
    @State private var touching = false
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
                river
                panel
            }
            .background(Palette.ground.ignoresSafeArea())
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
                        .background(.white.opacity(0.12), in: Circle())
                }
                .foregroundStyle(.white)
                .accessibilityLabel("Close game")
                .accessibilityIdentifier("game.close")

                Picker("Mode", selection: Binding(get: { game.mode }, set: { game.switchMode($0) })) {
                    Text("You fly").tag(GameMode.human)
                    Text("Watch Laya").tag(GameMode.watch)
                }
                .pickerStyle(.segmented)
                .accessibilityIdentifier("game.mode")

                Button { game.setPaused(!game.paused) } label: {
                    Image(systemName: game.paused ? "play.fill" : "pause.fill").font(.system(size: 14, weight: .bold))
                        .frame(width: 34, height: 34)
                        .background(.white.opacity(0.12), in: Circle())
                }
                .foregroundStyle(.white)
                .accessibilityLabel(game.paused ? "Resume" : "Pause")
                .accessibilityIdentifier("game.pause")
            }
            HStack(alignment: .firstTextBaseline) {
                stat("SCORE", "\(game.hud.score)", id: "game.score")
                Spacer()
                MascotView(state: game.hud.over ? .empty : (game.mode == .watch ? .scanning : .watching), size: 44)
                    .alignmentGuide(.firstTextBaseline) { $0[.bottom] - 6 }
                Spacer()
                fuelGauge
                Spacer()
                stat("ROWS", "\(game.hud.rows)", id: "game.rows", alignment: .trailing)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 10)
        .background(LinearGradient(colors: [Palette.navyTop, Palette.navyBottom], startPoint: .top, endPoint: .bottom)
            .ignoresSafeArea(edges: .top))
    }

    private func stat(_ label: String, _ value: String, id: String, alignment: HorizontalAlignment = .leading) -> some View {
        VStack(alignment: alignment, spacing: 0) {
            Text(label).font(Typeface.mono(10, weight: .medium)).tracking(0.8).foregroundStyle(.white.opacity(0.65))
            Text(value).font(Typeface.display(30)).monospacedDigit().foregroundStyle(Palette.cyan)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label.capitalized)
        .accessibilityValue(value)
        .accessibilityIdentifier(id)
    }

    private var fuelGauge: some View {
        let fuel = game.hud.fuelPercent
        let low = Double(fuel) < Rules.shared.FUEL_LOW
        return VStack(spacing: 4) {
            Text("FUEL").font(Typeface.mono(10, weight: .medium)).tracking(0.8).foregroundStyle(.white.opacity(0.65))
            ZStack(alignment: .leading) {
                Capsule().fill(.white.opacity(0.15))
                Capsule().fill(low ? Palette.amber : Palette.mint)
                    .frame(width: 110 * CGFloat(fuel) / 100)
            }
            .frame(width: 110, height: 8)
            Text("\(fuel)%").font(Typeface.mono(11)).foregroundStyle(.white.opacity(0.85))
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Fuel")
        .accessibilityValue(low ? "\(fuel) percent, low" : "\(fuel) percent")
        .accessibilityIdentifier("game.fuel")
    }

    // MARK: River

    private var river: some View {
        GeometryReader { geo in
            let width = geo.size.width
            ZStack {
                SpriteView(scene: scene, preferredFramesPerSecond: 60, options: [.ignoresSiblingOrder])
                    .onAppear {
                        scene.size = geo.size
                        scene.controller = game
                        syncSettings()
                    }
                    .onChange(of: geo.size) { _, s in scene.size = s }
                    .accessibilityHidden(true)
                // Touches land on this layer, not on the SKView, so SwiftUI owns the gesture.
                Color.clear
                    .contentShape(Rectangle())
                    .gesture(steerGesture(width: width))
                    .accessibilityElement()
                    .accessibilityLabel(riverLabel)
                    .accessibilityIdentifier("game.river")
                    .accessibilityAddTraits(.allowsDirectInteraction)
                if game.paused { pausedOverlay }
                else if game.hud.over { overOverlay }
            }
        }
        .aspectRatio(CGFloat(GameController.columns) / CGFloat(GameController.viewRows), contentMode: .fit)
        .frame(maxWidth: .infinity)
        .layoutPriority(1)
        .clipped()
    }

    private var riverLabel: String {
        game.mode == .human
            ? "River. Drag left or right to steer, hold to fire, tap to pause."
            : "River. \(pilotName) is flying. Tap to pause."
    }

    private func steerGesture(width: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 0, coordinateSpace: .local)
            .onChanged { v in
                let col = ColumnMapping.column(x: v.location.x, width: width, columns: GameController.columns)
                if !touching {
                    touching = true
                    game.touchBegan(column: col, x: v.location.x, time: CACurrentMediaTime())
                }
                game.touchMoved(column: col, x: v.location.x)
            }
            .onEnded { _ in
                touching = false
                game.touchEnded(time: CACurrentMediaTime())
            }
    }

    private var pausedOverlay: some View {
        VStack(spacing: 12) {
            Text("Paused").font(Typeface.display(34)).foregroundStyle(.white)
            Button("Resume") { game.setPaused(false) }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("game.resume")
        }
        .padding(24)
        .background(Palette.navyTop.opacity(0.85), in: RoundedRectangle(cornerRadius: 16))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("game.paused")
    }

    private var overOverlay: some View {
        VStack(spacing: 8) {
            Text(game.hud.death ?? "Run over").font(Typeface.display(28)).foregroundStyle(.white)
            Text("Score \(game.hud.score) · \(game.hud.rows) rows").font(Typeface.mono(13)).foregroundStyle(.white.opacity(0.85))
            if game.mode == .human {
                Button("Fly again") { game.newRiver() }.buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("game.again")
            } else {
                Text("Next river in a moment…").font(.footnote).foregroundStyle(.white.opacity(0.75))
            }
        }
        .padding(20)
        .background(Palette.navyTop.opacity(0.85), in: RoundedRectangle(cornerRadius: 16))
    }

    // MARK: Panel

    @ViewBuilder
    private var panel: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if game.mode == .human { humanPanel } else { watchPanel }
            }
            .padding(16)
        }
        .scrollBounceBehavior(.basedOnSize)
    }

    private var humanPanel: some View {
        VStack(alignment: .leading, spacing: 10) {
            Caption(text: "You are flying")
            Text("Drag left or right to steer. Hold to fire. Tap to pause.")
                .font(.subheadline).foregroundStyle(Palette.ink)
            Toggle("Auto-fire", isOn: $autoFire).accessibilityIdentifier("game.autofire")
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
        case .laya: return "Laya"
        case .you: return "You"
        default: return "The baseline autopilot"
        }
    }

    @ViewBuilder
    private var watchPanel: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Caption(text: game.pilot == .laya ? "Laya is flying" : "Baseline autopilot is flying")
                Spacer()
                if game.pilot == .laya { Pill(text: "On device", color: Palette.mint, symbol: "cpu") }
            }
            switch game.pilot {
            case .opening:
                Label("Checking and opening Laya… the baseline flies meanwhile.", systemImage: "hourglass")
                    .font(.footnote).foregroundStyle(Palette.inkSoft)
            case .baseline(let reason):
                VStack(alignment: .leading, spacing: 6) {
                    Text(reason).font(.footnote).foregroundStyle(Palette.ink)
                    NavigationLink { LayaModelView() } label: {
                        Label("Me → Laya model", systemImage: "arrow.down.circle")
                            .font(Typeface.mono(12, weight: .medium))
                    }
                    .accessibilityIdentifier("game.getModel")
                }
                .padding(10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Palette.amber.opacity(0.12), in: RoundedRectangle(cornerRadius: 10))
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("game.noModel")
            default:
                EmptyView()
            }
            Text(game.hud.source).font(.footnote).foregroundStyle(Palette.inkSoft)
                .accessibilityIdentifier("game.source")
            bars
            readouts
        }
        .card()
        if game.pilot == .laya { scoreboard }
    }

    private var bars: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(game.pilot == .laya ? "RAW MODEL OUTPUT (UNCALIBRATED)" : "DECISION")
                .font(Typeface.mono(10, weight: .medium)).tracking(0.8).foregroundStyle(Palette.inkSoft)
            ForEach(game.hud.bars) { bar in
                HStack(spacing: 8) {
                    Text(bar.label)
                        .font(.system(size: 12, weight: bar.chosen ? .bold : .regular))
                        .foregroundStyle(bar.excluded == nil ? Palette.ink : Palette.inkSoft.opacity(0.6))
                        .frame(width: 138, alignment: .leading)
                        .lineLimit(1)
                    GeometryReader { g in
                        ZStack(alignment: .leading) {
                            RoundedRectangle(cornerRadius: 3).fill(Palette.hairline)
                            if let raw = bar.raw {
                                RoundedRectangle(cornerRadius: 3).fill(bar.chosen ? Palette.blue : Palette.cyan.opacity(0.7))
                                    .frame(width: max(2, g.size.width * raw))
                            } else if bar.chosen {
                                RoundedRectangle(cornerRadius: 3).stroke(Palette.blue, lineWidth: 1.5)
                            }
                        }
                    }
                    .frame(height: 10)
                    Text(bar.excluded ?? bar.raw.map { String(format: "%.2f", $0) } ?? (bar.chosen ? "chosen" : ""))
                        .font(Typeface.mono(11))
                        .foregroundStyle(Palette.inkSoft)
                        .frame(width: 56, alignment: .trailing)
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(bar.label)
                .accessibilityValue(barValue(bar))
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("game.bars")
    }

    private func barValue(_ bar: ActionBar) -> String {
        var parts: [String] = []
        if let raw = bar.raw { parts.append("raw probability \(Int((raw * 100).rounded())) percent") }
        if let ex = bar.excluded { parts.append(ex.contains("crash") ? "not offered, would crash" : "not offered, would shoot the last fuel") }
        if bar.chosen { parts.append("chosen") }
        return parts.isEmpty ? "not chosen" : parts.joined(separator: ", ")
    }

    private var readouts: some View {
        let h = game.hud
        return VStack(alignment: .leading, spacing: 3) {
            mono("decisions/s  \(String(format: "%.1f", h.decisionsPerSecond))", id: "game.dps")
            mono("latency p50  \(h.latencyP50.map { String(format: "%.0f ms", $0) } ?? "—")")
            mono("model \(h.modelDecisions) · mechanical \(h.mechanical) · failed \(h.failures) · overrides \(h.overrides)")
            mono("\(h.fps) fps", id: "game.fps")
        }
    }

    private func mono(_ s: String, id: String? = nil) -> some View {
        Text(s).font(Typeface.mono(11)).foregroundStyle(Palette.ink)
            .accessibilityIdentifier(id ?? "")
    }

    private var scoreboard: some View {
        let h = game.hud
        return VStack(alignment: .leading, spacing: 8) {
            Caption(text: "Same river, seed \(game.seed)")
            HStack(spacing: 0) {
                scoreColumn("Laya", score: h.score, rows: h.rows, over: h.over, lead: h.score >= (h.baselineScore ?? 0))
                scoreColumn("Baseline", score: h.baselineScore ?? 0, rows: h.baselineRows ?? 0, over: h.baselineOver,
                            lead: (h.baselineScore ?? 0) > h.score)
            }
            Text("The baseline is a few lines of rules. Untuned Laya has no reason to beat it; this shows which one does.")
                .font(.caption).foregroundStyle(Palette.inkSoft)
        }
        .card()
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("game.scoreboard")
    }

    private func scoreColumn(_ name: String, score: Int, rows: Int, over: Bool, lead: Bool) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(name.uppercased()).font(Typeface.mono(10, weight: .medium)).foregroundStyle(lead ? Palette.blue : Palette.inkSoft)
            Text("\(score)").font(Typeface.display(28)).monospacedDigit().foregroundStyle(Palette.ink)
            Text("\(rows) rows\(over ? " · down" : "")").font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// The Play card on Now.
struct PlayCard: View {
    var onPlay: (GameMode) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Caption(text: "Riverflight")
                    Text("Watch Laya fly").font(Typeface.display(26)).foregroundStyle(Palette.ink)
                }
                Spacer()
                Image(systemName: "airplane").font(.system(size: 26, weight: .semibold))
                    .foregroundStyle(Palette.blue).rotationEffect(.degrees(-90))
                    .accessibilityHidden(true)
            }
            Text("The on-device model flies a river and shows its work: every choice, live, against a few lines of rules on the same river.")
                .font(.subheadline).foregroundStyle(Palette.inkSoft)
            HStack(spacing: 10) {
                Button { onPlay(.watch) } label: {
                    Label("Watch Laya fly", systemImage: "eye").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
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
    }
}
