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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A prize wheel, spun hard and left to slow.
 *
 * Pegs round the rim knock a flapper at the top: a click per peg, fast and
 * blurred at first, then further and further apart. Each peg takes a little
 * of the wheel's speed to get past, so near the end one may not make it — the
 * wheel climbs onto the peg, hangs, and rocks back. Where it stops is drawn
 * from the spin, so nobody, the person watching included, knows until then.
 */
class WheelSim : Sim {

    private var segments = 12
    private var angle = 0f
    private var omega = 0f
    private var friction = 0.3f
    private var flap = 0f
    private var flapV = 0f
    private var lastPeg = 0
    private var elapsed = 0f
    private var stoppedAt = -1f
    private var payoffFrame = false
    private var winner = -1
    private var rocks = 0
    private var radius = 0.8f
    private var halfW = 0.46f
    private var cx = FloatArray(0)
    private var cy = FloatArray(0)
    private var cvx = FloatArray(0)
    private var cvy = FloatArray(0)
    private var ctint = FloatArray(0)

    override fun setup(env: SimEnv) {
        segments = env.params.i("segments", 8).coerceIn(MIN_SEGMENTS, MAX_SEGMENTS)
        radius = min(env.halfWidth, 1f) * 0.86f
        halfW = env.halfWidth
        angle = env.rng.range(0f, 2f * PI.toFloat())
        // A spin that stops after about this long, at full strength.
        val seconds = env.rng.range(11f, 15f) * env.params.f("spin", 1f).coerceIn(0.3f, 3f)
        omega = env.rng.range(11f, 15f)
        friction = omega / seconds
        flap = 0f
        flapV = 0f
        lastPeg = pegIndex(angle)
        elapsed = 0f
        stoppedAt = -1f
        payoffFrame = false
        winner = -1
        rocks = 0
        cx = FloatArray(0); cy = FloatArray(0); cvx = FloatArray(0); cvy = FloatArray(0); ctint = FloatArray(0)
    }

    private val seg get() = (2.0 * PI / segments).toFloat()

