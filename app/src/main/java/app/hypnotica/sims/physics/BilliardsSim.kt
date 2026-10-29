package app.hypnotica.sims.physics

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Macro
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A rack broken and then cleared, shot by shot, by a player who never misses
 * an aim.
 *
 * The physics is a real bar table's, in metres: 57 mm balls on a 7-foot bed,
 * near-elastic ball-on-ball collisions (equal masses, restitution 0.95),
 * cushions that give back three quarters of the speed, and cloth that first
 * makes a struck ball skid — a central hit leaves it sliding with no spin, and
 * it slows hard until it rolls at 5/7 of its speed — and then barely slows it
 * at all. Pockets have jaws: a ball that comes in too wide rattles out off the
 * cushion's point.
 *
 * After the break the shooter picks, every time, the easiest pot it can see:
 * a clear line from the cue ball to the "ghost ball" spot touching the object
 * ball, a clear line from there to a pocket, the thinnest cut it can find.
 */
class BilliardsSim : Sim {

    private var hw = 0.46f
    private var long = false
    // Half-extents of the bed in world units, x and y (rotated on a wide screen).
    private var hx = 0.37f
    private var hy = 0.74f
    private var r = 0.02f
    /** World units per metre. */
    private var wpm = 0.74f
    private var balls = 16
    private var px = FloatArray(0)
    private var py = FloatArray(0)
    private var vx = FloatArray(0)
    private var vy = FloatArray(0)
    private var roll = FloatArray(0)
    private var band = FloatArray(0)
    private var inPlay = BooleanArray(0)
    private var sinking = FloatArray(0)
    private var sinkX = FloatArray(0)
    private var sinkY = FloatArray(0)
    private var tray = IntArray(0)
    private var trayCount = 0
    private val pocketX = FloatArray(6)
    private val pocketY = FloatArray(6)
    private val pocketR = FloatArray(6)
    private val jawX = FloatArray(12)
    private val jawY = FloatArray(12)
    private var cornerMouth = 0f
    private var sideMouth = 0f
    private var cushionE = 0.75f

    private var elapsed = 0f
    private var phase = AIM
    private var phaseAt = 0f
    private var shots = 0
    private var called = 0
    private var aimX = 0f
    private var aimY = 1f
    private var shotSpeed = 0f
    private var aimBall = -1
    private var aimPocket = -1
    private var ghostX = 0f
    private var ghostY = 0f
    private var breakDone = false
    private var payoffFrame = false
    private var paidAt = -1f
    private var scratches = 0
    private var cueOut = false
    private var lastClick = 0f

