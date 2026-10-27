package app.hypnotica.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import app.hypnotica.core.preview.Preview
import app.hypnotica.core.preview.Previews
import app.hypnotica.core.render.G
import app.hypnotica.core.sim.SimDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Previews, built once per sim and kept for as long as the app is open.
 *
 * Every one of the 57 sims costs well under a frame to simulate for a moment,
 * but not while a list is scrolling, so they are built off the main thread and
 * remembered.
 */
object PreviewCache {

    private val map = ConcurrentHashMap<String, Preview>()

    fun cached(id: String): Preview? = map[id]

    suspend fun load(def: SimDef): Preview {
        map[def.id]?.let { return it }
        val built = withContext(Dispatchers.Default) { Previews.build(def) }
        map[def.id] = built
        return built
    }
}

/**
 * A Library card's picture: the sim's own first second, painted from its draw
 * list. Falls back to a palette bar chart for the moment before it is ready, so
 * a card is never empty.
 */
@Composable
fun SimThumbnail(def: SimDef, modifier: Modifier = Modifier) {
    var preview by remember(def.id) { mutableStateOf(PreviewCache.cached(def.id)) }
    LaunchedEffect(def.id) {
        if (preview == null) preview = PreviewCache.load(def)
    }
    val shown = preview
    Canvas(modifier) {
        if (shown == null || shown.isEmpty) {
            drawFallback(def)
        } else {
            drawPreview(shown)
        }
    }
}

private fun DrawScope.drawPreview(p: Preview) {
    val scale = size.height * 0.5f
    val cx = size.width * 0.5f
    val cy = size.height * 0.5f
    for (i in 0 until p.count) {
        val color = Color(
            red = p.r(i).coerceIn(0f, 1f),
            green = p.g(i).coerceIn(0f, 1f),
            blue = p.b(i).coerceIn(0f, 1f),
            alpha = p.a(i).coerceIn(0f, 1f)
        )
        val x = cx + p.x(i) * scale
        val y = cy - p.y(i) * scale
        val hw = (p.hw(i) * scale).coerceAtLeast(0.4f)
        val hh = (p.hh(i) * scale).coerceAtLeast(0.4f)
        when (p.shape(i)) {
            G.SHAPE_DISC.toInt() -> drawCircle(color, hw, Offset(x, y))

            G.SHAPE_GLOW.toInt() ->
                drawCircle(color.copy(alpha = color.alpha * 0.45f), hw * 1.3f, Offset(x, y))

            G.SHAPE_RING.toInt() -> {
                val thickness = ((1f - p.ringInner(i)) * hw).coerceIn(0.6f, hw)
                drawCircle(
                    color = color,
                    radius = (hw - thickness * 0.5f).coerceAtLeast(0.5f),
                    center = Offset(x, y),
                    style = Stroke(width = thickness)
                )
            }

            else -> {
                val rot = p.rot(i)
                if (abs(rot) < 0.01f) {
                    drawRect(color, Offset(x - hw, y - hh), Size(hw * 2f, hh * 2f))
                } else {
                    // Sim rotation is counter-clockwise in world space; the
                    // canvas y axis points the other way.
                    rotate(-rot * DEGREES, Offset(x, y)) {
                        drawRect(color, Offset(x - hw, y - hh), Size(hw * 2f, hh * 2f))
                    }
                }
            }
        }
    }
}

/** Shown for the moment before the real preview exists. */
private fun DrawScope.drawFallback(def: SimDef) {
    val palette = app.hypnotica.core.render.Palette.byName(def.defaultPalette)
    val bars = 9
    val gap = size.width * 0.02f
    val barWidth = (size.width - gap * (bars + 1)) / bars
    val seed = def.id.hashCode()
    for (i in 0 until bars) {
        val h = (((seed shr i) and 7) / 7f * 0.62f + 0.16f) * size.height
        drawRect(
            color = Color(palette.at(i / (bars - 1f))),
            topLeft = Offset(gap + i * (barWidth + gap), size.height - h),
            size = Size(barWidth, h)
        )
    }
}

private const val DEGREES = 57.29578f
