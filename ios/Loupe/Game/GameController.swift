import Foundation
import LoupeKit
import UIKit

enum GameMode: String, CaseIterable {
    case human, watch
}

/// Who is flying the watched run.
enum PilotStatus: Equatable {
    /// Human mode: you are.
    case you
    /// Laya is being verified and opened; the baseline flies meanwhile.
    case opening
    /// Laya, through the shared Backend.
    case laya
    /// The baseline autopilot, and why (no model, or it failed to open).
    case baseline(reason: String)
}

/// One bar in the probability panel.
struct ActionBar: Equatable, Identifiable {
    var id: String { label }
    let label: String
    /// The model's share for this move (raw, with its measured word bias divided out: what it chose
    /// by), or nil when no model was asked. Not a calibrated probability.
    let raw: Double?
    let chosen: Bool
    /// "✕ crash" / "✕ fuel" when the action was not offered.
    let excluded: String?
}

/// What the SwiftUI HUD shows. Refreshed ten times a second, not every frame.
struct HUDSnapshot: Equatable {
    var score = 0
    var fuelPercent = 100
    var rows = 0
    var over = false
    var death: String?
    var bars: [ActionBar] = []
    var source = "—"
    var decisionsPerSecond = 0.0
    var latencyP50: Double?
    var modelDecisions = 0
    var mechanical = 0
    var failures = 0
    var overrides = 0
    var baselineScore: Int?
    var baselineRows: Int?
    var baselineOver = false
    var fps = 0
    // Speed showcase
    var level = 1
    var askedPerSecond = 0.0
    var latencyP95: Double?
    /// Recent model latencies in ms, oldest first, for the sparkline.
    var sparkline: [Double] = []
    var requested = 0
    var dropped = 0
    var lateAnswers = 0
    var maxSustained = 0.0
    /// Percent of decisions that landed within one decision interval; nil before any.
    var onTimePercent: Double?
    /// Decisions where a way was predicted to crash and another predicted safe, and of those, the
    /// ones where the pilot flew a safe way (`DecisionStats.collisionChoices` / `collisionsAvoided`).
    var collisionChoices = 0
    var collisionsAvoided = 0
    /// Separate times the safety net took over.
    var takeovers = 0
}

/// A published HUD snapshot, observed by the views that draw it and nothing else.
@MainActor
final class HUDStore: ObservableObject {
    @Published var hud = HUDSnapshot()
}

/// The end-of-run card for a Laya run: what the phone sustained, and Laya against the baseline.
struct RunResults: Equatable {
    var maxSustained: Double
    /// Decisions landed per second over the whole run (simulation time).
    var averagePerSecond: Double = 0
    var onTimePercent: Double?
    var collisionChoices: Int = 0
    var collisionsAvoided: Int = 0
    var takeovers: Int = 0
    var totalDecisions: Int
    var modelDecisions: Int
    var dropped: Int
    var p50: Double?
    var p95: Double?
    /// Rows flown in each level reached, in level order.
    var rowsPerLevel: [(level: Int, rows: Int)]
    var layaScore: Int
    var layaRows: Int
    var layaLevel: Int
    var death: String?
    var baselineScore: Int
    var baselineRows: Int
    var baselineLevel: Int
    /// True when the baseline was still flying as Laya went down.
    var baselineAlive: Bool
    var rush: Bool

    static func == (a: RunResults, b: RunResults) -> Bool {
        a.totalDecisions == b.totalDecisions && a.layaRows == b.layaRows && a.baselineRows == b.baselineRows
            && a.maxSustained == b.maxSustained && a.rowsPerLevel.map(\.rows) == b.rowsPerLevel.map(\.rows)
    }
}

