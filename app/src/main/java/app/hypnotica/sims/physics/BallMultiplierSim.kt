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
import app.hypnotica.core.sim.TouchEvent
import app.hypnotica.core.sim.TrapFormat
import app.hypnotica.sims.algorithms.SegDigits
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The marble multiplier machine: a handful of balls dropped from a sliding
 * hopper fall through three rows of gates. A ball through a ×3 gate comes out
 * as three; through the red one it is gone. Between the rows, pegs scatter
 * them, so where each lands is anyone's guess, and the gates slide, so the
 * same drop never meets the same row twice. Four cups catch whatever is left.
 *
 * Two things to watch at once: how high the count goes, and which cup ends
 * up fullest. Nothing is scripted — every ball is its own little fall — and
 * there is a ceiling on balls so the phone keeps up; at the ceiling the gates
 * stop multiplying and say so.
 */
class BallMultiplierSim : Sim {

    // The falling balls, packed: position, velocity, how many gate rows passed.
    private var x = FloatArray(MAX_BALLS)
    private var y = FloatArray(MAX_BALLS)
    private var vx = FloatArray(MAX_BALLS)
    private var vy = FloatArray(MAX_BALLS)
    private var row = IntArray(MAX_BALLS)
    private var live = 0

    // The landed balls, by cup, in the order they landed.
    private val cupCount = IntArray(CUPS)
    private var landed = 0
    private var killed = 0
    private var made = 0

    private var hw = 0.45f
    private var ballR = 0.009f
    private var cupW = 0.2f

    private val gateY = floatArrayOf(0.5f, 0.08f, -0.32f)
    private val gateKind = Array(GATE_ROWS) { IntArray(SEGMENTS) }
    private val gateEdge = Array(GATE_ROWS) { FloatArray(SEGMENTS + 1) }
    private val gateOffset = FloatArray(GATE_ROWS)
    private val gateDir = FloatArray(GATE_ROWS)
    private val gateLit = Array(GATE_ROWS) { FloatArray(SEGMENTS) }
    private val calledFive = BooleanArray(GATE_ROWS)

    private var pegRows = 0
    private val pegY = FloatArray(MAX_PEG_ROWS)
    /** Each row's pegs sit at (k - centre) × gap, k from 0 to count - 1: symmetric about the middle. */
    private val pegCentre = FloatArray(MAX_PEG_ROWS)
    private val pegCount = IntArray(MAX_PEG_ROWS)
    private var pegGap = 0.08f

    private var toDrop = 0
    private var dropClock = 0f
    private var hopperX = 0f
    private var hopperPhase = 0f
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var winner = -1
    private var mark = 0
    private var capped = false
    private var noteBudget = 0f
    private var pegBudget = 0f

