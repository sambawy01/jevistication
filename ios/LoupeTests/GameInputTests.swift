import LoupeKit
import XCTest
@testable import Loupe

final class GameInputTests: XCTestCase {
    func testColumnMappingClampsToTheRiver() {
        XCTAssertEqual(ColumnMapping.column(x: 0, width: 280, columns: 28), 0)
        XCTAssertEqual(ColumnMapping.column(x: 140, width: 280, columns: 28), 14)
        XCTAssertEqual(ColumnMapping.column(x: 400, width: 280, columns: 28), 28)
        XCTAssertEqual(ColumnMapping.column(x: -5, width: 280, columns: 28), 0)
        XCTAssertEqual(ColumnMapping.column(x: 10, width: 0, columns: 28), 14)
    }

    func testDragSteersTowardTheFingerWithADeadband() {
        var s = TouchSteering()
        s.began(column: 10, x: 100, time: 0)
        XCTAssertEqual(s.keys(playerX: 14).left, true)
        XCTAssertEqual(s.keys(playerX: 14).right, false)
        s.moved(column: 20, x: 200)
        XCTAssertEqual(s.keys(playerX: 14).right, true)
        s.moved(column: 14.2, x: 142)
        let k = s.keys(playerX: 14)
        XCTAssertFalse(k.left || k.right, "inside the deadband the plane holds course")
    }

    func testQuickStillTouchIsATapButAHoldOrDragIsNot() {
        var s = TouchSteering()
        s.began(column: 14, x: 140, time: 0)
        s.moved(column: 14.3, x: 143)
        XCTAssertTrue(s.ended(time: 0.15))
        XCTAssertFalse(s.touching)
        s.began(column: 14, x: 140, time: 1.0)
        XCTAssertFalse(s.ended(time: 1.5), "a hold is not a tap")
        s.began(column: 14, x: 140, time: 2.0)
        s.moved(column: 17, x: 170)
        XCTAssertFalse(s.ended(time: 2.1), "a drag is not a tap")
    }

    // MARK: Fire

    func testSteeringNeverFires() {
        // The owner's report: every drag fired, so steering destroyed fuel depots. A steering touch,
        // however long or far, leaves the gun alone.
        var s = TouchSteering()
        s.began(column: 14, x: 140, time: 0)
        s.moved(column: 20, x: 200)
        s.moved(column: 4, x: 40)
        _ = s.keys(playerX: 14)
        XCTAssertFalse(FireControl.fire(button: false, autoFire: false, depotInLine: { false }))
    }

    func testFireButtonFiresEvenAtADepot() {
        XCTAssertTrue(FireControl.fire(button: true, autoFire: false, depotInLine: { true }),
                      "a deliberate press fires: the player chose to")
        XCTAssertTrue(FireControl.fire(button: true, autoFire: true, depotInLine: { true }))
    }

    func testAutoFireHoldsOnADepotInLineAndFiresOtherwise() {
        XCTAssertTrue(FireControl.fire(button: false, autoFire: true, depotInLine: { false }))
        XCTAssertFalse(FireControl.fire(button: false, autoFire: true, depotInLine: { true }))
        var asked = false
        XCTAssertFalse(FireControl.fire(button: false, autoFire: false, depotInLine: { asked = true; return false }))
        XCTAssertFalse(asked, "the depot scan runs only when auto-fire needs it")
    }

    // MARK: Touch routing (steer finger and fire finger)

    func testRouterSteerAndFireTouchesAreIndependent() {
        var r = TouchRouter<Int>()
        XCTAssertEqual(r.began(1, onFireButton: false), .steer)
        XCTAssertEqual(r.began(2, onFireButton: true), .fire)
        XCTAssertEqual(r.role(of: 1), .steer)
        XCTAssertEqual(r.role(of: 2), .fire)
        XCTAssertEqual(r.began(3, onFireButton: false), .ignored, "one steering finger at a time")
        XCTAssertEqual(r.began(4, onFireButton: true), .ignored, "one fire finger at a time")
        XCTAssertEqual(r.ended(3), .ignored)

        XCTAssertEqual(r.ended(2), .fire, "lifting the fire finger")
        XCTAssertEqual(r.role(of: 1), .steer, "leaves steering alone")
        XCTAssertEqual(r.began(5, onFireButton: true), .fire)
        XCTAssertEqual(r.ended(1), .steer, "lifting the steering finger")
        XCTAssertEqual(r.role(of: 5), .fire, "leaves the gun firing")
        XCTAssertEqual(r.began(6, onFireButton: false), .steer, "a new finger steers again")
    }

