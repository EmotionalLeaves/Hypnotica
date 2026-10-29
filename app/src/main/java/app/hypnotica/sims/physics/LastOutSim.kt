package app.hypnotica.sims.physics

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.LawKnob
import app.hypnotica.core.sim.NO_CONTENDERS
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
 * A spinning ring with one gap, and balls inside. Each time the gap comes
 * round to the bottom, some fall out; the last one left inside wins.
 *
 * The gap widens a little every turn, so it always ends — but the last few
 * keep getting bumped away from the opening at the last moment, which is the
 * whole point.
 */
class LastOutSim : Sim {

    private var n = 8
    private var x = FloatArray(0)
    private var y = FloatArray(0)
    private var vx = FloatArray(0)
    private var vy = FloatArray(0)
    private var out = BooleanArray(0)
    private var outAt = FloatArray(0)
    /** Touching the wall already: rolling round it is one contact, not a knock per substep. */
    private var onWall = BooleanArray(0)
    private var tint = FloatArray(0)
    private var inside = 0
    private var r = 0.04f
    private var ringR = 0.8f
    private var gapAngle = 0f
    private var gap = 0.5f
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var winner = -1
    private var halfW = 0.46f
    private var gaps = 1

    override fun setup(env: SimEnv) {
        n = env.params.i("balls", 8).coerceIn(2, MAX_BALLS)
        gaps = env.params.i("gaps", 1).coerceIn(1, MAX_GAPS)
        halfW = env.halfWidth
        ringR = min(halfW, 1f) * 0.85f
        r = (ringR * 0.09f * sqrt(8f / n)).coerceIn(0.008f, 0.08f)
        x = FloatArray(n); y = FloatArray(n); vx = FloatArray(n); vy = FloatArray(n)
        out = BooleanArray(n); outAt = FloatArray(n) { -1f }; onWall = BooleanArray(n)
        tint = FloatArray(n) { (it + 0.5f) / n }
        for (i in 0 until n) {
            var tries = 0
            do {
                val a = env.rng.range(0f, 2f * PI.toFloat())
                val d = sqrt(env.rng.nextFloat()) * (ringR - 2f * r)
                x[i] = cos(a) * d; y[i] = sin(a) * d
                tries++
            } while (tries < 40 && (0 until i).any { j -> (x[j] - x[i]).let { it * it } + (y[j] - y[i]).let { it * it } < 4f * r * r })
            val b = env.rng.range(0f, 2f * PI.toFloat())
            val s = env.rng.range(0.3f, 0.8f)
            vx[i] = cos(b) * s; vy[i] = sin(b) * s
        }
        inside = n
        // The gap starts at the top, so the first escape is a turn away.
        gapAngle = (PI / 2).toFloat()
        gap = START_GAP
        elapsed = 0f
        doneAt = -1f
        payoffFrame = false
        winner = -1
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        val spin = env.params.f("spin", 1f) * SPIN
        gapAngle += spin * h
        if (doneAt < 0f) gap = min(MAX_GAP, gap + GAP_GROWTH * h)
        val g = env.laws.gravity * GRAVITY_SCALE
        val sub = 4
        val dh = h / sub
        repeat(sub) {
            for (i in 0 until n) {
                vy[i] -= g * dh
                x[i] += vx[i] * dh; y[i] += vy[i] * dh
                if (out[i]) continue
                val d = sqrt(x[i] * x[i] + y[i] * y[i])
                if (d + r <= ringR - r * 0.25f) onWall[i] = false
                if (d + r <= ringR) continue
                // At the wall: through the gap, or off it.
                val a = atan2(y[i], x[i])
                // Once it's won, the gap is wall: the winner stays in.
                val open = doneAt < 0f && inGap(a)
                if (open && d > ringR - r * 0.2f) {
                    if (d > ringR + r) escape(env, i)
                    continue
                }
                if (open) continue
                // Already more than half out when the gap's edge swung past:
                // it was leaving, and it's gone. Snapped back inside, as it
                // was, a ball visibly on its way out could be saved — and win.
                if (d > ringR && doneAt < 0f) { escape(env, i); continue }
                val nx = x[i] / d; val ny = y[i] / d
                x[i] = nx * (ringR - r); y[i] = ny * (ringR - r)
                val vn = vx[i] * nx + vy[i] * ny
                if (vn > 0f) {
                    vx[i] -= (1f + BOUNCE) * vn * nx; vy[i] -= (1f + BOUNCE) * vn * ny
                    // The spinning wall drags what it touches round with it.
                    vx[i] += -ny * spin * ringR * 0.15f
                    vy[i] += nx * spin * ringR * 0.15f
                    // Only a knock that arrives: a ball riding round the
                    // wall (the winner, once the gap closes) re-met it every
                    // substep, a 90-a-second buzz with nothing visibly hitting.
                    if (vn > 0.3f && !onWall[i]) env.emit(EventKind.STEP, 0.4f + 0.5f * tint[i], (x[i] / max(0.1f, halfW)).coerceIn(-1f, 1f), (0.1f + 0.2f * vn).coerceAtMost(0.4f))
                    onWall[i] = true
                }
            }
            for (i in 0 until n) {
                if (out[i]) continue
                for (j in i + 1 until n) {
                    if (out[j]) continue
                    val ex = x[j] - x[i]; val ey = y[j] - y[i]
                    val d2 = ex * ex + ey * ey
                    if (d2 >= 4f * r * r || d2 < 1e-12f) continue
                    val d = sqrt(d2)
                    val nx = ex / d; val ny = ey / d
                    val push = (2f * r - d) * 0.5f
                    x[i] -= nx * push; y[i] -= ny * push
                    x[j] += nx * push; y[j] += ny * push
                    val rel = (vx[i] - vx[j]) * nx + (vy[i] - vy[j]) * ny
                    if (rel > 0f) {
                        vx[i] -= rel * nx; vy[i] -= rel * ny
                        vx[j] += rel * nx; vy[j] += rel * ny
                    }
                }
            }
        }
    }

