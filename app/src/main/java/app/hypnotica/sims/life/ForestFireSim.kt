package app.hypnotica.sims.life

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

/**
 * The Drossel–Schwabl forest fire: trees sprout on empty ground, lightning
 * strikes at random, and fire spreads from a burning tree to its neighbours,
 * leaving ash. Nothing in the rules is about size, yet the forest organises
 * itself to the edge of disaster: while trees are sparse a strike burns a
 * handful, but growth pushes the forest past the density at which the trees
 * form one connected wood, and then a single strike can sweep the screen.
 * The burnt ground regrows, the fronts come round again, and the fire sizes
 * follow a power law — self-organised criticality.
 *
 * The payoff is the first great burn: one strike that takes a sixth of the
 * whole forest.
 */
class ForestFireSim : Sim {

    private var cols = 0
    private var rows = 0
    private var cell = 0.01f
    private var x0 = 0f
    private var y0 = 0f
    private var halfW = 0.46f

    /** 0 empty, 1 tree, 2 burning, then ASH_FIRST.. as the ash cools. */
    private var state = ByteArray(0)
    private var next = ByteArray(0)
    /** How long each tree has stood, in ticks, capped: young trees are drawn paler. */
    private var age = ByteArray(0)
    /** Which strike set each burning cell alight. */
    private var fireOf = IntArray(0)
    private var fireNext = IntArray(0)
    private var burntBy = IntArray(MAX_FIRES)   // cells burnt by each live fire
    private var fireCount = 0

    private var tickCarry = 0f
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var trees = 0
    private var burning = 0
    private var biggest = 0
    private var strikes = 0
    private var crackleCarry = 0f
    private var lastStrikeX = 0f
    private var lastStrikeY = 0f
    private var lastStrikeAt = -10f

    override fun setup(env: SimEnv) {
        halfW = env.halfWidth
        rows = env.params.i("rows", 210).coerceIn(60, 500)
        cell = (FIELD_TOP - FIELD_BOTTOM) / rows
        cols = ((2f * (halfW - MARGIN)) / cell).toInt().coerceAtLeast(20)
        x0 = -cols * cell * 0.5f
        y0 = FIELD_BOTTOM
        val n = cols * rows
        state = ByteArray(n); next = ByteArray(n); age = ByteArray(n)
        fireOf = IntArray(n); fireNext = IntArray(n)
        // A young, patchy forest to start, just under half the ground.
        trees = 0
        for (k in 0 until n) if (env.rng.nextFloat() < START_DENSITY) {
            state[k] = TREE; age[k] = env.rng.int(0, AGE_CAP).toByte(); trees++
        }
        burntBy.fill(0)
        fireCount = 0
        tickCarry = 0f
        elapsed = 0f
        doneAt = -1f
        payoffFrame = false
        burning = 0
        biggest = 0
        strikes = 0
        crackleCarry = 0f
        lastStrikeAt = -10f
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        tickCarry += h * TICKS_PER_SECOND
        var budget = 4
        while (tickCarry >= 1f && budget-- > 0) {
            tickCarry -= 1f
            tick(env)
        }
        if (tickCarry > 1f) tickCarry = 1f
        // The crackle of whatever is burning: one note now and then, louder
        // and higher the bigger the blaze, panned nowhere in particular
        // because a front can span the screen.
        crackleCarry = min(1.5f, crackleCarry + h * CRACKLES_PER_SECOND)
        if (burning > CRACKLE_FROM && crackleCarry >= 1f) {
            crackleCarry -= 1f
            val size = (burning / (cols * 0.6f)).coerceIn(0f, 1f)
            env.emit(EventKind.CROSS, 0.2f + 0.5f * size, 0f, 0.2f + 0.5f * size)
        }
    }

