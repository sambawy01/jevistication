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
        XCTAssertEqual(s.keys(playerX: 14, autoFire: false, now: 0).left, true)
        XCTAssertEqual(s.keys(playerX: 14, autoFire: false, now: 0).right, false)
        s.moved(column: 20, x: 200)
        XCTAssertEqual(s.keys(playerX: 14, autoFire: false, now: 0.1).right, true)
        s.moved(column: 14.2, x: 142)
        let k = s.keys(playerX: 14, autoFire: false, now: 0.1)
        XCTAssertFalse(k.left || k.right, "inside the deadband the plane holds course")
    }

    func testHoldFiresButATapDoesNot() {
        var s = TouchSteering()
        s.began(column: 14, x: 140, time: 1.0)
        XCTAssertFalse(s.keys(playerX: 14, autoFire: false, now: 1.1).fire, "still a possible tap")
        XCTAssertTrue(s.keys(playerX: 14, autoFire: false, now: 1.3).fire, "held past the tap window")
        XCTAssertFalse(s.ended(time: 1.3), "a hold is not a tap")
        XCTAssertFalse(s.keys(playerX: 14, autoFire: false, now: 1.4).fire, "lifting stops the gun")
    }

    func testDraggingFiresAtOnceAndIsNotATap() {
        var s = TouchSteering()
        s.began(column: 14, x: 140, time: 0)
        s.moved(column: 16, x: 160)
        XCTAssertTrue(s.keys(playerX: 14, autoFire: false, now: 0.05).fire)
        XCTAssertFalse(s.ended(time: 0.1))
    }

    func testQuickStillTouchIsATap() {
        var s = TouchSteering()
        s.began(column: 14, x: 140, time: 0)
        s.moved(column: 14.3, x: 143)
        XCTAssertTrue(s.ended(time: 0.15))
        XCTAssertFalse(s.touching)
    }

    func testAutoFireFiresWithoutATouch() {
        let s = TouchSteering()
        let k = s.keys(playerX: 14, autoFire: true, now: 0)
        XCTAssertTrue(k.fire)
        XCTAssertFalse(k.left || k.right)
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
}
