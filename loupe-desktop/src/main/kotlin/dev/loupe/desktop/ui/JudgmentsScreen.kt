package dev.loupe.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.loupe.desktop.core.Analysis
import dev.loupe.desktop.core.LoupeController
import dev.loupe.desktop.core.ModelState
import dev.loupe.desktop.core.Screen
import dev.loupe.desktop.core.SweepState
import dev.loupe.templates.Shape
import dev.loupe.templates.UserJudgment

@Composable
fun JudgmentsScreen(c: LoupeController) {
    var editing by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (c.judgments.isEmpty()) {
            Empty("No judgments yet", "Pick a template from the Library, or write your own question.") {
                PrimaryButton("Open the Library") { c.screen = Screen.LIBRARY }
            }
            return@Column
        }
        when (val m = c.model) {
            is ModelState.Unavailable -> Note("The model is not loaded, so judgments cannot run: ${m.message}", warn = true)
            ModelState.Loading -> Note("The model is loading; Run becomes available when it is ready.")
            is ModelState.Ready -> Muted("A run judges every item that has text, skipping ones already judged under the current wording. Items are read locally; it runs in the background and can be cancelled.")
        }
        if (c.scan.items.isEmpty()) Note("No items scanned yet — add a source first (Sources).", warn = true)
        c.sweep?.let { SweepPanel(c, it) }
        for (j in c.judgments) {
            JudgmentCard(c, j, editing == j.id, onEdit = { editing = if (editing == j.id) null else j.id })
        }
    }
}

@Composable
private fun SweepPanel(c: LoupeController, s: SweepState) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            H3((if (s.running) "Running: " else if (s.cancelled) "Cancelled: " else "Last run: ") + (c.judgment(s.judgmentId)?.title ?: s.judgmentId))
            Spacer(Modifier.weight(1f))
            if (s.running) SecondaryButton("Cancel") { c.cancelSweep() }
        }
        LinearProgressIndicator(progress = if (s.total == 0) 1f else s.done.toFloat() / s.total, modifier = Modifier.fillMaxWidth(), color = loupe.accent, backgroundColor = loupe.barTrack)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("${s.done} / ${s.total}", "items judged this run")
            Stat(fmt(s.itemsPerSecond, 1), "items per second")
            Stat(s.medianMillis?.let { fmt(it, 0) + " ms" } ?: "—", "median per model call")
            Stat(s.etaSeconds?.let { fmt(it, 0) + " s" } ?: "—", "left at this rate")
        }
        Muted(
            "${s.alreadyDecided} already judged under this wording (skipped) · ${s.withoutText} without text (not sent to the model) · " +
                "${s.mechanical} answered mechanically · ${s.unusable} unusable answers",
        )
        s.error?.let { Note("The run stopped: $it", error = true) }
    }
}

@Composable
private fun JudgmentCard(c: LoupeController, j: UserJudgment, editing: Boolean, onEdit: () -> Unit) {
    val summary = remember(c.ledger, c.correctionIndex, j) { Analysis.calibration(c.ledger, j, c.correctionIndex) }
    val running = c.sweep?.running == true
    val ready = c.model is ModelState.Ready
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                H2(j.title)
                Body(j.question, color = loupe.accent)
                Muted(
                    "${j.shape.candidates.size} options · threshold ${fmt(j.threshold)} (raw) · posture ${j.onFailure.name.lowercase()}" +
                        (j.templateId?.let { " · from template '$it'" } ?: " · written by you"),
                )
            }
            if (j.warnOnly) Pill("warn-only", color = loupe.warn, ground = loupe.warnGround)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat(summary.decisions.toString(), "items judged (current wording)")
            Stat(summary.corrections.toString(), "your corrections")
            Stat(pct(summary.coverage), "acted on at the threshold")
            Stat(pct(summary.declined), "left unsure")
        }
        if (summary.earlierWording > 0) Muted("${summary.earlierWording} decision(s) under earlier wording are kept in the ledger but not counted.")
        j.desktopNote?.let { Muted(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton("Run on new items", enabled = ready && !running && c.scan.items.isNotEmpty()) { c.sweep(j.id) }
            SecondaryButton("Re-run all", enabled = ready && !running && c.scan.items.isNotEmpty()) { c.sweep(j.id, rerunAll = true) }
            LinkButton("Results") { c.selectedJudgmentId = j.id; c.screen = Screen.RESULTS }
            LinkButton("Queue") { c.selectedJudgmentId = j.id; c.screen = Screen.QUEUE }
            LinkButton("Calibration") { c.selectedJudgmentId = j.id; c.screen = Screen.CALIBRATION }
            Spacer(Modifier.weight(1f))
            LinkButton(if (editing) "Close editor" else "Edit wording") { onEdit() }
            LinkButton("Delete") { c.deleteJudgment(j.id) }
        }
        if (editing) Editor(c, j)
    }
}

@Composable
private fun Editor(c: LoupeController, j: UserJudgment) {
    var title by remember(j.id) { mutableStateOf(j.title) }
    var question by remember(j.id) { mutableStateOf(j.question) }
    val editableOptions = j.shape is Shape.Pick || j.shape is Shape.Binary
    var options by remember(j.id) { mutableStateOf(j.shape.candidates.joinToString("\n")) }
    var invariant by remember(j.id) { mutableStateOf(j.invariant) }
    var breaks by remember(j.id) { mutableStateOf(j.breaks) }
    var lookalikes by remember(j.id) { mutableStateOf(j.lookalikes) }
    var problems by remember(j.id) { mutableStateOf<List<String>>(emptyList()) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Note("Changing the question or the options changes what the model is asked, so this judgment's calibration starts again. Criteria text is not read by the model; editing it keeps calibration.", warn = true)
        Field(title, { title = it }, "Name", singleLine = true)
        Field(question, { question = it }, "Question")
        if (editableOptions) Field(options, { options = it }, "Options, one per line", minLines = 2)
        Field(invariant, { invariant = it }, "True when…")
        Field(breaks, { breaks = it }, "False when…")
        Field(lookalikes, { lookalikes = it }, "Looks like it but isn't…")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Save") {
                val newOptions = if (editableOptions) options.lines().map { it.trim() }.filter { it.isNotEmpty() } else null
                problems = c.reword(j.id, question, newOptions, title, invariant, breaks, lookalikes)
            }
            Spacer(Modifier.width(4.dp))
        }
        for (p in problems) Body(p, color = loupe.warn)
    }
}
