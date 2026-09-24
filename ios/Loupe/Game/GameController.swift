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
    /// The model's raw probability, or nil when no model was asked.
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
    @Published private(set) var hud = HUDSnapshot()
    @Published var autoFire = false

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

    static let tick: TimeInterval = 1.0 / 60.0
    static let maxCatchUp: TimeInterval = 0.1
    static let autoRestartAfter: TimeInterval = 2.5
    static let columns = Int(Rules.shared.COLUMNS)
    static let viewRows = Int(Rules.shared.VIEW_ROWS)

    init(mode: GameMode,
         seed: Int64 = 1,
         backendProvider: @escaping @MainActor () async -> Backend? = { await LayaModel.shared.backend() },
         modelInstalled: @escaping @MainActor () -> Bool = { LayaModel.shared.isInstalled },
         executor: PilotExecutor = QueuePilotExecutor.shared,
         returnToSimulation: @escaping (@escaping () -> Void) -> Void = { w in DispatchQueue.main.async(execute: w) },
         settings: ModelSettingsSource = ModelSettingsService.shared) {
        self.settings = settings
        self.mode = mode
        self.seed = seed
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

    private func start(mode: GameMode, seed: Int64) {
        openTask?.cancel()
        openTask = nil
        self.mode = mode
        self.seed = seed
        steering.cancel()
        switch mode {
        case .human:
            pilot = .you
            begin(session: GameSessions.shared.human(seed: seed), scheduler: nil, shadow: nil)
        case .watch:
            // Model settings (`features.game`), read at each start: off, the baseline flies.
            let layaOn = settings.useLaya(Features.shared.GAME)
            settings.recordRun(Features.shared.GAME, layaOff: !layaOn)
            if !layaOn {
                flyBaseline(reason: MS.t("banner.game"))
                return
            }
            if !modelInstalled() {
                flyBaseline(reason: "The Laya model isn't on this phone, so the baseline autopilot is flying.")
                return
            }
            pilot = .opening
            // The baseline flies while Laya's files are verified and opened (a few seconds, once).
            begin(session: GameSessions.shared.baseline(seed: seed), scheduler: nil, shadow: nil)
            openTask = Task { [weak self] in
                guard let self else { return }
                let backend = await self.backendProvider()
                guard !Task.isCancelled, self.mode == .watch, self.seed == seed else { return }
                if let backend {
                    self.flyLaya(backend)
                } else {
                    self.flyBaseline(reason: "Laya could not be opened (see Me → Laya model), so the baseline autopilot is flying.")
                }
            }
        }
    }

    private func flyBaseline(reason: String) {
        pilot = .baseline(reason: reason)
        begin(session: GameSessions.shared.baseline(seed: seed), scheduler: nil, shadow: nil)
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
        begin(session: GameSessions.shared.hosted(seed: seed, decider: decider,
                                                  decisionInterval: GameSession.companion.DEFAULT_DECISION_INTERVAL),
              scheduler: scheduler,
              shadow: GameSessions.shared.baseline(seed: seed))
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
        pendingEffects.removeAll()
        refreshHUD()
    }

    // MARK: Loop

    func setPaused(_ value: Bool) {
        paused = value
        lastTime = nil
        steering.cancel()
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
            if mode == .watch, let since = overSince, now - since > Self.autoRestartAfter {
                newRiver()
            }
        }
    }

    /// One simulation tick, public for tests.
    func step(now: TimeInterval) {
        let world = session.world
        if mode == .human {
            let k = steering.keys(playerX: world.playerX, autoFire: autoFire, now: now)
            session.human = HumanInput(left: k.left, right: k.right, fire: k.fire)
        }
        session.tick()
        for event in world.events {
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
        if ticksSinceHUD >= 6 {
            ticksSinceHUD = 0
            refreshHUD()
        }
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

    // MARK: Touch (human mode)

    func touchBegan(column: Double, x: Double, time: TimeInterval) {
        guard mode == .human, !paused else { return }
        steering.began(column: column, x: x, time: time)
    }

    func touchMoved(column: Double, x: Double) {
        steering.moved(column: column, x: x)
    }

    /// A tap pauses (or, once the run is over, starts the next river).
    func touchEnded(time: TimeInterval) {
        let wasTap = steering.ended(time: time)
        if mode == .watch || wasTap {
            if session.world.over && mode == .human { newRiver(); return }
            setPaused(!paused)
        }
    }

    // MARK: HUD

    func refreshHUD() {
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
            let raw = GameSessions.shared.raw(decision: decision, action: action)
            let excluded: String? = legal?.excluded[action].map { $0 == Exclusion.fatal ? "✕ crash" : "✕ fuel" }
            return ActionBar(label: action.label, raw: raw >= 0 ? raw : nil, chosen: decision?.action == action, excluded: excluded)
        }
        h.source = Self.sourceText(decision)
        h.decisionsPerSecond = stats.decisionsPerSecond(nowNanos: GameClock.shared.nanoTime())
        h.latencyP50 = stats.latencyMillis(q: 0.5)?.doubleValue
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
        if h != hud { hud = h }
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
        case .model: return "Laya chose"
        case .mechanical: return "Only one safe move: mechanics decided, Laya was not asked"
        case .baseline: return "Rules, no model: no probabilities to show"
        default: return "Model answer unusable (\(d.failure ?? "?")); holding course"
        }
    }

    func close() {
        openTask?.cancel()
        modelClaim?.release()
        modelClaim = nil
        scheduler?.close()
        session.close()
        shadow?.close()
    }
}
