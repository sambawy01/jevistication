package dev.loupe.game

import dev.loupe.engine.Backend
import dev.loupe.engine.FailurePosture
import dev.loupe.engine.Judgment
import dev.loupe.engine.TextState
import kotlin.math.abs

/** Who, or what, produced a decision. */
enum class DecisionSource {
    /** The model chose among two or more legal actions. */
    MODEL,

    /** Only one action was legal, so no one was asked: mechanics answered. */
    MECHANICAL,

    /** The scripted baseline autopilot. */
    BASELINE,

    /** The model's answer was unusable (it threw, or returned junk); the null action was flown. */
    FAILURE,
}

/**
 * One pilot decision.
 *
 * [raw] is the model's probability over the candidates it was offered, **exactly as the model
 * returned it**. It is not calibrated — Laya ships with temperature `[1, 1, 1]` and its card calls it
 * over-confident, and no calibration has been fitted for this judgment — so nothing downstream may
 * call these numbers calibrated. It is null when no model was consulted.
 *
 * [adjusted] is what [ModelPilot] actually chooses by: [raw] divided by the model's own answer to
 * the same options when every one of them reads the same, renormalised (see [ModelPilot]). Still
 * not a calibrated probability. Null when no model was asked or no adjustment was made.
 */
data class PilotDecision(
    val action: Action,
    val source: DecisionSource,
    val raw: Map<Action, Double>? = null,
    val failure: String? = null,
    /** Wall-clock time the pilot spent deciding. */
    val latencyNanos: Long = 0,
    /** The tick whose observation this decision answered. */
    val observedTick: Long = 0,
    val adjusted: Map<Action, Double>? = null,
) {
    /** The largest raw mass, or null when no model was asked. */
    val topProbability: Double? get() = raw?.values?.maxOrNull()
}

/**
 * A pilot: given what can be seen, pick one of the legal actions.
 *
 * Pilots may be slow and run off the simulation thread, so they see only an [Observation]. A pilot
 * must return an action from [Observation.legal]; the session checks, and treats anything else as a
 * failure.
 */
interface Pilot {
    val name: String

    fun decide(observation: Observation): PilotDecision
}

/**
 * The dumb baseline: a few lines of hand-written rules, no model.
 *
 * It is the thing the model has to beat (the spec's "baseline check", and proving milestone 6), and
 * because it is cheap and deterministic it can fly thousands of runs to label states for a later
 * fine-tune. It deliberately does nothing clever: head for fuel when low, line up on the nearest
 * enemy, otherwise fly the middle of the channel ahead; shoot when something is in line.
 */
class BaselinePilot : Pilot {
    override val name: String = "baseline"

    override fun decide(observation: Observation): PilotDecision {
        val started = GameClock.nanoTime()
        val preferred = Action.of(steerToward(target(observation)), shouldFire(observation))
        val action = closestLegal(preferred, observation.legal.actions)
        return PilotDecision(action, DecisionSource.BASELINE, latencyNanos = GameClock.nanoTime() - started, observedTick = observation.tick)
    }

    /** Columns to move, right positive. */
    private fun target(o: Observation): Double {
        if ((o.landAheadRows ?: Int.MAX_VALUE) <= 2) return escapeLand(o)
        o.depot?.let { d ->
            val thirsty = o.fuelPercent < 60 || (o.fuelPercent < 90 && d.ahead < 10)
            val reachable = abs(d.across) <= d.ahead * 2 - 0.5
            if (thirsty && reachable) return d.across
        }
        // Too close to shoot in time and nearly in line: get out of the way.
        o.threats.firstOrNull { it.ahead < 3.0 && abs(it.across) < 2.5 }?.let { return if (it.across > 0) it.across - 3.5 else it.across + 3.5 }
        o.threats.firstOrNull { it.ahead in 3.0..10.0 }?.let { return it.across }
        val channel = o.farChannels.minByOrNull { abs(it.center) } ?: return (o.waterRight - o.waterLeft) / 2
        return if (o.landAheadRows != null) channel.center else (channel.center + (o.waterRight - o.waterLeft) / 2) / 2
    }

    /** Land is about to be under the plane: head for the nearest channel far ahead. */
    private fun escapeLand(o: Observation): Double =
        o.farChannels.minByOrNull { abs(it.center) }?.center ?: ((o.waterRight - o.waterLeft) / 2)

