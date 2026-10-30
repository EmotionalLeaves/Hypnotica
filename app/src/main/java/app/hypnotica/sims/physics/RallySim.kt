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
import app.hypnotica.sims.algorithms.SegDigits
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Two paddles, one ball, first to so many points — held upright, as a phone
 * is, so the rally runs top to bottom.
 *
 * Each paddle reads where the ball will cross its line, walls folded in, and
 * goes for it at its own top speed with its own small misjudgement; the ball
 * gets a little faster on every return. Neither side is told to lose. The
 * longer the rally, the higher the note, until somebody is a fraction late.
 */
class RallySim : Sim {

    private var wall = 0.42f
    private var target = 3
    private var bx = 0f
    private var by = 0f
    private var vx = 0f
    private var vy = 0f
    private var speedUp = 1f
    private var rally = 0
    private var longest = 0

    private val px = FloatArray(2)
    private val reach = FloatArray(2)
    private val misjudge = FloatArray(2)
    private val bias = FloatArray(2)
    private val score = IntArray(2)
    private val hitGlow = FloatArray(2)
    private var serveIn = 0f
    private var server = 0
    private var goalFlash = 0f
    private var goalSide = 0

    private val trailX = FloatArray(TRAIL)
    private val trailY = FloatArray(TRAIL)
    private var trailHead = 0
    private var trailTick = 0f

    private var elapsed = 0f
    private var doneAt = -1f
    private var won = -1
    private var payoffFrame = false

    override fun setup(env: SimEnv) {
        target = env.params.i("points", 3).coerceIn(1, 21)
        wall = env.halfWidth * 0.92f
        px[0] = 0f; px[1] = 0f
        score[0] = 0; score[1] = 0
        // Two players, never quite equal: one a touch quicker, the other a
        // touch surer, decided fresh each game.
        for (s in 0..1) bias[s] = env.rng.range(-0.18f, 0.18f)
        server = env.rng.int(0, 2)
        rally = 0; longest = 0
        elapsed = 0f; doneAt = -1f; won = -1; payoffFrame = false
        goalFlash = 0f
        serve(env, 0.6f)
    }

    private fun serve(env: SimEnv, delay: Float) {
        bx = 0f; by = 0f
        val a = env.rng.range(-0.7f, 0.7f)
        val dir = if (server == 0) -1f else 1f
        vx = sin(a); vy = cos(a) * dir
        speedUp = 1f
        rally = 0
        serveIn = delay
        for (k in 0 until TRAIL) { trailX[k] = bx; trailY[k] = by }
        for (s in 0..1) aim(env, s)
    }

    /** Where this side means to meet the ball, with its own error. */
    private fun aim(env: SimEnv, side: Int) {
        val skill = env.params.f("skill", 0.6f).coerceIn(0f, 1f)
        // A faster ball is harder to read: the error grows with it, which is
        // what ends a rally that skill alone would keep going for ever.
        val pace = Math.pow(speedUp.toDouble(), 2.2).toFloat()
        misjudge[side] = env.rng.normal() * (0.25f + (1f - skill)) * PADDLE_HALF * 0.6f * pace
        reach[side] = PADDLE_SPEED * (0.85f + 0.5f * skill) * (1f + bias[side])
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val a = abs(dt)
        elapsed += a
        goalFlash = max(0f, goalFlash - a * 1.5f)
        for (s in 0..1) hitGlow[s] = max(0f, hitGlow[s] - a * 3f)
        if (won >= 0) return
        val speed = env.params.f("speed", 1f).coerceIn(0.1f, 5f)

        if (serveIn > 0f) {
            serveIn -= a * speed
            settlePaddles(a * speed)
            return
        }

        val v = BALL_SPEED * speedUp * speed
        val slices = kotlin.math.ceil(v * a / 0.02f).toInt().coerceIn(1, 32)
        val h = a / slices
        repeat(slices) { if (won < 0 && serveIn <= 0f) advance(env, h, v) }
        movePaddles(a * speed)

        trailTick += a
        if (trailTick >= 1f / 60f) {
            trailTick = 0f
            trailHead = (trailHead + 1) % TRAIL
            trailX[trailHead] = bx; trailY[trailHead] = by
        }
    }

