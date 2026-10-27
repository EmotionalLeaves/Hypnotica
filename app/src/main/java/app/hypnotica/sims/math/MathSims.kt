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
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Mandelbrot set, zooming forever.
 *
 * Escape times are computed across every core and drawn through the point path,
 * so a quarter of a million samples a frame stay inside the budget.
 */
class MandelbrotSim : Sim {

    /** The payoff fires once; without this the bell retriggers every step. */
    private var reached = false

    private var cols = 0
    private var rows = 0
    private var iterations = IntArray(0)
    private var maxIter = 220
    private var zoom = 0.6
    private var targetZoom = 0.6
    private var centreX = -0.743643887037151
    private var centreY = 0.131825904205330
    private var elapsed = 0f
    private var payoffFrame = false
    private var zoomRate = 0.22f
    private var lastSoundIter = 0
    private var sinceRecompute = 999f
    /** The zoom the current image was computed at. */
    private var computedZoom = 0.6
    /** Doublings of magnification so far, each one a note. */
    private var doublings = 0

    override fun setup(env: SimEnv) {
        val p = env.params
        val quality = p.i("resolution", 190).coerceIn(40, 460)
        rows = quality
        cols = max(8, (quality * env.aspect).toInt())
        iterations = IntArray(cols * rows)
        maxIter = p.i("detail", 220).coerceIn(40, 1200)
        zoom = 0.6
        computedZoom = 0.6
        doublings = 0
        targetZoom = 0.6
        zoomRate = p.f("zoom", 0.22f)
        when (p.choiceIndex("target")) {
            1 -> { centreX = -0.16070135; centreY = 1.0375665 }
            2 -> { centreX = -1.25066; centreY = 0.02012 }
            3 -> { centreX = 0.360240443437614; centreY = 0.641313061064803 }
            else -> { centreX = -0.743643887037151; centreY = 0.131825904205330 }
        }
        elapsed = 0f
        payoffFrame = false
        lastSoundIter = 0
        sinceRecompute = 999f
        reached = false
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        targetZoom *= Math.exp((zoomRate * dt).toDouble())
        zoom += (targetZoom - zoom) * min(1.0, (dt * 6f).toDouble())

        // An escape-time image costs far more than a physics step, so it is
        // recomputed on its own clock rather than once per 120 Hz step. Eight
        // images a second is past the point where a slow zoom looks continuous.
        // A note each time the magnification grows by half again: the dive's
        // own beat. The only other sound follows the iteration count at the
        // centre, which deep in a zoom can hold steady for twenty seconds. A
        // note per doubling was one every three seconds at the shipped speed.
        val nowDoublings = (ln(zoom / 0.6) / ln(1.5)).toInt()
        if (nowDoublings > doublings) {
            doublings = nowDoublings
            env.emit(EventKind.STEP, ((doublings % 12) / 12f), 0f, 0.3f)
        }

        sinceRecompute += abs(dt)
        if (sinceRecompute < 1f / REFRESH_HZ) return
        sinceRecompute = 0f
        computedZoom = zoom

        val scale = 1.4 / zoom
        val halfW = env.halfWidth
        val cs = cols
        val rs = rows
        val mi = maxIter
        val cx = centreX
        val cy = centreY

        Parallel.forRange(rs) { from, to ->
            for (row in from until to) {
                val y0 = cy + (row.toDouble() / (rs - 1) * 2.0 - 1.0) * scale
                val base = row * cs
                for (col in 0 until cs) {
                    val x0 = cx + (col.toDouble() / (cs - 1) * 2.0 - 1.0) * scale * halfW
                    var x = 0.0
                    var y = 0.0
                    var i = 0
                    while (x * x + y * y <= 4.0 && i < mi) {
                        val xt = x * x - y * y + x0
                        y = 2.0 * x * y + y0
                        x = xt
                        i++
                    }
                    iterations[base + col] = i
                }
            }
        }

        // The sound follows how deep the picture has become.
        val centreIter = iterations[(rs / 2) * cs + cs / 2]
        if (abs(centreIter - lastSoundIter) > mi / 40) {
            lastSoundIter = centreIter
            env.emit(EventKind.STEP, (centreIter.toFloat() / mi).coerceIn(0f, 1f), 0f, 0.4f)
        }
        // Deep enough that the detail on screen is a thousand times finer than
         // where it started. That is the zoom arriving, which is what the sim
         // promises, rather than eighteen seconds having gone by.
        if (!reached && zoom >= 0.6 * TARGET_DEPTH) {
            reached = true
            payoffFrame = true
            env.emit(EventKind.DONE, 0.8f, 0f, 1f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        g.reservePoints(cols * rows)
        val halfW = env.halfWidth
        val dx = 2f * halfW / cols
        val dy = 2f / rows
        // The image is recomputed eight times a second and the zoom is
        // continuous, so in between the last image is drawn grown by however
        // far the zoom has gone since — about the centre, which is where the
        // zoom is aimed. It used to sit still for an eighth of a second and
        // then jump 2.8%, eight times a second: a zoom that stuttered, on the
        // one sim whose whole subject is a smooth dive.
        val k = (zoom / computedZoom).toFloat().coerceIn(0.5f, 4f)
        val size = max(dx, dy) * 1.05f * k
        for (row in 0 until rows) {
            val y = (-1f + (row + 0.5f) * dy) * k
            val base = row * cols
            for (col in 0 until cols) {
                val i = iterations[base + col]
                if (i >= maxIter) continue
                val t = (ln(1f + i.toFloat()) / ln(1f + maxIter.toFloat()))
                g.point((-halfW + (col + 0.5f) * dx) * k, y, size, palette.at((t * 3f) % 1f))
            }
        }
    }

    override fun onInput(env: SimEnv, e: TouchEvent) {
        if (e.kind == TouchEvent.Kind.TAP) {
            val scale = 1.4 / zoom
            centreX += (e.x / env.halfWidth) * scale * env.halfWidth
            centreY += e.y * scale
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "zoom" to String.format(java.util.Locale.US, "%.0fx", zoom / 0.6),
        "detail" to maxIter.toString()
    )

    companion object {
        /**
         * How much finer the detail has to get before the zoom has arrived.
         * A thousandfold is plainly deeper than where it started and is reached
         * at the default zoom rate inside a normal run.
         */
        const val TARGET_DEPTH = 1000.0

        /** Full images a second. The zoom still reads as smooth below this. */
        const val REFRESH_HZ = 8f

        fun def(): SimDef = SimDef(
            id = "math/mandelbrot",
            title = "Mandelbrot zoom",
            shelf = Shelf.MATH,
            family = "Fractals & iteration",
            blurb = "One equation, infinite depth. Tap to steer.",
            formats = listOf(TrapFormat.GROW, TrapFormat.REVEAL),
            heroLabel = "New target",
            defaultPalette = "Aurora",
            typicalSeconds = 30f,
            params = listOf(
                Param.choice(
                    "target", "Where to dive",
                    listOf("Seahorse valley", "Spiral", "Antenna", "Misiurewicz"), 0,
                    card = Card.DATA, input = ParamInput.CHIPS
                ),
                Param.int("resolution", "Resolution", 40, 460, 190, card = Card.LOOK),
                Param.int("detail", "Detail", 40, 1200, 220, card = Card.LOOK, logScale = true),
                Param.float("zoom", "Zoom speed", 0.02f, 1.2f, 0.22f, card = Card.MOTION)
            ),
            create = { MandelbrotSim() }
        )
    }
}

/**
 * The chaos game: drop a point at random, repeatedly jump halfway to a randomly
 * chosen corner, and a Sierpinski triangle appears out of pure noise.
 */
class ChaosGameSim : Sim {

    /** The payoff fires once; without this the bell retriggers every step. */
    private var formed = false

    private var corners = 3
    private var ratio = 0.5f
    private var px = 0f
    private var py = 0f
    private var points = FloatArray(0)
    private var tints = FloatArray(0)
    /** Every point ever plotted. The buffer holds the newest [capacity]. */
    private var count = 0L
    private var capacity = 0
    /** Slow phase for the breathing that starts once the shape has formed. */
    private var breath = 0f
    private var carry = 0f
    private var payoffFrame = false
    private val cornerX = FloatArray(12)
    private val cornerY = FloatArray(12)

    override fun setup(env: SimEnv) {
        val p = env.params
        corners = p.i("corners", 3).coerceIn(3, 12)
        ratio = p.f("ratio", 0.5f)
        capacity = p.i("dots", 120000).coerceIn(1000, 400000)
        points = FloatArray(capacity * 2)
        tints = FloatArray(capacity)
        count = 0L
        breath = 0f
        carry = 0f
        payoffFrame = false
        for (i in 0 until corners) {
            val a = -PI.toFloat() / 2f + i * 2f * PI.toFloat() / corners
            cornerX[i] = cos(a) * 0.92f
            cornerY[i] = sin(a) * 0.92f
        }
        px = 0f
        py = 0f
        formed = false
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        carry += abs(dt) * env.params.f("rate", 6000f)
        var budget = min(carry.toInt(), 60000)
        carry -= budget
        // Once the shape has formed it does not stop: the jump ratio breathes a
        // few per cent either side of the slider, and because the newest points
        // overwrite the oldest, the fractal's gaps slowly open and close while
        // the dots keep landing. It used to report finished the moment the
        // buffer was full, which in the feed and in TickTock meant the sim was
        // thrown away the instant it had finished becoming worth looking at.
        if (formed) breath += abs(dt) * BREATH_HZ * 2f * PI.toFloat()
        val r = ratio * (1f + BREATH_DEPTH * sin(breath))
        val before = count
        while (budget-- > 0) {
            val c = env.rng.int(0, corners)
            px += (cornerX[c] - px) * r
            py += (cornerY[c] - py) * r
            val slot = (count % capacity).toInt()
            points[slot * 2] = px
            points[slot * 2 + 1] = py
            tints[slot] = c.toFloat() / corners
            count++
        }
        // A note every time another block of dots lands. The old test — the
        // count landing in a narrow window — only caught that window when a
        // frame's batch happened to straddle it, so the notes came in random
        // clumps with three-second silences between.
        if (count / NOTE_EVERY != before / NOTE_EVERY) {
            val level = if (formed) 0.5f + 0.5f * sin(breath)
            else (count.toFloat() / capacity).coerceIn(0f, 1f)
            env.emit(EventKind.WRITE, level, 0f, 0.35f)
        }
        // The payoff is the moment the shape is unmistakable.
        val formAt = capacity * 6L / 10L
        if (!formed && count >= formAt && count - formAt < 2000) {
            formed = true
            payoffFrame = true
            env.emit(EventKind.DONE, 0.85f, 0f, 1f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val shown = min(count, capacity.toLong()).toInt()
        g.reservePoints(shown)
        val size = env.params.f("dot", 0.0035f)
        for (i in 0 until shown) {
            g.point(points[i * 2], points[i * 2 + 1], size, palette.at(tints[i]))
        }
        for (i in 0 until corners) {
            g.glow(cornerX[i], cornerY[i], 0.07f, Palette.ACCENT, 0.4f)
        }
    }

    /** Drawn at a fixed size; on a phone held upright the view pulls back to fit it. */
    override fun frameHalfWidth(env: SimEnv): Float = 0.82f

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = false

    override fun counters(): List<Pair<String, String>> =
        listOf("dots" to String.format(java.util.Locale.US, "%,d", count))

    companion object {
        /** One slow breath every twenty-five seconds. */
        private const val BREATH_HZ = 0.04f
        private const val NOTE_EVERY = 2048L

        /** How far the jump ratio breathes either side of the slider's value. */
        private const val BREATH_DEPTH = 0.06f

        fun def(): SimDef = SimDef(
            id = "math/chaos-game",
            title = "Chaos game",
            shelf = Shelf.MATH,
            family = "Fractals & iteration",
            blurb = "Random jumps that draw a perfect triangle.",
            formats = listOf(TrapFormat.GROW, TrapFormat.REVEAL),
            heroLabel = "Start again",
            defaultPalette = "Candy",
            typicalSeconds = 20f,
            params = listOf(
                Param.int("corners", "Corners", 3, 12, 3, card = Card.DATA),
                Param.float("ratio", "Jump fraction", 0.2f, 0.8f, 0.5f, card = Card.DATA),
                Param.int("dots", "Dots", 20000, 400000, 120000, card = Card.DATA, logScale = true),
                Param.float("rate", "Speed", 200f, 120000f, 6000f, card = Card.MOTION, logScale = true),
                Param.float("dot", "Dot size", 0.001f, 0.012f, 0.0035f, card = Card.LOOK)
            ),
            create = { ChaosGameSim() }
        )
    }
}

/**
 * The Ulam spiral: whole numbers wound into a square spiral, primes lit up. The
 * diagonal streaks nobody can fully explain are the payoff.
 */
class PrimeSpiralSim : Sim {

    /** The payoff fires once; without this the bell retriggers every step. */
    private var spiralled = false
    private var restSeconds = -1f

    private var limit = 40000
    private var drawn = 0
    private var isPrime = BooleanArray(0)
    private var xs = FloatArray(0)
    private var ys = FloatArray(0)
    private var carry = 0f
    private var payoffFrame = false
    private var primeCount = 0
    private var sacks = false

    override fun setup(env: SimEnv) {
        val p = env.params
        limit = p.i("limit", 40000).coerceIn(200, 400000)
        sacks = p.choiceIndex("layout") == 1
        isPrime = sieve(limit)
        xs = FloatArray(limit)
        ys = FloatArray(limit)
        buildLayout()
        drawn = 0
        carry = 0f
        payoffFrame = false
        primeCount = 0
    }

    private fun sieve(n: Int): BooleanArray {
        val flags = BooleanArray(n + 1) { it >= 2 }
        var i = 2
        while (i.toLong() * i <= n) {
            if (flags[i]) {
                var j = i * i
                while (j <= n) {
                    flags[j] = false
                    j += i
                }
            }
            i++
        }
        return flags
    }

    private fun buildLayout() {
        if (sacks) {
            // Sacks spiral: radius grows with the square root, angle with it too.
            for (n in 0 until limit) {
                val r = sqrt(n.toFloat())
                val a = r * 2f * PI.toFloat()
                xs[n] = cos(a) * r
                ys[n] = sin(a) * r
            }
        } else {
            var x = 0
            var y = 0
            var dx = 1
            var dy = 0
            var segment = 1
            var stepsInSegment = 0
            var turns = 0
            for (n in 0 until limit) {
                xs[n] = x.toFloat()
                ys[n] = y.toFloat()
                x += dx
                y += dy
                stepsInSegment++
                if (stepsInSegment == segment) {
                    stepsInSegment = 0
                    val t = dx
                    dx = -dy
                    dy = t
                    turns++
                    if (turns % 2 == 0) segment++
                }
            }
        }
        spiralled = false
        restSeconds = -1f
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        if (restSeconds >= 0f) {
            restSeconds -= abs(dt)
            return
        }
        carry += abs(dt) * env.params.f("rate", 2600f)
        var budget = min(carry.toInt(), 80000)
        carry -= budget
        val before = drawn
        drawn = min(limit, drawn + budget)
        for (n in before until drawn) if (isPrime[n]) primeCount++
        if (drawn > before && primeCount > 0) {
            env.emit(EventKind.WRITE, (drawn.toFloat() / limit).coerceIn(0f, 1f), 0f, 0.3f)
        }
        if (!spiralled && drawn >= limit) {
            spiralled = true
            payoffFrame = true
            // The finished spiral is the thing worth looking at, so the run
            // holds on it instead of ending on the same step as its payoff.
            restSeconds = 1.5f
            env.emit(EventKind.DONE, 0.9f, 0f, 1f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        g.reservePoints(min(drawn, limit))
        val extent = if (sacks) sqrt(limit.toFloat()) else sqrt(limit.toFloat()) * 0.5f
        val scale = 0.95f / max(1f, extent)
        val size = max(0.0022f, scale * 0.9f)
        for (n in 2 until drawn) {
            if (!isPrime[n]) continue
            g.point(xs[n] * scale, ys[n] * scale, size, palette.at((n.toFloat() / limit) % 1f))
        }
    }

    /** Drawn at a fixed size; on a phone held upright the view pulls back to fit it. */
    override fun frameHalfWidth(env: SimEnv): Float = 0.97f

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = drawn >= limit && restSeconds in -1000f..0f

    override fun counters(): List<Pair<String, String>> = listOf(
        "numbers" to String.format(java.util.Locale.US, "%,d", drawn),
        "primes" to String.format(java.util.Locale.US, "%,d", primeCount)
    )

    companion object {
        fun def(): SimDef = SimDef(
            id = "math/prime-spiral",
            title = "Prime spiral",
            shelf = Shelf.MATH,
            family = "Number theory",
            blurb = "Primes on a spiral line up in streaks nobody can explain.",
            formats = listOf(TrapFormat.GROW, TrapFormat.REVEAL),
            heroLabel = "Draw again",
            defaultPalette = "Mono glow",
            typicalSeconds = 16f,
            params = listOf(
                Param.choice("layout", "Layout", listOf("Ulam square", "Sacks"), 0, card = Card.DATA, input = ParamInput.TILES),
                Param.int("limit", "Numbers", 8000, 400000, 40000, card = Card.DATA, logScale = true),
                Param.float("rate", "Speed", 200f, 50000f, 2600f, card = Card.MOTION, logScale = true)
            ),
            create = { PrimeSpiralSim() }
        )
    }
}

/**
 * Monte Carlo pi: throw darts at a square, count how many land in the circle.
 * The estimate wobbling in on 3.14159 is the whole story.
 */
class MonteCarloPiSim : Sim {

    // Longs: this runs for as long as anyone watches, and at five thousand
    // darts a second an Int count wraps in under five days.
    private var total = 0L
    private var inside = 0L
    private var capacity = 60000
    private var xs = FloatArray(0)
    private var ys = FloatArray(0)
    private var hit = BooleanArray(0)
    private var stored = 0
    private var carry = 0f
    private var payoffFrame = false
    private var converged = false

    override fun setup(env: SimEnv) {
        capacity = env.params.i("dots", 60000).coerceIn(500, 400000)
        xs = FloatArray(capacity)
        ys = FloatArray(capacity)
        hit = BooleanArray(capacity)
        total = 0L
        inside = 0L
        stored = 0
        carry = 0f
        payoffFrame = false
        converged = false
    }

    private fun estimate(): Float = if (total == 0L) 0f else (4.0 * inside / total).toFloat()

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        carry += abs(dt) * env.params.f("rate", 5000f)
        var budget = min(carry.toInt(), 50000)
        carry -= budget
        // It never stops. The estimate is the whole of every dart ever thrown,
        // so it goes on tightening as 1/sqrt(n) for as long as anyone watches;
        // the picture keeps the newest [capacity] of them, overwriting the
        // oldest, so the square keeps glittering instead of freezing full.
        // It used to report finished the moment the buffer filled, and the
        // feed and TickTock threw it away at that instant — mid-convergence.
        while (budget-- > 0) {
            val x = env.rng.range(-1f, 1f)
            val y = env.rng.range(-1f, 1f)
            val isIn = x * x + y * y <= 1f
            if (isIn) inside++
            val slot = (total % capacity).toInt()
            total++
            xs[slot] = x
            ys[slot] = y
            hit[slot] = isIn
            if (stored < capacity) stored++
        }
        if (total % 512 < 24) {
            env.emit(EventKind.STEP, (estimate() / 4f).coerceIn(0f, 1f), 0f, 0.3f)
        }
        // The estimate is only ever as good as the sample is large — the error
        // falls off as 1/sqrt(n) — so the tolerance follows the count instead
        // of being a fixed 0.006 that a five-hundred-dart run can never meet
        // and that four seeds in twenty-four never met even at full size.
        val tolerance = max(0.006f, 2.4f / sqrt(max(1L, total).toFloat()))
        if (!converged && total > (capacity * 0.6f).toLong() &&
            abs(estimate() - PI.toFloat()) < tolerance
        ) {
            converged = true
            payoffFrame = true
            env.emit(EventKind.DONE, 0.9f, 0f, 1f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val r = 0.86f
        g.reservePoints(stored)
        for (i in 0 until stored) {
            val c = if (hit[i]) palette.at(0.75f) else Palette.withAlpha(Palette.TEXT_SECONDARY, 0.55f)
            g.point(xs[i] * r, ys[i] * r, 0.004f, c)
        }
        g.ring(0f, 0f, r, 0.006f, Palette.ACCENT, alpha = 0.9f)
        g.rect(0f, 0f, r, 0.004f, Palette.withAlpha(Palette.TEXT_SECONDARY, 0.3f))
        g.rect(0f, 0f, 0.004f, r, Palette.withAlpha(Palette.TEXT_SECONDARY, 0.3f))
    }

    /** Drawn at a fixed size; on a phone held upright the view pulls back to fit it. */
    override fun frameHalfWidth(env: SimEnv): Float = 0.89f

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = false

    override fun counters(): List<Pair<String, String>> = listOf(
        "pi" to String.format(java.util.Locale.US, "%.5f", estimate()),
        "darts" to String.format(java.util.Locale.US, "%,d", total)
    )

    companion object {
        fun def(): SimDef = SimDef(
            id = "math/monte-carlo-pi",
            title = "Finding pi with darts",
            shelf = Shelf.MATH,
            family = "Probability & statistics",
            blurb = "Random throws that settle on 3.14159.",
            formats = listOf(TrapFormat.NUMBER_UP, TrapFormat.FILL),
            heroLabel = "Throw again",
            defaultPalette = "Ocean",
            typicalSeconds = 18f,
            params = listOf(
                Param.int("dots", "Darts", 20000, 400000, 60000, card = Card.DATA, logScale = true),
                Param.float("rate", "Speed", 100f, 80000f, 5000f, card = Card.MOTION, logScale = true)
            ),
            create = { MonteCarloPiSim() }
        )
    }
}

/**
 * Fourier epicycles: a chain of spinning circles whose tip traces a shape. The
 * sum of the circles is also, exactly, the waveform you hear.
 */
class FourierSim : Sim {

    private var terms = 24
    private var amps = FloatArray(0)
    private var freqs = IntArray(0)
    private var phases = FloatArray(0)
    private var traceX = FloatArray(0)
    private var traceY = FloatArray(0)
    private var traceCount = 0
    private var head = 0
    private var theta = 0f
    private var payoffFrame = false
    private var loops = 0

    override fun setup(env: SimEnv) {
        val p = env.params
        terms = p.i("terms", 24).coerceIn(1, 120)
        amps = FloatArray(terms)
        freqs = IntArray(terms)
        phases = FloatArray(terms)
        val shape = p.choiceIndex("shape")
        for (k in 0 until terms) {
            val n = 2 * k + 1
            when (shape) {
                0 -> { freqs[k] = n; amps[k] = 4f / (PI.toFloat() * n); phases[k] = 0f }          // square
                1 -> { freqs[k] = n; amps[k] = 8f / (PI.toFloat() * PI.toFloat() * n * n); phases[k] = if (k % 2 == 0) 0f else PI.toFloat() } // triangle
                2 -> { freqs[k] = k + 1; amps[k] = 2f / (PI.toFloat() * (k + 1)); phases[k] = 0f } // sawtooth
                else -> {
                    freqs[k] = k + 1
                    amps[k] = (if (k == 0) 0.8f else 0.5f / (k + 1))
                    phases[k] = env.rng.range(0f, 2f * PI.toFloat())
                }
            }
        }
        val trail = p.i("trail", 1400).coerceIn(100, 20000)
        traceX = FloatArray(trail)
        traceY = FloatArray(trail)
        traceCount = 0
        head = 0
        theta = 0f
        payoffFrame = false
        loops = 0
        // How far the traced curve reaches, sampled once round the loop, so
        // the view can pull back to hold all of it. A square wave of many
        // terms reached 1.7 either side, on a screen under 0.5 wide.
        var reach = 0f
        for (step in 0 until 720) {
            val th = step * (2f * PI.toFloat() / 720f)
            var x = 0f
            var y = 0f
            for (k in 0 until terms) {
                val a = freqs[k] * th + phases[k]
                x += amps[k] * cos(a)
                y += amps[k] * sin(a)
            }
            reach = max(reach, max(abs(x), abs(y)))
        }
        extent = reach * 0.55f + 0.05f
    }

    private var extent = 1f
    override fun frameHalfWidth(env: SimEnv): Float = extent
    override fun frameHalfHeight(env: SimEnv): Float = extent

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val speed = env.params.f("speed", 0.9f)
        theta += speed * abs(dt)
        if (theta > 2f * PI.toFloat()) {
            theta -= 2f * PI.toFloat()
            loops++
            // The payoff is the shape closing on itself for the first time.
            if (loops == 2) {
                payoffFrame = true
                env.emit(EventKind.DONE, 0.8f, 0f, 1f)
            }
        }
        var x = 0f
        var y = 0f
        for (k in 0 until terms) {
            val a = freqs[k] * theta + phases[k]
            x += amps[k] * cos(a)
            y += amps[k] * sin(a)
        }
        traceX[head] = x * 0.55f
        traceY[head] = y * 0.55f
        head = (head + 1) % traceX.size
        if (traceCount < traceX.size) traceCount++
        env.emit(EventKind.STEP, ((y + 1.3f) / 2.6f).coerceIn(0f, 1f), 0f, 0.3f)
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        var x = 0f
        var y = 0f
        for (k in 0 until terms) {
            val a = freqs[k] * theta + phases[k]
            val nx = x + amps[k] * cos(a)
            val ny = y + amps[k] * sin(a)
            val c = Palette.withAlpha(palette.at(k.toFloat() / terms), 0.5f)
            g.ring(x * 0.55f, y * 0.55f, abs(amps[k]) * 0.55f, 0.0022f, c, alpha = 0.65f)
            g.line(x * 0.55f, y * 0.55f, nx * 0.55f, ny * 0.55f, 0.0022f, c, 0.8f)
            x = nx
            y = ny
        }
        for (k in 0 until traceCount) {
            val idx = (head - 1 - k + traceX.size * 2) % traceX.size
            val age = k.toFloat() / traceCount
            g.point(traceX[idx], traceY[idx], 0.006f * (1.1f - age * 0.5f),
                Palette.withAlpha(Palette.SUCCESS, (1f - age) * 0.9f + 0.1f))
        }
        g.glow(x * 0.55f, y * 0.55f, 0.06f, Palette.SUCCESS, 0.8f)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "circles" to terms.toString(),
        "loops" to loops.toString()
    )

    companion object {
        fun def(): SimDef = SimDef(
            id = "math/fourier",
            title = "Fourier epicycles",
            shelf = Shelf.MATH,
            family = "Waves & fields",
            blurb = "Spinning circles that add up to a square wave.",
            formats = listOf(TrapFormat.SONG),
            heroLabel = "New shape",
            defaultPalette = "Aurora",
            typicalSeconds = 22f,
            params = listOf(
                Param.choice("shape", "Target wave", listOf("Square", "Triangle", "Sawtooth", "Random"), 0, card = Card.DATA, input = ParamInput.TILES),
                Param.int("terms", "Circles", 1, 120, 24, card = Card.DATA, logScale = true),
                Param.int("trail", "Trail", 100, 20000, 1400, card = Card.LOOK, logScale = true),
                Param.float("speed", "Speed", 0.1f, 4f, 0.9f, card = Card.MOTION)
            ),
            create = { FourierSim() }
        )
    }
}

/**
 * The Collatz conjecture: halve it if even, triple and add one if odd, and every
 * number tried so far eventually reaches one. Nobody has proved it must.
 */
class CollatzSim : Sim {

    private var start = 27
    private var sequence = LongArray(0)

    /** The longest Collatz trajectory the sim will draw. */
    private val MAX_TRAJECTORY = 2048
    private var length = 0
    private var shown = 0f
    private var payoffFrame = false
    private var peak = 0L
    private var autoAdvance = true

    override fun setup(env: SimEnv) {
        start = env.params.i("start", 27).coerceIn(2, 2_000_000)
        autoAdvance = env.params.b("auto", true)
        buildSequence(start)
        shown = 0f
        payoffFrame = false
    }

    /**
     * A Collatz trajectory climbs far above its starting number — from 1.7
     * million it passes two billion — so it is kept in longs. Truncating it to
     * Int made negative values, and the logarithm of a negative number is NaN,
     * which reached both the draw list and the synth and stayed there.
     */
    private fun buildSequence(from: Int) {
        // Written into a buffer that is allocated once: auto-advance calls this
        // from step() every time a number finishes, and a fresh list each time
        // would be garbage made inside the loop.
        if (sequence.size < MAX_TRAJECTORY) sequence = LongArray(MAX_TRAJECTORY)
        var n = from.toLong()
        var at = 0
        peak = from.toLong()
        while (n != 1L && at < MAX_TRAJECTORY - 1) {
            sequence[at++] = n
            n = if (n % 2L == 0L) n / 2 else 3 * n + 1
            if (n > peak) peak = n
        }
        sequence[at++] = 1L
        length = at
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val before = shown.toInt()
        shown += env.params.f("rate", 11f) * abs(dt)
        val after = min(length, shown.toInt())
        for (i in before until after) {
            val v = sequence[i]
            env.emit(
                EventKind.WRITE,
                (ln(1f + v.toFloat()) / logPeak()).coerceIn(0f, 1f),
                (i.toFloat() / length) * 2f - 1f,
                0.55f
            )
        }
        if (after >= length && before < length) {
            payoffFrame = true
            env.emit(EventKind.DONE, 1f, 0f, 1f)
        }
        if (autoAdvance && shown > length + 12) {
            start += 1
            buildSequence(start)
            shown = 0f
        }
    }

    /** Never zero, so nothing divides by it. */
    private fun logPeak(): Float = max(1e-3f, ln(1f + peak.toFloat()))

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val halfW = env.halfWidth * 0.94f
        val n = min(length, max(1, shown.toInt()))
        val stepX = 2f * halfW / max(1, length - 1)
        val logPeak = logPeak()
        for (i in 0 until n) {
            val v = sequence[i]
            val t = ln(1f + v.toFloat()) / logPeak
            val x = -halfW + i * stepX
            val h = t * 1.6f
            val reached = i == length - 1
            g.bar(x, -0.85f, max(stepX * 0.35f, 0.0015f), h, if (reached) Palette.SUCCESS else palette.at(t))
        }
        if (n >= length) {
            g.glow(halfW, -0.85f, 0.14f, Palette.SUCCESS, 0.8f)
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "start" to start.toString(),
        "steps" to length.toString(),
        "peak" to String.format(java.util.Locale.US, "%,d", peak)
    )

    companion object {
        fun def(): SimDef = SimDef(
            id = "math/collatz",
            title = "Collatz: always back to one",
            shelf = Shelf.MATH,
            family = "Number theory",
            blurb = "Halve or triple-plus-one. It always ends at one; nobody knows why.",
            formats = listOf(TrapFormat.NUMBER_UP, TrapFormat.SONG, TrapFormat.SNAP),
            heroLabel = "Next number",
            defaultPalette = "Ember",
            typicalSeconds = 14f,
            params = listOf(
                Param.int("start", "Starting number", 2, 2_000_000, 27, card = Card.DATA, logScale = true),
                Param.bool("auto", "Walk through numbers", true, card = Card.DATA),
                Param.float("rate", "Speed", 1f, 120f, 11f, card = Card.MOTION, unit = "/s", logScale = true)
            ),
            create = { CollatzSim() }
        )
    }
}

object MathSims {
    fun defs(): List<SimDef> = listOf(
        MandelbrotSim.def(),
        ChaosGameSim.def(),
        PrimeSpiralSim.def(),
        MonteCarloPiSim.def(),
        FourierSim.def(),
        CollatzSim.def()
    )
}
