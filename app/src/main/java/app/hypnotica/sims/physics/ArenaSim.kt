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
 * A battle royale for bouncing balls.
 *
 * Every ball has a health ring. When two collide, the one moving faster
 * deals the damage — so speed is everything, and every wall bounce is a
 * little kick. The arena closes in as the fight goes on; whoever is left at
 * the end is the winner, and nobody can say who that will be until it's
 * nearly over.
 */
class ArenaSim : Sim {

    private var n = 8
    private var x = FloatArray(0)
    private var y = FloatArray(0)
    private var vx = FloatArray(0)
    private var vy = FloatArray(0)
    private var hp = FloatArray(0)
    private var hit = FloatArray(0)
    private var tint = FloatArray(0)
    private var aliveCount = 0
    private var radius = 0.05f
    private var arena = 0.8f
    private var arena0 = 0.8f
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var winner = -1
    // Debris from the knocked-out.
    private var dx = FloatArray(0)
    private var dy = FloatArray(0)
    private var dvx = FloatArray(0)
    private var dvy = FloatArray(0)
    private var dlife = FloatArray(0)
    private var dtint = FloatArray(0)
    private var dNext = 0

    override fun setup(env: SimEnv) {
        n = env.params.i("fighters", 8).coerceIn(2, MAX_FIGHTERS)
        arena0 = min(env.halfWidth, 1f) * 0.92f
        arena = arena0
        radius = (arena0 * 0.11f * sqrt(8f / n)).coerceIn(0.012f, 0.1f)
        x = FloatArray(n); y = FloatArray(n); vx = FloatArray(n); vy = FloatArray(n)
        hp = FloatArray(n) { 1f }; hit = FloatArray(n)
        tint = FloatArray(n) { (it + 0.5f) / n }
        for (i in 0 until n) {
            // Round the edge, facing in, so the first clashes come quickly.
            val a = 2f * PI.toFloat() * i / n + env.rng.range(-0.2f, 0.2f)
            val r = arena0 * env.rng.range(0.3f, 0.7f)
            x[i] = cos(a) * r; y[i] = sin(a) * r
            val s = env.rng.range(0.4f, 0.7f)
            val b = env.rng.range(0f, 2f * PI.toFloat())
            vx[i] = cos(b) * s; vy[i] = sin(b) * s
        }
        aliveCount = n
        elapsed = 0f
        doneAt = -1f
        payoffFrame = false
        winner = -1
        dx = FloatArray(DEBRIS); dy = FloatArray(DEBRIS); dvx = FloatArray(DEBRIS); dvy = FloatArray(DEBRIS)
        dlife = FloatArray(DEBRIS); dtint = FloatArray(DEBRIS); dNext = 0
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        val pace = env.params.f("pace", 1f)
        // The arena closes in once the opening skirmish is over.
        if (doneAt < 0f && elapsed > SHRINK_AFTER) {
            arena = max(arena0 * MIN_ARENA, arena - arena0 * SHRINK_RATE * h * pace)
        }
        val sub = 4
        val dh = h * pace / sub
        repeat(sub) {
            for (i in 0 until n) {
                if (hp[i] <= 0f) continue
                x[i] += vx[i] * dh; y[i] += vy[i] * dh
                // The arena wall: bounce, and a little kick from it.
                val d = sqrt(x[i] * x[i] + y[i] * y[i])
                if (d > arena - radius) {
                    val nx = x[i] / d; val ny = y[i] / d
                    x[i] = nx * (arena - radius); y[i] = ny * (arena - radius)
                    val vn = vx[i] * nx + vy[i] * ny
                    if (vn > 0f) {
                        vx[i] -= 2f * vn * nx; vy[i] -= 2f * vn * ny
                        val sp = sqrt(vx[i] * vx[i] + vy[i] * vy[i]).coerceAtLeast(1e-4f)
                        val boosted = min(MAX_SPEED, sp * WALL_KICK)
                        vx[i] *= boosted / sp; vy[i] *= boosted / sp
                        env.emit(EventKind.STEP, 0.3f + 0.5f * tint[i], (x[i] / max(0.1f, arena0)).coerceIn(-1f, 1f), 0.18f)
                        // The zone: once the wall is closing, touching it
                        // hurts, and more the smaller it gets. Two fighters
                        // circling each other along the wall can't last.
                        if (arena < arena0 && doneAt < 0f) {
                            hp[i] -= ZONE_DAMAGE * arena0 / arena
                            hit[i] = max(hit[i], 0.5f)
                            if (hp[i] <= 0f) knockOut(env, i)
                        }
                    }
                }
            }
            for (i in 0 until n) {
                if (hp[i] <= 0f) continue
                for (j in i + 1 until n) {
                    if (hp[j] <= 0f) continue
                    val ex = x[j] - x[i]; val ey = y[j] - y[i]
                    val d2 = ex * ex + ey * ey
                    if (d2 >= 4f * radius * radius || d2 < 1e-10f) continue
                    val d = sqrt(d2)
                    val nx = ex / d; val ny = ey / d
                    val rel = (vx[i] - vx[j]) * nx + (vy[i] - vy[j]) * ny
                    // Apart, then bounce.
                    val push = (2f * radius - d) * 0.5f
                    x[i] -= nx * push; y[i] -= ny * push
                    x[j] += nx * push; y[j] += ny * push
                    if (rel <= 0f) continue
                    vx[i] -= rel * nx; vy[i] -= rel * ny
                    vx[j] += rel * nx; vy[j] += rel * ny
                    // The faster one hits the slower one, harder the faster.
                    val si = vx[i] * vx[i] + vy[i] * vy[i]
                    val sj = vx[j] * vx[j] + vy[j] * vy[j]
                    val (att, def) = if (si >= sj) i to j else j to i
                    val dmg = (DAMAGE * rel * env.params.f("damage", 1f)).coerceIn(0.03f, 0.6f)
                    hp[def] -= dmg
                    hit[def] = 1f
                    env.emit(EventKind.HIT, 0.25f + 0.6f * tint[att], (x[def] / max(0.1f, arena0)).coerceIn(-1f, 1f), (0.3f + dmg).coerceAtMost(1f))
                    if (hp[def] <= 0f) knockOut(env, def)
                }
            }
        }
        for (i in 0 until n) if (hit[i] > 0f) hit[i] = max(0f, hit[i] - h * 4f)
        for (k in 0 until DEBRIS) {
            if (dlife[k] <= 0f) continue
            dlife[k] -= h
            dvx[k] *= 1f - 2f * h; dvy[k] *= 1f - 2f * h
            dx[k] += dvx[k] * h; dy[k] += dvy[k] * h
        }
    }

