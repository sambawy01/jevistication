import XCTest
@testable import Loupe

/// The 3D mascot's pure half (epic #7 child 8): state → pose mapping, blending math, Reduce Motion.
final class MascotRigTests: XCTestCase {
    private func rig(_ s: MascotState, reduce: Bool = false) -> MascotRig {
        var r = MascotRig(random: { 0.5 }); r.reduceMotion = reduce; r.set(s); return r
    }

    func testStateToPoseMapping() {
        XCTAssertEqual(rig(.idle).poseName, .idle)
        XCTAssertEqual(rig(.greeting).poseName, .wave)
        XCTAssertEqual(rig(.watching).poseName, .watching)
        XCTAssertEqual(rig(.scanning).poseName, .scanning)
        XCTAssertEqual(rig(.thinking).poseName, .unsure)
        XCTAssertEqual(rig(.found).poseName, .found)
        XCTAssertEqual(rig(.happy).poseName, .happy)
        XCTAssertEqual(rig(.empty).poseName, .empty)
        XCTAssertEqual(MascotState.shrug, .empty)
    }

    func testFacesPerState() {
        XCTAssertEqual(rig(.scanning).face, .scanBar)
        XCTAssertEqual(rig(.found).face, .happy)
        XCTAssertEqual(rig(.happy).face, .happy)
        XCTAssertEqual(rig(.thinking).face, .unsure)
        XCTAssertEqual(rig(.empty).face, .empty)
        XCTAssertEqual(rig(.greeting).face, .big)
        XCTAssertEqual(rig(.idle).face, .open)
    }

    func testPoseKeyframesMatchThePrototype() {
        let z = SIMD2<Double>(0, 0)
        let found = MascotPose.target(.found, t: 0, stateT: 1, look: z, scanX: nil, idleWave: false, reduceMotion: true)
        XCTAssertEqual(found.aAo, 2.55); XCTAssertEqual(found.aBo, 2.55)       // both arms up
        let think = MascotPose.target(.unsure, t: 0, stateT: 0, look: z, scanX: nil, idleWave: false, reduceMotion: true)
        XCTAssertEqual(think.aBx, 2.05); XCTAssertEqual(think.hz, 0.26)        // hand to chin, head tilt
        let shrug = MascotPose.target(.empty, t: 0, stateT: 0, look: z, scanX: nil, idleWave: false, reduceMotion: true)
        XCTAssertEqual(shrug.sh, 0.035)
        let scan = MascotPose.target(.scanning, t: 0, stateT: 0, look: z, scanX: 1, idleWave: false, reduceMotion: false)
        XCTAssertEqual(scan.lean, 0.2); XCTAssertEqual(scan.hy, 0.35, accuracy: 1e-9)
        let watch = MascotPose.target(.watching, t: 0, stateT: 0, look: [1, -1], scanX: nil, idleWave: false, reduceMotion: false)
        XCTAssertEqual(watch.hy, 0.7); XCTAssertEqual(watch.hx, -0.45)          // head follows the touch
    }

    func testBlendingEasesWithoutSnapping() {
        let a = MascotPose.rest
        var b = MascotPose.rest; b.hx = 1; b.aAo = 2.14
        let k = MascotPose.easing(dt: 1 / 60, rate: 7, reduceMotion: false)
        XCTAssertEqual(k, 1 - exp(-7.0 / 60), accuracy: 1e-12)
        let m = a.blended(toward: b, k: k)
        XCTAssertEqual(m.hx, k, accuracy: 1e-12)
        XCTAssertGreaterThan(m.hx, 0); XCTAssertLessThan(m.hx, 0.2)
        XCTAssertEqual(a.blended(toward: b, k: 0), a)
        XCTAssertEqual(a.blended(toward: b, k: 1), b)
        XCTAssertEqual(a.blended(toward: b, k: 5), b)                           // clamped
        // frame-rate independent: two half steps == one full step
        let k2 = MascotPose.easing(dt: 1 / 120, rate: 7, reduceMotion: false)
        let twice = a.blended(toward: b, k: k2).blended(toward: b, k: k2)
        XCTAssertEqual(twice.hx, m.hx, accuracy: 1e-9)
    }