    override fun setup(env: SimEnv) {
        hw = env.halfWidth * 0.94f
        cupW = 2f * hw / CUPS
        ballR = BALL_R
        live = 0; landed = 0; killed = 0; made = 0
        cupCount.fill(0)
        toDrop = env.params.i("balls", 12).coerceIn(1, 60)
        made = toDrop
        dropClock = 0f
        hopperPhase = env.rng.range(0f, 2f * PI.toFloat())
        elapsed = 0f; doneAt = -1f; payoffFrame = false; winner = -1; mark = 0; capped = false
        noteBudget = 0f; pegBudget = 0f
        calledFive.fill(false)

        // Each row of gates: a ×2, a ×3, a narrow ×5 and a red one, in a
        // fresh order and fresh widths, sliding one way or the other.
        for (r in 0 until GATE_ROWS) {
            val kinds = intArrayOf(2, 3, 5, 10, 0)
            for (i in kinds.size - 1 downTo 1) { val j = env.rng.int(0, i + 1); val t = kinds[i]; kinds[i] = kinds[j]; kinds[j] = t }
            val w = FloatArray(SEGMENTS) { share(kinds[it]) * env.rng.range(0.8f, 1.2f) }
            val sum = w.sum()
            gateEdge[r][0] = 0f
            for (i in 0 until SEGMENTS) {
                gateKind[r][i] = kinds[i]
                gateEdge[r][i + 1] = gateEdge[r][i] + w[i] / sum * 2f * hw
            }
            gateOffset[r] = env.rng.range(0f, 2f * hw)
            gateDir[r] = if (r % 2 == 0) 1f else -1f
            gateLit[r].fill(0f)
        }

        // Pegs between the rows: staggered lines, a ball's width or so apart
        // more than the ball, so they scatter rather than trap.
        pegGap = max(0.07f, 2f * hw / 7f)
        pegRows = 0
        val tops = floatArrayOf(gateY[0] - 0.08f, gateY[1] - 0.08f, gateY[2] - 0.08f)
        val bottoms = floatArrayOf(gateY[1] + 0.06f, gateY[2] + 0.06f, CUP_TOP + 0.06f)
        for (b in 0 until 3) {
            var py = tops[b]
            var k = 0
            while (py > bottoms[b] && pegRows < MAX_PEG_ROWS) {
                pegY[pegRows] = py
                val m = max(1, floor(2f * hw / pegGap).toInt())
                // The offset rows run right to the walls, so nothing slides
                // down the side untouched.
                val count = if ((k + b) % 2 == 0) m else m + 1
                pegCount[pegRows] = count
                pegCentre[pegRows] = (count - 1) * 0.5f
                pegRows++; k++
                py -= PEG_ROW_GAP
            }
        }
    }

    /** How wide each kind of gate is, before the jitter. */
    private fun share(kind: Int): Float = when (kind) { 2 -> 0.33f; 3 -> 0.25f; 5 -> 0.14f; 10 -> 0.07f; else -> 0.21f }

    private fun cupOf(px: Float): Int = ((px + hw) / cupW).toInt().coerceIn(0, CUPS - 1)

    /**
     * The size a cup packs its balls at: full size while they fit, smaller as
     * the cup gets crowded, so a big haul packs down into its cup instead of
     * towering up through the pegs.
     */
    private fun packR(cup: Int): Float {
        val n = max(1, cupCount[cup])
        val fit = sqrt(cupW * (CUP_TOP - CUP_FLOOR) * 0.9f / (3.5f * n))
        return min(ballR, fit)
    }

    private fun perRowOf(rr: Float): Int = max(3, floor(cupW * 0.96f / (2f * rr)).toInt())

    /** The top of a cup's pile: rows of balls, hexagonally packed. */
    private fun pileTop(cup: Int): Float {
        val rr = packR(cup)
        val rows = (cupCount[cup] + perRowOf(rr) - 1) / perRowOf(rr)
        return CUP_FLOOR + rows * rr * 1.75f + rr
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val pace = env.params.f("pace", 1f).coerceIn(0.2f, 3f)
        val h0 = abs(dt) * pace
        elapsed += h0
        noteBudget = min(1.5f, noteBudget + abs(dt) * NOTES_PER_SECOND)
        pegBudget = min(1.5f, pegBudget + abs(dt) * PEG_NOTES_PER_SECOND)
        val gateSpeed = env.params.f("slide", 1f).coerceIn(0f, 3f)
        for (r in 0 until GATE_ROWS) {
            gateOffset[r] = ((gateOffset[r] + gateDir[r] * GATE_SPEED * (1f + 0.3f * r) * gateSpeed * h0) % (2f * hw) + 2f * hw) % (2f * hw)
            for (i in 0 until SEGMENTS) gateLit[r][i] = max(0f, gateLit[r][i] - abs(dt) * 3f)
        }
        // Back and forth at an even speed: a sine lingers at its ends and
        // dropped twice as many balls over the outer cups.
        val u = ((hopperPhase + elapsed * HOPPER_SPEED) / (2f * PI.toFloat())) % 1f
        hopperX = (if (u < 0.5f) 4f * u - 1f else 3f - 4f * u) * hw * 0.7f
        if (doneAt >= 0f) return

        // The hopper lets one go every so often.
        if (toDrop > 0) {
            dropClock -= h0
            if (dropClock <= 0f) {
                dropClock = DROP_EVERY
                toDrop--
                spawn(hopperX, HOPPER_Y, env.rng.range(-0.08f, 0.08f), 0f, 0)
                env.emit(EventKind.STEP, 0.7f, (hopperX / hw).coerceIn(-1f, 1f), 0.25f)
            }
        }

        val sub = SUBSTEPS
        val h = h0 / sub
        repeat(sub) { move(env, h) }

        val total = landed + live + toDrop
        while (mark < MARKS.size && total >= MARKS[mark]) {
            if (mark == MARKS.size - 1 || total < MARKS[mark + 1]) {
                env.callout(String.format(java.util.Locale.US, if (MARKS[mark] >= 1000) "%,d!!" else "%,d!", MARKS[mark]), big = MARKS[mark] >= 1000)
            }
            mark++
        }

        if (toDrop == 0 && live == 0) {
            doneAt = elapsed
            payoffFrame = true
            var best = 0; var second = -1
            for (c in 1 until CUPS) if (cupCount[c] > cupCount[best]) best = c
            for (c in 0 until CUPS) if (c != best && (second < 0 || cupCount[c] > cupCount[second])) second = c
            winner = if (landed > 0) best else -1
            val close = second >= 0 && cupCount[best] > 0 && cupCount[best] - cupCount[second] <= max(1, cupCount[best] / 30)
            env.callout(
                when {
                    landed == 0 -> "Wiped out!"
                    close -> "Photo finish!"
                    else -> String.format(java.util.Locale.US, "%,d balls!", landed)
                },
                big = true
            )
        }
    }

