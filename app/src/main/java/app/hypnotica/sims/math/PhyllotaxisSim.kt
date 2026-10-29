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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A sunflower head growing seed by seed.
 *
 * Each new seed is born at the centre, a fixed turn round from the one before,
 * and every older seed is pushed outward — seed k sits at radius √(age), the
 * even packing of Vogel's model. At the golden angle, 137.508°, no seed ever
 * lines up with an earlier one, and the head fills with two families of
 * crossing spirals whose counts are neighbouring Fibonacci numbers. Nudge the
 * angle a fraction of a degree and the seeds start to queue up behind each
 * other in straight spokes.
 */
class PhyllotaxisSim : Sim {

    private var total = 500
    private var born = 0f
    private var grown = false
    private var payoffFrame = false
    private var sinceGrown = 0f
    private var noteCarry = 0f
    private var lastSounded = 0
    private var angleDeg = GOLDEN_DEG

    /**
     * The two spiral families a seed of a given age lines up along — the index
     * steps to its nearest neighbours, one turning each way — sampled in bands
     * of equal radius. Worked out from the angle, not assumed to be Fibonacci,
     * so a spoke angle gets its spokes counted too.
     */
    private val bandA = IntArray(BANDS)
    private val bandB = IntArray(BANDS)
    private var tableAngle = Float.NaN
    private var tableTotal = -1
    private var rimA = 1
    private var rimB = 2

    override fun setup(env: SimEnv) {
        val p = env.params
        total = p.i("seeds", 500).coerceIn(50, 5000)
        angleDeg = p.f("angle", GOLDEN_DEG)
        born = 0f
        grown = false
        payoffFrame = false
        sinceGrown = 0f
        noteCarry = 0f
        lastSounded = 0
        tableAngle = Float.NaN
        tableTotal = -1
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        angleDeg = env.params.f("angle", GOLDEN_DEG).coerceIn(90f, 180f)
        if (grown) {
            val before = sinceGrown
            sinceGrown += abs(dt)
            refreshFamilies()
            // The second family of spirals starts in from the rim (the first
            // arrives with the payoff's own sound).
            val bStart = FAMILY_SECONDS * 0.8f
            if (before < bStart && sinceGrown >= bStart) env.emit(EventKind.CROSS, 0.72f, 0f, 0.45f)
            return
        }
        val speed = env.params.f("speed", 45f).coerceIn(1f, 5000f)
        val before = born.toInt()
        born = min(total.toFloat(), born + abs(dt) * speed)
        val now = born.toInt()
        // A note for a newly born seed, paced: at ninety seeds a second one
        // each would be a buzz. Pitched by the direction it is born in, so the
        // golden angle's never-repeating turn is what you hear, and panned to
        // that side of the dial where the newest direction is drawn.
        noteCarry = min(1.5f, noteCarry + abs(dt) * NOTE_RATE)
        if (now > before && noteCarry >= 1f) {
            noteCarry -= 1f
            val k = now - 1
            val turn = frac(k * angleDeg / 360.0)
            env.emit(EventKind.BIRTH, turn, (cos(turn * 2.0 * PI).toFloat() * 0.6f), 0.35f)
            lastSounded = k
        }
        if (born >= total) {
            grown = true
            payoffFrame = true
        }
        refreshFamilies()
    }

    private fun smooth(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }

    private fun frac(x: Double): Float = (x - floor(x)).toFloat()

    /** Signed distance of x from the nearest whole number, in -0.5..0.5. */
    private fun wrap(x: Double): Double = x - floor(x + 0.5)

    /**
     * Index step m to the two nearest neighbours of a seed of age [a] that
     * turn opposite ways, measured exactly: seeds at √a and √(a+m), Δ turns
     * apart. The visible spirals are exactly those chains of nearest
     * neighbours.
     */
    private fun pair(a: Double, phi: Double, out: IntArray) {
        var best1 = Double.MAX_VALUE; var m1 = 1
        var best2 = Double.MAX_VALUE; var m2 = 2
        var sign1 = 0
        val ra = sqrt(a)
        val limit = min(MAX_STEP, max(8, (a * 0.9).toInt()))
        // The best of all first, then the best that turns the other way.
        for (m in 1..limit) {
            val w = wrap(m * phi)
            val rb = sqrt(a + m)
            val d = ra * ra + rb * rb - 2.0 * ra * rb * cos(2.0 * PI * w)
            if (d < best1) { best1 = d; m1 = m; sign1 = if (w >= 0.0) 1 else -1 }
        }
        for (m in 1..limit) {
            if (m == m1) continue
            val w = wrap(m * phi)
            val s = if (w >= 0.0) 1 else -1
            if (s == sign1 && abs(w) > 1e-9) continue
            val rb = sqrt(a + m)
            val d = ra * ra + rb * rb - 2.0 * ra * rb * cos(2.0 * PI * w)
            if (d < best2) { best2 = d; m2 = m }
        }
        out[0] = m1; out[1] = m2
    }

