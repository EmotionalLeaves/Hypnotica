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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * King of the hill: every ball wants the circle in the middle, and a ball
 * scores for every moment it has the circle to itself. Two in it and
 * nobody scores; they knock each other flying instead. First to fill its
 * bar wins.
 */
class KingOfTheHillSim : Sim {

    private var n = 4
    private val x = FloatArray(MAX)
    private val y = FloatArray(MAX)
    private val vx = FloatArray(MAX)
    private val vy = FloatArray(MAX)
    private val score = FloatArray(MAX)
    private val flash = FloatArray(MAX)
    private var r = 0.05f
    private var arena = 0.44f
    private var hill = 0.12f
    private var target = 8f
    private var holder = -1
    private var winner = -1
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var tickClock = 0f
    private var bumps = 0
    private var beat = 0f
    private var heldFor = 0f
    private var matchPoint = false

    override fun setup(env: SimEnv) {
        n = env.params.i("players", 4).coerceIn(2, MAX)
        target = env.params.f("target", 5f).coerceIn(2f, 40f)
        arena = min(env.halfWidth, 1f) * 0.92f
        r = arena * 0.085f
        hill = arena * 0.28f
        val rng = env.rng
        for (i in 0 until n) {
            val a = 2f * PI.toFloat() * i / n + rng.range(-0.2f, 0.2f)
            x[i] = cos(a) * arena * 0.8f; y[i] = sin(a) * arena * 0.8f
            vx[i] = 0f; vy[i] = 0f
            score[i] = 0f; flash[i] = 0f
        }
        holder = -1; winner = -1
        elapsed = 0f; doneAt = -1f; payoffFrame = false; tickClock = 0f; bumps = 0; beat = 0f
        heldFor = 0f; matchPoint = false
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h0 = abs(dt) * env.params.f("pace", 1f).coerceIn(0.2f, 3f)
        elapsed += h0
        val pull = arena * 2.4f * env.params.f("hunger", 1f).coerceIn(0.2f, 3f)
        val rng = env.rng
        val sub = 4
        val h = h0 / sub
        repeat(sub) {
            for (i in 0 until n) {
                // Drawn to the middle, wandering a little as it goes.
                val d = max(1e-4f, sqrt(x[i] * x[i] + y[i] * y[i]))
                if (doneAt < 0f) { vx[i] -= x[i] / d * pull * h; vy[i] -= y[i] / d * pull * h }
                vx[i] += rng.range(-1f, 1f) * arena * 3f * h; vy[i] += rng.range(-1f, 1f) * arena * 3f * h
                vx[i] *= 1f - 0.6f * h; vy[i] *= 1f - 0.6f * h
                // A speed limit: three-way shoves stacked their kicks and the
                // balls flew off to infinity.
                val sp = sqrt(vx[i] * vx[i] + vy[i] * vy[i])
                val cap = arena * MAX_SPEED
                if (sp > cap) { vx[i] *= cap / sp; vy[i] *= cap / sp }
                x[i] += vx[i] * h; y[i] += vy[i] * h
                if (d > arena - r) {
                    val nx = x[i] / d; val ny = y[i] / d
                    val vn = vx[i] * nx + vy[i] * ny
                    if (vn > 0f) { vx[i] -= 2f * vn * nx; vy[i] -= 2f * vn * ny }
                    x[i] = nx * (arena - r); y[i] = ny * (arena - r)
                }
            }
            for (i in 0 until n) for (j in i + 1 until n) {
                val dx = x[j] - x[i]; val dy = y[j] - y[i]
                val d = sqrt(dx * dx + dy * dy)
                if (d < 2f * r && d > 1e-5f) {
                    val nx = dx / d; val ny = dy / d
                    val rel = (vx[j] - vx[i]) * nx + (vy[j] - vy[i]) * ny
                    if (rel < 0f) {
                        // Bouncier than a real collision: shoving is the game.
                        val k = rel * 1.2f - arena * 0.4f
                        vx[i] += k * nx; vy[i] += k * ny; vx[j] -= k * nx; vy[j] -= k * ny
                        flash[i] = 1f; flash[j] = 1f
                        bumps++
                        env.emit(EventKind.HIT, 0.3f + 0.5f * min(1f, abs(rel) / arena), ((x[i] + x[j]) * 0.5f / arena).coerceIn(-1f, 1f), 0.5f)
                    }
                    val push = (2f * r - d) * 0.5f
                    x[i] -= nx * push; y[i] -= ny * push; x[j] += nx * push; y[j] += ny * push
                }
            }
        }
        for (i in 0 until n) flash[i] = max(0f, flash[i] - h0 * 4f)
        if (doneAt >= 0f) return
        // Who has the hill: exactly one ball inside it.
        var inside = -1; var count = 0
        for (i in 0 until n) if (sqrt(x[i] * x[i] + y[i] * y[i]) < hill) { inside = i; count++ }
        val now = if (count == 1) inside else -1
        if (now != holder) {
            // Knocked off after holding it a while: a steal.
            if (holder >= 0 && heldFor > 1.2f && now >= 0) env.callout("Stolen!")
            holder = now
            heldFor = 0f
            if (now >= 0) env.emit(EventKind.CROSS, 0.4f + 0.5f * now / n, 0f, 0.5f)
        }
        if (holder >= 0) heldFor += h0
        if (!matchPoint) for (i in 0 until n) if (score[i] > target * 0.8f) { matchPoint = true; env.callout("Match point!") }
        if (holder >= 0) {
            score[holder] += h0 * SCORE_RATE
            tickClock += h0
            if (tickClock > 0.25f) { tickClock = 0f; env.emit(EventKind.STEP, (score[holder] / target).coerceIn(0f, 1f), 0f, 0.15f) }
            if (score[holder] >= target) {
                winner = holder
                doneAt = elapsed
                payoffFrame = true
                env.emit(EventKind.DONE, 0.9f, 0f, 1f)
                env.callout("King of the hill!", big = true)
            }
        }
        // Close to the line: a heartbeat.
        var top = 0f
        for (i in 0 until n) top = max(top, score[i])
        if (doneAt < 0f && top > target * 0.8f) {
            beat += h0 * 2f
            if (beat >= 1f) { beat -= 1f; env.emit(EventKind.STEP, 0.02f, 0f, 0.5f) }
        } else beat = 0f
    }

