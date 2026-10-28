package app.hypnotica.sims.math

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.recipe.ParamInput
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.Parallel
import app.hypnotica.core.sim.Shelf
import app.hypnotica.core.sim.Sim
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.sim.SimEnv
import app.hypnotica.core.sim.TouchEvent
import app.hypnotica.core.sim.TrapFormat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Voronoi cells: colour every pixel by whichever seed is nearest.
 *
 * The seeds drift, so the cell walls slide about and occasionally a cell is
 * squeezed out of existence entirely.
 */
class VoronoiSim : Sim {

    private var seeds = 28
    private var sx = FloatArray(0)
    private var sy = FloatArray(0)
    private var svx = FloatArray(0)
    private var svy = FloatArray(0)
    private var tint = FloatArray(0)
    private var cols = 0
    private var rows = 0
    private var owner = IntArray(0)
    private var settled = false
    private var closestPair = Float.MAX_VALUE
    private var pinchHeld = 0f
    private var elapsed = 0f
    private var payoffFrame = false
    private var sinceRecompute = 999f
    private var manhattan = false

    override fun setup(env: SimEnv) {
        val p = env.params
        seeds = p.i("seeds", 28).coerceIn(2, 300)
        manhattan = p.choiceIndex("metric") == 1
        sx = FloatArray(seeds); sy = FloatArray(seeds)
        svx = FloatArray(seeds); svy = FloatArray(seeds)
        tint = FloatArray(seeds)
        val drift = p.f("drift", 0.1f)
        for (i in 0 until seeds) {
            sx[i] = env.rng.range(-env.halfWidth, env.halfWidth)
            sy[i] = env.rng.range(-1f, 1f)
            svx[i] = env.rng.range(-drift, drift)
            svy[i] = env.rng.range(-drift, drift)
            tint[i] = env.rng.nextFloat()
        }
        rows = p.i("resolution", 150).coerceIn(30, 420)
        cols = max(8, (rows * env.aspect).toInt())
        owner = IntArray(cols * rows)
        settled = false
        closestPair = Float.MAX_VALUE
        pinchHeld = 0f
        elapsed = 0f
        payoffFrame = false
        sinceRecompute = 999f
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        val halfW = env.halfWidth
        for (i in 0 until seeds) {
            sx[i] += svx[i] * dt
            sy[i] += svy[i] * dt
            if (sx[i] < -halfW || sx[i] > halfW) svx[i] = -svx[i]
            if (sy[i] < -1f || sy[i] > 1f) svy[i] = -svy[i]
        }

        // The pinch: two seeds at their closest, where the border between them
        // is shortest and their cells are squeezed thin. Every seed always owns
        // at least the cell it sits in, so a diagram of drifting seeds never
        // settles and never completes — waiting for either waits for ever, and
        // the fourteen-second clock this replaces said nothing about the
        // picture at all.
        if (elapsed > PINCH_EARLIEST && !settled) {
            var nearest = Float.MAX_VALUE
            for (i in 0 until seeds) {
                for (j in i + 1 until seeds) {
                    val d = hypot(sx[i] - sx[j], sy[i] - sy[j])
                    if (d < nearest) nearest = d
                }
            }
            if (nearest < closestPair) {
                closestPair = nearest
                pinchHeld = 0f
            } else {
                pinchHeld += abs(dt)
            }
            if (pinchHeld > PINCH_HELD) {
                settled = true
                payoffFrame = true
                env.emit(EventKind.DONE, 0.75f, 0f, 1f)
            }
        }

        // The nearest-seed test is per pixel, so it runs on its own clock.
        sinceRecompute += abs(dt)
        if (sinceRecompute < 1f / REFRESH_HZ) return
        sinceRecompute = 0f

        val cs = cols
        val rs = rows
        Parallel.forRange(rs) { from, to ->
            for (r in from until to) {
                val y = -1f + (r + 0.5f) * 2f / rs
                val base = r * cs
                for (c in 0 until cs) {
                    val x = -halfW + (c + 0.5f) * 2f * halfW / cs
                    var bestIndex = 0
                    var bestDist = Float.MAX_VALUE
                    for (k in 0 until seeds) {
                        val dx = abs(x - sx[k])
                        val dy = abs(y - sy[k])
                        val d = if (manhattan) dx + dy else dx * dx + dy * dy
                        if (d < bestDist) { bestDist = d; bestIndex = k }
                    }
                    owner[base + c] = bestIndex
                }
            }
        }
        env.emit(EventKind.STEP, (elapsed * 0.1f) % 1f, 0f, 0.25f)
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val halfW = env.halfWidth
        g.reservePoints(cols * rows)
        val dx = 2f * halfW / cols
        val dy = 2f / rows
        val size = max(dx, dy) * 1.05f
        for (r in 0 until rows) {
            val y = -1f + (r + 0.5f) * dy
            val base = r * cols
            for (c in 0 until cols) {
                g.point(-halfW + (c + 0.5f) * dx, y, size, palette.at(tint[owner[base + c]]))
            }
        }
        for (i in 0 until seeds) {
            g.disc(sx[i], sy[i], 0.009f, Palette.TEXT_PRIMARY)
        }
    }

