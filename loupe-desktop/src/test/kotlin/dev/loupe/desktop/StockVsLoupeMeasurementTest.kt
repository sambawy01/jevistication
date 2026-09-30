package dev.loupe.desktop

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import dev.loupe.backend.onnx.HuggingFaceSubwordEncoder
import dev.loupe.backend.onnx.LayaPrompt
import dev.loupe.backend.onnx.LayaRuntimeRules
import dev.loupe.backend.onnx.LayaSpecialTokens
import dev.loupe.backend.onnx.LayaTokenizer
import dev.loupe.backend.onnx.OnnxBackend
import dev.loupe.backend.onnx.TensorNames
import dev.loupe.desktop.core.LoupeController
import dev.loupe.desktop.core.ModelState
import dev.loupe.desktop.core.Store
import dev.loupe.engine.DecisionEngine
import dev.loupe.engine.Item
import dev.loupe.engine.Judgment
import dev.loupe.engine.TextState
import dev.loupe.game.desktop.ModelLoader
import dev.loupe.game.desktop.ModelStatus
import dev.loupe.kit.measure.FastDecisions
import dev.loupe.kit.settings.ModelPrior
import dev.loupe.sources.Scanner
import dev.loupe.templates.Shape
import dev.loupe.templates.Template
import dev.loupe.templates.TemplateLibrary
import dev.loupe.templates.UserJudgment
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Stock Laya vs the Loupe stack (docs/STOCK-VS-LOUPE-MEASURE.md). This test is the Loupe half:
 * it runs every labelled record through the shipped phone stack and writes the records themselves,
 * so `tools/measure-stock-vs-loupe.py` can run the authors' own package on exactly the same inputs.
 *
 * Arms written here, all over the **same** state text (what `DecisionEngine` would send):
 * - `loupe`: INT8 graph, `LayaPrompt`, score reversal on (the shipped default), no calibration.
 * - `loupe_cal`: the same masses through `ModelPrior` with calibration on (never changes the answer).
 * - `loupe_fp32`: the fp32 export of the same weights, same prompt and reversal (precision ablation).
 * - `loupe_norev`: INT8 with the reversal off, score questions only (reversal ablation).
 *
 * **Double-gated**: it needs `models/` and `-Ploupe.measure=stock-vs-loupe`, because it runs
 * thousands of model calls on two graphs and does not belong in every `check`. Records land in
 * `measure-results/stock-vs-loupe/` at the repo root (gitignored), or `-Dloupe.measure.out`.
 */
class StockVsLoupeMeasurementTest {

    @TempDir
    lateinit var tmp: Path

    private val labelled = listOf("is-receipt", "phishing", "needs-reply")
    private val gson = GsonBuilder().disableHtmlEscaping().serializeSpecialFloatingPointValues().create()

    private class Rec(
        val dataset: String,
        val itemId: String,
        val questionId: String,
        val item: Item,
        val choice: Judgment.Choice,
        val shape: String,
        val positive: String?,
        val label: String,
        val threshold: Double,
        val baseline: String?,
    )

