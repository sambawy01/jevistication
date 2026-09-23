package dev.loupe.game

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * How hard one section of river is. Each bridge ends a section: rows `[0, 140)` are section 1, the
 * bridge on row 140 starts section 2, and so on. The plane flies at the speed of the section its
 * bottom edge is in, and the generator shapes and populates each row with the parameters of the
 * section that row is in.
 *
 * **A pure function of the section index**, so it is deterministic for a seed by construction: the
 * seed decides the river's shape and spawns, the row index decides how hard they are.
 *
 * Every parameter ramps linearly from section 1 — which is exactly the game as it was before
 * sections existed — to [CAP_SECTION], and holds there. The caps are what keep the game possible;
 * the two parameters that decide *whether* the river can be flown at all, [wallStep] and
 * [minChannel], are not tuned but **derived** from the speed, below.
 *
 * ## Why the river stays passable at any speed
 *
 * The plane crosses [reach] = `LATERAL / scroll` columns while the river scrolls one row. A wall
 * that moves `s` columns per row can be out-steered only while `s < reach`; as the scroll rises the
 * reach falls, so a fixed wall step would eventually outrun the plane. So the average wall step is
 * derived: [wallStep] = `min(1, WALL_TRACK × reach)`, and no wall ever moves more than one column in
 * a single row (the generator spends a per-row budget, see [RiverGenerator]). With the scroll capped
 * at [SCROLL_CAP], `reach ≥ 1.2`, so even that single one-column step is inside one row's reach with
 * margin to spare.
 *
 * The plane's box is 1.5 rows tall, so it sits over up to three rows at once, and a channel that
 * slides one column per row loses two columns across those three rows while the plane's own sweep
 * that row is `PLAYER_W + 1`. The channel therefore needs `PLAYER_W + 1 + 2` = 4.5 columns, rounded
 * up to whole columns: [minChannel] is **5 in every section**, and it cannot go lower without the
 * walls slowing to less than one column per two rows. Channels still get narrower as the game goes
 * on — the channels either side of an island ([islandGap]) and the single channel between the
 * widest banks ([maxBank]) close in towards that floor — but the floor itself is a proof, not a knob.
 *
 * Tests walk the river on many seeds through every section to past the cap and prove a path exists
 * with the plane's real footprint and 90% of its real reach.
 */