    private fun steerToward(dx: Double): Int = when {
        dx < -DEADBAND -> -1
        dx > DEADBAND -> 1
        else -> 0
    }

    private fun shouldFire(o: Observation): Boolean {
        if (!o.weaponReady) return false
        val depotInLine = o.depot?.let { abs(it.across) < 1.0 } ?: false
        if (depotInLine && o.fuelPercent < 80) return false
        val enemyInLine = o.threats.any { abs(it.across) < 1.2 && it.ahead < 14 }
        val bridgeClose = (o.bridgeAheadRows ?: Int.MAX_VALUE) < 12
        return enemyInLine || bridgeClose
    }

    private companion object {
        const val DEADBAND = 0.4
    }
}

/**
 * The legal action nearest to [preferred]: same steering first (gun as preferred, then the
 * other), then one step of steering away, then two.
 */
fun closestLegal(preferred: Action, legal: List<Action>): Action {
    if (preferred in legal) return preferred
    return legal.minWith(
        compareBy<Action>({ abs(it.steer - preferred.steer) }, { if (it.fire == preferred.fire) 0 else 1 }),
    )
}

/**
 * The model pilot: one Laya `Choice` per decision — **which way to fly** — over at most three ways.
 *
 * **Rules only remove.** Before the model is asked, [gates] narrows the legal set
 * ([Mechanics.legalActions], which has already removed every move that crashes):
 *
 * - the fuel gates ([FuelGate.SHIPPED]): low on fuel with a depot in reach, only the ways toward it
 *   ([fuelFocus]); and a way the fuel projection says runs dry goes when another way reaches fuel
 *   ([dryGate]) — running out of fuel is a crash too;
 * - the gun gate ([fireGate]): of each way's two gun settings, the pointless or harmful one goes —
 *   firing with nothing in line (it wastes the reload), holding fire at a target in line, firing
 *   at a depot in line. A way is never removed by it: when its preferred setting is not legal, the
 *   other is kept.
 *
 * What is left is at most one move per way — left, straight, right — and **the model picks the
 * way**. When one move is left the model is not asked at all ([DecisionSource.MECHANICAL]).
 *
 * **The question is small, and the scene is words.** Measured 2026-09-25 (docs/BUILD.md), the
 * six-way "which move keeps it off the banks, shoots targets and reaches fuel" question over a
 * numeric scene was answered near chance: half the model's answers were within 0.1 of the next,
 * a mirrored scene was steered the mirrored way only 74% of the time, and it fired on half its
 * decisions, shooting its own fuel. Now each way carries a short description of what happens if
 * the plane flies it ([PathText], from the motion-aware [Prediction] in the observation's
 * [PathAhead]: "crash: heli crossing in, 0.5 s", "safe, boat moving away", "fuel that way"), and the
 * question is only "Which way is safest?" — a reading question a choice model is good at. Rules
 * still only remove; the prediction is a description, never a filter.
 *
 * **Word bias, divided out.** Laya prefers some option words whatever the scene: with every way
 * described identically it gave "right" 0.74 against "left" 0.26. So each answer is divided by
 * the model's own answer to the same options when every way reads the same (content-free
 * calibration, computed once per option set with the model itself and cached), and the pilot flies
 * the largest [PilotDecision.adjusted] share. [PilotDecision.raw] stays exactly what the model
 * returned. The first decision over a new option set also pays for its two calibration passes;
 * that time is counted in its latency. Not thread-safe: one decision at a time (every host runs
 * the pilot on one model thread).
 *
 * **Failure posture: null action.** A backend that throws, or returns anything that fails the
 * engine's strict validation (unknown or missing labels, NaN, masses that do not normalise), yields
 * [Action.HOLD] with the failure recorded — never an exception into the game loop. The safety
 * override still stands behind that hold.
 */
