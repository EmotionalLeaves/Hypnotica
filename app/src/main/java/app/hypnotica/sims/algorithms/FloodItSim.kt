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
 * The flood puzzle: a board of coloured squares, and one move — repaint the
 * patch you hold, from the top-left corner, in another colour, so it swallows
 * every neighbour already that colour. One colour across the whole board wins.
 *
 * Played greedily, a move ahead: each turn takes the colour that swallows the
 * most squares, ties going to the one that leaves the longest edge to grow
 * from. Each swallowed square changes a moment after the one before it, by
 * its distance from the patch, so a move pours across the board as a wave.
 */
class FloodItSim : Sim {

    private var n = 14
    private var k = 6
    private var cell = 0.05f
    private var ox = 0f
    private var oy = 0f
    private var colour = IntArray(0)
    private var owned = BooleanArray(0)
    private var turnedAt = FloatArray(0)
    private var from = IntArray(0)
    private var queue = IntArray(0)
    private var dist = IntArray(0)
    private var seen = BooleanArray(0)
    private var gain = IntArray(0)
    private var edge = IntArray(0)

    private var current = 0
    private var moves = 0
    private var ownedCount = 0
    private var waveT = 0f
    private var waveLen = 0f
    private var thinking = 0f
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var finishPulse = 0f

    override fun setup(env: SimEnv) {
        n = env.params.i("size", 14).coerceIn(4, MAX_N)
        k = env.params.i("colours", 6).coerceIn(2, MAX_K)
        cell = min(2f * env.halfWidth * 0.94f / n, 1.6f / n)
        ox = -n * cell * 0.5f
        oy = -n * cell * 0.5f
        val total = n * n
        colour = IntArray(total) { env.rng.int(0, k) }
        from = colour.copyOf()
        owned = BooleanArray(total)
        turnedAt = FloatArray(total) { -1f }
        queue = IntArray(total)
        dist = IntArray(total)
        seen = BooleanArray(total)
        gain = IntArray(k)
        edge = IntArray(k)
        current = colour[0]
        moves = 0
        elapsed = 0f; doneAt = -1f; payoffFrame = false
        finishPulse = 0f
        waveT = 0f; waveLen = 0f
        thinking = THINK_S
        // The patch starts as everything already joined to the corner.
        absorb(current, null)
        for (i in 0 until total) if (owned[i]) turnedAt[i] = 0f
    }

    /**
     * Repaint the patch [c] and take in every neighbour of that colour, by
     * breadth-first search from the patch's edge; records each new square's
     * distance so the wave can be timed. Returns how many it took.
     */
    private fun absorb(c: Int, env: SimEnv?): Int {
        val total = n * n
        seen.fill(false)
        var head = 0; var tail = 0
        for (i in 0 until total) if (owned[i] || i == 0) { seen[i] = true; dist[i] = 0; queue[tail++] = i; owned[i] = true }
        var took = 0
        var far = 0
        while (head < tail) {
            val i = queue[head++]
            val x = i % n; val y = i / n
            for (d in 0 until 4) {
                val nx = x + DX[d]; val ny = y + DY[d]
                if (nx < 0 || ny < 0 || nx >= n || ny >= n) continue
                val j = ny * n + nx
                if (seen[j] || colour[j] != c) continue
                seen[j] = true
                owned[j] = true
                dist[j] = dist[i] + 1
                far = max(far, dist[j])
                queue[tail++] = j
                took++
            }
        }
        for (i in 0 until total) if (owned[i]) { from[i] = colour[i]; colour[i] = c }
        ownedCount = 0
        for (i in 0 until total) if (owned[i]) ownedCount++
        if (env != null) {
            // The patch's own squares change first, then the swallowed ones by
            // how far in they are.
            for (i in 0 until total) {
                if (!owned[i]) continue
                turnedAt[i] = elapsed + (if (dist[i] == 0) 0f else dist[i] * WAVE_STEP)
            }
            waveLen = (far + 1) * WAVE_STEP + POP_S
        }
        return took
    }