data class Difficulty(
    val section: Int,
    /** Forward speed, rows per second. */
    val scroll: Double,
    /** Most columns a wall may move per row, on average. Derived from the speed; at most 1. */
    val wallStep: Double,
    /** Narrowest any channel may ever be, in columns. Derived; see the class notes. */
    val minChannel: Int,
    /** Widest a bank may grow, in columns: the single channel is never narrower than `COLUMNS - 2 × maxBank`. */
    val maxBank: Int,
    /** The water left either side of an island: an island rises until each channel beside it is this wide. */
    val islandGap: Int,
    /** Perlin frequency of the banks down the river: higher changes shape more often. */
    val frequency: Double,
    /** Furthest the whole river may drift sideways from the centre line, in columns. */
    val meander: Int,
    /** Chance an enemy spawns on an eligible row (every [RiverGenerator.ENEMY_EVERY] rows). */
    val enemyChance: Double,
    val heliShare: Double,
    val movingBoatShare: Double,
    /** Multiplier on every enemy's sideways speed. */
    val enemySpeed: Double,
    /** Rows between fuel depots, drawn uniformly from `[depotGapMin, depotGapMax]`. */
    val depotGapMin: Int,
    val depotGapMax: Int,
    val fuelDrainPerS: Double,
    /** Scaled with the scroll, so one pass over a depot gives the same fuel at any speed. */
    val fuelRefillPerS: Double,
) {
    /** Columns the plane can cross while the river scrolls one row. */
    val reach: Double get() = Rules.LATERAL / scroll

    /** Fuel burned per row flown, percent of a full tank. */
    val fuelPerRow: Double get() = fuelDrainPerS / scroll

    companion object {
        /** Rows per section: one bridge ends each. */
        const val SECTION_ROWS: Int = RiverGenerator.BRIDGE_EVERY

        /** The section at which every parameter reaches its cap and stops. */
        const val CAP_SECTION: Int = 10

        const val SCROLL_CAP: Double = 11.5

        /** Shape of the climb from section 1 to the cap: 1 would be linear. */
        const val RAMP_EXPONENT: Double = 2.0

        /**
         * The share of the plane's reach a wall may use. 0.7 leaves the plane 30% of its sideways
         * speed spare while it follows a wall — for dodging, and for the latency of a decision.
         */
        const val WALL_TRACK: Double = 0.7

        /** Section 1's fuel burn per row (2.5/s at 7 rows/s), and the cap's: 12% more per row. */
        private const val BURN_GROWTH: Double = 1.12

        /** Section 1's rows per depot scaled up to this at the cap: 40–65 becomes 56–91. */
        private const val DEPOT_GAP_GROWTH: Double = 1.4

        /** The section containing row [row]. Row 0 is in section 1; each bridge row starts the next. */
        fun sectionOfRow(row: Int): Int = 1 + row.coerceAtLeast(0) / SECTION_ROWS

        /** The first row of [section]: the bridge that starts it (row 0 for section 1). */
        fun firstRow(section: Int): Int = (section.coerceAtLeast(1) - 1) * SECTION_ROWS

        /** The difficulty at world height [y]. */
        fun at(y: Double): Difficulty = forRow(kotlin.math.floor(y).toInt())

        fun forRow(row: Int): Difficulty = of(sectionOfRow(row))

        private val table: Array<Difficulty> = Array(CAP_SECTION) { build(it + 1) }

        fun of(section: Int): Difficulty {
            require(section >= 1) { "sections start at 1, was $section" }
            return if (section <= CAP_SECTION) table[section - 1] else table[CAP_SECTION - 1].copy(section = section)
        }

        private fun build(section: Int): Difficulty {
            // 0 at section 1, 1 at the cap; eased in, so the first sections stay close to the
            // original game and the steep part of the climb is sections 5–10.
            // StrictMath: the same bits on every JVM, so a seed replays identically everywhere.
            val t = StrictMath.pow((section - 1).toDouble() / (CAP_SECTION - 1), RAMP_EXPONENT)
            fun lerp(a: Double, b: Double): Double = a + (b - a) * t
            fun lerpInt(a: Int, b: Int): Int = lerp(a.toDouble(), b.toDouble()).roundToInt()

            val scroll = lerp(Rules.SCROLL, SCROLL_CAP)
            val reach = Rules.LATERAL / scroll
            val gapScale = lerp(1.0, DEPOT_GAP_GROWTH)
            return Difficulty(
                section = section,
                scroll = scroll,
                wallStep = minOf(1.0, WALL_TRACK * reach),
                minChannel = ceil(Rules.PLAYER_W + 3.0).toInt(),
                maxBank = lerpInt(RiverGenerator.MAX_BANK, 10),
                islandGap = lerpInt(RiverGenerator.ISLAND_GAP, 5),
                frequency = lerp(RiverGenerator.FREQUENCY, 0.10),
                meander = lerpInt(0, 4),
                enemyChance = lerp(RiverGenerator.ENEMY_CHANCE, 0.6),
                heliShare = lerp(RiverGenerator.HELI_SHARE, 0.55),
                movingBoatShare = lerp(RiverGenerator.MOVING_BOAT_SHARE, 0.9),
                enemySpeed = lerp(1.0, 1.6),
                depotGapMin = (RiverGenerator.DEPOT_GAP_MIN * gapScale).roundToInt(),
                depotGapMax = (RiverGenerator.DEPOT_GAP_MAX * gapScale).roundToInt(),
                // Per second, the burn rises with the speed (more river per second) and a little
                // more on top; section 1 is exactly the original 2.5/s.
                fuelDrainPerS = Rules.FUEL_DRAIN_PER_S * (scroll / Rules.SCROLL) * lerp(1.0, BURN_GROWTH),
                fuelRefillPerS = Rules.FUEL_REFILL_PER_S * (scroll / Rules.SCROLL),
            )
        }
    }
}