    private fun spawn(px: Float, py: Float, pvx: Float, pvy: Float, passed: Int) {
        if (live >= MAX_BALLS) return
        x[live] = px; y[live] = py; vx[live] = pvx; vy[live] = pvy; row[live] = passed
        live++
    }

    private fun remove(i: Int) {
        live--
        x[i] = x[live]; y[i] = y[live]; vx[i] = vx[live]; vy[i] = vy[live]; row[i] = row[live]
    }

    private fun move(env: SimEnv, h: Float) {
        val grav = GRAVITY * env.laws.gravity.coerceIn(0.1f, 3f)
        var i = 0
        while (i < live) {
            val oy = y[i]
            vy[i] = max(-TERMINAL, vy[i] - grav * h)
            vx[i] *= (1f - DRAG * h)
            x[i] += vx[i] * h
            y[i] += vy[i] * h
            // The side walls.
            val lim = hw - ballR
            if (x[i] < -lim) { x[i] = -lim; vx[i] = abs(vx[i]) * WALL_E }
            if (x[i] > lim) { x[i] = lim; vx[i] = -abs(vx[i]) * WALL_E }

            // A gate row crossed on the way down.
            val r = row[i]
            if (r < GATE_ROWS && oy >= gateY[r] && y[i] < gateY[r]) {
                row[i] = r + 1
                val seg = segmentAt(r, x[i])
                val kind = gateKind[r][seg]
                gateLit[r][seg] = 1f
                if (kind == 0) {
                    killed++
                    if (noteBudget >= 1f) { noteBudget -= 1f; env.emit(EventKind.DEATH, 0.2f, (x[i] / hw).coerceIn(-1f, 1f), 0.3f) }
                    remove(i)
                    continue
                }
                if (kind >= 5 && !calledFive[r]) { calledFive[r] = true; env.callout(if (kind == 10) "×10!" else "×5!", big = kind == 10) }
                var room = MAX_BALLS - live
                if (room < kind - 1 && !capped) { capped = true; env.callout("MAX!", big = true) }
                var k = 1
                while (k < kind && room > 0) {
                    val spread = 0.08f + 0.012f * kind
                    spawn(x[i], y[i], vx[i] + env.rng.range(-spread, spread), vy[i] * env.rng.range(0.8f, 1f), r + 1)
                    made++
                    room--
                    k++
                }
                if (noteBudget >= 1f) {
                    noteBudget -= 1f
                    val kick = if (kind >= 5) EventKind.CROSS else EventKind.HIT
                    env.emit(kick, (0.3f + 0.06f * kind + 0.08f * r).coerceAtMost(1f), (x[i] / hw).coerceIn(-1f, 1f), (0.25f + 0.05f * kind).coerceAtMost(0.8f))
                }
            }

            // Pegs: the nearest one in the nearest row.
            if (y[i] > CUP_TOP) peg(env, i)

            // Into a cup: the dividers, then the pile.
            if (y[i] < CUP_TOP) {
                val c = cupOf(x[i])
                val leftWall = -hw + c * cupW
                val rightWall = leftWall + cupW
                if (x[i] - ballR < leftWall + DIVIDER && c > 0 && oy < CUP_TOP + 0.001f) { x[i] = leftWall + DIVIDER + ballR; vx[i] = abs(vx[i]) * WALL_E }
                if (x[i] + ballR > rightWall - DIVIDER && c < CUPS - 1 && oy < CUP_TOP + 0.001f) { x[i] = rightWall - DIVIDER - ballR; vx[i] = -abs(vx[i]) * WALL_E }
                if (y[i] <= pileTop(c)) {
                    cupCount[c]++
                    landed++
                    if (pegBudget >= 1f) { pegBudget -= 1f; env.emit(EventKind.STEP, 0.15f + 0.2f * c, (x[i] / hw).coerceIn(-1f, 1f), 0.12f) }
                    remove(i)
                    continue
                }
            }
            i++
        }
    }

