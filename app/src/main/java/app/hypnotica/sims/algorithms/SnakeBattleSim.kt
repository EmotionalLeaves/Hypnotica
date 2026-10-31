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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Snake battle: four snakes on one board, all after the same apples. Each
 * steers itself — towards the nearest apple, away from walls, other bodies
 * and any pocket too small to fit in — and a snake that runs into anything
 * is out, its body left behind as a trail of apples for the rest. Two heads
 * meeting: the longer one wins; a tie takes both. Past half a minute the
 * walls start closing in, so it always ends.
 *
 * Pick a colour before it starts.
 */
class SnakeBattleSim : Sim {

    private var cols = 24
    private var rows = 40
    private var cell = 0.04f
    private var ox = 0f
    private var oy = 0f
    private var snakes = 4

    // Bodies as ring buffers of cell indices, head at [head], length [len].
    private val body = Array(MAX_SNAKES) { IntArray(MAX_CELLS) }
    private val head = IntArray(MAX_SNAKES)
    private val len = IntArray(MAX_SNAKES)
    private val dir = IntArray(MAX_SNAKES)
    private val alive = BooleanArray(MAX_SNAKES)
    private val grow = IntArray(MAX_SNAKES)
    private val outAt = FloatArray(MAX_SNAKES)
    private val eaten = IntArray(MAX_SNAKES)
    private var aliveCount = 0

    /** Who is in each cell: -1 empty, else the snake's index. */
    private var owner = IntArray(0)
    private var food = BooleanArray(0)
    private var foodCount = 0
    private var wantFood = 6
    /** How hard they go for apples against how careful they are, set per battle. */
    private var greed = 1f

    /** The border: cells less than [inset] from an edge are wall. */
    private var inset = 0
    private var shrinkClock = 0f

    private var moveClock = 0f
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var winner = -1
    private var longest = 0
    private var moves = 0
    private var eatFlash = FloatArray(MAX_SNAKES)
    private val seen = BooleanArray(MAX_CELLS)
    private val queue = IntArray(MAX_CELLS)
    private var calledClosing = false

    override fun setup(env: SimEnv) {
        val hw = env.halfWidth * 0.96f
        cols = env.params.i("size", 22).coerceIn(12, 40)
        cell = 2f * hw / cols
        rows = min(MAX_ROWS, floor(1.9f / cell).toInt())
        while (cols * rows > MAX_CELLS) rows--
        ox = -cols * cell * 0.5f
        oy = -rows * cell * 0.5f
        snakes = env.params.i("snakes", 4).coerceIn(2, MAX_SNAKES)
        wantFood = env.params.i("apples", 6).coerceIn(1, 30)
        greed = env.params.f("greed", 1f).coerceIn(0f, 3f)
        owner = IntArray(cols * rows) { -1 }
        food = BooleanArray(cols * rows)
        foodCount = 0
        inset = 0; shrinkClock = 0f; calledClosing = false
        moveClock = 0f; elapsed = 0f; doneAt = -1f; payoffFrame = false; winner = -1; longest = 0; moves = 0
        eatFlash = FloatArray(MAX_SNAKES)
        aliveCount = snakes
        // Spread round the board, each pointing in towards the middle.
        // Colours dealt to places at random, so no colour owns a corner.
        val slot = IntArray(snakes) { it }
        for (i in snakes - 1 downTo 1) { val j = env.rng.int(0, i + 1); val t = slot[i]; slot[i] = slot[j]; slot[j] = t }
        for (s in 0 until snakes) {
            val a = (slot[s] + 0.5f) / snakes * 6.2831855f + env.rng.range(-0.2f, 0.2f)
            val cx = ((0.5f + 0.32f * kotlin.math.cos(a)) * cols).toInt().coerceIn(2, cols - 3)
            val cy = ((0.5f + 0.36f * kotlin.math.sin(a)) * rows).toInt().coerceIn(2, rows - 3)
            dir[s] = if (abs(kotlin.math.cos(a)) > abs(kotlin.math.sin(a))) (if (kotlin.math.cos(a) > 0f) LEFT else RIGHT) else (if (kotlin.math.sin(a) > 0f) DOWN else UP)
            len[s] = 0; head[s] = 0; grow[s] = START_LEN - 1
            alive[s] = true; outAt[s] = -1f; eaten[s] = 0
            val c = cy * cols + cx
            if (owner[c] >= 0) { alive[s] = false; aliveCount--; continue }
            body[s][0] = c; len[s] = 1; owner[c] = s
        }
        while (foodCount < wantFood) placeFood(env)
    }

