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
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The old screensaver question: will it hit the corner?
 *
 * The badge bounces round the screen changing colour at every wall, and
 * every so often it comes so close. This one is built backwards from the
 * answer: the corner and the moment are picked first, and the start is
 * worked out by unfolding the bounces into a straight line, so the corner
 * hit is exact — and everything before it is a near miss.
 */
class CornerSim : Sim {

    private var x0 = 0f
    private var y0 = 0f
    private var vx = 0.3f
    private var vy = 0.2f
    private var wx = 1f
    private var wy = 1f
    private var bw = 0.1f
    private var bh = 0.06f
    private var halfW = 0.46f
    private var x = 0f
    private var y = 0f
    private var tint = 0f
    private var bounces = 0
    private var lastCellX = 0
    private var lastCellY = 0
    private var elapsed = 0f
    private var cornerAt = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var flash = 0f
    private var lineT = 0f
    private val sparkX = FloatArray(SPARKS)
    private val sparkY = FloatArray(SPARKS)
    private val sparkVx = FloatArray(SPARKS)
    private val sparkVy = FloatArray(SPARKS)
    private val sparkT = FloatArray(SPARKS)
    private var sparkLife = 0f

    override fun setup(env: SimEnv) {
        halfW = env.halfWidth
        bw = min(halfW, 1f) * 0.22f * env.params.f("size", 1f).coerceIn(0.4f, 2f)
        bh = bw * 0.6f
        wx = max(0.05f, 2f * (halfW - bw))
        wy = max(0.05f, 2f * (1f - bh))
        val speed = BASE_SPEED * min(halfW, 1f) / 0.46f
        cornerAt = env.rng.range(9f, 15f)
        val a = env.rng.range(0.55f, 1.0f) * (if (env.rng.bool()) 1f else -1f) + (if (env.rng.bool()) 0f else PI.toFloat())
        vx = cos(a) * speed
        vy = sin(a) * speed * 1.4f
        // Unfolded, the walls are every wx along a straight line: land the
        // badge on a whole number of widths, both ways at once, at cornerAt.
        x0 = startFor(vx, wx)
        y0 = startFor(vy, wy)
        tint = env.rng.nextFloat()
        bounces = 0
        lastCellX = cellOf(x0, wx, vx); lastCellY = cellOf(y0, wy, vy)
        elapsed = 0f
        doneAt = -1f
        payoffFrame = false
        flash = 0f
        lineT = 0f
        sparkLife = 0f
        place(0f)
    }

    /** Where to start, in unfolded units, to be on a wall exactly at cornerAt. */
    private fun startFor(v: Float, w: Float): Float {
        val travel = v * cornerAt
        val k = if (v >= 0f) ceil(travel / w) else floor(travel / w)
        return k * w - travel
    }

    /**
     * Which stretch of the unfolded line [u] is on. Moving backwards, a wall
     * is passed on landing exactly on it, so the cell counts from the other
     * side: else the corner counted a second bounce a step after it.
     */
    private fun cellOf(u: Float, w: Float, v: Float): Int =
        if (v >= 0f) floor(u / w + 1e-4f).toInt() else (ceil(u / w - 1e-4f) - 1f).toInt()

    /** Fold the straight line back into the box. */
    private fun fold(u: Float, w: Float): Float {
        val m = ((u % (2f * w)) + 2f * w) % (2f * w)
        return if (m <= w) m else 2f * w - m
    }

    private fun place(t: Float) {
        x = -halfW + bw + fold(x0 + vx * t, wx)
        y = -1f + bh + fold(y0 + vy * t, wy)
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        flash = max(0f, flash - h * 2f)
        // Time along the line, run at the pace as it is set each frame: a
        // change of pace mid-run speeds it up rather than making it jump.
        lineT += h * (if (doneAt >= 0f) 1f else env.params.f("pace", 1f))
        // Never past the corner before it has been reached.
        if (doneAt < 0f && lineT > cornerAt) lineT = cornerAt
        val u = lineT
        place(u)
        val cx = cellOf(x0 + vx * u, wx, vx)
        val cy = cellOf(y0 + vy * u, wy, vy)
        val hitX = cx != lastCellX
        val hitY = cy != lastCellY
        lastCellX = cx; lastCellY = cy
        if (hitX || hitY) {
            bounces++
            tint = (tint + 0.31f) % 1f
            // Only the planned moment is the corner: both walls crossed in
            // one step counted, and a near miss a few thousandths off ended
            // the run seconds early, before the exact hit it was built for.
            if (doneAt < 0f && u >= cornerAt - 1e-3f) {
                corner(env)
            } else {
                env.emit(EventKind.HIT, tint, (x / max(0.1f, halfW)).coerceIn(-1f, 1f), 0.45f)
            }
        }
        if (doneAt < 0f && u >= cornerAt) corner(env)
        if (sparkLife > 0f) {
            sparkLife -= h
            for (i in 0 until SPARKS) {
                sparkVy[i] -= 0.8f * h
                sparkX[i] += sparkVx[i] * h; sparkY[i] += sparkVy[i] * h
            }
        }
    }