    override fun setup(env: SimEnv) {
        val p = env.params
        hw = env.halfWidth
        long = hw > 1f
        // The bed is twice as long as it is wide; a landscape screen turns it.
        val w = if (long) min(0.8f, hw * 0.42f) else min(hw * 0.8f, 0.42f)
        if (long) { hx = w * 2f; hy = w } else { hx = w; hy = w * 2f }
        wpm = 2f * w / BED_WIDTH
        r = BALL_R * wpm
        val rows = when (p.choiceIndex("rack")) { 0 -> 5; 1 -> 4; else -> 3 }
        val objects = rows * (rows + 1) / 2
        balls = objects + 1
        px = FloatArray(balls); py = FloatArray(balls)
        vx = FloatArray(balls); vy = FloatArray(balls)
        roll = FloatArray(balls)
        band = FloatArray(balls) { env.rng.range(0f, PI.toFloat()) }
        inPlay = BooleanArray(balls) { true }
        sinking = FloatArray(balls) { -1f }
        sinkX = FloatArray(balls); sinkY = FloatArray(balls)
        tray = IntArray(balls)
        trayCount = 0
        // Ball numbers into the rack: the 8 in the middle of the third row
        // on a full rack, the rest shuffled.
        val order = (1..objects).toMutableList()
        for (i in order.size - 1 downTo 1) {
            val j = env.rng.int(0, i + 1)
            val t = order[i]; order[i] = order[j]; order[j] = t
        }
        if (rows == 5) { order.remove(8); order.add(4, 8) }
        numbers = IntArray(balls)
        // Rack apex on the foot spot, rows running to the foot cushion, tight.
        val (fx, fy) = alongLong(0.5f)
        val (ux, uy) = longDir()
        var k = 1
        val dRow = r * sqrt(3f) * 1.0005f
        for (row in 0 until rows) {
            for (c in 0..row) {
                val off = (c - row / 2f) * 2f * r * 1.0005f
                px[k] = fx + ux * dRow * row - uy * off
                py[k] = fy + uy * dRow * row + ux * off
                numbers[k] = order[k - 1]
                k++
            }
        }
        // The cue ball behind the head string, a little off centre.
        val (hxs, hys) = alongLong(-0.5f)
        val side = env.rng.range(-0.35f, 0.35f) * (if (long) hy else hx)
        px[0] = hxs - uy * side; py[0] = hys + ux * side
        numbers[0] = 0
        // Aim at the apex, a hair off full, so no two breaks are alike.
        val ax = px[1] - px[0]; val ay = py[1] - py[0]
        val a = atan2(ay, ax) + env.rng.range(-0.012f, 0.012f)
        aimX = cos(a); aimY = sin(a)
        aimBall = 1; aimPocket = -1
        layoutPockets()
        cushionE = (CUSHION_E * env.laws.bounce / 0.88f).coerceIn(0.5f, 0.92f)
        elapsed = 0f
        phase = AIM
        phaseAt = 0f
        shots = 0
        called = 0
        breakDone = false
        payoffFrame = false
        paidAt = -1f
        scratches = 0
        cueOut = false
    }

    private var numbers = IntArray(0)

    /** A point on the table's long axis: −1 head cushion … 1 foot cushion. */
    private fun alongLong(t: Float): Pair<Float, Float> = if (long) Pair(t * hx, 0f) else Pair(0f, -t * hy)

    private fun longDir(): Pair<Float, Float> = if (long) Pair(1f, 0f) else Pair(0f, -1f)

