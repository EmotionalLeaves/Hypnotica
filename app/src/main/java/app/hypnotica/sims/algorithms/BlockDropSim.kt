package app.hypnotica.sims.algorithms

import app.hypnotica.core.audio.Song
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
 * Falling blocks, played by a search: every piece is tried in every turn and
 * every column, each landing is scored on what it leaves behind — how tall the
 * stack is, how many holes it buries, how ragged its top is, how many rows it
 * finishes — and the best one is steered into place and dropped.
 *
 * The score is four numbers and nothing else, which is the interesting part:
 * no lookahead and no plan, and it still plays for as long as anyone watches.
 * The landing it chose is shown as a ghost the moment the piece appears, so
 * you can see it decide before it moves.
 */
class BlockDropSim : Sim {

    private var w = 10
    private val h = ROWS
    private var cell = 0.08f
    private var ox = 0f
    private var oy = 0f

    private var grid = IntArray(0)
    private var scratch = IntArray(0)
    private var heights = IntArray(0)

    private var bag = IntArray(7)
    private var bagPos = 7
    private var type = 0
    private var next = 0
    private var rot = 0
    private var px = 0
    private var py = 0f
    private var targetRot = 0
    private var targetX = 0
    private var landY = 0

    private var phase = STEER
    private var timer = 0f
    private var clearing = BooleanArray(0)
    private var clearT = 0f
    private var lastClear = 0
    private var flash = 0f

    private var target = 10
    private var lines = 0
    private var pieces = 0
    private var fours = 0
    private var resets = 0
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false

    override fun setup(env: SimEnv) {
        val p = env.params
        w = p.i("width", 10).coerceIn(MIN_W, MAX_W)
        target = p.i("lines", 10).coerceIn(1, 999)
        // The board fills the height, or the width on a narrow screen, with
        // room above for the next piece.
        cell = min(1.62f / h, 2f * env.halfWidth * 0.92f / w)
        ox = -w * cell * 0.5f
        oy = -0.96f
        grid = IntArray(w * h)
        scratch = IntArray(w * h)
        heights = IntArray(w)
        clearing = BooleanArray(h)
        bagPos = 7
        lines = 0; pieces = 0; fours = 0; resets = 0
        elapsed = 0f; doneAt = -1f; payoffFrame = false
        flash = 0f; lastClear = 0
        next = deal(env)
        spawn(env)
    }

    /** The seven pieces dealt from a shuffled bag, so a long drought of one never happens. */
    private fun deal(env: SimEnv): Int {
        if (bagPos >= 7) {
            for (i in 0 until 7) bag[i] = i
            env.rng.shuffle(bag)
            bagPos = 0
        }
        return bag[bagPos++]
    }

    private fun spawn(env: SimEnv) {
        type = next
        next = deal(env)
        rot = 0
        px = (w - widthOf(type, 0)) / 2
        py = (h - heightOf(type, 0)).toFloat()
        if (!choose()) {
            // Nowhere left to put it: the stack has reached the top. Clear the
            // board, say so, and play on — the count carries over.
            resets++
            grid.fill(0)
            env.emit(EventKind.DEATH, 0.2f, 0f, 0.8f)
            choose()
        }
        phase = STEER
        timer = 0f
        pieces++
    }

    /**
     * Every turn in every column, dropped straight down and scored. Sets the
     * target and returns false only when nothing fits at all.
     */
    private fun choose(): Boolean {
        for (x in 0 until w) {
            var top = 0
            for (y in h - 1 downTo 0) if (grid[y * w + x] != 0) { top = y + 1; break }
            heights[x] = top
        }
        var best = Float.NEGATIVE_INFINITY
        var found = false
        val turns = if (type == O) 1 else 4
        for (r in 0 until turns) {
            val pw = widthOf(type, r)
            for (x in 0..w - pw) {
                val y0 = landing(type, r, x)
                if (y0 + heightOf(type, r) > h) continue
                val s = score(type, r, x, y0)
                if (s > best) {
                    best = s; targetRot = r; targetX = x; landY = y0; found = true
                }
            }
        }
        return found
    }

