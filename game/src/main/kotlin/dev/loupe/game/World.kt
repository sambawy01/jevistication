package dev.loupe.game

import kotlin.math.floor

/**
 * The whole simulation state, advanced one fixed tick at a time by [step].
 *
 * **Deterministic by construction.** The only randomness is the river's, drawn from the seed in row
 * order; nothing here reads a clock, and nothing iterates a hash-ordered collection. The same seed
 * and the same action sequence give bit-identical worlds, which is what makes a model-vs-baseline
 * comparison on one seed meaningful and what the look-ahead in [Mechanics] relies on.
 *
 * [copy] is a deep copy of everything mutable (the river is shared: its rows never change once
 * generated). That is how the legal-action check and the safety override ask "what if" without
 * disturbing the real game.
 */
class World private constructor(
    val river: River,
    tick: Long,
    cameraY: Double,
    playerX: Double,
    fuel: Double,
    score: Int,
    cooldown: Int,
    private var spawnedThrough: Int,
    private val enemiesList: MutableList<Enemy>,
    private val depotsList: MutableList<Depot>,
    private val bridgesList: MutableList<Bridge>,
    private val bulletsList: MutableList<Bullet>,
    val tally: Tally,
) {
    /**
     * A new run on the river of [seed]. From [startSection] above 1, the run begins just past the
     * bridge that opens that section, as if that bridge had just been shot: same river, same
     * spawns, only the start moves. Section 1 is the ordinary start.
     */
    constructor(seed: Long, startSection: Int = 1) : this(
        river = River(seed),
        tick = 0,
        cameraY = startCamera(startSection),
        playerX = Rules.HALF.toDouble(),
        fuel = Rules.FUEL_MAX,
        score = 0,
        cooldown = 0,
        // Past the start bridge: it and everything below it is behind the plane.
        spawnedThrough = if (startSection <= 1) -1 else Difficulty.firstRow(startSection),
        enemiesList = mutableListOf(),
        depotsList = mutableListOf(),
        bridgesList = mutableListOf(),
        bulletsList = mutableListOf(),
        tally = Tally(),
    ) {
        spawnAhead()
    }

    /** Running totals for a run. */
    data class Tally(
        var kills: Int = 0,
        var depotsShot: Int = 0,
        var bridges: Int = 0,
        var refuelTicks: Int = 0,
        var shotsFired: Int = 0,
    )

    var tick: Long = tick; private set

    /** Bottom of the view, in rows. Also the distance flown. */
    var cameraY: Double = cameraY; private set
    var playerX: Double = playerX; private set
    var fuel: Double = fuel; private set
    var score: Int = score; private set

    /** Ticks until the gun can fire again. */
    var cooldown: Int = cooldown; private set

    /** Null while flying; the reason once the run is over. */
    var death: DeathCause? = null; private set

    val over: Boolean get() = death != null
    val playerY: Double get() = cameraY + Rules.PLAYER_ROW

    /** How hard the river is where the plane is: the section its bottom edge is in. */
    val difficulty: Difficulty get() = Difficulty.at(playerY)
    val section: Int get() = difficulty.section
    val weaponReady: Boolean get() = cooldown == 0

    val enemies: List<Enemy> get() = enemiesList
    val depots: List<Depot> get() = depotsList
    val bridges: List<Bridge> get() = bridgesList
    val bullets: List<Bullet> get() = bulletsList

    /** What happened during the most recent [step]. */
    val events: List<GameEvent> get() = eventsList
    private val eventsList = mutableListOf<GameEvent>()

    /** The plane's box. */
    val player: Box
        get() = object : Box {
            override val x = playerX
            override val y = playerY
            override val width = Rules.PLAYER_W
            override val height = Rules.PLAYER_H
        }

    /** Advances one tick with [action] held. Does nothing once the run is over. */
    fun step(action: Action) {
        eventsList.clear()
        if (over) return
        tick++

        playerX = (playerX + action.steer * Rules.LATERAL * Rules.DT)
            .coerceIn(Rules.PLAYER_W / 2, Rules.COLUMNS - Rules.PLAYER_W / 2)
        if (cooldown > 0) cooldown--
        if (action.fire && cooldown == 0) {
            bulletsList += Bullet(playerX, playerY + Rules.PLAYER_H)
            cooldown = Rules.FIRE_COOLDOWN_TICKS
            tally.shotsFired++
        }

        // The section's speed at the start of the tick; passing a bridge speeds up the next tick.
        val difficulty = difficulty
        cameraY += difficulty.scroll * Rules.DT
        spawnAhead()
        moveEnemies(difficulty)
        moveBullets()
        burnFuel(difficulty)
        collide()
        cull()
    }

    fun copy(): World = World(
        river = river,
        tick = tick,
        cameraY = cameraY,
        playerX = playerX,
        fuel = fuel,
        score = score,
        cooldown = cooldown,
        spawnedThrough = spawnedThrough,
        enemiesList = enemiesList.mapTo(mutableListOf()) { it.copy() },
        depotsList = depotsList.mapTo(mutableListOf()) { it.copy() },
        bridgesList = bridgesList.mapTo(mutableListOf()) { it.copy() },
        bulletsList = bulletsList.mapTo(mutableListOf()) { it.copy() },
        tally = tally.copy(),
    ).also { it.death = death }

    /**
     * A fingerprint of the complete state, for determinism checks. Two worlds with the same
     * fingerprint are, for every purpose the game has, the same world.
     */
    fun fingerprint(): String = buildString {
        append(tick).append('|').append(cameraY).append('|').append(playerX).append('|')
        append(fuel).append('|').append(score).append('|').append(cooldown).append('|').append(death)
        enemiesList.forEach { append("|e").append(it.kind).append(it.x).append(',').append(it.y).append(it.alive) }
        depotsList.forEach { append("|d").append(it.x).append(',').append(it.y).append(it.alive) }
        bridgesList.forEach { append("|b").append(it.row).append(it.alive) }
        bulletsList.forEach { append("|u").append(it.x).append(',').append(it.y) }
    }

    /**
     * Scenario hooks for tests: put the plane or an entity exactly where a test needs it. Internal,
     * so no pilot or UI can reach them.
     */
    internal fun placePlayer(x: Double) {
        playerX = x
    }

    internal fun setFuel(value: Double) {
        fuel = value
    }

    internal fun addEnemy(enemy: Enemy) {
        enemiesList += enemy
    }

    internal fun addDepot(depot: Depot) {
        depotsList += depot
    }

    internal fun clearEntities(keepBridges: Boolean = false) {
        enemiesList.clear()
        depotsList.clear()
        if (!keepBridges) bridgesList.clear()
    }

    private fun spawnAhead() {
        val through = floor(cameraY).toInt() + Rules.VIEW_ROWS + 1
        while (spawnedThrough < through) {
            spawnedThrough++
            val row = river.row(spawnedThrough)
            val y = row.index.toDouble()
            if (row.bridge) {
                bridgesList += Bridge(row.index, row.left.toDouble(), (Rules.COLUMNS - row.right).toDouble())
            }
            for (spawn in row.spawns) {
                when (spawn) {
                    is Spawn.Enemy -> enemiesList += Enemy(spawn.kind, spawn.x, y + 0.05, spawn.vx)
                    is Spawn.Depot -> depotsList += Depot(spawn.x, y)
                }
            }
        }
    }

    private fun moveEnemies(difficulty: Difficulty) {
        // Enemies wake the same *time* ahead of the plane in every section: further up the river
        // when it runs faster (exactly ACTIVATION_ROWS in section 1).
        val activation = Rules.ACTIVATION_ROWS * difficulty.scroll / Rules.SCROLL
        for (enemy in enemiesList) {
            if (!enemy.alive || enemy.vx == 0.0) continue
            if (enemy.y - playerY > activation) continue
            val nextX = enemy.x + enemy.vx * Rules.DT
            val row = river.rowAt(enemy.y)
            if (row.landIn(nextX - enemy.width / 2, nextX + enemy.width / 2)) {
                enemy.vx = -enemy.vx
            } else {
                enemy.x = nextX
            }
        }
    }

    private fun moveBullets() {
        val iterator = bulletsList.iterator()
        while (iterator.hasNext()) {
            val bullet = iterator.next()
            bullet.y += Rules.BULLET_SPEED * Rules.DT
            if (bullet.y > cameraY + Rules.VIEW_ROWS || hitSomething(bullet)) iterator.remove()
        }
    }

    /** Destroys the first live target [bullet] touches, scoring it. True when it hit. */
    private fun hitSomething(bullet: Bullet): Boolean {
        enemiesList.firstOrNull { it.alive && it.overlaps(bullet) }?.let { enemy ->
            enemy.alive = false
            award(enemy.kind.word, enemy.x, enemy.y, enemy.kind.points)
            tally.kills++
            return true
        }
        depotsList.firstOrNull { it.alive && it.overlaps(bullet) }?.let { depot ->
            depot.alive = false
            award("depot", depot.x, depot.y, Rules.SCORE_DEPOT)
            tally.depotsShot++
            return true
        }
        bridgesList.firstOrNull { it.alive && it.overlaps(bullet) }?.let { bridge ->
            bridge.alive = false
            award("bridge", bullet.x, bridge.y, Rules.SCORE_BRIDGE)
            tally.bridges++
            return true
        }
        return false
    }

    private fun award(what: String, x: Double, y: Double, points: Int) {
        score += points
        eventsList += GameEvent.Destroyed(what, x, y, points)
    }

    private fun burnFuel(difficulty: Difficulty) {
        val plane = player
        if (depotsList.any { it.alive && it.overlaps(plane) }) {
            fuel = (fuel + difficulty.fuelRefillPerS * Rules.DT).coerceAtMost(Rules.FUEL_MAX)
            tally.refuelTicks++
        } else {
            fuel = (fuel - difficulty.fuelDrainPerS * Rules.DT).coerceAtLeast(0.0)
        }
    }

    private fun collide() {
        val plane = player
        val x0 = plane.x - plane.width / 2
        val x1 = plane.x + plane.width / 2
        val cause = when {
            rowsUnder(plane).any { it.landIn(x0, x1) } -> DeathCause.BANK
            enemiesList.any { it.alive && it.overlaps(plane) } -> DeathCause.ENEMY
            bridgesList.any { it.alive && it.overlaps(plane) } -> DeathCause.BRIDGE
            fuel <= 0.0 -> DeathCause.FUEL
            else -> null
        }
        if (cause != null) {
            death = cause
            eventsList += GameEvent.Died(cause, playerX, playerY)
        }
    }

    private companion object {
        /** The camera for a run starting in [section]: the plane's bottom edge just past its bridge. */
        fun startCamera(section: Int): Double =
            if (section <= 1) 0.0 else Difficulty.firstRow(section) + 1.0 - Rules.PLAYER_ROW
    }

    private fun rowsUnder(box: Box): List<Row> {
        val first = floor(box.y).toInt()
        val last = floor(box.y + box.height - 1e-9).toInt()
        return (first..last).map { river.row(it) }
    }

    private fun cull() {
        val bottom = cameraY - 2
        enemiesList.removeAll { !it.alive || it.y + it.height < bottom }
        depotsList.removeAll { it.y + it.height < bottom }
        bridgesList.removeAll { it.y + it.height < bottom }
    }
}