    private fun advance(env: SimEnv, h: Float, v: Float) {
        val len = sqrt(vx * vx + vy * vy).coerceAtLeast(1e-6f)
        vx /= len; vy /= len
        bx += vx * v * h
        by += vy * v * h
        if (bx < -wall) { bx = -wall; vx = abs(vx); env.emit(EventKind.STEP, 0.3f, -1f, 0.15f) }
        if (bx > wall) { bx = wall; vx = -abs(vx); env.emit(EventKind.STEP, 0.3f, 1f, 0.15f) }
        // Side 0 is at the bottom, side 1 at the top.
        for (s in 0..1) {
            val lineY = if (s == 0) -PADDLE_Y else PADDLE_Y
            val coming = if (s == 0) vy < 0f && by <= lineY else vy > 0f && by >= lineY
            if (!coming) continue
            if (abs(bx - px[s]) <= PADDLE_HALF + BALL_R) {
                // Returned: the angle from where on the paddle it struck.
                val off = ((bx - px[s]) / PADDLE_HALF).coerceIn(-1f, 1f)
                val ang = off * MAX_ANGLE
                vx = sin(ang)
                vy = cos(ang) * (if (s == 0) 1f else -1f)
                by = lineY
                rally++
                longest = max(longest, rally)
                if (rally == 10 || rally == 20 || rally % 50 == 0) env.callout("Rally ×$rally!", big = rally >= 20)
                // A save off the very edge of the paddle.
                else if (abs(off) > 0.9f && rally > 2) env.callout("Edge save!")
                speedUp = min(MAX_SPEEDUP, speedUp * SPEEDUP)
                hitGlow[s] = 1f
                env.emit(EventKind.HIT, (0.15f + rally * 0.05f).coerceAtMost(1f), (px[s] / wall).coerceIn(-1f, 1f), 0.5f)
                aim(env, 1 - s)
            } else if (abs(by) > abs(lineY) + 0.06f) {
                // Through: a point to the other side.
                val other = 1 - s
                score[other]++
                goalFlash = 1f
                goalSide = s
                env.emit(EventKind.CROSS, 0.4f + 0.1f * score[other], 0f, 0.8f)
                if (score[other] >= target) {
                    won = other
                    doneAt = elapsed
                    payoffFrame = true
                    env.emit(EventKind.DONE, 0.9f, 0f, 1f)
                    env.callout("Game!", big = true)
                } else {
                    when {
                        score[other] == target - 1 && score[s] == target - 1 -> env.callout("Deciding point!")
                        score[other] == target - 1 -> env.callout("Game point!")
                        else -> env.callout("Point!")
                    }
                    server = s
                    serve(env, SERVE_PAUSE)
                }
                return
            }
        }
    }

    private fun movePaddles(a: Float) {
        for (s in 0..1) {
            val toward = if (s == 0) vy < 0f else vy > 0f
            val goal = if (toward) {
                val lineY = if (s == 0) -PADDLE_Y else PADDLE_Y
                val t = (lineY - by) / vy
                var x = bx + vx * t
                val span = 2f * wall
                var u = (x + wall) % (2f * span)
                if (u < 0f) u += 2f * span
                x = if (u <= span) u - wall else 2f * span - u - wall
                x + misjudge[s]
            } else px[s] * 0.97f
            val lim = reach[s] * a
            px[s] += (goal - px[s]).coerceIn(-lim, lim)
            px[s] = px[s].coerceIn(-wall + PADDLE_HALF * 0.5f, wall - PADDLE_HALF * 0.5f)
        }
    }

