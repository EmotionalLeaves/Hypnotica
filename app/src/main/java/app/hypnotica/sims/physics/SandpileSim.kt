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
        val palette = env.palette
        val half = n / 2
        // Only the part of the grid the pile has reached is walked.
        val r = min(half, reach + 1)
        val cell = 2f * VIEW / n
        g.reservePoints((2 * r + 1) * (2 * r + 1))
        val c1 = palette.at(0.25f)
        val c2 = palette.at(0.6f)
        val c3 = palette.at(0.95f)
        val c0 = Palette.withAlpha(palette.at(0.05f), 0.35f)
        for (y in half - r..half + r) {
            val wy = (y - half) * cell
            val row = y * n
            for (x in half - r..half + r) {
                val h = cells[row + x].toInt()
                val c = when (h) {
                    0 -> if (abs(x - half) < reach && abs(y - half) < reach) c0 else continue
                    1 -> c1
                    2 -> c2
                    else -> c3
                }
                g.point((x - half) * cell, wy, cell * 1.05f, c)
            }
        }
    }

    override fun frameHalfWidth(env: SimEnv): Float = VIEW + 0.03f

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = grown && elapsed - grownAt > HOLD_SECONDS

    override fun counters(): List<Pair<String, String>> = listOf(
        "grains" to String.format(java.util.Locale.US, "%,d", grains),
        "topplings" to String.format(java.util.Locale.US, "%,d", topplings),
        "biggest avalanche" to String.format(java.util.Locale.US, "%,d", biggest)
    )

    companion object {
        /** Half the side of the grid on screen, in world units. */
        const val VIEW = 0.9f
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
            defaultPalette = "Ember",
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
