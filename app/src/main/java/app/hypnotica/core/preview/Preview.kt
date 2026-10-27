package app.hypnotica.core.preview

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
         * Enough to be recognisable, few enough to paint on a scrolling list:
         * eight cards on screen at this many shapes is already two thousand
         * draw calls a frame on the main thread.
         */
        const val MAX_SHAPES = 240

        val EMPTY = Preview(FloatArray(0), 0, Palette.BG)
    }
}

/** Builds previews by running a sim headlessly for a moment. */
object Previews {

    /**
     * Run [def] for [seconds] of simulated time and snapshot the frame.
     *
     * Deliberately short: the point is a card that looks like the sim, not a
     * finished run. Anything that throws returns an empty preview rather than
     * taking the Library down with it.
     */
    /** Why the last preview came back empty, for the test that checks them. */
    @Volatile
    var lastError: String? = null
        private set

    fun build(def: SimDef, seconds: Float = 1.6f, aspect: Float = 1.35f): Preview {
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
            val steps = (seconds * core.stepHz).toInt()
            var done = 0
            while (done < steps && !core.finished) {
                core.advanceSteps(STEP_CHUNK)
                core.events.clear()
                done += STEP_CHUNK
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

        var i = 0
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
        if (fit < 1f) {
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
        "n", "count", "beads", "trail", "walkers", "particles" -> 1200f
        "dots", "points", "limit", "ink", "samples" -> 1200f
        "bodies" -> 150f
        "grid", "size" -> 96f
        "depth", "rings" -> 6f
        "balls" -> 12f
        else -> null
    }

    private const val SEED = 20260615L
    private const val STEP_CHUNK = 60
}
