package app.hypnotica.sims.algorithms

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Macro
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.recipe.ParamInput
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.Rng
import app.hypnotica.core.sim.Shelf
import app.hypnotica.core.sim.Sim
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.sim.SimEnv
import app.hypnotica.core.sim.TrapFormat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A backtracking solver filling a real Sudoku: try a digit, check its row,
 * column and box, move on or take it back.
 *
 * Plain backtracking walks the empty cells in reading order and tries 1 to 9
 * in each, so every refusal (a red digit, and the cell that clashes with it)
 * and every retreat (a cell wiped back to empty) is on the board. With
 * constraint propagation on, it always fills the cell with the fewest digits
 * left — a forced cell first, which is what propagating a constraint is — and
 * the same puzzle falls in a few dozen moves instead of thousands.
 *
 * The puzzles are real ones with a single solution, shuffled by the moves that
 * keep a Sudoku a Sudoku (relabelled digits, rows swapped inside a band, bands
 * swapped, the same for columns, a transpose), so each seed is a fresh grid of
 * the same difficulty.
 */
class SudokuSim : Sim {

    /**
     * The solver as a machine that does one thing per [act]: test one digit,
     * place it, or take one back. The same machine counts the run in setup and
     * plays it live, so the count is exact.
     */
    private class Solver(givens: IntArray, val propagate: Boolean) {
        val grid = givens.copyOf()
        val rowM = IntArray(9); val colM = IntArray(9); val boxM = IntArray(9)
        val empties: IntArray
        // Plain: the digit last tried at each empty, in order.
        val tried: IntArray
        var k = 0
        // Propagating: a stack of (cell, digits not yet tried there).
        val sCell = IntArray(81); val sRem = IntArray(81)
        var depth = 0
        var phase = CHOOSE
        var filled = 0
        var solved = false

        // What the last action did, for the picture and the sound.
        var lastKind = NONE
        var lastCell = -1
        var lastDigit = 0

        init {
            var e = 0
            for (i in 0 until 81) if (grid[i] == 0) e++ else mark(i, grid[i], true)
            empties = IntArray(e)
            var j = 0
            for (i in 0 until 81) if (grid[i] == 0) empties[j++] = i
            tried = IntArray(e)
            filled = 81 - e
        }

        private fun mark(i: Int, d: Int, on: Boolean) {
            val bit = 1 shl d
            val r = i / 9; val c = i % 9; val b = (r / 3) * 3 + c / 3
            if (on) { rowM[r] = rowM[r] or bit; colM[c] = colM[c] or bit; boxM[b] = boxM[b] or bit }
            else { rowM[r] = rowM[r] and bit.inv(); colM[c] = colM[c] and bit.inv(); boxM[b] = boxM[b] and bit.inv() }
        }

        fun used(i: Int): Int { val r = i / 9; val c = i % 9; return rowM[r] or colM[c] or boxM[(r / 3) * 3 + c / 3] }

        private fun place(i: Int, d: Int) { grid[i] = d; mark(i, d, true); filled++ }
        private fun clear(i: Int) { val d = grid[i]; if (d != 0) { mark(i, d, false); grid[i] = 0; filled-- } }

        fun act() {
            if (solved) return
            if (propagate) actMrv() else actPlain()
        }

        private fun actPlain() {
            if (k >= empties.size) { solved = true; lastKind = NONE; return }
            val cell = empties[k]
            clear(cell)
            val v = tried[k] + 1
            if (v > 9) {
                tried[k] = 0
                k--
                lastKind = BACK; lastCell = cell; lastDigit = 0
                return
            }
            tried[k] = v
            lastCell = cell; lastDigit = v
            if (used(cell) and (1 shl v) == 0) {
                place(cell, v)
                k++
                lastKind = PLACE
                if (k >= empties.size) solved = true
            } else {
                lastKind = REJECT
            }
        }

