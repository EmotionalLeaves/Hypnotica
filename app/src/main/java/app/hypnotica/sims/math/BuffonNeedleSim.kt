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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Buffon's needle (1777): drop needles on a floor of boards whose width is
 * the needle's length, and the share that land across a crack is 2/π. So
 * dropping needles measures π — badly at first, then better and better. Each
 * needle that crosses lights up; the estimate is drawn as it wanders and
 * settles onto the dashed line at 3.14159…
 */
class BuffonNeedleSim : Sim {

    private var target = 3000
    private var len = 0.08f
    private var gap = 0.08f
    private var x0 = 0f
    private var y0 = 0f
    private var halfW = 0.45f
    private var nx = FloatArray(0)
    private var ny = FloatArray(0)
    private var na = FloatArray(0)
    private var cross = BooleanArray(0)
    private var dropped = 0
    private var crossed = 0
    private var budget = 0f
    private var lastAt = -1
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false

    private val hx = FloatArray(HIST)
    private val hy = FloatArray(HIST)
    private val hv = FloatArray(HIST)
    private var hn = 0

    override fun setup(env: SimEnv) {
        target = env.params.i("needles", 3000).coerceIn(50, MAX_NEEDLES)
        halfW = env.halfWidth * 0.94f
        len = env.params.f("length", 0.09f).coerceIn(0.02f, 0.3f)
        gap = len
        x0 = -halfW; y0 = -0.15f
        nx = FloatArray(target); ny = FloatArray(target); na = FloatArray(target)
        cross = BooleanArray(target)
        dropped = 0; crossed = 0; budget = 0f; lastAt = -1; hn = 0
        elapsed = 0f; doneAt = -1f; payoffFrame = false
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val a = abs(dt)
        elapsed += a
        if (dropped >= target) return
        val speed = env.params.f("speed", 1f).coerceIn(0.1f, 6f)
        val rate = min(MAX_RATE, START_RATE * exp(elapsed * speed / ACCEL_S))
        budget += a * rate * speed
        var n = 0
        while (budget >= 1f && dropped < target && n < MAX_PER_STEP) {
            budget -= 1f
            drop(env, n == 0)
            n++
        }
    }

    private fun drop(env: SimEnv, audible: Boolean) {
        val i = dropped
        // The floor is the lower part of the screen; boards run across it.
        val x = env.rng.range(x0, -x0)
        val y = env.rng.range(-0.95f, y0)
        val ang = env.rng.range(0f, PI.toFloat())
        nx[i] = x; ny[i] = y; na[i] = ang
        // It crosses a crack if its two ends sit on different boards.
        val dy = sin(ang) * len * 0.5f
        val b0 = floor((y - dy) / gap); val b1 = floor((y + dy) / gap)
        cross[i] = b0 != b1
        dropped++
        if (cross[i]) crossed++
        lastAt = i
        if (audible) env.emit(if (cross[i]) EventKind.HIT else EventKind.STEP, if (cross[i]) 0.6f else 0.3f, (x / halfW).coerceIn(-1f, 1f), if (cross[i]) 0.4f else 0.15f)
        val every = max(1, target / HIST)
        if (dropped % every == 0 && hn < HIST && crossed > 0) {
            hv[hn] = (2.0 * dropped / crossed).toFloat()
            hn++
        }
        if (dropped >= target && doneAt < 0f) {
            doneAt = elapsed
            payoffFrame = true
            env.emit(EventKind.DONE, 0.9f, 0f, 1f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        // The cracks between boards.
        var y = floor(-0.95f / gap) * gap
        while (y <= y0 + 0.001f) {
            if (y >= -0.95f) g.line(-halfW, y, halfW, y, 0.003f, Palette.TEXT_SECONDARY, 0.35f)
            y += gap
        }
        val cHit = pal.at(0.08f)
        val cMiss = Palette.withAlpha(Palette.TEXT_SECONDARY, 0.5f)
        // Needles, the newest brightest.
        for (i in 0 until dropped) {
            val dx = cos(na[i]) * len * 0.5f; val dy = sin(na[i]) * len * 0.5f
            val c = if (cross[i]) cHit else cMiss
            g.line(nx[i] - dx, ny[i] - dy, nx[i] + dx, ny[i] + dy, if (i == lastAt) 0.006f else 0.0035f, c, if (cross[i]) 0.85f else 0.5f)
        }
        if (lastAt >= 0 && doneAt < 0f) g.glow(nx[lastAt], ny[lastAt], len * 1.2f, if (cross[lastAt]) cHit else Palette.TEXT_PRIMARY, 0.4f)

        // The estimate, against π.
        val gy0 = 0.15f; val gy1 = 0.85f
        fun gy(v: Float) = gy0 + (gy1 - gy0) * ((v - LOW) / (HIGH - LOW)).coerceIn(0f, 1f)
        var x = -halfW
        while (x < halfW) { g.line(x, gy(PI.toFloat()), min(halfW, x + 0.025f), gy(PI.toFloat()), 0.004f, Palette.SUCCESS, 0.55f); x += 0.045f }
        g.line(-halfW, gy0, halfW, gy0, 0.003f, Palette.TEXT_SECONDARY, 0.3f)
        if (hn >= 2) {
            for (i in 0 until hn) { hx[i] = -halfW + 2f * halfW * (i + 1f) / HIST; hy[i] = gy(hv[i]) }
            g.polyline(hx, hy, hn, 0.007f, pal.at(0.6f))
            g.disc(hx[hn - 1], hy[hn - 1], 0.012f, Palette.brighten(pal.at(0.6f), 0.4f))
        }
        if (doneAt >= 0f) {
            val p = ((elapsed - doneAt) / 1.5f).coerceIn(0f, 1f)
            g.glow(halfW, gy(PI.toFloat()), 0.3f, Palette.SUCCESS, 0.5f * (1f - p))
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && elapsed - doneAt > 3.5f

    override fun counters(): List<Pair<String, String>> = listOf(
        "needles" to "$dropped",
        "across a crack" to "$crossed",
        "π ≈" to if (crossed == 0) "—" else String.format(java.util.Locale.US, "%.4f", 2.0 * dropped / crossed)
    )

    companion object {
        const val MAX_NEEDLES = 20_000
        const val HIST = 200
        const val START_RATE = 1.5f
        const val MAX_RATE = 3000f
        const val ACCEL_S = 2.2f
        const val MAX_PER_STEP = 200
        const val LOW = 2.4f
        const val HIGH = 4.0f

        fun def(): SimDef = SimDef(
            id = "math/buffon-needle",
            title = "Buffon's needle",
            shelf = Shelf.MATH,
            family = "Probability & statistics",
            blurb = "Drop needles on floorboards. The share that cross a crack works out π.",
            formats = listOf(TrapFormat.NUMBER_UP, TrapFormat.FILL),
            heroLabel = "Sweep the floor",
            defaultPalette = "Ember",
            typicalSeconds = 18f,
            params = listOf(
                Param.int("needles", "Needles", 50, MAX_NEEDLES, 3000, card = Card.DATA, logScale = true),
                Param.float("length", "Needle length", 0.02f, 0.3f, 0.09f, card = Card.DATA),
                Param.float("speed", "Speed", 0.3f, 4f, 1f, card = Card.MOTION, logScale = true)
            ),
            create = { BuffonNeedleSim() }
        )
    }
}
