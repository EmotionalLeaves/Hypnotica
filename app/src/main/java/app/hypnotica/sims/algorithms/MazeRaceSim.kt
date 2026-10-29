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

/**
 * Four ways out of the same maze, raced side by side: breadth-first, which
 * floods every corridor evenly; depth-first, which runs down one until it
 * hits a wall; greedy, which always heads for the exit; and the oldest trick
 * there is, a hand on the right-hand wall. They all get one cell a tick.
 *
 * The exit is somewhere new every maze, so which wins depends on it — near
 * the start and breadth-first gets there first, a lucky first turn makes
 * depth-first unbeatable, a long detour wrecks greedy, and the wall follower
 * is right more often than it deserves. The routes are shown at the end.
 */
class MazeRaceSim : Sim {

    private var cols = 13
    private var rows = 25
    private var cells = 0
    /** Walls: bit 0 east, bit 1 south, of each cell. */
    private var walls = IntArray(0)
    private val order = Array(SOLVERS) { IntArray(0) }
    private val parent = Array(SOLVERS) { IntArray(0) }
    private val length = IntArray(SOLVERS)
    private val bestDist = IntArray(SOLVERS)
    private var rate = 10f
    private var shown = 0f
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var winner = -1
    private var cell = 0.03f
    private var panelW = 0.4f
    private var panelH = 0.9f
    private var layoutCols = 2
    private var goal = 0

    override fun setup(env: SimEnv) {
        val halfW = env.halfWidth
        // Two by two on a tall screen, four across on a wide one.
        layoutCols = if (halfW > 1.2f) 4 else 2
        val layoutRows = SOLVERS / layoutCols
        panelW = 2f * halfW * 0.96f / layoutCols
        panelH = 1.86f / layoutRows
        cols = env.params.i("size", 13).coerceIn(5, MAX_SIZE)
        cell = min((panelW * 0.9f) / cols, (panelH * 0.86f) / 5f)
        rows = ((panelH * 0.86f) / cell).toInt().coerceIn(5, 200)
        cells = cols * rows
        carve(env)
        // Anywhere not right beside the start, in the top-left.
        val far = (cols + rows) / 5
        // And a goal with one clear winner: on a small maze two solvers often
        // tied, and the first in the list was named the winner of a dead heat.
        var attempt = 0
        do {
            var tries = 0
            do {
                goal = env.rng.int(0, cells)
                tries++
            } while (tries < 200 && (goal % cols + goal / cols) < far)
            for (s in 0 until SOLVERS) solve(env, s)
            val best = (0 until SOLVERS).minOf { length[it] }
            val unique = (0 until SOLVERS).count { length[it] == best } == 1
        } while (!unique && ++attempt < 40)
        winner = (0 until SOLVERS).minByOrNull { length[it] } ?: 0
        // Paced so the winner gets out in about this long, whatever the maze.
        rate = length[winner] / RACE_SECONDS
        shown = 0f
        bestDist.fill(Int.MAX_VALUE)
        elapsed = 0f
        doneAt = -1f
        payoffFrame = false
    }

    /** A perfect maze: one route between any two cells, by random backtracking. */
    private fun carve(env: SimEnv) {
        walls = IntArray(cells) { 3 }
        val seen = BooleanArray(cells)
        val stack = IntArray(cells)
        var top = 0
        stack[top++] = 0
        seen[0] = true
        val nb = IntArray(4)
        while (top > 0) {
            val c = stack[top - 1]
            val x = c % cols; val y = c / cols
            var k = 0
            if (x + 1 < cols && !seen[c + 1]) nb[k++] = c + 1
            if (x > 0 && !seen[c - 1]) nb[k++] = c - 1
            if (y + 1 < rows && !seen[c + cols]) nb[k++] = c + cols
            if (y > 0 && !seen[c - cols]) nb[k++] = c - cols
            if (k == 0) { top--; continue }
            val n = nb[env.rng.int(0, k)]
            when (n) {
                c + 1 -> walls[c] = walls[c] and 1.inv()
                c - 1 -> walls[n] = walls[n] and 1.inv()
                c + cols -> walls[c] = walls[c] and 2.inv()
                else -> walls[n] = walls[n] and 2.inv()
            }
            seen[n] = true
            stack[top++] = n
        }
    }

    private fun open(c: Int, d: Int): Int {
        val x = c % cols; val y = c / cols
        return when (d) {
            0 -> if (x + 1 < cols && walls[c] and 1 == 0) c + 1 else -1
            1 -> if (x > 0 && walls[c - 1] and 1 == 0) c - 1 else -1
            2 -> if (y + 1 < rows && walls[c] and 2 == 0) c + cols else -1
            else -> if (y > 0 && walls[c - cols] and 2 == 0) c - cols else -1
        }
    }

    private fun dist(c: Int): Int = abs(c % cols - goal % cols) + abs(c / cols - goal / cols)

