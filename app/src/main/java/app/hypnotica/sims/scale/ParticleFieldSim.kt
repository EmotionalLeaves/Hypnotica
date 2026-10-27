package app.hypnotica.sims.scale

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Macro
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.LawKnob
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.FastMath
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The huge-count path.
 *
 * Positions and velocities live in flat float arrays, the whole field is one
 * draw call, and nothing allocates per particle per frame. This is the sim that
 * proves tens of thousands of objects stay at 60 fps.
 */
class ParticleFieldSim : Sim {

    private var n = 20000
    private var px = FloatArray(0)
    private var py = FloatArray(0)
    private var vx = FloatArray(0)
    private var vy = FloatArray(0)
    private var tint = FloatArray(0)

    private var attractorX = FloatArray(4)
    private var attractorY = FloatArray(4)
    private var attractorK = FloatArray(4)
    private var attractors = 3

    private var flow = 1f
    private var swirl = 1f
    private var drag = 0.02f
    private var elapsed = 0f
    private var payoffFrame = false
    private var dotSize = 0.004f
    private var soundTimer = 0f
    private var flipped = false
    private val wallHits = java.util.concurrent.atomic.AtomicInteger(0)

    override fun setup(env: SimEnv) {
        val p = env.params
        n = p.i("count", 20000).coerceIn(100, 400_000)
        flow = p.f("flow", 1f)
        swirl = p.f("swirl", 1f)
        drag = p.f("drag", 0.02f)
        dotSize = p.f("dot", 0.004f)
        attractors = p.i("attractors", 3).coerceIn(0, 4)

        px = FloatArray(n); py = FloatArray(n)
        vx = FloatArray(n); vy = FloatArray(n)
        tint = FloatArray(n)

        val w = env.halfWidth
        for (i in 0 until n) {
            px[i] = env.rng.range(-w, w)
            py[i] = env.rng.range(-1f, 1f)
            vx[i] = 0f
            vy[i] = 0f
            tint[i] = env.rng.nextFloat()
        }
        for (a in 0 until 4) {
            attractorX[a] = env.rng.range(-w * 0.7f, w * 0.7f)
            attractorY[a] = env.rng.range(-0.7f, 0.7f)
            attractorK[a] = env.rng.range(0.4f, 1f) * (if (env.rng.bool(0.75f)) 1f else -1f)
        }
        elapsed = 0f
        payoffFrame = false
        soundTimer = 0f
        flipped = false
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        // Live rather than cached at setup, so the field reshapes under the
        // finger instead of the four hundred thousand particles being thrown
        // back to their starting places on every release. Three map lookups
        // against a pass over the whole cloud is nothing. The attractor count
        // stays a rebuild: it sizes an array.
        flow = env.params.f("flow", 1f)
        swirl = env.params.f("swirl", 1f)
        drag = env.params.f("drag", 0.02f)
        val laws = env.laws
        val w = env.halfWidth
        val gx = laws.gravityDirX * laws.gravity * 0.01f
        val gy = -laws.gravityDirY * laws.gravity * 0.01f
        // The product is what has to be clamped. Clamping the coefficient and
        // then multiplying by dt left this above 1 whenever time ran backwards,
        // which turned drag into gain and drove every one of up to four hundred
        // thousand particles to the speed limit within a few seconds.
        val dragF = 1f - ((drag + laws.friction) * abs(dt) * 8f).coerceIn(0f, 0.6f)
        val t = elapsed * 0.35f
        val wrap = laws.space != app.hypnotica.core.laws.Space.BOX
        val restitution = if (laws.sticky) 0f else laws.bounce

        wallHits.set(0)
        Parallel.forRange(n) { from, to -> integrate(from, to, dt, gx, gy, dragF, t, w, wrap, restitution) }
        val movedFast = wallHits.get()

        // At this scale individual events would be static, so the sound follows
        // the texture: one voice whose pitch tracks how much is hitting the walls.
        soundTimer += abs(dt)
        if (soundTimer > 0.08f) {
            soundTimer = 0f
            val density = (movedFast.toFloat() / max(1, n / 50)).coerceIn(0f, 1f)
            if (density > 0.02f) {
                env.emit(EventKind.STEP, density, 0f, (0.2f + density * 0.5f).coerceAtMost(0.8f))
            }
        }

        // The payoff lands about three quarters of the way through a typical run:
        // the attractors flip sign and the whole field reorganises at once.
        if (!flipped && elapsed > PAYOFF_AT) {
            flipped = true
            payoffFrame = true
            for (a in 0 until 4) attractorK[a] = -attractorK[a]
            env.emit(EventKind.DONE, 0.8f, 0f, 1f)
        }
    }

