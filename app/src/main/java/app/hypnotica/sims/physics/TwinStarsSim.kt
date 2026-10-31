package app.hypnotica.sims.physics

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
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The same three stars twice, on top of each other: the 3, 4, 5 triangle, and
 * a copy whose lightest star starts a hair to one side — a millionth of a
 * millionth, by default. Nothing in either is random.
 *
 * For a long while the copy is invisible, exactly under the original. The gap
 * between them is drawn as a gauge counting its digits: it grows tenfold
 * every few swings, and when it reaches the size of the picture the two
 * systems come apart and go their own ways — sometimes throwing out a
 * different star. That is chaos: perfect order, every step of it, and still
 * impossible to predict.
 */
class TwinStarsSim : Sim {

    private val a = ThreeBody()
    private val b = ThreeBody()
    private var scale = 0.1f
    private var nudge = 1e-12
    private var gap = 0.0
    private var digitsSeen = 0
    private var splitAt = -1f
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var outA = -1
    private var outB = -1
    private val tx = Array(6) { FloatArray(TRAIL) }
    private val ty = Array(6) { FloatArray(TRAIL) }
    private var head = 0
    private var count = 0
    private var tick = 0f

    override fun setup(env: SimEnv) {
        val digits = env.params.i("digit", 12).coerceIn(2, 15)
        nudge = 10.0.pow(-digits)
        for (s in arrayOf(a, b)) {
            s.reset()
            s.set(0, 3.0, 1.0, 3.0, 0.0, 0.0)
            s.set(1, 4.0, -2.0, -1.0, 0.0, 0.0)
            s.set(2, 5.0, 1.0, -1.0, 0.0, 0.0)
        }
        b.x[0] += nudge
        a.centre(); b.centre()
        scale = env.halfWidth * 0.92f / 4.2f
        gap = nudge
        digitsSeen = digits
        splitAt = -1f
        outA = -1; outB = -1
        elapsed = 0f; doneAt = -1f; payoffFrame = false
        head = 0; count = 0; tick = 0f
        record()
    }

