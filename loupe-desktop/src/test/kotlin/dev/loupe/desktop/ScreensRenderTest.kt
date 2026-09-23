package dev.loupe.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.loupe.desktop.core.Analysis
import dev.loupe.desktop.core.Screen
import dev.loupe.desktop.ui.LibraryState
import dev.loupe.desktop.ui.LoupeApp
import dev.loupe.desktop.ui.Platform
import dev.loupe.templates.TemplateLibrary
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every screen composes and renders off-screen — empty, then full of data — with a stub model and
 * with none. Catches a screen that throws during composition, which no controller test would.
 */
class ScreensRenderTest {

    @TempDir
    lateinit var tmp: Path

    private fun renderAll(app: dev.loupe.desktop.core.LoupeController, dark: Boolean) {
        val scene = ImageComposeScene(width = 1280, height = 820, density = Density(1f)) { LoupeApp(app, Platform.None, dark = dark) }
        try {
            var t = 0L
            for (s in Screen.entries) {
                app.screen = s
                repeat(3) { scene.render(t); t += 16_000_000L }
            }
            app.screen = Screen.LIBRARY
            LibraryState.writing = true
            val image = scene.render(t)
            LibraryState.writing = false
            assertTrue(image.width == 1280 && image.height == 820)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `every screen renders with no data and no model`() {
        val app = controller(tmp.resolve("empty"), backend = null)
        try {
            runBlocking { app.start().join() }
            app.awaitModel()
            renderAll(app, dark = false)
        } finally {
            app.close()
        }
    }

    @Test
    fun `every screen renders with data, corrections and watcher results, light and dark`() {
        val app = controller(tmp.resolve("full"))
        try {
            runBlocking { app.start().join(); app.loadSampleData().join() }
            app.awaitModel()
            app.useTemplate(TemplateLibrary.byId("is-receipt")!!)
            app.useTemplate(TemplateLibrary.byId("phishing")!!)
            app.useTemplate(TemplateLibrary.byId("urgency")!!)
            for (j in app.judgments) runBlocking { app.sweep(j.id)!!.join() }
            val j = app.judgments.first()
            app.selectedJudgmentId = j.id
            for (row in Analysis.effectiveRows(app.ledger, j, app.correctionIndex).take(35)) {
                app.correct(j.id, row.itemId!!, row.distribution.argmax)
            }
            runBlocking { app.runWatchers().join() }
            renderAll(app, dark = false)
            app.selectedJudgmentId = app.judgments.last().id
            renderAll(app, dark = true)
        } finally {
            app.close()
        }
    }
}
