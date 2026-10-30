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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Clone war: one ball of each colour. Every time a ball hits the wall it
 * splits in two; every time two balls of different colours touch, both are
 * gone. One becomes dozens in seconds, the two swarms meet in the middle and
 * annihilate in bursts, and whichever side happens to be breeding faster at
 * the moment pushes the line back. After a while the splitting stops, and
 * the last ball standing decides it.
 */
class CloneWarSim : Sim {

    private var teams = 2
    private val x = FloatArray(MAX)
    private val y = FloatArray(MAX)
    private val vx = FloatArray(MAX)
    private val vy = FloatArray(MAX)
    private val team = IntArray(MAX)
    private val born = FloatArray(MAX)
    private var count = 0
    private val counts = IntArray(4)
    private val peak = IntArray(4)
    private var r = 0.012f
    private var arena = 0.44f
    private var speed = 0.5f
    private var winner = -1
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var voice = 0f
    // Bursts where two met.
    private val bx = FloatArray(BURSTS)
    private val by = FloatArray(BURSTS)
    private val bl = FloatArray(BURSTS)
    private var bNext = 0
    private var cell = IntArray(0)
    private var next = IntArray(MAX)
    private var gw = 1

    override fun setup(env: SimEnv) {
        teams = env.params.i("colours", 2).coerceIn(2, 4)
        arena = min(env.halfWidth, 1f) * 0.92f
        r = arena * 0.03f
        speed = arena * 0.9f
        count = 0
        counts.fill(0); peak.fill(0)
        // A small squad each, on opposite sides: one ball apiece was over
        // before it began whenever an unlucky first clash took a lone ball.
        for (t in 0 until teams) {
            val a = 2f * PI.toFloat() * t / teams + PI.toFloat() / 2f
            repeat(SQUAD) { add(env, cos(a) * arena * 0.55f + env.rng.range(-0.05f, 0.05f) * arena, sin(a) * arena * 0.55f + env.rng.range(-0.05f, 0.05f) * arena, t) }
        }
        bl.fill(0f)
        gw = max(1, (2f * arena / (2f * r)).toInt())
        cell = IntArray(gw * gw)
        winner = -1; elapsed = 0f; doneAt = -1f; payoffFrame = false; voice = 0f
        rngClash = app.hypnotica.core.sim.Rng(env.rng.int(0, Int.MAX_VALUE).toLong())
    }

    private fun add(env: SimEnv, px: Float, py: Float, t: Int) {
        if (count >= MAX) return
        val a = env.rng.range(0f, 2f * PI.toFloat())
        x[count] = px; y[count] = py
        vx[count] = cos(a) * speed; vy[count] = sin(a) * speed
        team[count] = t; born[count] = elapsed
        count++
        counts[t]++
        peak[t] = max(peak[t], counts[t])
    }

    private fun remove(i: Int) {
        counts[team[i]]--
        count--
        x[i] = x[count]; y[i] = y[count]; vx[i] = vx[count]; vy[i] = vy[count]
        team[i] = team[count]; born[i] = born[count]
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h0 = abs(dt) * env.params.f("pace", 1f).coerceIn(0.2f, 3f)
        elapsed += h0
        for (k in 0 until BURSTS) if (bl[k] > 0f) bl[k] -= h0 * 2.5f
        val rush = env.params.f("rush", 1f).coerceIn(0.3f, 3f)
        val breeding = doneAt < 0f && elapsed < env.params.f("breed", 22f)
        var splits = 0
        var clashes = 0
        val sub = 2
        val h = h0 / sub
        repeat(sub) {
            var i = 0
            val n0 = count
            while (i < n0 && i < count) {
                x[i] += vx[i] * h * rush; y[i] += vy[i] * h * rush
                val d = sqrt(x[i] * x[i] + y[i] * y[i])
                if (d > arena - r) {
                    val nx = x[i] / d; val ny = y[i] / d
                    val vn = vx[i] * nx + vy[i] * ny
                    if (vn > 0f) {
                        vx[i] -= 2f * vn * nx; vy[i] -= 2f * vn * ny
                        // Every wall hit is a split, while there is room.
                        if (breeding && count < CAP) { add(env, x[i] - nx * r, y[i] - ny * r, team[i]); splits++ }
                    }
                    x[i] = nx * (arena - r); y[i] = ny * (arena - r)
                }
                i++
            }
            // A truce at first, while the two swarms breed: then war.
            if (elapsed > TRUCE) clashes += collide()
        }
        if (clashes > 0) env.emit(EventKind.DEATH, 0.3f + 0.4f * (count.toFloat() / CAP), 0f, min(0.8f, 0.25f + clashes * 0.05f))
        voice += h0
        if (splits > 0 && voice > 0.06f) {
            voice = 0f
            env.emit(EventKind.BIRTH, (0.2f + 0.7f * count / CAP.toFloat()).coerceIn(0f, 1f), 0f, min(0.4f, 0.1f + splits * 0.02f))
        }
        var standing = 0; var last = -1
        for (t in 0 until teams) if (counts[t] > 0) { standing++; last = t }
        if (doneAt < 0f && standing <= 1 && elapsed > TRUCE) {
            winner = last
            doneAt = elapsed
            payoffFrame = true
            env.emit(EventKind.DONE, 0.9f, 0f, 1f)
        }
    }

