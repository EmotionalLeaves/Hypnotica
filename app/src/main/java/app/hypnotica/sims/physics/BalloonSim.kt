package app.hypnotica.sims.physics

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.LawKnob
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
 * A balloon, pumped. Each pump a little bigger and a note a little higher; the
 * skin starts to tremble; and at a size nobody can see coming it goes, in a
 * cloud of confetti.
 *
 * The bursting size is drawn at random for each balloon from the top third of
 * what it could hold, so the same balloon never pops at the same pump twice —
 * the whole hook is not knowing which pump it will be.
 */
class BalloonSim : Sim {

    private var radius = 0.08f
    private var target = 0.08f
    private var burstAt = 0.6f
    private var maxR = 0.8f
    private var sincePump = 0f
    private var pumps = 0
    private var popped = false
    private var poppedAt = -1f
    private var elapsed = 0f
    private var payoffFrame = false
    private var tremble = 0f
    private var cx = FloatArray(0)
    private var cy = FloatArray(0)
    private var cvx = FloatArray(0)
    private var cvy = FloatArray(0)
    private var ctint = FloatArray(0)
    private var cspin = FloatArray(0)

    override fun setup(env: SimEnv) {
        maxR = min(env.halfWidth, 1f) * 0.92f
        radius = maxR * 0.1f
        target = radius
        // Somewhere in the last third of what it could hold.
        burstAt = maxR * env.rng.range(0.62f, 0.98f)
        sincePump = 0f
        pumps = 0
        popped = false
        poppedAt = -1f
        elapsed = 0f
        payoffFrame = false
        tremble = 0f
        halfW = env.halfWidth
        // The confetti is in the balloon from the start, visible through the
        // skin, jostled by every pump — so the pop has been promised all along.
        val n = env.params.i("confetti", 600).coerceIn(20, 20000)
        cx = FloatArray(n); cy = FloatArray(n); cvx = FloatArray(n); cvy = FloatArray(n)
        ctint = FloatArray(n); cspin = FloatArray(n)
        for (i in 0 until n) {
            val a = env.rng.range(0f, 2f * PI.toFloat())
            val r = sqrt(env.rng.nextFloat()) * 0.85f
            cx[i] = cos(a) * r; cy[i] = sin(a) * r
            ctint[i] = env.rng.nextFloat()
            cspin[i] = env.rng.range(0f, 6.28f)
        }
    }