    private fun segmentAt(r: Int, px: Float): Int {
        val span = 2f * hw
        val u = ((px + hw - gateOffset[r]) % span + span) % span
        for (s in 0 until SEGMENTS) if (u < gateEdge[r][s + 1]) return s
        return SEGMENTS - 1
    }

    private fun peg(env: SimEnv, i: Int) {
        // Rows are evenly spaced down each band; find the one this ball is level with.
        var best = -1
        var bd = PEG_R + ballR
        for (p in 0 until pegRows) {
            val d = abs(y[i] - pegY[p])
            if (d < bd) { bd = d; best = p }
        }
        if (best < 0) return
        val py = pegY[best]
        val k = Math.round(x[i] / pegGap + pegCentre[best]).coerceIn(0, pegCount[best] - 1)
        val px = (k - pegCentre[best]) * pegGap
        val dx = x[i] - px; val dy = y[i] - py
        val d2 = dx * dx + dy * dy
        val reach = PEG_R + ballR
        if (d2 >= reach * reach || d2 < 1e-10f) return
        val d = sqrt(d2)
        val nx = dx / d; val ny = dy / d
        val vn = vx[i] * nx + vy[i] * ny
        if (vn < 0f) {
            vx[i] -= (1f + PEG_E) * vn * nx
            vy[i] -= (1f + PEG_E) * vn * ny
            // A nudge either way, so a ball dead on a peg does not balance.
            vx[i] += env.rng.range(-0.04f, 0.04f)
            if (pegBudget >= 1f && -vn > 0.25f) {
                pegBudget -= 1f
                env.emit(EventKind.HIT, (0.5f + py * 0.4f).coerceIn(0f, 1f), (px / hw).coerceIn(-1f, 1f), 0.1f)
            }
        }
        x[i] = px + nx * reach
        y[i] = py + ny * reach
    }

