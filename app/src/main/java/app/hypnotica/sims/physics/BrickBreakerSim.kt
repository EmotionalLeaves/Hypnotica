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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A wall of bricks, a paddle that plays itself and a handful of balls.
 *
 * The paddle does more than catch: it picks a brick still standing and works
 * out where on its face the ball has to land to be sent at it, so as the wall
 * thins the shots get visibly deliberate, and the last brick is hunted down
 * rather than waited for. Behind the wall is a pattern in the palette, and
 * every brick that breaks shows another piece of it.
 */
class BrickBreakerSim : Sim {

    private var cols = 8
    private var rows = 8
    private var left = -0.4f
    private var bw = 0.1f
    private var bh = 0.045f
    private var top = 0.8f
    private var alive = BooleanArray(0)
    private var hitGlow = FloatArray(0)
    private var remaining = 0

    private var n = 3
    private var bx = FloatArray(0)
    private var by = FloatArray(0)
    private var vx = FloatArray(0)
    private var vy = FloatArray(0)
    private var held = FloatArray(0)
    private var trailX = FloatArray(0)
    private var trailY = FloatArray(0)
    private var trailHead = 0
    private var trailTick = 0f

    private var wall = 0.42f
    private var paddleX = 0f
    private var paddleHalf = 0.08f
    private var aimOffset = 0f
    private var misses = 0

    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false

    override fun setup(env: SimEnv) {
        val p = env.params
        rows = p.i("rows", 6).coerceIn(2, MAX_ROWS)
        n = p.i("balls", 6).coerceIn(1, MAX_BALLS)
        wall = env.halfWidth * 0.94f
        cols = max(4, (wall * 2f / 0.105f).toInt())
        bw = wall * 2f / cols
        bh = min(0.05f, 0.9f / rows)
        top = 0.86f
        left = -wall
        alive = BooleanArray(rows * cols) { true }
        hitGlow = FloatArray(rows * cols)
        remaining = rows * cols
        paddleHalf = max(0.06f, wall * 0.2f)
        paddleX = 0f
        aimOffset = 0f
        bx = FloatArray(n); by = FloatArray(n); vx = FloatArray(n); vy = FloatArray(n)
        held = FloatArray(n)
        trailX = FloatArray(n * TRAIL); trailY = FloatArray(n * TRAIL)
        trailHead = 0; trailTick = 0f
        for (i in 0 until n) {
            // Launched one after another, fanned out, so they spread at once.
            bx[i] = paddleX
            by[i] = PADDLE_Y + 0.03f
            val a = (i + 0.5f) / n * 1.6f - 0.8f + env.rng.range(-0.1f, 0.1f)
            vx[i] = sin(a); vy[i] = cos(a)
            held[i] = i * 0.25f
        }
        for (k in 0 until n * TRAIL) { trailX[k] = bx[k / TRAIL]; trailY[k] = by[k / TRAIL] }
        misses = 0
        elapsed = 0f; doneAt = -1f; payoffFrame = false
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val a = abs(dt)
        elapsed += a
        val speed = env.params.f("speed", 1f).coerceIn(0.1f, 6f)
        val v = BALL_SPEED * speed
        for (k in hitGlow.indices) if (hitGlow[k] > 0f) hitGlow[k] = max(0f, hitGlow[k] - a * 2.5f)

        movePaddle(a, speed)

        // In slices short enough that no ball passes through a brick.
        val slices = ceil(v * a / (bh * 0.4f)).toInt().coerceIn(1, 32)
        val h = a / slices
        repeat(slices) {
            for (i in 0 until n) {
                if (held[i] > 0f) {
                    held[i] -= h
                    bx[i] = paddleX; by[i] = PADDLE_Y + 0.03f
                    continue
                }
                advance(env, i, h, v)
            }
        }

        trailTick += a
        if (trailTick >= 1f / 60f) {
            trailTick = 0f
            trailHead = (trailHead + 1) % TRAIL
            for (i in 0 until n) { trailX[i * TRAIL + trailHead] = bx[i]; trailY[i * TRAIL + trailHead] = by[i] }
        }
    }

