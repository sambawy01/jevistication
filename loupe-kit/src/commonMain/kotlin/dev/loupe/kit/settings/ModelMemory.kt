package dev.loupe.kit.settings

import dev.loupe.engine.Backend
import dev.loupe.persistence.StoreLock
import kotlin.time.TimeSource

/** An opened model: its backend, and how to free it. */
interface LoadedModel {
    val backend: Backend

    fun close()
}

/**
 * `global.memory_mode` and `global.idle_unload_min` on the phone: when Laya is kept in memory.
 *
 * - `full` keeps Laya loaded once it has been opened (what the app always did).
 * - `balanced` frees it after `idle_unload_min` minutes without a decision (0 = never).
 * - `low` frees it as soon as a run ends and nothing else wants the model.
 *
 * [backend] is what every consumer holds: it reloads Laya on the next decision after an unload
 * (through [opener], which verifies the files again, so that decision waits a few seconds). A failed
 * reload throws inside `score`, which every caller already turns into an unusable answer.
 *
 * Every model call runs on the host's one model thread, and so does every [unload], [maintain] and
 * [afterRun]; an unload is still refused while a call is in flight, so a model is never closed
 * under a decision.
 */
class ModelMemory(
    private val opener: () -> LoadedModel?,
    private val clockMillis: () -> Long = defaultClock(),
) {
    private val lock = StoreLock()
    private var loaded: LoadedModel? = null
    private var lastUse: Long = clockMillis()
    private var inFlight = 0
    private var forgotten = false

    /** Times Laya was (re)opened and freed; for tests and the settings screen. */
    var loads: Int = 0
        private set
    var unloads: Int = 0
        private set

    val isLoaded: Boolean get() = lock.withLock { loaded != null }

    val backend: Backend = Backend { judgment, state ->
        val model = acquire()
        try {
            model.backend.score(judgment, state)
        } finally {
            lock.withLock {
                inFlight--
                lastUse = clockMillis()
            }
        }
    }

    /** Hands over a model the host has just opened (the first, verified open). */
    fun install(model: LoadedModel) {
        val old = lock.withLock {
            val previous = loaded
            loaded = model
            forgotten = false
            lastUse = clockMillis()
            loads++
            previous
        }
        if (old != null && old !== model) old.close()
    }

    private fun acquire(): LoadedModel {
        val held = lock.withLock {
            val m = loaded
            if (m != null) inFlight++
            m to forgotten
        }
        held.first?.let { return it }
        check(!held.second) { "Laya was removed from this phone" }
        val opened = opener() ?: throw IllegalStateException("Laya could not be loaded again")
        val (use, spare) = lock.withLock {
            val existing = loaded
            inFlight++
            if (existing != null) {
                existing to opened
            } else {
                loaded = opened
                loads++
                opened to null
            }
        }
        spare?.close()
        return use
    }

    /** `full` asks for Laya loaded now: opens it if it was freed. False when it could not be. */
    fun ensureLoaded(): Boolean {
        if (isLoaded) return true
        return runCatching {
            acquire()
            lock.withLock { inFlight-- }
        }.isSuccess
    }

    /** Frees Laya now; false when it was not loaded or a decision is in flight. */
    fun unload(): Boolean {
        val model = lock.withLock {
            val m = loaded
            if (m == null || inFlight > 0) return@withLock null
            loaded = null
            unloads++
            m
        } ?: return false
        model.close()
        return true
    }

    /** Frees Laya for good (its files are being removed or replaced): no reload after this. */
    fun forget() {
        val model = lock.withLock {
            forgotten = true
            val m = loaded
            loaded = null
            m
        }
        model?.close()
    }

    /** Milliseconds since the last decision ended (or since the model was opened). */
    fun idleMillis(): Long = lock.withLock { clockMillis() - lastUse }

    /**
     * The maintenance tick (every 30 seconds on the phone): under `balanced`, frees Laya once it has
     * been idle for [idleUnloadMin] minutes; under `low`, frees it whenever nothing wants the model.
     * [laneFree] is false while foreground work or the game holds the model lane.
     */
    fun maintain(mode: String, idleUnloadMin: Double, laneFree: Boolean): Boolean = when (mode) {
        EngineSettings.MEMORY_BALANCED ->
            laneFree && idleUnloadMin > 0 && idleMillis() >= (idleUnloadMin * 60_000).toLong() && unload()
        EngineSettings.MEMORY_LOW -> laneFree && unload()
        else -> false
    }

    /** After a run on the model thread: `low` frees Laya straight away. */
    fun afterRun(mode: String, laneFree: Boolean): Boolean = mode == EngineSettings.MEMORY_LOW && laneFree && unload()

    /** For Swift: [maintain] with the current settings. */
    fun maintainWith(settings: EngineSettings, laneFree: Boolean): Boolean =
        maintain(settings.memoryMode, settings.idleUnloadMin, laneFree)

    companion object {
        /** How often the host should call [maintain]: Station's "within 30 seconds". */
        const val TICK_SECONDS: Double = 30.0

        private fun defaultClock(): () -> Long {
            val start = TimeSource.Monotonic.markNow()
            return { start.elapsedNow().inWholeMilliseconds }
        }
    }
}
