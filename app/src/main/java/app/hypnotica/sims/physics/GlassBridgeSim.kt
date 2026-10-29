package app.hypnotica.sims.physics

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.recipe.ParamInput
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.NO_CONTENDERS
import app.hypnotica.core.sim.Shelf
import app.hypnotica.core.sim.Sim
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.sim.SimEnv
import app.hypnotica.core.sim.TrapFormat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A bridge of glass panes in pairs: in every pair one holds and one
 * shatters, and nothing tells them apart. The marbles go one at a time, each
 * guessing its way up. Every fall shows the ones behind which pane not to
 * take, so each gets a little further — who makes it across?
 */
class GlassBridgeSim : Sim {

    private var rows = 12
    private var marbles = 8
    private var lanes = 2
    private var safe = IntArray(0)        // which pane holds, per row, counted from the left
    private var known = BooleanArray(0)   // the safe pane is known: stood on, or the only one left
    private var broken = IntArray(0)      // a bit for each pane that has shattered
    private var current = 0
    private var row = -1                  // -1: on the start platform
    private var side = 0
    private var fromX = 0f
    private var fromY = 0f
    private var hop = 0f
    private var hopping = false
    private var fallingY = 0f
    private var fallingV = 0f
    private var fallingX = 0f
    private var falling = false
    private var crossed = BooleanArray(0)
    private var fell = BooleanArray(0)
    private var firstAcross = -1
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var wait = 0f
    private var halfW = 0.46f
    /**
     * On a wide screen the bridge runs left to right, start on the left:
     * upright it was a narrow strip in the middle of a landscape frame.
     * Everything is worked out in bridge space (x across the lanes, y along
     * the bridge) and turned onto the screen as it is drawn.
     */
    private var across = false
    /** Screen units per bridge unit along the bridge, when it runs across. */
    private var stretch = 1f
    /** The width the lanes are laid out in: the screen's, or its height when turned. */
    private var laneW = 0.46f
    // Shards.
    private val sx = FloatArray(SHARDS)
    private val sy = FloatArray(SHARDS)
    private val svx = FloatArray(SHARDS)
    private val svy = FloatArray(SHARDS)
    private val sr = FloatArray(SHARDS)
    private val sLife = FloatArray(SHARDS)
    private val sTurn = FloatArray(SHARDS)
    private var sNext = 0

    override fun setup(env: SimEnv) {
        halfW = env.halfWidth
        across = halfW > 1.2f
        stretch = if (across) halfW * 0.94f / (TOP + 0.08f) else 1f
        laneW = if (across) 0.92f else min(halfW, 1f)
        rows = env.params.i("rows", 12).coerceIn(3, MAX_ROWS)
        marbles = env.params.i("marbles", 8).coerceIn(1, MAX_MARBLES)
        lanes = if (env.params.choiceIndex("panes") == 1) 3 else 2
        // A row of three costs a fall each on average, twice a pair's: half
        // the bridge, so someone still has a chance of getting across.
        if (lanes == 3) rows = max(3, rows / 2)
        safe = IntArray(rows) { env.rng.int(0, lanes) }
        known = BooleanArray(rows)
        broken = IntArray(rows)
        crossed = BooleanArray(marbles)
        fell = BooleanArray(marbles)
        current = 0
        row = -1
        hopping = false
        falling = false
        firstAcross = -1
        elapsed = 0f
        doneAt = -1f
        payoffFrame = false
        wait = 0.6f
        sLife.fill(0f)
        sNext = 0
    }