    private fun colour(env: SimEnv, i: Int): Int = env.palette.at((i + 0.5f) / n)

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        g.disc(0f, 0f, arena, Palette.lerp(Palette.BG, env.palette.at(0.5f), 0.05f))
        g.ring(0f, 0f, arena, 0.008f, Palette.withAlpha(Palette.TEXT_PRIMARY, 0.6f))
        // The hill, in its holder's colour while held.
        val hc = if (holder >= 0) colour(env, holder) else Palette.TEXT_SECONDARY
        if (holder >= 0) g.glow(0f, 0f, hill * 1.4f, hc, 0.35f)
        g.disc(0f, 0f, hill, Palette.lerp(Palette.BG, hc, if (holder >= 0) 0.3f else 0.08f))
        g.ring(0f, 0f, hill, 0.006f, hc, alpha = 0.9f)
        for (i in 0 until n) {
            val c = colour(env, i)
            g.disc(x[i], y[i], r, if (flash[i] > 0f) Palette.lerp(c, Palette.TEXT_PRIMARY, 0.6f * flash[i]) else c)
            // Its score as a ring filling round it.
            val share = (score[i] / target).coerceIn(0f, 1f)
            if (share > 0f) g.ring(x[i], y[i], r * 1.35f, r * 0.18f, c, (1f - share) * 2f * PI.toFloat(), (PI / 2).toFloat())
        }
        // The race to the line, across the top.
        val bw = 2f * arena / n
        for (i in 0 until n) {
            val c = colour(env, i)
            val cx = -arena + (i + 0.5f) * bw
            val share = (score[i] / target).coerceIn(0f, 1f)
            g.rect(cx, arena + 0.06f, bw * 0.42f, 0.012f, Palette.withAlpha(c, 0.25f))
            if (share > 0f) g.rect(cx - bw * 0.42f * (1f - share), arena + 0.06f, bw * 0.42f * share, 0.012f, c)
        }
        if (winner >= 0) g.glow(x[winner], y[winner], r * 4f, colour(env, winner), (1f - (elapsed - doneAt) / 2f).coerceIn(0.3f, 0.9f))
    }

    override fun frameHalfWidth(env: SimEnv): Float = arena + 0.01f

    override fun contenders(env: SimEnv): IntArray = IntArray(n) { colour(env, it) }

    override fun winner(): Int = winner

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = (doneAt >= 0f && elapsed - doneAt > 4f) || elapsed > 120f

    override fun scoreForBest(): Float = bumps.toFloat()
    override fun scoreForBestText(value: Float): String = "${value.toInt()} shoves"

    override fun counters(): List<Pair<String, String>> =
        (0 until n).map { "player ${it + 1}" to String.format(java.util.Locale.US, "%.1f / %.0f", score[it], target) } +
            listOf("shoves" to "$bumps")

    companion object {
        const val MAX = 6
        /** Arena radii a second. */
        const val MAX_SPEED = 3.5f
        /** Points a second for holding the hill alone. */
        const val SCORE_RATE = 2f

        fun def(): SimDef = SimDef(
            id = "physics/king-of-the-hill",
            title = "King of the hill",
            shelf = Shelf.PHYSICS,
            family = "Battles",
            blurb = "Everyone wants the middle. Hold it alone to score; two in it and they shove. First to fill its bar wins.",
            formats = listOf(TrapFormat.GUESS, TrapFormat.RACE, TrapFormat.COLOR_BATTLE),
            heroLabel = "New round",
            defaultPalette = "Spectrum",
            typicalSeconds = 22f,
            params = listOf(
                Param.int("players", "Players", 2, MAX, 4, card = Card.DATA),
                Param.float("target", "Points to win", 2f, 40f, 5f, card = Card.DATA),
                Param.float("pace", "Pace", 0.3f, 3f, 1f, card = Card.MOTION, logScale = true),
                Param.float("hunger", "Pull to the middle", 0.2f, 3f, 1f, card = Card.MOTION)
            ),
            create = { KingOfTheHillSim() }
        )
    }
}