class ModelPilot(
    private val backend: Backend,
    val question: String = QUESTION,
    /** Characters of state (Model settings' `features.game.text_chars`; [STATE_BUDGET] by default). */
    private val stateBudget: Int = STATE_BUDGET,
    /** Which fuel rule removes ways before the model is asked ([FuelGate.SHIPPED] by default). */
    val fuelGate: FuelGate = FuelGate.SHIPPED,
) : Pilot {
    override val name: String = "model"

    /** The words for each way. Swappable only by tests, to keep earlier pilots measurable. */
    internal var words: (Observation, Int) -> String = PathText::describe

    /** The content-free descriptions the word bias is measured over; see [NEUTRAL]. */
    internal var neutralWords: List<String> = NEUTRAL

    private val priors = HashMap<List<String>, List<Double>>()

    override fun decide(observation: Observation): PilotDecision {
        val offered = gates(observation, observation.legal.actions, fuelGate)
        if (offered.size == 1) {
            return PilotDecision(offered.single(), DecisionSource.MECHANICAL, observedTick = observation.tick)
        }
        val judgment = judgment(observation, offered, question, words)
        val state = TextState.build(listOf("river" to PathText.scene(observation)), stateBudget)
        val started = GameClock.nanoTime()
        val answered = runCatching {
            val distribution = judgment.validate(backend.score(judgment, state).masses)
            val raw = judgment.candidates.map { distribution.getValue(it).value }
            raw to prior(judgment.candidates)
        }
        val latency = GameClock.nanoTime() - started
        return answered.fold(
            onSuccess = { (raw, prior) ->
                val share = raw.indices.map { raw[it] / prior[it] }
                val total = share.sum()
                val best = share.indices.maxBy { share[it] }
                PilotDecision(
                    action = offered[best],
                    source = DecisionSource.MODEL,
                    raw = offered.indices.associate { offered[it] to raw[it] },
                    latencyNanos = latency,
                    observedTick = observation.tick,
                    adjusted = offered.indices.associate { offered[it] to share[it] / total },
                )
            },
            onFailure = { failure ->
                PilotDecision(
                    action = Action.HOLD,
                    source = DecisionSource.FAILURE,
                    failure = failure.message ?: failure::class.simpleName ?: "unusable response",
                    latencyNanos = latency,
                    observedTick = observation.tick,
                )
            },
        )
    }

    /**
     * The model's answer over [labels] when the ways cannot be told apart: each described
     * identically, in each of [NEUTRAL], over [NEUTRAL_SCENE], averaged. Cached per option set.
     */
    private fun prior(labels: List<String>): List<Double> = priors.getOrPut(labels) {
        val sum = DoubleArray(labels.size)
        for (same in neutralWords) {
            val neutral = Judgment.Choice(
                id = JUDGMENT_ID,
                question = question,
                candidates = labels,
                onFailure = FailurePosture.NULL_ACTION,
                descriptions = labels.associateWith { same },
            )
            val d = neutral.validate(backend.score(neutral, TextState.build(listOf("river" to NEUTRAL_SCENE), stateBudget)).masses)
            labels.forEachIndexed { i, label -> sum[i] += d.getValue(label).value / neutralWords.size }
        }
        sum.toList()
    }

    companion object {
        /** The word for each way: `-1`, `0`, `1`. */
        fun way(steer: Int): String = when {
            steer < 0 -> "left"
            steer > 0 -> "right"
            else -> "straight"
        }

        /** The exact question the model is asked for [offered] (one move per way, as [gates] leaves them). */
        fun judgment(
            o: Observation,
            offered: List<Action>,
            question: String = QUESTION,
            words: (Observation, Int) -> String = PathText::describe,
        ): Judgment.Choice {
            val labels = offered.map { way(it.steer) }
            return Judgment.Choice(
                id = JUDGMENT_ID,
                question = question,
                candidates = labels,
                onFailure = FailurePosture.NULL_ACTION,
                descriptions = offered.associate { way(it.steer) to words(o, it.steer) },
            )
        }

        /**
         * The fuel gate: when fuel is low and a depot is reachable, only moves that steer toward it
         * (and never a shot at it once it is in line) are offered. The model zero-shot kept flying
         * past depots and running dry; this is a rule, like the safety override, and the model
         * still chooses among what is left.
         */
        fun fuelFocus(o: Observation, legal: List<Action>): List<Action> {
            val d = o.depot ?: return legal
            val thirsty = o.fuelPercent < 60 || (o.fuelPercent < 90 && d.ahead < 10)
            if (!thirsty || abs(d.across) > d.ahead * 2 - 0.5) return legal
            val want = when {
                d.across < -0.4 -> -1
                d.across > 0.4 -> 1
                else -> 0
            }
            var focused = legal.filter { it.steer == want }.ifEmpty { legal }
            if (abs(d.across) < 1.0) focused = focused.filter { !it.fire }.ifEmpty { focused }
            return focused
        }

        /**
         * The gun gate: one move per way. Firing is wanted when the gun is ready and an enemy is in
         * line within range or a bridge is close, and no fuel depot is in line; otherwise holding
         * fire is. Each way keeps its wanted gun setting when that is legal, else the other: the
         * gate removes pointless or harmful shots (and the passive twin of a needed one), never a
         * way. Measured 2026-09-25: left to the model, the gun fired on half of all decisions and
         * shot 59 depots in 20 runs; 19 of the 20 ended out of fuel.
         */
        fun fireGate(o: Observation, legal: List<Action>): List<Action> {
            val enemyInLine = o.threats.any { abs(it.across) < IN_LINE && it.ahead < FIRE_RANGE }
            val bridgeClose = (o.bridgeAheadRows ?: Int.MAX_VALUE) < BRIDGE_RANGE
            val depotInLine = o.depot?.let { abs(it.across) < IN_LINE } ?: false
            val fire = o.weaponReady && (enemyInLine || bridgeClose) && !depotInLine
            return legal.map { it.steer }.distinct().sorted().map { steer ->
                val wanted = Action.of(steer, fire)
                if (wanted in legal) wanted else Action.of(steer, !fire)
            }
        }

        /**
         * The dry gate: when the fuel projection ([FuelOutlook]) says some ways run dry and another
         * reaches fuel, the ways that run dry go. Rules only remove: it never picks among the ways
         * that reach fuel, and with no way reaching fuel it removes nothing (every way burns the same).
         */
        fun dryGate(o: Observation, legal: List<Action>): List<Action> {
            val fuel = legal.map { it.steer }.distinct().associateWith { o.path(it)?.predicted?.fuel }
            if (fuel.values.none { it?.reaches == true }) return legal
            return legal.filter { fuel[it.steer]?.runsDry != true }.ifEmpty { legal }
        }

        /** The fuel rule [gates] applies, then the gun gate. */
        fun fuelGated(o: Observation, legal: List<Action>, gate: FuelGate): List<Action> = when (gate) {
            FuelGate.THIRST -> fuelFocus(o, legal)
            FuelGate.DRY -> dryGate(o, legal)
            FuelGate.BOTH -> dryGate(o, fuelFocus(o, legal))
            FuelGate.NONE -> legal
        }

        /** Both gates, fuel first: a low tank outranks a target. */
        fun gates(o: Observation, legal: List<Action>, fuel: FuelGate = FuelGate.SHIPPED): List<Action> =
            fireGate(o, fuelGated(o, legal, fuel))

        const val JUDGMENT_ID: String = "game.river.way"
        const val QUESTION: String = "Which way is safest?"

        /** Columns either side of the plane that count as "in line" for the gun. */
        const val IN_LINE: Double = 1.2
        const val FIRE_RANGE: Double = 14.0
        const val BRIDGE_RANGE: Int = 12

        /** The content-free descriptions and scene the word bias is measured over. */
        val NEUTRAL: List<String> = listOf("safe", "crash: boat in 1 s")
        const val NEUTRAL_SCENE: String = "fuel 70%"

        /** Characters of state; [PathText.scene] stays well inside it, so it is never cut. */
        const val STATE_BUDGET: Int = 600
    }
}

/** Which fuel rule [ModelPilot.gates] applies before the model is asked. Rules only remove ways. */
enum class FuelGate {
    /** Under 60% (under 90% with the depot within 10 rows) and a depot in reach: only the ways toward it. */
    THIRST,

    /** Ways the fuel projection says run dry go, when another way reaches fuel ([ModelPilot.dryGate]). */
    DRY,

    /** [THIRST], then [DRY]. */
    BOTH,

    /** No fuel rule. */
    NONE,
    ;

    companion object {
        /** Both, measured 2026-09-26 (docs/BUILD.md): the dry gate alone was no better than the old one, both together were. */
        val SHIPPED: FuelGate = BOTH
    }
}
