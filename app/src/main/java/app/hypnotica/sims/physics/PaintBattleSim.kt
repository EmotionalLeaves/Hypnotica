package app.hypnotica.sims.physics

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
import app.hypnotica.sims.life.cellRow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Paint battle: a ball of each colour bounces round a white floor, painting
 * it as it rolls — over the others' paint as readily as bare floor. When the
 * clock runs out, whoever covers the most wins. The lead changes hands a
 * dozen times; it is settled in the last few seconds more often than not.
 */
class PaintBattleSim : Sim {

    private var teams = 4
    private val x = FloatArray(MAX)
    private val y = FloatArray(MAX)
    private val vx = FloatArray(MAX)
    private val vy = FloatArray(MAX)
    private val counts = IntArray(MAX)
    private var r = 0.05f
    private var halfW = 0.44f
    private var halfH = 0.8f
    private var cols = 60
    private var rows = 110
    private var cell = 0.015f
    private var floor = ByteArray(0)
    private var duration = 20f
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var winner = -1
    private var leader = -1
    private var leadChanges = 0
    private var lastSecond = -1
    private val colour = IntArray(MAX)

    override fun setup(env: SimEnv) {
        teams = env.params.i("colours", 4).coerceIn(2, MAX)
        duration = env.params.f("time", 20f).coerceIn(5f, 90f)
        halfW = env.halfWidth * 0.94f
        halfH = 0.84f
        cols = 90
        cell = 2f * halfW / cols
        rows = (2f * halfH / cell).toInt()
        halfH = rows * cell * 0.5f
        floor = ByteArray(cols * rows) { -1 }
        r = halfW * 0.11f
        val rng = env.rng
        val speed = halfW * 1.9f
        for (t in 0 until teams) {
            // Spread round the middle, each heading off at random.
            val a = 2f * PI.toFloat() * t / teams + PI.toFloat() / 4f
            x[t] = cos(a) * halfW * 0.45f; y[t] = sin(a) * halfH * 0.45f
            val va = rng.range(0f, 2f * PI.toFloat())
            vx[t] = cos(va) * speed; vy[t] = sin(va) * speed
            counts[t] = 0
        }
        elapsed = 0f; doneAt = -1f; payoffFrame = false
        winner = -1; leader = -1; leadChanges = 0; lastSecond = -1
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h0 = abs(dt) * env.params.f("pace", 1f).coerceIn(0.2f, 3f)
        if (doneAt >= 0f) { elapsed += h0; return }
        elapsed += h0
        r = halfW * 0.11f * env.params.f("brush", 1f).coerceIn(0.4f, 2f)
        val sub = 3
        val h = h0 / sub
        repeat(sub) {
            for (i in 0 until teams) {
                x[i] += vx[i] * h; y[i] += vy[i] * h
                if (x[i] < -halfW + r) { x[i] = -halfW + r; vx[i] = abs(vx[i]) }
                if (x[i] > halfW - r) { x[i] = halfW - r; vx[i] = -abs(vx[i]) }
                if (y[i] < -halfH + r) { y[i] = -halfH + r; vy[i] = abs(vy[i]) }
                if (y[i] > halfH - r) { y[i] = halfH - r; vy[i] = -abs(vy[i]) }
            }
            for (i in 0 until teams) for (j in i + 1 until teams) {
                val dx = x[j] - x[i]; val dy = y[j] - y[i]
                val d = sqrt(dx * dx + dy * dy)
                if (d < 2f * r && d > 1e-5f) {
                    val nx = dx / d; val ny = dy / d
                    val rel = (vx[j] - vx[i]) * nx + (vy[j] - vy[i]) * ny
                    if (rel < 0f) {
                        vx[i] += rel * nx; vy[i] += rel * ny; vx[j] -= rel * nx; vy[j] -= rel * ny
                        env.emit(EventKind.HIT, 0.5f, ((x[i] + x[j]) * 0.5f / halfW).coerceIn(-1f, 1f), 0.4f)
                    }
                    val push = (2f * r - d) * 0.5f
                    x[i] -= nx * push; y[i] -= ny * push; x[j] += nx * push; y[j] += ny * push
                }
            }
            for (i in 0 until teams) paint(i)
        }
        // The lead, and the clock's last seconds.
        var best = -1
        for (t in 0 until teams) if (best < 0 || counts[t] > counts[best]) best = t
        if (best != leader && counts[best] > 0) {
            if (leader >= 0) { leadChanges++; env.emit(EventKind.CROSS, 0.4f + 0.5f * best / teams, 0f, 0.5f) }
            leader = best
        }
        val left = duration - elapsed
        val sec = kotlin.math.ceil(left).toInt()
        if (sec != lastSecond) {
            lastSecond = sec
            if (sec in 1..5) env.emit(EventKind.STEP, 0.3f + 0.1f * (5 - sec), 0f, 0.55f)
        }
        if (left <= 0f) {
            winner = best
            doneAt = elapsed
            payoffFrame = true
            env.emit(EventKind.DONE, 0.9f, 0f, 1f)
        }
    }