        private fun actMrv() {
            when (phase) {
                CHOOSE -> {
                    if (filled == 81) { solved = true; lastKind = NONE; return }
                    var best = -1; var bestN = 10; var bestMask = 0
                    for (i in 0 until 81) {
                        if (grid[i] != 0) continue
                        val m = FULL and used(i).inv()
                        val n = Integer.bitCount(m)
                        if (n < bestN) { bestN = n; best = i; bestMask = m; if (n <= 1) break }
                    }
                    if (bestN == 0) {
                        // A cell with no digit left: the last choice was wrong.
                        lastKind = DEAD; lastCell = best; lastDigit = 0
                        phase = BACK_PHASE
                        return
                    }
                    sCell[depth] = best; sRem[depth] = bestMask; depth++
                    lastKind = FOCUS; lastCell = best; lastDigit = bestN
                    phase = TRY
                }
                TRY -> {
                    val f = depth - 1
                    val cell = sCell[f]
                    if (sRem[f] == 0) {
                        depth--
                        lastKind = BACK; lastCell = cell; lastDigit = 0
                        phase = BACK_PHASE
                        return
                    }
                    val bit = Integer.lowestOneBit(sRem[f])
                    sRem[f] = sRem[f] and bit.inv()
                    val d = Integer.numberOfTrailingZeros(bit)
                    place(cell, d)
                    lastKind = PLACE; lastCell = cell; lastDigit = d
                    phase = CHOOSE
                    if (filled == 81) solved = true
                }
                else -> {
                    // Take back the newest placement, then try its next digit.
                    if (depth == 0) { solved = true; return }
                    val cell = sCell[depth - 1]
                    clear(cell)
                    lastKind = WIPE; lastCell = cell; lastDigit = 0
                    phase = TRY
                }
            }
        }

        /** The clue or placed digit in [cell]'s row, column or box that equals [d], or -1. */
        fun clash(cell: Int, d: Int): Int {
            val r = cell / 9; val c = cell % 9
            for (j in 0 until 9) {
                if (j != c && grid[r * 9 + j] == d) return r * 9 + j
                if (j != r && grid[j * 9 + c] == d) return j * 9 + c
            }
            val br = (r / 3) * 3; val bc = (c / 3) * 3
            for (a in 0 until 3) for (b in 0 until 3) {
                val i = (br + a) * 9 + bc + b
                if (i != cell && grid[i] == d) return i
            }
            return -1
        }

        companion object {
            const val FULL = 0x3FE
            const val CHOOSE = 0; const val TRY = 1; const val BACK_PHASE = 2
            const val NONE = 0; const val PLACE = 1; const val REJECT = 2; const val BACK = 3
            const val DEAD = 4; const val FOCUS = 5; const val WIPE = 6
        }
    }

    private var givens = IntArray(81)
    private var solver = Solver(IntArray(81), false)
    private var pace = AlgoPace(1, 1f, 1f)
    private var done = 0
    private var tries = 0L
    private var backtracks = 0L
    private var clock = 0f
    private var vclock = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var sinceSound = 1f
    private val clearedAt = FloatArray(81) { -9f }
    private var rejectCell = -1
    private var rejectDigit = 0
    private var rejectAt = -9f
    private var clashCell = -1
    private var deadCell = -1
    private var deadAt = -9f
    private val depthLog = ByteArray(DEPTH_LOG)
    private var depthCount = 0
    private var depthEvery = 1
    private var depthTick = 0
    private var empties = 1

    override fun setup(env: SimEnv) {
        val level = env.params.choiceIndex("level").coerceIn(0, 2)
        val propagate = env.params.b("propagate", false)
        val pool = PUZZLES[level]
        val first = env.rng.int(0, pool.size)
        // Plain backtracking on a hard grid costs anything from thirty thousand
        // tries to millions, depending only on how the shuffle ordered its cells.
        // Count each shuffle and keep the first that a run can play.
        var best: IntArray? = null
        var bestCost = Int.MAX_VALUE
        for (attempt in 0 until 12) {
            val g = shuffled(pool[(first + attempt) % pool.size], env.rng)
            val cost = count(g, propagate, COST_CAP)
            if (cost in 1 until bestCost) { best = g; bestCost = cost }
            if (bestCost <= COST_GOOD) break
        }
        givens = best ?: shuffled(PUZZLES[0][0], env.rng).also { bestCost = count(it, propagate, Int.MAX_VALUE) }
        solver = Solver(givens, propagate)
        empties = solver.empties.size
        // Propagation solves in a few dozen moves; those are worth watching
        // one by one, so they start slower than the plain search's blur.
        pace = AlgoPace(bestCost, SOLVE_SECONDS, if (propagate) 3f else 5f)
        done = 0; tries = 0; backtracks = 0
        clock = 0f; vclock = 0f; doneAt = -1f; payoffFrame = false; sinceSound = 1f
        clearedAt.fill(-9f)
        rejectCell = -1; rejectAt = -9f; clashCell = -1; deadCell = -1; deadAt = -9f
        depthCount = 0; depthEvery = 1; depthTick = 0
    }

