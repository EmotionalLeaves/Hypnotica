package app.hypnotica.sims.physics

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Macro
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
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The Abelian sandpile: grains dropped one at a time on the centre of a grid.
 * A cell holding four topples, one grain to each neighbour, and those may
 * topple in turn — an avalanche. Nothing in the rule mentions a pattern, and
 * the pile grows into a four-fold fractal anyway.
 *
 * Every avalanche worth hearing is a note, pitched by its size: most are a
 * cell or two, now and then one runs right across the pile, and the spread of
 * sizes is the point — this is the textbook case of a system that organises
 * itself to the edge of collapse.
 */
class SandpileSim : Sim {

    private var n = 161
    private var cells = ByteArray(0)
    private var stack = IntArray(0)
    private var stackSize = 0
    private var carry = 0f
    private var grains = 0L
    private var topplings = 0L
    private var reach = 0
    private var biggest = 1
    private var payoffFrame = false
    private var grown = false
    private var grownAt = 0f
    private var elapsed = 0f

    override fun setup(env: SimEnv) {
        n = (env.params.i("size", 161) or 1).coerceIn(51, MAX_SIZE)
        cells = ByteArray(n * n)
        stack = IntArray(n * n * 2)
        stackSize = 0
        carry = 0f
        grains = 0L
        topplings = 0L
        reach = 0
        biggest = 1
        payoffFrame = false
        grown = false
        grownAt = 0f
        elapsed = 0f
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        if (grown && elapsed - grownAt > HOLD_SECONDS) return
        carry += abs(dt) * env.params.f("rate", 3000f)
        var drops = min(carry.toInt(), MAX_DROPS_PER_STEP)
        carry -= drops
        val centre = (n / 2) * n + n / 2
        var budget = TOPPLE_BUDGET
        // An avalanche left unfinished by the budget carries on next step
        // before anything new is dropped, so each one is heard whole.
        if (stackSize > 0) budget -= relax(env, budget)
        while (drops > 0 && budget > 0 && stackSize == 0) {
            drops--
            grains++
            cells[centre]++
            if (cells[centre] >= 4) {
                push(centre)
                budget -= relax(env, budget)
            }
        }

        // The payoff: the pattern has grown out to most of the grid.
        if (!grown && reach >= (n / 2) * PAYOFF_REACH) {
            grown = true
            grownAt = elapsed
            payoffFrame = true
            env.emit(EventKind.DONE, 0.9f, 0f, 1f)
        }
    }

    private fun push(i: Int) {
        if (stackSize < stack.size) stack[stackSize++] = i
    }

    /**
     * Topple until stable or out of budget; returns the topplings done. The
     * avalanche's size is counted across steps and sounded when it ends.
     */
    private var avalanche = 0
    private fun relax(env: SimEnv, budget: Int): Int {
        var done = 0
        val half = n / 2
        while (stackSize > 0 && done < budget) {
            val i = stack[--stackSize]
            if (cells[i] < 4) continue
            cells[i] = (cells[i] - 4).toByte()
            done++
            avalanche++
            val x = i % n
            val y = i / n
            val r = max(abs(x - half), abs(y - half)) + 1
            if (r > reach) reach = r
            // Grains that fall off the edge are gone.
            if (x > 0) topple(i - 1)
            if (x < n - 1) topple(i + 1)
            if (y > 0) topple(i - n)
            if (y < n - 1) topple(i + n)
            if (cells[i] >= 4) push(i)
        }
        topplings += done
        if (stackSize == 0 && avalanche > 0) {
            if (avalanche >= SOUNDED_AVALANCHE) {
                if (avalanche > biggest) biggest = avalanche
                val size = (ln(avalanche.toFloat()) / ln(max(biggest, 8).toFloat())).coerceIn(0f, 1f)
                env.emit(EventKind.HIT, size, 0f, (0.3f + 0.6f * size).coerceAtMost(0.9f))
            }
            avalanche = 0
        }
        return done
    }

    private fun topple(j: Int) {
        val v = cells[j] + 1
        cells[j] = v.toByte()
        if (v == 4) push(j)
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val half = n / 2
        // Only the part of the grid the pile has reached is walked.
        val r = min(half, reach + 1)
        val view = view(env)
        val cell = 2f * view / n
        val tones = tones(env.palette)
        if (n > RUN_LIMIT) {
            // A huge grid is a pixel or less a cell: soft points are the cheap
            // path and look the same at that size.
            g.reservePoints((2 * r + 1) * (2 * r + 1))
            for (y in half - r..half + r) {
                val row = y * n
                var first = half + r + 1; var last = half - r - 1
                for (x in half - r..half + r) if (cells[row + x].toInt() != 0) { first = x; break }
                for (x in half + r downTo half - r) if (cells[row + x].toInt() != 0) { last = x; break }
                for (x in first..last) g.point((x - half) * cell, (y - half) * cell, cell * 1.45f, tones[cells[row + x].toInt().coerceIn(0, 3)])
            }
            return
        }
        // Crisp square cells, one rect per run of equal counts along a row:
        // the fractal is the exact 0-3 counts, and soft round dots of four
        // near tones read as orange noise. A run is a fraction of a cell
        // taller and wider than its cells so neighbouring rows and runs
        // overlap instead of leaving an anti-aliased seam between them.
        val pad = cell * 0.06f
        for (y in half - r..half + r) {
            val row = y * n
            var first = half + r + 1; var last = half - r - 1
            for (x in half - r..half + r) if (cells[row + x].toInt() != 0) { first = x; break }
            for (x in half + r downTo half - r) if (cells[row + x].toInt() != 0) { last = x; break }
            if (first > last) continue
            val wy = (y - half) * cell
            var x = first
            while (x <= last) {
                val v = cells[row + x].toInt().coerceIn(0, 3)
                var e = x
                while (e < last && cells[row + e + 1].toInt().coerceIn(0, 3) == v) e++
                val x0 = (x - half - 0.5f) * cell
                val x1 = (e - half + 0.5f) * cell
                g.rect((x0 + x1) * 0.5f, wy, (x1 - x0) * 0.5f + pad, cell * 0.5f + pad, tones[v])
                x = e + 1
            }
        }
    }