    override fun onInput(env: SimEnv, e: TouchEvent) {
        if (e.kind == TouchEvent.Kind.TAP && seeds > 0) {
            sx[0] = e.x
            sy[0] = e.y
            sinceRecompute = 999f
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> =
        listOf("cells" to seeds.toString())

    companion object {
        /** Nothing is the closest pair before this much of the run. */
        const val PINCH_EARLIEST = 5f

        /** How long the closest pair has to hold before it is the one. */
        const val PINCH_HELD = 0.7f

        /**
         * Cell recolours a second: the frame rate. At twelve the seed dots
         * glided every frame and the borders between them caught up in steps,
         * so the cells visibly lagged behind their own seeds. A recompute
         * measures 0.9 ms at the shipped resolution and 3.6 ms at the
         * slider's top on two cores, which a frame can carry.
         */
        const val REFRESH_HZ = 60f

        fun def(): SimDef = SimDef(
            id = "math/voronoi",
            title = "Voronoi cells",
            shelf = Shelf.MATH,
            family = "Geometry & topology",
            blurb = "Every point belongs to its nearest seed. The walls slide.",
            formats = listOf(TrapFormat.COLOR_BATTLE, TrapFormat.REVEAL),
            heroLabel = "New seeds",
            defaultPalette = "Candy",
            typicalSeconds = 10f,
            params = listOf(
                Param.choice("metric", "Distance", listOf("Straight line", "City blocks"), 0,
                    card = Card.DATA, input = ParamInput.TILES),
                Param.int("seeds", "Seeds", 2, 300, 28, card = Card.DATA, logScale = true),
                Param.int("resolution", "Resolution", 30, 420, 150, card = Card.LOOK),
                Param.float("drift", "Drift", 0f, 0.5f, 0.1f, card = Card.MOTION)
            ),
            create = { VoronoiSim() }
        )
    }
}

/**
 * Riemann sums: rectangles under a curve, getting narrower until the total
 * stops moving. The number converging on the exact area is the payoff.
 */
class RiemannSim : Sim {

    private var bars = 4
    private var maxBars = 400
    private var carry = 0f
    private var payoffFrame = false
    private var curve = 0
    private var exact = 0.0
    private var estimate = 0.0
    private var converged = false
    private var restSeconds = -1f

    override fun setup(env: SimEnv) {
        val p = env.params
        curve = p.choiceIndex("curve")
        bars = 2
        maxBars = p.i("maxbars", 400).coerceIn(8, 200_000)
        carry = 0f
        payoffFrame = false
        converged = false
        restSeconds = -1f
        exact = when (curve) {
            0 -> 2.0 / 3.0          // x^2 from 0 to 1 -> 1/3, scaled below
            1 -> 2.0                // sin over 0..pi
            else -> 1.0
        }
        recompute()
    }

    private fun f(x: Float): Float = when (curve) {
        0 -> x * x
        1 -> sin(x * PI.toFloat())
        else -> sqrt(max(0f, 1f - x * x))
    }

    private fun exactArea(): Double = when (curve) {
        0 -> 1.0 / 3.0
        1 -> 2.0 / PI
        else -> PI / 4.0
    }

    private fun recompute() {
        var sum = 0.0
        val n = bars
        for (i in 0 until n) {
            val x = (i + 0.5f) / n
            sum += f(x).toDouble() / n
        }
        estimate = sum
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        if (restSeconds >= 0f) {
            restSeconds -= abs(dt)
            return
        }
        carry += abs(dt) * env.params.f("rate", 2.6f)
        while (carry >= 1f && bars < maxBars) {
            carry -= 1f
            bars = min(maxBars, (bars * 1.16f).toInt() + 1)
            recompute()
            env.emit(
                EventKind.WRITE,
                (estimate / max(0.0001, exactArea())).toFloat().coerceIn(0f, 1f),
                0f, 0.55f
            )
        }
        if (!converged && bars >= maxBars) {
            converged = true
            payoffFrame = true
            restSeconds = 1.5f
            env.emit(EventKind.DONE, 1f, 0f, 1f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val halfW = env.halfWidth * 0.9f
        val baseY = -0.7f
        val height = 1.4f
        val barW = 2f * halfW / bars
        val show = min(bars, 1200)
        val stride = max(1, bars / show)
        var i = 0
        while (i < bars) {
            val xMid = (i + 0.5f) / bars
            val h = f(xMid) * height
            g.bar(
                -halfW + (i + 0.5f) * barW, baseY, max(barW * 0.46f, 0.0012f), h,
                Palette.withAlpha(palette.at(xMid), 0.8f)
            )
            i += stride
        }
        // The curve itself, drawn over the bars.
        var prevX = -halfW
        var prevY = baseY + f(0f) * height
        for (k in 1..160) {
            val t = k / 160f
            val x = -halfW + t * 2f * halfW
            val y = baseY + f(t) * height
            g.line(prevX, prevY, x, y, 0.004f, Palette.SUCCESS)
            prevX = x
            prevY = y
        }
        g.line(-halfW, baseY, halfW, baseY, 0.003f, Palette.withAlpha(Palette.TEXT_SECONDARY, 0.5f))
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = converged && restSeconds in -1000f..0f

    override fun counters(): List<Pair<String, String>> = listOf(
        "rectangles" to bars.toString(),
        "area" to String.format(java.util.Locale.US, "%.5f", estimate),
        "exact" to String.format(java.util.Locale.US, "%.5f", exactArea())
    )

    companion object {
        fun def(): SimDef = SimDef(
            id = "math/riemann",
            title = "Rectangles under a curve",
            shelf = Shelf.MATH,
            family = "Visual proofs & calculus",
            blurb = "Narrower and narrower, until the total stops moving.",
            formats = listOf(TrapFormat.NUMBER_UP, TrapFormat.PERFECT_FIT),
            heroLabel = "Start over",
            defaultPalette = "Ocean",
            typicalSeconds = 16f,
            params = listOf(
                Param.choice("curve", "Curve", listOf("x squared", "sine", "quarter circle"), 0,
                    card = Card.DATA, input = ParamInput.TILES),
                Param.int("maxbars", "Finest division", 8, 200000, 400, card = Card.DATA, logScale = true),
                Param.float("rate", "Speed", 0.5f, 20f, 2.6f, card = Card.MOTION, logScale = true)
            ),
            create = { RiemannSim() }
        )
    }
}

/**
 * A mass warping a grid, and light bending as it passes.
 *
 * The rubber-sheet picture of gravity: not literally correct relativity, but the
 * deflection follows the real formula for a light ray grazing a mass.
 */
class CurvedSpaceSim : Sim {

    private var gridX = 28
    private var gridY = 44
    private var massAmount = 1f
    private var massX = 0f
    private var massY = 0f
    private var bentMost = false
    private var closest = Float.MAX_VALUE
    private var closingFor = 0f
    private var rays = 7
    private var elapsed = 0f
    private var payoffFrame = false
    private val pathX = Array(16) { FloatArray(260) }
    private val pathY = Array(16) { FloatArray(260) }
    private val pathLen = IntArray(16)

    override fun setup(env: SimEnv) {
        val p = env.params
        gridX = p.i("gridx", 28).coerceIn(6, 90)
        gridY = p.i("gridy", 44).coerceIn(6, 140)
        massAmount = p.f("mass", 1f)
        rays = p.i("rays", 7).coerceIn(1, 16)
        rs = RS_PER_MASS * massAmount
        massX = 0f
        massY = 0f
        elapsed = 0f
        payoffFrame = false
        traceRays(env)
        bentMost = false
        closest = Float.MAX_VALUE
        closingFor = 0f
    }

    private var captured = 0
    private var rs = 0.035f

    /**
     * The height of space at a point, as Flamm's paraboloid has it: the
     * exact shape of the plane through a non-rotating mass, embedded one
     * dimension up, z = 2√(rs·(r − rs)), measured down from far away. Inside
     * the horizon there is no surface; it stops at the throat.
     */
    private fun height(x: Float, y: Float): Float {
        val r = max(rs, hypot(x - massX, y - massY))
        return 2f * sqrt(rs * (r - rs)) - zFar
    }

    private val zFar get() = 2f * sqrt(rs * (FAR - rs))

    /** Plane to screen: the surface seen from above at an angle, so its dip shows. */
    private fun screenY(y: Float, h: Float): Float = y * COS_TILT + h * SIN_TILT

    private fun traceRays(env: SimEnv) {
        val halfW = env.halfWidth
        captured = 0
        for (r in 0 until rays) {
            var x = -halfW * 1.02f
            var y = -RAY_SPAN + r * (2f * RAY_SPAN / max(1, rays - 1))
            var vx = 1f
            var vy = 0f
            var n = 0
            val step = 0.012f
            // The path of light past a mass, exactly as general relativity
            // has it. Its shape obeys u'' + u = (3/2)·rs·u² (u = 1/r), and a
            // particle pulled by (3/2)·rs·h²/r⁴ towards the mass, h its
            // angular momentum, traces that same shape: so it is pushed by
            // that, and only the shape of its path is kept. Far out this is
            // Einstein's 2·rs/b; close in it is the real thing, and a ray
            // aimed within 3√3/2·rs spirals in for good.
            val h = abs((x - massX) * vy - (y - massY) * vx)
            val k = 1.5f * rs * h * h
            while (n < pathX[r].size && x < halfW * 1.02f && abs(y) < 2f) {
                val ddx = massX - x
                val ddy = massY - y
                val d = sqrt(ddx * ddx + ddy * ddy)
                // Inside the photon sphere, 1.5 rs, light can't get out again.
                if (d < 1.5f * rs) { captured++; break }
                // A step of fixed length along the path, whatever the speed.
                val speed = hypot(vx, vy)
                val dt = step / speed
                val a = k / (d * d * d * d * d)
                vx += ddx * a * dt
                vy += ddy * a * dt
                x += vx * dt
                y += vy * dt
                pathX[r][n] = x
                pathY[r][n] = y
                n++
            }
            pathLen[r] = n
        }
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        if (env.params.b("orbitmass", true)) {
            massX = cos(elapsed * 0.35f) * env.halfWidth * 0.35f
            massY = sin(elapsed * 0.5f) * 0.3f
        }
        traceRays(env)
        env.emit(EventKind.STEP, ((massY + 1f) * 0.5f).coerceIn(0f, 1f), massX.coerceIn(-1f, 1f), 0.25f)
        // The mass at its closest approach, where the grid is pulled hardest
        // and the rays bend most: the running minimum stops falling, and that
        // turning point is the picture this sim exists to show. A clock at
        // twelve seconds fired wherever the orbit happened to be.
        if (elapsed > PAYOFF_EARLIEST) {
            val reach = hypot(massX, massY)
            if (reach < closest) {
                closest = reach
                closingFor = 0f
            } else {
                closingFor += abs(dt)
            }
            if (!bentMost && closingFor > CLOSEST_HELD) {
                bentMost = true
                payoffFrame = true
                env.emit(EventKind.DONE, 0.75f, 0f, 1f)
            }
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val halfW = env.halfWidth * 0.98f
        val gridColour = Palette.withAlpha(Palette.TEXT_SECONDARY, 0.3f)
        // The grid lies on the curved surface, so it dips into the well.
        val top = GRID_REACH
        for (i in 0..gridX) {
            val x = -halfW + i * 2f * halfW / gridX
            var prevY = screenY(-top, height(x, -top))
            for (j in 1..gridY * 2) {
                val y = -top + j * top / gridY
                val sy = screenY(y, height(x, y))
                g.line(x, prevY, x, sy, 0.0016f, gridColour)
                prevY = sy
            }
        }
        for (j in 0..gridY) {
            val y = -top + j * 2f * top / gridY
            var prevX = -halfW
            var prevY = screenY(y, height(-halfW, y))
            for (i in 1..gridX * 2) {
                val x = -halfW + i * halfW / gridX
                val sy = screenY(y, height(x, y))
                g.line(prevX, prevY, x, sy, 0.0016f, gridColour)
                prevX = x
                prevY = sy
            }
        }
        for (r in 0 until rays) {
            val c = palette.at(r.toFloat() / max(1, rays - 1))
            for (n in 0 until pathLen[r] - 1) {
                val x0 = pathX[r][n]; val y0 = pathY[r][n]; val x1 = pathX[r][n + 1]; val y1 = pathY[r][n + 1]
                g.line(x0, screenY(y0, height(x0, y0)), x1, screenY(y1, height(x1, y1)), 0.0026f, c, 0.9f)
            }
        }
        // The mass at the bottom of its well; the horizon at its true size.
        val my = screenY(massY, height(massX, massY))
        g.glow(massX, my, 0.12f + 0.05f * massAmount, Palette.WARNING, 0.55f)
        g.disc(massX, my, max(0.02f, rs), Palette.BG)
        g.ring(massX, my, max(0.02f, rs) + 0.005f, 0.005f, Palette.WARNING)
    }

    override fun onInput(env: SimEnv, e: TouchEvent) {
        if (e.kind == TouchEvent.Kind.DRAG || e.kind == TouchEvent.Kind.TAP) {
            massX = e.x
            // The screen shows the surface tilted, with the mass at the
            // bottom of its well: undo both to put it under the finger.
            massY = (e.y + zFar * SIN_TILT) / COS_TILT
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "rays" to rays.toString(),
        "swallowed" to captured.toString(),
        "mass" to String.format(java.util.Locale.US, "%.1f", massAmount)
    )

    companion object {
        /** The horizon's radius for each unit of the Mass slider. */
        const val RS_PER_MASS = 0.035f
        /** Where "far away" is, for the surface's height. */
        const val FAR = 1.6f
        const val COS_TILT = 0.83f
        const val SIN_TILT = 0.56f
        /** The grid reaches this far up and down the plane, filling the tilted screen. */
        const val GRID_REACH = 1.35f
        const val RAY_SPAN = 0.95f

        /** Nothing is the closest approach before this much of the run. */
        const val PAYOFF_EARLIEST = 6f

        /** How long the closest approach has to hold before it is the one. */
        const val CLOSEST_HELD = 0.6f

        fun def(): SimDef = SimDef(
            id = "dimensions/curved-space",
            title = "A mass bending space",
            shelf = Shelf.DIMENSIONS,
            family = "Curved space & spacetime",
            blurb = "Space round a mass as it really curves, and light bending by Einstein's amount.",
            formats = listOf(TrapFormat.LOOP, TrapFormat.REVEAL),
            heroLabel = "Move the mass",
            defaultPalette = "Aurora",
            typicalSeconds = 22f,
            loopSeconds = 18.65f,
            params = listOf(
                Param.float("mass", "Mass", 0.1f, 4f, 1f, card = Card.DATA),
                Param.int("rays", "Light rays", 1, 16, 7, card = Card.DATA),
                Param.int("gridx", "Grid columns", 6, 90, 28, card = Card.LOOK),
                Param.int("gridy", "Grid rows", 6, 140, 44, card = Card.LOOK),
                Param.bool("orbitmass", "Let the mass drift", true, card = Card.MOTION)
            ),
            create = { CurvedSpaceSim() }
        )
    }
}

object GeometrySims {
    fun defs(): List<SimDef> = listOf(
        VoronoiSim.def(),
        RiemannSim.def(),
        CurvedSpaceSim.def()
    )
}