    private fun integrate(
        from: Int, to: Int, dt: Float, gx: Float, gy: Float, dragF: Float,
        t: Float, w: Float, wrap: Boolean, restitution: Float
    ) {
        var localHits = 0
        for (i in from until to) {
            var ax = gx
            var ay = gy

            // A cheap, allocation-free flow field: two sine layers, fast trig.
            if (flow > 0.001f) {
                val fx = FastMath.sin(py[i] * 3.1f + t) + 0.5f * FastMath.sin(py[i] * 7.3f - t * 1.7f)
                val fy = FastMath.cos(px[i] * 2.9f - t * 1.1f) + 0.5f * FastMath.cos(px[i] * 6.7f + t * 1.3f)
                ax += fx * flow * 0.5f
                ay += fy * flow * 0.5f
            }

            if (swirl > 0.001f) {
                for (a in 0 until attractors) {
                    val dx = attractorX[a] - px[i]
                    val dy = attractorY[a] - py[i]
                    val d2 = dx * dx + dy * dy + 0.01f
                    val inv = attractorK[a] * swirl / d2
                    ax += dx * inv * 0.02f
                    ay += dy * inv * 0.02f
                    // A tangential term is what makes the field curl instead of collapse.
                    ax += -dy * inv * 0.012f
                    ay += dx * inv * 0.012f
                }
            }

            var nvx = (vx[i] + ax * dt) * dragF
            var nvy = (vy[i] + ay * dt) * dragF

            val sp2 = nvx * nvx + nvy * nvy
            if (sp2 > MAX_SPEED2) {
                val k = MAX_SPEED / sqrt(sp2)
                nvx *= k; nvy *= k
            }

            var nx = px[i] + nvx * dt
            var ny = py[i] + nvy * dt

            if (wrap) {
                if (nx > w) nx -= 2 * w else if (nx < -w) nx += 2 * w
                if (ny > 1f) ny -= 2f else if (ny < -1f) ny += 2f
            } else {
                if (nx > w) { nx = w; nvx = -nvx * restitution; localHits++ }
                else if (nx < -w) { nx = -w; nvx = -nvx * restitution; localHits++ }
                if (ny > 1f) { ny = 1f; nvy = -nvy * restitution; localHits++ }
                else if (ny < -1f) { ny = -1f; nvy = -nvy * restitution; localHits++ }
            }

            px[i] = nx; py[i] = ny; vx[i] = nvx; vy[i] = nvy
        }
        if (localHits > 0) wallHits.addAndGet(localHits)
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val sizeScale = if (n > 120_000) 0.6f else 1f
        // Past a few hundred thousand there are more particles than there are
        // pixels to put them in, and filling the draw list is the one part of
        // the frame that cannot be split across cores. A sample is drawn at a
        // proportionally larger size, so the field is as dense and as bright as
        // it was: all of them are still simulated, and the counter still says
        // so. The same trade the Riemann sum makes with its bars.
        val stride = if (n > DRAW_EVERY_ABOVE) 1 + n / DRAW_EVERY_ABOVE else 1
        val spread = if (stride > 1) sqrt(stride.toFloat()) else 1f
        g.reservePoints(n / stride + 1)
        var i = 0
        while (i < n) {
            val speed = min(1f, sqrt(vx[i] * vx[i] + vy[i] * vy[i]) * 0.55f)
            val c = palette.at((tint[i] * 0.5f + speed * 0.5f) % 1f)
            g.point(px[i], py[i], dotSize * sizeScale * spread * (0.7f + speed * 0.8f), c)
            i += stride
        }
        for (a in 0 until attractors) {
            g.glow(attractorX[a], attractorY[a], 0.16f, if (attractorK[a] > 0) Palette.ACCENT else Palette.DANGER, 0.22f)
        }
    }

