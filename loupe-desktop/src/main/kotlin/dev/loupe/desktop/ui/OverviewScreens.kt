package dev.loupe.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.loupe.desktop.core.Analysis
import dev.loupe.desktop.core.Census
import dev.loupe.desktop.core.LoupeController
import dev.loupe.desktop.core.ModelState
import dev.loupe.desktop.core.Screen
import java.nio.file.Path
import java.util.Locale

/** Census: classify, then count — by answer, by month, by source. Counting, never prose. */
@Composable
fun CensusScreen(c: LoupeController) {
    var judgmentId by remember { mutableStateOf<String?>(null) }
    val j = judgmentId?.let(c::judgment)
    val census: Census = remember(c.ledger, c.correctionIndex, j, c.itemsById, c.scan) {
        if (j == null) {
            Analysis.itemCensus(c.scan.items, c.sourceNames)
        } else {
            Analysis.census(Analysis.views(Analysis.effectiveRows(c.ledger, j, c.correctionIndex), j, c.itemsById), c.sourceNames)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Muted("Count:")
            Pill("Everything scanned", selected = j == null, onClick = { judgmentId = null })
            for (x in c.judgments) Pill(x.title, selected = x.id == judgmentId, onClick = { judgmentId = x.id })
        }
        if (census.total == 0) {
            Empty(if (j == null) "Nothing scanned yet" else "\"${j.title}\" has not run yet", if (j == null) "Add a source to count what is in it." else "Run it from Judgments, then count its answers here.")
            return@Column
        }
        Muted(
            if (j == null) {
                "${census.total} items, by type, month and source. Dates come from email headers, EXIF, PDF metadata or, failing those, the file's modified time."
            } else {
                "${census.total} items answered by \"${j.title}\". Unsure items are counted as unsure, not guessed; your corrections replace the model's answer."
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(Modifier.weight(1f)) {
                H3(if (j == null) "By type" else "By answer")
                val max = census.byAnswer.maxOfOrNull { it.second } ?: 0
                for ((k, n) in census.byAnswer) CountBar(if (j == null) k else shownCensus(c, j.id, k), n, max)
            }
            Card(Modifier.weight(1f)) {
                H3("By source")
                val max = census.bySource.maxOfOrNull { it.second } ?: 0
                for ((k, n) in census.bySource) CountBar(k, n, max)
            }
        }
        Card {
            H3("By month")
            val max = census.byMonth.maxOfOrNull { it.second } ?: 0
            for ((k, n) in census.byMonth) CountBar(k, n, max)
        }
    }
}

private fun shownCensus(c: LoupeController, judgmentId: String, key: String): String {
    val j = c.judgment(judgmentId) ?: return key
    return if (key in j.shape.candidates) shownAnswer(j, key) else key
}

/** C3: the five watchers. Warnings only — there is no "all clear" on this screen. */
@Composable
fun WatchersScreen(c: LoupeController, platform: Platform) {
    LaunchedEffect(c.scan, c.watcherReport == null) {
        if (c.watcherReport == null && c.scan.items.isNotEmpty() && !c.watchersRunning) c.runWatchers()
    }
    val r = c.watcherReport
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Note("The watchers warn; they never bless. An empty section means these particular checks found nothing — not that everything is fine.", modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            SecondaryButton("Run again", enabled = !c.watchersRunning && c.scan.items.isNotEmpty()) { c.runWatchers() }
        }
        if (c.watchersRunning) LinearProgressIndicator(color = loupe.accent, backgroundColor = loupe.barTrack)
        if (r == null) {
            if (c.scan.items.isEmpty()) Empty("Nothing to watch yet", "Add a source first.") { PrimaryButton("Go to Sources") { c.screen = Screen.SOURCES } }
            return@Column
        }
        Card {
            H2("Expiry radar")
            Muted("Rule: ${r.rule.name}, from ${r.today}. Dates are read mechanically; the latest date on a document is its expiry, and an ambiguous date is taken on its earlier reading.")
            val alerts = r.expiryAlerts
            if (alerts == null) {
                Note("The model is not loaded, so what each document *is* has not been judged — below are documents with an expiry word and a date, found mechanically.", warn = true)
            } else {
                for (a in alerts) {
                    Note("${a.documentType}: expires ${a.expiry} — ${a.daysRemaining} days — breaches \"${a.rule.name}\"" + if (a.dateWasAmbiguous) " (the date could be read two ways; the earlier was used)" else "", warn = true)
                }
                if (alerts.isEmpty()) Muted("The model judged none of the candidates below to be an expiring document of a listed type.")
            }
            for (e in r.expiryCandidates) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Body("${e.item.name} — latest date ${e.expiry} (${e.daysRemaining} days)" + if (e.breachesRule) " — inside the rule" else "", color = if (e.breachesRule) loupe.warn else loupe.ink, modifier = Modifier.weight(1f))
                    LinkButton("Open") { platform.open(e.item.path) }
                }
            }
            if (r.expiryCandidates.isEmpty()) Muted("No document with an expiry word and a date within a year was found.")
        }
        Card {
            H2("Recurring money")
            Muted("${r.chargesFound} charge(s) read from emails that say something was charged or paid, and from CSV files with merchant, date and amount columns. A cadence needs three charges.")
            for (rc in r.recurring) {
                Body(
                    "${rc.merchant}: ${rc.cadence.name.lowercase()}, ${rc.occurrences} charges, typically ${money(rc.typicalAmountMinor)}, last ${rc.lastCharged} (${rc.daysSinceLastCharge} days ago)",
                    color = if (rc.daysSinceLastCharge > 45 && rc.cadence.name == "MONTHLY") loupe.warn else loupe.ink,
                )
            }
            if (r.recurring.isEmpty()) Muted("No merchant charged three or more times.")
        }
        Card {
            H2("Term changes")
            Muted("Two versions of one document — files whose names differ only by numbers, or successive emails from one address — with their labelled amounts compared.")
            for (t in r.termChanges) {
                for (ch in t.changes) {
                    Note(
                        "${ch.label}: ${money(ch.beforeMinor)} → ${money(ch.afterMinor)}" + (ch.percentChange?.let { " (${if (it > 0) "+" else ""}${fmt(it, 1)}%)" } ?: "") + " — ${t.earlier.name} → ${t.later.name}",
                        warn = ch.increased,
                    )
                }
            }
            if (r.termChanges.isEmpty()) Muted("No labelled amount moved between versions of a document.")
        }
        Card {
            H2("Person impersonation")
            Muted("There is no address book on the desktop, so a contact is inferred from history: a display name used at least twice from one address. ${r.emailsChecked} email(s) checked.")
            for (f in r.impersonation) {
                Note("\"${f.item.email?.fromName}\" <${f.item.email?.fromAddress}> — " + f.signals.joinToString("; ") { it.detail } + " — ${f.item.name}", warn = true)
            }
            if (r.impersonation.isEmpty()) Muted("No known name arrived from an unfamiliar address.")
        }
        Card {
            H2("Site fraud")
            Muted("${r.linksChecked} link(s) and every sender's domain scored by the one phishing formula shared with Loupe Station (docs/PHISHING-FORMULA.md), with the brand the sender claims; origin facts only, never page content. Caution and danger are listed.")
            for (f in r.fraud) {
                Note("${f.item.name}: ${f.what} — ${f.verdict.level} (${f.verdict.score}): " + f.verdict.reasons.filter { it.weight > 0 }.joinToString("; ") { it.text }, warn = true)
            }
            if (r.fraud.isEmpty()) Muted("No link or sender reached caution. This is not an all-clear: these checks cover origin only.")
        }
    }
}

