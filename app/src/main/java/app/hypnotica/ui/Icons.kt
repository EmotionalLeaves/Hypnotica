package app.hypnotica.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The app's icons, drawn rather than typed.
 *
 * They were Unicode characters, and a character is whatever the phone's fonts
 * make of it: ⏸ and ⤴ came out as colour emoji that ignored the tint, ◷ and ▦
 * from fallback fonts at half the size of ★, ‹ an x-height mark. Drawn on a
 * 24-unit grid with one stroke weight and round ends, they read as one set.
 *
 * Keyed by the same glyphs the screens already pass, so a call site names the
 * icon by the character it used to be.
 */
fun hasIcon(glyph: String): Boolean = glyph in ICONS

private val ICONS = setOf(
    "‹", "★", "☆", "♥", "♡", "⚙", "▦", "◷", "⤴", "⚄", "≡", "↻", "▶", "⏸", "✇", "↶", "×", "⌕", "▾"
)

@Composable
fun GlyphIcon(glyph: String, color: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) { drawGlyphIcon(glyph, color) }
}

/** Draws [glyph] into the scope's square, on a 24-unit grid. */
fun DrawScope.drawGlyphIcon(glyph: String, color: Color) {
    val u = size.minDimension / 24f
    val ox = (size.width - 24f * u) / 2f
    val oy = (size.height - 24f * u) / 2f
    fun p(x: Float, y: Float) = Offset(ox + x * u, oy + y * u)
    val line = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun path(vararg pts: Pair<Float, Float>, close: Boolean = false): Path = Path().apply {
        pts.forEachIndexed { i, (x, y) -> val o = p(x, y); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
        if (close) close()
    }
    when (glyph) {
        "‹" -> drawPath(path(15f to 5f, 8f to 12f, 15f to 19f), color, style = line)
        "★", "☆" -> {
            val star = Path()
            for (k in 0 until 10) {
                val r = if (k % 2 == 0) 9.5f else 4.2f
                val a = -PI / 2 + k * PI / 5
                val o = p(12f + r * cos(a).toFloat(), 12.6f + r * sin(a).toFloat())
                if (k == 0) star.moveTo(o.x, o.y) else star.lineTo(o.x, o.y)
            }
            star.close()
            drawPath(star, color, style = if (glyph == "★") Fill else line)
            if (glyph == "★") drawPath(star, color, style = line)
        }
        "♥", "♡" -> {
            val heart = Path().apply {
                val a = p(12f, 20f)
                moveTo(a.x, a.y)
                val c1 = p(3f, 14f); val c2 = p(1.5f, 7f); val b = p(7f, 4.5f)
                cubicTo(c1.x, c1.y, c2.x, c2.y, b.x, b.y)
                val c3 = p(9.5f, 3.5f); val c4 = p(11.5f, 5.5f); val top = p(12f, 7f)
                cubicTo(c3.x, c3.y, c4.x, c4.y, top.x, top.y)
                val c5 = p(12.5f, 5.5f); val c6 = p(14.5f, 3.5f); val d = p(17f, 4.5f)
                cubicTo(c5.x, c5.y, c6.x, c6.y, d.x, d.y)
                val c7 = p(22.5f, 7f); val c8 = p(21f, 14f)
                cubicTo(c7.x, c7.y, c8.x, c8.y, a.x, a.y)
                close()
            }
            if (glyph == "♥") drawPath(heart, color, style = Fill)
            drawPath(heart, color, style = line)
        }
        "⚙" -> {
            val gear = Path()
            val teeth = 8
            for (k in 0 until teeth * 4) {
                val r = if ((k / 2) % 2 == 0) 9.5f else 7.2f
                val a = (k - 0.5f) * 2 * PI / (teeth * 4)
                val o = p(12f + r * cos(a).toFloat(), 12f + r * sin(a).toFloat())
                if (k == 0) gear.moveTo(o.x, o.y) else gear.lineTo(o.x, o.y)
            }
            gear.close()
            drawPath(gear, color, style = line)
            drawCircle(color, radius = 2.8f * u, center = p(12f, 12f), style = line)
        }
        "▦" -> {
            for (r in 0..1) for (c in 0..1) {
                drawRoundRect(
                    color, topLeft = p(4f + c * 9f, 4f + r * 9f), size = Size(7f * u, 7f * u),
                    cornerRadius = CornerRadius(1.6f * u), style = line
                )
            }
        }
        "◷" -> {
            // TickTock: a stack of clips, the front one playing.
            drawRoundRect(color, topLeft = p(7f, 2.5f), size = Size(10f * u, 2f * u), cornerRadius = CornerRadius(1f * u))
            drawRoundRect(color, topLeft = p(4.5f, 6.5f), size = Size(15f * u, 15f * u), cornerRadius = CornerRadius(3f * u), style = line)
            drawPath(path(10.5f to 10.5f, 15f to 14f, 10.5f to 17.5f, close = true), color, style = Fill)
        }
        "⤴" -> {
            // Share: out of the box, upwards.
            drawPath(path(8f to 10f, 6f to 10f, 6f to 20f, 18f to 20f, 18f to 10f, 16f to 10f), color, style = line)
            drawLine(color, p(12f, 15f), p(12f, 3.5f), strokeWidth = 2f * u, cap = StrokeCap.Round)
            drawPath(path(8f to 7.5f, 12f to 3.5f, 16f to 7.5f), color, style = line)
        }
        "⚄" -> {
            drawRoundRect(color, topLeft = p(4f, 4f), size = Size(16f * u, 16f * u), cornerRadius = CornerRadius(3.5f * u), style = line)
            for ((x, y) in listOf(8.5f to 8.5f, 15.5f to 8.5f, 12f to 12f, 8.5f to 15.5f, 15.5f to 15.5f)) {
                drawCircle(color, radius = 1.4f * u, center = p(x, y))
            }
        }
        "≡" -> {
            // Controls: three sliders.
            for ((y, knob) in listOf(6f to 15f, 12f to 8f, 18f to 13f)) {
                drawLine(color, p(4f, y), p(20f, y), strokeWidth = 2f * u, cap = StrokeCap.Round)
                drawCircle(color, radius = 2.4f * u, center = p(knob, y))
            }
        }
        "↶" -> {
            // Undo: back to the left, and down round a bend.
            drawPath(path(8.5f to 5.5f, 4f to 10f, 8.5f to 14.5f), color, style = line)
            val bend = Path().apply {
                val a = p(4f, 10f); moveTo(a.x, a.y)
                val b = p(14f, 10f); lineTo(b.x, b.y)
                val c1 = p(17.3f, 10f); val c2 = p(20f, 12.7f); val d = p(20f, 16f)
                cubicTo(c1.x, c1.y, c2.x, c2.y, d.x, d.y)
                val e = p(20f, 18.5f); lineTo(e.x, e.y)
            }
            drawPath(bend, color, style = line)
        }
        "↻" -> {
            val back = false
            val arc = Path()
            val start = if (back) 200.0 else -20.0
            val sweep = if (back) -250.0 else 250.0
            for (k in 0..24) {
                val a = Math.toRadians(start + sweep * k / 24.0)
                val o = p(12f + 7.5f * cos(a).toFloat(), 12.5f + 7.5f * sin(a).toFloat())
                if (k == 0) arc.moveTo(o.x, o.y) else arc.lineTo(o.x, o.y)
            }
            drawPath(arc, color, style = line)
            val endA = Math.toRadians(start)
            val e = 12f + 7.5f * cos(endA).toFloat() to 12.5f + 7.5f * sin(endA).toFloat()
            if (back) drawPath(path(e.first - 1f to e.second - 5f, e.first to e.second, e.first + 4.5f to e.second - 2f), color, style = line)
            else drawPath(path(e.first + 1f to e.second - 5f, e.first to e.second, e.first - 4.5f to e.second - 2f), color, style = line)
        }
        "▶" -> drawPath(path(7.5f to 4.5f, 19f to 12f, 7.5f to 19.5f, close = true), color, style = Fill)
        "⏸" -> {
            drawRoundRect(color, topLeft = p(6f, 4.5f), size = Size(4.2f * u, 15f * u), cornerRadius = CornerRadius(1.2f * u))
            drawRoundRect(color, topLeft = p(13.8f, 4.5f), size = Size(4.2f * u, 15f * u), cornerRadius = CornerRadius(1.2f * u))
        }
        "✇" -> {
            // Studio: a frame of film.
            drawRoundRect(color, topLeft = p(3.5f, 5f), size = Size(17f * u, 14f * u), cornerRadius = CornerRadius(2.5f * u), style = line)
            drawLine(color, p(8f, 5f), p(8f, 19f), strokeWidth = 2f * u)
            drawLine(color, p(16f, 5f), p(16f, 19f), strokeWidth = 2f * u)
        }
        "×" -> {
            drawLine(color, p(6.5f, 6.5f), p(17.5f, 17.5f), strokeWidth = 2f * u, cap = StrokeCap.Round)
            drawLine(color, p(17.5f, 6.5f), p(6.5f, 17.5f), strokeWidth = 2f * u, cap = StrokeCap.Round)
        }
        "⌕" -> {
            drawCircle(color, radius = 6.5f * u, center = p(10.5f, 10.5f), style = line)
            drawLine(color, p(15.5f, 15.5f), p(20f, 20f), strokeWidth = 2f * u, cap = StrokeCap.Round)
        }
        "▾" -> drawPath(path(6f to 9.5f, 12f to 15.5f, 18f to 9.5f), color, style = line)
    }
}