    private fun tick(env: SimEnv) {
        val growth = env.params.f("growth", 1f) * GROW_P
        val lightning = env.params.f("lightning", 1f) * STRIKE_P
        val w = cols; val hgt = rows
        var nowBurning = 0
        var nowTrees = 0
        for (y in 0 until hgt) {
            val r = y * w
            for (x in 0 until w) {
                val k = r + x
                val s = state[k].toInt()
                when {
                    s == TREE.toInt() -> {
                        // Caught from a burning neighbour: straight across
                        // always, corner to corner sometimes, so the fronts
                        // come out round rather than the pure four-neighbour
                        // rule's diamonds.
                        var from = -1
                        if (x > 0 && state[k - 1].toInt() == BURNING.toInt()) from = k - 1
                        else if (x < w - 1 && state[k + 1].toInt() == BURNING.toInt()) from = k + 1
                        else if (y > 0 && state[k - w].toInt() == BURNING.toInt()) from = k - w
                        else if (y < hgt - 1 && state[k + w].toInt() == BURNING.toInt()) from = k + w
                        else if (diagonalBurning(x, y, k) && env.rng.nextFloat() < DIAGONAL) from = diagFrom
                        if (from >= 0) {
                            next[k] = BURNING
                            val id = fireOf[from]
                            fireNext[k] = id
                            burntBy[id]++
                            nowBurning++
                        } else if (env.rng.nextFloat() < lightning) {
                            // Lightning.
                            val id = newFire()
                            next[k] = BURNING
                            fireNext[k] = id
                            burntBy[id] = 1
                            nowBurning++
                            strikes++
                            lastStrikeX = x0 + (x + 0.5f) * cell
                            lastStrikeY = y0 + (y + 0.5f) * cell
                            lastStrikeAt = elapsed
                            env.emit(EventKind.HIT, 0.85f, (lastStrikeX / halfW).coerceIn(-1f, 1f), 0.7f)
                        } else {
                            next[k] = TREE
                            if (age[k] < AGE_CAP) age[k]++
                            nowTrees++
                        }
                    }
                    s == BURNING.toInt() -> next[k] = ASH_FIRST
                    s >= ASH_FIRST.toInt() -> next[k] = if (s >= ASH_LAST) EMPTY else (s + 1).toByte()
                    else -> {
                        if (env.rng.nextFloat() < growth) {
                            next[k] = TREE; age[k] = 0; nowTrees++
                        } else next[k] = EMPTY
                    }
                }
            }
        }
        val ts = state; state = next; next = ts
        val tf = fireOf; fireOf = fireNext; fireNext = tf
        burning = nowBurning
        trees = nowTrees
        // The great burn: the first single fire to take a sixth of the forest.
        // Measured against the whole ground, not the trees standing, so it
        // means the same on every screen.
        for (id in 0 until fireCount) if (burntBy[id] > biggest) biggest = burntBy[id]
        if (doneAt < 0f && biggest >= BIG_SHARE * cols * rows) {
            doneAt = elapsed
            payoffFrame = true
        }
        if (burning == 0) fireCount = 0
    }

    private var diagFrom = -1
    private var colSum = IntArray(0)

    private fun diagonalBurning(x: Int, y: Int, k: Int): Boolean {
        val w = cols
        val b = BURNING.toInt()
        if (y > 0) {
            if (x > 0 && state[k - w - 1].toInt() == b) { diagFrom = k - w - 1; return true }
            if (x < w - 1 && state[k - w + 1].toInt() == b) { diagFrom = k - w + 1; return true }
        }
        if (y < rows - 1) {
            if (x > 0 && state[k + w - 1].toInt() == b) { diagFrom = k + w - 1; return true }
            if (x < w - 1 && state[k + w + 1].toInt() == b) { diagFrom = k + w + 1; return true }
        }
        return false
    }

