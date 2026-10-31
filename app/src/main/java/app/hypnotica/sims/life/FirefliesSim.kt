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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Fireflies in a dark field, each with its own clock, flashing when it runs
 * out. Seeing a neighbour flash nudges a firefly's clock forward a little —
 * nothing more — and that is enough: flickers become patches, patches become
 * waves rolling across the field, and in the end the whole field lights at
 * once. (Mirollo and Strogatz, 1990: pulse-coupled oscillators, all of them
 * slightly different, still lock.)
 */
class FirefliesSim : Sim {

    private var n = 150
    private var x = FloatArray(0)
    private var y = FloatArray(0)
    private var hx = FloatArray(0)
    private var hy = FloatArray(0)
    private var phase = FloatArray(0)
    private var rate = FloatArray(0)
    private var lastFlash = FloatArray(0)
    private var flashedNow = IntArray(0)
    private var flashedCount = 0
    private var wander = FloatArray(0)

    private var halfW = 0.45f
    private var elapsed = 0f
    private var best = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var bloom = 0f

    override fun setup(env: SimEnv) {
        n = env.params.i("fireflies", 150).coerceIn(10, MAX_N)
        halfW = env.halfWidth * 0.94f
        val rng = env.rng
        x = FloatArray(n) { rng.range(-halfW, halfW) }
        y = FloatArray(n) { rng.range(-0.92f, 0.92f) }
        hx = x.copyOf(); hy = y.copyOf()
        phase = FloatArray(n) { rng.nextFloat() }
        // A natural rhythm each, a little apart: about one flash a second.
        rate = FloatArray(n) { (1f + rng.normal() * 0.05f).coerceIn(0.85f, 1.15f) }
        lastFlash = FloatArray(n) { -10f }
        flashedNow = IntArray(n)
        wander = FloatArray(n) { rng.range(0f, 6.2831855f) }
        elapsed = 0f; best = 0f; doneAt = -1f; payoffFrame = false; bloom = 0f
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val a = abs(dt)
        elapsed += a
        bloom = max(0f, bloom - a * 1.2f)
        val speed = env.params.f("speed", 1f).coerceIn(0.1f, 5f)
        val pull = env.params.f("pull", 0.5f).coerceIn(0f, 1f)
        val h = a * speed
        val reach = REACH
        val reach2 = reach * reach

        // Drift a little, each round its own spot.
        for (i in 0 until n) {
            wander[i] += h * 0.6f
            x[i] = hx[i] + 0.025f * cos(wander[i] * 1.3f + i)
            y[i] = hy[i] + 0.025f * sin(wander[i] + i * 0.7f)
        }

        flashedCount = 0
        for (i in 0 until n) {
            phase[i] += rate[i] * h * BASE_HZ
            if (phase[i] >= 1f) {
                phase[i] -= 1f
                lastFlash[i] = elapsed
                flashedNow[flashedCount++] = i
            }
        }
        // Each flash nudges the clocks of the fireflies that can see it, more
        // for the near ones; a clock pushed past its end flashes there and
        // then and nudges its own neighbours — which is how a patch catches.
        var k = 0
        while (k < flashedCount) {
            val i = flashedNow[k++]
            for (j in 0 until n) {
                if (lastFlash[j] == elapsed) continue
                val dx = x[j] - x[i]; val dy = y[j] - y[i]
                val d2 = dx * dx + dy * dy
                if (d2 > reach2) continue
                // Concave in phase: a late clock is pushed further than an early one.
                phase[j] += pull * KICK * (1f - d2 / reach2) * (0.3f + phase[j])
                if (phase[j] >= 1f) {
                    phase[j] = 0f
                    lastFlash[j] = elapsed
                    flashedNow[flashedCount++] = j
                }
            }
        }
        if (flashedCount > 0) {
            val share = flashedCount.toFloat() / n
            env.emit(EventKind.STEP, 0.3f + 0.5f * share, (x[flashedNow[0]] / halfW).coerceIn(-1f, 1f), min(0.5f, 0.12f + share))
        }

        // How much of the field lit together, just now.
        var together = 0
        for (i in 0 until n) if (elapsed - lastFlash[i] < WINDOW_S) together++
        val share = together.toFloat() / n
        if (share > best + 0.15f && share > 0.35f) {
            env.emit(EventKind.CROSS, 0.4f + share * 0.5f, 0f, 0.4f)
            // The build to the moment, called on the way up.
            if (best < 0.5f && share >= 0.5f && share < SYNCED) env.callout("Half in step…")
            else if (best < 0.75f && share >= 0.75f && share < SYNCED) env.callout("Almost…")
            best = share
        }
        if (doneAt < 0f && share >= SYNCED) {
            doneAt = elapsed
            payoffFrame = true
            bloom = 1f
            env.emit(EventKind.DONE, 0.9f, 0f, 1f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        for (i in 0 until n) {
            val since = elapsed - lastFlash[i]
            val glow = exp(-since * 6f)
            val c = pal.at(0.15f + 0.3f * (i % 7) / 7f)
            if (glow > 0.02f) {
                g.glow(x[i], y[i], 0.09f + 0.05f * glow, c, glow * 0.75f)
                g.disc(x[i], y[i], 0.008f, Palette.brighten(c, 0.6f), min(1f, 0.25f + glow))
            } else {
                // Dark between flashes, only the body showing — and its clock
                // as a faint warming, so you can see who is next.
                g.disc(x[i], y[i], 0.006f, c, 0.12f + 0.25f * phase[i] * phase[i])
            }
        }
        if (bloom > 0f) g.glow(0f, 0f, 1.4f, pal.at(0.3f), bloom * 0.25f)
    }

    override fun payoff(): Boolean = payoffFrame

    /** Endless: once the field has caught, it goes on flashing together. */
    override fun finished(): Boolean = false

    override fun counters(): List<Pair<String, String>> = listOf(
        "fireflies" to "$n",
        "most at once" to "${(best * 100).toInt()}%"
    )

    companion object {
        const val MAX_N = 1200
        const val BASE_HZ = 1.1f
        const val REACH = 0.42f
        const val KICK = 0.044f
        const val WINDOW_S = 0.12f
        const val SYNCED = 0.9f

        fun def(): SimDef = SimDef(
            id = "life/fireflies",
            title = "Fireflies",
            shelf = Shelf.LIFE,
            family = "Sync",
            blurb = "Every flash nudges the neighbours' clocks a little. In the end the whole field lights at once.",
            formats = listOf(TrapFormat.SNAP, TrapFormat.CHAIN, TrapFormat.GROW),
            heroLabel = "New night",
            defaultPalette = "Aurora",
            typicalSeconds = 22f,
            params = listOf(
                Param.int("fireflies", "Fireflies", 10, MAX_N, 150, card = Card.DATA, logScale = true),
                Param.float("pull", "How much a flash pulls", 0.05f, 1f, 0.5f, card = Card.MOTION),
                Param.float("speed", "Speed", 0.3f, 4f, 1f, card = Card.MOTION, logScale = true)
            ),
            create = { FirefliesSim() }
        )
    }
}
