package app.hypnotica.sims.physics

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.recipe.ParamInput
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.LawKnob
import app.hypnotica.core.sim.NO_CONTENDERS
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
 * The fastest way down.
 *
 * Beads let go together from the same point slide, without friction, to the
 * same lower point along four ramps: the straight line, Galileo's circular
 * arc, a steep dip, and the cycloid. The straight line is the shortest and
 * loses; the cycloid — the curve a point on a rolling wheel draws — wins,
 * as Johann Bernoulli proved in 1696.
 *
 * The other mode lets beads go from different heights on one cycloid; every
 * one reaches the bottom at the same instant (the tautochrone), and they keep
 * meeting there on every swing.
 *
 * Timing is exact rather than integrated: each ramp is a fine polyline, and
 * along a straight piece the speed changes at a constant rate, so the time to
 * cross it is 2·Δs / (v₀ + v₁) with each v from the energy, √(2gΔh). Summed,
 * that is the true time down the curve to well under a millisecond.
 */
class BrachistochroneSim : Sim {

    private var iso = false
    private var lanes = 4
    private var hw = 0.46f
    // Layout, world units.
    private var ax = 0f
    private var ay = 0f
    private var dx = 0f
    private var dy = 0f
    private var metres = 20f
    private var grav = 9.81f
    private var boardTop = 0f
    private var boardGap = 0f
    private var boardX0 = 0f
    private var boardX1 = 0f

    // One polyline per ramp, with its cumulative arc length and time.
    private var px = Array(0) { FloatArray(0) }
    private var py = Array(0) { FloatArray(0) }
    private var ts = Array(0) { DoubleArray(0) }
    private var total = DoubleArray(0)
    // Isochrone: where along the arch each bead starts, and the quarter period.
    private var startIdx = IntArray(0)
    private var quarter = 0.0

    private var elapsed = 0f
    private var run = 0.0
    private var finishedAt = DoubleArray(0)
    private var rank = IntArray(0)
    private var arrivals = 0
    private var meets = 0
    private var payoffFrame = false
    private var paidAt = -1f
    private var winner = -1
    private var trails = true
    private var nextTick = IntArray(0)
    private var maxT = 1.0

    override fun setup(env: SimEnv) {
        val p = env.params
        iso = p.choiceIndex("mode") == 1
        metres = p.f("height", 20f).coerceIn(2f, 60f)
        trails = p.b("trails", true)
        grav = max(0f, env.laws.gravity)
        hw = env.halfWidth
        layout()
        lanes = if (iso) ISO_BEADS else RAMPS
        if (iso) buildIso() else buildRace()
        elapsed = 0f
        run = 0.0
        finishedAt = DoubleArray(lanes) { -1.0 }
        rank = IntArray(lanes) { -1 }
        arrivals = 0
        meets = 0
        payoffFrame = false
        paidAt = -1f
        winner = -1
        nextTick = IntArray(lanes) { 1 }
        maxT = 0.0
        for (k in 0 until lanes) maxT = max(maxT, if (iso) quarter else total[k])
        if (maxT <= 0.0 || !maxT.isFinite()) maxT = 1.0
    }

    private fun layout() {
        if (hw < 1f) {
            // Upright phone: the ramps across the top, the timing board below.
            dx = 2f * hw * 0.84f
            dy = min(dx / HALF_PI, 0.56f)
            dx = dy * HALF_PI
            ax = -dx / 2f
            ay = 0.64f
            boardTop = -0.12f
            boardGap = 0.17f
            boardX0 = -hw * 0.86f
            boardX1 = hw * 0.86f
        } else {
            // Wide: ramps on the left, board on the right.
            dy = 1.0f
            dx = dy * HALF_PI
            ax = -hw * 0.92f
            ay = 0.62f
            boardX0 = ax + dx + 0.25f
            boardX1 = hw * 0.9f
            boardTop = 0.45f
            boardGap = 0.24f
        }
    }

