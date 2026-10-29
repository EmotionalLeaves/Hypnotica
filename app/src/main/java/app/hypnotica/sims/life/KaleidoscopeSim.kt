package app.hypnotica.sims.life

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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A kaleidoscope. Two mirrors at an angle of π/n make one wedge-shaped
 * chamber; whatever lies in it is reflected, and reflected again, into 2n
 * copies round the centre — the dihedral symmetry of order 2n. The beads
 * tumble in the chamber as the tube slowly turns (gravity swinging round
 * them), so the picture never repeats. Then the beads slide into places on a
 * few rings — on the mirrors, or halfway between — and the tumbling pattern
 * locks into a perfect mandala.
 *
 * Only one wedge is ever simulated; every other piece of the picture is an
 * exact reflection of it, which is the whole trick of the instrument.
 */
class KaleidoscopeSim : Sim {

    private var folds = 6
    private var wedge = (PI / 6).toFloat()
    private var radius = 0.44f

    private var n = 0
    private var bx = FloatArray(0)   // in the chamber's own frame, the first mirror along +x
    private var by = FloatArray(0)
    private var vx = FloatArray(0)
    private var vy = FloatArray(0)
    private var size = FloatArray(0)
    private var spin = FloatArray(0)
    private var turn = FloatArray(0)
    private var kind = IntArray(0)
    private var hue = IntArray(0)
    // Where each bead goes when the pattern locks.
    private var tx = FloatArray(0)
    private var ty = FloatArray(0)
    private var tSize = FloatArray(0)
    private var tTurn = FloatArray(0)
    private var ring = IntArray(0)
    // Where it was when the lock began, so the slide is a clean ease.
    private var fx = FloatArray(0)
    private var fy = FloatArray(0)
    private var fSize = FloatArray(0)
    private var fTurn = FloatArray(0)

    private var rings = 4
    private var elapsed = 0f
    private var lockAt = 8f
    private var locking = false
    private var doneAt = -1f
    private var payoffFrame = false
    private var spinView = 0f
    private var gravityAngle = 0f
    private var hitCarry = 0f
    private var heldHit = -1
    private var ringsSounded = 0

    override fun setup(env: SimEnv) {
        folds = env.params.i("mirrors", 6).coerceIn(3, 12)
        wedge = PI.toFloat() / folds
        radius = min(env.halfWidth - 0.03f, 0.9f)
        n = env.params.i("pieces", 12).coerceIn(4, 40)
        bx = FloatArray(n); by = FloatArray(n); vx = FloatArray(n); vy = FloatArray(n)
        size = FloatArray(n); spin = FloatArray(n); turn = FloatArray(n)
        kind = IntArray(n); hue = IntArray(n)
        tx = FloatArray(n); ty = FloatArray(n); tSize = FloatArray(n); tTurn = FloatArray(n); ring = IntArray(n)
        fx = FloatArray(n); fy = FloatArray(n); fSize = FloatArray(n); fTurn = FloatArray(n)
        for (i in 0 until n) {
            // Scattered through the chamber, sized to it.
            val r = radius * (0.15f + 0.8f * sqrt(env.rng.nextFloat()))
            val a = wedge * env.rng.range(0.1f, 0.9f)
            bx[i] = cos(a) * r; by[i] = sin(a) * r
            vx[i] = env.rng.range(-0.25f, 0.25f); vy[i] = env.rng.range(-0.25f, 0.25f)
            size[i] = radius * env.rng.range(0.045f, 0.09f)
            spin[i] = env.rng.range(-1.2f, 1.2f)
            turn[i] = env.rng.range(0f, 2f * PI.toFloat())
            kind[i] = i % KINDS
            hue[i] = env.rng.int(0, 3)
        }
        planLock()
        elapsed = 0f
        lockAt = LOCK_AT / env.params.f("pace", 1f).coerceIn(0.3f, 3f)
        locking = false
        doneAt = -1f
        payoffFrame = false
        spinView = 0f
        gravityAngle = env.rng.range(0f, 2f * PI.toFloat())
        hitCarry = 0f
        heldHit = -1
        ringsSounded = 0
    }

