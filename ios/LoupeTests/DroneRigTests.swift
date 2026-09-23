import XCTest
@testable import Loupe

/// The drone mascot's pure half (epic #7 child 17): Station mood mapping, kind setting, Reduce Motion, pause rule.
final class DroneRigTests: XCTestCase {
    private func rig(_ s: MascotState, reduce: Bool = false) -> DroneRig {
        var r = DroneRig(random: { 0.5 }); r.reduceMotion = reduce; r.set(s); return r
    }

    func testStateToStationMoodMapping() {
        let settledLook: (MascotState) -> DroneLook = { DroneLook.of($0, stateT: 0.1, tapping: false, reduceMotion: false) }
        XCTAssertEqual(settledLook(.idle).mood, .idle)
        XCTAssertEqual(settledLook(.greeting).mood, .booting)          // power-up first
        XCTAssertEqual(DroneLook.of(.greeting, stateT: 1.0, tapping: false, reduceMotion: false).mood, .happy)
        XCTAssertEqual(DroneLook.of(.greeting, stateT: 3.0, tapping: false, reduceMotion: false).mood, .idle)
        XCTAssertEqual(DroneLook.of(.greeting, stateT: 0, tapping: false, reduceMotion: true).mood, .happy)  // no boot flicker
        XCTAssertEqual(settledLook(.watching).mood, .idle); XCTAssertTrue(settledLook(.watching).followsLook)
        XCTAssertEqual(settledLook(.scanning).mood, .thinking); XCTAssertTrue(settledLook(.scanning).scan)
        XCTAssertEqual(settledLook(.thinking).mood, .thinking); XCTAssertEqual(settledLook(.thinking).ring, .long)
        XCTAssertEqual(settledLook(.found).mood, .happy); XCTAssertTrue(settledLook(.found).flash)
        XCTAssertEqual(settledLook(.happy).eyes, .happy)
        XCTAssertEqual(settledLook(.empty).mood, .unsure)
        XCTAssertEqual(settledLook(.empty).roll, 11 * .pi / 180, accuracy: 1e-12)
        XCTAssertEqual(DroneLook.of(.scanning, stateT: 0, tapping: true, reduceMotion: false).mood, .happy)
    }

    func testStationColours() {
        XCTAssertEqual(DronePalette.glow(.idle, dark: false), 0x4D7DFF)
        XCTAssertEqual(DronePalette.glow(.happy, dark: true), 0x5FE0A5)
        XCTAssertEqual(DronePalette.glow(.unsure, dark: false), 0xF09A1A)
        var r = rig(.found, reduce: true)
        XCTAssertEqual(r.step(dt: 1 / 60).glow, DronePalette.rgb(0x1FB574))
    }

    func testHopAndBobFollowStationKeyframes() {
        XCTAssertEqual(DroneRig.hop(0.35 * 0.7).y, 0.183, accuracy: 1e-9)
        XCTAssertEqual(DroneRig.hop(0.35 * 0.7).scale, 1.06, accuracy: 1e-9)
        XCTAssertEqual(DroneRig.hop(0.8).y, 0)
        XCTAssertEqual(DroneRig.flash(0.18), 0.36, accuracy: 1e-9)
        var r = rig(.idle)
        var maxY = 0.0
        for _ in 0..<(60 * 5) { maxY = max(maxY, r.step(dt: 1 / 60).y) }
        XCTAssertEqual(maxY, DroneRig.bob, accuracy: 0.002)
    }

    func testReduceMotionIsStaticPerState() {
        for s in MascotState.allCases {
            var r = rig(s, reduce: true)
            let f1 = r.step(dt: 1 / 60)
            XCTAssertFalse(r.isAnimating, "\(s) needs no more frames")
            for _ in 0..<30 { _ = r.step(dt: 1 / 60) }
            let f2 = r.step(dt: 1 / 60)
            XCTAssertEqual(f1.y, 0); XCTAssertEqual(f2.ringAngle, 0); XCTAssertEqual(f1.scale, 1)
            if s != .greeting { XCTAssertEqual(f1, f2, "\(s) holds still") }
        }
    }

    func testIdleBlinksAndTapReacts() {
        var r = rig(.idle)
        var blinked = false
        for _ in 0..<(60 * 8) { if r.step(dt: 1 / 60).eyeScale.x < 0.5 { blinked = true } }
        XCTAssertTrue(blinked)
        r.tap(); XCTAssertEqual(r.step(dt: 1 / 60).eyes, .happy)
        for _ in 0..<140 { _ = r.step(dt: 1 / 60) }
        XCTAssertEqual(r.step(dt: 1 / 60).eyes, .open)
    }

    func testFaceKeyIgnoresScanWhenAbsent() {
        var r = rig(.idle, reduce: true)
        let f = r.step(dt: 1 / 60)
        var g = f; g.ringAngle = 3
        XCTAssertEqual(DroneFaceRenderer.key(f, dark: false), DroneFaceRenderer.key(g, dark: false))
        XCTAssertNotEqual(DroneFaceRenderer.key(f, dark: false), DroneFaceRenderer.key(f, dark: true))
    }

    func testKindSettingDefaultsToRobotAndPersists() {
        let d = UserDefaults(suiteName: "DroneRigTests")!
        d.removePersistentDomain(forName: "DroneRigTests")
        XCTAssertEqual(MascotKind.stored(in: d), .robot)
        MascotKind.store(.drone, in: d)
        XCTAssertEqual(MascotKind.stored(in: d), .drone)
        d.set("martian", forKey: MascotKind.storageKey)
        XCTAssertEqual(MascotKind.stored(in: d), .robot)
        XCTAssertEqual(MascotKind.allCases.map(\.title), ["Robot", "Drone"])
    }

    func testPauseRuleCoversTheDrone() {
        let live = rig(.thinking)
        XCTAssertTrue(MascotPlayback.shouldPlay(onScreen: true, appActive: true, tabSelected: true, animating: live.isAnimating))
        XCTAssertFalse(MascotPlayback.shouldPlay(onScreen: false, appActive: true, tabSelected: true, animating: live.isAnimating))
        XCTAssertFalse(MascotPlayback.shouldPlay(onScreen: true, appActive: false, tabSelected: true, animating: live.isAnimating))
        XCTAssertFalse(MascotPlayback.shouldPlay(onScreen: true, appActive: true, tabSelected: false, animating: live.isAnimating))
        var still = rig(.thinking, reduce: true); _ = still.step(dt: 1 / 60)
        XCTAssertFalse(MascotPlayback.shouldPlay(onScreen: true, appActive: true, tabSelected: true, animating: still.isAnimating))
    }

    /// The live view is VoiceOver-hidden and builds for both kinds.
    @MainActor func testSceneViewBuildsForBothKinds() {
        for k in MascotKind.allCases {
            let v = MascotSCNView(kind: k)
            XCTAssertEqual(v.kind, k)
            XCTAssertTrue(v.accessibilityElementsHidden)
        }
        XCTAssertGreaterThan(MascotStills.shared.image(.found, points: 24, kind: .drone).size.width, 0)
    }

    /// Writes the bundled no-Metal fallback when TEST_RUNNER_LOUPE_EXPORT_DRONE names a path.
    @MainActor func testExportDroneStillWhenAsked() throws {
        guard let path = ProcessInfo.processInfo.environment["LOUPE_EXPORT_DRONE"] else { throw XCTSkip("export not requested") }
        let img = MascotStills.shared.image(.idle, points: 341, kind: .drone)
        try XCTUnwrap(img.pngData()).write(to: URL(fileURLWithPath: path))
    }
}