    /** Run solver [s] to the exit, keeping the order it visits cells in. */
    private fun solve(env: SimEnv, s: Int) {
        if (s == WALL) { follow(); return }
        val out = IntArray(cells)
        var count = 0
        val par = IntArray(cells) { -1 }
        val seen = BooleanArray(cells)
        val g = IntArray(cells)
        // A frontier as a plain list; the mazes are small enough that a scan
        // for the best is cheaper than it sounds and much simpler.
        val front = IntArray(cells)
        var fn = 0
        var head = 0
        front[fn++] = 0
        seen[0] = true
        // Depth-first's choice of corridor, shuffled once per maze, so it's
        // luck rather than a fixed bias that decides where it goes first.
        val dirs = intArrayOf(0, 1, 2, 3)
        env.rng.shuffle(dirs)
        while (head < fn || (s >= 2 && fn > 0)) {
            val c: Int = when (s) {
                BFS -> front[head++]
                DFS -> front[--fn]
                else -> {
                    var bi = 0; var bv = Int.MAX_VALUE
                    for (i in 0 until fn) {
                        val f = front[i]
                        val v = dist(f)
                        if (v < bv) { bv = v; bi = i }
                    }
                    val c = front[bi]
                    front[bi] = front[--fn]
                    c
                }
            }
            out[count++] = c
            if (c == goal) break
            for (k in 0 until 4) {
                val n = open(c, dirs[k])
                if (n < 0 || seen[n]) continue
                seen[n] = true
                par[n] = c
                g[n] = g[c] + 1
                front[fn++] = n
            }
            if (s == DFS && head > fn) head = fn
        }
        order[s] = out.copyOf(count)
        parent[s] = par
        length[s] = count
    }