    /** Actions to solve [g], or -1 past [cap]. */
    private fun count(g: IntArray, propagate: Boolean, cap: Int): Int {
        val s = Solver(g, propagate)
        var n = 0
        while (!s.solved) {
            s.act(); n++
            if (n > cap) return -1
        }
        return n
    }

    private fun shuffled(src: String, rng: Rng): IntArray {
        val lab = IntArray(10) { it }
        val digits = IntArray(9) { it + 1 }
        rng.shuffle(digits)
        for (d in 1..9) lab[d] = digits[d - 1]
        val rows = order(rng); val cols = order(rng)
        val transpose = rng.bool()
        val out = IntArray(81)
        for (r in 0 until 9) for (c in 0 until 9) {
            val v = src[rows[r] * 9 + cols[c]] - '0'
            val i = if (transpose) c * 9 + r else r * 9 + c
            out[i] = lab[v]
        }
        return out
    }

    /** Bands in a random order, and the three lines inside each band too. */
    private fun order(rng: Rng): IntArray {
        val bands = intArrayOf(0, 1, 2); rng.shuffle(bands)
        val out = IntArray(9)
        var j = 0
        for (b in bands) {
            val inner = intArrayOf(0, 1, 2); rng.shuffle(inner)
            for (x in inner) out[j++] = b * 3 + x
        }
        return out
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        clock += h
        sinceSound += h
        if (doneAt >= 0f) return
        vclock += h * env.params.f("speed", 1f).coerceIn(0.1f, 8f)
        val target = pace.due(vclock)
        var soundKind = Solver.NONE
        var soundCell = -1
        var soundDigit = 0
        var budget = MAX_PER_STEP
        val filledBefore = solver.filled
        var acted = 0
        while (done < target && budget-- > 0 && !solver.solved) {
            solver.act()
            done++
            acted++
            val c = solver.lastCell
            when (solver.lastKind) {
                Solver.PLACE -> {
                    tries++
                    if (soundKind != Solver.BACK) { soundKind = Solver.PLACE; soundCell = c; soundDigit = solver.lastDigit }
                }
                Solver.REJECT -> {
                    tries++
                    rejectCell = c; rejectDigit = solver.lastDigit; rejectAt = clock
                    if (soundKind == Solver.NONE) { soundKind = Solver.REJECT; soundCell = c; soundDigit = solver.lastDigit }
                }
                Solver.BACK -> {
                    backtracks++
                    clearedAt[c] = clock
                    soundKind = Solver.BACK; soundCell = c
                }
                Solver.WIPE -> clearedAt[c] = clock
                Solver.DEAD -> {
                    backtracks++
                    deadCell = c; deadAt = clock
                    soundKind = Solver.BACK; soundCell = c
                }
            }
            logDepth(solver.filled - (81 - empties))
        }
        // The clash shown is the one for the last refusal on screen.
        clashCell = if (clock - rejectAt < REJECT_SECONDS && rejectCell >= 0) solver.clash(rejectCell, rejectDigit) else -1
        if (solver.solved) {
            doneAt = clock
            payoffFrame = true
            logDepth(empties)
            return
        }
        // At full pace thousands of tries go by a frame and some retreat is
        // always among them, so picking "the most telling" event made the last
        // four seconds one low note forty times a second while the board
        // visibly filled. Past a handful of actions a frame the note follows
        // the board instead: its pitch is how many cells are filled (the trace
        // under the board, heard), a placement voice while the frame gained
        // ground and a retreat voice while it lost it.
        if (acted > BLUR_ACTS) {
            if (sinceSound >= BLUR_GAP) {
                sinceSound = 0f
                val depth = (solver.filled - (81 - empties)).toFloat() / empties.coerceAtLeast(1)
                val cc = currentCell()
                val pan = if (cc >= 0) ((cc % 9) - 4) / 4.5f else 0f
                if (solver.filled >= filledBefore) env.emit(EventKind.WRITE, 0.15f + 0.75f * depth, pan, 0.32f)
                else env.emit(EventKind.DEATH, 0.15f + 0.75f * depth, pan, 0.38f)
            }
            return
        }
        // One note per fortieth of a second, the most telling of the batch,
        // keeps a slow search a ticking one. A retreat outranks a placement.
        if (soundKind != Solver.NONE && sinceSound >= SOUND_GAP) {
            sinceSound = 0f
            val pan = ((soundCell % 9) - 4) / 4.5f
            when (soundKind) {
                Solver.PLACE -> env.emit(EventKind.WRITE, soundDigit / 9f, pan, 0.5f)
                Solver.REJECT -> env.emit(EventKind.COMPARE, soundDigit / 9f, pan, 0.25f)
                else -> env.emit(EventKind.DEATH, 0.25f, pan, 0.45f)
            }
        }
    }

