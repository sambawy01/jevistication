package dev.loupe.desktop

import dev.loupe.desktop.core.Analysis
import dev.loupe.desktop.core.LoupeController
import dev.loupe.desktop.core.ModelState
import dev.loupe.desktop.core.Store
import dev.loupe.engine.Fixture
import dev.loupe.engine.JudgmentReport
import dev.loupe.game.desktop.ModelLoader
import dev.loupe.game.desktop.ModelStatus
import dev.loupe.sources.Scanner
import dev.loupe.templates.TemplateLibrary
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Criteria in the prompt vs bare options, measured with the real Laya model on the **synthetic**
 * sample against hand labels (`sample-labels.tsv`). **Gated** on `models/`. It prints the numbers
 * recorded in `docs/BUILD.md` and asserts only that both arms ran on every labelled item: invented
 * data with labels written by the person measuring is not an accuracy result, and no default
 * should be switched on it.
 */
class CriteriaMeasurementTest {

    @TempDir
    lateinit var tmp: Path

    private val columns = listOf("is-receipt", "phishing", "needs-reply")

    @Test
    fun `measures with and without criteria on the labelled sample`() {
        val dir = ModelLoader.modelsDir()
        assumeTrue(Files.isRegularFile(ModelLoader.graphPath(dir)) && Files.isRegularFile(ModelLoader.tokenizerPath(dir)), "Laya not present under $dir; skipping")
        val status = ModelLoader.load(dir, fallback = "")
        assumeTrue(status is ModelStatus.Ready, "Laya failed to load: $status")
        val loaded = (status as ModelStatus.Ready).model

        val labels = javaClass.getResourceAsStream("/sample-labels.tsv")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
            .associate { it[0] to it.drop(1) }

        val app = LoupeController(Store(tmp), Scanner(zone = ZoneOffset.UTC), { LocalDate.of(2026, 9, 23) }) {
            ModelState.Ready(loaded.backend, "Laya INT8", loaded)
        }
        try {
            runBlocking { app.start().join(); app.loadSampleData().join() }
            while (app.model is ModelState.Loading) Thread.sleep(10)
            val items = app.scan.items.filter { it.hasText }
            fun key(id: String) = id.substringAfter("sample-data/")
            assertEquals(labels.keys, items.map { key(it.id) }.toSet(), "labels must cover exactly the sample's text items")

            for ((col, templateId) in columns.withIndex()) {
                assertTrue(app.useTemplate(TemplateLibrary.byId(templateId)!!).isEmpty())
                val judgment = app.judgments.last()
                val (pos, neg) = judgment.shape.candidates
                val fixtures = items.map { Fixture(it.toItem(), if (labels.getValue(key(it.id))[col] == "+") pos else neg, it.sourceId) }
                val result = Analysis.compareCriteria(app.backend!!, judgment, fixtures)!!
                assertEquals(items.size, result.without.n)
                assertEquals(items.size, result.with.n)
                println(line(templateId, "without", result.without, fixtures.size, 0))
                println(line(templateId, "with", result.with, fixtures.size, result.criteriaCut))
                println("[criteria]   positive reads: \"$pos: ${judgment.optionCriteria()[pos]}\"")
                println("[criteria]   negative reads: \"$neg: ${judgment.optionCriteria()[neg]}\"")
            }
        } finally {
            app.close()
        }
    }

    private fun line(id: String, arm: String, r: JudgmentReport, n: Int, cut: Int) = String.format(
        Locale.ROOT,
        "[criteria] %-12s %-8s acc %2d/%d (%.1f%%)  ECE %.3f  Brier %.3f  coverage %.2f  baseline %.1f%%  criteria cut on %d",
        id, arm, Math.round(r.accuracyAtFullCoverage * n).toInt(), n, r.accuracyAtFullCoverage * 100, r.ece, r.brier, r.coverage, r.baselineAccuracy * 100, cut,
    )
}