    private fun placeFood(env: SimEnv) {
        repeat(200) {
            val x = env.rng.int(inset, cols - inset)
            val y = env.rng.int(inset, rows - inset)
            val c = y * cols + x
            if (owner[c] < 0 && !food[c]) { food[c] = true; foodCount++; return }
        }
        foodCount = wantFood // Board too full: stop trying.
    }

    private fun inside(x: Int, y: Int) = x >= inset && y >= inset && x < cols - inset && y < rows - inset

    private fun stepCell(c: Int, d: Int): Int {
        val x = c % cols + DX[d]; val y = c / cols + DY[d]
        return if (inside(x, y)) y * cols + x else -1
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        for (s in 0 until snakes) eatFlash[s] = max(0f, eatFlash[s] - h * 4f)
        // Once it is decided the board holds still for the winner's moment:
        // left to roam a closed-in board, the winner crashed on its lap of honour.
        if (doneAt >= 0f) return
        // Faster as the field thins, and faster still with the pace knob.
        val pace = env.params.f("pace", 1f).coerceIn(0.2f, 4f)
        val rate = (BASE_RATE + RATE_PER_OUT * (snakes - aliveCount)) * pace
        moveClock += h * rate
        var n = 0
        while (moveClock >= 1f && n < 4) { moveClock -= 1f; move(env); n++ }

        // The walls close in past [CLOSE_AT], a ring at a time.
        if (doneAt < 0f && elapsed > CLOSE_AT) {
            if (!calledClosing) { calledClosing = true; env.callout("Walls closing!") }
            shrinkClock += h
            if (shrinkClock > CLOSE_EVERY && inset < min(cols, rows) / 2 - 2) {
                shrinkClock = 0f
                inset++
                for (c in food.indices) if (food[c] && !inside(c % cols, c / cols)) { food[c] = false; foodCount-- }
                // A head the wall closes over is out; a body loses what is
                // behind the first cell caught.
                var caught = 0
                for (s in 0 until snakes) if (alive[s]) {
                    var k = 0
                    while (k < len[s]) { val c = cellAt(s, k); if (!inside(c % cols, c / cols)) break; k++ }
                    if (k == 0) { kill(env, s); caught++ }
                    else if (k < len[s]) {
                        for (j in k until len[s]) { val c = cellAt(s, j); if (owner[c] == s) owner[c] = -1 }
                        len[s] = k
                    }
                }
                settle(env, caught, false, true)
                env.emit(EventKind.STEP, 0.2f, 0f, 0.5f)
            }
        }
    }

