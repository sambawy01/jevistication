package dev.loupe.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.loupe.desktop.core.Analysis
import dev.loupe.desktop.core.LoupeController
import dev.loupe.desktop.core.ModelState
import dev.loupe.desktop.core.Screen
import dev.loupe.desktop.core.Store
import dev.loupe.desktop.ui.LibraryState
import dev.loupe.desktop.ui.LoupeApp
import dev.loupe.desktop.ui.Platform
import dev.loupe.desktop.ui.ThresholdState
import dev.loupe.templates.TemplateLibrary
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.system.exitProcess

/**
 * Renders every screen off-screen to PNG: `./gradlew :loupe-desktop:snapshot -Pout=<dir>`.
 *
 * The real app over the synthetic sample dataset, in a **throwaway home** (never the user's
 * `~/Library/Application Support/Loupe`), with the real model when `models/` is present. To give
 * the queue, calibration and baseline screens something to show, a scripted "user" answers the
 * receipt judgment's items with the sample's known truth — which the sample has because it is
 * synthetic. Those answers are scaffolding for the screenshot, and the numbers they produce are a
 * check on 45 invented items, not an accuracy claim.
 */
fun main(args: Array<String>) {
    val out = File(args.getOrNull(0) ?: "build/snapshots").apply { mkdirs() }
    val home = Path.of(args.getOrNull(1) ?: "build/snapshot-home").absolute()
    if (Files.exists(home)) home.toFile().deleteRecursively()
    Files.createDirectories(home)
    configurePdfBox(home)
    System.setProperty("loupe.home", home.toString())

    val c = LoupeController(Store(home), modelLoader = ::loadLaya)
    runBlocking { c.start().join(); c.loadSampleData().join() }
    while (c.model is ModelState.Loading) Thread.sleep(20)
    println("[snapshot] model: ${c.model::class.simpleName}; items: ${c.scan.items.size}")

    c.useTemplate(TemplateLibrary.byId("is-receipt")!!)
    val receipt = c.selectedJudgment!!
    c.useTemplate(TemplateLibrary.byId("phishing")!!)
    c.useTemplate(TemplateLibrary.byId("document-type")!!)
    if (c.model is ModelState.Ready) {
        for (j in c.judgments) runBlocking { c.sweep(j.id)?.join() }
        // The scripted user: answer every receipt decision with the sample's known truth.
        val positive = receipt.positiveLabel!!
        val negative = receipt.shape.candidates.first { it != positive }
        for (row in Analysis.effectiveRows(c.ledger, receipt, c.correctionIndex)) {
            val item = c.itemsById[row.itemId] ?: continue
            val truth = if (item.name in SAMPLE_RECEIPTS) positive else negative
            c.correct(receipt.id, item.id, truth, confirmed = truth == row.distribution.argmax)
        }
        // Leave a few unanswered so the queue has something in it.
        repeat(6) { c.undoLastCorrection(receipt.id) }
    }
    runBlocking { c.runWatchers().join() }
    c.selectedJudgmentId = receipt.id
    ThresholdState.candidates[receipt.id] = 0.60f
    c.notice = "Snapshot over the synthetic sample dataset."

    var dark by mutableStateOf(false)
    val scene = ImageComposeScene(width = 1440, height = 920, density = Density(1f)) { LoupeApp(c, Platform.None, dark = dark) }
    var t = 0L
    fun shoot(name: String) {
        repeat(8) { scene.render(t); t += 50_000_000L; Thread.sleep(15) }
        val file = File(out, "loupe-$name.png")
        file.writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        println("[snapshot] wrote $file")
    }
    for (s in Screen.entries) {
        c.screen = s
        shoot(s.name.lowercase())
    }
    c.screen = Screen.LIBRARY
    LibraryState.writing = true
    shoot("library-write-your-own")
    LibraryState.writing = false
    c.screen = Screen.RESULTS
    dark = true
    shoot("results-dark")
    scene.close()
    c.close()
    exitProcess(0)
}

/** The receipts in the synthetic sample: what the scripted user answers "a receipt" for. */
private val SAMPLE_RECEIPTS = setOf(
    "CloudBox Plus — payment received", "Your Streamflix receipt (Jun 2026)", "Your Streamflix receipt (Jul 2026)",
    "Your Streamflix receipt (Aug 2026)", "Your Streamflix receipt (Sep 2026)", "fresh-basket-2026-08-14.txt",
    "fresh-basket-2026-08-14 (copy).txt", "cafe-luna-2026-09-02.txt", "homeware-direct-invoice-kettle.txt",
    "Booking confirmed: QX7-4411", "casa-azul-booking.html",
)
