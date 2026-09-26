import Combine
import XCTest
@testable import Loupe

/// Laya is required (owner rule 2026-09-25): the one readiness source, the gating decision, the
/// launch step, and the locks lifting live without a restart.
@MainActor
final class ModelReadinessTests: XCTestCase {
    /// A stand-in for `LayaModel`: its status publisher, whether the files are on disk, and a
    /// refresh that re-reads them (as `LayaModel.refresh()` does).
    final class FakeModel {
        let status = CurrentValueSubject<LayaModel.Status, Never>(.notInstalled)
        var installed = false
        private(set) var refreshes = 0
        func refresh() {
            refreshes += 1
            status.send(installed ? .ready : .notInstalled)
        }
    }

    private func readiness(_ m: FakeModel, host: Bool = true, override: ModelReadiness.State? = nil) -> ModelReadiness {
        ModelReadiness(status: m.status.eraseToAnyPublisher(), installed: { m.installed }, refresh: { m.refresh() },
                       hostConfigured: host, override: override, observesApp: false)
    }

    /// Lets the `receive(on: RunLoop.main)` hop deliver.
    private func settle() { RunLoop.main.run(until: Date().addingTimeInterval(0.05)) }

    // MARK: Deriving the state

    func testStateFromStatus() {
        typealias R = ModelReadiness
        XCTAssertEqual(R.derive(status: .ready, installed: true), .ready)
        XCTAssertEqual(R.derive(status: .ready, installed: false), .missing, "ready but the files are gone")
        XCTAssertEqual(R.derive(status: .checking, installed: true), .ready, "opening Laya does not flicker the locks")
        XCTAssertEqual(R.derive(status: .checking, installed: false), .missing)
        XCTAssertEqual(R.derive(status: .notInstalled, installed: false), .missing)
        XCTAssertEqual(R.derive(status: .downloading(0.4), installed: false), .downloading(0.4))
        XCTAssertEqual(R.derive(status: .paused(0.7), installed: false), .paused(0.7))
        XCTAssertEqual(R.derive(status: .verifying("tokenizer.json"), installed: false), .verifying)
        XCTAssertEqual(R.derive(status: .failed("bad hash"), installed: false), .failed("bad hash"))
        XCTAssertTrue(ModelReadiness.State.ready.isReady)
        for s: ModelReadiness.State in [.missing, .downloading(0.9), .paused(0.1), .verifying, .failed("x")] {
            XCTAssertFalse(s.isReady, "\(s)")
        }
    }

    // MARK: The gate

    func testGateLocksOnlyModelFeaturesWhileNotReady() {
        let notReady: [ModelReadiness.State] = [.missing, .downloading(0.5), .paused(0.5), .verifying, .failed("no")]
        for s in notReady {
            XCTAssertEqual(ModelGate.decide(needsModel: true, s), .locked(s), "\(s)")
            XCTAssertEqual(ModelGate.decide(needsModel: false, s), .open, "rules-only features never lock (\(s))")
        }
        XCTAssertEqual(ModelGate.decide(needsModel: true, .ready), .open)
        XCTAssertEqual(ModelGate.decide(needsModel: false, .ready), .open)
    }

    func testLockedTextSaysWhatIsTrue() {
        XCTAssertTrue(ModelGate.detail(.missing, hostConfigured: true).contains("400 MB"))
        XCTAssertTrue(ModelGate.detail(.missing, hostConfigured: false).contains("no model host"))
        XCTAssertTrue(ModelGate.detail(.downloading(0.42), hostConfigured: true).contains("42%"))
        XCTAssertTrue(ModelGate.detail(.paused(0.3), hostConfigured: true).contains("30%"))
        XCTAssertEqual(ModelGate.detail(.failed("A file did not match its fingerprint."), hostConfigured: true),
                       "A file did not match its fingerprint.")
    }

    // MARK: Live changes

    func testDownloadFinishingUnlocksWithoutARestart() {
        let m = FakeModel()
        let r = readiness(m)
        settle()
        XCTAssertEqual(r.state, .missing)
        m.status.send(.downloading(0.25)); settle()
        XCTAssertEqual(r.state, .downloading(0.25))
        m.status.send(.verifying("laya-multilingual-choice.int8.onnx")); settle()
        XCTAssertEqual(r.state, .verifying)
        m.installed = true
        m.status.send(.checking); settle()                 // installed → LayaModel opens it
        XCTAssertEqual(r.state, .ready)
        m.status.send(.ready); settle()
        XCTAssertTrue(r.isReady)
    }

