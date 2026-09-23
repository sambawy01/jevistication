package dev.loupe.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.loupe.desktop.core.Analysis
import dev.loupe.desktop.core.DecisionView
import dev.loupe.desktop.core.LoupeController
import dev.loupe.desktop.core.Screen
import dev.loupe.engine.DecisionEngine
import dev.loupe.templates.UserJudgment

private enum class Sort(val title: String) { LEAST_SURE("least sure first"), MOST_SURE("most sure first"), NEWEST("newest first"), NAME("by name") }

private object ResultsState {
    var filter by mutableStateOf("all")
    var sort by mutableStateOf(Sort.LEAST_SURE)
    var query by mutableStateOf("")
    var selectedItem by mutableStateOf<String?>(null)
}

@Composable
fun ResultsScreen(c: LoupeController, platform: Platform) = NeedsJudgment(c) { j ->
    val views = remember(c.ledger, c.correctionIndex, j, c.itemsById) {
        Analysis.views(Analysis.effectiveRows(c.ledger, j, c.correctionIndex), j, c.itemsById)
    }
    if (views.isEmpty()) {
        Empty("\"${j.title}\" has not run yet", "Run it across your items from the Judgments screen; results appear here as it goes.") {
            PrimaryButton("Go to Judgments") { c.screen = Screen.JUDGMENTS }
        }
        return@NeedsJudgment
    }
    val filtered = views.filter { v ->
        when (val f = ResultsState.filter) {
            "all" -> true
            "unsure" -> !v.acted && !v.unusable
            "unusable" -> v.unusable
            "corrected" -> v.correction != null
            else -> v.acted && v.topLabel == f
        }
    }.filter { v ->
        val q = ResultsState.query.trim().lowercase()
        q.isEmpty() || (v.item?.name?.lowercase()?.contains(q) ?: false) || (v.item?.text?.lowercase()?.contains(q) ?: false)
    }.let { list ->
        when (ResultsState.sort) {
            Sort.LEAST_SURE -> list.sortedBy { it.topMass }
            Sort.MOST_SURE -> list.sortedByDescending { it.topMass }
            Sort.NEWEST -> list.sortedByDescending { it.item?.date }
            Sort.NAME -> list.sortedBy { it.item?.name?.lowercase() }
        }
    }
    val corrections = views.count { it.correction != null }
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Note(
            "Numbers are the model's raw probabilities — uncalibrated. You have corrected $corrections item(s); calibration is measured on the Calibration screen once there are ${Analysis.MIN_FOR_RELIABILITY}. " +
                "Items at or above the threshold (${fmt(j.threshold)}) are answered; below it the engine is unsure and they wait in the queue.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            val counts = views.groupingBy { if (it.unusable) "unusable" else if (it.acted) it.topLabel else "unsure" }.eachCount()
            Pill("All ${views.size}", selected = ResultsState.filter == "all", onClick = { ResultsState.filter = "all" })
            for (label in j.shape.candidates) {
                val n = counts[label] ?: 0
                if (n > 0) Pill("${shownAnswer(j, label)} $n", selected = ResultsState.filter == label, onClick = { ResultsState.filter = label })
            }
            Pill("Unsure ${counts["unsure"] ?: 0}", selected = ResultsState.filter == "unsure", onClick = { ResultsState.filter = "unsure" })
            if ((counts["unusable"] ?: 0) > 0) Pill("Could not judge ${counts["unusable"]}", color = loupe.error, selected = ResultsState.filter == "unusable", onClick = { ResultsState.filter = "unusable" })
            Pill("Corrected $corrections", selected = ResultsState.filter == "corrected", onClick = { ResultsState.filter = "corrected" })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Field(ResultsState.query, { ResultsState.query = it }, "Search names and text", singleLine = true, modifier = Modifier.width(320.dp))
            Spacer(Modifier.width(8.dp))
            Muted("Sort:")
            for (s in Sort.entries) Pill(s.title, selected = ResultsState.sort == s, onClick = { ResultsState.sort = s })
        }
        val unusable = views.count { it.unusable }
        if (unusable > 0) {
            Note(
                "$unusable item(s) could not be judged — the model's answer was unusable. " +
                    if (j.onFailure == dev.loupe.engine.FailurePosture.LOUD) "This judgment is LOUD: check these yourself; silence here is not \"nothing found\"." else "Nothing was done with them.",
                error = true,
            )
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LazyColumn(Modifier.weight(1.2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(filtered, key = { it.row.itemId ?: it.hashCode().toString() }) { v -> ResultRow(j, v, ResultsState.selectedItem == v.row.itemId) { ResultsState.selectedItem = v.row.itemId } }
            }
            val selected = filtered.firstOrNull { it.row.itemId == ResultsState.selectedItem } ?: filtered.firstOrNull()
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (selected != null) ItemDetail(c, j, selected, platform)
            }
        }
    }
}

