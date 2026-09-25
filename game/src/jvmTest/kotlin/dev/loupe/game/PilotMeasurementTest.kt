package dev.loupe.game

import dev.loupe.backend.onnx.HuggingFaceSubwordEncoder
import dev.loupe.backend.onnx.LayaPrompt
import dev.loupe.backend.onnx.LayaSpecialTokens
import dev.loupe.backend.onnx.LayaTokenizer
import dev.loupe.backend.onnx.OnnxBackend
import dev.loupe.backend.onnx.TensorNames
import dev.loupe.engine.Backend
import dev.loupe.engine.FailurePosture
import dev.loupe.engine.Judgment
import dev.loupe.engine.TextState
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Laya flying Riverflight, measured against the baseline on the same seeds — **gated** on `models/`
 * exactly as [LayaPilotTest] is (skipped, not failed, without the weights).
 *
 * The river is the one the iPhone flies (progressive difficulty), with a charged decision latency
 * of 4 ticks (67 ms, at the phone's measured P50) for every pilot. Per run it records distance,
 * score, level, cause of death, depots shot, the ticks the safety override flew instead of the
 * pilot, and, for every model decision: the options offered, the raw margin between the top two,
 * the latency, whether the baseline would have picked the same move on the same observation, and
 * whether the override fired or the plane died in the half second after it.
 *
 * It also asks a scene-reading probe: every sampled state is mirrored left-to-right; a pilot that
 * reads the scene steers the mirror image the mirror way.
 *
 * "Before" is [LegacyModelPilot], the pilot shipped up to beb3112, kept here verbatim so the
 * comparison stays reproducible. These tests measure; they do not grade. The only assertion is that
 * the model path never fails a decision. The numbers are recorded in docs/BUILD.md (2026-09-25).
 */
class PilotMeasurementTest {

    private val modelsDir: Path = Paths.get(System.getProperty("loupe.models.dir") ?: "models")
    private val tokenizerPath = modelsDir.resolve("laya-multilingual/tokenizer/tokenizer.json")
    private val graphPath = modelsDir.resolve("laya-multilingual-onnx/laya-multilingual-choice.int8.onnx")

    private fun <T> withModel(block: (OnnxBackend) -> T): T {
        assumeTrue(Files.isRegularFile(tokenizerPath), "Laya tokenizer not present at $tokenizerPath; skipping")
        assumeTrue(Files.isRegularFile(graphPath), "Laya INT8 graph not present at $graphPath; skipping")
        return open().use { (_, backend) -> block(backend) }
    }

    /** One tokenizer and graph; the caller closes both through the returned handle. */
    private fun open(): Opened {
        val encoder = HuggingFaceSubwordEncoder.openLayaMultilingual(tokenizerPath)
        val backend = OnnxBackend.open(graphPath, LayaTokenizer(LayaPrompt(encoder, LayaSpecialTokens.MULTILINGUAL)), TensorNames.LAYA)
        return Opened(encoder, backend)
    }

    private data class Opened(val encoder: AutoCloseable, val backend: OnnxBackend) : AutoCloseable {
        override fun close() {
            backend.close()
            encoder.close()
        }
    }

    /**
     * Runs [seeds] × [pilots] episodes on [threads] threads, each thread with its own Laya. The
     * charged latency is fixed, so a run's outcome does not depend on how busy the machine is; the
     * latencies it records do, which is why latency is quoted from the sequential test.
     */
    private fun runAll(names: List<String>, threads: Int): List<Measure.Run> {
        val opened = java.util.Collections.synchronizedList(mutableListOf<Opened>())
        val local = ThreadLocal.withInitial { open().also { opened += it } }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        try {
            val jobs = names.flatMap { name ->
                seeds.map { seed ->
                    pool.submit<Measure.Run> {
                        val pilot: Pilot = when (name) {
                            "before" -> LegacyModelPilot(local.get().backend)
                            "after" -> ModelPilot(local.get().backend)
                            "gates-only" -> GatesOnlyPilot()
                            else -> BaselinePilot()
                        }
                        Measure.run(seed, pilot, Difficulty.PROGRESSIVE, seconds * Rules.TICK_HZ, 4)
                    }
                }
            }
            return jobs.map { it.get() }
        } finally {
            pool.shutdown()
            pool.awaitTermination(1, java.util.concurrent.TimeUnit.MINUTES)
            opened.forEach { it.close() }
        }
    }

    /** Prints [text] and, with `-Ploupe.game.out=<file>`, appends it there too (test XML can be lost). */
    private fun report(text: String) {
        println(text)
        System.getProperty("loupe.game.out")?.let { java.io.File(it).appendText(text + "\n") }
    }

    private val seeds: List<Long> = (System.getProperty("loupe.game.seeds")?.toInt() ?: 4).let { n -> (1L..n.toLong()).toList() }
    private val seconds: Int = System.getProperty("loupe.game.seconds")?.toInt() ?: 45

    @Test
    fun `Laya against the baseline on the phone's river`() {
        assumeTrue(Files.isRegularFile(tokenizerPath), "Laya tokenizer not present at $tokenizerPath; skipping")
        assumeTrue(Files.isRegularFile(graphPath), "Laya INT8 graph not present at $graphPath; skipping")
        val variants = (System.getProperty("loupe.game.variants") ?: "before,after").split(',').filter { it == "before" || it == "after" || it == "gates-only" }
        val threads = System.getProperty("loupe.game.threads")?.toInt() ?: 3
        val runs = runAll(variants + "baseline", threads)
        report(Measure.table(runs))
        report(Measure.summary(runs))
        assertEquals(0, runs.sumOf { r -> r.decisions.count { it.source == DecisionSource.FAILURE } }, "the model path failed")
    }

    @Test
    fun `mirror probe - does the pilot read the scene`() = withModel { backend ->
        val states = Measure.sampleStates(seeds.take(8), every = 60)
        for (pilot in listOf(LegacyModelPilot(backend), ModelPilot(backend), GatesOnlyPilot(), BaselinePilot())) {
            report(Measure.mirrorProbe(pilot, states))
        }
    }
}

/** The measuring machinery, shared by the gated tests. */
object Measure {
    /** Everything recorded about one decision. */
    data class Decision(
        val tick: Long,
        val source: DecisionSource,
        val action: Action,
        /** What the baseline would have flown on the same observation. */
        val baseline: Action,
        val offered: Int,
        val top: Double?,
        val margin: Double?,
        val latencyMs: Double,
        var overrideSoon: Boolean = false,
        var diedSoon: Boolean = false,
    )

    data class Run(
        val pilot: String,
        val seed: Long,
        val rows: Double,
        val score: Int,
        val level: Int,
        val death: DeathCause?,
        val kills: Int,
        val depotsShot: Int,
        val bridges: Int,
        val refuelTicks: Int,
        val overrideTicks: Int,
        val ticks: Long,
        val requested: Int,
        val dropped: Int,
        val lateAnswers: Int,
        val decisions: List<Decision>,
    )

    /** Ticks after a decision in which an override or a death counts as "what happened next". */
    const val SOON: Int = 30

    fun run(seed: Long, pilot: Pilot, difficulty: Difficulty, maxTicks: Int, delayTicks: Int): Run {
        val shadow = BaselinePilot()
        val log = mutableListOf<Decision>()
        val recorder = object : Pilot {
            override val name: String = pilot.name
            override fun decide(observation: Observation): PilotDecision {
                val d = pilot.decide(observation)
                val b = shadow.decide(observation).action
                val sorted = d.raw?.values?.sortedDescending()
                log += Decision(
                    tick = observation.tick, source = d.source, action = d.action, baseline = b,
                    offered = d.raw?.size ?: 1, top = sorted?.firstOrNull(),
                    margin = sorted?.let { if (it.size >= 2) it[0] - it[1] else null },
                    latencyMs = d.latencyNanos / 1e6,
                )
                return d
            }
        }
        val overrides = mutableListOf<Long>()
        var simNanos = 0L
        GameSession(seed, Control.Piloted(LockstepDecider(recorder, delayTicks)), clock = { simNanos }, difficulty = difficulty).use { s ->
            while (!s.world.over && s.world.tick < maxTicks) {
                val before = s.world.tick
                s.tick()
                simNanos += 1_000_000_000L / Rules.TICK_HZ
                if (s.lastOverride?.tick == before) overrides += before
            }
            val w = s.world
            val deathTick = if (w.over) w.tick else Long.MAX_VALUE
            var oi = 0
            for (d in log) {
                while (oi < overrides.size && overrides[oi] < d.tick) oi++
                d.overrideSoon = oi < overrides.size && overrides[oi] <= d.tick + SOON
                d.diedSoon = deathTick <= d.tick + SOON
            }
            return Run(
                pilot.name, seed, w.cameraY, w.score, w.level, w.death, w.tally.kills, w.tally.depotsShot,
                w.tally.bridges, w.tally.refuelTicks, overrides.size, w.tick, s.stats.requested, s.stats.dropped,
                s.stats.lateAnswers, log,
            )
        }
    }

    fun table(runs: List<Run>): String = buildString {
        appendLine(f("%-8s %4s %7s %6s %3s %-6s %5s %5s %4s %6s %6s %6s %6s", "pilot", "seed", "rows", "score", "lvl", "end", "kills", "depSh", "brdg", "ovrTk", "model", "differ", "margin"))
        for (r in runs) {
            val m = r.decisions.filter { it.source == DecisionSource.MODEL }
            appendLine(
                f(
                    "%-8s %4d %7.1f %6d %3d %-6s %5d %5d %4d %6d %6d %6s %6s", r.pilot, r.seed, r.rows, r.score, r.level,
                    r.death?.name ?: "alive", r.kills, r.depotsShot, r.bridges, r.overrideTicks, m.size,
                    if (m.isEmpty()) "-" else f("%.0f%%", 100.0 * m.count { it.action != it.baseline } / m.size),
                    m.mapNotNull { it.margin }.takeIf { it.isNotEmpty() }?.let { f("%.2f", it.average()) } ?: "-",
                ),
            )
        }
    }

    fun summary(runs: List<Run>): String = buildString {
        for ((pilot, group) in runs.groupBy { it.pilot }) {
            val rows = group.map { it.rows }.sorted()
            val deaths = group.mapNotNull { it.death }.groupingBy { it }.eachCount()
            val all = group.flatMap { it.decisions }
            val model = all.filter { it.source == DecisionSource.MODEL }
            val differ = model.filter { it.action != it.baseline }
            val agree = model.filter { it.action == it.baseline }
            val steerDiffer = model.filter { it.action.steer != it.baseline.steer }
            val lat = model.map { it.latencyMs }.sorted()
            appendLine("== $pilot over ${group.size} seeds")
            appendLine(
                f(
                    "  rows mean %.1f median %.1f (min %.1f max %.1f); score mean %.0f; level mean %.2f; alive %d/%d; deaths %s",
                    rows.average(), rows[rows.size / 2], rows.first(), rows.last(), group.map { it.score }.average(),
                    group.map { it.level }.average(), group.count { it.death == null }, group.size, deaths,
                ),
            )
            appendLine(
                f(
                    "  kills %d, depots shot %d, bridges %d, refuel ticks %d, override ticks %d (%.1f per 100 rows), dropped %d, late answers %d / requested %d",
                    group.sumOf { it.kills }, group.sumOf { it.depotsShot }, group.sumOf { it.bridges }, group.sumOf { it.refuelTicks },
                    group.sumOf { it.overrideTicks }, 100.0 * group.sumOf { it.overrideTicks } / group.sumOf { it.rows },
                    group.sumOf { it.dropped }, group.sumOf { it.lateAnswers }, group.sumOf { it.requested },
                ),
            )
            if (model.isNotEmpty()) {
                val offered = model.groupingBy { it.offered }.eachCount().toSortedMap()
                appendLine(
                    f(
                        "  decisions %d: model %d, mechanical %d; options offered to the model %s; mean top %.2f, mean margin %.2f, margin<0.1 %.0f%%",
                        all.size, model.size, all.count { it.source == DecisionSource.MECHANICAL }, offered,
                        model.mapNotNull { it.top }.average(), model.mapNotNull { it.margin }.average(),
                        100.0 * model.count { (it.margin ?: 1.0) < 0.1 } / model.size,
                    ),
                )
                appendLine(
                    f(
                        "  differs from baseline on %.0f%% of model decisions (steering differs %.0f%%); override within %d ticks: after differ %.1f%%, after agree %.1f%%; died within: differ %.2f%%, agree %.2f%%",
                        100.0 * differ.size / model.size, 100.0 * steerDiffer.size / model.size, SOON,
                        pct(differ.count { it.overrideSoon }, differ.size), pct(agree.count { it.overrideSoon }, agree.size),
                        pct(differ.count { it.diedSoon }, differ.size), pct(agree.count { it.diedSoon }, agree.size),
                    ),
                )
                val picks = model.groupingBy { it.action.steer }.eachCount()
                appendLine(
                    f(
                        "  model steer picks left %d / straight %d / right %d; fires %.0f%% (baseline would fire %.0f%%)",
                        picks[-1] ?: 0, picks[0] ?: 0, picks[1] ?: 0,
                        100.0 * model.count { it.action.fire } / model.size, 100.0 * model.count { it.baseline.fire } / model.size,
                    ),
                )
                appendLine(f("  latency P50 %.1f ms, P95 %.1f ms", lat[lat.size / 2], lat[((lat.size - 1) * 0.95).toInt()]))
            }
        }
    }

    /** Observations from baseline flights, every [every] ticks. */
    fun sampleStates(seeds: List<Long>, every: Int): List<Observation> {
        val out = mutableListOf<Observation>()
        for (seed in seeds) {
            GameSession(seed, Control.Piloted(LockstepDecider(BaselinePilot(), 4)), difficulty = Difficulty.PROGRESSIVE).use { s ->
                while (!s.world.over && s.world.tick < 90 * Rules.TICK_HZ) {
                    if (s.world.tick % every == 0L) out += Observation.of(s.world, Mechanics.legalActions(s.world))
                    s.tick()
                }
            }
        }
        return out
    }

    /** The same scene, left for right. */
    fun mirror(o: Observation): Observation = o.copy(
        playerX = Rules.COLUMNS - o.playerX,
        waterLeft = o.waterRight,
        waterRight = o.waterLeft,
        farChannels = o.farChannels.map { Span(-it.to, -it.from) }.reversed(),
        threats = o.threats.map { it.copy(across = -it.across, moving = -it.moving) },
        depot = o.depot?.let { it.copy(across = -it.across) },
        paths = o.paths.map { it.copy(steer = -it.steer) }.sortedBy { it.steer },
        legal = LegalActions(
            o.legal.actions.map { Action.of(-it.steer, it.fire) }.sortedBy { it.ordinal },
            o.legal.excluded.mapKeys { Action.of(-it.key.steer, it.key.fire) },
        ),
    )

    /**
     * For states where the pilot had a real steering choice (two or more steers offered, and not
     * left-right symmetric in its offers), how often the mirror image is steered the mirror way.
     */
    fun mirrorProbe(pilot: Pilot, states: List<Observation>): String {
        var asked = 0
        var consistent = 0
        var straightBoth = 0
        val picks = IntArray(3)
        for (o in states) {
            if (o.legal.actions.map { it.steer }.distinct().size < 2) continue
            val a = pilot.decide(o)
            val b = pilot.decide(mirror(o))
            if (a.source == DecisionSource.MECHANICAL) continue
            asked++
            picks[a.action.steer + 1]++
            if (b.action.steer == -a.action.steer) consistent++
            if (a.action.steer == 0 && b.action.steer == 0) straightBoth++
        }
        return f(
            "mirror probe %-8s: %d states with a steering choice; mirror-consistent %.0f%% (of which straight both ways %.0f%%); picks L/S/R %d/%d/%d",
            pilot.name, asked, pct(consistent, asked), pct(straightBoth, asked), picks[0], picks[1], picks[2],
        )
    }

    private fun pct(n: Int, d: Int): Double = if (d == 0) 0.0 else 100.0 * n / d

    private fun f(format: String, vararg args: Any?): String = String.format(Locale.ROOT, format, *args)
}

/**
 * A control, not a pilot anyone flies: [ModelPilot]'s gates exactly, then no model — straight on
 * when straight is offered, otherwise a seeded coin between the ways left. It answers "how much of
 * the flying is the gates and the safety net?", so the model's share is not overstated.
 */
class GatesOnlyPilot : Pilot {
    override val name: String = "gates"
    private val coin = kotlin.random.Random(7)

    override fun decide(observation: Observation): PilotDecision {
        val offered = ModelPilot.gates(observation, observation.legal.actions)
        if (offered.size == 1) return PilotDecision(offered.single(), DecisionSource.MECHANICAL, observedTick = observation.tick)
        val pick = offered.firstOrNull { it.steer == 0 } ?: offered[coin.nextInt(offered.size)]
        return PilotDecision(pick, DecisionSource.MODEL, offered.associateWith { if (it == pick) 1.0 else 0.0 }, observedTick = observation.tick)
    }
}

/**
 * The model pilot as shipped up to beb3112, verbatim: one six-way question over steer × fire, the
 * numeric [StateText] scene, and the fuel and fire gates of that commit. Kept only so the
 * before/after measurement stays reproducible.
 */
class LegacyModelPilot(private val backend: Backend) : Pilot {
    override val name: String = "before"

    override fun decide(observation: Observation): PilotDecision {
        val legal = gates(observation, observation.legal.actions)
        if (legal.size == 1) return PilotDecision(legal.single(), DecisionSource.MECHANICAL, observedTick = observation.tick)
        val judgment = Judgment.Choice(id = "game.river.move", question = QUESTION, candidates = legal.map { it.label }, onFailure = FailurePosture.NULL_ACTION)
        val state = TextState.build(listOf("river" to StateText.describe(observation)), 600)
        val started = GameClock.nanoTime()
        val validated = runCatching { judgment.validate(backend.score(judgment, state).masses) }
        val latency = GameClock.nanoTime() - started
        return validated.fold(
            onSuccess = { dist ->
                PilotDecision(Action.ofLabel(dist.argmax) ?: Action.HOLD, DecisionSource.MODEL, legal.associateWith { dist.getValue(it.label).value }, latencyNanos = latency, observedTick = observation.tick)
            },
            onFailure = { PilotDecision(Action.HOLD, DecisionSource.FAILURE, failure = it.message, latencyNanos = latency, observedTick = observation.tick) },
        )
    }

    private fun gates(o: Observation, legal: List<Action>): List<Action> {
        val fuel = fuelFocus(o, legal)
        return if (fuel.size < legal.size) fuel else fireFocus(o, legal)
    }

    private fun fuelFocus(o: Observation, legal: List<Action>): List<Action> {
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

    private fun fireFocus(o: Observation, legal: List<Action>): List<Action> {
        if (!o.weaponReady) return legal
        val enemyInLine = o.threats.any { abs(it.across) < 1.2 && it.ahead < 14 }
        val bridgeClose = (o.bridgeAheadRows ?: Int.MAX_VALUE) < 12
        if (!enemyInLine && !bridgeClose) return legal
        return legal.filter { it.fire }.ifEmpty { legal }
    }

    private companion object {
        const val QUESTION: String =
            "A plane flies up a river. Which move keeps it off the banks and away from enemies, " +
                "shoots targets in line, and reaches fuel when fuel is low?"
    }
}