    private var halfW = 0.46f

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        if (!popped) {
            sincePump += h * env.params.f("pace", 1f)
            if (sincePump >= PUMP_EVERY) {
                sincePump = 0f
                pumps++
                // Each pump adds air, so the radius grows by less as it gets
                // bigger: volume, not width, is what the pump adds.
                val r3 = target * target * target + PUMP_VOLUME * maxR * maxR * maxR
                target = Math.cbrt(r3.toDouble()).toFloat()
                val share = (target / maxR).coerceIn(0f, 1f)
                env.emit(EventKind.STEP, share, 0f, 0.35f + 0.4f * share)
            }
            radius += (target - radius) * min(1f, h * 8f)
            // The skin trembles as it nears its limit — the only warning there is.
            val near = ((radius - burstAt * 0.75f) / (burstAt * 0.25f)).coerceIn(0f, 1f)
            tremble = near * near
            if (radius >= burstAt) pop(env)
        } else {
            val g = env.laws.gravity * 0.06f
            for (i in cx.indices) {
                cvx[i] *= 1f - 1.2f * h
                cvy[i] = cvy[i] * (1f - 1.2f * h) - g * h
                val was = cy[i]
                cx[i] += cvx[i] * h
                cy[i] += cvy[i] * h
                // The pitter-patter of it landing, one piece in twenty heard.
                if (i % 20 == 0 && was >= FLOOR && cy[i] < FLOOR) {
                    env.emit(EventKind.HIT, 0.6f + 0.4f * ctint[i], (cx[i] / max(0.1f, halfW)).coerceIn(-1f, 1f), 0.25f)
                }
                // The screen's edges are walls; confetti piles against them
                // rather than leaving.
                if (abs(cx[i]) > halfW) { cx[i] = cx[i].coerceIn(-halfW, halfW); cvx[i] = -cvx[i] * 0.3f }
            }
        }
    }

    private fun pop(env: SimEnv) {
        popped = true
        poppedAt = elapsed
        payoffFrame = true
        env.emit(EventKind.DONE, 1f, 0f, 1f)
        repeat(POP_NOTES) { env.emit(EventKind.HIT, env.rng.range(0.4f, 1f), env.rng.range(-0.9f, 0.9f), env.rng.range(0.4f, 0.9f)) }
        // Out from where each piece was inside the balloon.
        for (i in cx.indices) {
            val ux = cx[i]; val uy = cy[i]
            cx[i] = ux * radius; cy[i] = uy * radius
            val v = env.rng.range(0.4f, 1.4f)
            val d = sqrt(ux * ux + uy * uy).coerceAtLeast(0.05f)
            cvx[i] = ux / d * v; cvy[i] = uy / d * v + 0.3f
        }
    }

    /** A big pump, now: three pumps' worth of air at once. */
    override fun hero(env: SimEnv) {
        if (popped) return
        val r3 = target * target * target + 3f * PUMP_VOLUME * maxR * maxR * maxR
        target = Math.cbrt(r3.toDouble()).toFloat()
        pumps++
        env.emit(EventKind.STEP, (target / maxR).coerceIn(0f, 1f), 0f, 0.8f)
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        if (!popped) {
            val wob = tremble * 0.012f
            val ox = sin(elapsed * 47f) * wob
            val oy = cos(elapsed * 53f) * wob
            val skin = palette.at(0.75f)
            // The string and the knot under it.
            g.line(ox, oy - radius, ox + 0.02f, -0.98f, 0.003f, Palette.TEXT_SECONDARY, 0.6f)
            g.rect(ox, oy - radius - 0.012f, 0.014f, 0.012f, skin, PI.toFloat() / 4f)
            g.glow(ox, oy, radius * 1.25f, skin, 0.25f + 0.35f * tremble)
            // Thinner, so paler, as it stretches.
            g.disc(ox, oy, radius, Palette.withAlpha(skin, 0.95f - 0.3f * (radius / maxR)))
            // The confetti inside, jostling.
            g.reservePoints(cx.size)
            for (i in cx.indices) {
                val j = 0.04f * sin(cspin[i] + elapsed * (2f + 6f * tremble))
                g.point(ox + (cx[i] + j) * radius, oy + (cy[i] - j) * radius, 0.01f, Palette.withAlpha(palette.at(ctint[i]), 0.8f))
            }
            g.disc(ox - radius * 0.35f, oy + radius * 0.4f, radius * 0.16f, Palette.withAlpha(Palette.TEXT_PRIMARY, 0.45f))
        } else {
            g.reservePoints(cx.size)
            for (i in cx.indices) {
                val flutter = 0.6f + 0.4f * abs(sin(cspin[i] + elapsed * 9f))
                g.point(cx[i], cy[i], 0.012f * flutter, palette.at(ctint[i]))
            }
        }
    }

    override fun frameHalfWidth(env: SimEnv): Float = 0f

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = popped && elapsed - poppedAt > FALL_SECONDS

    override fun counters(): List<Pair<String, String>> = listOf(
        "pumps" to pumps.toString(),
        if (popped) "popped at" to "pump $pumps" else "size" to String.format(java.util.Locale.US, "%.0f%%", radius / maxR * 100f)
    )

    companion object {
        const val PUMP_EVERY = 0.55f
        const val PUMP_VOLUME = 0.035f
        const val POP_NOTES = 10
        const val FALL_SECONDS = 3f
        const val FLOOR = -0.6f

        fun def(): SimDef = SimDef(
            id = "physics/balloon",
            title = "Pump the balloon",
            shelf = Shelf.PHYSICS,
            family = "Pressure & bursting",
            blurb = "Every pump a little bigger. Which one pops it?",
            formats = listOf(TrapFormat.SNAP, TrapFormat.GUESS, TrapFormat.CUT),
            lawKnobs = setOf(LawKnob.GRAVITY),
            heroLabel = "Big pump",
            heroRestarts = false,
            defaultPalette = "Candy",
            typicalSeconds = 12f,
            params = listOf(
                Param.int("confetti", "Confetti", 20, 20000, 600, card = Card.LOOK, logScale = true),
                Param.float("pace", "Pumping speed", 0.3f, 3f, 1f, card = Card.MOTION)
            ),
            create = { BalloonSim() }
        )
    }
}
