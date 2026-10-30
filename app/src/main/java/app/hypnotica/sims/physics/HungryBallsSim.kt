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
 * Hungry balls: whenever two touch, the bigger swallows the smaller and
 * takes its size. Small balls are quick and dodge; big ones are slow and
 * fill the room, so they bump into everything. Who ends up the only one
 * left is often not who was biggest at the start.
 */
class HungryBallsSim : Sim {

    private var n = 30
    private val x = FloatArray(MAX)
    private val y = FloatArray(MAX)
    private val vx = FloatArray(MAX)
    private val vy = FloatArray(MAX)
    /** Mass is area; the radius drawn eases toward the true one. */
    private val r = FloatArray(MAX)
    private val shown = FloatArray(MAX)
    private val tint = FloatArray(MAX)
    private val eaten = IntArray(MAX)
    private val flash = FloatArray(MAX)
    private val gone = BooleanArray(MAX)
    // A swallowed ball shrinks into its eater.
    private val gx = FloatArray(MAX)
    private val gy = FloatArray(MAX)
    private val gr = FloatArray(MAX)
    private val gLife = FloatArray(MAX)
    private val gBy = IntArray(MAX)
    private var arena = 0.44f
    /** The wall as it stands now: it closes in, slowly, so the last few must meet. */
    private var wall = 0.44f
    private var alive = 0
    private var winner = -1
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false

    override fun setup(env: SimEnv) {
        n = env.params.i("count", 30).coerceIn(3, MAX)
        arena = min(env.halfWidth, 1f) * 0.92f
        wall = arena
        val rng = env.rng
        // Sizes so that all of them together make one ball a third of the arena across.
        val total = (arena * FINAL) * (arena * FINAL)
        val raw = FloatArray(n) { rng.range(0.4f, 1.6f) }
        val sum = raw.sum()
        for (i in 0 until n) {
            r[i] = sqrt(total * raw[i] / sum); shown[i] = r[i]
            tint[i] = rng.nextFloat()
            eaten[i] = 0; flash[i] = 0f; gone[i] = false; gLife[i] = 0f
            // Scattered without overlaps, a few tries each.
            var tries = 0
            do {
                val a = rng.range(0f, 2f * PI.toFloat()); val d = sqrt(rng.nextFloat()) * (arena - r[i])
                x[i] = cos(a) * d; y[i] = sin(a) * d
                tries++
            } while (tries < 40 && (0 until i).any { j -> sq(x[i] - x[j]) + sq(y[i] - y[j]) < sq(r[i] + r[j]) })
            val va = rng.range(0f, 2f * PI.toFloat())
            val s = speedFor(r[i])
            vx[i] = cos(va) * s; vy[i] = sin(va) * s
        }
        alive = n; winner = -1
        elapsed = 0f; doneAt = -1f; payoffFrame = false
    }

    private fun sq(v: Float) = v * v

