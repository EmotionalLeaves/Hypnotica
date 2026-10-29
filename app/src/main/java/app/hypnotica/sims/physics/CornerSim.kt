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
import kotlin.math.sqrt

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

    // One entry per badge.
    private var count = 1
    private var x0 = FloatArray(1)
    private var y0 = FloatArray(1)
    private var vx = FloatArray(1)
    private var vy = FloatArray(1)
    private var x = FloatArray(1)
    private var y = FloatArray(1)
    private var tint = FloatArray(1)
    private var lastCellX = IntArray(1)
    private var lastCellY = IntArray(1)
    private var cornerAt = FloatArray(1)
    private var wx = 1f
    private var wy = 1f
    private var bw = 0.1f
    private var bh = 0.06f
    /** Its half-height at the most the wobble tips it. */
    private var bhEff = 0.06f
    private var halfW = 0.46f
    private var bounces = 0
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var flash = 0f
    private var lineT = 0f
    private var first = -1
    private val sparkX = FloatArray(SPARKS)
    private val sparkY = FloatArray(SPARKS)
    private val sparkVx = FloatArray(SPARKS)
    private val sparkVy = FloatArray(SPARKS)
    private val sparkT = FloatArray(SPARKS)
    private var sparkLife = 0f
    /** The corner it hit, as signs: kept, since the badge moves on. */
    private var hitX = 0
    private var hitY = 0
    private var hitTint = 0f

    override fun setup(env: SimEnv) {
        halfW = env.halfWidth
        count = env.params.i("badges", 1).coerceIn(1, MAX_BADGES)
        // Smaller when there are several, so they have room.
        bw = min(halfW, 1f) * 0.22f * env.params.f("size", 1f).coerceIn(0.4f, 2f) * (if (count > 1) 0.75f else 1f)
        bh = bw * 0.6f
        wx = max(0.05f, 2f * (halfW - bw))
        // Tipped by the wobble, the badge stands taller: the walls are met
        // by its tipped corners, not its flat top, or it poked through.
        val tip = env.params.f("wobble", 0f).coerceIn(0f, 1f) * 0.35f
        bhEff = (bw - bh) * sin(tip) + bh
        wy = max(0.05f, 2f * (1f - bhEff))
        val speed = BASE_SPEED * min(halfW, 1f) / 0.46f
        x0 = FloatArray(count); y0 = FloatArray(count); vx = FloatArray(count); vy = FloatArray(count)
        x = FloatArray(count); y = FloatArray(count); tint = FloatArray(count)
        lastCellX = IntArray(count); lastCellY = IntArray(count); cornerAt = FloatArray(count)
        for (k in 0 until count) {
            // Each its own moment; several, and the race is who's first.
            cornerAt[k] = env.rng.range(9f, 15f)
            aim(env, k, speed)
            // A lone badge changes colour at every wall; several keep their
            // own, so they can be told apart and picked.
            tint[k] = if (count == 1) env.rng.nextFloat() else (k + 0.5f) / count
        }
        first = (0 until count).minByOrNull { cornerAt[it] } ?: 0
        // A badge that only grazes a corner before the winner's moment looks,
        // to anyone watching, like it got there first: throw it another way.
        for (k in 0 until count) {
            if (k == first) continue
            var tries = 0
            // Its own corner too close behind the winner's reads as a tie:
            // move its moment later as well.
            if (cornerAt[k] - cornerAt[first] < TIE_GAP) cornerAt[k] = cornerAt[first] + TIE_GAP + env.rng.range(0f, 2f)
            aim(env, k, speed)
            while (tries++ < 40 && grazesBefore(k, cornerAt[first])) aim(env, k, speed)
        }
        for (k in 0 until count) { lastCellX[k] = cellOf(x0[k], wx, vx[k]); lastCellY[k] = cellOf(y0[k], wy, vy[k]) }
        bounces = 0
        elapsed = 0f
        doneAt = -1f
        payoffFrame = false
        flash = 0f
        lineT = 0f
        sparkLife = 0f
        hitX = 0; hitY = 0
        for (k in 0 until count) place(k, 0f)
    }

    private fun aim(env: SimEnv, k: Int, speed: Float) {
        val a = env.rng.range(0.55f, 1.0f) * (if (env.rng.bool()) 1f else -1f) + (if (env.rng.bool()) 0f else PI.toFloat())
        vx[k] = cos(a) * speed * env.rng.range(0.85f, 1.15f)
        vy[k] = sin(a) * speed * 1.4f * env.rng.range(0.85f, 1.15f)
        // Unfolded, the walls are every wx along a straight line: land the
        // badge on a whole number of widths, both ways at once, at its moment.
        x0[k] = startFor(vx[k], wx, cornerAt[k])
        y0[k] = startFor(vy[k], wy, cornerAt[k])
    }

    /** Whether badge [k] comes within a near-touch of any corner before [until]. */
    private fun grazesBefore(k: Int, until: Float): Boolean {
        var t = 0f
        val dt = 1f / 240f
        while (t < until + 0.05f) {
            val fx = fold(x0[k] + vx[k] * t, wx); val fy = fold(y0[k] + vy[k] * t, wy)
            val ex = min(fx, wx - fx); val ey = min(fy, wy - fy)
            if (ex < GRAZE && ey < GRAZE) return true
            t += dt
        }
        return false
    }

    /** Where to start, in unfolded units, to be on a wall exactly at [at]. */
    private fun startFor(v: Float, w: Float, at: Float): Float {
        val travel = v * at
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

    private fun place(k: Int, t: Float) {
        x[k] = -halfW + bw + fold(x0[k] + vx[k] * t, wx)
        y[k] = -1f + bhEff + fold(y0[k] + vy[k] * t, wy)
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        flash = max(0f, flash - h * 2f)
        // Time along the line, run at the pace as it is set each frame: a
        // change of pace mid-run speeds it up rather than making it jump.
        lineT += h * (if (doneAt >= 0f) 1f else env.params.f("pace", 1f))
        // Never past the first corner before it has been reached.
        if (doneAt < 0f && lineT > cornerAt[first]) lineT = cornerAt[first]
        val u = lineT
        for (k in 0 until count) {
            place(k, u)
            val cx = cellOf(x0[k] + vx[k] * u, wx, vx[k])
            val cy = cellOf(y0[k] + vy[k] * u, wy, vy[k])
            val hit = cx != lastCellX[k] || cy != lastCellY[k]
            lastCellX[k] = cx; lastCellY[k] = cy
            if (hit) {
                bounces++
                if (count == 1) tint[k] = (tint[k] + 0.31f) % 1f
                // Only the planned moment is the corner: both walls crossed in
                // one step counted, and a near miss a few thousandths off
                // ended the run seconds early, before the exact hit.
                if (doneAt < 0f && k == first && u >= cornerAt[k] - 1e-3f) corner(env)
                else env.emit(EventKind.HIT, tint[k], (x[k] / max(0.1f, halfW)).coerceIn(-1f, 1f), 0.45f)
            }
        }
        if (doneAt < 0f && u >= cornerAt[first]) corner(env)
        if (sparkLife > 0f) {
            sparkLife -= h
            for (i in 0 until SPARKS) {
                sparkVy[i] -= 0.8f * h
                sparkX[i] += sparkVx[i] * h; sparkY[i] += sparkVy[i] * h
            }
        }
    }

    private fun corner(env: SimEnv) {
        val k = first
        doneAt = elapsed
        // Fired a hair before the walls register, the next step counted them
        // as one more bounce: count them now, as part of the corner.
        lastCellX[k] = cellOf(x0[k] + vx[k] * (cornerAt[k] + 1e-3f), wx, vx[k])
        lastCellY[k] = cellOf(y0[k] + vy[k] * (cornerAt[k] + 1e-3f), wy, vy[k])
        payoffFrame = true
        flash = 1f
        env.emit(EventKind.DONE, 1f, 0f, 1f)
        // Sparks from the exact corner it touched.
        val sx = if (x[k] > 0f) halfW else -halfW
        val sy = if (y[k] > 0f) 1f else -1f
        hitX = if (sx > 0f) 1 else -1; hitY = if (sy > 0f) 1 else -1
        hitTint = tint[k]
        sparkLife = SPARK_LIFE
        for (i in 0 until SPARKS) {
            val a = env.rng.range(0f, 2f * PI.toFloat())
            val s = env.rng.range(0.3f, 1.4f)
            sparkX[i] = sx; sparkY[i] = sy
            sparkVx[i] = -abs(cos(a)) * s * (if (sx > 0f) 1f else -1f)
            sparkVy[i] = -abs(sin(a)) * s * (if (sy > 0f) 1f else -1f)
            sparkT[i] = env.rng.nextFloat()
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        if (flash > 0f) g.flash(0.25f * flash)
        // The four corners, marked, so the target is on screen all along —
        // and the one it hit lit in its colour. A bare black screen gave the
        // eye nothing to judge "so close" by.
        for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
            val lit = sx == hitX && sy == hitY
            val c = if (lit) palette.at(hitTint) else Palette.TEXT_SECONDARY
            val a = if (lit) 1f else 0.35f
            val w = if (lit) 0.008f else 0.005f
            val cx = sx * (halfW - w * 0.5f); val cy = sy * (1f - w * 0.5f)
            g.line(cx, cy, cx - sx * CORNER_MARK, cy, w, c, a)
            g.line(cx, cy, cx, cy - sy * CORNER_MARK, w, c, a)
            if (lit) g.glow(sx * halfW, sy * 1f, 0.12f, c, 0.6f)
        }
        // A wobble, if asked for: a little tilt back and forth as it goes.
        val rot = env.params.f("wobble", 0f) * 0.35f * sin(elapsed * 3f)
        // The round ends reach exactly to the box's edge, so a wall touch is a touch.
        val ex = cos(rot) * (bw - bh); val ey = sin(rot) * (bw - bh)
        for (k in 0 until count) {
            val c = palette.at(tint[k])
            val won = doneAt >= 0f && k == first
            // The badge: a rounded slab with a ring set in it.
            g.glow(x[k], y[k], bw * 1.6f, c, 0.25f + (if (won || count == 1) 0.5f * flash else 0f))
            // Sparks before the badge that won, so they fly out from behind it.
            if (won && sparkLife > 0f) drawSparks(g, palette)
            g.rect(x[k], y[k], bw - bh, bh, c, rot)
            g.disc(x[k] - ex, y[k] - ey, bh, c)
            g.disc(x[k] + ex, y[k] + ey, bh, c)
            g.ring(x[k], y[k], bh * 0.75f, bh * 0.22f, 0xFF07080C.toInt())
            g.rect(x[k], y[k], bw * 0.55f, bh * 0.12f, 0xFF07080C.toInt(), rot)
        }
    }

    /**
     * Streaks along their flight, bright and quick to go: dots that dimmed
     * for two and a half seconds spread a grey speckle over the whole screen.
     */
    private fun drawSparks(g: G, palette: app.hypnotica.core.render.Palette) {
        val a = (sparkLife / SPARK_LIFE).coerceIn(0f, 1f)
        for (i in 0 until SPARKS) {
            val vx = sparkVx[i]; val vy = sparkVy[i]
            g.line(sparkX[i], sparkY[i], sparkX[i] - vx * 0.035f, sparkY[i] - vy * 0.035f, 0.006f,
                Palette.brighten(palette.at(sparkT[i]), 0.3f), sqrt(a))
        }
    }

    override fun contenders(env: SimEnv): IntArray =
        if (count < 2) app.hypnotica.core.sim.NO_CONTENDERS else IntArray(count) { env.palette.at(tint[it]) }

    override fun winner(): Int = if (count < 2 || doneAt < 0f) -1 else first

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && elapsed - doneAt > 3f

    override fun counters(): List<Pair<String, String>> = listOf(
        "bounces" to bounces.toString(),
        if (doneAt >= 0f) "corner" to (if (count > 1) "#${first + 1}!" else "yes!") else "corner" to "not yet"
    )

    companion object {
        const val BASE_SPEED = 0.42f
        const val SPARKS = 220
        const val SPARK_LIFE = 1.8f
        const val MAX_BADGES = 4
        /** Closer than this to a corner counts as touching it, to the eye. */
        const val GRAZE = 0.02f
        const val TIE_GAP = 0.6f
        /** How far each corner's marks run along its two walls. */
        const val CORNER_MARK = 0.07f

        fun def(): SimDef = SimDef(
            id = "physics/corner",
            title = "Corner hit",
            shelf = Shelf.PHYSICS,
            family = "Bouncing",
            blurb = "Bounce, bounce, so close… will it ever hit the corner? With several, which hits first?",
            formats = listOf(TrapFormat.GUESS, TrapFormat.SNAP),
            heroLabel = "Throw again",
            defaultPalette = "Spectrum",
            typicalSeconds = 16f,
            params = listOf(
                Param.int("badges", "Badges", 1, MAX_BADGES, 1, card = Card.DATA),
                Param.float("size", "Size", 0.4f, 2f, 1f, card = Card.LOOK),
                Param.float("pace", "Pace", 0.5f, 3f, 1f, card = Card.MOTION),
                Param.float("wobble", "Wobble", 0f, 1f, 0f, card = Card.MOTION)
            ),
            create = { CornerSim() }
        )
    }
}
