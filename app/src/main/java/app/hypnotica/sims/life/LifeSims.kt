package app.hypnotica.sims.life

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Macro
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.recipe.ParamInput
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** The ant's four headings, hoisted: this runs up to 120,000 times a step. */
private val ANT_DX = intArrayOf(0, 1, 0, -1)
private val ANT_DY = intArrayOf(-1, 0, 1, 0)

/**
 * Boids: three local rules, no leader, and a flock appears anyway.
 *
 * Neighbours are found through a uniform grid rather than every pair, which is
 * what turns an impossible n-squared problem into fifty thousand birds.
 */
class BoidsSim : Sim {

    // Front and back buffers: see the swap in step().
    private var nextPx = FloatArray(0)
    private var nextPy = FloatArray(0)
    private var nextVx = FloatArray(0)
    private var nextVy = FloatArray(0)

    /** The payoff fires once; without this the bell retriggers every step. */
    private var flocked = false

    private var n = 3000
    private var px = FloatArray(0)
    private var py = FloatArray(0)
    private var vx = FloatArray(0)
    private var vy = FloatArray(0)
    private var tint = FloatArray(0)

    // Uniform grid for neighbour lookup.
    private var cellSize = 0.12f
    private var gridW = 0
    private var gridH = 0
    private var cellStart = IntArray(0)
    private var cellCount = IntArray(0)
    private var cellCursor = IntArray(0)
    private var order = IntArray(0)
    private var cellOf = IntArray(0)

    private var separation = 1f
    private var alignment = 1f
    private var cohesion = 1f
    private var maxSpeed = 0.55f
    private var elapsed = 0f
    private var payoffFrame = false
    private var flockiness = 0f

    override fun setup(env: SimEnv) {
        val p = env.params
        n = p.i("count", 3000).coerceIn(10, 60000)
        separation = p.f("separation", 1f)
        alignment = p.f("alignment", 1f)
        cohesion = p.f("cohesion", 1f)
        maxSpeed = p.f("speed", 0.55f)
        // The grid cell is the perception radius, but never so large that a
        // single cell holds a crowd: the neighbour walk costs one pass over
        // every bird in the nine cells around it, so a fixed cell size makes the
        // whole sim quadratic in the population. Sizing it by density keeps the
        // cost flat, and a bird in a dense flock perceiving a smaller patch is
        // what happens in a real one.
        val perception = p.f("range", 0.12f).coerceAtLeast(0.02f)
        val area = 4f * env.halfWidth
        val byDensity = sqrt(Flock.TARGET_PER_CELL * area / max(1, n))
        cellSize = byDensity.coerceIn(0.012f, perception)

        px = FloatArray(n); py = FloatArray(n)
        vx = FloatArray(n); vy = FloatArray(n)
        nextPx = FloatArray(n); nextPy = FloatArray(n)
        nextVx = FloatArray(n); nextVy = FloatArray(n)
        tint = FloatArray(n)
        cellOf = IntArray(n)
        order = IntArray(n)

        val w = env.halfWidth
        for (i in 0 until n) {
            px[i] = env.rng.range(-w, w)
            py[i] = env.rng.range(-1f, 1f)
            val a = env.rng.range(0f, 2f * PI.toFloat())
            vx[i] = cos(a) * maxSpeed
            vy[i] = sin(a) * maxSpeed
            tint[i] = env.rng.nextFloat()
        }
        gridW = max(1, (2f * w / cellSize).toInt() + 1)
        gridH = max(1, (2f / cellSize).toInt() + 1)
        cellStart = IntArray(gridW * gridH + 1)
        cellCount = IntArray(gridW * gridH)
        cellCursor = IntArray(gridW * gridH)
        elapsed = 0f
        payoffFrame = false
        flockiness = 0f
    }

