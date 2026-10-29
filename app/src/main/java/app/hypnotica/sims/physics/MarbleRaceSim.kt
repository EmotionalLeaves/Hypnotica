package app.hypnotica.sims.physics

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.recipe.ParamInput
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
/** How far below the view's centre the leader may get before the camera is pulled down with it. */
private const val CAM_KEEP = 0.85f

class MarbleRaceSim : Sim {

    private var count = 8
    private var x = FloatArray(0)
    private var y = FloatArray(0)
    private var vx = FloatArray(0)
    private var vy = FloatArray(0)
    private var done = BooleanArray(0)
    private var place = IntArray(0)
    private var placed = 0
    private var lastHomeAt = 0f

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
    /** The step each marble last knocked a ramp or paddle on, so a roll is not a buzz. */
    private var knockStep = IntArray(0)
    private var steps = 0
    private var course = 0
    private var rampDrop = 0.1f
    private var rampX1 = FloatArray(0)
    private var rampY1 = FloatArray(0)
    private var rampX2 = FloatArray(0)
    private var rampY2 = FloatArray(0)

    override fun setup(env: SimEnv) {
        val p = env.params
        count = p.i("marbles", 8).coerceIn(2, MAX_MARBLES)
        length = p.f("length", 9f).coerceIn(4f, 40f)
        halfW = env.halfWidth * 0.94f
        val rows = (length / ROW_GAP).toInt()
        course = p.choiceIndex("course").coerceIn(0, 2)
        buildRamps(rows)
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
                // On a ramp course, only a few pegs, kept off the ramps: a
                // peg on a ramp's line made a pocket a marble sat in.
                if (course == RAMPS && env.rng.nextFloat() < 0.8f) continue
                if (course != PEGS && nearRamp(xx, yy)) continue
                px.add(xx); py.add(yy)
            }
        }
        rowStart[rows] = px.size
        pegX = px.toFloatArray(); pegY = py.toFloatArray()
        val paddles = (rows / PADDLE_EVERY).coerceAtLeast(0)
        paddleY = FloatArray(paddles) { -1.2f - (it * PADDLE_EVERY + PADDLE_EVERY / 2 + 0.5f) * ROW_GAP }
        paddleX = FloatArray(paddles) { env.rng.range(-halfW * 0.4f, halfW * 0.4f) }
        paddleSpin = FloatArray(paddles) { (if (env.rng.bool()) 1f else -1f) * env.rng.range(1.5f, 3f) }
        // No paddle near a ramp: one at a ramp's low end batted the marbles
        // back up it, over and over, and the race never finished.
        if (rampX1.isNotEmpty()) {
            val keep = paddleY.indices.filter { k ->
                rampY1.indices.none { r ->
                    paddleY[k] > min(rampY1[r], rampY2[r]) - PADDLE_LEN - R * 3f && paddleY[k] < max(rampY1[r], rampY2[r]) + PADDLE_LEN + R * 3f
                }
            }
            paddleY = FloatArray(keep.size) { paddleY[keep[it]] }
            paddleX = FloatArray(keep.size) { paddleX[keep[it]] }
            paddleSpin = FloatArray(keep.size) { paddleSpin[keep[it]] }
        }

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
        knockStep = IntArray(count) { -100 }
        steps = 0
    }

    private val finishY: Float get() = -1.2f - length - 0.3f

    /**
     * The line finished marbles stand in, in the order they came home: a
     * second row behind the first when they won't fit side by side.
     */
    private val perRow: Int get() = max(1, min(count, (1.8f * halfW / (R * 2.4f)).toInt()))
    private fun slotX(place: Int): Float {
        val inRow = min(perRow, count - (place - 1) / perRow * perRow)
        val k = (place - 1) % perRow
        return R * 2.4f * (k - (inRow - 1) * 0.5f)
    }
    private fun slotY(place: Int): Float = finishY - 0.13f - (place - 1) / perRow * R * 2.4f

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        steps++
        val g = env.laws.gravity * GRAVITY_SCALE
        val bounce = env.laws.bounce.coerceIn(0.1f, 0.95f) * 0.75f
        val h = abs(dt)
        for (i in 0 until count) {
            if (done[i]) {
                // Home: rolled into its place in the line under the finish.
                x[i] += (slotX(place[i]) - x[i]) * min(1f, h * 6f)
                y[i] += (slotY(place[i]) - y[i]) * min(1f, h * 6f)
                continue
            }
            vy[i] -= g * h
            vx[i] *= 1f - 0.15f * h
            val ox = x[i]; val oy = y[i]
            x[i] += vx[i] * h
            y[i] += vy[i] * h
            // Walls.
            // A lid on the course, so nothing floats off the top of it.
            if (y[i] > -0.4f) { y[i] = -0.4f; vy[i] = -abs(vy[i]) * 0.5f }
            // The walls drag, so hugging one is not an express lane past the pegs.
            if (x[i] < -halfW + R) { x[i] = -halfW + R; vx[i] = abs(vx[i]) * bounce + 0.08f; vy[i] *= WALL_DRAG }
            if (x[i] > halfW - R) { x[i] = halfW - R; vx[i] = -abs(vx[i]) * bounce - 0.08f; vy[i] *= WALL_DRAG }
            pegs(env, i, bounce)
            ramps(env, i, ox, oy)
            paddles(env, i, bounce)
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
                lastHomeAt = elapsed
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
        // Once someone is home, the camera stays at the finish and the rest
        // come in to it. Following the next leader, it swung away up the
        // course the moment the race was won, and the win was never seen.
        // The leader low on the screen, so the chasing pack above it is in
        // the picture too: centred on the leader, the race was one marble.
        val target = (if (winnerAt >= 0f) finishY + 0.6f else max(lead + 0.5f, finishY + 0.6f)).coerceAtMost(-0.4f)
        camY += (target - camY) * min(1f, h * 3f)
        // The easing let a fast leader drop out of the bottom of the frame for
        // a second and more; the view never lets it go.
        if (winnerAt < 0f && lead != finishY) camY = camY.coerceAtMost(lead + CAM_KEEP)
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
                    // Only pegs in the camera's view are heard: the pack far
                    // up the course (and the stragglers once the camera parks
                    // at the finish) rattled pegs nobody could see.
                    if (steps - lastHitStep[k] > 6 && -vn > 0.25f && abs(pegY[k] - camY) < 1.05f) {
                        lastHitStep[k] = steps
                        val across = ((pegX[k] + halfW) / (2f * halfW)).coerceIn(0f, 1f)
                        env.emit(EventKind.HIT, across, across * 1.6f - 0.8f, min(1f, 0.25f - vn * 0.25f))
                    }
                }
            }
        }
    }

    /**
     * The ramps: a zigzag down the course, each from one wall most of the way
     * across, sloping down to its open end, the next from the other wall.
     * Every ramp on the Ramps course, every fourth row's worth on Mixed.
     */
    private fun buildRamps(rows: Int) {
        if (course == PEGS) { rampX1 = FloatArray(0); rampY1 = FloatArray(0); rampX2 = FloatArray(0); rampY2 = FloatArray(0); return }
        // A set slope, however wide the screen: a fixed drop over a long ramp
        // was too gentle to roll down against the drag, and marbles sat at
        // the end. The rows between ramps make room for the drop.
        val drop = RAMP_SLOPE * 2f * halfW * RAMP_SPAN
        val room = kotlin.math.ceil((drop + 0.22f) / ROW_GAP).toInt()
        val every = if (course == RAMPS) max(3, room) else max(6, room * 2)
        rampDrop = drop
        // No more than about six screen-widths of rolling in all: on a wide
        // screen every ramp is a long way across, and the race dragged.
        val n = max(1, min(rows / every, (RAMP_TOTAL / (2f * halfW * RAMP_SPAN)).toInt().coerceAtLeast(3)))
        rampX1 = FloatArray(n); rampY1 = FloatArray(n); rampX2 = FloatArray(n); rampY2 = FloatArray(n)
        for (k in 0 until n) {
            val y = -1.2f - (k * every + every * 0.5f) * ROW_GAP
            // Most of the way across: the gap at the open end is the way down.
            val reach = halfW * RAMP_SPAN
            if (k % 2 == 0) {
                rampX1[k] = -halfW; rampY1[k] = y + rampDrop * 0.5f
                rampX2[k] = -halfW + 2f * reach; rampY2[k] = y - rampDrop * 0.5f
            } else {
                rampX1[k] = halfW; rampY1[k] = y + rampDrop * 0.5f
                rampX2[k] = halfW - 2f * reach; rampY2[k] = y - rampDrop * 0.5f
            }
        }
    }

    private fun nearRamp(px: Float, py: Float): Boolean {
        for (k in rampX1.indices) if (segDist(px, py, k) < RAMP_CLEAR) return true
        return false
    }

    /** Distance from a point to ramp [k], and the closest point on it, kept. */
    private var cx = 0f
    private var cy = 0f
    private fun segDist(px: Float, py: Float, k: Int): Float {
        val ax = rampX1[k]; val ay = rampY1[k]; val bx = rampX2[k]; val by = rampY2[k]
        val ex = bx - ax; val ey = by - ay
        val t = (((px - ax) * ex + (py - ay) * ey) / (ex * ex + ey * ey)).coerceIn(0f, 1f)
        cx = ax + ex * t; cy = ay + ey * t
        val dx = px - cx; val dy = py - cy
        return sqrt(dx * dx + dy * dy)
    }

    /** Which side of ramp [k]'s line a point is on: above is positive. */
    private fun side(px: Float, py: Float, k: Int): Float {
        val ex = rampX2[k] - rampX1[k]; val ey = rampY2[k] - rampY1[k]
        val s = ex * (py - rampY1[k]) - ey * (px - rampX1[k])
        return if (ex >= 0f) s else -s
    }

    /**
     * A marble landing hard on a ramp or batted by a paddle, heard if it is in
     * view. They were silent, so a pack running the ramps on camera made no
     * sound at all for a second and a half.
     */
    private fun knock(env: SimEnv, i: Int, vn: Float) {
        if (-vn < 0.35f || steps - knockStep[i] < 10 || abs(y[i] - camY) > 1.05f) return
        knockStep[i] = steps
        env.emit(EventKind.HIT, 0.3f + 0.4f * ((x[i] + halfW) / (2f * halfW)).coerceIn(0f, 1f), (x[i] / halfW).coerceIn(-1f, 1f), min(0.8f, 0.2f - vn * 0.2f))
    }

    private fun ramps(env: SimEnv, i: Int, ox: Float, oy: Float) {
        for (k in rampX1.indices) {
            val d = segDist(x[i], y[i], k)
            val reach = R + RAMP_THICK
            // Swept: a marble that started above the ramp stays above it,
            // however far it went this step. Resolved by where it is now, a
            // fast or shoved marble came out underneath.
            val wasAbove = side(ox, oy, k) > 0f
            val isAbove = side(x[i], y[i], k) > 0f
            val ex = rampX2[k] - rampX1[k]; val ey = rampY2[k] - rampY1[k]
            val len2 = (ex * ex + ey * ey).coerceAtLeast(1e-9f)
            // Along the ramp itself, not the line it lies on: past the open
            // end is the way down.
            val along = ((x[i] - rampX1[k]) * ex + (y[i] - rampY1[k]) * ey) / len2
            if (wasAbove != isAbove && along in 0f..1f && d < reach * 4f) {
                val len = sqrt(len2)
                var nx = -ey / len; var ny = ex / len
                if (ex < 0f) { nx = -nx; ny = -ny }
                if (!wasAbove) { nx = -nx; ny = -ny }
                x[i] = cx + nx * reach; y[i] = cy + ny * reach
                val vn = vx[i] * nx + vy[i] * ny
                if (vn < 0f) { knock(env, i, vn); vx[i] -= 1.2f * vn * nx; vy[i] -= 1.2f * vn * ny }
                continue
            }
            if (d >= reach || d < 1e-6f) continue
            val nx = (x[i] - cx) / d; val ny = (y[i] - cy) / d
            x[i] = cx + nx * reach; y[i] = cy + ny * reach
            val vn = vx[i] * nx + vy[i] * ny
            // Rolls along it rather than bouncing off: a small bounce, and
            // almost all the speed along the ramp kept.
            if (vn < 0f) { knock(env, i, vn); vx[i] -= 1.2f * vn * nx; vy[i] -= 1.2f * vn * ny }
        }
    }

    private fun paddles(env: SimEnv, i: Int, bounce: Float) {
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
                knock(env, i, vn)
                vx[i] -= (1f + bounce) * vn * nx
                vy[i] -= (1f + bounce) * vn * ny
            }
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val top = camY + 1.15f
        val bottom = camY - 1.15f
        // The course's side rails, and a faint tick every course-unit down,
        // so the board reads as a track and the fall as speed.
        val rail = Palette.lerp(Palette.TEXT_SECONDARY, 0xFF000000.toInt(), 0.45f)
        g.line(-halfW, 1.1f, -halfW, -1.1f, 0.006f, rail, 0.8f)
        g.line(halfW, 1.1f, halfW, -1.1f, 0.006f, rail, 0.8f)
        var tick = kotlin.math.ceil(bottom * 2f) / 2f
        while (tick < top) {
            if (tick < -1.0f && tick > finishY + 0.2f) g.line(-halfW, tick - camY, -halfW + 0.04f, tick - camY, 0.004f, rail, 0.8f)
            tick += 0.5f
        }
        // Pegs in view: each tinted faintly by the note it plays (its place
        // across the course), lit up when struck. Plain grey dots read as a
        // sparse field of dust.
        for (k in pegX.indices) {
            val py = pegY[k]
            if (py > top || py < bottom) continue
            val tone = palette.at(((pegX[k] + halfW) / (2f * halfW)).coerceIn(0f, 1f))
            val since = steps - lastHitStep[k]
            val lit = since < 24 && lastHitStep[k] > 0
            if (lit) {
                val f = 1f - since / 24f
                val c = Palette.brighten(tone, 0.3f)
                g.glow(pegX[k], py - camY, PEG_R * (3f + 3f * f), c, 0.55f * f)
                g.ring(pegX[k], py - camY, PEG_R * (1.4f + 2.2f * (1f - f)), 0.003f, c, alpha = 0.8f * f)
                g.disc(pegX[k], py - camY, PEG_R, Palette.lerp(Palette.lerp(Palette.TEXT_SECONDARY, tone, 0.45f), c, f))
            } else {
                g.disc(pegX[k], py - camY, PEG_R, Palette.lerp(Palette.TEXT_SECONDARY, tone, 0.45f), 0.8f)
            }
        }
        for (k in rampX1.indices) {
            if (min(rampY1[k], rampY2[k]) > top + 0.2f || max(rampY1[k], rampY2[k]) < bottom - 0.2f) continue
            val c = palette.at((k * 0.37f) % 1f)
            g.line(rampX1[k], rampY1[k] - camY, rampX2[k], rampY2[k] - camY, RAMP_THICK * 5f, c, 0.12f)
            g.line(rampX1[k], rampY1[k] - camY, rampX2[k], rampY2[k] - camY, RAMP_THICK * 2f, c)
        }
        for (k in paddleY.indices) {
            if (paddleY[k] > top + PADDLE_LEN || paddleY[k] < bottom - PADDLE_LEN) continue
            val a = paddleSpin[k] * elapsed
            g.rect(paddleX[k], paddleY[k] - camY, PADDLE_LEN, PADDLE_THICK, Palette.withAlpha(Palette.TEXT_PRIMARY, 0.75f), a)
            g.disc(paddleX[k], paddleY[k] - camY, PADDLE_THICK * 1.6f, rail)
        }
        // The finish line: a chequered band right across.
        if (finishY in bottom - 0.1f..top + 0.1f) {
            // Squares of a set size, as many as fit: a set number of them
            // across a wide screen was a band of slabs.
            val flags = max(FLAGS, (2f * halfW / FLAG_MAX).toInt())
            val sq = 2f * halfW / flags
            g.glow(0f, finishY - camY, halfW * 0.9f, Palette.TEXT_PRIMARY, 0.08f)
            for (k in 0 until flags) for (row in 0..1) {
                if ((k + row) % 2 != 0) continue
                g.rect(-halfW + sq * (k + 0.5f), finishY - camY + (row - 0.5f) * sq, sq * 0.5f, sq * 0.5f, Palette.TEXT_PRIMARY, alpha = 0.7f)
            }
        }
        var leader = -1
        for (i in 0 until count) if (!done[i] && (leader < 0 || y[i] < y[leader])) leader = i
        for (i in 0 until count) {
            val c = palette.at(hueOf(i))
            val yy = y[i]
            if (yy > top || yy < bottom) continue
            val sy = yy - camY
            if (!done[i]) {
                // A streak behind it, as long as it is fast.
                val tail = 0.07f
                val tx = x[i] - vx[i] * tail; val ty = sy - vy[i] * tail
                val sp = sqrt(vx[i] * vx[i] + vy[i] * vy[i])
                if (sp > 0.2f) {
                    g.line(tx, ty, x[i], sy, R * 1.3f, c, 0.18f)
                    g.line((tx + x[i]) * 0.5f, (ty + sy) * 0.5f, x[i], sy, R * 1.7f, c, 0.25f)
                }
            }
            if (place[i] == 1) {
                // The winner, ringed, and pulsing once it's home.
                g.glow(x[i], sy, R * (3.2f + 0.6f * sin(elapsed * 5f)), c, 0.7f)
                g.ring(x[i], sy, R * 1.55f, R * 0.25f, Palette.TEXT_PRIMARY)
            } else if (i == leader && winnerAt < 0f) {
                g.glow(x[i], sy, R * 3.4f, c, 0.6f)
                g.ring(x[i], sy, R * 1.5f, R * 0.18f, Palette.TEXT_PRIMARY, alpha = 0.7f)
            } else g.glow(x[i], sy, R * 2.6f, c, 0.4f)
            g.disc(x[i], sy, R, c)
            g.disc(x[i] - R * 0.32f, sy + R * 0.32f, R * 0.3f, Palette.TEXT_PRIMARY, 0.55f)
        }
        // The race at a glance: every marble's progress down the whole course,
        // on the right-hand rail. Most of the field is off
        // screen at any moment; this keeps the whole race in the picture.
        val trackTop = 0.85f; val trackBottom = -0.85f
        // Along the right rail itself, which doubles as the track.
        val trackX = halfW
        g.rect(trackX, trackBottom, 0.016f, 0.004f, Palette.TEXT_PRIMARY, 0f, 0.7f)
        for (i in 0 until count) {
            val f = ((-0.6f - y[i]) / (-0.6f - finishY)).coerceIn(0f, 1f)
            val c = palette.at(hueOf(i))
            g.disc(trackX, trackTop + (trackBottom - trackTop) * f, if (i == leader || place[i] == 1) 0.013f else 0.009f, c, if (done[i] && place[i] != 1) 0.6f else 1f)
        }
    }

    /**
     * Each marble's place on the palette. Spread over i / (count - 1), the
     * first and last both landed on the ends of the default wheel palette,
     * and the race had two red marbles.
     */
    private fun hueOf(i: Int): Float = i / count.coerceAtLeast(1).toFloat()

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean =
        // And the last one home given time to roll into its place: ended the
        // moment it crossed, the run froze with it sitting on the line.
        (placed == count && winnerAt >= 0f && elapsed - winnerAt > 2.5f && elapsed - lastHomeAt > 1.2f) || (winnerAt >= 0f && elapsed - winnerAt > 10f) ||
            // A race nobody can finish — no gravity to fall by — is let go.
            (winnerAt < 0f && elapsed > GIVE_UP_SECONDS + GIVE_UP_PER_LENGTH * length)

    override fun contenders(env: SimEnv): IntArray =
        if (count > 8) app.hypnotica.core.sim.NO_CONTENDERS else IntArray(count) { env.palette.at(hueOf(it)) }

    override fun winner(): Int = if (count > 8) -1 else place.indexOfFirst { it == 1 }

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
        const val PEGS = 0
        const val RAMPS = 1
        const val RAMP_SPAN = 0.78f
        const val RAMP_SLOPE = 0.16f
        const val RAMP_TOTAL = 7f
        const val RAMP_THICK = 0.008f
        /** Pegs kept this far off a ramp: past a rolling marble's whole height. */
        const val RAMP_CLEAR = 0.13f
        const val GAP_CHANCE = 0.18f
        const val PADDLE_EVERY = 7
        const val PADDLE_LEN = 0.14f
        const val PADDLE_THICK = 0.012f
        const val GRAVITY_SCALE = 0.5f
        const val STUCK_SPEED = 0.06f
        // With the course: a long course on a wide screen is still going at a minute.
        const val GIVE_UP_SECONDS = 25f
        const val GIVE_UP_PER_LENGTH = 3.5f
        const val WALL_DRAG = 0.8f
        const val WALL_CLEAR = R * 2f + PEG_R * 2f
        const val STUCK_SECONDS = 0.6f
        /** Squares across the finish line. */
        const val FLAGS = 20
        const val FLAG_MAX = 0.05f

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
                Param.choice("course", "Course", listOf("Pegs", "Ramps", "Mixed"), 0, card = Card.DATA, input = ParamInput.TILES),
                Param.int("marbles", "Marbles", 2, MAX_MARBLES, 8, card = Card.DATA),
                Param.float("length", "Course length", 4f, 40f, 9f, card = Card.DATA)
            ),
            create = { MarbleRaceSim() }
        )
    }
}
