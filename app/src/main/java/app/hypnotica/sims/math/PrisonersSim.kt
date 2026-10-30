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
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The hundred prisoners (Gál and Miltersen, 2003). A hundred boxes hold the
 * prisoners' hundred numbers in random order; each prisoner may open fifty,
 * and all of them must find their own number or all of them lose. Guessing,
 * the chance is one in 2^100 — never. The trick: open the box with your own
 * number on it, then the box numbered with what you found, and so on round
 * the loop. Everyone wins exactly when no loop is longer than fifty, and that
 * happens about 31% of the time: 1 − ln 2, near enough.
 *
 * Each round the boxes are coloured by the loop they belong to and the loops
 * are traced, longest last; then it is played again, faster, and the share of
 * rounds won settles onto 31%.
 */
class PrisonersSim : Sim {

    private var n = 100
    private var target = 300
    private var perm = IntArray(0)
    private var loopOf = IntArray(0)
    private var loopLen = IntArray(0)
    private var loopOrder = IntArray(0)
    private var loops = 0
    private var longest = 0
    private var cols = 10
    private var cell = 0.08f
    private var ox = 0f
    private var oy = 0f

    private var rounds = 0
    private var wins = 0
    private var phase = 0f
    private var budget = 0f
    private var tracedLoops = 0
    private var flash = 0f
    private var lastWon = false
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false

    private val hx = FloatArray(HIST)
    private val hy = FloatArray(HIST)
    private val hv = FloatArray(HIST)
    private var hn = 0
    private var every = 1

    override fun setup(env: SimEnv) {
        n = (env.params.i("boxes", 100) / 2 * 2).coerceIn(4, MAX_N)
        target = env.params.i("rounds", 300).coerceIn(10, 100_000)
        perm = IntArray(n); loopOf = IntArray(n); loopLen = IntArray(n); loopOrder = IntArray(n)
        cols = ceil(sqrt(n.toFloat())).toInt()
        val rowsN = (n + cols - 1) / cols
        val hw = env.halfWidth * 0.9f
        cell = min(2f * hw / cols, 0.9f / rowsN)
        ox = -cols * cell * 0.5f
        oy = 0.95f
        rounds = 0; wins = 0; phase = 0f; budget = 0f; hn = 0; flash = 0f
        every = max(1, target / HIST)
        elapsed = 0f; doneAt = -1f; payoffFrame = false
        deal(env)
    }

    /** A fresh round: shuffle the numbers into the boxes and find the loops. */
    private fun deal(env: SimEnv) {
        for (i in 0 until n) perm[i] = i
        env.rng.shuffle(perm)
        loopOf.fill(-1)
        loops = 0; longest = 0
        for (i in 0 until n) {
            if (loopOf[i] >= 0) continue
            var j = i; var len = 0
            while (loopOf[j] < 0) { loopOf[j] = loops; j = perm[j]; len++ }
            loopLen[loops] = len
            longest = max(longest, len)
            loops++
        }
        // Traced shortest first, so the one that decides it comes last.
        for (k in 0 until loops) loopOrder[k] = k
        for (a in 1 until loops) {
            val v = loopOrder[a]; var b = a - 1
            while (b >= 0 && loopLen[loopOrder[b]] > loopLen[v]) { loopOrder[b + 1] = loopOrder[b]; b-- }
            loopOrder[b + 1] = v
        }
        tracedLoops = 0
        phase = 0f
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val a = abs(dt)
        elapsed += a
        flash = max(0f, flash - a * 2f)
        val speed = env.params.f("speed", 1f).coerceIn(0.1f, 6f)
        val rate = min(MAX_RATE, START_RATE * exp(elapsed * speed / ACCEL_S))
        if (rate < SLOW_RATE) {
            // One round, slowly enough to watch the loops being traced.
            phase += a * rate * speed
            val shown = (phase / 0.8f * loops).toInt().coerceAtMost(loops)
            while (tracedLoops < shown) {
                val len = loopLen[loopOrder[tracedLoops]]
                env.emit(EventKind.WRITE, (len.toFloat() / n).coerceIn(0.05f, 1f), 0f, 0.3f)
                tracedLoops++
            }
            if (phase >= 1f) { finish(env, true); deal(env) }
        } else {
            budget += a * rate * speed
            var k = 0
            while (budget >= 1f && k < MAX_PER_STEP) {
                budget -= 1f
                deal(env)
                tracedLoops = loops
                phase = 1f
                finish(env, k == 0)
                k++
            }
        }
    }