    /** Through any of the gaps, spaced evenly round the ring. */
    private fun inGap(a: Float): Boolean {
        val twoPi = 2f * PI.toFloat()
        for (k in 0 until gaps) {
            var rel = (a - gapAngle - k * twoPi / gaps) % twoPi
            if (rel < 0f) rel += twoPi
            if (rel < gapNow) return true
        }
        return false
    }

    /** Each gap's width: narrower the more there are (with the square
     *  root: shared out evenly they were too narrow to get through), so more gaps
     *  doesn't just mean over sooner. */
    private val gapNow get() = min(gap / kotlin.math.sqrt(gaps.toFloat()), 2f * PI.toFloat() / gaps * 0.6f)

    private fun escape(env: SimEnv, i: Int) {
        if (doneAt >= 0f) return
        out[i] = true
        outAt[i] = elapsed
        inside--
        // Down the scale as they go: the last few notes are the lowest.
        env.emit(EventKind.HIT, (inside.toFloat() / n).coerceIn(0f, 1f) * 0.8f + 0.1f, (x[i] / max(0.1f, halfW)).coerceIn(-1f, 1f), 0.6f)
        if (inside == 1) {
            winner = (0 until n).first { !out[it] }
            doneAt = elapsed
            payoffFrame = true
            env.emit(EventKind.DONE, 0.9f, 0f, 1f)
        }
    }