    /** The row the piece's lowest cell rests on, dropped from above in column [x]. */
    private fun landing(t: Int, r: Int, x: Int): Int {
        val s = SHAPES[t][r]
        var y0 = 0
        for (i in 0 until 4) {
            val cx = s[i * 2]; val cy = s[i * 2 + 1]
            y0 = max(y0, heights[x + cx] - cy)
        }
        return y0
    }

    /** What a landing leaves behind, rows it completes taken out first. */
    private fun score(t: Int, r: Int, x: Int, y0: Int): Float {
        System.arraycopy(grid, 0, scratch, 0, grid.size)
        val s = SHAPES[t][r]
        for (i in 0 until 4) scratch[(y0 + s[i * 2 + 1]) * w + x + s[i * 2]] = 1
        // Compact out the finished rows.
        var write = 0
        var done = 0
        for (y in 0 until h) {
            var full = true
            for (cx in 0 until w) if (scratch[y * w + cx] == 0) { full = false; break }
            if (full) { done++; continue }
            if (write != y) System.arraycopy(scratch, y * w, scratch, write * w, w)
            write++
        }
        for (y in write until h) for (cx in 0 until w) scratch[y * w + cx] = 0
        var aggregate = 0
        var holes = 0
        var bump = 0
        var prev = -1
        for (cx in 0 until w) {
            var top = 0
            for (y in h - 1 downTo 0) if (scratch[y * w + cx] != 0) { top = y + 1; break }
            for (y in 0 until top) if (scratch[y * w + cx] == 0) holes++
            aggregate += top
            if (prev >= 0) bump += abs(top - prev)
            prev = top
        }
        return -0.510066f * aggregate + 0.760666f * done - 0.35663f * holes - 0.184483f * bump
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val a = abs(dt)
        elapsed += a
        flash = max(0f, flash - a * 3f)
        val speed = env.params.f("speed", 1f).coerceIn(0.1f, 8f)
        timer += a * speed
        when (phase) {
            STEER -> {
                // Turn first, then slide, one move a tick: you see it decide.
                while (timer >= MOVE_S) {
                    timer -= MOVE_S
                    if (rot != targetRot) {
                        rot = (rot + 1) % 4
                        px = min(px, w - widthOf(type, rot))
                        py = (h - heightOf(type, rot)).toFloat()
                        env.emit(EventKind.STEP, 0.6f, pan(px), 0.12f)
                    } else if (px != targetX) {
                        px += if (targetX > px) 1 else -1
                        env.emit(EventKind.STEP, 0.4f, pan(px), 0.1f)
                    } else {
                        phase = DROP
                        timer = 0f
                        break
                    }
                }
            }
            DROP -> {
                py -= a * speed * DROP_ROWS_PER_S
                if (py <= landY) {
                    py = landY.toFloat()
                    lock(env)
                }
            }
            CLEAR -> {
                clearT += a * speed
                if (clearT >= CLEAR_S) collapse(env)
            }
            PAUSE -> if (timer >= PAUSE_S) spawn(env)
        }
    }

    private fun lock(env: SimEnv) {
        val s = SHAPES[type][rot]
        for (i in 0 until 4) {
            val y = landY + s[i * 2 + 1]
            if (y < h) grid[y * w + px + s[i * 2]] = type + 1
        }
        env.emit(EventKind.HIT, (px + widthOf(type, rot) * 0.5f) / w, pan(px), 0.5f)
        var n = 0
        for (y in 0 until h) {
            var full = true
            for (x in 0 until w) if (grid[y * w + x] == 0) { full = false; break }
            clearing[y] = full
            if (full) n++
        }
        if (n > 0) {
            phase = CLEAR
            clearT = 0f
            lastClear = n
            flash = 0.4f + 0.15f * n
            for (k in 0 until n) env.emit(EventKind.CROSS, 0.35f + 0.15f * k, 0f, 0.5f + 0.12f * n)
        } else {
            phase = PAUSE
            timer = 0f
        }
    }