    private fun finish(env: SimEnv, audible: Boolean) {
        rounds++
        lastWon = longest <= n / 2
        if (lastWon) wins++
        flash = 1f
        if (audible) {
            if (lastWon) env.emit(EventKind.CROSS, 0.75f, 0f, 0.5f) else env.emit(EventKind.DEATH, 0.2f, 0f, 0.4f)
        }
        if (rounds % every == 0) {
            if (hn >= HIST) { for (k in 0 until HIST / 2) hv[k] = hv[k * 2 + 1]; hn = HIST / 2; every *= 2 }
            hv[hn] = wins.toFloat() / rounds; hn++
        }
        if (rounds >= target && doneAt < 0f) {
            doneAt = elapsed
            payoffFrame = true
            env.emit(EventKind.DONE, 0.9f, 0f, 1f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        val half = cell * 0.42f
        // The boxes, coloured by loop once that loop has been traced; the
        // longest loop drawn bright, red if it is over half.
        for (i in 0 until n) {
            val x = ox + (i % cols + 0.5f) * cell
            val y = oy - (i / cols + 0.5f) * cell
            val l = loopOf[i]
            var rank = 0
            while (rank < loops && loopOrder[rank] != l) rank++
            val traced = rank < tracedLoops
            val isLongest = loopLen[l] == longest
            val c = when {
                !traced -> BOX
                isLongest && longest > n / 2 -> Palette.lerp(DANGER, Palette.TEXT_PRIMARY, 0.15f)
                isLongest -> Palette.SUCCESS
                else -> Palette.lerp(pal.at((l * 0.137f) % 1f), Palette.BG, 0.35f)
            }
            g.rect(x, y, half, half, c)
        }
        // The loop being traced, as arrows box to box (slow rounds only).
        if (phase < 1f && tracedLoops > 0) {
            val l = loopOrder[tracedLoops - 1]
            var start = 0
            while (start < n && loopOf[start] != l) start++
            if (start < n) {
                var j = start
                val c = if (loopLen[l] > n / 2) DANGER else Palette.TEXT_PRIMARY
                do {
                    val k = perm[j]
                    g.line(ox + (j % cols + 0.5f) * cell, oy - (j / cols + 0.5f) * cell,
                        ox + (k % cols + 0.5f) * cell, oy - (k / cols + 0.5f) * cell, cell * 0.06f, c, 0.6f)
                    j = k
                } while (j != start)
            }
        }
        if (flash > 0f && phase >= 1f) {
            val cy = oy - ((n + cols - 1) / cols) * cell * 0.5f
            g.glow(0f, cy, cols * cell * 0.7f, if (lastWon) Palette.SUCCESS else DANGER, flash * 0.3f)
        }

        // The share of rounds won, settling on 1 - ln 2.
        val hw = env.halfWidth * 0.9f
        val gy0 = -0.9f; val gy1 = -0.25f
        fun gy(v: Float) = gy0 + (gy1 - gy0) * (v / 0.6f).coerceIn(0f, 1f)
        g.line(-hw, gy0, hw, gy0, 0.003f, Palette.TEXT_SECONDARY, 0.35f)
        var x = -hw
        val expect = expected()
        while (x < hw) { g.line(x, gy(expect), min(hw, x + 0.025f), gy(expect), 0.004f, Palette.SUCCESS, 0.5f); x += 0.045f }
        if (hn >= 2) {
            for (i in 0 until hn) { hx[i] = -hw + 2f * hw * (i + 1f) / HIST; hy[i] = gy(hv[i]) }
            g.polyline(hx, hy, hn, 0.007f, pal.at(0.6f))
        }
    }

    /** The exact chance: 1 minus the chance some loop is longer than half. */
    private fun expected(): Float {
        var s = 0.0
        for (k in n / 2 + 1..n) s += 1.0 / k
        return (1.0 - s).toFloat()
    }

    override fun payoff(): Boolean = payoffFrame

    /** Endless: past the target it plays on, the count settling ever closer. */
    override fun finished(): Boolean = false

    override fun counters(): List<Pair<String, String>> = listOf(
        "rounds" to "$rounds",
        "all freed" to String.format(java.util.Locale.US, "%.1f%%", wins * 100f / max(1, rounds)),
        "longest loop" to "$longest of ${n / 2} allowed",
        "guessing would win" to "1 in 2^$n"
    )

    companion object {
        const val MAX_N = 400
        const val HIST = 200
        const val START_RATE = 0.45f
        const val SLOW_RATE = 2.5f
        const val MAX_RATE = 1500f
        const val ACCEL_S = 2.6f
        const val MAX_PER_STEP = 100
        val BOX = 0xFF2A2D38.toInt()
        val DANGER = 0xFFFF5470.toInt()

        fun def(): SimDef = SimDef(
            id = "math/hundred-prisoners",
            title = "A hundred prisoners",
            shelf = Shelf.MATH,
            family = "Probability & statistics",
            blurb = "Each may open half the boxes; all must find their number. Follow the loops and they win 31% of the time.",
            formats = listOf(TrapFormat.SNAP, TrapFormat.NUMBER_UP, TrapFormat.REVEAL),
            heroLabel = "New round",
            defaultPalette = "Spectrum",
            typicalSeconds = 20f,
            params = listOf(
                Param.int("rounds", "Rounds", 10, 100_000, 300, card = Card.DATA, logScale = true),
                Param.int("boxes", "Prisoners", 4, MAX_N, 100, card = Card.DATA),
                Param.float("speed", "Speed", 0.3f, 4f, 1f, card = Card.MOTION, logScale = true)
            ),
            create = { PrisonersSim() }
        )
    }
}