    /**
     * Half the side of the grid on screen: the full width of a phone held
     * upright. Capped below the height on a wide screen, where 0.97 of the
     * height put the disc's top and bottom against the frame edges.
     */
    private fun view(env: SimEnv): Float = min(env.halfWidth * 0.97f, 0.88f)

    /**
     * Four clearly different tones for the counts 0..3, 0 a deep shade just
     * off the background — the nested triangles and diamonds then stand out
     * from one another. Picked
     * from four evenly spaced places on the palette and sorted, because which
     * end of a palette is light differs from palette to palette.
     */
    private var tonesFor: Palette? = null
    private var toneCache = IntArray(4)
    private fun tones(p: Palette): IntArray {
        if (tonesFor === p) return toneCache
        // A hue wheel comes back round to where it began: quarter it instead.
        val wraps = p.at(0f) == p.at(0.999f)
        val picks = if (wraps) intArrayOf(p.at(0.08f), p.at(0.33f), p.at(0.58f), p.at(0.83f))
        else intArrayOf(p.at(0f), p.at(0.34f), p.at(0.67f), p.at(0.999f))
        val sorted = picks.sortedBy { luma(it) }
        // Most settled sites hold 3 or 2, so 3 gets a rich mid tone as the
        // ground and 2 the brightest, which lights the nested triangles; with
        // 3 brightest the whole disc washed out to one colour.
        toneCache = intArrayOf(
            Palette.lerp(Palette.BG, sorted[0], 0.35f),
            sorted[2],
            Palette.brighten(sorted[3], 0.06f),
            sorted[1]
        )
        tonesFor = p
        return toneCache
    }

    private fun luma(c: Int): Float =
        0.299f * ((c ushr 16) and 0xFF) + 0.587f * ((c ushr 8) and 0xFF) + 0.114f * (c and 0xFF)

    override fun frameHalfWidth(env: SimEnv): Float = view(env) + 0.01f

    /**
     * The view closes in on the pile while it is small and pulls back as it
     * grows, so it always fills the width: early on it was a small disc in
     * an empty screen.
     */
    override fun focus(env: SimEnv): Float {
        val view = view(env)
        val cell = 2f * view / n
        // Zoomed so the pile's edge lands at 0.96 / 0.97 of [view]: on a phone
        // that is the old pile / 0.96, and on a wide screen it keeps the
        // margin above and below instead of zooming back out to the edges.
        val pile = (min(n / 2, reach + 1) + 1.5f) * cell
        return max(pile * min(env.halfWidth, 1f) * 0.97f / (0.96f * view), 1e-3f)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = grown && elapsed - grownAt > HOLD_SECONDS

    override fun counters(): List<Pair<String, String>> = listOf(
        "grains" to String.format(java.util.Locale.US, "%,d", grains),
        "topplings" to String.format(java.util.Locale.US, "%,d", topplings),
        "biggest avalanche" to String.format(java.util.Locale.US, "%,d", biggest)
    )

    companion object {
        /** Past this grid size cells are a pixel or less: drawn as points. */
        const val RUN_LIMIT = 401
        const val MAX_SIZE = 801
        /** Grown this far towards the edge, the pattern is complete enough. */
        const val PAYOFF_REACH = 0.82f
        const val HOLD_SECONDS = 4f
        const val MAX_DROPS_PER_STEP = 2000
        /** Topplings a step may spend; past it the avalanche runs on next step. */
        const val TOPPLE_BUDGET = 25_000
        /** Avalanches smaller than this are the pile's hiss, not a note. */
        const val SOUNDED_AVALANCHE = 24

        fun def(): SimDef = SimDef(
            id = "physics/sandpile",
            title = "Sandpile avalanches",
            shelf = Shelf.PHYSICS,
            family = "Avalanches & criticality",
            blurb = "Drop grains one at a time. Four make a cell spill. A fractal falls out.",
            formats = listOf(TrapFormat.CHAIN, TrapFormat.GROW, TrapFormat.FILL),
            heroLabel = "Start a new pile",
            // Ocean: four counts as four clear steps of one cool family. Ember's
            // ramp is all orange-red and the fractal blurred into one warm
            // disc; Aurora's four hues were legible but garish.
            defaultPalette = "Ocean",
            typicalSeconds = 20f,
            params = listOf(
                Param.int("size", "Pile size", 51, MAX_SIZE, 161, card = Card.DATA),
                Param.float("rate", "Grains a second", 50f, 50000f, 3000f, card = Card.MOTION, logScale = true)
            ),
            macros = listOf(
                Macro("pour", "Pour", Card.MOTION, mapOf("rate" to 200f..30000f), 0.5f, logs = setOf("rate"))
            ),
            create = { SandpileSim() }
        )
    }
}
