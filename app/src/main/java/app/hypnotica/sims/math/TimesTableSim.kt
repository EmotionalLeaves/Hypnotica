package app.hypnotica.sims.math

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
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
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A times table drawn on a circle.
 *
 * N points round a circle, and each point n is joined to point k·n (mod N).
 * Nothing is drawn but straight chords, yet they crowd onto a curve: at k = 2
 * the cardioid, at 3 the nephroid, and at every whole k the epicycloid with
 * k − 1 cusps — the envelope (k·e^{iθ} + e^{ikθ}) / (k + 1), which is drawn in
 * exactly once the multiplier locks on. Between whole numbers k slides and
 * the chords swirl from one curve to the next.
 */
class TimesTableSim : Sim {

    private var points = 240
    private var target = 3
    private var x = 0f
    private var locked = false
    private var payoffFrame = false
    private var sinceLock = 0f
    private var sweep = 0f
    private var elapsed = 0f

    override fun setup(env: SimEnv) {
        val p = env.params
        points = p.i("points", 240).coerceIn(10, 2000)
        target = TARGETS[p.choiceIndex("target").coerceIn(0, TARGETS.size - 1)]
        x = 0f
        locked = false
        payoffFrame = false
        sinceLock = 0f
        sweep = 0f
        elapsed = 0f
    }

    /** Seconds per whole step of k at speed 1: the longer the trip, the brisker each leg. */
    private fun unitSeconds(): Float = (11f / (target - 1)).coerceIn(2.6f, 8f)

