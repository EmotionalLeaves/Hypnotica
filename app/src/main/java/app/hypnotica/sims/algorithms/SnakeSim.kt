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
 * Snake, played perfectly, to the last square.
 *
 * The snake follows a route through every square of the board and back — a
 * Hamiltonian cycle — so it can never trap itself; while it is short it cuts
 * across the route towards the apple, but only where the cut can't land it
 * in front of its own tail. Early on it darts; by the end it is winding the
 * whole board, one square of space left, and then none.
 */
class SnakeSim : Sim {

    private var cols = 10
    private var rows = 16
    private var cells = 160
    private var order = IntArray(0)   // cell -> its place on the route
    private var body = IntArray(0)    // ring buffer of cells, tail first
    private var start = 0
    private var len = 0
    private var occupied = BooleanArray(0)
    // The apples on the board: one, or three at once for a feast.
    private var apples = IntArray(1) { -1 }
    private var golden = BooleanArray(1)
    private var mode = 0
    private var eaten = 0
    private var grow = 0
    private var carry = 0f
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var eatFlash = 0f
    private var cell = 0.1f
    private var ox = 0f
    private var oy = 0f
    private var halfW = 0.46f

    override fun setup(env: SimEnv) {
        halfW = env.halfWidth
        cols = (env.params.i("cols", 10).coerceIn(4, MAX_COLS) / 2) * 2
        // As many rows as fill the screen at square cells; an even count, so
        // the route can close.
        val w = 2f * halfW * 0.92f
        val tall = 1.7f
        cell = w / cols
        rows = ((tall / cell).toInt() / 2 * 2).coerceIn(4, 200)
        if (rows * cell > tall) cell = tall / rows
        cells = cols * rows
        ox = -cols * cell * 0.5f
        oy = -rows * cell * 0.5f - 0.04f
        // The route: up column 0, then back and forth across the rest, row by
        // row, ending beside where it began.
        order = IntArray(cells)
        var k = 0
        for (r in rows - 1 downTo 0) { order[idx(0, r)] = k++ }
        for (r in 0 until rows) {
            if (r % 2 == 0) for (c in 1 until cols) order[idx(c, r)] = k++
            else for (c in cols - 1 downTo 1) order[idx(c, r)] = k++
        }
        // That runs from the top of column 0 down: reverse it so the route's
        // next step from the top-left corner is column 0's next cell down
        // and the last cell (row rows-1, column 1) leads back to it.
        // Built that way it is already a loop; nothing to reverse.
        body = IntArray(cells)
        occupied = BooleanArray(cells)
        start = 0
        len = 0
        val first = idx(0, rows - 1)
        push(first)
        grow = INITIAL_LENGTH - 1
        mode = env.params.choiceIndex("apples").coerceIn(0, 2)
        val n = if (mode == 2) FEAST else 1
        apples = IntArray(n) { -1 }
        golden = BooleanArray(n)
        eaten = 0
        mark = 0
        lastDir = 0
        rateNow = MIN_RATE
        for (i in 0 until n) placeApple(env, i)
        carry = 0f
        elapsed = 0f
        doneAt = -1f
        payoffFrame = false
        eatFlash = 0f
    }

    private fun idx(c: Int, r: Int) = r * cols + c

    private fun push(c: Int) {
        body[(start + len) % cells] = c
        len++
        occupied[c] = true
    }

    private fun popTail() {
        occupied[body[start]] = false
        start = (start + 1) % cells
        len--
    }

    private fun head(): Int = body[(start + len - 1) % cells]
    private fun tail(): Int = body[start]

    private fun dist(a: Int, b: Int): Int = (order[b] - order[a] + cells) % cells

    private fun placeApple(env: SimEnv, i: Int) {
        apples[i] = -1
        golden[i] = false
        // Not under the snake, nor on another apple.
        val free = cells - len - apples.count { it >= 0 }
        if (free <= 0) return
        var pick = env.rng.int(0, free)
        for (c in 0 until cells) {
            if (occupied[c] || c in apples) continue
            if (pick == 0) {
                apples[i] = c
                // In golden mode, every so often one is gold: worth five.
                golden[i] = mode == 1 && env.rng.nextFloat() < GOLD_CHANCE
                return
            }
            pick--
        }
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        eatFlash = max(0f, eatFlash - h * 4f)
        if (doneAt >= 0f) return
        // Faster and faster, all the way: readable at first, a blur winding
        // the whole board at the end. Growing by a constant factor a second,
        // a small board is done in seconds and the biggest in half a minute.
        val pace = env.params.f("pace", 1f)
        val rate = min(MAX_RATE, MIN_RATE * Math.exp((elapsed * pace / DOUBLING).toDouble()).toFloat())
        rateNow = rate
        carry += h * rate
        var moves = 0
        notedThisStep = false
        while (carry >= 1f && doneAt < 0f && moves < MAX_MOVES_PER_STEP) {
            carry -= 1f
            moves++
            advance(env)
        }
        if (moves >= MAX_MOVES_PER_STEP) carry = 0f
    }

