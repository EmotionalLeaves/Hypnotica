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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Planets in a double-star system.
 *
 * A pair of stars on an eccentric orbit, and a cloud of planets dropped in
 * at every distance: some round the bigger star, some round the whole pair.
 * The stars pull, and planets are flung out or fall in — but not anywhere.
 * The survivors end up hugging the bigger star, or circling the pair from far
 * out where it pulls like one star. The dashed rings are where Holman and
 * Wiegert (1999) put the edges of those safe zones for this pair; the edge
 * is sharp, and the survivors stop right at it.
 *
 * Shown twice: the whole system above, and the bigger star close up below.
 */
class SafeZonesSim : Sim {

    private var n = 1200
    private var px = FloatArray(0)
    private var py = FloatArray(0)
    private var pvx = FloatArray(0)
    private var pvy = FloatArray(0)
    private var fate = ByteArray(0)
    private var lostAt = FloatArray(0)
    private var inner = BooleanArray(0)

    private var t = 0.0
    private var s1x = 0f; private var s1y = 0f
    private var s2x = 0f; private var s2y = 0f
    private var lastPeri = 0
    private var orbits = 0
    private var lastLossOrbit = 0f
    private val lostByOrbit = IntArray(64)
    private var lost = 0
    private var fell = 0
    private var flung = 0
    private var lossTick = 0
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false

    private var topScale = 0.09f
    private var botScale = 0.7f
    private var halfW = 0.46f

    override fun setup(env: SimEnv) {
        n = env.params.i("planets", 1200).coerceIn(50, MAX_N)
        halfW = env.halfWidth
        topScale = min(halfW * 0.95f, 0.46f) / OUTER_MAX
        botScale = min(halfW * 0.95f, 0.46f) / ZOOM_R
        px = FloatArray(n); py = FloatArray(n); pvx = FloatArray(n); pvy = FloatArray(n)
        fate = ByteArray(n); lostAt = FloatArray(n); inner = BooleanArray(n)
        t = 0.0
        stars()
        val rng = env.rng
        for (i in 0 until n) {
            // Half round the bigger star, half round the pair, each at every distance.
            inner[i] = i % 2 == 0
            val r: Float; val cx: Float; val cy: Float; val host: Float
            if (inner[i]) {
                r = rng.range(INNER_MIN, INNER_MAX); cx = s1x; cy = s1y; host = M1
            } else {
                r = rng.range(OUTER_MIN, OUTER_MAX); cx = 0f; cy = 0f; host = 1f
            }
            val ang = rng.range(0f, 2f * PI.toFloat())
            val v = sqrt(host / r)
            px[i] = cx + r * cos(ang); py[i] = cy + r * sin(ang)
            pvx[i] = -v * sin(ang); pvy[i] = v * cos(ang)
            if (inner[i]) { pvx[i] += S1VX; pvy[i] += S1VY }
        }
        lastPeri = 0; orbits = 0; lastLossOrbit = 0f
        lost = 0; fell = 0; flung = 0; lossTick = 0
        lostByOrbit.fill(0)
        elapsed = 0f; doneAt = -1f; payoffFrame = false
    }

