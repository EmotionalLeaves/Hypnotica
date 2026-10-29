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
        if (lanes == 2) (if (s == 0) -1f else 1f) * min(halfW, 1f) * 0.32f
        else (s - 1) * min(halfW, 1f) * 0.6f
    private val paneHalf get() = min(halfW, 1f) * (if (lanes == 2) 0.26f else 0.25f)
    private val paneH get() = (TOP - BOTTOM) / (rows + 1f) * 0.32f

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        val pace = env.params.f("pace", 1f)
        // Seen from above, the glass falls away from us: the shards spread a
        // little and shrink out of sight. Falling down the screen, they
        // rained across the panes still standing below.
        for (i in 0 until SHARDS) {
            if (sLife[i] <= 0f) continue
            sLife[i] -= h * SHARD_FADE
            svx[i] *= 1f - min(1f, 3f * h); svy[i] *= 1f - min(1f, 3f * h)
            sx[i] += svx[i] * h; sy[i] += svy[i] * h
            if (abs(sx[i]) > halfW) sLife[i] = 0f
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
        if (!hopping) {
            if (row == rows - 1) {
                // Across.
                crossed[current] = true
                env.emit(EventKind.STEP, 1f, 0f, 0.8f)
                if (firstAcross < 0) {
                    firstAcross = current
                    doneAt = elapsed
                    payoffFrame = true
                    env.emit(EventKind.DONE, 1f, 0f, 1f)
                }
                nextMarble(env)
                return
            }
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
            env.emit(EventKind.HIT, 0.2f + 0.7f * row / rows, (sideX(side) / max(0.1f, halfW)).coerceIn(-1f, 1f), if (known) 0.25f else 0.5f)
            wait = if (known) 0f else GUESS_PAUSE
        }
    }

    private var lastSide = 0
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
        repeat(4) { env.emit(EventKind.HIT, env.rng.range(0.75f, 1f), (x / max(0.1f, halfW)).coerceIn(-1f, 1f), 0.7f) }
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
        // The start and finish platforms.
        g.rect(0f, BOTTOM - 0.04f, min(halfW, 1f) * 0.7f, 0.035f, 0xFF2A2E38.toInt())
        g.rect(0f, TOP + 0.04f, min(halfW, 1f) * 0.7f, 0.035f, if (firstAcross >= 0) Palette.SUCCESS else 0xFF2A2E38.toInt())
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
                g.rect(x, y, paneHalf, paneH, Palette.withAlpha(tintC, if (sure) 0.28f else 0.16f))
                outline(g, x, y, Palette.withAlpha(tintC, if (sure) 0.9f else 0.55f), 0.004f)
                // A glint across one corner.
                g.line(x - paneHalf * 0.75f, y - paneH * 0.2f, x - paneHalf * 0.45f, y + paneH * 0.6f, 0.004f, Palette.withAlpha(0xFFFFFFFF.toInt(), if (sure) 0.25f else 0.4f))
            }
        }
        for (i in 0 until SHARDS) {
            if (sLife[i] <= 0f) continue
            val k = sLife[i]
            g.rect(sx[i], sy[i], sr[i] * k, sr[i] * 0.55f * k, GLASS, sTurn[i] + sx[i] * 7f, alpha = 0.4f + 0.5f * k)
        }
        // The marbles: waiting on the start platform, the one on the bridge,
        // the ones across standing on the finish.
        val r = MARBLE
        // Closer together when there are many, so the whole queue fits the platform.
        val queueStep = min(r * 2.6f, min(halfW, 1f) * 1.2f / max(1, marbles))
        for (m in 0 until marbles) {
            val c = palette.at((m + 0.5f) / marbles)
            when {
                m == current && falling -> {
                    // Dropping away beneath the bridge: smaller and darker
                    // until it is gone, where it broke through.
                    val depth = ((fallingY + 1.4f) / (rowY(row) + 1.4f)).coerceIn(0f, 1f)
                    g.disc(fallingX, rowY(row), r * (0.25f + 0.75f * depth), c, alpha = depth)
                }
                m == current && row >= -1 && !fell[m] && !crossed[m] -> {
                    val (x, y) = if (hopping) {
                        val tx = sideX(side); val ty = rowY(row + 1)
                        val p = hop.coerceIn(0f, 1f)
                        (fromX + (tx - fromX) * p) to (fromY + (ty - fromY) * p)
                    } else if (row < 0) 0f to START_Y else sideX(lastSide) to rowY(row)
                    // A hop seen from above: it grows as it rises toward us.
                    val lift = if (hopping) sin(hop.coerceIn(0f, 1f) * PI.toFloat()) else 0f
                    g.glow(x, y, r * 2.6f, c, 0.45f)
                    g.disc(x, y, r * (1f + 0.35f * lift), c)
                }
                crossed[m] -> {
                    g.disc(-min(halfW, 1f) * 0.6f + m * queueStep, TOP + 0.04f, r, c)
                }
                !fell[m] && m > current -> {
                    g.disc(-min(halfW, 1f) * 0.6f + (m - current) * queueStep, BOTTOM - 0.04f, r, c)
                }
            }
        }
        if (firstAcross >= 0) {
            val k = (1f - (elapsed - doneAt) * 0.8f).coerceIn(0f, 1f)
            if (k > 0f) g.glow(0f, TOP, 0.5f, palette.at((firstAcross + 0.5f) / marbles), 0.5f * k)
        }
    }

    private fun outline(g: G, x: Float, y: Float, c: Int, w: Float) {
        val hx = paneHalf; val hy = paneH
        g.line(x - hx - w * 0.5f, y + hy, x + hx + w * 0.5f, y + hy, w, c)
        g.line(x - hx - w * 0.5f, y - hy, x + hx + w * 0.5f, y - hy, w, c)
        g.line(x - hx, y - hy, x - hx, y + hy, w, c)
        g.line(x + hx, y - hy, x + hx, y + hy, w, c)
    }

    override fun contenders(env: SimEnv): IntArray =
        if (marbles > MAX_PICKS) NO_CONTENDERS else IntArray(marbles) { env.palette.at((it + 0.5f) / marbles) }

    override fun winner(): Int = if (marbles > MAX_PICKS || doneAt < 0f) -1 else firstAcross.coerceAtLeast(-1).let { if (it < 0) -1 else it }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && elapsed - doneAt > 3f && !falling

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
        const val SHARD_FADE = 1.4f
        val GLASS = 0xFFA8D8FF.toInt()

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
