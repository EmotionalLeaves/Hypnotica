package app.hypnotica.sims.algorithms

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.Shelf
import app.hypnotica.core.sim.Sim
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.sim.SimEnv
import app.hypnotica.core.sim.TrapFormat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Blocks of every size dropped into a box, each landing exactly in its place,
 * until the last one closes the last gap and the box is solid.
 *
 * The box is cut into blocks first — split at random, again and again, along
 * whole rows and columns — so the pieces are known to fit before the first
 * one falls. They fall lowest first, so each lands on something already
 * there, and each one landing is a note set by its size: big blocks low,
 * slivers high.
 */
class PackSim : Sim {

    private class Block(
        val c: Int, val r: Int, val w: Int, val h: Int, val tint: Float
    ) {
        var y = 0f
        var vy = 0f
        var landed = false
        var squash = 0f
    }

    private var cols = 8
    private var rows = 14
    private var cell = 0.1f
    private var ox = 0f
    private var oy = 0f
    private var blocks = ArrayList<Block>()
    private var next = 0
    private var falling = ArrayList<Block>()
    private var since = 0f
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var shine = 0f

    override fun setup(env: SimEnv) {
        cols = env.params.i("cols", 8).coerceIn(3, MAX_COLS)
        val halfW = env.halfWidth
        // Taller than wide on any screen: a box to fill, not a tray.
        cell = min(2f * halfW * 0.86f / cols, 1.5f / (cols * 1.2f))
        rows = ((1.5f / cell).toInt()).coerceIn(3, 120)
        ox = -cols * cell * 0.5f
        oy = -0.92f
        blocks = ArrayList()
        val maxSide = env.params.i("chunk", 4).coerceIn(1, 12)
        split(env, 0, 0, cols, rows, maxSide)
        // Lowest first, so every block lands on what is already there; along
        // a row, left to right, the way a person would stack them.
        blocks.sortWith(compareBy<Block> { it.r }.thenBy { it.c })
        for (b in blocks) b.y = spawnY(b)
        next = 0
        falling = ArrayList()
        since = 0f
        elapsed = 0f
        doneAt = -1f
        payoffFrame = false
        shine = 0f
    }

    /** Cut the rectangle in two at random until every piece is small enough. */
    private fun split(env: SimEnv, c: Int, r: Int, w: Int, h: Int, maxSide: Int) {
        val canW = w > 1; val canH = h > 1
        val must = w > maxSide || h > maxSide
        // Even a small piece is sometimes cut, for slivers among the slabs.
        if (!must && (!(canW || canH) || env.rng.bool(0.55f))) {
            blocks.add(Block(c, r, w, h, env.rng.nextFloat()))
            return
        }
        val vertical = when {
            !canH -> true
            !canW -> false
            else -> env.rng.bool(w.toFloat() / (w + h))
        }
        if (vertical) {
            val k = env.rng.int(1, w)
            split(env, c, r, k, h, maxSide); split(env, c + k, r, w - k, h, maxSide)
        } else {
            val k = env.rng.int(1, h)
            split(env, c, r, w, k, maxSide); split(env, c, r + k, w, h - k, maxSide)
        }
    }

