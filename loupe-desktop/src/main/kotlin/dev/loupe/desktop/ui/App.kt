package dev.loupe.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.LinearProgressIndicator
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
import dev.loupe.desktop.core.LoupeController
import dev.loupe.desktop.core.ModelState
import dev.loupe.desktop.core.Screen
import dev.loupe.templates.Shape
import dev.loupe.templates.UserJudgment
import java.nio.file.Path

/**
 * What the screens need from the operating system, kept out of them so the whole UI renders
 * off-screen for snapshots and tests. Every call is read-only with respect to the user's files:
 * opening a file hands it to its default app; nothing is moved, renamed or deleted.
 */
interface Platform {
    fun chooseFolder(title: String): Path?
    fun chooseFile(title: String, extensions: Set<String>): Path?
    fun open(path: Path)
    fun reveal(path: Path)

    /** For snapshots and tests: every dialog is cancelled and nothing is opened. */
    object None : Platform {
        override fun chooseFolder(title: String): Path? = null
        override fun chooseFile(title: String, extensions: Set<String>): Path? = null
        override fun open(path: Path) = Unit
        override fun reveal(path: Path) = Unit
    }
}

/** The window's content: navigation, the current screen, and a status line. */
@Composable
fun LoupeApp(c: LoupeController, platform: Platform, dark: Boolean? = null) {
    LoupeTheme(dark = dark ?: androidx.compose.foundation.isSystemInDarkTheme()) {
        Row(Modifier.fillMaxSize().background(loupe.ground)) {
            NavRail(c)
            Column(Modifier.fillMaxHeight().weight(1f)) {
                TopBar(c)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (c.screen) {
                        Screen.SOURCES -> SourcesScreen(c, platform)
                        Screen.LIBRARY -> LibraryScreen(c)
                        Screen.JUDGMENTS -> JudgmentsScreen(c)
                        Screen.RESULTS -> ResultsScreen(c, platform)
                        Screen.QUEUE -> QueueScreen(c)
                        Screen.CALIBRATION -> CalibrationScreen(c)
                        Screen.THRESHOLD -> ThresholdScreen(c)
                        Screen.BASELINE -> BaselineScreen(c)
                        Screen.CENSUS -> CensusScreen(c)
                        Screen.WATCHERS -> WatchersScreen(c, platform)
                        Screen.EXPORT -> ExportScreen(c, platform)
                        Screen.GAME -> GameScreen(c)
                    }
                }
                StatusBar(c)
            }
        }
    }
}

private val GROUPS = listOf(
    "Your data" to listOf(Screen.SOURCES),
    "Judgments" to listOf(Screen.LIBRARY, Screen.JUDGMENTS),
    "One judgment" to listOf(Screen.RESULTS, Screen.QUEUE, Screen.CALIBRATION, Screen.THRESHOLD, Screen.BASELINE),
    "Across everything" to listOf(Screen.CENSUS, Screen.WATCHERS, Screen.EXPORT),
    "Demo" to listOf(Screen.GAME),
)

@Composable
private fun NavRail(c: LoupeController) {
    Column(Modifier.width(212.dp).fillMaxHeight().background(loupe.panel).padding(vertical = 16.dp, horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(22.dp).height(22.dp).clip(RoundedCornerShape(50)).background(loupe.accent))
            Spacer(Modifier.width(10.dp))
            Text("Loupe", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = loupe.ink)
        }
        Muted("On-device decisions. Read-only.", Modifier.padding(top = 2.dp, bottom = 12.dp))
        for ((group, screens) in GROUPS) {
            Text(group.uppercase(), fontSize = 10.sp, color = loupe.faint, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 8.dp))
            for (s in screens) {
                val selected = c.screen == s
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        .background(if (selected) loupe.raised else loupe.panel)
                        .clickable { c.screen = s }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(3.dp).height(14.dp).background(if (selected) loupe.accent else loupe.panel))
                    Spacer(Modifier.width(8.dp))
                    Text(s.title, fontSize = 13.sp, color = if (selected) loupe.ink else loupe.muted, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    Spacer(Modifier.weight(1f))
                    badge(c, s)?.let { Text(it, fontSize = 11.sp, color = loupe.faint) }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        ModelChip(c)
    }
}

