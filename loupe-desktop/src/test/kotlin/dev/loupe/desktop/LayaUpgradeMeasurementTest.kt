package dev.loupe.desktop

import dev.loupe.desktop.core.LoupeController
import dev.loupe.desktop.core.ModelState
import dev.loupe.desktop.core.Store
import dev.loupe.engine.Backend
import dev.loupe.engine.DecisionEngine
import dev.loupe.engine.Fixture
import dev.loupe.engine.Harness
import dev.loupe.engine.Item
import dev.loupe.engine.Judgment
import dev.loupe.engine.JudgmentReport
import dev.loupe.engine.Probability
import dev.loupe.engine.TextState
import dev.loupe.game.desktop.ModelLoader
import dev.loupe.game.desktop.ModelStatus
import dev.loupe.sources.Scanner
import dev.loupe.templates.Shape
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
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Issue #8 step 6: every library template before (pin c7527708, score levels sent in written order)
 * vs after (laya 0.3.20 + Station's REVERSE_SCORE_ON), against its keyword baseline, on the
 * template's own examples and on the **synthetic** sample. The upgrade does not change the exported
 * graph or any token sequence (golden/criteria parity), so "before" is the same INT8 graph with the
 * reversal off. **Gated** on `models/`; writes build/laya-upgrade-measure.md for docs/.
 */
class LayaUpgradeMeasurementTest {

    @TempDir
    lateinit var tmp: Path

    private val labelled = listOf("is-receipt", "phishing", "needs-reply")

    @Test
    fun `measures all templates before and after the upgrade`() {
        val dir = ModelLoader.modelsDir()
        assumeTrue(Files.isRegularFile(ModelLoader.graphPath(dir)) && Files.isRegularFile(ModelLoader.tokenizerPath(dir)), "Laya not present under $dir; skipping")
        val status = ModelLoader.load(dir, fallback = "")
        assumeTrue(status is ModelStatus.Ready, "Laya failed to load: $status")
        val loaded = (status as ModelStatus.Ready).model

        val labels = javaClass.getResourceAsStream("/sample-labels.tsv")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }.associate { it[0] to it.drop(1) }

        val app = LoupeController(Store(tmp), Scanner(zone = ZoneOffset.UTC), { LocalDate.of(2026, 9, 24) }) {
            ModelState.Ready(loaded.backend, "Laya INT8", loaded)
        }
        val out = StringBuilder()
        try {
            runBlocking { app.start().join(); app.loadSampleData().join() }
            while (app.model is ModelState.Loading) Thread.sleep(10)
            val backend: Backend = app.backend!!
            val sample = app.scan.items.filter { it.hasText }
            fun key(id: String) = id.substringAfter("sample-data/")
            val templates = TemplateLibrary.ALL
            out.appendLine("| template | shape | examples: acc before → after | examples: keyword baseline | sample: agrees with baseline before → after | sample: answers changed |")
            out.appendLine("|---|---|---|---|---|---|")
            var changedTotal = 0
            var n = 0
            var wins = 0 to 0
            val sampleRows = StringBuilder()
            for (t in templates) {
                val values = t.parameters.associate { it.name to it.example }
                val j = (t.instantiate("m-${t.id}", values) as dev.loupe.templates.Template.InstantiateResult.Created).judgment
                val after = j.choice
                val before = after.copy(ordinal = false)
                val baseline = j.baseline?.asFunction()
                val engine = { c: Judgment.Choice -> DecisionEngine(backend, Probability.of(j.threshold)) }
                val ex = t.examples.mapIndexed { i, e -> Fixture(Item("${t.id}-ex$i", e.text), e.answer, "examples") }
                fun report(c: Judgment.Choice, fx: List<Fixture>): JudgmentReport =
                    Harness.evaluate(c, fx, engine(c), baseline ?: { _ -> "" })
                val exBefore = if (ex.isEmpty()) null else report(before, ex)
                val exAfter = if (ex.isEmpty() || !after.ordinal) exBefore else report(after, ex)
                fun top(c: Judgment.Choice, text: String): String {
                    val m = backend.score(c, TextState.build(listOf("item" to text), 4_000)).masses
                    return m.maxBy { it.value }.key
                }
                val sb = sample.map { top(before, it.text) }
                val sa = if (after.ordinal) sample.map { top(after, it.text) } else sb
                val base = sample.map { baseline?.invoke(Item(it.id, it.text)) }
                val agreeB = sb.indices.count { base[it] != null && sb[it] == base[it] }
                val agreeA = sa.indices.count { base[it] != null && sa[it] == base[it] }
                val changed = sb.indices.count { sb[it] != sa[it] }
                changedTotal += changed
                n++
                val shape = when (j.shape) { is Shape.Ordinal -> "score"; is Shape.Pick -> "pick"; else -> "yes/no" }
                fun pct(r: JudgmentReport?) = r?.let { String.format(Locale.ROOT, "%d/%d", Math.round(it.accuracyAtFullCoverage * it.n), it.n) } ?: "—"
                val baseEx = exBefore?.let { String.format(Locale.ROOT, "%d/%d", Math.round(it.baselineAccuracy * it.n), it.n) } ?: "—"
                if (exAfter != null && baseline != null) {
                    wins = (wins.first + if (exAfter.accuracyAtFullCoverage > exAfter.baselineAccuracy) 1 else 0) to (wins.second + 1)
                }
                out.appendLine("| `${t.id}` | $shape | ${pct(exBefore)} → ${pct(exAfter)} | $baseEx | " +
                    (if (baseline == null) "— (no baseline)" else "$agreeB/${sample.size} → $agreeA/${sample.size}") + " | $changed |")
                if (t.id in labelled) {
                    val col = labelled.indexOf(t.id)
                    val (pos, neg) = j.shape.candidates
                    val fx = sample.map { Fixture(it.toItem(), if (labels.getValue(key(it.id))[col] == "+") pos else neg, it.sourceId) }
                    val r = report(after, fx)
                    sampleRows.appendLine(String.format(Locale.ROOT, "| `%s` | %d/%d (%.1f%%) | %.3f | %.3f | %.1f%% |", t.id,
                        Math.round(r.accuracyAtFullCoverage * r.n), r.n, r.accuracyAtFullCoverage * 100, r.ece, r.brier, r.baselineAccuracy * 100))
                }
            }
            out.appendLine()
            out.appendLine("Templates: $n. Sample answers changed by the upgrade, summed over all templates: $changedTotal. " +
                "Templates whose examples Laya (after) answers better than the keyword baseline: ${wins.first} of ${wins.second} with a baseline and examples.")
            out.appendLine()
            out.appendLine("| hand-labelled sample judgment | accuracy (before = after) | ECE | Brier | keyword baseline |")
            out.appendLine("|---|---|---|---|---|")
            out.append(sampleRows)
            assertEquals(TemplateLibrary.ALL.size, n)
        } finally {
            app.close()
        }
        val file = Paths.get("build/laya-upgrade-measure.md")
        Files.createDirectories(file.parent)
        Files.writeString(file, out.toString())
        println(out)
    }
}
