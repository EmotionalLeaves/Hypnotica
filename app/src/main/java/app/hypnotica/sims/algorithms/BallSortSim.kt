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
import kotlin.math.sin

/**
 * The ball-sort puzzle from a thousand game adverts, played by a solver that
 * doesn't lose. Tubes of mixed balls and two empty ones; a ball can go on an
 * empty tube or on its own colour. Each move lifts a ball out and drops it
 * into its new tube with a click, and each tube that comes out one colour
 * rings.
 *
 * Solved by search before the first move — depth first, trying the moves
 * that finish a tube or stack a colour first, and never visiting a position
 * twice — so the play is all forward and the end is certain.
 */
class BallSortSim : Sim {

    private var colors = 5
    private var cap = 4
    private var tubes = 7
    private var state = Array(0) { IntArray(0) }
    private var height = IntArray(0)
    private var moves = IntArray(0)   // pairs: from, to
    private var moveIndex = 0
    private var phase = 0f            // 0..1 through the current move
    private var moving = -1           // the color in flight, or -1
    private var fromT = 0
    private var toT = 0
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var done = BooleanArray(0)
    private var ring = FloatArray(0)
    private var halfW = 0.46f
    private var tubeW = 0.08f
    private var ballR = 0.03f
    private var floorY = -0.6f
    private var stepSeconds = 0.4f

    override fun setup(env: SimEnv) {
        halfW = env.halfWidth
        colors = env.params.i("colors", 5).coerceIn(2, MAX_COLORS)
        cap = 4
        tubes = colors + 2
        // A shuffled bag of every colour four times, dealt into the full tubes.
        var solution: IntArray? = null
        var tries = 0
        while (solution == null && tries < 20) {
            tries++
            val bag = IntArray(colors * cap) { it / cap }
            env.rng.shuffle(bag)
            state = Array(tubes) { IntArray(cap) }
            height = IntArray(tubes)
            for (i in bag.indices) { val t = i / cap; state[t][height[t]++] = bag[i] }
            solution = solve()
        }
        if (solution == null) {
            // Never seen: a sorted board stirred by a few moves, checked by
            // the same search, shallower until it solves.
            var depth = 24
            while (solution == null && depth > 0) {
                state = Array(tubes) { t -> IntArray(cap) { if (t < colors) t else 0 } }
                height = IntArray(tubes) { t -> if (t < colors) cap else 0 }
                repeat(depth) {
                    val a = env.rng.int(0, tubes); val b = env.rng.int(0, tubes)
                    if (a != b && height[a] > 0 && height[b] < cap) state[b][height[b]++] = state[a][--height[a]]
                }
                solution = solve()
                depth /= 2
            }
        }
        moves = solution ?: IntArray(0)
        moveIndex = 0
        phase = 0f
        moving = -1
        done = BooleanArray(tubes)
        ring = FloatArray(tubes)
        // Done means full and one colour: an empty tube isn't sorted, and
        // counting the two that start empty kept them from ever ringing.
        for (t in 0 until tubes) done[t] = height[t] == cap && isDone(state[t], height[t])
        // Two rows on a narrow screen once there are more than five tubes: in
        // one row the balls were specks with the top of the screen empty.
        layoutRows = if (tubes > 5 && halfW < 0.9f) 2 else 1
        perRow = (tubes + layoutRows - 1) / layoutRows
        // Capped so a wide screen gets bigger tubes, not a strip of small ones.
        spacing = min(2f * halfW * 0.92f / perRow, 0.34f)
        tubeW = spacing * 0.36f
        ballR = tubeW * 0.82f
        // And by height: the rows, the gap between them, and the lift over
        // the top must all fit, or a ball in flight left the screen.
        // Layout top: the top row's rim, plus the lift and the lifted ball,
        // all in ball radii, plus the arc's bump; shifted down by LOWER radii.
        val tallest = 0.9f / ((layoutRows - 1) * ((cap * 2.05f + 0.6f + 5f) * 0.5f) + (cap * 2.05f + 0.6f) * 0.5f + 4.3f - LOWER)
        if (ballR > tallest) {
            ballR = tallest
            tubeW = ballR / 0.82f
        }
        // Each row's tubes are this tall; the rows sit one above the other.
        val tubeH = (cap * 2.05f + 0.6f) * ballR
        rowGap = tubeH + ballR * 5f
        val tubeHFinal = (cap * 2.05f + 0.6f) * ballR
        rowGap = tubeHFinal + ballR * 5f
        // Lowered a little: the lift only goes up, so the room is below.
        floorY = -(layoutRows - 1) * rowGap * 0.5f - tubeHFinal * 0.5f - LOWER * ballR
        // About twelve seconds of play, however long the solution.
        stepSeconds = (TARGET_SECONDS / max(1, moves.size / 2)).coerceIn(0.12f, 0.6f)
        elapsed = 0f
        doneAt = -1f
        payoffFrame = false
    }

    private fun isDone(t: IntArray, h: Int): Boolean = h == 0 || (h == cap && (1 until cap).all { t[it] == t[0] })

