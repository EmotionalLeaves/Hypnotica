package app.hypnotica.sims.life

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.recipe.ParamInput
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.Shelf
import app.hypnotica.core.sim.Sim
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.sim.SimEnv
import app.hypnotica.core.sim.TrapFormat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A crystal grown one particle at a time. Each wanders in at random from far
 * off and freezes the moment it touches the crystal — so the tips, which
 * stick out furthest, catch the most and grow fastest, and the crystal
 * branches into fronds. With symmetry on, every particle that sticks is
 * copied round the centre: a snowflake.
 */
class SnowflakeSim : Sim {

    private var size = 241
    private var grid = ByteArray(0)
    private var order = IntArray(0)   // when each cell froze, for its colour
    private var frozen = 0
    private var radius = 1f
    private var maxRadius = 100f
    private var folds = 6
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var wx = 0
    private var wy = 0
    private var walking = false
    private var lastMove = 0
    private var cellsList = IntArray(0)
    private var listCount = 0
    private var lastRing = 0

    override fun setup(env: SimEnv) {
        size = (env.params.i("size", 241) / 2 * 2 + 1).coerceIn(61, MAX_SIZE)
        grid = ByteArray(size * size)
        order = IntArray(size * size)
        cellsList = IntArray(size * size)
        listCount = 0
        folds = FOLDS[env.params.choiceIndex("shape").coerceIn(0, FOLDS.size - 1)]
        val c = size / 2
        frozen = 1
        radius = 1f
        freeze(c, c)
        // A nucleus with one short spoke per fold, along the axes. Grown from
        // a single square, the first particle to stick set the arms off at
        // whatever angle it came in at, and its mirror image grew beside it:
        // every arm came in a pair, twelve for a six-fold flake. A real
        // flake's arms grow from the corners of its hexagonal seed, along
        // the mirror lines, and seeded that way the tips that catch the most
        // are the six (four, eight) the shape is named for.
        if (folds > 1) {
            val spoke = max(3f, size * 0.035f)
            for (m in 0 until folds) {
                val a = 2f * PI.toFloat() * m / folds
                joinFreeze(c, c, (c + cos(a) * spoke).roundToInt(), (c + sin(a) * spoke).roundToInt())
            }
        }
        maxRadius = size * 0.47f
        elapsed = 0f
        doneAt = -1f
        payoffFrame = false
        walking = false
        lastRing = 0
    }