    /**
     * Right hand on the wall, walking. Every step counts, the walks back out
     * of dead ends included; in a maze with one route between any two cells
     * it always gets there.
     */
    private fun follow() {
        val out = IntArray(cells * 4 + 1)
        var count = 0
        val par = IntArray(cells) { -1 }
        val seen = BooleanArray(cells)
        var c = 0
        var facing = 2
        seen[0] = true
        out[count++] = 0
        while (c != goal && count < out.size) {
            // Right, straight on, left, back.
            for (d in intArrayOf(RIGHT[facing], facing, LEFT[facing], BACK[facing])) {
                val n = open(c, d)
                if (n < 0) continue
                if (!seen[n]) { seen[n] = true; par[n] = c }
                c = n
                facing = d
                break
            }
            out[count++] = c
        }
        order[WALL] = out.copyOf(count)
        parent[WALL] = par
        length[WALL] = count
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        if (doneAt >= 0f) return
        val before = shown.toInt()
        shown += h * rate * env.params.f("pace", 1f)
        val now = shown.toInt()
        // A note when a solver gets closer to the exit than it has been:
        // the sound of each one homing in, in its own register.
        for (s in 0 until SOLVERS) {
            val upto = min(now, length[s])
            for (i in before until upto) {
                val d = dist(order[s][i])
                if (d < bestDist[s]) {
                    bestDist[s] = d
                    val closeness = 1f - d.toFloat() / (cols + rows)
                    env.emit(EventKind.STEP, (0.1f + 0.2f * s + 0.25f * closeness).coerceIn(0f, 1f), PAN[s], 0.25f)
                }
            }
        }
        if (now >= length[winner]) {
            doneAt = elapsed
            payoffFrame = true
            env.emit(EventKind.DONE, 0.9f, PAN[winner], 1f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val wallC = Palette.withAlpha(Palette.TEXT_PRIMARY, 0.5f)
        val t = shown.toInt()
        val wall = cell * 0.13f
        val ext = wall * 0.5f
        g.reserveInstances(SOLVERS * (cells * 3 + 16))
        for (s in 0 until SOLVERS) {
            val px = -panelW * layoutCols * 0.5f + panelW * (s % layoutCols) + panelW * 0.5f
            val py = 0.93f - panelH * (s / layoutCols) - panelH * 0.5f
            val ox = px - cols * cell * 0.5f
            val oy = py - rows * cell * 0.5f - 0.02f
            val mw = cols * cell * 0.5f
            val mh = rows * cell * 0.5f
            val mcy = oy + mh
            val c = contender(env, s)
            val lit = Palette.brighten(c, 0.4f)
            val won = doneAt >= 0f && s == winner
            // The winner's maze framed in its colour; a glow behind it used
            // to bleed over the neighbouring mazes.
            if (won) g.rect(px, mcy, mw + cell * 0.45f, mh + cell * 0.45f, c)
            g.rect(px, mcy, mw + (if (won) cell * 0.3f else 0f), mh + (if (won) cell * 0.3f else 0f), 0xFF0D1018.toInt())
            // Visited cells, oldest dim, the newest bright.
            val upto = min(t, length[s])
            val trail = Palette.lerp(c, 0xFF0D1018.toInt(), if (doneAt >= 0f) 0.55f else 0.35f)
            for (i in 0 until upto) {
                val k = order[s][i]
                val x = ox + (k % cols + 0.5f) * cell
                val y = oy + (rows - 1 - k / cols + 0.5f) * cell
                val age = (upto - i).toFloat()
                g.rect(x, y, cell * 0.5f, cell * 0.5f, if (age < 6f && doneAt < 0f) lit else trail)
            }
            // The walls, run on by half their width so corners meet.
            g.line(ox - ext, oy, ox + cols * cell + ext, oy, wall, wallC)
            g.line(ox - ext, oy + rows * cell, ox + cols * cell + ext, oy + rows * cell, wall, wallC)
            g.line(ox, oy - ext, ox, oy + rows * cell + ext, wall, wallC)
            g.line(ox + cols * cell, oy - ext, ox + cols * cell, oy + rows * cell + ext, wall, wallC)
            for (k in 0 until cells) {
                val x = k % cols; val y = rows - 1 - k / cols
                if (walls[k] and 1 != 0 && x + 1 < cols)
                    g.line(ox + (x + 1) * cell, oy + y * cell - ext, ox + (x + 1) * cell, oy + (y + 1) * cell + ext, wall, wallC)
                if (walls[k] and 2 != 0 && k / cols + 1 < rows)
                    g.line(ox + x * cell - ext, oy + y * cell, ox + (x + 1) * cell + ext, oy + y * cell, wall, wallC)
            }
            // Once the race is over, each solver's route out as far as it got
            // — the winner's whole way — as one line, not a string of squares.
            if (doneAt >= 0f && upto > 0) {
                val lw = cell * 0.32f
                val routeC = if (won) Palette.TEXT_PRIMARY else Palette.brighten(c, 0.3f)
                var k = order[s][upto - 1]
                var lx = Float.NaN
                var ly = 0f
                while (k >= 0) {
                    val x = ox + (k % cols + 0.5f) * cell
                    val y = oy + (rows - 1 - k / cols + 0.5f) * cell
                    if (!lx.isNaN()) g.line(lx, ly, x, y, lw, routeC)
                    g.disc(x, y, lw * 0.5f, routeC)
                    lx = x; ly = y
                    k = parent[s][k]
                }
            }
            // The exit, on top.
            val ex = ox + (goal % cols + 0.5f) * cell
            val ey = oy + (rows - 1 - goal / cols + 0.5f) * cell
            g.disc(ex, ey, cell * 0.42f, Palette.SUCCESS)
            g.ring(ex, ey, cell * 0.42f, cell * 0.1f, Palette.TEXT_PRIMARY, alpha = 0.8f)
        }
    }

    private fun contender(env: SimEnv, s: Int): Int = env.palette.at(0.1f + 0.8f * s / (SOLVERS - 1f))

    override fun contenders(env: SimEnv): IntArray = IntArray(SOLVERS) { contender(env, it) }

    override fun winner(): Int = if (doneAt >= 0f) winner else -1

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && elapsed - doneAt > 3.5f

    override fun counters(): List<Pair<String, String>> {
        val t = shown.toInt()
        if (doneAt >= 0f) return listOf("winner" to NAMES[winner], "cells" to length[winner].toString())
        // Who's closest to the exit right now.
        var lead = 0; var ld = Int.MAX_VALUE
        for (s in 0 until SOLVERS) if (bestDist[s] < ld) { ld = bestDist[s]; lead = s }
        return listOf("closest" to NAMES[lead], "cells" to t.toString())
    }

    companion object {
        const val SOLVERS = 4
        const val BFS = 0
        const val DFS = 1
        const val GREEDY = 2
        const val WALL = 3
        // Directions as open() numbers them: 0 east, 1 west, 2 south, 3 north.
        val RIGHT = intArrayOf(2, 3, 1, 0)
        val LEFT = intArrayOf(3, 2, 0, 1)
        val BACK = intArrayOf(1, 0, 3, 2)
        const val MAX_SIZE = 40
        const val RACE_SECONDS = 10f
        val NAMES = arrayOf("breadth-first", "depth-first", "greedy", "wall follower")
        val PAN = floatArrayOf(-0.6f, 0.6f, -0.6f, 0.6f)

        fun def(): SimDef = SimDef(
            id = "algorithms/maze-race",
            title = "Maze race",
            shelf = Shelf.ALGORITHMS,
            family = "Search",
            blurb = "Four ways out of the same maze. One cell a tick each. Who's out first?",
            formats = listOf(TrapFormat.RACE, TrapFormat.GUESS),
            heroLabel = "New maze",
            defaultPalette = "Spectrum",
            typicalSeconds = 12f,
            params = listOf(
                Param.int("size", "Maze width", 5, MAX_SIZE, 13, card = Card.DATA),
                Param.float("pace", "Pace", 0.2f, 5f, 1f, card = Card.MOTION)
            ),
            create = { MazeRaceSim() }
        )
    }
}