    /**
     * The mandala: the beads dealt round a few rings, inside out, two to a
     * ring — one lying along the first mirror and one along the second, so
     * each ring becomes a crown of 2n matching pieces at equal spacing (a
     * third, if a ring gets one, sits halfway between). A ring's pieces take
     * its colour and a size that leaves a gap between neighbours.
     */
    private fun planLock() {
        rings = min(MAX_RINGS, (n + 1) / 2)
        val perRing = IntArray(rings)
        for (i in 0 until n) perRing[i % rings]++
        val seen = IntArray(rings)
        for (i in 0 until n) {
            val k = i % rings
            ring[i] = k
            val slot = seen[k]++
            val a = when (slot % 3) { 0 -> 0f; 1 -> wedge; else -> wedge * 0.5f }
            val rr = radius * (0.22f + 0.68f * k / max(1, rings - 1))
            tx[i] = cos(a) * rr; ty[i] = sin(a) * rr
            // Neighbours on a ring are rr × wedge apart (half that once a
            // third piece sits between).
            val gap = rr * wedge * (if (perRing[k] > 2) 0.5f else 1f)
            tSize[i] = min(radius * 0.085f, gap * 0.36f)
            // Pointing out from the centre, so each crown is radial.
            tTurn[i] = a
        }
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        spinView += h * env.params.f("turn", 0.15f)
        if (!locking && elapsed >= lockAt) {
            locking = true
            for (i in 0 until n) { fx[i] = bx[i]; fy[i] = by[i]; fSize[i] = size[i]; fTurn[i] = turn[i] }
        }
        if (!locking) tumble(env, h) else lock(env)
    }

    /**
     * The tube turning: gravity swings slowly round the chamber and the beads
     * slide and tumble under it, bouncing off the mirrors and the rim.
     */
    private fun tumble(env: SimEnv, h: Float) {
        gravityAngle += h * TUBE_TURN
        val gx = cos(gravityAngle) * GRAVITY; val gy = sin(gravityAngle) * GRAVITY
        var hitBead = -1
        for (i in 0 until n) {
            vx[i] = (vx[i] + gx * h) * (1f - DRAG * h)
            vy[i] = (vy[i] + gy * h) * (1f - DRAG * h)
            bx[i] += vx[i] * h; by[i] += vy[i] * h
            turn[i] += spin[i] * h
            val m = size[i] * 0.6f
            // The first mirror, along the x axis.
            if (by[i] < m) {
                by[i] = m
                if (vy[i] < -MIN_BOUNCE) hitBead = i
                if (vy[i] < 0f) vy[i] = -vy[i] * BOUNCE
            }
            // The second mirror, at the wedge angle: reflect the velocity's
            // component along its normal.
            val nx = -sin(wedge); val ny = cos(wedge)
            val d = bx[i] * nx + by[i] * ny
            if (d > -m) {
                bx[i] -= (d + m) * nx; by[i] -= (d + m) * ny
                val vn = vx[i] * nx + vy[i] * ny
                if (vn > MIN_BOUNCE) hitBead = i
                if (vn > 0f) { vx[i] -= (1f + BOUNCE) * vn * nx; vy[i] -= (1f + BOUNCE) * vn * ny }
            }
            // The rim.
            val r = sqrt(bx[i] * bx[i] + by[i] * by[i])
            val rim = radius - m
            if (r > rim) {
                val ux = bx[i] / r; val uy = by[i] / r
                bx[i] = ux * rim; by[i] = uy * rim
                val vr = vx[i] * ux + vy[i] * uy
                if (vr > MIN_BOUNCE) hitBead = i
                if (vr > 0f) { vx[i] -= (1f + BOUNCE) * vr * ux; vy[i] -= (1f + BOUNCE) * vr * uy }
            }
            // And off each other, gently, so they do not heap into one blot.
            for (j in i + 1 until n) {
                val dx = bx[j] - bx[i]; val dy = by[j] - by[i]
                val need = (size[i] + size[j]) * 1.05f
                val d2 = dx * dx + dy * dy
                if (d2 < need * need && d2 > 1e-10f) {
                    val dd = sqrt(d2)
                    val push = (need - dd) * 0.5f / dd
                    bx[i] -= dx * push; by[i] -= dy * push
                    bx[j] += dx * push; by[j] += dy * push
                }
            }
        }
        // The spacing push can nudge a bead back over the rim; held inside.
        for (i in 0 until n) {
            val rim = radius - size[i] * 0.6f
            val r = sqrt(bx[i] * bx[i] + by[i] * by[i])
            if (r > rim) { bx[i] *= rim / r; by[i] *= rim / r }
        }
        // A tap as a bead strikes the glass, a few a second at most; every
        // strike shows in all 2n reflections at once, so it is not panned.
        hitCarry = min(1.5f, hitCarry + h * HITS_PER_SECOND)
        if (hitBead >= 0) heldHit = hitBead
        if (heldHit >= 0 && hitCarry >= 1f) {
            hitCarry -= 1f
            env.emit(EventKind.HIT, 0.3f + 0.2f * hue[heldHit] + 0.05f * kind[heldHit], 0f, 0.3f)
            heldHit = -1
        }
    }

