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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A pendulum swinging over three magnets, seen from above.
 *
 * Let go, the bob wanders between them chaotically and in the end settles over
 * one. Which one depends on where it started — so sensitively that the map of
 * "start here, end there", painted in behind it a start point at a time, is a
 * fractal: big calm regions near each magnet, and between them bands that
 * split into finer bands without end.
 *
 * The bob obeys the textbook model: a restoring pull to the centre (the
 * pendulum, small swings), air drag, and each magnet's attraction falling off
 * as the inverse square of the distance through the gap h between bob and
 * table. The map is computed coarse to fine, so it sharpens like a photo
 * loading rather than wiping in.
 */
class MagnetPendulumSim : Sim {

    private var magnets = 3
    private var mx = FloatArray(0)
    private var my = FloatArray(0)
    private var k = 0.5f
    private var drag = 0.2f
    private var gap2 = 0.0625f
    // World units per model unit.
    private var scale = 0.3f
    private var hw = 0.46f

    // The map.
    private var cols = 0
    private var rows = 0
    private var cell = 0.01f
    private var disp = ByteArray(0)
    private var stride = 32
    private var cursor = 0
    private var levels = 0
    private var levelsDone = 0
    private var computed = 0
    private var totalCells = 0
    private var complete = false
    // The background run in progress.
    private var bx = 0f; private var by = 0f; private var bvx = 0f; private var bvy = 0f
    private var bsteps = 0
    private var bCell = -1

    // The hero run on screen.
    private var hx = 0f; private var hy = 0f; private var hvx = 0f; private var hvy = 0f
    private var hSettled = -1
    private var hRestAt = 0f
    private var hNear = -1
    private val trailX = FloatArray(TRAIL)
    private val trailY = FloatArray(TRAIL)
    private var trailHead = 0
    private var trailLen = 0
    private var hStartX = 0f
    private var hStartY = 0f
    private var runs = 0
    private val tally = IntArray(MAX_MAGNETS)

    private var elapsed = 0f
    private var payoffFrame = false
    private var paidAt = -1f
    private var showTrail = true
    private val acc = FloatArray(2)

    override fun setup(env: SimEnv) {
        val p = env.params
        magnets = p.i("magnets", 3).coerceIn(2, MAX_MAGNETS)
        drag = p.f("drag", 0.2f).coerceIn(0.08f, 0.5f)
        val h = p.f("gap", 0.25f).coerceIn(0.12f, 0.5f)
        gap2 = h * h
        showTrail = p.b("trail", true)
        k = SPRING * env.laws.gravity / 9.81f
        hw = env.halfWidth
        scale = min(hw, 1f) * 0.62f
        mx = FloatArray(magnets) { cos(PI / 2 + 2 * PI * it / magnets).toFloat() }
        my = FloatArray(magnets) { sin(PI / 2 + 2 * PI * it / magnets).toFloat() }
        // Square cells sized so the whole screen is about the same number of
        // starts on any shape of screen.
        cell = sqrt(2f * hw * 2f / CELLS)
        cols = ceil(2f * hw / cell).toInt()
        rows = ceil(2f / cell).toInt()
        totalCells = cols * rows
        disp = ByteArray(totalCells) { -1 }
        stride = 1
        while (stride * 2 <= FIRST_STRIDE) stride *= 2
        levels = 0
        var s = stride
        while (s >= 1) { levels++; s /= 2 }
        levelsDone = 0
        cursor = 0
        computed = 0
        complete = false
        bCell = -1
        tally.fill(0)
        runs = 0
        elapsed = 0f
        payoffFrame = false
        paidAt = -1f
        newHero(env)
    }

    private fun newHero(env: SimEnv) {
        // Somewhere visible, away from dead centre.
        val ext = min(hw, 1f) / scale * 0.9f
        val a = env.rng.range(0f, 2f * PI.toFloat())
        val r = env.rng.range(0.9f, ext.coerceAtLeast(1.2f))
        hx = cos(a) * r; hy = sin(a) * r
        hvx = 0f; hvy = 0f
        hStartX = hx; hStartY = hy
        hSettled = -1
        hNear = -1
        trailHead = 0; trailLen = 0
    }

    private fun accel(x: Float, y: Float, vx: Float, vy: Float) {
        var ax = -k * x - drag * vx
        var ay = -k * y - drag * vy
        for (i in 0 until magnets) {
            val dx = mx[i] - x; val dy = my[i] - y
            val d2 = dx * dx + dy * dy + gap2
            val inv = 1f / (d2 * sqrt(d2))
            ax += dx * inv; ay += dy * inv
        }
        acc[0] = ax; acc[1] = ay
    }

