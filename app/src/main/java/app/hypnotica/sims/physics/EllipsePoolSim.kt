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
import app.hypnotica.core.sim.TouchEvent
import app.hypnotica.core.sim.TrapFormat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The elliptical pool. Two thousand balls leave one focus of an ellipse at
 * once, in every direction, at one speed. Every one of them bounces off the
 * wall straight into the other focus — the ellipse's defining property — and
 * because every such path is the same length they all arrive together: the
 * expanding ring folds over itself and collapses to a point. Then it opens
 * again and comes home. For as long as you watch.
 *
 * In a stadium (two half-circles joined by straight sides) the same start
 * never comes back together: one flat wall is enough to make the table
 * chaotic, and the ring smears into a uniform haze. In a circle the ring
 * folds into the bright cusped curves of a caustic. After Nils Berglund's
 * billiard videos.
 */
class EllipsePoolSim : Sim {

    private var n = 2000
    private val x = FloatArray(MAX_N)
    private val y = FloatArray(MAX_N)
    private val vx = FloatArray(MAX_N)
    private val vy = FloatArray(MAX_N)
    private var shape = 0
    /** Horizontal and vertical semi-axes of the table. */
    private var ax = 0.4f
    private var ay = 0.8f
    /** Stadium: half the length of the straight sides; circle: zero. */
    private var flat = 0f
    private var focusY = 0.5f
    private var speed = 0.3f
    private var meet = 1f
    private var elapsed = 0f
    private var bounces = 0L
    private var reunions = 0
    private var nextMeet = 1
    private var pulse = 0f
    private var hitCarry = 0f
    private var hitsSince = 0
    private var payoffFrame = false
    private var paid = false
    private val wx = FloatArray(WALL + 1)
    private val wy = FloatArray(WALL + 1)

    override fun setup(env: SimEnv) {
        val p = env.params
        n = p.i("count", 2000).coerceIn(50, MAX_N)
        shape = p.choiceIndex("table")
        val e = p.f("ecc", 0.8f).coerceIn(0.2f, 0.95f)
        val hw = env.halfWidth * 0.94f
        // The long axis upright; as tall as the screen allows with the short
        // one fitting across it.
        val q = sqrt(1f - e * e)
        ay = min(0.86f, hw / q)
        ax = ay * q
        val c = ay * e
        when (shape) {
            1 -> { flat = ay - ax; focusY = c }     // stadium, same bounding box
            2 -> { ay = ax; flat = 0f; focusY = ax * 0.55f }  // circle
            else -> { flat = 0f; focusY = c }
        }
        // Every path from one focus to the other off the wall is 2·ay long
        // in the ellipse; the other tables use the same clock.
        val pathLen = 2f * max(ay, ax + flat)
        speed = pathLen / MEET_SECONDS
        meet = MEET_SECONDS
        for (i in 0 until n) {
            // Evenly round the circle, with a hair of jitter so no two coincide.
            val a = (2.0 * PI * (i + 0.5) / n).toFloat()
            x[i] = 0f; y[i] = -focusY
            vx[i] = cos(a) * speed; vy[i] = sin(a) * speed
        }
        elapsed = 0f; bounces = 0; reunions = 0; nextMeet = 1
        pulse = 0f; hitCarry = 0f; hitsSince = 0
        payoffFrame = false; paid = false
        // The wall outline, once.
        for (k in 0..WALL) {
            val t = (2.0 * PI * k / WALL).toFloat()
            if (shape == 0) { wx[k] = ax * cos(t); wy[k] = ay * sin(t) }
            else {
                val cx = ax * cos(t); val cy = ax * sin(t)
                wx[k] = cx; wy[k] = cy + if (sin(t) >= 0f) flat else -flat
            }
        }
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h0 = abs(dt) * env.params.f("speed", 1f).coerceIn(0.1f, 4f)
        elapsed += h0
        pulse = max(0f, pulse - abs(dt) * 1.2f)
        // In small steps, so a fast setting still meets the wall cleanly.
        val sub = max(1, (h0 / 0.008f).toInt() + 1)
        val h = h0 / sub
        repeat(sub) { for (i in 0 until n) advance(i, h) }

        hitCarry += abs(dt) * 7f
        if (hitCarry >= 1f) {
            hitCarry = 0f
            if (hitsSince > 0) {
                val share = hitsSince.toFloat() / n
                env.emit(EventKind.STEP, (0.3f + share).coerceAtMost(1f), 0f, (0.05f + share * 1.5f).coerceAtMost(0.35f))
            }
            hitsSince = 0
        }
        // The ellipse brings them together every [meet] seconds, at
        // alternate foci. The other tables keep the same appointment and
        // miss it.
        if (elapsed >= nextMeet * meet) {
            nextMeet++
            if (shape == 0) {
                reunions++
                pulse = 1f
                env.emit(EventKind.HIT, if (reunions % 2 == 1) 0.85f else 0.6f, 0f, 0.9f)
            }
            if (!paid) { paid = true; payoffFrame = true; if (shape != 0) env.emit(EventKind.CROSS, 0.5f, 0f, 0.6f) }
        }
    }

