import Combine
import Foundation
import UIKit

/// Whether Laya, the on-device model, can be used right now: the app's one source of truth for
/// every feature that needs the model (owner rule 2026-09-25: the model is not optional, so
/// features that need it are locked, visibly, until it is here — never started and left to fail).
///
/// Derived from `LayaModel.status` and whether the pinned files are on disk. Files are hashed
/// (SHA-256) when downloaded and again when Laya is opened; a file that fails its pin is deleted,
/// which reads here as `missing` again. The state changes live: a finished download, files
/// copied in by a developer (checked every few seconds while missing and in the foreground), and
/// a re-check whenever the app comes back to the foreground. No restart needed.
@MainActor
final class ModelReadiness: ObservableObject {
    enum State: Equatable {
        case ready
        case missing
        case downloading(Double)      // 0...1
        case paused(Double)
        case verifying
        case failed(String)

        var isReady: Bool { self == .ready }
    }

    static let shared: ModelReadiness = {
        let laya = LayaModel.shared
        return ModelReadiness(status: laya.$status.eraseToAnyPublisher(),
                              installed: { laya.isInstalled },
                              refresh: { laya.refresh() },
                              hostConfigured: laya.hostConfigured,
                              override: LaunchOptions.current.modelState)
    }()

    @Published private(set) var state: State
    /// The bundled manifest names a download host. When false, Get Laya says so and offers no Download.
    let hostConfigured: Bool
    var isReady: Bool { state.isReady }

    private let installed: () -> Bool
    private let refreshSource: () -> Void
    private let override: State?
    private var lastStatus: LayaModel.Status
    private var bag: Set<AnyCancellable> = []
    private var poll: Timer?
    private var appActive = false
    /// How often the files are looked for while missing (existence checks only, no hashing).
    static let pollSeconds: TimeInterval = 4

    /// - Parameter override: DEBUG launch argument `-LoupeModelState ready|missing` (UI tests own the
    ///   model state without the 400 MB files); nil follows the real files.
    init(status: AnyPublisher<LayaModel.Status, Never>,
         installed: @escaping () -> Bool,
         refresh: @escaping () -> Void,
         hostConfigured: Bool,
         override: State? = nil,
         observesApp: Bool = true) {
        self.installed = installed
        self.refreshSource = refresh
        self.hostConfigured = hostConfigured
        self.override = override
        self.lastStatus = .notInstalled
        self.state = override ?? .missing
        status.receive(on: RunLoop.main).sink { [weak self] s in self?.statusChanged(s) }.store(in: &bag)
        // A publisher that replays its current value (like @Published) has set the state by now;
        // derive once more so a source that does not replay is still right at launch.
        recompute()
        guard observesApp else { return }
        NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)
            .sink { [weak self] _ in self?.recheck() }.store(in: &bag)
        NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)
            .sink { [weak self] _ in self?.appActive = true; self?.updatePolling() }.store(in: &bag)
        NotificationCenter.default.publisher(for: UIApplication.didEnterBackgroundNotification)
            .sink { [weak self] _ in self?.appActive = false; self?.updatePolling() }.store(in: &bag)
        appActive = UIApplication.shared.applicationState != .background
        updatePolling()
    }

    /// The state for a `LayaModel.Status`. Opening Laya (`.checking`) is still ready when the files
    /// are here, so a feature does not flicker locked while the model loads.
    nonisolated static func derive(status: LayaModel.Status, installed: Bool) -> State {
        switch status {
        case .ready, .checking: return installed ? .ready : .missing
        case .notInstalled: return .missing
        case .downloading(let p): return .downloading(p)
        case .paused(let p): return .paused(p)
        case .verifying: return .verifying
        case .failed(let m): return .failed(m)
        }
    }

    /// Looks for the files again (foreground, a "Check again" tap, a locked screen appearing).
    func recheck() {
        guard override == nil else { return }
        refreshSource()
        recompute()
    }

    private func statusChanged(_ s: LayaModel.Status) {
        lastStatus = s
        recompute()
    }

    private func recompute() {
        let next = override ?? Self.derive(status: lastStatus, installed: installed())
        if next != state { state = next }
        updatePolling()
    }

    /// Polls only while the files are missing, nothing is downloading and the app is in front.
    private func updatePolling() {
        let want = appActive && override == nil && state == .missing
        if want, poll == nil {
            poll = Timer.scheduledTimer(withTimeInterval: Self.pollSeconds, repeats: true) { [weak self] t in
                guard let self else { t.invalidate(); return }
                Task { @MainActor in
                    guard self.state == .missing, self.installed() else { return }
                    self.recheck()
                }
            }
            poll?.tolerance = 1
        } else if !want, let p = poll {
            p.invalidate(); poll = nil
        }
    }
}

extension ModelReadiness.State {
    /// `-LoupeModelState ready|missing`.
    init?(launchValue: String) {
        switch launchValue {
        case "ready": self = .ready
        case "missing": self = .missing
        default: return nil
        }
    }
}

/// The gating decision for one feature, pure (tests call it directly).
enum ModelGate {
    enum Decision: Equatable {
        case open
        case locked(ModelReadiness.State)
    }

    /// Rules-only features (privacy check, mail triage rules, sources, settings) never lock.
    static func decide(needsModel: Bool, _ state: ModelReadiness.State) -> Decision {
        guard needsModel, !state.isReady else { return .open }
        return .locked(state)
    }

    /// What the locked state says under "Needs Laya, the on-device model".
    static func detail(_ state: ModelReadiness.State, hostConfigured: Bool) -> String {
        switch state {
        case .ready: return ""
        case .missing:
            return hostConfigured ? "Get the decision model once (about 400 MB) and this unlocks. It runs on this iPhone."
                                  : "This build cannot download the decision model yet: no model host is set."
        case .downloading(let p): return "The decision model is downloading: \(Int(p * 100))%. This unlocks when it finishes."
        case .paused(let p): return "The download is paused at \(Int(p * 100))%."
        case .verifying: return "Checking the decision model's files…"
        case .failed(let m): return m
        }
    }
}
