package app.hypnotica.sims.physics

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
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Three stars, Newton's gravity, nothing else.
 *
 * - **3, 4, 5 triangle** (Burrau, 1913): masses 3, 4 and 5 at rest on the
 *   corners of a 3-4-5 right triangle, each opposite the side of its own
 *   length. After a long tangle of near misses the lightest is thrown out and
 *   the other two leave the other way as a pair — the ending Szebehely and
 *   Peters found in 1967, and the one this integrator lands on too.
 * - **Figure eight** (Moore 1993; Chenciner and Montgomery 2000): three equal
 *   stars chasing each other round one figure-eight path for ever. The kick
 *   pushes the middle star sideways to see how hard it has to be to break it.
 * - **Thrown together**: three equal stars dropped from rest at random. Half
 *   of such systems throw a star out within a few dozen crossings.
 * - **Butterfly** and **Moth** (Šuvakov and Dmitrašinović, 2013): two more of
 *   the orbits that should not exist — three equal stars on a line, the
 *   outer two thrown the same way and the middle one twice as hard the other,
 *   that come back exactly to where they began.
 */
class ThreeStarsSim : Sim {

    private val b = ThreeBody()
    private var scenario = 0
    private var scale = 0.12f
    private var rate = 4f
    private var eta = 0.005
    private var ejected = -1
    private var ejectedAt = -1f
    private var loops = 0
    private var lastNear = false
    private var nearGlow = FloatArray(3)
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false

    private val tx = Array(3) { FloatArray(TRAIL) }
    private val ty = Array(3) { FloatArray(TRAIL) }
    private var tHead = 0
    private var tCount = 0
    private var tTick = 0f
    private var trailLen = 240
    private val pairClose = BooleanArray(3)
    private val sx0 = DoubleArray(3)
    private val sy0 = DoubleArray(3)

