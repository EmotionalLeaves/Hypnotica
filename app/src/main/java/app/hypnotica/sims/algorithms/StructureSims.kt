package app.hypnotica.sims.algorithms

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
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tower of Hanoi, solved the way the proof does it: move everything above, move
 * the big one, move everything back. The move count is always 2^n - 1.
 */
class HanoiSim : Sim {

    private var discs = 7
    private val pegs = Array(3) { ArrayList<Int>() }
    private val moves = ArrayList<IntArray>()
    private var played = 0
    private var carry = 0f
    private var payoffFrame = false
    private var restSeconds = -1f
    private var lifting = -1
    private var liftProgress = 0f
    private var fromPeg = 0
    private var toPeg = 0

    override fun setup(env: SimEnv) {
        discs = env.params.i("discs", 7).coerceIn(1, 18)
        for (p in pegs) p.clear()
        for (d in discs downTo 1) pegs[0].add(d)
        moves.clear()
        plan(discs, 0, 2, 1)
        played = 0
        carry = 0f
        payoffFrame = false
        restSeconds = -1f
        lifting = -1
    }

    private fun plan(n: Int, from: Int, to: Int, via: Int) {
        if (n == 0) return
        if (moves.size > 300_000) return
        plan(n - 1, from, via, to)
        moves.add(intArrayOf(from, to))
        plan(n - 1, via, to, from)
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        if (restSeconds >= 0f) {
            restSeconds -= abs(dt)
            return
        }
        carry += abs(dt) * env.params.f("rate", 6.5f)
        while (carry >= 1f && played < moves.size) {
            carry -= 1f
            val m = moves[played]
            fromPeg = m[0]
            toPeg = m[1]
            val disc = pegs[fromPeg].removeAt(pegs[fromPeg].size - 1)
            pegs[toPeg].add(disc)
            lifting = disc
            liftProgress = 0f
            played++
            env.emit(
                EventKind.SWAP,
                1f - (disc.toFloat() / discs),
                (toPeg - 1).toFloat() * 0.7f,
                0.6f
            )
        }
        liftProgress = min(1f, liftProgress + abs(dt) * 6f)
        if (played >= moves.size && restSeconds < 0f) {
            payoffFrame = true
            restSeconds = 1.8f
            env.emit(EventKind.DONE, 1f, 0f, 1f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val halfW = env.halfWidth * 0.92f
        val pegGap = halfW
        val baseY = -0.75f
        val discH = min(0.085f, 1.3f / discs)
        for (i in 0 until 3) {
            val x = (i - 1) * pegGap
            g.rect(x, baseY + 0.55f, 0.008f, 0.55f, Palette.withAlpha(Palette.TEXT_SECONDARY, 0.45f))
        }
        g.rect(0f, baseY - 0.02f, halfW * 1.5f, 0.018f, Palette.withAlpha(Palette.TEXT_SECONDARY, 0.5f))
        for (i in 0 until 3) {
            val x = (i - 1) * pegGap
            for ((level, disc) in pegs[i].withIndex()) {
                val w = (0.1f + 0.82f * disc / discs) * pegGap * 0.5f
                val y = baseY + level * discH + discH * 0.5f
                val c = palette.at(disc.toFloat() / discs)
                val justMoved = disc == lifting && level == pegs[i].size - 1
                g.rect(x, y, w, discH * 0.42f, if (justMoved) Palette.brighten(c, 0.3f) else c)
                if (justMoved) g.glow(x, y, w * 1.4f, c, 0.5f * (1f - liftProgress))
            }
        }
        if (payoffFrame) g.flash(0.4f)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = played >= moves.size && restSeconds in -1000f..0f

    override fun counters(): List<Pair<String, String>> = listOf(
        "moves" to String.format("%,d / %,d", played, moves.size),
        "discs" to discs.toString()
    )

    companion object {
        fun def(): SimDef = SimDef(
            id = "algorithms/hanoi",
            title = "Tower of Hanoi",
            shelf = Shelf.ALGORITHMS,
            family = "Puzzles & backtracking",
            blurb = "Always two to the power of n, minus one.",
            formats = listOf(TrapFormat.PERFECT_FIT, TrapFormat.SONG, TrapFormat.NUMBER_UP),
            heroLabel = "Solve again",
            defaultPalette = "Candy",
            typicalSeconds = 26f,
            params = listOf(
                Param.int("discs", "Discs", 1, 18, 7, card = Card.DATA),
                Param.float("rate", "Moves", 0.5f, 400f, 6.5f, card = Card.MOTION, unit = "/s", logScale = true)
            ),
            create = { HanoiSim() }
        )
    }
}

/**
 * N-queens by backtracking: place, test, and take back the last move whenever
 * the board says no. The retreat is as much of the story as the advance.
 */
class NQueensSim : Sim {

    private var n = 10
    private var cols = IntArray(0)
    private var row = 0
    private var tries = 0L
    private var backtracks = 0L
    private var solved = false
    private var payoffFrame = false
    private var carry = 0f
    private var restSeconds = -1f
    private var lastRejected = -1

    override fun setup(env: SimEnv) {
        n = env.params.i("size", 13).coerceIn(4, 24)
        cols = IntArray(n) { -1 }
        row = 0
        tries = 0
        backtracks = 0
        solved = false
        payoffFrame = false
        carry = 0f
        restSeconds = -1f
        lastRejected = -1
    }

    private fun safe(r: Int, c: Int): Boolean {
        for (i in 0 until r) {
            val ci = cols[i]
            if (ci == c || abs(ci - c) == r - i) return false
        }
        return true
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        if (restSeconds >= 0f) {
            restSeconds -= abs(dt)
            return
        }
        if (solved) return
        carry += abs(dt) * env.params.f("rate", 34f)
        var budget = min(carry.toInt(), 20000)
        carry -= budget
        while (budget-- > 0 && !solved) {
            if (row >= n) {
                solved = true
                payoffFrame = true
                restSeconds = 2f
                env.emit(EventKind.DONE, 1f, 0f, 1f)
                break
            }
            var c = cols[row] + 1
            var placed = false
            while (c < n) {
                tries++
                if (safe(row, c)) {
                    cols[row] = c
                    row++
                    placed = true
                    env.emit(EventKind.WRITE, c.toFloat() / n, (c.toFloat() / n) * 2f - 1f, 0.5f)
                    break
                }
                lastRejected = c
                c++
            }
            if (!placed) {
                cols[row] = -1
                row--
                backtracks++
                if (row < 0) {
                    // No solution for this size; start again rather than stall.
                    row = 0
                    cols = IntArray(n) { -1 }
                }
                env.emit(EventKind.DEATH, 0.3f, 0f, 0.5f)
            }
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val size = min(2f * env.halfWidth * 0.92f / n, 1.84f / n)
        val originX = -size * n * 0.5f
        val originY = -size * n * 0.5f
        for (r in 0 until n) {
            for (c in 0 until n) {
                val dark = (r + c) % 2 == 0
                g.rect(
                    originX + (c + 0.5f) * size, originY + (r + 0.5f) * size,
                    size * 0.5f, size * 0.5f,
                    Palette.withAlpha(Palette.TEXT_SECONDARY, if (dark) 0.1f else 0.04f)
                )
            }
        }
        for (r in 0 until n) {
            val c = cols[r]
            if (c < 0) continue
            val x = originX + (c + 0.5f) * size
            val y = originY + (r + 0.5f) * size
            val col = palette.at(r.toFloat() / n)
            g.glow(x, y, size * 1.1f, col, 0.5f)
            g.disc(x, y, size * 0.33f, col)
        }
        if (row in 0 until n) {
            g.rect(
                0f, originY + (row + 0.5f) * size,
                size * n * 0.5f, size * 0.5f,
                Palette.withAlpha(Palette.ACCENT, 0.12f)
            )
        }
        if (payoffFrame) g.flash(0.4f)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = solved && restSeconds in -1000f..0f

    override fun counters(): List<Pair<String, String>> = listOf(
        "placements tried" to String.format("%,d", tries),
        "backtracks" to String.format("%,d", backtracks)
    )

    companion object {
        fun def(): SimDef = SimDef(
            id = "algorithms/n-queens",
            title = "N-queens",
            shelf = Shelf.ALGORITHMS,
            family = "Puzzles & backtracking",
            blurb = "Place, test, take it back. The retreat is half the algorithm.",
            formats = listOf(TrapFormat.PERFECT_FIT, TrapFormat.GUESS),
            heroLabel = "Solve again",
            defaultPalette = "Ember",
            typicalSeconds = 20f,
            params = listOf(
                Param.int("size", "Board size", 4, 24, 13, card = Card.DATA),
                Param.float("rate", "Speed", 2f, 20000f, 34f, card = Card.MOTION, unit = "/s", logScale = true)
            ),
            create = { NQueensSim() }
        )
    }
}

/**
 * A binary search tree filling up, and the shape it ends with.
 *
 * Random keys give a tree about twice as deep as the ideal; sorted keys give a
 * straight line, which is the whole argument for balancing.
 */
class BinaryTreeSim : Sim {

    private var keys = FloatArray(0)
    private var left = IntArray(0)
    private var right = IntArray(0)
    private var depth = IntArray(0)
    private var xs = FloatArray(0)
    private var size = 0
    private var target = 220
    private var carry = 0f
    private var maxDepth = 0
    private var comparisons = 0L
    private var payoffFrame = false
    private var sorted = false
    private var restSeconds = -1f

    override fun setup(env: SimEnv) {
        val p = env.params
        target = p.i("nodes", 220).coerceIn(1, 4000)
        keys = FloatArray(target)
        left = IntArray(target) { -1 }
        right = IntArray(target) { -1 }
        depth = IntArray(target)
        xs = FloatArray(target)
        size = 0
        carry = 0f
        maxDepth = 0
        comparisons = 0
        payoffFrame = false
        restSeconds = -1f
        sorted = p.choiceIndex("order") == 1
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        if (restSeconds >= 0f) {
            restSeconds -= abs(dt)
            return
        }
        carry += abs(dt) * env.params.f("rate", 26f)
        while (carry >= 1f && size < target) {
            carry -= 1f
            val key = if (sorted) size.toFloat() / target else env.rng.nextFloat()
            insert(key, env)
        }
        if (size >= target && restSeconds < 0f) {
            payoffFrame = true
            restSeconds = 1.8f
            env.emit(EventKind.DONE, 1f, 0f, 1f)
        }
    }

    private fun insert(key: Float, env: SimEnv) {
        val index = size
        keys[index] = key
        if (index == 0) {
            depth[0] = 0
            size = 1
            maxDepth = 0
            return
        }
        var current = 0
        var d = 0
        while (true) {
            comparisons++
            d++
            if (key < keys[current]) {
                if (left[current] < 0) { left[current] = index; break }
                current = left[current]
            } else {
                if (right[current] < 0) { right[current] = index; break }
                current = right[current]
            }
            if (d > target) break
        }
        depth[index] = d
        if (d > maxDepth) maxDepth = d
        size++
        env.emit(EventKind.WRITE, key, key * 2f - 1f, 0.45f)
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        if (size == 0) return
        val halfW = env.halfWidth * 0.95f
        val levels = max(1, maxDepth)
        val rowH = 1.75f / (levels + 1)
        // Place by key so the in-order layout is the sorted order.
        for (i in 0 until size) xs[i] = -halfW + keys[i] * 2f * halfW
        for (i in 0 until size) {
            val x = xs[i]
            val y = 0.88f - depth[i] * rowH
            val l = left[i]
            val r = right[i]
            val c = palette.at(depth[i].toFloat() / levels)
            if (l in 0 until size) g.line(x, y, xs[l], 0.88f - depth[l] * rowH, 0.0022f, c, 0.6f)
            if (r in 0 until size) g.line(x, y, xs[r], 0.88f - depth[r] * rowH, 0.0022f, c, 0.6f)
        }
        val dotSize = max(0.004f, min(0.016f, 2.4f / size))
        for (i in 0 until size) {
            g.disc(xs[i], 0.88f - depth[i] * rowH, dotSize, palette.at(depth[i].toFloat() / levels))
        }
        if (payoffFrame) g.flash(0.3f)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = size >= target && restSeconds in -1000f..0f

    override fun counters(): List<Pair<String, String>> = listOf(
        "nodes" to size.toString(),
        "depth" to maxDepth.toString(),
        "ideal" to (Math.log(max(1, size).toDouble()) / Math.log(2.0)).toInt().toString()
    )

    companion object {
        fun def(): SimDef = SimDef(
            id = "algorithms/binary-tree",
            title = "Binary search tree",
            shelf = Shelf.ALGORITHMS,
            family = "Data structures & code",
            blurb = "Random keys build a bushy tree; sorted keys build a line.",
            formats = listOf(TrapFormat.GROW, TrapFormat.GUESS),
            heroLabel = "Rebuild",
            defaultPalette = "Ocean",
            typicalSeconds = 20f,
            params = listOf(
                Param.choice("order", "Keys arrive", listOf("Random", "Already sorted"), 0,
                    card = Card.DATA, input = ParamInput.TILES),
                Param.int("nodes", "Nodes", 1, 4000, 220, card = Card.DATA, logScale = true),
                Param.float("rate", "Speed", 1f, 2000f, 26f, card = Card.MOTION, unit = "/s", logScale = true)
            ),
            create = { BinaryTreeSim() }
        )
    }
}

/**
 * A ripple-carry adder built from logic gates, counting upwards.
 *
 * Every wire is drawn in its real state, so the carry visibly travels along the
 * chain, which is the reason fast adders exist at all.
 */
class LogicGatesSim : Sim {

    private var bits = 8
    private var a = 0
    private var b = 0
    private var carryBits = BooleanArray(0)
    private var sumBits = BooleanArray(0)
    private var tick = 0f
    private var counting = 0
    private var payoffFrame = false

    override fun setup(env: SimEnv) {
        bits = env.params.i("bits", 6).coerceIn(2, 16)
        carryBits = BooleanArray(bits + 1)
        sumBits = BooleanArray(bits)
        a = 0
        b = env.params.i("addend", 1).coerceIn(0, (1 shl bits) - 1)
        tick = 0f
        counting = 0
        payoffFrame = false
        compute()
    }

    private fun compute() {
        var c = false
        for (i in 0 until bits) {
            val ab = (a shr i) and 1 == 1
            val bb = (b shr i) and 1 == 1
            val s = ab xor bb xor c
            val nc = (ab && bb) || (c && (ab xor bb))
            sumBits[i] = s
            carryBits[i] = c
            c = nc
        }
        carryBits[bits] = c
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        tick += abs(dt) * env.params.f("rate", 4.5f)
        while (tick >= 1f) {
            tick -= 1f
            a = (a + b) and ((1 shl bits) - 1)
            counting++
            compute()
            var flips = 0
            for (i in 0 until bits) if (carryBits[i]) flips++
            env.emit(
                EventKind.WRITE,
                (a.toFloat() / (1 shl bits)).coerceIn(0f, 1f),
                0f,
                (0.3f + flips.toFloat() / bits * 0.6f).coerceAtMost(1f)
            )
            // A carry all the way along the chain is the moment worth waiting for.
            if (carryBits[bits]) {
                payoffFrame = true
                env.emit(EventKind.DONE, 0.9f, 0f, 1f)
            }
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val halfW = env.halfWidth * 0.9f
        val colW = 2f * halfW / bits
        val on = Palette.SUCCESS
        val off = Palette.withAlpha(Palette.TEXT_SECONDARY, 0.28f)
        val rows = listOf(0.6f, 0.3f, -0.1f, -0.5f)
        val labels = listOf(a, b)
        for ((rowIndex, value) in labels.withIndex()) {
            for (i in 0 until bits) {
                val bit = (value shr (bits - 1 - i)) and 1 == 1
                g.rect(
                    -halfW + (i + 0.5f) * colW, rows[rowIndex],
                    colW * 0.35f, 0.07f, if (bit) palette.at(0.3f + rowIndex * 0.3f) else off
                )
            }
        }
        // Carry chain.
        for (i in 0 until bits) {
            val c = carryBits[bits - 1 - i]
            val x = -halfW + (i + 0.5f) * colW
            g.disc(x, rows[2], 0.02f, if (c) Palette.WARNING else off)
            if (i < bits - 1) {
                g.line(x, rows[2], x + colW, rows[2], 0.004f,
                    if (c) Palette.WARNING else off, if (c) 1f else 0.4f)
            }
        }
        // Sum.
        for (i in 0 until bits) {
            val bit = sumBits[bits - 1 - i]
            val x = -halfW + (i + 0.5f) * colW
            if (bit) g.glow(x, rows[3], colW * 0.7f, on, 0.45f)
            g.rect(x, rows[3], colW * 0.35f, 0.07f, if (bit) on else off)
        }
        if (payoffFrame) g.flash(0.35f)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "value" to a.toString(),
        "binary" to a.toString(2).padStart(bits, '0')
    )

    companion object {
        fun def(): SimDef = SimDef(
            id = "algorithms/logic-gates",
            title = "Ripple-carry adder",
            shelf = Shelf.ALGORITHMS,
            family = "Computing & crypto",
            blurb = "Watch the carry travel along the chain, bit by bit.",
            formats = listOf(TrapFormat.NUMBER_UP, TrapFormat.SONG, TrapFormat.CHAIN),
            heroLabel = "Reset the count",
            defaultPalette = "Mono glow",
            typicalSeconds = 22f,
            params = listOf(
                Param.int("bits", "Bits", 2, 16, 6, card = Card.DATA),
                Param.int("addend", "Add each tick", 0, 255, 1, card = Card.DATA),
                Param.float("rate", "Ticks", 0.3f, 60f, 4.5f, card = Card.MOTION, unit = "/s", logScale = true)
            ),
            create = { LogicGatesSim() }
        )
    }
}

/**
 * Gradient descent on a bumpy loss surface, with several walkers started in
 * different places so you can see which ones find the bottom and which get
 * stuck in a dip on the way.
 */
class GradientDescentSim : Sim {

    private var walkers = 24
    private var wx = FloatArray(0)
    private var wy = FloatArray(0)
    private var vx = FloatArray(0)
    private var vy = FloatArray(0)
    private var best = Float.MAX_VALUE
    private var payoffFrame = false
    private var elapsed = 0f
    private var converged = false
    private var bumps = 3f
    private var momentum = 0.86f
    private var rate = 0.05f
    private var gridX = 0
    private var gridY = 0

    override fun setup(env: SimEnv) {
        val p = env.params
        walkers = p.i("walkers", 24).coerceIn(1, 400)
        bumps = p.f("bumps", 3f)
        momentum = p.f("momentum", 0.86f)
        rate = p.f("rate", 0.022f)
        wx = FloatArray(walkers); wy = FloatArray(walkers)
        vx = FloatArray(walkers); vy = FloatArray(walkers)
        for (i in 0 until walkers) {
            wx[i] = env.rng.range(-env.halfWidth * 0.9f, env.halfWidth * 0.9f)
            wy[i] = env.rng.range(-0.9f, 0.9f)
        }
        gridX = 44
        gridY = 70
        best = Float.MAX_VALUE
        payoffFrame = false
        elapsed = 0f
        converged = false
    }

    /** A bowl with ripples: one true minimum, plenty of local dips. */
    private fun loss(x: Float, y: Float): Float =
        (x * x + y * y) * 0.6f + sin(x * bumps) * cos(y * bumps) * 0.22f

    private fun gradX(x: Float, y: Float): Float =
        1.2f * x + bumps * cos(x * bumps) * cos(y * bumps) * 0.22f

    private fun gradY(x: Float, y: Float): Float =
        1.2f * y - bumps * sin(x * bumps) * sin(y * bumps) * 0.22f

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        var lowest = Float.MAX_VALUE
        for (i in 0 until walkers) {
            vx[i] = vx[i] * momentum - gradX(wx[i], wy[i]) * rate
            vy[i] = vy[i] * momentum - gradY(wx[i], wy[i]) * rate
            wx[i] = (wx[i] + vx[i] * dt * 6f).coerceIn(-env.halfWidth, env.halfWidth)
            wy[i] = (wy[i] + vy[i] * dt * 6f).coerceIn(-1f, 1f)
            val l = loss(wx[i], wy[i])
            if (l < lowest) lowest = l
        }
        if (lowest < best) {
            best = lowest
            env.emit(EventKind.STEP, (1f - (best + 0.3f) / 1.6f).coerceIn(0f, 1f), 0f, 0.35f)
        }
        if (!converged && elapsed > 9f && best < 0.015f) {
            converged = true
            payoffFrame = true
            env.emit(EventKind.DONE, 1f, 0f, 1f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val halfW = env.halfWidth
        g.reservePoints(gridX * gridY + walkers)
        val dx = 2f * halfW / gridX
        val dy = 2f / gridY
        for (r in 0 until gridY) {
            val y = -1f + (r + 0.5f) * dy
            for (c in 0 until gridX) {
                val x = -halfW + (c + 0.5f) * dx
                val l = loss(x, y)
                g.point(x, y, max(dx, dy) * 1.05f, Palette.withAlpha(palette.at((l / 1.6f).coerceIn(0f, 1f)), 0.6f))
            }
        }
        for (i in 0 until walkers) {
            g.glow(wx[i], wy[i], 0.045f, Palette.SUCCESS, 0.5f)
            g.disc(wx[i], wy[i], 0.013f, Palette.brighten(Palette.SUCCESS, 0.25f))
        }
        if (payoffFrame) g.flash(0.3f)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "best loss" to String.format("%.4f", best),
        "walkers" to walkers.toString()
    )

    companion object {
        fun def(): SimDef = SimDef(
            id = "algorithms/gradient-descent",
            title = "Gradient descent",
            shelf = Shelf.ALGORITHMS,
            family = "Machine learning & optimization",
            blurb = "Some walkers find the bottom; some get stuck in a dip.",
            formats = listOf(TrapFormat.RACE, TrapFormat.GUESS, TrapFormat.LAST_STANDING),
            heroLabel = "Drop them again",
            defaultPalette = "Colour-safe",
            typicalSeconds = 18f,
            params = listOf(
                Param.int("walkers", "Walkers", 1, 400, 24, card = Card.DATA, logScale = true),
                Param.float("bumps", "Bumpiness", 0f, 10f, 3f, card = Card.DATA),
                Param.float("rate", "Learning rate", 0.005f, 0.4f, 0.022f, card = Card.MOTION, logScale = true),
                Param.float("momentum", "Momentum", 0f, 0.98f, 0.86f, card = Card.MOTION)
            ),
            create = { GradientDescentSim() }
        )
    }
}

/**
 * A force-directed graph untangling itself: edges pull, every node pushes, and
 * a readable layout falls out of the two forces balancing.
 */
class GraphLayoutSim : Sim {

    private var n = 70
    private var px = FloatArray(0)
    private var py = FloatArray(0)
    private var vx = FloatArray(0)
    private var vy = FloatArray(0)
    private var edgeA = IntArray(0)
    private var edgeB = IntArray(0)
    private var edges = 0
    private var energy = 1f
    private var payoffFrame = false
    private var settled = false
    private var elapsed = 0f

    override fun setup(env: SimEnv) {
        val p = env.params
        n = p.i("nodes", 70).coerceIn(3, 900)
        px = FloatArray(n); py = FloatArray(n)
        vx = FloatArray(n); vy = FloatArray(n)
        for (i in 0 until n) {
            px[i] = env.rng.range(-0.25f, 0.25f)
            py[i] = env.rng.range(-0.25f, 0.25f)
        }
        val extra = p.f("extra", 0.6f)
        val maxEdges = n - 1 + (n * extra).toInt()
        edgeA = IntArray(maxEdges)
        edgeB = IntArray(maxEdges)
        edges = 0
        // A spanning tree first, so the graph is always one connected piece.
        for (i in 1 until n) {
            edgeA[edges] = i
            edgeB[edges] = env.rng.int(0, i)
            edges++
        }
        while (edges < maxEdges) {
            val a = env.rng.int(0, n)
            val b = env.rng.int(0, n)
            if (a != b) {
                edgeA[edges] = a
                edgeB[edges] = b
                edges++
            }
        }
        energy = 1f
        payoffFrame = false
        settled = false
        elapsed = 0f
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        val repel = env.params.f("repel", 1f) * 0.00035f
        val spring = env.params.f("spring", 1f) * 2.2f
        val ideal = (1.4f / sqrt(n.toFloat())).coerceIn(0.03f, 0.4f)

        for (i in 0 until n) {
            var fx = -px[i] * 0.25f
            var fy = -py[i] * 0.25f
            for (j in 0 until n) {
                if (i == j) continue
                val dx = px[i] - px[j]
                val dy = py[i] - py[j]
                val d2 = dx * dx + dy * dy + 0.0006f
                val f = repel / d2
                fx += dx * f
                fy += dy * f
            }
            vx[i] = (vx[i] + fx * dt) * 0.86f
            vy[i] = (vy[i] + fy * dt) * 0.86f
        }
        for (e in 0 until edges) {
            val a = edgeA[e]
            val b = edgeB[e]
            val dx = px[b] - px[a]
            val dy = py[b] - py[a]
            val d = hypot(dx, dy) + 1e-6f
            val f = (d - ideal) * spring
            val ux = dx / d * f * dt
            val uy = dy / d * f * dt
            vx[a] += ux; vy[a] += uy
            vx[b] -= ux; vy[b] -= uy
        }
        var moved = 0f
        for (i in 0 until n) {
            px[i] = (px[i] + vx[i] * dt).coerceIn(-env.halfWidth, env.halfWidth)
            py[i] = (py[i] + vy[i] * dt).coerceIn(-1f, 1f)
            moved += abs(vx[i]) + abs(vy[i])
        }
        energy = energy * 0.9f + (moved / n) * 0.1f
        env.emit(EventKind.STEP, energy.coerceIn(0f, 1f), 0f, 0.25f)
        if (!settled && elapsed > 10f && energy < 0.012f) {
            settled = true
            payoffFrame = true
            env.emit(EventKind.DONE, 0.85f, 0f, 1f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        g.reserveInstances(edges + n)
        for (e in 0 until edges) {
            val a = edgeA[e]
            val b = edgeB[e]
            g.line(px[a], py[a], px[b], py[b], 0.0018f, Palette.withAlpha(palette.at(0.35f), 0.5f))
        }
        val r = max(0.005f, min(0.02f, 0.9f / sqrt(n.toFloat())))
        for (i in 0 until n) {
            g.disc(px[i], py[i], r, palette.at(i.toFloat() / n))
        }
        if (payoffFrame) g.flash(0.25f)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "nodes" to n.toString(),
        "edges" to edges.toString()
    )

    companion object {
        fun def(): SimDef = SimDef(
            id = "algorithms/graph-layout",
            title = "Graph untangling itself",
            shelf = Shelf.ALGORITHMS,
            family = "Graphs & networks",
            blurb = "Edges pull, nodes push, and a readable shape appears.",
            formats = listOf(TrapFormat.GROW, TrapFormat.LOOP, TrapFormat.REVEAL),
            heroLabel = "New graph",
            defaultPalette = "Aurora",
            typicalSeconds = 20f,
            params = listOf(
                Param.int("nodes", "Nodes", 3, 900, 70, card = Card.DATA, logScale = true),
                Param.float("extra", "Extra edges", 0f, 2f, 0.6f, card = Card.DATA),
                Param.float("repel", "Repulsion", 0.1f, 4f, 1f, card = Card.MOTION),
                Param.float("spring", "Edge pull", 0.1f, 4f, 1f, card = Card.MOTION)
            ),
            create = { GraphLayoutSim() }
        )
    }
}

object StructureSims {
    fun defs(): List<SimDef> = listOf(
        HanoiSim.def(),
        NQueensSim.def(),
        BinaryTreeSim.def(),
        LogicGatesSim.def(),
        GradientDescentSim.def(),
        GraphLayoutSim.def()
    )
}