    private fun advance(env: SimEnv, i: Int, h: Float, v: Float) {
        // Speed held constant: only the direction is the ball's own.
        val len = sqrt(vx[i] * vx[i] + vy[i] * vy[i]).coerceAtLeast(1e-6f)
        var dx = vx[i] / len
        var dy = vy[i] / len
        // Never so flat that it shuttles side to side for ever.
        if (abs(dy) < MIN_RISE) { dy = if (dy < 0f) -MIN_RISE else MIN_RISE; dx = sqrt(1f - dy * dy) * (if (dx < 0f) -1f else 1f) }
        vx[i] = dx; vy[i] = dy
        val ox = bx[i]; val oy = by[i]
        var nx = ox + dx * v * h
        var ny = oy + dy * v * h

        if (nx < -wall) { nx = -wall; vx[i] = abs(vx[i]); env.emit(EventKind.STEP, 0.2f, -1f, 0.12f) }
        if (nx > wall) { nx = wall; vx[i] = -abs(vx[i]); env.emit(EventKind.STEP, 0.2f, 1f, 0.12f) }
        if (ny > CEILING) { ny = CEILING; vy[i] = -abs(vy[i]); env.emit(EventKind.STEP, 0.3f, 0f, 0.12f) }

        // The wall of bricks: the cell it has moved into, and from which side.
        val c1 = cellCol(nx); val r1 = cellRow(ny)
        if (r1 in 0 until rows && c1 in 0 until cols && alive[r1 * cols + c1]) {
            val c0 = cellCol(ox); val r0 = cellRow(oy)
            if (c0 != c1) vx[i] = -vx[i]
            if (r0 != r1 || c0 == c1) vy[i] = -vy[i]
            nx = ox; ny = oy
            breakBrick(env, r1, c1)
        }

        // The paddle.
        if (vy[i] < 0f && oy >= PADDLE_Y && ny < PADDLE_Y && abs(nx - paddleX) <= paddleHalf + BALL_R) {
            val off = ((nx - paddleX) / paddleHalf).coerceIn(-1f, 1f)
            val ang = off * MAX_ANGLE
            vx[i] = sin(ang); vy[i] = cos(ang)
            ny = PADDLE_Y
            env.emit(EventKind.HIT, 0.08f, (paddleX / wall).coerceIn(-1f, 1f), 0.3f)
            chooseAim(env)
        }

        // Missed: back on the paddle for another go.
        if (ny < -1.02f) {
            misses++
            held[i] = 0.4f
            nx = paddleX; ny = PADDLE_Y + 0.03f
            val a = env.rng.range(-0.5f, 0.5f)
            vx[i] = sin(a); vy[i] = cos(a)
            env.emit(EventKind.DEATH, 0.15f, 0f, 0.3f)
        }
        bx[i] = nx; by[i] = ny
    }

    private fun breakBrick(env: SimEnv, r: Int, c: Int) {
        val k = r * cols + c
        alive[k] = false
        hitGlow[k] = 1f
        remaining--
        // Higher rows ring higher.
        env.emit(EventKind.HIT, 0.25f + 0.7f * (rows - 1 - r).toFloat() / max(1, rows - 1), ((left + (c + 0.5f) * bw) / wall).coerceIn(-1f, 1f), 0.55f)
        if (remaining in 1..3 && doneAt < 0f) env.callout("$remaining left!")
        if (remaining == 0 && doneAt < 0f) {
            doneAt = elapsed
            payoffFrame = true
            env.callout("CLEARED!", big = true)
            env.emit(EventKind.DONE, 0.9f, 0f, 1f)
        }
    }

    /**
     * Where on the paddle the next ball should land: picked so the bounce
     * heads for one brick still standing. With the wall nearly gone this is
     * what finds the last few, rather than leaving them to chance.
     */
    private fun chooseAim(env: SimEnv) {
        if (remaining <= 0) { aimOffset = env.rng.range(-0.5f, 0.5f) * paddleHalf; return }
        var pick = env.rng.int(0, remaining)
        var k = 0
        while (k < alive.size) {
            if (alive[k]) { if (pick == 0) break; pick-- }
            k++
        }
        if (k >= alive.size) return
        val tx = left + (k % cols + 0.5f) * bw
        val ty = top - (k / cols + 0.5f) * bh
        // The ball lands about where the paddle is now; aim from there.
        val ang = atan2(tx - paddleX, ty - PADDLE_Y).coerceIn(-MAX_ANGLE, MAX_ANGLE)
        aimOffset = ang / MAX_ANGLE * paddleHalf * 0.9f
    }

    /** Under the lowest ball on its way down, offset so it bounces where it was aimed. */
    private fun movePaddle(a: Float, speed: Float) {
        var best = -1
        var bestT = Float.MAX_VALUE
        for (i in 0 until n) {
            if (held[i] > 0f || vy[i] >= 0f) continue
            val t = (by[i] - PADDLE_Y) / -vy[i]
            if (t < bestT) { bestT = t; best = i }
        }
        val goal = if (best < 0) 0f else {
            // Where it will cross the paddle's line, walls folded in.
            var x = bx[best] + vx[best] / abs(vy[best]) * (by[best] - PADDLE_Y)
            val span = 2f * wall
            var u = (x + wall) % (2f * span)
            if (u < 0f) u += 2f * span
            x = if (u <= span) u - wall else 2f * span - u - wall
            x - aimOffset
        }
        val lim = PADDLE_SPEED * speed * a
        paddleX += (goal - paddleX).coerceIn(-lim, lim)
        paddleX = paddleX.coerceIn(-wall + paddleHalf, wall - paddleHalf)
    }