    /** Depth-first, best moves first, every position once; null if it gives up. */
    private fun solve(): IntArray? {
        val st = Array(tubes) { state[it].copyOf() }
        val ht = height.copyOf()
        val seen = HashSet<String>()
        val path = ArrayList<Int>()
        var budget = SEARCH_BUDGET
        fun key(): String {
            // Tubes in sorted order: two positions differing only by which
            // tube is which are the same position.
            val parts = Array(tubes) { t -> (0 until ht[t]).joinToString("") { ('a' + st[t][it]).toString() } }
            parts.sort()
            return parts.joinToString("|")
        }
        fun solved(): Boolean = (0 until tubes).all { isDone(st[it], ht[it]) }
        fun dfs(): Boolean {
            if (solved()) return true
            if (--budget <= 0) return false
            if (!seen.add(key())) return false
            // Candidate moves, scored.
            val cand = ArrayList<IntArray>()
            for (a in 0 until tubes) {
                if (ht[a] == 0) continue
                if (isDone(st[a], ht[a])) continue
                val c = st[a][ht[a] - 1]
                // How many of that colour on top of a, in a run.
                var run = 0
                while (run < ht[a] && st[a][ht[a] - 1 - run] == c) run++
                for (b in 0 until tubes) {
                    if (b == a || ht[b] == cap) continue
                    if (ht[b] > 0 && st[b][ht[b] - 1] != c) continue
                    // Moving a whole uniform tube into an empty one changes nothing.
                    if (ht[b] == 0 && run == ht[a]) continue
                    val score = if (ht[b] > 0) 10 + ht[b] else 0
                    cand.add(intArrayOf(a, b, score))
                }
            }
            cand.sortByDescending { it[2] }
            for (m in cand) {
                val a = m[0]; val b = m[1]
                val c = st[a][--ht[a]]
                st[b][ht[b]++] = c
                path.add(a); path.add(b)
                if (dfs()) return true
                path.removeAt(path.size - 1); path.removeAt(path.size - 1)
                st[a][ht[a]++] = st[b][--ht[b]]
            }
            return false
        }
        return if (dfs()) tidy(path) else null
    }

    /**
     * The search finds a way, not a short one: it would drop a ball in one
     * tube and lift it straight out again next move. A ball moved a → b and
     * then b → c is one move a → c (legal, since c and the ball are as they
     * were), and a → b then b → a is no move at all.
     */
    private fun tidy(path: ArrayList<Int>): IntArray {
        val m = ArrayList<Int>(path)
        var changed = true
        while (changed) {
            changed = false
            var i = 0
            while (i + 3 < m.size) {
                val a = m[i]; val b = m[i + 1]; val b2 = m[i + 2]; val c = m[i + 3]
                if (b == b2) {
                    repeat(4) { m.removeAt(i) }
                    if (c != a) { m.add(i, c); m.add(i, a) }
                    changed = true
                    i = max(0, i - 2)
                } else i += 2
            }
        }
        return m.toIntArray()
    }

    private var layoutRows = 1
    private var perRow = 7
    private var spacing = 0.12f
    private var rowGap = 0.5f

    private fun rowOf(t: Int) = t / perRow

    private fun tubeX(t: Int): Float {
        val inRow = if (rowOf(t) == layoutRows - 1) tubes - perRow * (layoutRows - 1) else perRow
        return spacing * (t % perRow + 0.5f - inRow * 0.5f)
    }

    /** The bottom of tube [t]: the first row at the top. */
    private fun base(t: Int) = floorY + (layoutRows - 1 - rowOf(t)) * rowGap