    override fun onInput(env: SimEnv, e: TouchEvent) {
        if (e.kind == TouchEvent.Kind.TAP || e.kind == TouchEvent.Kind.DRAG) {
            if (attractors > 0) {
                attractorX[0] = e.x
                attractorY[0] = e.y
                attractorK[0] = abs(attractorK[0])
            }
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> =
        listOf("particles" to String.format(java.util.Locale.US, "%,d", n))

    companion object {
        /**
         * Above this many particles the draw samples rather than drawing every
         * one. Chosen so a 1080p screen still gets a point for every few
         * pixels, which is the point at which more of them stop being visible.
         */
        const val DRAW_EVERY_ABOVE = 200_000

        private const val MAX_SPEED = 3.2f
        private const val MAX_SPEED2 = MAX_SPEED * MAX_SPEED
        private const val PAYOFF_AT = 18f

        fun def(): SimDef = SimDef(
            id = "life/particle-field",
            title = "Particle field",
            shelf = Shelf.LIFE,
            family = "Particles & flocking",
            blurb = "Up to 400,000 particles in one flow field, one draw call.",
            formats = listOf(TrapFormat.LOOP, TrapFormat.GROW),
            lawKnobs = setOf(LawKnob.GRAVITY, LawKnob.GRAVITY_DIRECTION, LawKnob.BOUNCE,
                LawKnob.FRICTION, LawKnob.CONTACT, LawKnob.SPACE),
            heroLabel = "New field",
            defaultPalette = "Aurora",
            typicalSeconds = 30f,
            params = listOf(
                Param.int("count", "Particles", 100, 400_000, 20000, card = Card.DATA, logScale = true),
                Param.float("flow", "Flow", 0f, 3f, 1f, card = Card.MOTION),
                Param.float("swirl", "Swirl", 0f, 3f, 1f, card = Card.MOTION),
                Param.int("attractors", "Attractors", 0, 4, 3, card = Card.MOTION),
                Param.float("drag", "Drag", 0f, 0.2f, 0.02f, card = Card.MOTION, advancedOnly = true),
                Param.float("dot", "Dot size", 0.001f, 0.02f, 0.004f, card = Card.LOOK)
            ),
            macros = listOf(
                Macro("size", "Size", Card.DATA, mapOf("count" to 2000f..400000f), 0.05f),
                Macro("chaos", "Chaos", Card.MOTION, mapOf("flow" to 0.2f..3f, "swirl" to 0.1f..3f), 0.35f)
            ),
            create = { ParticleFieldSim() }
        )
    }
}

/**
 * Conway's Game of Life on a grid of up to a million cells, stepped with flat
 * byte arrays and drawn as points.
 */
class GameOfLifeSim : Sim {

    private var w = 160
    private var h = 160
    private var cells = ByteArray(0)
    private var next = ByteArray(0)
    private var births = 0
    private var deaths = 0
    private var generation = 0
    private var stepTimer = 0f
    private var payoffFrame = false
    private var alive = 0
    private var birthRule = 0b000001000
    private var surviveRule = 0b000001100
    private var quietGenerations = 0
    private var settled = false
    private var lastAlive = -1
    private var peakAlive = 0

    override fun setup(env: SimEnv) {
        val p = env.params
        val side = p.i("grid", 160).coerceIn(16, 1024)
        w = side
        h = side
        cells = ByteArray(w * h)
        next = ByteArray(w * h)
        val density = p.f("density", 0.32f)
        for (i in cells.indices) cells[i] = if (env.rng.nextFloat() < density) 1 else 0
        generation = 0
        stepTimer = 0f
        payoffFrame = false
        births = 0
        deaths = 0
        quietGenerations = 0
        settled = false
        lastAlive = -1
        peakAlive = 0
        applyRule(p.choice("rule", "Life B3/S23"))
        countAlive()
    }

    private fun applyRule(name: String) {
        when (name) {
            "HighLife B36/S23" -> { birthRule = (1 shl 3) or (1 shl 6); surviveRule = (1 shl 2) or (1 shl 3) }
            "Seeds B2/S" -> { birthRule = (1 shl 2); surviveRule = 0 }
            "Day & night B3678/S34678" -> {
                birthRule = (1 shl 3) or (1 shl 6) or (1 shl 7) or (1 shl 8)
                surviveRule = (1 shl 3) or (1 shl 4) or (1 shl 6) or (1 shl 7) or (1 shl 8)
            }
            "Maze B3/S12345" -> {
                birthRule = (1 shl 3)
                surviveRule = (1 shl 1) or (1 shl 2) or (1 shl 3) or (1 shl 4) or (1 shl 5)
            }
            else -> { birthRule = (1 shl 3); surviveRule = (1 shl 2) or (1 shl 3) }
        }
    }

    private fun countAlive() {
        var a = 0
        for (c in cells) if (c.toInt() != 0) a++
        alive = a
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val rate = env.params.f("rate", 12f)
        stepTimer += abs(dt) * rate
        var guard = 4
        while (stepTimer >= 1f && guard > 0) {
            stepTimer -= 1f
            guard--
            generation++
            var b = 0
            var d = 0
            for (y in 0 until h) {
                val yUp = if (y == 0) h - 1 else y - 1
                val yDn = if (y == h - 1) 0 else y + 1
                val rowC = y * w
                val rowU = yUp * w
                val rowD = yDn * w
                for (x in 0 until w) {
                    val xL = if (x == 0) w - 1 else x - 1
                    val xR = if (x == w - 1) 0 else x + 1
                    val count = cells[rowU + xL] + cells[rowU + x] + cells[rowU + xR] +
                        cells[rowC + xL] + cells[rowC + xR] +
                        cells[rowD + xL] + cells[rowD + x] + cells[rowD + xR]
                    val wasAlive = cells[rowC + x].toInt() != 0
                    val nowAlive = if (wasAlive) (surviveRule shr count) and 1 == 1
                    else (birthRule shr count) and 1 == 1
                    next[rowC + x] = if (nowAlive) 1 else 0
                    if (nowAlive && !wasAlive) b++
                    if (!nowAlive && wasAlive) d++
                }
            }
            val t = cells; cells = next; next = t
            births = b
            deaths = d
            countAlive()
            val activity = ((b + d).toFloat() / max(1, w * h / 40)).coerceIn(0f, 1f)
            if (b > 0) env.emit(EventKind.BIRTH, activity, -0.3f, (0.2f + activity * 0.5f).coerceAtMost(0.8f))
            if (d > 0) env.emit(EventKind.DEATH, 1f - activity, 0.3f, (0.15f + activity * 0.4f).coerceAtMost(0.7f))

            // The payoff is the moment the soup stops boiling: the population
            // stops falling and what is left is structure, not noise. Waiting for
            // perfect stillness would take thousands of generations nobody sees.
            if (peakAlive == 0) peakAlive = alive
            val stable = kotlin.math.abs(alive - lastAlive) <= maxOf(2, alive / 50)
            if (stable) quietGenerations++ else quietGenerations = 0
            lastAlive = alive
            if (!settled && quietGenerations >= 12 && generation > 30) {
                settled = true
                payoffFrame = true
                env.emit(EventKind.DONE, 0.75f, 0f, 1f)
            }
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val halfW = env.halfWidth
        val cellW = (2f * halfW) / w
        val cellH = 2f / h
        val size = max(cellW, cellH)
        g.reservePoints(alive)
        for (y in 0 until h) {
            val row = y * w
            val cy = -1f + (y + 0.5f) * cellH
            for (x in 0 until w) {
                if (cells[row + x].toInt() == 0) continue
                val cx = -halfW + (x + 0.5f) * cellW
                g.point(cx, cy, size, palette.at((x + y).toFloat() / (w + h)))
            }
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "generation" to generation.toString(),
        "alive" to String.format(java.util.Locale.US, "%,d", alive)
    )

    companion object {
        fun def(): SimDef = SimDef(
            id = "life/game-of-life",
            title = "Game of Life",
            shelf = Shelf.LIFE,
            family = "Cellular automata",
            blurb = "Four rules, a million cells, endless structure.",
            formats = listOf(TrapFormat.GROW, TrapFormat.COLOR_BATTLE),
            heroLabel = "Reseed",
            defaultPalette = "Ocean",
            typicalSeconds = 22f,
            params = listOf(
                Param.int("grid", "Grid size", 16, 1024, 160, card = Card.DATA, logScale = true),
                Param.float("density", "Start density", 0.02f, 0.9f, 0.32f, card = Card.DATA),
                Param.choice(
                    "rule", "Rule",
                    listOf("Life B3/S23", "HighLife B36/S23", "Seeds B2/S", "Day & night B3678/S34678", "Maze B3/S12345"),
                    0, card = Card.DATA
                ),
                Param.float("rate", "Generations", 0.5f, 60f, 12f, card = Card.MOTION, unit = "/s", logScale = true)
            ),
            macros = listOf(
                Macro("size", "Size", Card.DATA, mapOf("grid" to 48f..512f), 0.3f)
            ),
            create = { GameOfLifeSim() }
        )
    }
}

/** The Lorenz attractor, drawn as the trail it carves. */
class LorenzSim : Sim {

    private var settled = false
    private var settleClock = 0f
    private var lastX = 0.0
    private var lastZ = 0.0

    /** How long before the "it has settled" test starts, and how often it runs. */
    private val SETTLE_AFTER = 6f
    private val SETTLE_SAMPLE = 1.5f
    private val SETTLE_SPEED = 0.02

    private var trailX = FloatArray(0)
    private var trailY = FloatArray(0)
    private var head = 0
    private var filled = 0
    private var x = 0.0
    private var y = 1.0
    private var z = 1.05
    private var sigma = 10.0
    private var rho = 28.0
    private var beta = 8.0 / 3.0
    private var carry = 0f
    private var payoffFrame = false
    private var lobeSwaps = 0L
    private var lastLobe = 1

    override fun setup(env: SimEnv) {
        val p = env.params
        val len = p.i("trail", 4000).coerceIn(200, 60000)
        trailX = FloatArray(len)
        trailY = FloatArray(len)
        head = 0
        filled = 0
        x = env.rng.range(-1f, 1f).toDouble()
        y = env.rng.range(-1f, 1f).toDouble()
        z = 1.0 + env.rng.range(0f, 2f)
        sigma = p.f("sigma", 10f).toDouble()
        rho = p.f("rho", 28f).toDouble()
        beta = p.f("beta", 2.667f).toDouble()
        carry = 0f
        payoffFrame = false
        lobeSwaps = 0
        lastLobe = 1
        settled = false
        settleClock = 0f
        lastX = x
        lastZ = z
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        settleClock += abs(dt)
        val speed = env.params.f("rate", 1200f)
        carry += abs(dt) * speed
        var steps = min(carry.toInt(), 6000)
        carry -= steps
        val h = 0.0016
        while (steps > 0) {
            steps--
            // Classic RK4; the attractor is sensitive enough that Euler drifts.
            val k1x = sigma * (y - x); val k1y = x * (rho - z) - y; val k1z = x * y - beta * z
            val x2 = x + k1x * h / 2; val y2 = y + k1y * h / 2; val z2 = z + k1z * h / 2
            val k2x = sigma * (y2 - x2); val k2y = x2 * (rho - z2) - y2; val k2z = x2 * y2 - beta * z2
            val x3 = x + k2x * h / 2; val y3 = y + k2y * h / 2; val z3 = z + k2z * h / 2
            val k3x = sigma * (y3 - x3); val k3y = x3 * (rho - z3) - y3; val k3z = x3 * y3 - beta * z3
            val x4 = x + k3x * h; val y4 = y + k3y * h; val z4 = z + k3z * h
            val k4x = sigma * (y4 - x4); val k4y = x4 * (rho - z4) - y4; val k4z = x4 * y4 - beta * z4
            x += h / 6 * (k1x + 2 * k2x + 2 * k3x + k4x)
            y += h / 6 * (k1y + 2 * k2y + 2 * k3y + k4y)
            z += h / 6 * (k1z + 2 * k2z + 2 * k3z + k4z)

            trailX[head] = (x * 0.034).toFloat()
            trailY[head] = (z * 0.034 - 0.86).toFloat()
            head = (head + 1) % trailX.size
            if (filled < trailX.size) filled++

            val lobe = if (x > 0) 1 else -1
            if (lobe != lastLobe) {
                lastLobe = lobe
                lobeSwaps++
                // The payoff is the attractor's shape being complete: both wings
                // fully drawn, which takes a good few crossings.
                if (lobeSwaps == PAYOFF_SWAPS) {
                    payoffFrame = true
                    env.emit(EventKind.DONE, 0.8f, 0f, 1f)
                }
                env.emit(EventKind.CROSS, ((z - 5.0) / 40.0).toFloat().coerceIn(0f, 1f), lobe * 0.6f, 0.7f)
            }
        }

        // Below rho about 24.7 the attractor has no wings to swap between: the
        // trajectory spirals into a fixed point and stays there. That settling
        // is the thing to watch at those values, so it is the payoff rather
        // than a run that never pays off at all.
        if (!settled && settleClock > SETTLE_AFTER) {
            val speedNow = kotlin.math.abs(x - lastX) + kotlin.math.abs(z - lastZ)
            if (speedNow < SETTLE_SPEED) {
                settled = true
                payoffFrame = true
                env.emit(EventKind.DONE, 0.4f, 0f, 0.9f)
            }
            lastX = x
            lastZ = z
            settleClock = SETTLE_AFTER - SETTLE_SAMPLE
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        g.reservePoints(filled)
        val size = env.params.f("dot", 0.006f)
        for (k in 0 until filled) {
            val idx = (head - 1 - k + trailX.size * 2) % trailX.size
            val age = k.toFloat() / filled
            val c = Palette.withAlpha(palette.at(0.15f + age * 0.7f), (1f - age) * 0.9f + 0.1f)
            g.point(trailX[idx], trailY[idx], size * (1.1f - age * 0.6f), c)
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> =
        listOf("lobe swaps" to lobeSwaps.toString())

    companion object {
        private const val PAYOFF_SWAPS = 26L

        fun def(): SimDef = SimDef(
            id = "math/lorenz",
            title = "Lorenz attractor",
            shelf = Shelf.MATH,
            family = "Chaos & dynamical systems",
            blurb = "Two lobes, never the same path twice.",
            formats = listOf(TrapFormat.GROW),
            heroLabel = "New start",
            defaultPalette = "Aurora",
            typicalSeconds = 28f,
            params = listOf(
                Param.int("trail", "Trail length", 200, 60000, 4000, card = Card.LOOK, logScale = true),
                Param.float("dot", "Dot size", 0.002f, 0.02f, 0.006f, card = Card.LOOK),
                Param.float("rate", "Speed", 100f, 8000f, 1200f, card = Card.MOTION, logScale = true),
                Param.float("sigma", "Sigma", 1f, 20f, 10f, card = Card.MOTION, advancedOnly = true),
                Param.float("rho", "Rho", 1f, 60f, 28f, card = Card.MOTION, advancedOnly = true),
                Param.float("beta", "Beta", 0.5f, 6f, 2.667f, card = Card.MOTION, advancedOnly = true)
            ),
            create = { LorenzSim() }
        )
    }
}

object ScaleSims {
    fun defs(): List<SimDef> = listOf(
        ParticleFieldSim.def(),
        GameOfLifeSim.def(),
        LorenzSim.def()
    )
}