    /** The slide into the mandala: inner rings first, each eased in. */
    private fun lock(env: SimEnv) {
        val t = elapsed - lockAt
        var settled = 0
        for (i in 0 until n) {
            val start = ring[i] * RING_STAGGER
            val u = ((t - start) / SLIDE_SECONDS).coerceIn(0f, 1f)
            val e = u * u * u * (u * (u * 6f - 15f) + 10f)
            bx[i] = fx[i] + (tx[i] - fx[i]) * e
            by[i] = fy[i] + (ty[i] - fy[i]) * e
            size[i] = fSize[i] + (tSize[i] - fSize[i]) * e
            turn[i] = fTurn[i] + angleTo(fTurn[i], tTurn[i]) * e
            if (u >= 1f) settled++
        }
        // A rising note as each ring clicks into place; the last is the payoff.
        val ringsDone = ((t - SLIDE_SECONDS) / RING_STAGGER + 1f).toInt().coerceIn(0, rings)
        while (ringsSounded < ringsDone && ringsSounded < rings - 1) {
            env.emit(EventKind.STEP, 0.3f + 0.5f * ringsSounded / max(1, rings - 1), 0f, 0.6f)
            ringsSounded++
        }
        if (doneAt < 0f && settled == n) {
            doneAt = elapsed
            payoffFrame = true
        }
    }

