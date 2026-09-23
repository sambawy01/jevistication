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
    static let queue = DispatchQueue(label: "dev.loupe.model", qos: .userInitiated)

    /// Runs `work` on the model queue under a claim at `priority`, released when it returns.
    /// The claim is taken *before* queueing, so a running sweep yields to it at its next item.
    static func run<T>(_ priority: ModelPriority, _ work: @escaping () -> T) async -> T {
        let claim = lane.claim(priority: priority)
        return await withCheckedContinuation { c in
            queue.async {
                let value = work()
                claim.release()
                c.resume(returning: value)
            }
        }
    }
}