/// The game's state and loop. The SpriteKit scene calls `advance(to:)` every frame; the simulation
/// runs whole 60 Hz ticks from the accumulated time (as the desktop `GameController`), so the frame
/// rate never changes the game. Everything here runs on the main thread; only the pilot's
/// `decide` leaves it (PilotScheduler).
@MainActor
final class GameController: ObservableObject {
    @Published private(set) var mode: GameMode
    @Published private(set) var pilot: PilotStatus = .you
    @Published private(set) var paused = false
    /// The HUD lives in its own stores, observed only by the leaf views that draw it, so a HUD
    /// refresh never re-renders the river's parent view (21 fps on device before this; see
    /// docs/BUILD.md 2026-09-25). `top` refreshes at 10 Hz, `panel` at 5 Hz.
    let top = HUDStore()
    let panel = HUDStore()
    var hud: HUDSnapshot { panel.hud }
    @Published var autoFire = false
    /// Rush: the river starts at level 4.
    @Published private(set) var rush: Bool
    /// The level just reached, while its flash shows.
    @Published private(set) var levelFlash: Int?
    /// Set when a Laya run ends; the card stays until the next river.
    @Published private(set) var results: RunResults?
    private var flashClear: DispatchWorkItem?
    private var runStart: TimeInterval?
    private var maxSustained = 0.0

    private(set) var seed: Int64
    private(set) var session: GameSession
    /// Watch mode with Laya flying: the baseline on the same seed, in lockstep, for the scoreboard.
    private(set) var shadow: GameSession?
    private var scheduler: PilotScheduler?
    /// Held while Laya flies (the game outranks sweeps). `nonisolated(unsafe)` so deinit can drop it:
    /// a controller freed without `close()` must not leave passive sorting paused for good.
    nonisolated(unsafe) private var modelClaim: ModelClaim?
    deinit { modelClaim?.release() }
    private var steering = TouchSteering()
    /// The FIRE button is held (human mode). Separate from the steering touch: steering never fires.
    private(set) var fireHeld = false
    /// VoiceOver pressed FIRE: fire the next shot the gun is ready for, then stop.
    private var firePulse = false
    /// The FIRE button's pressed look, in its own store so a press never re-renders the river's parent.
    let fireButton = FireButtonState()

    /// Explosions for the scene to draw: world x, y and whether it is a big one.
    private(set) var pendingEffects: [(x: Double, y: Double, big: Bool)] = []

    var reducedMotion = false
    var hapticsEnabled = true

    private var lastTime: TimeInterval?
    private var accumulated: TimeInterval = 0
    private var overSince: TimeInterval?
    private var frames = 0
    private var fpsWindowStart: TimeInterval?
    private var fps = 0
    private var ticksSinceHUD = 0
    private var openTask: Task<Void, Never>?
    private let backendProvider: @MainActor () async -> Backend?
    private let modelInstalled: @MainActor () -> Bool
    private let executor: PilotExecutor
    private let settings: ModelSettingsSource
    private let returnToSimulation: (@escaping () -> Void) -> Void

    private let hitHaptic = UIImpactFeedbackGenerator(style: .light)
    private let crashHaptic = UINotificationFeedbackGenerator()

    static let notInstalled = "Needs the Loupe Decision Model on this iPhone. The rule-based pilot flies meanwhile."
    static let tick: TimeInterval = 1.0 / 60.0
    static let maxCatchUp: TimeInterval = 0.1
    static let autoRestartAfter: TimeInterval = 2.5
    static let columns = Int(Rules.shared.COLUMNS)
    static let viewRows = Int(Rules.shared.VIEW_ROWS)

    init(mode: GameMode,
         seed: Int64 = 1,
         rush: Bool = false,
         backendProvider: @escaping @MainActor () async -> Backend? = { LaunchOptions.current.noModel ? nil : await LayaModel.shared.backend() },
         modelInstalled: @escaping @MainActor () -> Bool = { ModelReadiness.shared.isReady },
         executor: PilotExecutor = QueuePilotExecutor.shared,
         returnToSimulation: @escaping (@escaping () -> Void) -> Void = { w in DispatchQueue.main.async(execute: w) },
         settings: ModelSettingsSource = ModelSettingsService.shared) {
        self.settings = settings
        self.mode = mode
        self.seed = seed
        self.rush = rush
        self.backendProvider = backendProvider
        self.modelInstalled = modelInstalled
        self.executor = executor
        self.returnToSimulation = returnToSimulation
        self.session = GameSessions.shared.human(seed: seed)
        start(mode: mode, seed: seed)
    }

