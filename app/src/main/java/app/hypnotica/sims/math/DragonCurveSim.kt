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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The Heighway dragon: a strip of paper folded in half again and again, then
 * opened out so every crease sits at a right angle.
 *
 * Opening one fold swings the second half of the strip a quarter turn about
 * the crease in the middle, and that is all this does: the curve so far is
 * copied, reversed, and turned 90° about its own end, again and again. The
 * line never crosses itself — its corners are cut so you can follow it — and
 * four dragons turned about their common start fit together without a gap
 * or an overlap.
 */
class DragonCurveSim : Sim {

    private var folds = 11
    private var four = true
    /** The whole unfolded strip; every generation is a prefix of it. */
    private var px = FloatArray(0)
    private var py = FloatArray(0)
    private var dir = IntArray(0)
    private var scrX = FloatArray(0)
    private var scrY = FloatArray(0)
    private val shade = IntArray(SHADES)

    private var gen = 0
    private var turn = 0f
    private var pause = LEAD_IN
    private var unfolded = false
    private var trace = 0f
    private var fillT = 0f
    private var done = false
    private var payoffFrame = false
    private var sinceDone = 0f

    override fun setup(env: SimEnv) {
        val p = env.params
        folds = p.i("folds", 11).coerceIn(4, 16)
        four = p.choiceIndex("copies") == 1
        val n = 1 shl folds
        px = FloatArray(n + 1); py = FloatArray(n + 1)
        px[0] = 0f; py[0] = 0f; px[1] = 1f; py[1] = 0f
        var len = 1
        while (len < n) {
            val ex = px[len]; val ey = py[len]
            // The reversed copy, turned a quarter anticlockwise about the end.
            for (j in 1..len) {
                val dx = px[len - j] - ex; val dy = py[len - j] - ey
                px[len + j] = ex - dy
                py[len + j] = ey + dx
            }
            len *= 2
        }
        // Each piece's heading, in quarter turns: the folds keep every piece
        // on the grid, so this is exact.
        dir = IntArray(n) { i ->
            val ddx = px[i + 1] - px[i]; val ddy = py[i + 1] - py[i]
            if (abs(ddx) > abs(ddy)) (if (ddx > 0f) 0 else 2) else (if (ddy > 0f) 1 else 3)
        }
        gen = 0
        turn = 0f
        pause = LEAD_IN
        unfolded = false
        trace = 0f
        fillT = 0f
        done = false
        payoffFrame = false
        sinceDone = 0f
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val a = abs(dt)
        if (done) { sinceDone += a; return }
        val speed = env.params.f("speed", 1f).coerceIn(0.05f, 10f)
        if (!unfolded) {
            if (pause > 0f) { pause -= a * speed; return }
            turn = min(1f, turn + a * speed / UNFOLD_SECONDS)
            if (turn >= 1f) {
                gen++
                turn = 0f
                pause = PAUSE
                // The crease snaps flat: a note a step higher each time,
                // from the side of the screen the crease is on.
                env.emit(EventKind.HIT, gen.toFloat() / folds, creasePan(env), 0.5f)
                if (gen >= folds) { unfolded = true; pause = PAUSE * 2f }
            }
            return
        }
        if (pause > 0f) { pause -= a * speed; return }
        // Three more dragons drawn out of the shared start, all at once.
        if (four && trace < 1f) {
            val before = trace
            trace = min(1f, trace + a * speed / TRACE_SECONDS)
            if ((trace * TRACE_NOTES).toInt() > (before * TRACE_NOTES).toInt() && trace < 1f) {
                env.emit(EventKind.STEP, 0.3f + 0.6f * trace, 0f, 0.3f)
            }
            if (trace >= 1f) {
                // The last pieces close the gaps between them.
                env.emit(EventKind.HIT, 0.85f, 0f, 0.55f)
                pause = PAUSE * 2f
            }
            return
        }
        // Every piece of strip becomes the square tile it is the diagonal of;
        // the tiles meet without gap or overlap, which is the payoff.
        fillT = min(1f, fillT + a * speed / FILL_SECONDS)
        if (fillT >= 1f) {
            done = true
            payoffFrame = true
        }
    }

