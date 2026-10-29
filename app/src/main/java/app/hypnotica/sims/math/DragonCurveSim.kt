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
    private var swing = 0f
    private var landed = 0
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
        swing = 0f
        landed = 0
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
                val last = gen >= folds
                if (last && !four) {
                    unfolded = true
                    done = true
                    payoffFrame = true
                } else {
                    env.emit(EventKind.HIT, gen.toFloat() / folds, creasePan(env), 0.5f)
                    if (last) { unfolded = true; pause = PAUSE * 2f }
                }
            }
            return
        }
        // Three more dragons swing round the start point into place.
        if (pause > 0f) { pause -= a * speed; return }
        swing = min(1f, swing + a * speed / SWING_SECONDS)
        val now = (0 until 3).count { copyEase(it) >= 1f }
        if (now > landed) {
            landed = now
            if (landed >= 3) {
                done = true
                payoffFrame = true
            } else {
                env.emit(EventKind.HIT, 0.5f + 0.15f * landed, 0f, 0.55f)
            }
        }
    }

    /**
     * Copy c's progress. One at a time, each a quarter turn on from the one
     * before: swung all at once from the first dragon, they passed through
     * each other in a tangle on the way.
     */
    private fun copyEase(c: Int): Float =
        // At the very end exactly 1: (1 − 2/3)·3 comes out a hair under in
        // floats, and the last dragon never counted as landed.
        if (swing >= 1f) 1f else ((swing - c / 3f) * 3f).coerceIn(0f, 1f)

    /** Copy c's angle about the shared start. */
    private fun copyAngle(c: Int): Float = (c + smooth(copyEase(c))) * PI.toFloat() * 0.5f

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
        // round by 45° a fold.
        val tall = env.halfWidth < 0.9f
        val rho = -atan2(dy[m], dx[m]) + if (tall) PI.toFloat() * 0.5f else 0f
        val cr = cos(rho); val sr = sin(rho)
        val sw = (0 until 3).map { smooth(copyEase(it)) }
        val copies = if (unfolded && four) 4 else 1
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (c in 0 until copies) {
            val ang = if (c == 0) 0f else copyAngle(c - 1)
            val ca = cos(ang); val sa = sin(ang)
            for (i in 0..m) {
                val wx = dx[i] * ca - dy[i] * sa
                val wy = dx[i] * sa + dy[i] * ca
                val vx = wx * cr - wy * sr
                val vy = wx * sr + wy * cr
                if (vx < minX) minX = vx; if (vx > maxX) maxX = vx
                if (vy < minY) minY = vy; if (vy > maxY) maxY = vy
            }
        }
        val availW = env.halfWidth * 2f * 0.9f
        val availH = 1.82f
        val scale = min(availW / max(1e-3f, maxX - minX), availH / max(1e-3f, maxY - minY)).coerceAtMost(0.5f)
        val cx = (minX + maxX) * 0.5f
        val cy = (minY + maxY) * 0.5f
        // Under a quarter of a side: where the curve touches itself the two cut
        // corners pass 0.35 of a side apart, and a fatter line left them a
        // hairline slit apart, a black slash at every touch point.
        val width = (scale * 0.22f).coerceIn(0.0018f, 0.022f)
        g.reserveInstances(copies * m * 2 + 16)

        val tint = if (unfolded && four) smooth(sw[0] * 1.6f) else 0f
        for (c in copies - 1 downTo 0) {
            val ang = if (c == 0) 0f else copyAngle(c - 1)
            val show = if (c == 0) 1f else smooth(copyEase(c - 1) * 5f)
            if (show <= 0f) continue
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
            for (i in 0 until m) {
                val col = shade[(i * (SHADES - 1)) / m]
                // Corners cut at a quarter of each side: the strip's path can
                // be followed through the places where the curve touches itself.
                val ax = sx(i); val ay = sy(i); val bx = sx(i + 1); val by = sy(i + 1)
                val qx = ax + (bx - ax) * 0.25f; val qy = ay + (by - ay) * 0.25f
                val rx = ax + (bx - ax) * 0.75f; val ry = ay + (by - ay) * 0.75f
                if (flat) {
                    val a0 = base + dir[i] * half
                    g.rect((qx + rx) * 0.5f, (qy + ry) * 0.5f, sideLen * 0.25f + width * 0.5f, width * 0.5f, col, a0, show)
                    if (i + 1 < m) {
                        val turn = if (((dir[i + 1] - dir[i]) and 3) == 1) 1f else -1f
                        val nx = bx + (sx(i + 2) - bx) * 0.25f
                        val ny = by + (sy(i + 2) - by) * 0.25f
                        g.rect((rx + nx) * 0.5f, (ry + ny) * 0.5f, sideLen * 0.17678f + width * 0.5f, width * 0.5f, col, a0 + turn * half * 0.5f, show)
                    }
                } else {
                    cap(g, qx, qy, rx, ry, width, col, show)
                    if (i + 1 < m) {
                        val nx = bx + (sx(i + 2) - bx) * 0.25f
                        val ny = by + (sy(i + 2) - by) * 0.25f
                        cap(g, rx, ry, nx, ny, width, col, show)
                    }
                }
            }
        }
        // The crease being opened, and the start the four dragons share.
        if (opening) {
            val ex = ((dx[n] * cr - dy[n] * sr) - cx) * scale
            val ey = ((dx[n] * sr + dy[n] * cr) - cy) * scale
            // A steady marker, not a pulse: the user finds throbbing highlights
            // disorienting.
            g.disc(ex, ey, 0.009f, Palette.TEXT_PRIMARY, 0.75f)
        }
        if (unfolded && four) {
            val ox = (-cx) * scale; val oy = (-cy) * scale
            g.disc(ox, oy, max(0.009f, width * 1.1f), Palette.TEXT_PRIMARY, smooth(swing * 3f))
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
        "dragons" to if (four) "${1 + landed}" else "1"
    )

    companion object {
        private const val LEAD_IN = 0.5f
        private const val SHADES = 128
        private const val UNFOLD_SECONDS = 0.78f
        private const val PAUSE = 0.14f
        private const val SWING_SECONDS = 2.4f
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
