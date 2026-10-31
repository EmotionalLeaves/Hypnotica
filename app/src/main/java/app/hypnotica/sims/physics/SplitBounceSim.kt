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
import app.hypnotica.core.sim.TouchEvent
import app.hypnotica.core.sim.TrapFormat
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One ball in a ring. Every time a ball hits the wall there is a chance it
 * splits in two, the halves flying apart at a slight angle. One becomes two,
 * two become four, and the count climbs slowly, then not slowly at all, until
 * the ring holds a thousand.
 *
 * Exponential growth you can watch: the first split takes a while, the
 * hundredth ball comes in a rush, and the last few hundred arrive in about a
 * second. Each ball's colour is how many splits back its line goes.
 */
class SplitBounceSim : Sim {

    private val x = FloatArray(MAX_BALLS)
    private val y = FloatArray(MAX_BALLS)
    private val vx = FloatArray(MAX_BALLS)
    private val vy = FloatArray(MAX_BALLS)
    private val gen = IntArray(MAX_BALLS)
    private val born = FloatArray(MAX_BALLS)
    private var n = 0
    private var target = 1000
    private var ringR = 0.45f
    private var ballR = 0.012f
    private var chance = 0.45f
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var mark = 0
    private var hits = 0L
    private var noteBudget = 0f
    private var deepest = 0
    private val flashAng = FloatArray(FLASHES)
    private val flashLife = FloatArray(FLASHES)
    private val flashGen = IntArray(FLASHES)
    private var flashHead = 0

    override fun setup(env: SimEnv) {
        ringR = min(env.halfWidth * 0.94f, 0.9f)
        target = env.params.i("target", 1000).coerceIn(10, MAX_BALLS)
        chance = env.params.f("chance", 0.45f).coerceIn(0.05f, 1f)
        ballR = ringR * BALL_SHARE
        n = 1
        val a = env.rng.range(0f, 6.2831855f)
        x[0] = 0f; y[0] = ringR * 0.3f
        vx[0] = cos(a) * SPEED * ringR; vy[0] = sin(a) * SPEED * ringR
        gen[0] = 0
        born.fill(-10f)
        elapsed = 0f; doneAt = -1f; payoffFrame = false; mark = 0; hits = 0L; noteBudget = 0f; deepest = 0
        flashLife.fill(0f); flashHead = 0
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h0 = abs(dt) * env.params.f("speed", 1f).coerceIn(0.2f, 3f)
        elapsed += h0
        noteBudget = min(1.5f, noteBudget + abs(dt) * NOTES_PER_SECOND)
        for (k in 0 until FLASHES) flashLife[k] = max(0f, flashLife[k] - abs(dt) * 2.5f)
        val grav = env.params.f("gravity", 0.4f).coerceIn(0f, 3f) * ringR
        val sub = SUBSTEPS
        val h = h0 / sub
        val room = ringR - ballR
        repeat(sub) {
            val count = n
            for (i in 0 until count) {
                vy[i] -= grav * h
                x[i] += vx[i] * h; y[i] += vy[i] * h
                val d2 = x[i] * x[i] + y[i] * y[i]
                if (d2 < room * room) continue
                val d = sqrt(d2)
                val nx = x[i] / d; val ny = y[i] / d
                val vn = vx[i] * nx + vy[i] * ny
                x[i] = nx * room; y[i] = ny * room
                if (vn <= 0f) continue
                vx[i] -= 2f * vn * nx; vy[i] -= 2f * vn * ny
                // Gravity drains a little speed from the low bounces; top it
                // back up, and never leave a ball skimming the wall: a grazer
                // rolls round the floor hitting it every step, and with a
                // split chance on every hit that was a runaway clump.
                val sp = sqrt(vx[i] * vx[i] + vy[i] * vy[i])
                val want = SPEED * ringR
                if (sp < want * 0.8f && sp > 1e-5f) { vx[i] *= want * 0.8f / sp; vy[i] *= want * 0.8f / sp }
                inward(i, nx, ny)
                hits++
                wall(env, i, nx, ny)
            }
        }

        while (mark < MARKS.size && n >= MARKS[mark]) {
            if (MARKS[mark] < target && (mark == MARKS.size - 1 || n < MARKS[mark + 1])) {
                env.callout(String.format(java.util.Locale.US, "%,d!", MARKS[mark]))
            }
            mark++
        }
        if (doneAt < 0f && n >= target) {
            doneAt = elapsed
            payoffFrame = true
            env.callout(String.format(java.util.Locale.US, "%,d!!", n), big = true)
        }
    }

    /** Turns a ball leaving the wall at a shallow angle a little more inwards, keeping its speed. */
    private fun inward(i: Int, nx: Float, ny: Float) {
        val sp = sqrt(vx[i] * vx[i] + vy[i] * vy[i])
        if (sp < 1e-6f) return
        val vin = -(vx[i] * nx + vy[i] * ny)
        val need = MIN_INWARD * sp
        if (vin >= need) return
        vx[i] -= (need - vin) * nx; vy[i] -= (need - vin) * ny
        val s2 = sqrt(vx[i] * vx[i] + vy[i] * vy[i])
        vx[i] *= sp / s2; vy[i] *= sp / s2
    }