    func testFilesCopiedInAreFoundOnRecheck() {
        let m = FakeModel()
        let r = readiness(m)
        settle()
        XCTAssertEqual(r.state, .missing)
        m.installed = true                                  // a developer copies the files in
        r.recheck(); settle()                               // foreground / the poll / Check again
        XCTAssertEqual(m.refreshes, 1)
        XCTAssertEqual(r.state, .ready)
        m.installed = false                                 // removed (Me → Laya model → Remove)
        m.status.send(.notInstalled); settle()
        XCTAssertEqual(r.state, .missing, "removing the model locks the features again")
    }

    func testReadinessPublishesEachChange() {
        let m = FakeModel()
        let r = readiness(m)
        settle()
        var seen: [ModelReadiness.State] = []
        let sub = r.$state.dropFirst().sink { seen.append($0) }
        m.status.send(.downloading(0.5)); settle()
        m.installed = true
        m.status.send(.ready); settle()
        XCTAssertEqual(seen, [.downloading(0.5), .ready])
        sub.cancel()
    }

    func testLaunchOverrideWinsAndIsNotRechecked() {
        let m = FakeModel()
        m.installed = true
        let locked = readiness(m, override: .missing)
        m.status.send(.ready); settle()
        XCTAssertEqual(locked.state, .missing, "-LoupeModelState missing: locked even with the files")
        locked.recheck()
        XCTAssertEqual(m.refreshes, 0)
        let open = readiness(FakeModel(), override: .ready)
        settle()
        XCTAssertTrue(open.isReady, "-LoupeModelState ready: open without the files")
        XCTAssertEqual(ModelReadiness.State(launchValue: "ready"), .ready)
        XCTAssertEqual(ModelReadiness.State(launchValue: "missing"), .missing)
        XCTAssertNil(ModelReadiness.State(launchValue: "maybe"))
    }

    // MARK: Relaunch (bug 2026-09-26: onboarding came back on every launch)

    /// The installed model must read as ready the moment readiness is created, with no run-loop turn:
    /// RootView takes its launch decision from that first value. Before the fix the replayed `.ready`
    /// arrived a turn later, the launch read `.missing`, and Get the Loupe Decision Model came back.
    func testInstalledModelIsReadyImmediatelyAtLaunch() {
        let m = FakeModel()
        m.installed = true
        m.status.send(.ready)                                // LayaModel.init: refresh() found the files
        let r = readiness(m)                                  // no settle(): this is the launch
        XCTAssertEqual(r.state, .ready, "an installed model reads as ready before the first run-loop turn")
        XCTAssertFalse(RootView.getLayaAtLaunch(ready: r.isReady, launch: LaunchOptions()),
                       "a relaunch with the model installed must not open Get the Loupe Decision Model")
    }

    /// A status published off the main thread still arrives (on the main thread).
    func testStatusFromABackgroundThreadStillArrives() {
        let m = FakeModel()
        let r = readiness(m)
        m.installed = true
        let sent = expectation(description: "sent")
        DispatchQueue.global().async { m.status.send(.ready); sent.fulfill() }
        wait(for: [sent], timeout: 2)
        settle()
        XCTAssertEqual(r.state, .ready)
    }

    // MARK: Onboarding

    func testGetLayaShowsAtLaunchUntilTheModelIsHere() {
        var launch = LaunchOptions()
        XCTAssertTrue(RootView.getLayaAtLaunch(ready: false, launch: launch), "every launch while it is missing")
        XCTAssertFalse(RootView.getLayaAtLaunch(ready: true, launch: launch), "no Get Laya step with the model")
        launch.skipOnboarding = true
        XCTAssertFalse(RootView.getLayaAtLaunch(ready: false, launch: launch))
        launch.skipOnboarding = false
        launch.game = .watch
        XCTAssertFalse(RootView.getLayaAtLaunch(ready: false, launch: launch), "a launch straight into the game")
    }

    // MARK: The game's model pilot

    func testGamePilotSwitchesToLayaWhenTheModelArrives() {
        var ready = false
        let game = GameController(mode: .watch, seed: 1, backendProvider: { nil }, modelInstalled: { ready })
        guard case .baseline(let reason) = game.pilot else { return XCTFail("\(game.pilot)") }
        XCTAssertEqual(reason, GameController.notInstalled)
        game.modelBecameReady()
        if case .baseline = game.pilot {} else { XCTFail("still not ready: nothing changes (\(game.pilot))") }
        ready = true
        game.modelBecameReady()
        XCTAssertEqual(game.pilot, .opening, "restarts with Laya")
        game.close()
    }
}
