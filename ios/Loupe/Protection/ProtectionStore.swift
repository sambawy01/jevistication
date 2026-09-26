import Combine
import SwiftUI

/// Browsing protection's data for the app's screens (2026-09-26): the Spotted log, Check a link's
/// recent checks, and what the Guard tab's badge and Now's card read.
///
/// - `unseenCount`: Spotted entries not yet viewed. **RootView (the Guard agent) should read it** as
///   `@ObservedObject private var protection = ProtectionStore.shared` and put
///   `.badge(protection.unseenCount)` on the Guard tab item. It drops to 0 when the Spotted list is
///   viewed (`markSeen()`, called by the list on appear).
/// - `summary`: `SpottedSummary` for Now ("Loupe spotted N risky sites this week": `summary.headline`,
///   nil when there were none).
///
/// The extensions write the same files; a Darwin notification from them (and the app coming to the
/// foreground) reloads.
@MainActor
final class ProtectionStore: ObservableObject {
    static let shared = ProtectionStore(observe: true)

    @Published private(set) var spotted: [SpottedEntry] = []
    @Published private(set) var unseenCount = 0
    @Published private(set) var summary = SpottedSummary(sitesThisWeek: 0, dangerousThisWeek: 0, unseen: 0, latest: nil)
    @Published private(set) var recent: [LinkVerdict] = []

    let log: SpottedLog
    let recentStore: RecentChecksStore
    private let defaults: UserDefaults
    private let now: () -> Date
    private var observers: Set<AnyCancellable> = []

    init(log: SpottedLog = SpottedLog(), recentStore: RecentChecksStore = RecentChecksStore(),
         defaults: UserDefaults = ProtectionGroup.defaults, now: @escaping () -> Date = Date.init, observe: Bool = false) {
        self.log = log
        self.recentStore = recentStore
        self.defaults = defaults
        self.now = now
        reload()
        if observe {
            NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)
                .sink { [weak self] _ in self?.reload() }
                .store(in: &observers)
            let center = CFNotificationCenterGetDarwinNotifyCenter()
            CFNotificationCenterAddObserver(center, Unmanaged.passUnretained(self).toOpaque(), { _, observer, _, _, _ in
                guard let observer else { return }
                let store = Unmanaged<ProtectionStore>.fromOpaque(observer).takeUnretainedValue()
                Task { @MainActor in store.reload() }
            }, ProtectionGroup.spottedChanged as CFString, nil, .deliverImmediately)
        }
    }

    func reload() {
        let entries = log.entries()
        spotted = entries
        unseenCount = entries.filter { !$0.seen }.count
        summary = SpottedSummary.of(entries, now: now())
        recent = recentStore.all()
    }

    /// A verdict from Check a link: kept in recent checks, and in the Spotted log when flagged.
    func add(_ v: LinkVerdict) {
        recentStore.add(v)
        log.record(v)
        reload()
    }

    /// The Spotted list was viewed: the Guard badge clears.
    func markSeen() {
        guard unseenCount > 0 else { return }
        log.markAllSeen()
        reload()
    }

    func clearSpotted() { log.clear(); reload() }
    func removeSpotted(_ id: UUID) { log.remove(id); reload() }
    func clearRecent() { recentStore.clear(); reload() }
    func removeRecent(_ id: UUID) { recentStore.remove(id); reload() }

    /// Notify about suspicious sites too (dangerous ones always, once notifications are allowed).
    var notifySuspicious: Bool {
        get { defaults.bool(forKey: ProtectionGroup.Keys.notifySuspicious) }
        set { defaults.set(newValue, forKey: ProtectionGroup.Keys.notifySuspicious); objectWillChange.send() }
    }
}