    /** The stars where Kepler puts them at time t: periapsis at t = 0. */
    private fun stars() {
        val mean = (t % (2.0 * PI))
        var e = mean
        repeat(6) { e -= (e - ECC * sin(e) - mean) / (1.0 - ECC * cos(e)) }
        val rx = (cos(e) - ECC).toFloat()
        val ry = (sqrt(1.0 - ECC * ECC) * sin(e)).toFloat()
        s1x = -MU * rx; s1y = -MU * ry
        s2x = (1f - MU) * rx; s2y = (1f - MU) * ry
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val a = abs(dt)
        elapsed += a
        val speed = env.params.f("speed", 1f).coerceIn(0.1f, 4f)
        val span = a * speed * RATE
        val subs = kotlin.math.ceil(span / MAX_DT).toInt().coerceIn(1, 400)
        val h = span / subs
        repeat(subs) {
            // Kick-drift-kick, the stars moved on the drift.
            kick(h * 0.5f, env)
            for (i in 0 until n) if (fate[i].toInt() == 0) { px[i] += pvx[i] * h; py[i] += pvy[i] * h }
            t += h
            stars()
            kick(h * 0.5f, env)
        }
        val o = (t / (2.0 * PI)).toInt()
        if (o > lastPeri) {
            lastPeri = o
            orbits = o
            // The pair at its closest: the moment that does the damage.
            env.emit(EventKind.CROSS, 0.25f, 0f, 0.35f)
        }
        if (lossTick > 0) {
            env.emit(EventKind.DEATH, 0.15f + min(0.6f, lossTick * 0.05f), 0f, min(0.5f, 0.15f + lossTick * 0.03f))
            lossTick = 0
        }
        // Settled: past the first few orbits, and the losses have dwindled to
        // a trickle — under one planet in sixty over the last two orbits.
        if (o < lostByOrbit.size) for (k in o until lostByOrbit.size) lostByOrbit[k] = lost
        val nowOrbits = (t / (2.0 * PI)).toFloat()
        // Only needed until the payoff; an endless run goes far past the table.
        val recent = if (o >= 2 && o - 2 < lostByOrbit.size) lost - lostByOrbit[o - 2] else n
        if (doneAt < 0f && nowOrbits > MIN_ORBITS && (recent * TRICKLE < n || nowOrbits > MAX_ORBITS)) {
            doneAt = elapsed
            payoffFrame = true
            env.emit(EventKind.DONE, 0.9f, 0f, 1f)
        }
    }

    private fun kick(h: Float, env: SimEnv) {
        for (i in 0 until n) {
            if (fate[i].toInt() != 0) continue
            val d1x = s1x - px[i]; val d1y = s1y - py[i]
            val d2x = s2x - px[i]; val d2y = s2y - py[i]
            val r1 = d1x * d1x + d1y * d1y
            val r2 = d2x * d2x + d2y * d2y
            if (r1 < FALL2 || r2 < FALL2) { lose(i, FELL); continue }
            if (px[i] * px[i] + py[i] * py[i] > GONE2) { lose(i, FLUNG); continue }
            val i1 = M1 / (r1 * sqrt(r1)); val i2 = (1f - M1) / (r2 * sqrt(r2))
            pvx[i] += (d1x * i1 + d2x * i2) * h
            pvy[i] += (d1y * i1 + d2y * i2) * h
        }
    }

    private fun lose(i: Int, how: Byte) {
        fate[i] = how
        lostAt[i] = elapsed
        lost++
        if (how == FELL) fell++ else flung++
        lossTick++
        lastLossOrbit = (t / (2.0 * PI)).toFloat()
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        val cIn = pal.at(0.12f); val cOut = pal.at(0.6f)
        // A faint rule between the two views.
        g.rect(0f, 0f, halfW * 0.9f, 0.002f, Palette.TEXT_SECONDARY, 0f, 0.2f)

        // Above: the whole system.
        val ty = TOP_Y
        dashed(g, 0f, ty, P_LIMIT * topScale, cOut)
        drawStar(g, s1x * topScale, ty + s1y * topScale, 0.018f, pal.at(0.05f))
        drawStar(g, s2x * topScale, ty + s2y * topScale, 0.012f, pal.at(0.85f))
        // Below: the bigger star, close.
        val by = BOTTOM_Y
        dashed(g, 0f, by, S_LIMIT * botScale, cIn)
        drawStar(g, 0f, by, 0.022f, pal.at(0.05f))
        val sx2 = (s2x - s1x) * botScale; val sy2 = (s2y - s1y) * botScale
        if (abs(sx2) < halfW && abs(sy2) < 0.46f) drawStar(g, sx2, by + sy2, 0.016f, pal.at(0.85f))

        for (i in 0 until n) {
            val f = fate[i].toInt()
            val c = if (inner[i]) cIn else cOut
            var a = 0.9f
            if (f != 0) {
                val since = elapsed - lostAt[i]
                if (since > 0.6f) continue
                a = (0.6f - since) / 0.6f
            }
            val tx = px[i] * topScale; val tyy = ty + py[i] * topScale
            if (abs(tx) < halfW && abs(tyy - ty) < 0.47f) g.point(tx, tyy, 0.006f, Palette.withAlpha(c, a))
            val bx = (px[i] - s1x) * botScale; val byy = by + (py[i] - s1y) * botScale
            if (abs(bx) < halfW && abs(byy - by) < 0.47f) g.point(bx, byy, 0.007f, Palette.withAlpha(c, a))
        }
        if (doneAt >= 0f) {
            val p = ((elapsed - doneAt) / 1.5f).coerceIn(0f, 1f)
            g.glow(0f, by, S_LIMIT * botScale * 1.3f, cIn, 0.3f * (1f - p))
            g.glow(0f, ty, P_LIMIT * topScale * 1.1f, cOut, 0.2f * (1f - p))
        }
    }

