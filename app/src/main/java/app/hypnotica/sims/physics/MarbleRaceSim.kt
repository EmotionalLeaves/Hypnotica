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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A marble race down a tall course of pegs and paddles, the camera riding
 * with the leader. Pick a colour at the top; the lead changes on every bounce.
 *
 * Every peg is a note — its pitch set by where it sits across the course, low
 * on the left and high on the right — so the race plays itself as it falls,
 * and the leader's run down the board is a melody.
 */
class MarbleRaceSim : Sim {

    private var count = 8
    private var x = FloatArray(0)
    private var y = FloatArray(0)
    private var vx = FloatArray(0)
    private var vy = FloatArray(0)
    private var done = BooleanArray(0)
    private var place = IntArray(0)
    private var placed = 0

    // The course: pegs in rows, paddles between some of them.
    private var pegX = FloatArray(0)
    private var pegY = FloatArray(0)
    private var rowStart = IntArray(0)
    private var paddleY = FloatArray(0)
    private var paddleX = FloatArray(0)
    private var paddleSpin = FloatArray(0)
    private var length = 12f
    private var halfW = 0.46f
    private var camY = 0f
    private var elapsed = 0f
    private var winnerAt = -1f
    private var payoffFrame = false
    private var lastHitStep = IntArray(0)
    private var stillFor = FloatArray(0)
    private var steps = 0

    override fun setup(env: SimEnv) {
        val p = env.params
        count = p.i("marbles", 8).coerceIn(2, MAX_MARBLES)
        length = p.f("length", 9f).coerceIn(4f, 40f)
        halfW = env.halfWidth * 0.94f
        val rows = (length / ROW_GAP).toInt()
        val perRow = max(2, (2f * halfW / PEG_SPACING).toInt())
        val px = ArrayList<Float>(); val py = ArrayList<Float>()
        rowStart = IntArray(rows + 1)
        for (r in 0 until rows) {
            rowStart[r] = px.size
            val yy = -1.2f - r * ROW_GAP
            val offset = if (r % 2 == 0) 0f else PEG_SPACING * 0.5f
            for (k in 0..perRow) {
                val xx = -halfW + offset + k * PEG_SPACING + env.rng.range(-0.02f, 0.02f)
                // Gaps, so lines form and the field is not a uniform sieve.
                // Clear of the walls by more than a marble: a peg closer than
                // that made a slot a marble fell into and never left.
                if (xx < -halfW + WALL_CLEAR || xx > halfW - WALL_CLEAR) continue
                if (env.rng.nextFloat() < GAP_CHANCE) continue
                px.add(xx); py.add(yy)
            }
        }
        rowStart[rows] = px.size
        pegX = px.toFloatArray(); pegY = py.toFloatArray()
        val paddles = (rows / PADDLE_EVERY).coerceAtLeast(0)
        paddleY = FloatArray(paddles) { -1.2f - (it * PADDLE_EVERY + PADDLE_EVERY / 2 + 0.5f) * ROW_GAP }
        paddleX = FloatArray(paddles) { env.rng.range(-halfW * 0.4f, halfW * 0.4f) }
        paddleSpin = FloatArray(paddles) { (if (env.rng.bool()) 1f else -1f) * env.rng.range(1.5f, 3f) }

        x = FloatArray(count) { -halfW * 0.6f + 1.2f * halfW * it / max(1, count - 1) + env.rng.range(-0.01f, 0.01f) }
        y = FloatArray(count) { -0.6f + env.rng.range(-0.05f, 0.05f) }
        vx = FloatArray(count) { env.rng.range(-0.05f, 0.05f) }
        vy = FloatArray(count)
        done = BooleanArray(count)
        place = IntArray(count)
        placed = 0
        camY = -0.6f
        elapsed = 0f
        winnerAt = -1f
        payoffFrame = false
        lastHitStep = IntArray(pegX.size)
        stillFor = FloatArray(count)
        steps = 0
    }