    private fun freeze(x: Int, y: Int) {
        val k = y * size + x
        if (grid[k].toInt() != 0) return
        grid[k] = 1
        order[k] = frozen
        cellsList[listCount++] = k
        val c = size / 2
        val r = sqrt(((x - c) * (x - c) + (y - c) * (y - c)).toFloat())
        if (r > radius) radius = r
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        if (doneAt >= 0f) return
        val c = size / 2
        // Steps of wandering this frame, more as the crystal grows: the walk
        // in from the launch circle gets longer.
        var budget = (h * STEPS_PER_SECOND * (1f + radius / 30f)).toInt().coerceAtMost(MAX_STEPS)
        // Grown to a schedule, not as fast as it can: with symmetry every
        // particle is twelve and a big crystal was done in a blink. Its reach
        // eases out to the edge over GROW_SECONDS.
        val sched = (elapsed * env.params.f("pace", 1f) / GROW_SECONDS).coerceIn(0f, 1f)
        val allowed = 2f + (maxRadius - 2f) * (1f - (1f - sched) * (1f - sched))
        while (budget-- > 0 && radius < allowed) {
            if (!walking) {
                // Launch from a circle just outside the crystal.
                val a = env.rng.range(0f, 2f * PI.toFloat())
                val lr = radius + 6f
                wx = (c + cos(a) * lr).roundToInt().coerceIn(1, size - 2)
                wy = (c + sin(a) * lr).roundToInt().coerceIn(1, size - 2)
                walking = true
            }
            lastMove = env.rng.int(0, 4)
            when (lastMove) {
                0 -> wx++
                1 -> wx--
                2 -> wy++
                else -> wy--
            }
            var dx = wx - c; var dy = wy - c
            var d2 = dx * dx + dy * dy
            // Far from the crystal, take one long stride rather than many
            // short ones: nothing can be touched on the way, and the walk is
            // the same in the end. Without it the wild shape at the largest
            // size fell twice behind its schedule.
            val d = sqrt(d2.toFloat())
            if (d > radius + 4f) {
                val jump = d - radius - 2f
                val a = env.rng.range(0f, 2f * PI.toFloat())
                wx = (wx + cos(a) * jump).roundToInt()
                wy = (wy + sin(a) * jump).roundToInt()
                dx = wx - c; dy = wy - c
                d2 = dx * dx + dy * dy
            }
            // Wandered too far: start another.
            if (d2 > (radius * 2.5f + 12f).let { it * it } || wx < 1 || wy < 1 || wx > size - 2 || wy > size - 2) {
                walking = false
                continue
            }
            val k = wy * size + wx
            // Never onto the crystal itself: a walker that stepped onto a
            // frozen square stuck there as if it had touched it, and with
            // sticking no longer certain (below) it could wander inside.
            if (grid[k].toInt() != 0) {
                when (lastMove) { 0 -> wx--; 1 -> wx++; 2 -> wy--; else -> wy++ }
                continue
            }
            val touched = when {
                grid[k - 1].toInt() != 0 -> k - 1
                grid[k + 1].toInt() != 0 -> k + 1
                grid[k - size].toInt() != 0 -> k - size
                grid[k + size].toInt() != 0 -> k + size
                else -> -1
            }
            if (touched >= 0 && !sticks(env, wx - c, wy - c)) continue
            if (touched >= 0) {
                // Touched: frozen here, and copied round the centre.
                stick(env, wx, wy, touched % size, touched / size)
                walking = false
                if (radius >= maxRadius) break
            }
        }
        // A note each time the crystal reaches a new ring outward.
        val ring = (radius / maxRadius * 16f).toInt()
        if (ring > lastRing) {
            lastRing = ring
            env.emit(EventKind.STEP, (ring / 16f).coerceIn(0f, 1f), 0f, 0.4f)
        }
        if (radius >= maxRadius && doneAt < 0f) {
            doneAt = elapsed
            payoffFrame = true
            env.emit(EventKind.DONE, 0.9f, 0f, 1f)
        }
    }

    /**
     * Whether a walker touching the crystal here freezes. On the arms' own
     * axes always; off them, only some of the time — the stand-in for the
     * ice lattice, whose six directions are where a real flake grows
     * fastest. Without it every tip split in two: a particle landing just
     * off an axis was mirrored onto the other side of it, the pair grew
     * apart, and a six-fold flake had twelve arms in six pairs. Side
     * branches still grow, slower, so the arms are feathered, not bare.
     */
    private fun sticks(env: SimEnv, dx: Int, dy: Int): Boolean {
        if (folds <= 1) return true
        val r = sqrt((dx * dx + dy * dy).toFloat())
        val wedge = 2f * PI.toFloat() / folds
        val a = atan2(dy.toFloat(), dx.toFloat())
        val off = a - wedge * kotlin.math.round(a / wedge)
        val fromAxis = r * abs(sin(off))
        return fromAxis <= AXIS_WIDTH || env.rng.nextFloat() < SIDE_STICK
    }

    private fun stick(env: SimEnv, x: Int, y: Int, tx: Int, ty: Int) {
        val c = size / 2
        frozen++
        val dx = (x - c).toFloat(); val dy = (y - c).toFloat()
        if (folds <= 1) {
            freeze(x, y)
        } else {
            val r = sqrt(dx * dx + dy * dy)
            val a = atan2(dy, dx)
            val tdx = (tx - c).toFloat(); val tdy = (ty - c).toFloat()
            val tr = sqrt(tdx * tdx + tdy * tdy)
            val ta = atan2(tdy, tdx)
            for (m in 0 until folds) {
                // Each turn, and its mirror image: twelve copies for six folds.
                for (mirror in 0..1) {
                    val turn = 2f * PI.toFloat() * m / folds
                    val aa = (if (mirror == 0) a else -a) + turn
                    val nx = (c + cos(aa) * r).roundToInt()
                    val ny = (c + sin(aa) * r).roundToInt()
                    // And joined to the copy of the cell it stuck to: rounded
                    // to the square grid, a turned copy could land a square
                    // off, and the crystal's arms came apart into specks
                    // that later walkers stuck to in mid-air.
                    val ta2 = (if (mirror == 0) ta else -ta) + turn
                    val ox = (c + cos(ta2) * tr).roundToInt()
                    val oy = (c + sin(ta2) * tr).roundToInt()
                    joinFreeze(ox, oy, nx, ny)
                }
            }
        }
        if (frozen % 40 == 0) env.emit(EventKind.HIT, (radius / maxRadius).coerceIn(0f, 1f), (dx / max(1f, maxRadius)).coerceIn(-1f, 1f), 0.25f)
    }

