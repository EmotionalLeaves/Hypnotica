package app.hypnotica.sims.physics

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.LawKnob
import app.hypnotica.core.sim.Shelf
import app.hypnotica.core.sim.Sim
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.sim.SimEnv
import app.hypnotica.core.sim.TrapFormat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Metronomes on a plank resting on two cans, started out of step.
 *
 * Each swing shoves the plank a little the other way; the plank, free to roll,
 * shoves every metronome back. A metronome ahead of the crowd is held back and
 * one behind is hurried, so within a minute or so the ticking that started as
 * rain becomes one beat. Locked on a table, they never would.
 *
 * Pantaleone's model (2002): each pendulum keeps its swing going with an
 * escapement, taken as the van der Pol term μ((θ/θ₀)² − 1)θ′ that feeds in
 * energy at small swings and takes it out at large ones; the plank's
 * acceleration enters each pendulum's equation as a sideways inertial force,
 * and every pendulum's reaction drives the plank. The two are solved together
 * each step, exactly as coupled equations, not as a nudge toward the average.
 * The ring above is the same story in phases: each dot is a metronome's place
 * in its swing, and the arrow is how much they agree.
 */
class MetronomeSyncSim : Sim {

    private var n = 5
    private var th = DoubleArray(0)
    private var om = DoubleArray(0)
    private var w = DoubleArray(0)
    private var x = 0.0
    private var xv = 0.0
    private var len = 0.13
    private var hw = 0.46f

    private var elapsed = 0f
    private var lockedFor = 0f
    private var order = 0.0
    private var meanPhase = 0.0
    private var payoffFrame = false
    private var paidAt = -1f
    private var ticks = 0
    private var tempo = 176f

    // Layout.
    private var ringX = 0f
    private var ringY = 0f
    private var ringR = 0f
    private var rowX0 = 0f
    private var rowX1 = 0f
    private var tableY = 0f
    private var mScale = 1f

    private val dth = DoubleArray(MAX_N)
    private val dom = DoubleArray(MAX_N)
    private val kth = Array(4) { DoubleArray(MAX_N) }
    private val kom = Array(4) { DoubleArray(MAX_N) }
    private val sth = DoubleArray(MAX_N)
    private val som = DoubleArray(MAX_N)
    private var trailR = FloatArray(0)
    private var trailHead = 0

    override fun setup(env: SimEnv) {
        val p = env.params
        n = p.i("count", 5).coerceIn(3, MAX_N)
        tempo = p.f("tempo", 176f).coerceIn(100f, 208f)
        val spread = p.f("spread", 1f).coerceIn(0f, 3f) / 100.0
        hw = env.halfWidth
        // Each metronome is a hair off the next, as real ones are.
        w = DoubleArray(n) { 1.0 + spread * (env.rng.range(-1f, 1f)) }
        th = DoubleArray(n)
        om = DoubleArray(n)
        scatter(env)
        x = 0.0; xv = 0.0
        elapsed = 0f
        lockedFor = 0f
        payoffFrame = false
        paidAt = -1f
        ticks = 0
        trailR = FloatArray(TRAIL)
        trailHead = 0
        layout()
    }

    /** Every metronome somewhere random in its swing, at full swing. */
    private fun scatter(env: SimEnv) {
        val omega = baseOmega()
        for (i in 0 until n) {
            val ph = env.rng.range(0f, 2f * PI.toFloat()).toDouble()
            th[i] = AMP * cos(ph)
            om[i] = -AMP * omega * w[i] * sin(ph)
        }
    }

    private fun layout() {
        if (hw < 1f) {
            ringX = 0f; ringY = 0.42f; ringR = min(0.3f, hw * 0.7f)
            rowX0 = -hw * 0.9f; rowX1 = hw * 0.9f
            tableY = -0.58f
            mScale = 1f
        } else {
            ringX = -hw * 0.55f; ringY = 0.02f; ringR = 0.55f
            rowX0 = -0.05f; rowX1 = hw * 0.92f
            tableY = -0.45f
            mScale = 1.35f
        }
    }

    /** A tick at every centre crossing, so [tempo] ticks a minute is half that in swings. */
    private fun baseOmega(): Double = PI * tempo / 60.0

