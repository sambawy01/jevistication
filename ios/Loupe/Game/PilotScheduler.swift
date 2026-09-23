import Foundation
import LoupeKit

/// Where pilot work runs. The app uses one serial background queue shared by every run (so a restart
/// never puts two threads inside Laya at once, as the desktop game's single model thread); tests
/// inject one they drive by hand.
protocol PilotExecutor: AnyObject {
    func execute(_ work: @escaping () -> Void)
}

final class QueuePilotExecutor: PilotExecutor {
    static let shared = QueuePilotExecutor()
    private let queue = DispatchQueue(label: "dev.loupe.game.pilot", qos: .userInitiated)
    func execute(_ work: @escaping () -> Void) { queue.async(execute: work) }
}

/// Runs a `HostedDecider`'s pilot off the simulation (main) thread and hands each decision back on
/// it. The session asks for a decision every `decisionInterval` ticks when none is in flight
/// (`GameSession`'s rule, the desktop's); this only carries the observation to the executor and the
/// answer back. Nothing here ever waits: a slow model means fewer decisions, never a dropped frame.
final class PilotScheduler {
    let decider: HostedDecider
    private let executor: PilotExecutor
    /// How a finished decision returns to the simulation thread.
    private let returnToSimulation: (@escaping () -> Void) -> Void

    private(set) var inFlight = false
    private(set) var dispatched = 0
    private(set) var landed = 0

    init(decider: HostedDecider,
         executor: PilotExecutor = QueuePilotExecutor.shared,
         returnToSimulation: @escaping (@escaping () -> Void) -> Void = { work in DispatchQueue.main.async(execute: work) }) {
        self.decider = decider
        self.executor = executor
        self.returnToSimulation = returnToSimulation
    }

    /// Call after every `session.tick()`, on the simulation thread.
    func afterTick() {
        guard !inFlight, !decider.closed, let observation = decider.take() else { return }
        inFlight = true
        dispatched += 1
        let decider = self.decider
        let back = returnToSimulation
        executor.execute { [weak self] in
            let decision = decider.decide(observation: observation)
            back {
                self?.inFlight = false
                self?.landed += 1
                decider.deliver(decision: decision)
            }
        }
    }

    /// Stops scheduling; a decision still computing is dropped when it returns.
    func close() { decider.close() }
}