    private fun record() {
        for (i in 0..2) {
            tx[i][head] = a.x[i].toFloat() * scale; ty[i][head] = a.y[i].toFloat() * scale
            tx[i + 3][head] = b.x[i].toFloat() * scale; ty[i + 3][head] = b.y[i].toFloat() * scale
        }
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        val speed = env.params.f("speed", 1f).coerceIn(0.1f, 5f)
        val target = a.t + h * RATE * speed
        a.advanceWith(b, target, ETA)

        // The gap: the largest distance between a star and its twin.
        var g = 0.0
        for (i in 0..2) {
            val dx = a.x[i] - b.x[i]; val dy = a.y[i] - b.y[i]
            g = max(g, sqrt(dx * dx + dy * dy))
        }
        gap = max(g, 1e-18)
        // Each new digit of disagreement is a tick, climbing.
        val d = (-log10(gap)).toInt()
        if (d < digitsSeen) {
            digitsSeen = d
            env.emit(EventKind.STEP, (0.2f + 0.05f * (15 - d)).coerceIn(0f, 1f), 0f, 0.3f)
            // The last few digits of agreement, counted down.
            if (splitAt < 0f && d in 1..3) env.callout(if (d == 1) "One digit left…" else "$d digits left…")
        }
        if (splitAt < 0f && gap > SPLIT) {
            splitAt = elapsed
            doneAt = elapsed
            payoffFrame = true
            env.emit(EventKind.DONE, 0.9f, 0f, 1f)
        }
        if (outA < 0) { outA = a.ejected(8.0); if (outA >= 0) { env.emit(EventKind.CROSS, 0.4f, -0.5f, 0.6f); env.callout("Flung out!") } }
        if (outB < 0) { outB = b.ejected(8.0); if (outB >= 0) env.emit(EventKind.CROSS, 0.7f, 0.5f, 0.6f) }

        tick += h
        if (tick >= 1f / 60f) {
            tick = 0f
            head = (head + 1) % TRAIL
            count = min(TRAIL, count + 1)
            record()
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        val n = min(count, TRAIL - 1)
        // The copy first, underneath, in its own colour; then the original.
        for (twin in intArrayOf(1, 0)) {
            for (i in 0..2) {
                val k = i + twin * 3
                val c = if (twin == 0) pal.at(HUE[i]) else Palette.TEXT_PRIMARY
                for (j in 1..n) {
                    val p = (head - j + TRAIL) % TRAIL
                    val q = (head - j + 1 + TRAIL) % TRAIL
                    if (!onScreen(env, tx[k][p], ty[k][p], 0f) || !onScreen(env, tx[k][q], ty[k][q], 0f)) continue
                    val f = 1f - j / TRAIL.toFloat()
                    g.line(tx[k][p], ty[k][p], tx[k][q], ty[k][q], 0.005f * (0.4f + f), c, (if (twin == 0) 0.7f else 0.4f) * f)
                }
            }
        }
        for (i in 0..2) {
            val c = pal.at(HUE[i])
            val r = 0.011f * kotlin.math.cbrt(a.m[i].toFloat())
            val ax = a.x[i].toFloat() * scale; val ay = a.y[i].toFloat() * scale
            val bx = b.x[i].toFloat() * scale; val by = b.y[i].toFloat() * scale
            if (!onScreen(env, ax, ay, r * 5f) || !onScreen(env, bx, by, r * 5f)) {
                if (onScreen(env, ax, ay, r * 5f)) { g.glow(ax, ay, r * 5f, c, 0.35f); g.disc(ax, ay, r, Palette.brighten(c, 0.5f)) }
                if (onScreen(env, bx, by, r * 5f)) g.ring(bx, by, r * 1.9f, r * 0.35f, Palette.TEXT_PRIMARY, 0f, 0f, 0.8f)
                continue
            }
            g.ring(bx, by, r * 1.9f, r * 0.35f, Palette.TEXT_PRIMARY, 0f, 0f, 0.8f)
            g.glow(ax, ay, r * 5f, c, 0.35f)
            g.disc(ax, ay, r, Palette.brighten(c, 0.5f))
        }
        // The gauge: how many digits the twins still agree to, as a bar
        // filling from the left — empty at the starting digit, full at "apart".
        val start = -log10(nudge).toFloat()
        val now = (-log10(gap)).toFloat()
        val share = ((start - now) / (start + 1f)).coerceIn(0f, 1f)
        val w = env.halfWidth * 0.8f
        val y = -0.9f
        g.rect(0f, y, w, 0.006f, Palette.TEXT_SECONDARY, 0f, 0.25f)
        g.rect(-w + w * share, y, w * share, 0.006f, if (splitAt >= 0f) Palette.SUCCESS else pal.at(0.95f))
        for (k in 0..start.toInt()) {
            val x = -w + 2f * w * k / (start + 1f)
            g.rect(x, y + 0.02f, 0.002f, 0.008f, Palette.TEXT_SECONDARY, 0f, 0.5f)
        }
    }

    private fun onScreen(env: SimEnv, x: Float, y: Float, r: Float): Boolean =
        abs(x) < env.halfWidth * 0.98f - r && abs(y) < 0.98f - r

    override fun payoff(): Boolean = payoffFrame

    /** Endless: once apart, the two go on their separate ways. */
    override fun finished(): Boolean = false

    override fun counters(): List<Pair<String, String>> = listOf(
        "nudged by" to String.format(java.util.Locale.US, "%.0e", nudge),
        "apart by" to String.format(java.util.Locale.US, "%.1e", gap),
        "original throws out" to name(outA),
        "copy throws out" to name(outB)
    )

    private fun name(i: Int) = if (i < 0) "not yet" else "the ${a.m[i].toInt()}"

    companion object {
        const val TRAIL = 180
        const val RATE = 3.6f
        const val ETA = 0.005
        const val SPLIT = 0.15
        private val HUE = floatArrayOf(0.12f, 0.6f, 0.95f)

        fun def(): SimDef = SimDef(
            id = "physics/sixteenth-digit",
            title = "The sixteenth digit",
            shelf = Shelf.PHYSICS,
            family = "Orbits, gravity & space",
            blurb = "The same three stars twice, one nudged by a millionth of a millionth. Watch them stop agreeing.",
            formats = listOf(TrapFormat.SNAP, TrapFormat.GUESS, TrapFormat.CHAIN),
            heroLabel = "Again",
            defaultPalette = "Spectrum",
            typicalSeconds = 22f,
            params = listOf(
                Param.int("digit", "Nudged at digit", 2, 15, 12, card = Card.DATA),
                Param.float("speed", "Speed", 0.3f, 4f, 1f, card = Card.MOTION, logScale = true)
            ),
            create = { TwinStarsSim() }
        )
    }
}