    override fun onInput(env: SimEnv, e: TouchEvent) {
        if (e.kind != TouchEvent.Kind.TAP || doneAt >= 0f) return
        // A tap drops one more from where the finger is, if it is above the first row.
        if (e.y > gateY[0] + 0.05f) { spawn(e.x.coerceIn(-hw + ballR, hw - ballR), min(e.y, HOPPER_Y), 0f, 0f, 0); made++ }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        g.reserveInstances(live + landed + pegRows * 12 + 80)
        // The cups: walls, a floor, each pile in its cup's colour.
        val wallC = Palette.withAlpha(Palette.TEXT_PRIMARY, 0.4f)
        g.rect(0f, CUP_FLOOR - 0.006f, hw, 0.006f, wallC)
        for (c in 0..CUPS) {
            val wx = -hw + c * cupW
            g.rect(wx, (CUP_TOP + CUP_FLOOR) * 0.5f, DIVIDER, (CUP_TOP - CUP_FLOOR) * 0.5f, wallC)
        }
        for (c in 0 until CUPS) {
            val col = cupColour(env, c)
            if (doneAt >= 0f && c == winner) {
                g.glow(-hw + (c + 0.5f) * cupW, (CUP_TOP + CUP_FLOOR) * 0.5f, cupW, col, 0.35f)
            }
            val n = cupCount[c]
            val rr = packR(c)
            val per = perRowOf(rr)
            val left = -hw + c * cupW + (cupW - per * 2f * rr) * 0.5f + rr
            for (k in 0 until n) {
                val pr = k / per
                val px = left + (k % per) * 2f * rr + if (pr % 2 == 1) rr * 0.5f else 0f
                g.disc(px, CUP_FLOOR + rr + pr * rr * 1.75f, rr * 0.92f, col)
            }
            // The count, under the cup.
            drawCount(g, -hw + (c + 0.5f) * cupW, CUP_FLOOR - 0.05f, n, col)
        }
        // Pegs.
        val pegC = Palette.withAlpha(Palette.TEXT_SECONDARY, 0.7f)
        for (p in 0 until pegRows) {
            for (k in 0 until pegCount[p]) g.disc((k - pegCentre[p]) * pegGap, pegY[p], PEG_R, pegC)
        }
        // Gates: coloured bands, the multiplier drawn on each.
        val span = 2f * hw
        for (r in 0 until GATE_ROWS) {
            for (s in 0 until SEGMENTS) {
                val kind = gateKind[r][s]
                val col = gateColour(kind)
                val lit = gateLit[r][s]
                // A band can wrap round the edge: drawn in up to two pieces.
                var a = gateEdge[r][s] + gateOffset[r]
                var b = gateEdge[r][s + 1] + gateOffset[r]
                if (a >= span) { a -= span; b -= span }
                drawBand(g, a, min(b, span), gateY[r], col, lit, kind)
                if (b > span) drawBand(g, 0f, b - span, gateY[r], col, lit, -1)
            }
        }
        // The hopper.
        if (toDrop > 0) {
            g.rect(hopperX - 0.035f, HOPPER_Y + 0.03f, 0.004f, 0.03f, wallC, rot = -0.5f)
            g.rect(hopperX + 0.035f, HOPPER_Y + 0.03f, 0.004f, 0.03f, wallC, rot = 0.5f)
        }
        // Falling balls, coloured by how many rows they have come through.
        for (i in 0 until live) {
            val col = Palette.brighten(pal.at(0.1f + 0.27f * row[i]), 0.25f)
            g.disc(x[i], y[i], ballR, col)
        }
    }

    private fun drawBand(g: G, a: Float, b: Float, gy: Float, col: Int, lit: Float, kind: Int) {
        if (b - a < 0.002f) return
        val cx = -hw + (a + b) * 0.5f
        g.rect(cx, gy, (b - a) * 0.5f - 0.003f, GATE_H, col, alpha = 0.28f + 0.4f * lit)
        g.rect(cx, gy + GATE_H, (b - a) * 0.5f - 0.003f, 0.0025f, col, alpha = 0.9f)
        if (lit > 0f) g.glow(cx, gy, (b - a) * 0.5f, col, 0.25f * lit)
        if (kind < 0 || b - a < 0.045f) return
        // The label: × and the digit, or a cross for the red one.
        val lh = GATE_H * 1.45f
        if (kind == 0) {
            g.rect(cx, gy, lh * 0.5f, lh * 0.09f, Palette.TEXT_PRIMARY, rot = 0.785f)
            g.rect(cx, gy, lh * 0.5f, lh * 0.09f, Palette.TEXT_PRIMARY, rot = -0.785f)
        } else {
            val tx = cx - lh * 0.35f
            g.rect(tx, gy, lh * 0.28f, lh * 0.07f, Palette.TEXT_PRIMARY, rot = 0.785f)
            g.rect(tx, gy, lh * 0.28f, lh * 0.07f, Palette.TEXT_PRIMARY, rot = -0.785f)
            if (kind < 10) SegDigits.digit(g, cx + lh * 0.3f, gy, lh, kind, Palette.TEXT_PRIMARY)
            else {
                SegDigits.digit(g, cx + lh * 0.25f, gy, lh, 1, Palette.TEXT_PRIMARY)
                SegDigits.digit(g, cx + lh * 0.85f, gy, lh, 0, Palette.TEXT_PRIMARY)
            }
        }
    }