    /** The greedy choice: most squares swallowed, then the longest edge left. */
    private fun choose(): Int {
        val total = n * n
        gain.fill(0); edge.fill(0)
        seen.fill(false)
        // Each colour's gain is the size of the regions of that colour touching
        // the patch, counted by flooding each such region once.
        for (i in 0 until total) {
            if (!owned[i]) continue
            val x = i % n; val y = i / n
            for (d in 0 until 4) {
                val nx = x + DX[d]; val ny = y + DY[d]
                if (nx < 0 || ny < 0 || nx >= n || ny >= n) continue
                val j = ny * n + nx
                if (owned[j] || seen[j]) continue
                val c = colour[j]
                var head = 0; var tail = 0
                queue[tail++] = j; seen[j] = true
                while (head < tail) {
                    val a = queue[head++]
                    gain[c]++
                    val ax = a % n; val ay = a / n
                    for (e in 0 until 4) {
                        val bx = ax + DX[e]; val by = ay + DY[e]
                        if (bx < 0 || by < 0 || bx >= n || by >= n) continue
                        val b = by * n + bx
                        if (owned[b]) continue
                        if (colour[b] != c) { edge[c]++; continue }
                        if (seen[b]) continue
                        seen[b] = true
                        queue[tail++] = b
                    }
                }
            }
        }
        var best = -1
        for (c in 0 until k) {
            if (c == current || gain[c] == 0) continue
            if (best < 0 || gain[c] > gain[best] || (gain[c] == gain[best] && edge[c] > edge[best])) best = c
        }
        return if (best < 0) (current + 1) % k else best
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val a = abs(dt)
        elapsed += a
        finishPulse = max(0f, finishPulse - a * 0.8f)
        val speed = env.params.f("speed", 1f).coerceIn(0.1f, 8f)
        if (doneAt >= 0f) return
        if (waveT < waveLen) {
            waveT += a
            // The squares sound as they turn, a handful to a note, pitched by
            // how far into the board the wave has reached.
            var turned = 0
            var reach = 0
            for (i in turnedAt.indices) {
                val t = turnedAt[i]
                if (t > elapsed - a && t <= elapsed && dist[i] > 0) { turned++; reach = max(reach, dist[i]) }
            }
            if (turned > 0) env.emit(EventKind.STEP, (0.2f + reach * 0.03f).coerceAtMost(1f), 0f, min(0.4f, 0.1f + turned * 0.03f))
            return
        }
        thinking -= a * speed
        if (thinking > 0f) return
        if (ownedCount == n * n) {
            doneAt = elapsed
            payoffFrame = true
            finishPulse = 1f
            env.emit(EventKind.DONE, 0.9f, 0f, 1f)
            env.callout("In $moves moves!", big = true)
            return
        }
        val c = choose()
        current = c
        moves++
        val took = absorb(c, env)
        waveT = 0f
        // Scaled by speed after the fact, so the wave keeps its shape.
        waveLen /= speed
        for (i in turnedAt.indices) if (turnedAt[i] > elapsed) turnedAt[i] = elapsed + (turnedAt[i] - elapsed) / speed
        thinking = THINK_S
        env.emit(EventKind.WRITE, (c + 0.5f) / k, 0f, 0.35f + min(0.5f, took / 40f))
        if (took >= n * n / 6 && took >= BIG_GULP) env.callout("Big grab! +$took")
        if (took >= BIG_GULP) env.emit(EventKind.CROSS, 0.5f + 0.4f * min(1f, took / (n * n * 0.25f)), 0f, 0.6f)
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        val half = cell * 0.46f
        for (y in 0 until n) for (x in 0 until n) {
            val i = y * n + x
            val cx = ox + (x + 0.5f) * cell
            // Row 0 at the top, as the corner is top-left.
            val cy = -oy - (y + 0.5f) * cell
            val t = turnedAt[i]
            val since = elapsed - t
            val target = colourOf(pal, colour[i])
            if (owned[i] && t >= 0f && since >= 0f && since < POP_S) {
                // Turning now: the new colour pops in over the old.
                val p = since / POP_S
                g.rect(cx, cy, half, half, colourOf(pal, from[i]))
                val s = half * (0.4f + 0.75f * p - 0.15f * p * p)
                g.rect(cx, cy, s, s, Palette.brighten(target, 0.35f * (1f - p)))
            } else if (owned[i] && t > elapsed) {
                g.rect(cx, cy, half, half, colourOf(pal, from[i]))
            } else {
                g.rect(cx, cy, half, half, if (owned[i]) target else Palette.lerp(target, Palette.BG, 0.18f))
            }
        }
        if (finishPulse > 0f) g.glow(0f, 0f, n * cell * 0.8f, colourOf(pal, current), finishPulse * 0.4f)
    }

    private fun colourOf(pal: app.hypnotica.core.render.Palette, c: Int): Int = pal.at(0.04f + c / max(1, k - 1).toFloat() * 0.9f)

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && elapsed - doneAt > 3f

    /** Fewest moves to one colour: fewer is better. */
    override fun scoreForBest(): Float = if (ownedCount == n * n) -moves.toFloat() else Float.NaN
    override fun scoreForBestText(value: Float): String = "${(-value).toInt()} moves"

    override fun counters(): List<Pair<String, String>> = listOf(
        "moves" to "$moves",
        "flooded" to "${ownedCount * 100 / (n * n)}%"
    )

    companion object {
        const val MAX_N = 48
        const val MAX_K = 8
        const val THINK_S = 0.18f
        const val WAVE_STEP = 0.035f
        const val POP_S = 0.22f
        const val BIG_GULP = 12
        private val DX = intArrayOf(1, -1, 0, 0)
        private val DY = intArrayOf(0, 0, 1, -1)

        fun def(): SimDef = SimDef(
            id = "algorithms/flood-it",
            title = "Flood it",
            shelf = Shelf.ALGORITHMS,
            family = "Puzzles",
            blurb = "Repaint the corner, swallow the neighbours, until the board is one colour.",
            formats = listOf(TrapFormat.FILL, TrapFormat.CHAIN, TrapFormat.NUMBER_UP),
            heroLabel = "New board",
            defaultPalette = "Candy",
            typicalSeconds = 18f,
            params = listOf(
                Param.int("size", "Board size", 4, MAX_N, 14, card = Card.DATA),
                Param.int("colours", "Colours", 2, MAX_K, 6, card = Card.DATA),
                Param.float("speed", "Speed", 0.3f, 5f, 1f, card = Card.MOTION, logScale = true)
            ),
            create = { FloodItSim() }
        )
    }
}