    // MARK: Runs

    func switchMode(_ newMode: GameMode) {
        guard newMode != mode else { return }
        start(mode: newMode, seed: seed)
    }

    func newRiver() { start(mode: mode, seed: seed + 1) }

    /// Laya arrived while the baseline was flying for want of it: restart the river with Laya.
    func modelBecameReady() {
        guard mode == .watch, case .baseline(let reason) = pilot, reason == Self.notInstalled, modelInstalled() else { return }
        start(mode: mode, seed: seed)
    }

    func setRush(_ on: Bool) {
        guard on != rush else { return }
        rush = on
        start(mode: mode, seed: seed)
    }

    /// Rows per level: 200, or `-LoupeGameLevelRows N` (UI tests, demos).
    static var levelRows: Int32 {
        let args = ProcessInfo.processInfo.arguments
        if let i = args.firstIndex(of: "-LoupeGameLevelRows"), i + 1 < args.count, let n = Int32(args[i + 1]), n > 0 { return n }
        return Difficulty.companion.LEVEL_ROWS
    }

    var difficulty: Difficulty {
        GameSessions.shared.difficulty(name: rush ? "rush" : "progressive", levelRows: Self.levelRows)
    }

    private func start(mode: GameMode, seed: Int64) {
        openTask?.cancel()
        openTask = nil
        self.mode = mode
        self.seed = seed
        steering.cancel()
        releaseFire()
        switch mode {
        case .human:
            pilot = .you
            begin(session: GameSessions.shared.humanOn(seed: seed, difficulty: difficulty), scheduler: nil, shadow: nil)
        case .watch:
            // Model settings (`features.game`), read at each start: off, the baseline flies.
            let layaOn = settings.useLaya(Features.shared.GAME)
            settings.recordRun(Features.shared.GAME, layaOff: !layaOn)
            if !layaOn {
                flyBaseline(reason: MS.t("banner.game"))
                return
            }
            if !modelInstalled() {
                flyBaseline(reason: Self.notInstalled)
                return
            }
            pilot = .opening
            // The baseline flies while Laya's files are verified and opened (a few seconds, once).
            begin(session: GameSessions.shared.baselineOn(seed: seed, difficulty: difficulty), scheduler: nil, shadow: nil)
            openTask = Task { [weak self] in
                guard let self else { return }
                let backend = await self.backendProvider()
                guard !Task.isCancelled, self.mode == .watch, self.seed == seed else { return }
                if let backend {
                    self.flyLaya(backend)
                } else {
                    self.flyBaseline(reason: "The decision model could not be opened (see Me → Decision model), so the rule-based pilot is flying.")
                }
            }
        }
    }

    private func flyBaseline(reason: String) {
        pilot = .baseline(reason: reason)
        begin(session: GameSessions.shared.baselineOn(seed: seed, difficulty: difficulty), scheduler: nil, shadow: nil)
    }

    /// Laya flies the same `ModelPilot` as the desktop game; the baseline flies the same seed alongside.
    func flyLaya(_ backend: Backend) {
        pilot = .laya
        // While Laya flies, the game holds the model lane: a passive sort pauses (and resumes after).
        modelClaim?.release()
        modelClaim = ModelWork.lane.claim(priority: .game)
        let policy = settings.policy(Features.shared.GAME)
        let decider = GameSessions.shared.modelDeciderWithBudget(backend: backend, stateBudget: Int32(policy.budget(builtIn: ModelPilot.companion.STATE_BUDGET)))
        let scheduler = PilotScheduler(decider: decider, executor: executor, returnToSimulation: returnToSimulation)
        let source = settings
        // Read live on the simulation (main) thread, so a change applies mid-run.
        scheduler.maxPerSecond = { [weak source] in
            MainActor.assumeIsolated { source?.current.gameMaxDecisionsPerS?.doubleValue }
        }
        begin(session: GameSessions.shared.hostedOn(seed: seed, decider: decider,
                                                    decisionInterval: GameSession.companion.DEFAULT_DECISION_INTERVAL,
                                                    difficulty: difficulty),
              scheduler: scheduler,
              shadow: GameSessions.shared.baselineOn(seed: seed, difficulty: difficulty))
    }

