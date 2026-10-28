package app.hypnotica.core.render

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The draw list a sim fills in each frame.
 *
 * Sims never touch OpenGL. They push shapes into these flat arrays and the
 * renderer turns the whole frame into two or three draw calls, which is what
 * keeps a hundred thousand objects at 60 fps.
 *
 * World coordinates: y is up, and the view shows y in [-1, 1] by default with x
 * widened by the aspect ratio.
 */
class G {

    companion object {
        const val STRIDE = 12

        const val SHAPE_RECT = 0f
        const val SHAPE_DISC = 1f
        const val SHAPE_RING = 2f
        const val SHAPE_GLOW = 3f

        private const val INITIAL_INSTANCES = 2048
        private const val INITIAL_POINTS = 8192

        /**
         * Ceilings on one frame's draw list.
         *
         * Well past anything the shipped sims produce — the biggest is 400,000
         * particles — and chosen so the direct buffers the renderer uploads
         * stay bounded: 1.5 M points is about 24 MB of position and colour.
         */
        const val MAX_POINTS = 1_500_000
        const val MAX_INSTANCES = 400_000
    }

    /** cx, cy, hw, hh, rot, shape, e0, e1, r, g, b, a */
    var instances = FloatArray(INITIAL_INSTANCES * STRIDE)
        private set
    var instanceCount = 0
        private set

    /** Same layout, drawn additively underneath everything else. */
    var glows = FloatArray(512 * STRIDE)
        private set
    var glowCount = 0
        private set

    /** x, y, size for the huge-count path. */
    var pointPos = FloatArray(INITIAL_POINTS * 3)
        private set
    var pointColor = ByteArray(INITIAL_POINTS * 4)
        private set
    var pointCount = 0
        private set

    /** Requested full-screen flash this frame, 0..1. The safety limiter may reduce it. */
    /**
     * True when a draw asked for more than the renderer will hold.
     *
     * These buffers used to double without any ceiling, and the renderer then
     * allocated a direct buffer from whatever count arrived — on the GL thread,
     * outside every guard. A recipe whose draw loops a few million times
     * returned from its own guarded call perfectly normally and then took the
     * process down on the upload. Refusing to grow turns that into a line of
     * text; what is already in the buffer still draws.
     */
    @JvmField
    var overflowed = false

    var flashRequest = 0f
        private set

    /** Background colour for the frame, ARGB. */
    var background = 0xFF07080C.toInt()

    fun reset() {
        instanceCount = 0
        glowCount = 0
        pointCount = 0
        flashRequest = 0f
        overflowed = false
    }

    fun flash(strength: Float) {
        if (strength > flashRequest) flashRequest = strength.coerceIn(0f, 1f)
    }

    // ------------------------------------------------------------- shapes

    private fun ensureInstances(extra: Int) {
        val need = (instanceCount + extra) * STRIDE
        if (need <= instances.size) return
        if (need > MAX_INSTANCES * STRIDE) {
            overflowed = true
            return
        }
        var cap = instances.size
        while (cap < need) cap *= 2
        if (cap > MAX_INSTANCES * STRIDE) cap = MAX_INSTANCES * STRIDE
        instances = instances.copyOf(cap)
    }

    private fun ensureGlows(extra: Int) {
        val need = (glowCount + extra) * STRIDE
        if (need <= glows.size) return
        var cap = glows.size
        while (cap < need) cap *= 2
        glows = glows.copyOf(cap)
    }

    /**
     * True when every number is one the GPU can use.
     *
     * A NaN position is implementation-defined in the vertex stage, and several
     * drivers answer by rasterising the primitive across the whole viewport —
     * a full-screen flash that the safety limiter never sees, because it never
     * passed through [flash]. One test on the hot path is worth that.
     */
    private fun usable(a: Float, b: Float, c: Float, d: Float): Boolean =
        a.isFinite() && b.isFinite() && c.isFinite() && d.isFinite()

