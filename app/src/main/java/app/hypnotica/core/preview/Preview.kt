package app.hypnotica.core.preview

import kotlin.math.min

import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.SimCore
import app.hypnotica.core.sim.SimDef

/**
 * One frame of a sim, flattened small enough to keep for every sim at once.
 *
 * Library cards used to show an invented glyph. This is the sim itself: the same
 * draw list the renderer would get, sampled down to a few hundred shapes and
 * painted with the ordinary canvas, so a card needs no GL context of its own.
 *
 * Layout per shape, [STRIDE] floats:
 * x, y, halfWidth, halfHeight, rotation, shape, ringInner, r, g, b, a.
 */
class Preview(val data: FloatArray, val count: Int, val background: Int) {

    fun x(i: Int) = data[i * STRIDE]
    fun y(i: Int) = data[i * STRIDE + 1]
    fun hw(i: Int) = data[i * STRIDE + 2]
    fun hh(i: Int) = data[i * STRIDE + 3]
    fun rot(i: Int) = data[i * STRIDE + 4]
    fun shape(i: Int) = data[i * STRIDE + 5].toInt()
    fun ringInner(i: Int) = data[i * STRIDE + 6]
    fun r(i: Int) = data[i * STRIDE + 7]
    fun g(i: Int) = data[i * STRIDE + 8]
    fun b(i: Int) = data[i * STRIDE + 9]
    fun a(i: Int) = data[i * STRIDE + 10]

    val isEmpty: Boolean get() = count == 0

    companion object {
        const val STRIDE = 11

        /**
         * The most shapes a preview keeps. It is painted once into a small
         * picture rather than on every frame of a scroll, so it can carry a
         * whole field of dots; at 240 a Mandelbrot card was a sprinkle.
         */
        const val MAX_SHAPES = 60_000

        val EMPTY = Preview(FloatArray(0), 0, Palette.BG)
    }
}

/** Builds previews by running a sim headlessly for a moment. */
object Previews {

    /**
     * Run [def] to its best moment and snapshot the frame: just after the
     * payoff, or the end of its run, or — for a sim that has neither in
     * reach — as far as [MAX_SECONDS]. With [seconds] given, exactly that long.
     *
     * It was always the first 1.6 seconds, and a sim that builds — a prime
     * spiral, a Collatz path, Langton's ant, the ball-volume bars, falling
     * sand — showed as an all-but-empty card: the Library sold every sim on
     * the moment before anything had happened. Anything that throws returns
     * an empty preview rather than taking the Library down with it.
     */
    /** Why the last preview came back empty, for the test that checks them. */
    @Volatile
    var lastError: String? = null
        private set

    /** How far into a sort its card is taken: well under way, not done. */
    private const val SORT_PREVIEW_SHARE = 0.35f

    fun build(def: SimDef, seconds: Float? = null, aspect: Float = 1.35f): Preview {
        return try {
            val core = SimCore(def, SEED)
            core.setAspect(aspect)
            core.setPalette(Palette.byName(def.defaultPalette))
            // A card is 120 dp tall. It does not need 40,000 boids, and the
            // Library has to stay smooth while it scrolls.
            for (p in def.params) {
                val cap = previewCap(p.key) ?: continue
                if (core.params.f(p.key) > cap) core.params.set(p.key, cap)
            }
            core.restart(SEED)
            // A sort's payoff is a sorted array, and a sorted array is the same
            // green ramp for every algorithm: twelve identical cards. Mid-sort,
            // each one shows its own way of working.
            val midway = seconds == null && def.id.startsWith("sorting/") && def.id != "sorting/race"
            val limit = if (midway) def.typicalSeconds * SORT_PREVIEW_SHARE
            else seconds ?: min(def.typicalSeconds * 1.15f, MAX_SECONDS)
            val steps = (limit * core.stepHz).toInt()
            val after = (AFTER_PAYOFF_SECONDS * core.stepHz).toInt()
            var done = 0
            var payoffSeenAt = -1
            // A card is not worth more than this much of a phone's time: a
            // deep Mandelbrot zoom costs more every second it runs.
            val budgetEnds = System.nanoTime() + WALL_BUDGET_MS * 1_000_000L
            while (done < steps && !core.finished) {
                if (seconds == null && done > 0 && System.nanoTime() > budgetEnds) break
                core.advanceSteps(STEP_CHUNK)
                core.events.clear()
                done += STEP_CHUNK
                if (seconds == null && !midway) {
                    if (payoffSeenAt < 0 && core.payoffFired) payoffSeenAt = done
                    if (payoffSeenAt >= 0 && done - payoffSeenAt >= after) break
                }
            }
            val g = G()
            g.reset()
            core.draw(g)
            flatten(g, core.fit())
        } catch (e: Throwable) {
            lastError = "${def.id}: $e"
            Preview.EMPTY
        }
    }

