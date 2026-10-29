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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A traffic jam out of nowhere. Cars drive round a loop, each keeping a safe
 * gap to the one in front and nothing else — no accident, no junction, no
 * roadworks — and a jam forms anyway: one driver brakes a little too hard, the
 * one behind brakes harder, and a wave of stopped cars rolls backwards round
 * the track while every car in it is trying to drive forwards.
 *
 * This is the experiment Sugiyama ran with real drivers on a real circuit in
 * 2008. The drivers here follow the intelligent driver model, the standard
 * description of how people follow the car ahead.
 *
 * The track is a stadium — two straights and two bends — tall and narrow, so
 * it fills an upright phone rather than sitting in a circle in the middle.
 */
class TrafficSim : Sim {

    private var n = 30
    private var pos = DoubleArray(0)
    private var speed = DoubleArray(0)
    private var jammed = BooleanArray(0)
    private var road = 300.0
    private var elapsed = 0f
    private var payoffFrame = false
    private var formed = false
    private var stops = 0L
    private var radius = 0.36f
    private var straight = 0.55f
    private var cruise = 1.0
    private var jamSpeed = 1.0
    /** Lying on its side, on a wide screen: tall and narrow it sat in a strip down the middle. */
    private var flat = false
    // The infield holds a space-time diagram, the figure from Sugiyama's paper:
    // where every car is round the lap (across) against time (down, newest at
    // the top). The empty infield was the biggest thing on a phone screen, and
    // this is the picture that shows what the ring only hints at — the jam is a
    // stripe that slides backwards while every car in it moves forwards.
    private var cols = 0
    private var stride = 1
    private var hist = FloatArray(0)
    private var histV = FloatArray(0)
    private var rows = 0
    private var head = 0
    private var nextSample = 0f

    override fun setup(env: SimEnv) {
        val p = env.params
        n = p.i("cars", 30).coerceIn(4, MAX_CARS)
        // Under seven and a half metres a car the traffic is at a standstill
        // before it starts, and "a jam from nowhere" is a jam from the start.
        road = n * p.f("room", 10f).coerceIn(7.5f, 30f).toDouble()
        pos = DoubleArray(n) { road * it / n }
        // Everyone at the steady speed for this spacing, so nothing is jammed
        // to begin with: the jam has to come out of the flow.
        val vEq = steadySpeed(road / n - CAR)
        speed = DoubleArray(n) { vEq }
        cruise = vEq
        // Stopped means stopped for this road: a quarter of the speed it
        // flows at, and never more than walking pace.
        jamSpeed = min(max(0.4, vEq * 0.25), 1.5)
        jammed = BooleanArray(n)
        elapsed = 0f
        payoffFrame = false
        formed = false
        stops = 0L
        stride = (n + MAX_COLS - 1) / MAX_COLS
        cols = (n + stride - 1) / stride
        hist = FloatArray(ROWS * cols)
        histV = FloatArray(ROWS * cols)
        rows = 0
        head = 0
        nextSample = 0f
        flat = env.halfWidth > 1f
        if (flat) {
            radius = 0.8f
            straight = max(env.halfWidth * 0.92f - radius, 0.1f)
        } else {
            radius = min(env.halfWidth * 0.8f, 0.42f)
            straight = max(0.92f - radius, 0.1f)
        }
    }

    /** The speed at which a gap of [gap] metres is exactly the gap wanted. */
    private fun steadySpeed(gap: Double): Double {
        var lo = 0.0
        var hi = V0
        repeat(40) {
            val v = (lo + hi) / 2
            val wanted = S0 + v * HEADWAY
            val a = 1.0 - (v / V0).let { it * it * it * it } - (wanted / gap) * (wanted / gap)
            if (a > 0) lo = v else hi = v
        }
        return lo
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        val h = (abs(dt) * SIM_SECONDS_PER_SECOND).toDouble()
        val jitter = env.params.f("jitter", 0.8f).toDouble()
        val acc = DoubleArray(n)
        for (i in 0 until n) {
            val ahead = (i + 1) % n
            var gap = pos[ahead] - pos[i] - CAR
            if (gap < -road / 2) gap += road
            if (gap <= 0.01) gap = 0.01
            if (n == 1) gap = road - CAR
            val v = speed[i]
            val dv = v - speed[ahead]
            val wanted = S0 + max(0.0, v * HEADWAY + v * dv / (2.0 * sqrt(A * B)))
            var a = A * (1.0 - (v / V0).let { it * it * it * it } - (wanted / gap) * (wanted / gap))
            // People are not machines: a little noise in every foot.
            a += (env.rng.nextFloat() - 0.5f) * 2.0 * jitter * NOISE
            acc[i] = a
        }
        for (i in 0 until n) {
            speed[i] = (speed[i] + acc[i] * h).coerceIn(0.0, V0 * 1.2)
            val before = pos[i]
            pos[i] = (pos[i] + speed[i] * h) % road
            // A tick each time a car crosses the start of the lap, pitched by
            // how fast it is going: the flow is heard before any jam, and a
            // jam is heard as the ticks slowing and sagging.
            if (pos[i] < before) {
                env.emit(EventKind.STEP, (speed[i] / (2.0 * max(cruise, 0.5))).toFloat().coerceIn(0f, 1f), panAt(0f), 0.3f)
            }
            val nowJammed = speed[i] < jamSpeed
            if (nowJammed != jammed[i]) {
                jammed[i] = nowJammed
                val t = (pos[i] / road).toFloat()
                if (nowJammed) {
                    stops++
                    env.emit(EventKind.HIT, 0.25f + 0.5f * t, panAt(t), 0.6f)
                } else {
                    env.emit(EventKind.STEP, 0.6f + 0.35f * t, panAt(t), 0.3f)
                }
            }
        }

        if (elapsed >= nextSample) {
            nextSample = elapsed + SAMPLE_SECONDS
            val base = head * cols
            for (c in 0 until cols) {
                val i = c * stride
                hist[base + c] = (pos[i] / road).toFloat()
                histV[base + c] = (speed[i] / (2.0 * max(cruise, 0.5))).toFloat().coerceIn(0f, 1f)
            }
            head = (head + 1) % ROWS
            if (rows < ROWS) rows++
        }

        // The payoff: a real jam, several cars stopped at once, from traffic
        // that started flowing.
        if (!formed && elapsed > MIN_SECONDS) {
            var stopped = 0
            for (i in 0 until n) if (jammed[i]) stopped++
            if (stopped >= max(2, n / 8)) {
                formed = true
                payoffFrame = true
                // No DONE here: the engine sounds it on the payoff frame.
            }
        }
    }

