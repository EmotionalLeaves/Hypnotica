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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Carve a maze, then find the way through it.
 *
 * Two algorithms back to back make the arc on their own: the carve is the build,
 * the moment the search touches the exit is the payoff, and the traced path is
 * the resolve.
 */
private const val WALL_N = 1
private const val WALL_E = 2
private const val WALL_S = 4
private const val WALL_W = 8
private val DX = intArrayOf(0, 1, 0, -1)
private val DY = intArrayOf(-1, 0, 1, 0)
private val BIT = intArrayOf(WALL_N, WALL_E, WALL_S, WALL_W)
private val OPPOSITE = intArrayOf(WALL_S, WALL_W, WALL_N, WALL_E)

class MazeSim : Sim {

    private var w = 24
    private var h = 36
    private var cells = IntArray(0)
    private var visited = BooleanArray(0)
    private var stack = IntArray(0)
    private var sp = 0
    private var carving = true
    private var carved = 0

    // Solving
    private var dist = IntArray(0)
    private var frontier = IntArray(0)
    private var frontierSize = 0
    private var nextFrontier = IntArray(0)
    private var nextSize = 0
    private var cameFrom = IntArray(0)
    private var solved = false
    private var pathCells = IntArray(0)
    private var pathLength = 0
    private var revealed = 0f

    private var payoffFrame = false
    private var carry = 0f
    private var restSeconds = -1f

    override fun setup(env: SimEnv) {
        val p = env.params
        w = p.i("width", 24).coerceIn(5, 120)
        h = p.i("height", 36).coerceIn(5, 160)
        val n = w * h
        cells = IntArray(n)
        visited = BooleanArray(n)
        stack = IntArray(n)
        dist = IntArray(n) { -1 }
        cameFrom = IntArray(n) { -1 }
        frontier = IntArray(n)
        nextFrontier = IntArray(n)
        pathCells = IntArray(n)
        sp = 0
        stack[sp++] = 0
        visited[0] = true
        carving = true
        carved = 1
        solved = false
        frontierSize = 0
        nextSize = 0
        pathLength = 0
        revealed = 0f
        payoffFrame = false
        carry = 0f
        restSeconds = -1f
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        if (restSeconds >= 0f) {
            restSeconds -= abs(dt)
            return
        }
        carry += abs(dt) * env.params.f("rate", 140f)
        var budget = min(carry.toInt(), 20000)
        carry -= budget
        while (budget-- > 0) {
            if (carving) {
                if (!carveStep(env)) {
                    carving = false
                    startSolve()
                }
            } else if (!solved) {
                if (!solveStep(env)) {
                    solved = true
                    payoffFrame = true
                    env.emit(EventKind.DONE, 1f, 0f, 1f)
                    tracePath()
                }
            } else {
                val before = revealed.toInt()
                revealed += 0.6f
                // The solution path sings as it is drawn, rising as it goes. The reveal is the maze's best moment and it was
                // silent — five seconds at the shipped speed, the longest quiet
                // stretch of any sim still moving. The mapping's own budget
                // folds a fast reveal into fewer, fuller notes.
                if (revealed.toInt() > before && pathLength > 0) {
                    val at = revealed.toInt().coerceAtMost(pathLength - 1)
                    // The same cell the drawing has just reached: it reveals
                    // pathCells in order, which runs from the goal back.
                    val cell = pathCells[at]
                    env.emit(
                        EventKind.WRITE,
                        at.toFloat() / pathLength,
                        ((cell % w).toFloat() / w) * 2f - 1f,
                        0.35f
                    )
                }
                if (revealed >= pathLength) {
                    restSeconds = 1.5f
                    break
                }
            }
        }
    }

    /** Reused by [carveStep]; a four-int array per step adds up at 120 Hz. */
    private val candidateScratch = IntArray(4)