    /**
     * Where the crease just opened sits across the screen, -1..1: the view
     * keeps start-to-end level (upright on a phone), so this is the crease's
     * offset from the middle of that line, across it.
     */
    private fun creasePan(env: SimEnv): Float {
        val n = 1 shl gen
        val half = 1 shl (gen - 1)
        val ex = px[half]; val ey = py[half]
        val mx = px[n] * 0.5f; val my = py[n] * 0.5f
        val len = max(1e-3f, hypot(px[n], py[n]))
        val ux = px[n] / len; val uy = py[n] / len
        val along = (ex - mx) * ux + (ey - my) * uy
        val across = -(ex - mx) * uy + (ey - my) * ux
        val tall = env.halfWidth < 0.9f
        return ((if (tall) -across else along) / (len * 0.6f)).coerceIn(-1f, 1f)
    }

    private fun smooth(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }

    // Scratch for the strip as drawn this frame.
    private var dx = FloatArray(0)
    private var dy = FloatArray(0)

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        val n = 1 shl min(gen, folds)
        val opening = !unfolded && gen < folds
        val th = smooth(turn) * (PI.toFloat() * 0.5f)
        val m = if (opening) 2 * n else n
        if (dx.size < m + 1) { dx = FloatArray(m + 1); dy = FloatArray(m + 1) }
        for (i in 0..n) { dx[i] = px[i]; dy[i] = py[i] }
        if (opening) {
            val ex = px[n]; val ey = py[n]
            val c = cos(th); val s = sin(th)
            for (j in 1..n) {
                val ux = px[n - j] - ex; val uy = py[n - j] - ey
                dx[n + j] = ex + ux * c - uy * s
                dy[n + j] = ey + ux * s + uy * c
            }
        }
        // The view turns with the strip, so start-to-end stays put and the
        // two halves open like a book rather than the whole curve wheeling
        // round by 45° a fold. Turned from the folded strip's start-to-end to
        // the opened one's, eased with the fold: the angle of the live end
        // was undefined while the strip lay folded flat (start and end in one
        // place), and the picture snapped a quarter turn at every fold.
        val tall = env.halfWidth < 0.9f
        val a0 = atan2(py[n], px[n])
        var view = a0
        if (opening) {
            var d = atan2(py[2 * n], px[2 * n]) - a0
            while (d > PI) d -= 2f * PI.toFloat()
            while (d < -PI) d += 2f * PI.toFloat()
            view = a0 + d * smooth(turn)
        }
        val rho = -view + if (tall) PI.toFloat() * 0.5f else 0f
        val cr = cos(rho); val sr = sin(rho)
        val fourNow = unfolded && four
        val copies = if (fourNow && trace > 0f) 4 else 1
        // Framed on the one dragon, then eased out to the four as they draw.
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var fx0 = 0f; var fx1 = 0f; var fy0 = 0f; var fy1 = 0f
        val blend = if (fourNow) smooth(trace) else 0f
        for (c in 0 until (if (blend > 0f) 4 else 1)) {
            val ang = c * PI.toFloat() * 0.5f
            val ca = cos(ang); val sa = sin(ang)
            for (i in 0..m) {
                val wx = dx[i] * ca - dy[i] * sa
                val wy = dx[i] * sa + dy[i] * ca
                val vx = wx * cr - wy * sr
                val vy = wx * sr + wy * cr
                if (vx < minX) minX = vx; if (vx > maxX) maxX = vx
                if (vy < minY) minY = vy; if (vy > maxY) maxY = vy
            }
            if (c == 0) { fx0 = minX; fx1 = maxX; fy0 = minY; fy1 = maxY }
        }
        minX = fx0 + (minX - fx0) * blend; maxX = fx1 + (maxX - fx1) * blend
        minY = fy0 + (minY - fy0) * blend; maxY = fy1 + (maxY - fy1) * blend
        val availW = env.halfWidth * 2f * 0.9f
        val availH = 1.82f
        val scale = min(availW / max(1e-3f, maxX - minX), availH / max(1e-3f, maxY - minY)).coerceAtMost(0.5f)
        val cx = (minX + maxX) * 0.5f
        val cy = (minY + maxY) * 0.5f
        // Under a quarter of a side: where the curve touches itself the two cut
        // corners pass 0.35 of a side apart, and a fatter line left them a
        // hairline slit apart, a black slash at every touch point.
        val width = (scale * 0.22f).coerceIn(0.0018f, 0.022f)
        g.reserveInstances(copies * m * 3 + 16)

