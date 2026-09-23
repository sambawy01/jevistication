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
        val started = System.nanoTime()
        val preferred = Action.of(steerToward(target(observation)), shouldFire(observation))
        val action = closestLegal(preferred, observation.legal.actions)
        return PilotDecision(action, DecisionSource.BASELINE, latencyNanos = System.nanoTime() - started, observedTick = observation.tick)
    }

    /**
     * Columns to move, right positive. Every distance ahead below is in section-1 rows, stretched
     * by [pace] so it means the same time at the section's speed; in section 1 the rules are
     * exactly the original ones.
     */
    private fun target(o: Observation): Double {
        val k = pace(o)
        if ((o.landAheadRows ?: Int.MAX_VALUE) <= 2 * k) return escapeLand(o)
        o.depot?.let { d ->
            val thirsty = o.fuelPercent < 60 || (o.fuelPercent < 90 && d.ahead < 10 * k)
            val reachable = abs(d.across) <= d.ahead * o.reach - 0.5
            if (thirsty && reachable) return d.across
        }
        // Too close to shoot in time and nearly in line: get out of the way.
        o.threats.firstOrNull { it.ahead < 3.0 * k && abs(it.across) < 2.5 }?.let { return if (it.across > 0) it.across - 3.5 else it.across + 3.5 }
        o.threats.firstOrNull { it.ahead in 3.0 * k..10.0 * k }?.let { return it.across }
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
        val k = pace(o)
        val enemyInLine = o.threats.any { abs(it.across) < 1.2 && it.ahead < 14 * k }
        val bridgeClose = (o.bridgeAheadRows ?: Int.MAX_VALUE) < 12 * k
        return enemyInLine || bridgeClose
    }

    private companion object {
        const val DEADBAND = 0.4

        /** How much faster than section 1 the river is running: 1 there. */
        fun pace(o: Observation): Double = o.scroll / Rules.SCROLL
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
 * The model pilot: one Laya `Choice` per decision, over the legal actions only.
 *
 * **One question, not two.** Steering and firing are asked together as up to six labels rather than
 * as a steering question and a firing question, because Laya scores every option at its own marker
 * in one forward pass: six options cost one call (~45 ms on a desktop CPU), two questions would cost
 * two. The candidate set is state-dependent — [Mechanics.legalActions] has already removed what
 * would crash — and it always includes the explicit no-op, "hold course", unless holding course is
 * itself fatal.
 *
 * **Mechanical first.** When only one action is legal the model is not asked at all.
 *
 * **Failure posture: null action.** A backend that throws, or returns anything that fails the
 * engine's strict validation (unknown or missing labels, NaN, masses that do not normalise), yields
 * [Action.HOLD] with the failure recorded — never an exception into the game loop. The safety
 * override still stands behind that hold.
 *
 * The probabilities it reports are the model's raw output; see [PilotDecision.raw].
 */
class ModelPilot(
    private val backend: Backend,
    val question: String = QUESTION,
) : Pilot {
    override val name: String = "model"

    override fun decide(observation: Observation): PilotDecision {
        val legal = observation.legal.actions
        if (legal.size == 1) {
            return PilotDecision(legal.single(), DecisionSource.MECHANICAL, observedTick = observation.tick)
        }
        val judgment = Judgment.Choice(
            id = JUDGMENT_ID,
            question = question,
            candidates = legal.map { it.label },
            onFailure = FailurePosture.NULL_ACTION,
        )
        val state = TextState.build(listOf("river" to StateText.describe(observation)), STATE_BUDGET)
        val started = System.nanoTime()
        val validated = runCatching { judgment.validate(backend.score(judgment, state)) }
        val latency = System.nanoTime() - started
        return validated.fold(
            onSuccess = { distribution ->
                val raw = legal.associateWith { distribution.getValue(it.label).value }
                val chosen = Action.ofLabel(distribution.argmax) ?: Action.HOLD
                PilotDecision(chosen, DecisionSource.MODEL, raw, latencyNanos = latency, observedTick = observation.tick)
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

    companion object {
        const val JUDGMENT_ID: String = "game.river.move"
        const val QUESTION: String =
            "A plane flies up a river. Which move keeps it off the banks and away from enemies, " +
                "shoots targets in line, and reaches fuel when fuel is low?"

        /** Characters of state; [StateText] stays well inside it, so it is never cut. */
        const val STATE_BUDGET: Int = 600
    }
}
