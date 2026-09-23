package dev.loupe.game.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Pixel sprites drawn for this game, as character grids.
 *
 * Original work: none is traced from, or modelled on, any commercial river shooter's art, and none
 * reuses the river-raid-2k prototype's sprites (its player sprite imitates the original's
 * silhouette, so it was deliberately left behind). The plane is a chevron glider rather than a jet,
 * the palette is dusk teal and sand rather than any arcade original's, and the fuel depot is a
 * striped buoy with no lettering.
 *
 * `.` is transparent; every other character is looked up in [palette].
 */
class Sprite(private val rows: List<String>, private val palette: Map<Char, Color>) {
    val width: Int = rows.first().length
    val height: Int = rows.size

    init {
        require(rows.all { it.length == width }) { "sprite rows must be the same width" }
        require(rows.all { row -> row.all { it == '.' || it in palette } }) { "sprite uses a colour missing from its palette" }
    }

    /** Draws the sprite with its bottom-centre at ([centerX], [bottomY]), [pixelsWide] canvas px across. */
    fun draw(scope: DrawScope, centerX: Float, bottomY: Float, pixelsWide: Float, tint: Color? = null) {
        val px = pixelsWide / width
        val left = centerX - pixelsWide / 2
        val top = bottomY - px * height
        for ((y, row) in rows.withIndex()) {
            for ((x, c) in row.withIndex()) {
                if (c == '.') continue
                scope.drawRect(
                    color = tint ?: palette.getValue(c),
                    topLeft = Offset(left + x * px, top + y * px),
                    // A hair of overlap so no seam shows between pixels at fractional sizes.
                    size = Size(px + 0.5f, px + 0.5f),
                )
            }
        }
    }
}

object Sprites {
    private val white = Color(0xFFF4F1E8)
    private val amber = Color(0xFFFF9F1C)
    private val slate = Color(0xFF3A4450)

    /** The player: a chevron glider with an amber spine. */
    val plane = Sprite(
        listOf(
            "....W....",
            "...WAW...",
            "..WWAWW..",
            ".WW.A.WW.",
            "WW..A..WW",
            "W..AAA..W",
            "...A.A...",
            "..SS.SS..",
        ),
        mapOf('W' to white, 'A' to amber, 'S' to slate),
    )

    /** A patrol boat. */
    val boat = Sprite(
        listOf(
            ".....MM.....",
            "....MLLM....",
            ".MMMMMMMMMM.",
            "MKKKKKKKKKKM",
            ".MMMMMMMMMM.",
        ),
        mapOf('M' to Color(0xFFB23A48), 'L' to Color(0xFFFFE8D6), 'K' to Color(0xFF3B1F2B)),
    )

    /** A rotor drone. */
    val heli = Sprite(
        listOf(
            "GGGGGGGGGG",
            "....GG....",
            "..PPPPP...",
            ".PPLLPPPPP",
            "..PPPPP..P",
            "...G..G...",
        ),
        mapOf('G' to Color(0xFFB8C4CC), 'P' to Color(0xFF7B4FB0), 'L' to Color(0xFFE8F7FF)),
    )

    /** A fuel buoy: striped, unlettered. */
    val depot = Sprite(
        listOf(
            "..YYY..",
            ".YYYYY.",
            "YYYKYYY",
            "YYKKKYY",
            "YYKKKYY",
            "YYYKYYY",
            "KKKKKKK",
            "YYYYYYY",
            "KKKKKKK",
            "YYYYYYY",
            ".KKKKK.",
        ),
        mapOf('Y' to Color(0xFFE9D758), 'K' to Color(0xFF1B1B1E)),
    )
}

/** The game's palette: dusk teal water, sand banks. */
object Palette {
    val water = Color(0xFF0E3B43)
    val ripple = Color(0xFF155462)
    val sand = Color(0xFFC9A66B)
    val sandShade = Color(0xFFB39058)
    val bankEdge = Color(0xFF7A5C34)
    val bridge = Color(0xFF6B6F76)
    val bridgeStripe = Color(0xFF2E3238)
    val bullet = Color(0xFFFFE066)
    val overrideFlash = Color(0xFFFF4D4D)
    val handOff = Color(0xFF4DD0E1)
    val panel = Color(0xFF12171C)
    val panelText = Color(0xFFE6E9EC)
    val muted = Color(0xFF8A96A3)
    val bar = Color(0xFF4DD0E1)
    val barChosen = Color(0xFFFF9F1C)
    val barTrack = Color(0xFF232B33)
}