    private fun settlePaddles(a: Float) {
        for (s in 0..1) px[s] += (0f - px[s]) * min(1f, a * 3f)
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val c0 = side(env, 0)
        val c1 = side(env, 1)
        // The net.
        var x = -wall
        while (x < wall) {
            g.rect(x + 0.012f, 0f, 0.012f, 0.003f, Palette.TEXT_SECONDARY, 0f, 0.35f)
            x += 0.05f
        }
        // Scores, big and faint, in each half.
        digits(g, score[1], 0.42f, 0.22f, c1)
        digits(g, score[0], -0.42f, 0.22f, c0)
        if (goalFlash > 0f) {
            val y = if (goalSide == 0) -0.95f else 0.95f
            g.glow(0f, y, wall * 1.6f, side(env, 1 - goalSide), goalFlash * 0.5f)
        }
        // The ball's trail, then the ball.
        val bc = Palette.TEXT_PRIMARY
        for (j in 1 until TRAIL) {
            val p = (trailHead - j + TRAIL) % TRAIL
            val q = (trailHead - j + 1 + TRAIL) % TRAIL
            val f = 1f - j / TRAIL.toFloat()
            g.line(trailX[p], trailY[p], trailX[q], trailY[q], BALL_R * 1.4f * f, if (vy > 0f) c0 else c1, 0.4f * f)
        }
        if (won < 0) {
            g.glow(bx, by, BALL_R * 5f, bc, 0.3f + 0.2f * (speedUp - 1f))
            g.disc(bx, by, BALL_R, bc)
        }
        // Paddles, glowing as they strike.
        g.rect(px[0], -PADDLE_Y - 0.014f, PADDLE_HALF, 0.012f, c0)
        g.rect(px[1], PADDLE_Y + 0.014f, PADDLE_HALF, 0.012f, c1)
        if (hitGlow[0] > 0f) g.glow(px[0], -PADDLE_Y, PADDLE_HALF * 1.8f, c0, hitGlow[0] * 0.5f)
        if (hitGlow[1] > 0f) g.glow(px[1], PADDLE_Y, PADDLE_HALF * 1.8f, c1, hitGlow[1] * 0.5f)
        if (won >= 0) {
            val y = if (won == 0) -0.42f else 0.42f
            g.glow(0f, y, 0.5f, side(env, won), 0.4f)
        }
    }

    private fun digits(g: G, n: Int, y: Float, h: Float, c: Int) {
        val k = if (n >= 10) 2 else 1
        val w = h * SegDigits.ASPECT * 1.25f
        var x = (k - 1) * w * 0.5f
        var v = n
        repeat(k) {
            SegDigits.digit(g, x, y, h, v % 10, c, 0.22f)
            v /= 10
            x -= w
        }
    }

    private fun side(env: SimEnv, s: Int): Int = env.palette.at(if (s == 0) 0.12f else 0.62f)

    override fun contenders(env: SimEnv): IntArray = intArrayOf(side(env, 0), side(env, 1))

    override fun winner(): Int = won

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && elapsed - doneAt > 3f

    override fun scoreForBest(): Float = longest.toFloat()
    override fun scoreForBestText(value: Float): String = "a rally of ${value.toInt()}"

    override fun counters(): List<Pair<String, String>> = listOf(
        "score" to "${score[0]} – ${score[1]}",
        "rally" to "$rally",
        "longest" to "$longest"
    )

    companion object {
        const val TRAIL = 10
        const val BALL_R = 0.016f
        const val BALL_SPEED = 1.25f
        const val SPEEDUP = 1.16f
        const val MAX_SPEEDUP = 2.2f
        const val PADDLE_Y = 0.86f
        const val PADDLE_HALF = 0.075f
        const val PADDLE_SPEED = 0.9f
        const val MAX_ANGLE = 0.95f
        const val SERVE_PAUSE = 0.6f

        fun def(): SimDef = SimDef(
            id = "physics/rally",
            title = "Rally",
            shelf = Shelf.PHYSICS,
            family = "Races & competitions",
            blurb = "Two paddles that read the ball, and a ball that gets faster with every return.",
            formats = listOf(TrapFormat.GUESS, TrapFormat.SONG, TrapFormat.SNAP),
            heroLabel = "New match",
            defaultPalette = "Ember",
            typicalSeconds = 30f,
            params = listOf(
                Param.int("points", "Points to win", 1, 21, 3, card = Card.DATA),
                Param.float("skill", "How good they are", 0f, 1f, 0.6f, card = Card.MOTION),
                Param.float("speed", "Speed", 0.3f, 3f, 1f, card = Card.MOTION, logScale = true)
            ),
            create = { RallySim() }
        )
    }
}
