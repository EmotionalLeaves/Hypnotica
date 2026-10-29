package app.hypnotica.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Brush
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
import kotlin.math.min

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
 * A Library card's picture: the sim at its best moment, painted from its draw
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
    val ready = shown != null && !shown.isEmpty
    // Fades up from a faint placeholder. The placeholder used to be a fully
    // saturated bar chart — it looked like a sort sim — swapped out in a frame.
    // A preview already cached starts at full, so scrolling back is instant.
    val fade by animateFloatAsState(if (ready) 1f else 0f, tween(Tokens.Base), label = "thumbFade")
    Canvas(modifier) {
        if (fade < 1f) drawFallback(def, FALLBACK_ALPHA * (1f - fade))
        if (shown != null && !shown.isEmpty && fade > 0f) drawPreview(shown, fade)
    }
}

private fun DrawScope.drawPreview(p: Preview, fade: Float) {
    val scale = size.height * 0.5f
    val cx = size.width * 0.5f
    val cy = size.height * 0.5f
    for (i in 0 until p.count) {
        val color = Color(
            red = p.r(i).coerceIn(0f, 1f),
            green = p.g(i).coerceIn(0f, 1f),
            blue = p.b(i).coerceIn(0f, 1f),
            alpha = p.a(i).coerceIn(0f, 1f) * fade
        )
        val x = cx + p.x(i) * scale
        val y = cy - p.y(i) * scale
        val hw = (p.hw(i) * scale).coerceAtLeast(0.4f)
        val hh = (p.hh(i) * scale).coerceAtLeast(0.4f)
        when (p.shape(i)) {
            G.SHAPE_DISC.toInt() -> drawCircle(color, hw, Offset(x, y))

            // Soft, as the GL shader draws it ((1 − d)² from the middle out).
            // It was a flat translucent disc, which on a card read as a stain.
            G.SHAPE_GLOW.toInt() -> if (hw > 0.5f) drawCircle(
                Brush.radialGradient(
                    0f to color,
                    0.5f to color.copy(alpha = color.alpha * 0.25f),
                    1f to color.copy(alpha = 0f),
                    center = Offset(x, y),
                    radius = hw
                ),
                hw,
                Offset(x, y)
            )

            G.SHAPE_RING.toInt() -> {
                // A ring narrower than the minimum would make an empty range,
                // and coerceIn throws on one — inside a draw pass, which takes
                // the whole card down with it.
                val thickness = ((1f - p.ringInner(i)) * hw)
                    .coerceAtMost(hw)
                    .coerceAtLeast(min(0.6f, hw))
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
private fun DrawScope.drawFallback(def: SimDef, alpha: Float) {
    val palette = app.hypnotica.core.render.Palette.byName(def.defaultPalette)
    val bars = 9
    val gap = size.width * 0.02f
    val barWidth = (size.width - gap * (bars + 1)) / bars
    val seed = def.id.hashCode()
    for (i in 0 until bars) {
        val h = (((seed shr i) and 7) / 7f * 0.62f + 0.16f) * size.height
        drawRect(
            color = Color(palette.at(i / (bars - 1f))).copy(alpha = alpha),
            topLeft = Offset(gap + i * (barWidth + gap), size.height - h),
            size = Size(barWidth, h)
        )
    }
}

private const val DEGREES = 57.29578f

/** How strongly the placeholder shows: a hint of the palette, not a picture. */
private const val FALLBACK_ALPHA = 0.18f
