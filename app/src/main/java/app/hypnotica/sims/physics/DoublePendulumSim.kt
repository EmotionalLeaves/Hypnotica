package app.hypnotica.sims.physics

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Macro
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.LawKnob
import app.hypnotica.core.sim.Parallel
import app.hypnotica.core.sim.Shelf
import app.hypnotica.core.sim.Sim
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.sim.SimEnv
import app.hypnotica.core.sim.TrapFormat
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The double-pendulum fan: hundreds of double pendulums let go from almost
 * exactly the same place — the same to six decimal places — swinging as one
 * for a few seconds, then peeling apart into a fan and a spray.
 *
 * It is the cleanest picture of chaos there is: nothing random anywhere, every
 * pendulum obeying the same two equations, and a difference smaller than the
 * width of an atom on the screen growing until no two of them agree.
 *
 * State is kept in doubles. Starting differences of a millionth of a radian
 * are below what a float can tell apart from 2.3, and the fan would split on
 * rounding rather than on the nudge the slider asked for.
 */
class DoublePendulumSim : Sim {

    private var count = 600
    private var a1 = DoubleArray(0)
    private var a2 = DoubleArray(0)
    private var w1 = DoubleArray(0)
    private var w2 = DoubleArray(0)
    private var turns = IntArray(0)
    private var flipped = BooleanArray(0)
    private var elapsed = 0f
    private var payoffFrame = false
    private var split = false
    private var splitAt = 0f
    private var flips = 0L
    private var spread = 0f
    private var leaderSide = true
    private var lastHeight = 0f
    private var rising = false

    /** How scattered they were at the start: a coarse nudge starts as a fan. */
    private var startSpread = 0f

    /** Where the first and last pendulums' lower bobs have been: their traced paths. */
    private val trailX = Array(2) { FloatArray(TRAIL) }
    private val trailY = Array(2) { FloatArray(TRAIL) }
    private var trailHead = 0
    private var trailFilled = 0

    override fun setup(env: SimEnv) {
        val p = env.params
        count = p.i("count", 600).coerceIn(1, MAX_PENDULUMS)
        val start = p.f("angle", 2.3f).toDouble()
        val nudge = 10.0.pow(-p.i("digits", 6).coerceIn(2, 9).toDouble())
        a1 = DoubleArray(count) { start + it * nudge }
        a2 = DoubleArray(count) { start + it * nudge }
        w1 = DoubleArray(count)
        w2 = DoubleArray(count)
        turns = IntArray(count) { turnOf(a2[it]) }
        flipped = BooleanArray(count)
        elapsed = 0f
        payoffFrame = false
        split = false
        splitAt = 0f
        flips = 0L
        spread = 0f
        leaderSide = a1.isEmpty() || a1[0] >= 0.0
        lastHeight = if (a1.isEmpty()) 0f else (-cos(a1[0]) - cos(a2[0])).toFloat()
        rising = false
        startSpread = bobSpread()
        trailHead = 0
        trailFilled = 0
    }

    /** Which full turn the lower arm is on; a change is a flip over the top. */
    private fun turnOf(a: Double): Int = floor((a + Math.PI) / (2.0 * Math.PI)).toInt()

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        val g = env.laws.gravity.toDouble() * GRAVITY_SCALE / ARM
        val damping = 1.0 - (env.laws.friction * abs(dt) * 0.3f).coerceIn(0f, 0.05f).toDouble()
        val h = dt.toDouble()

        // Every pendulum on its own, so the work splits across cores, and the
        // flips are marked here and sounded below in index order: a run has
        // to sound the same every time it is played.
        Parallel.forRange(count) { from, to ->
            for (i in from until to) {
                integrate(i, g, h)
                w1[i] *= damping
                w2[i] *= damping
                val t = turnOf(a2[i])
                if (t != turns[i]) {
                    turns[i] = t
                    flipped[i] = true
                }
            }
        }

        val voiceEvery = max(1, count / VOICES)
        var i = 0
        while (i < count) {
            if (flipped[i]) {
                flips++
                if (i % voiceEvery == 0) {
                    val t = if (count <= 1) 0.5f else i.toFloat() / (count - 1)
                    env.emit(EventKind.HIT, t, (t * 2f - 1f) * 0.8f, 0.6f)
                }
                flipped[i] = false
            }
            i++
        }

