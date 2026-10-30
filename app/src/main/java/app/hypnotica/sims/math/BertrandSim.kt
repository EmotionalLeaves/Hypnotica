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
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Bertrand's paradox (1889): draw a chord of a circle "at random". How often
 * is it longer than a side of the triangle inside it? Three ways to choose,
 * each as random as the others — two random points on the rim; a random
 * point along a random radius; a random point inside, as the chord's middle
 * — and three different answers: a third, a half, a quarter. The question
 * never said which "random" it meant. All three are drawn at once, the long
 * chords lit, until each count has settled on its own answer.
 */
class BertrandSim : Sim {

    private var target = 600
    private val count = IntArray(3)
    private val long = IntArray(3)
    private var r = 0.25f
    private val cy = FloatArray(3)
    private var cap = 600
    private var x1 = Array(3) { FloatArray(0) }
    private var y1 = Array(3) { FloatArray(0) }
    private var x2 = Array(3) { FloatArray(0) }
    private var y2 = Array(3) { FloatArray(0) }
    private var isLong = Array(3) { BooleanArray(0) }
    private var budget = 0f
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false

    override fun setup(env: SimEnv) {
        target = env.params.i("chords", 600).coerceIn(20, MAX_CHORDS)
        cap = DRAWN
        r = min(env.halfWidth * 0.62f, 0.27f)
        for (k in 0..2) cy[k] = 0.62f - k * 0.62f
        x1 = Array(3) { FloatArray(cap) }; y1 = Array(3) { FloatArray(cap) }
        x2 = Array(3) { FloatArray(cap) }; y2 = Array(3) { FloatArray(cap) }
        isLong = Array(3) { BooleanArray(cap) }
        count.fill(0); long.fill(0)
        budget = 0f
        elapsed = 0f; doneAt = -1f; payoffFrame = false
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val a = abs(dt)
        elapsed += a
        val speed = env.params.f("speed", 1f).coerceIn(0.1f, 6f)
        val rate = min(MAX_RATE, START_RATE * exp(elapsed * speed / ACCEL_S))
        budget += a * rate * speed
        var k = 0
        while (budget >= 1f && k < MAX_PER_STEP) {
            budget -= 1f
            for (m in 0..2) chord(env, m, k == 0)
            k++
        }
        if (count[0] >= target && doneAt < 0f) {
            doneAt = elapsed
            payoffFrame = true
            env.emit(EventKind.DONE, 0.9f, 0f, 1f)
        }
    }

