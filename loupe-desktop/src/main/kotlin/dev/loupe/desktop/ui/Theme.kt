package dev.loupe.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Colors
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.Typography
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * Loupe's own colours beyond Material's: the few meanings the screens rely on. Warning is amber,
 * not red-for-danger: a warning here is "look at this", and red is kept for "could not judge".
 * Nothing is ever coloured as safe — there is no green "all clear" colour in the palette at all.
 */
data class LoupeColors(
    val ground: Color,
    val panel: Color,
    val raised: Color,
    val ink: Color,
    val muted: Color,
    val faint: Color,
    val line: Color,
    val accent: Color,
    val accentInk: Color,
    val warn: Color,
    val warnGround: Color,
    val error: Color,
    val errorGround: Color,
    val bar: Color,
    val barTrack: Color,
    val dark: Boolean,
)

private val Light = LoupeColors(
    ground = Color(0xFFF4F1EA), panel = Color(0xFFFFFDF8), raised = Color(0xFFFFFFFF),
    ink = Color(0xFF1D1B18), muted = Color(0xFF5E5850), faint = Color(0xFF8F877C), line = Color(0xFFE2DCD0),
    accent = Color(0xFF1F5F66), accentInk = Color(0xFFFFFFFF),
    warn = Color(0xFF8A5A00), warnGround = Color(0xFFFFF1CF),
    error = Color(0xFFA3261A), errorGround = Color(0xFFFBE3DF),
    bar = Color(0xFF2E7C84), barTrack = Color(0xFFE7E1D6), dark = false,
)

private val Dark = LoupeColors(
    ground = Color(0xFF15181A), panel = Color(0xFF1C2023), raised = Color(0xFF23282C),
    ink = Color(0xFFECE7DE), muted = Color(0xFFB0A89C), faint = Color(0xFF7D766C), line = Color(0xFF30363B),
    accent = Color(0xFF6FC3C9), accentInk = Color(0xFF0E1A1B),
    warn = Color(0xFFF2C063), warnGround = Color(0xFF3A2F17),
    error = Color(0xFFF08A7C), errorGround = Color(0xFF3B1F1B),
    bar = Color(0xFF5DB3BA), barTrack = Color(0xFF2C3236), dark = true,
)

val LocalLoupe = staticCompositionLocalOf { Light }

/** The palette in force. */
val loupe: LoupeColors @Composable get() = LocalLoupe.current

/** Follows the system's light or dark appearance. */
@Composable
fun LoupeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) Dark else Light
    val material: Colors = if (dark) {
        darkColors(primary = c.accent, onPrimary = c.accentInk, background = c.ground, surface = c.panel, onSurface = c.ink, onBackground = c.ink, error = c.error, secondary = c.accent)
    } else {
        lightColors(primary = c.accent, onPrimary = c.accentInk, background = c.ground, surface = c.panel, onSurface = c.ink, onBackground = c.ink, error = c.error, secondary = c.accent)
    }
    val typography = Typography(
        h5 = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
        h6 = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
        subtitle1 = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        body1 = TextStyle(fontSize = 13.sp),
        body2 = TextStyle(fontSize = 12.sp),
        caption = TextStyle(fontSize = 11.sp),
        button = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    )
    CompositionLocalProvider(LocalLoupe provides c) {
        MaterialTheme(colors = material, typography = typography, content = content)
    }
}

// ---------------------------------------------------------------------- small building blocks

@Composable
fun H1(text: String) = Text(text, style = MaterialTheme.typography.h5, color = loupe.ink)

@Composable
fun H2(text: String, modifier: Modifier = Modifier) = Text(text, style = MaterialTheme.typography.h6, color = loupe.ink, modifier = modifier)

@Composable
fun H3(text: String) = Text(text, style = MaterialTheme.typography.subtitle1, color = loupe.ink)

@Composable
fun Body(text: String, color: Color = loupe.ink, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.body1, color = color, modifier = modifier)

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) = Text(text, style = MaterialTheme.typography.body2, color = loupe.muted, modifier = modifier)

@Composable
fun Mono(text: String, color: Color = loupe.ink, modifier: Modifier = Modifier) =
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = color, modifier = modifier)