    func testFireButtonLayoutIsThumbSizedAndRoundWithSlop() {
        let side = FireButtonLayout.touchSize
        let bounds = CGRect(x: 0, y: 0, width: side, height: side)
        let f = FireButtonLayout.frame(in: bounds)
        XCTAssertGreaterThanOrEqual(f.width, 64)
        XCTAssertEqual(f.midX, bounds.midX)
        XCTAssertEqual(f.midY, bounds.midY)
        XCTAssertTrue(FireButtonLayout.contains(CGPoint(x: f.midX, y: f.midY), in: bounds))
        XCTAssertTrue(FireButtonLayout.contains(CGPoint(x: f.minX - 4, y: f.midY), in: bounds), "a little slop")
        XCTAssertFalse(FireButtonLayout.contains(CGPoint(x: 0, y: 0), in: bounds), "round, not square")
    }

    /// FIRE lives in its own touch view (the bar under the river): holding it and dragging on the
    /// river are two touches on two views, and both reach the game at once.
    @MainActor
    func testHeldFireAndARiverDragWorkTogether() {
        let game = GameController(mode: .human, seed: 3, modelInstalled: { false })
        let fire = FireTouchView(frame: CGRect(x: 0, y: 0, width: FireButtonLayout.touchSize, height: FireButtonLayout.touchSize))
        fire.game = game
        XCTAssertTrue(fire.point(inside: CGPoint(x: fire.bounds.midX, y: fire.bounds.midY), with: nil))
        XCTAssertFalse(fire.isExclusiveTouch)
        game.fireBegan()                                    // what FireTouchView does on touchesBegan
        game.touchBegan(column: 2, x: 20, time: 0)          // a drag on the river at the same time
        let start = game.session.world.playerX
        let shots = game.session.world.tally.shotsFired
        for i in 0..<30 { game.step(now: Double(i) / 60) }
        XCTAssertLessThan(game.session.world.playerX, start, "the drag steers while FIRE is held")
        XCTAssertGreaterThan(game.session.world.tally.shotsFired, shots, "FIRE fires while the other finger steers")
        game.touchEnded(time: 0.5)
        game.fireEnded()                                    // what FireTouchView does on touchesEnded
        XCTAssertFalse(game.fireButton.pressed)
        XCTAssertFalse(game.paused)
        game.close()
    }

    @MainActor
    func testTouchInHumanModeReachesTheSession() {
        let game = GameController(mode: .human, seed: 3, modelInstalled: { false })
        game.touchBegan(column: 2, x: 20, time: 0)
        let start = game.session.world.playerX
        for i in 0..<20 { game.step(now: Double(i) / 60) }
        XCTAssertLessThan(game.session.world.playerX, start, "dragging left steers left")
        game.touchEnded(time: 0.4)
        XCTAssertFalse(game.paused, "a drag is not a tap")
        game.touchBegan(column: 14, x: 140, time: 1.0)
        game.touchEnded(time: 1.1)
        XCTAssertTrue(game.paused, "a tap pauses")
        game.close()
    }

    @MainActor
    func testDraggingInHumanModeSteersWithoutFiring() {
        let game = GameController(mode: .human, seed: 3, modelInstalled: { false })
        game.touchBegan(column: 2, x: 20, time: 0)
        game.touchMoved(column: 1, x: 10)
        for i in 0..<40 {
            game.step(now: Double(i) / 60)
            XCTAssertFalse(game.session.human.fire, "tick \(i): a steering drag fired")
        }
        game.touchEnded(time: 1)
        XCTAssertFalse(game.paused)
        game.close()
    }

    @MainActor
    func testFireWhileSteeringThenLiftEither() {
        let game = GameController(mode: .human, seed: 3, modelInstalled: { false })
        let start = game.session.world.playerX
        game.touchBegan(column: 2, x: 20, time: 0)
        game.fireBegan()
        for i in 0..<20 { game.step(now: Double(i) / 60) }
        XCTAssertTrue(game.session.human.fire, "the fire finger fires")
        XCTAssertTrue(game.session.human.left, "while the other finger steers")
        XCTAssertLessThan(game.session.world.playerX, start)
        XCTAssertTrue(game.fireButton.pressed)

        // A quick touch on the river while FIRE is held is the other thumb, not a pause.
        game.touchEnded(time: 0.4)
        XCTAssertFalse(game.paused)
        game.touchBegan(column: 26, x: 260, time: 0.5)
        game.touchEnded(time: 0.55)
        XCTAssertFalse(game.paused, "no pause while FIRE is held")

        // Lift the steering finger: the gun keeps firing, the plane stops steering.
        game.step(now: 0.6)
        XCTAssertTrue(game.session.human.fire)
        XCTAssertFalse(game.session.human.left || game.session.human.right)

        // Steer again, then lift the fire finger: steering continues, the gun stops.
        game.touchBegan(column: 26, x: 260, time: 0.7)
        game.fireEnded()
        game.step(now: 0.75)
        XCTAssertFalse(game.session.human.fire)
        XCTAssertTrue(game.session.human.right)
        XCTAssertFalse(game.fireButton.pressed)
        game.close()
    }

