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

    /** However many discs, the tower is finished inside this many seconds. */
    private val TARGET_SECONDS = 19f

    /** Moves a second that become notes; the rest are heard as texture. */
    private val MAX_NOTES_PER_SECOND = 110f
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
        // 2^n - 1 moves: at eighteen discs that is a quarter of a million, so
        // the pace has to follow the tower. It used to be a floor under an
        // absolute moves-a-second slider, and with the shipped seven discs the
        // floor (6.7) sat above the slider's own default (6.5) — so the slider
        // drew the same picture at every position from 0.5 up to the floor,
        // including where it shipped. Speed is a multiplier over the pace that
        // finishes the tower on the arc, so every position does something at
        // every size.
        val rate = moves.size / TARGET_SECONDS * env.params.f("rate", 1f).coerceIn(0.05f, 40f)
        val voiceEvery = max(1, (rate / MAX_NOTES_PER_SECOND).toInt())
        carry += abs(dt) * rate
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
            // Eighteen discs is a quarter of a million moves in twenty seconds.
            // Only a sample of them becomes a note; the rest are the texture of
            // the tower moving.
            if (played % voiceEvery == 0) {
                env.emit(
                    EventKind.SWAP,
                    1f - (disc.toFloat() / discs),
                    (toPeg - 1).toFloat() * 0.7f,
                    0.6f
                )
            }
        }
        liftProgress = min(1f, liftProgress + abs(dt) * 6f)
        if (played >= moves.size && restSeconds < 0f) {
            payoffFrame = true
            restSeconds = 1.5f
            env.emit(EventKind.DONE, 1f, 0f, 1f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val halfW = env.halfWidth * 0.92f
        // The widest disc reaches 0.46 of a peg gap either side of its peg, so
        // the gap is two thirds of the width: at the full width the biggest
        // discs on the outer pegs ran off both edges of the screen.
        val pegGap = halfW / 1.5f
        val baseY = -0.75f
        val discH = min(0.085f, 1.3f / discs)
        for (i in 0 until 3) {
            val x = (i - 1) * pegGap
            g.rect(x, baseY + 0.55f, 0.008f, 0.55f, Palette.withAlpha(Palette.TEXT_SECONDARY, 0.45f))
        }
        g.rect(0f, baseY - 0.02f, halfW, 0.018f, Palette.withAlpha(Palette.TEXT_SECONDARY, 0.5f))
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
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = played >= moves.size && restSeconds in -1000f..0f

    override fun counters(): List<Pair<String, String>> = listOf(
        "moves" to String.format(java.util.Locale.US, "%,d / %,d", played, moves.size),
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
                Param.float("rate", "Speed", 0.1f, 20f, 1f, card = Card.MOTION, unit = "x", logScale = true)
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

    /** Scales the try rate with the board, so a big board still solves. */
    private val BASE_TRIES = 34f

    /**
     * Chosen by measurement, not taste: with the ramp below, the shipped board
     * of ten solves in 7.2 seconds, which is where the design doc's arc puts
     * the payoff in the ten seconds this sim declares. The old rate solved it
     * in 2.3 — too fast to see the backtracking the sim's own blurb is about,
     * "place, test, take it back".
     */
    private val WATCHABLE_DIVISOR = 3400f

    /** How quickly the search gathers pace. Measured, see the note in step(). */
    private val RAMP_SECONDS = 6f

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
    private var elapsed = 0f

    override fun setup(env: SimEnv) {
        n = env.params.i("size", 10).coerceIn(4, 16)
        elapsed = 0f
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
        elapsed += abs(dt)
        // Backtracking cost climbs steeply with the board, so the rate a
        // board needs to finish in a watchable time climbs with it. That used
        // to be a floor under an absolute tries-a-second slider — and the floor
        // sat above the slider's own default, so on the shipped board the
        // control did nothing at all from one end up to 85, which is 41% of a
        // log slider's travel. A multiplier over the board's own rate means
        // every position does something at every size.
        // And the search accelerates as it runs. No formula in n can set a
        // rate that suits every board: measured, the first solution costs about
        // 13 tries at four queens, 200 at ten, and over fourteen thousand at
        // sixteen, which is not any power of n. A rate that climbs covers the
        // range — small boards still solve in seconds, sixteen reaches an
        // answer instead of never — and it suits the sim, which should feel
        // like it is bearing down on the solution rather than plodding.
        val ramp = 1f + elapsed / RAMP_SECONDS
        val base = BASE_TRIES * n * n * n / WATCHABLE_DIVISOR * ramp * ramp
        carry += abs(dt) * base * env.params.f("rate", 1f).coerceIn(0.05f, 60f)
        var budget = min(carry.toInt(), 200000)
        carry -= budget
        while (budget-- > 0 && !solved) {
            if (row >= n) {
                solved = true
                payoffFrame = true
                restSeconds = 1.5f
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
                    // Refilled in place: allocating here would happen inside step.
                    row = 0
                    java.util.Arrays.fill(cols, -1)
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
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = solved && restSeconds in -1000f..0f

    override fun counters(): List<Pair<String, String>> = listOf(
        "placements tried" to String.format(java.util.Locale.US, "%,d", tries),
        "backtracks" to String.format(java.util.Locale.US, "%,d", backtracks)
    )

    companion object {
        fun def(): SimDef = SimDef(
            id = "algorithms/n-queens",
            title = "N-queens",
            shelf = Shelf.ALGORITHMS,
            family = "Puzzles & backtracking",
            blurb = "Place, test, take it back. The retreat is half the algorithm.",
            formats = listOf(TrapFormat.PERFECT_FIT, TrapFormat.GUESS, TrapFormat.LAST_STANDING),
            heroLabel = "Solve again",
            defaultPalette = "Ember",
            typicalSeconds = 10f,
            params = listOf(
                // Plain backtracking, which is the point of watching it, does
                // not finish a board much past this inside a run.
                Param.int("size", "Board size", 4, 16, 10, card = Card.DATA),
                Param.float(
                    "rate", "Speed", 0.05f, 60f, 1f,
                    card = Card.MOTION, unit = "x", logScale = true
                )
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

    /**
     * Seconds a tree takes at 1x, whatever its size. The shipped 220 nodes at
     * the old twenty-six a second took this long, so the shipped tree looks
     * exactly as it did.
     */
    private val TREE_SECONDS = 8.5f

    override fun setup(env: SimEnv) {
        val p = env.params
        target = p.i("nodes", 220).coerceIn(1, 100_000)
        keys = FloatArray(target)
        left = IntArray(target) { -1 }
        right = IntArray(target) { -1 }
        depth = IntArray(target)
        xs = FloatArray(target)
        size = 0
        // A whole node owed from the start, so the root is planted on the very
        // first step. At twenty-six a second from an empty tree the first
        // frames drew nothing at all, and every new run — including the one
        // that starts after each finished tree — opened on a black screen
        // reading "0 nodes".
        carry = 1f
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
        // A multiplier over the pace that finishes this many nodes on the arc.
        // An absolute twenty-six a second meant the Nodes slider did nothing
        // above about twelve hundred — a bigger tree was never built inside a
        // run — and TickTock's generated clips, which pick up to four thousand
        // nodes, needed two and a half minutes to pay off and were thrown away.
        carry += abs(dt) * target / TREE_SECONDS * env.params.f("rate", 1f).coerceIn(0.05f, 40f)
        while (carry >= 1f && size < target) {
            carry -= 1f
            val key = if (sorted) size.toFloat() / target else env.rng.nextFloat()
            insert(key, env)
        }
        if (size >= target && restSeconds < 0f) {
            payoffFrame = true
            restSeconds = 1.5f
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
        val edgeStride = maxOf(1, size / Draw.MAX_EDGES)
        for (i in 0 until size) xs[i] = -halfW + keys[i] * 2f * halfW
        for (i in 0 until size) {
            val x = xs[i]
            val y = 0.88f - depth[i] * rowH
            val l = left[i]
            val r = right[i]
            val c = palette.at(depth[i].toFloat() / levels)
            if (edgeStride == 1 || i % edgeStride == 0) {
                if (l in 0 until size) g.line(x, y, xs[l], 0.88f - depth[l] * rowH, 0.0022f, c, 0.6f)
                if (r in 0 until size) g.line(x, y, xs[r], 0.88f - depth[r] * rowH, 0.0022f, c, 0.6f)
            }
        }
        val dotSize = max(0.004f, min(0.016f, 2.4f / size))
        // A hundred thousand nodes are a texture, not a diagram: past the
        // threshold the nodes go through the point path and the edges thin out.
        if (size > Draw.AS_POINTS) {
            g.reservePoints(size)
            for (i in 0 until size) {
                g.point(
                    xs[i],
                    0.88f - depth[i] * rowH,
                    // World units, like every point size: 1.6 was the size of
                    // the whole screen, clamped to the renderer's largest dot.
                    dotSize * 1.6f,
                    palette.at(depth[i].toFloat() / levels)
                )
            }
        } else
        for (i in 0 until size) {
            g.disc(xs[i], 0.88f - depth[i] * rowH, dotSize, palette.at(depth[i].toFloat() / levels))
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = size >= target && restSeconds in -1000f..0f

    override fun counters(): List<Pair<String, String>> = listOf(
        "nodes" to size.toString(),
        "depth" to maxDepth.toString(),
        "ideal" to (Math.log(max(1, size).toDouble()) / Math.log(2.0)).toInt().toString()
    )

    private object Draw {
        /** Above this many nodes, the tree is painted as points. */
        const val AS_POINTS = 2_000

        /** Edges are sampled so a huge tree does not become a solid block. */
        const val MAX_EDGES = 3_000
    }

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
            typicalSeconds = 12f,
            params = listOf(
                Param.choice("order", "Keys arrive", listOf("Random", "Already sorted"), 0,
                    card = Card.DATA, input = ParamInput.TILES),
                Param.int("nodes", "Nodes", 1, 100000, 220, card = Card.DATA, logScale = true),
                // Reaches what the node slider offers: a hundred thousand nodes at
                // twenty-six a second is an hour.
                Param.float("rate", "Speed", 0.1f, 20f, 1f, card = Card.MOTION, unit = "x", logScale = true)
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
                // To 63, what the shipped six-bit register can hold. It went to
                // 255 and was clamped to the register in setup, so everything
                // above 63 drew the same picture — three quarters of the slider.
                Param.int("addend", "Add each tick", 0, 63, 1, card = Card.DATA),
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
    private var convergedFor = 0f
    private var bumps = 3f
    private var momentum = 0.86f
    private var rate = 0.008f
    private var gridX = 0
    private var gridY = 0
    private var settled = BooleanArray(0)
    private var stillFor = FloatArray(0)
    private var settledCount = 0

    override fun setup(env: SimEnv) {
        val p = env.params
        walkers = p.i("walkers", 24).coerceIn(1, 400)
        settled = BooleanArray(walkers)
        stillFor = FloatArray(walkers)
        settledCount = 0
        bumps = p.f("bumps", 3f)
        momentum = p.f("momentum", 0.86f)
        rate = p.f("rate", 0.008f)
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
        convergedFor = 0f
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
        // Live, not cached at setup. Watching a learning rate overshoot and a
        // momentum escape a local minimum is the whole subject of this sim, and
        // it only reads that way if the sliders change the walkers in flight
        // rather than restarting them.
        momentum = env.params.f("momentum", 0.86f)
        rate = env.params.f("rate", 0.008f)
        var lowest = Float.MAX_VALUE
        for (i in 0 until walkers) {
            vx[i] = vx[i] * momentum - gradX(wx[i], wy[i]) * rate
            vy[i] = vy[i] * momentum - gradY(wx[i], wy[i]) * rate
            wx[i] = (wx[i] + vx[i] * dt * 6f).coerceIn(-env.halfWidth, env.halfWidth)
            wy[i] = (wy[i] + vy[i] * dt * 6f).coerceIn(-1f, 1f)
            val l = loss(wx[i], wy[i])
            if (l < lowest) lowest = l
            // Each walker lands with its own note, pitched by how deep its dip
            // is. The sim used to sound only when the overall best improved,
            // which stops within three seconds, and then held six seconds of
            // silence while the rest of the walkers were still rolling in.
            val speed = hypot(vx[i], vy[i]) * 6f
            if (!settled[i]) {
                stillFor[i] = if (speed < SETTLE_SPEED) stillFor[i] + abs(dt) else 0f
                if (stillFor[i] > SETTLE_SECONDS) {
                    settled[i] = true
                    settledCount++
                    env.emit(EventKind.HIT, (1f - (l + 0.3f) / 1.6f).coerceIn(0f, 1f),
                        (wx[i] / env.halfWidth).coerceIn(-1f, 1f), 0.6f)
                }
            } else if (speed > SETTLE_SPEED * 4f) {
                // A slider kicked it loose; it gets to land again.
                settled[i] = false
                stillFor[i] = 0f
                settledCount--
            }
        }
        if (lowest < best) {
            best = lowest
            env.emit(EventKind.STEP, (1f - (best + 0.3f) / 1.6f).coerceIn(0f, 1f), 0f, 0.35f)
        }
        // Done when every walker has come to rest, wherever it rests: the
        // question the sim asks is which ones find the bottom.
        if (!converged && elapsed > MIN_SECONDS && (settledCount == walkers || elapsed > BACKSTOP_SECONDS)) {
            converged = true
            payoffFrame = true
            env.emit(EventKind.DONE, 1f, 0f, 1f)
        }
        if (converged) convergedFor += abs(dt)
    }

    /**
     * Seconds the finished picture is held before the run reports finished,
     * and the screens give it the resolve beat and start a fresh one. It
     * reached its end and then sat there — measured, twenty-seven seconds frozen and fifty silent in a sixty-second run, the walkers parked in the minimum — without ever saying
     * it was done, so nothing restarted it: a still, silent screen that read
     * as the app having stopped.
     */
    private val HOLD_SECONDS = 1.5f
    private val SETTLE_SPEED = 0.02f
    private val SETTLE_SECONDS = 0.4f
    private val MIN_SECONDS = 4f
    private val BACKSTOP_SECONDS = 30f

    override fun finished(): Boolean = converged && convergedFor > HOLD_SECONDS

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
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "best loss" to String.format(java.util.Locale.US, "%.4f", best),
        "landed" to "$settledCount / $walkers"
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
            typicalSeconds = 11f,
            params = listOf(
                Param.int("walkers", "Walkers", 1, 400, 24, card = Card.DATA, logScale = true),
                Param.float("bumps", "Bumpiness", 0f, 10f, 3f, card = Card.DATA),
                Param.float("rate", "Learning rate", 0.005f, 0.4f, 0.008f, card = Card.MOTION, logScale = true),
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

    /**
     * How the graph is framed: drawn scaled about its own centre so it fills
     * the screen, eased as it grows. Its physics settles into a small knot —
     * measured, five per cent of the screen — and it was drawn at that size, a
     * cluster in the middle of an empty phone. The framing changes only what
     * is drawn; the layout underneath is untouched.
     */
    private var fitScale = 1f
    private var fitX = 0f
    private var fitY = 0f

    override fun setup(env: SimEnv) {
        fitScale = 1f
        fitX = 0f
        fitY = 0f
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
        frame(env, dt)
        env.emit(EventKind.STEP, energy.coerceIn(0f, 1f), 0f, 0.25f)
        if (!settled && elapsed > 10f && energy < 0.012f) {
            settled = true
            payoffFrame = true
            env.emit(EventKind.DONE, 0.85f, 0f, 1f)
        }
    }

    /** Scratch for the framing's percentiles, so it allocates nothing per step. */
    private var sortX = FloatArray(0)
    private var sortY = FloatArray(0)

    /**
     * Ease the framing towards the graph's extent: the bulk of the nodes, with
     * the outliers allowed to reach the edge. Framing the full extent let a
     * couple of dangling leaves set the scale, and the graph stayed a knot in
     * the middle of the screen.
     */
    private fun frame(env: SimEnv, dt: Float) {
        if (n < 2) return
        if (sortX.size < n) { sortX = FloatArray(n); sortY = FloatArray(n) }
        for (i in 0 until n) { sortX[i] = px[i]; sortY[i] = py[i] }
        java.util.Arrays.sort(sortX, 0, n)
        java.util.Arrays.sort(sortY, 0, n)
        val lo = (n * 0.02f).toInt()
        val hi = (n - 1 - lo).coerceAtLeast(lo + 1)
        val bulkW = (sortX[hi] - sortX[lo]) * 0.5f + 0.02f
        val bulkH = (sortY[hi] - sortY[lo]) * 0.5f + 0.02f
        val fullW = (sortX[n - 1] - sortX[0]) * 0.5f + 0.02f
        val fullH = (sortY[n - 1] - sortY[0]) * 0.5f + 0.02f
        val bulk = min(env.halfWidth * FILL / bulkW, FILL / bulkH)
        // The whole graph may run to the edge of the screen, not off it.
        val whole = min(env.halfWidth / fullW, 1f / fullH)
        val target = min(bulk, whole).coerceIn(0.6f, MAX_FIT)
        // Slow, so the frame breathes with the graph rather than snapping to it.
        val k = min(1f, abs(dt) * 1.2f)
        fitScale += (target - fitScale) * k
        fitX += ((sortX[lo] + sortX[hi]) * 0.5f - fitX) * k
        fitY += ((sortY[lo] + sortY[hi]) * 0.5f - fitY) * k
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val s = fitScale
        g.reserveInstances(edges + n)
        for (e in 0 until edges) {
            val a = edgeA[e]
            val b = edgeB[e]
            g.line(
                (px[a] - fitX) * s, (py[a] - fitY) * s, (px[b] - fitX) * s, (py[b] - fitY) * s,
                0.0018f * min(s, 2f), Palette.withAlpha(palette.at(0.35f), 0.5f)
            )
        }
        // Grown with the framing, but not all the way: at full scale the
        // shipped nodes would be coins.
        val r = max(0.005f, min(0.02f, 0.9f / sqrt(n.toFloat()))) * kotlin.math.sqrt(s)
        for (i in 0 until n) {
            g.disc((px[i] - fitX) * s, (py[i] - fitY) * s, r, palette.at(i.toFloat() / n))
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "nodes" to n.toString(),
        "edges" to edges.toString()
    )

    companion object {
        /** Share of the screen the graph is framed to fill. */
        private const val FILL = 0.8f

        /** The most the framing will magnify, so a two-node graph is not a wall. */
        private const val MAX_FIT = 6f

        fun def(): SimDef = SimDef(
            id = "algorithms/graph-layout",
            title = "Graph untangling itself",
            shelf = Shelf.ALGORITHMS,
            family = "Graphs & networks",
            blurb = "Edges pull, nodes push, and a readable shape appears.",
            formats = listOf(TrapFormat.GROW, TrapFormat.REVEAL),
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
        GraphLayoutSim.def(),
        SnakeSim.def(),
        PackSim.def()
    )
}