    private fun drawStar(g: G, x: Float, y: Float, r: Float, c: Int) {
        g.glow(x, y, r * 4f, c, 0.45f)
        g.disc(x, y, r, Palette.brighten(c, 0.5f))
    }

    private fun dashed(g: G, x: Float, y: Float, r: Float, c: Int) {
        val k = 48
        for (j in 0 until k step 2) {
            val a0 = j * 2f * PI.toFloat() / k; val a1 = (j + 1) * 2f * PI.toFloat() / k
            g.line(x + r * cos(a0), y + r * sin(a0), x + r * cos(a1), y + r * sin(a1), 0.0045f, Palette.brighten(c, 0.3f), 0.85f)
        }
    }

    override fun payoff(): Boolean = payoffFrame

    /** Endless: the survivors go on orbiting in their safe zones. */
    override fun finished(): Boolean = false

    override fun counters(): List<Pair<String, String>> {
        fun pc(v: Int) = "${v * 100 / max(1, n)}%"
        return listOf(
            "still orbiting" to pc(n - lost),
            "flung out" to pc(flung),
            "fell into a star" to pc(fell),
            "orbits of the pair" to "$orbits"
        )
    }

    companion object {
        const val MAX_N = 4000
        const val MU = 0.3f
        const val M1 = 1f - MU
        const val ECC = 0.3
        /** Holman and Wiegert's limits for μ = 0.3, e = 0.3, in pair separations. */
        const val S_LIMIT = 0.222f
        const val P_LIMIT = 3.45f
        const val INNER_MIN = 0.08f
        const val INNER_MAX = 0.55f
        const val OUTER_MIN = 1.2f
        const val OUTER_MAX = 5f
        const val ZOOM_R = 0.62f
        const val TOP_Y = 0.48f
        const val BOTTOM_Y = -0.5f
        const val RATE = 5f
        const val MAX_DT = 0.004f
        const val FALL2 = 0.02f * 0.02f
        const val GONE2 = 9f * 9f
        const val MIN_ORBITS = 5f
        const val TRICKLE = 60
        const val MAX_ORBITS = 14f
        const val FELL: Byte = 1
        const val FLUNG: Byte = 2
        /** The bigger star's velocity at periapsis (a = 1, G·M = 1). */
        val S1VX = 0f
        val S1VY = (-MU * sqrt((1.0 + ECC) / (1.0 - ECC)).toFloat())

        fun def(): SimDef = SimDef(
            id = "physics/safe-zones",
            title = "Planets round two suns",
            shelf = Shelf.PHYSICS,
            family = "Orbits, gravity & space",
            blurb = "Planets dropped at every distance round a pair of stars. The survivors stop at the safe zones' edge.",
            formats = listOf(TrapFormat.LAST_STANDING, TrapFormat.REVEAL, TrapFormat.GUESS),
            heroLabel = "Drop them again",
            defaultPalette = "Spectrum",
            typicalSeconds = 16f,
            params = listOf(
                Param.int("planets", "Planets", 50, MAX_N, 1200, card = Card.DATA, logScale = true),
                Param.float("speed", "Speed", 0.3f, 3f, 1f, card = Card.MOTION, logScale = true)
            ),
            create = { SafeZonesSim() }
        )
    }
}
