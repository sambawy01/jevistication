import Foundation
import LoupeKit

/// What a run reads from Model settings, and where it reports that Laya was off. The app's is
/// `ModelSettingsService.shared`; tests pass their own.
@MainActor
protocol ModelSettingsSource: AnyObject {
    /// The settings now (an immutable snapshot, safe to hand to the model thread).
    var current: EngineSettings { get }
    /// A run of [feature] happened with `use_laya` off (or on again): drives that screen's banner.
    func recordRun(_ feature: String, layaOff: Bool)
}

extension ModelSettingsSource {
    func policy(_ feature: String) -> RunPolicy { current.policy(feature: feature) }
    func useLaya(_ feature: String) -> Bool { current.useLaya(feature: feature) }
}

/// Model settings on the iPhone (docs/MODEL-SETTINGS.md): LoupeKit's `EngineSettingsStore` over
/// `engine_settings.json` in Application Support/Loupe — Loupe Station's schema v1, the same keys,
/// ranges and defaults, plus the phone's features. Every consumer reads it at the start of a run,
/// so a change applies to the next run with no restart; `memory_mode` loads or unloads Laya at once
/// and `idle_unload_min` applies at the next 30-second memory tick (`LayaModel`).
///
/// There are no environment variables or MDM overrides on iOS, so Station's "set by environment"
/// never appears here.
@MainActor
final class ModelSettingsService: ObservableObject, ModelSettingsSource {
    /// The app's store. Under XCTest or `-LoupeFixtures` it lives in a throwaway directory (as the
    /// ledger does), so tests never touch the user's settings.
    nonisolated static let sharedStore = EngineSettingsStore(directory: LedgerService.defaultHome().path)

    static let shared: ModelSettingsService = {
        let s = ModelSettingsService(store: sharedStore)
        s.onReload = { settings in LayaModel.shared.memorySettingsChanged(settings) }
        return s
    }()

    let store: EngineSettingsStore
    @Published private(set) var settings: EngineSettings
    /// Features whose latest run had Laya off: their screens show the banner.
    @Published private(set) var layaOffRuns: Set<String> = []
    @Published var notice: String?

    /// Called after a change to `memory_mode` or `idle_unload_min`.
    var onReload: ((EngineSettings) -> Void)?

    init(store: EngineSettingsStore) {
        self.store = store
        self.settings = store.current
    }

    var current: EngineSettings { store.current }

    func recordRun(_ feature: String, layaOff: Bool) {
        if layaOff { layaOffRuns.insert(feature) } else { layaOffRuns.remove(feature) }
    }

    func wasLayaOff(_ feature: String) -> Bool { layaOffRuns.contains(feature) }

    // MARK: Changes

    func setBool(_ key: String, _ value: Bool) { apply(store.setBool(key: key, value: value)) }

    func setString(_ key: String, _ value: String?) { apply(store.setString(key: key, value: value)) }

    func setNumber(_ key: String, _ value: Double?) { apply(store.setNumber(key: key, value: value.map { KotlinDouble(value: $0) })) }

    func setTextChars(_ key: String, mode: String, value: Int) {
        apply(store.setTextChars(key: key, mode: mode, value: Int32(clamping: value)))
    }

    func reset(_ key: String) { apply(store.reset(key: key)) }

    func restoreAll() {
        let change = store.resetAll()
        apply(change)
        if !change.changed.isEmpty { notice = MS.t("restored") }
    }

    private func apply(_ change: SettingsChange) {
        settings = store.current
        if !change.errors.isEmpty {
            notice = change.errors.joined(separator: " ")
            return
        }
        guard !change.changed.isEmpty else { return }
        if change.reload.isEmpty {
            notice = MS.t("saved")
        } else {
            notice = MS.t("savedMemory")
            onReload?(settings)
        }
    }
}
