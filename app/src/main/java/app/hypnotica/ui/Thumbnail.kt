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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
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
 * Kept as pictures, not draw lists. A list had to be painted on every frame of
 * a scroll, so it was capped at 240 shapes — and a sim made of thousands of
 * dots (Mandelbrot, the prime spiral, a flock) came out as a sparse sprinkle
 * of specks. Painted once, off the main thread, into a small bitmap, a card
 * can carry the whole picture and costs one image draw to show.
 */
object PreviewCache {

    private val map = ConcurrentHashMap<String, ImageBitmap>()

    fun cached(id: String): ImageBitmap? = map[id]

    suspend fun load(def: SimDef): ImageBitmap? {
        map[def.id]?.let { return it }
        val built = withContext(Dispatchers.Default) {
            val preview = Previews.build(def)
            if (preview.isEmpty) null else paint(preview)
        } ?: return null
        map[def.id] = built
        return built
    }

    private fun paint(p: Preview): ImageBitmap {
        val bmp = ImageBitmap(CARD_PX_W, CARD_PX_H)
        val canvas = androidx.compose.ui.graphics.Canvas(bmp)
        androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
            Density(1f), LayoutDirection.Ltr, canvas, Size(CARD_PX_W.toFloat(), CARD_PX_H.toFloat())
        ) { drawPreview(p, 1f) }
        return bmp
    }

    /** The card's picture size: about a card's width on a dense phone screen. */
    private const val CARD_PX_W = 400
    private const val CARD_PX_H = 320
}

/**
 * A Library card's picture: the sim at its best moment. A faint palette
 * placeholder holds the place until it is ready, so a card is never empty.
 */
@Composable
fun SimThumbnail(def: SimDef, modifier: Modifier = Modifier) {
    var picture by remember(def.id) { mutableStateOf(PreviewCache.cached(def.id)) }
    LaunchedEffect(def.id) {
        if (picture == null) picture = PreviewCache.load(def)
    }
    val shown = picture
    // Fades up from a faint placeholder; one already cached starts at full,
    // so scrolling back is instant.
    val fade by animateFloatAsState(if (shown != null) 1f else 0f, tween(Tokens.Base), label = "thumbFade")
    Canvas(modifier) {
        if (fade < 1f) drawFallback(def, FALLBACK_ALPHA * (1f - fade))
        if (shown != null && fade > 0f) {
            // Cropped to fill, as the card is a slightly different shape.
            val scale = maxOf(size.width / shown.width, size.height / shown.height)
            val w = shown.width * scale
            val h = shown.height * scale
            drawImage(
                shown,
                dstOffset = IntOffset(((size.width - w) / 2f).toInt(), ((size.height - h) / 2f).toInt()),
                dstSize = IntSize(w.toInt(), h.toInt()),
                alpha = fade,
                filterQuality = FilterQuality.Medium
            )
        }
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

/**
 * Shown for the moment before the real preview exists: a soft glow in the
 * sim's own palette. It was nine bars, so while the cards filled in a
 * Mandelbrot and a flock both looked like sorting charts for a moment.
 */
private fun DrawScope.drawFallback(def: SimDef, alpha: Float) {
    val palette = app.hypnotica.core.render.Palette.byName(def.defaultPalette)
    val seed = def.id.hashCode()
    // Placed from the id, so a card's glow sits still rather than jumping.
    val fx = 0.3f + (seed and 0xFF) / 255f * 0.4f
    val fy = 0.3f + ((seed shr 8) and 0xFF) / 255f * 0.4f
    val r = min(size.width, size.height) * 0.62f
    for (k in 0 until 2) {
        val c = Color(palette.at(if (k == 0) 0.2f else 0.8f)).copy(alpha = alpha * 2.2f)
        val center = if (k == 0) Offset(size.width * fx, size.height * fy)
        else Offset(size.width * (1f - fx), size.height * (1f - fy))
        drawCircle(
            Brush.radialGradient(
                0f to c,
                1f to c.copy(alpha = 0f),
                center = center,
                radius = r
            ),
            r,
            center
        )
    }
}

private const val DEGREES = 57.29578f

/** How strongly the placeholder shows: a hint of the palette, not a picture. */
private const val FALLBACK_ALPHA = 0.18f