    private func begin(session: GameSession, scheduler: PilotScheduler?, shadow: GameSession?) {
        if scheduler == nil { modelClaim?.release(); modelClaim = nil }
        self.scheduler?.close()
        self.session.close()
        self.shadow?.close()
        self.session = session
        self.scheduler = scheduler
        self.shadow = shadow
        accumulated = 0
        overSince = nil
        results = nil
        levelFlash = nil
        runStart = nil
        maxSustained = 0
        pendingEffects.removeAll()
        // The pilot's live run (a mobile-only kind, `game`): each decision it makes, who made it, where it went.
        liveJob?.finish("cancelled", "act.res.stopped")
        liveJob = ActivityCenter.shared.start("game", title: "act.title.game", view: "game", stage: "act.stage.playing")
        reported = (0, 0, 0)
        refreshHUD()
    }

    private var liveJob: LiveJob?
    private var reported: (model: Int, mechanical: Int, failures: Int) = (0, 0, 0)

    /// New decisions since the last HUD refresh go to the live run: an action label and its probability only.
    private func reportDecisions(_ h: HUDSnapshot) {
        guard let job = liveJob else { return }
        let chosen = h.bars.first { $0.chosen }
        let label = chosen?.label.lowercased()
        let dm = h.modelDecisions - reported.model, dr = h.mechanical - reported.mechanical, df = h.failures - reported.failures
        if dm > 0 {
            job.count("read", dm)
            job.decision("move", label, chosen?.raw, src: "laya", model: "multilingual")
            job.gate("accepted", dm)
        }
        if dr > 0 {
            job.count("read", dr)
            job.decision("move", label, 1, src: "rule")
            job.gate("accepted", dr)
        }
        if df > 0 { job.count("read", df); job.gate("skipped", df) }
        #if DEBUG
        if DeviceDiag.enabled, dm + dr + df > 0, (h.modelDecisions + h.mechanical) / 20 != (reported.model + reported.mechanical) / 20 {
            DeviceDiag.say("game model=\(h.modelDecisions) rules=\(h.mechanical) failures=\(h.failures)")
        }
        #endif
        reported = (h.modelDecisions, h.mechanical, h.failures)
        if h.over {
            job.finish("done", "act.res.game", ["score": h.score, "rows": h.rows])
            liveJob = nil
        }
    }

    // MARK: Loop

    func setPaused(_ value: Bool) {
        paused = value
        lastTime = nil
        steering.cancel()
        releaseFire()
    }

    /// Called by the scene every frame with its clock.
    func advance(to now: TimeInterval) {
        countFrame(now)
        defer { lastTime = now }
        guard let last = lastTime, !paused else { return }
        accumulated += min(now - last, Self.maxCatchUp)
        while accumulated >= Self.tick {
            step(now: now)
            accumulated -= Self.tick
        }
        if session.world.over {
            if overSince == nil { overSince = now }
            if results == nil, mode == .watch, pilot == .laya { results = makeResults() }
            // A Laya run keeps its results card until the viewer moves on; the baseline loops.
            if mode == .watch, results == nil, let since = overSince, now - since > Self.autoRestartAfter {
                newRiver()
            }
        }
    }