    func testStateChangeBlendsOverSeveralFrames() {
        var r = rig(.idle)
        for _ in 0..<120 { _ = r.step(dt: 1 / 60) }
        r.set(.found)
        let first = r.step(dt: 1 / 60).pose.aAo
        XCTAssertLessThan(first, 1.0)                                           // no snap to 2.55
        for _ in 0..<60 { _ = r.step(dt: 1 / 60) }
        XCTAssertEqual(r.current.aAo, 2.55, accuracy: 0.05)
    }

    func testOneShotStatesSettleAndTapReturns() {
        var r = rig(.greeting)
        for _ in 0..<200 { _ = r.step(dt: 1 / 60) }                             // > 2.8 s
        XCTAssertEqual(r.poseName, .idle)
        XCTAssertEqual(r.face, .big)
        r.set(.scanning); r.tap()
        XCTAssertEqual(r.poseName, .wave); XCTAssertEqual(r.face, .happy)
        for _ in 0..<140 { _ = r.step(dt: 1 / 60) }                             // > 2.2 s
        XCTAssertEqual(r.poseName, .scanning)
    }

    func testReduceMotionIsStaticPerState() {
        for s in MascotState.allCases {
            var r = rig(s, reduce: true)
            let f1 = r.step(dt: 1 / 60)
            XCTAssertEqual(r.current, r.target(), "\(s) snaps to its pose")
            XCTAssertFalse(r.isAnimating, "\(s) needs no more frames")
            for _ in 0..<90 { _ = r.step(dt: 1 / 60) }
            let f2 = r.step(dt: 1 / 60)
            XCTAssertEqual(f1.pose, f2.pose, "\(s) holds still")
            XCTAssertEqual(f2.breath, 0); XCTAssertEqual(f2.eyeOpen, 1)
        }
        var r = rig(.found, reduce: true)
        _ = r.step(dt: 1 / 60)
        XCTAssertEqual(r.current.y, 0)                                          // no hop
        XCTAssertEqual(r.current.aAo, 2.55)                                     // but the pose is there
    }

    func testIdleBlinksAndLooks() {
        var r = rig(.idle)
        r.lookTarget = [1, 0]
        var blinked = false
        for _ in 0..<(60 * 5) { if r.step(dt: 1 / 60).eyeOpen < 1 { blinked = true } }
        XCTAssertTrue(blinked)
        XCTAssertEqual(r.step(dt: 1 / 60).look.x, 1, accuracy: 0.01)
    }

    func testFaceKeyChangesOnlyWithTheFace() {
        let a = MascotFaceRenderer.Spec(face: .open, eyeOpen: 1, look: [0, 0], scanU: 0.3)
        var b = a; b.scanU = 0.9
        XCTAssertEqual(MascotFaceRenderer.key(a), MascotFaceRenderer.key(b))    // scan position ignored off scan face
        b.face = .scanBar; var c = b; c.scanU = 0.1
        XCTAssertNotEqual(MascotFaceRenderer.key(b), MascotFaceRenderer.key(c))
    }

    func testPauseRule() {
        XCTAssertTrue(MascotPlayback.shouldPlay(onScreen: true, appActive: true, tabSelected: true, animating: true))
        XCTAssertFalse(MascotPlayback.shouldPlay(onScreen: true, appActive: true, tabSelected: false, animating: true))
        XCTAssertFalse(MascotPlayback.shouldPlay(onScreen: false, appActive: true, tabSelected: true, animating: true))
        XCTAssertFalse(MascotPlayback.shouldPlay(onScreen: true, appActive: false, tabSelected: true, animating: true))
        XCTAssertFalse(MascotPlayback.shouldPlay(onScreen: true, appActive: true, tabSelected: true, animating: false))
    }
}