    private fun advance(env: SimEnv) {
        val hd = head()
        val hc = hd % cols; val hr = hd / cols
        // The route's next cell is always safe.
        var best = -1
        var bestGain = -1
        val toTail = if (len > 1) dist(hd, tail()) else cells
        var toApple = cells
        for (a in apples) if (a >= 0) toApple = min(toApple, dist(hd, a))
        // Short cuts only while there is room to spare.
        val cutting = len + grow < cells * CUT_UNTIL
        for (d in 0 until 4) {
            val c = hc + DX[d]; val r = hr + DY[d]
            if (c < 0 || r < 0 || c >= cols || r >= rows) continue
            val n = idx(c, r)
            if (occupied[n] && !(n == tail() && grow == 0)) continue
            val gain = dist(hd, n)
            if (gain == 1) { if (best < 0) { best = n; bestGain = 1 }; continue }
            if (!cutting) continue
            // A cut must land before the tail, with the growth still owed as
            // margin, and not past the apple.
            // Golden apples owe five at once, so in that game keep five spare.
            if (gain >= toTail - grow - SLACK - (if (mode == 1) GOLD_GROWTH else 0)) continue
            if (gain > toApple) continue
            if (gain > bestGain) { best = n; bestGain = gain }
        }
        if (best < 0) {
            // Cannot happen on the route; if it does, start over rather than freeze.
            doneAt = elapsed
            return
        }
        if (grow > 0) grow-- else popTail()
        push(best)
        // A soft tick as the head turns, while the snake is slow enough to
        // follow: the opening seconds, before the first apple, were silent
        // with the head visibly winding across the board. Past a few moves a
        // second the turns blur and the apples carry the sound.
        val dir = best - hd
        if (dir != lastDir && lastDir != 0 && rateNow < TICK_UNTIL_RATE && !notedThisStep) {
            notedThisStep = true
            env.emit(EventKind.STEP, 0.25f + 0.5f * (best / cols).toFloat() / max(1, rows - 1),
                ((best % cols) / max(1f, cols - 1f)) * 2f - 1f, 0.18f)
        }
        lastDir = dir
        val ate = apples.indexOf(best)
        if (ate >= 0) {
            val gold = golden[ate]
            // Never owed more than the board has room for.
            // Only the free run of route ahead of the head counts as room:
            // cells skipped by short cuts sit inside the body's span and can't
            // be reached, and owing more than the run trapped the snake.
            grow = min(grow + (if (gold) GOLD_GROWTH else GROW_PER_APPLE), max(0, dist(best, tail()) - 1))
            eatFlash = if (gold) 2f else 1f
            eaten++
            val pan = ((best % cols) / max(1f, cols - 1f)) * 2f - 1f
            // Each apple the next note up the scale; gold, a chord. One apple
            // a step sounds: at the end the snake eats a dozen within a frame,
            // and a note for each was a smear of identical clicks.
            if (!notedThisStep) {
                notedThisStep = true
                env.emit(EventKind.HIT, (eaten % 15) / 15f, pan, 0.55f)
                if (gold) for (k in 1..3) env.emit(EventKind.HIT, ((eaten + 2 * k) % 15) / 15f, pan, 0.45f)
            }
            placeApple(env, ate)
        }
        // The board filling, called at the shares that make you stay.
        val share = len * 100 / max(1, cells)
        while (mark < MARKS.size && share >= MARKS[mark]) {
            if (mark == MARKS.size - 1 || share < MARKS[mark + 1]) env.callout(MARK_WORDS[mark])
            mark++
        }
        if (len >= cells) {
            doneAt = elapsed
            payoffFrame = true
        }
    }

    private var mark = 0