    @Test
    fun `writes the Loupe arms and the shared records`() {
        assumeTrue(System.getProperty("loupe.measure") == "stock-vs-loupe", "run with -Ploupe.measure=stock-vs-loupe; skipping")
        val dir = ModelLoader.modelsDir()
        val tokenizerJson = ModelLoader.tokenizerPath(dir)
        val int8 = dir.resolve("laya-multilingual-onnx/laya-multilingual-choice.int8.onnx")
        val fp32 = dir.resolve("laya-multilingual-onnx/laya-multilingual-choice.fp32.onnx")
        assumeTrue(Files.isRegularFile(tokenizerJson) && Files.isRegularFile(int8), "Laya not present under $dir; skipping")
        val root = dir.toAbsolutePath().parent
        val out = Paths.get(System.getProperty("loupe.measure.out") ?: root.resolve("measure-results/stock-vs-loupe").toString())
        Files.createDirectories(out)

        val records = collectRecords(root)
        assertTrue(records.isNotEmpty())

        HuggingFaceSubwordEncoder.openLayaMultilingual(tokenizerJson).use { encoder ->
            val tokenizer = LayaTokenizer(LayaPrompt(encoder, LayaSpecialTokens.MULTILINGUAL))
            val calibrated = ModelPrior.recalibrator(useCalibration = true)
            val rows = records.map { r -> JsonObject().also { describe(r, it) } }

            // One graph at a time: the fp32 session alone is ~1.3 GB of native memory.
            OnnxBackend.open(int8, tokenizer, TensorNames.LAYA).use { backend ->
                warmUp(backend, records)
                records.forEachIndexed { i, r ->
                    val state = stateOf(r)
                    val (scored, ms) = timed { backend.score(r.choice, state) }
                    val raw = r.choice.validate(scored.masses)
                    rows[i].add("loupe", arm(r.choice, scored.masses, ms))
                    rows[i].addProperty("model_context_cut", scored.modelContext != null)
                    rows[i].addProperty("option_text_cut", scored.optionCriteria != null)
                    val cal = calibrated.calibrate(raw, r.choice, state).distribution
                    rows[i].add("loupe_cal", arm(r.choice, r.choice.candidates.associateWith { cal.getValue(it).value }, null))
                    if (r.choice.ordinal) {
                        val (plain, pms) = timed { backend.score(r.choice.copy(ordinal = false), state) }
                        rows[i].add("loupe_norev", arm(r.choice, plain.masses, pms))
                    }
                }
            }
            if (Files.isRegularFile(fp32)) {
                OnnxBackend.open(fp32, tokenizer, TensorNames.LAYA).use { backend ->
                    warmUp(backend, records)
                    records.forEachIndexed { i, r ->
                        val (scored, ms) = timed { backend.score(r.choice, stateOf(r)) }
                        rows[i].add("loupe_fp32", arm(r.choice, scored.masses, ms))
                    }
                }
            }
            Files.newBufferedWriter(out.resolve("records.jsonl")).use { w ->
                rows.forEach { w.write(gson.toJson(it)); w.newLine() }
            }
            println("stock-vs-loupe: wrote ${rows.size} records to ${out.resolve("records.jsonl")}")
        }
    }

    /** Exactly what `DecisionEngine.decide` hands the backend for this item. */
    private fun stateOf(r: Rec): TextState =
        TextState.build(listOf(r.item.id to r.item.text), DecisionEngine.DEFAULT_STATE_BUDGET)

    private fun describe(r: Rec, o: JsonObject) {
        val state = stateOf(r)
        o.addProperty("dataset", r.dataset)
        o.addProperty("item_id", r.itemId)
        o.addProperty("question_id", r.questionId)
        o.addProperty("state", state.text)
        o.addProperty("state_budget_cut", state.budgetCut != null)
        o.addProperty("shape", r.shape)
        o.addProperty("instructions", r.choice.question)
        o.add("options", JsonArray().also { a -> r.choice.candidates.forEach(a::add) })
        o.add("descriptions", JsonArray().also { a -> r.choice.descriptionList.forEach { d -> if (d == null) a.add(JsonNull.INSTANCE) else a.add(d) } })
        o.addProperty("ordinal", r.choice.ordinal)
        o.addProperty("reversed_in_loupe", LayaRuntimeRules.reversesScore(r.choice))
        r.positive?.let { o.addProperty("positive", it) }
        o.addProperty("label", r.label)
        o.addProperty("threshold", r.threshold)
        o.addProperty("on_failure", r.choice.onFailure.name)
        if (r.baseline != null) o.addProperty("baseline", r.baseline) else o.add("baseline", JsonNull.INSTANCE)
    }

    private fun arm(choice: Judgment.Choice, masses: Map<String, Double>, ms: Double?): JsonObject = JsonObject().apply {
        add("probs", JsonArray().also { a -> choice.candidates.forEach { a.add(masses.getValue(it)) } })
        if (ms != null) addProperty("ms", ms)
    }