    /// One simulation tick, public for tests.
    func step(now: TimeInterval) {
        let world = session.world
        if mode == .human {
            let k = steering.keys(playerX: world.playerX)
            let fire = FireControl.fire(button: fireHeld || firePulse, autoFire: autoFire) {
                Self.depotInLineOfFire(world)
            }
            // A VoiceOver press lasts until the gun is ready, so it always fires one shot.
            if firePulse, world.cooldown == 0 { firePulse = false }
            session.human = HumanInput(left: k.left, right: k.right, fire: fire)
        }
        // Model settings' cap (`features.game.max_decisions_per_s`, nil = uncapped) floors the cadence.
        if let cap = settings.current.gameMaxDecisionsPerS?.doubleValue, cap > 0 {
            session.minInterval = Int32(max(1, (60 / cap).rounded(.up)))
        } else {
            session.minInterval = 1
        }
        if runStart == nil { runStart = now }
        let wasOver = world.over
        session.tick()
        // Once the run is over the world stops stepping and never clears its last events;
        // reading them again every frame replayed the crash haptic until the screen closed.
        for event in (wasOver ? [] : world.events) {
            if let up = event as? GameEventLevelUp { levelUp(Int(up.level)) }
            if let d = event as? GameEventDestroyed {
                pendingEffects.append((d.x, d.y, d.what == "bridge"))
                if mode == .human { haptic(crash: false) }
            } else if let d = event as? GameEventDied {
                pendingEffects.append((d.x, d.y, true))
                haptic(crash: true)
            }
        }
        scheduler?.afterTick()
        if let shadow, !shadow.world.over { shadow.tick() }
        ticksSinceHUD += 1
        if ticksSinceHUD % 6 == 0 { refreshHUD(panel: ticksSinceHUD % 12 == 0) }
        if ticksSinceHUD >= 60 { ticksSinceHUD = 0 }
    }