    private fun wall(env: SimEnv, i: Int, nx: Float, ny: Float) {
        // The very first bounce always splits, so the run is under way at once.
        // A ball just split cannot split again until it has crossed the ring.
        val ready = elapsed - born[i] > COOLDOWN
        val split = doneAt < 0f && n < target && ready && (hits == 1L || env.rng.nextFloat() < chance)
        if (noteBudget >= 1f) {
            noteBudget -= 1f
            val ang = (atan2(ny, nx) / 6.2831855f + 0.5f)
            env.emit(if (split) EventKind.BIRTH else EventKind.HIT, ang.coerceIn(0f, 1f), nx.coerceIn(-1f, 1f), if (split) 0.4f else 0.18f)
        }
        if (!split) return
        // The two halves leave at a few degrees either side of the bounce.
        val j = n++
        val tilt = SPLIT_ANGLE * (0.5f + env.rng.nextFloat())
        val c = cos(tilt); val s = sin(tilt)
        val ox = vx[i]; val oy = vy[i]
        vx[i] = ox * c - oy * s; vy[i] = ox * s + oy * c
        vx[j] = ox * c + oy * s; vy[j] = -ox * s + oy * c
        x[j] = x[i]; y[j] = y[i]
        inward(i, nx, ny); inward(j, nx, ny)
        born[i] = elapsed; born[j] = elapsed
        gen[i]++; gen[j] = gen[i]
        if (gen[i] > deepest) deepest = gen[i]
        flashAng[flashHead] = atan2(ny, nx); flashLife[flashHead] = 1f; flashGen[flashHead] = gen[i]
        flashHead = (flashHead + 1) % FLASHES
    }

    override fun onInput(env: SimEnv, e: TouchEvent) {
        if (e.kind != TouchEvent.Kind.TAP || doneAt >= 0f || n >= target) return
        // A tap adds a ball where the finger is, sent off away from the centre.
        val d = sqrt(e.x * e.x + e.y * e.y)
        if (d > ringR - ballR) return
        val j = n++
        val a = if (d > 1e-3f) atan2(e.y, e.x) else 0f
        x[j] = e.x; y[j] = e.y
        vx[j] = cos(a) * SPEED * ringR; vy[j] = sin(a) * SPEED * ringR
        gen[j] = 0
        born[j] = elapsed
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        g.reserveInstances(n + FLASHES + 4)
        g.ring(0f, 0f, ringR, 0.008f, Palette.withAlpha(Palette.TEXT_PRIMARY, 0.75f))
        for (k in 0 until FLASHES) {
            val life = flashLife[k]
            if (life <= 0f) continue
            val col = pal.at((flashGen[k] % GEN_COLOURS) / GEN_COLOURS.toFloat())
            g.glow(ringR * cos(flashAng[k]), ringR * sin(flashAng[k]), 0.05f, col, 0.6f * life)
        }
        // Smaller as the ring fills, so a thousand still read as balls.
        val r = ballR * (0.55f + 0.45f / sqrt(max(1f, n / 60f)))
        for (i in 0 until n) {
            val col = pal.at((gen[i] % GEN_COLOURS) / GEN_COLOURS.toFloat())
            g.disc(x[i], y[i], max(r, ringR * 0.006f), col)
        }
        if (doneAt >= 0f) {
            val t = ((elapsed - doneAt) / 2f).coerceIn(0f, 1f)
            g.glow(0f, 0f, ringR * 1.1f, pal.at(0.5f), (1f - t) * 0.4f)
        }
    }

    override fun frameHalfWidth(env: SimEnv): Float = ringR + 0.01f

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && elapsed - doneAt > 4f

    /** The fastest to the target: fewer seconds is better. */
    override fun scoreForBest(): Float = if (doneAt >= 0f) -doneAt else Float.NaN
    override fun scoreForBestText(value: Float): String = String.format(java.util.Locale.US, "%,d in %.1f s", target, -value)

    override fun counters(): List<Pair<String, String>> = listOf(
        "balls" to String.format(java.util.Locale.US, "%,d", n),
        "splits deep" to deepest.toString(),
        "wall hits" to String.format(java.util.Locale.US, "%,d", hits)
    )

    companion object {
        const val MAX_BALLS = 2000
        const val FLASHES = 24
        const val GEN_COLOURS = 12
        const val BALL_SHARE = 0.03f
        /** Speed in ring radii a second. */
        const val SPEED = 1.9f
        const val SPLIT_ANGLE = 0.12f
        /** A ball leaves the wall at least this steeply: this share of its speed inwards. */
        const val MIN_INWARD = 0.35f
        /** Seconds after a split before either half may split again. */
        const val COOLDOWN = 0.3f
        const val SUBSTEPS = 3
        const val NOTES_PER_SECOND = 18f
        val MARKS = intArrayOf(10, 100, 250, 500)

        fun def(): SimDef = SimDef(
            id = "physics/split-bounce",
            title = "1 to 1,000",
            shelf = Shelf.PHYSICS,
            family = "Bouncing & collisions",
            blurb = "Every bounce might split a ball in two. One becomes a thousand — slowly, then all at once.",
            formats = listOf(TrapFormat.NUMBER_UP, TrapFormat.FILL),
            heroLabel = "Start over",
            defaultPalette = "Spectrum",
            typicalSeconds = 15f,
            params = listOf(
                Param.int("target", "Stop at", 10, MAX_BALLS, 1000, card = Card.DATA, logScale = true),
                Param.float("chance", "Split chance", 0.05f, 1f, 0.45f, card = Card.DATA),
                Param.float("speed", "Speed", 0.2f, 3f, 1f, card = Card.MOTION, logScale = true),
                Param.float("gravity", "Gravity", 0f, 3f, 0.4f, card = Card.MOTION)
            ),
            create = { SplitBounceSim() }
        )
    }
}
