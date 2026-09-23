package dev.loupe.game.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.system.exitProcess

/**
 * The desktop demo: `./gradlew :game-desktop:run`.
 *
 * Starts at once with the baseline flying, loads Laya in the background, and hands the controls to
 * the model when it is ready — or says, on screen, why it could not.
 *
 * `LOUPE_GAME_EXIT_AFTER=<seconds>` in the environment makes it log its measured numbers once a
 * second and quit after that long: a smoke test that the window opens and the model flies.
 */
fun main() {
    val exitAfter = System.getenv("LOUPE_GAME_EXIT_AFTER")?.toLongOrNull()
    application {
        val controller = remember { GameController() }
        LaunchedEffect(Unit) {
            val status = withContext(Dispatchers.IO) { ModelLoader.load() }
            controller.onModelStatus(status)
            if (status is ModelStatus.Unavailable) println("[riverflight] ${status.message}")
        }
        if (exitAfter != null) {
            LaunchedEffect(Unit) {
                repeat(exitAfter.toInt()) {
                    delay(1_000)
                    log(controller)
                }
                controller.close()
                exitProcess(0)
            }
        }
        Window(
            onCloseRequest = {
                controller.close()
                exitApplication()
            },
            title = "Riverflight (working name) — Loupe demo",
            state = rememberWindowState(width = 1240.dp, height = 940.dp),
            onPreviewKeyEvent = { onKey(controller, it) },
        ) {
            App(controller)
        }
    }
}

@Composable
fun App(controller: GameController) {
    LaunchedEffect(controller) {
        while (true) withFrameNanos { controller.advance(it) }
    }
    val frame = controller.frame
    MaterialTheme(colors = darkColors()) {
        Row(Modifier.fillMaxSize().background(Palette.panel)) {
            Row(
                Modifier.weight(1f).fillMaxHeight().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (lane in controller.lanes) {
                    Box(Modifier.weight(1f, fill = false)) { GameView(lane, frame) }
                }
            }
            SidePanel(controller, frame, Modifier.width(380.dp).fillMaxHeight())
        }
    }
}

/** Arrows and space are the plane's; they are taken before any control can consume them. */
private fun onKey(controller: GameController, event: KeyEvent): Boolean {
    val down = when (event.type) {
        KeyEventType.KeyDown -> true
        KeyEventType.KeyUp -> false
        else -> return false
    }
    val input = controller.currentHuman()
    when (event.key) {
        Key.DirectionLeft, Key.A -> controller.setHuman(input.copy(left = down))
        Key.DirectionRight, Key.D -> controller.setHuman(input.copy(right = down))
        Key.Spacebar -> controller.setHuman(input.copy(fire = down))
        Key.Enter -> if (down) controller.nextRun()
        Key.P -> if (down) controller.togglePause()
        else -> return false
    }
    return true
}

private fun log(controller: GameController) {
    val now = System.nanoTime()
    for (lane in controller.lanes) {
        val s = lane.session.stats
        val w = lane.session.world
        println(
            String.format(
                Locale.ROOT,
                "[riverflight] %-8s decisions/s %.1f  p50 %s ms  p95 %s ms  decisions %d  model %d  failures %d  overrides %d  score %d  rows %d%s",
                lane.label, s.decisionsPerSecond(now),
                s.latencyMillis(0.5)?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "-",
                s.latencyMillis(0.95)?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "-",
                s.total, s.count(dev.loupe.game.DecisionSource.MODEL), s.count(dev.loupe.game.DecisionSource.FAILURE),
                s.overrides, w.score, w.cameraY.toInt(), if (w.over) " over(${w.death})" else "",
            ),
        )
    }
}
