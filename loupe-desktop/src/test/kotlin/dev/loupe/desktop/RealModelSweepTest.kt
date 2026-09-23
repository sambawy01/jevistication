package dev.loupe.desktop

import dev.loupe.desktop.core.Analysis
import dev.loupe.desktop.core.LoupeController
import dev.loupe.desktop.core.ModelState
import dev.loupe.desktop.core.Store
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
 * The real Laya model over the sample dataset, through the app's own controller: the same path the
 * window takes. **Gated** — skipped, not failed, when the gitignored `models/` is absent, so CI
 * stays green without it (the build declares `models/` as a test input, so a skip and a pass are
 * never replayed across each other from the build cache).
 *
 * It asserts only that the machinery works on the real model — every item judged, nothing
 * unusable, the watchers' model half running. It prints what the model answered and how fast, and
 * deliberately asserts nothing about accuracy: there is no labelled corpus, and sample answers are
 * not an accuracy number.
 */
class RealModelSweepTest {

    @TempDir
    lateinit var tmp: Path

    private val templates = listOf("is-receipt", "phishing", "email-kind", "subscription-charge", "document-type")

    @Test
    fun `sweeps the sample dataset with real Laya on five templates`() {
        val dir = ModelLoader.modelsDir()
        assumeTrue(Files.isRegularFile(ModelLoader.graphPath(dir)) && Files.isRegularFile(ModelLoader.tokenizerPath(dir)), "Laya not present under $dir; skipping")
        val status = ModelLoader.load(dir, fallback = "")
        assumeTrue(status is ModelStatus.Ready, "Laya failed to load: $status")
        val loaded = (status as ModelStatus.Ready).model

        val app = LoupeController(Store(tmp), Scanner(zone = ZoneOffset.UTC), { LocalDate.of(2026, 9, 23) }) {
            ModelState.Ready(loaded.backend, "Laya INT8", loaded)
        }
        try {
            runBlocking { app.start().join(); app.loadSampleData().join() }
            while (app.model is ModelState.Loading) Thread.sleep(10)
            val withText = app.scan.items.count { it.hasText }
            for (id in templates) {
                assertTrue(app.useTemplate(TemplateLibrary.byId(id)!!).isEmpty())
                val judgment = app.selectedJudgment!!
                val t0 = System.nanoTime()
                runBlocking { app.sweep(judgment.id)!!.join() }
                val wall = (System.nanoTime() - t0) / 1e9
                val sweep = app.sweep!!
                assertEquals(withText, sweep.done)
                assertEquals(0, sweep.unusable, "no unusable answers from the real model")
                val views = Analysis.views(Analysis.effectiveRows(app.ledger, judgment, app.correctionIndex), judgment, app.itemsById)
                println(
                    String.format(
                        Locale.ROOT,
                        "[laya-sweep] %-20s %d items in %.1f s — %.1f items/s, median %.1f ms/item (model calls: %d, mechanical: %d), threshold %.2f",
                        id, sweep.done, wall, sweep.done / wall, sweep.medianMillis ?: 0.0, sweep.done - sweep.mechanical, sweep.mechanical, judgment.threshold,
                    ),
                )
                for (v in views.sortedBy { it.item?.name }) {
                    println(String.format(Locale.ROOT, "[laya-sweep]   %-44s %-24s %.3f %s", v.item?.name?.take(44), v.topLabel, v.topMass, if (v.acted) "" else "(unsure)"))
                }
            }
            runBlocking { app.runWatchers().join() }
            val alerts = app.watcherReport!!.expiryAlerts!!
            println("[laya-sweep] expiry radar with the model: " + alerts.map { "${it.documentType} ${it.expiry} (${it.daysRemaining} days)" })
        } finally {
            app.close()
        }
    }
}