        val tint = if (fourNow) smooth(trace * 1.6f) else 0f
        val drawn = (trace * m).toInt().coerceIn(0, m)
        for (c in copies - 1 downTo 0) {
            val ang = c * PI.toFloat() * 0.5f
            val upto = if (c == 0) m else drawn
            if (upto <= 0) continue
            val ca = cos(ang); val sa = sin(ang)
            val k0 = ca * cr - sa * sr
            val k1 = ca * sr + sa * cr
            // Screen position of strip point i for this copy: turn by the
            // copy's angle and the view's at once, centre, scale.
            if (scrX.size < m + 1) { scrX = FloatArray(m + 1); scrY = FloatArray(m + 1) }
            for (i in 0..m) {
                scrX[i] = ((dx[i] * k0 - dy[i] * k1) - cx) * scale
                scrY[i] = ((dx[i] * k1 + dy[i] * k0) - cy) * scale
            }
            val sxs = scrX; val sys = scrY
            fun sx(i: Int) = sxs[i]
            fun sy(i: Int) = sys[i]
            val hue = 0.04f + 0.235f * c
            // The colour along the strip, from a small table: two palette
            // lookups and two blends per piece were most of the frame.
            for (q in 0 until SHADES) {
                val t = q / (SHADES - 1f)
                val grad = pal.at(0.04f + 0.74f * t)
                val solid = Palette.lerp(Palette.BG, pal.at(hue), 0.62f + 0.38f * t)
                shade[q] = if (c == 0) Palette.lerp(grad, solid, tint) else solid
            }
            // Once every crease is open the strip only ever runs along four
            // directions, so each piece's angle is known without any trig —
            // with four dragons that is 32,000 pieces a frame, and atan2 and
            // hypot on each made the finale the slowest frame in the sim.
            val flat = !opening
            val base = rho + ang
            val half = PI.toFloat() * 0.5f
            val sideLen = scale
            val tile = sideLen * 0.35355f * 0.88f
            for (i in 0 until upto) {
                val col = shade[(i * (SHADES - 1)) / m]
                // The fill sweeps out from the shared start along the strip.
                val f = if (fillT > 0f) smooth((fillT * (1f + FILL_FRONT) - i.toFloat() / m) / FILL_FRONT) else 0f
                val lineA = 1f - f
                // Corners cut at a quarter of each side: the strip's path can
                // be followed through the places where the curve touches itself.
                val ax = sx(i); val ay = sy(i); val bx = sx(i + 1); val by = sy(i + 1)
                if (f > 0f) {
                    // The square whose diagonal is this piece: the squares of
                    // all the pieces tile the plane, so the dragons' tiles meet
                    // edge to edge without a gap or an overlap.
                    g.rect((ax + bx) * 0.5f, (ay + by) * 0.5f, tile, tile,
                        Palette.lerp(col, Palette.TEXT_PRIMARY, 0.08f), base + dir[i] * half + half * 0.5f, alpha = f)
                }
                if (lineA <= 0.01f) continue
                val qx = ax + (bx - ax) * 0.25f; val qy = ay + (by - ay) * 0.25f
                val rx = ax + (bx - ax) * 0.75f; val ry = ay + (by - ay) * 0.75f
                val last = i + 1 >= upto
                if (flat) {
                    val a0r = base + dir[i] * half
                    g.rect((qx + rx) * 0.5f, (qy + ry) * 0.5f, sideLen * 0.25f + width * 0.5f, width * 0.5f, col, a0r, lineA)
                    if (!last) {
                        val turnS = if (((dir[i + 1] - dir[i]) and 3) == 1) 1f else -1f
                        val nx = bx + (sx(i + 2) - bx) * 0.25f
                        val ny = by + (sy(i + 2) - by) * 0.25f
                        g.rect((rx + nx) * 0.5f, (ry + ny) * 0.5f, sideLen * 0.17678f + width * 0.5f, width * 0.5f, col, a0r + turnS * half * 0.5f, lineA)
                    }
                } else {
                    cap(g, qx, qy, rx, ry, width, col, lineA)
                    if (!last) {
                        val nx = bx + (sx(i + 2) - bx) * 0.25f
                        val ny = by + (sy(i + 2) - by) * 0.25f
                        cap(g, rx, ry, nx, ny, width, col, lineA)
                    }
                }
            }
            // The pens drawing the new dragons: steady points, not a pulse.
            if (c > 0 && trace < 1f) g.disc(sx(upto), sy(upto), max(0.008f, width * 1.1f), Palette.TEXT_PRIMARY, 0.85f)
        }
        // The crease being opened, and the start the four dragons share.
        if (opening) {
            // On the cut corner the line actually takes round the crease, not
            // the crease point itself, which the cut leaves off the line.
            val qx = (dx[n - 1] + dx[n + 1]) * 0.125f + dx[n] * 0.75f
            val qy = (dy[n - 1] + dy[n + 1]) * 0.125f + dy[n] * 0.75f
            val ex = ((qx * cr - qy * sr) - cx) * scale
            val ey = ((qx * sr + qy * cr) - cy) * scale
            // A steady marker, not a pulse: the user finds throbbing highlights
            // disorienting.
            g.disc(ex, ey, 0.009f, Palette.TEXT_PRIMARY, 0.75f)
        }
        if (fourNow) {
            val ox = (-cx) * scale; val oy = (-cy) * scale
            g.disc(ox, oy, max(0.009f, width * 1.1f), Palette.TEXT_PRIMARY, smooth(trace * 3f) * (1f - 0.6f * fillT))
        }
    }

    /** A segment with its ends run on half a width, so joints close. */
    private fun cap(g: G, x0: Float, y0: Float, x1: Float, y1: Float, w: Float, c: Int, a: Float) {
        val ddx = x1 - x0; val ddy = y1 - y0
        val len = hypot(ddx, ddy)
        if (len < 1e-7f) return
        g.rect((x0 + x1) * 0.5f, (y0 + y1) * 0.5f, len * 0.5f + w * 0.5f, w * 0.5f, c, atan2(ddy, ddx), a)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = done && sinceDone > HOLD_SECONDS

    override fun counters(): List<Pair<String, String>> = listOf(
        "folds" to "${min(gen, folds)} / $folds",
        "creases" to String.format(java.util.Locale.US, "%,d", (1 shl min(gen, folds)) - 1),
        "dragons" to if (four && trace >= 1f) "4" else "1"
    )

    companion object {
        private const val LEAD_IN = 0.5f
        private const val SHADES = 128
        private const val UNFOLD_SECONDS = 0.78f
        private const val PAUSE = 0.14f
        private const val TRACE_SECONDS = 2.4f
        private const val TRACE_NOTES = 10
        private const val FILL_SECONDS = 1.1f
        /** Width of the fill's front along the strip, as a share of it. */
        private const val FILL_FRONT = 0.35f
        private const val HOLD_SECONDS = 3.5f

        fun def(): SimDef = SimDef(
            id = "math/dragon-curve",
            title = "Paper-fold dragon",
            shelf = Shelf.MATH,
            family = "Fractals & iteration",
            blurb = "Fold a strip in half again and again, open every crease to a right angle: a dragon.",
            formats = listOf(TrapFormat.GROW, TrapFormat.PERFECT_FIT),
            heroLabel = "Unfold again",
            // Spectrum: the four finished dragons need four hues that can be told
            // apart, and Ember's are all one warm family.
            defaultPalette = "Spectrum",
            typicalSeconds = 19f,
            params = listOf(
                Param.int("folds", "Folds", 4, 16, 11, card = Card.DATA),
                Param.choice("copies", "Finish", listOf("One dragon", "Four dragons"), 1, card = Card.DATA, input = ParamInput.TILES),
                Param.float("speed", "Unfolding speed", 0.3f, 4f, 1f, card = Card.MOTION, logScale = true)
            ),
            create = { DragonCurveSim() }
        )
    }
}
