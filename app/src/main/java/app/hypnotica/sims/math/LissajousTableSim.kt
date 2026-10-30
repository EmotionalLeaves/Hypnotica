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
import kotlin.math.min
import kotlin.math.sin

/**
 * The Lissajous table: a row of wheels across the top turning once, twice,
 * three times… for every turn of the slowest, and the same down the side.
 * Every square of the table takes its x from the wheel above it and its y from
 * the wheel beside it, and draws what those two make together.
 *
 * Nothing is fitted to anything: the curves close, all of them on the same
 * instant, because every wheel comes back to the top together when the
 * slowest finishes its one turn. Each wheel sounds as it passes the top, so
 * the whole table is a polyrhythm — one against two against three — that
 * resolves on that instant.
 */
class LissajousTableSim : Sim {

    private var n = 6
    private var size = 0.12f
    private var left = 0f
    private var top = 0f
    private var theta = 0f
    private var lastTheta = 0f
    private var count = 0
    private var xs: Array<FloatArray> = emptyArray()
    private var ys: Array<FloatArray> = emptyArray()
    private var closed = false
    private var closeGlow = 0f
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false

    override fun setup(env: SimEnv) {
        n = env.params.i("wheels", 6).coerceIn(2, MAX_N)
        // n wheels each way plus the header row and column, inside the screen.
        size = min(2f * env.halfWidth * 0.96f / (n + 1), 1.9f / (n + 1))
        left = -(n + 1) * size * 0.5f
        top = (n + 1) * size * 0.5f
        xs = Array(n * n) { FloatArray(POINTS + 1) }
        ys = Array(n * n) { FloatArray(POINTS + 1) }
        theta = 0f; lastTheta = 0f
        count = 0
        closed = false
        closeGlow = 0f
        elapsed = 0f; doneAt = -1f; payoffFrame = false
        record()
    }

    /** Centre of the wheel or square in column [c], row [r]; 0 is the header. */
    private fun cx(c: Int) = left + (c + 0.5f) * size
    private fun cy(r: Int) = top - (r + 0.5f) * size

    private fun record() {
        if (count > POINTS) return
        val rad = size * RADIUS
        for (r in 0 until n) for (c in 0 until n) {
            val k = r * n + c
            xs[k][count] = cx(c + 1) + rad * cos((c + 1) * theta - HALF_PI)
            ys[k][count] = cy(r + 1) - rad * sin((r + 1) * theta - HALF_PI)
        }
        count++
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val a = abs(dt)
        elapsed += a
        closeGlow = (closeGlow - a * 0.7f).coerceAtLeast(0f)
        val speed = env.params.f("speed", 1f).coerceIn(0.1f, 6f)
        lastTheta = theta
        theta += a * speed * TWO_PI / CYCLE_S
        // Each wheel sounds as it passes the top: wheel k does so k times a cycle.
        for (k in 1..n) {
            val before = floor(lastTheta * k / TWO_PI)
            val after = floor(theta * k / TWO_PI)
            if (after > before) {
                val note = (k - 1f) / (n - 1f).coerceAtLeast(1f)
                env.emit(EventKind.HIT, 0.1f + 0.8f * note, (k / (n + 1f)) * 2f - 1f, 0.25f)
            }
        }
        // The curves are sampled evenly over the one cycle that draws them whole.
        while (!closed && count <= POINTS && theta >= count * TWO_PI / POINTS) {
            val t = theta
            theta = count * TWO_PI / POINTS
            record()
            theta = t
        }
        if (!closed && theta >= TWO_PI) {
            closed = true
            closeGlow = 1f
            doneAt = elapsed
            payoffFrame = true
            env.emit(EventKind.DONE, 0.9f, 0f, 1f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        val rad = size * RADIUS
        // The wheels: across the top and down the side, each with its dot.
        for (k in 1..n) {
            val col = pal.at((k - 1f) / n)
            val ang = k * theta - HALF_PI
            val tx = cx(k); val ty = cy(0)
            g.ring(tx, ty, rad, size * 0.02f, col, 0f, 0f, 0.6f)
            val dx = tx + rad * cos(ang); val dy = ty - rad * sin(ang)
            g.disc(dx, dy, size * 0.06f, Palette.brighten(col, 0.4f))
            // Its guide line, down through its column.
            g.line(dx, dy, dx, cy(n) - size * 0.5f, 0.0025f, col, 0.18f)
            val sx = cx(0); val sy = cy(k)
            val ang2 = k * theta - HALF_PI
            g.ring(sx, sy, rad, size * 0.02f, col, 0f, 0f, 0.6f)
            val ex = sx + rad * cos(ang2); val ey = sy - rad * sin(ang2)
            g.disc(ex, ey, size * 0.06f, Palette.brighten(col, 0.4f))
            g.line(ex, ey, cx(n) + size * 0.5f, ey, 0.0025f, col, 0.18f)
        }
        // The table: each curve in the blend of its two wheels' colours.
        for (r in 0 until n) for (c in 0 until n) {
            val k = r * n + c
            val col = Palette.lerp(pal.at(c.toFloat() / n), pal.at(r.toFloat() / n), 0.5f)
            g.polyline(xs[k], ys[k], min(count, POINTS + 1), size * 0.022f, col, 0.9f)
            run {
                val px = cx(c + 1) + rad * cos((c + 1) * theta - HALF_PI)
                val py = cy(r + 1) - rad * sin((r + 1) * theta - HALF_PI)
                g.disc(px, py, size * 0.045f, Palette.TEXT_PRIMARY)
            }
        }
        if (closeGlow > 0f) g.glow(cx(n / 2 + 1), cy(n / 2 + 1), size * n * 0.8f, Palette.TEXT_PRIMARY, closeGlow * 0.3f)
    }

    override fun payoff(): Boolean = payoffFrame

    /** Endless: closed, the curves go on being traced round and round. */
    override fun finished(): Boolean = false

    override fun counters(): List<Pair<String, String>> = listOf(
        "turns of the slowest" to String.format(java.util.Locale.US, "%.2f", (theta / TWO_PI).coerceAtMost(1f)),
        "curves" to "${n * n}"
    )

    companion object {
        const val MAX_N = 9
        const val POINTS = 360
        const val RADIUS = 0.4f
        const val CYCLE_S = 14f
        const val TWO_PI = (2.0 * PI).toFloat()
        const val HALF_PI = (PI / 2.0).toFloat()

        fun def(): SimDef = SimDef(
            id = "math/lissajous-table",
            title = "Lissajous table",
            shelf = Shelf.MATH,
            family = "Curves",
            blurb = "Every square takes x from the wheel above and y from the wheel beside. They all close at once.",
            formats = listOf(TrapFormat.GROW, TrapFormat.SNAP, TrapFormat.SONG),
            heroLabel = "Draw again",
            defaultPalette = "Spectrum",
            typicalSeconds = 18f,
            params = listOf(
                Param.int("wheels", "Wheels each way", 2, MAX_N, 6, card = Card.DATA),
                Param.float("speed", "Speed", 0.3f, 4f, 1f, card = Card.MOTION, logScale = true)
            ),
            create = { LissajousTableSim() }
        )
    }
}