    private fun rowY(r: Int) = BOTTOM + (TOP - BOTTOM) * (r + 1f) / (rows + 1f)
    private fun sideX(s: Int) =
        if (lanes == 2) (if (s == 0) -1f else 1f) * laneW * 0.32f
        else (s - 1) * laneW * 0.6f
    private val paneHalf get() = laneW * (if (lanes == 2) 0.26f else 0.25f)
    private val paneH get() = (TOP - BOTTOM) / (rows + 1f) * 0.32f

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        // Once someone is across, every safe pane is known and the rest
        // follow at a trot: at walking pace the run ended with one frozen
        // mid-bridge and the others still queueing at the start.
        val pace = env.params.f("pace", 1f) * (if (firstAcross >= 0) FOLLOW_PACE else 1f)
        // Seen from above, the glass falls away from us: the shards spread a
        // little and shrink out of sight. Falling down the screen, they
        // rained across the panes still standing below.
        for (i in 0 until SHARDS) {
            if (sLife[i] <= 0f) continue
            sLife[i] -= h * SHARD_FADE
            svx[i] *= 1f - min(1f, 3f * h); svy[i] *= 1f - min(1f, 3f * h)
            sx[i] += svx[i] * h; sy[i] += svy[i] * h
            if (abs(sx[i]) > laneW) sLife[i] = 0f
        }
        if (falling) {
            fallingV -= 5f * h
            fallingY += fallingV * h
            if (fallingY < -1.4f) {
                falling = false
                nextMarble(env)
            }
            return
        }
        if (current >= marbles || doneAt >= 0f && current >= marbles) return
        if (wait > 0f) { wait -= h * pace; return }
        if (!hopping && row == rows - 1) {
            // Off the last pane: a sure hop onto the finish platform, to its
            // place in the line. It used to vanish from the last row and
            // reappear in the line in the same frame.
            fromX = sideX(lastSide); fromY = rowY(row)
            hop = 0f
            hopping = true
        }
        if (hopping && row == rows - 1) {
            hop += h * pace / SURE_HOP
            if (hop >= 1f) {
                hopping = false
                // Across.
                crossed[current] = true
                // Heard where it lands: its place in the line on the finish platform.
                env.emit(EventKind.STEP, 1f, pan(finishX(current), TOP + 0.04f), 0.8f)
                if (firstAcross < 0) {
                    firstAcross = current
                    doneAt = elapsed
                    payoffFrame = true
                    env.emit(EventKind.DONE, 1f, 0f, 1f)
                }
                nextMarble(env)
            }
            return
        }
        if (!hopping) {
            // Choose the next pane: the known one if somebody found it, else a guess.
            val next = row + 1
            side = if (known[next]) safe[next] else {
                // A guess among the panes nobody has broken yet.
                val open = (0 until lanes).filter { broken[next] and (1 shl it) == 0 }
                open[env.rng.int(0, open.size)]
            }
            fromX = if (row < 0) 0f else sideX(sideOf(row))
            fromY = if (row < 0) START_Y else rowY(row)
            hop = 0f
            hopping = true
        }
        val known = this.known[row + 1]
        // Sure steps are quick; a guess takes its time.
        hop += h * pace / (if (known) SURE_HOP else GUESS_HOP)
        if (hop >= 1f) {
            hopping = false
            row++
            if (side != safe[row]) {
                // Wrong pane.
                broken[row] = broken[row] or (1 shl side)
                // Known once only one is left whole.
                if (Integer.bitCount(broken[row]) == lanes - 1) this.known[row] = true
                shatter(env, sideX(side), rowY(row))
                fell[current] = true
                falling = true
                fallingX = sideX(side)
                fallingY = rowY(row)
                fallingV = 0.3f
                return
            }
            this.known[row] = true
            lastSide = side
            env.emit(EventKind.HIT, 0.2f + 0.7f * row / rows, pan(sideX(side), rowY(row)), if (known) 0.25f else 0.5f)
            wait = if (known) 0f else GUESS_PAUSE
        }
    }

    private var lastSide = 0
    private fun finishX(m: Int): Float {
        val r = MARBLE * (if (across) 1.35f else 1f)
        // Closer together when there are many, so the whole line fits the platform.
        val queueStep = min(r * 2.6f, laneW * 1.2f / max(1, marbles))
        return -laneW * 0.6f + m * queueStep
    }
    private fun sideOf(r: Int) = if (r < 0) 0 else lastSide

    private fun shatter(env: SimEnv, x: Float, y: Float) {
        repeat(40) {
            val i = sNext; sNext = (sNext + 1) % SHARDS
            sx[i] = x + env.rng.range(-paneHalf, paneHalf); sy[i] = y + env.rng.range(-paneH, paneH)
            val a = env.rng.range(0f, 2f * PI.toFloat())
            val s = env.rng.range(0.05f, 0.4f)
            svx[i] = cos(a) * s; svy[i] = sin(a) * s
            sr[i] = env.rng.range(0.008f, 0.02f)
            sLife[i] = 1f
            sTurn[i] = env.rng.range(0f, 3.14f)
        }
        repeat(4) { env.emit(EventKind.HIT, env.rng.range(0.75f, 1f), pan(x, y), 0.7f) }
    }

    private fun nextMarble(env: SimEnv) {
        current++
        row = -1
        lastSide = 0
        wait = 0.35f
        if (current >= marbles && doneAt < 0f) {
            // Nobody made it.
            doneAt = elapsed
            payoffFrame = true
            env.emit(EventKind.DONE, 0.2f, 0f, 1f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        // The steel the panes sit in: a pair of rails down each lane, from
        // platform to platform. Without them the panes floated in the dark
        // as loose tiles, and a broken one left no gap in anything.
        val railOff = paneHalf + RAIL_GAP
        for (s in 0 until lanes) {
            for (side in floatArrayOf(-1f, 1f)) {
                val x = sideX(s) + side * railOff
                lineB(g, x, BOTTOM, x, TOP, RAIL_W, RAIL)
            }
        }
        // The start and finish platforms.
        rectB(g, 0f, BOTTOM - 0.04f, laneW * 0.7f, 0.035f, 0xFF2A2E38.toInt())
        rectB(g, 0f, TOP + 0.04f, laneW * 0.7f, 0.035f, if (firstAcross >= 0) Palette.SUCCESS else 0xFF2A2E38.toInt())
        for (r in 0 until rows) {
            val y = rowY(r)
            for (s in 0 until lanes) {
                if (broken[r] and (1 shl s) != 0) {
                    // Where a pane was: its empty frame.
                    outline(g, sideX(s), y, Palette.withAlpha(Palette.TEXT_SECONDARY, 0.22f), 0.003f)
                    continue
                }
                val x = sideX(s)
                // Glass: a pale see-through sheet with a bright rim. Known safe
                // panes turn green. Opaque slate read as tiles, not glass.
                val sure = known[r] && safe[r] == s
                val tintC = if (sure) Palette.SUCCESS else GLASS
                rectB(g, x, y, paneHalf, paneH, Palette.withAlpha(tintC, if (sure) 0.28f else 0.16f))
                outline(g, x, y, Palette.withAlpha(tintC, if (sure) 0.9f else 0.55f), 0.004f)
                // A glint across one corner.
                lineB(g, x - paneHalf * 0.75f, y - paneH * 0.2f, x - paneHalf * 0.45f, y + paneH * 0.6f, 0.004f, Palette.withAlpha(0xFFFFFFFF.toInt(), if (sure) 0.25f else 0.4f))
            }
        }
        // Once it is decided, the panes nobody tried show which held: the
        // whole safe path laid out, so the end reads as an answer rather
        // than a half-tested bridge.
        if (doneAt >= 0f) {
            val k = ((elapsed - doneAt) / REVEAL_SECONDS).coerceIn(0f, 1f)
            if (k > 0f) for (r in 0 until rows) {
                if (known[r]) continue
                val x = sideX(safe[r]); val y = rowY(r)
                rectB(g, x, y, paneHalf, paneH, Palette.withAlpha(Palette.SUCCESS, 0.14f * k))
                outline(g, x, y, Palette.withAlpha(Palette.SUCCESS, 0.6f * k), 0.004f)
            }
        }
        for (i in 0 until SHARDS) {
            if (sLife[i] <= 0f) continue
            val k = sLife[i]
            rectB(g, sx[i], sy[i], sr[i] * k, sr[i] * 0.55f * k, GLASS, sTurn[i] + sx[i] * 7f, 0.4f + 0.5f * k)
        }
        // The marbles: waiting on the start platform, the one on the bridge,
        // the ones across standing on the finish.
        val r = MARBLE * (if (across) 1.35f else 1f)
        // Closer together when there are many, so the whole queue fits the platform.
        val queueStep = min(r * 2.6f, laneW * 1.2f / max(1, marbles))
        for (m in 0 until marbles) {
            val c = palette.at((m + 0.5f) / marbles)
            when {
                m == current && falling -> {
                    // Dropping away beneath the bridge: smaller and darker
                    // until it is gone, where it broke through.
                    val depth = ((fallingY + 1.4f) / (rowY(row) + 1.4f)).coerceIn(0f, 1f)
                    discB(g, fallingX, rowY(row), r * (0.25f + 0.75f * depth), c, depth)
                }
                m == current && row >= -1 && !fell[m] && !crossed[m] -> {
                    val (x, y) = if (hopping) {
                        val last = row == rows - 1
                        val tx = if (last) finishX(m) else sideX(side)
                        val ty = if (last) TOP + 0.04f else rowY(row + 1)
                        val p = hop.coerceIn(0f, 1f)
                        (fromX + (tx - fromX) * p) to (fromY + (ty - fromY) * p)
                    } else if (row < 0) 0f to START_Y else sideX(lastSide) to rowY(row)
                    // A hop seen from above: it grows as it rises toward us.
                    val lift = if (hopping) sin(hop.coerceIn(0f, 1f) * PI.toFloat()) else 0f
                    glowB(g, x, y, r * 2.6f, c, 0.45f)
                    discB(g, x, y, r * (1f + 0.35f * lift), c)
                }
                crossed[m] -> {
                    discB(g, finishX(m), TOP + 0.04f, r, c)
                }
                !fell[m] && m > current -> {
                    discB(g, -laneW * 0.6f + (m - current) * queueStep, BOTTOM - 0.04f, r, c)
                }
            }
        }
        if (firstAcross >= 0) {
            val k = (1f - (elapsed - doneAt) * 0.8f).coerceIn(0f, 1f)
            if (k > 0f) glowB(g, 0f, TOP, 0.5f, palette.at((firstAcross + 0.5f) / marbles), 0.5f * k)
        }
    }

    private fun outline(g: G, x: Float, y: Float, c: Int, w: Float) {
        val hx = paneHalf; val hy = paneH
        lineB(g, x - hx - w * 0.5f, y + hy, x + hx + w * 0.5f, y + hy, w, c)
        lineB(g, x - hx - w * 0.5f, y - hy, x + hx + w * 0.5f, y - hy, w, c)
        lineB(g, x - hx, y - hy, x - hx, y + hy, w, c)
        lineB(g, x + hx, y - hy, x + hx, y + hy, w, c)
    }

    // Bridge space onto the screen: upright as is; across, turned a quarter
    // so the start is on the left and lane 0 on top, stretched along.
    private fun sxOf(bx: Float, by: Float) = if (across) by * stretch else bx
    private fun syOf(bx: Float, by: Float) = if (across) -bx else by
    private fun pan(bx: Float, by: Float) = (sxOf(bx, by) / max(0.1f, halfW)).coerceIn(-1f, 1f)
    private fun rectB(g: G, x: Float, y: Float, hx: Float, hy: Float, c: Int, rot: Float = 0f, alpha: Float = 1f) {
        if (across) g.rect(y * stretch, -x, hy * stretch, hx, c, rot, alpha = alpha)
        else g.rect(x, y, hx, hy, c, rot, alpha = alpha)
    }
    private fun discB(g: G, x: Float, y: Float, r: Float, c: Int, alpha: Float = 1f) =
        g.disc(sxOf(x, y), syOf(x, y), r, c, alpha = alpha)
    private fun glowB(g: G, x: Float, y: Float, r: Float, c: Int, a: Float) =
        g.glow(sxOf(x, y), syOf(x, y), r, c, a)
    private fun lineB(g: G, x0: Float, y0: Float, x1: Float, y1: Float, w: Float, c: Int, a: Float = 1f) =
        g.line(sxOf(x0, y0), syOf(x0, y0), sxOf(x1, y1), syOf(x1, y1), w, c, a)

    override fun contenders(env: SimEnv): IntArray =
        if (marbles > MAX_PICKS) NO_CONTENDERS else IntArray(marbles) { env.palette.at((it + 0.5f) / marbles) }

    override fun winner(): Int = if (marbles > MAX_PICKS || doneAt < 0f) -1 else firstAcross.coerceAtLeast(-1).let { if (it < 0) -1 else it }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean =
        doneAt >= 0f && elapsed - doneAt > 3f && !falling && (current >= marbles || elapsed - doneAt > FOLLOW_SECONDS)

    override fun counters(): List<Pair<String, String>> = listOf(
        "fallen" to fell.count { it }.toString(),
        if (firstAcross >= 0) "across first" to "#${firstAcross + 1}" else "furthest" to "row ${(0 until rows).count { known[it] || broken[it] != 0 }}"
    )

    companion object {
        const val MAX_ROWS = 30
        const val MAX_MARBLES = 16
        const val MAX_PICKS = 8
        const val BOTTOM = -0.82f
        const val TOP = 0.82f
        /** Where the one up next stands: clear of the queue on the platform below it. */
        const val START_Y = BOTTOM + 0.03f
        const val MARBLE = 0.026f
        const val SURE_HOP = 0.12f
        const val GUESS_HOP = 0.3f
        const val GUESS_PAUSE = 0.12f
        const val SHARDS = 400
        /** How much faster the rest cross once the way is known, and how long they get. */
        const val FOLLOW_PACE = 2.5f
        const val FOLLOW_SECONDS = 9f
        const val SHARD_FADE = 1.4f
        val GLASS = 0xFFA8D8FF.toInt()
        val RAIL = 0xFF343A47.toInt()
        const val RAIL_W = 0.006f
        const val RAIL_GAP = 0.012f
        const val REVEAL_SECONDS = 1.2f

        fun def(): SimDef = SimDef(
            id = "physics/glass-bridge",
            title = "Glass bridge",
            shelf = Shelf.PHYSICS,
            family = "Chance",
            blurb = "One pane in each pair holds — or one in three. Each fall shows the next the way. Who gets across?",
            formats = listOf(TrapFormat.GUESS, TrapFormat.LAST_STANDING, TrapFormat.ESCAPE),
            heroLabel = "New bridge",
            defaultPalette = "Spectrum",
            typicalSeconds = 18f,
            params = listOf(
                Param.choice("panes", "Panes", listOf("Pairs", "Threes"), 0, card = Card.DATA, input = ParamInput.TILES),
                Param.int("rows", "Rows", 3, MAX_ROWS, 12, card = Card.DATA),
                Param.int("marbles", "Marbles", 1, MAX_MARBLES, 8, card = Card.DATA),
                Param.float("pace", "Pace", 0.3f, 4f, 1f, card = Card.MOTION)
            ),
            create = { GlassBridgeSim() }
        )
    }
}