    private fun logDepth(d: Int) {
        if (++depthTick < depthEvery) return
        depthTick = 0
        if (depthCount == DEPTH_LOG) {
            for (i in 0 until DEPTH_LOG / 2) depthLog[i] = max(depthLog[2 * i].toInt(), depthLog[2 * i + 1].toInt()).toByte()
            depthCount = DEPTH_LOG / 2
            depthEvery *= 2
        }
        depthLog[depthCount++] = d.coerceIn(0, 81).toByte()
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val halfW = env.halfWidth
        val portrait = halfW < 0.8f
        val side: Float
        val bx: Float
        val by: Float
        var trace = false
        var tl = 0f; var tr = 0f; var tt = 0f; var tb = 0f
        if (portrait) {
            // The board is the hero: a slim strip of trace under it, the pair
            // centred a touch high. (The trace used to take the whole lower
            // half of a phone, a big noisy chart louder than the puzzle.)
            side = min(2f * halfW * 0.94f, 1.3f)
            val th = min(0.17f, side * 0.18f)
            val gap = side * 0.07f
            trace = 2f - side > th + gap + 0.2f
            val groupH = side + if (trace) gap + th else 0f
            by = groupH * 0.5f - side * 0.5f + 0.05f
            bx = 0f
            if (trace) { tl = -side * 0.5f; tr = side * 0.5f; tt = by - side * 0.5f - gap; tb = tt - th }
        } else {
            side = min(1.72f, 2f * halfW * 0.6f)
            val traceW = min(side * 0.8f, 2f * halfW * 0.9f - side - 0.16f)
            trace = traceW > 0.4f
            val total = side + if (trace) 0.16f + traceW else 0f
            bx = -total * 0.5f + side * 0.5f
            by = 0f
            if (trace) { tl = bx + side * 0.5f + 0.16f; tr = tl + traceW; tt = side * 0.16f; tb = -side * 0.16f }
        }
        val cell = side / 9f
        val ox = bx - side * 0.5f
        val oy = by + side * 0.5f
        g.reserveInstances(81 * 9 + DEPTH_LOG * 2 + 32)
        val since = if (doneAt >= 0f) clock - doneAt else -1f
        val solvedMix = if (since >= 0f) (since / 0.5f).coerceIn(0f, 1f) else 0f
        // Grid lines are the gaps between cells, drawn over a lighter backing;
        // the boxes' lines are three times as wide, the way a printed grid is.
        val lineC = Palette.lerp(0xFF2A3142.toInt(), Palette.SUCCESS, solvedMix * 0.6f)
        val pad = cell * 0.09f
        g.rect(bx, by, side * 0.5f + pad, side * 0.5f + pad, lineC)
        val ink = palette.bright(SOLVE_T, 0.08f)
        for (i in 0 until 81) {
            val r = i / 9; val c = i % 9
            val x = ox + (c + 0.5f) * cell
            val y = oy - (r + 0.5f) * cell
            // Thin inset everywhere, a thicker one on box edges.
            val il = if (c % 3 == 0) cell * 0.06f else cell * 0.02f
            val ir = if (c % 3 == 2) cell * 0.06f else cell * 0.02f
            val iu = if (r % 3 == 0) cell * 0.06f else cell * 0.02f
            val id = if (r % 3 == 2) cell * 0.06f else cell * 0.02f
            val cx = x + (il - ir) * 0.5f
            val cy = y + (id - iu) * 0.5f
            val hw = cell * 0.5f - (il + ir) * 0.5f
            val hh = cell * 0.5f - (iu + id) * 0.5f
            val given = givens[i] != 0
            var bg = if (given) GIVEN_BG else EMPTY_BG
            // A cell just wiped by a retreat glows red and fades: at speed the
            // board becomes a heat map of where the search keeps going back.
            val cleared = clock - clearedAt[i]
            if (cleared in 0f..CLEAR_SECONDS && solver.grid[i] == 0) {
                bg = Palette.lerp(bg, Palette.DANGER, 0.45f * (1f - cleared / CLEAR_SECONDS))
            }
            if (i == deadCell && clock - deadAt < CLEAR_SECONDS) {
                bg = Palette.lerp(bg, Palette.DANGER, 0.6f * (1f - (clock - deadAt) / CLEAR_SECONDS))
            }
            if (i == clashCell && doneAt < 0f) bg = Palette.lerp(bg, Palette.DANGER, 0.35f)
            val focus = doneAt < 0f && i == currentCell()
            if (focus) bg = Palette.lerp(bg, ink, 0.28f)
            g.rect(cx, cy, hw, hh, bg)
            val d = solver.grid[i]
            val dh = cell * 0.54f
            if (given) {
                SegDigits.digit(g, cx, cy, dh, d, GIVEN_INK)
            } else if (d != 0) {
                // The finale lights the solver's own digits corner to corner,
                // each easing up once and staying lit (no pulse: the user finds
                // throbbing light disorienting); the clues stay as printed.
                val wave = if (since >= 0f) ease((since - (r + c) * 0.045f) / 0.35f) else 0f
                val col = if (focus) Palette.TEXT_PRIMARY else Palette.brighten(ink, 0.22f * wave)
                if (wave > 0.02f) g.glow(cx, cy, cell * 0.7f, ink, 0.22f * wave)
                SegDigits.digit(g, cx, cy, dh, d, col)
            } else if (i == rejectCell && clock - rejectAt < REJECT_SECONDS) {
                // The digit refused here, in red, fading.
                SegDigits.digit(g, cx, cy, dh, rejectDigit, Palette.DANGER, 1f - (clock - rejectAt) / REJECT_SECONDS)
            } else if (focus && solver.propagate) {
                // What is still possible in the cell being decided: one pip per
                // digit, in the digit's place on a keypad.
                val m = Solver.FULL and solver.used(i).inv()
                for (dd in 1..9) {
                    if (m and (1 shl dd) == 0) continue
                    val px = cx + ((dd - 1) % 3 - 1) * cell * 0.25f
                    val py = cy - ((dd - 1) / 3 - 1) * cell * 0.25f
                    g.disc(px, py, cell * 0.055f, ink, 0.85f)
                }
            }
        }
        if (trace) drawTrace(g, palette, tl, tr, tt, tb, solvedMix)
    }