    private fun push(
        target: FloatArray, base: Int,
        cx: Float, cy: Float, hw: Float, hh: Float, rot: Float,
        shape: Float, e0: Float, e1: Float, color: Int, alphaScale: Float
    ) {
        target[base] = cx
        target[base + 1] = cy
        target[base + 2] = hw
        target[base + 3] = hh
        target[base + 4] = rot
        target[base + 5] = shape
        target[base + 6] = e0
        target[base + 7] = e1
        target[base + 8] = ((color ushr 16) and 0xFF) / 255f
        target[base + 9] = ((color ushr 8) and 0xFF) / 255f
        target[base + 10] = (color and 0xFF) / 255f
        target[base + 11] = (((color ushr 24) and 0xFF) / 255f) * alphaScale.coerceIn(0f, 1f)
    }

    fun rect(cx: Float, cy: Float, hw: Float, hh: Float, color: Int, rot: Float = 0f, alpha: Float = 1f) {
        if (!usable(cx, cy, hw, hh) || !rot.isFinite()) return
        ensureInstances(1)
        push(instances, instanceCount * STRIDE, cx, cy, hw, hh, rot, SHAPE_RECT, 0f, 0f, color, alpha)
        instanceCount++
    }

    /** A bar standing on [baseY], useful for sorting views. */
    fun bar(cx: Float, baseY: Float, halfWidth: Float, height: Float, color: Int, alpha: Float = 1f) {
        rect(cx, baseY + height * 0.5f, halfWidth, height * 0.5f, color, 0f, alpha)
    }

    fun disc(cx: Float, cy: Float, r: Float, color: Int, alpha: Float = 1f) {
        if (!usable(cx, cy, r, r)) return
        ensureInstances(1)
        push(instances, instanceCount * STRIDE, cx, cy, r, r, 0f, SHAPE_DISC, 0f, 0f, color, alpha)
        instanceCount++
    }

    /**
     * A ring. [thickness] is in world units; [gap] is the size of a missing arc in
     * radians and [gapAngle] where that gap sits.
     */
    fun ring(
        cx: Float, cy: Float, r: Float, thickness: Float, color: Int,
        gap: Float = 0f, gapAngle: Float = 0f, alpha: Float = 1f
    ) {
        if (!usable(cx, cy, r, thickness) || !gap.isFinite() || !gapAngle.isFinite()) return
        if (r <= 0f) return
        // coerceIn lets a NaN through, so the division is guarded above.
        val inner = ((r - thickness) / r).coerceIn(0f, 0.999f)
        ensureInstances(1)
        push(instances, instanceCount * STRIDE, cx, cy, r, r, gapAngle, SHAPE_RING, inner, gap, color, alpha)
        instanceCount++
    }