    private fun move(env: SimEnv) {
        if (aliveCount == 0) return
        moves++
        // Everyone chooses first, then everyone moves.
        val next = IntArray(snakes) { -1 }
        for (s in 0 until snakes) if (alive[s]) { dir[s] = choose(env, s, greed); next[s] = stepCell(headOf(s), dir[s]) }
        // Tails leave first (unless growing), so following a tail is safe.
        for (s in 0 until snakes) if (alive[s] && grow[s] == 0 && next[s] >= 0 && !food[next[s]]) {
            val tail = tailOf(s)
            if (owner[tail] == s) owner[tail] = -1
        }
        val out = BooleanArray(snakes)
        for (s in 0 until snakes) if (alive[s]) {
            val c = next[s]
            if (c < 0) { out[s] = true; continue }
            val o = owner[c]
            if (o >= 0) { out[s] = true; continue }
            // Head to head into the same cell: the longer one goes through.
            for (t in 0 until snakes) if (t != s && alive[t] && next[t] == c) {
                if (len[t] >= len[s]) out[s] = true
            }
        }
        var outs = 0
        var headOn = false
        for (s in 0 until snakes) if (alive[s] && out[s]) {
            outs++
            for (t in 0 until snakes) if (t != s && alive[t] && next[t] == next[s] && next[s] >= 0) headOn = true
        }
        // Survivors advance.
        for (s in 0 until snakes) if (alive[s] && !out[s]) {
            val c = next[s]
            val ate = food[c]
            if (ate) { food[c] = false; foodCount--; grow[s] += GROW; eaten[s]++; eatFlash[s] = 1f }
            push(s, c)
            if (grow[s] > 0) grow[s]-- else popTail(s)
            if (len[s] > longest) longest = len[s]
            if (ate) env.emit(EventKind.HIT, (0.3f + 0.02f * len[s]).coerceAtMost(1f), pan(c), 0.4f)
        }
        for (s in 0 until snakes) if (alive[s] && out[s]) kill(env, s)
        settle(env, outs, headOn, false)
        while (foodCount < wantFood) placeFood(env)
    }

    /** Out, and left behind as apples. */
    private fun kill(env: SimEnv, s: Int) {
        alive[s] = false
        outAt[s] = elapsed
        aliveCount--
        for (k in 0 until len[s]) {
            val c = cellAt(s, k)
            if (owner[c] == s) owner[c] = -1
            if (k % 2 == 0 && !food[c] && inside(c % cols, c / cols)) { food[c] = true; foodCount++ }
        }
        env.emit(EventKind.DEATH, 0.3f, pan(headOf(s)), 0.7f)
    }

    /** What the losses of one move add up to: a winner, a draw, or a word. */
    private fun settle(env: SimEnv, outs: Int, headOn: Boolean, wall: Boolean) {
        if (outs > 0 && doneAt < 0f) {
            when {
                aliveCount == 1 -> {
                    winner = (0 until snakes).first { alive[it] }
                    doneAt = elapsed; payoffFrame = true
                    env.callout(if (headOn) "Head-on — last snake standing!" else "Last snake standing!", big = true)
                }
                aliveCount == 0 -> {
                    doneAt = elapsed; payoffFrame = true; winner = -1
                    env.callout("Draw!", big = true)
                }
                outs >= 2 -> env.callout(if (headOn) "Head-on! Both out!" else "Double KO!", big = true)
                aliveCount == 2 -> env.callout("Final two!")
                wall -> env.callout("Caught by the wall!")
                headOn -> env.callout("Head-on!")
                else -> env.callout("Crash!")
            }
        }
    }

    /** The best of the three ways forward, by room, apples and danger. */
    private fun choose(env: SimEnv, s: Int, greed: Float): Int {
        val h = headOf(s)
        var best = dir[s]
        var bestScore = Float.NEGATIVE_INFINITY
        for (d in 0 until 4) {
            if (d == OPPOSITE[dir[s]] && len[s] > 1) continue
            val c = stepCell(h, d)
            if (c < 0) continue
            val o = owner[c]
            // Another snake's tail cell is free next move unless it is about to eat.
            if (o >= 0 && !(c == tailOf(o) && grow[o] == 0)) continue
            var score = 0f
            // Room: the cells reachable from here, up to a little more than its length.
            val need = len[s] + 4
            val room = reach(c, need * 2)
            if (room < need) score -= 1000f - room * 10f
            score += min(room, need * 2) * 0.2f
            // Apples: the nearest one, by steps.
            score -= greed * 1.5f * nearestFood(c)
            // Danger: a cell next to a bigger snake's head.
            for (t in 0 until snakes) if (t != s && alive[t]) {
                val th = headOf(t)
                val md = abs(th % cols - c % cols) + abs(th / cols - c / cols)
                if (md == 1) score += if (len[t] >= len[s]) -40f else 15f * greed
            }
            score += env.rng.range(0f, 1.5f)
            if (score > bestScore) { bestScore = score; best = d }
        }
        return best
    }