    @MainActor
    func testFireIsIgnoredWhilePausedAndReleasedByPause() {
        let game = GameController(mode: .human, seed: 3, modelInstalled: { false })
        game.fireBegan()
        XCTAssertTrue(game.fireHeld)
        game.setPaused(true)
        XCTAssertFalse(game.fireHeld, "pausing lets go of the gun")
        game.fireBegan()
        XCTAssertFalse(game.fireHeld)
        game.setPaused(false)
        game.fireOnce()
        game.step(now: 0)
        XCTAssertTrue(game.session.human.fire, "VoiceOver's press fires")
        game.close()
    }

    @MainActor
    func testFireIsIgnoredInWatchMode() {
        let game = GameController(mode: .watch, seed: 3, modelInstalled: { false })
        game.fireBegan()
        XCTAssertFalse(game.fireHeld)
        game.close()
    }

    /// Auto-fire on a real river: every tick it fires exactly when no live depot is in the line of
    /// fire (the shared `Mechanics.depotInLineOfFire`, plus `Mechanics.shotHitsDepot` for the depot
    /// the plane is flying over), and it never destroys a depot. The plane is
    /// steered under each depot ahead so the depot case is really exercised.
    @MainActor
    func testAutoFireHoldsWhileADepotIsInLineAndNeverShootsOne() {
        var inLineTicks = 0, clearTicks = 0, overheadTicks = 0, shots = 0
        for seed in [Int64(3), 7, 11, 19] {
            let game = GameController(mode: .human, seed: seed, modelInstalled: { false })
            game.autoFire = true
            var t = 0.0
            for _ in 0..<3000 {
                let w = game.session.world
                if w.over { break }
                // Steer under the next depot ahead, so the depot cases really happen.
                if let d = w.depots.filter({ $0.alive && $0.y > w.playerY }).min(by: { $0.y < $1.y }) {
                    game.touchCancelled()
                    game.touchBegan(column: d.x, x: d.x * 10, time: t)
                } else {
                    game.touchCancelled()
                }
                let inLine = GameController.depotInLineOfFire(w)
                let shared = Mechanics.shared.depotInLineOfFire(world: w)
                if shared { XCTAssertTrue(inLine, "the shared check holds the gun") }
                if inLine && !shared { overheadTicks += 1 }
                let shotBefore = w.tally.depotsShot, firedBefore = w.tally.shotsFired
                game.step(now: t)
                t += 1.0 / 60
                XCTAssertEqual(game.session.human.fire, !inLine, "seed \(seed): auto-fire fires iff no depot is in line")
                if inLine { inLineTicks += 1 } else { clearTicks += 1 }
                shots += Int(game.session.world.tally.shotsFired - firedBefore)
                XCTAssertEqual(game.session.world.tally.depotsShot, shotBefore, "seed \(seed): auto-fire shot a fuel depot")
            }
            game.close()
        }
        XCTAssertGreaterThan(inLineTicks, 0, "the test never put a depot in the line of fire")
        XCTAssertGreaterThan(clearTicks, 0)
        XCTAssertGreaterThan(shots, 20, "auto-fire does fire when the way is clear")
        XCTAssertGreaterThan(overheadTicks, 0, "the test never flew over a depot, the case the line-of-fire check misses")
    }

    /// The hold is asked every tick while auto-fire is on and the gun is ready: it must stay far
    /// inside a 60 Hz frame (16.7 ms), here a worst case with the gun always ready.
    @MainActor
    func testAutoFireHoldCheckIsCheap() {
        let session = GameSessions.shared.humanOn(seed: 5, difficulty: GameSessions.shared.difficulty(name: "rush", levelRows: 200))
        let world = session.world
        var worst = 0.0, total = 0.0, n = 0
        for _ in 0..<400 {
            if world.over { break }
            let t0 = CACurrentMediaTime()
            _ = GameController.depotInLineOfFire(world)
            let dt = CACurrentMediaTime() - t0
            worst = max(worst, dt); total += dt; n += 1
            session.human = HumanInput(left: false, right: false, fire: false)
            session.tick()
        }
        print("auto-fire hold check: mean \(String(format: "%.3f", total / Double(max(n, 1)) * 1000)) ms, worst \(String(format: "%.3f", worst * 1000)) ms over \(n) ticks")
        XCTAssertLessThan(total / Double(max(n, 1)), 0.0005, "mean over 0.5 ms")
    }
}