    private fun curveTimes(xs: FloatArray, ys: FloatArray, startY: Float): DoubleArray {
        val n = xs.size
        val t = DoubleArray(n)
        // Metres per world unit: the ramp is [metres] tall.
        val scale = (metres / dy).toDouble()
        val g = grav.toDouble()
        var vPrev = 0.0
        for (i in 1 until n) {
            val ds = scale * kotlin.math.hypot((xs[i] - xs[i - 1]).toDouble(), (ys[i] - ys[i - 1]).toDouble())
            val drop = scale * (startY - ys[i]).toDouble()
            val v = sqrt(max(0.0, 2.0 * g * drop))
            val sum = vPrev + v
            t[i] = t[i - 1] + if (sum > 1e-12) 2.0 * ds / sum else Double.POSITIVE_INFINITY
            vPrev = v
        }
        return t
    }

    private fun buildRace() {
        val bx = ax + dx
        val by = ay - dy
        px = Array(RAMPS) { FloatArray(SAMPLES + 1) }
        py = Array(RAMPS) { FloatArray(SAMPLES + 1) }
        // 0: straight line.
        for (i in 0..SAMPLES) {
            val u = i / SAMPLES.toFloat()
            px[0][i] = ax + dx * u; py[0][i] = ay - dy * u
        }
        // 1: the circle through both ends with a vertical tangent at the top,
        // the curve Galileo thought fastest.
        val r = (dx * dx + dy * dy) / (2f * dx)
        val ccx = ax + r
        val endA = kotlin.math.atan2(by - ay, bx - ccx)
        for (i in 0..SAMPLES) {
            val a = PI.toFloat() + (endA + 2f * PI.toFloat() - PI.toFloat()) * (i / SAMPLES.toFloat())
            px[1][i] = ccx + r * cos(a); py[1][i] = ay + r * sin(a)
        }
        // 2: a steep dip that dives deeper than it needs to and climbs out.
        for (i in 0..SAMPLES) {
            val u = i / SAMPLES.toFloat()
            val w = 1f - u
            val x1 = ax; val y1 = ay - 1.3f * dy
            val x2 = ax + 0.3f * dx; val y2 = by - 0.3f * dy
            px[2][i] = w * w * w * ax + 3f * w * w * u * x1 + 3f * w * u * u * x2 + u * u * u * bx
            py[2][i] = w * w * w * ay + 3f * w * w * u * y1 + 3f * w * u * u * y2 + u * u * u * by
        }
        // 3: the cycloid, x = R(θ − sin θ), y = −R(1 − cos θ); the end is its
        // lowest point, θ = π, which is what fixes the width at π/2 × height.
        val rc = dy / 2f
        for (i in 0..SAMPLES) {
            val th = PI.toFloat() * cycloidU(i / SAMPLES.toFloat())
            px[3][i] = ax + rc * (th - sin(th)); py[3][i] = ay - rc * (1f - cos(th))
        }
        ts = Array(RAMPS) { curveTimes(px[it], py[it], ay) }
        total = DoubleArray(RAMPS) { ts[it][SAMPLES] }
    }

    /** Sampled densest at the top, where the bead is slowest and the curve steepest. */
    private fun cycloidU(u: Float): Float = u * u * (3f - 2f * u) * 0.5f + u * 0.5f

    private fun buildIso() {
        // One whole arch, the same height scale as the race, lowest point at
        // the centre. Each bead's time to the bottom is a quarter of the swing.
        val archW = dx
        val rc = archW / (2f * PI.toFloat())
        val cx0 = ax
        val n = SAMPLES * 2
        px = Array(1) { FloatArray(n + 1) }
        py = Array(1) { FloatArray(n + 1) }
        for (i in 0..n) {
            val th = 2f * PI.toFloat() * i / n
            px[0][i] = cx0 + rc * (th - sin(th)); py[0][i] = ay - rc * (1f - cos(th))
        }
        startIdx = IntArray(ISO_BEADS) { k -> (SAMPLES * (k / ISO_BEADS.toFloat()) * 0.86f).toInt() }
        // Each bead's own time table, from its own resting start down to the bottom.
        ts = Array(ISO_BEADS) { k ->
            val s = startIdx[k]
            val xs = px[0].copyOfRange(s, SAMPLES + 1)
            val ys = py[0].copyOfRange(s, SAMPLES + 1)
            curveTimes(xs, ys, ys[0])
        }
        total = DoubleArray(ISO_BEADS) { ts[it][ts[it].size - 1] }
        val rM = rc * metres / dy
        quarter = PI * sqrt(rM / max(1e-6f, grav).toDouble())
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        if (elapsed < HOLD) return
        if (run == 0.0) env.emit(EventKind.STEP, 0.6f, panOf(ax), 0.6f)
        val before = run
        run += h
        if (iso) stepIso(env, before) else stepRace(env, before)
    }