    private fun cellCol(x: Float): Int = kotlin.math.floor((x - left) / bw).toInt()
    private fun cellRow(y: Float): Int = if (y > top) -1 else kotlin.math.floor((top - y) / bh).toInt()

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        // The picture behind the wall: rings in the palette, shown brick by brick.
        val cy = top - rows * bh * 0.5f
        for (r in 0 until rows) for (c in 0 until cols) {
            val k = r * cols + c
            val x = left + (c + 0.5f) * bw
            val y = top - (r + 0.5f) * bh
            if (alive[k]) {
                val col = pal.at(0.05f + 0.9f * r / max(1, rows - 1).toFloat())
                g.rect(x, y, bw * 0.46f, bh * 0.4f, col)
            } else {
                // A spiral behind the wall, in fine tiles: each broken brick
                // uncovers its own piece of one picture.
                val sw = bw / REVEAL_X
                val sh = bh / REVEAL_Y
                for (sy in 0 until REVEAL_Y) for (sx in 0 until REVEAL_X) {
                    val px = x - bw * 0.5f + (sx + 0.5f) * sw
                    val py = y - bh * 0.5f + (sy + 0.5f) * sh
                    val dx = px; val dy = (py - cy) * 1.6f
                    val d = sqrt(dx * dx + dy * dy)
                    val t = ((atan2(dy, dx) / TWO_PI + 1f) + d * 2.4f) % 1f
                    g.rect(px, py, sw * 0.5f, sh * 0.5f, pal.at(t), 0f, 0.42f)
                }
                if (hitGlow[k] > 0f) g.glow(x, y, bw * 0.9f, Palette.TEXT_PRIMARY, hitGlow[k] * 0.5f)
            }
        }
        // Trails, then balls.
        for (i in 0 until n) {
            val c = pal.at(0.15f + 0.7f * i / max(1, n - 1).toFloat())
            for (j in 1 until TRAIL) {
                val a = (trailHead - j + TRAIL) % TRAIL
                val b = (trailHead - j + 1 + TRAIL) % TRAIL
                g.line(trailX[i * TRAIL + a], trailY[i * TRAIL + a], trailX[i * TRAIL + b], trailY[i * TRAIL + b], BALL_R * 1.2f * (1f - j / TRAIL.toFloat()), c, 0.35f * (1f - j / TRAIL.toFloat()))
            }
            g.glow(bx[i], by[i], BALL_R * 4f, c, 0.35f)
            g.disc(bx[i], by[i], BALL_R, Palette.brighten(c, 0.5f))
        }
        g.rect(paddleX, PADDLE_Y - 0.012f, paddleHalf, 0.012f, Palette.TEXT_PRIMARY)
        g.glow(paddleX, PADDLE_Y - 0.012f, paddleHalf * 1.2f, Palette.TEXT_PRIMARY, 0.12f)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && elapsed - doneAt > 3f

    override fun counters(): List<Pair<String, String>> = buildList {
        add("bricks left" to "$remaining")
        add("balls" to "$n")
        if (misses > 0) add("missed" to "$misses")
    }

    companion object {
        const val MAX_ROWS = 24
        const val MAX_BALLS = 40
        const val TRAIL = 8
        const val BALL_R = 0.012f
        const val BALL_SPEED = 1.5f
        const val PADDLE_SPEED = 2.6f
        const val PADDLE_Y = -0.86f
        const val CEILING = 0.95f
        const val MIN_RISE = 0.3f
        const val MAX_ANGLE = 1.05f
        const val REVEAL_X = 4
        const val REVEAL_Y = 2
        const val TWO_PI = 6.2831855f

        fun def(): SimDef = SimDef(
            id = "physics/brick-breaker",
            title = "Brick breaker",
            shelf = Shelf.PHYSICS,
            family = "Bouncing & collisions",
            blurb = "A paddle that aims. Every brick it breaks shows a bit more of what is behind the wall.",
            formats = listOf(TrapFormat.REVEAL, TrapFormat.LAST_STANDING, TrapFormat.SONG),
            heroLabel = "Build the wall",
            defaultPalette = "Candy",
            typicalSeconds = 28f,
            params = listOf(
                Param.int("balls", "Balls", 1, MAX_BALLS, 6, card = Card.DATA, logScale = true),
                Param.int("rows", "Rows of bricks", 2, MAX_ROWS, 6, card = Card.DATA),
                Param.float("speed", "Speed", 0.3f, 4f, 1f, card = Card.MOTION, logScale = true)
            ),
            create = { BrickBreakerSim() }
        )
    }
}