    private fun rebuildGrid(halfW: Float) {
        java.util.Arrays.fill(cellCount, 0)
        for (i in 0 until n) {
            val cx = (((px[i] + halfW) / cellSize).toInt()).coerceIn(0, gridW - 1)
            val cy = (((py[i] + 1f) / cellSize).toInt()).coerceIn(0, gridH - 1)
            val c = cy * gridW + cx
            cellOf[i] = c
            cellCount[c]++
        }
        var running = 0
        for (c in 0 until gridW * gridH) {
            cellStart[c] = running
            running += cellCount[c]
        }
        cellStart[gridW * gridH] = running
        // Preallocated: this runs 120 times a second, and a fresh array each
        // time would hand the collector a megabyte a second to clean up.
        val cursor = cellCursor
        java.util.Arrays.fill(cursor, 0, gridW * gridH, 0)
        for (i in 0 until n) {
            val c = cellOf[i]
            order[cellStart[c] + cursor[c]] = i
            cursor[c]++
        }
        flocked = false
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        val halfW = env.halfWidth
        rebuildGrid(halfW)

        val wrap = env.laws.space != app.hypnotica.core.laws.Space.BOX
        Parallel.forRange(n) { from, to ->
            flock(from, to, dt, halfW, wrap)
        }
        // Swap, rather than copy: the arrays are the state.
        var swap = px; px = nextPx; nextPx = swap
        swap = py; py = nextPy; nextPy = swap
        swap = vx; vx = nextVx; nextVx = swap
        swap = vy; vy = nextVy; nextVy = swap

        // How aligned the flock is, which is what "it worked" looks like.
        var sx = 0f
        var sy = 0f
        val sample = min(n, 600)
        for (i in 0 until sample) {
            val s = sqrt(vx[i] * vx[i] + vy[i] * vy[i]) + 1e-6f
            sx += vx[i] / s
            sy += vy[i] / s
        }
        val order0 = sqrt(sx * sx + sy * sy) / sample
        flockiness = flockiness * 0.95f + order0 * 0.05f
        if (elapsed > 2f) {
            env.emit(EventKind.STEP, flockiness.coerceIn(0f, 1f), 0f, 0.25f)
        }
        if (!flocked && elapsed > 6f && flockiness > 0.72f) {
            flocked = true
            payoffFrame = true
            env.emit(EventKind.DONE, 0.85f, 0f, 1f)
        }
    }