private fun badge(c: LoupeController, s: Screen): String? = when (s) {
    Screen.SOURCES -> c.scan.items.size.takeIf { it > 0 }?.toString()
    Screen.JUDGMENTS -> c.judgments.size.takeIf { it > 0 }?.toString()
    else -> null
}

@Composable
private fun ModelChip(c: LoupeController) {
    val (title, detail, warn) = when (val m = c.model) {
        ModelState.Loading -> Triple("Model loading…", "Mechanical features work now.", false)
        is ModelState.Ready -> Triple("Model ready", m.description, false)
        is ModelState.Unavailable -> Triple("Model not loaded", "Judgments cannot run. Mechanical features still work.", true)
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (warn) loupe.warnGround else loupe.raised).padding(10.dp)) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (warn) loupe.warn else loupe.ink)
        Text(detail, fontSize = 11.sp, color = loupe.muted)
        Text("Offline: nothing leaves this computer.", fontSize = 11.sp, color = loupe.faint)
    }
}

@Composable
private fun TopBar(c: LoupeController) {
    Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        H1(c.screen.title)
        Spacer(Modifier.weight(1f))
        if (c.screen.perJudgment) JudgmentPicker(c)
    }
}

/** Picks the judgment the per-judgment screens show. */
@Composable
fun JudgmentPicker(c: LoupeController) {
    var open by remember { mutableStateOf(false) }
    Box {
        Pill(c.selectedJudgment?.title?.let { "Judgment: $it  ▾" } ?: "Choose a judgment  ▾", color = loupe.ink, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (c.judgments.isEmpty()) DropdownMenuItem(onClick = { open = false; c.screen = Screen.LIBRARY }) { Text("No judgments yet — open the Library") }
            for (j in c.judgments) {
                DropdownMenuItem(onClick = { c.selectedJudgmentId = j.id; open = false }) { Text(j.title) }
            }
        }
    }
}

@Composable
private fun StatusBar(c: LoupeController) {
    Row(Modifier.fillMaxWidth().background(loupe.panel).padding(horizontal = 28.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        val sweep = c.sweep
        if (c.scanning) {
            Muted("Scanning… ${c.scanProgress?.let { "${it.filesSeen} files, ${it.itemsRead} items" } ?: ""}")
        }
        if (sweep != null && sweep.running) {
            LinearProgressIndicator(progress = if (sweep.total == 0) 1f else sweep.done.toFloat() / sweep.total, modifier = Modifier.width(140.dp), color = loupe.accent, backgroundColor = loupe.barTrack)
            Muted("Sweeping ${c.judgment(sweep.judgmentId)?.title ?: ""}: ${sweep.done}/${sweep.total}, ${fmt(sweep.itemsPerSecond, 1)} items/s")
        }
        Text(c.notice ?: "", fontSize = 12.sp, color = loupe.muted, maxLines = 1, modifier = Modifier.weight(1f))
    }
}

/** The per-judgment screens' shared "nothing selected" state. */
@Composable
fun NeedsJudgment(c: LoupeController, content: @Composable (UserJudgment) -> Unit) {
    val j = c.selectedJudgment
    if (j == null) {
        Empty("No judgment selected", "Make one from the Library, run it from Judgments, then come back here.") {
            PrimaryButton("Open the Library") { c.screen = Screen.LIBRARY }
        }
    } else {
        content(j)
    }
}

/**
 * How an answer is shown. A warn-only judgment's negative option is what the model reads — it is
 * never shown to the user, because §4 forbids a blessing: the screen says "no signal" instead.
 */
fun shownAnswer(j: UserJudgment, label: String): String {
    val shape = j.shape
    if (j.warnOnly && shape is Shape.Binary && label == shape.negative) return "no signal (not an all-clear)"
    if (j.warnOnly && shape == Shape.YesNo && label == "no") return "no signal (not an all-clear)"
    if (shape is Shape.Ordinal) {
        val i = shape.candidates.indexOf(label)
        if (i >= 0) return "$label — ${shape.bands[i]}"
    }
    return label
}