    private fun panAt(t: Float): Float {
        val (x, _) = place(t)
        return (x / max(radius, 0.01f)).coerceIn(-1f, 1f) * 0.8f
    }

    /** Where on screen a share [t] of the way round the stadium is. */
    private fun place(t: Float): Pair<Float, Float> {
        val (x, y) = placeUpright(t)
        return if (flat) y to x else x to y
    }

    private fun placeUpright(t: Float): Pair<Float, Float> {
        val bend = PI.toFloat() * radius
        val total = 4f * straight + 2f * bend
        var d = (t - kotlin.math.floor(t)) * total
        // Up the right-hand straight, over the top bend, down the left, under the bottom.
        if (d < 2f * straight) return radius to (-straight + d)
        d -= 2f * straight
        if (d < bend) {
            val a = d / radius
            return radius * cos(a) to straight + radius * sin(a)
        }
        d -= bend
        if (d < 2f * straight) return -radius to (straight - d)
        d -= 2f * straight
        val a = PI.toFloat() + d / radius
        return radius * cos(a) to -straight + radius * sin(a)
    }

    /** Stamp on the brakes, as if something ran out in front: this car and the two behind it. */
    private fun brake(i: Int, env: SimEnv) {
        for (k in 0 until 3) speed[(i - k + n) % n] = 0.0
        env.emit(EventKind.HIT, 0.2f, panAt((pos[i] / road).toFloat()), 0.9f)
    }

    override fun hero(env: SimEnv) {
        if (n > 0) brake(env.rng.int(0, n), env)
    }

    override fun onInput(env: SimEnv, e: TouchEvent) {
        if (e.kind != TouchEvent.Kind.TAP || n == 0) return
        // The car nearest the tap.
        var best = 0
        var bestD = Float.MAX_VALUE
        for (i in 0 until n) {
            val (x, y) = place((pos[i] / road).toFloat())
            val d = (x - e.x) * (x - e.x) + (y - e.y) * (y - e.y)
            if (d < bestD) { bestD = d; best = i }
        }
        brake(best, env)
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val bend = PI.toFloat() * radius
        val total = 4f * straight + 2f * bend
        val carLength = (CAR / road * total).toFloat().coerceIn(0.006f, 0.06f)
        // The road: a lane of tarmac with its edges marked. A ring of faint
        // dots left the cars floating in the dark.
        val lane = max(carLength * 1.0f, 0.03f)
        val pi = PI.toFloat()
        val edge = Palette.withAlpha(Palette.TEXT_SECONDARY, 0.3f)
        for (sx in floatArrayOf(-radius, radius)) {
            if (flat) g.rect(0f, sx, straight, lane * 0.5f, TARMAC) else g.rect(sx, 0f, lane * 0.5f, straight, TARMAC)
            for (off in floatArrayOf(-lane * 0.5f, lane * 0.5f)) {
                if (flat) g.line(-straight, sx + off, straight, sx + off, 0.003f, edge)
                else g.line(sx + off, -straight, sx + off, straight, 0.003f, edge)
            }
        }
        // The bends: the top half of a ring over the top, the bottom half
        // under — or, lying down, the right half and the left.
        val bends = if (flat) arrayOf(Triple(straight, 0f, pi / 2f), Triple(-straight, 0f, -pi / 2f))
        else arrayOf(Triple(0f, straight, -pi), Triple(0f, -straight, 0f))
        for ((bx, by, gapAt) in bends) {
            g.ring(bx, by, radius + lane * 0.5f, lane, TARMAC, pi, gapAt)
            g.ring(bx, by, radius + lane * 0.5f, 0.003f, edge, pi, gapAt)
            g.ring(bx, by, radius - lane * 0.5f + 0.0015f, 0.003f, edge, pi, gapAt)
        }
        drawDiagram(g, palette, lane)
        for (i in 0 until n) {
            val t = (pos[i] / road).toFloat()
            val (x, y) = place(t)
            val (ax, ay) = place(t + 0.001f)
            val heading = atan2(ay - y, ax - x)
            // Against twice the flowing speed, so the steady stream sits mid
            // palette and a stop reads at once.
            val share = (speed[i] / (2.0 * max(cruise, 0.5))).toFloat().coerceIn(0f, 1f)
            // Slow is the hot end of the palette, flowing the cool end.
            val c = palette.at(0.05f + 0.9f * share)
            // Never thinner than a hairline: a few hundred cars drew as dust.
            g.rect(x, y, carLength * 0.5f, max(carLength * 0.28f, 0.006f), c, heading)
            if (jammed[i]) {
                // Brake lights.
                g.glow(x, y, carLength * 1.6f, Palette.WARNING, 0.35f)
                g.disc(x - cos(heading) * carLength * 0.5f, y - sin(heading) * carLength * 0.5f,
                    carLength * 0.16f, Palette.DANGER)
            }
        }
    }

