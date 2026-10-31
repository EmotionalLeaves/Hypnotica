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
import kotlin.math.E
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The secretary problem: candidates arrive one at a time in random order,
 * each must be hired or turned away on the spot, and only hiring the very
 * best counts. The rule: turn away the first 37% whatever they are like,
 * then hire the first one better than all of them. It finds the best about
 * 37% of the time — 1/e — however many candidates there are, a hundred or a
 * million. Each round the candidates rise as bars; the look-only stretch is
 * shaded; then rounds go faster and the success rate settles on 1/e.
 */
class SecretarySim : Sim {

    private var n = 40
    private var target = 400
    private var cut = 15
    private var score = FloatArray(0)
    private var shown = 0
    private var hired = -1
    private var best = 0
    private var rounds = 0
    private var wins = 0
    private var lastWon = false
    private var phase = 0f
    private var budget = 0f
    private var flash = 0f
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private val hx = FloatArray(HIST)
    private val hy = FloatArray(HIST)
    private val hv = FloatArray(HIST)
    private var hn = 0
    private var every = 1

    override fun setup(env: SimEnv) {
        n = env.params.i("candidates", 40).coerceIn(5, MAX_N)
        target = env.params.i("rounds", 400).coerceIn(10, 100_000)
        cut = (n / E).toInt().coerceAtLeast(1)
        score = FloatArray(n)
        rounds = 0; wins = 0; hn = 0; budget = 0f; flash = 0f
        every = max(1, target / HIST)
        elapsed = 0f; doneAt = -1f; payoffFrame = false
        deal(env)
    }

    private fun deal(env: SimEnv) {
        for (i in 0 until n) score[i] = env.rng.nextFloat()
        best = 0
        for (i in 1 until n) if (score[i] > score[best]) best = i
        // The rule: the best of the first [cut] sets the bar; hire the first to beat it.
        var bar = 0f
        for (i in 0 until cut) bar = max(bar, score[i])
        hired = n - 1
        for (i in cut until n) if (score[i] > bar) { hired = i; break }
        shown = 0
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
            phase += a * rate * speed
            // Candidates walk in one by one, up to the one hired.
            val want = min(n, ((phase / 0.75f) * (hired + 1)).toInt())
            while (shown < want) {
                val i = shown
                env.emit(if (i == hired) EventKind.CROSS else EventKind.STEP, score[i], (i.toFloat() / n * 2f - 1f), if (i == hired) 0.5f else 0.15f)
                shown++
            }
            if (phase >= 1f) { finish(env, true); deal(env) }
        } else {
            budget += a * rate * speed
            var k = 0
            while (budget >= 1f && k < MAX_PER_STEP) {
                budget -= 1f
                deal(env)
                shown = n
                phase = 1f
                finish(env, k == 0)
                k++
            }
        }
    }

    private fun finish(env: SimEnv, audible: Boolean) {
        rounds++
        lastWon = hired == best
        if (lastWon) wins++
        flash = 1f
        if (rounds <= CALLED_ROUNDS) env.callout(if (lastWon) "Hired the best!" else if (hired == n - 1) "Stuck with the last one" else "Not the best…")
        if (audible) env.emit(if (lastWon) EventKind.HIT else EventKind.DEATH, if (lastWon) 0.8f else 0.2f, 0f, 0.4f)
        if (rounds % every == 0) {
            if (hn >= HIST) { for (k in 0 until HIST / 2) hv[k] = hv[k * 2 + 1]; hn = HIST / 2; every *= 2 }
            hv[hn] = wins.toFloat() / rounds; hn++
        }
        if (rounds >= target && doneAt < 0f) {
            doneAt = elapsed
            payoffFrame = true
            env.emit(EventKind.DONE, 0.9f, 0f, 1f)
            env.callout("The best one, ${(100f * wins / max(1, rounds)).roundToInt()}% of the time!", big = true)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        val hw = env.halfWidth * 0.9f
        val bw = 2f * hw / n
        val base = 0.05f
        val top = 0.9f
        // The look-only stretch, shaded.
        g.rect(-hw + cut * bw * 0.5f, (base + top) * 0.5f, cut * bw * 0.5f, (top - base) * 0.5f, Palette.TEXT_SECONDARY, 0f, 0.07f)
        val upto = if (phase >= 1f) n else shown
        for (i in 0 until upto) {
            val x = -hw + (i + 0.5f) * bw
            val h = (top - base) * score[i]
            val c = when {
                i == hired && i == best -> Palette.SUCCESS
                i == hired -> DANGER
                i == best && phase >= 0.9f -> Palette.brighten(pal.at(0.6f), 0.4f)
                i < cut -> Palette.lerp(pal.at(score[i]), Palette.BG, 0.5f)
                else -> pal.at(score[i])
            }
            g.bar(x, base, bw * 0.4f, h, c)
        }
        if (hired in 0 until upto) {
            val x = -hw + (hired + 0.5f) * bw
            g.glow(x, base + (top - base) * score[hired], bw * 3f, if (hired == best) Palette.SUCCESS else DANGER, 0.25f + 0.4f * flash)
        }
        // Success rate against 1/e.
        val gy0 = -0.9f; val gy1 = -0.2f
        fun gy(v: Float) = gy0 + (gy1 - gy0) * (v / 0.7f).coerceIn(0f, 1f)
        g.line(-hw, gy0, hw, gy0, 0.003f, Palette.TEXT_SECONDARY, 0.35f)
        var x = -hw
        while (x < hw) { g.line(x, gy(1f / E.toFloat()), min(hw, x + 0.025f), gy(1f / E.toFloat()), 0.004f, Palette.SUCCESS, 0.5f); x += 0.045f }
        if (hn >= 2) {
            for (i in 0 until hn) { hx[i] = -hw + 2f * hw * (i + 1f) / HIST; hy[i] = gy(hv[i]) }
            g.polyline(hx, hy, hn, 0.007f, pal.at(0.6f))
        }
    }

    override fun payoff(): Boolean = payoffFrame

    /** Endless: past the target it plays on, the count settling ever closer. */
    override fun finished(): Boolean = false

    override fun counters(): List<Pair<String, String>> = listOf(
        "rounds" to "$rounds",
        "hired the best" to String.format(java.util.Locale.US, "%.1f%%", wins * 100f / max(1, rounds)),
        "turned away first" to "$cut of $n"
    )

    companion object {
        const val CALLED_ROUNDS = 4
        const val MAX_N = 400
        const val HIST = 200
        const val START_RATE = 0.5f
        const val SLOW_RATE = 2.5f
        const val MAX_RATE = 2000f
        const val ACCEL_S = 2.5f
        const val MAX_PER_STEP = 150
        val DANGER = 0xFFFF5470.toInt()

        fun def(): SimDef = SimDef(
            id = "math/secretary",
            title = "The 37% rule",
            shelf = Shelf.MATH,
            family = "Probability & statistics",
            blurb = "Turn away the first 37%, then hire the first who beats them all. It finds the best one time in e.",
            formats = listOf(TrapFormat.SNAP, TrapFormat.NUMBER_UP, TrapFormat.GROW),
            heroLabel = "Hire again",
            defaultPalette = "Ocean",
            typicalSeconds = 20f,
            params = listOf(
                Param.int("rounds", "Rounds", 10, 100_000, 400, card = Card.DATA, logScale = true),
                Param.int("candidates", "Candidates", 5, MAX_N, 40, card = Card.DATA, logScale = true),
                Param.float("speed", "Speed", 0.3f, 4f, 1f, card = Card.MOTION, logScale = true)
            ),
            create = { SecretarySim() }
        )
    }
}