    /**
     * [fit] is the pull-back a sim drawn at a fixed size asks for, applied
     * here as the player applies it, so a card shows the whole Fourier curve
     * rather than its middle.
     */
    private fun flatten(g: G, fit: Float = 1f): Preview {
        val total = g.instanceCount + g.glowCount + g.pointCount
        if (total <= 0) return Preview.EMPTY
        val keep = minOf(total, Preview.MAX_SHAPES)
        val out = FloatArray(keep * Preview.STRIDE)
        var written = 0

        // Sample evenly, so a 200,000-bar sort still shows its whole shape.
        // Integer division would keep the leftmost run whenever total is just
        // over the cap, so the step is fractional and carried along.
        val step = total.toFloat() / keep
        var cursor = 0f
        var index = 0

        // Glows first: they sit under everything, as on screen. Last, they
        // were painted over the picture.
        var i = 0
        while (i < g.glowCount && written < keep) {
            if (index >= cursor) {
                copyInstance(out, written, g.glows, i * G.STRIDE)
                written++
                cursor += step
            }
            index++
            i++
        }
        i = 0
        while (i < g.instanceCount && written < keep) {
            if (index >= cursor) {
                copyInstance(out, written, g.instances, i * G.STRIDE)
                written++
                cursor += step
            }
            index++
            i++
        }
        i = 0
        while (i < g.pointCount && written < keep) {
            if (index >= cursor) {
                val p = i * 3
                val c = i * 4
                val base = written * Preview.STRIDE
                val half = maxOf(0.005f, g.pointPos[p + 2] * 0.5f)
                out[base] = g.pointPos[p]
                out[base + 1] = g.pointPos[p + 1]
                out[base + 2] = half
                out[base + 3] = half
                out[base + 4] = 0f
                out[base + 5] = G.SHAPE_DISC
                out[base + 6] = 0f
                out[base + 7] = (g.pointColor[c].toInt() and 0xFF) / 255f
                out[base + 8] = (g.pointColor[c + 1].toInt() and 0xFF) / 255f
                out[base + 9] = (g.pointColor[c + 2].toInt() and 0xFF) / 255f
                out[base + 10] = (g.pointColor[c + 3].toInt() and 0xFF) / 255f
                written++
                cursor += step
            }
            index++
            i++
        }
        // Pulled back or closed in, as the player shows it; the card clips.
        if (fit != 1f) {
            for (k in 0 until written) {
                val base = k * Preview.STRIDE
                for (f in 0 until 4) out[base + f] *= fit
            }
        }
        return Preview(out, written, g.background)
    }

    /** cx, cy, hw, hh, rot, shape, e0, e1, r, g, b, a -> the preview layout. */
    private fun copyInstance(out: FloatArray, slot: Int, src: FloatArray, from: Int) {
        val base = slot * Preview.STRIDE
        out[base] = src[from]
        out[base + 1] = src[from + 1]
        out[base + 2] = src[from + 2]
        out[base + 3] = src[from + 3]
        out[base + 4] = src[from + 4]
        out[base + 5] = src[from + 5]
        out[base + 6] = src[from + 6]
        out[base + 7] = src[from + 8]
        out[base + 8] = src[from + 9]
        out[base + 9] = src[from + 10]
        out[base + 10] = src[from + 11]
    }

    /** Preview-only ceilings for the parameters that set a sim's size. */
    private fun previewCap(key: String): Float? = when (key) {
        // Painted once into a picture now, so these only bound the time a
        // card takes to simulate, not the cost of drawing it. A trail cut to
        // 1,200 points left the harmonograph a tiny last loop.
        "n", "count", "beads", "walkers", "particles" -> 4000f
        "trail" -> 40000f
        "dots", "points", "limit", "ink", "samples" -> 20000f
        "bodies" -> 150f
        "grid", "size" -> 96f
        "depth", "rings" -> 6f
        "balls" -> 12f
        else -> null
    }

    private const val SEED = 20260615L
    private const val STEP_CHUNK = 60

    /** The furthest a card will run looking for its sim's best moment. */
    private const val MAX_SECONDS = 24f

    /**
     * How long after the payoff the picture is taken: the moment itself.
     * Half a second later the escaping ball had gone and the card showed an
     * empty ring.
     */
    private const val AFTER_PAYOFF_SECONDS = 0.1f

    /** Wall-clock time one card may spend getting there. */
    private const val WALL_BUDGET_MS = 450L
}