    /** A shake: every ball inside flung at random. */
    override fun hero(env: SimEnv) {
        if (doneAt >= 0f) return
        for (i in 0 until n) {
            if (out[i]) continue
            vx[i] += env.rng.range(-1f, 1f); vy[i] += env.rng.range(-0.4f, 1.4f)
        }
        env.emit(EventKind.STEP, 0.6f, 0f, 0.8f)
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        // The ring as arcs between the gaps.
        val twoPi = 2f * PI.toFloat()
        // Once it's won the gap is wall, so it is drawn swinging shut: left
        // open, the winner bounced off thin air at the opening.
        val shut = if (doneAt < 0f) 1f else (1f - (elapsed - doneAt) / SHUT_SECONDS).coerceIn(0f, 1f)
        val gapVis = gapNow * shut
        val arc = twoPi / gaps - gapVis
        // The floor inside the ring a shade above the void, so in and out
        // read at a glance: a ball that has escaped is on the dark.
        g.disc(0f, 0f, ringR, FLOOR)
        val ringC = if (doneAt >= 0f && winner >= 0) Palette.lerp(Palette.TEXT_PRIMARY, palette.at(tint[winner]), 1f - shut) else Palette.TEXT_PRIMARY
        for (k in 0 until gaps) {
            val start = gapAngle + k * twoPi / gaps + gapVis + (gapNow - gapVis) * 0.5f
            if (gapVis <= 1e-4f) g.ring(0f, 0f, ringR + RING_W, RING_W, ringC, alpha = 0.9f)
            else g.ring(0f, 0f, ringR + RING_W, RING_W, ringC, twoPi - arc, start + arc, 0.9f)
        }
        for (i in 0 until n) {
            if (out[i] && y[i] < -1.4f) continue
            val c = palette.at(tint[i])
            if (i == winner) g.glow(x[i], y[i], r * (4f + sin(elapsed * 6f)), c, 0.7f)
            g.disc(x[i], y[i], r, if (out[i]) Palette.withAlpha(c, 0.45f) else c)
        }
    }

    override fun frameHalfWidth(env: SimEnv): Float = 0f

    override fun contenders(env: SimEnv): IntArray =
        if (n > MAX_PICKS) NO_CONTENDERS else IntArray(n) { env.palette.at(tint[it]) }

    override fun winner(): Int = if (n > MAX_PICKS) -1 else winner

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = (doneAt >= 0f && elapsed - doneAt > 3f) || elapsed > GIVE_UP_SECONDS

    override fun counters(): List<Pair<String, String>> =
        if (winner >= 0) listOf("last one in" to "#${winner + 1}")
        else listOf("inside" to "$inside / $n")

    companion object {
        // Translucent: glows are drawn under every shape, and an opaque floor
        // hid the winner's glow inside the ring.
        private val FLOOR = 0x80141A24.toInt()
        const val MAX_BALLS = 300
        const val MAX_GAPS = 4
        const val MAX_PICKS = 8
        const val SPIN = 0.7f
        const val START_GAP = 0.35f
        const val MAX_GAP = 2.4f
        const val GAP_GROWTH = 0.03f
        const val GRAVITY_SCALE = 0.12f
        const val BOUNCE = 0.85f
        const val GIVE_UP_SECONDS = 90f
        const val SHUT_SECONDS = 0.35f
        /** The ring's width, all of it outside ringR: a ring's radius is its outer edge, and centred on the wall it overlapped every ball touching it. */
        const val RING_W = 0.022f

        fun def(): SimDef = SimDef(
            id = "physics/last-out",
            title = "Last one in",
            shelf = Shelf.PHYSICS,
            family = "Escapes",
            blurb = "One gap, going round. They fall out one by one. Who's left?",
            formats = listOf(TrapFormat.ESCAPE, TrapFormat.LAST_STANDING, TrapFormat.GUESS),
            lawKnobs = setOf(LawKnob.GRAVITY),
            heroLabel = "Shake",
            heroRestarts = false,
            defaultPalette = "Spectrum",
            typicalSeconds = 18f,
            params = listOf(
                Param.int("balls", "Balls", 2, MAX_BALLS, 8, card = Card.DATA, logScale = true),
                Param.int("gaps", "Gaps", 1, MAX_GAPS, 1, card = Card.DATA),
                Param.float("spin", "Spin", 0.2f, 4f, 1f, card = Card.MOTION)
            ),
            create = { LastOutSim() }
        )
    }
}
