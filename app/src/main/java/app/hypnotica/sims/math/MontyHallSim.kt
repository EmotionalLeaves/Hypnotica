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

/**
 * The Monty Hall problem, played a thousand times by two players at once.
 *
 * A car behind one door, goats behind the rest. Both players pick the same
 * door; the host, who knows, opens every other door but one, all goats. One
 * player sticks, one switches. Most people say it makes no difference. Played
 * once it proves nothing — so it is played again and again, faster and
 * faster, and the two win rates are drawn as they settle: sticking on one in
 * three, switching on two in three. With more doors, switching wins more.
 */
class MontyHallSim : Sim {

    private var doors = 3
    private var target = 1000
    private var games = 0
    private var stickWins = 0
    private var switchWins = 0
    private var car = 0
    private var pick = 0
    private var other = 0
    private var phase = 0f
    private var budget = 0f
    private var rate = 1f
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var winner = -1

    private val histStick = FloatArray(HIST)
    private val histSwitch = FloatArray(HIST)
    private val histX = FloatArray(HIST)
    private val histYs = FloatArray(HIST)
    private val histYw = FloatArray(HIST)
    private var histN = 0
    private var flashStick = 0f
    private var flashSwitch = 0f

    override fun setup(env: SimEnv) {
        doors = env.params.i("doors", 3).coerceIn(3, MAX_DOORS)
        target = env.params.i("games", 1000).coerceIn(10, 1_000_000)
        games = 0; stickWins = 0; switchWins = 0
        phase = 0f; budget = 0f; rate = START_RATE
        histN = 0
        flashStick = 0f; flashSwitch = 0f
        elapsed = 0f; doneAt = -1f; payoffFrame = false; winner = -1
        deal(env)
    }

    /** A new game: the car hidden, the pick made, the host's one closed door chosen. */
    private fun deal(env: SimEnv) {
        car = env.rng.int(0, doors)
        pick = env.rng.int(0, doors)
        // The host leaves shut the car's door — or, if the pick is the car, a
        // goat chosen at random.
        other = if (pick != car) car else {
            var o = env.rng.int(0, doors - 1)
            if (o >= pick) o++
            o
        }
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val a = abs(dt)
        elapsed += a
        flashStick = max(0f, flashStick - a * 3f)
        flashSwitch = max(0f, flashSwitch - a * 3f)
        if (doneAt >= 0f && elapsed - doneAt > 0.01f && games >= target) return
        val speed = env.params.f("speed", 1f).coerceIn(0.1f, 6f)
        // Slow enough to follow at first, then faster and faster.
        rate = min(MAX_RATE, START_RATE * exp(elapsed * speed / ACCEL_S))
        if (rate < SLOW_RATE) {
            phase += a * rate * speed
            if (phase >= 1f) { phase = 0f; finish(env, true); if (games < target) deal(env) }
        } else {
            phase = 1f
            budget += a * rate * speed
            var n = 0
            while (budget >= 1f && games < target && n < MAX_PER_STEP) {
                budget -= 1f
                deal(env)
                finish(env, n == 0)
                n++
            }
        }
    }