    private fun collapse(env: SimEnv) {
        var write = 0
        var n = 0
        for (y in 0 until h) {
            if (clearing[y]) { n++; clearing[y] = false; continue }
            if (write != y) System.arraycopy(grid, y * w, grid, write * w, w)
            write++
        }
        for (y in write until h) for (x in 0 until w) grid[y * w + x] = 0
        val before = lines
        lines += n
        if (n == 4) fours++
        when (n) {
            4 -> env.callout("TETRA!", big = true)
            3 -> env.callout("Triple!")
            2 -> env.callout("Double!")
        }
        if (before < target && lines >= target && doneAt < 0f) {
            doneAt = elapsed
            payoffFrame = true
            env.emit(EventKind.DONE, 0.9f, 0f, 1f)
        }
        phase = PAUSE
        timer = 0f
    }

    private fun pan(x: Int): Float = ((x + 1f) / w * 2f - 1f).coerceIn(-1f, 1f)

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        val bw = w * cell * 0.5f
        val bh = h * cell * 0.5f
        val cx = ox + bw
        val cy = oy + bh
        // The well, a shade off the background, and its edge.
        g.rect(cx, cy, bw + cell * 0.12f, bh + cell * 0.12f, WELL_EDGE)
        g.rect(cx, cy, bw, bh, WELL)
        // Faint columns, so the slide reads as steps.
        for (x in 1 until w) g.line(ox + x * cell, oy, ox + x * cell, oy + h * cell, cell * 0.02f, Palette.TEXT_SECONDARY, 0.06f)

        val inset = cell * 0.44f
        val clearPulse = if (phase == CLEAR) 1f - clearT / CLEAR_S else 0f
        for (y in 0 until h) {
            val row = clearing[y] && phase == CLEAR
            for (x in 0 until w) {
                val v = grid[y * w + x]
                if (v == 0) continue
                val c = colour(pal, v - 1)
                val col = if (row) Palette.lerp(c, Palette.TEXT_PRIMARY, 0.4f + 0.6f * clearPulse) else c
                val s = if (row) inset * (0.4f + 0.6f * clearPulse) else inset
                g.rect(ox + (x + 0.5f) * cell, oy + (y + 0.5f) * cell, s, s, col)
            }
            if (row) g.glow(cx, oy + (y + 0.5f) * cell, bw * 1.1f, Palette.TEXT_PRIMARY, 0.25f * clearPulse)
        }

        if (phase == STEER || phase == DROP) {
            val s = SHAPES[type][rot]
            val c = colour(pal, type)
            // Where the search decided it goes: shown from the moment it appears.
            val ts = SHAPES[type][targetRot]
            for (i in 0 until 4) {
                val gx = ox + (targetX + ts[i * 2] + 0.5f) * cell
                val gy = oy + (landY + ts[i * 2 + 1] + 0.5f) * cell
                g.rect(gx, gy, inset, inset, c, 0f, 0.16f)
            }
            for (i in 0 until 4) {
                val x = ox + (px + s[i * 2] + 0.5f) * cell
                val y = oy + (py + s[i * 2 + 1] + 0.5f) * cell
                g.rect(x, y, inset, inset, Palette.brighten(c, 0.3f))
            }
        }

        // The next piece, small, above the right of the well.
        val ns = SHAPES[next][0]
        val small = cell * 0.5f
        val nx = ox + w * cell - widthOf(next, 0) * small
        val ny = oy + h * cell + small * 0.9f
        for (i in 0 until 4) {
            g.rect(nx + (ns[i * 2] + 0.5f) * small, ny + (ns[i * 2 + 1] + 0.5f) * small, small * 0.42f, small * 0.42f, colour(pal, next), 0f, 0.7f)
        }