/** A bordered panel. */
@Composable
fun Card(modifier: Modifier = Modifier, padding: Int = 16, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(loupe.panel)
            .border(1.dp, loupe.line, RoundedCornerShape(10.dp))
            .padding(padding.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** A tinted note: [warn] for things to look at, otherwise informational. Never a green "safe". */
@Composable
fun Note(text: String, warn: Boolean = false, error: Boolean = false, modifier: Modifier = Modifier) {
    val ground = when {
        error -> loupe.errorGround
        warn -> loupe.warnGround
        else -> loupe.raised
    }
    val ink = when {
        error -> loupe.error
        warn -> loupe.warn
        else -> loupe.muted
    }
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(ground).border(1.dp, loupe.line, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(text, style = MaterialTheme.typography.body2, color = ink)
    }
}

/** A small rounded label. */
@Composable
fun Pill(text: String, color: Color = loupe.muted, ground: Color = loupe.raised, onClick: (() -> Unit)? = null, selected: Boolean = false) {
    val g = if (selected) loupe.accent else ground
    val c = if (selected) loupe.accentInk else color
    val m = Modifier.clip(RoundedCornerShape(50)).background(g).border(1.dp, if (selected) loupe.accent else loupe.line, RoundedCornerShape(50))
    Box((if (onClick != null) m.clickable(onClick = onClick) else m).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(text, fontSize = 11.sp, color = c, fontWeight = FontWeight.Medium)
    }
}

/** A horizontal probability bar with its number, [label]led as raw where it is raw. */
@Composable
fun ProbabilityBar(value: Double, modifier: Modifier = Modifier, width: Int = 120, color: Color = loupe.bar, threshold: Double? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(width.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(loupe.barTrack)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(value.toFloat().coerceIn(0f, 1f)).background(color))
            if (threshold != null) {
                Box(Modifier.padding(start = (width * threshold).dp.coerceAtMost((width - 1).dp)).width(2.dp).fillMaxHeight().background(loupe.ink))
            }
        }
        Spacer(Modifier.width(6.dp))
        Mono(fmt(value, 3), color = loupe.muted)
    }
}

/** A labelled count bar for census-style charts. */
@Composable
fun CountBar(label: String, count: Int, max: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = loupe.ink, modifier = Modifier.width(220.dp), maxLines = 1)
        Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(3.dp)).background(loupe.barTrack)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(if (max == 0) 0f else count.toFloat() / max).background(loupe.bar))
        }
        Spacer(Modifier.width(8.dp))
        Mono(count.toString(), modifier = Modifier.width(48.dp))
    }
}

/** Label and value on one line. */
@Composable
fun Fact(label: String, value: String, valueColor: Color = loupe.ink) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 12.sp, color = loupe.muted, modifier = Modifier.width(170.dp))
        Text(value, fontSize = 12.sp, color = valueColor)
    }
}

/** A big number with a caption. */
@Composable
fun RowScope.Stat(value: String, caption: String, color: Color = loupe.ink) {
    Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(loupe.raised).border(1.dp, loupe.line, RoundedCornerShape(8.dp)).padding(12.dp)) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = color)
        Text(caption, fontSize = 11.sp, color = loupe.muted)
    }
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) =
    androidx.compose.material.Button(onClick = onClick, enabled = enabled, colors = ButtonDefaults.buttonColors(backgroundColor = loupe.accent, contentColor = loupe.accentInk)) { Text(text) }

@Composable
fun SecondaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) =
    OutlinedButton(onClick = onClick, enabled = enabled, colors = ButtonDefaults.outlinedButtonColors(backgroundColor = loupe.panel, contentColor = loupe.accent)) { Text(text) }

@Composable
fun LinkButton(text: String, enabled: Boolean = true, onClick: () -> Unit) =
    TextButton(onClick = onClick, enabled = enabled, colors = ButtonDefaults.textButtonColors(contentColor = loupe.accent)) { Text(text) }

/** Shown when a screen has nothing to show yet, with what to do about it. */
@Composable
fun Empty(title: String, detail: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        H2(title)
        Muted(detail)
        action?.invoke()
    }
}

fun fmt(v: Double, digits: Int = 2): String = String.format(Locale.ROOT, "%.${digits}f", v)

fun pct(v: Double, digits: Int = 0): String = String.format(Locale.ROOT, "%.${digits}f%%", v * 100)