    /** Recursive backtracker, with the recursion written out as a stack. */
    private fun carveStep(env: SimEnv): Boolean {
        if (sp == 0) return false
        val current = stack[sp - 1]
        val cx = current % w
        val cy = current / w
        var options = 0
        val candidates = candidateScratch
        for (d in 0 until 4) {
            val nx = cx + DX[d]
            val ny = cy + DY[d]
            if (nx in 0 until w && ny in 0 until h && !visited[ny * w + nx]) {
                candidates[options++] = d
            }
        }
        if (options == 0) {
            sp--
            return sp > 0
        }
        val d = candidates[env.rng.int(0, options)]
        val nx = cx + DX[d]
        val ny = cy + DY[d]
        val next = ny * w + nx
        cells[current] = cells[current] or BIT[d]
        cells[next] = cells[next] or OPPOSITE[d]
        visited[next] = true
        stack[sp++] = next
        carved++
        env.emit(EventKind.WRITE, sp.toFloat() / (w * h), (cx.toFloat() / w) * 2f - 1f, 0.4f)
        return true
    }

    private fun startSolve() {
        frontierSize = 0
        frontier[frontierSize++] = 0
        dist[0] = 0
    }

    /** Breadth-first flood, one ring at a time. */
    private fun solveStep(env: SimEnv): Boolean {
        if (frontierSize == 0) return false
        nextSize = 0
        val goal = w * h - 1
        for (i in 0 until frontierSize) {
            val c = frontier[i]
            if (c == goal) return false
            val cx = c % w
            val cy = c / w
            for (d in 0 until 4) {
                if (cells[c] and BIT[d] == 0) continue
                val nx = cx + DX[d]
                val ny = cy + DY[d]
                if (nx !in 0 until w || ny !in 0 until h) continue
                val n = ny * w + nx
                if (dist[n] >= 0) continue
                dist[n] = dist[c] + 1
                cameFrom[n] = c
                nextFrontier[nextSize++] = n
            }
        }
        val tmp = frontier
        System.arraycopy(nextFrontier, 0, tmp, 0, nextSize)
        frontierSize = nextSize
        if (frontierSize > 0) {
            env.emit(EventKind.COMPARE, (dist[frontier[0]].toFloat() / (w + h)).coerceIn(0f, 1f), 0f, 0.4f)
        }
        return frontierSize > 0
    }