    private val tmp = IntArray(2)

    private fun refreshFamilies() {
        if (angleDeg == tableAngle && total == tableTotal) return
        tableAngle = angleDeg
        tableTotal = total
        val phi = angleDeg / 360.0
        for (b in 0 until BANDS) {
            val u = (b + 0.5) / BANDS
            pair(max(1.0, total * u * u), phi, tmp)
            bandA[b] = tmp[0]; bandB[b] = tmp[1]
        }
        pair(total * 0.97, phi, tmp)
        rimA = min(tmp[0], tmp[1]); rimB = max(tmp[0], tmp[1])
    }

    private class Layout(val hx: Float, val hy: Float, val r: Float, val dx: Float, val dy: Float, val dr: Float)

    /**
     * Upright, the head takes the width and the turn dial sits beneath it;
     * wide, the dial moves to the side. A head alone in a tall frame left a
     * dead band above and below it.
     */
    private fun layout(env: SimEnv): Layout {
        val hw = env.halfWidth
        return if (hw < 0.9f) {
            val r = min(hw * 0.93f, 0.62f)
            val hy = 0.93f - r
            val room = hy - r + 0.97f
            val dr = min(0.3f, room * 0.3f)
            Layout(0f, hy, r, 0f, hy - r - room * 0.46f, dr)
        } else {
            val r = 0.9f
            val dr = min(0.3f, (hw - r) * 0.42f).coerceAtLeast(0.14f)
            val hx = -min(hw - r - 0.05f, dr * 1.4f + 0.05f).coerceAtLeast(0f)
            Layout(hx, 0f, r, hx + r + (hw - (hx + r)) * 0.5f, 0f, dr)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        val lay = layout(env)
        val n = born
        val count = min(total, n.toInt() + 1)
        if (count <= 0 || n <= 0f) { drawDial(g, pal, lay, n); return }
        // While the head is small the view holds the seeds at a readable size
        // and pulls back as it grows; then the head fills its circle.
        val radius = min(lay.r, C_MAX * sqrt(max(1f, n)))
        val c = radius / sqrt(max(1f, n))
        val dot = max(0.0035f, c * 0.74f)
        val rad = (angleDeg * PI / 180.0)
        val reveal = if (grown) (sinceGrown / REVEAL_SECONDS).coerceIn(0f, 1f) else 0f
        val ease = smooth(reveal)
        g.reserveInstances(count * 4 + 64)

        // A dark disc under the head so the seeds sit on something.
        g.glow(lay.hx, lay.hy, radius * 1.25f, pal.at(0.1f), 0.18f)
        g.disc(lay.hx, lay.hy, radius + dot * 1.6f, Palette.lerp(Palette.BG, pal.at(0.55f), 0.10f))

        // Seeds first, links over them once the head is grown.
        for (k in 0 until count) {
            val age = n - k
            if (age <= 0f) continue
            val rr = radius * sqrt(age / n)
            val th = k * rad
            val x = lay.hx + rr * cos(th).toFloat()
            val y = lay.hy + rr * sin(th).toFloat()
            val grow = min(1f, age / 1.5f)
            val t = age / n
            val col = pal.at(0.04f + 0.6f * t)
            // The seeds step back a little when the spirals are drawn over them.
            val lit = 1f - 0.72f * ease
            g.disc(x, y, dot * grow * 1.18f, Palette.BG)
            g.disc(x, y, dot * grow, Palette.lerp(Palette.BG, Palette.brighten(col, 0.08f), 0.35f + 0.65f * lit))
        }

        if (reveal > 0f) {
            // The two families arrive one after the other — the spirals
            // turning one way, then the other — each in its own colour, so
            // each count can be read on its own before they cross.
            val colA = Palette.brighten(pal.at(0.04f), 0.2f)
            // Lifted towards white: the far end of a warm palette is the same
            // red as the outer seeds, and that family vanished into them.
            val colB = Palette.lerp(pal.at(0.72f), Palette.TEXT_PRIMARY, 0.55f)
            val phi = angleDeg / 360.0
            val width = max(0.0024f, c * 0.27f)
            val showA = smooth(sinceGrown / FAMILY_SECONDS)
            val showB = smooth((sinceGrown - FAMILY_SECONDS * 0.8f) / FAMILY_SECONDS)
            for (k in 0 until count) {
                val age = n - k
                if (age < 1f) continue
                val u = sqrt(age / n)
                val band = min(BANDS - 1, (u * BANDS).toInt())
                val rr = radius * u
                val th = k * rad
                val x = lay.hx + rr * cos(th).toFloat()
                val y = lay.hy + rr * sin(th).toFloat()
                for (which in 0..1) {
                    val m = if (which == 0) bandA[band] else bandB[band]
                    val j = k - m
                    if (j < 0) continue
                    val cw = wrap(m * phi) >= 0.0
                    val show = if (cw) showA else showB
                    // Each family sweeps in from the rim, where it is counted.
                    val local = ((show * 1.4f - (1f - u)) / 0.4f).coerceIn(0f, 1f)
                    if (local <= 0f) continue
                    val r2 = radius * sqrt((n - j) / n)
                    val th2 = j * rad
                    val x2 = lay.hx + r2 * cos(th2).toFloat()
                    val y2 = lay.hy + r2 * sin(th2).toFloat()
                    g.line(x, y, x2, y2, width, if (cw) colA else colB, local * 0.92f)
                }
            }
        }
        drawDial(g, pal, lay, n)
    }

    /**
     * The newest seeds' directions round a dial: at the golden angle each
     * lands in the widest gap left by the others, which is the whole reason
     * the head packs so evenly; at a spoke angle they stack up on a few.
     */
    private fun drawDial(g: G, pal: Palette, lay: Layout, n: Float) {
        val cx = lay.dx; val cy = lay.dy; val r = lay.dr
        g.ring(cx, cy, r, 0.003f, Palette.TEXT_SECONDARY, alpha = 0.35f)
        val newest = n.toInt() - 1
        if (newest < 0) return
        val rad = angleDeg * PI / 180.0
        val shown = min(DIAL_SEEDS, newest + 1)
        for (i in shown - 1 downTo 0) {
            val k = newest - i
            val th = k * rad
            val fade = 1f - i.toFloat() / DIAL_SEEDS
            val x = cx + r * cos(th).toFloat()
            val y = cy + r * sin(th).toFloat()
            g.disc(x, y, 0.010f + 0.006f * fade, Palette.BG)
            g.disc(x, y, 0.007f + 0.006f * fade, pal.at(0.04f + 0.55f * (1f - fade)), 0.35f + 0.65f * fade)
        }
        // The turn from the last seed to the newest, as an arc and a hand.
        val th1 = newest * rad
        val th0 = (newest - 1) * rad
        val hand = Palette.TEXT_PRIMARY
        g.line(cx, cy, cx + r * 0.86f * cos(th1).toFloat(), cy + r * 0.86f * sin(th1).toFloat(), 0.004f, hand, 0.85f)
        if (newest >= 1) {
            g.line(cx, cy, cx + r * 0.86f * cos(th0).toFloat(), cy + r * 0.86f * sin(th0).toFloat(), 0.003f, hand, 0.3f)
            val turn = (angleDeg * PI / 180.0).toFloat()
            val mid = (th0 + turn * 0.5f).toFloat()
            // ring() draws all but a gap; the gap is what is not the turn.
            g.ring(cx, cy, r * 0.38f, 0.004f, pal.at(0.08f), gap = (2f * PI.toFloat() - turn), gapAngle = mid + PI.toFloat(), alpha = 0.9f)
        }
        g.disc(cx, cy, 0.008f, hand, 0.8f)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = grown && sinceGrown > HOLD_SECONDS

    override fun counters(): List<Pair<String, String>> = listOf(
        "seeds" to String.format(java.util.Locale.US, "%,d", born.toInt()),
        "spirals" to "$rimA · $rimB",
        "turn" to String.format(java.util.Locale.US, "%.3f°", angleDeg)
    )

    companion object {
        /** 360° / φ², the golden angle. */
        const val GOLDEN_DEG = 137.50776f
        private const val BANDS = 48
        private const val MAX_STEP = 400
        /** The largest seed spacing, so the first few seeds are not giant blobs. */
        private const val C_MAX = 0.034f
        private const val NOTE_RATE = 7f
        private const val DIAL_SEEDS = 21
        private const val REVEAL_SECONDS = 0.8f
        private const val FAMILY_SECONDS = 1.1f
        private const val HOLD_SECONDS = 4f

        fun def(): SimDef = SimDef(
            id = "math/phyllotaxis",
            title = "Sunflower spirals",
            shelf = Shelf.MATH,
            family = "Number theory",
            blurb = "One seed every 137.5°. The spirals come out in Fibonacci numbers.",
            formats = listOf(TrapFormat.GROW, TrapFormat.REVEAL),
            heroLabel = "Grow again",
            defaultPalette = "Ember",
            typicalSeconds = 15f,
            params = listOf(
                Param.float("angle", "Turn per seed", 135f, 140f, GOLDEN_DEG, card = Card.DATA, unit = "°"),
                Param.int("seeds", "Seeds", 100, 5000, 500, card = Card.DATA, logScale = true),
                Param.float("speed", "Seeds a second", 5f, 1500f, 45f, card = Card.MOTION, logScale = true)
            ),
            create = { PhyllotaxisSim() }
        )
    }
}
