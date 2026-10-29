package app.hypnotica.sims.math

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.recipe.ParamInput
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.Shelf
import app.hypnotica.core.sim.Sim
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.sim.SimEnv
import app.hypnotica.core.sim.TrapFormat
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A space-filling curve, refined order by order.
 *
 * Each order replaces every cell of the last with a small copy of the curve,
 * so each point splits into four (nine for Peano's) and they spread out from
 * where their parent was. In the limit one unbroken line passes through every
 * point of the square; at the last order a pen runs its whole length to show
 * it is still a single line, and the colour, graded along it, shows the other
 * half of the story — points near each other on the line stay near each other
 * in the square.
 */
class HilbertCurveSim : Sim {

    private var kind = 0
    private var orders = 6
    /** Per order, the curve's cells in visiting order, as grid coordinates. */
    private var cellX = arrayOf<IntArray>()
    private var cellY = arrayOf<IntArray>()
    private var sides = IntArray(0)
    private var branch = 4

    private var order = 1
    private var morph = 1f
    private var holdLeft = 0f
    private var tracing = false
    private var pen = 0f
    private var done = false
    private var payoffFrame = false
    private var sinceDone = 0f
    private var sinceTrace = 0f

    override fun setup(env: SimEnv) {
        val p = env.params
        kind = p.choiceIndex("curve").coerceIn(0, 2)
        val detail = p.i("order", 6).coerceIn(1, 7)
        // Peano's curve splits in threes, so the same detail means fewer
        // orders: matched on cell size, not on the count.
        orders = if (kind == 2) max(1, (detail * ln(2.0) / ln(3.0)).roundToInt()) else detail
        branch = if (kind == 2) 9 else 4
        build()
        order = 1
        morph = 1f
        holdLeft = FIRST_HOLD
        tracing = false
        pen = 0f
        done = false
        payoffFrame = false
        sinceDone = 0f
        sinceTrace = 0f
    }

    // ------------------------------------------------------------ building

    private fun lsystem(o: Int): String {
        val (axiom, depth) = when (kind) {
            0 -> "A" to o
            1 -> "LFL+F+LFL" to (o - 1)
            else -> "X" to o
        }
        var s = axiom
        repeat(depth) {
            val b = StringBuilder(s.length * 9)
            for (ch in s) {
                b.append(
                    when (kind) {
                        0 -> when (ch) { 'A' -> "+BF-AFA-FB+"; 'B' -> "-AF+BFB+FA-"; else -> ch.toString() }
                        1 -> when (ch) { 'L' -> "-RF+LFL+FR-"; 'R' -> "+LF-RFR-FL+"; else -> ch.toString() }
                        else -> when (ch) { 'X' -> "XFYFX+F+YFXFY-F-XFYFX"; 'Y' -> "YFXFY-F-XFYFX+F+YFXFY"; else -> ch.toString() }
                    }
                )
            }
            s = b.toString()
        }
        return s
    }

    private fun build() {
        cellX = Array(orders) { IntArray(0) }
        cellY = Array(orders) { IntArray(0) }
        sides = IntArray(orders)
        for (o in 1..orders) {
            val s = lsystem(o)
            var n = 1
            for (ch in s) if (ch == 'F') n++
            val xs = IntArray(n); val ys = IntArray(n)
            var x = 0; var y = 0; var dx = 1; var dy = 0; var i = 1
            for (ch in s) {
                when (ch) {
                    'F' -> { x += dx; y += dy; xs[i] = x; ys[i] = y; i++ }
                    '+' -> { val t = dx; dx = -dy; dy = t }
                    '-' -> { val t = dx; dx = dy; dy = -t }
                }
            }
            val mx = xs.min(); val my = ys.min()
            for (j in 0 until n) { xs[j] -= mx; ys[j] -= my }
            val side = xs.max() + 1
            sides[o - 1] = side
            // Turn each order to sit the way the last one did, so its points
            // spread out of their own parent's cell rather than a mirror
            // image of it: the turtle's start and heading drift between
            // depths of the rewriting.
            var bestX = xs; var bestY = ys
            if (o == 1) {
                // The first order opens at the bottom of the square.
                var best = Int.MIN_VALUE
                for (sym in 0 until 8) {
                    val (tx, ty) = transform(xs, ys, side, sym)
                    val score = -(ty[0] + ty[n - 1]) * 4 - tx[0]
                    if (score > best) { best = score; bestX = tx; bestY = ty }
                }
            } else {
                val px = cellX[o - 2]; val py = cellY[o - 2]
                val ratio = side / sides[o - 2]
                var best = -1
                for (sym in 0 until 8) {
                    val (tx, ty) = transform(xs, ys, side, sym)
                    var agree = 0
                    for (j in 0 until n) {
                        val q = j / branch
                        if (tx[j] / ratio == px[q] && ty[j] / ratio == py[q]) agree++
                    }
                    if (agree > best) { best = agree; bestX = tx; bestY = ty }
                }
            }
            cellX[o - 1] = bestX; cellY[o - 1] = bestY
        }
    }

    private fun transform(xs: IntArray, ys: IntArray, side: Int, sym: Int): Pair<IntArray, IntArray> {
        val n = xs.size
        val ox = IntArray(n); val oy = IntArray(n)
        val m = side - 1
        for (j in 0 until n) {
            var x = xs[j]; var y = ys[j]
            if (sym and 4 != 0) { val t = x; x = y; y = t }
            if (sym and 1 != 0) x = m - x
            if (sym and 2 != 0) y = m - y
            ox[j] = x; oy[j] = y
        }
        return ox to oy
    }

    // ------------------------------------------------------------ running

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val a = abs(dt)
        if (done) { sinceDone += a; return }
        val speed = env.params.f("speed", 1f).coerceIn(0.05f, 10f)
        if (tracing) {
            sinceTrace += a
            val before = pen
            pen = min(1f, pen + a * speed / TRACE_SECONDS)
            // A note each sixteenth of the line, at the pen's side.
            if ((pen * TRACE_NOTES).toInt() != (before * TRACE_NOTES).toInt() || (pen >= 1f && before < 1f)) {
                val (px, _) = penPoint()
                env.emit(EventKind.WRITE, pen, (px * 2f - 1f).coerceIn(-1f, 1f), 0.32f)
            }
            if (pen >= 1f) { done = true; payoffFrame = true }
            return
        }
        if (morph < 1f) {
            morph = min(1f, morph + a * speed / MORPH_SECONDS)
            if (morph >= 1f) {
                holdLeft = HOLD_SECONDS
                // The new order has settled: a note higher for each.
                env.emit(EventKind.HIT, order.toFloat() / orders, 0f, 0.5f)
            }
            return
        }
        holdLeft -= a * speed
        if (holdLeft > 0f) return
        if (order < orders) {
            order++
            morph = 0f
        } else {
            tracing = true
        }
    }

    /** Where the pen is, as a share of the square (0..1 each way). */
    private fun penPoint(): Pair<Float, Float> {
        val xs = cellX[orders - 1]; val ys = cellY[orders - 1]
        val n = xs.size
        val f = pen * (n - 1)
        val i = min(n - 2, f.toInt()).coerceAtLeast(0)
        val u = f - i
        val side = sides[orders - 1].toFloat()
        val x = (xs[i] + (xs[i + 1] - xs[i]) * u + 0.5f) / side
        val y = (ys[i] + (ys[i + 1] - ys[i]) * u + 0.5f) / side
        return x to y
    }

    private class Layout(val cx: Float, val cy: Float, val size: Float, val barY: Float)

    private fun layout(env: SimEnv): Layout {
        val hw = env.halfWidth
        return if (hw < 0.9f) {
            val size = min(2f * hw * 0.92f, 1.3f)
            val cy = 0.14f
            Layout(0f, cy, size, cy - size * 0.5f - (1f + cy - size * 0.5f) * 0.45f)
        } else {
            Layout(0f, 0.12f, 1.46f, -0.86f)
        }
    }

    private var bx = FloatArray(0)
    private var by = FloatArray(0)

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        val lay = layout(env)
        val o = order
        val xs = cellX[o - 1]; val ys = cellY[o - 1]
        val n = xs.size
        if (bx.size < n) { bx = FloatArray(n); by = FloatArray(n) }
        val side = sides[o - 1].toFloat()
        val left = lay.cx - lay.size * 0.5f
        val bottom = lay.cy - lay.size * 0.5f
        val e = smooth(morph)
        val prevSide = if (o >= 2) sides[o - 2].toFloat() else side
        for (j in 0 until n) {
            val tx = left + (xs[j] + 0.5f) / side * lay.size
            val ty = bottom + (ys[j] + 0.5f) / side * lay.size
            if (o >= 2 && e < 1f) {
                // Spreading out of the parent's cell.
                val q = j / branch
                val px = left + (cellX[o - 2][q] + 0.5f) / prevSide * lay.size
                val py = bottom + (cellY[o - 2][q] + 0.5f) / prevSide * lay.size
                bx[j] = px + (tx - px) * e
                by[j] = py + (ty - py) * e
            } else {
                bx[j] = tx; by[j] = ty
            }
        }
        val cell = lay.size / (prevSide + (side - prevSide) * e)
        val width = max(0.0024f, cell * 0.42f)
        // A faint square the curve fills.
        g.rect(lay.cx, lay.cy, lay.size * 0.5f + 0.012f, lay.size * 0.5f + 0.012f, Palette.lerp(Palette.BG, pal.at(0.5f), 0.05f))
        g.reserveInstances(n + 200)
        val ahead = if (tracing || done) 1f - 0.5f * smooth(sinceTrace / 0.5f) else 1f
        val penAt = if (done) 1f else pen
        val lit = if (done) 1f - smooth(sinceDone / 1.2f) else 1f
        for (j in 0 until n - 1) {
            val t = j.toFloat() / max(1, n - 2)
            val col = pal.at(0.04f + 0.74f * t)
            val behind = (tracing || done) && t <= penAt
            val c = if (behind) Palette.brighten(col, 0.18f * lit) else col
            segment(g, bx[j], by[j], bx[j + 1], by[j + 1], width, c, if (behind) 1f else 0.88f * ahead)
        }
        if (tracing || done) {
            val (px, py) = penPoint()
            val sx = left + px * lay.size; val sy = bottom + py * lay.size
            val col = pal.at(0.04f + 0.74f * penAt)
            g.glow(sx, sy, max(0.05f, cell * 3f), col, 0.6f * lit)
            g.disc(sx, sy, max(0.011f, width * 0.9f), Palette.TEXT_PRIMARY, lit)
        }
        drawBar(g, pal, lay, penAt)
    }

    /** A line with square caps that reach past its ends, so corners close. */
    private fun segment(g: G, x0: Float, y0: Float, x1: Float, y1: Float, w: Float, c: Int, a: Float) {
        val dx = x1 - x0; val dy = y1 - y0
        val len = hypot(dx, dy)
        if (len < 1e-6f) { g.rect(x0, y0, w * 0.5f, w * 0.5f, c, 0f, a); return }
        g.rect((x0 + x1) * 0.5f, (y0 + y1) * 0.5f, len * 0.5f + w * 0.5f, w * 0.5f, c, atan2(dy, dx), a)
    }

    /**
     * The line before it was folded into the square: the same colours laid
     * out straight, with the pen's place on it. Every point of the bar has its
     * own place in the square, and neighbours on the bar stay neighbours there.
     */
    private fun drawBar(g: G, pal: Palette, lay: Layout, penAt: Float) {
        val half = lay.size * 0.5f
        val y = lay.barY
        val seg = 96
        val h = 0.016f
        val traced = tracing || done
        for (s in 0 until seg) {
            val t0 = s.toFloat() / seg
            val x = -half + (s + 0.5f) * (2f * half / seg)
            val behind = traced && t0 <= penAt
            g.rect(x, y, half / seg * 1.06f, h, pal.at(0.04f + 0.74f * (s + 0.5f) / seg), alpha = if (!traced || behind) 0.95f else 0.45f)
        }
        if (traced) {
            val x = -half + 2f * half * penAt
            g.rect(x, y, 0.004f, h * 2.1f, Palette.TEXT_PRIMARY)
        }
    }

    private fun smooth(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = done && sinceDone > HOLD_AFTER

    override fun counters(): List<Pair<String, String>> {
        val side = sides[order - 1]
        return listOf(
            "order" to "$order / $orders",
            "cells" to String.format(java.util.Locale.US, "%,d", side * side),
            "line" to if (tracing || done) String.format(java.util.Locale.US, "%.0f%%", pen * 100f) else "—"
        )
    }

    companion object {
        private const val FIRST_HOLD = 0.7f
        private const val MORPH_SECONDS = 1.05f
        private const val HOLD_SECONDS = 0.45f
        private const val TRACE_SECONDS = 3.2f
        private const val TRACE_NOTES = 16
        private const val HOLD_AFTER = 3.2f

        fun def(): SimDef = SimDef(
            id = "math/hilbert-curve",
            title = "Space-filling curve",
            shelf = Shelf.MATH,
            family = "Fractals & iteration",
            blurb = "One line, folded finer and finer, until it passes through every point of the square.",
            formats = listOf(TrapFormat.FILL, TrapFormat.GROW),
            heroLabel = "Draw again",
            defaultPalette = "Spectrum",
            typicalSeconds = 15f,
            params = listOf(
                Param.choice("curve", "Curve", listOf("Hilbert", "Moore", "Peano"), 0, card = Card.DATA, input = ParamInput.TILES),
                Param.int("order", "Detail", 1, 7, 6, card = Card.DATA),
                Param.float("speed", "Speed", 0.3f, 4f, 1f, card = Card.MOTION, logScale = true)
            ),
            create = { HilbertCurveSim() }
        )
    }
}
