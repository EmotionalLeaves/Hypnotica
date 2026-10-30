package app.hypnotica.sims.math

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.recipe.ParamInput
import app.hypnotica.core.render.G
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.Shelf
import app.hypnotica.core.sim.Sim
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.sim.SimEnv
import app.hypnotica.core.sim.TrapFormat
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Barnsley's fern. A dot jumps about by one of four stretch-and-turn rules,
 * picked at random — the big one most of the time, the others now and then
 * — and everywhere it lands it leaves a mark. Nothing in the rules says
 * "leaf", "frond" or "stem", yet the marks fill in a fern, every frond a
 * small copy of the whole. Change the numbers a little and it is a
 * different plant.
 */
class FernSim : Sim {

    private var plant = 0
    private var maps = BARNSLEY
    private var cum = FloatArray(4)
    private var limit = 120_000
    private var xs = FloatArray(0)
    private var ys = FloatArray(0)
    private var shade = FloatArray(0)
    private var count = 0
    private var x = 0f
    private var y = 0f
    private var scale = 0.1f
    private var cx = 0f
    private var cy = 0f
    private var minY = 0f
    private var spanY = 1f
    private var carry = 0f
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var voice = 0f

    override fun setup(env: SimEnv) {
        plant = env.params.choiceIndex("plant").coerceIn(0, PLANTS.size - 1)
        maps = PLANTS[plant]
        var acc = 0f
        cum = FloatArray(maps.size) { acc += maps[it][6]; acc }
        limit = env.params.i("dots", 120_000).coerceIn(10_000, MAX_DOTS)
        if (xs.size != limit) { xs = FloatArray(limit); ys = FloatArray(limit); shade = FloatArray(limit) }
        // Its extent found by running the rules a while first, so any plant
        // fills the screen whatever its own coordinates are.
        var px = 0f; var py = 0f
        var x0 = 1e9f; var x1 = -1e9f; var y0 = 1e9f; var y1 = -1e9f
        val probe = app.hypnotica.core.sim.Rng(7L)
        repeat(20_000) { k ->
            val m = pick(probe.nextFloat())
            val nx = m[0] * px + m[1] * py + m[4]
            val ny = m[2] * px + m[3] * py + m[5]
            px = nx; py = ny
            if (k > 20) { x0 = min(x0, px); x1 = max(x1, px); y0 = min(y0, py); y1 = max(y1, py) }
        }
        val hw = env.halfWidth * 0.92f
        scale = min(2f * hw / max(1e-3f, x1 - x0), 1.84f / max(1e-3f, y1 - y0))
        cx = (x0 + x1) * 0.5f; cy = (y0 + y1) * 0.5f
        minY = y0; spanY = max(1e-3f, y1 - y0)
        x = 0f; y = 0f; count = 0; carry = 0f
        elapsed = 0f; doneAt = -1f; payoffFrame = false; voice = 0f
    }