    private fun layoutPockets() {
        cornerMouth = CORNER_MOUTH * wpm
        sideMouth = SIDE_MOUTH * wpm
        var i = 0
        for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
            pocketX[i] = sx * (hx + r * 0.35f); pocketY[i] = sy * (hy + r * 0.35f); pocketR[i] = r * 1.75f; i++
        }
        // The side pockets sit in the middle of the long cushions.
        for (s in intArrayOf(-1, 1)) {
            if (long) { pocketX[i] = 0f; pocketY[i] = s * (hy + r * 0.9f) } else { pocketX[i] = s * (hx + r * 0.9f); pocketY[i] = 0f }
            pocketR[i] = r * 1.65f; i++
        }
        // Jaw points: where each cushion ends at a pocket mouth.
        var j = 0
        for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
            jawX[j] = sx * (hx - cornerMouth); jawY[j] = sy * hy; j++
            jawX[j] = sx * hx; jawY[j] = sy * (hy - cornerMouth); j++
        }
        for (s in intArrayOf(-1, 1)) for (e in intArrayOf(-1, 1)) {
            if (long) { jawX[j] = e * sideMouth; jawY[j] = s * hy } else { jawX[j] = s * hx; jawY[j] = e * sideMouth }
            j++
        }
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        cushionE = (CUSHION_E * env.laws.bounce / 0.88f).coerceIn(0.5f, 0.92f)
        // Balls dropping into pockets finish their fall.
        for (b in 0 until balls) if (sinking[b] >= 0f) {
            sinking[b] += h
            if (sinking[b] > SINK_TIME) {
                sinking[b] = -2f
                if (b != 0) {
                    tray[trayCount++] = b
                    if (remaining() == 0 && paidAt < 0f) { payoffFrame = true; paidAt = elapsed }
                }
            }
        }
        when (phase) {
            AIM -> {
                val wait = if (shots == 0) BREAK_WAIT else AIM_TIME
                if (elapsed - phaseAt > wait) {
                    strike(env)
                    phase = ROLL
                    phaseAt = elapsed
                }
            }
            ROLL -> {
                val subs = SUBSTEPS
                val sdt = h / subs
                repeat(subs) { physics(env, sdt) }
                if (allStill() && elapsed - phaseAt > 0.3f && !anySinking()) {
                    if (remaining() > 0) {
                        if (!inPlay[0]) respotCue()
                        plan(env)
                        phase = AIM
                        phaseAt = elapsed
                    } else phase = DONE
                }
            }
        }
    }

    private fun anySinking(): Boolean { for (b in 0 until balls) if (sinking[b] >= 0f) return true; return false }

    private fun remaining(): Int { var n = 0; for (b in 1 until balls) if (inPlay[b] || sinking[b] >= 0f) n++; return n }

    private fun allStill(): Boolean {
        for (b in 0 until balls) if (inPlay[b] && (vx[b] != 0f || vy[b] != 0f)) return false
        return true
    }

    private fun strike(env: SimEnv) {
        val speed = if (shots == 0) env.params.f("power", 7f).coerceIn(3f, 10f) else shotSpeed
        vx[0] = aimX * speed * wpm
        vy[0] = aimY * speed * wpm
        // Struck through the centre, the cue ball skids before it rolls.
        roll[0] = speed * wpm * ROLL_SHARE
        shots++
        breakDone = breakDone || shots > 0
        env.emit(EventKind.STEP, 0.45f + 0.04f * min(speed, 8f), pan(px[0]), (0.3f + speed / 12f).coerceAtMost(1f))
    }

    private fun physics(env: SimEnv, dt: Float) {
        val slide = SLIDE_DECEL * wpm
        val rollD = ROLL_DECEL * wpm
        for (b in 0 until balls) {
            if (!inPlay[b]) continue
            val s = hypot(vx[b], vy[b])
            if (s == 0f) continue
            // Skidding until it rolls, then the cloth's gentle rolling drag.
            val dec = (if (s > roll[b]) slide else rollD) * dt
            val ns = s - dec
            if (ns <= STOP * wpm) { vx[b] = 0f; vy[b] = 0f; roll[b] = 0f; continue }
            vx[b] *= ns / s; vy[b] *= ns / s
            px[b] += vx[b] * dt; py[b] += vy[b] * dt
        }
        // Ball on ball.
        val d2min = 4f * r * r
        for (a in 0 until balls) {
            if (!inPlay[a]) continue
            for (b in a + 1 until balls) {
                if (!inPlay[b]) continue
                val dx = px[b] - px[a]; val dy = py[b] - py[a]
                val d2 = dx * dx + dy * dy
                if (d2 >= d2min || d2 == 0f) continue
                // Rewind both to the instant they touched, so the line of
                // centres is the true one: resolving at the overlap bends a
                // thin cut by degrees, enough to miss a pocket.
                val rvx = vx[b] - vx[a]; val rvy = vy[b] - vy[a]
                val rv2 = rvx * rvx + rvy * rvy
                var back = 0f
                if (rv2 > 1e-12f) {
                    // |d − rv·τ| = 2r for the τ ≥ 0 that undoes the overlap.
                    val bq = dx * rvx + dy * rvy
                    val cq = d2 - d2min
                    val disc = bq * bq - rv2 * cq
                    if (disc >= 0f) back = ((bq + sqrt(disc)) / rv2).coerceIn(0f, dt)
                }
                px[a] -= vx[a] * back; py[a] -= vy[a] * back
                px[b] -= vx[b] * back; py[b] -= vy[b] * back
                var cx = px[b] - px[a]; var cy = py[b] - py[a]
                var d = sqrt(cx * cx + cy * cy)
                if (d < 1e-7f) { cx = dx; cy = dy; d = sqrt(d2) }
                val nx = cx / d; val ny = cy / d
                // Whatever overlap is left (resting contacts), pushed apart half each.
                val over = max(0f, 2f * r - d) * 0.5f
                px[a] -= nx * over; py[a] -= ny * over
                px[b] += nx * over; py[b] += ny * over
                val rel = (vx[a] - vx[b]) * nx + (vy[a] - vy[b]) * ny
                if (rel <= 0f) {
                    px[a] += vx[a] * back; py[a] += vy[a] * back
                    px[b] += vx[b] * back; py[b] += vy[b] * back
                    continue
                }
                val j = rel * (1f + BALL_E) * 0.5f
                vx[a] -= j * nx; vy[a] -= j * ny
                vx[b] += j * nx; vy[b] += j * ny
                // Each leaves sliding without spin from the new direction.
                roll[a] = hypot(vx[a], vy[a]) * ROLL_SHARE
                roll[b] = hypot(vx[b], vy[b]) * ROLL_SHARE
                // Then on to the end of the substep with the new velocities.
                px[a] += vx[a] * back; py[a] += vy[a] * back
                px[b] += vx[b] * back; py[b] += vy[b] * back
                val impact = rel / wpm
                if (impact > CLICK_MIN && elapsed - lastClick > 0.012f) {
                    lastClick = elapsed
                    env.emit(EventKind.HIT, 0.85f, pan((px[a] + px[b]) * 0.5f), (impact / 5f).coerceIn(0.1f, 1f))
                }
            }
        }
        for (b in 0 until balls) if (inPlay[b]) cushions(env, b)
    }

    private fun inCornerMouthX(x: Float) = abs(x) > hx - cornerMouth
    private fun inCornerMouthY(y: Float) = abs(y) > hy - cornerMouth
    private fun inSideMouth(b: Int) = if (long) abs(px[b]) < sideMouth else abs(py[b]) < sideMouth

    private fun cushions(env: SimEnv, b: Int) {
        val limX = hx - r; val limY = hy - r
        var hit = 0f
        // Long-side cushions of a tall table are the x walls; on a wide table, y.
        if (abs(px[b]) > limX && !inCornerMouthY(py[b]) && !(!long && inSideMouth(b))) {
            val s = if (px[b] > 0) 1f else -1f
            px[b] = s * limX - (px[b] - s * limX)
            if (vx[b] * s > 0f) { hit = abs(vx[b]); vx[b] = -vx[b] * cushionE; vy[b] *= CUSHION_GRIP; roll[b] = hypot(vx[b], vy[b]) * 0.9f }
        }
        if (abs(py[b]) > limY && !inCornerMouthX(px[b]) && !(long && inSideMouth(b))) {
            val s = if (py[b] > 0) 1f else -1f
            py[b] = s * limY - (py[b] - s * limY)
            if (vy[b] * s > 0f) { hit = max(hit, abs(vy[b])); vy[b] = -vy[b] * cushionE; vx[b] *= CUSHION_GRIP; roll[b] = hypot(vx[b], vy[b]) * 0.9f }
        }
        // The cushion's points at each pocket.
        for (j in 0 until 12) {
            val dx = px[b] - jawX[j]; val dy = py[b] - jawY[j]
            val d2 = dx * dx + dy * dy
            if (d2 >= r * r || d2 == 0f) continue
            val d = sqrt(d2)
            val nx = dx / d; val ny = dy / d
            px[b] = jawX[j] + nx * r; py[b] = jawY[j] + ny * r
            val vn = vx[b] * nx + vy[b] * ny
            if (vn < 0f) {
                hit = max(hit, -vn)
                vx[b] -= (1f + cushionE) * vn * nx; vy[b] -= (1f + cushionE) * vn * ny
                roll[b] = hypot(vx[b], vy[b]) * 0.9f
            }
        }
        if (hit / wpm > CLICK_MIN && elapsed - lastClick > 0.012f) {
            lastClick = elapsed
            env.emit(EventKind.HIT, 0.3f, pan(px[b]), (hit / wpm / 6f).coerceIn(0.08f, 0.7f))
        }
        // Into a pocket.
        for (k in 0 until 6) {
            val dx = px[b] - pocketX[k]; val dy = py[b] - pocketY[k]
            if (dx * dx + dy * dy < pocketR[k] * pocketR[k]) { pot(env, b, k); return }
        }
        // Anything that got past the cushion line elsewhere has left the bed
        // through a mouth: it is in the nearest pocket.
        if (abs(px[b]) > hx + r * 1.5f || abs(py[b]) > hy + r * 1.5f) {
            var best = 0; var bd = Float.MAX_VALUE
            for (k in 0 until 6) { val d = hypot(px[b] - pocketX[k], py[b] - pocketY[k]); if (d < bd) { bd = d; best = k } }
            pot(env, b, best)
        }
    }

    private fun pot(env: SimEnv, b: Int, k: Int) {
        inPlay[b] = false
        sinking[b] = 0f
        sinkX[b] = pocketX[k]; sinkY[b] = pocketY[k]
        px[b] = pocketX[k]; py[b] = pocketY[k]
        vx[b] = 0f; vy[b] = 0f
        if (b == 0) { scratches++; cueOut = true }
        if (b == aimBall && aimPocket == k && shots > 1) called++
        env.emit(EventKind.DEATH, if (b == 0) 0.2f else 0.35f + 0.04f * numbers[b], pan(pocketX[k]), 0.8f)
    }

    private fun respotCue() {
        val (sx, sy) = alongLong(-0.5f)
        var x = sx; var y = sy
        var tries = 0
        while (overlaps(x, y) && tries < 40) {
            val (ux, uy) = longDir()
            x -= ux * r * 0.5f; y -= uy * r * 0.5f
            tries++
        }
        px[0] = x; py[0] = y; vx[0] = 0f; vy[0] = 0f
        inPlay[0] = true
        sinking[0] = -1f
        cueOut = false
    }

    private fun overlaps(x: Float, y: Float): Boolean {
        for (b in 1 until balls) if (inPlay[b] && hypot(px[b] - x, py[b] - y) < 2.1f * r) return true
        return false
    }

    /** Distance from point (cx, cy) to the segment a→b. */
    private fun segDist(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 > 0f) (((cx - ax) * dx + (cy - ay) * dy) / l2).coerceIn(0f, 1f) else 0f
        return hypot(ax + dx * t - cx, ay + dy * t - cy)
    }

    private fun clear(ax: Float, ay: Float, bx: Float, by: Float, skipA: Int, skipB: Int): Boolean {
        for (o in 0 until balls) {
            if (o == skipA || o == skipB || !inPlay[o]) continue
            if (segDist(ax, ay, bx, by, px[o], py[o]) < 2f * r * 1.04f) return false
        }
        return true
    }

    /** The easiest pot on the table, or the nearest ball hit full if there is none. */
    private fun plan(env: SimEnv) {
        var best = Float.MAX_VALUE
        var bestBall = -1; var bestPocket = -1
        var bx0 = 0f; var by0 = 0f; var bSpeed = 0f
        val cx = px[0]; val cy = py[0]
        for (b in 1 until balls) {
            if (!inPlay[b]) continue
            for (k in 0 until 6) {
                // Aim a little inside the pocket's centre, at the middle of its mouth.
                val tx = pocketX[k] * 0.985f; val ty = pocketY[k] * 0.985f
                val ux0 = tx - px[b]; val uy0 = ty - py[b]
                val dp = hypot(ux0, uy0)
                if (dp < 1e-4f) continue
                val ux = ux0 / dp; val uy = uy0 / dp
                // A side pocket only takes balls coming at it fairly square.
                if (k >= 4) {
                    val nrm = if (long) abs(uy) else abs(ux)
                    if (nrm < 0.55f) continue
                }
                val gx = px[b] - ux * 2f * r; val gy = py[b] - uy * 2f * r
                if (abs(gx) > hx - r || abs(gy) > hy - r) continue
                val cdx = gx - cx; val cdy = gy - cy
                val dc = hypot(cdx, cdy)
                if (dc < r) continue
                val cosCut = (cdx * ux + cdy * uy) / dc
                if (cosCut < MAX_CUT_COS) continue
                if (!clear(px[b], py[b], tx, ty, b, 0)) continue
                if (!clear(cx, cy, gx, gy, 0, b)) continue
                val score = (1f - cosCut) * 6f + (dp + dc) / wpm * 0.25f
                if (score < best) {
                    best = score; bestBall = b; bestPocket = k
                    bx0 = gx; by0 = gy
                    bSpeed = shotFor(dp / wpm, dc / wpm, cosCut)
                }
            }
        }
        if (bestBall < 0) {
            // No pot: hit the nearest ball full and firmly, to open things up.
            var nd = Float.MAX_VALUE
            for (b in 1 until balls) if (inPlay[b]) { val d = hypot(px[b] - cx, py[b] - cy); if (d < nd) { nd = d; bestBall = b } }
            bx0 = px[bestBall]; by0 = py[bestBall]
            bSpeed = 4.5f
            bestPocket = -1
        }
        aimBall = bestBall; aimPocket = bestPocket
        ghostX = bx0; ghostY = by0
        val dx = bx0 - cx; val dy = by0 - cy
        val d = hypot(dx, dy).coerceAtLeast(1e-5f)
        aimX = dx / d; aimY = dy / d
        shotSpeed = bSpeed
    }

    private fun pan(x: Float) = (x / max(0.1f, hw)).coerceIn(-1f, 1f)

    /** Speed left after [dist] metres from a skidding start at [v0], m/s. */
    private fun arriving(v0: Float, dist: Float): Float {
        val skid = (v0 * v0 - (ROLL_SHARE * v0) * (ROLL_SHARE * v0)) / (2f * SLIDE_DECEL)
        if (dist <= skid) return sqrt(max(0f, v0 * v0 - 2f * SLIDE_DECEL * dist))
        val vr = ROLL_SHARE * v0
        return sqrt(max(0f, vr * vr - 2f * ROLL_DECEL * (dist - skid)))
    }

    /**
     * The cue speed that sends the object ball [toPocket] metres with
     * [ARRIVE] m/s to spare, through a cut of cos [cosCut] after the cue ball
     * has skidded and rolled [toGhost] metres: both skids are counted, which
     * is what the shooter kept getting wrong when it aimed by distance alone.
     */
    private fun shotFor(toPocket: Float, toGhost: Float, cosCut: Float): Float {
        fun reaches(vObj: Float) = arriving(vObj, toPocket) >= ARRIVE
        var lo = 0.2f; var hi = 8f
        repeat(24) { val m = (lo + hi) / 2f; if (reaches(m)) hi = m else lo = m }
        val need = hi / (cosCut * (1f + BALL_E) * 0.5f)
        lo = 0.2f; hi = 9f
        repeat(24) { val m = (lo + hi) / 2f; if (arriving(m, toGhost) >= need) hi = m else lo = m }
        return hi.coerceIn(0.8f, MAX_SHOT)
    }

    private fun ballColour(env: SimEnv, b: Int): Int {
        val n = numbers[b]
        if (n == 0) return CUE
        if (n == 8) return EIGHT
        return env.palette.at(((n - 1) % 7) / 7f + 0.04f)
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        val rail = RAIL_W * wpm
        // Wood rails, the cushion lip, then the bed.
        g.rect(0f, 0f, hx + rail, hy + rail, RAIL)
        g.rect(0f, 0f, hx + rail * 0.35f, hy + rail * 0.35f, Palette.lerp(FELT_BASE, pal.at(0.55f), 0.3f))
        g.rect(0f, 0f, hx, hy, Palette.lerp(FELT_BASE, pal.at(0.55f), 0.2f))
        // Diamonds on the rails, the sights a player aims by.
        for (i in 1..3) {
            val fxs = -hx + 2f * hx * i / 4f
            for (sy in intArrayOf(-1, 1)) g.disc(fxs, sy * (hy + rail * 0.65f), r * 0.28f, DIAMOND)
        }
        for (i in 1..7) {
            if (i == 4) continue
            val fys = -hy + 2f * hy * i / 8f
            for (sx in intArrayOf(-1, 1)) g.disc(sx * (hx + rail * 0.65f), fys, r * 0.28f, DIAMOND)
        }
        // The head string and foot spot, faintly.
        val (fsx, fsy) = alongLong(0.5f)
        g.disc(fsx, fsy, r * 0.18f, Palette.TEXT_SECONDARY, 0.4f)
        // Pockets.
        for (k in 0 until 6) {
            g.disc(pocketX[k], pocketY[k], pocketR[k] * 1.12f, POCKET_RIM)
            g.disc(pocketX[k], pocketY[k], pocketR[k], POCKET)
        }
        // The plan, while aiming: a faint line to the ghost ball and on to the pocket.
        val aiming = phase == AIM && inPlay[0]
        val t = elapsed - phaseAt
        if (aiming && shots > 0 && aimBall >= 0) {
            val fade = (t / 0.3f).coerceIn(0f, 1f) * 0.5f
            dashed(g, px[0], py[0], ghostX, ghostY, fade)
            g.ring(ghostX, ghostY, r, 0.0025f, Palette.TEXT_PRIMARY, alpha = fade)
            if (aimPocket >= 0) dashed(g, px[aimBall], py[aimBall], pocketX[aimPocket], pocketY[aimPocket], fade * 0.7f)
        }
        // Balls: shadow, body, stripe, highlight.
        for (b in 0 until balls) {
            if (!inPlay[b] && sinking[b] < 0f) continue
            var x = px[b]; var y = py[b]
            var rr = r
            if (sinking[b] >= 0f) {
                val u = (sinking[b] / SINK_TIME).coerceIn(0f, 1f)
                rr = r * (1f - 0.6f * u)
                x = sinkX[b]; y = sinkY[b]
            }
            drawBall(env, g, b, x, y, rr, if (sinking[b] >= 0f) 1f - sinking[b] / SINK_TIME else 1f)
        }
        // The cue: drawn back, then thrust through.
        if (aiming) {
            val wait = if (shots == 0) BREAK_WAIT else AIM_TIME
            val u = (t / wait).coerceIn(0f, 1f)
            val back = if (u < 0.85f) r * (0.6f + 3.2f * (u / 0.85f)) else r * (3.8f * (1f - (u - 0.85f) / 0.15f))
            val tipX = px[0] - aimX * (r + back); val tipY = py[0] - aimY * (r + back)
            val len = 0.55f * (if (long) 1.2f else 1f)
            g.line(tipX, tipY, tipX - aimX * len, tipY - aimY * len, r * 0.42f, CUE_STICK, (u * 4f).coerceAtMost(1f))
            g.line(tipX, tipY, tipX - aimX * r * 0.6f, tipY - aimY * r * 0.6f, r * 0.42f, CUE_TIP, (u * 4f).coerceAtMost(1f))
        }
        // Potted balls, in the order they went down.
        val ty = if (long) -hy - rail - 0.09f else -hy - rail - 0.075f
        val tx0 = -min(hw * 0.85f, (balls - 1) * r * 1.15f)
        if (paidAt >= 0f && trayCount > 0) {
            // The cleared rack lit once the last ball drops; it eases in and holds.
            val u = ((elapsed - paidAt) / 0.6f).coerceIn(0f, 1f)
            val mid = tx0 + (trayCount - 1) * r * 1.15f
            g.glow(mid, ty, trayCount * r * 1.5f, Palette.TEXT_PRIMARY, 0.18f * u)
        }
        for (i in 0 until trayCount) {
            val b = tray[i]
            drawBall(env, g, b, tx0 + i * r * 2.3f, ty, r * 0.95f, 1f)
        }
    }

    private fun drawBall(env: SimEnv, g: G, b: Int, x: Float, y: Float, rr: Float, a: Float) {
        val n = numbers[b]
        val c = ballColour(env, b)
        g.disc(x + rr * 0.18f, y - rr * 0.22f, rr * 1.02f, 0xFF000000.toInt(), 0.35f * a)
        if (n > 8) {
            g.disc(x, y, rr, STRIPE_WHITE, a)
            g.rect(x, y, rr * 0.8f, rr * 0.44f, c, band[b], a)
        } else g.disc(x, y, rr, c, a)
        if (n in 1..15) g.disc(x, y, rr * 0.36f, STRIPE_WHITE, 0.85f * a)
        g.disc(x - rr * 0.38f, y + rr * 0.4f, rr * 0.22f, 0xFFFFFFFF.toInt(), 0.55f * a)
    }

    private fun dashed(g: G, x0: Float, y0: Float, x1: Float, y1: Float, a: Float) {
        val d = hypot(x1 - x0, y1 - y0)
        val n = (d / (r * 1.2f)).toInt()
        for (i in 0 until n step 2) {
            val u0 = i / n.toFloat(); val u1 = (i + 1) / n.toFloat()
            g.line(x0 + (x1 - x0) * u0, y0 + (y1 - y0) * u0, x0 + (x1 - x0) * u1, y0 + (y1 - y0) * u1, 0.0025f, Palette.TEXT_PRIMARY, a)
        }
    }

    override fun frameHalfWidth(env: SimEnv): Float = hx + RAIL_W * wpm + 0.01f

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = paidAt >= 0f && elapsed - paidAt > 3f

    override fun counters(): List<Pair<String, String>> = listOf(
        "on the table" to remaining().toString(),
        "pots called" to "$called of ${max(0, shots - 1)}",
        "scratches" to scratches.toString()
    )

    companion object {
        const val AIM = 0
        const val ROLL = 1
        const val DONE = 2
        /** A 7-foot bar table's bed, metres. */
        const val BED_WIDTH = 0.99f
        const val BALL_R = 0.028575f
        const val RAIL_W = 0.1f
        /** Jaw points this far from the corner along each cushion: a mouth of about 2.1 balls. */
        const val CORNER_MOUTH = 0.085f
        /** Half the side pocket's mouth: about 2.2 balls across. */
        const val SIDE_MOUTH = 0.062f
        const val BALL_E = 0.95f
        const val CUSHION_E = 0.75f
        const val CUSHION_GRIP = 0.92f
        /** Sliding friction μ ≈ 0.2 and rolling resistance ≈ 0.02, times g. */
        const val SLIDE_DECEL = 1.96f
        const val ROLL_DECEL = 0.2f
        /** A ball hit without spin rolls, once it stops skidding, at 5/7 of its speed. */
        const val ROLL_SHARE = 5f / 7f
        const val STOP = 0.04f
        const val CLICK_MIN = 0.06f
        const val MAX_CUT_COS = 0.34f
        const val ARRIVE = 0.45f
        const val MAX_SHOT = 6.5f
        const val SUBSTEPS = 12
        const val BREAK_WAIT = 1.2f
        const val AIM_TIME = 0.6f
        const val SINK_TIME = 0.18f
        const val RAIL = 0xFF2B211B.toInt()
        const val FELT_BASE = 0xFF0B0D12.toInt()
        const val DIAMOND = 0xFFD8D2C4.toInt()
        const val POCKET = 0xFF020304.toInt()
        const val POCKET_RIM = 0xFF15110E.toInt()
        const val CUE = 0xFFF2EFE6.toInt()
        const val EIGHT = 0xFF141519.toInt()
        const val STRIPE_WHITE = 0xFFEDEAE2.toInt()
        const val CUE_STICK = 0xFFB8925E.toInt()
        const val CUE_TIP = 0xFFE8E2D4.toInt()

        fun def(): SimDef = SimDef(
            id = "physics/billiards-break",
            title = "Break and run",
            shelf = Shelf.PHYSICS,
            family = "Collisions",
            blurb = "One break, then every ball, one pot at a time, until the table is empty.",
            formats = listOf(TrapFormat.LAST_STANDING, TrapFormat.SONG, TrapFormat.CHAIN),
            lawKnobs = setOf(LawKnob.BOUNCE),
            heroLabel = "Rack them up",
            defaultPalette = "Spectrum",
            typicalSeconds = 50f,
            // The one live dial, offered in Simple mode too.
            macros = listOf(Macro("power-macro", "Break power", Card.MOTION, mapOf("power" to 3f..10f))),
            params = listOf(
                Param.choice("rack", "Rack", listOf("15 balls", "10 balls", "6 balls"), 1, card = Card.DATA, input = ParamInput.TILES),
                Param.float("power", "Break power", 3f, 10f, 7f, card = Card.MOTION, unit = "m/s")
            ),
            create = { BilliardsSim() }
        )
    }
}