    private fun flock(from: Int, to: Int, dt: Float, halfW: Float, wrap: Boolean) {
        val r2 = cellSize * cellSize
        for (i in from until to) {
            // A bird in a dense flock has hundreds of neighbours inside its
            // perception radius, and the averages have converged long before
            // that. Stopping at a fixed number turns the cost from quadratic in
            // the population into linear, and looks the same.
            var sepX = 0f; var sepY = 0f
            var aliX = 0f; var aliY = 0f
            var cohX = 0f; var cohY = 0f
            var neighbours = 0

            val gx = (((px[i] + halfW) / cellSize).toInt()).coerceIn(0, gridW - 1)
            val gy = (((py[i] + 1f) / cellSize).toInt()).coerceIn(0, gridH - 1)
            for (oy in -1..1) {
                if (neighbours >= Flock.MAX) break
                val cy = gy + oy
                if (cy < 0 || cy >= gridH) continue
                for (ox in -1..1) {
                    if (neighbours >= Flock.MAX) break
                    val cx = gx + ox
                    if (cx < 0 || cx >= gridW) continue
                    val c = cy * gridW + cx
                    var k = cellStart[c]
                    val end = cellStart[c] + cellCount[c]
                    while (k < end && neighbours < Flock.MAX) {
                        val j = order[k]
                        k++
                        if (j == i) continue
                        val dx = px[j] - px[i]
                        val dy = py[j] - py[i]
                        val d2 = dx * dx + dy * dy
                        if (d2 > r2 || d2 < 1e-9f) continue
                        neighbours++
                        cohX += px[j]; cohY += py[j]
                        aliX += vx[j]; aliY += vy[j]
                        val inv = 1f / d2
                        sepX -= dx * inv * 0.0012f
                        sepY -= dy * inv * 0.0012f
                    }
                }
            }

            var ax = 0f
            var ay = 0f
            if (neighbours > 0) {
                cohX = cohX / neighbours - px[i]
                cohY = cohY / neighbours - py[i]
                aliX = aliX / neighbours - vx[i]
                aliY = aliY / neighbours - vy[i]
                ax += cohX * cohesion * 0.9f + aliX * alignment * 2.4f + sepX * separation * 30f
                ay += cohY * cohesion * 0.9f + aliY * alignment * 2.4f + sepY * separation * 30f
            }

            var nvx = vx[i] + ax * dt
            var nvy = vy[i] + ay * dt
            val s = sqrt(nvx * nvx + nvy * nvy)
            if (s > 1e-6f) {
                val k = maxSpeed / s
                nvx *= k
                nvy *= k
            }
            var nx = px[i] + nvx * dt
            var ny = py[i] + nvy * dt
            if (wrap) {
                // Wrap-around space: the flock leaves one edge and returns at
                // the other.
                if (nx > halfW) nx -= 2 * halfW else if (nx < -halfW) nx += 2 * halfW
                if (ny > 1f) ny -= 2f else if (ny < -1f) ny += 2f
            } else {
                // In a box, the birds turn at the walls rather than piling up.
                if (nx > halfW) { nx = halfW; nvx = -abs(nvx) }
                else if (nx < -halfW) { nx = -halfW; nvx = abs(nvx) }
                if (ny > 1f) { ny = 1f; nvy = -abs(nvy) }
                else if (ny < -1f) { ny = -1f; nvy = abs(nvy) }
            }
            // Written to the back buffer: a bird near a slice boundary would
            // otherwise read neighbours that another thread had already moved,
            // which makes the step depend on thread timing — and determinism is
            // what the payoff pre-check and the offline render rely on.
            nextPx[i] = nx; nextPy[i] = ny; nextVx[i] = nvx; nextVy[i] = nvy
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val small = n > 4000
        if (small) {
            g.reservePoints(n)
            for (i in 0 until n) {
                g.point(px[i], py[i], 0.006f, palette.at(tint[i]))
            }
        } else {
            g.reserveInstances(n)
            for (i in 0 until n) {
                val a = atan2(vy[i], vx[i])
                g.rect(px[i], py[i], 0.016f, 0.005f, palette.at(tint[i]), a)
            }
        }
        if (payoffFrame) g.flash(0.3f)
    }

    override fun onInput(env: SimEnv, e: TouchEvent) {
        if (e.kind == TouchEvent.Kind.TAP || e.kind == TouchEvent.Kind.DRAG) {
            // A finger scatters the flock, which then reforms.
            for (i in 0 until n) {
                val dx = px[i] - e.x
                val dy = py[i] - e.y
                val d2 = dx * dx + dy * dy
                if (d2 < 0.09f && d2 > 1e-6f) {
                    val inv = 0.05f / d2
                    vx[i] += dx * inv
                    vy[i] += dy * inv
                }
            }
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "birds" to String.format(java.util.Locale.US, "%,d", n),
        "alignment" to String.format(java.util.Locale.US, "%.0f%%", flockiness * 100f)
    )

    private object Flock {
        /** Neighbours any one bird will consider. Averages converge long before. */
        const val MAX = 64

        /** Birds per grid cell the sizing aims for. */
        const val TARGET_PER_CELL = 6f
    }

    companion object {
        fun def(): SimDef = SimDef(
            id = "life/boids",
            title = "Flocking",
            shelf = Shelf.LIFE,
            family = "Particles & flocking",
            blurb = "Three rules, no leader, and a flock forms anyway.",
            formats = listOf(TrapFormat.LOOP, TrapFormat.GROW, TrapFormat.COLOR_BATTLE),
            lawKnobs = setOf(LawKnob.SPACE),
            heroLabel = "Scatter",
            defaultPalette = "Aurora",
            typicalSeconds = 28f,
            params = listOf(
                Param.int("count", "Birds", 10, 60000, 3000, card = Card.DATA, logScale = true),
                Param.float("separation", "Separation", 0f, 3f, 1f, card = Card.MOTION),
                Param.float("alignment", "Alignment", 0f, 3f, 1f, card = Card.MOTION),
                Param.float("cohesion", "Cohesion", 0f, 3f, 1f, card = Card.MOTION),
                Param.float("speed", "Speed", 0.1f, 1.6f, 0.55f, card = Card.MOTION),
                Param.float("range", "Neighbour range", 0.03f, 0.3f, 0.12f, card = Card.MOTION, advancedOnly = true)
            ),
            macros = listOf(
                Macro("chaos", "Chaos", Card.MOTION, mapOf("separation" to 0.2f..3f, "alignment" to 2.5f..0.2f), 0.3f)
            ),
            create = { BoidsSim() }
        )
    }
}

/**
 * Langton's ant: two rules, and after ten thousand steps of apparent chaos it
 * suddenly builds a highway and leaves in a straight line forever.
 */
class LangtonsAntSim : Sim {

    private var w = 180
    private var h = 260
    private var cells = ByteArray(0)
    private var ax = 0
    private var ay = 0
    private var dir = 0
    private var steps = 0L
    private var carry = 0f
    private var payoffFrame = false
    private var highwayAt = -1L
    private var blackCount = 0
    private var recentTurns = IntArray(256)
    private var turnIndex = 0

    override fun setup(env: SimEnv) {
        val p = env.params
        w = p.i("width", 180).coerceIn(20, 600)
        h = p.i("height", 260).coerceIn(20, 800)
        cells = ByteArray(w * h)
        ax = w / 2
        ay = h / 2
        dir = 0
        steps = 0
        carry = 0f
        payoffFrame = false
        highwayAt = -1
        blackCount = 0
        turnIndex = 0
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        carry += abs(dt) * env.params.f("rate", 700f)
        var budget = min(carry.toInt(), 120000)
        carry -= budget
        while (budget-- > 0) {
            val idx = ay * w + ax
            val black = cells[idx].toInt() != 0
            dir = if (black) (dir + 3) % 4 else (dir + 1) % 4
            cells[idx] = if (black) 0 else 1
            blackCount += if (black) -1 else 1
            recentTurns[turnIndex and 255] = if (black) 0 else 1
            turnIndex++
            ax += ANT_DX[dir]
            ay += ANT_DY[dir]
            // Wrapping, not clamping: an ant pinned against a wall flips the
            // same two cells forever, and the highway — which is the payoff —
            // never appears.
            if (ax < 0 || ax >= w || ay < 0 || ay >= h) {
                ax = ((ax % w) + w) % w
                ay = ((ay % h) + h) % h
            }
            steps++
            if (steps % 97L == 0L) {
                env.emit(EventKind.STEP, (blackCount.toFloat() / (w * h)).coerceIn(0f, 1f), 0f, 0.3f)
            }
            // The highway is a 104-step repeating pattern; spotting it is the payoff.
            if (highwayAt < 0 && steps > 9000 && steps % 104L == 0L && isHighway()) {
                highwayAt = steps
                payoffFrame = true
                env.emit(EventKind.DONE, 0.9f, 0f, 1f)
            }
        }
    }

    /** A rolling window whose turn pattern repeats with period 104. */
    private fun isHighway(): Boolean {
        if (turnIndex < 256) return false
        for (k in 0 until 104) {
            val a = recentTurns[(turnIndex - 1 - k) and 255]
            val b = recentTurns[(turnIndex - 1 - k - 104) and 255]
            if (a != b) return false
        }
        return true
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val halfW = env.halfWidth
        val size = min(2f * halfW / w, 2f / h)
        val originX = -size * w * 0.5f
        val originY = -size * h * 0.5f
        g.reservePoints(max(1, blackCount))
        for (i in 0 until w * h) {
            if (cells[i].toInt() == 0) continue
            g.point(
                originX + (i % w + 0.5f) * size,
                originY + (i / w + 0.5f) * size,
                size,
                palette.at(((i % w) + (i / w)).toFloat() / (w + h))
            )
        }
        g.glow(originX + (ax + 0.5f) * size, originY + (ay + 0.5f) * size, size * 6f, Palette.SUCCESS, 0.8f)
        g.disc(originX + (ax + 0.5f) * size, originY + (ay + 0.5f) * size, size * 1.4f, Palette.SUCCESS)
        if (payoffFrame) g.flash(0.35f)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "steps" to String.format(java.util.Locale.US, "%,d", steps),
        if (highwayAt > 0) ("highway at" to String.format(java.util.Locale.US, "%,d", highwayAt)) else ("black" to blackCount.toString())
    )

    companion object {
        fun def(): SimDef = SimDef(
            id = "life/langtons-ant",
            title = "Langton's ant",
            shelf = Shelf.LIFE,
            family = "Cellular automata",
            blurb = "Ten thousand steps of chaos, then it builds a highway.",
            formats = listOf(TrapFormat.SNAP, TrapFormat.GROW, TrapFormat.REVEAL),
            heroLabel = "Restart the ant",
            defaultPalette = "Ember",
            typicalSeconds = 26f,
            params = listOf(
                Param.int("width", "Columns", 20, 600, 180, card = Card.DATA),
                Param.int("height", "Rows", 20, 800, 260, card = Card.DATA),
                Param.float("rate", "Speed", 50f, 200000f, 700f, card = Card.MOTION, unit = "steps/s", logScale = true)
            ),
            create = { LangtonsAntSim() }
        )
    }
}

/**
 * Falling sand: grains settle, pile and slide. Cheap rules, endlessly watchable.
 */
class FallingSandSim : Sim {

    /** The payoff fires once; without this the bell retriggers every step. */
    private var settled = false

    private var w = 140
    private var h = 220
    private var grid = ByteArray(0)
    private var colour = FloatArray(0)
    private var carry = 0f
    private var pourTimer = 0f
    private var filled = 0
    private var payoffFrame = false
    private var pourX = 0
    private var capacity = 0

    override fun setup(env: SimEnv) {
        val p = env.params
        w = p.i("width", 140).coerceIn(20, 400)
        h = p.i("height", 220).coerceIn(20, 600)
        grid = ByteArray(w * h)
        colour = FloatArray(w * h)
        carry = 0f
        pourTimer = 0f
        filled = 0
        payoffFrame = false
        pourX = w / 2
        capacity = (w * h * 0.32f).toInt()
        settled = false
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val pourRate = env.params.f("pour", 420f)
        pourTimer += abs(dt) * pourRate
        while (pourTimer >= 1f && filled < capacity) {
            pourTimer -= 1f
            pourX = (pourX + env.rng.int(-2, 3)).coerceIn(2, w - 3)
            val idx = pourX
            if (grid[idx].toInt() == 0) {
                grid[idx] = 1
                colour[idx] = (env.time * 0.05f) % 1f
                filled++
            }
        }

        carry += abs(dt) * env.params.f("rate", 90f)
        var passes = min(carry.toInt(), 8)
        carry -= passes
        var moved = 0
        while (passes-- > 0) {
            // Bottom-up so a grain cannot fall twice in one pass.
            for (y in h - 2 downTo 0) {
                val row = y * w
                val below = (y + 1) * w
                for (x in 0 until w) {
                    if (grid[row + x].toInt() == 0) continue
                    if (grid[below + x].toInt() == 0) {
                        grid[below + x] = 1; grid[row + x] = 0
                        colour[below + x] = colour[row + x]
                        moved++
                    } else {
                        val dir = if (env.rng.bool()) -1 else 1
                        val sx = x + dir
                        if (sx in 0 until w && grid[below + sx].toInt() == 0) {
                            grid[below + sx] = 1; grid[row + x] = 0
                            colour[below + sx] = colour[row + x]
                            moved++
                        }
                    }
                }
            }
        }
        if (moved > 0) {
            env.emit(EventKind.STEP, (moved.toFloat() / max(1, filled)).coerceIn(0f, 1f), 0f, 0.3f)
        }
        if (!settled && filled >= capacity && moved == 0) {
            settled = true
            payoffFrame = true
            env.emit(EventKind.DONE, 0.8f, 0f, 1f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val halfW = env.halfWidth
        val size = min(2f * halfW / w, 2f / h)
        val originX = -size * w * 0.5f
        val originY = size * h * 0.5f
        g.reservePoints(filled)
        for (i in 0 until w * h) {
            if (grid[i].toInt() == 0) continue
            g.point(
                originX + (i % w + 0.5f) * size,
                originY - (i / w + 0.5f) * size,
                size,
                palette.at(colour[i])
            )
        }
        if (payoffFrame) g.flash(0.25f)
    }

    override fun onInput(env: SimEnv, e: TouchEvent) {
        if (e.kind == TouchEvent.Kind.DRAG || e.kind == TouchEvent.Kind.TAP) {
            val halfW = env.halfWidth
            val size = min(2f * halfW / w, 2f / h)
            val gx = ((e.x + size * w * 0.5f) / size).toInt()
            val gy = ((size * h * 0.5f - e.y) / size).toInt()
            if (gx in 1 until w - 1 && gy in 0 until h) pourX = gx
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> =
        listOf("grains" to String.format(java.util.Locale.US, "%,d", filled))

    companion object {
        fun def(): SimDef = SimDef(
            id = "life/falling-sand",
            title = "Falling sand",
            shelf = Shelf.LIFE,
            family = "Cellular automata",
            blurb = "Grains pile, slide and settle. Drag to move the pour.",
            formats = listOf(TrapFormat.FILL, TrapFormat.GROW),
            heroLabel = "Empty it",
            defaultPalette = "Ember",
            typicalSeconds = 30f,
            params = listOf(
                Param.int("width", "Columns", 20, 400, 140, card = Card.DATA),
                Param.int("height", "Rows", 20, 600, 220, card = Card.DATA),
                Param.float("pour", "Pour rate", 10f, 2000f, 420f, card = Card.MOTION, logScale = true),
                Param.float("rate", "Settle speed", 10f, 600f, 90f, card = Card.MOTION, logScale = true)
            ),
            create = { FallingSandSim() }
        )
    }
}

/**
 * Predator and prey: two populations chasing each other round a loop forever.
 * The classic Lotka-Volterra cycle, drawn as both a field and a phase portrait.
 */
class PredatorPreySim : Sim {

    private var prey = 0.9
    private var predators = 0.4
    private var voiceClock = 0f

    /** Seconds between the two tracking voices. */
    private val VOICE_EVERY = 0.19f
    private var a = 1.1
    private var b = 0.4
    private var c = 0.4
    private var d = 0.1
    private var historyX = FloatArray(0)
    private var historyY = FloatArray(0)
    private var head = 0
    private var filled = 0
    private var elapsed = 0f
    private var cycles = 0
    private var lastRising = true
    private var payoffFrame = false

    override fun setup(env: SimEnv) {
        val p = env.params
        prey = p.f("prey0", 0.9f).toDouble()
        predators = p.f("pred0", 0.4f).toDouble()
        voiceClock = 0f
        a = p.f("growth", 1.1f).toDouble()
        b = p.f("predation", 0.4f).toDouble()
        c = p.f("efficiency", 0.4f).toDouble()
        d = p.f("death", 0.1f).toDouble()
        val len = p.i("history", 3000).coerceIn(200, 40000)
        historyX = FloatArray(len)
        historyY = FloatArray(len)
        head = 0
        filled = 0
        elapsed = 0f
        cycles = 0
        lastRising = true
        payoffFrame = false
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        val h = (dt * env.params.f("rate", 2.2f)).toDouble()
        // RK2 keeps the cycle closed; Euler spirals outwards and ruins the loop.
        val k1x = a * prey - b * prey * predators
        val k1y = c * prey * predators - d * predators
        val mx = prey + k1x * h * 0.5
        val my = predators + k1y * h * 0.5
        val k2x = a * mx - b * mx * my
        val k2y = c * mx * my - d * my
        prey = (prey + k2x * h).coerceIn(0.0001, 40.0)
        predators = (predators + k2y * h).coerceIn(0.0001, 40.0)

        historyX[head] = prey.toFloat()
        historyY[head] = predators.toFloat()
        head = (head + 1) % historyX.size
        if (filled < historyX.size) filled++

        // The populations rising and falling are themselves the sound: two
        // voices tracking each curve, so the model is heard and not only seen.
        voiceClock += abs(dt)
        if (voiceClock >= VOICE_EVERY) {
            voiceClock = 0f
            env.emit(
                EventKind.STEP,
                (prey / 4.0).toFloat().coerceIn(0f, 1f),
                -0.6f,
                (0.2f + (prey / 8.0).toFloat()).coerceIn(0.15f, 0.7f)
            )
            env.emit(
                EventKind.BIRTH,
                (predators / 3.0).toFloat().coerceIn(0f, 1f),
                0.6f,
                (0.18f + (predators / 6.0).toFloat()).coerceIn(0.15f, 0.6f)
            )
        }

        val rising = k2x > 0
        if (rising != lastRising) {
            lastRising = rising
            if (rising) {
                cycles++
                if (cycles == 2) {
                    payoffFrame = true
                    env.emit(EventKind.DONE, 0.8f, 0f, 1f)
                }
            }
            env.emit(EventKind.CROSS, (prey / 4.0).toFloat().coerceIn(0f, 1f), if (rising) -0.5f else 0.5f, 0.7f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val halfW = env.halfWidth

        // Phase portrait: the closed loop is the point of the model.
        g.reservePoints(filled)
        for (k in 0 until filled) {
            val idx = (head - 1 - k + historyX.size * 2) % historyX.size
            val age = k.toFloat() / filled
            g.point(
                (historyX[idx] / 4f - 0.5f) * halfW * 1.5f,
                historyY[idx] / 3f - 0.1f,
                0.006f,
                Palette.withAlpha(palette.at(0.2f + age * 0.5f), (1f - age) * 0.85f + 0.15f)
            )
        }
        g.glow((prey.toFloat() / 4f - 0.5f) * halfW * 1.5f, predators.toFloat() / 3f - 0.1f, 0.09f, Palette.SUCCESS, 0.8f)

        // Live populations as two bars.
        g.bar(-halfW * 0.78f, -0.95f, 0.05f, (prey.toFloat() / 4f).coerceIn(0f, 1.6f), palette.at(0.25f))
        g.bar(-halfW * 0.6f, -0.95f, 0.05f, (predators.toFloat() / 3f).coerceIn(0f, 1.6f), palette.at(0.8f))
        if (payoffFrame) g.flash(0.25f)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "prey" to String.format(java.util.Locale.US, "%.2f", prey),
        "predators" to String.format(java.util.Locale.US, "%.2f", predators),
        "cycles" to cycles.toString()
    )

    companion object {
        fun def(): SimDef = SimDef(
            id = "life/predator-prey",
            title = "Predator and prey",
            shelf = Shelf.LIFE,
            family = "Biology & ecology",
            blurb = "Two populations chasing each other round a closed loop.",
            formats = listOf(TrapFormat.LOOP, TrapFormat.SONG, TrapFormat.LAST_STANDING),
            heroLabel = "New balance",
            defaultPalette = "Candy",
            typicalSeconds = 26f,
            params = listOf(
                Param.float("prey0", "Starting prey", 0.1f, 4f, 0.9f, card = Card.DATA),
                Param.float("pred0", "Starting predators", 0.05f, 3f, 0.4f, card = Card.DATA),
                Param.float("growth", "Prey growth", 0.2f, 3f, 1.1f, card = Card.MOTION, advancedOnly = true),
                Param.float("predation", "Predation", 0.05f, 2f, 0.4f, card = Card.MOTION, advancedOnly = true),
                Param.float("efficiency", "Efficiency", 0.05f, 2f, 0.4f, card = Card.MOTION, advancedOnly = true),
                Param.float("death", "Predator death", 0.02f, 1.5f, 0.1f, card = Card.MOTION, advancedOnly = true),
                Param.int("history", "Trail", 200, 40000, 3000, card = Card.LOOK, logScale = true),
                Param.float("rate", "Speed", 0.1f, 8f, 2.2f, card = Card.MOTION)
            ),
            create = { PredatorPreySim() }
        )
    }
}

/**
 * Reaction and diffusion: two chemicals, one feeding on the other, producing
 * coral, spots and mazes from a uniform start.
 */
class ReactionDiffusionSim : Sim {

    /** The payoff fires once; without this the bell retriggers every step. */
    private var aged = false

    private var w = 150
    private var h = 220
    private var a = FloatArray(0)
    private var b = FloatArray(0)
    private var a2 = FloatArray(0)
    private var b2 = FloatArray(0)
    private var feed = 0.055f
    private var kill = 0.062f
    private var carry = 0f
    private var generations = 0
    private var payoffFrame = false

    override fun setup(env: SimEnv) {
        val p = env.params
        w = p.i("width", 150).coerceIn(32, 420)
        h = p.i("height", 220).coerceIn(32, 600)
        val n = w * h
        a = FloatArray(n) { 1f }
        b = FloatArray(n)
        a2 = FloatArray(n)
        b2 = FloatArray(n)
        when (p.choiceIndex("pattern")) {
            1 -> { feed = 0.0367f; kill = 0.0649f }   // mitosis
            2 -> { feed = 0.0545f; kill = 0.062f }    // maze
            3 -> { feed = 0.078f; kill = 0.061f }     // spots
            else -> { feed = 0.055f; kill = 0.062f }  // coral
        }
        val seeds = p.i("seeds", 16).coerceIn(1, 200)
        repeat(seeds) {
            val cx = env.rng.int(6, w - 6)
            val cy = env.rng.int(6, h - 6)
            for (y in -4..4) for (x in -4..4) {
                val idx = (cy + y) * w + (cx + x)
                if (idx in 0 until n) b[idx] = 1f
            }
        }
        carry = 0f
        generations = 0
        payoffFrame = false
        aged = false
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        carry += abs(dt) * env.params.f("rate", 42f)
        var passes = min(carry.toInt(), 6)
        carry -= passes
        val dA = 1.0f
        val dB = 0.5f
        while (passes-- > 0) {
            Parallel.forRange(h) { from, to -> react(from, to, dA, dB) }
            val ta = a; a = a2; a2 = ta
            val tb = b; b = b2; b2 = tb
            generations++
        }
        var total = 0f
        var i = 0
        while (i < w * h) { total += b[i]; i += 37 }
        env.emit(EventKind.STEP, (total / (w * h / 37f)).coerceIn(0f, 1f), 0f, 0.25f)
        if (!aged && generations > 900) {
            aged = true
            payoffFrame = true
            env.emit(EventKind.DONE, 0.8f, 0f, 1f)
        }
    }

    private fun react(fromRow: Int, toRow: Int, dA: Float, dB: Float) {
        for (y in fromRow until toRow) {
            val yUp = if (y == 0) h - 1 else y - 1
            val yDn = if (y == h - 1) 0 else y + 1
            val row = y * w
            val up = yUp * w
            val dn = yDn * w
            for (x in 0 until w) {
                val xl = if (x == 0) w - 1 else x - 1
                val xr = if (x == w - 1) 0 else x + 1
                val av = a[row + x]
                val bv = b[row + x]
                val lapA = (a[up + x] + a[dn + x] + a[row + xl] + a[row + xr]) * 0.2f +
                    (a[up + xl] + a[up + xr] + a[dn + xl] + a[dn + xr]) * 0.05f - av
                val lapB = (b[up + x] + b[dn + x] + b[row + xl] + b[row + xr]) * 0.2f +
                    (b[up + xl] + b[up + xr] + b[dn + xl] + b[dn + xr]) * 0.05f - bv
                val reaction = av * bv * bv
                a2[row + x] = (av + (dA * lapA - reaction + feed * (1f - av))).coerceIn(0f, 1f)
                b2[row + x] = (bv + (dB * lapB + reaction - (kill + feed) * bv)).coerceIn(0f, 1f)
            }
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val halfW = env.halfWidth
        val size = min(2f * halfW / w, 2f / h)
        val originX = -size * w * 0.5f
        val originY = -size * h * 0.5f
        g.reservePoints(w * h)
        for (i in 0 until w * h) {
            val v = b[i]
            if (v < 0.08f) continue
            g.point(
                originX + (i % w + 0.5f) * size,
                originY + (i / w + 0.5f) * size,
                size * 1.05f,
                palette.at((v * 2.2f).coerceIn(0f, 1f))
            )
        }
        if (payoffFrame) g.flash(0.25f)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> =
        listOf("generations" to String.format(java.util.Locale.US, "%,d", generations))

    companion object {
        fun def(): SimDef = SimDef(
            id = "life/reaction-diffusion",
            title = "Reaction and diffusion",
            shelf = Shelf.LIFE,
            family = "Biology & ecology",
            blurb = "Two chemicals that grow coral, mazes and spots.",
            formats = listOf(TrapFormat.GROW, TrapFormat.REVEAL, TrapFormat.LOOP),
            heroLabel = "Reseed",
            defaultPalette = "Ocean",
            typicalSeconds = 34f,
            params = listOf(
                Param.choice("pattern", "Pattern", listOf("Coral", "Mitosis", "Maze", "Spots"), 0, card = Card.DATA, input = ParamInput.TILES),
                Param.int("width", "Columns", 32, 420, 150, card = Card.DATA),
                Param.int("height", "Rows", 32, 600, 220, card = Card.DATA),
                Param.int("seeds", "Starting blobs", 1, 200, 16, card = Card.DATA),
                Param.float("rate", "Speed", 2f, 160f, 42f, card = Card.MOTION, logScale = true)
            ),
            create = { ReactionDiffusionSim() }
        )
    }
}

object LifeSims {
    fun defs(): List<SimDef> = listOf(
        BoidsSim.def(),
        LangtonsAntSim.def(),
        FallingSandSim.def(),
        PredatorPreySim.def(),
        ReactionDiffusionSim.def()
    )
}
