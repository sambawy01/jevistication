package dev.loupe.backend.onnx

import dev.loupe.engine.Decision
import dev.loupe.engine.DecisionEngine
import dev.loupe.engine.Item
import dev.loupe.engine.Probability
import dev.loupe.kit.measure.FastDecisions
import dev.loupe.persistence.JsonText
import dev.loupe.persistence.JsonValue
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Laya's half of the model comparison: every single-label Fast Decisions head, answered through
 * Loupe's own path, written as predictions for `tools/bench/score.py` (docs/HANDOFF-MODEL-BENCH.md).
 *
 * **Gated** like [LayaModelTest]: skipped, not failed, unless the tokenizer, the int8 graph and the
 * fetched dataset are all present, so CI stays green on a machine without them.
 *
 * The path is the shipped one on purpose — `DecisionEngine` over `OnnxBackend` with `LayaPrompt`,
 * the INT8 graph, `TextState`'s budget — because the question is "is Kai better than what we
 * ship", not "than Laya in a lab". The threshold is 0 so nothing abstains for confidence; an answer
 * the engine downgraded to Abstain because the input was cut is still taken (full coverage) and
 * flagged `cut`. An Unusable answer is written with no prediction, and the scorer counts it wrong.
 */
class FastDecisionsBenchTest {
    private val dataDir = Paths.get(System.getProperty("loupe.fastdecisions.dir") ?: "../third-party/fast-decisions")
    private val outFile = File(System.getProperty("loupe.bench.out") ?: "build/bench/laya.jsonl")

    @Test
    fun `write Laya predictions for every single-label Fast Decisions head`() {
        val tokenizerPath = LayaFixture.tokenizerJson()
        val graph = LayaFixture.graph("int8")
        assumeTrue(LayaFixture.present(tokenizerPath), "Laya tokenizer not present at $tokenizerPath; skipping")
        assumeTrue(LayaFixture.present(graph), "Laya int8 graph not present at $graph; skipping")
        val missing = FastDecisions.DOMAINS.filterNot { Files.isRegularFile(dataDir.resolve("$it.jsonl")) }
        assumeTrue(missing.isEmpty(), "Fast Decisions not fetched into $dataDir (run tools/fetch-fast-decisions.sh); skipping")

        outFile.parentFile.mkdirs()
        var written = 0
        HuggingFaceSubwordEncoder.openLayaMultilingual(tokenizerPath).use { encoder ->
            val tokenizer = LayaTokenizer(LayaPrompt(encoder, LayaSpecialTokens.MULTILINGUAL))
            OnnxBackend.open(graph, tokenizer, TensorNames.LAYA).use { backend ->
                val engine = DecisionEngine(backend, threshold = Probability.of(0.0))
                outFile.bufferedWriter().use { out ->
                    for (name in FastDecisions.DOMAINS) {
                        val domain = FastDecisions.parse(name, dataDir.resolve("$name.jsonl").toFile().readText())
                        for (head in domain.heads.filter { FastDecisions.isHarnessable(it) }) {
                            val judgment = FastDecisions.judgment(head)
                            for (case in domain.cases(head.task)) {
                                val started = System.nanoTime()
                                val outcome = runCatching { engine.decide(judgment, Item(case.itemId, case.text)) }
                                val ms = (System.nanoTime() - started) / 1e6
                                val line = linkedMapOf<String, JsonValue>(
                                    "model" to JsonValue.Str("laya-int8"),
                                    "domain" to JsonValue.Str(name),
                                    "item_id" to JsonValue.Str(case.itemId),
                                    "task" to JsonValue.Str(head.task),
                                    "ms" to JsonValue.num(ms),
                                )
                                outcome.fold(
                                    onSuccess = { o ->
                                        when (val d = o.decision) {
                                            is Decision.Act -> line["predicted"] = JsonValue.Str(d.label)
                                            is Decision.Abstain -> {
                                                line["predicted"] = JsonValue.Str(d.topLabel)
                                                line["cut"] = JsonValue.Bool(d.inputCut != null)
                                            }
                                            is Decision.Unusable -> {
                                                line["predicted"] = JsonValue.Null
                                                line["error"] = JsonValue.Str(d.reason)
                                            }
                                        }
                                        if (o.decision !is Decision.Unusable) {
                                            val dist = o.row.distribution
                                            line["probabilities"] = JsonValue.Obj(
                                                LinkedHashMap(dist.labels.associateWith { JsonValue.num(dist.getValue(it).value) }),
                                            )
                                        }
                                    },
                                    onFailure = { t ->
                                        line["predicted"] = JsonValue.Null
                                        line["error"] = JsonValue.Str(t.message ?: t::class.simpleName ?: "error")
                                    },
                                )
                                out.write(JsonText.compact(JsonValue.Obj(line)))
                                out.write("\n")
                                written++
                            }
                        }
                    }
                }
            }
        }
        println("Laya predictions: $written lines -> ${outFile.absolutePath}")
        assertTrue(written > 0)
    }
}
