package dev.loupe.game.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.loupe.game.AsyncDecider
import dev.loupe.game.BaselinePilot
import dev.loupe.game.Control
import dev.loupe.game.GameEvent
import dev.loupe.game.GameSession
import dev.loupe.game.HumanInput
import dev.loupe.game.LockstepDecider
import dev.loupe.game.ModelPilot
import dev.loupe.game.Rules
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Who flies the main view. */
enum class PilotMode(val title: String) {
    MODEL("Model (Laya)"),
    BASELINE("Baseline rules"),
    HUMAN("You"),
}

/** A short-lived explosion, in world coordinates. */
class Effect(val x: Double, val y: Double, val big: Boolean, var age: Int = 0)

/** One running game plus what only the screen needs about it. */
class Lane(val label: String, val mode: PilotMode, val session: GameSession) {
    val effects: MutableList<Effect> = mutableListOf()
    var overSinceNanos: Long? = null
}

/**
 * The game's UI state and loop, with nothing desktop-specific in it: it uses only the Compose
 * runtime, so it moves to an Android `ViewModel` unchanged. The desktop entry point supplies the
 * frame clock and the keyboard.
 *
 * The simulation runs at a fixed 60 Hz regardless of the display's frame rate: [advance]
 * accumulates real time and steps whole ticks. The model runs on its own thread
 * ([modelExecutor], shared by every run so a restart never puts two threads in the model at once),
 * so a slow decision never stalls the picture.
 */
class GameController : AutoCloseable {
    var modelStatus: ModelStatus by mutableStateOf(ModelStatus.Loading)
        private set
    var mode: PilotMode by mutableStateOf(PilotMode.BASELINE)
        private set
    var compare: Boolean by mutableStateOf(false)
        private set
    var threshold: Float by mutableStateOf(0.30f)
        private set
    var overrideEnabled: Boolean by mutableStateOf(true)
        private set
    var paused: Boolean by mutableStateOf(false)
        private set

    /** Bumped once per rendered frame; drawing reads it so the canvas repaints. */
    var frame: Long by mutableLongStateOf(0L)
        private set

    var lanes: List<Lane> by mutableStateOf(emptyList())
        private set

    private var seed: Long = 1
    private var human = HumanInput()
    private var lastFrameNanos: Long? = null
    private var accumulated = 0L
    private val modelExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "laya-pilot").apply { isDaemon = true }
    }

    init {
        restart(seed)
    }

    val modelReady: Boolean get() = modelStatus is ModelStatus.Ready

    fun onModelStatus(status: ModelStatus) {
        modelStatus = status
        // Hand the controls to the model the moment it is ready, unless the player took them.
        if (status is ModelStatus.Ready && mode == PilotMode.BASELINE) {
            mode = PilotMode.MODEL
            restart(seed)
        }
    }

    fun selectMode(newMode: PilotMode) {
        if (newMode == PilotMode.MODEL && !modelReady) return
        mode = newMode
        if (newMode != PilotMode.MODEL) compare = false
        restart(seed)
    }

    fun toggleCompare() {
        if (!modelReady) return
        compare = !compare
        if (compare) mode = PilotMode.MODEL
        restart(seed)
    }

    fun changeThreshold(value: Float) {
        threshold = value.coerceIn(0f, 1f)
        lanes.forEach { it.session.threshold = threshold.toDouble() }
    }

    fun toggleOverride() {
        overrideEnabled = !overrideEnabled
        lanes.forEach { it.session.overrideEnabled = overrideEnabled }
    }

    fun togglePause() {
        paused = !paused
        lastFrameNanos = null
    }

    fun setHuman(input: HumanInput) {
        human = input
        lanes.forEach { it.session.human = input }
    }

    fun currentHuman(): HumanInput = human

    /** A fresh run on the next seed (all lanes share it, so a comparison is like for like). */
    fun nextRun() = restart(seed + 1)

    fun restart(newSeed: Long) {
        seed = newSeed
        lanes.forEach { it.session.close() }
        lanes = if (compare && modelReady) {
            listOf(lane(PilotMode.MODEL, "Model"), lane(PilotMode.BASELINE, "Baseline"))
        } else {
            listOf(lane(mode, mode.title))
        }
        accumulated = 0
    }

    private fun lane(laneMode: PilotMode, label: String): Lane {
        val control = when (laneMode) {
            PilotMode.MODEL -> {
                val model = (modelStatus as ModelStatus.Ready).model
                Control.Piloted(AsyncDecider(ModelPilot(model.backend), modelExecutor))
            }
            // The baseline takes microseconds; computing it in step keeps it deterministic.
            PilotMode.BASELINE -> Control.Piloted(LockstepDecider(BaselinePilot(), 0))
            PilotMode.HUMAN -> Control.Human
        }
        val session = GameSession(seed, control, threshold = threshold.toDouble(), overrideEnabled = overrideEnabled)
        session.human = human
        return Lane(label, laneMode, session)
    }

    /** Called once per display frame with the frame clock. */
    fun advance(nowNanos: Long) {
        val last = lastFrameNanos
        lastFrameNanos = nowNanos
        if (last != null && !paused) {
            accumulated += (nowNanos - last).coerceAtMost(MAX_CATCH_UP_NANOS)
            while (accumulated >= TICK_NANOS) {
                lanes.forEach(::step)
                accumulated -= TICK_NANOS
            }
            autoRestart(nowNanos)
        }
        frame++
    }

    private fun step(lane: Lane) {
        val world = lane.session.world
        lane.session.tick()
        for (event in world.events) {
            when (event) {
                is GameEvent.Destroyed -> lane.effects += Effect(event.x, event.y, event.what == "bridge")
                is GameEvent.Died -> lane.effects += Effect(event.x, event.y, true)
            }
        }
        lane.effects.forEach { it.age++ }
        lane.effects.removeAll { it.age > EFFECT_TICKS }
    }

    /** Pilots start the next run by themselves after a pause; a human presses Enter. */
    private fun autoRestart(nowNanos: Long) {
        lanes.forEach { lane -> if (lane.session.world.over && lane.overSinceNanos == null) lane.overSinceNanos = nowNanos }
        val allOver = lanes.all { it.session.world.over }
        if (!allOver || lanes.any { it.mode == PilotMode.HUMAN }) return
        val since = lanes.maxOf { it.overSinceNanos ?: nowNanos }
        if (nowNanos - since > AUTO_RESTART_NANOS) nextRun()
    }

    override fun close() {
        lanes.forEach { it.session.close() }
        modelExecutor.shutdownNow()
        // A decision may still be inside the native session; let it finish before freeing it.
        modelExecutor.awaitTermination(2, TimeUnit.SECONDS)
        (modelStatus as? ModelStatus.Ready)?.model?.close()
    }

    companion object {
        const val TICK_NANOS: Long = 1_000_000_000L / Rules.TICK_HZ
        const val MAX_CATCH_UP_NANOS: Long = 100_000_000L
        const val AUTO_RESTART_NANOS: Long = 2_500_000_000L
        const val EFFECT_TICKS: Int = 24

        /** How long the override flash stays up, in ticks. */
        const val FLASH_TICKS: Long = 18
    }
}
