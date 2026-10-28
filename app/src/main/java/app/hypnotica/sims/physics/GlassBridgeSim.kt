package app.hypnotica.sims.physics

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
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
    private var safe = IntArray(0)        // which side holds, per row: 0 left, 1 right
    private var known = BooleanArray(0)   // revealed by someone before
    private var broken = IntArray(0)      // -1, or the side that shattered
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
    private var sNext = 0

    override fun setup(env: SimEnv) {
        halfW = env.halfWidth
        rows = env.params.i("rows", 12).coerceIn(3, MAX_ROWS)
        marbles = env.params.i("marbles", 8).coerceIn(1, MAX_MARBLES)
        safe = IntArray(rows) { env.rng.int(0, 2) }
        known = BooleanArray(rows)
        broken = IntArray(rows) { -1 }
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
        sy.fill(-9f)
        sNext = 0
    }

    private fun rowY(r: Int) = BOTTOM + (TOP - BOTTOM) * (r + 1f) / (rows + 1f)
    private fun sideX(s: Int) = (if (s == 0) -1f else 1f) * min(halfW, 1f) * 0.32f
    private val paneHalf get() = min(halfW, 1f) * 0.26f
    private val paneH get() = (TOP - BOTTOM) / (rows + 1f) * 0.32f

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        val pace = env.params.f("pace", 1f)
        for (i in 0 until SHARDS) {
            if (sy[i] < -1.5f) continue
            svy[i] -= 2.5f * h
            sx[i] += svx[i] * h; sy[i] += svy[i] * h
            // Gone once off the side, rather than drawn out past it.
            if (abs(sx[i]) > halfW) sy[i] = -9f
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
            side = if (known[next]) safe[next] else env.rng.int(0, 2)
            fromX = if (row < 0) 0f else sideX(sideOf(row))
            fromY = if (row < 0) BOTTOM else rowY(row)
            hop = 0f
            hopping = true
        }
        val known = this.known[row + 1]
        // Sure steps are quick; a guess takes its time.
        hop += h * pace / (if (known) SURE_HOP else GUESS_HOP)
        if (hop >= 1f) {
            hopping = false
            row++
            this.known[row] = true
            if (side != safe[row]) {
                // Wrong pane.
                broken[row] = side
                shatter(env, sideX(side), rowY(row))
                fell[current] = true
                falling = true
                fallingX = sideX(side)
                fallingY = rowY(row)
                fallingV = 0.3f
                // The other one, then, for everyone after.
                return
            }
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
            val s = env.rng.range(0.1f, 0.6f)
            svx[i] = cos(a) * s; svy[i] = sin(a) * s + 0.2f
            sr[i] = env.rng.range(0.006f, 0.016f)
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
            for (s in 0..1) {
                if (broken[r] == s) continue
                val x = sideX(s)
                // Known safe panes are lit; the rest are just glass.
                val glass = if (known[r] && safe[r] == s) Palette.withAlpha(Palette.SUCCESS, 0.45f) else 0x55A8D8FF
                g.rect(x, y, paneHalf, paneH, glass)
                g.line(x - paneHalf, y + paneH, x + paneHalf, y + paneH, 0.004f, 0xAAE0F4FF.toInt())
            }
        }
        for (i in 0 until SHARDS) if (sy[i] > -1.5f) g.rect(sx[i], sy[i], sr[i], sr[i] * 0.6f, 0xCCC8ECFF.toInt(), sx[i] * 7f)
        // The marbles: waiting below, the one on the bridge, the ones across.
        val r = MARBLE
        for (m in 0 until marbles) {
            val c = palette.at((m + 0.5f) / marbles)
            when {
                m == current && falling -> g.disc(fallingX, fallingY, r, c)
                m == current && row >= -1 && !fell[m] && !crossed[m] -> {
                    val (x, y) = if (hopping) {
                        val tx = sideX(side); val ty = rowY(row + 1)
                        val p = hop.coerceIn(0f, 1f)
                        (fromX + (tx - fromX) * p) to (fromY + (ty - fromY) * p + 0.06f * sin(p * PI.toFloat()))
                    } else if (row < 0) 0f to BOTTOM else sideX(lastSide) to rowY(row)
                    g.glow(x, y, r * 3f, c, 0.5f)
                    g.disc(x, y + r * 0.5f, r, c)
                }
                crossed[m] -> {
                    val k = m
                    g.disc(-min(halfW, 1f) * 0.6f + k * r * 2.6f, TOP + 0.04f + r * 1.6f, r, c)
                }
                !fell[m] && m > current -> {
                    g.disc(-min(halfW, 1f) * 0.6f + (m - current) * r * 2.6f, BOTTOM - 0.04f - r * 1.6f, r, c)
                }
            }
        }
        if (firstAcross >= 0) {
            val k = (1f - (elapsed - doneAt) * 0.8f).coerceIn(0f, 1f)
            if (k > 0f) g.glow(0f, TOP, 0.5f, palette.at((firstAcross + 0.5f) / marbles), 0.5f * k)
        }
    }

    override fun contenders(env: SimEnv): IntArray =
        if (marbles > MAX_PICKS) NO_CONTENDERS else IntArray(marbles) { env.palette.at((it + 0.5f) / marbles) }

    override fun winner(): Int = if (marbles > MAX_PICKS || doneAt < 0f) -1 else firstAcross.coerceAtLeast(-1).let { if (it < 0) -1 else it }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && elapsed - doneAt > 3f && !falling

    override fun counters(): List<Pair<String, String>> = listOf(
        "fallen" to fell.count { it }.toString(),
        if (firstAcross >= 0) "across first" to "#${firstAcross + 1}" else "furthest" to "row ${known.count { it }}"
    )

    companion object {
        const val MAX_ROWS = 30
        const val MAX_MARBLES = 16
        const val MAX_PICKS = 8
        const val BOTTOM = -0.82f
        const val TOP = 0.82f
        const val MARBLE = 0.026f
        const val SURE_HOP = 0.12f
        const val GUESS_HOP = 0.3f
        const val GUESS_PAUSE = 0.12f
        const val SHARDS = 400

        fun def(): SimDef = SimDef(
            id = "physics/glass-bridge",
            title = "Glass bridge",
            shelf = Shelf.PHYSICS,
            family = "Chance",
            blurb = "One pane in each pair holds. Each fall shows the next the way. Who gets across?",
            formats = listOf(TrapFormat.GUESS, TrapFormat.LAST_STANDING, TrapFormat.ESCAPE),
            heroLabel = "New bridge",
            defaultPalette = "Spectrum",
            typicalSeconds = 18f,
            params = listOf(
                Param.int("rows", "Rows", 3, MAX_ROWS, 12, card = Card.DATA),
                Param.int("marbles", "Marbles", 1, MAX_MARBLES, 8, card = Card.DATA),
                Param.float("pace", "Pace", 0.3f, 4f, 1f, card = Card.MOTION)
            ),
            create = { GlassBridgeSim() }
        )
    }
}
