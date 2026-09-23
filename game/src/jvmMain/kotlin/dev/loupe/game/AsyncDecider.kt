package dev.loupe.game

import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Computes decisions on a background thread and releases each the first tick after it finishes —
 * the real-time arrangement the desktop game uses. The simulation never waits for the model.
 */
class AsyncDecider(
    override val pilot: Pilot,
    /**
     * Where decisions run. Null gives this decider a thread of its own, shut down on [close]. Pass
     * one to share it — the desktop game does, so a restarted run never has two threads inside the
     * same model at once; a shared executor is left running on [close].
     */
    executor: ExecutorService? = null,
) : Decider {
    private val ownsExecutor = executor == null
    private val executor: ExecutorService = executor ?: Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pilot-${pilot.name}").apply { isDaemon = true }
    }

    private var inFlight: Future<PilotDecision>? = null
    private var observation: Observation? = null

    override val busy: Boolean get() = inFlight != null

    override fun submit(observation: Observation) {
        check(inFlight == null) { "a decision is already in flight" }
        this.observation = observation
        inFlight = executor.submit<PilotDecision> { safely(pilot, observation) }
    }

    override fun poll(tick: Long): PilotDecision? {
        val future = inFlight ?: return null
        if (!future.isDone) return null
        inFlight = null
        return try {
            future.get()
        } catch (e: ExecutionException) {
            failed(observation!!, e.cause ?: e)
        }
    }

    override fun close() {
        inFlight?.cancel(false)
        if (ownsExecutor) executor.shutdownNow()
    }
}