    private fun corner(env: SimEnv) {
        doneAt = elapsed
        // Fired a hair before the walls register, the next step counted them
        // as one more bounce: count them now, as part of the corner.
        lastCellX = cellOf(x0 + vx * (cornerAt + 1e-3f), wx, vx)
        lastCellY = cellOf(y0 + vy * (cornerAt + 1e-3f), wy, vy)
        payoffFrame = true
        flash = 1f
        env.emit(EventKind.DONE, 1f, 0f, 1f)
        // Sparks from the exact corner it touched.
        val sx = if (x > 0f) halfW else -halfW
        val sy = if (y > 0f) 1f else -1f
        sparkLife = 2.5f
        for (i in 0 until SPARKS) {
            val a = env.rng.range(0f, 2f * PI.toFloat())
            val s = env.rng.range(0.2f, 1.2f)
            sparkX[i] = sx; sparkY[i] = sy
            sparkVx[i] = -abs(cos(a)) * s * (if (sx > 0f) 1f else -1f)
            sparkVy[i] = -abs(sin(a)) * s * (if (sy > 0f) 1f else -1f)
            sparkT[i] = env.rng.nextFloat()
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val c = palette.at(tint)
        if (flash > 0f) g.flash(0.25f * flash)
        // The badge: a rounded slab with a ring set in it.
        // A wobble, if asked for: a little tilt back and forth as it goes.
        val rot = env.params.f("wobble", 0f) * 0.35f * sin(elapsed * 3f)
        // The round ends reach exactly to the box's edge, so a wall touch is a touch.
        val ex = cos(rot) * (bw - bh); val ey = sin(rot) * (bw - bh)
        g.glow(x, y, bw * 1.6f, c, 0.25f + 0.5f * flash)
        g.rect(x, y, bw - bh, bh, c, rot)
        g.disc(x - ex, y - ey, bh, c)
        g.disc(x + ex, y + ey, bh, c)
        g.ring(x, y, bh * 0.75f, bh * 0.22f, 0xFF07080C.toInt())
        g.rect(x, y, bw * 0.55f, bh * 0.12f, 0xFF07080C.toInt(), rot)
        if (sparkLife > 0f) {
            g.reservePoints(SPARKS)
            val a = (sparkLife / 2.5f).coerceIn(0f, 1f)
            for (i in 0 until SPARKS) g.point(sparkX[i], sparkY[i], 0.014f, Palette.withAlpha(palette.at(sparkT[i]), a))
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && elapsed - doneAt > 3f

    override fun counters(): List<Pair<String, String>> = listOf(
        "bounces" to bounces.toString(),
        if (doneAt >= 0f) "corner" to "yes!" else "corner" to "not yet"
    )

    companion object {
        const val BASE_SPEED = 0.42f
        const val SPARKS = 400

        fun def(): SimDef = SimDef(
            id = "physics/corner",
            title = "Corner hit",
            shelf = Shelf.PHYSICS,
            family = "Bouncing",
            blurb = "Bounce, bounce, so close… will it ever hit the corner?",
            formats = listOf(TrapFormat.GUESS, TrapFormat.SNAP),
            heroLabel = "Throw again",
            defaultPalette = "Spectrum",
            typicalSeconds = 16f,
            params = listOf(
                Param.float("size", "Size", 0.4f, 2f, 1f, card = Card.LOOK),
                Param.float("pace", "Pace", 0.5f, 3f, 1f, card = Card.MOTION),
                Param.float("wobble", "Wobble", 0f, 1f, 0f, card = Card.MOTION)
            ),
            create = { CornerSim() }
        )
    }
}