    private fun restY(b: Block) = oy + b.r * cell
    @Suppress("UNUSED_PARAMETER")
    private fun spawnY(b: Block) = 1.05f

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        shine = max(0f, shine - h * 0.8f)
        // Release on a rhythm that quickens as the box fills.
        if (next < blocks.size) {
            since += h
            val fill = next.toFloat() / blocks.size
            // The whole box in about the same time however many blocks it
            // takes, the gaps closing up as it fills: half again as long as
            // the average at the start, half as long at the end.
            val every = ((FILL_SECONDS / env.params.f("pace", 1f).coerceAtLeast(0.05f)) / blocks.size * (1.5f - fill)).coerceAtMost(MAX_GAP)
            while (since >= every && next < blocks.size) {
                since -= every
                blocks[next].vy = -RELEASE_SPEED
                falling.add(blocks[next])
                next++
            }
        }
        // Whatever the law says, blocks go down: the box is the floor.
        val g = (abs(env.laws.gravity) * GRAVITY_SCALE).coerceAtLeast(MIN_FALL)
        val it = falling.iterator()
        while (it.hasNext()) {
            val b = it.next()
            b.vy -= g * h
            b.y += b.vy * h
            val rest = restY(b)
            if (b.y <= rest) {
                b.y = rest
                b.landed = true
                b.squash = (abs(b.vy) * 0.08f).coerceAtMost(0.35f)
                // Bigger blocks land lower: a slab thuds, a sliver ticks.
                val area = (b.w * b.h).toFloat()
                val pitch = (1f - sqrt(area / BIG_AREA)).coerceIn(0f, 1f)
                env.emit(EventKind.HIT, 0.1f + 0.85f * pitch, ((b.c + b.w * 0.5f) / cols) * 2f - 1f, (0.3f + 0.05f * area).coerceAtMost(0.9f))
                it.remove()
            }
        }
        for (b in blocks) if (b.squash > 0f) b.squash = max(0f, b.squash - h * 3f)
        if (doneAt < 0f && next >= blocks.size && falling.isEmpty()) {
            doneAt = elapsed
            payoffFrame = true
            shine = 1f
            env.emit(EventKind.DONE, 1f, 0f, 1f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val bw = cols * cell; val bh = rows * cell
        // The box: three sides, open at the top.
        val wall = Palette.withAlpha(Palette.TEXT_SECONDARY, 0.7f)
        g.line(ox - 0.01f, oy - 0.01f, ox - 0.01f, oy + bh + 0.02f, 0.008f, wall)
        g.line(ox + bw + 0.01f, oy - 0.01f, ox + bw + 0.01f, oy + bh + 0.02f, 0.008f, wall)
        g.line(ox - 0.01f, oy - 0.01f, ox + bw + 0.01f, oy - 0.01f, 0.008f, wall)
        val gap = cell * 0.04f
        for (i in 0 until next) {
            val b = blocks[i]
            val w = b.w * cell; val hh = b.h * cell
            val sq = b.squash
            val cx = ox + b.c * cell + w * 0.5f
            val cy = b.y + hh * 0.5f * (1f - sq * 0.5f)
            val c = palette.at(b.tint)
            g.rect(cx, cy, w * 0.5f * (1f + sq * 0.15f) - gap, hh * 0.5f * (1f - sq * 0.5f) - gap, c)
            // A lighter top edge, so stacked blocks read as solid things.
            g.rect(cx, cy + hh * 0.5f * (1f - sq * 0.5f) - gap - cell * 0.06f, w * 0.5f - gap * 2f, cell * 0.05f, Palette.brighten(c, 0.35f))
        }
        if (shine > 0f) {
            // The sweep of light across the finished block.
            val sx = ox + bw * (1f - shine) * 1.4f - bw * 0.2f
            g.glow(sx, oy + bh * 0.5f, bh * 0.45f, Palette.TEXT_PRIMARY, 0.35f * shine)
            g.glow(0f, oy + bh * 0.5f, bw * 0.9f, Palette.SUCCESS, 0.25f * shine)
        }
    }

    override fun frameHalfWidth(env: SimEnv): Float = cols * cell * 0.5f + 0.03f

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && elapsed - doneAt > 3f

    override fun counters(): List<Pair<String, String>> = listOf(
        "blocks" to "$next / ${blocks.size}",
        "full" to "${filledCells() * 100 / max(1, cols * rows)}%"
    )

    private fun filledCells(): Int {
        var s = 0
        for (i in 0 until next) { val b = blocks[i]; if (b.landed) s += b.w * b.h }
        return s
    }

    companion object {
        const val MAX_COLS = 30
        const val FILL_SECONDS = 11f
        /** A box of a few big blocks still drops them at a steady clip. */
        const val MAX_GAP = 0.9f
        const val GRAVITY_SCALE = 0.45f
        const val BIG_AREA = 16f
        const val MIN_FALL = 1.5f
        const val RELEASE_SPEED = 0.6f

        fun def(): SimDef = SimDef(
            id = "algorithms/perfect-pack",
            title = "Perfect fit",
            shelf = Shelf.ALGORITHMS,
            family = "Packing",
            blurb = "Every block has exactly one place. The last one closes the box.",
            formats = listOf(TrapFormat.PERFECT_FIT, TrapFormat.FILL, TrapFormat.SNAP),
            heroLabel = "Pack another",
            defaultPalette = "Candy",
            typicalSeconds = 16f,
            params = listOf(
                Param.int("cols", "Box width", 3, MAX_COLS, 8, card = Card.DATA),
                Param.int("chunk", "Biggest block", 1, 12, 4, card = Card.DATA),
                Param.float("pace", "Pace", 0.3f, 5f, 1f, card = Card.MOTION)
            ),
            create = { PackSim() }
        )
    }
}