    private fun knockOut(env: SimEnv, i: Int) {
        hp[i] = 0f
        aliveCount--
        env.emit(EventKind.STEP, 0.1f, (x[i] / max(0.1f, arena0)).coerceIn(-1f, 1f), 0.9f)
        repeat(DEBRIS_PER_KO) {
            val k = dNext; dNext = (dNext + 1) % DEBRIS
            val a = env.rng.range(0f, 2f * PI.toFloat())
            val s = env.rng.range(0.2f, 1.2f)
            dx[k] = x[i]; dy[k] = y[i]; dvx[k] = cos(a) * s; dvy[k] = sin(a) * s
            dlife[k] = env.rng.range(0.5f, 1.4f); dtint[k] = tint[i]
        }
        if (aliveCount <= 1 && doneAt < 0f) {
            winner = (0 until n).firstOrNull { hp[it] > 0f } ?: i
            doneAt = elapsed
            payoffFrame = true
            env.emit(EventKind.DONE, 0.3f + 0.6f * tint[winner], 0f, 1f)
        }
    }

    /** Everyone still standing gets a burst of speed. */
    override fun hero(env: SimEnv) {
        if (doneAt >= 0f) return
        for (i in 0 until n) {
            if (hp[i] <= 0f) continue
            vx[i] *= 1.6f; vy[i] *= 1.6f
        }
        env.emit(EventKind.STEP, 0.8f, 0f, 0.8f)
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        // The arena, and where it started, faintly.
        g.ring(0f, 0f, arena0, 0.004f, Palette.TEXT_SECONDARY, alpha = 0.25f)
        g.ring(0f, 0f, arena, 0.012f, if (arena < arena0) Palette.DANGER else Palette.TEXT_SECONDARY, alpha = 0.85f)
        for (k in 0 until DEBRIS) {
            if (dlife[k] <= 0f) continue
            g.point(dx[k], dy[k], radius * 0.35f * min(1f, dlife[k] * 2f), palette.at(dtint[k]))
        }
        for (i in 0 until n) {
            if (hp[i] <= 0f) continue
            val c = palette.at(tint[i])
            val r = radius * (1f + 0.25f * hit[i])
            if (i == winner) g.glow(x[i], y[i], radius * (3f + sin(elapsed * 6f)), c, 0.6f)
            g.disc(x[i], y[i], r, if (hit[i] > 0f) Palette.brighten(c, 0.7f * hit[i]) else c)
            // Health: a ring with the lost share cut away, from the top.
            val lost = (1f - hp[i]).coerceIn(0f, 1f) * 2f * PI.toFloat()
            g.ring(x[i], y[i], r * 1.45f, r * 0.22f, Palette.withAlpha(Palette.TEXT_SECONDARY, 0.25f))
            if (hp[i] > 0f) g.ring(x[i], y[i], r * 1.45f, r * 0.22f, if (hp[i] < 0.3f) Palette.DANGER else Palette.SUCCESS, lost, (PI / 2).toFloat())
        }
    }