    private val finishY: Float get() = -1.2f - length - 0.3f

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        steps++
        val g = env.laws.gravity * GRAVITY_SCALE
        val bounce = env.laws.bounce.coerceIn(0.1f, 0.95f) * 0.75f
        val h = abs(dt)
        for (i in 0 until count) {
            if (done[i]) continue
            vy[i] -= g * h
            vx[i] *= 1f - 0.15f * h
            x[i] += vx[i] * h
            y[i] += vy[i] * h
            // Walls.
            // A lid on the course, so nothing floats off the top of it.
            if (y[i] > -0.4f) { y[i] = -0.4f; vy[i] = -abs(vy[i]) * 0.5f }
            // The walls drag, so hugging one is not an express lane past the pegs.
            if (x[i] < -halfW + R) { x[i] = -halfW + R; vx[i] = abs(vx[i]) * bounce + 0.08f; vy[i] *= WALL_DRAG }
            if (x[i] > halfW - R) { x[i] = halfW - R; vx[i] = -abs(vx[i]) * bounce - 0.08f; vy[i] *= WALL_DRAG }
            pegs(env, i, bounce)
            paddles(i, bounce)
            // A marble wedged between two pegs is nudged free, as a real one
            // would be by the board's vibration.
            if (vx[i] * vx[i] + vy[i] * vy[i] < STUCK_SPEED * STUCK_SPEED) stillFor[i] += h else stillFor[i] = 0f
            if (stillFor[i] > STUCK_SECONDS) {
                stillFor[i] = 0f
                // Sideways and down the course, never up it: upward, with no
                // gravity to bring it back, a marble left the course for good.
                vx[i] += if (env.rng.bool()) 0.5f else -0.5f
                vy[i] -= 0.3f
            }
            if (y[i] < finishY) {
                done[i] = true
                place[i] = ++placed
                env.emit(EventKind.HIT, 1f - (placed - 1f) / count, (x[i] / halfW).coerceIn(-1f, 1f), if (placed == 1) 1f else 0.5f)
                if (placed == 1) {
                    winnerAt = elapsed
                    payoffFrame = true
                    env.emit(EventKind.DONE, 0.95f, 0f, 1f)
                }
            }
        }
        // Marbles knock each other.
        for (i in 0 until count) for (j in i + 1 until count) {
            if (done[i] || done[j]) continue
            val dx = x[j] - x[i]; val dy = y[j] - y[i]
            val d2 = dx * dx + dy * dy
            if (d2 < 4f * R * R && d2 > 1e-9f) {
                val d = sqrt(d2); val nx = dx / d; val ny = dy / d
                val push = (2f * R - d) * 0.5f
                x[i] -= nx * push; y[i] -= ny * push; x[j] += nx * push; y[j] += ny * push
                val rel = (vx[j] - vx[i]) * nx + (vy[j] - vy[i]) * ny
                if (rel < 0f) {
                    val imp = -rel * 0.9f
                    vx[i] -= imp * nx; vy[i] -= imp * ny; vx[j] += imp * nx; vy[j] += imp * ny
                }
            }
        }
        // The camera follows the leader, smoothly.
        var lead = Float.MAX_VALUE
        for (i in 0 until count) if (!done[i] && y[i] < lead) lead = y[i]
        if (lead == Float.MAX_VALUE) lead = finishY
        val target = max(lead + 0.35f, finishY + 0.6f).coerceAtMost(-0.4f)
        camY += (target - camY) * min(1f, h * 3f)
    }

    private fun pegs(env: SimEnv, i: Int, bounce: Float) {
        val row = floor((-1.2f - y[i]) / ROW_GAP + 0.5f).toInt()
        for (r in row - 1..row + 1) {
            if (r < 0 || r >= rowStart.size - 1) continue
            for (k in rowStart[r] until rowStart[r + 1]) {
                val dx = x[i] - pegX[k]; val dy = y[i] - pegY[k]
                val d2 = dx * dx + dy * dy
                val reach = R + PEG_R
                if (d2 >= reach * reach || d2 < 1e-9f) continue
                val d = sqrt(d2); val nx = dx / d; val ny = dy / d
                x[i] = pegX[k] + nx * reach; y[i] = pegY[k] + ny * reach
                val vn = vx[i] * nx + vy[i] * ny
                if (vn < 0f) {
                    vx[i] -= (1f + bounce) * vn * nx
                    vy[i] -= (1f + bounce) * vn * ny
                    // A little sideways kick, so no two marbles take the same line.
                    vx[i] += (env.rng.nextFloat() - 0.5f) * 0.12f
                    if (steps - lastHitStep[k] > 6 && -vn > 0.25f) {
                        lastHitStep[k] = steps
                        val across = ((pegX[k] + halfW) / (2f * halfW)).coerceIn(0f, 1f)
                        env.emit(EventKind.HIT, across, across * 1.6f - 0.8f, min(1f, 0.25f - vn * 0.25f))
                    }
                }
            }
        }
    }

    private fun paddles(i: Int, bounce: Float) {
        for (k in paddleY.indices) {
            if (abs(y[i] - paddleY[k]) > PADDLE_LEN + R) continue
            val a = paddleSpin[k] * elapsed
            val ux = cos(a); val uy = sin(a)
            val rx = x[i] - paddleX[k]; val ry = y[i] - paddleY[k]
            val along = (rx * ux + ry * uy).coerceIn(-PADDLE_LEN, PADDLE_LEN)
            val cx = paddleX[k] + ux * along; val cy = paddleY[k] + uy * along
            val dx = x[i] - cx; val dy = y[i] - cy
            val d2 = dx * dx + dy * dy
            val reach = R + PADDLE_THICK
            if (d2 >= reach * reach || d2 < 1e-9f) continue
            val d = sqrt(d2); val nx = dx / d; val ny = dy / d
            x[i] = cx + nx * reach; y[i] = cy + ny * reach
            // The paddle's own speed at the contact point, flung into the marble.
            val sx = -paddleSpin[k] * along * uy
            val sy = paddleSpin[k] * along * ux
            val vn = (vx[i] - sx) * nx + (vy[i] - sy) * ny
            if (vn < 0f) {
                vx[i] -= (1f + bounce) * vn * nx
                vy[i] -= (1f + bounce) * vn * ny
            }
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val top = camY + 1.15f
        val bottom = camY - 1.15f
        // Pegs in view.
        for (k in pegX.indices) {
            val py = pegY[k]
            if (py > top || py < bottom) continue
            val lit = steps - lastHitStep[k] < 10 && lastHitStep[k] > 0
            val c = if (lit) Palette.brighten(palette.at(((pegX[k] + halfW) / (2f * halfW)).coerceIn(0f, 1f)), 0.3f)
            else Palette.withAlpha(Palette.TEXT_SECONDARY, 0.55f)
            g.disc(pegX[k], py - camY, PEG_R, c)
            if (lit) g.glow(pegX[k], py - camY, PEG_R * 4f, c, 0.4f)
        }
        for (k in paddleY.indices) {
            if (paddleY[k] > top + PADDLE_LEN || paddleY[k] < bottom - PADDLE_LEN) continue
            val a = paddleSpin[k] * elapsed
            g.rect(paddleX[k], paddleY[k] - camY, PADDLE_LEN, PADDLE_THICK, Palette.withAlpha(Palette.TEXT_PRIMARY, 0.7f), a)
        }
        // The finish line.
        if (finishY in bottom..top) {
            g.rect(0f, finishY - camY, halfW, 0.006f, Palette.SUCCESS, alpha = 0.9f)
        }
        for (i in 0 until count) {
            val c = palette.at(i / max(1f, count - 1f))
            val yy = if (done[i]) finishY - 0.15f - 0.05f * (place[i] - 1) else y[i]
            if (yy > top || yy < bottom) continue
            g.glow(x[i], yy - camY, R * 3f, c, 0.45f)
            g.disc(x[i], yy - camY, R, c)
            if (place[i] == 1) g.disc(x[i], yy - camY, R * 0.4f, Palette.TEXT_PRIMARY)
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean =
        (placed == count && winnerAt >= 0f && elapsed - winnerAt > 2.5f) || (winnerAt >= 0f && elapsed - winnerAt > 10f) ||
            // A race nobody can finish — no gravity to fall by — is let go.
            (winnerAt < 0f && elapsed > GIVE_UP_SECONDS)

    override fun counters(): List<Pair<String, String>> {
        var lead = 0
        for (i in 0 until count) if (y[i] < y[lead] && !done[i]) lead = i
        val winner = (0 until count).firstOrNull { place[it] == 1 }
        return listOf(
            if (winner != null) "won" to "marble ${winner + 1}" else "leading" to "marble ${lead + 1}",
            "home" to "$placed / $count"
        )
    }

    companion object {
        const val MAX_MARBLES = 24
        const val R = 0.032f
        const val PEG_R = 0.014f
        const val PEG_SPACING = 0.15f
        const val ROW_GAP = 0.17f
        const val GAP_CHANCE = 0.18f
        const val PADDLE_EVERY = 7
        const val PADDLE_LEN = 0.14f
        const val PADDLE_THICK = 0.012f
        const val GRAVITY_SCALE = 0.5f
        const val STUCK_SPEED = 0.06f
        const val GIVE_UP_SECONDS = 60f
        const val WALL_DRAG = 0.8f
        const val WALL_CLEAR = R * 2f + PEG_R * 2f
        const val STUCK_SECONDS = 0.6f

        fun def(): SimDef = SimDef(
            id = "physics/marble-race",
            title = "Marble race",
            shelf = Shelf.PHYSICS,
            family = "Races & competitions",
            blurb = "Eight marbles, a long fall of pegs. Pick your colour.",
            formats = listOf(TrapFormat.RACE, TrapFormat.GUESS, TrapFormat.SONG),
            lawKnobs = setOf(LawKnob.GRAVITY, LawKnob.BOUNCE),
            heroLabel = "Race again",
            defaultPalette = "Spectrum",
            typicalSeconds = 15f,
            params = listOf(
                Param.int("marbles", "Marbles", 2, MAX_MARBLES, 8, card = Card.DATA),
                Param.float("length", "Course length", 4f, 40f, 9f, card = Card.DATA)
            ),
            create = { MarbleRaceSim() }
        )
    }
}