    private fun stepRace(env: SimEnv, before: Double) {
        for (k in 0 until RAMPS) {
            val t = total[k]
            // Quiet ticks as each bead passes the quarter marks on its ramp.
            val tick = nextTick[k]
            if (tick < 4 && run < t) {
                val markT = ts[k][SAMPLES * tick / 4]
                if (run >= markT) {
                    nextTick[k] = tick + 1
                    val i = SAMPLES * tick / 4
                    env.emit(EventKind.COMPARE, 0.3f + 0.12f * k + 0.1f * tick, panOf(px[k][i]), 0.18f)
                }
            }
            if (finishedAt[k] < 0.0 && run >= t) {
                finishedAt[k] = t
                rank[k] = arrivals++
                env.emit(EventKind.HIT, 0.9f - 0.15f * rank[k], panOf(ax + dx), if (rank[k] == 0) 0.9f else 0.5f)
                if (rank[k] == 0) {
                    winner = k
                    payoffFrame = true
                    paidAt = elapsed
                }
            }
        }
    }

    private fun stepIso(env: SimEnv, before: Double) {
        // Every bead is at the bottom at quarter, 3×quarter, 5×quarter…
        val m0 = ((before + quarter) / (2 * quarter)).toInt()
        val m1 = ((run + quarter) / (2 * quarter)).toInt()
        if (m1 > m0) {
            meets++
            for (k in 0 until ISO_BEADS) if (finishedAt[k] < 0.0) { finishedAt[k] = total[k]; rank[k] = arrivals++ }
            env.emit(EventKind.HIT, 0.55f, panOf(ax + dx / 2f), if (meets == 1) 1f else 0.6f)
            if (meets == 1) { payoffFrame = true; paidAt = elapsed }
        }
    }

    private fun panOf(x: Float) = (x / max(0.1f, hw)).coerceIn(-1f, 1f)

