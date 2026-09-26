package com.loupeai.android

import androidx.compose.ui.graphics.toArgb
import com.loupeai.android.ui.Palette
import kotlin.test.Test
import kotlin.test.assertEquals

/** The palette is ios/Loupe/Design/Theme.swift's, value for value; a drift here is a design change. */
class PaletteTest {
    private fun hex(c: androidx.compose.ui.graphics.Color): String = "%06X".format(c.toArgb() and 0xFFFFFF)

    @Test
    fun `matches the iOS dark neon palette`() {
        val expected = mapOf(
            "ground" to "070B18", "groundMid" to "0B1530", "groundHigh" to "13235A", "card" to "0E1834",
            "cardHigh" to "14224A", "ink" to "EAF0FF", "inkSoft" to "A7B4D4", "onAccent" to "041026",
            "blue" to "6B95FF", "blueBright" to "2F6BFF", "cyan" to "22D3EE", "mint" to "10B981",
            "amber" to "F59E0B", "red" to "EF4444", "okText" to "4ADE80", "warnText" to "FBBF24",
            "dangerText" to "FCA5A5", "track" to "1A2A55",
        )
        val actual = mapOf(
            "ground" to Palette.ground, "groundMid" to Palette.groundMid, "groundHigh" to Palette.groundHigh,
            "card" to Palette.card, "cardHigh" to Palette.cardHigh, "ink" to Palette.ink, "inkSoft" to Palette.inkSoft,
            "onAccent" to Palette.onAccent, "blue" to Palette.blue, "blueBright" to Palette.blueBright,
            "cyan" to Palette.cyan, "mint" to Palette.mint, "amber" to Palette.amber, "red" to Palette.red,
            "okText" to Palette.okText, "warnText" to Palette.warnText, "dangerText" to Palette.dangerText,
            "track" to Palette.track,
        ).mapValues { hex(it.value) }
        assertEquals(expected, actual)
    }

    @Test
    fun `maps verdict levels to their status colours`() {
        assertEquals(Palette.dangerText, Palette.level("danger").first)
        assertEquals(Palette.warnText, Palette.level("caution").first)
        assertEquals(Palette.okText, Palette.level("safe").first)
        assertEquals(Palette.okSoft, Palette.level("safe").second)
    }

    @Test
    fun `an unknown level fails safe to caution and is reported`() {
        for (unknown in listOf("", "Safe", "ok", "critical", "unknown")) {
            val reported = mutableListOf<String>()
            assertEquals(Palette.warnText to Palette.warnSoft, Palette.level(unknown) { reported += it }, unknown)
            assertEquals(listOf(unknown), reported)
        }
        val quiet = mutableListOf<String>()
        for (known in listOf("danger", "caution", "safe")) Palette.level(known) { quiet += it }
        assertEquals(emptyList(), quiet)
    }
}
