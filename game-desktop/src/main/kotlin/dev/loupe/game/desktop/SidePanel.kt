package dev.loupe.game.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.RadioButton
import androidx.compose.material.Slider
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.loupe.game.Action
import dev.loupe.game.DecisionSource
import dev.loupe.game.Exclusion
import java.util.Locale

/**
 * Everything beside the river: who is flying, the live probability bars, the hand-off slider,
 * the safety-net switch and the measured speed. Plain Compose Material, portable to Android.
 *
 * The bars show the model's **raw** output. They are labelled so, never "confidence" and never
 * "calibrated": no calibration has been fitted for this judgment, and Laya's own card calls the
 * checkpoint over-confident.
 */
@Composable
fun SidePanel(controller: GameController, frame: Long, modifier: Modifier = Modifier) {
    frame // repaint the live numbers every frame
    val lane = controller.lanes.first()
    val session = lane.session
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Riverflight", color = Palette.panelText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Label("working name — a Loupe demo: the on-device model flies, and shows its work")

        when (val status = controller.modelStatus) {
            ModelStatus.Loading -> Note("Loading the Laya model… the baseline flies meanwhile.", Palette.handOff)
            is ModelStatus.Ready -> Label("Model: Laya multilingual, INT8 ONNX, on this CPU, offline.")
            is ModelStatus.Unavailable -> Note(status.message, Palette.overrideFlash)
        }

        Section("Pilot")
        for (mode in PilotMode.entries) {
            val enabled = mode != PilotMode.MODEL || controller.modelReady
            Row(
                Modifier.fillMaxWidth().selectable(selected = controller.mode == mode && !controller.compare, enabled = enabled) {
                    controller.selectMode(mode)
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = controller.mode == mode && !controller.compare, onClick = null, enabled = enabled)
                Spacer(Modifier.width(6.dp))
                Text(mode.title, color = if (enabled) Palette.panelText else Palette.muted)
            }
        }
        Toggle("Model vs baseline, same seed", controller.compare, enabled = controller.modelReady) { controller.toggleCompare() }

        Section("Hand-off threshold")
        Label(
            "When the model's top raw probability is below this, the decision is yours: " +
                "← → to steer, space to shoot, until the next decision lands.",
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(controller.threshold, { controller.changeThreshold(it) }, Modifier.width(220.dp))
            Mono(String.format(Locale.ROOT, "%.2f", controller.threshold))
        }

        Toggle("Safety override (flashes red when it acts)", controller.overrideEnabled) { controller.toggleOverride() }

        Section(if (lane.mode == PilotMode.MODEL) "Raw model output (uncalibrated)" else "Decision")
        DecisionBars(lane, controller.threshold, frame)

        Section("Measured")
        val stats = session.stats
        val now = System.nanoTime()
        Mono("decisions/s  ${fmt(stats.decisionsPerSecond(now), 1)}")
        Mono("latency p50  ${stats.latencyMillis(0.5)?.let { fmt(it, 1) + " ms" } ?: "—"}")
        Mono("latency p95  ${stats.latencyMillis(0.95)?.let { fmt(it, 1) + " ms" } ?: "—"}")
        Mono("decisions    ${stats.total}  (model ${stats.count(DecisionSource.MODEL)}, mechanical ${stats.count(DecisionSource.MECHANICAL)})")
        Mono("failures     ${stats.count(DecisionSource.FAILURE)}")
        Mono("hand-offs    ${stats.handOffs}")
        Mono("overrides    ${stats.overrides}")
        Mono("speed        ${fmt(session.world.difficulty.scroll, 1)} rows/s  (section ${session.world.section})")
        if (controller.lanes.size > 1) {
            Section("Side by side")
            for (other in controller.lanes) {
                val w = other.session.world
                Mono("${other.label.padEnd(9)} score ${w.score}  rows ${w.cameraY.toInt()}${if (w.over) "  (${w.death})" else ""}")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ controller.nextRun() }) { Text("New river") }
            Button({ controller.togglePause() }) { Text(if (controller.paused) "Resume" else "Pause") }
        }
        Label("Keys: ← → steer · space shoot · Enter new river · P pause")
    }
}

@Composable
private fun DecisionBars(lane: Lane, threshold: Float, frame: Long) {
    // Read so this recomposes every frame: with strong skipping (Kotlin 2.x), a composable whose
    // arguments are the same instances is skipped, and the session mutates in place.
    frame
    val session = lane.session
    if (lane.mode == PilotMode.HUMAN) {
        Label("You are flying. The safety override still stands behind you when it is on.")
        return
    }
    val decision = session.current
    val legal = session.currentLegal
    when (decision?.source) {
        null -> Label("Waiting for the first decision…")
        DecisionSource.MECHANICAL -> Note("Only one legal move: mechanics decided, the model was not asked.", Palette.muted)
        DecisionSource.FAILURE -> Note("Model answer unusable (${decision.failure}); holding course.", Palette.overrideFlash)
        DecisionSource.BASELINE -> Label("Rules, no model: no probabilities to show.")
        DecisionSource.MODEL -> if (session.handedOff) Note("Top raw probability under the threshold: your turn.", Palette.handOff)
    }
    Label("Greyed = not offered to the model: ✕ crash would be fatal, ✕ fuel would shoot the last fuel.")
    for (action in Action.entries) {
        val raw = decision?.raw?.get(action)
        val offered = legal?.actions?.contains(action) ?: true
        val chosen = decision?.action == action
        val note = when (legal?.excluded?.get(action)) {
            Exclusion.FATAL -> "✕ crash"
            Exclusion.SHOOTS_LAST_FUEL -> "✕ fuel"
            null -> raw?.let { fmt(it, 2) } ?: ""
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                action.label,
                color = if (offered) Palette.panelText else Palette.muted,
                fontSize = 12.sp,
                fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.width(145.dp),
            )
            Canvas(Modifier.width(110.dp).height(12.dp)) {
                drawRect(Palette.barTrack)
                if (raw != null) drawRect(if (chosen) Palette.barChosen else Palette.bar, size = Size(size.width * raw.toFloat(), size.height))
                // The hand-off line: bars that never cross it leave the decision to you.
                val x = size.width * threshold
                drawRect(Palette.handOff, Offset(x - 1f, 0f), Size(2f, size.height))
            }
            Spacer(Modifier.width(6.dp))
            Text(note, color = Palette.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
    session.lastOverride?.let { Label("last override: ${it.wanted.label} → ${it.replacedWith.label}") }
}

@Composable
private fun Toggle(text: String, checked: Boolean, enabled: Boolean = true, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
        Spacer(Modifier.width(6.dp))
        Text(text, color = if (enabled) Palette.panelText else Palette.muted, fontSize = 13.sp)
    }
}

@Composable
private fun Section(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(text.uppercase(), color = Palette.barChosen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun Label(text: String) = Text(text, color = Palette.muted, fontSize = 12.sp)

@Composable
private fun Note(text: String, color: Color) = Text(text, color = color, fontSize = 12.sp)

@Composable
private fun Mono(text: String) = Text(text, color = Palette.panelText, fontSize = 12.sp, fontFamily = FontFamily.Monospace)

private fun fmt(v: Double, digits: Int): String = String.format(Locale.ROOT, "%.${digits}f", v)