@Composable
private fun ResultRow(j: UserJudgment, v: DecisionView, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (selected) loupe.raised else loupe.panel).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(v.item?.name ?: v.row.itemId ?: "?", fontSize = 13.sp, color = loupe.ink, maxLines = 1)
            Text(listOfNotNull(v.item?.kind?.title, v.item?.date?.toString(), v.item?.duplicateOf?.let { "exact duplicate" }).joinToString(" · "), fontSize = 11.sp, color = loupe.faint)
        }
        Column(Modifier.width(230.dp), horizontalAlignment = Alignment.End) {
            val (text, color) = when {
                v.unusable -> "could not judge" to loupe.error
                v.acted -> shownAnswer(j, v.topLabel) to loupe.ink
                else -> "unsure — leans ${shownAnswer(j, v.topLabel)}" to loupe.warn
            }
            Text(text, fontSize = 12.sp, color = color, fontWeight = FontWeight.Medium, maxLines = 1)
            ProbabilityBar(v.topMass, width = 110, threshold = j.threshold)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                v.correction == null -> ""
                v.correction == v.topLabel -> "✓ you agreed"
                else -> "✎ ${shownAnswer(j, v.correction)}"
            },
            fontSize = 11.sp, color = loupe.accent, modifier = Modifier.width(110.dp), maxLines = 1,
        )
    }
}

/** One item: its mechanical facts, the full raw distribution, and one-click corrections. */
@Composable
fun ItemDetail(c: LoupeController, j: UserJudgment, v: DecisionView, platform: Platform) {
    val item = v.item
    Card(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        H2(item?.name ?: "Item no longer scanned")
        if (item == null) {
            Muted("This decision is about ${v.row.itemId}, which is not in the current scan (its source was removed or moved). The ledger keeps it.")
            return@Card
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Open") { platform.open(item.path) }
            SecondaryButton("Show in Finder") { platform.reveal(item.path) }
        }
        H3("What the model said (raw)")
        for (label in v.row.distribution.labels) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(shownAnswer(j, label), fontSize = 12.sp, color = loupe.ink, modifier = Modifier.width(230.dp), maxLines = 1)
                ProbabilityBar(v.row.distribution.getValue(label).value, width = 140)
            }
        }
        if (v.mechanical) {
            val copyOf = item.duplicateOf?.let { " — a byte-identical copy of $it" } ?: ""
            Muted("Answered by rule: ${v.mechanicalCheck}$copyOf; the model was not asked.")
        }
        v.row.failure?.let { Note("Unusable answer: $it", error = true) }
        v.inputCutNote?.let { Muted(it) }
        H3("Your answer")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (label in j.shape.candidates) {
                Pill(
                    shownAnswer(j, label) + if (label == v.topLabel) " (model)" else "",
                    selected = v.correction == label,
                    onClick = { c.correct(j.id, item.id, label, confirmed = label == v.topLabel) },
                )
            }
        }
        Muted("Corrections are recorded, never overwritten; each one is an outcome in the ledger that calibration, the slider and the baseline read.")
        Note("Preview only: Loupe never changes your files in this version. " + actionPreview(j, v))
        H3("Mechanical facts")
        Fact("Location", item.location)
        Fact("Type", "${item.kind.title} (${item.mime})")
        Fact("Size", "${item.sizeBytes} bytes")
        Fact("SHA-256", item.contentHash.take(16) + "…")
        item.date?.let { Fact("Date", "$it (from the ${item.dateOrigin?.title})") }
        item.email?.let { e ->
            Fact("From", listOfNotNull(e.fromName, e.fromAddress?.let { "<$it>" }).joinToString(" "))
            e.subject?.let { Fact("Subject", it) }
            if (e.links.isNotEmpty()) Fact("Links", e.links.joinToString("\n"))
        }
        item.duplicateOf?.let { Fact("Exact duplicate of", it) }
        for ((k, value) in item.facts) Fact(k, value)
        H3("What the model read")
        val read = item.text.take(DecisionEngine.DEFAULT_STATE_BUDGET)
        Mono(read.take(3000) + if (read.length > 3000) "\n…" else "", color = loupe.muted)
        if (item.text.length > DecisionEngine.DEFAULT_STATE_BUDGET) Muted("The model reads the first ${DecisionEngine.DEFAULT_STATE_BUDGET} characters, cut with an explicit marker, never summarised.")
    }
}

private fun actionPreview(j: UserJudgment, v: DecisionView): String = when {
    !v.acted && v.row.truncated && v.topMass >= j.threshold -> "The model read only part of this item, so it is not acted on; it waits for you."
    !v.acted -> "Unsure items are never acted on; this one waits for you."
    j.templateId in setOf("is-duplicate", "is-junk", "is-stale", "refetchable-download", "superseded-version") && v.topLabel == j.positiveLabel ->
        "The action would be: move to a review folder — and the phone build would ask you first."
    j.warnOnly && v.topLabel == j.positiveLabel -> "The action would be: warn you before you act on this message."
    else -> "The action would be: file it with the others that share this answer."
}

/** Items for the queue's detail pane reuse [ItemDetail]; this finds the view for an item. */
fun viewFor(c: LoupeController, j: UserJudgment, itemId: String): DecisionView? =
    Analysis.views(Analysis.effectiveRows(c.ledger, j, c.correctionIndex).filter { it.itemId == itemId }, j, c.itemsById).firstOrNull()