    /** A slot for a new fire's tally; slots are reused once nothing is burning. */
    private fun newFire(): Int {
        if (fireCount < MAX_FIRES) return fireCount++
        // Out of slots mid-blaze: share the last one. Only the tally of a
        // small late fire can be off, never the big burn's.
        return MAX_FIRES - 1
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val wheel = palette.name == Palette.SPECTRUM.name
        // The fire at the palette's brighter end, the wood well away from it
        // and darkened, so flame always reads against forest whatever the
        // palette; on the colour wheel, a green wood and an orange fire.
        val fireT: Float; val woodT: Float
        if (wheel) { fireT = 0.08f; woodT = 0.33f } else {
            fireT = if (luma(palette.at(0f)) >= luma(palette.at(1f))) 0f else 1f
            woodT = if (fireT == 0f) 0.62f else 0.38f
        }
        val wood = palette.at(woodT)
        // The canopy, shaded by how thick the wood is round each cell (its
        // 3×3 neighbourhood) rather than tree by tree: drawn per tree, a
        // random forest is salt-and-pepper noise, and the patches of old and
        // young growth that make the fire's history readable were lost in it.
        val canopy = IntArray(CANOPY + 1) { i -> if (i == 0) 0 else Palette.lerp(Palette.BG, wood, 0.08f + 0.62f * (i / CANOPY.toFloat()).let { it * it }) }
        val flame = Palette.brighten(palette.at(fireT), 0.25f)
        val ashBase = Palette.lerp(Palette.BG, palette.at(fireT), 0.55f)
        val ash = IntArray(ASH_LAST - ASH_FIRST + 1) { i ->
            val u = 1f - i / (ASH_LAST - ASH_FIRST + 1f)
            // Embers cooling: the fire's colour dimming to a faint grey-brown.
            Palette.lerp(Palette.lerp(Palette.BG, 0xFF3A3632.toInt(), 0.7f), ashBase, u * u)
        }
        if (colSum.size != cols) colSum = IntArray(cols)
        for (y in 0 until rows) {
            val r = y * cols
            val yc = y0 + (y + 0.5f) * cell
            // Trees in each column of the three rows round this one.
            for (x in 0 until cols) {
                var c = 0
                if (y > 0 && state[r - cols + x] == TREE) c++
                if (state[r + x] == TREE) c++
                if (y < rows - 1 && state[r + cols + x] == TREE) c++
                colSum[x] = c
            }
            g.cellRow(x0, yc, cell, cols) { x ->
                val s = state[r + x].toInt()
                when {
                    s == TREE.toInt() || s == EMPTY.toInt() -> {
                        val n9 = colSum[x] + (if (x > 0) colSum[x - 1] else colSum[x]) + (if (x < cols - 1) colSum[x + 1] else colSum[x])
                        canopy[(n9 * CANOPY + 4) / 9]
                    }
                    s == BURNING.toInt() -> flame
                    else -> ash[s - ASH_FIRST]
                }
            }
        }
        // A soft light over the fronts: one glow for every few burning cells
        // along a coarse lattice, so a big front is lit as a band rather than
        // a thousand blobs.
        if (burning > 0) {
            val step = GLOW_LATTICE
            var count = 0
            var y = step / 2
            while (y < rows && count < MAX_GLOWS) {
                var x = step / 2
                while (x < cols && count < MAX_GLOWS) {
                    var hot = 0
                    for (dy in -step / 2 until step / 2) for (dx in -step / 2 until step / 2) {
                        val yy = y + dy; val xx = x + dx
                        if (yy in 0 until rows && xx in 0 until cols && state[yy * cols + xx] == BURNING) hot++
                    }
                    if (hot > 0) {
                        g.glow(x0 + (x + 0.5f) * cell, y0 + (y + 0.5f) * cell, cell * step * 1.3f, flame, min(0.45f, 0.08f + hot * 0.04f))
                        count++
                    }
                    x += step
                }
                y += step
            }
        }
        // The strike itself: a bright point that fades over a quarter second.
        val since = elapsed - lastStrikeAt
        if (since in 0f..STRIKE_SHOW) {
            val k = 1f - since / STRIKE_SHOW
            g.disc(lastStrikeX, lastStrikeY, cell * 1.6f, Palette.TEXT_PRIMARY, k)
        }
    }

    private fun luma(c: Int): Float =
        0.3f * ((c ushr 16) and 0xFF) + 0.59f * ((c ushr 8) and 0xFF) + 0.11f * (c and 0xFF)

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> {
        val total = max(1, cols * rows).toFloat()
        return listOf(
            "forest" to "${(trees / total * 100f).toInt()}%",
            "biggest fire" to "${(biggest / total * 100f).toInt()}%",
            "strikes" to strikes.toString()
        )
    }

    companion object {
        const val EMPTY: Byte = 0
        const val TREE: Byte = 1
        const val BURNING: Byte = 2
        const val ASH_FIRST: Byte = 3
        const val ASH_LAST = 12
        const val AGE_CAP = 90
        const val CANOPY = 6
        const val MAX_FIRES = 4096
        const val FIELD_TOP = 0.86f
        const val FIELD_BOTTOM = -0.96f
        const val MARGIN = 0.0f
        const val TICKS_PER_SECOND = 20f
        const val START_DENSITY = 0.48f
        /** Per empty cell per tick. */
        const val GROW_P = 0.0016f
        /** Per tree per tick: about one strike a second on a phone-sized wood. */
        const val STRIKE_P = 0.000004f
        const val DIAGONAL = 0.35f
        const val BIG_SHARE = 0.16f
        const val CRACKLES_PER_SECOND = 5f
        const val CRACKLE_FROM = 12
        const val GLOW_LATTICE = 8
        const val MAX_GLOWS = 220
        const val STRIKE_SHOW = 0.25f

        fun def(): SimDef = SimDef(
            id = "life/forest-fire",
            title = "Forest fire",
            shelf = Shelf.LIFE,
            family = "Avalanches & criticality",
            blurb = "Trees grow, lightning strikes, fire spreads. The forest tunes itself to the edge of disaster.",
            formats = listOf(TrapFormat.SNAP, TrapFormat.CHAIN),
            heroLabel = "New forest",
            defaultPalette = "Spectrum",
            typicalSeconds = 12f,
            params = listOf(
                Param.int("rows", "Size", 80, 400, 210, card = Card.DATA),
                Param.float("growth", "Growth", 0.3f, 3f, 1f, card = Card.MOTION),
                Param.float("lightning", "Lightning", 0.2f, 5f, 1f, card = Card.MOTION, logScale = true)
            ),
            create = { ForestFireSim() }
        )
    }
}