    private fun finish(env: SimEnv, audible: Boolean) {
        games++
        val stick = pick == car
        if (stick) stickWins++ else switchWins++
        if (audible) {
            if (stick) { flashStick = 1f; env.emit(EventKind.HIT, 0.25f, -0.5f, 0.4f) }
            else { flashSwitch = 1f; env.emit(EventKind.HIT, 0.7f, 0.5f, 0.4f) }
        }
        // The running rates, kept at evenly spaced games for the graph.
        val every = max(1, target / HIST)
        if (games % every == 0 && histN < HIST) {
            histStick[histN] = stickWins.toFloat() / games
            histSwitch[histN] = switchWins.toFloat() / games
            histN++
        }
        if (games >= target && doneAt < 0f) {
            doneAt = elapsed
            payoffFrame = true
            winner = if (switchWins >= stickWins) 1 else 0
            env.emit(EventKind.DONE, 0.9f, 0f, 1f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        val cStick = pal.at(STICK_HUE)
        val cSwitch = pal.at(SWITCH_HUE)
        val hw = env.halfWidth * 0.9f

        // The doors, in a row near the top.
        val dw = min(0.16f, 2f * hw / (doors * 1.25f))
        val dh = min(0.22f, dw * 1.5f)
        val y = 0.62f
        val reveal = ((phase - 0.35f) / 0.3f).coerceIn(0f, 1f)
        val final = ((phase - 0.75f) / 0.2f).coerceIn(0f, 1f)
        for (d in 0 until doors) {
            val x = -hw + (d + 0.5f) * (2f * hw / doors)
            val opened = if (d == pick || d == other) final else reveal
            // Behind the door: the car glows, a goat is a grey lump.
            if (d == car) {
                g.glow(x, y, dw * 1.4f, Palette.SUCCESS, 0.5f * opened)
                g.disc(x, y - dh * 0.15f, dw * 0.32f, Palette.SUCCESS, opened)
            } else {
                g.disc(x, y - dh * 0.2f, dw * 0.25f, Palette.TEXT_SECONDARY, 0.6f * opened)
            }
            // The door itself slides up as it opens.
            val left = 1f - opened
            if (left > 0.01f) g.rect(x, y + dh * 0.5f * (1f - left), dw * 0.5f, dh * 0.5f * left, DOOR)
            g.rect(x, y - dh * 0.5f - 0.006f, dw * 0.55f, 0.004f, Palette.TEXT_SECONDARY, 0f, 0.5f)
            if (d == pick) g.ring(x, y + dh * 0.62f, 0.022f, 0.006f, cStick, 0f, 0f, 0.9f)
            if (d == other) g.ring(x, y + dh * 0.62f, 0.022f, 0.006f, cSwitch, 0f, 0f, 0.5f + 0.5f * reveal)
        }

        // The graph: win rate against games played, with the answers ruled in.
        val gx0 = -hw; val gx1 = hw
        val gy0 = -0.8f; val gy1 = 0.25f
        fun gy(v: Float) = gy0 + (gy1 - gy0) * v
        g.line(gx0, gy0, gx1, gy0, 0.003f, Palette.TEXT_SECONDARY, 0.4f)
        val oneIn = 1f / doors
        dashes(g, gx0, gx1, gy(oneIn), cStick)
        dashes(g, gx0, gx1, gy(1f - oneIn), cSwitch)
        if (histN >= 2) {
            for (i in 0 until histN) {
                histX[i] = gx0 + (gx1 - gx0) * (i + 1f) / HIST
                histYs[i] = gy(histStick[i]); histYw[i] = gy(histSwitch[i])
            }
            g.polyline(histX, histYs, histN, 0.007f, cStick)
            g.polyline(histX, histYw, histN, 0.007f, cSwitch)
        }
        // Now, as two bars at the right edge of the graph's top.
        if (games > 0) {
            val s = stickWins.toFloat() / games
            val w = switchWins.toFloat() / games
            g.rect(-hw * 0.5f, gy1 + 0.1f, hw * 0.45f * s, 0.018f, cStick, 0f, 0.7f + 0.3f * flashStick)
            g.rect(hw * 0.5f, gy1 + 0.1f, hw * 0.45f * w, 0.018f, cSwitch, 0f, 0.7f + 0.3f * flashSwitch)
        }
        if (doneAt >= 0f) {
            val p = ((elapsed - doneAt) / 1.2f).coerceIn(0f, 1f)
            g.glow(gx1, gy(1f - oneIn), 0.25f, cSwitch, 0.5f * (1f - p))
        }
    }

    private fun dashes(g: G, x0: Float, x1: Float, y: Float, c: Int) {
        var x = x0
        while (x < x1) {
            g.line(x, y, min(x1, x + 0.025f), y, 0.003f, c, 0.45f)
            x += 0.045f
        }
    }

    override fun contenders(env: SimEnv): IntArray = intArrayOf(env.palette.at(STICK_HUE), env.palette.at(SWITCH_HUE))

    override fun winner(): Int = winner

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && elapsed - doneAt > 3.5f

    override fun counters(): List<Pair<String, String>> {
        val n = max(1, games)
        return listOf(
            "games" to "$games",
            "sticking wins" to String.format(java.util.Locale.US, "%.1f%%", stickWins * 100f / n),
            "switching wins" to String.format(java.util.Locale.US, "%.1f%%", switchWins * 100f / n)
        )
    }

    companion object {
        const val MAX_DOORS = 10
        const val HIST = 200
        const val START_RATE = 0.9f
        const val SLOW_RATE = 4f
        const val MAX_RATE = 2000f
        const val ACCEL_S = 2.4f
        const val MAX_PER_STEP = 200
        const val STICK_HUE = 0.08f
        const val SWITCH_HUE = 0.55f
        val DOOR = 0xFF3A2E4E.toInt()

        fun def(): SimDef = SimDef(
            id = "math/monty-hall",
            title = "Monty Hall",
            shelf = Shelf.MATH,
            family = "Probability & statistics",
            blurb = "Stick or switch? Two players, a thousand games, and the rates settle where nobody expects.",
            formats = listOf(TrapFormat.GUESS, TrapFormat.NUMBER_UP, TrapFormat.SNAP),
            heroLabel = "Play again",
            defaultPalette = "Spectrum",
            typicalSeconds = 18f,
            params = listOf(
                Param.int("games", "Games", 10, 100_000, 1000, card = Card.DATA, logScale = true),
                Param.int("doors", "Doors", 3, MAX_DOORS, 3, card = Card.DATA),
                Param.float("speed", "Speed", 0.3f, 4f, 1f, card = Card.MOTION, logScale = true)
            ),
            create = { MontyHallSim() }
        )
    }
}
