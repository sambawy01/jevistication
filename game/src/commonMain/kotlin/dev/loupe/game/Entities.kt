package dev.loupe.game

/**
 * One of the six things the plane can do in a tick: steer left, hold or steer right, each with or
 * without firing. [label] is the exact text the model scores, so it is plain words, short, and
 * never changes without changing [Judgment criteria hash][dev.loupe.engine.Judgment.criteriaHash].
 *
 * [HOLD] is the explicit no-op the research asks for in every choice set: without one, a model
 * that has no view is forced to act.
 */
enum class Action(val steer: Int, val fire: Boolean, val label: String) {
    LEFT(-1, false, "steer left"),
    LEFT_FIRE(-1, true, "steer left and shoot"),
    HOLD(0, false, "hold course"),
    HOLD_FIRE(0, true, "hold course and shoot"),
    RIGHT(1, false, "steer right"),
    RIGHT_FIRE(1, true, "steer right and shoot"),
    ;

    /** The same steering with firing switched on or off. */
    fun withFire(fire: Boolean): Action = of(steer, fire)

    companion object {
        fun of(steer: Int, fire: Boolean): Action =
            entries.first { it.steer == steer.coerceIn(-1, 1) && it.fire == fire }

        fun ofLabel(label: String): Action? = entries.firstOrNull { it.label == label }
    }
}

enum class EnemyKind(val width: Double, val height: Double, val speed: Double, val points: Int, val word: String) {
    BOAT(2.2, 0.9, 2.5, Rules.SCORE_BOAT, "boat"),
    HELI(1.6, 1.0, 4.5, Rules.SCORE_HELI, "heli"),
}

/** Why a run ended. */
enum class DeathCause { BANK, ENEMY, BRIDGE, FUEL }

/** An axis-aligned box: `x` is the centre column, `y` the bottom row edge. */
interface Box {
    val x: Double
    val y: Double
    val width: Double
    val height: Double

    fun overlaps(other: Box): Boolean = overlaps(other.x - other.width / 2, other.x + other.width / 2, other.y, other.y + other.height)

    fun overlaps(x0: Double, x1: Double, y0: Double, y1: Double): Boolean =
        x - width / 2 < x1 && x + width / 2 > x0 && y < y1 && y + height > y0
}

data class Enemy(
    val kind: EnemyKind,
    override var x: Double,
    override val y: Double,
    var vx: Double,
    var alive: Boolean = true,
) : Box {
    override val width: Double get() = kind.width
    override val height: Double get() = kind.height
}

data class Depot(override val x: Double, override val y: Double, var alive: Boolean = true) : Box {
    override val width: Double get() = WIDTH
    override val height: Double get() = HEIGHT

    companion object {
        const val WIDTH: Double = 1.4
        const val HEIGHT: Double = 2.4
    }
}

/** A bridge across the whole river at one row. The plane cannot pass under an intact one. */
data class Bridge(val row: Int, val from: Double, val to: Double, var alive: Boolean = true) : Box {
    override val x: Double get() = (from + to) / 2
    override val y: Double get() = row.toDouble()
    override val width: Double get() = to - from
    override val height: Double get() = 1.0
}

data class Bullet(override val x: Double, override var y: Double) : Box {
    override val width: Double get() = Rules.BULLET_W
    override val height: Double get() = Rules.BULLET_H
}

/** Something that happened during one tick, for the renderer and the tallies. */
sealed interface GameEvent {
    data class Destroyed(val what: String, val x: Double, val y: Double, val points: Int) : GameEvent
    data class Died(val cause: DeathCause, val x: Double, val y: Double) : GameEvent

    /** The camera crossed into [level] at river row [row]. */
    data class LevelUp(val level: Int, val row: Int) : GameEvent
}