    /**
     * k as a function of progress: x − sin(2πx)/2π has zero speed at every
     * whole number, so the sweep eases into each named curve, rests on it for
     * a beat, and pulls away again.
     */
    private fun kOf(x: Float): Float = 1f + x - (sin(2.0 * PI * x) / (2.0 * PI)).toFloat()

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val a = abs(dt)
        elapsed += a
        val speed = env.params.f("speed", 1f).coerceIn(0.05f, 10f)
        if (!locked) {
            val before = x
            val end = (target - 1).toFloat()
            if (elapsed > LEAD_IN) x = min(end, x + a * speed / unitSeconds())
            // Passing through a whole number: a named curve, if only for a beat.
            val passed = before.toInt()
            if (x.toInt() > passed && x < end) {
                env.emit(EventKind.CROSS, (x / end).coerceIn(0f, 1f), 0f, 0.55f)
            }
            if (x >= end) {
                locked = true
                payoffFrame = true
            }
        } else {
            // The envelope is drawn in by a point on a small circle rolling
            // round a fixed one; a note each time the point dips to a cusp,
            // on the side of the screen the cusp is on.
            val before = sinceLock
            sinceLock += a
            val k = target
            val t0 = 2.0 * PI * (before / TRACE_SECONDS).coerceIn(0f, 1f)
            val t1 = 2.0 * PI * (sinceLock / TRACE_SECONDS).coerceIn(0f, 1f)
            for (j in 0 until k - 1) {
                val tc = (2 * j + 1) * PI / (k - 1)
                if (tc > t0 && tc <= t1) {
                    val lay = layout(env)
                    val ex = (k * cos(tc) + cos(k * tc)) / (k + 1)
                    val ey = (k * sin(tc) + sin(k * tc)) / (k + 1)
                    val sx = lay.cx + lay.r * (ex * cos(ROT) - ey * sin(ROT)).toFloat()
                    env.emit(EventKind.HIT, 0.45f + 0.5f * j / max(1, k - 2), (sx / env.halfWidth).coerceIn(-1f, 1f), 0.45f)
                }
            }
        }
        // One chord shown bright, sweeping steadily round the circle, so the
        // rule n → k·n can be read off the picture as it moves (a chord that
        // blinked from place to place was a strobe). It sounds each time it
        // passes another stretch of points, pitched by where its far end
        // lands and panned to that side.
        // Only while k moves: once locked the chord only fades, and its ticks
        // ran on over the cusps' notes.
        if (!locked) {
            val before = sweep
            sweep = (sweep + a * speed * SWEEP_TURNS * points) % points
            val stride = max(1f, points / NOTES_PER_TURN)
            if ((sweep / stride).toInt() != (before / stride).toInt()) {
                val k = kOf(x)
                val to = ((k * sweep / points) % 1f + 1f) % 1f
                val ang = ROT + 2.0 * PI * to
                env.emit(EventKind.STEP, to, (cos(ang).toFloat() * 0.8f), 0.28f)
            }
        }
    }

    private class Layout(val cx: Float, val cy: Float, val r: Float, val rulerY: Float, val rulerHalf: Float)

    private fun layout(env: SimEnv): Layout {
        val hw = env.halfWidth
        return if (hw < 0.9f) {
            val r = min(hw * 0.93f, 0.6f)
            Layout(0f, 0.02f, r, 0.02f - r - (0.9f - r) * 0.42f, r * 0.9f)
        } else {
            Layout(0f, 0.09f, 0.83f, -0.87f, 0.75f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        val lay = layout(env)
        val k = kOf(x)
        val n = points
        val r = lay.r
        g.reserveInstances(n * 2 + 900)

        // Faint disc and rim: the stage.
        g.disc(lay.cx, lay.cy, r * 1.03f, Palette.lerp(Palette.BG, pal.at(0.5f), 0.06f))
        g.ring(lay.cx, lay.cy, r, 0.003f, Palette.TEXT_SECONDARY, alpha = 0.35f)

        val two = 2.0 * PI / n
        val width = (0.0032f - 0.0008f * (n / 600f)).coerceIn(0.0016f, 0.0032f)
        val settle = if (locked) (sinceLock / TRACE_SECONDS).coerceIn(0f, 1f) else 0f
        for (i in 1 until n) {
            val a0 = ROT + two * i
            val a1 = ROT + two * i * k
            val x0 = lay.cx + r * cos(a0).toFloat()
            val y0 = lay.cy + r * sin(a0).toFloat()
            val x1 = lay.cx + r * cos(a1).toFloat()
            val y1 = lay.cy + r * sin(a1).toFloat()
            val len = hypot(x1 - x0, y1 - y0) / (2f * r)
            if (len < 1e-4f) continue
            // Colour by chord length: the short chords hug the curve and take
            // the bright end, the long ones cross the middle and recede.
            val col = pal.at(0.05f + 0.68f * len)
            g.line(x0, y0, x1, y1, width, col, 0.62f - 0.25f * len - 0.12f * settle)
        }
        // The points themselves, small, on the rim.
        val dot = (0.006f - n / 400000f).coerceIn(0.0028f, 0.006f)
        for (i in 0 until n) {
            val a0 = ROT + two * i
            g.disc(lay.cx + r * cos(a0).toFloat(), lay.cy + r * sin(a0).toFloat(), dot, Palette.TEXT_SECONDARY, 0.7f)
        }

        // The sweeping chord, easing out once the curve is drawn in.
        val hl = if (locked) 1f - (sinceLock / TRACE_SECONDS).coerceIn(0f, 1f) else 1f
        if (hl > 0f) {
            val a0 = ROT + two * sweep
            val a1 = ROT + two * sweep * k
            val x0 = lay.cx + r * cos(a0).toFloat(); val y0 = lay.cy + r * sin(a0).toFloat()
            val x1 = lay.cx + r * cos(a1).toFloat(); val y1 = lay.cy + r * sin(a1).toFloat()
            g.line(x0, y0, x1, y1, 0.004f, Palette.TEXT_PRIMARY, 0.75f * hl)
            g.disc(x0, y0, 0.010f, Palette.TEXT_PRIMARY, hl)
            g.disc(x1, y1, 0.010f, Palette.TEXT_PRIMARY, hl)
        }

        // The exact envelope: a ghost of it as k rests on each whole number on
        // the way, and on the last one drawn in for good.
        val near = abs(x - x.roundToInt())
        val ghost = if (!locked) ((1f - near / 0.1f).coerceIn(0f, 1f) * 0.55f) else 0f
        if (ghost > 0.01f && x.roundToInt() >= 1) drawEnvelope(g, lay, k.roundToInt().toFloat(), 1f, ghost, pal, false)
        if (locked) {
            val away = 1f - ((sinceLock - TRACE_SECONDS) / 0.7f).coerceIn(0f, 1f)
            drawRoller(g, lay, target, settle, away * away * (3f - 2f * away))
            drawEnvelope(g, lay, target.toFloat(), settle, 1f, pal, true)
        }

        drawRuler(g, pal, lay, k)
    }

    private fun drawEnvelope(g: G, lay: Layout, k: Float, upTo: Float, alpha: Float, pal: Palette, pen: Boolean) {
        val steps = 360
        val last = (steps * upTo).toInt()
        if (last < 1) return
        val col = Palette.brighten(pal.at(0.05f), 0.35f)
        var px = 0f; var py = 0f
        for (s in 0..last) {
            val t = 2.0 * PI * s / steps
            val ex = (k * cos(t) + cos(k * t)) / (k + 1)
            val ey = (k * sin(t) + sin(k * t)) / (k + 1)
            // Turned with the points, which start from the top.
            val xr = (ex * cos(ROT) - ey * sin(ROT)).toFloat()
            val yr = (ex * sin(ROT) + ey * cos(ROT)).toFloat()
            val sx = lay.cx + lay.r * xr
            val sy = lay.cy + lay.r * yr
            if (s > 0) {
                g.line(px, py, sx, sy, 0.0105f, Palette.BG, alpha * 0.7f)
                g.line(px, py, sx, sy, 0.0055f, col, alpha)
            }
            px = sx; py = sy
        }
        if (pen && upTo < 1f) {
            g.glow(px, py, 0.07f, col, 0.7f)
            g.disc(px, py, 0.011f, Palette.TEXT_PRIMARY)
        }
    }

    /**
     * Why the chords make an epicycloid: a circle of radius 1/(k+1) rolling
     * round a fixed one of radius (k−1)/(k+1) carries a point that traces the
     * same envelope. Shown while the pen draws it, then eased away.
     */
    private fun drawRoller(g: G, lay: Layout, k: Int, upTo: Float, a: Float) {
        if (a <= 0.01f) return
        val r = lay.r
        val fixedR = r * (k - 1f) / (k + 1f)
        val rollR = r / (k + 1f)
        val t = 2.0 * PI * upTo
        val ux = (cos(t) * cos(ROT) - sin(t) * sin(ROT)).toFloat()
        val uy = (cos(t) * sin(ROT) + sin(t) * cos(ROT)).toFloat()
        val ccx = lay.cx + (fixedR + rollR) * ux
        val ccy = lay.cy + (fixedR + rollR) * uy
        val vx = (cos(k * t) * cos(ROT) - sin(k * t) * sin(ROT)).toFloat()
        val vy = (cos(k * t) * sin(ROT) + sin(k * t) * cos(ROT)).toFloat()
        val ppx = ccx + rollR * vx; val ppy = ccy + rollR * vy
        g.ring(lay.cx, lay.cy, fixedR, 0.0026f, Palette.TEXT_SECONDARY, alpha = 0.5f * a)
        g.disc(ccx, ccy, rollR, Palette.BG, 0.45f * a)
        g.ring(ccx, ccy, rollR, 0.003f, Palette.TEXT_PRIMARY, alpha = 0.75f * a)
        g.line(ccx, ccy, ppx, ppy, 0.003f, Palette.TEXT_PRIMARY, 0.75f * a)
        g.disc(ccx, ccy, 0.006f, Palette.TEXT_PRIMARY, 0.8f * a)
    }

    /** A number line for k: the whole numbers it will lock to, and where it is now. */
    private fun drawRuler(g: G, pal: Palette, lay: Layout, k: Float) {
        val y = lay.rulerY
        val half = lay.rulerHalf
        val span = (target - 1).coerceAtLeast(1)
        fun xs(v: Float) = -half + 2f * half * ((v - 1f) / span)
        g.line(-half, y, half, y, 0.003f, Palette.TEXT_SECONDARY, 0.4f)
        g.line(-half, y, xs(k), y, 0.005f, pal.at(0.05f), 0.9f)
        for (w in 1..target) {
            val tx = xs(w.toFloat())
            val reached = k >= w - 1e-3f
            g.rect(tx, y, 0.003f, 0.022f, if (reached) Palette.TEXT_PRIMARY else Palette.TEXT_SECONDARY, alpha = if (reached) 0.9f else 0.4f)
        }
        g.glow(xs(k), y, 0.05f, pal.at(0.05f), 0.5f)
        g.disc(xs(k), y, 0.013f, Palette.TEXT_PRIMARY)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = locked && sinceLock > HOLD_SECONDS

    override fun counters(): List<Pair<String, String>> {
        val k = kOf(x)
        val whole = k.roundToInt()
        val named = abs(k - whole) < 0.01f && whole >= 2
        return listOf(
            "k" to String.format(java.util.Locale.US, "×%.2f", k),
            "curve" to if (named) curveName(whole) else "—",
            "points" to points.toString()
        )
    }

    private fun curveName(k: Int): String = when (k) {
        2 -> "cardioid"
        3 -> "nephroid"
        else -> "epicycloid, ${k - 1} cusps"
    }

    companion object {
        /** Point 0 at the top, so the cardioid's cusp points up. */
        private val ROT = PI / 2.0
        private val TARGETS = intArrayOf(2, 3, 4, 5, 6, 8, 10)
        private const val LEAD_IN = 0.6f
        /** Turns of the circle a second for the bright chord. */
        private const val SWEEP_TURNS = 0.09f
        private const val NOTES_PER_TURN = 36f
        private const val TRACE_SECONDS = 2.6f
        private const val HOLD_SECONDS = 4.6f

        fun def(): SimDef = SimDef(
            id = "math/times-table",
            title = "Times tables on a circle",
            shelf = Shelf.MATH,
            family = "Number theory",
            blurb = "Join n to k·n round a circle and the chords crowd into a cardioid, then a nephroid.",
            formats = listOf(TrapFormat.REVEAL, TrapFormat.PERFECT_FIT),
            heroLabel = "Sweep again",
            defaultPalette = "Aurora",
            typicalSeconds = 16f,
            params = listOf(
                Param.choice("target", "Lock onto", listOf("Cardioid ×2", "Nephroid ×3", "×4", "×5", "×6", "×8", "×10"), 1, card = Card.DATA),
                Param.int("points", "Points", 10, 2000, 240, card = Card.DATA, logScale = true),
                Param.float("speed", "Sweep speed", 0.2f, 5f, 1f, card = Card.MOTION, logScale = true)
            ),
            create = { TimesTableSim() }
        )
    }
}