    override fun onInput(env: SimEnv, e: TouchEvent) {
        if (e.kind != TouchEvent.Kind.TAP) return
        // A tap breaks again from wherever it lands: off a focus the ring
        // no longer comes back to a point, and the difference is the lesson.
        if (exitAt(0f, 0f, e.x, e.y) < 1f) return
        for (i in 0 until n) {
            val a = (2.0 * PI * (i + 0.5) / n).toFloat()
            x[i] = e.x; y[i] = e.y
            vx[i] = cos(a) * speed; vy[i] = sin(a) * speed
        }
        env.emit(EventKind.HIT, 0.5f, 0f, 0.7f)
    }

    /** Move ball [i] for [h] seconds, reflecting off the wall exactly. */
    private fun advance(i: Int, h: Float) {
        var left = h
        var guard = 0
        while (left > 0f && guard < 4) {
            guard++
            val px = x[i]; val py = y[i]
            val dx = vx[i] * left; val dy = vy[i] * left
            val s = exitAt(px, py, dx, dy)
            if (s >= 1f) { x[i] = px + dx; y[i] = py + dy; return }
            val hx = px + dx * s; val hy = py + dy * s
            var nx: Float; var ny: Float
            if (shape == 0) { nx = hx / (ax * ax); ny = hy / (ay * ay) }
            else if (abs(hy) <= flat) { nx = if (hx > 0f) 1f else -1f; ny = 0f }
            else { nx = hx; ny = hy - (if (hy > 0f) flat else -flat) }
            val inv = 1f / sqrt(nx * nx + ny * ny)
            nx *= inv; ny *= inv
            val d = vx[i] * nx + vy[i] * ny
            vx[i] -= 2f * d * nx; vy[i] -= 2f * d * ny
            // A hair inside, so the next segment starts in the table.
            x[i] = hx - nx * 1e-5f; y[i] = hy - ny * 1e-5f
            left *= (1f - s)
            bounces++
            hitsSince++
        }
    }

