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
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One row of grid cells as solid squares, a rect per run of the same colour.
 *
 * Grid sims drew each cell as a point, and the point shader is a soft round
 * dot: a sand pile, a field of chemical or an ant's trail read as a dot
 * grid with black between — "swiss cheese". Rects have hard edges in the
 * shader, so abutting runs tile with no seams, and merging a run of equal
 * colours keeps the draw list a fraction of the cell count. [colorOf]
 * answers 0 for an empty cell; palette colours are opaque, so never 0.
 * Each rect is a hair over its cell so the preview's antialiasing cannot
 * leave hairlines between rows either.
 */
internal inline fun G.cellRow(x0: Float, yc: Float, size: Float, count: Int, colorOf: (Int) -> Int) {
    var start = 0
    var cur = 0
    for (x in 0..count) {
        val c = if (x < count) colorOf(x) else 0
        if (c != cur) {
            if (cur != 0) rect(x0 + (start + x) * 0.5f * size, yc, (x - start) * 0.5f * size + size * 0.04f, size * 0.56f, cur)
            start = x
            cur = c
        }
    }
}

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

    private var n = 600
    private var px = FloatArray(0)
    private var py = FloatArray(0)
    private var vx = FloatArray(0)
    private var vy = FloatArray(0)
    private var tint = FloatArray(0)

    // Uniform grid for neighbour lookup.
    private var cellSize = 0.12f
    private var gridW = 0
    /** How far the birds see, in world units: the slider, honoured. */
    private var perception = 0.12f
    /** Cells to walk either side to cover [perception]. */
    private var cellRadius = 1
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
    /** The best order the flock has reached, and how long since it improved. */
    private var bestOrder = 0f
    private var orderStalledFor = 0f
    private var earlyOrder = -1f

    override fun setup(env: SimEnv) {
        flocked = false
        val p = env.params
        // The count is for an upright phone; a wide screen has several times
        // the room, and the same six hundred birds there were a sparse, dim
        // scatter. Scaled to the area, so the density — and the look — hold.
        n = (p.i("count", 600) * max(1f, env.halfWidth / 0.46f)).toInt().coerceIn(10, 60000)
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
        // The grid is sized for cost; the perception radius is the person's.
        //
        // These used to be one number: cellSize was the density-derived size
        // capped at the slider, and the flocking physics used cellSize as its
        // radius. So whenever density wanted a smaller cell than the slider
        // asked for — which is the normal case, and is true at the shipped
        // value — "Neighbour range" did nothing. Measured at nine positions, it
        // drew the same picture at seven of them: everything above 0.0975 of a
        // slider that goes to 0.3.
        //
        // Now the radius is what the slider says and the scan covers it, by
        // walking as many cells as the radius spans. The per-bird neighbour cap
        // that keeps this linear is doing the work of bounding the cost.
        perception = p.f("range", 0.12f).coerceAtLeast(0.02f)
        // One cell per perception radius, so three by three covers it exactly.
        //
        // The cell used to be sized by density and capped at the slider, and
        // the flocking physics used the CELL as its radius — so whenever
        // density wanted a smaller cell than the slider asked for, which is the
        // normal case and is true at the shipped value, "Neighbour range" did
        // nothing. Measured at nine positions it drew the same picture at seven
        // of them: everything above 0.0975 of a slider that goes to 0.3.
        //
        // Sizing the cell to the radius instead was measured against two other
        // arrangements. Density-sized cells with a wider walk covers the same
        // ground and costs more — 182 ms a step for sixty thousand birds at the
        // narrowest range against 41 before — because it is twenty-five cells
        // of bookkeeping rather than nine. The budget below is what bounds the
        // work now, and it bounds it wherever the birds happen to be.
        cellSize = perception.coerceAtLeast(0.012f)
        cellRadius = 1

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
        bestOrder = 0f
        orderStalledFor = 0f
        earlyOrder = -1f
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
        // (Not reset here: the grid is rebuilt every step, and clearing the
        // payoff flag with it rang the "done" bell sixty times a second.)
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        // Read each step rather than cached at setup. These four are the whole
        // Motion card of this sim, and caching them meant the only way to apply
        // one was to rebuild the flock — which works, but resets every bird to
        // its starting place on every release of the slider. Read live, the
        // flock reorganises under your finger, which is the thing worth
        // watching. Four map lookups a step against a neighbour walk over
        // thousands of birds is not a cost worth naming.
        separation = env.params.f("separation", 1f)
        alignment = env.params.f("alignment", 1f)
        cohesion = env.params.f("cohesion", 1f)
        maxSpeed = env.params.f("speed", 0.55f)
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
        var cx = 0f
        val sample = min(n, 600)
        for (i in 0 until sample) {
            val s = sqrt(vx[i] * vx[i] + vy[i] * vy[i]) + 1e-6f
            sx += vx[i] / s
            sy += vy[i] / s
            cx += px[i]
        }
        val order0 = sqrt(sx * sx + sy * sy) / sample
        flockiness = flockiness * 0.95f + order0 * 0.05f
        // From almost the start: two silent seconds opened every run.
        // A wingbeat pitched by how aligned the flock is, quickening as it
        // organises (six a second milling, twelve streaming), panned to where
        // the birds are. It was a note on every step — a hundred and twenty a
        // second, a flat drone that nothing on screen beat time to.
        if (elapsed > 0.4f) {
            beatCarry = min(1.5f, beatCarry + abs(dt) * (6f + 6f * flockiness.coerceIn(0f, 1f)))
            if (beatCarry >= 1f) {
                beatCarry -= 1f
                env.emit(EventKind.STEP, flockiness.coerceIn(0f, 1f), (cx / sample / halfW).coerceIn(-1f, 1f), 0.25f)
            }
        }
        // The payoff is the flock having finished organising: the order it is
        // heading towards, reached.
        //
        // It was an absolute 72% of global alignment, which only a tight
        // perception radius reaches — a wide one averages over so many birds
        // that the flock mills rather than aligns, and measured at a quarter of
        // the Neighbour range slider's travel it never paid off at all. How much
        // better than it started, and whether it is still improving, are
        // questions a flock can answer at any radius.
        if (earlyOrder < 0f && elapsed > Flock.ORDER_BASELINE_AT) earlyOrder = flockiness
        if (flockiness > bestOrder) {
            bestOrder = flockiness
            orderStalledFor = 0f
        } else {
            orderStalledFor += abs(dt)
        }
        val organised = earlyOrder > 0f && bestOrder > earlyOrder * Flock.ORDER_GAIN
        // Not before ten seconds: turning before the walls, the flock
        // organises sooner, and a stall at seven called it done before the
        // streams had formed.
        if (!flocked && elapsed > 10f &&
            ((organised && orderStalledFor > Flock.ORDER_STALL) || elapsed > Flock.ORDER_BACKSTOP)
        ) {
            flocked = true
            payoffFrame = true
        }
    }

    private var beatCarry = 0f

    private fun flock(from: Int, to: Int, dt: Float, halfW: Float, wrap: Boolean) {
        val r2 = perception * perception
        // Separation reaches only the nearest part of the perception disc, as
        // in Reynolds' boids. Applied across the whole radius it out-pushed
        // cohesion everywhere, spaced every bird evenly from every other, and
        // the flock read as a uniform gas of dashes filling the screen rather
        // than streams with sky between them.
        val sepR2 = r2 * SEP_FRAC * SEP_FRAC
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
            // Outwards in rings, nearest cells first.
            //
            // The walk stops at Flock.MAX neighbours, and it used to start at a
            // corner of the block of cells — which is harmless over three by
            // three, where every cell is a neighbour, and wrong as soon as the
            // block is wider: the cap filled up with whatever happened to be in
            // the far corner and the bird steered by birds three cells away
            // while ignoring the ones beside it. Measured, that stopped boids
            // flocking at all — the payoff never fired under any law.
            // Candidates examined, not just those accepted. The accepted cap
            // alone does not bound the walk: with a small radius in a dense
            // flock most candidates fail the distance test, so nothing
            // short-circuits and every cell in range is walked in full —
            // measured at 182 ms a step for sixty thousand birds at the
            // narrowest range, against 41 before the radius was honoured.
            // Nearest-first ordering is what makes a budget safe to spend.
            var looked = 0
            var ring = 0
            while (ring <= cellRadius && neighbours < Flock.MAX && looked < Flock.LOOK) {
                var oy = -ring
                while (oy <= ring && neighbours < Flock.MAX && looked < Flock.LOOK) {
                    val cy = gy + oy
                    val edgeY = oy == -ring || oy == ring
                    var ox = -ring
                    while (ox <= ring && neighbours < Flock.MAX && looked < Flock.LOOK) {
                        // Only the perimeter of this ring; the inside was done
                        // by the rings before it.
                        if (!edgeY && ox != -ring && ox != ring) {
                            ox = ring
                            continue
                        }
                        val cx = gx + ox
                        ox++
                        if (cy < 0 || cy >= gridH || cx < 0 || cx >= gridW) continue
                        val c = cy * gridW + cx
                        var k = cellStart[c]
                        // A share of the budget per cell, too. In a dense
                        // flock the bird's own cell alone filled the budget,
                        // so it steered by its own cell only, cohesion pulled
                        // every bird to its cell's middle, and at twenty
                        // thousand the flock froze into a lattice of clumps
                        // on the grid. At the shipped density no cell holds
                        // its share, and nothing changes.
                        val end = min(cellStart[c] + cellCount[c], cellStart[c] + Flock.LOOK_PER_CELL + 1)
                        var taken = 0
                        while (k < end && neighbours < Flock.MAX && looked < Flock.LOOK && taken < Flock.MAX_PER_CELL) {
                            val j = order[k]
                            k++
                            if (j == i) continue
                            looked++
                            val dx = px[j] - px[i]
                            val dy = py[j] - py[i]
                            val d2 = dx * dx + dy * dy
                            if (d2 > r2 || d2 < 1e-9f) continue
                            neighbours++
                            taken++
                            cohX += px[j]; cohY += py[j]
                            aliX += vx[j]; aliY += vy[j]
                            if (d2 < sepR2) {
                                val inv = 1f / d2
                                sepX -= dx * inv * 0.0012f
                                sepY -= dy * inv * 0.0012f
                            }
                        }
                    }
                    oy++
                }
                ring++
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

            if (!wrap) {
                // In a box, birds see the wall coming and turn before it, as
                // in Reynolds' own boids. Bounced only at contact, the flock
                // packed itself against the top and sides in a band of
                // birds flying into the edge.
                val m = WALL_MARGIN
                val dl = px[i] + halfW; val dr = halfW - px[i]
                val db = py[i] + 1f; val dtp = 1f - py[i]
                if (dl < m) ax += (1f - dl / m) * WALL_PUSH
                if (dr < m) ax -= (1f - dr / m) * WALL_PUSH
                if (db < m) ay += (1f - db / m) * WALL_PUSH
                if (dtp < m) ay -= (1f - dtp / m) * WALL_PUSH
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
        // Coloured by heading, not at random. A random tint per bird made the
        // flock a rainbow confetti of dashes in which no flock could be seen;
        // by heading, birds flying together share a colour and each flock
        // reads as one stream. |angle|/pi is continuous all the way round, so
        // a ramp palette has no seam where the heading wraps. The bird's own
        // tint survives as a faint shimmer within its stream.
        if (small) {
            g.reservePoints(n)
            for (i in 0 until n) {
                val a = atan2(vy[i], vx[i])
                g.point(px[i], py[i], 0.006f, Palette.brighten(palette.at(abs(a) / PI.toFloat()), (tint[i] - 0.5f) * 0.12f))
            }
        } else {
            g.reserveInstances(n * 2)
            // A bright head and a faint streak behind it, both along the
            // bird's velocity: a motion trail without keeping history. Lone
            // dashes of one length read as hatching; the streaks make each
            // stream's direction of travel visible at a glance. The heading
            // uses most of the ramp but not its far end, where Aurora's
            // orange sat against its cyan as a clash.
            for (i in 0 until n) {
                val a = atan2(vy[i], vx[i])
                val c = Palette.brighten(palette.at(0.04f + 0.72f * abs(a) / PI.toFloat()), (tint[i] - 0.5f) * 0.10f)
                val x = px[i]; val y = py[i]
                val tx = vx[i]; val ty = vy[i]
                g.line(x - tx * 0.12f, y - ty * 0.12f, x - tx * 0.03f, y - ty * 0.03f, 0.0034f, c, 0.28f)
                g.line(x - tx * 0.035f, y - ty * 0.035f, x, y, 0.0058f, Palette.brighten(c, 0.08f))
            }
        }
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

        /**
         * Birds a bird will look at, accepted or not. Bounds the walk whatever
         * the radius and the density, which the accepted cap cannot.
         */
        const val LOOK = 120

        /** The shares of [LOOK] and [MAX] any one of the nine cells may use. */
        const val LOOK_PER_CELL = LOOK / 9
        const val MAX_PER_CELL = MAX / 9 + 1

        /** When the starting disorder is read, after the first scatter settles. */
        const val ORDER_BASELINE_AT = 3f

        /** How much more ordered than that counts as having flocked. */
        const val ORDER_GAIN = 1.6f

        /** Not improving for this long means it has arrived. */
        const val ORDER_STALL = 2.5f

        /** A flock that never organises still owes a payoff. */
        const val ORDER_BACKSTOP = 26f
    }

    companion object {
        /** How far from a wall a bird starts to turn, and how hard. */
        const val WALL_MARGIN = 0.15f
        /** Separation radius as a share of perception. */
        const val SEP_FRAC = 0.3f
        const val WALL_PUSH = 10f
        fun def(): SimDef = SimDef(
            id = "life/boids",
            title = "Flocking",
            shelf = Shelf.LIFE,
            family = "Particles & flocking",
            blurb = "Three rules, no leader, and a flock forms anyway.",
            formats = listOf(TrapFormat.GROW, TrapFormat.COLOR_BATTLE),
            lawKnobs = setOf(LawKnob.SPACE),
            heroLabel = "Scatter",
            defaultPalette = "Aurora",
            typicalSeconds = 28f,
            params = listOf(
                // Six hundred, down from three thousand: on a phone-shaped box
                // three thousand birds filled every gap, and the flocks — the
                // point of the sim — could not be told apart from the crowd.
                Param.int("count", "Birds", 10, 60000, 600, card = Card.DATA, logScale = true),
                Param.float("separation", "Separation", 0f, 3f, 1f, card = Card.MOTION),
                Param.float("alignment", "Alignment", 0f, 3f, 1f, card = Card.MOTION),
                Param.float("cohesion", "Cohesion", 0f, 3f, 1f, card = Card.MOTION),
                Param.float("speed", "Speed", 0.1f, 1.6f, 0.55f, card = Card.MOTION),
                // The shipped value is the radius the sim was really using.
                // It said 0.12 and used the density-derived cell size, which at
                // three thousand birds is 0.067 — so the flocking weights are
                // tuned for 0.07, and setting the slider's default to what the
                // code did keeps the shipped flock exactly as it was while the
                // control around it becomes real.
                Param.float(
                    "range", "Neighbour range", 0.03f, 0.3f, 0.07f,
                    card = Card.MOTION, advancedOnly = true
                )
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

    private var w = 120
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
        w = p.i("width", 120).coerceIn(60, 600)
        h = p.i("height", 260).coerceIn(60, 800)
        cells = ByteArray(w * h)
        flippedAt = IntArray(w * h)
        ax = w / 2
        ay = h / 2
        dir = 0
        steps = 0
        carry = 0f
        payoffFrame = false
        highwayAt = -1
        blackCount = 0
        turnIndex = 0
        minX = ax; maxX = ax; minY = ay; maxY = ay
        panX = ax + 0.5f
        panY = ay + 0.5f
        gone = false
        goneFor = 0f
    }

    /** The step each cell last flipped at: its colour is its age. */
    private var flippedAt = IntArray(0)

    /**
     * The box of cells the ant has visited, and the eased centre of it the
     * view is panned to. The view used to close in on a box round the START,
     * but the heap grows lopsided and the highway leaves in one direction, so
     * half that box was always empty: a heap in one corner of a zoomed view,
     * and at the end a small smudge in the middle of an empty screen with
     * the highway off to one side. Framing what was visited fills the screen
     * with the heap early and with heap and highway together at the end.
     */
    private var minX = 0
    private var maxX = 0
    private var minY = 0
    private var maxY = 0
    private var panX = 0f
    private var panY = 0f

    /**
     * The ant has walked off the edge along its highway. It used to wrap: the
     * highway came back in at the opposite side as a second, unrelated line,
     * and on the torus it would in time crash into its own debris — which the
     * ant on the infinite plane never does. Before the highway it still wraps
     * (see step), since a small grid can be met before the highway forms.
     */
    private var gone = false
    private var goneFor = 0f

    override fun focus(env: SimEnv): Float {
        val size = min(2f * env.halfWidth / w, 2f / h)
        // The box round the start, across and up taken separately: the screen
        // is twice as tall as it is wide, and a square took the width's limit
        // for both, leaving a small smudge in a tall empty screen.
        val hw = env.halfWidth
        val m = min(hw, 1f)
        val halfX = (maxX - minX + 1) * 0.5f
        val halfY = (maxY - minY + 1) * 0.5f
        return max((halfX + FOCUS_MARGIN) * size * m / hw, (halfY + FOCUS_MARGIN) * size * m)
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        // Eased, so the view glides as the box grows a cell at a time.
        val ease = min(1f, abs(dt) * 4f)
        panX += ((minX + maxX + 1) * 0.5f - panX) * ease
        panY += ((minY + maxY + 1) * 0.5f - panY) * ease
        if (gone) {
            goneFor += abs(dt)
            return
        }
        carry += abs(dt) * env.params.f("rate", 700f)
        var budget = min(carry.toInt(), 120000)
        carry -= budget
        while (budget-- > 0) {
            val idx = ay * w + ax
            val black = cells[idx].toInt() != 0
            dir = if (black) (dir + 3) % 4 else (dir + 1) % 4
            cells[idx] = if (black) 0 else 1
            flippedAt[idx] = steps.toInt()
            blackCount += if (black) -1 else 1
            recentTurns[turnIndex and 255] = if (black) 0 else 1
            turnIndex++
            val nx = ax + ANT_DX[dir]
            val ny = ay + ANT_DY[dir]
            steps++
            if (nx < 0 || nx >= w || ny < 0 || ny >= h) {
                // On its highway, it walks off the world and keeps going.
                if (highwayAt >= 0) {
                    gone = true
                    // At the edge it walked off, in the framed view.
                    val pan = (ax + 0.5f - panX) / ((maxX - minX + 1) * 0.5f + FOCUS_MARGIN)
                    env.emit(EventKind.STEP, 0.95f, pan.coerceIn(-1f, 1f), 0.6f)
                    break
                }
                // Before: wrapping, not clamping. An ant pinned against a
                // wall flips the same two cells forever, and the highway —
                // which is the payoff — never appears.
                ax = ((nx % w) + w) % w
                ay = ((ny % h) + h) % h
            } else {
                ax = nx
                ay = ny
            }
            if (ax < minX) minX = ax else if (ax > maxX) maxX = ax
            if (ay < minY) minY = ay else if (ay > maxY) maxY = ay
            if (steps % 97L == 0L) {
                // Panned to the ant's place in the view, which is framed on
                // the box it has drawn (see focus).
                val pan = (ax + 0.5f - panX) / ((maxX - minX + 1) * 0.5f + FOCUS_MARGIN)
                env.emit(EventKind.STEP, (blackCount.toFloat() / (w * h)).coerceIn(0f, 1f), pan.coerceIn(-1f, 1f), 0.3f)
            }
            // The highway is a 104-step repeating pattern; spotting it is the payoff.
            if (highwayAt < 0 && steps > 9000 && steps % 104L == 0L && isHighway()) {
                highwayAt = steps
                payoffFrame = true
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
        // Panned to the visited box, but never so far that the view, at the
        // zoom the camera will use (SimCore.fit), shows past the grid's edge.
        val zoom = (min(halfW, 1f) / focus(env)).coerceIn(1f, 4f)
        val visX = halfW / (zoom * size)
        val visY = 1f / (zoom * size)
        val px = if (visX * 2f >= w) w * 0.5f else panX.coerceIn(visX, w - visX)
        val py = if (visY * 2f >= h) h * 0.5f else panY.coerceIn(visY, h - visY)
        val originX = -size * px
        val originY = -size * py
        // Square cells, coloured by age: the freshest trail at the bright end
        // of the palette, the oldest at the deep end. They were round dots
        // coloured by position, a speckle whose colour said nothing — and the
        // highway changed colour as it crossed the screen. By age, the chaos
        // reads as a slowly cooling heap and the highway as a hot new road
        // running out of it. In bands, so runs of a row merge into one rect.
        val now = steps.toInt()
        val span = max(1f, steps.toFloat())
        val shades = IntArray(AGE_BANDS) { palette.at(it / (AGE_BANDS - 1f) * 0.92f) }
        for (y in 0 until h) {
            val row = y * w
            g.cellRow(originX, originY + (y + 0.5f) * size, size, w) { x ->
                if (cells[row + x].toInt() == 0) 0
                else {
                    val age = (now - flippedAt[row + x]) / span
                    // A square root, so the young highway spans many bands
                    // and the old heap only a few.
                    shades[(sqrt(age) * (AGE_BANDS - 1)).toInt().coerceIn(0, AGE_BANDS - 1)]
                }
            }
        }
        if (!gone) {
            val cx = originX + (ax + 0.5f) * size
            val cy = originY + (ay + 0.5f) * size
            // At least a few pixels, so the ant is findable once the view pulls back.
            val r = max(size * 1.1f, 0.006f)
            g.glow(cx, cy, r * 5f, Palette.SUCCESS, 0.7f)
            g.disc(cx, cy, r, Palette.SUCCESS)
        }
    }

    override fun finished(): Boolean = gone && goneFor > 2.5f

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "steps" to String.format(java.util.Locale.US, "%,d", steps),
        if (highwayAt > 0) ("highway at" to String.format(java.util.Locale.US, "%,d", highwayAt)) else ("black" to blackCount.toString())
    )

    companion object {
        const val FOCUS_MARGIN = 6
        const val AGE_BANDS = 24
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
                // From sixty: on a smaller wrapped grid the ant meets its own
                // trail coming round and the highway never forms.
                // 120, not 180: the heap the ant builds before its highway is
                // about seventy cells across whatever the grid, and on the
                // wider grid the finished picture — heap and highway, fully
                // zoomed out — was a small smudge in the middle of the screen.
                // 120 by 260 is the shape of an upright phone.
                Param.int("width", "Columns", 60, 600, 120, card = Card.DATA),
                Param.int("height", "Rows", 60, 800, 260, card = Card.DATA),
                Param.float("rate", "Speed", 50f, 200000f, 700f, card = Card.MOTION, unit = "steps/s", logScale = true)
            ),
            create = { LangtonsAntSim() }
        )
    }
}

/**
 * Falling sand: grains settle, pile and slide. Cheap rules, endlessly watchable.
 */
/** Grains a full box holds at the shipped 120 by 250. */
/**
 * How full the box ends. It was 0.32, and the finished pile was a low ridge
 * along the floor of a mostly empty box.
 */
private const val SAND_FILL = 0.42f

private const val SAND_SHIPPED_CAPACITY = 120f * 250f * SAND_FILL

class FallingSandSim : Sim {

    /** The payoff fires once; without this the bell retriggers every step. */
    private var settled = false
    private var settledFor = 0f
    /** Passes in a row in which no grain moved. */
    private var quietPasses = 0

    private var w = 120
    private var h = 250
    private var grid = ByteArray(0)
    private var colour = FloatArray(0)
    private var carry = 0f
    private var pourTimer = 0f
    private var filled = 0
    private var payoffFrame = false
    private var pourX = 0
    private var pourAt = 0f
    private var vacated = IntArray(0)
    private var passId = 0
    private var pourDrift = 0f
    private var capacity = 0
    /** Draw scratch: each grain's colour blended with its neighbours'. */
    private var smooth = FloatArray(0)

    override fun setup(env: SimEnv) {
        val p = env.params
        w = p.i("width", 120).coerceIn(20, 400)
        h = p.i("height", 250).coerceIn(20, 600)
        // The sliders give the tank for an upright phone. On a wide screen
        // that tank stood as a narrow column in the middle of the frame, so
        // the same grains (half as many again, for the bigger picture) are
        // reshaped to the screen: a tank as wide as the screen allows.
        // Filled a little less: the pile is one broad heap, and at the phone's
        // share its peak reached the rim and took half a minute more to settle.
        val shape = env.halfWidth * TANK_FILL_X / TANK_FILL_Y
        var fill = SAND_FILL
        if (shape > w.toFloat() / h * 1.3f) {
            fill = SAND_FILL * 0.72f
            val cells = w * h * 1.2f
            val rows = kotlin.math.sqrt(cells / shape).toInt().coerceIn(20, 600)
            w = (rows * shape).toInt().coerceIn(20, 800)
            h = rows
        }
        grid = ByteArray(w * h)
        colour = FloatArray(w * h)
        vacated = IntArray(w * h)
        passId = 0
        carry = 0f
        pourTimer = 0f
        filled = 0
        payoffFrame = false
        pourX = w / 2
        capacity = (w * h * fill).toInt()
        pourAt = w / 2f
        pourDrift = 0f
        settled = false
        settledFor = 0f
        quietPasses = 0
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        // In proportion to the box: the same rate filled a 20-by-20 box in a
        // third of a second and a 400-by-600 one in three minutes. The slider
        // is grains a second at the shipped size, and the fill takes the same
        // time whatever the size.
        val pourRate = env.params.f("pour", 560f) * (capacity / SAND_SHIPPED_CAPACITY).coerceAtLeast(0.05f)
        // The spout wanders slowly, so the pile grows several peaks. It used
        // to jump up to two columns at random with every grain, and the
        // falling grains drew that walk as a scraggly zigzag hanging from the
        // top; now it is a steady stream that sways.
        // A gentle pull back towards the middle, too: left to wander, the
        // spout could spend half a run against one wall and heap the whole
        // pile into that corner of the tank.
        // In columns, so scaled to the tank: a wide tank's spout crawled.
        val sway = max(1f, w / 120f)
        pourDrift = (pourDrift * (1f - abs(dt)) + env.rng.range(-1f, 1f) * abs(dt) * 40f * sway +
            (w * 0.5f - pourAt) / w * abs(dt) * 12f * sway).coerceIn(-14f * sway, 14f * sway)
        pourAt = (pourAt + pourDrift * abs(dt)).coerceIn(w * 0.2f, w * 0.8f)
        if (pourAt <= w * 0.2f || pourAt >= w * 0.8f) pourDrift = -pourDrift
        pourX = pourAt.toInt().coerceIn(2, w - 3)
        pourTimer += abs(dt) * pourRate
        // The stream is as wide as it needs to be to carry the rate at about
        // half density: a cell at the spout takes one grain a pass, so a
        // one-column stream cannot pour faster than the settle speed, and a
        // packed one fell as a solid wall that looked like part of the pile.
        val passRate = max(1f, env.params.f("rate", 160f))
        val spread = (pourRate / passRate).toInt().coerceIn(1, max(1, w / 6))
        var tries = 0
        while (pourTimer >= 1f && filled < capacity && tries < 4 * spread + 8) {
            tries++
            val x = (pourX + env.rng.int(-spread, spread + 1)).coerceIn(0, w - 1)
            if (grid[x].toInt() == 0) {
                pourTimer -= 1f
                grid[x] = 1
                // Strata: the colour sweeps along the palette and back, so
                // the layers shade from one into the next. A sawtooth jumped
                // from the dark end to the light one in a hard seam.
                val phase = (env.time * 0.05f) % 1f
                colour[x] = 1f - abs(2f * phase - 1f)
                filled++
            }
        }
        // Grains the spout could not place this frame are not owed later.
        pourTimer = min(pourTimer, 4f * spread)

        carry += abs(dt) * env.params.f("rate", 160f)
        var passes = min(carry.toInt(), 8)
        carry -= passes
        var moved = 0
        while (passes-- > 0) {
            val before = moved
            // A cell emptied during this pass is not free until the next.
            // Updated in place, a whole column fell as one in a single pass
            // — each grain finding the cell below just vacated — so a grain
            // on a vertical face never looked sideways, and a sheer cliff
            // stood for seconds, draining one grain a pass from its foot.
            // Deciding from the state at the start of the pass, as a
            // cellular automaton should, the face slumps to the angle of
            // repose at once.
            passId++
            val pass = passId
            // Bottom-up so a grain cannot fall twice in one pass.
            for (y in h - 2 downTo 0) {
                val row = y * w
                val below = (y + 1) * w
                // The sweep direction alternates by row and pass. Always
                // left to right, a grain's left-hand landing cell had already
                // been claimed by its neighbours' falls and slides, its right
                // one never: the heap grew lopsided, its peak drifting right
                // of the spout with a sheer cliff standing on the left.
                val flip = ((y + pass) and 1) == 1
                for (i in 0 until w) {
                    val x = if (flip) w - 1 - i else i
                    if (grid[row + x].toInt() == 0) continue
                    if (grid[below + x].toInt() == 0 && vacated[below + x] != pass) {
                        grid[below + x] = 1; grid[row + x] = 0
                        vacated[row + x] = pass
                        colour[below + x] = colour[row + x]
                        moved++
                    } else {
                        // Down one side at random, or, blocked, the other:
                        // a grain only stops when both are full.
                        val dir = if (env.rng.bool()) -1 else 1
                        var sx = x + dir
                        if (sx !in 0 until w || grid[below + sx].toInt() != 0 || vacated[below + sx] == pass) sx = x - dir
                        if (sx in 0 until w && grid[below + sx].toInt() == 0 && vacated[below + sx] != pass) {
                            grid[below + sx] = 1; grid[row + x] = 0
                            vacated[row + x] = pass
                            colour[below + sx] = colour[row + x]
                            moved++
                        }
                    }
                }
            }
            // Counted pass by pass: a step that ran no pass at all had
            // "nothing moved" too, and called the pile settled mid-fall.
            if (moved == before) quietPasses++ else quietPasses = 0
        }
        // The patter of grains landing: about fourteen a second while the
        // spout pours, thinning with the slumping once it stops, rising in
        // pitch as the tank fills, at the spout's side. It was a STEP on every
        // step anything moved — a hundred and fifteen a second, one buzz.
        if (moved > 0) {
            val pouring = filled < capacity
            val busy = if (pouring) 1f else min(1f, moved / 200f)
            patterCarry = min(1.5f, patterCarry + abs(dt) * 14f * busy)
            if (patterCarry >= 1f) {
                patterCarry -= 1f
                val pan = if (pouring) (pourX + 0.5f) / w * 2f - 1f else 0f
                env.emit(EventKind.STEP, (filled.toFloat() / max(1, capacity)).coerceIn(0f, 1f), pan, 0.15f + 0.2f * busy)
            }
        }
        if (!settled && filled >= capacity && quietPasses >= QUIET_PASSES) {
            settled = true
            payoffFrame = true
        }
        if (settled) settledFor += abs(dt)
    }

    /**
     * Seconds the finished picture is held before the run reports finished,
     * and the screens give it the resolve beat and start a fresh one. It
     * reached its end and then sat there — measured, twenty seconds frozen and silent in a sixty-second run — without ever saying
     * it was done, so nothing restarted it: a still, silent screen that read
     * as the app having stopped.
     */
private var patterCarry = 0f
        private val HOLD_SECONDS = 1.5f

    override fun finished(): Boolean = settled && settledFor > HOLD_SECONDS

    /**
     * Grain size on screen. Inset from the screen's edges, so the glass walls
     * and the spout above the tank are on screen rather than on its border.
     */
    private fun cellSize(halfW: Float): Float = min(2f * halfW * TANK_FILL_X / w, 2f * TANK_FILL_Y / h)

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val halfW = env.halfWidth
        val size = cellSize(halfW)
        val originX = -size * w * 0.5f
        // A little below centre: the spout sits above the tank's top.
        val originY = size * h * 0.5f - size * 2f
        // The glass the sand is poured into: two walls and a floor, so the
        // pile stands on something and the space above it reads as the tank
        // rather than as empty screen.
        val glass = Palette.withAlpha(Palette.TEXT_PRIMARY, 0.32f)
        val lw = max(size * 0.5f, 0.004f)
        val left = originX - lw * 0.5f
        val right = -originX + lw * 0.5f
        val floor = originY - size * h - lw * 0.5f
        g.line(left, floor, right, floor, lw, glass)
        g.line(left, floor, left, originY, lw, glass)
        g.line(right, floor, right, originY, lw, glass)
        // The spout.
        g.rect(originX + (pourX + 0.5f) * size, originY + size * 2f, size * 3f, size * 1.5f, palette.at(0f))
        // Square grains in banded shades, merged into runs: as round dots the
        // pile was a dot grid with black showing between every grain.
        val shades = IntArray(SAND_BANDS) { palette.at(it / (SAND_BANDS - 1f)) }
        // Each grain shaded with its touching neighbours, weighted to itself:
        // where an avalanche runs down a flank it interleaves grains from two
        // strata one by one, and drawn raw that flank was a checkerboard of
        // light and dark squares, a moiré of diagonal streaks beside the
        // clean bands of the other side. The strata themselves are many
        // grains thick and keep their edges.
        if (smooth.size != w * h) smooth = FloatArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                if (grid[i].toInt() == 0) continue
                var sum = colour[i] * 2f
                var n = 2f
                if (x > 0 && grid[i - 1].toInt() != 0) { sum += colour[i - 1]; n += 1f }
                if (x < w - 1 && grid[i + 1].toInt() != 0) { sum += colour[i + 1]; n += 1f }
                if (y > 0 && grid[i - w].toInt() != 0) { sum += colour[i - w]; n += 1f }
                if (y < h - 1 && grid[i + w].toInt() != 0) { sum += colour[i + w]; n += 1f }
                smooth[i] = sum / n
            }
        }
        for (y in 0 until h) {
            val row = y * w
            g.cellRow(originX, originY - (y + 0.5f) * size, size, w) { x ->
                if (grid[row + x].toInt() == 0) 0
                else shades[(smooth[row + x] * (SAND_BANDS - 1) + 0.5f).toInt().coerceIn(0, SAND_BANDS - 1)]
            }
        }
    }

    override fun onInput(env: SimEnv, e: TouchEvent) {
        if (e.kind == TouchEvent.Kind.DRAG || e.kind == TouchEvent.Kind.TAP) {
            val halfW = env.halfWidth
            val size = cellSize(halfW)
            val gx = ((e.x + size * w * 0.5f) / size).toInt()
            val gy = ((size * h * 0.5f - size * 2f - e.y) / size).toInt()
            if (gx in 1 until w - 1 && gy in 0 until h) { pourX = gx; pourAt = gx.toFloat(); pourDrift = 0f }
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> =
        listOf("grains" to String.format(java.util.Locale.US, "%,d", filled))

    companion object {
        const val QUIET_PASSES = 3
        const val SAND_BANDS = 32
        /** Shares of the screen's width and height the tank may take. */
        const val TANK_FILL_X = 0.9f
        const val TANK_FILL_Y = 0.9f
        fun def(): SimDef = SimDef(
            id = "life/falling-sand",
            title = "Falling sand",
            shelf = Shelf.LIFE,
            family = "Cellular automata",
            blurb = "Grains pile, slide and settle. Drag to move the pour.",
            formats = listOf(TrapFormat.FILL, TrapFormat.GROW),
            heroLabel = "Empty it",
            defaultPalette = "Ember",
            // Settled means no grain moving for three passes in a row, which is
            // a little later than the old test, which fired mid-fall.
            typicalSeconds = 36f,
            params = listOf(
                // About the shape of an upright phone. At 140 by 220 the tank
                // was wider than the screen's proportions allowed, so it was
                // fitted to the width: the walls sat on the screen's edges,
                // invisible, with an empty band under the floor.
                Param.int("width", "Columns", 20, 400, 120, card = Card.DATA),
                Param.int("height", "Rows", 20, 600, 250, card = Card.DATA),
                Param.float("pour", "Pour rate", 10f, 2000f, 560f, card = Card.MOTION, logScale = true),
                Param.float("rate", "Settle speed", 10f, 600f, 160f, card = Card.MOTION, logScale = true)
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

    /**
     * The largest populations seen this run, which set the plot's scale. It
     * was a fixed four prey and three predators to the screen, and at the
     * shipped values the predators reach nine: two thirds of the loop was
     * drawn above the top of the screen. Only ever grows, so the picture
     * settles once the loop has been round once instead of breathing.
     */
    private var peakPrey = 1.0
    private var peakPred = 1.0
    private var voiceClock = 0f

    /** Seconds between the two tracking voices. */
    private val VOICE_EVERY = 0.19f
    private var a = 1.1
    private var b = 0.4
    private var c = 0.4
    private var d = 0.1
    private var historyX = FloatArray(0)
    private var historyY = FloatArray(0)
    /** When each sample was taken: the steps shrink in the payoff's slow motion. */
    private var historyT = FloatArray(0)
    private var head = 0
    private var filled = 0
    private var elapsed = 0f
    private var cycles = 0
    private var lastRising = true
    private var payoffFrame = false

    override fun setup(env: SimEnv) {
        val p = env.params
        prey = p.f("prey0", 0.6f).toDouble()
        predators = p.f("pred0", 1.4f).toDouble()
        peakPrey = max(prey * 1.2, 0.5)
        peakPred = max(predators * 1.2, 0.5)
        voiceClock = 0f
        a = p.f("growth", 1.1f).toDouble()
        b = p.f("predation", 0.4f).toDouble()
        c = p.f("efficiency", 0.4f).toDouble()
        d = p.f("death", 0.1f).toDouble()
        val len = p.i("history", 3000).coerceIn(200, 40000)
        historyX = FloatArray(len)
        historyY = FloatArray(len)
        historyT = FloatArray(len)
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
        // In logarithms, u = ln prey and v = ln predators, where the
        // equations are u' = a − b·e^v and v' = c·e^u − d: the populations can
        // fall as close to zero as the model says (a millionth, at the
        // defaults) without a floor. The floor that used to stand in put the
        // system on a different, smaller orbit after the first crash, and the
        // loop drawn wasn't the model's. RK4 keeps the orbit closed.
        var u = ln(prey); var v = ln(predators)
        fun du(vv: Double) = a - b * exp(vv)
        fun dv(uu: Double) = c * exp(uu) - d
        val k1u = du(v); val k1v = dv(u)
        val k2u = du(v + k1v * h / 2); val k2v = dv(u + k1u * h / 2)
        val k3u = du(v + k2v * h / 2); val k3v = dv(u + k2u * h / 2)
        val k4u = du(v + k3v * h); val k4v = dv(u + k3u * h)
        u += h / 6 * (k1u + 2 * k2u + 2 * k3u + k4u)
        v += h / 6 * (k1v + 2 * k2v + 2 * k3v + k4v)
        prey = exp(u).coerceIn(1e-300, 1e6)
        predators = exp(v).coerceIn(1e-300, 1e6)
        if (prey > peakPrey) peakPrey = prey
        if (predators > peakPred) peakPred = predators

        historyX[head] = prey.toFloat()
        historyY[head] = predators.toFloat()
        historyT[head] = abs(env.time)
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

        // Prey rising: u' = a − b·predators > 0.
        val rising = a - b * predators > 0
        if (rising != lastRising) {
            lastRising = rising
            if (rising) {
                cycles++
                if (cycles == 2) payoffFrame = true
            }
            env.emit(EventKind.CROSS, (prey / 4.0).toFloat().coerceIn(0f, 1f), if (rising) -0.5f else 0.5f, 0.7f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val halfW = env.halfWidth

        // Two plots of the one model, stacked: the phase portrait above — prey
        // across, predators up, the closed loop that is the point of the
        // model — and the two populations against time below, the classic
        // pair of out-of-step waves. It was the loop as a faint dotted
        // trail with two tall solid bars over the bottom of it, which hid
        // the loop's lower edge and said nothing the loop did not.
        // Stacked on a phone; side by side on a wide screen, where stacking
        // left both plots short strips in the middle of a lot of black.
        val wide = halfW > 1f
        val left: Float; val pRight: Float; val phaseBottom: Float; val phaseTop: Float
        val wLeft: Float; val wRight: Float; val waveBottom: Float; val waveTop: Float
        if (wide) {
            val span = halfW * 0.9f
            val half = (2f * span - 0.2f) * 0.5f
            left = -span; pRight = -span + half; phaseBottom = -0.8f; phaseTop = 0.84f
            wLeft = span - half; wRight = span; waveBottom = -0.62f; waveTop = 0.62f
        } else {
            val pw = min(halfW, 1.1f) * 0.84f
            left = -pw; pRight = pw; phaseBottom = -0.1f; phaseTop = 0.84f
            wLeft = -pw; wRight = pw; waveBottom = -0.88f; waveTop = -0.34f
        }
        val sx = (pRight - left) / peakPrey.toFloat()
        val sy = (phaseTop - phaseBottom) / peakPred.toFloat()
        val axis = Palette.withAlpha(Palette.TEXT_PRIMARY, 0.2f)
        val preyColour = palette.at(0.08f)
        val predColour = palette.at(0.92f)
        val loopColour = palette.at(0.5f)
        // Axes: each plot's own L, coloured by what it measures.
        g.line(left, phaseBottom, pRight, phaseBottom, 0.004f, Palette.withAlpha(preyColour, 0.45f))
        g.line(left, phaseBottom, left, phaseTop, 0.004f, Palette.withAlpha(predColour, 0.45f))
        g.line(wLeft, waveBottom, wRight, waveBottom, 0.004f, axis)
        // The balance point, which the loop circles and never reaches: prey
        // d/c, predators a/b.
        val ex = left + (d / c).toFloat() * sx
        val ey = phaseBottom + (a / b).toFloat() * sy
        if (ex < pRight && ey < phaseTop) {
            g.line(ex - 0.018f, ey, ex + 0.018f, ey, 0.003f, axis)
            g.line(ex, ey - 0.018f, ex, ey + 0.018f, 0.003f, axis)
        }
        // The loop as a line, oldest faint and newest bright. At most a few
        // hundred segments: the history is thousands of steps, and a dot per
        // step drew a dotted line wherever the orbit ran fast.
        val stride = max(1, filled / 900)
        var prevX = Float.NaN; var prevY = 0f
        var k = filled - 1
        while (k >= 0) {
            val idx = (head - 1 - k + historyX.size * 2) % historyX.size
            val px = left + historyX[idx] * sx
            val py = phaseBottom + historyY[idx] * sy
            if (!prevX.isNaN()) {
                val age = k.toFloat() / max(1, filled)
                g.line(prevX, prevY, px, py, 0.0065f, loopColour, 0.15f + 0.85f * (1f - age) * (1f - age))
            }
            prevX = px; prevY = py
            k = if (k == 0) -1 else max(0, k - stride)
        }
        val cx = left + prey.toFloat() * sx
        val cy = phaseBottom + predators.toFloat() * sy
        g.glow(cx, cy, 0.07f, loopColour, 0.6f)
        g.disc(cx, cy, 0.013f, Palette.TEXT_PRIMARY)
        // Below: the same history against time, each population on its own
        // scale, newest at the right. Placed by when each sample was taken,
        // not by its index: the payoff's slow motion takes four samples a
        // tick, and by index the curves grew a kink there that rode along.
        val newest = if (filled > 0) historyT[(head - 1 + historyX.size) % historyX.size] else 0f
        val oldest = if (filled > 0) historyT[(head - filled + historyX.size * 2) % historyX.size] else 0f
        val window = max(1e-3f, newest - oldest)
        val wh = waveTop - waveBottom
        var lx = Float.NaN; var lp = 0f; var lq = 0f
        k = filled - 1
        while (k >= 0) {
            val idx = (head - 1 - k + historyX.size * 2) % historyX.size
            val x = wRight - (wRight - wLeft) * (newest - historyT[idx]) / window
            val yp = waveBottom + (historyX[idx] / peakPrey.toFloat()).coerceIn(0f, 1f) * wh
            val yq = waveBottom + (historyY[idx] / peakPred.toFloat()).coerceIn(0f, 1f) * wh
            if (!lx.isNaN()) {
                g.line(lx, lp, x, yp, 0.006f, preyColour)
                g.line(lx, lq, x, yq, 0.006f, predColour)
            }
            lx = x; lp = yp; lq = yq
            k = if (k == 0) -1 else max(0, k - stride)
        }
        if (filled > 0) {
            g.disc(wRight, waveBottom + (prey / peakPrey).toFloat().coerceIn(0f, 1f) * wh, 0.011f, preyColour)
            g.disc(wRight, waveBottom + (predators / peakPred).toFloat().coerceIn(0f, 1f) * wh, 0.011f, predColour)
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        // Three significant figures: in the crash the prey is a millionth,
        // and two decimals read "0.00" for most of every cycle.
        "prey" to String.format(java.util.Locale.US, "%.3g", prey),
        "predators" to String.format(java.util.Locale.US, "%.3g", predators),
        "cycles" to cycles.toString()
    )

    companion object {
        fun def(): SimDef = SimDef(
            id = "life/predator-prey",
            title = "Predator and prey",
            shelf = Shelf.LIFE,
            family = "Biology & ecology",
            blurb = "Two populations chasing each other round a closed loop.",
            formats = listOf(TrapFormat.SONG, TrapFormat.LAST_STANDING),
            heroLabel = "New balance",
            defaultPalette = "Candy",
            typicalSeconds = 26f,
            params = listOf(
                // Started nearer the balance point (0.25, 2.75) than the old
                // 0.9 and 0.4, whose orbit crashed the prey to a millionth
                // and drew the loop flat along both axes. Same equations; a
                // rounder orbit that reads as a loop.
                Param.float("prey0", "Starting prey", 0.1f, 4f, 0.6f, card = Card.DATA),
                Param.float("pred0", "Starting predators", 0.05f, 3f, 1.4f, card = Card.DATA),
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
            // Measured, not copied: the old maze was coral's numbers to three
            // places and drew the same picture; the old spots only swelled
            // the seeds. These make one labyrinth, and a field of spots that
            // keep dividing (88 → 117 in two minutes).
            2 -> { feed = 0.029f; kill = 0.057f }     // maze
            3 -> { feed = 0.03f; kill = 0.062f }      // spots
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
        // A pulse that quickens while the pattern is spreading and slows to
        // three a second once it only shifts. It was a STEP on every step, a
        // hundred and twenty a second of one pitch: a drone, not the growth.
        val mean = total / (w * h / 37f)
        val growth = if (abs(dt) > 0f) (abs(mean - lastMean) / abs(dt) * 40f).coerceIn(0f, 1f) else 0f
        lastMean = mean
        pulseCarry = min(1.5f, pulseCarry + abs(dt) * (3f + 9f * growth))
        if (pulseCarry >= 1f) {
            pulseCarry -= 1f
            env.emit(EventKind.STEP, mean.coerceIn(0f, 1f), 0f, 0.15f + 0.25f * growth)
        }
        if (!aged && generations > 900) {
            aged = true
            payoffFrame = true
        }
    }

    private var lastMean = 0f
    private var pulseCarry = 0f

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
        // The field is a torus — every edge wraps to the opposite one — so
        // repeating it to cover the screen is the same pattern, exactly, not
        // a stretch. It used to be drawn once in the middle with bands of
        // black above and below, and a blob crossing the wrap was cut off
        // flat at the bottom edge.
        val cols = kotlin.math.ceil(halfW / size).toInt()
        val rowsUp = kotlin.math.ceil(1f / size).toInt()
        val x0 = -cols * size
        // Shaded so more of the second chemical is brighter, and the thin
        // edge of each blob fades into the background. The ramp ran the
        // other way: bright rims round dark cores, which read as donuts.
        // Ramps that go from light to dark are flipped so the light end is
        // the strong one; a bright-ended ramp is used as it is.
        val first = palette.at(0f)
        val last = palette.at(1f)
        fun lum(c: Int) = ((c ushr 16) and 255) * 0.3f + ((c ushr 8) and 255) * 0.59f + (c and 255) * 0.11f
        val flip = lum(first) > lum(last)
        val shades = IntArray(RD_BANDS) { k ->
            val t = 0.06f + 0.94f * k / (RD_BANDS - 1f)
            palette.at(if (flip) 1f - t else t)
        }
        for (sy in -rowsUp until rowsUp) {
            val y = ((sy + h * 64) % h)
            val row = y * w
            g.cellRow(x0, (sy + 0.5f) * size, size, 2 * cols) { sx ->
                val v = b[row + ((sx - cols + w * 64) % w)]
                if (v < 0.06f) 0
                else shades[((v - 0.06f) * (1f / 0.34f) * (RD_BANDS - 1)).toInt().coerceIn(0, RD_BANDS - 1)]
            }
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> =
        listOf("generations" to String.format(java.util.Locale.US, "%,d", generations))

    companion object {
        const val RD_BANDS = 28
        fun def(): SimDef = SimDef(
            id = "life/reaction-diffusion",
            title = "Reaction and diffusion",
            shelf = Shelf.LIFE,
            family = "Biology & ecology",
            blurb = "Two chemicals that grow coral, mazes and spots.",
            formats = listOf(TrapFormat.GROW, TrapFormat.REVEAL),
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
        ReactionDiffusionSim.def(),
        ColourWarSim.def(),
        RpsBattleSim.def(),
        SnowflakeSim.def()
    )
}