    /** Paint the cells under ball [i] its colour. */
    private fun paint(i: Int) {
        val cx = ((x[i] + halfW) / cell).toInt()
        val cy = ((y[i] + halfH) / cell).toInt()
        val reach = (r / cell).toInt() + 1
        val r2 = (r / cell) * (r / cell)
        val me = i.toByte()
        for (dy in -reach..reach) for (dx in -reach..reach) {
            if (dx * dx + dy * dy > r2) continue
            val qx = cx + dx; val qy = cy + dy
            if (qx !in 0 until cols || qy !in 0 until rows) continue
            val k = qy * cols + qx
            val was = floor[k]
            if (was == me) continue
            if (was >= 0) counts[was.toInt()]--
            floor[k] = me
            counts[i]++
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        for (t in 0 until teams) colour[t] = pal.at(if (teams == 2) (if (t == 0) 0.05f else 0.6f) else (t + 0.5f) / teams)
        val bare = Palette.lerp(Palette.BG, Palette.TEXT_PRIMARY, 0.12f)
        val x0 = -halfW
        for (row in 0 until rows) {
            val base = row * cols
            val yc = -halfH + (row + 0.5f) * cell
            g.cellRow(x0, yc, cell, cols) { c ->
                val v = floor[base + c].toInt()
                if (v < 0) bare else Palette.lerp(colour[v], Palette.BG, 0.25f)
            }
        }
        for (i in 0 until teams) {
            val c = colour[i]
            g.disc(x[i], y[i], r * 1.08f, Palette.BG, 0.6f)
            g.disc(x[i], y[i], r, Palette.brighten(c, 0.15f))
            if (i == leader && doneAt < 0f) g.ring(x[i], y[i], r * 1.3f, r * 0.12f, Palette.TEXT_PRIMARY, alpha = 0.8f)
        }
        // Across the top: the shares, and under them the clock running down.
        val total = max(1, cols * rows)
        var left = -halfW
        val yBar = halfH + 0.06f
        for (t in 0 until teams) {
            val w = 2f * halfW * counts[t] / total
            if (w > 0f) g.rect(left + w * 0.5f, yBar, w * 0.5f, 0.016f, colour[t])
            left += w
        }
        val share = (1f - elapsed / duration).coerceIn(0f, 1f)
        val urgent = duration - elapsed < 5f && doneAt < 0f
        g.rect(-halfW + halfW * share, -halfH - 0.05f, halfW * share, 0.008f, if (urgent) Palette.DANGER else Palette.TEXT_SECONDARY)
        if (winner >= 0) {
            val f = ((elapsed - doneAt) / 0.6f).coerceIn(0f, 1f)
            g.glow(x[winner], y[winner], r * 4f, colour[winner], 0.8f * (1f - 0.5f * f))
        }
    }

    override fun frameHalfWidth(env: SimEnv): Float = halfW + 0.01f

    override fun contenders(env: SimEnv): IntArray =
        IntArray(teams) { env.palette.at(if (teams == 2) (if (it == 0) 0.05f else 0.6f) else (it + 0.5f) / teams) }

    override fun winner(): Int = winner

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && elapsed - doneAt > 4f

    override fun counters(): List<Pair<String, String>> {
        val total = (cols * rows).toFloat()
        val out = ArrayList<Pair<String, String>>(teams + 2)
        out.add("time left" to String.format(java.util.Locale.US, "%.1f s", max(0f, duration - elapsed)))
        for (t in 0 until teams) out.add("colour ${t + 1}" to String.format(java.util.Locale.US, "%.0f%%", 100f * counts[t] / total))
        out.add("lead changes" to "$leadChanges")
        return out
    }

    companion object {
        const val MAX = 6

        fun def(): SimDef = SimDef(
            id = "physics/paint-battle",
            title = "Paint battle",
            shelf = Shelf.PHYSICS,
            family = "Battles",
            blurb = "Every ball paints the floor as it rolls, over the others too. Most floor when the clock runs out wins.",
            formats = listOf(TrapFormat.COLOR_BATTLE, TrapFormat.GUESS),
            heroLabel = "New floor",
            defaultPalette = "Spectrum",
            typicalSeconds = 22f,
            params = listOf(
                Param.int("colours", "Colours", 2, MAX, 4, card = Card.DATA),
                Param.float("time", "Clock", 5f, 90f, 20f, card = Card.DATA, unit = "s"),
                Param.float("pace", "Pace", 0.3f, 3f, 1f, card = Card.MOTION, logScale = true),
                Param.float("brush", "Brush size", 0.4f, 2f, 1f, card = Card.MOTION)
            ),
            create = { PaintBattleSim() }
        )
    }
}
