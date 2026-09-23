package dev.loupe.game

/**
 * Every tuning constant of the simulation, in one place.
 *
 * Units: the world is a grid [COLUMNS] wide and unbounded upward; `x` is in columns, `y` in rows,
 * time in fixed ticks of [DT] seconds. Speeds are given per second and converted per tick where
 * used, so a change of tick rate does not silently change the game.
 *
 * The numbers are this game's own. Scores in particular are deliberately *not* the values of any
 * commercial river shooter.
 */
object Rules {
    /** Fixed simulation rate. The sim never reads a wall clock; a tick is a tick. */
    const val TICK_HZ: Int = 60
    const val DT: Double = 1.0 / TICK_HZ

    /** Width of the world in columns. */
    const val COLUMNS: Int = 28
    const val HALF: Int = COLUMNS / 2

    /** Rows visible on screen, and therefore the furthest anything is spawned ahead. */
    const val VIEW_ROWS: Int = 32

    /** The player's box sits this many rows above the bottom of the view. */
    const val PLAYER_ROW: Double = 3.0
    const val PLAYER_W: Double = 1.5
    const val PLAYER_H: Double = 1.5

    /** Forward speed, rows per second. */
    const val SCROLL: Double = 7.0

    /**
     * Sideways speed, columns per second: twice the scroll, so the plane can always cross two
     * columns per row — faster than any wall is allowed to move (see [RiverGenerator]).
     */
    const val LATERAL: Double = 14.0

    const val BULLET_SPEED: Double = 45.0
    const val BULLET_W: Double = 0.3
    const val BULLET_H: Double = 0.8
    const val FIRE_COOLDOWN_TICKS: Int = 12

    const val FUEL_MAX: Double = 100.0
    const val FUEL_DRAIN_PER_S: Double = 2.5
    const val FUEL_REFILL_PER_S: Double = 60.0

    /** Below this the plane is low on fuel: depots stop being targets and start being lifelines. */
    const val FUEL_LOW: Double = 35.0

    /** Enemies sit still until the plane is this many rows away. */
    const val ACTIVATION_ROWS: Double = 14.0

    const val SCORE_BOAT: Int = 20
    const val SCORE_HELI: Int = 40
    const val SCORE_DEPOT: Int = 30
    const val SCORE_BRIDGE: Int = 150

    /** No enemies or depots below this row, so every run starts in open water. */
    const val SAFE_START_ROWS: Int = 24
}