    private fun <T> timed(block: () -> T): Pair<T, Double> {
        val t0 = System.nanoTime()
        val v = block()
        return v to (System.nanoTime() - t0) / 1e6
    }

    private fun warmUp(backend: OnnxBackend, records: List<Rec>) {
        records.take(5).forEach { backend.score(it.choice, stateOf(it)) }
    }

    private fun shapeOf(s: Shape): String = when (s) {
        Shape.YesNo -> "yesno"
        is Shape.Binary -> "binary"
        is Shape.Pick -> "pick"
        is Shape.Ordinal -> "score"
    }

    private fun judgmentOf(t: Template): UserJudgment {
        val values = t.parameters.associate { it.name to it.example }
        return (t.instantiate("m-${t.id}", values) as Template.InstantiateResult.Created).judgment
    }

    private fun collectRecords(root: Path): List<Rec> {
        val out = mutableListOf<Rec>()

        // 1. The hand-labelled synthetic sample: 45 items x 3 yes/no judgments.
        val labels = javaClass.getResourceAsStream("/sample-labels.tsv")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }.associate { it[0] to it.drop(1) }
        val app = LoupeController(Store(tmp), Scanner(zone = ZoneOffset.UTC), { LocalDate.of(2026, 9, 30) }) { ModelState.Unavailable("records only") }
        val sample = try {
            runBlocking { app.start().join(); app.loadSampleData().join() }
            app.scan.items.filter { it.hasText }
        } finally {
            app.close()
        }
        for (id in labelled) {
            val t = TemplateLibrary.ALL.first { it.id == id }
            val j = judgmentOf(t)
            val (pos, neg) = j.shape.candidates
            val base = j.baseline?.asFunction()
            for (s in sample) {
                val key = s.id.substringAfter("sample-data/")
                val item = s.toItem()
                out += Rec("sample", key, t.id, item, j.choice, shapeOf(j.shape), j.shape.positiveOption,
                    if (labels.getValue(key)[labelled.indexOf(id)] == "+") pos else neg, j.threshold, base?.invoke(item))
            }
        }

        // 2. Every template's own examples (2-3 each, written with the templates).
        for (t in TemplateLibrary.ALL) {
            val j = judgmentOf(t)
            val base = j.baseline?.asFunction()
            t.examples.forEachIndexed { i, e ->
                val item = Item("${t.id}-ex$i", e.text)
                out += Rec("templates", item.id, t.id, item, j.choice, shapeOf(j.shape), j.shape.positiveOption,
                    e.answer, j.threshold, base?.invoke(item))
            }
        }

        // 3. Fast Decisions development split, single-label heads, questionFor untouched.
        val fd = root.resolve("third-party/fast-decisions")
        if (Files.isDirectory(fd)) {
            for (name in FastDecisions.DOMAINS) {
                val file = fd.resolve("$name.jsonl")
                if (!Files.isRegularFile(file)) continue
                val domain = FastDecisions.parse(name, Files.readString(file))
                for (head in domain.heads.filter { FastDecisions.isHarnessable(it) }) {
                    val choice = FastDecisions.judgment(head)
                    val base = FastDecisions.lexicalBaseline(head)
                    for (f in FastDecisions.fixtures(domain, head)) {
                        out += Rec("fast", f.item.id, head.id, f.item, choice, "labels", null, f.trueLabel,
                            FAST_THRESHOLD, base(f.item))
                    }
                }
            }
        } else {
            println("stock-vs-loupe: no Fast Decisions data under $fd (tools/fetch-fast-decisions.sh); skipping that dataset")
        }
        return out
    }

    private companion object {
        /** The hand-off's engine threshold for Fast Decisions heads (docs/HANDOFF-FAST-DECISIONS.md §5). */
        const val FAST_THRESHOLD = 0.5
    }
}
