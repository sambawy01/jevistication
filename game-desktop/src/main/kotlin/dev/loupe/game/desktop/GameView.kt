package dev.loupe.game.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.loupe.game.DeathCause
import dev.loupe.game.EnemyKind
import dev.loupe.game.GameSession
import dev.loupe.game.Rules
import kotlin.math.floor

/**
 * One lane of the game: the river on a canvas, with the HUD and banners laid over it.
 *
 * Portrait, 28 columns by 32 rows, drawn from the simulation's exact geometry — what you see is what
 * the collision test sees. Only Compose Foundation drawing is used, so this draws the same on Android.
 */
@Composable
fun GameView(lane: Lane, frame: Long, modifier: Modifier = Modifier) {
    val session = lane.session
    val world = session.world
    val flashing = session.lastOverride?.let { world.tick - it.tick < GameController.FLASH_TICKS } == true
    Box(
        modifier
            .aspectRatio(Rules.COLUMNS.toFloat() / Rules.VIEW_ROWS)
            .border(if (flashing) 4.dp else 1.dp, if (flashing) Palette.overrideFlash else Palette.barTrack),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            frame // read so every frame repaints
            drawWorld(lane)
        }
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Hud(lane.label, bold = true)
                Spacer(Modifier.width(12.dp))
                Hud("score ${world.score}")
                Spacer(Modifier.width(12.dp))
                Hud("rows ${world.cameraY.toInt()}")
                Spacer(Modifier.width(12.dp))
                Hud("section ${world.section}")
            }
            Spacer(Modifier.height(6.dp))
            FuelGauge(world.fuel / Rules.FUEL_MAX)
        }
        if (flashing) {
            Banner("SAFETY OVERRIDE", Palette.overrideFlash, Modifier.align(Alignment.Center).padding(bottom = 120.dp))
        }
        if (session.handedOff && !world.over) {
            Banner("YOUR TURN  ← → space", Palette.handOff, Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp))
        }
        if (world.over) {
            val cause = when (world.death) {
                DeathCause.BANK -> "hit the bank"
                DeathCause.ENEMY -> "hit an enemy"
                DeathCause.BRIDGE -> "hit a bridge"
                DeathCause.FUEL -> "ran out of fuel"
                null -> ""
            }
            val next = if (lane.mode == PilotMode.HUMAN) "Enter to fly again" else "next run shortly"
            Banner("GAME OVER — $cause\n$next", Palette.panelText, Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun Hud(text: String, bold: Boolean = false) {
    Text(
        text,
        color = Palette.panelText,
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.background(Color(0xAA000000)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun FuelGauge(fraction: Double) {
    val low = fraction * Rules.FUEL_MAX < Rules.FUEL_LOW
    Row(verticalAlignment = Alignment.CenterVertically) {
        Hud("fuel")
        Spacer(Modifier.width(6.dp))
        Canvas(Modifier.width(120.dp).height(10.dp)) {
            drawRect(Palette.barTrack)
            drawRect(if (low) Palette.overrideFlash else Palette.barChosen, size = Size(size.width * fraction.toFloat(), size.height))
        }
    }
}

@Composable
private fun Banner(text: String, color: Color, modifier: Modifier) {
    Text(
        text,
        color = color,
        fontSize = 20.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        modifier = modifier.background(Color(0xCC000000)).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

private fun DrawScope.drawWorld(lane: Lane) {
    val world = lane.session.world
    val cell = size.width / Rules.COLUMNS
    val top = world.cameraY + Rules.VIEW_ROWS
    fun sy(worldY: Double): Float = ((top - worldY) * cell).toFloat()
    fun sx(col: Double): Float = (col * cell).toFloat()

    drawRect(Palette.water)
    val first = floor(world.cameraY).toInt()
    for (r in first..first + Rules.VIEW_ROWS) {
        val row = world.river.row(r)
        val y = sy(r + 1.0)
        // Ripples: a sparse, fixed pattern keyed on the row so the water visibly moves.
        if (r % 3 == 0) {
            val rx = ((r * 7919) % (Rules.COLUMNS - 4)) + 2.0
            if (row.channelAt(rx) != null) drawRect(Palette.ripple, Offset(sx(rx), y + cell * 0.45f), Size(cell * 1.5f, cell * 0.12f))
        }
        land(0.0, row.left.toDouble(), y, cell, r)
        land((Rules.COLUMNS - row.right).toDouble(), Rules.COLUMNS.toDouble(), y, cell, r)
        if (row.hasIsland) land(row.islandFrom.toDouble(), row.islandTo.toDouble(), y, cell, r)
    }

    for (bridge in world.bridges) {
        val y = sy(bridge.y + 1.0)
        if (bridge.alive) {
            drawRect(Palette.bridge, Offset(sx(bridge.from), y), Size(sx(bridge.to) - sx(bridge.from), cell))
            var c = bridge.from
            while (c < bridge.to) {
                drawRect(Palette.bridgeStripe, Offset(sx(c), y), Size(cell * 0.25f, cell))
                c += 1.0
            }
        } else {
            var c = bridge.from
            while (c < bridge.to) {
                drawRect(Palette.bridge.copy(alpha = 0.5f), Offset(sx(c), y + cell * 0.3f), Size(cell * 0.4f, cell * 0.4f))
                c += 2.0
            }
        }
    }

    for (depot in world.depots) {
        if (depot.alive) Sprites.depot.draw(this, sx(depot.x), sy(depot.y), sx(depot.width))
    }
    for (enemy in world.enemies) {
        if (!enemy.alive) continue
        val sprite = if (enemy.kind == EnemyKind.BOAT) Sprites.boat else Sprites.heli
        sprite.draw(this, sx(enemy.x), sy(enemy.y), sx(enemy.width))
    }
    for (bullet in world.bullets) {
        drawRect(
            Palette.bullet,
            Offset(sx(bullet.x - bullet.width / 2), sy(bullet.y + bullet.height)),
            Size(sx(bullet.width), (bullet.height * cell).toFloat()),
        )
    }
    if (!world.over) {
        Sprites.plane.draw(this, sx(world.playerX), sy(world.playerY), sx(Rules.PLAYER_W))
    }
    for (effect in lane.effects) {
        val t = effect.age / GameController.EFFECT_TICKS.toFloat()
        val radius = cell * (if (effect.big) 2.6f else 1.4f) * (0.3f + t)
        val center = Offset(sx(effect.x), sy(effect.y + 0.5))
        drawCircle(Palette.barChosen.copy(alpha = 1f - t), radius, center, style = Stroke(width = cell * 0.35f))
        drawCircle(Palette.bullet.copy(alpha = (1f - t) * 0.7f), radius * 0.5f, center)
    }
}

private fun DrawScope.land(from: Double, to: Double, y: Float, cell: Float, row: Int) {
    if (to <= from) return
    val x0 = (from * cell).toFloat()
    val w = ((to - from) * cell).toFloat()
    drawRect(if (row % 2 == 0) Palette.sand else Palette.sandShade, Offset(x0, y), Size(w, cell + 0.5f))
    // A darker lip where land meets water, so the edge the collision test uses is visible.
    if (from > 0) drawRect(Palette.bankEdge, Offset(x0, y), Size(cell * 0.18f, cell + 0.5f))
    if (to < Rules.COLUMNS) drawRect(Palette.bankEdge, Offset(x0 + w - cell * 0.18f, y), Size(cell * 0.18f, cell + 0.5f))
}
