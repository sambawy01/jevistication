package dev.loupe.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.loupe.desktop.core.Analysis
import dev.loupe.desktop.core.LoupeController
import dev.loupe.desktop.core.Screen
import dev.loupe.engine.DecisionEngine
import dev.loupe.engine.SelectionReason

private val DIGITS = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)

/**
 * The uncertain queue (D1): the decisions a correction teaches most, plus a random audit arm of
 * confident ones. One keystroke per answer; every answer is appended to the corrections log.
 */
@Composable
fun QueueScreen(c: LoupeController) = NeedsJudgment(c) { j ->
    val queue = remember(c.ledger, c.correctionIndex, j) { Analysis.queue(c.ledger, j, c.correctionIndex) }
    var index by remember(j.id) { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(j.id, queue.size) { runCatching { focus.requestFocus() } }
    if (index >= queue.size) index = (queue.size - 1).coerceAtLeast(0)
    val entry = queue.getOrNull(index)
    val item = entry?.row?.itemId?.let { c.itemsById[it] }

    fun answer(label: String) {
        if (entry == null || item == null) return
        c.correct(j.id, item.id, label, confirmed = label == entry.row.distribution.argmax)
        // The answered item leaves the queue; the same index now points at the next one.
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 8.dp).focusRequester(focus).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown || entry == null) return@onPreviewKeyEvent false
            val digit = DIGITS.indexOf(e.key)
            when {
                digit in j.shape.candidates.indices -> { answer(j.shape.candidates[digit]); true }
                e.key == Key.Enter -> { answer(entry.row.distribution.argmax); true }
                e.key == Key.S || e.key == Key.DirectionRight || e.key == Key.DirectionDown -> { index = (index + 1).coerceAtMost(queue.size - 1); true }
                e.key == Key.DirectionLeft || e.key == Key.DirectionUp -> { index = (index - 1).coerceAtLeast(0); true }
                e.key == Key.U -> { c.undoLastCorrection(j.id); true }
                else -> false
            }
        },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (queue.isEmpty()) {
            Empty("Nothing waiting", if (c.ledger.none { it.judgmentId == j.id }) "Run \"${j.title}\" first; the queue fills with what it is least sure about." else "Every decision for \"${j.title}\" under its current wording has your answer.") {
                PrimaryButton("Go to Judgments") { c.screen = Screen.JUDGMENTS }
            }
            return@Column
        }
        Note(
            "Keys: 1–${j.shape.candidates.size} give that answer · Enter agrees with the model · S or → skips · ← goes back · U undoes your last answer. " +
                "Most items are chosen because the model is torn (${queue.count { it.reason == SelectionReason.UNCERTAIN }}); a few confident ones are picked at random " +
                "(${queue.count { it.reason == SelectionReason.AUDIT }}) so your answers also catch it being confidently wrong.",
        )
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LazyColumn(Modifier.width(300.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(queue, key = { _, e -> e.row.itemId ?: e.hashCode().toString() }) { i, e ->
                    val name = e.row.itemId?.let { c.itemsById[it]?.name } ?: e.row.itemId ?: "?"
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (i == index) loupe.raised else loupe.panel).clickable { index = i }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(name, fontSize = 12.sp, color = loupe.ink, maxLines = 1)
                            Text(if (e.reason == SelectionReason.AUDIT) "random audit (confident)" else "torn: " + fmt(e.informativeness), fontSize = 11.sp, color = loupe.faint)
                        }
                    }
                }
            }
            Card(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                if (entry == null || item == null) {
                    Muted("This item is no longer in the scan.")
                    return@Card
                }
                Muted("${index + 1} of ${queue.size} · " + if (entry.reason == SelectionReason.AUDIT) "picked at random from confident answers" else "picked because the model is torn")
                H2(item.name)
                Body(j.question, color = loupe.accent)
                val top = entry.row.distribution.argmax
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    j.shape.candidates.forEachIndexed { i, label ->
                        val mass = entry.row.distribution.getValue(label).value
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (label == top) loupe.raised else loupe.panel).clickable { answer(label) }.padding(10.dp),
                        ) {
                            Text("${i + 1}", fontSize = 11.sp, color = loupe.faint)
                            Text(shownAnswer(j, label), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = loupe.ink, maxLines = 2)
                            ProbabilityBar(mass, width = 80)
                            if (label == top) Text("model's pick (raw)", fontSize = 10.sp, color = loupe.faint)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Skip") { index = (index + 1).coerceAtMost(queue.size - 1) }
                    LinkButton("Undo last answer") { c.undoLastCorrection(j.id) }
                    Spacer(Modifier.weight(1f))
                }
                H3("From " + (item.email?.fromName ?: item.kind.title))
                Mono(item.text.take(DecisionEngine.DEFAULT_STATE_BUDGET).take(2500), color = loupe.muted)
            }
        }
    }
}