    private fun drawDiagram(g: G, palette: Palette, lane: Float) {
        if (rows == 0) return
        val inner = radius - lane * 0.5f
        // Across is the lap, down is time; on its side the infield is wide, so
        // the lap runs along the straights there too.
        val hx = if (flat) straight * 0.9f else inner * 0.74f
        val hy = if (flat) inner * 0.74f else straight * 0.94f
        val rowH = 2f * hy / ROWS
        // A hairline frame so the plot reads as a figure, not as stray dots.
        val frame = Palette.withAlpha(Palette.TEXT_SECONDARY, 0.14f)
        g.line(-hx, hy + rowH, hx, hy + rowH, 0.003f, frame)
        g.line(-hx, -hy - rowH, hx, -hy - rowH, 0.003f, frame)
        // Sized by the row, not the column: with many cars the dots overlap
        // sideways into solid bands, where column-sized dots drew a mesh of
        // separate rows. The overlap is paid back in alpha below.
        val dot = rowH
        val crowd = sqrt(min(1f, 36f / max(cols, 1)))
        g.reservePoints(rows * cols)
        for (r in 0 until rows) {
            val slot = ((head - 1 - r) % ROWS + ROWS) % ROWS
            val y = hy - r * rowH
            // Older rows recede; flowing cars stay dim so the stopped ones —
            // the jam stripe — carry the picture.
            val age = 1f - 0.7f * r / ROWS
            val base = slot * cols
            for (c in 0 until cols) {
                val v = histV[base + c]
                val x = -hx + 2f * hx * hist[base + c]
                val hot = (1f - v * 2f).coerceIn(0f, 1f)
                val a = age * crowd * (0.2f + 0.8f * hot)
                g.point(x, y, dot, Palette.withAlpha(palette.at(0.05f + 0.9f * v), a))
            }
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> {
        var stopped = 0
        for (i in 0 until n) if (jammed[i]) stopped++
        return listOf(
            "cars" to n.toString(),
            "stopped now" to stopped.toString(),
            "stops" to stops.toString()
        )
    }

    companion object {
        const val MAX_CARS = 600
        /** Desired speed, metres a second. */
        const val V0 = 30.0
        /** Comfortable acceleration and braking, metres a second squared. */
        const val A = 0.3
        const val B = 1.67
        /** Time gap kept to the car in front, seconds. */
        const val HEADWAY = 1.6
        /** Minimum gap at a standstill, metres. */
        const val S0 = 2.0
        const val CAR = 4.5
        const val NOISE = 1.5
        /** Simulated seconds of traffic per second on screen. */
        const val SIM_SECONDS_PER_SECOND = 4.0f
        const val MIN_SECONDS = 3f
        const val ROWS = 64
        const val MAX_COLS = 120
        const val SAMPLE_SECONDS = 0.3f
        val TARMAC = 0xFF161921.toInt()

        fun def(): SimDef = SimDef(
            id = "physics/traffic",
            title = "Jam out of nowhere",
            shelf = Shelf.PHYSICS,
            family = "Crowds & traffic",
            blurb = "Nobody crashes, nobody stops — and the traffic jams anyway.",
            formats = listOf(TrapFormat.CHAIN, TrapFormat.SNAP),
            heroLabel = "Tap the brakes",
            heroRestarts = false,
            defaultPalette = "Ember",
            typicalSeconds = 20f,
            params = listOf(
                Param.int("cars", "Cars", 4, MAX_CARS, 30, card = Card.DATA, logScale = true),
                Param.float("room", "Road per car", 7.5f, 30f, 10f, card = Card.DATA, unit = "m"),
                Param.float("jitter", "Nervous drivers", 0f, 1f, 0.8f, card = Card.MOTION)
            ),
            create = { TrafficSim() }
        )
    }
}
