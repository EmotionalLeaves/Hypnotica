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
 * Weapon balls: bouncing balls that each carry a spinning weapon, and get
 * better with every hit they land. The sword grows longer, the dagger spins
 * faster, the spear hits harder, the hammer hits hardest of all but turns
 * slowly. Blade on blade is a parry — both weapons bounce back and spin the
 * other way. A ball is out when its health ring is gone; last one bouncing
 * wins.
 *
 * Nothing is scripted: who wins is the bounces, the spins and the parries,
 * and a weapon that got lucky early snowballs.
 */
class WeaponBallSim : Sim {

    private var n = 2
    private val x = FloatArray(MAX)
    private val y = FloatArray(MAX)
    private val vx = FloatArray(MAX)
    private val vy = FloatArray(MAX)
    private val hp = FloatArray(MAX)
    private val ang = FloatArray(MAX)
    private val spin = FloatArray(MAX)
    private val reach = FloatArray(MAX)
    private val dmg = FloatArray(MAX)
    private val kind = IntArray(MAX)
    private val tint = FloatArray(MAX)
    private val flash = FloatArray(MAX)
    private val gone = BooleanArray(MAX)
    private val hits = IntArray(MAX)
    /** Per attacker and target, seconds until that blade may hit that ball again. */
    private val cool = FloatArray(MAX * MAX)
    private val parryCool = FloatArray(MAX * MAX)
    private var r = 0.08f
    /** The Whirl control: every weapon's spin, scaled live. */
    private var spinMul = 1f
    private var arena = 0.44f
    private var speed = 0.9f
    private var alive = 2
    private var winner = -1
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    // Sparks: from parries and hits.
    private val sx = FloatArray(SPARKS)
    private val sy = FloatArray(SPARKS)
    private val svx = FloatArray(SPARKS)
    private val svy = FloatArray(SPARKS)
    private val slife = FloatArray(SPARKS)
    private val scol = IntArray(SPARKS)
    private var sNext = 0

    override fun setup(env: SimEnv) {
        n = env.params.i("fighters", 2).coerceIn(2, MAX)
        arena = min(env.halfWidth, 1f) * 0.92f
        r = arena * (if (n == 2) 0.2f else 0.15f * sqrt(3f / n))
        speed = arena * 2.1f
        val rng = env.rng
        // Different weapons where there are enough to go round, dealt at random.
        val order = intArrayOf(0, 1, 2, 3)
        for (i in 3 downTo 1) { val j = rng.int(0, i + 1); val t = order[i]; order[i] = order[j]; order[j] = t }
        for (i in 0 until n) {
            val a = 2f * PI.toFloat() * i / n + rng.range(-0.3f, 0.3f)
            x[i] = cos(a) * arena * 0.5f; y[i] = sin(a) * arena * 0.5f
            val va = rng.range(0f, 2f * PI.toFloat())
            vx[i] = cos(va) * speed; vy[i] = sin(va) * speed
            hp[i] = 1f; flash[i] = 0f; gone[i] = false; hits[i] = 0
            kind[i] = order[i % 4]
            ang[i] = rng.range(0f, 2f * PI.toFloat())
            spin[i] = BASE_SPIN[kind[i]] * (if (rng.bool()) 1f else -1f)
            reach[i] = BASE_REACH[kind[i]] * r
            dmg[i] = BASE_DMG[kind[i]]
            tint[i] = if (n == 2) (if (i == 0) 0.05f else 0.55f) else (i + 0.5f) / n
        }
        cool.fill(0f); parryCool.fill(0f)
        slife.fill(0f)
        alive = n; winner = -1
        elapsed = 0f; doneAt = -1f; payoffFrame = false
    }

