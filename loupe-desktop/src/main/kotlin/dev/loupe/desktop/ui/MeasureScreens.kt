package dev.loupe.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.loupe.desktop.core.Analysis
import dev.loupe.desktop.core.LoupeController
import dev.loupe.desktop.core.Screen
import dev.loupe.engine.Policy

/** D2: per judgment only — there is no screen, and no API, for an average across judgments. */
@Composable
fun CalibrationScreen(c: LoupeController) = NeedsJudgment(c) { j ->
    val s = remember(c.ledger, c.correctionIndex, j) { Analysis.calibration(c.ledger, j, c.correctionIndex) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Note("This is one judgment's record, on your corrections only. There is deliberately no overall accuracy: \"is this a receipt\" and \"is this urgent\" are different problems, and an average would hide both.")
        if (s.decisions == 0) {
            Empty("Nothing measured yet", "Run \"${j.title}\" and answer some items in the queue.") { PrimaryButton("Go to Judgments") { c.screen = Screen.JUDGMENTS } }
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat(s.decisions.toString(), "items judged (current wording)")
            Stat(s.corrections.toString(), "your corrections")
            Stat(pct(s.coverage), "coverage: answered at the threshold")
            Stat(pct(s.declined), "declined: left unsure")
            Stat(s.unusable.toString(), "could not judge", color = if (s.unusable > 0) loupe.error else loupe.ink)
        }
        Card {
            H3("Agreement with you")
            if (s.agreement == null) {
                Body("${Analysis.MIN_FOR_AGREEMENT - s.corrections} more correction(s) needed before an agreement figure is shown — below ${Analysis.MIN_FOR_AGREEMENT} it would mostly be noise.", color = loupe.warn)
            } else {
                Fact("Selective accuracy", s.selectiveAccuracy?.let { "${pct(it, 1)} on the ${s.selectiveN} corrected item(s) it answered" } ?: "no corrected item was above the threshold")
                Fact("Agreement, forced to answer", "${pct(s.agreement!!, 1)} over ${s.corrections} correction(s)")
                Muted("Selective accuracy is the number to watch: what the engine gets right when it does answer. Your corrections lean towards the items it was unsure about, so these read harsher than a random sample would.")
            }
        }
        Card {
            H3("Calibration (is its confidence honest?)")
            val view = s.view
            if (view == null) {
                Body("${s.correctionsNeededForReliability} more correction(s) needed for a reliability diagram and ECE. Until then the probabilities shown are the model's raw output, uncalibrated.", color = loupe.warn)
            } else {
                Fact("ECE", fmt(view.ece ?: 0.0, 3) + " (0 is perfectly calibrated)")
                Reliability(view.reliability)
                if (view.overconfidentBins.isNotEmpty()) {
                    Note(
                        "Over-confident where it claims " + view.overconfidentBins.joinToString { "${pct(it.lower)}–${pct(it.upper)} (right ${pct(it.accuracy)} of ${it.count})" },
                        warn = true,
                    )
                }
            }
            s.heldOut?.let { h ->
                Fact("Temperature fit, held out", "T=${fmt(h.temperature)} fitted on ${h.fitN}, scored on ${h.testN}: ECE ${fmt(h.rawEce, 3)} raw → ${fmt(h.fittedEce, 3)} fitted")
                Muted("Shown for information; the app still acts on raw probabilities.")
            } ?: Muted("A held-out temperature fit appears at ${Analysis.MIN_FOR_FIT} corrections (half to fit, half to check).")
        }
        if (s.earlierWording > 0) Muted("${s.earlierWording} decision(s) made under earlier wording are not counted here.")
    }
}

@Composable
private fun Reliability(bins: List<dev.loupe.engine.ReliabilityBin>) {
    Row(Modifier.fillMaxWidth().height(140.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        for (b in bins) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (b.count == 0) "" else pct(b.accuracy), fontSize = 9.sp, color = loupe.muted)
                Box(Modifier.fillMaxWidth().height((100 * (if (b.count == 0) 0.0 else b.accuracy)).dp).background(if (b.count == 0) loupe.barTrack else loupe.bar))
                Text("${(b.lower * 100).toInt()}", fontSize = 9.sp, color = loupe.faint)
                Text("n=${b.count}", fontSize = 9.sp, color = loupe.faint)
            }
        }
    }
    Muted("Bars: how often it was right in each confidence band (x: claimed confidence, %). A calibrated model's bar reaches its band.")
}

/** The slider's position per judgment, kept across screen switches (and settable for snapshots). */
internal object ThresholdState {
    val candidates = androidx.compose.runtime.mutableStateMapOf<String, Float>()
}