    /** Which gap between pegs is under the pointer, the pointer being straight up. */
    private fun pegIndex(a: Float): Int = floor((POINTER - a) / seg).toInt()

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        // The flapper springs back to centre.
        flapV += (-flap * FLAP_SPRING - flapV * FLAP_DAMP) * h
        flap += flapV * h
        if (stoppedAt >= 0f) {
            stepConfetti(env, h)
            return
        }
        // Friction, and a little drag, so the end is a crawl rather than a halt.
        val sign = if (omega >= 0f) 1f else -1f
        omega -= sign * (friction + DRAG * abs(omega)) * h
        if (omega * sign < 0f) omega = 0f
        val before = angle
        angle += omega * h
        val peg = pegIndex(angle)
        if (peg != lastPeg) {
            // A peg reached the flapper. Getting past costs speed; without
            // enough, the wheel is thrown back into the gap it came from.
            val need = PEG_COST * (2f * PI.toFloat() / segments)
            val energy = 0.5f * omega * omega
            if (energy < need && abs(omega) < ROCK_SPEED) {
                angle = before
                omega = -omega * 0.45f
                rocks++
                flapV += 10f * sign
                env.emit(EventKind.HIT, 0.25f, 0f, 0.45f)
            } else {
                omega = sign * sqrt(max(0f, omega * omega - 2f * need * PEG_TAX))
                lastPeg = peg
                flapV -= sign * min(40f, 6f + abs(omega) * 2f)
                // The click: higher round the wheel, so a lap is a scale.
                val k = ((peg % segments) + segments) % segments
                env.emit(EventKind.HIT, 0.3f + 0.6f * k / segments, 0f, (0.25f + 0.04f * abs(omega)).coerceAtMost(0.7f))
            }
        }
        if (abs(omega) < STOP_SPEED && friction > 0f) {
            omega = 0f
            stoppedAt = elapsed
            payoffFrame = true
            winner = ((pegIndex(angle) % segments) + segments) % segments
            env.emit(EventKind.DONE, 0.4f + 0.5f * winner / segments, 0f, 1f)
            burst(env)
        }
    }

    private fun burst(env: SimEnv) {
        val n = CONFETTI
        cx = FloatArray(n); cy = FloatArray(n); cvx = FloatArray(n); cvy = FloatArray(n); ctint = FloatArray(n)
        for (i in 0 until n) {
            cx[i] = env.rng.range(-0.1f, 0.1f)
            cy[i] = radius + 0.06f
            val a = env.rng.range(0.2f, PI.toFloat() - 0.2f)
            val v = env.rng.range(0.6f, 1.8f)
            cvx[i] = cos(a) * v; cvy[i] = sin(a) * v
            ctint[i] = env.rng.nextFloat()
        }
    }

    private fun stepConfetti(env: SimEnv, h: Float) {
        val g = env.laws.gravity * 0.08f
        for (i in cx.indices) {
            cvx[i] *= 1f - 1.5f * h
            cvy[i] = cvy[i] * (1f - 1.5f * h) - g * h
            cx[i] += cvx[i] * h
            cy[i] += cvy[i] * h
            // The screen's sides are walls: it piles up rather than leaving.
            if (abs(cx[i]) > halfW) { cx[i] = cx[i].coerceIn(-halfW, halfW); cvx[i] = -cvx[i] * 0.3f }
        }
    }

    /** A second spin, right now, on top of whatever speed it has. */
    override fun hero(env: SimEnv) {
        if (stoppedAt >= 0f) return
        omega += if (omega >= 0f) 8f else -8f
        env.emit(EventKind.STEP, 0.7f, 0f, 0.8f)
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val s = seg
        val twoPi = 2f * PI.toFloat()
        g.glow(0f, 0f, radius * 1.15f, palette.at(0.5f), 0.18f)
        for (k in 0 until segments) {
            val a0 = angle + k * s
            // Alternate light and dark so neighbours never blur together.
            val base = palette.at((k + 0.5f) / segments)
            val c = if (k % 2 == 0) base else Palette.brighten(base, 0.25f)
            val won = k == winner
            val pulse = if (won) 0.5f + 0.5f * sin((elapsed - stoppedAt) * 10f) else 0f
            val col = if (won) Palette.brighten(c, 0.3f + 0.3f * pulse) else if (winner >= 0) Palette.withAlpha(c, 0.45f) else c
            // A wedge: a ring as thick as its radius with everything but this
            // segment's arc cut away.
            g.ring(0f, 0f, radius, radius, col, twoPi - s, a0 + s)
        }
        if (winner >= 0) {
            val mid = angle + (winner + 0.5f) * s
            g.glow(cos(mid) * radius * 0.6f, sin(mid) * radius * 0.6f, radius * 0.5f, palette.at((winner + 0.5f) / segments), 0.5f)
        }
        // The pegs round the rim.
        for (k in 0 until segments) {
            val a = angle + k * s
            g.disc(cos(a) * radius * 0.97f, sin(a) * radius * 0.97f, 0.012f, Palette.TEXT_PRIMARY)
        }
        g.ring(0f, 0f, radius, 0.01f, Palette.TEXT_PRIMARY, alpha = 0.7f)
        // The hub.
        g.disc(0f, 0f, radius * 0.12f, 0xFF15171E.toInt())
        g.ring(0f, 0f, radius * 0.12f, 0.008f, Palette.TEXT_PRIMARY, alpha = 0.8f)
        // The flapper, at the top, pointing in.
        val tipX = sin(flap) * 0.09f
        val baseY = radius + 0.09f
        g.line(0f, baseY, tipX, radius - 0.02f, 0.022f, Palette.DANGER)
        g.disc(0f, baseY, 0.018f, Palette.DANGER)
        if (cx.isNotEmpty()) {
            g.reservePoints(cx.size)
            for (i in cx.indices) g.point(cx[i], cy[i], 0.012f, palette.at(ctint[i]))
        }
    }

    override fun contenders(env: SimEnv): IntArray =
        if (segments > MAX_PICKS) app.hypnotica.core.sim.NO_CONTENDERS
        else IntArray(segments) { k ->
            val base = env.palette.at((k + 0.5f) / segments)
            if (k % 2 == 0) base else Palette.brighten(base, 0.25f)
        }

    override fun winner(): Int = if (segments > MAX_PICKS) -1 else winner

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = stoppedAt >= 0f && elapsed - stoppedAt > 3f

    override fun counters(): List<Pair<String, String>> =
        if (winner >= 0) listOf("landed on" to (winner + 1).toString(), "rocked back" to rocks.toString())
        else listOf("speed" to String.format(java.util.Locale.US, "%.1f", abs(omega)), "rocked back" to rocks.toString())

    companion object {
        const val MIN_SEGMENTS = 4
        const val MAX_SEGMENTS = 40
        val POINTER = (PI / 2).toFloat()
        const val FLAP_SPRING = 900f
        const val FLAP_DAMP = 18f
        const val DRAG = 0.05f
        /** What a peg asks of the wheel's energy, per radian of segment. */
        const val PEG_COST = 0.9f
        /** How much of that a peg actually takes when it is passed. */
        const val PEG_TAX = 0.15f
        /** Below this speed a peg can throw the wheel back. */
        const val ROCK_SPEED = 2.5f
        const val STOP_SPEED = 0.02f
        const val CONFETTI = 400
        const val MAX_PICKS = 8

        fun def(): SimDef = SimDef(
            id = "physics/wheel",
            title = "Spin the wheel",
            shelf = Shelf.PHYSICS,
            family = "Chance",
            blurb = "Click, click, click… click. Where does it stop?",
            formats = listOf(TrapFormat.GUESS, TrapFormat.SNAP),
            heroLabel = "Spin harder",
            heroRestarts = false,
            defaultPalette = "Spectrum",
            typicalSeconds = 11f,
            params = listOf(
                Param.int("segments", "Segments", MIN_SEGMENTS, MAX_SEGMENTS, 8, card = Card.DATA),
                Param.float("spin", "Spin length", 0.3f, 3f, 1f, card = Card.MOTION)
            ),
            create = { WheelSim() }
        )
    }
}