    private fun slotY(t: Int, i: Int) = base(t) + ballR + i * ballR * 2.05f
    private fun topY(t: Int) = slotY(t, cap) + ballR * 0.6f

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        for (t in ring.indices) ring[t] = max(0f, ring[t] - h * 1.5f)
        if (doneAt >= 0f || elapsed < LEAD) return
        if (moving < 0) {
            if (moveIndex * 2 >= moves.size) {
                doneAt = elapsed
                payoffFrame = true
                env.emit(EventKind.DONE, 1f, 0f, 1f)
                for (t in 0 until tubes) ring[t] = 1f
                return
            }
            fromT = moves[moveIndex * 2]; toT = moves[moveIndex * 2 + 1]
            moving = state[fromT][--height[fromT]]
            phase = 0f
        }
        phase += h / stepSeconds * env.params.f("pace", 1f)
        if (phase >= 1f) {
            state[toT][height[toT]++] = moving
            env.emit(EventKind.HIT, (moving + 0.5f) / colors, (tubeX(toT) / max(0.1f, halfW)).coerceIn(-1f, 1f), 0.5f)
            if (!done[toT] && isDone(state[toT], height[toT]) && height[toT] == cap) {
                done[toT] = true
                ring[toT] = 1f
                env.emit(EventKind.STEP, (moving + 0.5f) / colors, 0f, 0.9f)
            }
            moving = -1
            moveIndex++
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val glass = Palette.withAlpha(Palette.TEXT_SECONDARY, 0.6f)
        for (t in 0 until tubes) {
            val x = tubeX(t)
            val bottom = base(t)
            val top = topY(t)
            if (ring[t] > 0f && height[t] > 0) g.glow(x, (bottom + top) * 0.5f, tubeW * 4f, palette.at((state[t][0] + 0.5f) / colors), 0.6f * ring[t])
            // A test tube: straight sides into a round bottom centred on the
            // lowest ball, so the ball sits in it. The bottom used to be a
            // whole circle centred below the ball, which drew a ring hanging
            // off the end of every tube.
            val curve = slotY(t, 0)
            val glassW = 0.006f
            g.rect(x, (curve + top) * 0.5f, tubeW, (top - curve) * 0.5f, Palette.withAlpha(Palette.TEXT_SECONDARY, 0.06f))
            g.ring(x, curve, tubeW, tubeW, Palette.withAlpha(Palette.TEXT_SECONDARY, 0.06f), gap = PI_F, gapAngle = 0f)
            g.line(x - tubeW + glassW * 0.5f, curve, x - tubeW + glassW * 0.5f, top, glassW, glass)
            g.line(x + tubeW - glassW * 0.5f, curve, x + tubeW - glassW * 0.5f, top, glassW, glass)
            g.ring(x, curve, tubeW, glassW, glass, gap = PI_F, gapAngle = 0f)
            // A lip at the rim.
            g.line(x - tubeW - glassW, top, x - tubeW + glassW, top, glassW, glass)
            g.line(x + tubeW - glassW, top, x + tubeW + glassW, top, glassW, glass)
            if (done[t] && height[t] == cap) {
                // A sorted tube keeps a coloured base line, so the end reads as finished.
                g.ring(x, curve, tubeW + glassW * 1.5f, glassW, palette.at((state[t][0] + 0.5f) / colors), gap = PI_F, gapAngle = 0f)
            }
            for (i in 0 until height[t]) {
                val c = palette.at((state[t][i] + 0.5f) / colors)
                g.disc(x, slotY(t, i), ballR, c)
                g.disc(x - ballR * 0.3f, slotY(t, i) + ballR * 0.3f, ballR * 0.28f, Palette.withAlpha(Palette.TEXT_PRIMARY, 0.45f))
            }
        }
        if (moving >= 0) {
            // Up out of its tube, across, and down into the next.
            val p = phase.coerceIn(0f, 1f)
            val x0 = tubeX(fromT); val x1 = tubeX(toT)
            val y0 = slotY(fromT, height[fromT]); val y1 = slotY(toT, height[toT])
            // Over the taller of the two tubes' rims, whichever row each is in.
            val lift = max(topY(fromT), topY(toT)) + ballR * 3f
            val (x, y) = when {
                p < 0.3f -> x0 to (y0 + (lift - y0) * ease(p / 0.3f))
                p < 0.7f -> (x0 + (x1 - x0) * ease((p - 0.3f) / 0.4f)) to (lift + 0.04f * sin((p - 0.3f) / 0.4f * 3.1416f))
                else -> x1 to (lift + (y1 - lift) * ease((p - 0.7f) / 0.3f))
            }
            // Drawn lifted — a shade bigger, with a shadow — so on its way
            // past the other row's tubes it reads as passing in front.
            val lifted = if (p > 0.15f && p < 0.85f) 1.18f else 1f
            g.disc(x + ballR * 0.25f, y - ballR * 0.35f, ballR * lifted, 0x66000000)
            g.disc(x, y, ballR * lifted, palette.at((moving + 0.5f) / colors))
            g.disc(x - ballR * 0.3f, y + ballR * 0.3f, ballR * 0.3f, Palette.withAlpha(Palette.TEXT_PRIMARY, 0.5f))
        }
    }

    private fun ease(t: Float): Float = t * t * (3f - 2f * t)

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && elapsed - doneAt > 3f

    override fun counters(): List<Pair<String, String>> = listOf(
        "moves" to "${moveIndex} / ${moves.size / 2}",
        "sorted" to "${done.count { it }} / $colors"
    )

    companion object {
        const val MAX_COLORS = 10
        const val SEARCH_BUDGET = 60000
        const val TARGET_SECONDS = 12f
        const val LEAD = 0.8f
        const val LOWER = 2f
        const val PI_F = 3.1415927f

        fun def(): SimDef = SimDef(
            id = "algorithms/ball-sort",
            title = "Ball sort",
            shelf = Shelf.ALGORITHMS,
            family = "Puzzles",
            blurb = "The puzzle from every game ad, solved — every move planned before the first.",
            formats = listOf(TrapFormat.PERFECT_FIT, TrapFormat.FILL, TrapFormat.SNAP),
            heroLabel = "New puzzle",
            defaultPalette = "Candy",
            typicalSeconds = 16f,
            params = listOf(
                Param.int("colors", "Colours", 2, MAX_COLORS, 5, card = Card.DATA),
                Param.float("pace", "Pace", 0.3f, 4f, 1f, card = Card.MOTION)
            ),
            create = { BallSortSim() }
        )
    }
}