    /** The short way round from [a] to [b], for the slide's turn. */
    private fun angleTo(a: Float, b: Float): Float {
        // A bead's shapes repeat every quarter turn at most (squares) or half
        // (petals), so the turn only needs to go the short way within that.
        var d = (b - a) % (PI.toFloat())
        if (d > PI.toFloat() / 2f) d -= PI.toFloat()
        if (d < -PI.toFloat() / 2f) d += PI.toFloat()
        return d
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val cols = IntArray(3) { palette.at(0.12f + 0.36f * it) }
        val accent = palette.bright(0.9f, 0.15f)
        val since = if (doneAt >= 0f) elapsed - doneAt else -1f
        val settle = if (since >= 0f) (since / 0.8f).coerceIn(0f, 1f) else 0f
        // The tube's glass: a dim disc and its rim.
        g.disc(0f, 0f, radius, Palette.lerp(Palette.BG, cols[1], 0.06f))
        g.ring(0f, 0f, radius + 0.006f, 0.006f, Palette.lerp(Palette.BG, Palette.TEXT_SECONDARY, 0.4f))
        // Once locked, a soft light rises behind the mandala and stays.
        if (settle > 0f) g.glow(0f, 0f, radius * 1.05f, accent, 0.22f * settle)
        val step = 2f * PI.toFloat() / folds
        for (pass in 0..1) for (i in 0 until n) {
            // Big pieces first, so small ones are never hidden under them.
            val big = size[i] > radius * 0.055f
            if ((pass == 0) != big) continue
            val r = sqrt(bx[i] * bx[i] + by[i] * by[i])
            val a = atan2(by[i], bx[i])
            val rel = turn[i] - a
            val c = if (locking) Palette.lerp(cols[hue[i]], ringColour(palette, ring[i]), smooth(elapsed - lockAt - ring[i] * RING_STAGGER)) else cols[hue[i]]
            for (m in 0 until folds) for (mirror in 0..1) {
                val aa = (if (mirror == 0) a else -a) + step * m + spinView
                val rot = (if (mirror == 0) rel else -rel) + aa
                shape(g, kind[i], cos(aa) * r, sin(aa) * r, size[i], rot, c)
            }
        }
        // The centre jewel, where every mirror meets.
        g.disc(0f, 0f, radius * 0.035f, Palette.lerp(accent, Palette.TEXT_PRIMARY, 0.3f))
    }

    private fun smooth(x: Float): Float {
        val u = (x / SLIDE_SECONDS).coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }

    private fun ringColour(p: Palette, k: Int): Int = p.at(0.1f + 0.75f * k / max(1, rings - 1))

    private fun shape(g: G, k: Int, x: Float, y: Float, s: Float, rot: Float, c: Int) {
        when (k) {
            0 -> g.disc(x, y, s, c, 0.92f)
            1 -> g.ring(x, y, s, s * 0.32f, c)
            // A petal: long along its heading.
            2 -> g.rect(x, y, s * 1.5f, s * 0.42f, c, rot, 0.9f)
            // A diamond.
            else -> g.rect(x, y, s * 0.72f, s * 0.72f, c, rot + PI.toFloat() / 4f, 0.9f)
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && elapsed - doneAt > 4f

    override fun counters(): List<Pair<String, String>> = listOf(
        "mirrors" to "$folds × 2",
        "pieces" to "$n → ${n * folds * 2}",
        "pattern" to if (doneAt >= 0f) "locked" else if (locking) "locking" else "tumbling"
    )

    companion object {
        const val KINDS = 4
        const val MAX_RINGS = 6
        const val LOCK_AT = 9f
        const val SLIDE_SECONDS = 1.6f
        const val RING_STAGGER = 0.45f
        const val TUBE_TURN = 0.55f
        const val GRAVITY = 0.14f
        const val DRAG = 0.35f
        const val BOUNCE = 0.55f
        const val MIN_BOUNCE = 0.05f
        const val HITS_PER_SECOND = 4f

        fun def(): SimDef = SimDef(
            id = "art/kaleidoscope",
            title = "Kaleidoscope",
            shelf = Shelf.ART,
            family = "Generative art",
            blurb = "Beads tumble in one wedge between two mirrors; the reflections make the rest, until it locks into a mandala.",
            formats = listOf(TrapFormat.REVEAL, TrapFormat.SNAP),
            heroLabel = "Shake it up",
            defaultPalette = "Aurora",
            typicalSeconds = 15f,
            params = listOf(
                Param.int("mirrors", "Mirror angle (π/n)", 3, 12, 6, card = Card.DATA),
                Param.int("pieces", "Pieces", 4, 40, 12, card = Card.DATA),
                Param.float("pace", "Pace", 0.3f, 3f, 1f, card = Card.DATA),
                Param.float("turn", "Turn", 0f, 0.8f, 0.15f, card = Card.MOTION)
            ),
            create = { KaleidoscopeSim() }
        )
    }
}