    /** The share of the segment (dx, dy) from (px, py) at which it leaves the table; ≥ 1 if it stays in. */
    private fun exitAt(px: Float, py: Float, dx: Float, dy: Float): Float {
        if (shape == 0) {
            val ia = 1f / (ax * ax); val ib = 1f / (ay * ay)
            val qa = dx * dx * ia + dy * dy * ib
            val qb = 2f * (px * dx * ia + py * dy * ib)
            val qc = px * px * ia + py * py * ib - 1f
            if (qa <= 0f) return 2f
            val disc = qb * qb - 4f * qa * qc
            if (disc < 0f) return 2f
            val s = (-qb + sqrt(disc)) / (2f * qa)
            return if (s >= 0f) s else 0f
        }
        var best = 2f
        // The straight sides.
        if (flat > 0f && dx != 0f) {
            val wallX = if (dx > 0f) ax else -ax
            val s = (wallX - px) / dx
            if (s in 0f..best) { val yy = py + dy * s; if (abs(yy) <= flat) best = s }
        }
        // The two round ends (one full circle when flat is zero).
        for (k in 0..1) {
            if (flat == 0f && k == 1) break
            val cy = if (k == 0) flat else -flat
            val ox = px; val oy = py - cy
            val qa = dx * dx + dy * dy
            val qb = 2f * (ox * dx + oy * dy)
            val qc = ox * ox + oy * oy - ax * ax
            val disc = qb * qb - 4f * qa * qc
            if (qa <= 0f || disc < 0f) continue
            val s = (-qb + sqrt(disc)) / (2f * qa)
            if (s < 0f || s > best) continue
            val yy = py + dy * s
            val onCap = flat == 0f || (if (k == 0) yy >= flat else yy <= -flat)
            if (onCap) best = s
        }
        return best
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        // The table.
        g.polyline(wx, wy, WALL + 1, 0.006f, Palette.withAlpha(pal.at(0.6f), 0.5f))
        // The two foci (the ellipse's; the start and its mirror on the others).
        val fc = Palette.TEXT_SECONDARY
        g.disc(0f, -focusY, 0.008f, fc, 0.6f)
        g.disc(0f, focusY, 0.008f, fc, 0.6f)
        if (pulse > 0f) {
            val at = if (reunions % 2 == 1) focusY else -focusY
            g.glow(0f, at, 0.08f + 0.18f * (1f - pulse), pal.at(0.5f), pulse * 0.7f)
        }
        // Each ball a short streak along its heading, coloured by the angle
        // it set off at: the ring is a rainbow, and after the fold its order
        // shows which side each piece came from.
        g.reserveInstances(n)
        val tail = STREAK / speed
        for (i in 0 until n) {
            val c = pal.at(i.toFloat() / n)
            g.line(x[i] - vx[i] * tail, y[i] - vy[i] * tail, x[i], y[i], 0.006f, c, 0.85f)
        }
    }

    override fun payoff(): Boolean = payoffFrame

    /** Endless: the ellipse brings them home forever; the others never do. */
    override fun finished(): Boolean = false

    override fun frameHalfWidth(env: SimEnv): Float = ax + 0.01f

    override fun counters(): List<Pair<String, String>> = listOf(
        "balls" to "$n",
        "bounces" to String.format(java.util.Locale.US, "%,d", bounces),
        "reunions" to "$reunions"
    )

    companion object {
        const val MAX_N = 6000
        const val WALL = 160
        /** Seconds from leaving one focus to meeting at the other. */
        const val MEET_SECONDS = 5f
        /** Length of each ball's streak, in screen units. */
        const val STREAK = 0.02f

        fun def(): SimDef = SimDef(
            id = "physics/ellipse-pool",
            title = "Elliptical pool",
            shelf = Shelf.PHYSICS,
            family = "Bouncing & collisions",
            blurb = "Two thousand balls leave one focus in every direction — and every one of them meets at the other.",
            formats = listOf(TrapFormat.SNAP, TrapFormat.SONG),
            heroLabel = "Break again",
            defaultPalette = "Spectrum",
            typicalSeconds = 9f,
            params = listOf(
                Param.choice("table", "Table", listOf("Ellipse", "Stadium", "Circle"), 0, card = Card.DATA),
                Param.int("count", "Balls", 50, MAX_N, 2000, card = Card.DATA, logScale = true),
                Param.float("ecc", "Stretch", 0.2f, 0.95f, 0.8f, card = Card.DATA),
                Param.float("speed", "Speed", 0.2f, 3f, 1f, card = Card.MOTION, logScale = true)
            ),
            create = { EllipsePoolSim() }
        )
    }
}