    /** Every square on the way from one to the other, side to side, so they touch. */
    private fun joinFreeze(x0: Int, y0: Int, x1: Int, y1: Int) {
        var x = x0; var y = y0
        val sx = if (x1 > x0) 1 else -1; val sy = if (y1 > y0) 1 else -1
        val ddx = abs(x1 - x0); val ddy = abs(y1 - y0)
        var err = ddx - ddy
        var guard = ddx + ddy + 2
        while (guard-- > 0) {
            if (x in 1 until size - 1 && y in 1 until size - 1) freeze(x, y)
            if (x == x1 && y == y1) break
            // One axis at a time: a diagonal step would only meet at a corner.
            if (err > 0 || (err == 0 && ddx >= ddy)) { x += sx; err -= ddy } else { y += sy; err += ddx }
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val scale = min(env.halfWidth, 1f) * 0.95f / (size * 0.5f)
        val c = size / 2
        g.reservePoints(listCount)
        for (i in 0 until listCount) {
            val k = cellsList[i]
            val x = (k % size - c) * scale
            val y = (k / size - c) * scale
            // Coloured by when it froze: the heart one colour, the newest tips another.
            val t = order[k].toFloat() / max(1, frozen)
            // Short of the far end of the ramp: on Ocean that end is a navy that
            // all but vanished against the background, and it is where the
            // newest tips are.
            g.point(x, y, scale * 1.6f, palette.at(0.05f + 0.7f * t))
        }
        if (doneAt >= 0f) {
            val k = (1f - (elapsed - doneAt)).coerceIn(0f, 1f)
            if (k > 0f) g.glow(0f, 0f, radius * scale * 1.2f, Palette.TEXT_PRIMARY, 0.3f * k)
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && elapsed - doneAt > 3f

    override fun counters(): List<Pair<String, String>> = listOf(
        "frozen" to listCount.toString(),
        "grown" to "${(radius / maxRadius * 100f).toInt().coerceAtMost(100)}%"
    )

    companion object {
        const val MAX_SIZE = 501
        /** Squares either side of an arm's axis that always stick. */
        const val AXIS_WIDTH = 1.2f
        /** How often a touch off the axes sticks. */
        const val SIDE_STICK = 0.3f
        const val STEPS_PER_SECOND = 2_400_000f
        const val MAX_STEPS = 60_000
        const val GROW_SECONDS = 13f
        val FOLDS = intArrayOf(6, 4, 8, 1)

        fun def(): SimDef = SimDef(
            id = "life/snowflake",
            title = "Crystal growth",
            shelf = Shelf.LIFE,
            family = "Growth",
            blurb = "Particles wander in and freeze where they touch. The tips grow fastest.",
            formats = listOf(TrapFormat.GROW, TrapFormat.FILL),
            heroLabel = "Grow another",
            defaultPalette = "Ocean",
            typicalSeconds = 16f,
            params = listOf(
                Param.choice("shape", "Symmetry", listOf("Snowflake", "Four-fold", "Eight-fold", "Wild"), 0, card = Card.DATA, input = ParamInput.TILES),
                Param.int("size", "Size", 61, MAX_SIZE, 241, card = Card.DATA),
                Param.float("pace", "Pace", 0.2f, 4f, 1f, card = Card.MOTION)
            ),
            create = { SnowflakeSim() }
        )
    }
}