    private fun drawCount(g: G, cx: Float, cy: Float, n: Int, col: Int) {
        val h = 0.04f
        val digits = n.toString()
        val w = h * SegDigits.ASPECT * 1.25f
        var dx = cx - (digits.length - 1) * w * 0.5f
        for (ch in digits) { SegDigits.digit(g, dx, cy, h, ch - '0', col); dx += w }
    }

    private fun gateColour(kind: Int): Int = when (kind) {
        2 -> 0xFF4C9AFF.toInt()
        3 -> 0xFF36D399.toInt()
        5 -> 0xFFFFC53D.toInt()
        10 -> 0xFFC77DFF.toInt()
        else -> 0xFFFF4D5E.toInt()
    }

    private fun cupColour(env: SimEnv, c: Int): Int = env.palette.at((c + 0.5f) / CUPS)

    override fun contenders(env: SimEnv): IntArray = IntArray(CUPS) { cupColour(env, it) }

    override fun winner(): Int = winner

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && elapsed - doneAt > 4f

    override fun scoreForBest(): Float = (landed + live + toDrop).toFloat()
    override fun scoreForBestText(value: Float): String = String.format(java.util.Locale.US, "%,d balls", value.toInt())
    override fun liveBest(): Boolean = true

    override fun counters(): List<Pair<String, String>> = listOf(
        "balls" to String.format(java.util.Locale.US, "%,d", landed + live + toDrop),
        "lost to red" to String.format(java.util.Locale.US, "%,d", killed)
    )

    companion object {
        const val CUPS = 4
        const val GATE_ROWS = 3
        const val SEGMENTS = 5
        const val MAX_BALLS = 1600
        const val MAX_PEG_ROWS = 24
        const val BALL_R = 0.0085f
        const val PEG_R = 0.008f
        const val PEG_ROW_GAP = 0.07f
        const val GATE_H = 0.028f
        const val DIVIDER = 0.003f
        const val CUP_TOP = -0.52f
        const val CUP_FLOOR = -0.9f
        const val HOPPER_Y = 0.86f
        const val HOPPER_SPEED = 1.3f
        const val DROP_EVERY = 0.33f
        const val GATE_SPEED = 0.12f
        const val GRAVITY = 1.6f
        const val TERMINAL = 1.1f
        const val DRAG = 0.4f
        const val WALL_E = 0.75f
        const val PEG_E = 0.45f
        const val SUBSTEPS = 4
        const val NOTES_PER_SECOND = 22f
        const val PEG_NOTES_PER_SECOND = 14f
        val MARKS = intArrayOf(100, 250, 500, 1000)

        fun def(): SimDef = SimDef(
            id = "physics/ball-multiplier",
            title = "Ball multiplier",
            shelf = Shelf.PHYSICS,
            family = "Chance",
            blurb = "A dozen balls, three rows of ×2, ×3, ×5, ×10 — and one red gate. How many make it, and which cup fills?",
            formats = listOf(TrapFormat.NUMBER_UP, TrapFormat.GUESS),
            heroLabel = "Drop again",
            defaultPalette = "Spectrum",
            typicalSeconds = 10f,
            params = listOf(
                Param.int("balls", "Balls in the hopper", 1, 60, 12, card = Card.DATA),
                Param.float("pace", "Pace", 0.2f, 3f, 1f, card = Card.MOTION, logScale = true),
                Param.float("slide", "Gate slide", 0f, 3f, 1f, card = Card.MOTION)
            ),
            create = { BallMultiplierSim() }
        )
    }
}
