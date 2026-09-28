import Foundation
import LoupeKit

/// The one model thread (BUILD.md F2: "single model thread"). Every Laya call in the app — the
/// Results sweep, the passive sort, the watchers' model half, flight ranking and the game's pilot —
/// runs on `queue`, so two calls are never inside the model at once. Who goes first is LoupeKit's
/// `ModelLane`: foreground work > the game > sweeps. A sweep checks the lane between items and
/// stops (checkpointed in the ledger) when anything above it holds a claim, so foreground work
/// waits at most one item.
enum ModelWork {
    static let lane = ModelLane()
    static let queue = DispatchQueue(label: "com.loupe-ai.ios.model", qos: .userInitiated)

    /// Runs `work` on the model queue under a claim at `priority`, released when it returns.
    /// The claim is taken *before* queueing, so a running sweep yields to it at its next item.
    static func run<T>(_ priority: ModelPriority, _ work: @escaping () -> T) async -> T {
        let claim = lane.claim(priority: priority)
        return await withCheckedContinuation { c in
            queue.async {
                let value = work()
                claim.release()
                runEnded()
                c.resume(returning: value)
            }
        }
    }

    /// Laya under Model settings' memory mode, once opened (set by `LayaModel`).
    static let memory = MemoryHolder()

    /// After a run, on the model queue: `memory_mode = low` frees Laya straight away.
    static func runEnded() {
        guard let m = memory.get() else { return }
        _ = m.afterRun(mode: ModelSettingsService.sharedStore.current.memoryMode, laneFree: lane.isFree())
    }

    final class MemoryHolder: @unchecked Sendable {
        private let lock = NSLock()
        private var value: ModelMemory?
        func set(_ m: ModelMemory?) { lock.lock(); value = m; lock.unlock() }
        func get() -> ModelMemory? { lock.lock(); defer { lock.unlock() }; return value }
    }
}

/// Rules-only work (mail triage with its site checks, the privacy check): LoupeKit's mechanical
/// rules, no model call on iPhone. It must NOT go through `ModelWork`: a background sort holds the
/// model queue for its whole run and yields only to a higher claim, so a sweep-priority rules run
/// sat behind thousands of items ("Classifying" that never moved, owner's iPhone 2026-09-28).
/// Each check has its own serial queue, so a long privacy check never holds up triage either, and
/// the runs share no mutable state (every call builds its own collectors; the rule tables are
/// read-only).
enum RulesWork {
    static let mail = DispatchQueue(label: "com.loupe-ai.ios.rules.mail", qos: .userInitiated)
    static let privacy = DispatchQueue(label: "com.loupe-ai.ios.rules.privacy", qos: .userInitiated)

    /// Runs `work` on `queue` and returns its value.
    static func run<T>(on queue: DispatchQueue, _ work: @escaping () -> T) async -> T {
        await withCheckedContinuation { c in
            queue.async { c.resume(returning: work()) }
        }
    }
}