    private var notedThisStep = false
    private var lastDir = 0
    private var rateNow = MIN_RATE

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        // The board, its edge lit green once the snake fills it.
        val full = doneAt >= 0f && len >= cells
        val k = if (full) ((elapsed - doneAt) * 1.5f).coerceIn(0f, 1f) else 0f
        g.rect(0f, oy + rows * cell * 0.5f, cols * cell * 0.5f + 0.006f, rows * cell * 0.5f + 0.006f,
            Palette.lerp(Palette.withAlpha(Palette.TEXT_SECONDARY, 0.25f), Palette.SUCCESS, k))
        g.rect(0f, oy + rows * cell * 0.5f, cols * cell * 0.5f, rows * cell * 0.5f, 0xFF0B0D12.toInt())
        val half = cell * 0.5f
        // Tail dim to head bright, along the palette.
        for (i in 0 until len) {
            val c = body[(start + i) % cells]
            val t = if (len > 1) i / (len - 1f) else 1f
            val x = ox + (c % cols) * cell + half
            val y = oy + (c / cols) * cell + half
            g.rect(x, y, half * 0.9f, half * 0.9f, palette.at(BODY_LO + BODY_SPAN * t))
        }
        // Joints, so it reads as one body and not beads.
        for (i in 1 until len) {
            val a = body[(start + i - 1) % cells]; val b = body[(start + i) % cells]
            val ax = ox + (a % cols) * cell + half; val ay = oy + (a / cols) * cell + half
            val bx = ox + (b % cols) * cell + half; val by = oy + (b / cols) * cell + half
            val t = i / max(1f, len - 1f)
            g.rect((ax + bx) * 0.5f, (ay + by) * 0.5f, if (ax != bx) half * 0.5f else half * 0.9f, if (ay != by) half * 0.5f else half * 0.9f, palette.at(BODY_LO + BODY_SPAN * t))
        }
        if (len > 0) {
            val hd = head()
            val x = ox + (hd % cols) * cell + half
            val y = oy + (hd / cols) * cell + half
            val hc = palette.at(BODY_LO + BODY_SPAN)
            g.glow(x, y, cell * (1.3f + 1.2f * eatFlash), hc, 0.3f + 0.4f * eatFlash)
            // The head a shade brighter, with a pale centre, so it reads as the
            // front of the snake and not one more square of it.
            g.rect(x, y, half * 0.9f, half * 0.9f, Palette.brighten(hc, 0.2f))
            g.disc(x, y, half * 0.32f, Palette.withAlpha(Palette.TEXT_PRIMARY, 0.9f))
        }
        for (i in apples.indices) {
            val apple = apples[i]
            if (apple < 0) continue
            val x = ox + (apple % cols) * cell + half
            val y = oy + (apple / cols) * cell + half
            val pulse = 0.85f + 0.15f * kotlin.math.sin(elapsed * 8f + i)
            val col = if (golden[i]) GOLD else Palette.DANGER
            g.glow(x, y, cell * (if (golden[i]) 2.4f else 1.4f), col, 0.5f)
            g.disc(x, y, half * 0.75f * pulse, col)
        }
    }

    override fun frameHalfWidth(env: SimEnv): Float = 0f

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && elapsed - doneAt > 3f

    override fun counters(): List<Pair<String, String>> = listOf(
        "length" to len.toString(),
        "board" to "${len * 100 / max(1, cells)}%"
    )

    companion object {
        val MARKS = intArrayOf(50, 75, 90, 95, 99)
        val MARK_WORDS = arrayOf("Half the board!", "75%!", "90%…", "95%… don't crash", "99%!!")
        const val MAX_COLS = 40
        const val FEAST = 3
        const val GOLD_CHANCE = 0.2f
        const val GOLD_GROWTH = 5
        const val GOLD = 0xFFFFC23A.toInt()
        const val INITIAL_LENGTH = 3
        /**
         * The body runs over this stretch of the palette, tail to head. It
         * ran to 0.95, where Spectrum wraps back to red: the head was the
         * apple's colour.
         */
        const val BODY_LO = 0.12f
        const val BODY_SPAN = 0.66f
        const val GROW_PER_APPLE = 1
        const val SLACK = 3
        const val CUT_UNTIL = 0.55f
        const val MIN_RATE = 9f
        /** Moves a second above which turning no longer ticks. */
        const val TICK_UNTIL_RATE = 24f
        /** Seconds for the speed to grow e-fold. */
        const val DOUBLING = 3.2f
        const val MAX_RATE = 1_000_000f
        const val MAX_MOVES_PER_STEP = 20000
        val DX = intArrayOf(1, -1, 0, 0)
        val DY = intArrayOf(0, 0, 1, -1)

        fun def(): SimDef = SimDef(
            id = "algorithms/snake",
            title = "Perfect snake",
            shelf = Shelf.ALGORITHMS,
            family = "Games",
            blurb = "Snake, played without a mistake, until it is the whole board.",
            formats = listOf(TrapFormat.FILL, TrapFormat.NUMBER_UP, TrapFormat.SONG),
            heroLabel = "New game",
            defaultPalette = "Spectrum",
            typicalSeconds = 17f,
            params = listOf(
                Param.choice("apples", "Apples", listOf("Classic", "Golden", "Feast"), 0, card = Card.DATA, input = ParamInput.TILES),
                Param.int("cols", "Board width", 4, MAX_COLS, 10, card = Card.DATA),
                Param.float("pace", "Pace", 0.2f, 5f, 1f, card = Card.MOTION)
            ),
            create = { SnakeSim() }
        )
    }
}
