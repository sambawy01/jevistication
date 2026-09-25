import Foundation
import LoupeKit
import SwiftUI

/// One running (or just finished) Sources scan, as the live display sees it. Producers report from any thread
/// through `ScanFeed`; the display observes only this object, and it publishes at most ~12 times a second, so a
/// scan never redraws the Sources screen per item (only the display's own canvas moves every frame).
@MainActor
final class LiveScan: ObservableObject, Identifiable {
    nonisolated let id = UUID()
    nonisolated let source: String
    nonisolated let feed: ScanFeed
    @Published private(set) var snapshot: ScanSnapshot
    /// How many snapshots were published (tests check the throttle end to end).
    private(set) var publishes = 0

    init(pipeline: ScanPipeline, hz: Double = 12, clock: @escaping @Sendable () -> TimeInterval = ScanFeed.monotonic) {
        source = pipeline.source
        snapshot = ScanSnapshot(pipeline: pipeline)
        feed = ScanFeed(pipeline: pipeline, hz: hz, clock: clock)
        feed.publish = { [weak self] snap in
            MainActor.assumeIsolated {
                self?.snapshot = snap
                self?.publishes += 1
            }
        }
    }

    var finished: Bool { snapshot.phase != .running }
}

/// The thread-safe side of a live scan: producers call it from their own queues; it aggregates under a lock
/// and flushes a snapshot to the main queue no more than `hz` times a second (with a trailing flush).
final class ScanFeed: @unchecked Sendable {
    private let lock = NSLock()
    private var stream: ScanStream
    private var throttle: ScanThrottle
    private let clock: @Sendable () -> TimeInterval
    /// Set by `LiveScan`; called on the main queue.
    var publish: (ScanSnapshot) -> Void = { _ in }

    static let monotonic: @Sendable () -> TimeInterval = { Date().timeIntervalSinceReferenceDate }

    init(pipeline: ScanPipeline, hz: Double, clock: @escaping @Sendable () -> TimeInterval) {
        stream = ScanStream(pipeline: pipeline)
        throttle = ScanThrottle(hz: hz)
        self.clock = clock
    }

    func item(_ e: ScanEvent) { change { $0.ingest(e, now: $1) } }

    func scanned(_ p: ScanProgress, label: String? = nil) {
        change {
            $0.scanned(seen: Int(p.filesSeen), total: Int(p.filesTotal), itemsRead: Int(p.itemsRead), skippedNow: Int(p.skipped),
                       current: p.current, label: label, now: $1)
        }
    }

    func status(_ s: String) { change { st, _ in st.setStatus(s) } }
    func listed(_ total: Int) { change { st, _ in st.listed(total: total) } }
    func fetched() { change { $0.markFetched(now: $1) } }
    func finish(saved: Int) { change({ $0.finish(saved: saved, now: $1) }, immediately: true) }
    func fail() { change({ st, _ in st.fail() }, immediately: true) }

    /// A read of the current aggregate (tests).
    func peek() -> ScanSnapshot {
        lock.lock(); defer { lock.unlock() }
        return stream.snapshot(now: clock())
    }

    private func change(_ f: (inout ScanStream, TimeInterval) -> Void, immediately: Bool = false) {
        lock.lock()
        let now = clock()
        f(&stream, now)
        let delay = immediately ? 0 : throttle.request(now: now)
        lock.unlock()
        guard let delay else { return }
        if delay == 0 && Thread.isMainThread && immediately {
            flush()
        } else {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [self] in flush() }
        }
    }

    private func flush() {
        lock.lock()
        let now = clock()
        let snap = stream.snapshot(now: now)
        throttle.fired(now: now)
        lock.unlock()
        publish(snap)
    }
}