    /** The empty cell the solver is working on now. */
    private fun currentCell(): Int {
        val s = solver
        return if (s.propagate) (if (s.depth > 0) s.sCell[s.depth - 1] else -1)
        else if (s.k in s.empties.indices) s.empties[s.k] else -1
    }

    /** Cells filled against time: the advance and retreat, under the board. */
    private fun drawTrace(g: G, palette: Palette, left: Float, right: Float, top: Float, bottom: Float, solvedMix: Float) {
        val h = top - bottom
        if (h < 0.08f) return
        val frame = Palette.withAlpha(Palette.TEXT_SECONDARY, 0.18f)
        val mid = (left + right) * 0.5f
        val hw = (right - left) * 0.5f
        g.rect(mid, bottom, hw, 0.0016f, frame)
        // The ceiling is "every cell filled"; it turns green when reached.
        g.rect(mid, top, hw, 0.0016f, Palette.lerp(frame, Palette.SUCCESS, solvedMix), alpha = 0.6f + 0.4f * solvedMix)
        val cnt = depthCount
        if (cnt < 2) return
        val colW = (right - left) / DEPTH_LOG
        val lineC = palette.at(SOLVE_T)
        var px = 0f; var py = 0f
        for (i in 0 until cnt) {
            val d = depthLog[i].toInt()
            val x = left + (i + 0.5f) * colW
            val y = bottom + h * d / empties.coerceAtLeast(1)
            if (d > 0) g.rect(x, (bottom + y) * 0.5f, colW * 0.5f, (y - bottom) * 0.5f, lineC, alpha = 0.08f)
            if (i > 0) g.line(px, py, x, y, 0.0028f, lineC, 0.6f)
            px = x; py = y
        }
        g.disc(px, py, 0.008f, Palette.brighten(lineC, 0.3f))
    }

