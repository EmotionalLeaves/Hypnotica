package app.hypnotica.sims.math

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
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The martingale: bet on red, and after every loss double the bet, so the
 * first win pays back everything lost plus one. It cannot lose — and every
 * line here climbs, one chip at a time, steady as a staircase. Until a run of
 * reds that is long enough, which always comes, asks for a bet bigger than
 * the whole pile. Each gambler's money is a line; the house edge of a
 * single green zero does the rest. The last one standing goes too.
 */
class MartingaleSim : Sim {

    private var n = 30
    private var bank = IntArray(0)
    private var bet = IntArray(0)
    private var bustAt = IntArray(0)
    private var peak = IntArray(0)
    private var hist = Array(0) { IntArray(0) }
    private var histLen = 0
    private var stride = 1
    private var bets = 0
    private var standing = 0
    private var budget = 0f
    private var lastBust = -1
    private var bustGlow = 0f
    private var top = 200
    private var startPile = 100
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private val px = FloatArray(HIST)
    private val py = FloatArray(HIST)

    override fun setup(env: SimEnv) {
        n = env.params.i("gamblers", 30).coerceIn(1, MAX_N)
        val start = env.params.i("stake", 100).coerceIn(4, 10_000)
        bank = IntArray(n) { start }
        bet = IntArray(n) { 1 }
        bustAt = IntArray(n) { -1 }
        peak = IntArray(n) { start }
        hist = Array(n) { IntArray(HIST) }
        for (g in 0 until n) hist[g][0] = start
        histLen = 1; stride = 1
        bets = 0; standing = n; budget = 0f; lastBust = -1; bustGlow = 0f
        top = start * 2
        startPile = start
        elapsed = 0f; doneAt = -1f; payoffFrame = false
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val a = abs(dt)
        elapsed += a
        bustGlow = max(0f, bustGlow - a * 1.5f)
        if (standing == 0) return
        val speed = env.params.f("speed", 1f).coerceIn(0.1f, 6f)
        val rate = min(MAX_RATE, START_RATE * exp(elapsed * speed / ACCEL_S))
        budget += a * rate * speed
        var k = 0
        while (budget >= 1f && standing > 0 && k < MAX_PER_STEP) {
            budget -= 1f
            spin(env, k == 0)
            k++
        }
    }