/** D3: move the threshold and see, over decisions already logged, what would change. */
@Composable
fun ThresholdScreen(c: LoupeController) = NeedsJudgment(c) { j ->
    val candidate: Float = ThresholdState.candidates[j.id] ?: j.threshold.toFloat()
    val preview = remember(c.ledger, c.correctionIndex, j, candidate) { Analysis.preview(c.ledger, j, c.correctionIndex, candidate.toDouble()) }
    val masses = remember(c.ledger, c.correctionIndex, j) {
        Analysis.effectiveRows(c.ledger, j, c.correctionIndex).filter { it.action != Policy.UNUSABLE }.map { it.distribution.getValue(it.distribution.argmax).value }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (masses.isEmpty()) {
            Empty("No logged decisions", "The preview is counted from decisions already made, so run \"${j.title}\" first.") { PrimaryButton("Go to Judgments") { c.screen = Screen.JUDGMENTS } }
            return@Column
        }
        Card {
            H3("Act when the model's top answer is at least ${fmt(candidate.toDouble())} (raw)")
            Slider(
                value = candidate,
                onValueChange = { ThresholdState.candidates[j.id] = Math.round(it * 100) / 100f },
                valueRange = 0.3f..0.99f,
                colors = SliderDefaults.colors(thumbColor = loupe.accent, activeTrackColor = loupe.accent, inactiveTrackColor = loupe.barTrack),
            )
            if (preview.additionalActions == 0 && preview.fewerActions == 0) {
                Body("Move the slider: the preview counts what a different threshold would have changed about the ${preview.rowsConsidered} decisions already logged.")
            } else {
                Body(preview.summary(), color = if (preview.mistakesIntroduced == null && preview.additionalActions > 0) loupe.warn else loupe.ink)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("+${preview.additionalActions}", "more acted on")
                Stat("−${preview.fewerActions}", "fewer acted on")
                Stat(preview.mistakesIntroduced?.toString() ?: "unknown", "you would have rescued (from your corrections)")
                Stat(preview.correctionsConsulted.toString(), "corrections this rests on")
            }
            Muted("Counted over ${preview.rowsConsidered} logged decision(s), not predicted. Where none of the newly-acted items carries your correction, the cost is reported as unknown rather than guessed.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Use ${fmt(candidate.toDouble())}", enabled = candidate.toDouble() != j.threshold) { c.setThreshold(j.id, candidate.toDouble()) }
                SecondaryButton("Reset to current (${fmt(j.threshold)})") { ThresholdState.candidates.remove(j.id) }
            }
        }
        Card {
            H3("Where its answers sit")
            val bins = 14
            val counts = IntArray(bins)
            for (m in masses) counts[((m - 0.3) / 0.7 * bins).toInt().coerceIn(0, bins - 1)]++
            val max = counts.maxOrNull() ?: 0
            Row(Modifier.fillMaxWidth().height(100.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                for (i in 0 until bins) {
                    val lower = 0.3 + 0.7 * i / bins
                    Box(Modifier.weight(1f).height((if (max == 0) 0 else 90 * counts[i] / max).dp).background(if (lower >= candidate) loupe.bar else loupe.barTrack))
                }
            }
            Muted("Top-answer probability of each logged decision, 0.3 to 1.0. Coloured bars are what the candidate threshold would act on.")
        }
    }
}

/** D4: model against the template's dumb baseline, on the items you have corrected. */
@Composable
fun BaselineScreen(c: LoupeController) = NeedsJudgment(c) { j ->
    val comparison = remember(c.ledger, c.correctionIndex, j, c.itemsById) { Analysis.baseline(c.ledger, j, c.correctionIndex, c.itemsById) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card {
            H3("The dumb version")
            Body(j.baseline?.description ?: "This judgment has no baseline.")
            Muted("Every judgment is measured against the version with no model. Upstream, a model-chosen method lost to \"keep the last 24k characters\" and they shipped the plain one; the same can happen here, and this screen says so.")
        }
        if (j.baseline == null) return@Column
        if (comparison == null) {
            Empty("Nothing to compare yet", "The comparison runs on items you have corrected. Answer some in the queue.") { PrimaryButton("Open the queue") { c.screen = Screen.QUEUE } }
            return@Column
        }
        val r = comparison.report
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat(pct(r.accuracyAtFullCoverage, 1), "model, forced to answer every item")
            Stat(pct(r.baselineAccuracy, 1), "dumb baseline, same items")
            Stat(if (r.coverage == 0.0) "—" else pct(r.selectiveAccuracy, 1), "model when it does answer (${pct(r.coverage)} of items)")
            Stat(r.n.toString(), "corrected items compared")
        }
        when {
            r.n < Analysis.MIN_FOR_BASELINE -> Note("Only ${r.n} corrected item(s): too few to say which wins. ${Analysis.MIN_FOR_BASELINE - r.n} more, please.", warn = true)
            r.beatsBaseline -> Note("On your ${r.n} corrected items the model beats the baseline, compared at equal (full) coverage.")
            else -> Note("The model is NOT beating the dumb baseline on your ${r.n} corrected items. Until it does, the baseline is the honest choice for this judgment.", warn = true)
        }
        Muted("Compared at equal coverage — both forced to answer every item — through the engine's own Harness, replaying exactly what the model logged rather than re-running it.")
    }
}