    /** Free cells reachable from [from], counted up to [cap]. */
    private fun reach(from: Int, cap: Int): Int {
        var qh = 0; var qt = 0
        queue[qt++] = from; seen[from] = true
        var count = 0
        while (qh < qt && count < cap) {
            val c = queue[qh++]
            count++
            for (d in 0 until 4) {
                val n = stepCell(c, d)
                if (n < 0 || seen[n] || owner[n] >= 0) continue
                seen[n] = true
                queue[qt++] = n
            }
        }
        for (i in 0 until qt) seen[queue[i]] = false
        return count
    }

    private fun nearestFood(c: Int): Float {
        val x = c % cols; val y = c / cols
        var best = cols + rows
        for (i in food.indices) if (food[i]) {
            val d = abs(i % cols - x) + abs(i / cols - y)
            if (d < best) best = d
        }
        return best.toFloat()
    }

    private fun headOf(s: Int) = body[s][head[s]]
    private fun cellAt(s: Int, k: Int) = body[s][(head[s] - k + MAX_CELLS) % MAX_CELLS]
    private fun tailOf(s: Int) = cellAt(s, len[s] - 1)

    private fun push(s: Int, c: Int) {
        head[s] = (head[s] + 1) % MAX_CELLS
        body[s][head[s]] = c
        len[s]++
        owner[c] = s
    }

    private fun popTail(s: Int) {
        val t = tailOf(s)
        if (owner[t] == s && t != headOf(s)) owner[t] = -1
        len[s]--
    }

    private fun pan(c: Int) = ((c % cols + 0.5f) / cols * 2f - 1f).coerceIn(-1f, 1f)