    /** One spin of the wheel for everyone still at the table. */
    private fun spin(env: SimEnv, audible: Boolean) {
        bets++
        var wins = 0
        for (g in 0 until n) {
            if (bustAt[g] >= 0) continue
            // Eighteen reds in thirty-seven pockets: the zero is the house's.
            val red = env.rng.int(0, 37) < 18
            if (red) {
                bank[g] += bet[g]; bet[g] = 1; wins++
                peak[g] = max(peak[g], bank[g])
            } else {
                bank[g] -= bet[g]
                val next = bet[g] * 2
                if (next > bank[g]) {
                    // The run of reds that was always coming: the next bet is
                    // more than what is left.
                    bustAt[g] = bets
                    standing--
                    lastBust = g
                    bustGlow = 1f
                    env.emit(EventKind.DEATH, 0.2f + 0.6f * g / max(1, n - 1).toFloat(), (g.toFloat() / n * 2f - 1f), 0.6f)
                    if (standing == 0 && doneAt < 0f) {
                        doneAt = elapsed
                        payoffFrame = true
                        env.emit(EventKind.DONE, 0.9f, 0f, 1f)
                    }
                } else bet[g] = next
            }
            top = max(top, bank[g])
        }
        if (audible && wins > 0) env.emit(EventKind.STEP, 0.55f, 0f, 0.12f)
        // The history, thinned by halves when it fills so the whole run fits.
        if (bets % stride == 0) {
            if (histLen >= HIST) {
                for (g in 0 until n) for (i in 0 until HIST / 2) hist[g][i] = hist[g][i * 2]
                histLen = HIST / 2
                stride *= 2
            }
            for (g in 0 until n) hist[g][histLen] = if (bustAt[g] >= 0) 0 else bank[g]
            histLen++
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        val hw = env.halfWidth * 0.92f
        val y0 = -0.85f; val y1 = 0.85f
        g.line(-hw, y0, hw, y0, 0.003f, Palette.TEXT_SECONDARY, 0.4f)
        // Where everyone started: every line that ends ends below it.
        val sy = yAt(startPile, y0, y1)
        var dx = -hw
        while (dx < hw) { g.line(dx, sy, min(hw, dx + 0.025f), sy, 0.0025f, Palette.TEXT_SECONDARY, 0.35f); dx += 0.05f }
        for (gm in 0 until n) {
            val c = pal.at(gm / max(1, n - 1).toFloat())
            val dead = bustAt[gm] >= 0
            val k = histLen
            for (i in 0 until k) {
                px[i] = xAt(i.toFloat(), hw)
                py[i] = yAt(hist[gm][i], y0, y1)
            }
            g.polyline(px, py, k, if (dead) 0.003f else 0.0045f, c, if (dead) 0.4f else 0.9f)
            if (!dead && k > 0) g.disc(px[k - 1], py[k - 1], 0.008f, Palette.brighten(c, 0.4f))
        }
        // A red mark on the floor where each gambler went under, so the end
        // reads as what it is — a row of busts — rather than an empty chart
        // with the last line fading out.
        for (gm in 0 until n) {
            val b = bustAt[gm]
            if (b < 0) continue
            val x = xAt(min((HIST - 1).toFloat(), b.toFloat() / stride), hw)
            g.disc(x, y0, 0.011f, DANGER, 0.85f)
            g.line(x, y0, x, y0 + 0.03f, 0.003f, DANGER, 0.6f)
        }
        if (lastBust >= 0 && bustGlow > 0f) {
            val x = xAt((histLen - 1).toFloat(), hw)
            g.glow(x, y0, 0.2f, DANGER, bustGlow * 0.6f)
        }
    }

    /**
     * Both axes on square-root scales. On straight ones the last gambler's
     * long climb took the whole chart and every other line, and every bust,
     * was crushed into a sliver at the bottom left by the end.
     */
    private fun xAt(i: Float, hw: Float): Float = -hw + 2f * hw * sqrt(i / (HIST - 1).toFloat())

    private fun yAt(v: Int, y0: Float, y1: Float): Float = y0 + (y1 - y0) * sqrt(max(0, v).toFloat() / top.toFloat())

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && elapsed - doneAt > 3.5f

    override fun scoreForBest(): Float = top.toFloat()
    override fun scoreForBestText(value: Float): String = "a gambler reached ${value.toInt()}"

    override fun counters(): List<Pair<String, String>> = listOf(
        "spins" to "$bets",
        "still at the table" to "$standing of $n",
        "richest ever" to "$top"
    )

    companion object {
        const val MAX_N = 200
        const val HIST = 240
        const val START_RATE = 3f
        const val MAX_RATE = 1500f
        const val ACCEL_S = 3.2f
        const val MAX_PER_STEP = 100
        val DANGER = 0xFFFF5470.toInt()

        fun def(): SimDef = SimDef(
            id = "math/martingale",
            title = "Double or nothing",
            shelf = Shelf.MATH,
            family = "Probability & statistics",
            blurb = "Double the bet after every loss and you can't lose. Every line climbs — until it doesn't.",
            formats = listOf(TrapFormat.LAST_STANDING, TrapFormat.SNAP, TrapFormat.NUMBER_UP),
            heroLabel = "New table",
            defaultPalette = "Spectrum",
            typicalSeconds = 22f,
            params = listOf(
                Param.int("gamblers", "Gamblers", 1, MAX_N, 30, card = Card.DATA, logScale = true),
                Param.int("stake", "Starting pile", 4, 10_000, 100, card = Card.DATA, logScale = true),
                Param.float("speed", "Speed", 0.3f, 4f, 1f, card = Card.MOTION, logScale = true)
            ),
            create = { MartingaleSim() }
        )
    }
}