    private func levelUp(_ level: Int) {
        levelFlash = level
        UIAccessibility.post(notification: .announcement, argument: "Level \(level). Faster.")
        flashClear?.cancel()
        let clear = DispatchWorkItem { [weak self] in
            MainActor.assumeIsolated { if self?.levelFlash == level { self?.levelFlash = nil } }
        }
        flashClear = clear
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.6, execute: clear)
    }

    /// Rows flown in each level of [world], in level order.
    static func rowsPerLevel(_ world: World) -> [(level: Int, rows: Int)] {
        let d = world.difficulty
        let top = Int(world.level)
        let start = Int(d.level(row: 0))
        let flown = Int(world.cameraY)
        return (start...max(start, top)).map { l in
            let first = Int(d.firstRow(level: Int32(l))?.intValue ?? 0)
            let next = l < top ? Int(d.firstRow(level: Int32(l + 1))?.intValue ?? flown) : flown
            return (l, max(0, min(next, flown) - first))
        }
    }

    /// The results card, from this run's own counters (the session's `DecisionStats` and world).
    /// Also remembered as the "last run on this iPhone" line on the Watch card.
    func makeResults() -> RunResults {
        let w = session.world, s = session.stats
        let b = shadow?.world
        let seconds = Double(w.tick) / Double(Rules.shared.TICK_HZ)
        let r = RunResults(
            maxSustained: maxSustained,
            averagePerSecond: seconds > 0 ? Double(s.total) / seconds : 0,
            onTimePercent: s.onTimePercent()?.doubleValue,
            collisionChoices: Int(s.collisionChoices),
            collisionsAvoided: Int(s.collisionsAvoided),
            takeovers: Int(s.takeovers),
            totalDecisions: Int(s.total),
            modelDecisions: Int(s.count(source: .model)),
            dropped: Int(s.dropped),
            p50: s.latencyMillis(q: 0.5)?.doubleValue,
            p95: s.latencyMillis(q: 0.95)?.doubleValue,
            rowsPerLevel: Self.rowsPerLevel(w),
            layaScore: Int(w.score), layaRows: Int(w.cameraY), layaLevel: Int(w.level),
            death: w.death.map { Self.deathText($0) },
            baselineScore: Int(b?.score ?? 0), baselineRows: Int(b?.cameraY ?? 0), baselineLevel: Int(b?.level ?? 1),
            baselineAlive: !(b?.over ?? true),
            rush: rush)
        LastRun.remember(r)
        return r
    }

    func drainEffects() -> [(x: Double, y: Double, big: Bool)] {
        defer { pendingEffects.removeAll(keepingCapacity: true) }
        return pendingEffects
    }

    private func haptic(crash: Bool) {
        guard hapticsEnabled else { return }
        if crash { crashHaptic.notificationOccurred(.error) } else { hitHaptic.impactOccurred(intensity: 0.6) }
    }

    private func countFrame(_ now: TimeInterval) {
        frames += 1
        guard let start = fpsWindowStart else { fpsWindowStart = now; frames = 0; return }
        if now - start >= 1 {
            fps = Int((Double(frames) / (now - start)).rounded())
            frames = 0
            fpsWindowStart = now
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("-LoupeGameLog") {
                print("[game] fps=\(fps) dps=\(String(format: "%.1f", hud.decisionsPerSecond)) p50=\(hud.latencyP50.map { String(format: "%.0fms", $0) } ?? "-") model=\(hud.modelDecisions) pilot=\(pilot)")
            }
            #endif
        }
    }

    /// Auto-fire's hold, from the shared rules in LoupeKit: a live depot in the line of fire
    /// (`Mechanics.depotInLineOfFire`, the pilots' check), or a shot now that would destroy one
    /// (`Mechanics.shotHitsDepot`, exact on world copies: it also catches the depot the plane is
    /// flying over to refuel). Asked for every steer, since the safety net may fly a different one
    /// than the player's and the bullet leaves after this tick's sideways move.
    static func depotInLineOfFire(_ world: World) -> Bool {
        if Mechanics.shared.depotInLineOfFire(world: world) { return true }
        return [Int32(0), -1, 1].contains { Mechanics.shared.shotHitsDepot(world: world, steer: $0) }
    }

    // MARK: Touch (human mode)

    func touchBegan(column: Double, x: Double, time: TimeInterval) {
        guard mode == .human, !paused else { return }
        steering.began(column: column, x: x, time: time)
    }

    func touchMoved(column: Double, x: Double) {
        steering.moved(column: column, x: x)
    }

    /// A tap pauses (or, once the run is over, starts the next river). Not while FIRE is held: a
    /// quick touch on the river then is the other thumb starting to steer, not a request to stop.
    func touchEnded(time: TimeInterval) {
        let wasTap = steering.ended(time: time)
        if mode == .watch || (wasTap && !fireHeld) {
            if session.world.over && mode == .human { newRiver(); return }
            setPaused(!paused)
        }
    }

    /// The system took the steering touch (a call, a gesture from the screen edge): stop steering,
    /// and do not count it as a tap.
    func touchCancelled() {
        steering.cancel()
    }

    /// The FIRE button went down (human mode, flying). It fires at the gun's cooldown until it lifts.
    func fireBegan() {
        guard mode == .human, !paused, !session.world.over else { return }
        fireHeld = true
        fireButton.pressed = true
    }

    func fireEnded() {
        releaseFire()
    }

    /// VoiceOver's activation of FIRE: one shot.
    func fireOnce() {
        guard mode == .human, !paused, !session.world.over else { return }
        firePulse = true
    }

    private func releaseFire() {
        fireHeld = false
        firePulse = false
        if fireButton.pressed { fireButton.pressed = false }
    }

    // MARK: HUD

    /// Rebuilds the snapshot. The top bar always gets it; the panel only when [panel] (5 Hz) or when
    /// something it shows discretely changed (the run ended). The live run hears once a second.
    func refreshHUD(panel refreshPanel: Bool = true) {
        let w = session.world
        let stats = session.stats
        var h = HUDSnapshot()
        h.score = Int(w.score)
        h.fuelPercent = Int((w.fuel / Rules.shared.FUEL_MAX * 100).rounded())
        h.rows = Int(w.cameraY)
        h.over = w.over
        h.death = w.death.map { Self.deathText($0) }
        let decision = session.current
        let legal = session.currentLegal
        h.bars = GameSessions.shared.actions.map { action in
            let raw = GameSessions.shared.shown(decision: decision, action: action)
            let excluded: String? = legal?.excluded[action].map { $0 == Exclusion.fatal ? "✕ crash" : "✕ fuel" }
            return ActionBar(label: action.label, raw: raw >= 0 ? raw : nil, chosen: decision?.action == action, excluded: excluded)
        }
        h.source = Self.sourceText(decision)
        h.decisionsPerSecond = stats.decisionsPerSecond(nowNanos: GameClock.shared.nanoTime())
        h.latencyP50 = stats.latencyMillis(q: 0.5)?.doubleValue
        h.latencyP95 = stats.latencyMillis(q: 0.95)?.doubleValue
        h.level = Int(w.level)
        h.askedPerSecond = session.control is ControlHuman ? 0 : session.askedPerSecond
        h.sparkline = stats.recentLatenciesMillis(n: 48).map { $0.doubleValue }
        h.requested = Int(stats.requested)
        h.dropped = Int(stats.dropped)
        h.lateAnswers = Int(stats.lateAnswers)
        h.onTimePercent = stats.onTimePercent()?.doubleValue
        h.collisionChoices = Int(stats.collisionChoices)
        h.collisionsAvoided = Int(stats.collisionsAvoided)
        h.takeovers = Int(stats.takeovers)
        // Sustained: the 2-second rate, counted only once the run has had two seconds to fill it.
        if !w.over, let start = runStart, (lastTime ?? start) - start > 2.5, stats.total > 0 {
            maxSustained = max(maxSustained, h.decisionsPerSecond)
        }
        h.maxSustained = maxSustained
        h.modelDecisions = Int(stats.count(source: .model))
        h.mechanical = Int(stats.count(source: .mechanical))
        h.failures = Int(stats.count(source: .failure))
        h.overrides = Int(stats.overrides)
        if let shadow {
            h.baselineScore = Int(shadow.world.score)
            h.baselineRows = Int(shadow.world.cameraY)
            h.baselineOver = shadow.world.over
        }
        h.fps = fps
        if ticksSinceHUD == 0 || h.over { reportDecisions(h) }
        var t = h
        // The top bar reads only these; the rest is the panel's.
        t.bars = []; t.sparkline = []
        if t != top.hud { top.hud = t }
        if (refreshPanel || h.over != panel.hud.over) && h != panel.hud { panel.hud = h }
    }

    static func deathText(_ cause: DeathCause) -> String {
        switch cause {
        case .bank: return "Hit the bank"
        case .enemy: return "Hit an enemy"
        case .bridge: return "Hit a bridge"
        default: return "Out of fuel"
        }
    }

    static func sourceText(_ d: PilotDecision?) -> String {
        guard let d else { return "Waiting for the first decision" }
        switch d.source {
        case .model: return "The decision model chose"
        case .mechanical: return "Only one safe move: mechanics decided, the model was not asked"
        case .baseline: return "Rules, no model: no probabilities to show"
        default: return "Model answer unusable (\(d.failure ?? "?")); holding course"
        }
    }

    func close() {
        liveJob?.finish("cancelled", "act.res.stopped")
        liveJob = nil
        openTask?.cancel()
        modelClaim?.release()
        modelClaim = nil
        scheduler?.close()
        session.close()
        shadow?.close()
    }
}

/// The FIRE button's pressed state, observed only by the button.
@MainActor
final class FireButtonState: ObservableObject {
    @Published var pressed = false
}

/// The last finished watch run's measured numbers, for the Watch card ("last run on this iPhone").
/// Nothing is shown until a run has finished here.
enum LastRun {
    static let key = "game.lastRun"

    struct Numbers: Equatable {
        var perSecond: Double
        var p50: Double?
        var decisions: Int
    }

    static func remember(_ r: RunResults, defaults: UserDefaults = .standard) {
        var d: [String: Double] = ["perSecond": r.averagePerSecond, "decisions": Double(r.totalDecisions)]
        if let p = r.p50 { d["p50"] = p }
        defaults.set(d, forKey: key)
    }

    static func read(defaults: UserDefaults = .standard) -> Numbers? {
        guard let d = defaults.dictionary(forKey: key) as? [String: Double], let ps = d["perSecond"], let n = d["decisions"], n > 0 else { return nil }
        return Numbers(perSecond: ps, p50: d["p50"], decisions: Int(n))
    }
}