    private fun pick(r: Float): FloatArray {
        for (i in cum.indices) if (r < cum[i]) return maps[i]
        return maps[maps.size - 1]
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val a = abs(dt)
        elapsed += a
        if (count >= limit) return
        // Slowly at first, so the first marks are seen to land at random;
        // then faster and faster as the plant fills in.
        val rate = min(MAX_RATE, START_RATE * exp(elapsed / RAMP_S)) * env.params.f("speed", 1f).coerceIn(0.1f, 5f)
        carry += a * rate
        var k = min(carry.toInt(), limit - count)
        carry -= k
        val rng = env.rng
        var lastMap = 0
        while (k-- > 0) {
            val r = rng.nextFloat()
            var mi = cum.size - 1
            for (i in cum.indices) if (r < cum[i]) { mi = i; break }
            val m = maps[mi]
            val nx = m[0] * x + m[1] * y + m[4]
            val ny = m[2] * x + m[3] * y + m[5]
            x = nx; y = ny
            xs[count] = (x - cx) * scale
            ys[count] = (y - cy) * scale
            shade[count] = ((y - minY) / spanY).coerceIn(0f, 1f)
            count++
            lastMap = mi
        }
        voice += a
        if (voice > 0.12f && count < limit) {
            voice = 0f
            env.emit(EventKind.STEP, shade[max(0, count - 1)], (xs[max(0, count - 1)]).coerceIn(-1f, 1f), if (lastMap == 0) 0.08f else 0.14f)
        }
        if (doneAt < 0f && count >= limit * PAYOFF_SHARE) {
            doneAt = elapsed; payoffFrame = true
            env.emit(EventKind.DONE, 0.8f, 0f, 0.9f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        g.reservePoints(count)
        // Each dot by the height it landed at: the stem dark, the tip light.
        val size = env.params.f("dot", 0.0032f)
        for (i in 0 until count) g.point(xs[i], ys[i], size, pal.at(0.1f + 0.8f * shade[i]))
        // The jumping dot itself, while it is still slow enough to follow.
        if (count in 1..400) g.disc(xs[count - 1], ys[count - 1], 0.012f, pal.at(0.95f))
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && count >= limit && elapsed - doneAt > 5f

    override fun counters(): List<Pair<String, String>> = listOf(
        "dots" to String.format(java.util.Locale.US, "%,d", count),
        "rules" to "${maps.size}"
    )

    companion object {
        const val MAX_DOTS = 400_000
        const val START_RATE = 40f
        const val MAX_RATE = 30_000f
        const val RAMP_S = 1.6f
        const val PAYOFF_SHARE = 0.75f

        /** Each rule: a, b, c, d, e, f, chance — x' = ax + by + e, y' = cx + dy + f. */
        val BARNSLEY = arrayOf(
            floatArrayOf(0f, 0f, 0f, 0.16f, 0f, 0f, 0.01f),
            floatArrayOf(0.85f, 0.04f, -0.04f, 0.85f, 0f, 1.6f, 0.85f),
            floatArrayOf(0.2f, -0.26f, 0.23f, 0.22f, 0f, 1.6f, 0.07f),
            floatArrayOf(-0.15f, 0.28f, 0.26f, 0.24f, 0f, 0.44f, 0.07f)
        )
        val CYCLOSORUS = arrayOf(
            floatArrayOf(0f, 0f, 0f, 0.25f, 0f, -0.4f, 0.02f),
            floatArrayOf(0.95f, 0.005f, -0.005f, 0.93f, -0.002f, 0.5f, 0.84f),
            floatArrayOf(0.035f, -0.2f, 0.16f, 0.04f, -0.09f, 0.02f, 0.07f),
            floatArrayOf(-0.04f, 0.2f, 0.16f, 0.04f, 0.083f, 0.12f, 0.07f)
        )
        val MAPLE = arrayOf(
            floatArrayOf(0.14f, 0.01f, 0f, 0.51f, -0.08f, -1.31f, 0.10f),
            floatArrayOf(0.43f, 0.52f, -0.45f, 0.5f, 1.49f, -0.75f, 0.35f),
            floatArrayOf(0.45f, -0.49f, 0.47f, 0.47f, -1.62f, -0.74f, 0.35f),
            floatArrayOf(0.49f, 0f, 0f, 0.51f, 0.02f, 1.62f, 0.20f)
        )
        val PLANTS = arrayOf(BARNSLEY, CYCLOSORUS, MAPLE)

        fun def(): SimDef = SimDef(
            id = "math/fern",
            title = "Four rules, one fern",
            shelf = Shelf.MATH,
            family = "Fractals & iteration",
            blurb = "A dot hops by four random rules. Nothing says \"leaf\" — and a fern fills in.",
            formats = listOf(TrapFormat.REVEAL, TrapFormat.GROW),
            heroLabel = "Grow again",
            defaultPalette = "Aurora",
            typicalSeconds = 14f,
            params = listOf(
                Param.choice("plant", "Plant", listOf("Barnsley fern", "Mutant fern", "Maple leaf"), 0, card = Card.DATA, input = ParamInput.CHIPS),
                Param.int("dots", "Dots", 10_000, MAX_DOTS, 120_000, card = Card.DATA, logScale = true),
                Param.float("speed", "Speed", 0.2f, 5f, 1f, card = Card.MOTION, logScale = true),
                Param.float("dot", "Dot size", 0.0015f, 0.008f, 0.0032f, card = Card.LOOK)
            ),
            create = { FernSim() }
        )
    }
}