    private fun tracePath() {
        var c = w * h - 1
        pathLength = 0
        var guard = w * h + 2
        while (c >= 0 && guard-- > 0) {
            pathCells[pathLength++] = c
            c = cameFrom[c]
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val halfW = env.halfWidth * 0.95f
        val cellW = (2f * halfW) / w
        val cellH = 1.9f / h
        val size = min(cellW, cellH)
        val originX = -size * w * 0.5f
        val originY = -size * h * 0.5f
        val wall = max(size * 0.1f, 0.0016f)

        // Visited cells as a soft fill, so the carve reads as a growing shape.
        for (i in 0 until w * h) {
            if (!visited[i]) continue
            val cx = originX + (i % w + 0.5f) * size
            val cy = originY + (i / w + 0.5f) * size
            val d = dist[i]
            val c = when {
                solved -> Palette.withAlpha(palette.at(0.2f), 0.18f)
                d >= 0 -> Palette.withAlpha(palette.at((d.toFloat() / (w + h)).coerceIn(0f, 1f)), 0.75f)
                else -> Palette.withAlpha(palette.at(0.15f), 0.16f)
            }
            g.rect(cx, cy, size * 0.48f, size * 0.48f, c)
        }

        // Walls: a line wherever a cell has no opening.
        for (i in 0 until w * h) {
            val cx = originX + (i % w) * size
            val cy = originY + (i / w) * size
            val c = Palette.withAlpha(Palette.TEXT_SECONDARY, 0.55f)
            if (cells[i] and WALL_N == 0) g.line(cx, cy, cx + size, cy, wall, c)
            if (cells[i] and WALL_W == 0) g.line(cx, cy, cx, cy + size, wall, c)
        }
        g.line(originX, originY + size * h, originX + size * w, originY + size * h, wall, Palette.withAlpha(Palette.TEXT_SECONDARY, 0.55f))
        g.line(originX + size * w, originY, originX + size * w, originY + size * h, wall, Palette.withAlpha(Palette.TEXT_SECONDARY, 0.55f))

        // Carving head.
        if (carving && sp > 0) {
            val c = stack[sp - 1]
            g.glow(originX + (c % w + 0.5f) * size, originY + (c / w + 0.5f) * size, size * 2.2f, Palette.ACCENT, 0.7f)
        }

        // The solution, drawn in as it is revealed.
        if (solved) {
            val show = min(pathLength, revealed.toInt())
            for (k in 0 until show) {
                val c = pathCells[k]
                val x = originX + (c % w + 0.5f) * size
                val y = originY + (c / w + 0.5f) * size
                g.disc(x, y, size * 0.3f, Palette.SUCCESS)
            }
            if (show > 0) {
                val c = pathCells[min(show, pathLength - 1)]
                g.glow(
                    originX + (c % w + 0.5f) * size,
                    originY + (c / w + 0.5f) * size,
                    size * 2.6f, Palette.SUCCESS, 0.8f
                )
            }
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = solved && revealed >= pathLength && restSeconds in -1000f..0f

    override fun counters(): List<Pair<String, String>> = listOf(
        "cells" to "${w * h}",
        if (carving) ("carved" to carved.toString()) else ("path" to pathLength.toString())
    )

    companion object Factory {
        fun def(): SimDef = SimDef(
            id = "algorithms/maze",
            title = "Maze, carved and solved",
            shelf = Shelf.ALGORITHMS,
            family = "Maze generation & solving",
            blurb = "A backtracker carves it, a flood finds the way out.",
            formats = listOf(TrapFormat.GROW, TrapFormat.PERFECT_FIT, TrapFormat.REVEAL, TrapFormat.ESCAPE),
            heroLabel = "New maze",
            defaultPalette = "Ocean",
            typicalSeconds = 28f,
            params = listOf(
                Param.int("width", "Columns", 5, 120, 24, card = Card.DATA),
                Param.int("height", "Rows", 5, 160, 36, card = Card.DATA),
                Param.float("rate", "Speed", 5f, 2000f, 140f, card = Card.MOTION, unit = "cells/s", logScale = true)
            ),
            create = { MazeSim() }
        )
    }
}

/**
 * Four search algorithms on the same grid, so the difference between them is the
 * shape of what they explore rather than a paragraph of explanation.
 */
class PathfindingSim : Sim {

    private var w = 40
    private var h = 60
    private var blocked = BooleanArray(0)
    private var dist = FloatArray(0)
    private var heuristicWeight = 0f
    private var cameFrom = IntArray(0)
    private var open = IntArray(0)
    private var openSize = 0
    private var closed = BooleanArray(0)
    private var start = 0
    private var goal = 0
    private var found = false
    private var pathCells = IntArray(0)
    private var terrain = FloatArray(0)
    private var weighted = false
    private var pathLength = 0
    private var revealed = 0f
    private var visitedCount = 0
    private var payoffFrame = false
    private var carry = 0f
    private var restSeconds = -1f
    private var depthFirst = false

    override fun setup(env: SimEnv) {
        val p = env.params
        w = p.i("width", 60).coerceIn(8, 160)
        h = p.i("height", 90).coerceIn(8, 220)
        val n = w * h
        blocked = BooleanArray(n)
        dist = FloatArray(n) { Float.MAX_VALUE }
        cameFrom = IntArray(n) { -1 }
        open = IntArray(n)
        closed = BooleanArray(n)
        pathCells = IntArray(n)

        when (p.choiceIndex("algorithm")) {
            0 -> { heuristicWeight = 0f; depthFirst = false; weighted = false } // Breadth-first
            1 -> { heuristicWeight = 0f; depthFirst = true; weighted = false }  // Depth-first
            // Dijkstra is breadth-first with costs, so the ground has costs:
            // without them it was the same search under a different label.
            2 -> { heuristicWeight = 0f; depthFirst = false; weighted = true }  // Dijkstra
            else -> { heuristicWeight = 1f; depthFirst = false; weighted = false } // A*
        }
        terrain = FloatArray(n) {
            if (!weighted) 1f
            else 1f + 5f * (env.rng.nextFloat() * env.rng.nextFloat())
        }

        val density = p.f("walls", 0.3f)
        for (i in 0 until n) blocked[i] = env.rng.nextFloat() < density
        start = 0
        goal = n - 1
        blocked[start] = false
        blocked[goal] = false
        // Carve a guaranteed corridor so there is always a route.
        var cx = 0
        var cy = 0
        while (cx < w - 1 || cy < h - 1) {
            if (cx < w - 1 && (cy == h - 1 || env.rng.bool())) cx++ else cy++
            blocked[cy * w + cx] = false
        }

        openSize = 0
        open[openSize++] = start
        dist[start] = 0f
        found = false
        pathLength = 0
        revealed = 0f
        visitedCount = 0
        payoffFrame = false
        carry = 0f
        restSeconds = -1f
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        if (restSeconds >= 0f) {
            restSeconds -= abs(dt)
            return
        }
        carry += abs(dt) * env.params.f("rate", 150f)
        var budget = min(carry.toInt(), 40000)
        carry -= budget
        while (budget-- > 0) {
            if (!found) {
                if (!expand(env)) {
                    found = true
                    payoffFrame = true
                    env.emit(EventKind.DONE, 1f, 0f, 1f)
                    trace()
                }
            } else {
                val before = revealed.toInt()
                revealed += 0.7f
                // The route sings as it is drawn, as the maze's does: the
                // reveal was three and a half silent seconds at the end.
                if (revealed.toInt() > before && pathLength > 0) {
                    val at = revealed.toInt().coerceAtMost(pathLength - 1)
                    val cell = pathCells[at]
                    env.emit(
                        EventKind.WRITE,
                        at.toFloat() / pathLength,
                        ((cell % w).toFloat() / w) * 2f - 1f,
                        0.35f
                    )
                }
                if (revealed >= pathLength) {
                    restSeconds = 1.5f
                    break
                }
            }
        }
    }

    private fun score(cell: Int): Float {
        if (heuristicWeight <= 0f) return dist[cell]
        val cx = cell % w
        val cy = cell / w
        val gx = goal % w
        val gy = goal / w
        return dist[cell] + heuristicWeight * (abs(gx - cx) + abs(gy - cy))
    }

    private fun expand(env: SimEnv): Boolean {
        if (openSize == 0) return false
        // Depth-first takes the newest, the others take the cheapest.
        var pick = openSize - 1
        if (!depthFirst) {
            var best = Float.MAX_VALUE
            for (i in 0 until openSize) {
                val s = score(open[i])
                if (s < best) { best = s; pick = i }
            }
        }
        val current = open[pick]
        open[pick] = open[--openSize]
        if (closed[current]) return true
        closed[current] = true
        visitedCount++
        if (current == goal) return false

        val cx = current % w
        val cy = current / w
        env.emit(
            EventKind.COMPARE,
            1f - (abs(goal % w - cx) + abs(goal / w - cy)).toFloat() / (w + h),
            (cx.toFloat() / w) * 2f - 1f,
            0.35f
        )
        for (d in 0 until 4) {
            val nx = cx + DX[d]
            val ny = cy + DY[d]
            if (nx !in 0 until w || ny !in 0 until h) continue
            val n = ny * w + nx
            if (blocked[n] || closed[n]) continue
            val cost = dist[current] + terrain[n]
            if (cost < dist[n]) {
                dist[n] = cost
                cameFrom[n] = current
                if (openSize >= open.size) {
                    // The list holds one entry per cell; a cell can be offered
                    // more than once as a cheaper route is found, so it grows
                    // rather than dropping the better route on the floor.
                    open = open.copyOf(open.size * 2)
                }
                open[openSize++] = n
            }
        }
        return true
    }

    private fun trace() {
        var c = goal
        pathLength = 0
        var guard = w * h + 2
        while (c >= 0 && guard-- > 0) {
            pathCells[pathLength++] = c
            c = cameFrom[c]
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val halfW = env.halfWidth * 0.95f
        val size = min((2f * halfW) / w, 1.9f / h)
        val originX = -size * w * 0.5f
        val originY = -size * h * 0.5f
        val usePoints = w * h > 6000
        if (usePoints) g.reservePoints(w * h) else g.reserveInstances(w * h)

        for (i in 0 until w * h) {
            val x = originX + (i % w + 0.5f) * size
            val y = originY + (i / w + 0.5f) * size
            val c = when {
                blocked[i] -> Palette.withAlpha(Palette.TEXT_SECONDARY, 0.2f)
                closed[i] -> palette.at((dist[i] / (w + h)).coerceIn(0f, 1f))
                dist[i] < Float.MAX_VALUE -> Palette.withAlpha(Palette.ACCENT, 0.7f)
                else -> continue
            }
            if (usePoints) g.point(x, y, size, c) else g.rect(x, y, size * 0.45f, size * 0.45f, c)
        }

        if (found) {
            val show = min(pathLength, revealed.toInt())
            for (k in 0 until show) {
                val c = pathCells[k]
                g.disc(originX + (c % w + 0.5f) * size, originY + (c / w + 0.5f) * size, size * 0.42f, Palette.SUCCESS)
            }
        }
        g.disc(originX + (start % w + 0.5f) * size, originY + (start / w + 0.5f) * size, size * 0.6f, Palette.SUCCESS)
        g.glow(originX + (goal % w + 0.5f) * size, originY + (goal / w + 0.5f) * size, size * 3f, Palette.WARNING, 0.8f)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = found && revealed >= pathLength && restSeconds in -1000f..0f

    override fun counters(): List<Pair<String, String>> = listOf(
        "visited" to visitedCount.toString(),
        "path" to pathLength.toString()
    )

    companion object Factory {
        fun def(): SimDef = SimDef(
            id = "algorithms/pathfinding",
            title = "Finding the way",
            shelf = Shelf.ALGORITHMS,
            family = "Pathfinding & search",
            blurb = "Breadth-first spreads, A* goes straight for it.",
            formats = listOf(TrapFormat.GUESS, TrapFormat.REVEAL, TrapFormat.RACE, TrapFormat.ESCAPE),
            heroLabel = "New map",
            defaultPalette = "Aurora",
            typicalSeconds = 10f,
            params = listOf(
                Param.choice(
                    "algorithm", "Algorithm",
                    listOf("Breadth-first", "Depth-first", "Dijkstra", "A*"), 3,
                    card = Card.MOTION, input = ParamInput.CHIPS
                ),
                Param.int("width", "Columns", 8, 160, 60, card = Card.DATA),
                Param.int("height", "Rows", 8, 220, 90, card = Card.DATA),
                Param.float("walls", "Wall density", 0f, 0.45f, 0.3f, card = Card.DATA),
                Param.float("rate", "Speed", 10f, 1500f, 150f, card = Card.MOTION, unit = "cells/s", logScale = true)
            ),
            create = { PathfindingSim() }
        )
    }
}

object MazeSims {
    fun defs(): List<SimDef> = listOf(MazeSim.def(), PathfindingSim.def())
}
