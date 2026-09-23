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
import dev.loupe.desktop.core.LoupeController
import dev.loupe.sources.SourceType

@Composable
fun SourcesScreen(c: LoupeController, platform: Platform) {
    var showSkipped by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Note(
            "Loupe only reads. It never modifies, moves or deletes your files, and nothing leaves this computer — " +
                "no network call exists in the app. Photos are read for metadata only: there is no OCR on the desktop.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton("Add folder…") {
                platform.chooseFolder("Choose a folder for Loupe to read")?.let { path -> c.addSource(SourceType.FOLDER, path)?.let { c.notice = it } }
            }
            SecondaryButton("Add mail export…") {
                platform.chooseFile("Choose an .mbox or .eml file", setOf("mbox", "eml"))?.let { path -> c.addSource(SourceType.MAIL_EXPORT, path)?.let { c.notice = it } }
            }
            SecondaryButton("Add folder of .eml files…") {
                platform.chooseFolder("Choose a folder of exported .eml messages")?.let { path -> c.addSource(SourceType.MAIL_EXPORT, path)?.let { c.notice = it } }
            }
            SecondaryButton("Load sample data") { c.loadSampleData() }
            Spacer(Modifier.weight(1f))
            LinkButton("Rescan", enabled = !c.scanning) { c.rescan() }
        }

        Card {
            H2("Sources")
            if (c.sources.isEmpty()) {
                Muted("None yet. Add a folder (Documents, Downloads, a photo folder) or a mail export, or load the synthetic sample data to try Loupe without your own files.")
            }
            for (s in c.sources) {
                val bad = c.scan.unavailable.firstOrNull { it.first.id == s.id }
                val count = c.scan.items.count { it.sourceId == s.id }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Pill(s.type.title)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Body(s.path.toString())
                        if (bad != null) Body(bad.second, color = loupe.warn) else Muted("$count item(s)")
                    }
                    LinkButton("Show") { platform.reveal(s.path) }
                    LinkButton("Remove") { c.removeSource(s.id) }
                }
            }
        }

        if (c.scanning) {
            Card {
                H3("Scanning")
                val p = c.scanProgress
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = loupe.accent, backgroundColor = loupe.barTrack)
                Muted(p?.let { "${it.filesSeen} files looked at, ${it.itemsRead} items read, ${it.skipped} skipped — ${it.current}" } ?: "Starting…")
            }
        }

        val scan = c.scan
        if (scan.items.isNotEmpty() || scan.skipped.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat(scan.items.size.toString(), "items read")
                Stat(scan.items.count { it.hasText }.toString(), "with text the model can read")
                Stat(scan.withoutText.toString(), "without text (not sent to the model)")
                Stat(scan.duplicates.toString(), "exact duplicates (by hash)")
                Stat(scan.skipped.size.toString(), "files skipped", color = if (scan.skipped.isEmpty()) loupe.ink else loupe.warn)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Card(Modifier.weight(1f)) {
                    H3("By type")
                    val counts = scan.countsByKind().entries.sortedByDescending { it.value }
                    val max = counts.maxOfOrNull { it.value } ?: 0
                    for ((kind, n) in counts) CountBar(kind.title, n, max)
                    Muted("Text comes from plain text, Markdown, CSV, JSON, HTML (tags stripped), email (mime4j) and PDF text layers (PDFBox). Images give metadata only.")
                }
                Card(Modifier.weight(1f)) {
                    H3("Skipped, and why")
                    if (scan.skipped.isEmpty()) Muted("Nothing was skipped.")
                    for ((reason, n) in scan.skippedByReason().entries.sortedByDescending { it.value }) Fact(reason, "$n file(s)")
                    if (scan.skipped.isNotEmpty()) {
                        LinkButton(if (showSkipped) "Hide the list" else "Show every skipped file") { showSkipped = !showSkipped }
                        if (showSkipped) for (s in scan.skipped.take(300)) Mono("${s.reason} — ${s.path}", color = loupe.muted)
                    }
                    if (scan.unavailable.isNotEmpty()) Note("${scan.unavailable.size} source(s) could not be opened: ${scan.unavailable.joinToString { it.second }}", warn = true)
                }
            }
        }
    }
}