    /** Opposite colours touching are both removed. A grid keeps it cheap. */
    private fun collide(): Int {
        cell.fill(-1)
        for (i in 0 until count) {
            val c = cellOf(x[i], y[i])
            next[i] = cell[c]; cell[c] = i
        }
        var gone = 0
        val km = killMark
        for (i in 0 until count) km[i] = false
        for (i in 0 until count) {
            if (km[i]) continue
            val cx = ((x[i] + arena) / (2f * r)).toInt().coerceIn(0, gw - 1)
            val cy = ((y[i] + arena) / (2f * r)).toInt().coerceIn(0, gw - 1)
            loop@ for (oy in -1..1) for (ox in -1..1) {
                val qx = cx + ox; val qy = cy + oy
                if (qx !in 0 until gw || qy !in 0 until gw) continue
                var j = cell[qy * gw + qx]
                while (j >= 0) {
                    if (j != i && !km[j] && team[j] != team[i]) {
                        val dx = x[j] - x[i]; val dy = y[j] - y[i]
                        if (dx * dx + dy * dy < 4f * r * r && rngClash.nextFloat() < CLASH) {
                            km[i] = true; km[j] = true
                            val k = bNext; bNext = (bNext + 1) % BURSTS
                            bx[k] = (x[i] + x[j]) * 0.5f; by[k] = (y[i] + y[j]) * 0.5f; bl[k] = 1f
                            gone++
                            break@loop
                        }
                    }
                    j = next[j]
                }
            }
        }
        // Removed from the end down, so swapping in the last keeps indices sound.
        var i = count - 1
        while (i >= 0) { if (km[i]) { km[i] = km[count - 1]; remove(i) }; i-- }
        return gone
    }

    private var killMark = BooleanArray(MAX)
    private var rngClash = app.hypnotica.core.sim.Rng(1L)

    private fun cellOf(px: Float, py: Float): Int {
        val cx = ((px + arena) / (2f * r)).toInt().coerceIn(0, gw - 1)
        val cy = ((py + arena) / (2f * r)).toInt().coerceIn(0, gw - 1)
        return cy * gw + cx
    }

    private fun colour(env: SimEnv, t: Int): Int = env.palette.at(if (teams == 2) (if (t == 0) 0.05f else 0.6f) else (t + 0.5f) / teams)

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        g.disc(0f, 0f, arena, Palette.lerp(Palette.BG, env.palette.at(0.5f), 0.05f))
        g.ring(0f, 0f, arena, 0.008f, Palette.withAlpha(Palette.TEXT_PRIMARY, 0.6f))
        for (k in 0 until BURSTS) if (bl[k] > 0f) {
            val f = bl[k]
            g.ring(bx[k], by[k], r * (1f + 3f * (1f - f)), r * 0.5f * f + 0.002f, Palette.TEXT_PRIMARY, alpha = f * 0.8f)
        }
        g.reserveInstances(count)
        for (i in 0 until count) {
            val c = colour(env, team[i])
            val fresh = ((elapsed - born[i]) / 0.25f).coerceIn(0f, 1f)
            g.disc(x[i], y[i], r * (0.6f + 0.4f * fresh), if (fresh < 1f) Palette.lerp(Palette.TEXT_PRIMARY, c, fresh) else c)
        }
        // The tally across the top: each colour's share of the balls.
        val total = max(1, count)
        var left = -arena
        val y0 = arena + 0.05f
        for (t in 0 until teams) {
            val w = 2f * arena * counts[t] / total
            if (w > 0f) g.rect(left + w * 0.5f, y0, w * 0.5f, 0.012f, colour(env, t))
            left += w
        }
        if (winner >= 0) g.glow(0f, 0f, arena, colour(env, winner), (1f - (elapsed - doneAt) / 2f).coerceIn(0.15f, 0.6f))
    }

    override fun frameHalfWidth(env: SimEnv): Float = arena + 0.01f

    override fun contenders(env: SimEnv): IntArray = IntArray(teams) { colour(env, it) }

    override fun winner(): Int = winner

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = (doneAt >= 0f && elapsed - doneAt > 4f) || elapsed > 90f

    override fun counters(): List<Pair<String, String>> =
        (0 until teams).map { t -> "colour ${t + 1}" to (if (counts[t] == 0 && elapsed > 1f) "wiped out" else "${counts[t]} (peak ${peak[t]})") }

    companion object {
        const val MAX = 600
        const val CAP = 360
        const val BURSTS = 120
        const val SQUAD = 6
        const val TRUCE = 5f
        /** Chance, each touch, that two opposite balls annihilate rather than pass. */
        const val CLASH = 0.35f

        fun def(): SimDef = SimDef(
            id = "physics/clone-war",
            title = "Clone war",
            shelf = Shelf.PHYSICS,
            family = "Battles",
            blurb = "Every wall hit splits a ball in two; opposite colours that touch are both gone. Last colour standing.",
            formats = listOf(TrapFormat.COLOR_BATTLE, TrapFormat.GUESS, TrapFormat.CHAIN),
            heroLabel = "Start over",
            defaultPalette = "Spectrum",
            typicalSeconds = 16f,
            params = listOf(
                Param.int("colours", "Colours", 2, 4, 2, card = Card.DATA),
                Param.float("breed", "Splitting stops after", 5f, 60f, 22f, card = Card.DATA, unit = "s"),
                Param.float("pace", "Pace", 0.3f, 3f, 1f, card = Card.MOTION, logScale = true),
                Param.float("rush", "Ball speed", 0.3f, 3f, 1f, card = Card.MOTION, logScale = true)
            ),
            create = { CloneWarSim() }
        )
    }
}