        if (flash > 0f) g.glow(cx, cy, bw * 1.6f, if (lastClear == 4) Palette.SUCCESS else Palette.TEXT_PRIMARY, flash * 0.35f)
    }

    private fun colour(pal: Palette, t: Int): Int = pal.at(0.06f + t / 6f * 0.88f)

    override fun payoff(): Boolean = payoffFrame

    /** Endless: past the target it plays on; the line count keeps climbing. */
    override fun finished(): Boolean = false

    override fun scoreForBest(): Float = fours.toFloat()
    override fun scoreForBestText(value: Float): String = "${value.toInt()} four-line clears"
    override fun liveBest(): Boolean = true

    override fun counters(): List<Pair<String, String>> = buildList {
        add("lines" to "$lines / $target")
        add("pieces" to "$pieces")
        if (fours > 0) add("four at once" to "$fours")
        if (resets > 0) add("topped out" to "$resets")
    }

    override fun tune(env: SimEnv): String = Song.KOROBEINIKI.name

    companion object {
        const val ROWS = 20
        const val MIN_W = 6
        const val MAX_W = 16
        const val STEER = 0
        const val DROP = 1
        const val CLEAR = 2
        const val PAUSE = 3
        const val O = 1
        const val MOVE_S = 0.035f
        const val DROP_ROWS_PER_S = 60f
        const val CLEAR_S = 0.3f
        const val PAUSE_S = 0.06f
        const val RESOLVE_S = 3f
        val WELL = 0xFF0D0F15.toInt()
        val WELL_EDGE = 0xFF1E212A.toInt()

        /** The seven pieces, as cells in a box, y up: I O T S Z J L. */
        private val BASE = arrayOf(
            intArrayOf(0, 1, 1, 1, 2, 1, 3, 1),
            intArrayOf(0, 0, 1, 0, 0, 1, 1, 1),
            intArrayOf(0, 1, 1, 1, 2, 1, 1, 2),
            intArrayOf(0, 1, 1, 1, 1, 2, 2, 2),
            intArrayOf(0, 2, 1, 2, 1, 1, 2, 1),
            intArrayOf(0, 2, 0, 1, 1, 1, 2, 1),
            intArrayOf(2, 2, 0, 1, 1, 1, 2, 1)
        )

        /** Each piece in each of four turns, shifted so its lowest, leftmost cell is at 0. */
        val SHAPES: Array<Array<IntArray>> = Array(7) { t ->
            Array(4) { r ->
                val c = BASE[t].copyOf()
                repeat(r) {
                    for (i in 0 until 4) {
                        val x = c[i * 2]; val y = c[i * 2 + 1]
                        c[i * 2] = y; c[i * 2 + 1] = -x
                    }
                }
                var mx = Int.MAX_VALUE; var my = Int.MAX_VALUE
                for (i in 0 until 4) { mx = min(mx, c[i * 2]); my = min(my, c[i * 2 + 1]) }
                for (i in 0 until 4) { c[i * 2] -= mx; c[i * 2 + 1] -= my }
                c
            }
        }

        fun widthOf(t: Int, r: Int): Int {
            val s = SHAPES[t][r]
            var m = 0
            for (i in 0 until 4) m = max(m, s[i * 2])
            return m + 1
        }

        fun heightOf(t: Int, r: Int): Int {
            val s = SHAPES[t][r]
            var m = 0
            for (i in 0 until 4) m = max(m, s[i * 2 + 1])
            return m + 1
        }

        fun def(): SimDef = SimDef(
            id = "algorithms/block-drop",
            title = "Block drop",
            shelf = Shelf.ALGORITHMS,
            family = "Games",
            blurb = "Falling blocks, played by a four-number score. The ghost is where it has decided.",
            formats = listOf(TrapFormat.NUMBER_UP, TrapFormat.PERFECT_FIT, TrapFormat.SONG),
            heroLabel = "New game",
            defaultPalette = "Spectrum",
            typicalSeconds = 22f,
            params = listOf(
                Param.int("lines", "Lines to clear", 1, 200, 10, card = Card.DATA, logScale = true),
                Param.int("width", "Board width", MIN_W, MAX_W, 10, card = Card.DATA),
                Param.float("speed", "Speed", 0.25f, 6f, 1f, card = Card.MOTION, logScale = true)
            ),
            create = { BlockDropSim() }
        )
    }
}
