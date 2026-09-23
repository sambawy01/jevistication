package dev.loupe.game.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.system.exitProcess

/**
 * Renders the real app off-screen to PNGs: `./gradlew :game-desktop:snapshot -Pout=<dir>`.
 *
 * The same [App] composable the window shows, driven by an off-screen scene in real time (the
 * model runs on its own thread as it does live), so a screenshot can be taken on a machine — or in a
 * session — that cannot capture the screen. Arguments: output directory, seconds, and `compare` to
 * render the side-by-side view.
 */
fun main(args: Array<String>) {
    val out = File(args.getOrNull(0) ?: "build/snapshots").apply { mkdirs() }
    val seconds = (args.getOrNull(1) ?: "12").toInt()
    val compare = args.getOrNull(2) == "compare"
    val controller = GameController()
    val status = ModelLoader.load()
    controller.onModelStatus(status)
    if (compare) controller.toggleCompare()
    val scene = ImageComposeScene(width = 1240, height = 940, density = Density(1f)) { App(controller) }
    val start = System.nanoTime()
    var shots = 0
    while (true) {
        val elapsed = System.nanoTime() - start
        val image = scene.render(elapsed)
        if (elapsed >= (shots + 1) * seconds * 1_000_000_000L / 3) {
            shots++
            val file = File(out, "riverflight${if (compare) "-compare" else ""}-$shots.png")
            file.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            println("[riverflight] wrote $file")
            if (shots == 3) break
        }
        Thread.sleep(16)
    }
    scene.close()
    controller.close()
    exitProcess(0)
}