    override fun setup(env: SimEnv) {
        scenario = env.params.choiceIndex("start")
        b.reset()
        when (scenario) {
            0 -> {
                b.set(0, 3.0, 1.0, 3.0, 0.0, 0.0)
                b.set(1, 4.0, -2.0, -1.0, 0.0, 0.0)
                b.set(2, 5.0, 1.0, -1.0, 0.0, 0.0)
                scale = env.halfWidth * 0.92f / 4.2f
                rate = 3.6f
                eta = 0.005
            }
            1 -> {
                val kick = env.params.f("kick", 0f).toDouble()
                b.set(0, 1.0, -0.97000436, 0.24308753, 0.4662036850, 0.4323657300)
                b.set(1, 1.0, 0.97000436, -0.24308753, 0.4662036850, 0.4323657300)
                // The middle star, nudged at right angles to its path by [kick].
                val vx = -0.93240737; val vy = -0.86473146
                val sp = sqrt(vx * vx + vy * vy)
                b.set(2, 1.0, 0.0, 0.0, vx - kick * vy / sp * sp, vy + kick * vx / sp * sp)
                scale = env.halfWidth * 0.92f / 1.1f
                rate = 0.85f
                eta = 0.01
            }
            3, 4 -> {
                // Their table's p1, p2: velocities (p1, p2), (p1, p2), (-2p1, -2p2).
                val p1 = if (scenario == 3) 0.306893 else 0.464445
                val p2 = if (scenario == 3) 0.125507 else 0.396060
                b.set(0, 1.0, -1.0, 0.0, p1, p2)
                b.set(1, 1.0, 1.0, 0.0, p1, p2)
                b.set(2, 1.0, 0.0, 0.0, -2 * p1, -2 * p2)
                scale = env.halfWidth * 0.92f / (if (scenario == 3) 1.3f else 1.6f)
                rate = if (scenario == 3) 0.85f else 1.6f
                eta = 0.005
            }
            else -> {
                val r = env.rng
                for (i in 0..2) b.set(i, 1.0, r.range(-1f, 1f).toDouble(), r.range(-1f, 1f).toDouble(), 0.0, 0.0)
                scale = env.halfWidth * 0.92f / 2.2f
                rate = 2.2f
                eta = 0.01
            }
        }
        b.centre()
        for (i in 0..2) { sx0[i] = b.x[i]; sy0[i] = b.y[i] }
        ejected = -1; ejectedAt = -1f; loops = 0; lastNear = true
        nearGlow.fill(0f)
        elapsed = 0f; doneAt = -1f; payoffFrame = false
        tHead = 0; tCount = 0; tTick = 0f
        trailLen = env.params.i("trail", 240).coerceIn(2, TRAIL)
        for (i in 0..2) { tx[i].fill(b.x[i].toFloat() * scale); ty[i].fill(b.y[i].toFloat() * scale) }
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val a = abs(dt)
        elapsed += a
        for (i in 0..2) nearGlow[i] = max(0f, nearGlow[i] - a * 3f)
        val speed = env.params.f("speed", 1f).coerceIn(0.1f, 5f)
        b.advanceTo(b.t + a * rate * speed, eta)

        // A near miss between any two: a flash on both and a note, higher the closer.
        for (p in 0..2) {
            val i = p; val j = (p + 1) % 3
            val dx = b.x[j] - b.x[i]; val dy = b.y[j] - b.y[i]
            val r = sqrt(dx * dx + dy * dy)
            val close = r < CLOSE[scenario]
            if (close && !pairClose[p]) {
                nearGlow[i] = 1f; nearGlow[j] = 1f
                val pan = ((b.x[i] + b.x[j]).toFloat() * 0.5f * scale / env.halfWidth).coerceIn(-1f, 1f)
                env.emit(EventKind.HIT, (0.9f - (r / CLOSE[scenario]).toFloat()).coerceIn(0.1f, 0.95f), pan, 0.5f)
            }
            pairClose[p] = close
        }

        if (periodic()) {
            // A loop is every star back where it began at once.
            var far = 0.0
            for (i in 0..2) {
                val dx = b.x[i] - sx0[i]; val dy = b.y[i] - sy0[i]
                far = max(far, dx * dx + dy * dy)
            }
            val near = far < 0.0025
            if (near && !lastNear && b.t > 1.0) {
                loops++
                env.emit(EventKind.CROSS, 0.6f, 0f, 0.6f)
                if (loops == 1 && doneAt < 0f) {
                    doneAt = elapsed; payoffFrame = true
                    env.emit(EventKind.DONE, 0.9f, 0f, 1f)
                    env.callout("Back where they started!", big = true)
                }
            }
            lastNear = near
        } else if (ejected < 0) {
            val e = b.ejected(ESCAPE[scenario])
            if (e >= 0) {
                ejected = e
                ejectedAt = elapsed
                if (doneAt < 0f) {
                    doneAt = elapsed; payoffFrame = true
                    env.emit(EventKind.DONE, 0.9f, 0f, 1f)
                    env.callout("Thrown out!", big = true)
                }
            }
        }

        tTick += a
        if (tTick >= 1f / 60f) {
            tTick = 0f
            tHead = (tHead + 1) % TRAIL
            tCount = min(TRAIL, tCount + 1)
            for (i in 0..2) { tx[i][tHead] = b.x[i].toFloat() * scale; ty[i][tHead] = b.y[i].toFloat() * scale }
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        for (i in 0..2) {
            val c = colour(env, i)
            // The trail, oldest faintest.
            val n = min(tCount, trailLen - 1)
            val xl = env.halfWidth * EDGE
            for (k in 1..n) {
                val p = (tHead - k + TRAIL) % TRAIL
                val q = (tHead - k + 1 + TRAIL) % TRAIL
                // A star thrown off the screen is let go of, trail and all.
                if (abs(tx[i][p]) > xl || abs(ty[i][p]) > EDGE || abs(tx[i][q]) > xl || abs(ty[i][q]) > EDGE) continue
                val f = 1f - k / trailLen.toFloat()
                g.line(tx[i][p], ty[i][p], tx[i][q], ty[i][q], 0.006f * (0.4f + f), c, 0.75f * f)
            }
        }
        for (i in 0..2) {
            val c = colour(env, i)
            val x = b.x[i].toFloat() * scale
            val y = b.y[i].toFloat() * scale
            val r = 0.012f * cbrt(b.m[i].toFloat()) / (if (scenario == 0) cbrt(4f) else 1f) * 1.4f
            if (abs(x) > env.halfWidth * EDGE - r * 6f || abs(y) > EDGE - r * 6f) continue
            g.glow(x, y, r * 6f, c, 0.35f + 0.5f * nearGlow[i])
            g.disc(x, y, r, Palette.brighten(c, 0.5f))
            if (i == ejected) g.ring(x, y, r * 2.6f, r * 0.4f, c, 0f, 0f, 0.6f)
        }
    }

    private fun colour(env: SimEnv, i: Int): Int = env.palette.at(STAR_HUE[i])

    override fun payoff(): Boolean = payoffFrame

    private fun periodic() = scenario == 1 || scenario == 3 || scenario == 4

    /** A choreography goes on dancing; a throw-out goes round again. */
    override fun finished(): Boolean = !periodic() && doneAt >= 0f && elapsed - doneAt > 5f

    override fun counters(): List<Pair<String, String>> = buildList {
        add("time" to String.format(java.util.Locale.US, "%.1f", b.t))
        when {
            periodic() -> add("loops" to "$loops")
            else -> add("thrown out" to if (ejected < 0) "not yet" else massName(ejected))
        }
        add("closest pass" to String.format(java.util.Locale.US, "%.4f", b.closest))
    }

    private fun massName(i: Int): String = when (scenario) {
        0 -> "the ${b.m[i].toInt()}"
        else -> listOf("gold", "blue", "red")[i]
    }

    companion object {
        const val TRAIL = 600
        const val EDGE = 0.98f
        private val STAR_HUE = floatArrayOf(0.12f, 0.6f, 0.95f)
        private val CLOSE = doubleArrayOf(0.25, 0.12, 0.15, 0.12, 0.12)
        private val ESCAPE = doubleArrayOf(8.0, 99.0, 5.0, 99.0, 99.0)

        fun def(): SimDef = SimDef(
            id = "physics/three-stars",
            title = "Three stars",
            shelf = Shelf.PHYSICS,
            family = "Orbits, gravity & space",
            blurb = "Masses 3, 4 and 5 let go from a right triangle. Which one gets thrown out?",
            formats = listOf(TrapFormat.GUESS, TrapFormat.SNAP, TrapFormat.ESCAPE),
            heroLabel = "Let go again",
            defaultPalette = "Spectrum",
            typicalSeconds = 24f,
            params = listOf(
                Param.choice("start", "Start", listOf("3, 4, 5 triangle", "Figure eight", "Thrown together", "Butterfly", "Moth"), 0, card = Card.DATA, input = ParamInput.TILES),
                Param.float("kick", "Kick to the eight", 0f, 0.4f, 0f, card = Card.DATA),
                Param.int("trail", "Trail", 20, TRAIL, 240, card = Card.LOOK),
                Param.float("speed", "Speed", 0.3f, 4f, 1f, card = Card.MOTION, logScale = true)
            ),
            create = { ThreeStarsSim() }
        )
    }

    override fun contenders(env: SimEnv): IntArray =
        if (periodic()) app.hypnotica.core.sim.NO_CONTENDERS else intArrayOf(colour(env, 0), colour(env, 1), colour(env, 2))

    override fun winner(): Int = ejected
}
