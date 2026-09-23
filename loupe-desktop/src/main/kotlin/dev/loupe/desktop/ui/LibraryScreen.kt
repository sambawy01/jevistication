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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
import androidx.compose.material.RadioButtonDefaults
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.loupe.desktop.core.LoupeController
import dev.loupe.desktop.core.Screen
import dev.loupe.engine.FailurePosture
import dev.loupe.templates.Category
import dev.loupe.templates.JudgmentDraft
import dev.loupe.templates.ParamKind
import dev.loupe.templates.Shape
import dev.loupe.templates.Template
import dev.loupe.templates.TemplateLibrary

/** Library state that outlives switching screens. */
internal object LibraryState {
    var writing by mutableStateOf(false)
    var query by mutableStateOf("")
    var category by mutableStateOf<Category?>(null)
    var selectedId by mutableStateOf(TemplateLibrary.ALL.first().id)
}

@Composable
fun LibraryScreen(c: LoupeController) {
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("Browse ${TemplateLibrary.ALL.size} templates", selected = !LibraryState.writing, onClick = { LibraryState.writing = false })
            Pill("Write your own", selected = LibraryState.writing, onClick = { LibraryState.writing = true })
            Spacer(Modifier.weight(1f))
            Muted("Templates are starting points: using one makes a judgment you own and can reword.")
        }
        if (LibraryState.writing) WriteYourOwn(c) else Browse(c)
    }
}

@Composable
private fun Browse(c: LoupeController) {
    val matches = TemplateLibrary.search(LibraryState.query, LibraryState.category)
    val selected = TemplateLibrary.byId(LibraryState.selectedId) ?: TemplateLibrary.ALL.first()
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(200.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Field(LibraryState.query, { LibraryState.query = it }, "Search", singleLine = true)
            Spacer(Modifier.height(8.dp))
            CategoryRow("All categories", TemplateLibrary.ALL.size, LibraryState.category == null) { LibraryState.category = null }
            for (cat in Category.entries) {
                CategoryRow(cat.title, TemplateLibrary.inCategory(cat).size, LibraryState.category == cat) { LibraryState.category = cat }
            }
        }
        LazyColumn(Modifier.width(330.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (matches.isEmpty()) item { Muted("No template matches \"${LibraryState.query}\". Try Write your own.") }
            items(matches, key = { it.id }) { t ->
                val on = t.id == selected.id
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (on) loupe.raised else loupe.panel)
                        .clickable { LibraryState.selectedId = t.id }.padding(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = loupe.ink, modifier = Modifier.weight(1f))
                        if (t.warnOnly) Pill("warn-only", color = loupe.warn, ground = loupe.warnGround)
                    }
                    Text(t.question, fontSize = 12.sp, color = loupe.muted, maxLines = 2)
                    Text(t.category.title + " · " + shapeName(t.shape), fontSize = 11.sp, color = loupe.faint)
                }
            }
        }
        TemplateDetail(c, selected, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun CategoryRow(title: String, n: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (selected) loupe.raised else loupe.ground).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(title, fontSize = 13.sp, color = if (selected) loupe.ink else loupe.muted, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
        Text(n.toString(), fontSize = 11.sp, color = loupe.faint)
    }
}

private fun shapeName(shape: Shape): String = when (shape) {
    Shape.YesNo, is Shape.Binary -> "yes/no"
    is Shape.Pick -> "choice of ${shape.candidates.size}"
    is Shape.Ordinal -> "score ${shape.range.first}–${shape.range.last}"
}

private fun postureText(p: FailurePosture): String = when (p) {
    FailurePosture.LOUD -> "LOUD — if the model's answer cannot be used, you are told; quiet failure here would look like \"nothing to worry about\"."
    FailurePosture.NULL_ACTION -> "Null action — an unusable answer changes nothing and the item waits for you."
    FailurePosture.OPEN -> "Open — an unusable answer simply finds nothing; nothing depends on it."
}