    /** 0 → 1 once, smoothly, then held. */
    private fun ease(t: Float): Float { val u = t.coerceIn(0f, 1f); return u * u * (3f - 2f * u) }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && clock - doneAt > 3f

    override fun counters(): List<Pair<String, String>> = listOf(
        "digits tried" to String.format(java.util.Locale.US, "%,d", tries),
        "backtracks" to String.format(java.util.Locale.US, "%,d", backtracks),
        "filled" to "${solver.filled} / 81"
    )

    companion object {
        private const val DEPTH_LOG = 256
        /** Where on the palette the solver's digits sit. */
        private const val SOLVE_T = 0.15f
        private const val SOLVE_SECONDS = 9f
        private const val COST_CAP = 400_000
        private const val COST_GOOD = 160_000
        /** A frame's work, whatever the pace asks: about 1.5 ms of solving. */
        private const val MAX_PER_STEP = 30_000
        private const val SOUND_GAP = 1f / 40f
        /** More actions than this in a frame is a blur: sound the board, not the act. */
        private const val BLUR_ACTS = 6
        private const val BLUR_GAP = 1f / 20f
        private const val REJECT_SECONDS = 0.35f
        private const val CLEAR_SECONDS = 0.45f
        private const val GIVEN_BG = 0xFF1A2030.toInt()
        private const val EMPTY_BG = 0xFF0E121A.toInt()
        private const val GIVEN_INK = 0xFFB4BBC8.toInt()

        /**
         * Real puzzles, each with exactly one solution (checked by counting).
         * Easy: 36 clues, plain backtracking a few hundred tries. Medium: the
         * Wikipedia example and two from Project Euler 96, thousands. Hard: two
         * sparse grids and Arto Inkala's "AI Escargot", tens of thousands up.
         */
        private val PUZZLES = arrayOf(
            arrayOf(
                "000260701680070090190004500820100040004602900050003028009300074040050036703018000",
                "100489006730000040000001295007120600500703008006095700914600000020000037800512004"
            ),
            arrayOf(
                "530070000600195000098000060800060003400803001700020006060000280000419005000080079",
                "003020600900305001001806400008102900700000008006708200002609500800203009005010300",
                "200080300060070084030500209000105408000000000402706000301007040720040060004010003"
            ),
            arrayOf(
                "020608000580009700000040000370000500600000004008000013000020000009800036000306090",
                "100007090030020008009600500005300900010080002600004000300000010040000007007000300",
                "000000039000001005003050800008090006070002000100400000009080050020000600400700000"
            )
        )

        fun def(): SimDef = SimDef(
            id = "algorithms/sudoku",
            title = "Sudoku solver",
            shelf = Shelf.ALGORITHMS,
            family = "Puzzles & backtracking",
            blurb = "Try a digit, check, take it back. Thousands of guesses, one grid.",
            formats = listOf(TrapFormat.PERFECT_FIT, TrapFormat.FILL, TrapFormat.SNAP),
            heroLabel = "New puzzle",
            defaultPalette = "Aurora",
            typicalSeconds = 12f,
            params = listOf(
                Param.choice("level", "Difficulty", listOf("Easy", "Medium", "Hard"), 1,
                    card = Card.DATA, input = ParamInput.TILES),
                Param.bool("propagate", "Propagate constraints", false, card = Card.DATA),
                Param.float("speed", "Speed", 0.25f, 4f, 1f, card = Card.MOTION, unit = "x", logScale = true)
            ),
            // No count to scale, so Simple mode gets the pace as its one dial.
            macros = listOf(
                Macro("pace", "Pace", Card.MOTION, mapOf("speed" to 0.25f..4f), default = 0.5f, mids = mapOf("speed" to 1f))
            ),
            create = { SudokuSim() }
        )
    }
}