    /** Where a bead is, [t] seconds after leaving rest, on table [k]. */
    private fun along(table: DoubleArray, t: Double): Float {
        val n = table.size
        if (t >= table[n - 1]) return (n - 1).toFloat()
        var lo = 0; var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (table[mid] <= t) lo = mid else hi = mid
        }
        val span = table[hi] - table[lo]
        val f = if (span > 0) ((t - table[lo]) / span).toFloat() else 0f
        return lo + f
    }

    private fun beadPos(k: Int, t: Double, out: FloatArray) {
        if (!iso) {
            val f = along(ts[k], max(0.0, t))
            interp(px[k], py[k], f, out)
            return
        }
        // Down from the start, up the far side to the mirror height, back.
        val q = quarter
        val period = 4 * q
        val tau = ((max(0.0, t) % period) + period) % period
        val s = startIdx[k]
        val nHalf = SAMPLES
        val down = ts[k]
        val idx: Float = when {
            tau < q -> s + along(down, tau * total[k] / q)
            tau < 2 * q -> 2f * nHalf - (s + along(down, (2 * q - tau) * total[k] / q))
            tau < 3 * q -> 2f * nHalf - (s + along(down, (tau - 2 * q) * total[k] / q))
            else -> s + along(down, (4 * q - tau) * total[k] / q)
        }
        interp(px[0], py[0], idx, out)
    }

    private fun interp(xs: FloatArray, ys: FloatArray, f: Float, out: FloatArray) {
        val i = f.toInt().coerceIn(0, xs.size - 1)
        val j = min(i + 1, xs.size - 1)
        val u = f - i
        out[0] = xs[i] + (xs[j] - xs[i]) * u
        out[1] = ys[i] + (ys[j] - ys[i]) * u
    }

    private val tmp = FloatArray(2)

    private fun laneColour(env: SimEnv, k: Int): Int = env.palette.at((k + 0.5f) / lanes)

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val done = paidAt >= 0f
        val bx = ax + dx
        val by = ay - dy
        // The ramps.
        val rampCount = if (iso) 1 else RAMPS
        for (k in 0 until rampCount) {
            val c = if (iso) Palette.TEXT_SECONDARY else laneColour(env, k)
            val lose = !iso && done && k != winner
            val col = if (lose) Palette.lerp(c, Palette.BG, 0.55f) else c
            val w = if (!iso && done && k == winner) 0.009f else 0.006f
            val xs = px[k]; val ys = py[k]
            var i = 0
            val stride = 4
            while (i < xs.size - 1) {
                val j = min(i + stride, xs.size - 1)
                g.line(xs[i], ys[i], xs[j], ys[j], w, col)
                i = j
            }
            if (!iso) for (tick in 1..3) {
                val q = SAMPLES * tick / 4
                g.disc(xs[q], ys[q], 0.006f, col, 0.8f)
            }
        }
        if (!iso && done && winner >= 0) {
            // The winning curve lights up once it has won, easing in and then
            // holding steady (no throbbing: the user finds it disorienting).
            val pulse = 0.3f * ((elapsed - paidAt) / 0.4f).coerceIn(0f, 1f)
            val xs = px[winner]; val ys = py[winner]
            for (i in 0 until xs.size step 24) g.glow(xs[i], ys[i], 0.06f, laneColour(env, winner), pulse)
        }
        // Start post and finish post.
        g.rect(ax - 0.012f, ay + 0.03f, 0.006f, 0.05f, POST)
        val gateUp = ((elapsed - HOLD + 0.25f) / 0.25f).coerceIn(0f, 1f)
        g.rect(ax + 0.01f, ay + 0.012f + gateUp * 0.06f, 0.02f, 0.004f, Palette.TEXT_SECONDARY)
        val fx = if (iso) ax + dx / 2f else bx
        val fy = if (iso) ay - dx / PI.toFloat() else by
        g.rect(fx, fy - 0.035f, 0.004f, 0.03f, POST)
        g.disc(fx, fy, 0.008f, Palette.TEXT_PRIMARY, 0.7f)

        // The beads, with faint afterimages of where they just were.
        val t = run
        for (k in 0 until lanes) {
            val c = laneColour(env, k)
            val arrived = finishedAt[k] >= 0.0 && !iso
            if (trails && t > 0.0 && !arrived) {
                for (a in 1..6) {
                    beadPos(k, t - a * 0.045, tmp)
                    g.disc(tmp[0], tmp[1], BEAD * (1f - a * 0.11f), c, 0.32f - a * 0.045f)
                }
            }
            beadPos(k, t, tmp)
            val x = tmp[0]; val y = tmp[1]
            if (arrived) {
                val since = (t - finishedAt[k]).toFloat()
                if (since < 0.6f) g.ring(x, y, BEAD * (1.2f + 4f * since), 0.004f, c, alpha = 0.8f * (1f - since / 0.6f))
                if (rank[k] == 0) { g.glow(x, y, BEAD * 6f, c, 0.6f); g.disc(x, y, BEAD * 1.15f, Palette.brighten(c, 0.15f)) }
                continue
            }
            g.glow(x, y, BEAD * 3.5f, c, 0.35f)
            g.disc(x, y, BEAD, c)
            g.disc(x - BEAD * 0.3f, y + BEAD * 0.3f, BEAD * 0.32f, Palette.TEXT_PRIMARY, 0.7f)
        }
        drawBoard(env, g)
    }

    /** One lane per bead: its ramp in miniature, then a bar of its time so far. */
    private fun drawBoard(env: SimEnv, g: G) {
        val iconW = 0.11f
        val x0 = boardX0 + iconW + 0.05f
        val x1 = boardX1
        val span = x1 - x0
        val fullT = maxT * 1.06
        for (k in 0 until lanes) {
            val y = boardTop - k * boardGap
            val c = laneColour(env, k)
            val done = finishedAt[k] >= 0.0
            val first = done && rank[k] == 0 && !iso
            val dim = !iso && paidAt >= 0f && !first
            // The icon: this lane's ramp, scaled into a little box.
            val xs = px[if (iso) 0 else k]; val ys = py[if (iso) 0 else k]
            val sx = iconW / (xs[xs.size - 1] - xs[0])
            val sy = boardGap * 0.55f / dy
            val ox = boardX0; val oy = y + boardGap * 0.27f
            var i = 0
            val stride = xs.size / 16
            while (i < xs.size - 1) {
                val j = min(i + stride, xs.size - 1)
                g.line(ox + (xs[i] - xs[0]) * sx, oy + (ys[i] - ys[0]) * sy, ox + (xs[j] - xs[0]) * sx, oy + (ys[j] - ys[0]) * sy, 0.004f, if (dim) Palette.lerp(c, Palette.BG, 0.5f) else c)
                i = j
            }
            if (iso) g.disc(ox + (xs[startIdx[k]] - xs[0]) * sx, oy + (ys[startIdx[k]] - ys[0]) * sy, 0.008f, c)
            // The time axis and the bar.
            g.rect(x0 + span / 2f, y, span / 2f, 0.0015f, AXIS)
            val tNow = if (done) finishedAt[k] else if (iso) min(run, quarter) else min(run, total[k])
            val len = (span * (tNow / fullT)).toFloat().coerceIn(0f, span)
            if (len > 0.002f) {
                val col = if (dim) Palette.lerp(c, Palette.BG, 0.5f) else c
                g.rect(x0 + len / 2f, y, len / 2f, 0.016f, col)
                if (done) g.rect(x0 + len, y, 0.004f, 0.03f, Palette.TEXT_PRIMARY, alpha = if (dim) 0.4f else 1f)
                if (first) g.glow(x0 + len, y, 0.08f, c, 0.6f)
            }
        }
        // Seconds along the bottom of the board, so the bars have a scale.
        val yb = boardTop - (lanes - 1) * boardGap - boardGap * 0.45f
        var s = 0
        while (s <= fullT) {
            val x = x0 + (span * (s / fullT)).toFloat()
            g.rect(x, yb, 0.0015f, if (s % 5 == 0) 0.014f else 0.008f, AXIS)
            s++
        }
    }

    override fun contenders(env: SimEnv): IntArray =
        if (iso) NO_CONTENDERS else IntArray(RAMPS) { laneColour(env, it) }

    override fun winner(): Int = if (iso) -1 else winner

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean {
        if (paidAt < 0f) return false
        return if (iso) meets >= 3 && elapsed - paidAt > 1f else arrivals >= RAMPS && elapsed - paidAt > 2.5f
    }

    override fun counters(): List<Pair<String, String>> {
        val l = java.util.Locale.US
        if (iso) {
            return listOf(
                "time to the bottom" to String.format(l, "%.2f s, from every height", quarter),
                "π√(R/g)" to String.format(l, "%.2f s", quarter),
                "meetings" to meets.toString()
            )
        }
        val out = ArrayList<Pair<String, String>>(4)
        for (k in 0 until RAMPS) {
            val v = if (finishedAt[k] >= 0.0) String.format(l, "%.3f s", finishedAt[k]) else String.format(l, "%.2f s", min(run, total[k]))
            out.add(NAMES[k] to v)
        }
        return out
    }

    companion object {
        const val RAMPS = 4
        const val ISO_BEADS = 5
        const val SAMPLES = 1200
        const val HOLD = 1.2f
        const val BEAD = 0.019f
        const val HALF_PI = (PI / 2).toFloat()
        val NAMES = listOf("straight", "circle", "steep dip", "cycloid")
        const val POST = 0xFF3A404D.toInt()
        const val AXIS = 0xFF2A2F3A.toInt()

        fun def(): SimDef = SimDef(
            id = "physics/brachistochrone",
            title = "Fastest way down",
            shelf = Shelf.PHYSICS,
            family = "Mechanics",
            blurb = "Four ramps, one start, one finish. The shortest path loses.",
            formats = listOf(TrapFormat.RACE, TrapFormat.GUESS),
            lawKnobs = setOf(LawKnob.GRAVITY),
            heroLabel = "Race again",
            defaultPalette = "Aurora",
            typicalSeconds = 6f,
            params = listOf(
                Param.choice("mode", "Mode", listOf("Race the ramps", "Same-time drop"), 0, card = Card.DATA, input = ParamInput.TILES),
                Param.float("height", "Ramp height", 2f, 60f, 20f, card = Card.MOTION, unit = "m"),
                Param.bool("trails", "Afterimages", true, card = Card.LOOK)
            ),
            create = { BrachistochroneSim() }
        )
    }
}