    /** One chord of the unit circle by method [m]. */
    private fun chord(env: SimEnv, m: Int, audible: Boolean) {
        val rng = env.rng
        val ax: Float; val ay: Float; val bx: Float; val by: Float
        when (m) {
            0 -> {
                val t1 = rng.range(0f, TWO_PI); val t2 = rng.range(0f, TWO_PI)
                ax = cos(t1); ay = sin(t1); bx = cos(t2); by = sin(t2)
            }
            1 -> {
                val t = rng.range(0f, TWO_PI); val d = rng.nextFloat()
                val h = sqrt(1f - d * d)
                val mx = d * cos(t); val my = d * sin(t)
                ax = mx - h * sin(t); ay = my + h * cos(t); bx = mx + h * sin(t); by = my - h * cos(t)
            }
            else -> {
                var mx: Float; var my: Float
                do { mx = rng.range(-1f, 1f); my = rng.range(-1f, 1f) } while (mx * mx + my * my > 1f)
                val d = sqrt(mx * mx + my * my).coerceAtLeast(1e-6f)
                val h = sqrt(1f - d * d)
                val ux = -my / d; val uy = mx / d
                ax = mx - h * ux; ay = my - h * uy; bx = mx + h * ux; by = my + h * uy
            }
        }
        val len2 = (ax - bx) * (ax - bx) + (ay - by) * (ay - by)
        // The triangle's side is √3 times the radius.
        val isL = len2 > 3f
        val i = count[m] % cap
        x1[m][i] = ax; y1[m][i] = ay; x2[m][i] = bx; y2[m][i] = by; isLong[m][i] = isL
        count[m]++
        if (isL) long[m]++
        if (audible && isL) env.emit(EventKind.HIT, 0.3f + 0.25f * m, m - 1f, 0.3f)
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        for (m in 0..2) {
            val c = pal.at(HUE[m])
            val oy = cy[m]
            val ox = -env.halfWidth * 0.25f
            // The chords, the long ones lit.
            val n = min(count[m], cap)
            for (i in 0 until n) {
                val col = if (isLong[m][i]) c else Palette.TEXT_SECONDARY
                g.line(ox + x1[m][i] * r, oy + y1[m][i] * r, ox + x2[m][i] * r, oy + y2[m][i] * r, 0.0018f, col, if (isLong[m][i]) 0.35f else 0.12f)
            }
            g.ring(ox, oy, r, 0.004f, Palette.TEXT_PRIMARY, 0f, 0f, 0.6f)
            // The triangle.
            for (k in 0..2) {
                val a0 = PI.toFloat() / 2f + k * TWO_PI / 3f; val a1 = a0 + TWO_PI / 3f
                g.line(ox + cos(a0) * r, oy + sin(a0) * r, ox + cos(a1) * r, oy + sin(a1) * r, 0.003f, Palette.TEXT_PRIMARY, 0.5f)
            }
            // The share so far, as a bar to the right with its answer ruled in.
            val bx = env.halfWidth * 0.55f
            val bh = r * 0.9f
            val share = if (count[m] == 0) 0f else long[m].toFloat() / count[m]
            g.rect(bx, oy - bh + bh * share, 0.035f, bh * share, c, 0f, 0.85f)
            g.rect(bx, oy, 0.036f, bh, Palette.TEXT_SECONDARY, 0f, 0.08f)
            val ans = ANSWER[m]
            g.line(bx - 0.06f, oy - bh + 2f * bh * ans, bx + 0.06f, oy - bh + 2f * bh * ans, 0.004f, Palette.SUCCESS, 0.8f)
        }
    }

    override fun payoff(): Boolean = payoffFrame

    /** Endless: past the target it plays on, the count settling ever closer. */
    override fun finished(): Boolean = false

    override fun counters(): List<Pair<String, String>> {
        fun pc(m: Int) = if (count[m] == 0) "—" else String.format(java.util.Locale.US, "%.1f%%", long[m] * 100f / count[m])
        return listOf(
            "chords" to "${count[0]} each",
            "two points on the rim" to pc(0) + " (1/3)",
            "a point on a radius" to pc(1) + " (1/2)",
            "a midpoint anywhere" to pc(2) + " (1/4)"
        )
    }

    companion object {
        const val MAX_CHORDS = 50_000
        const val DRAWN = 1500
        const val START_RATE = 1.2f
        const val MAX_RATE = 1500f
        const val ACCEL_S = 2.4f
        const val MAX_PER_STEP = 100
        const val TWO_PI = (2.0 * PI).toFloat()
        private val HUE = floatArrayOf(0.08f, 0.45f, 0.8f)
        private val ANSWER = floatArrayOf(1f / 3f, 0.5f, 0.25f)

        fun def(): SimDef = SimDef(
            id = "math/bertrand",
            title = "Bertrand's paradox",
            shelf = Shelf.MATH,
            family = "Probability & statistics",
            blurb = "Three equally random ways to draw a chord, three different answers: a third, a half, a quarter.",
            formats = listOf(TrapFormat.NUMBER_UP, TrapFormat.FILL, TrapFormat.SNAP),
            heroLabel = "Draw again",
            defaultPalette = "Spectrum",
            typicalSeconds = 18f,
            params = listOf(
                Param.int("chords", "Chords each", 20, MAX_CHORDS, 600, card = Card.DATA, logScale = true),
                Param.float("speed", "Speed", 0.3f, 4f, 1f, card = Card.MOTION, logScale = true)
            ),
            create = { BertrandSim() }
        )
    }
}