        // The leader's upper arm passing the bottom: a steady beat under the
        // flips, so the fan is never silent while it swings.
        // The top of each of the leader's swings, too — the moment it hangs
        // before falling back — so the first slow swing from high up is not
        // two silent seconds.
        val height = (-cos(a1[0]) - cos(a2[0])).toFloat()
        if (height < lastHeight && rising) {
            env.emit(EventKind.STEP, ((height + 2f) / 4f).coerceIn(0f, 1f), 0f, 0.3f)
        }
        rising = height > lastHeight
        lastHeight = height
        val side = a1[0] >= 0.0
        if (side != leaderSide) {
            leaderSide = side
            val height = (1.0 - cos(a2[0])).toFloat() / 2f
            env.emit(EventKind.STEP, height.coerceIn(0f, 1f), if (side) 0.3f else -0.3f, 0.35f)
        }

        // The payoff is the split: the moment the lower bobs stop agreeing,
        // measured as how widely they are scattered, not timed.
        spread = bobSpread()
        if (count > 0) {
            for ((slot, k) in intArrayOf(0, count - 1).withIndex()) {
                trailX[slot][trailHead] = (sin(a1[k]) * ARM + sin(a2[k]) * ARM).toFloat()
                trailY[slot][trailHead] = (-cos(a1[k]) * ARM - cos(a2[k]) * ARM).toFloat()
            }
            trailHead = (trailHead + 1) % TRAIL
            if (trailFilled < TRAIL) trailFilled++
        }
        // Against how scattered they began, too: with a coarse nudge the fan
        // starts open, and split the instant it was let go.
        val needed = max(SPLIT_SPREAD * ARM, startSpread * SPLIT_GROWTH)
        if (!split && count > 1 && elapsed > 1f && spread > needed) {
            split = true
            splitAt = elapsed
            payoffFrame = true
            env.emit(EventKind.DONE, 0.9f, 0f, 1f)
        }
    }

    /** One RK4 step of the standard equal-mass, equal-arm double pendulum. */
    private fun integrate(i: Int, g: Double, h: Double) {
        val t1 = a1[i]; val t2 = a2[i]; val o1 = w1[i]; val o2 = w2[i]
        val k1a1 = o1; val k1a2 = o2
        val k1w1 = acc1(t1, t2, o1, o2, g); val k1w2 = acc2(t1, t2, o1, o2, g)
        val m1 = t1 + k1a1 * h / 2; val m2 = t2 + k1a2 * h / 2
        val n1 = o1 + k1w1 * h / 2; val n2 = o2 + k1w2 * h / 2
        val k2w1 = acc1(m1, m2, n1, n2, g); val k2w2 = acc2(m1, m2, n1, n2, g)
        val p1 = t1 + n1 * h / 2; val p2 = t2 + n2 * h / 2
        val q1 = o1 + k2w1 * h / 2; val q2 = o2 + k2w2 * h / 2
        val k3w1 = acc1(p1, p2, q1, q2, g); val k3w2 = acc2(p1, p2, q1, q2, g)
        val r1 = t1 + q1 * h; val r2 = t2 + q2 * h
        val s1 = o1 + k3w1 * h; val s2 = o2 + k3w2 * h
        val k4w1 = acc1(r1, r2, s1, s2, g); val k4w2 = acc2(r1, r2, s1, s2, g)
        a1[i] = t1 + h / 6 * (k1a1 + 2 * n1 + 2 * q1 + s1)
        a2[i] = t2 + h / 6 * (k1a2 + 2 * n2 + 2 * q2 + s2)
        w1[i] = o1 + h / 6 * (k1w1 + 2 * k2w1 + 2 * k3w1 + k4w1)
        w2[i] = o2 + h / 6 * (k1w2 + 2 * k2w2 + 2 * k3w2 + k4w2)
    }

    // Masses and arms equal, arms of unit length with g scaled to match.
    private fun acc1(t1: Double, t2: Double, o1: Double, o2: Double, g: Double): Double {
        val d = t1 - t2
        val den = 3.0 - cos(2.0 * d)
        return (-3.0 * g * sin(t1) - g * sin(t1 - 2.0 * t2) -
            2.0 * sin(d) * (o2 * o2 + o1 * o1 * cos(d))) / den
    }

    private fun acc2(t1: Double, t2: Double, o1: Double, o2: Double, g: Double): Double {
        val d = t1 - t2
        val den = 3.0 - cos(2.0 * d)
        return (2.0 * sin(d) * (o1 * o1 * 2.0 + g * 2.0 * cos(t1) + o2 * o2 * cos(d))) / den
    }

    /** How far the lower bobs are scattered: the root-mean-square distance from their mean. */
    private fun bobSpread(): Float {
        if (count <= 1) return 0f
        val step = max(1, count / SPREAD_SAMPLES)
        var sx = 0.0; var sy = 0.0; var n = 0
        var i = 0
        while (i < count) {
            sx += sin(a1[i]) + sin(a2[i])
            sy += -cos(a1[i]) - cos(a2[i])
            n++
            i += step
        }
        val mx = sx / n; val my = sy / n
        var v = 0.0
        i = 0
        while (i < count) {
            val x = sin(a1[i]) + sin(a2[i]) - mx
            val y = -cos(a1[i]) - cos(a2[i]) - my
            v += x * x + y * y
            i += step
        }
        return (sqrt(v / n) * ARM).toFloat()
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val arm = ARM
        // The arms are always drawn. Above three hundred pendulums only the
        // bobs were, as dots — and before the split all six hundred sit on one
        // spot, so for the first ten seconds the screen showed a single speck
        // and no pendulum at all. Faint arms stacked on each other read as one
        // solid pendulum until they part, then as the fan.
        val armEvery = max(1, count / MAX_ARMS)
        val drawn = (count + armEvery - 1) / armEvery
        // Once they have split, the arms fade back and leave the spray of
        // bobs: six hundred faint rods from the pivot were a radial scribble.
        val after = if (split) (1f - (elapsed - splitAt) / 3f).coerceIn(0.3f, 1f) else 1f
        val faint = (3f / sqrt(drawn.toFloat())).coerceIn(0.05f, 0.6f) * after
        val asPoints = count > DRAWN_AS_POINTS
        g.reserveInstances(drawn * 2 + (if (asPoints) 0 else count) + 2 * TRAIL + 16)
        // A faint dial round the pivot — how far the lower bob can reach, and
        // the upper bob's circle inside it — so the frame has its shape
        // before the fan opens: until the split the screen was one thin line
        // in the dark.
        val dial = palette.at(0.6f)
        g.ring(0f, 0f, arm * 2f, 0.003f, dial, alpha = 0.22f)
        g.ring(0f, 0f, arm, 0.0025f, dial, alpha = 0.12f)
        if (asPoints) g.reservePoints(count)
        val bob = if (count > 40) 0.01f else 0.022f
        val size = (0.014f - count / 2_000_000f).coerceIn(0.005f, 0.014f)
        var i = 0
        while (i < count) {
            val t = if (count <= 1) 0.5f else i.toFloat() / (count - 1)
            val c = palette.at(t)
            val x1 = (sin(a1[i]) * arm).toFloat()
            val y1 = (-cos(a1[i]) * arm).toFloat()
            val x2 = x1 + (sin(a2[i]) * arm).toFloat()
            val y2 = y1 + (-cos(a2[i]) * arm).toFloat()
            if (i % armEvery == 0) {
                g.line(0f, 0f, x1, y1, 0.005f, c, faint)
                g.line(x1, y1, x2, y2, 0.004f, c, faint)
            }
            if (asPoints) g.point(x2, y2, size, c) else g.disc(x2, y2, bob, c)
            i++
        }
        // The path the marked bobs have traced, fading behind them: the
        // chaotic loops the pendulum is famous for, and something to watch in
        // the seconds before the split, when the fan is one thin pendulum.
        if (trailFilled > 1) {
            for (slot in 0 until (if (split && count > 1) 2 else 1)) {
                val c = palette.at(markT(palette, slot == 0))
                val tc = Palette.brighten(c, 0.12f)
                for (j in 1 until trailFilled) {
                    val a = (trailHead - trailFilled + j - 1 + TRAIL * 2) % TRAIL
                    val b = (a + 1) % TRAIL
                    val f = j.toFloat() / trailFilled
                    g.line(trailX[slot][a], trailY[slot][a], trailX[slot][b], trailY[slot][b], 0.005f + 0.009f * f, tc, 0.2f + 0.75f * f * f)
                }
            }
        }
        // Before the split, the stacked pendulum drawn as one solid one.
        if (!split && count > 0) {
            val c = palette.at(0f)
            val x1 = (sin(a1[0]) * arm).toFloat(); val y1 = (-cos(a1[0]) * arm).toFloat()
            val x2 = x1 + (sin(a2[0]) * arm).toFloat(); val y2 = y1 + (-cos(a2[0]) * arm).toFloat()
            // Bold, a solid rod and joint: six hundred pendulums in one.
            val rod = Palette.lerp(c, Palette.TEXT_PRIMARY, 0.3f)
            g.line(0f, 0f, x1, y1, 0.05f, c, 0.16f)
            g.line(x1, y1, x2, y2, 0.046f, c, 0.16f)
            g.line(0f, 0f, x1, y1, 0.024f, rod, 0.95f)
            g.line(x1, y1, x2, y2, 0.02f, rod, 0.95f)
            g.disc(x1, y1, 0.028f, rod)
            g.disc(x1, y1, 0.01f, Palette.BG, 0.6f)
        }
        // The first and the last pendulum, the two furthest apart at the
        // start, marked: one bright bob while they agree, two once they part.
        if (count > 1) {
            for (k in intArrayOf(0, count - 1)) {
                val c = palette.at(markT(palette, k == 0))
                val x2 = (sin(a1[k]) * arm + sin(a2[k]) * arm).toFloat()
                val y2 = (-cos(a1[k]) * arm - cos(a2[k]) * arm).toFloat()
                g.glow(x2, y2, 0.07f, c, 0.45f)
                g.disc(x2, y2, 0.018f, Palette.brighten(c, 0.3f))
            }
        }
        // The pivot: a mount, not a speck.
        g.disc(0f, 0f, 0.022f, Palette.TEXT_SECONDARY, 0.9f)
        g.disc(0f, 0f, 0.009f, Palette.BG)
    }

    /**
     * Where on the palette each marked pendulum's colour sits. A hue wheel
     * comes back round, so its far end is the same red as its start and the
     * two marked bobs and their traces were indistinguishable: half way is
     * the opposite hue.
     */
    private fun markT(p: Palette, first: Boolean): Float =
        if (first) 0f else if (p.at(0f) == p.at(0.999f)) 0.5f else 1f

    /** Both arms reach out from the pivot in every direction. */
    override fun frameHalfWidth(env: SimEnv): Float = ARM * 2f + 0.05f
    override fun frameHalfHeight(env: SimEnv): Float = ARM * 2f + 0.05f

    override fun payoff(): Boolean = payoffFrame

    /** Once they have split, a while to watch the spray, then go again. */
    override fun finished(): Boolean =
        (split && elapsed - splitAt > AFTER_SPLIT_SECONDS) ||
            // Let go low, a double pendulum swings politely and they never
            // disagree; after a long look the run goes round again.
            (!split && elapsed > NEVER_SPLITS_SECONDS)

    override fun counters(): List<Pair<String, String>> = listOf(
        "pendulums" to String.format(java.util.Locale.US, "%,d", count),
        "flips" to String.format(java.util.Locale.US, "%,d", flips),
        if (split) "split at" to String.format(java.util.Locale.US, "%.1f s", splitAt)
        else "agreeing" to "still"
    )

    companion object {
        /** Each arm's length on screen. */
        const val ARM = 0.42f

        /** Gravity in the sim's units: a swing of about two seconds. */
        const val GRAVITY_SCALE = 0.16f

        /** Scattered this far, as a share of an arm, they have split. */
        const val SPLIT_SPREAD = 0.3f

        const val AFTER_SPLIT_SECONDS = 9f
        const val SPLIT_GROWTH = 4f
        const val NEVER_SPLITS_SECONDS = 40f
        const val MAX_PENDULUMS = 20_000
        const val DRAWN_AS_POINTS = 2000
        /** At most this many pendulums have their arms drawn; the rest are sampled. */
        const val MAX_ARMS = 800
        const val VOICES = 40
        /** Steps of the marked bobs' traced path. */
        const val TRAIL = 330
        const val SPREAD_SAMPLES = 256

        fun def(): SimDef = SimDef(
            id = "physics/double-pendulum",
            title = "Double-pendulum fan",
            shelf = Shelf.PHYSICS,
            family = "Pendulums & oscillators",
            blurb = "Six hundred pendulums, a millionth of a radian apart. Watch them disagree.",
            formats = listOf(TrapFormat.SNAP, TrapFormat.CHAIN),
            lawKnobs = setOf(LawKnob.GRAVITY, LawKnob.FRICTION),
            heroLabel = "Drop again",
            defaultPalette = "Spectrum",
            typicalSeconds = 16f,
            params = listOf(
                Param.int("count", "Pendulums", 1, MAX_PENDULUMS, 600, card = Card.DATA, logScale = true),
                Param.int("digits", "Alike to", 2, 9, 6, card = Card.DATA, unit = "decimals"),
                Param.float("angle", "Start height", 0.6f, 3.1f, 2.3f, card = Card.MOTION, unit = "rad")
            ),
            macros = listOf(
                Macro("chaos", "Chaos", Card.MOTION, mapOf("angle" to 0.6f..3.1f), 0.68f)
            ),
            create = { DoublePendulumSim() }
        )
    }
}