    /** Small and quick, big and slow. */
    private fun speedFor(rad: Float): Float = arena * 0.32f * sqrt(arena * 0.05f / max(rad, 1e-4f)).coerceIn(0.4f, 2.4f)

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h0 = abs(dt) * env.params.f("pace", 1f).coerceIn(0.2f, 3f)
        elapsed += h0
        // After a few seconds of feeding frenzy, the wall starts to close in,
        // down to just room for the last ball and a bit: the stragglers
        // that dodge round the edge can't for ever.
        wall = (arena - max(0f, elapsed - CLOSE_AFTER) * arena * CLOSE_RATE).coerceAtLeast(arena * FINAL * 1.7f)
        val sub = 3
        val h = h0 / sub
        repeat(sub) { substep(env, h) }
        for (i in 0 until n) {
            shown[i] += (r[i] - shown[i]) * min(1f, h0 * 8f)
            flash[i] = max(0f, flash[i] - h0 * 3f)
            if (gLife[i] > 0f) gLife[i] -= h0 * 4f
        }
    }

    private fun substep(env: SimEnv, h: Float) {
        for (i in 0 until n) {
            if (gone[i]) continue
            x[i] += vx[i] * h; y[i] += vy[i] * h
            val d = sqrt(x[i] * x[i] + y[i] * y[i])
            if (d > wall - r[i] && d > 1e-5f) {
                val nx = x[i] / d; val ny = y[i] / d
                val vn = vx[i] * nx + vy[i] * ny
                if (vn > 0f) { vx[i] -= 2f * vn * nx; vy[i] -= 2f * vn * ny; env.emit(EventKind.STEP, 0.2f + 0.6f * (1f - r[i] / arena), nx, 0.06f) }
                val room = max(0f, wall - r[i])
                x[i] = nx * room; y[i] = ny * room
            }
            // Each ball keeps the speed its size gives it.
            val s = sqrt(vx[i] * vx[i] + vy[i] * vy[i])
            val want = speedFor(r[i])
            if (s > 1e-5f) { val k = 1f + (want / s - 1f) * min(1f, h * 3f); vx[i] *= k; vy[i] *= k }
        }
        for (i in 0 until n) {
            if (gone[i]) continue
            for (j in i + 1 until n) {
                if (gone[j] || gone[i]) continue
                val dx = x[j] - x[i]; val dy = y[j] - y[i]
                val rr = r[i] + r[j]
                if (dx * dx + dy * dy >= rr * rr) continue
                // The bigger eats; between equals, the quicker.
                val big = if (r[i] > r[j] || (r[i] == r[j] && (vx[i] * vx[i] + vy[i] * vy[i]) >= (vx[j] * vx[j] + vy[j] * vy[j]))) i else j
                val small = if (big == i) j else i
                swallow(env, big, small)
            }
        }
    }

    private fun swallow(env: SimEnv, big: Int, small: Int) {
        val a1 = r[big] * r[big]; val a2 = r[small] * r[small]
        val a = a1 + a2
        // Momentum kept; the centre moves to the joint centre of mass.
        vx[big] = (vx[big] * a1 + vx[small] * a2) / a
        vy[big] = (vy[big] * a1 + vy[small] * a2) / a
        x[big] = (x[big] * a1 + x[small] * a2) / a
        y[big] = (y[big] * a1 + y[small] * a2) / a
        r[big] = sqrt(a)
        eaten[big] += 1 + eaten[small]
        flash[big] = 1f
        gone[small] = true
        gx[small] = x[small]; gy[small] = y[small]; gr[small] = r[small]; gLife[small] = 1f; gBy[small] = big
        alive--
        // The bigger the eater, the lower the gulp.
        env.emit(EventKind.HIT, (1f - r[big] / (arena * FINAL)).coerceIn(0.05f, 1f), (x[big] / arena).coerceIn(-1f, 1f), (0.35f + 0.5f * sqrt(a2) / r[big]).coerceAtMost(0.9f))
        if (alive == 1 && doneAt < 0f) {
            winner = big
            doneAt = elapsed
            payoffFrame = true
            env.emit(EventKind.DONE, 0.6f, 0f, 1f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        g.ring(0f, 0f, arena, 0.004f, Palette.withAlpha(Palette.TEXT_SECONDARY, 0.25f))
        g.disc(0f, 0f, wall, Palette.lerp(Palette.BG, pal.at(0.5f), 0.05f))
        g.ring(0f, 0f, wall, 0.008f, Palette.withAlpha(Palette.TEXT_PRIMARY, 0.6f))
        // The swallowed, sliding into their eaters as they shrink.
        for (i in 0 until n) {
            if (!gone[i] || gLife[i] <= 0f) continue
            val e = gBy[i]
            val f = gLife[i]
            val px = x[e] + (gx[i] - x[e]) * f; val py = y[e] + (gy[i] - y[e]) * f
            g.disc(px, py, gr[i] * f, pal.at(tint[i]), f)
        }
        for (i in 0 until n) {
            if (gone[i]) continue
            val c = pal.at(tint[i])
            val rad = shown[i]
            g.glow(x[i], y[i], rad * 1.5f, c, 0.2f + 0.5f * flash[i])
            g.disc(x[i], y[i], rad, Palette.lerp(c, Palette.TEXT_PRIMARY, 0.5f * flash[i]))
            g.disc(x[i], y[i], rad * 0.72f, Palette.lerp(c, Palette.BG, 0.25f))
            g.ring(x[i], y[i], rad, max(0.003f, rad * 0.08f), Palette.brighten(c, 0.3f))
        }
        if (winner >= 0) g.glow(x[winner], y[winner], r[winner] * 2.2f, pal.at(tint[winner]), (1f - (elapsed - doneAt) / 2f).coerceIn(0.2f, 0.9f))
    }

    override fun frameHalfWidth(env: SimEnv): Float = arena + 0.01f

    override fun winner(): Int = if (n > MAX_PICKS) -1 else winner

    override fun contenders(env: SimEnv): IntArray =
        if (n > MAX_PICKS) app.hypnotica.core.sim.NO_CONTENDERS else IntArray(n) { env.palette.at(tint[it]) }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = (doneAt >= 0f && elapsed - doneAt > 4f) || elapsed > 90f

    override fun counters(): List<Pair<String, String>> {
        var big = -1
        for (i in 0 until n) if (!gone[i] && (big < 0 || r[i] > r[big])) big = i
        return listOf(
            "left" to "$alive",
            "biggest has eaten" to (if (big >= 0) "${eaten[big]}" else "0")
        )
    }

    companion object {
        const val MAX = 200
        /** The last ball's radius, as a share of the arena's. */
        const val FINAL = 0.3f
        const val CLOSE_AFTER = 5f
        /** Share of the arena's radius the wall closes by each second. */
        const val CLOSE_RATE = 0.045f
        const val MAX_PICKS = 8

        fun def(): SimDef = SimDef(
            id = "physics/hungry-balls",
            title = "Hungry balls",
            shelf = Shelf.PHYSICS,
            family = "Battles",
            blurb = "Two touch, the bigger swallows the smaller. Small ones are quick. Last ball left wins.",
            formats = listOf(TrapFormat.LAST_STANDING, TrapFormat.GROW, TrapFormat.CHAIN),
            heroLabel = "New feast",
            defaultPalette = "Candy",
            typicalSeconds = 18f,
            params = listOf(
                Param.int("count", "Balls", 3, MAX, 30, card = Card.DATA, logScale = true),
                Param.float("pace", "Pace", 0.3f, 3f, 1f, card = Card.MOTION, logScale = true)
            ),
            create = { HungryBallsSim() }
        )
    }
}