    override fun frameHalfWidth(env: SimEnv): Float = 0f

    override fun contenders(env: SimEnv): IntArray =
        if (n > MAX_PICKS) app.hypnotica.core.sim.NO_CONTENDERS else IntArray(n) { env.palette.at(tint[it]) }

    override fun winner(): Int = if (n > MAX_PICKS) -1 else winner

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = (doneAt >= 0f && elapsed - doneAt > 3f) || elapsed > GIVE_UP_SECONDS

    override fun counters(): List<Pair<String, String>> =
        if (winner >= 0) listOf("winner" to "#${winner + 1}")
        else listOf("standing" to "$aliveCount / $n")

    companion object {
        const val MAX_FIGHTERS = 64
        const val SHRINK_AFTER = 4f
        const val SHRINK_RATE = 0.035f
        const val MIN_ARENA = 0.3f
        const val WALL_KICK = 1.04f
        const val MAX_SPEED = 2.4f
        const val DAMAGE = 0.15f
        const val DEBRIS = 600
        const val DEBRIS_PER_KO = 40
        const val GIVE_UP_SECONDS = 90f
        const val ZONE_DAMAGE = 0.012f
        const val MAX_PICKS = 8

        fun def(): SimDef = SimDef(
            id = "physics/arena",
            title = "Ball battle",
            shelf = Shelf.PHYSICS,
            family = "Battles",
            blurb = "Faster ball deals the damage. The arena closes in. Last one standing.",
            formats = listOf(TrapFormat.LAST_STANDING, TrapFormat.GUESS, TrapFormat.COLOR_BATTLE),
            heroLabel = "Frenzy",
            heroRestarts = false,
            defaultPalette = "Spectrum",
            typicalSeconds = 11f,
            params = listOf(
                Param.int("fighters", "Fighters", 2, MAX_FIGHTERS, 8, card = Card.DATA),
                Param.float("pace", "Pace", 0.3f, 4f, 1f, card = Card.MOTION),
                Param.float("damage", "Damage", 0.2f, 4f, 1f, card = Card.MOTION)
            ),
            create = { ArenaSim() }
        )
    }
}