    private fun tipX(i: Int) = x[i] + cos(ang[i]) * (r + reach[i])
    private fun tipY(i: Int) = y[i] + sin(ang[i]) * (r + reach[i])
    private fun baseX(i: Int) = x[i] + cos(ang[i]) * r * 0.7f
    private fun baseY(i: Int) = y[i] + sin(ang[i]) * r * 0.7f

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h0 = abs(dt) * env.params.f("pace", 1f).coerceIn(0.2f, 3f)
        elapsed += h0
        spinMul = env.params.f("whirl", 1f).coerceIn(0.2f, 3f)
        val sub = 4
        val h = h0 / sub
        repeat(sub) { substep(env, h) }
        for (i in 0 until n) flash[i] = max(0f, flash[i] - h0 * 4f)
        for (k in 0 until SPARKS) if (slife[k] > 0f) {
            slife[k] -= h0 * 2.2f
            sx[k] += svx[k] * h0; sy[k] += svy[k] * h0
            svx[k] *= 0.9f; svy[k] *= 0.9f
        }
    }

    private fun substep(env: SimEnv, h: Float) {
        for (k in cool.indices) { if (cool[k] > 0f) cool[k] -= h; if (parryCool[k] > 0f) parryCool[k] -= h }
        for (i in 0 until n) {
            if (gone[i]) continue
            x[i] += vx[i] * h; y[i] += vy[i] * h
            ang[i] += spin[i] * spinMul * h
            // The wall: a reflection, and the speed held, so nobody stalls.
            val d = sqrt(x[i] * x[i] + y[i] * y[i])
            if (d > arena - r) {
                val nx = x[i] / d; val ny = y[i] / d
                val vn = vx[i] * nx + vy[i] * ny
                if (vn > 0f) { vx[i] -= 2f * vn * nx; vy[i] -= 2f * vn * ny }
                x[i] = nx * (arena - r); y[i] = ny * (arena - r)
            }
            val s = sqrt(vx[i] * vx[i] + vy[i] * vy[i])
            if (s > 1e-4f) { val k = speed / s; vx[i] *= k; vy[i] *= k }
        }
        // Ball on ball: elastic, equal masses.
        for (i in 0 until n) for (j in i + 1 until n) {
            if (gone[i] || gone[j]) continue
            val dx = x[j] - x[i]; val dy = y[j] - y[i]
            val d = sqrt(dx * dx + dy * dy)
            if (d < 2f * r && d > 1e-5f) {
                val nx = dx / d; val ny = dy / d
                val rel = (vx[j] - vx[i]) * nx + (vy[j] - vy[i]) * ny
                if (rel < 0f) { vx[i] += rel * nx; vy[i] += rel * ny; vx[j] -= rel * nx; vy[j] -= rel * ny }
                val push = (2f * r - d) * 0.5f
                x[i] -= nx * push; y[i] -= ny * push; x[j] += nx * push; y[j] += ny * push
            }
        }
        // Blade on blade: a parry.
        for (i in 0 until n) for (j in i + 1 until n) {
            if (gone[i] || gone[j] || parryCool[i * MAX + j] > 0f) continue
            if (segmentsCross(baseX(i), baseY(i), tipX(i), tipY(i), baseX(j), baseY(j), tipX(j), tipY(j))) {
                parryCool[i * MAX + j] = 0.25f
                spin[i] = -spin[i]; spin[j] = -spin[j]
                ang[i] += spin[i] * 0.03f; ang[j] += spin[j] * 0.03f
                val mx = (tipX(i) + tipX(j) + baseX(i) + baseX(j)) * 0.25f
                val my = (tipY(i) + tipY(j) + baseY(i) + baseY(j)) * 0.25f
                spark(env, mx, my, 8, Palette.TEXT_PRIMARY)
                env.emit(EventKind.CROSS, 0.85f, (mx / arena).coerceIn(-1f, 1f), 0.55f)
            }
        }
        // Blade on ball: a hit.
        for (i in 0 until n) {
            if (gone[i]) continue
            for (j in 0 until n) {
                if (j == i || gone[j] || cool[i * MAX + j] > 0f) continue
                val bx = baseX(i); val by = baseY(i)
                val ex = tipX(i) - bx; val ey = tipY(i) - by
                val len2 = ex * ex + ey * ey
                val t = (((x[j] - bx) * ex + (y[j] - by) * ey) / len2).coerceIn(0f, 1f)
                val px = bx + ex * t - x[j]; val py = by + ey * t - y[j]
                if (px * px + py * py < r * r) hit(env, i, j)
            }
        }
    }

    private fun hit(env: SimEnv, i: Int, j: Int) {
        cool[i * MAX + j] = 0.45f
        hp[j] -= dmg[i] / START_HP
        flash[j] = 1f
        hits[i]++
        // Knocked back along the line between them.
        val dx = x[j] - x[i]; val dy = y[j] - y[i]
        val d = max(1e-4f, sqrt(dx * dx + dy * dy))
        vx[j] += dx / d * speed * 0.8f; vy[j] += dy / d * speed * 0.8f
        // And the weapon that landed it gets better.
        val grow = env.params.f("growth", 1f).coerceIn(0f, 3f)
        when (kind[i]) {
            0 -> reach[i] = min(reach[i] + r * 0.12f * grow, r * 3.5f)   // sword: longer
            1 -> spin[i] = (spin[i] * (1f + 0.12f * grow)).coerceIn(-24f, 24f) // dagger: faster
            2 -> dmg[i] += 1f * grow                                   // spear: harder
            else -> { dmg[i] += 2f * grow; spin[i] *= 1f + 0.04f * grow } // hammer
        }
        val c = env.palette.at(tint[i])
        spark(env, x[j] + (x[i] - x[j]) * 0.4f, y[j] + (y[i] - y[j]) * 0.4f, 10, c)
        env.emit(EventKind.HIT, (1f - hp[j]).coerceIn(0f, 1f) * 0.7f + 0.2f, (x[j] / arena).coerceIn(-1f, 1f), 0.75f)
        if (hp[j] <= 0f) {
            hp[j] = 0f
            gone[j] = true
            alive--
            spark(env, x[j], y[j], 30, env.palette.at(tint[j]))
            env.emit(EventKind.DEATH, 0.4f, (x[j] / arena).coerceIn(-1f, 1f), 0.9f)
            if (alive == 1 && doneAt < 0f) {
                winner = (0 until n).first { !gone[it] }
                doneAt = elapsed
                payoffFrame = true
                env.emit(EventKind.DONE, 0.9f, 0f, 1f)
            }
        }
    }

    private fun spark(env: SimEnv, px: Float, py: Float, count: Int, c: Int) {
        repeat(count) {
            val k = sNext; sNext = (sNext + 1) % SPARKS
            val a = env.rng.range(0f, 2f * PI.toFloat())
            val s = env.rng.range(0.3f, 1.2f) * arena
            sx[k] = px; sy[k] = py; svx[k] = cos(a) * s; svy[k] = sin(a) * s
            slife[k] = 1f; scol[k] = c
        }
    }

    private fun segmentsCross(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float): Boolean {
        val d1 = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
        val d2 = (bx - ax) * (dy - ay) - (by - ay) * (dx - ax)
        val d3 = (dx - cx) * (ay - cy) - (dy - cy) * (ax - cx)
        val d4 = (dx - cx) * (by - cy) - (dy - cy) * (bx - cx)
        return d1 * d2 < 0f && d3 * d4 < 0f
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        g.disc(0f, 0f, arena, Palette.lerp(Palette.BG, pal.at(0.5f), 0.05f))
        g.ring(0f, 0f, arena, 0.01f, Palette.withAlpha(Palette.TEXT_PRIMARY, 0.7f))
        for (k in 0 until SPARKS) if (slife[k] > 0f) {
            val l = slife[k]
            g.line(sx[k], sy[k], sx[k] - svx[k] * 0.03f, sy[k] - svy[k] * 0.03f, 0.005f, scol[k], l)
        }
        for (i in 0 until n) {
            if (gone[i]) continue
            val c = pal.at(tint[i])
            // The weapon: its shape says what it is.
            val bx = baseX(i); val by = baseY(i)
            var tx = tipX(i); var ty = tipY(i)
            // Drawn only up to the wall: a long sword by the wall reached
            // out of the arena and off the screen.
            val tr = sqrt(tx * tx + ty * ty)
            if (tr > arena) {
                val ex = tx - bx; val ey = ty - by
                var lo = 0f; var hi = 1f
                repeat(12) { val m = (lo + hi) * 0.5f; val qx = bx + ex * m; val qy = by + ey * m; if (qx * qx + qy * qy > arena * arena) hi = m else lo = m }
                tx = bx + ex * lo; ty = by + ey * lo
            }
            val metal = Palette.lerp(Palette.TEXT_PRIMARY, c, 0.25f)
            when (kind[i]) {
                0 -> { // sword: a long blade with a crossguard
                    g.line(bx, by, tx, ty, r * 0.22f, metal)
                    val gx = x[i] + cos(ang[i]) * r * 1.05f; val gy = y[i] + sin(ang[i]) * r * 1.05f
                    val px = -sin(ang[i]) * r * 0.35f; val py = cos(ang[i]) * r * 0.35f
                    g.line(gx - px, gy - py, gx + px, gy + py, r * 0.12f, c)
                }
                1 -> { // dagger: short and wide
                    g.line(bx, by, tx, ty, r * 0.34f, metal)
                }
                2 -> { // spear: a shaft and a point
                    g.line(bx, by, tx, ty, r * 0.12f, Palette.lerp(c, Palette.TEXT_PRIMARY, 0.2f))
                    g.disc(tx, ty, r * 0.2f, metal)
                }
                else -> { // hammer: a handle and a head
                    g.line(bx, by, tx, ty, r * 0.14f, Palette.lerp(c, Palette.TEXT_PRIMARY, 0.2f))
                    g.rect(tx, ty, r * 0.3f, r * 0.45f, metal, ang[i])
                }
            }
            g.glow(x[i], y[i], r * 1.6f, c, 0.25f + 0.5f * flash[i])
            g.disc(x[i], y[i], r, if (flash[i] > 0f) Palette.lerp(c, Palette.TEXT_PRIMARY, 0.7f * flash[i]) else c)
            // Health: the band round the rim, the lost share cut from the top.
            val lost = (1f - hp[i]).coerceIn(0f, 1f) * 2f * PI.toFloat()
            g.ring(x[i], y[i], r * 0.78f, r * 0.16f, Palette.withAlpha(Palette.BG, 0.6f))
            g.ring(x[i], y[i], r * 0.76f, r * 0.12f, if (hp[i] < 0.3f) Palette.DANGER else Palette.TEXT_PRIMARY, lost, (PI / 2).toFloat())
            if (winner == i) g.glow(x[i], y[i], r * 3f, c, (1f - (elapsed - doneAt) / 2f).coerceIn(0.2f, 1f))
        }
    }

    override fun frameHalfWidth(env: SimEnv): Float = arena + 0.01f

    override fun contenders(env: SimEnv): IntArray = IntArray(n) { env.palette.at(tint[it]) }

    override fun winner(): Int = winner

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = (doneAt >= 0f && elapsed - doneAt > 4f) || elapsed > GIVE_UP

    override fun counters(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>(n)
        for (i in 0 until n) {
            val stat = when (kind[i]) {
                0 -> String.format(java.util.Locale.US, "reach %.0f", reach[i] / r * 10f)
                1 -> String.format(java.util.Locale.US, "spin %.1f", abs(spin[i]))
                else -> String.format(java.util.Locale.US, "hits for %.0f", dmg[i])
            }
            out.add(NAMES[kind[i]] to if (gone[i]) "out" else "${(hp[i] * START_HP).toInt()} hp · $stat")
        }
        return out
    }

    companion object {
        const val MAX = 4
        const val SPARKS = 240
        const val START_HP = 100f
        const val GIVE_UP = 120f
        val NAMES = arrayOf("Sword", "Dagger", "Spear", "Hammer")
        /** Spin in radians a second, reach in ball radii, damage per hit. */
        val BASE_SPIN = floatArrayOf(3.2f, 6.5f, 2.6f, 2.0f)
        val BASE_REACH = floatArrayOf(1.7f, 0.9f, 2.1f, 1.4f)
        val BASE_DMG = floatArrayOf(6f, 4f, 7.5f, 12f)

        fun def(): SimDef = SimDef(
            id = "physics/weapon-balls",
            title = "Weapon balls",
            shelf = Shelf.PHYSICS,
            family = "Battles",
            blurb = "Every hit makes the weapon better: the sword grows, the dagger spins faster. Blade on blade is a parry.",
            formats = listOf(TrapFormat.GUESS, TrapFormat.LAST_STANDING, TrapFormat.COLOR_BATTLE),
            heroLabel = "Rematch",
            defaultPalette = "Spectrum",
            typicalSeconds = 24f,
            params = listOf(
                Param.int("fighters", "Fighters", 2, MAX, 2, card = Card.DATA),
                Param.float("pace", "Pace", 0.3f, 3f, 1f, card = Card.MOTION, logScale = true),
                Param.float("growth", "Power-up per hit", 0f, 3f, 1f, card = Card.DATA),
                Param.float("whirl", "Whirl", 0.2f, 3f, 1f, card = Card.MOTION, logScale = true)
            ),
            create = { WeaponBallSim() }
        )
    }
}