    private fun derivs(t: DoubleArray, o: DoubleArray, xvel: Double, outT: DoubleArray, outO: DoubleArray, env: SimEnv): Double {
        val omega = baseOmega()
        val plank = env.params.f("plank", 3f).coerceIn(0.5f, 12f).toDouble()
        val free = env.params.b("free", true)
        // Accelerations each pendulum would have with the plank held still.
        var sumA = 0.0
        var sumC2 = 0.0
        for (i in 0 until n) {
            val wi = omega * w[i]
            val q = t[i] / THETA0
            val a = -wi * wi * sin(t[i]) - MU * omega * (q * q - 1.0) * o[i]
            outO[i] = a
            val c = cos(t[i])
            sumA += a * c - o[i] * o[i] * sin(t[i])
            sumC2 += c * c
        }
        // The plank and everything on it: masses in units of one metronome's
        // swinging mass. Its acceleration and theirs are solved together.
        var xa = 0.0
        if (free) {
            val mTot = plank * n + n
            xa = (-len * sumA - PLANK_DRAG * xvel) / (mTot - sumC2)
            for (i in 0 until n) outO[i] -= xa / len * cos(t[i])
        }
        for (i in 0 until n) outT[i] = o[i]
        return xa
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt).toDouble()
        elapsed += abs(dt)
        val newTempo = env.params.f("tempo", 176f).coerceIn(100f, 208f)
        tempo = newTempo
        len = 9.81 / (baseOmega() * baseOmega())
        val sub = h / SUBSTEPS
        val free = env.params.b("free", true)
        for (s in 0 until SUBSTEPS) {
            rk4(sub, env, free)
            for (i in 0 until n) {
                // A tick as the rod crosses the centre.
                if (sth[i] * th[i] < 0.0) {
                    ticks++
                    env.emit(EventKind.HIT, 0.5f + 0.04f * i, pan(rodX(i)), 0.35f)
                }
            }
        }
        if (!free) { x = 0.0; xv = 0.0 }
        // How much they agree: the length of the average of their phases.
        val omega = baseOmega()
        var cx = 0.0; var cy = 0.0
        for (i in 0 until n) {
            val ph = phase(i, omega)
            cx += cos(ph); cy += sin(ph)
        }
        order = sqrt(cx * cx + cy * cy) / n
        meanPhase = atan2(cy, cx)
        trailR[trailHead] = order.toFloat()
        trailHead = (trailHead + 1) % TRAIL
        if (order > LOCK) lockedFor += abs(dt) else lockedFor = 0f
        if (paidAt < 0f && lockedFor > LOCK_HOLD) {
            payoffFrame = true
            paidAt = elapsed
        }
    }

    private fun phase(i: Int, omega: Double): Double = atan2(-om[i] / (omega * w[i]), th[i])

    private fun rk4(hh: Double, env: SimEnv, free: Boolean) {
        for (i in 0 until n) { sth[i] = th[i]; som[i] = om[i] }
        val x0 = x; val v0 = xv
        val a1 = derivs(th, om, xv, kth[0], kom[0], env)
        for (i in 0 until n) { dth[i] = th[i] + kth[0][i] * hh / 2; dom[i] = om[i] + kom[0][i] * hh / 2 }
        val a2 = derivs(dth, dom, v0 + a1 * hh / 2, kth[1], kom[1], env)
        for (i in 0 until n) { dth[i] = th[i] + kth[1][i] * hh / 2; dom[i] = om[i] + kom[1][i] * hh / 2 }
        val a3 = derivs(dth, dom, v0 + a2 * hh / 2, kth[2], kom[2], env)
        for (i in 0 until n) { dth[i] = th[i] + kth[2][i] * hh; dom[i] = om[i] + kom[2][i] * hh }
        val a4 = derivs(dth, dom, v0 + a3 * hh, kth[3], kom[3], env)
        for (i in 0 until n) {
            th[i] += hh / 6 * (kth[0][i] + 2 * kth[1][i] + 2 * kth[2][i] + kth[3][i])
            om[i] += hh / 6 * (kom[0][i] + 2 * kom[1][i] + 2 * kom[2][i] + kom[3][i])
        }
        if (free) {
            x = x0 + hh * (v0 + hh / 6 * (a1 + a2 + a3))
            xv = v0 + hh / 6 * (a1 + 2 * a2 + 2 * a3 + a4)
            // The cans sit on a table that slopes nowhere; a whisker of
            // centring keeps a drift of millimetres from walking it off.
            xv -= x * CENTRING * hh
        }
    }

    override fun hero(env: SimEnv) {
        scatter(env)
        lockedFor = 0f
        env.emit(EventKind.STEP, 0.4f, 0f, 0.6f)
    }

    private fun pan(xw: Float) = (xw / max(0.1f, hw)).coerceIn(-1f, 1f)

    private fun slot(i: Int): Float = rowX0 + (rowX1 - rowX0) * (i + 0.5f) / n

    private fun plankShift(): Float = (x * PLANK_DRAW).toFloat()

    private fun rodX(i: Int): Float = slot(i) + plankShift()

    private fun colour(env: SimEnv, i: Int) = env.palette.at((i + 0.5f) / n)

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        drawRing(env, g)
        val s = mScale
        val shift = plankShift()
        val span = rowX1 - rowX0
        // Table, cans, plank.
        g.rect((rowX0 + rowX1) * 0.5f, tableY - 0.03f, span * 0.5f + 0.06f, 0.03f, TABLE)
        g.rect((rowX0 + rowX1) * 0.5f, tableY - 0.001f, span * 0.5f + 0.06f, 0.0015f, TABLE_LIT)
        val canR = 0.035f * s
        // A rolling can moves half as far as the plank on top of it.
        val canShift = shift * 0.5f
        val turn = -canShift / canR
        for (c in 0..1) {
            val cx = rowX0 + span * (0.22f + 0.56f * c) + canShift
            val cy = tableY + canR
            g.disc(cx, cy, canR, CAN)
            g.ring(cx, cy, canR, 0.004f, CAN_LIT)
            g.line(cx, cy, cx + cos(turn) * canR * 0.8f, cy + sin(turn) * canR * 0.8f, 0.004f, CAN_LIT)
        }
        val plankY = tableY + 2f * canR + 0.012f
        g.rect((rowX0 + rowX1) * 0.5f + shift, plankY, span * 0.5f + 0.03f, 0.012f, PLANK)
        g.rect((rowX0 + rowX1) * 0.5f + shift, plankY + 0.0105f, span * 0.5f + 0.03f, 0.0015f, PLANK_LIT)
        // The metronomes.
        val pitch = span / n
        val bodyW = min(0.055f * s, pitch * 0.36f)
        val bodyH = min(0.24f * s, bodyW * 4.6f)
        val base = plankY + 0.012f
        val locked = paidAt >= 0f
        for (i in 0 until n) {
            val cx = slot(i) + shift
            val c = colour(env, i)
            // Body: a tall block narrowing to a rounded crown.
            g.rect(cx, base + bodyH * 0.45f, bodyW, bodyH * 0.45f, BODY)
            g.rect(cx, base + bodyH * 0.9f, bodyW * 0.8f, bodyH * 0.06f, BODY)
            g.disc(cx, base + bodyH * 0.95f, bodyW * 0.8f, BODY)
            g.rect(cx, base + bodyH * 0.5f, bodyW * 0.62f, bodyH * 0.36f, FACE)
            // The scale behind the rod: a faint arc of the swing.
            val pivotY = base + bodyH * 0.16f
            val rodL = bodyH * 0.98f
            val sweep = (AMP * 2).toFloat()
            g.ring(cx, pivotY, rodL * 0.72f, 0.003f, Palette.lerp(c, Palette.BG, 0.6f), gap = 2f * PI.toFloat() - sweep, gapAngle = (PI / 2).toFloat() - sweep / 2 + sweep)
            // The rod and its sliding weight: tipped by θ, pivot near the foot.
            val a = th[i].toFloat()
            val tx = cx - sin(a) * rodL; val ty = pivotY + cos(a) * rodL
            g.line(cx, pivotY, tx, ty, 0.0055f * s, Palette.lerp(c, Palette.TEXT_PRIMARY, 0.25f))
            val wx = cx - sin(a) * rodL * 0.7f; val wy = pivotY + cos(a) * rodL * 0.7f
            g.rect(wx, wy, bodyW * 0.42f, bodyW * 0.26f, c, -a)
            if (locked) g.glow(tx, ty, bodyW * 1.6f, c, 0.25f * ((elapsed - paidAt) / 0.6f).coerceIn(0f, 1f))
            g.disc(cx, pivotY, 0.007f * s, Palette.TEXT_SECONDARY)
        }
    }

    private fun drawRing(env: SimEnv, g: G) {
        val omega = baseOmega()
        // The track every phase dot runs round, and the agreement arrow.
        g.ring(ringX, ringY, ringR, 0.004f, RING)
        g.ring(ringX, ringY, ringR * LOCK.toFloat(), 0.002f, RING, alpha = 0.6f)
        for (i in 0 until n) {
            val ph = phase(i, omega).toFloat()
            val c = colour(env, i)
            val px = ringX + cos(ph) * ringR; val py = ringY + sin(ph) * ringR
            g.glow(px, py, 0.05f, c, 0.35f)
            g.disc(px, py, 0.017f, c)
        }
        val r = order.toFloat()
        val mp = meanPhase.toFloat()
        val ex = ringX + cos(mp) * ringR * r; val ey = ringY + sin(mp) * ringR * r
        val arrow = if (paidAt >= 0f) Palette.TEXT_PRIMARY else Palette.TEXT_SECONDARY
        g.line(ringX, ringY, ex, ey, 0.006f, arrow)
        g.disc(ex, ey, 0.01f, arrow)
        g.disc(ringX, ringY, 0.006f, arrow)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = paidAt >= 0f && elapsed - paidAt > 6f

    override fun counters(): List<Pair<String, String>> = listOf(
        "in step" to String.format(java.util.Locale.US, "%.0f%%", order * 100),
        "tempo" to String.format(java.util.Locale.US, "%.0f bpm", tempo),
        "state" to if (paidAt >= 0f) "locked" else "drifting"
    )

    companion object {
        const val MAX_N = 9
        @JvmField var AMP = 0.6
        /** van der Pol settles at about twice θ₀. */
        val THETA0 get() = AMP / 2
        @JvmField var MU = 0.012
        const val PLANK_DRAG = 0.0
        const val CENTRING = 0.2
        const val SUBSTEPS = 2
        const val LOCK = 0.97
        const val LOCK_HOLD = 2f
        const val TRAIL = 240
        /** World units per metre of plank travel: metronomes are drawn about 1.5× life size. */
        const val PLANK_DRAW = 1.5
        const val TABLE = 0xFF1A1D25.toInt()
        const val TABLE_LIT = 0xFF3A404D.toInt()
        const val CAN = 0xFF2A2F3A.toInt()
        const val CAN_LIT = 0xFF6A7180.toInt()
        const val PLANK = 0xFF4A3F36.toInt()
        const val PLANK_LIT = 0xFF7A6A5C.toInt()
        const val BODY = 0xFF262A33.toInt()
        const val FACE = 0xFF151820.toInt()
        const val RING = 0xFF2E3440.toInt()

        fun def(): SimDef = SimDef(
            id = "physics/metronome-sync",
            title = "Metronomes in step",
            shelf = Shelf.PHYSICS,
            family = "Sync",
            blurb = "Five metronomes, out of step, on a plank that can roll. Listen to the rain turn into one beat.",
            formats = listOf(TrapFormat.SNAP, TrapFormat.SONG),
            lawKnobs = emptySet(),
            heroLabel = "Knock them out of step",
            heroRestarts = false,
            defaultPalette = "Aurora",
            typicalSeconds = 20f,
            params = listOf(
                Param.int("count", "Metronomes", 3, MAX_N, 5, card = Card.DATA),
                Param.float("spread", "Mismatch", 0f, 3f, 1f, card = Card.DATA, unit = "%"),
                Param.float("plank", "Plank weight", 0.5f, 12f, 3f, card = Card.MOTION),
                Param.float("tempo", "Tempo", 100f, 208f, 176f, card = Card.MOTION, unit = "bpm"),
                Param.bool("free", "Plank can roll", true, card = Card.MOTION)
            ),
            create = { MetronomeSyncSim() }
        )
    }
}
