package dev.loupe.kit.sweep

import dev.loupe.persistence.StoreLock

/**
 * Who wants the model, most urgent first. There is one model and one model thread (BUILD.md F2:
 * "single model thread"); these decide who yields when two want it.
 */
enum class ModelPriority(val rank: Int) {
    /** A retroactive or passive sweep: runs only when nothing above it wants the model. */
    SWEEP(0),

    /** The game's pilot: yields only to the user's own foreground work. */
    GAME(1),

    /** Work the user is looking at: flight ranking, a Results run, the watchers' model half. */
    FOREGROUND(2),
}

/** A held claim on the model. [release] is idempotent. */
class ModelClaim internal constructor(private val lane: ModelLane, val priority: ModelPriority, internal val id: Long) {
    fun release() = lane.release(this)
}

/**
 * The single model lane's arbiter. It owns no thread: the host runs every model call on one serial
 * queue (so two calls are never inside the model at once) and holds a [ModelClaim] while its work is
 * queued or running. Lower-priority work polls [shouldYield] between items and stops (checkpointed)
 * as soon as something above it has a claim; the host resumes it when [isFree] says so again.
 *
 * Thread-safe: claims come from the UI thread, polls from the model thread.
 */
class ModelLane {
    private val lock = StoreLock()
    private val claims = LinkedHashMap<Long, ModelPriority>()
    private var nextId = 1L
    private val idleListeners = mutableListOf<() -> Unit>()

    fun claim(priority: ModelPriority): ModelClaim = lock.withLock {
        val id = nextId++
        claims[id] = priority
        ModelClaim(this, priority, id)
    }

    internal fun release(claim: ModelClaim) {
        val notify = lock.withLock {
            if (claims.remove(claim.id) == null) return@withLock emptyList()
            if (claims.values.any { it.rank > ModelPriority.SWEEP.rank }) emptyList() else idleListeners.toList()
        }
        notify.forEach { it() }
    }

    /** True when work at [priority] must stop: someone above it holds a claim. */
    fun shouldYield(priority: ModelPriority): Boolean = lock.withLock { claims.values.any { it.rank > priority.rank } }

    /** The highest claim held, or null when nobody wants the model. */
    fun highest(): ModelPriority? = lock.withLock { claims.values.maxByOrNull { it.rank } }

    /** True when nothing above a sweep holds a claim. */
    fun isFree(): Boolean = !shouldYield(ModelPriority.SWEEP)

    /** Called (on the releasing thread) whenever the last claim above [ModelPriority.SWEEP] goes. */
    fun onIdle(listener: () -> Unit) = lock.withLock { idleListeners += listener }
}