    /** The magnet a resting bob sits over, or −1 while it is still moving. */
    private fun settledOn(x: Float, y: Float, vx: Float, vy: Float): Int {
        if (vx * vx + vy * vy > SETTLE_V2) return -1
        var best = -1; var bd = SETTLE_R2
        for (i in 0 until magnets) {
            val dx = mx[i] - x; val dy = my[i] - y
            val d = dx * dx + dy * dy
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    private fun nearest(x: Float, y: Float): Int {
        var best = 0; var bd = Float.MAX_VALUE
        for (i in 0 until magnets) {
            val dx = mx[i] - x; val dy = my[i] - y
            val d = dx * dx + dy * dy
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    /** One step of kick-drift-kick, the drag folded into each kick. */
    private fun leap(x: FloatArray) {
        accel(x[0], x[1], x[2], x[3])
        x[2] += acc[0] * HDT; x[3] += acc[1] * HDT
        x[0] += x[2] * DT; x[1] += x[3] * DT
        accel(x[0], x[1], x[2], x[3])
        x[2] += acc[0] * HDT; x[3] += acc[1] * HDT
    }

    private val st = FloatArray(4)

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        k = SPRING * env.laws.gravity / 9.81f
        if (!complete) paint(env)
        stepHero(env, h)
    }

    private fun paint(env: SimEnv) {
        var budget = (EVALS * env.params.f("paint", 1f).coerceIn(0.25f, 3f)).toInt()
        while (budget > 0 && !complete) {
            if (bCell < 0) {
                bCell = nextCell(env) ?: return
                val i = bCell % cols; val j = bCell / cols
                bx = (-hw + (i + 0.5f) * cell) / scale
                by = (1f - (j + 0.5f) * cell) / scale
                bvx = 0f; bvy = 0f; bsteps = 0
            }
            st[0] = bx; st[1] = by; st[2] = bvx; st[3] = bvy
            var end = -1
            while (budget > 0) {
                leap(st)
                bsteps++
                budget--
                if (bsteps % 8 == 0) {
                    end = settledOn(st[0], st[1], st[2], st[3])
                    if (end >= 0) break
                    if (bsteps > MAX_STEPS) { end = nearest(st[0], st[1]); break }
                }
            }
            bx = st[0]; by = st[1]; bvx = st[2]; bvy = st[3]
            if (end >= 0) {
                // Quicker settles are brighter, so the calm regions glow and
                // the tangled ones sink back.
                // Two tones only: the starts that fall straight in are lit,
                // the rest flat. Finer bands of settling time flickered
                // between neighbours and speckled the calm regions like grain.
                val shade = if (bsteps < FAST_STEPS) 1 else 0
                fill(bCell, (end * SHADES + shade).toByte())
                computed++
                bCell = -1
            }
        }
    }

    /** The next start to try: coarse grid first, each finer level filling the gaps. */
    private fun nextCell(env: SimEnv): Int? {
        while (true) {
            val perRow = (cols + stride - 1) / stride
            val perCol = (rows + stride - 1) / stride
            if (cursor >= perRow * perCol) {
                levelsDone++
                env.emit(EventKind.BIRTH, 0.3f + 0.6f * levelsDone / levels, 0f, 0.4f)
                if (stride == 1) {
                    complete = true
                    payoffFrame = true
                    paidAt = elapsed
                    return null
                }
                stride /= 2
                cursor = 0
                continue
            }
            val i = (cursor % perRow) * stride
            val j = (cursor / perRow) * stride
            cursor++
            // Already done at a coarser level.
            if (levelsDone > 0 && i % (stride * 2) == 0 && j % (stride * 2) == 0) continue
            // Inside a calm region the answer is already known: when the four
            // coarser starts around this one all end on the same magnet just
            // as fast, so does this one. Only on the two finest levels, where
            // those starts are at most four cells apart; trusting them at a
            // coarse level would paint over thin bands for good. This is what
            // lets the map reach full detail early instead of spending most of
            // the run re-proving the calm lobes while the edges stay blocky.
            if (levelsDone > 0 && stride <= 2) {
                val v = agreed(i, j)
                if (v >= 0) { fill(j * cols + i, v.toByte()); computed++; continue }
            }
            return j * cols + i
        }
    }

    /** The shared value of the four coarser starts around (i, j), or −1. */
    private fun agreed(i: Int, j: Int): Int {
        val s2 = stride * 2
        val i0 = i - i % s2; val j0 = j - j % s2
        val i1 = i0 + s2; val j1 = j0 + s2
        if (i1 >= cols || j1 >= rows) return -1
        val v = disp[j0 * cols + i0].toInt()
        if (v < 0) return -1
        if (disp[j0 * cols + i1].toInt() != v || disp[j1 * cols + i0].toInt() != v || disp[j1 * cols + i1].toInt() != v) return -1
        return v
    }

    private fun fill(c: Int, v: Byte) {
        val i0 = c % cols; val j0 = c / cols
        val i1 = min(cols, i0 + stride); val j1 = min(rows, j0 + stride)
        for (j in j0 until j1) for (i in i0 until i1) disp[j * cols + i] = v
    }

    private fun stepHero(env: SimEnv, h: Float) {
        if (hSettled >= 0 && elapsed - hRestAt > HERO_REST) {
            newHero(env)
            // A soft pick-up as the bob reappears at its new start ring.
            env.emit(EventKind.STEP, 0.55f, pan(hx), 0.2f)
        }
        val speed = env.params.f("speed", 1f).coerceIn(0.3f, 3f)
        val modelT = h * HERO_RATE * speed
        val n = max(1, (modelT / DT).toInt())
        val sub = modelT / n
        st[0] = hx; st[1] = hy; st[2] = hvx; st[3] = hvy
        repeat(n) {
            accel(st[0], st[1], st[2], st[3])
            st[2] += acc[0] * sub * 0.5f; st[3] += acc[1] * sub * 0.5f
            st[0] += st[2] * sub; st[1] += st[3] * sub
            accel(st[0], st[1], st[2], st[3])
            st[2] += acc[0] * sub * 0.5f; st[3] += acc[1] * sub * 0.5f
        }
        hx = st[0]; hy = st[1]; hvx = st[2]; hvy = st[3]
        trailX[trailHead] = hx; trailY[trailHead] = hy
        trailHead = (trailHead + 1) % TRAIL
        if (trailLen < TRAIL) trailLen++
        // A soft note each time it swings in over a different magnet.
        val near = nearest(hx, hy)
        val dx = mx[near] - hx; val dy = my[near] - hy
        if (dx * dx + dy * dy < NEAR_R2 && near != hNear) {
            hNear = near
            env.emit(EventKind.COMPARE, 0.35f + 0.5f * near / magnets, pan(hx), 0.25f)
        }
        // Caught, rather than perfectly still: once it circles inside one
        // magnet's pull slowly it cannot leave, and waiting for true rest
        // left the screen on a bob barely moving for seconds.
        if (hSettled >= 0) return
        val end = if (hvx * hvx + hvy * hvy < CAUGHT_V2 && dx * dx + dy * dy < CAUGHT_R2) near else -1
        if (end >= 0) {
            hSettled = end
            hRestAt = elapsed
            runs++
            tally[end]++
            env.emit(EventKind.HIT, 0.35f + 0.5f * end / magnets, pan(hx), 0.7f)
        }
    }

    private fun pan(x: Float) = (x * scale / max(0.1f, hw)).coerceIn(-1f, 1f)

    private fun magnetColour(env: SimEnv, i: Int): Int = env.palette.at((i + 0.5f) / magnets)

    override fun hero(env: SimEnv) {
        newHero(env)
        env.emit(EventKind.STEP, 0.6f, pan(hx), 0.5f)
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        // The map: runs of equal colour along each row, one bar per run.
        val shadeCols = IntArray(magnets * SHADES)
        for (m in 0 until magnets) for (s in 0 until SHADES) {
            shadeCols[m * SHADES + s] = Palette.lerp(Palette.BG, magnetColour(env, m), 0.2f + 0.14f * s / (SHADES - 1))
        }
        val half = cell * 0.5f
        for (j in 0 until rows) {
            val y = 1f - (j + 0.5f) * cell
            var i = 0
            val base = j * cols
            while (i < cols) {
                val v = disp[base + i]
                var e = i + 1
                while (e < cols && disp[base + e] == v) e++
                if (v >= 0) {
                    val x0 = -hw + i * cell; val x1 = -hw + e * cell
                    // Overlapped a little both ways: butted edges showed as
                    // faint scanlines and a seam down the middle.
                    g.rect((x0 + x1) * 0.5f, y, (x1 - x0) * 0.5f + cell * 0.06f, half + cell * 0.06f, shadeCols[v.toInt()])
                }
                i = e
            }
        }
        // The magnets.
        for (i in 0 until magnets) {
            val x = mx[i] * scale; val y = my[i] * scale
            val c = magnetColour(env, i)
            g.glow(x, y, 0.09f, c, 0.45f)
            g.disc(x, y, 0.028f, Palette.lerp(c, Palette.BG, 0.25f))
            g.ring(x, y, 0.028f, 0.006f, Palette.brighten(c, 0.2f))
        }
        // The pivot straight above the centre, and the string seen from above.
        val bxw = hx * scale; val byw = hy * scale
        g.line(0f, 0f, bxw, byw, 0.003f, Palette.TEXT_SECONDARY, 0.35f)
        g.disc(0f, 0f, 0.008f, Palette.TEXT_SECONDARY, 0.7f)
        // Its path, fading behind it.
        if (showTrail && trailLen > 1) {
            val col = if (hSettled >= 0) magnetColour(env, hSettled) else Palette.TEXT_PRIMARY
            var prevX = 0f; var prevY = 0f
            for (n in 0 until trailLen) {
                val idx = (trailHead - trailLen + n + TRAIL) % TRAIL
                val x = trailX[idx] * scale; val y = trailY[idx] * scale
                if (n > 0) g.line(prevX, prevY, x, y, 0.004f, col, 0.1f + 0.7f * n / trailLen)
                prevX = x; prevY = y
            }
        }
        // Where this run began.
        g.ring(hStartX * scale, hStartY * scale, 0.014f, 0.004f, Palette.TEXT_PRIMARY, alpha = 0.7f)
        // The bob, with its shadow on the table.
        val bc = if (hSettled >= 0) Palette.brighten(magnetColour(env, hSettled), 0.2f) else Palette.TEXT_PRIMARY
        g.disc(bxw + 0.01f, byw - 0.012f, 0.024f, 0xFF000000.toInt(), 0.45f)
        g.glow(bxw, byw, 0.07f, bc, 0.35f)
        g.disc(bxw, byw, 0.022f, bc)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = paidAt >= 0f && elapsed - paidAt > 5f

    override fun counters(): List<Pair<String, String>> {
        val pct = if (totalCells > 0) 100 * computed / totalCells else 0
        return listOf(
            "starts mapped" to String.format(java.util.Locale.US, "%,d (%d%%)", computed, pct),
            "detail" to "level $levelsDone of $levels",
            "bob" to if (hSettled >= 0) "settled on magnet ${hSettled + 1}" else "swinging"
        )
    }

    companion object {
        const val MAX_MAGNETS = 6
        const val SPRING = 0.5f
        const val DT = 0.03f
        const val HDT = DT * 0.5f
        const val CELLS = 26000f
        const val FIRST_STRIDE = 16
        const val EVALS = 11000
        const val MAX_STEPS = 3000
        const val SHADES = 2
        const val FAST_STEPS = 330
        const val SETTLE_V2 = 0.0004f
        const val SETTLE_R2 = 0.25f
        const val NEAR_R2 = 0.12f
        const val CAUGHT_V2 = 0.01f
        const val CAUGHT_R2 = 0.06f
        const val TRAIL = 360
        /** Model time units per second for the bob on screen. */
        const val HERO_RATE = 9f
        const val HERO_REST = 1.2f

        fun def(): SimDef = SimDef(
            id = "physics/magnet-pendulum",
            title = "Magnet pendulum",
            shelf = Shelf.PHYSICS,
            family = "Chaos",
            blurb = "Which magnet will it settle on? Start by start, the answer paints itself — and it is a fractal.",
            formats = listOf(TrapFormat.GUESS, TrapFormat.REVEAL, TrapFormat.GROW),
            // Gravity is the pendulum's pull back to the middle.
            lawKnobs = setOf(LawKnob.GRAVITY),
            heroLabel = "Drop it somewhere new",
            heroRestarts = false,
            defaultPalette = "Spectrum",
            typicalSeconds = 20f,
            params = listOf(
                Param.int("magnets", "Magnets", 2, MAX_MAGNETS, 3, card = Card.DATA),
                Param.float("drag", "Air drag", 0.08f, 0.5f, 0.2f, card = Card.DATA),
                Param.float("gap", "Height over the magnets", 0.12f, 0.5f, 0.25f, card = Card.DATA),
                Param.float("paint", "Painting speed", 0.25f, 3f, 1f, card = Card.MOTION),
                Param.float("speed", "Bob speed", 0.3f, 3f, 1f, card = Card.MOTION),
                Param.bool("trail", "Bob's path", true, card = Card.LOOK)
            ),
            create = { MagnetPendulumSim() }
        )
    }
}
