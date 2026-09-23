package dev.loupe.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.loupe.desktop.core.AppPaths
import dev.loupe.desktop.core.LoupeController
import dev.loupe.desktop.core.ModelState
import dev.loupe.desktop.core.Store
import dev.loupe.desktop.ui.LoupeApp
import dev.loupe.desktop.ui.Platform
import dev.loupe.game.desktop.App
import dev.loupe.game.desktop.GameController
import dev.loupe.game.desktop.ModelLoader
import dev.loupe.game.desktop.ModelStatus
import dev.loupe.game.desktop.handleGameKey
import kotlinx.coroutines.delay
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Loupe on the desktop: `./gradlew :loupe-desktop:run`.
 *
 * Opens at once; loads Laya in the background (model-dependent actions are disabled, with the
 * reason, until it is ready or if it is absent) and rescans the saved sources. Everything it learns
 * is kept under [AppPaths.home].
 *
 * `LOUPE_EXIT_AFTER=<seconds>` makes it print its state once a second and quit: a smoke test that
 * the window opens and the model loads.
 */
fun main() {
    System.setProperty("apple.awt.application.name", "Loupe")
    val home = AppPaths.ensure(AppPaths.home())
    configurePdfBox(home)
    val controller = LoupeController(Store(home), modelLoader = ::loadLaya)
    controller.start()
    val exitAfter = System.getenv("LOUPE_EXIT_AFTER")?.toLongOrNull()
    // The open game, if any: closed before the app frees the model it borrowed, so no decision is
    // still inside the native session when that session is released.
    var openGame: GameController? = null

    application {
        if (exitAfter != null) {
            LaunchedEffect(Unit) {
                repeat(exitAfter.toInt()) {
                    delay(1_000)
                    println("[loupe] home=$home model=${controller.model::class.simpleName} items=${controller.scan.items.size} judgments=${controller.judgments.size} ledger=${controller.ledger.size}")
                }
                controller.close()
                exitProcess(0)
            }
        }
        Window(
            onCloseRequest = {
                openGame?.close()
                controller.close()
                exitApplication()
            },
            title = "Loupe",
            state = rememberWindowState(width = 1440.dp, height = 920.dp),
        ) {
            LoupeApp(controller, DesktopPlatform(window))
        }
        if (controller.gameOpen) {
            // The game borrows the app's model and must not free it when its window closes.
            val game = remember { GameController(ownsModel = false).also { openGame = it } }
            LaunchedEffect(controller.model) {
                game.onModelStatus(
                    when (val m = controller.model) {
                        is ModelState.Ready -> m.loaded?.let { ModelStatus.Ready(it) } ?: ModelStatus.Unavailable("No model to lend.")
                        is ModelState.Unavailable -> ModelStatus.Unavailable(m.message)
                        ModelState.Loading -> ModelStatus.Loading
                    },
                )
            }
            DisposableEffect(Unit) {
                onDispose {
                    game.close()
                    openGame = null
                }
            }
            Window(
                onCloseRequest = { controller.closeGame() },
                title = "Riverflight (working name) — watch Loupe's model think",
                state = rememberWindowState(width = 1240.dp, height = 940.dp),
                onPreviewKeyEvent = { handleGameKey(game, it) },
            ) {
                App(game)
            }
        }
    }
}

/** Loads Laya exactly as the game does, from the gitignored `models/` the run task points at. */
fun loadLaya(): ModelState = when (val status = ModelLoader.load(fallback = "Judgments cannot run; mechanical features (sources, census, watchers' checks) still work.")) {
    is ModelStatus.Ready -> ModelState.Ready(status.model.backend, "Laya multilingual, INT8, on this CPU", status.model)
    is ModelStatus.Unavailable -> ModelState.Unavailable(status.message)
    ModelStatus.Loading -> ModelState.Unavailable("Model loading did not finish.")
}

/**
 * PDFBox writes a font cache, and falls back to the home directory when the folder it is given does
 * not exist. Keep it inside Loupe's own folder.
 */
fun configurePdfBox(home: Path) {
    val cache = Files.createDirectories(home.resolve("cache"))
    System.setProperty("pdfbox.fontcache", cache.toString())
}

/** The real desktop: native file dialogs, and the default app to open a file. Read-only. */
class DesktopPlatform(private val frame: Frame?) : Platform {
    private val mac = System.getProperty("os.name").lowercase().contains("mac")

    override fun chooseFolder(title: String): Path? {
        if (mac) {
            System.setProperty("apple.awt.fileDialogForDirectories", "true")
            try {
                val dialog = FileDialog(frame, title, FileDialog.LOAD)
                dialog.isVisible = true
                val dir = dialog.directory ?: return null
                val file = dialog.file ?: return null
                return File(dir, file).toPath()
            } finally {
                System.setProperty("apple.awt.fileDialogForDirectories", "false")
            }
        }
        val chooser = javax.swing.JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
        }
        return if (chooser.showOpenDialog(frame) == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
    }

    override fun chooseFile(title: String, extensions: Set<String>): Path? {
        val dialog = FileDialog(frame, title, FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in extensions }
        dialog.isVisible = true
        val dir = dialog.directory ?: return null
        val file = dialog.file ?: return null
        return File(dir, file).toPath()
    }

    override fun open(path: Path) {
        // Off the UI thread: the OS can take a moment to launch the default app.
        Thread { runCatching { Desktop.getDesktop().open(path.toFile()) } }.start()
    }

    override fun reveal(path: Path) {
        Thread {
            runCatching {
                val desktop = Desktop.getDesktop()
                if (desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR) && !Files.isDirectory(path)) {
                    desktop.browseFileDirectory(path.toFile())
                } else {
                    desktop.open((if (Files.isDirectory(path)) path else path.parent).toFile())
                }
            }
        }.start()
    }
}
