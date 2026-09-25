import XCTest
import LoupeKit
@testable import Loupe

/// A Backend that always prefers one label, counting calls; no model involved.
final class FakeGameBackend: NSObject, Backend {
    let preferred: String
    private(set) var calls = 0
    init(preferring label: String) { preferred = label }
    func score(judgment: JudgmentChoice, state: TextState) -> Scored {
        calls += 1
        let n = Double(judgment.candidates.count)
        var masses: [String: KotlinDouble] = [:]
        for c in judgment.candidates {
            masses[c] = KotlinDouble(value: c == preferred ? 0.7 : 0.3 / max(n - 1, 1))
        }
        if masses[preferred] == nil, let first = judgment.candidates.first {
            // The preferred move was not offered: spread evenly, first wins ties.
            for c in judgment.candidates { masses[c] = KotlinDouble(value: 1 / n) }
            masses[first] = KotlinDouble(value: 1 / n)
        }
        return Scored(masses: masses, modelContext: nil, optionCriteria: nil)
    }
}

/// Queues pilot work until the test runs it, standing in for the background queue.
final class ManualExecutor: PilotExecutor {
    private(set) var queued: [() -> Void] = []
    func execute(_ work: @escaping () -> Void) { queued.append(work) }
    func runAll() {
        let w = queued
        queued.removeAll()
        w.forEach { $0() }
    }
}

@MainActor
final class PilotSchedulingTests: XCTestCase {
    private func hosted(_ backend: Backend, executor: ManualExecutor) -> (GameSession, PilotScheduler) {
        let decider = GameSessions.shared.modelDecider(backend: backend)
        let scheduler = PilotScheduler(decider: decider, executor: executor, returnToSimulation: { $0() })
        let session = GameSessions.shared.hosted(seed: 4, decider: decider, decisionInterval: 6)
        return (session, scheduler)
    }

    func testTheSimulationNeverWaitsForThePilot() {
        let backend = FakeGameBackend(preferring: "hold course")
        let exec = ManualExecutor()
        let (session, scheduler) = hosted(backend, executor: exec)
        for _ in 0..<30 {
            session.tick()
            scheduler.afterTick()
        }
        XCTAssertEqual(session.world.tick, 30, "ticks ran while the decision was still queued")
        XCTAssertEqual(scheduler.dispatched, 1, "one decision in flight at a time")
        XCTAssertEqual(exec.queued.count, 1)
        XCTAssertEqual(backend.calls, 0, "the model runs on the executor, never inside a tick")
        XCTAssertNil(session.current)
        session.close()
    }

    func testDecisionsLandOnTheNextTickWithRawProbabilities() {
        let backend = FakeGameBackend(preferring: "hold course")
        let exec = ManualExecutor()
        let (session, scheduler) = hosted(backend, executor: exec)
        session.tick(); scheduler.afterTick()
        exec.runAll()
        XCTAssertEqual(backend.calls, 1)
        XCTAssertEqual(scheduler.landed, 1)
        session.tick(); scheduler.afterTick()
        let d = session.current
        XCTAssertEqual(d?.source, DecisionSource.model)
        XCTAssertEqual(d?.action, Action.hold)
        XCTAssertEqual(GameSessions.shared.raw(decision: d, action: .hold), 0.7, accuracy: 1e-9)
        XCTAssertEqual(scheduler.dispatched, 1, "not due again until six ticks after the last request")
        for _ in 0..<5 { session.tick(); scheduler.afterTick() }
        XCTAssertEqual(scheduler.dispatched, 2, "the next request goes out once one landed and one is due")
        session.close()
    }

    func testRequestsFollowTheDecisionInterval() {
        let exec = ManualExecutor()
        let (session, scheduler) = hosted(FakeGameBackend(preferring: "hold course"), executor: exec)
        for _ in 0..<60 {
            session.tick()
            scheduler.afterTick()
            exec.runAll() // an instant pilot
        }
        // One request every 6 ticks at 60 Hz: 10 a second, never more.
        XCTAssertEqual(scheduler.dispatched, 10)
        XCTAssertEqual(session.stats.total, 10)
        session.close()
    }

    func testADecisionReturningAfterCloseIsDropped() {
        let exec = ManualExecutor()
        let (session, scheduler) = hosted(FakeGameBackend(preferring: "hold course"), executor: exec)
        session.tick(); scheduler.afterTick()
        scheduler.close()
        exec.runAll()
        session.tick(); scheduler.afterTick()
        XCTAssertNil(session.current)
        XCTAssertEqual(scheduler.dispatched, 1, "a closed scheduler starts nothing new")
        session.close()
    }

    func testWatchModeFliesLayaWithABaselineScoreboard() async {
        let backend = FakeGameBackend(preferring: "hold course and shoot")
        let exec = ManualExecutor()
        let game = GameController(mode: .watch, seed: 9,
                                  backendProvider: { backend }, modelInstalled: { true },
                                  executor: exec, returnToSimulation: { $0() })
        XCTAssertEqual(game.pilot, .opening)
        // Let the open task run.
        for _ in 0..<50 where game.pilot != .laya { await Task.yield() }
        XCTAssertEqual(game.pilot, .laya)
        XCTAssertNotNil(game.shadow)
        for i in 0..<120 {
            game.step(now: Double(i) / 60)
            exec.runAll()
        }
        XCTAssertGreaterThan(backend.calls, 5)
        XCTAssertEqual(game.shadow?.world.tick, game.session.world.tick, "the baseline flies the same river in lockstep")
        XCTAssertEqual(game.hud.bars.count, 6)
        XCTAssertTrue(game.hud.bars.contains { $0.raw != nil })
        XCTAssertNotNil(game.hud.baselineScore)
        game.close()
    }

    func testWatchModeWithoutTheModelFliesTheBaselineAndSaysSo() {
        let game = GameController(mode: .watch, seed: 1, modelInstalled: { false })
        guard case .baseline(let reason) = game.pilot else { return XCTFail("\(game.pilot)") }
        XCTAssertTrue(reason.contains("Needs Laya"))
        XCTAssertNil(game.shadow)
        game.close()
    }

    /// A model slower than the cadence: the game counts what it asked for and never got.
    func testASlowModelAtRushCadenceIsCountedAsDropped() {
        let backend = FakeGameBackend(preferring: "hold course")
        let exec = ManualExecutor()
        let decider = GameSessions.shared.modelDecider(backend: backend)
        let scheduler = PilotScheduler(decider: decider, executor: exec, returnToSimulation: { $0() })
        let session = GameSessions.shared.hostedOn(seed: 4, decider: decider, decisionInterval: 6,
                                                   difficulty: GameSessions.shared.difficulty(name: "rush", levelRows: 10_000))
        XCTAssertEqual(session.currentInterval, 3, "rush starts at level 4: 20 decisions a second")
        for t in 0..<120 {
            session.tick(); scheduler.afterTick()
            if t % 10 == 9 { exec.runAll() } // the model answers every 10 ticks (6/s)
        }
        XCTAssertGreaterThan(session.stats.dropped, 10)
        XCTAssertGreaterThan(session.stats.lateRequests, 5)
        XCTAssertEqual(session.askedPerSecond, 20, accuracy: 1e-9)
        session.close()
    }
}