@Composable
private fun TemplateDetail(c: LoupeController, t: Template, modifier: Modifier) {
    val values = remember(t.id) { mutableStateMapOf<String, String>().apply { t.parameters.forEach { put(it.name, "") } } }
    var problems by remember(t.id) { mutableStateOf<List<String>>(emptyList()) }
    Card(modifier.verticalScroll(rememberScrollState())) {
        Muted(t.category.title.uppercase())
        H2(t.title)
        Body(t.question, color = loupe.accent)
        t.desktopNote?.let { Note(it, warn = t.warnOnly) }
        H3("Answers")
        when (val s = t.shape) {
            is Shape.Binary -> {
                Body("Two options, each saying what it means (the model reads them):")
                Mono("• ${s.positive}\n• ${s.negative}")
                if (t.warnOnly) Muted("Warn-only: the second option is never shown to you as \"safe\" — the app shows \"no signal\".")
            }
            is Shape.Pick -> {
                Mono(s.candidates.joinToString("\n") { "• $it" + if (it == s.noOp) "   (the no-op: nothing here applies)" else "" })
            }
            is Shape.Ordinal -> Mono(s.candidates.zip(s.bands).joinToString("\n") { (v, b) -> "• $v — $b" })
            Shape.YesNo -> Mono("• yes\n• no")
        }
        H3("What it means")
        Fact("True when", t.invariant)
        Fact("False when", t.breaks)
        Fact("Looks like it, isn't", t.lookalikes)
        Muted("Today the model reads the question and the options. These three parts are for you and for correcting it — Laya's prompt has no slot for them yet.")
        H3("How it behaves")
        Fact("If the model fails", postureText(t.onFailure))
        Fact("Written for", t.sources.joinToString { it.title })
        Fact("Dumb baseline", t.baseline?.description ?: "none")
        t.mechanical?.let { Fact("Mechanical first", it.description) }
        H3("Examples")
        for (e in t.examples) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(loupe.raised).padding(8.dp)) {
                Mono(e.text, color = loupe.muted)
                Text("→ ${e.answer}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = loupe.ink)
                Muted(e.why)
            }
        }
        if (t.parameters.isNotEmpty()) {
            H3("Fill in")
            for (p in t.parameters) {
                val hint = when (p.kind) {
                    ParamKind.DATE -> " (yyyy-mm-dd)"
                    ParamKind.SENDER -> " (name, address or domain)"
                    ParamKind.TEXT -> ""
                }
                Field(values[p.name] ?: "", { values[p.name] = it }, p.label + hint, placeholder = p.example, singleLine = true)
                val v = values[p.name].orEmpty()
                if (v.isNotBlank()) p.validate(v)?.let { Body(it, color = loupe.warn) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Use template") {
                problems = c.useTemplate(t, values.toMap())
                if (problems.isEmpty()) c.screen = Screen.JUDGMENTS
            }
            Muted("Creates a judgment you own. Rewording it later restarts its calibration.")
        }
        for (p in problems) Body(p, color = loupe.warn)
    }
}

@Composable
private fun WriteYourOwn(c: LoupeController) {
    var title by remember { mutableStateOf("") }
    var question by remember { mutableStateOf("") }
    var options by remember { mutableStateOf("") }
    var invariant by remember { mutableStateOf("") }
    var breaks by remember { mutableStateOf("") }
    var lookalikes by remember { mutableStateOf("") }
    var keywords by remember { mutableStateOf("") }
    var posture by remember { mutableStateOf(FailurePosture.NULL_ACTION) }
    var result by remember { mutableStateOf<List<String>>(emptyList()) }
    val draft = JudgmentDraft(
        title = title,
        question = question,
        options = options.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() },
        invariant = invariant,
        breaks = breaks,
        lookalikes = lookalikes,
        baselineKeywords = keywords.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        onFailure = posture,
    )
    val findings = if (question.isBlank()) emptyList() else draft.findings()
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            H2("Write your own judgment")
            Field(title, { title = it }, "Name (for your lists)", singleLine = true)
            Field(question, { question = it }, "The question, in plain language", placeholder = "Is this from my landlord?")
            Field(options, { options = it }, "Options — one per line or comma-separated (leave empty for yes/no)", placeholder = "from my landlord\nnot from my landlord", minLines = 3)
            Muted("Tip: two options that say what they mean (\"a receipt\" / \"not a receipt\") did better than bare yes/no on the sample data. Add a no-op option such as \"not sure\" or \"none of these\" to a choice so the model is not forced to pick.")
            Field(invariant, { invariant = it }, "True when…")
            Field(breaks, { breaks = it }, "False when…")
            Field(lookalikes, { lookalikes = it }, "Looks like it but isn't…")
            Field(keywords, { keywords = it }, "Dumb-baseline keywords (comma-separated; two-option questions)", singleLine = true)
            H3("If the model's answer cannot be used")
            for (p in FailurePosture.entries) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { posture = p }) {
                    RadioButton(selected = posture == p, onClick = { posture = p }, colors = RadioButtonDefaults.colors(selectedColor = loupe.accent))
                    Muted(postureText(p))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Create judgment", enabled = question.isNotBlank() && findings.isEmpty()) {
                    result = c.createFromDraft(draft)
                    if (result.isEmpty()) c.screen = Screen.JUDGMENTS
                }
            }
            for (r in result) Body(r, color = loupe.warn)
        }
        Card(Modifier.width(360.dp)) {
            H3("Checked as you type")
            when {
                question.isBlank() -> Muted("Start with a question. It is linted live: this is a classifier, so it refuses rating scales (\"rate 1–10\"), requests for prose (\"explain why\"), requests to write, and two questions in one.")
                findings.isEmpty() -> Note("Compiles: ${draft.options.ifEmpty { listOf("yes", "no") }.joinToString(" · ")}")
                else -> for (f in findings) Note("${f.rule}: ${f.message}", warn = true)
            }
        }
    }
}

/** A labelled text field in the app's colours. */
@Composable
fun Field(value: String, onChange: (String) -> Unit, label: String, placeholder: String = "", singleLine: Boolean = false, minLines: Int = 1, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder, fontSize = 12.sp, color = loupe.faint) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else minLines,
        modifier = modifier.fillMaxWidth(),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = loupe.ink),
        colors = TextFieldDefaults.outlinedTextFieldColors(focusedBorderColor = loupe.accent, unfocusedBorderColor = loupe.line, focusedLabelColor = loupe.accent, cursorColor = loupe.accent, textColor = loupe.ink, backgroundColor = loupe.panel),
    )
}
