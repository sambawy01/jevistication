package dev.loupe.game

import dev.loupe.backend.onnx.HuggingFaceSubwordEncoder
import dev.loupe.backend.onnx.LayaPrompt
import dev.loupe.backend.onnx.LayaSpecialTokens
import dev.loupe.backend.onnx.LayaTokenizer
import dev.loupe.backend.onnx.OnnxBackend
import dev.loupe.backend.onnx.TensorNames
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The model pilot on the real Laya INT8 graph.
 *
 * **Gated**, exactly as `LayaModelTest` is: the tokenizer and graph are gitignored, so each test is
 * skipped — not failed — when they are absent, and CI stays green without them. `models/` is a
 * declared test input in `game/build.gradle.kts`, so a result is never replayed from the build cache
 * across the weights appearing or disappearing.
 *
 * These tests measure; they do not grade. The match result is printed and is whatever it is — the
 * only assertions are that the model path works end to end without a single failed decision.
 */
class LayaPilotTest {

    private val modelsDir: Path = Paths.get(System.getProperty("loupe.models.dir") ?: "models")
    private val tokenizerPath = modelsDir.resolve("laya-multilingual/tokenizer/tokenizer.json")
    private val graphPath = modelsDir.resolve("laya-multilingual-onnx/laya-multilingual-choice.int8.onnx")

    private fun <T> withModel(block: (OnnxBackend, LayaPrompt) -> T): T {
        assumeTrue(Files.isRegularFile(tokenizerPath), "Laya tokenizer not present at $tokenizerPath; skipping")
        assumeTrue(Files.isRegularFile(graphPath), "Laya INT8 graph not present at $graphPath; skipping")
        HuggingFaceSubwordEncoder.openLayaMultilingual(tokenizerPath).use { encoder ->
            val prompt = LayaPrompt(encoder, LayaSpecialTokens.MULTILINGUAL)
            OnnxBackend.open(graphPath, LayaTokenizer(prompt), TensorNames.LAYA).use { backend ->
                return block(backend, prompt)
            }
        }
    }

    private fun sampleObservations(): List<Observation> {
        val out = mutableListOf<Observation>()
        // From the start and from the cap section, so the token budget is held where the state is busiest.
        for (start in listOf(1, Difficulty.CAP_SECTION)) {
            for (seed in 1L..3L) {
                val session = GameSession(seed, Control.Piloted(LockstepDecider(BaselinePilot(), 3)), startSection = start)
                while (!session.world.over && session.world.tick < 3_600) {
                    if (session.world.tick % 30 == 0L) out += Observation.of(session.world, Mechanics.legalActions(session.world))
                    session.tick()
                }
            }
        }
        return out
    }

    @Test
    fun `the whole Laya sequence for a decision stays short`() = withModel { _, prompt ->
        val sequences = sampleObservations().map { o ->
            prompt.build(ModelPilot.QUESTION, StateText.describe(o), o.legal.actions.map { it.label })
        }
        val stateTokens = sequences.map { it.stateTokens }
        val total = sequences.map { it.inputIds.size }
        println(
            "Laya tokens over ${sequences.size} real states: state max ${stateTokens.max()} " +
                "(mean ${"%.1f".format(stateTokens.average())}), whole sequence max ${total.max()} " +
                "(mean ${"%.1f".format(total.average())})",
        )
        assertTrue(sequences.none { it.stateTruncated }, "a state was cut to fit")
        assertTrue(stateTokens.max() <= 120, "state reached ${stateTokens.max()} tokens")
        assertTrue(total.max() <= 200, "sequence reached ${total.max()} tokens")
    }

    @Test
    fun `decision latency on this machine`() = withModel { backend, _ ->
        val pilot = ModelPilot(backend)
        val observations = sampleObservations().filter { it.legal.actions.size > 1 }
        repeat(10) { pilot.decide(observations[it % observations.size]) } // warm-up, not counted
        val stats = DecisionStats(window = 1_000)
        val started = System.nanoTime()
        var n = 0
        for (o in observations.take(200)) {
            val decision = pilot.decide(o)
            assertEquals(DecisionSource.MODEL, decision.source, decision.failure)
            stats.record(decision, System.nanoTime())
            n++
        }
        val seconds = (System.nanoTime() - started) / 1e9
        println(
            "Laya INT8 decisions: $n, P50 ${"%.1f".format(stats.latencyMillis(0.5))} ms, " +
                "P95 ${"%.1f".format(stats.latencyMillis(0.95))} ms, back-to-back throughput " +
                "${"%.1f".format(n / seconds)} decisions/s",
        )
    }

    @Test
    fun `model versus baseline on the same seeds`() = withModel { backend, _ ->
        val seeds = listOf(1L, 2L, 3L)
        for (override in listOf(true, false)) {
            val settings = Match.Settings(maxTicks = 60 * Rules.TICK_HZ, overrideEnabled = override)
            val episodes = Match.run(seeds, listOf(ModelPilot(backend), BaselinePilot()), settings)
            println(Match.report(episodes, settings))
            assertEquals(0, episodes.sumOf { it.failures }, "the model path failed on some decisions")
        }
    }
}