    private fun cx(c: Int) = ox + (c % cols + 0.5f) * cell
    private fun cy(c: Int) = oy + (c / cols + 0.5f) * cell

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        g.reserveInstances(cols * rows / 2 + 200)
        // The board, and whatever the walls have taken.
        g.rect(0f, 0f, cols * cell * 0.5f, rows * cell * 0.5f, Palette.withAlpha(Palette.TEXT_PRIMARY, 0.04f))
        if (inset > 0) {
            val wallC = Palette.withAlpha(Palette.DANGER, 0.22f)
            val w = cols * cell; val hgt = rows * cell; val t = inset * cell
            g.rect(0f, oy + t * 0.5f, w * 0.5f, t * 0.5f, wallC)
            g.rect(0f, -oy - t * 0.5f, w * 0.5f, t * 0.5f, wallC)
            g.rect(ox + t * 0.5f, 0f, t * 0.5f, hgt * 0.5f - t, wallC)
            g.rect(-ox - t * 0.5f, 0f, t * 0.5f, hgt * 0.5f - t, wallC)
        }
        // Apples.
        val appleC = Palette.brighten(Palette.DANGER, 0.15f)
        for (c in food.indices) if (food[c]) g.disc(cx(c), cy(c), cell * 0.3f, appleC)
        // Snakes: the fallen fade out where they fell, the living drawn last.
        for (pass in 0..1) for (s in 0 until snakes) {
            if ((pass == 0) == alive[s]) continue
            val col = colour(env, s)
            val fade = if (alive[s]) 1f else (1f - (elapsed - outAt[s]) / 1.2f).coerceIn(0f, 1f)
            if (fade <= 0f) continue
            val half = cell * 0.44f
            for (k in len[s] - 1 downTo 0) {
                val c = cellAt(s, k)
                val shade = Palette.lerp(col, Palette.BG, 0.35f * k / max(1, len[s]))
                g.rect(cx(c), cy(c), half, half, shade, alpha = fade)
                // Joints: fill the gap to the next cell so the body reads as one.
                if (k > 0) {
                    val p = cellAt(s, k - 1)
                    g.rect((cx(c) + cx(p)) * 0.5f, (cy(c) + cy(p)) * 0.5f, if (p % cols != c % cols) half else half * 0.8f, if (p / cols != c / cols) half else half * 0.8f, shade, alpha = fade)
                }
            }
            if (alive[s]) {
                val hc = headOf(s)
                if (eatFlash[s] > 0f) g.glow(cx(hc), cy(hc), cell * 2.5f, col, 0.5f * eatFlash[s])
                // Eyes, looking the way it is going.
                val ex = DX[dir[s]] * cell * 0.15f; val ey = DY[dir[s]] * cell * 0.15f
                val px = DY[dir[s]] * cell * 0.18f; val py = DX[dir[s]] * cell * 0.18f
                g.disc(cx(hc) + ex + px, cy(hc) + ey + py, cell * 0.1f, Palette.BG)
                g.disc(cx(hc) + ex - px, cy(hc) + ey - py, cell * 0.1f, Palette.BG)
                if (doneAt >= 0f && s == winner) g.glow(cx(hc), cy(hc), cell * 6f, col, 0.35f)
            }
        }
    }

    private fun colour(env: SimEnv, s: Int): Int = env.palette.at((s + 0.5f) / snakes)

    override fun contenders(env: SimEnv): IntArray = IntArray(snakes) { colour(env, it) }

    override fun winner(): Int = winner

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && elapsed - doneAt > 4f

    override fun scoreForBest(): Float = longest.toFloat()
    override fun scoreForBestText(value: Float): String = "a snake ${value.toInt()} long"
    override fun liveBest(): Boolean = true

    override fun counters(): List<Pair<String, String>> = listOf(
        "snakes left" to "$aliveCount",
        "longest" to "$longest",
        "moves" to "$moves"
    )

    companion object {
        const val MAX_SNAKES = 6
        const val MAX_ROWS = 80
        const val MAX_CELLS = 3200
        const val START_LEN = 4
        const val GROW = 2
        const val BASE_RATE = 10f
        const val RATE_PER_OUT = 1.5f
        const val CLOSE_AT = 15f
        const val CLOSE_EVERY = 1.0f
        const val UP = 0
        const val RIGHT = 1
        const val DOWN = 2
        const val LEFT = 3
        val DX = intArrayOf(0, 1, 0, -1)
        val DY = intArrayOf(1, 0, -1, 0)
        val OPPOSITE = intArrayOf(DOWN, LEFT, UP, RIGHT)

        fun def(): SimDef = SimDef(
            id = "algorithms/snake-battle",
            title = "Snake battle",
            shelf = Shelf.ALGORITHMS,
            family = "Battles",
            blurb = "Four snakes, one board, the same apples. Crash and you're food. Pick a colour.",
            formats = listOf(TrapFormat.LAST_STANDING, TrapFormat.GUESS),
            heroLabel = "New battle",
            defaultPalette = "Spectrum",
            typicalSeconds = 22f,
            params = listOf(
                Param.int("snakes", "Snakes", 2, MAX_SNAKES, 4, card = Card.DATA),
                Param.int("size", "Board width", 12, 40, 22, card = Card.DATA),
                Param.int("apples", "Apples", 1, 30, 6, card = Card.DATA),
                Param.float("pace", "Pace", 0.2f, 4f, 1f, card = Card.MOTION, logScale = true),
                Param.float("greed", "Greed", 0f, 3f, 1f, card = Card.DATA)
            ),
            create = { SnakeBattleSim() }
        )
    }
}