private fun money(minor: Long): String = String.format(Locale.ROOT, "%d.%02d", minor / 100, kotlin.math.abs(minor % 100))

/** F4: judgments, calibration and the ledger as files you own. */
@Composable
fun ExportScreen(c: LoupeController, platform: Platform) {
    var written by remember { mutableStateOf<List<Path>>(emptyList()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card {
            H2("Export everything Loupe has learned")
            Fact("loupe-judgments.json", "every judgment, with its question, options, criteria hash and three-part criteria")
            Fact("loupe-calibration.json", "per-judgment agreement, ECE and reliability bins, from your corrections")
            Fact("loupe-ledger.jsonl", "every decision, losslessly: full distribution, propensity, criteria hash, item, your correction")
            Fact("loupe-corrections.jsonl", "every correction and undo, in order")
            Muted("${c.ledger.size} decision(s), ${c.corrections.size} correction record(s), ${c.judgments.size} judgment(s).")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton("Export to a folder…") {
                    platform.chooseFolder("Choose where to write the export")?.let { written = c.export(it) }
                }
                LinkButton("Show Loupe's own files") { platform.reveal(c.store.home) }
            }
            Muted("Loupe keeps its working copy in ${c.store.home} — the same formats, so the store is its own export.")
        }
        if (written.isNotEmpty()) {
            Card {
                H3("Written")
                for (p in written) Row(verticalAlignment = Alignment.CenterVertically) {
                    Mono(p.toString(), modifier = Modifier.weight(1f))
                    LinkButton("Show") { platform.reveal(p) }
                }
            }
        }
        c.loadReport?.let { lr ->
            if (lr.unreadableLines > 0) Note("${lr.unreadableLines} saved line(s) could not be read at start-up and were skipped (a torn last line after a crash looks like this).", warn = true)
        }
    }
}

/** F5 from inside the app: the Riverflight game, flown by the same model. */
@Composable
fun GameScreen(c: LoupeController) {
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card {
            H2("Watch it think")
            Body("Riverflight (a working name) is a river shooter flown by the same model that judges your files — ten decisions a second, each one a typed choice with its raw probability bars on screen.")
            Muted("Mechanical first: exact look-ahead removes every move that would crash before the model is asked. A safety override replaces a fatal move. A dumb autopilot flies the same seeds, and the game says which wins — today, untuned, the autopilot does.")
            val ready = c.model is ModelState.Ready
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(if (c.gameOpen) "Game window is open" else "Open the game", enabled = !c.gameOpen) { c.openGame() }
                if (!ready) Muted("Without the model the game flies its baseline autopilot.")
            }
            Muted("Keys in the game: ← → steer, space fires, P pauses, Enter restarts.")
        }
    }
}