    /** A soft additive blob, drawn under the solid shapes. */
    fun glow(cx: Float, cy: Float, r: Float, color: Int, alpha: Float = 1f) {
        if (!usable(cx, cy, r, r)) return
        ensureGlows(1)
        push(glows, glowCount * STRIDE, cx, cy, r, r, 0f, SHAPE_GLOW, 0f, 0f, color, alpha)
        glowCount++
    }

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, color: Int, alpha: Float = 1f) {
        if (!usable(x1, y1, x2, y2) || !width.isFinite()) return
        val dx = x2 - x1
        val dy = y2 - y1
        val len = hypot(dx, dy)
        if (len < 1e-7f) return
        rect((x1 + x2) * 0.5f, (y1 + y2) * 0.5f, len * 0.5f, width * 0.5f, color, atan2(dy, dx), alpha)
    }

    fun polyline(
        xs: FloatArray,
        ys: FloatArray,
        count: Int,
        width: Float,
        color: Int,
        alpha: Float = 1f
    ) {
        @Suppress("NAME_SHADOWING")
        // A count larger than the arrays threw on the GL thread, which takes the
        // renderer with it. The shorter of the three wins.
        val count = minOf(count, xs.size, ys.size)
        if (count < 2) return
        ensureInstances(count - 1)
        for (i in 0 until count - 1) {
            line(xs[i], ys[i], xs[i + 1], ys[i + 1], width, color, alpha)
        }
    }

    // ------------------------------------------------------------- points

    private fun ensurePoints(extra: Int) {
        val need = pointCount + extra
        if (need * 3 <= pointPos.size) return
        if (need > MAX_POINTS) {
            overflowed = true
            return
        }
        var cap = pointPos.size / 3
        while (cap < need) cap *= 2
        if (cap > MAX_POINTS) cap = MAX_POINTS
        pointPos = pointPos.copyOf(cap * 3)
        pointColor = pointColor.copyOf(cap * 4)
    }

    /** The huge-count path: one soft dot, 16 bytes, no per-object draw call. */
    fun point(x: Float, y: Float, size: Float, color: Int) {
        if (!usable(x, y, size, size)) return
        ensurePoints(1)
        val p = pointCount * 3
        pointPos[p] = x
        pointPos[p + 1] = y
        pointPos[p + 2] = size
        val c = pointCount * 4
        pointColor[c] = ((color ushr 16) and 0xFF).toByte()
        pointColor[c + 1] = ((color ushr 8) and 0xFF).toByte()
        pointColor[c + 2] = (color and 0xFF).toByte()
        pointColor[c + 3] = ((color ushr 24) and 0xFF).toByte()
        pointCount++
    }

    /** Reserve room before a tight loop so the arrays grow once, not log(n) times. */
    fun reservePoints(n: Int) = ensurePoints(n)

    /**
     * Repeat everything drawn so far [folds] times round the centre, a
     * kaleidoscope. Shapes, glows and points each get turned copies; a shape's
     * own rotation turns with it, so a ring's gap and a bar's angle follow.
     * Stops short rather than overflow: a frame already near the ceiling
     * gets fewer copies, never a crash.
     */
    fun kaleido(folds: Int) {
        if (folds <= 1) return
        val step = (2.0 * Math.PI / folds).toFloat()
        val n0 = instanceCount
        val g0 = glowCount
        val p0 = pointCount
        for (m in 1 until folds) {
            val c = kotlin.math.cos(step * m)
            val sn = kotlin.math.sin(step * m)
            if ((instanceCount + n0) > MAX_INSTANCES || (pointCount + p0) > MAX_POINTS) {
                overflowed = true
                return
            }
            ensureInstances(n0)
            for (i in 0 until n0) {
                val from = i * STRIDE
                val to = instanceCount * STRIDE
                System.arraycopy(instances, from, instances, to, STRIDE)
                val x = instances[from]; val y = instances[from + 1]
                instances[to] = x * c - y * sn
                instances[to + 1] = x * sn + y * c
                instances[to + 4] = instances[from + 4] + step * m
                instanceCount++
            }
            ensureGlows(g0)
            for (i in 0 until g0) {
                val from = i * STRIDE
                val to = glowCount * STRIDE
                System.arraycopy(glows, from, glows, to, STRIDE)
                val x = glows[from]; val y = glows[from + 1]
                glows[to] = x * c - y * sn
                glows[to + 1] = x * sn + y * c
                glowCount++
            }
            ensurePoints(p0)
            for (i in 0 until p0) {
                val from = i * 3
                val to = pointCount * 3
                val x = pointPos[from]; val y = pointPos[from + 1]
                pointPos[to] = x * c - y * sn
                pointPos[to + 1] = x * sn + y * c
                pointPos[to + 2] = pointPos[from + 2]
                System.arraycopy(pointColor, i * 4, pointColor, pointCount * 4, 4)
                pointCount++
            }
        }
    }

    fun reserveInstances(n: Int) = ensureInstances(n)
}
