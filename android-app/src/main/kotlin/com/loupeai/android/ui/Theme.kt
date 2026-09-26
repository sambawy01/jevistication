package com.loupeai.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Loupe dark neon: the palette of ios/Loupe/Design/Theme.swift (owner decision 2026-09-24), value
 * for value, so both phones look like one product. Views take colours from here, never literals.
 *
 * Contrast is iOS's (WCAG 2.x on `card` #0E1834): ink 16.1:1, inkSoft 8.3:1, blue 5.9:1, cyan 10.2:1,
 * okText 10.9:1, warnText 11.0:1, dangerText 9.5:1, onAccent on blue 5.6:1.
 */
object Palette {
    // Grounds, back to front.
    val ground = Color(0xFF070B18)
    val groundMid = Color(0xFF0B1530)
    val groundHigh = Color(0xFF13235A)

    // Surfaces.
    val card = Color(0xFF0E1834)
    val cardHigh = Color(0xFF14224A)

    // Text.
    val ink = Color(0xFFEAF0FF)
    val inkSoft = Color(0xFFA7B4D4)

    /** Text on a filled accent (primary buttons, active chips). */
    val onAccent = Color(0xFF041026)

    // Signals. `blue` is the text-safe accent; `blueBright` fills, strokes and glows.
    val blue = Color(0xFF6B95FF)
    val blueBright = Color(0xFF2F6BFF)
    val cyan = Color(0xFF22D3EE)
    val mint = Color(0xFF10B981)
    val amber = Color(0xFFF59E0B)
    val red = Color(0xFFEF4444)

    // The hero band's gradient.
    val navyTop = Color(0xFF0B1530)
    val navyBottom = Color(0xFF13235A)

    // Lines.
    val hairline = Color(0xFF22D3EE).copy(alpha = 0.16f)
    val border = Color(0xFF3B82F6).copy(alpha = 0.28f)
    val borderActive = Color(0xFF22D3EE).copy(alpha = 0.75f)

    // Status text and tints, text-safe on the dark surfaces.
    val okText = Color(0xFF4ADE80)
    val okSoft = Color(0xFF10B981).copy(alpha = 0.16f)
    val warnText = Color(0xFFFBBF24)
    val warnSoft = Color(0xFFF59E0B).copy(alpha = 0.16f)
    val dangerText = Color(0xFFFCA5A5)
    val dangerSoft = Color(0xFFEF4444).copy(alpha = 0.18f)
    val track = Color(0xFF1A2A55)
    val accentSoft = Color(0xFF2F6BFF).copy(alpha = 0.18f)

    /** A verdict level's text colour and tint: the formula's three levels, never "safe" wording. */
    fun level(level: String): Pair<Color, Color> = when (level) {
        "danger" -> dangerText to dangerSoft
        "caution" -> warnText to warnSoft
        else -> okText to okSoft
    }
}

/** Effect tokens (Theme.swift `Effects`): the card radius, in dp. */
object Effects {
    const val RADIUS_DP: Int = 16
}

private val LoupeColors = darkColorScheme(
    primary = Palette.cyan,
    onPrimary = Palette.onAccent,
    secondary = Palette.blue,
    onSecondary = Palette.onAccent,
    tertiary = Palette.mint,
    background = Palette.ground,
    onBackground = Palette.ink,
    surface = Palette.card,
    onSurface = Palette.ink,
    surfaceVariant = Palette.cardHigh,
    onSurfaceVariant = Palette.inkSoft,
    outline = Palette.border,
    outlineVariant = Palette.hairline,
    error = Palette.red,
    onError = Palette.ink,
)

/** The app's one theme: dark neon only, as on iOS (there is no light variant). */
@Composable
fun LoupeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LoupeColors, content = content)
}
