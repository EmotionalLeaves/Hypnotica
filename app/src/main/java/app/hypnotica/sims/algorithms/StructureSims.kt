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
import kotlin.math.ceil
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
    private var liftFrom = 0

    /** The most the tower may take of the screen's height of two. */
    private val TOWER_HEIGHT = 1.2f

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
        liftProgress = 1f
        pendingNote = false
        restStarted = false
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
            // The last disc finishes landing while the tower rests.
            liftProgress = min(1f, liftProgress + abs(dt) * 4f)
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
        // At least two moves a second: one disc's single move used to wait
        // the whole nineteen seconds.
        val rate = max(moves.size / TARGET_SECONDS, MIN_MOVES_PER_SECOND) * env.params.f("rate", 1f).coerceIn(0.05f, 40f)
        val voiceEvery = max(1, (rate / MAX_NOTES_PER_SECOND).toInt())
        carry += abs(dt) * rate
        while (carry >= 1f && played < moves.size) {
            carry -= 1f
            val m = moves[played]
            fromPeg = m[0]
            toPeg = m[1]
            val disc = pegs[fromPeg].removeAt(pegs[fromPeg].size - 1)
            liftFrom = pegs[fromPeg].size
            pegs[toPeg].add(disc)
            // A disc still in the air when the next move starts lands this
            // instant (the drawing snaps it home), so its note is due now.
            landNote(env)
            lifting = disc
            liftProgress = 0f
            played++
            // Eighteen discs is a quarter of a million moves in twenty seconds.
            // Only a sample of them becomes a note; the rest are the texture of
            // the tower moving.
            if (played % voiceEvery == 0) {
                pendingNote = true
                pendingValue = 1f - (disc.toFloat() / discs)
                pendingPan = (toPeg - 1).toFloat() * 0.7f
            }
        }
        // A move takes the time until the next one, but never longer than
        // a third of a second.
        liftProgress = min(1f, liftProgress + abs(dt) * max(3f, rate * 1.15f))
        // The note is the disc landing on its peg, not leaving the old one:
        // since the move became a flight it sounded up to a third of a second
        // before the disc arrived.
        if (liftProgress >= 1f) landNote(env)
        // Finished when the last disc is down, not when it takes off.
        if (played >= moves.size && liftProgress >= 1f && restSeconds < 0f) {
            payoffFrame = true
            restSeconds = 1.5f
            restStarted = true
        }
    }

    private var pendingNote = false
    private var pendingValue = 0f
    private var pendingPan = 0f

    private fun landNote(env: SimEnv) {
        if (!pendingNote) return
        pendingNote = false
        env.emit(EventKind.SWAP, pendingValue, pendingPan, 0.6f)
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val halfW = env.halfWidth * 0.92f
        // The widest disc reaches 0.46 of a peg gap either side of its peg, so
        // the gap is two thirds of the width: at the full width the biggest
        // discs on the outer pegs ran off both edges of the screen.
        val pegGap = halfW / 1.5f
        // Discs as tall as the screen allows: at a fixed 0.085 the shipped
        // seven made a small tower at the bottom of a big empty screen.
        // 0.13, not 0.115: the shipped seven left a dead band half a screen
        // tall between the move counter and the peg tops on a phone.
        val discH = min(0.13f, TOWER_HEIGHT / discs)
        val towerH = discs * discH
        val pegLen = towerH + discH * 1.2f
        // On a tall phone the tower sits low and the move count, in binary,
        // runs across the top (see drawCount); otherwise it is centred.
        val counter = towerH + discH * 3f < 1.35f
        // The counter sits one small gap above the highest a flying disc goes,
        // and tower + counter are centred as one block: pinned to the top of
        // the screen with the tower pinned to the bottom, a phone showed a
        // dead band a third of a screen tall between them.
        val countR = min(2f * halfW / discs * 0.36f, 0.05f)
        val liftTopRel = pegLen + discH * 1.4f
        val barRel = liftTopRel + 0.09f
        val lampRel = barRel + 0.06f + countR
        val baseY = if (counter) -(lampRel + countR - 0.04f) * 0.5f
                    else -(towerH + discH * 3f) * 0.5f - 0.05f
        val pegTop = baseY + pegLen
        val rail = Palette.withAlpha(Palette.TEXT_SECONDARY, 0.5f)
        val pegW = max(0.006f, min(0.012f, discH * 0.12f))
        for (i in 0 until 3) {
            val x = (i - 1) * pegGap
            g.rect(x, baseY + pegLen * 0.5f, pegW, pegLen * 0.5f, rail)
            g.disc(x, pegTop, pegW, rail)
        }
        val slab = 0.02f
        g.rect(0f, baseY - slab, halfW, slab, rail)
        g.disc(-halfW, baseY - slab, slab, rail)
        g.disc(halfW, baseY - slab, slab, rail)
        val flying = lifting >= 0 && liftProgress < 1f
        for (i in 0 until 3) {
            val x = (i - 1) * pegGap
            for ((level, disc) in pegs[i].withIndex()) {
                val top = level == pegs[i].size - 1
                // The disc in flight is drawn on its way, below.
                if (flying && top && i == toPeg && disc == lifting) continue
                pill(g, x, baseY + level * discH + discH * 0.5f, discWidth(disc, pegGap), discH, palette.at(disc.toFloat() / discs))
            }
        }
        if (flying) {
            // Up off its peg, over, and down onto the next: the move is the
            // thing to see, and the disc used to jump there in one frame.
            val p = liftProgress
            val x0 = (fromPeg - 1) * pegGap
            val x1 = (toPeg - 1) * pegGap
            val y0 = baseY + liftFrom * discH + discH * 0.5f
            val y1 = baseY + (pegs[toPeg].size - 1) * discH + discH * 0.5f
            val lift = pegTop + discH * 0.9f
            val x: Float
            val y: Float
            when {
                p < 0.3f -> { x = x0; y = y0 + (lift - y0) * ease(p / 0.3f) }
                p < 0.7f -> { x = x0 + (x1 - x0) * ease((p - 0.3f) / 0.4f); y = lift }
                else -> { x = x1; y = lift + (y1 - lift) * ease((p - 0.7f) / 0.3f) }
            }
            val c = palette.at(lifting.toFloat() / discs)
            g.glow(x, y, discWidth(lifting, pegGap) * 1.3f, c, 0.35f)
            pill(g, x, y, discWidth(lifting, pegGap), discH, Palette.brighten(c, 0.15f))
        }
        if (counter) drawCount(g, palette, halfW, baseY + lampRel, countR, baseY + barRel)
    }

    /**
     * The move number in binary, one lamp per disc, smallest on the right.
     * Hanoi's solution is a binary count: move k moves the disc whose bit
     * turns on at k, so the lamp that lights is always the disc in the air.
     * Below it, the share of the 2^n − 1 moves done.
     */
    private fun drawCount(g: G, palette: Palette, halfW: Float, y: Float, r: Float, by: Float) {
        val slot = 2f * halfW / discs
        val dim = 0xFF262B36.toInt()
        for (d in 1..discs) {
            val bit = d - 1
            val x = halfW - (bit + 0.5f) * slot
            val lit = (played shr bit) and 1 == 1
            val c = palette.at(d.toFloat() / discs)
            if (lit && d == lifting && liftProgress < 1f) g.glow(x, y, r * 2.6f, c, 0.45f)
            g.disc(x, y, r, if (lit) c else dim)
            if (!lit) g.ring(x, y, r, r * 0.12f, Palette.withAlpha(c, 0.5f))
        }
        val total = max(1, moves.size)
        val frac = played.toFloat() / total
        g.rect(0f, by, halfW, 0.012f, 0xFF1A1E28.toInt())
        if (frac > 0f) g.rect(-halfW + halfW * frac, by, halfW * frac, 0.012f,
            if (played >= moves.size) Palette.SUCCESS else palette.at(0.5f))
    }

    /** From a third of the widest up: thinner, the small discs were balls. */
    private fun discWidth(disc: Int, pegGap: Float) = (0.3f + 0.62f * disc / discs) * pegGap * 0.5f

    /** A rounded disc: a bar with round ends and a lit top edge. */
    private fun pill(g: G, x: Float, y: Float, hw: Float, discH: Float, c: Int) {
        val r = discH * 0.42f
        val body = max(0f, hw - r)
        g.rect(x, y, body, r, c)
        g.disc(x - body, y, r, c)
        g.disc(x + body, y, r, c)
        g.rect(x, y + r * 0.55f, body, r * 0.14f, Palette.brighten(c, 0.3f), alpha = 0.8f)
    }

    private fun ease(t: Float): Float = t * t * (3f - 2f * t)

    override fun payoff(): Boolean = payoffFrame

    // restSeconds starts at -1, so "in -1000..0" alone ended the run the step
    // the last disc took off, before it landed.
    override fun finished(): Boolean = restStarted && restSeconds <= 0f

    private var restStarted = false

    override fun counters(): List<Pair<String, String>> = listOf(
        "moves" to String.format(java.util.Locale.US, "%,d / %,d", played, moves.size),
        "discs" to discs.toString()
    )

    companion object {
        const val MIN_MOVES_PER_SECOND = 2f
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
    private var lastRejectedRow = -1
    private var elapsed = 0f

    override fun setup(env: SimEnv) {
        n = env.params.i("size", 10).coerceIn(4, 16)
        elapsed = 0f
        depthEvery = 1
        depthTick = 0
        cols = IntArray(n) { -1 }
        row = 0
        tries = 0
        backtracks = 0
        solved = false
        payoffFrame = false
        carry = 0f
        restSeconds = -1f
        lastRejected = -1
        lastRejectedRow = -1
        depthCount = 0
        java.util.Arrays.fill(depthLog, 0)
    }

    /**
     * How deep the search stood, sampled through the run (and thinned by
     * halves when full, so the whole run always fits): the advance and the
     * retreat the blurb is about, drawn as a sawtooth under the board.
     */
    private val depthLog = ByteArray(DEPTH_LOG)
    private var depthCount = 0
    private var depthEvery = 1
    private var depthTick = 0

    private fun logDepth(d: Int) {
        if (++depthTick < depthEvery) return
        depthTick = 0
        if (depthCount == DEPTH_LOG) {
            for (i in 0 until DEPTH_LOG / 2) depthLog[i] = max(depthLog[2 * i].toInt(), depthLog[2 * i + 1].toInt()).toByte()
            depthCount = DEPTH_LOG / 2
            depthEvery *= 2
        }
        depthLog[depthCount++] = d.toByte()
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
        // 13 tries at four queens, 876 at eight, and about 160,000 at
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
                lastRejectedRow = row
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
        logDepth(if (solved) n else max(0, row))
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val size = min(2f * env.halfWidth * 0.94f / n, 1.84f / n)
        val half = size * n * 0.5f
        // On a tall phone the board, held by the width, left a third of the
        // screen empty above and below. There it moves up and the search's
        // depth over the run fills the space beneath.
        val spare = 2f - 2f * half
        val chart = spare > 0.5f
        val cy = if (chart) 1f - half - 0.1f else 0f
        val originX = -half
        val originY = cy - half
        g.reserveInstances(n * n * 2 + n * 3 + DEPTH_LOG + 16)
        // A frame, lit green once the board is solved.
        val frame = if (solved) Palette.SUCCESS else Palette.withAlpha(Palette.TEXT_SECONDARY, 0.55f)
        val fw = max(0.004f, size * 0.06f)
        g.rect(0f, cy, half + fw, half + fw, frame)
        // Solid squares: the board used to be a tenth and a twenty-fifth of
        // grey over black, which on a phone was barely a board at all.
        for (r in 0 until n) {
            for (c in 0 until n) {
                g.rect(
                    originX + (c + 0.5f) * size, originY + (r + 0.5f) * size,
                    size * 0.5f, size * 0.5f,
                    if ((r + c) % 2 == 0) DARK_SQUARE else LIGHT_SQUARE
                )
            }
        }
        if (row in 0 until n && !solved) {
            g.rect(0f, originY + (row + 0.5f) * size, half, size * 0.5f, Palette.ACCENT, alpha = 0.22f)
            // In the row being filled, the squares the queens below attack:
            // why the next queen has so few places to go. (Shaded in every
            // empty row it tinted the whole upper board a muddy red.)
            for (c in 0 until n) {
                var hit = false
                for (q in 0 until row) {
                    val qc = cols[q]
                    if (qc >= 0 && (qc == c || abs(qc - c) == row - q)) { hit = true; break }
                }
                if (hit) g.rect(originX + (c + 0.5f) * size, originY + (row + 0.5f) * size,
                    size * 0.5f, size * 0.5f, Palette.DANGER, alpha = 0.16f)
            }
            // The square just tried and refused, in the row being filled.
            if (lastRejectedRow == row && lastRejected in 0 until n) {
                g.rect(originX + (lastRejected + 0.5f) * size, originY + (row + 0.5f) * size,
                    size * 0.42f, size * 0.42f, Palette.DANGER, alpha = 0.45f)
            }
        }
        for (r in 0 until n) {
            val c = cols[r]
            if (c < 0) continue
            val x = originX + (c + 0.5f) * size
            val y = originY + (r + 0.5f) * size
            val col = palette.at(0.72f * r / max(1, n - 1))
            g.glow(x, y, size * (if (solved) 1.0f else 0.75f), col, if (solved) 0.6f else 0.4f)
            g.disc(x, y, size * 0.36f, col)
            g.ring(x, y, size * 0.36f, size * 0.06f, Palette.brighten(col, 0.4f))
            g.disc(x, y, size * 0.12f, Palette.withAlpha(Palette.TEXT_PRIMARY, 0.85f))
        }
        if (chart) drawDepth(g, palette, -half, half, originY - 0.12f, -0.92f)
    }

    /** Depth against time: n rows high, a line at the top for "solved". */
    private fun drawDepth(g: G, palette: Palette, left: Float, right: Float, top: Float, bottom: Float) {
        val h = top - bottom
        if (h < 0.2f) return
        val frame = Palette.withAlpha(Palette.TEXT_SECONDARY, 0.2f)
        g.rect((left + right) * 0.5f, bottom, (right - left) * 0.5f, 0.002f, frame)
        // The goal: every row filled.
        g.rect((left + right) * 0.5f, top, (right - left) * 0.5f, 0.003f, if (solved) Palette.SUCCESS else frame)
        // Faint guides at each row's height.
        for (r in 1 until n) {
            val y = bottom + h * r / n
            g.rect((left + right) * 0.5f, y, (right - left) * 0.5f, 0.0012f, Palette.withAlpha(Palette.TEXT_SECONDARY, 0.07f))
        }
        val cnt = depthCount
        if (cnt < 2) return
        // Filled columns, so the sawtooth reads as a mass climbing and falling.
        val colW = (right - left) / DEPTH_LOG
        val lineC = palette.at(0.6f)
        var px = 0f
        var py = 0f
        for (i in 0 until cnt) {
            val d = depthLog[i].toInt()
            val x = left + (i + 0.5f) * colW
            val y = bottom + h * d / n
            if (d > 0) g.rect(x, (bottom + y) * 0.5f, colW * 0.5f, (y - bottom) * 0.5f, lineC, alpha = 0.16f)
            if (i > 0) g.line(px, py, x, y, 0.004f, lineC, 0.9f)
            px = x; py = y
        }
        g.disc(px, py, 0.012f, Palette.brighten(lineC, 0.3f))
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = solved && restSeconds in -1000f..0f

    override fun counters(): List<Pair<String, String>> = listOf(
        "placements tried" to String.format(java.util.Locale.US, "%,d", tries),
        "backtracks" to String.format(java.util.Locale.US, "%,d", backtracks)
    )

    companion object {
        private const val DEPTH_LOG = 256
        private const val DARK_SQUARE = 0xFF141821.toInt()
        private const val LIGHT_SQUARE = 0xFF252B39.toInt()

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
    /**
     * Each node's place along the tree's breadth, 0..1, from relayout(), and
     * the eased value drawn.
     */
    private var rankU = FloatArray(0)
    private var shownU = FloatArray(0)
    private var stack = IntArray(0)
    private var order = IntArray(0)
    private var span = FloatArray(0)
    private var start = FloatArray(0)
    private var ys = FloatArray(0)
    private var totalUnits = 1f
    private var layoutDirty = false
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
        rankU = FloatArray(target)
        shownU = FloatArray(target)
        stack = IntArray(target + 1)
        order = IntArray(target)
        span = FloatArray(target)
        start = FloatArray(target)
        ys = FloatArray(target)
        totalUnits = 1f
        layoutDirty = false
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
        // Nodes glide to their new columns as the tree grows rather than jump —
        // through the rest as well: it used to stop with the last insert, so
        // the final nodes froze mid-glide as long stray edges across the tree.
        val ease = min(1f, abs(dt) * 10f)
        for (i in 0 until size) shownU[i] += (rankU[i] - shownU[i]) * ease
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
        val firstNew = size
        while (carry >= 1f && size < target) {
            carry -= 1f
            val key = if (sorted) size.toFloat() / target else env.rng.nextFloat()
            insert(key, env)
        }
        if (layoutDirty) relayout()
        // Every node added this step appears where it belongs. Only the last
        // one did, so on a big tree (several inserts a step) the rest slid in
        // from the top edge, trailing edges across the whole picture.
        for (i in firstNew until size) shownU[i] = rankU[i]
        if (size >= target && restSeconds < 0f) {
            payoffFrame = true
            restSeconds = 1.5f
        }
    }

    private fun insert(key: Float, env: SimEnv) {
        val index = size
        keys[index] = key
        if (index == 0) {
            depth[0] = 0
            size = 1
            maxDepth = 0
            rankU[0] = 0.5f; shownU[0] = 0.5f
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
        layoutDirty = true
        // Pan from where the node lands on screen. On a tall phone the tree
        // lies on its side, so across the screen is depth, not key: panning by
        // key put every node of a sideways tree in the middle on average.
        val sideways = isSideways(env.halfWidth * 0.95f, max(1, maxDepth), max(1f, totalUnits))
        val pan = if (sideways) (d.toFloat() / max(1, maxDepth)) * 2f - 1f else key * 2f - 1f
        env.emit(EventKind.WRITE, key, pan, 0.45f)
    }

    /**
     * A tidy layout, in three linear passes (iterative: a sorted-key tree is a
     * hundred thousand deep). Each leaf takes one unit of breadth, a node with
     * one child half a unit more, and a parent sits centred over its children,
     * so the left-to-right order is still the sorted order. Laid out by key
     * value, keys that fell close together piled whole clumps of a level on
     * top of each other; one column per node (in-order rank) cured that but
     * shrank the nodes to specks. This needs about half as many columns.
     */
    private fun relayout() {
        layoutDirty = false
        val n = size
        // Pre-order (node, left, right); reversed, every child precedes its parent.
        var sp = 0
        var k = 0
        stack[sp++] = 0
        while (sp > 0) {
            val v = stack[--sp]
            order[k++] = v
            if (right[v] >= 0) stack[sp++] = right[v]
            if (left[v] >= 0) stack[sp++] = left[v]
        }
        for (j in n - 1 downTo 0) {
            val v = order[j]
            val l = left[v]; val r = right[v]
            span[v] = when {
                l < 0 && r < 0 -> 1f
                l >= 0 && r >= 0 -> span[l] + span[r]
                else -> span[if (l >= 0) l else r] + 0.5f
            }
        }
        start[0] = 0f
        for (j in 0 until n) {
            val v = order[j]
            val l = left[v]; val r = right[v]
            if (l >= 0) start[l] = start[v]
            if (r >= 0) start[r] = if (l >= 0) start[v] + span[l] else start[v] + 0.5f
        }
        val total = span[0]
        for (j in n - 1 downTo 0) {
            val v = order[j]
            val l = left[v]; val r = right[v]
            span[v] = when {
                l < 0 && r < 0 -> start[v] + 0.5f
                l >= 0 && r >= 0 -> (span[l] + span[r]) * 0.5f
                l >= 0 -> span[l] + 0.5f
                else -> span[r] - 0.5f
            }   // span now holds each node's centre, children first
        }
        for (j in 0 until n) rankU[j] = span[j] / total
        totalUnits = total
        // A new node appears where it belongs, not sliding in from the left.
        shownU[n - 1] = rankU[n - 1]
    }

    private fun isSideways(halfW: Float, levels: Int, units: Float): Boolean {
        fun dotFor(len: Float, row: Float) = min(len / units * 0.36f, row * 0.28f)
        return dotFor(1.84f, (2f * halfW - 0.04f) / levels) > 1.25f * dotFor(2f * halfW, 1.75f / (levels + 1))
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        if (size == 0) return
        val halfW = env.halfWidth * 0.95f
        val levels = max(1, maxDepth)
        val units = max(1f, totalUnits)
        // Breadth along whichever axis gives the nodes more room. A bushy tree
        // is far wider than it is deep, so on a tall phone it lies on its side
        // (root on the left, smallest keys at the top) and gets twice the
        // room per node; on a wide screen it stands the usual way up.
        val upLen = 2f * halfW
        val upRow = 1.75f / (levels + 1)
        val sideLen = 1.84f
        val sideRow = (2f * halfW - 0.04f) / levels
        fun dotFor(len: Float, row: Float) = min(len / units * 0.36f, row * 0.28f)
        val sideways = isSideways(halfW, levels, units)
        val dotSize = (if (sideways) dotFor(sideLen, sideRow) else dotFor(upLen, upRow)).coerceIn(0.0028f, 0.016f)
        val edgeW = (dotSize * 0.4f).coerceIn(0.0012f, 0.003f)
        for (i in 0 until size) {
            if (sideways) {
                xs[i] = -halfW + 0.02f + depth[i] * sideRow
                ys[i] = 0.92f - shownU[i] * sideLen
            } else {
                xs[i] = -halfW + shownU[i] * upLen
                ys[i] = 0.88f - depth[i] * upRow
            }
        }
        val edgeStride = maxOf(1, size / Draw.MAX_EDGES)
        // Depth runs over most of the palette, not into its far end, which on
        // Ocean is navy: the deepest nodes were black on black.
        fun shade(d: Int) = palette.at(Draw.DEPTH_SPAN * d / levels)
        g.reserveInstances(if (size > Draw.AS_POINTS) min(size, Draw.MAX_EDGES) * 2 else size * 4)
        for (i in 0 until size) {
            val l = left[i]
            val r = right[i]
            if (edgeStride == 1 || i % edgeStride == 0) {
                if (l in 0 until size) g.line(xs[i], ys[i], xs[l], ys[l], edgeW, shade(depth[l]), 0.55f)
                if (r in 0 until size) g.line(xs[i], ys[i], xs[r], ys[r], edgeW, shade(depth[r]), 0.55f)
            }
        }
        // A hundred thousand nodes are a texture, not a diagram: past the
        // threshold the nodes go through the point path and the edges thin out.
        if (size > Draw.AS_POINTS) {
            g.reservePoints(size)
            for (i in 0 until size) {
                // World units, like every point size: 1.6 was the size of
                // the whole screen, clamped to the renderer's largest dot.
                g.point(xs[i], ys[i], dotSize * 1.6f, shade(depth[i]))
            }
        } else
        for (i in 0 until size) {
            // A dark rim, so neighbours that touch stay two nodes, not a blob.
            g.disc(xs[i], ys[i], dotSize * 1.25f, Palette.BG)
            g.disc(xs[i], ys[i], dotSize, shade(depth[i]))
        }
        val complete = size >= target
        // The newest node, lit.
        if (size > 1 && size <= Draw.AS_POINTS && !complete) {
            val k = size - 1
            g.glow(xs[k], ys[k], dotSize * 5f, Palette.TEXT_PRIMARY, 0.5f)
            g.disc(xs[k], ys[k], dotSize * 1.1f, Palette.TEXT_PRIMARY)
        }
        // The finished tree's answer: its longest search, root to deepest
        // leaf, traced in white. "depth" in the counter is this path, and it
        // is what random vs sorted keys is about; the finale used to leave the
        // last-inserted node lit instead, a node that meant nothing.
        if (complete && size > 1 && size <= Draw.AS_POINTS) drawDeepest(g, dotSize, edgeW)
    }

    private fun drawDeepest(g: G, dotSize: Float, edgeW: Float) {
        var deepest = 0
        for (i in 0 until size) if (depth[i] > depth[deepest]) deepest = i
        val key = keys[deepest]
        var v = 0
        var guard = size
        val w = max(edgeW * 2.2f, 0.004f)
        while (guard-- > 0) {
            val next = if (v == deepest) -1 else if (key < keys[v]) left[v] else right[v]
            if (next < 0 || next >= size) break
            g.line(xs[v], ys[v], xs[next], ys[next], w, Palette.TEXT_PRIMARY, 0.9f)
            g.disc(xs[v], ys[v], dotSize * 1.05f, Palette.TEXT_PRIMARY)
            v = next
        }
        g.glow(xs[deepest], ys[deepest], dotSize * 5f, Palette.TEXT_PRIMARY, 0.45f)
        g.disc(xs[deepest], ys[deepest], dotSize * 1.3f, Palette.TEXT_PRIMARY)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = size >= target && restSeconds in -1000f..0f

    override fun counters(): List<Pair<String, String>> = listOf(
        "nodes" to size.toString(),
        "depth" to maxDepth.toString(),
        "ideal" to (Math.log(max(1, size).toDouble()) / Math.log(2.0)).toInt().toString()
    )

    private object Draw {
        const val DEPTH_SPAN = 0.8f

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
 * Every wire is drawn in its real state, one gate delay per bit: within each
 * tick the carry works its way up the chain and each sum bit changes only
 * when it arrives — the wait that is the reason fast adders exist at all.
 */
class LogicGatesSim : Sim {

    private var bits = 8
    private var a = 0
    private var b = 0
    private var carryBits = BooleanArray(0)
    private var sumBits = BooleanArray(0)
    private var prevCarry = BooleanArray(0)
    private var prevSum = BooleanArray(0)
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
        rolloverPending = false
        rollovers = 0
        historyHead = 0
        historyCount = 0
        history[historyHead] = a
        historyHead = 1
        historyCount = 1
        compute()
    }

    private fun compute() {
        // What the outputs read until the new carry reaches them: a ripple
        // adder's sum bits hold their old values, then change in turn.
        prevCarry = carryBits.copyOf()
        prevSum = sumBits.copyOf()
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
            // A rollover still rippling when the next tick starts has, on
            // screen, just reached the top lamp (the new tick redraws it whole).
            rollover(env)
            a = (a + b) and ((1 shl bits) - 1)
            history[historyHead] = a
            historyHead = (historyHead + 1) % TAPE
            if (historyCount < TAPE) historyCount++
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
            // The count rolling over — a carry out of the top bit — is the
            // moment worth waiting for. Only with an addend of one is it always
            // a carry run the whole length of the chain; with a bigger step the
            // low bits may never carry at all (an even step never carries out
            // of bit 0), so waiting for a full-length ripple would wait
            // forever. Said as what it is.
            if (carryBits[bits]) rolloverPending = true
        }
        // The carry out lights when the ripple reaches it, one gate delay per
        // bit into the tick (see draw's stage); it used to sound and pay off at
        // the start of the tick, a ripple's length before the lamp came on.
        if (rolloverPending && tick * (bits + 1) >= bits) rollover(env)
    }

    private var rolloverPending = false
    private var rollovers = 0

    private fun rollover(env: SimEnv) {
        if (!rolloverPending) return
        rolloverPending = false
        payoffFrame = true
        rollovers++
        // The first is the payoff, which the engine sounds itself; a second
        // DONE on the same step played the ending twice.
        if (rollovers > 1) env.emit(EventKind.DONE, 0.9f, -0.8f, 1f)
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        // Drawn as the circuit it is: one full-adder cell per bit, most
        // significant on the left, A and B wired in from above, the sum wired
        // out below, and the carry wire running cell to cell from the right —
        // with the carry out of the top cell on its own lamp at the far left.
        // It used to be four rows of unconnected squares and dots, which said
        // nothing about where the carry goes.
        val halfW = env.halfWidth * 0.94f
        val colW = 2f * halfW / (bits + 1)
        fun colX(i: Int) = -halfW + (i + 1.5f) * colW   // column of bit (bits - 1 - i)
        val dim = 0xFF3A4050.toInt()
        val inA = palette.at(0.15f)
        val inB = palette.at(0.55f)
        val carryC = Palette.WARNING
        val on = Palette.SUCCESS
        val wire = max(0.004f, min(0.008f, colW * 0.05f))
        // Laid out down the whole height of a phone, with the count's recent
        // history as a tape at the bottom (see drawTape); squeezed into the
        // middle it left the top and bottom fifths empty.
        val yA = 0.84f
        val yB = 0.64f
        val yBox = 0.24f
        val boxHW = colW * 0.27f
        val boxHH = min(0.15f, colW * 0.85f)
        val ySum = -0.2f
        val off = colW * 0.13f
        val bitHW = min(colW * 0.11f, 0.035f)
        val bitHH = bitHW * 1.2f
        g.reserveInstances(bits * 16 + 24 + bits * TAPE)
        // How far along the chain the carry has got in this tick: one gate
        // delay per bit, so bit k's carry and sum settle after the k below
        // it. Everything further up still shows what it showed before.
        val stage = tick.coerceIn(0f, 1f) * (bits + 1)
        fun carryAt(k: Int) = if (k <= stage) carryBits[k] else prevCarry.getOrElse(k) { false }
        fun sumAt(k: Int) = if (k + 1 <= stage) sumBits[k] else prevSum.getOrElse(k) { false }
        for (i in 0 until bits) {
            val k = bits - 1 - i
            val x = colX(i)
            val aBit = (a shr k) and 1 == 1
            val bBit = (b shr k) and 1 == 1
            // Input wires, then the input bits on top of them.
            g.line(x - off, yA, x - off, yBox + boxHH, wire, if (aBit) inA else dim)
            g.line(x + off, yB, x + off, yBox + boxHH, wire, if (bBit) inB else dim)
            g.rect(x - off, yA, bitHW, bitHH, if (aBit) inA else dim)
            g.rect(x + off, yB, bitHW, bitHH, if (bBit) inB else dim)
            // Carry in from the cell to the right (bit k - 1), or the 0 fed
            // into the bottom bit.
            val cin = carryAt(k)
            val rightEdge = if (i == bits - 1) x + colW * 0.5f else colX(i + 1) - boxHW
            if (cin) g.glow((x + boxHW + rightEdge) * 0.5f, yBox, colW * 0.35f, carryC, 0.45f)
            g.line(x + boxHW, yBox, rightEdge, yBox, wire * 1.8f, if (cin) carryC else dim)
            // The sum wire and lamp.
            val sb = sumAt(k)
            g.line(x, yBox - boxHH, x, ySum, wire, if (sb) on else dim)
            if (sb) g.glow(x, ySum, colW * 0.55f, on, 0.35f)
            g.rect(x, ySum, colW * 0.3f, 0.075f, if (sb) on else dim)
            // The adder cell, edged in the carry colour while the ripple is
            // passing through it.
            val front = stage.toInt() == k && tick < 1f && carryBits[k + 1]
            g.rect(x, yBox, boxHW + 0.006f, boxHH + 0.006f, if (front) carryC else 0xFF555C6C.toInt())
            g.rect(x, yBox, boxHW, boxHH, 0xFF10131A.toInt())
            // A plus sign, so the box reads as an adder.
            val ps = boxHW * 0.45f
            g.rect(x, yBox, ps, wire * 0.8f, Palette.TEXT_SECONDARY, alpha = 0.7f)
            g.rect(x, yBox, wire * 0.8f, ps, Palette.TEXT_SECONDARY, alpha = 0.7f)
        }
        // Carry out of the top bit: the count rolling over.
        val cout = carryAt(bits)
        val xo = -halfW + 0.5f * colW
        g.line(xo, yBox, colX(0) - boxHW, yBox, wire * 1.8f, if (cout) carryC else dim)
        if (cout) g.glow(xo, yBox, colW * 0.8f, carryC, 0.5f)
        g.disc(xo, yBox, colW * 0.22f, if (cout) carryC else dim)
        // The count as a gauge filling towards the roll-over.
        val full = 1 shl bits
        val gy = -0.34f
        val gw = halfW
        g.rect(0f, gy, gw, 0.016f, 0xFF1A1E28.toInt())
        val frac = a.toFloat() / full
        if (frac > 0f) g.rect(-gw + gw * frac, gy, gw * frac, 0.016f, palette.at(0.35f))
        // The tape: the register's last values, newest on top, one row per
        // tick, in the same columns as the bits. Counting in binary draws its
        // own pattern: each column flips half as often as the one to its right.
        val tTop = -0.42f
        val tBottom = -0.95f
        val rowH = (tTop - tBottom) / TAPE
        val sq = min(colW * 0.3f, 0.06f)
        for (rIdx in 0 until min(TAPE, historyCount)) {
            val v = history[(historyHead - 1 - rIdx + TAPE * 2) % TAPE]
            val y = tTop - (rIdx + 0.5f) * rowH
            val fade = 1f - rIdx.toFloat() / TAPE
            for (i in 0 until bits) {
                val k = bits - 1 - i
                val lit = (v shr k) and 1 == 1
                if (lit) g.rect(colX(i), y, sq, rowH * 0.4f, inA, alpha = 0.25f + 0.65f * fade)
                else g.rect(colX(i), y, sq, rowH * 0.4f, dim, alpha = 0.35f * fade)
            }
        }
    }

    private val history = IntArray(TAPE)
    private var historyHead = 0
    private var historyCount = 0

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "value" to a.toString(),
        "binary" to a.toString(2).padStart(bits, '0')
    )

    companion object {
        /** Rows of the history tape. */
        private const val TAPE = 24
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
    private var voicedBest = Float.MAX_VALUE
    private var payoffFrame = false
    private var elapsed = 0f
    private var converged = false
    private var convergedFor = 0f
    private var bumps = 5f
    private var momentum = 0.86f
    private var rate = 0.008f
    private var gridX = 0
    private var gridY = 0
    private var settled = BooleanArray(0)
    private var stillFor = FloatArray(0)
    private var settledCount = 0
    /** Each walker's path so far, a ring of [TRAIL] points spaced [TRAIL_STEP] apart. */
    private var trailX = FloatArray(0)
    private var trailY = FloatArray(0)
    private var trailHead = IntArray(0)
    private var trailLen = IntArray(0)
    /** When each walker last landed, for the landing ring. */
    private var landedAt = FloatArray(0)
    /** The lowest point of the surface on screen: landing there is finding the bottom. */
    private var minX = 0f
    private var minY = 0f
    private var minLoss = 0f
    /** Contour segments (x1, y1, x2, y2, level t), built once per surface. */
    private var contours = FloatArray(0)
    private var contourCount = 0
    private var contourHalfW = -1f

    override fun setup(env: SimEnv) {
        val p = env.params
        walkers = p.i("walkers", 24).coerceIn(1, 400)
        settled = BooleanArray(walkers)
        stillFor = FloatArray(walkers)
        settledCount = 0
        bumps = p.f("bumps", 5f)
        momentum = p.f("momentum", 0.86f)
        rate = p.f("rate", 0.008f)
        wx = FloatArray(walkers); wy = FloatArray(walkers)
        vx = FloatArray(walkers); vy = FloatArray(walkers)
        for (i in 0 until walkers) {
            wx[i] = env.rng.range(-env.halfWidth * 0.9f, env.halfWidth * 0.9f)
            wy[i] = env.rng.range(-0.9f, 0.9f)
        }
        trailX = FloatArray(walkers * TRAIL); trailY = FloatArray(walkers * TRAIL)
        trailHead = IntArray(walkers); trailLen = IntArray(walkers)
        for (i in 0 until walkers) pushTrail(i)
        landedAt = FloatArray(walkers) { -10f }
        // Square tiles, about six thousand of them whatever the aspect: a fixed
        // 44 x 70 grid was visibly blocky on a phone and stretched on a tablet.
        val halfW = env.halfWidth
        val cell = sqrt(4f * halfW / 6000f)
        gridX = ceil(2f * halfW / cell).toInt().coerceIn(8, 400)
        gridY = ceil(2f / cell).toInt().coerceIn(8, 400)
        findMinimum(halfW)
        contourHalfW = -1f
        best = Float.MAX_VALUE
        voicedBest = Float.MAX_VALUE
        payoffFrame = false
        elapsed = 0f
        converged = false
        convergedFor = 0f
    }

    private fun pushTrail(i: Int) {
        val base = i * TRAIL
        if (trailLen[i] > 0) {
            val last = base + (trailHead[i] + TRAIL - 1) % TRAIL
            if (hypot(wx[i] - trailX[last], wy[i] - trailY[last]) < TRAIL_STEP) return
        }
        trailX[base + trailHead[i]] = wx[i]
        trailY[base + trailHead[i]] = wy[i]
        trailHead[i] = (trailHead[i] + 1) % TRAIL
        if (trailLen[i] < TRAIL) trailLen[i]++
    }

    /** A coarse scan then a short descent from the best cell: the screen's true lowest point. */
    private fun findMinimum(halfW: Float) {
        var bx = 0f; var by = 0f; var bl = Float.MAX_VALUE
        val n = 120
        for (r in 0..n) for (c in 0..n) {
            val x = -halfW + 2f * halfW * c / n
            val y = -1f + 2f * r / n
            val l = loss(x, y)
            if (l < bl) { bl = l; bx = x; by = y }
        }
        repeat(400) {
            bx = (bx - 0.02f * gradX(bx, by)).coerceIn(-halfW, halfW)
            by = (by - 0.02f * gradY(bx, by)).coerceIn(-1f, 1f)
        }
        minX = bx; minY = by; minLoss = loss(bx, by)
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
        var lowestX = 0f
        for (i in 0 until walkers) {
            vx[i] = vx[i] * momentum - gradX(wx[i], wy[i]) * rate
            vy[i] = vy[i] * momentum - gradY(wx[i], wy[i]) * rate
            // Kept a walker's width inside the screen: pinned on the very edge,
            // half of it was drawn off the side.
            val edgeX = env.halfWidth - EDGE_INSET
            wx[i] = (wx[i] + vx[i] * dt * 6f).coerceIn(-edgeX, edgeX)
            wy[i] = (wy[i] + vy[i] * dt * 6f).coerceIn(-1f + EDGE_INSET, 1f - EDGE_INSET)
            pushTrail(i)
            val l = loss(wx[i], wy[i])
            if (l < lowest) { lowest = l; lowestX = wx[i] }
            // Each walker lands with its own note, pitched by how deep its dip
            // is. The sim used to sound only when the overall best improved,
            // which stops within three seconds, and then held six seconds of
            // silence while the rest of the walkers were still rolling in.
            val speed = hypot(vx[i], vy[i]) * 6f
            if (!settled[i]) {
                // Still, and at the bottom of something: slow alone counted a
                // walker just setting off, or creeping down a gentle slope.
                val slope = hypot(gradX(wx[i], wy[i]), gradY(wx[i], wy[i]))
                stillFor[i] = if (speed < SETTLE_SPEED && slope < SETTLE_SLOPE) stillFor[i] + abs(dt) else 0f
                if (stillFor[i] > SETTLE_SECONDS) {
                    settled[i] = true
                    settledCount++
                    landedAt[i] = elapsed
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
        }
        // A note each time the best has gone a step deeper, not each step it
        // improved at all: a walker sliding down a slope bettered it on every
        // step, seventy notes a second of one unchanging descent.
        if (best < voicedBest - 0.03f) {
            voicedBest = best
            env.emit(EventKind.STEP, (1f - (best + 0.3f) / 1.6f).coerceIn(0f, 1f),
                (lowestX / env.halfWidth).coerceIn(-1f, 1f), 0.35f)
        }
        // Done when every walker has come to rest, wherever it rests: the
        // question the sim asks is which ones find the bottom.
        if (!converged && elapsed > MIN_SECONDS && (settledCount == walkers || elapsed > BACKSTOP_SECONDS)) {
            converged = true
            payoffFrame = true
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
        if (contourHalfW != halfW) buildContours(halfW)
        val trailBudget = max(2, min(TRAIL, 2400 / max(1, walkers)))
        g.reserveInstances(gridX * gridY + contourCount + walkers * (trailBudget + 4) + 4)
        val dx = 2f * halfW / gridX
        val dy = 2f / gridY
        // The surface as terrain: colour by height, lower ground darker so the
        // eye falls into the valleys, and lit from the upper left by its own
        // slope so the bumps read as hills and dips. Flat colour alone read as
        // a plain gradient with the dips invisible.
        for (r in 0 until gridY) {
            val y = -1f + (r + 0.5f) * dy
            for (c in 0 until gridX) {
                val x = -halfW + (c + 0.5f) * dx
                val t = heightT(x, y, halfW)
                val gx = gradX(x, y) * RELIEF
                val gy = gradY(x, y) * RELIEF
                val lambert = ((gx * 0.5f - gy * 0.5f + 0.707f) / sqrt(gx * gx + gy * gy + 1f)).coerceIn(0f, 1f)
                // Only the cool first half of the ramp, and kept low: Viridis'
                // yellow-green peaks were the brightest thing on screen and the
                // same hues as the amber and green landing marks they hid.
                val base = Palette.lerp(palette.at(TERRAIN_T0 + TERRAIN_SPAN * t), Palette.BG, 0.74f - 0.3f * t)
                val shaded = if (lambert >= 0.707f) Palette.lerp(base, Palette.TEXT_PRIMARY, (lambert - 0.707f) * 0.35f)
                    else Palette.lerp(base, Palette.BG, (0.707f - lambert) * 0.9f)
                // Overlapping by a fifth: a hair over left a faint tile grid.
                g.rect(x, y, dx * 0.6f, dy * 0.6f, shaded)
            }
        }
        // Contour lines, every equal step of the colour scale.
        for (k in 0 until contourCount) {
            val o = k * 5
            val t = contours[o + 4]
            g.line(contours[o], contours[o + 1], contours[o + 2], contours[o + 3], 0.0028f,
                Palette.lerp(palette.at(TERRAIN_T0 + TERRAIN_SPAN * t), Palette.TEXT_PRIMARY, 0.35f), 0.22f + 0.2f * t)
        }
        // The true bottom, marked, so it is plain which walkers found it.
        g.ring(minX, minY, 0.03f, 0.004f, Palette.SUCCESS, alpha = 0.55f)
        g.disc(minX, minY, 0.005f, Palette.SUCCESS, 0.7f)
        // Each walker's path down, fading towards where it started.
        for (i in 0 until walkers) {
            val n = min(trailLen[i], trailBudget)
            if (n < 2) continue
            val col = walkerColour(i)
            val base = i * TRAIL
            var px = 0f; var py = 0f
            for (j in 0 until n) {
                val idx = base + (trailHead[i] + TRAIL - n + j) % TRAIL
                val x = trailX[idx]; val y = trailY[idx]
                if (j > 0) {
                    val k = j.toFloat() / (n - 1)
                    g.line(px, py, x, y, 0.004f + 0.003f * k, col, 0.15f + 0.6f * k)
                }
                px = x; py = y
            }
            // Joined to where the walker is now, not where it was last sampled.
            g.line(px, py, wx[i], wy[i], 0.007f, col, 0.75f)
        }
        val r = 0.016f
        for (i in 0 until walkers) {
            val col = walkerColour(i)
            if (!settled[i]) g.glow(wx[i], wy[i], 0.05f, Palette.TEXT_PRIMARY, 0.3f)
            else {
                // A ring thrown out on landing, and a soft glow left behind.
                val k = ((elapsed - landedAt[i]) / 0.7f)
                if (k in 0f..1f) g.ring(wx[i], wy[i], r + 0.06f * k, 0.005f, col, alpha = 1f - k)
                g.glow(wx[i], wy[i], 0.045f, col, 0.35f)
            }
            g.disc(wx[i], wy[i], r * 1.45f, Palette.BG)
            g.disc(wx[i], wy[i], r, col)
        }
    }

    /**
     * White while rolling; once landed, green at the true bottom and amber in
     * a dip that is not the bottom: the answer to the question the sim asks.
     */
    private fun walkerColour(i: Int): Int = when {
        !settled[i] -> Palette.TEXT_PRIMARY
        loss(wx[i], wy[i]) <= minLoss + 0.015f -> Palette.SUCCESS
        else -> Palette.WARNING
    }

    /** Height on the colour scale, square-rooted to spread the low ground. */
    private fun heightT(x: Float, y: Float, halfW: Float): Float {
        val lo = -0.25f
        val hi = (halfW * halfW + 1f) * 0.6f + 0.22f
        return sqrt(((loss(x, y) - lo) / (hi - lo)).coerceIn(0f, 1f))
    }

    /** Marching squares over a fine grid, once: the surface only changes in setup. */
    private fun buildContours(halfW: Float) {
        contourHalfW = halfW
        val cell = sqrt(4f * halfW / 9000f)
        val nx = ceil(2f * halfW / cell).toInt().coerceIn(8, 300)
        val ny = ceil(2f / cell).toInt().coerceIn(8, 300)
        val sx = 2f * halfW / nx
        val sy = 2f / ny
        val h = FloatArray((nx + 1) * (ny + 1))
        for (r in 0..ny) for (c in 0..nx) h[r * (nx + 1) + c] = heightT(-halfW + c * sx, -1f + r * sy, halfW)
        val out = ArrayList<Float>()
        for (lv in 1 until CONTOURS) {
            val t = lv.toFloat() / CONTOURS
            for (r in 0 until ny) for (c in 0 until nx) {
                val i0 = r * (nx + 1) + c
                val a = h[i0]; val b = h[i0 + 1]; val d = h[i0 + nx + 1]; val e = h[i0 + nx + 2]
                // Corners: a bottom-left, b bottom-right, e top-right, d top-left.
                val pts = FloatArray(8)
                var np = 0
                val x0 = -halfW + c * sx; val y0 = -1f + r * sy
                fun edge(v1: Float, v2: Float, ax: Float, ay: Float, bx: Float, by: Float) {
                    if ((v1 < t) != (v2 < t) && np < 8) {
                        val f = (t - v1) / (v2 - v1)
                        pts[np++] = ax + (bx - ax) * f; pts[np++] = ay + (by - ay) * f
                    }
                }
                edge(a, b, x0, y0, x0 + sx, y0)
                edge(b, e, x0 + sx, y0, x0 + sx, y0 + sy)
                edge(e, d, x0 + sx, y0 + sy, x0, y0 + sy)
                edge(d, a, x0, y0 + sy, x0, y0)
                var k = 0
                while (k + 3 < np) {
                    out.add(pts[k]); out.add(pts[k + 1]); out.add(pts[k + 2]); out.add(pts[k + 3]); out.add(t)
                    k += 4
                }
            }
        }
        contours = out.toFloatArray()
        contourCount = contours.size / 5
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "best loss" to String.format(java.util.Locale.US, "%.4f", best),
        "landed" to "$settledCount / $walkers"
    )

    companion object {
        /** The ground has to be about level, too, for a walker to have landed. */
        const val SETTLE_SLOPE = 0.03f
        const val EDGE_INSET = 0.024f
        const val TRAIL = 64
        const val TRAIL_STEP = 0.014f
        /** Contour levels across the colour scale. */
        const val CONTOURS = 14
        /** How steep the slopes look to the light: enough to model the bumps. */
        const val RELIEF = 0.9f
        /** The stretch of the palette the terrain takes its colour from. */
        const val TERRAIN_T0 = 0.08f
        const val TERRAIN_SPAN = 0.52f
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
                // Five, not three: at three a phone-width screen held one smooth
                // bowl and every walker found the bottom — no dip to get stuck in,
                // which is the whole point of the sim.
                Param.float("bumps", "Bumpiness", 0f, 10f, 5f, card = Card.DATA),
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
    private var peakEnergy = 0f
    private var lowestEnergy = Float.MAX_VALUE
    private var flatFor = 0f
    private var settledAt = -1f

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
    /** Each node's colour position, from where it sat in the hidden field. */
    private var hue = FloatArray(0)
    private var degree = IntArray(0)

    override fun setup(env: SimEnv) {
        fitScale = 1f
        fitX = 0f
        fitY = 0f
        val p = env.params
        n = p.i("nodes", 70).coerceIn(3, 900)
        px = FloatArray(n); py = FloatArray(n)
        vx = FloatArray(n); vy = FloatArray(n)
        for (i in 0 until n) {
            // Scrambled into a small seed that the forces unfold. Scattered
            // wide, the graph locked into its first folds and stayed crossed.
            px[i] = env.rng.range(-SEED_SPREAD, SEED_SPREAD)
            py[i] = env.rng.range(-SEED_SPREAD, SEED_SPREAD)
        }
        val extra = p.f("extra", 0.35f)
        val maxEdges = n - 1 + (n * extra).toInt()
        edgeA = IntArray(maxEdges)
        edgeB = IntArray(maxEdges)
        edges = 0
        // The graph is built over hidden positions in a screen-shaped field,
        // each node linked only to near neighbours there, and then scrambled.
        // Random pairs anywhere made a graph with no good drawing at all: the
        // layout settled into a ball of crossing edges however long it ran.
        // A near-neighbour graph is close to planar, so untangling it has a
        // real answer to find, and the colours (by hidden position) come back
        // into a smooth gradient when it does.
        val fw = env.halfWidth.coerceIn(0.3f, 2f)
        val hx = FloatArray(n) { env.rng.range(-fw, fw) }
        val hy = FloatArray(n) { env.rng.range(-1f, 1f) }
        hue = FloatArray(n) { ((hy[it] + 1f) * 0.5f * 0.75f + (hx[it] / fw + 1f) * 0.5f * 0.25f).coerceIn(0f, 1f) }
        // Each pair once: a repeated pair pulled twice as hard.
        val seen = HashSet<Long>()
        fun key(a: Int, b: Int): Long = min(a, b).toLong() * n + max(a, b)
        fun d2(a: Int, b: Int): Float { val dx = hx[a] - hx[b]; val dy = hy[a] - hy[b]; return dx * dx + dy * dy }
        // A spanning tree first, so the graph is always one connected piece:
        // each node joins the nearest of those before it.
        for (i in 1 until n) {
            var best = 0
            var bd = Float.MAX_VALUE
            for (j in 0 until i) { val d = d2(i, j); if (d < bd) { bd = d; best = j } }
            edgeA[edges] = i
            edgeB[edges] = best
            seen.add(key(i, best))
            edges++
        }
        // Each node's nearest few, for the extra edges.
        val near = IntArray(n * NEAR)
        val nearD = FloatArray(NEAR)
        for (i in 0 until n) {
            java.util.Arrays.fill(nearD, Float.MAX_VALUE)
            for (k in 0 until NEAR) near[i * NEAR + k] = -1
            for (j in 0 until n) {
                if (j == i) continue
                val d = d2(i, j)
                if (d >= nearD[NEAR - 1]) continue
                var k = NEAR - 1
                while (k > 0 && nearD[k - 1] > d) { nearD[k] = nearD[k - 1]; near[i * NEAR + k] = near[i * NEAR + k - 1]; k-- }
                nearD[k] = d; near[i * NEAR + k] = j
            }
        }
        val possible = n.toLong() * (n - 1) / 2
        val target = min(maxEdges.toLong(), possible).toInt()
        var tries = 0
        while (edges < target && tries++ < target * 40 + 100) {
            val a = env.rng.int(0, n)
            val b = near[a * NEAR + env.rng.int(0, NEAR)]
            if (b >= 0 && a != b && seen.add(key(a, b))) {
                edgeA[edges] = a
                edgeB[edges] = b
                edges++
            }
        }
        // Past what near neighbours can supply (a dense remix), any pair.
        while (edges < target && tries++ < target * 80 + 200) {
            val a = env.rng.int(0, n)
            val b = env.rng.int(0, n)
            if (a != b && seen.add(key(a, b))) { edgeA[edges] = a; edgeB[edges] = b; edges++ }
        }
        degree = IntArray(n)
        for (e in 0 until edges) { degree[edgeA[e]]++; degree[edgeB[e]]++ }
        energy = 1f
        peakEnergy = 0f
        lowestEnergy = Float.MAX_VALUE
        flatFor = 0f
        settledAt = -1f
        payoffFrame = false
        settled = false
        elapsed = 0f
        tickCarry = 0f
    }

    private var tickCarry = 0f

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        val spring = env.params.f("spring", 1f) * 2.2f
        val ideal = idealLength()
        // Repulsion on the same scale as the springs (Fruchterman–Reingold's
        // k²/d). It was a hundred times weaker: the graph shrank into a ball
        // whose edges were three node-spacings long, every one crossing
        // dozens of others. Stronger for the first seconds, so the graph
        // spreads out before its springs lock any folds in.
        val repel = env.params.f("repel", 1f) * ideal * ideal * (1f + 2f * max(0f, 1f - elapsed / 8f))

        // Pulled to the centre harder across the screen's short side than its
        // long one, so the graph settles into the screen's shape. Pulled
        // evenly it settled round, and a round graph on an upright phone was
        // framed by the width: a knot across the middle, the top and bottom
        // thirds empty.
        val hwScreen = env.halfWidth.coerceIn(0.2f, 5f)
        // (Gently: as the cube of the aspect it squeezed the graph sideways
        // into crossings.)
        val aniso = hwScreen
        val kx = if (hwScreen < 1f) 0.25f / aniso else 0.25f
        val ky = if (hwScreen < 1f) 0.25f else 0.25f * aniso
        val damp = kotlin.math.exp(-DAMP_RATE * abs(dt))
        for (i in 0 until n) {
            var fx = -px[i] * kx
            var fy = -py[i] * ky
            for (j in 0 until n) {
                if (i == j) continue
                val dx = px[i] - px[j]
                val dy = py[i] - py[j]
                val d2 = dx * dx + dy * dy + 0.0006f
                val f = repel / d2
                fx += dx * f
                fy += dy * f
            }
            vx[i] = (vx[i] + fx * dt) * damp
            vy[i] = (vy[i] + fy * dt) * damp
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
        var fastest = 0f
        var fastX = 0f
        for (i in 0 until n) {
            px[i] = (px[i] + vx[i] * dt).coerceIn(-env.halfWidth, env.halfWidth)
            py[i] = (py[i] + vy[i] * dt).coerceIn(-1f, 1f)
            val sp = abs(vx[i]) + abs(vy[i])
            moved += sp
            if (sp > fastest) { fastest = sp; fastX = px[i] }
        }
        energy = energy * 0.9f + (moved / n) * 0.1f
        frame(env, dt)
        // Ticks as fast as the graph is moving, up to sixteen a second while it
        // flies apart, thinning as it relaxes and gone once it is still. It
        // was a STEP on every step — a hundred and twenty a second, a flat buzz
        // that went on just the same over the settled, motionless graph.
        val motion = if (peakEnergy > 0f) (energy / peakEnergy).coerceIn(0f, 1f) else 1f
        if (motion > 0.04f) tickCarry += abs(dt) * 16f * kotlin.math.sqrt(motion)
        if (tickCarry >= 1f) {
            tickCarry -= 1f
            env.emit(EventKind.STEP, energy.coerceIn(0f, 1f), (fastX / env.halfWidth).coerceIn(-1f, 1f), 0.15f + 0.25f * motion)
        }
        // Settled: still against what it started with, or no longer getting
        // any stiller, or simply long enough. A fixed 0.012 was never
        // reached by a big graph pushed hard apart.
        if (energy > peakEnergy) peakEnergy = energy
        if (energy < lowestEnergy * 0.98f) { lowestEnergy = energy; flatFor = 0f } else flatFor += abs(dt)
        val calm = energy < 0.012f || energy < peakEnergy * 0.05f || flatFor > 4f || elapsed > 40f
        if (!settled && elapsed > 10f && calm) {
            settled = true
            settledAt = elapsed
            payoffFrame = true
        }
    }

    private fun idealLength(): Float = (0.35f / sqrt(n.toFloat())).coerceIn(0.01f, 0.25f)

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
        g.reserveInstances(edges + n * 2)
        val ideal = idealLength()
        // Edges in their nodes' blended colour, faded by how stretched they
        // are: once the graph has untangled the short local edges carry the
        // shape and the few long ones recede, instead of every edge being one
        // grey mesh over everything.
        for (e in 0 until edges) {
            val a = edgeA[e]
            val b = edgeB[e]
            val len = hypot(px[b] - px[a], py[b] - py[a])
            val stretch = ((len / ideal - 1.3f) / 2f).coerceIn(0f, 1f)
            val c = Palette.lerp(Palette.TEXT_SECONDARY, palette.at((hue[a] + hue[b]) * 0.5f), 0.7f)
            g.line(
                (px[a] - fitX) * s, (py[a] - fitY) * s, (px[b] - fitX) * s, (py[b] - fitY) * s,
                0.005f - 0.0025f * stretch, c, 0.8f - 0.55f * stretch
            )
        }
        // Grown with the framing, but not all the way: at full scale the
        // shipped nodes would be coins, and at 0.02 they overlapped in clumps.
        // Hubs a little larger than leaves, so the structure reads.
        val r = (max(0.004f, min(0.013f, 0.1f / sqrt(n.toFloat()))) * kotlin.math.sqrt(s)).coerceAtMost(0.02f)
        for (i in 0 until n) {
            val x = (px[i] - fitX) * s
            val y = (py[i] - fitY) * s
            val ri = r * (0.75f + 0.12f * min(degree[i], 6))
            // A dark rim keeps nodes that touch apart.
            g.disc(x, y, ri * 1.3f, Palette.BG)
            g.disc(x, y, ri, palette.at(hue[i]))
        }
    }

    override fun payoff(): Boolean = payoffFrame

    /** The settled shape held a few seconds, then a new graph: it sat forever. */
    override fun finished(): Boolean = settledAt >= 0f && elapsed - settledAt > 4f

    override fun counters(): List<Pair<String, String>> = listOf(
        "nodes" to n.toString(),
        "edges" to edges.toString()
    )

    companion object {
        /** Share of the screen the graph is framed to fill. */
        private const val FILL = 0.8f
        /** Near neighbours each node may take an extra edge to. */
        private const val NEAR = 5
        /**
         * Velocity decay per second. At 0.86 a step (18/s) the nodes crawled
         * through treacle and froze wherever they first landed.
         */
        private const val DAMP_RATE = 1.2f
        private const val SEED_SPREAD = 0.02f

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
                Param.float("extra", "Extra edges", 0f, 2f, 0.35f, card = Card.DATA),
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
        PackSim.def(),
        MazeRaceSim.def(),
        BallSortSim.def()
    )
}
