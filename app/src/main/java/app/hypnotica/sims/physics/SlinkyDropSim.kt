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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A slinky hanging from a hand, let go.
 *
 * The bottom does not move. The top crashes down onto the coils below it and a
 * front of collapsing coils runs down the spring, but until that front arrives
 * the bottom is held up by exactly the tension it always had — nothing below
 * the front has been told yet. When it gets there, the whole stack falls as one.
 *
 * Modelled as it is built: N equal coils joined by springs that pull but cannot
 * push (a slinky's coils can only close until they touch), the touching coils
 * meeting perfectly inelastically. Units are metres and seconds; the screen is
 * two metres tall, and the run is filmed with a high-speed camera, so the
 * clock on screen is the real one.
 */
class SlinkyDropSim : Sim {

    private var n = 50
    private var y = DoubleArray(0)
    private var v = DoubleArray(0)
    private var y0 = DoubleArray(0)
    private var d0 = 0.0012
    private var kSeg = 1.0
    private var mass = 0.0
    private var grav = 9.81
    private var topY = 0.82
    private var floorY = -0.93
    private var radius = 0.08f
    private var slow = 6f
    private var holdFor = 2f

    private var elapsed = 0f
    /** Real (unslowed) seconds since the hand let go; negative before. */
    private var real = -1.0
    private var released = false
    private var moved = false
    private var payoffFrame = false
    private var hangTime = 0.0
    private var theory = 0.0
    private var front = 0
    private var landedAt = -1f
    private var landed = 0
    private var ball = true
    private var ballY = 0.0
    private var ballV = 0.0
    private var ballLanded = false
    private var hang = 1.2
    private val scratchW = DoubleArray(MAX_COILS)
    private val scratchV = DoubleArray(MAX_COILS)
    private val scratchLen = IntArray(MAX_COILS)

    override fun setup(env: SimEnv) {
        val p = env.params
        n = p.i("coils", 70).coerceIn(MIN_COILS, MAX_COILS)
        slow = p.f("slow", 6f).coerceIn(1f, 10f)
        ball = p.b("ball", true)
        grav = max(0.0, env.laws.gravity.toDouble())
        // The hanging length is the slinky's own weight over its stiffness:
        // the average tension is half the weight, so it stretches Mg/2K.
        // The control is that length at Earth gravity, so softer means longer
        // and the frame always fits; another gravity stretches it honestly.
        hang = p.f("stretch", 1.2f).coerceIn(0.6f, 1.45f).toDouble()
        mass = TOTAL_MASS / n
        val kTotal = TOTAL_MASS * EARTH_G / (2.0 * hang)
        // n − 1 springs in series, each n − 1 times as stiff as the whole.
        kSeg = kTotal * (n - 1)
        d0 = COLLAPSED / n
        // The top stack's momentum grows at exactly Mg, and it sits at the
        // hanging position of the coil the front has reached, so
        // (M/K)(ξ²/2 − ξ³/3) = t²/2 for the front at mass fraction ξ: it
        // reaches the bottom at √(M/3K). Gravity cancels out — it stretches
        // the spring and speeds the fall in exactly equal measure.
        theory = sqrt(TOTAL_MASS / (3.0 * kTotal))
        radius = min(0.085f, env.halfWidth * 0.2f)
        y = DoubleArray(n); v = DoubleArray(n); y0 = DoubleArray(n)
        y[0] = topY
        for (j in 0 until n - 1) {
            // The spring under coil j carries every coil below it.
            val t = (n - 1 - j) * mass * grav
            y[j + 1] = y[j] - d0 - t / kSeg
        }
        for (i in 0 until n) y0[i] = y[i]
        elapsed = 0f
        real = -1.0
        released = false
        moved = false
        payoffFrame = false
        hangTime = 0.0
        front = 0
        landedAt = -1f
        landed = 0
        ballY = y[n - 1]
        ballV = 0.0
        ballLanded = false
        holdFor = HOLD
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        grav = max(0.0, env.laws.gravity.toDouble())
        slow = env.params.f("slow", 6f).coerceIn(1f, 10f)
        if (!released) {
            if (elapsed >= holdFor) {
                released = true
                real = 0.0
                env.emit(EventKind.STEP, 0.75f, 0f, 0.7f)
            } else return
        }
        val realDt = (h / slow).toDouble()
        val subs = SUBSTEPS
        val sdt = realDt / subs
        val frontBefore = front
        repeat(subs) { substep(sdt) }
        real += realDt

        if (ball && !ballLanded) {
            ballV -= grav * realDt
            ballY += ballV * realDt
            if (ballY <= floorY + BALL_R) {
                ballY = floorY + BALL_R
                ballLanded = true
                env.emit(EventKind.HIT, 0.35f, panOf(env, min(BALL_X * env.halfWidth, 0.32f)), 0.55f)
            }
        }

        // The collapse front: the deepest coil now riding in the top stack.
        var f = 0
        while (f < n - 1 && y[f] - y[f + 1] <= d0 * 1.05) f++
        if (f > front) front = f
        if (front > frontBefore && !moved) {
            // A click as each few coils slap into the stack, rising as the
            // front runs down; every coil would be a buzz.
            val every = max(1, n / 16)
            if (front / every != frontBefore / every) {
                env.emit(EventKind.HIT, 0.25f + 0.65f * front / n, 0f, 0.3f + 0.3f * front / n)
            }
        }

        if (!moved && abs(v[n - 1]) > MOVE_SPEED) {
            moved = true
            payoffFrame = true
            hangTime = real
        }

        // The stack hitting the floor: one thud for the first coil, a softer
        // patter as the rest pile on.
        if (moved && landedAt < 0f) {
            var down = 0
            for (i in 0 until n) if (y[i] <= floorY + (n - 1 - i) * d0 + d0 * 0.6) down++
            if (down > 0 && landed == 0) env.emit(EventKind.HIT, 0.2f, 0f, 0.9f)
            if (down >= n) {
                landedAt = elapsed
                env.emit(EventKind.HIT, 0.5f, 0f, 0.5f)
            }
            landed = down
        }
    }

    private fun substep(dt: Double) {
        val last = n - 1
        // Tension-only springs: a gap wider than the coil's own thickness pulls.
        for (i in 0 until n) scratchW[i] = -grav
        for (j in 0 until last) {
            val stretch = y[j] - y[j + 1] - d0
            if (stretch > 0) {
                val a = kSeg * stretch / mass
                scratchW[j] -= a
                scratchW[j + 1] += a
            }
        }
        for (i in 0 until n) v[i] += scratchW[i] * dt
        // Coils that would close past touching this substep meet inelastically:
        // within each run of touching coils the velocities may only separate,
        // and the nearest such velocities to the free ones are the
        // momentum-conserving inelastic answer (pool-adjacent-violators).
        var i = 0
        while (i < n) {
            var j = i
            while (j < last && y[j] - y[j + 1] + (v[j] - v[j + 1]) * dt <= d0) j++
            if (j > i) isotonic(i, j)
            i = j + 1
        }
        for (k in 0 until n) y[k] += v[k] * dt
        // The floor stops whatever reaches it, and the coils stack on it.
        if (y[last] < floorY) {
            for (k in last downTo 0) {
                val lim = floorY + (last - k) * d0
                if (y[k] < lim) { y[k] = lim; if (v[k] < 0) v[k] = 0.0 }
            }
        }
    }

    /** Make v[a..b] non-increasing downwards with the least change: PAV. */
    private fun isotonic(a: Int, b: Int) {
        var blocks = 0
        for (k in a..b) {
            scratchV[blocks] = v[k]
            scratchLen[blocks] = 1
            blocks++
            // Upper coil moving down faster than the one below: they merge.
            while (blocks > 1 && scratchV[blocks - 2] < scratchV[blocks - 1]) {
                val l1 = scratchLen[blocks - 2]; val l2 = scratchLen[blocks - 1]
                scratchV[blocks - 2] = (scratchV[blocks - 2] * l1 + scratchV[blocks - 1] * l2) / (l1 + l2)
                scratchLen[blocks - 2] = l1 + l2
                blocks--
            }
        }
        var k = a
        for (bI in 0 until blocks) repeat(scratchLen[bI]) { v[k++] = scratchV[bI] }
    }

    private fun panOf(env: SimEnv, x: Float) = (x / max(0.1f, env.halfWidth)).coerceIn(-1f, 1f)

    override fun hero(env: SimEnv) {}

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        val hw = env.halfWidth
        val r = radius
        val lw = 0.0065f
        // The floor.
        g.rect(0f, floorY.toFloat() - 0.04f, hw * 1.2f, 0.04f, FLOOR)
        g.rect(0f, floorY.toFloat() - 0.002f, hw * 1.2f, 0.002f, FLOOR_LIT)
        // Where the bottom coil hung: the line it has not yet left.
        val mark = y0[n - 1].toFloat()
        val markA = if (moved) max(0.15f, 0.9f - (real - hangTime).toFloat() * 6f) else 0.55f
        var dx = -hw * 0.95f
        while (dx < hw * 0.95f) {
            g.rect(dx + 0.012f, mark, 0.012f, 0.0018f, Palette.TEXT_SECONDARY, alpha = markA)
            dx += 0.04f
        }
        // A clamp on a rod from the ceiling: two jaws gripping the top coil,
        // swinging apart the instant it lets go.
        val open = if (released) min(1f, (real / 0.03).toFloat()) else 0f
        val ty = topY.toFloat()
        g.rect(0f, 0.97f, hw * 1.2f, 0.03f, FLOOR)
        g.rect(0f, (0.94f + ty + 0.07f) * 0.5f, 0.007f, (0.94f - ty - 0.07f) * 0.5f, HAND)
        g.rect(0f, ty + 0.07f, r + 0.03f, 0.008f, HAND)
        for (side in intArrayOf(-1, 1)) {
            val jx = side * (r + 0.018f)
            val tilt = side * open * 0.5f
            val cx = jx + side * open * 0.012f
            g.rect(cx, ty + 0.035f, 0.007f, 0.035f, HAND, tilt)
            g.rect(cx - side * 0.006f, ty + 0.004f, 0.006f, 0.004f, HAND_LIT, tilt)
        }
        // The coils as a helix seen side-on: the back half of each turn dim,
        // drawn first, the front half bright over it.
        for (pass in 0..1) {
            for (i in 0 until n) {
                val ya = y[i].toFloat()
                val yb = if (i < n - 1) y[i + 1].toFloat() else ya - d0.toFloat()
                val mid = (ya + yb) * 0.5f
                val c = pal.at(i / (n - 1f) * 0.85f)
                if (pass == 0) {
                    if (i < n - 1) g.line(r, mid, -r, yb, lw * 0.8f, Palette.lerp(c, Palette.BG, 0.62f))
                } else {
                    g.line(-r, ya, r, mid, lw, c)
                }
            }
        }
        // The bottom coil: the one everyone is watching.
        val by = y[n - 1].toFloat()
        val bc = pal.at(0.85f)
        // Steady: the user finds throbbing highlights disorienting.
        val lit = if (moved) 1f else 0.6f
        g.glow(0f, by, r * 1.6f, bc, 0.35f * lit)
        g.line(-r, by, r, by - d0.toFloat() * 0.5f, lw * 1.6f, Palette.brighten(bc, 0.25f))
        // The ball let go beside it at the same instant.
        if (ball) {
            val bx = min(BALL_X * hw, 0.32f)
            g.disc(bx, ballY.toFloat(), BALL_R, BALL)
            g.disc(bx - BALL_R * 0.3f, ballY.toFloat() + BALL_R * 0.3f, BALL_R * 0.35f, BALL_LIT)
        }
        if (moved) {
            // A brief ring where the bottom finally let go.
            // Viewed seconds since it let go: the ring lasts 0.6 s on screen.
            val t = ((real - hangTime) * slow / 0.6).toFloat()
            if (t < 1f) g.ring(0f, mark, r * (1.2f + 1.5f * t), 0.006f, Palette.TEXT_PRIMARY, alpha = 0.8f * (1f - t))
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = landedAt >= 0f && elapsed - landedAt > 2.2f

    override fun counters(): List<Pair<String, String>> {
        val l = java.util.Locale.US
        val clock = if (released) String.format(l, "%.0f ms", real * 1000) else "holding"
        return if (moved) listOf(
            "bottom hung still" to String.format(l, "%.0f ms", hangTime * 1000),
            "theory √(M/3K)" to String.format(l, "%.0f ms", theory * 1000),
            "camera" to String.format(l, "%.0f× slow", slow)
        ) else listOf(
            "since release" to clock,
            "bottom coil" to "still",
            "camera" to String.format(l, "%.0f× slow", slow)
        )
    }

    companion object {
        const val MIN_COILS = 20
        const val MAX_COILS = 120
        const val TOTAL_MASS = 0.25
        const val EARTH_G = 9.81
        /** Height of the slinky closed up, metres. */
        const val COLLAPSED = 0.06
        const val SUBSTEPS = 40
        const val HOLD = 1.5f
        const val MOVE_SPEED = 0.03
        const val BALL_R = 0.022f
        const val BALL_X = 0.62f
        const val FLOOR = 0xFF1A1D25.toInt()
        const val FLOOR_LIT = 0xFF3A404D.toInt()
        const val HAND = 0xFF5C626E.toInt()
        const val HAND_LIT = 0xFF8A919E.toInt()
        const val BALL = 0xFFB9BEC7.toInt()
        const val BALL_LIT = 0xFFF2F4F8.toInt()

        fun def(): SimDef = SimDef(
            id = "physics/slinky-drop",
            title = "Slinky drop",
            shelf = Shelf.PHYSICS,
            family = "Waves",
            blurb = "Let go of a hanging slinky. The bottom stays exactly where it is… until the news arrives.",
            formats = listOf(TrapFormat.SNAP, TrapFormat.GUESS),
            // Gravity stretches it and pulls it down; the hang time, oddly,
            // does not depend on it.
            lawKnobs = setOf(LawKnob.GRAVITY),
            heroLabel = "Hang it again",
            defaultPalette = "Spectrum",
            typicalSeconds = 4.5f,
            params = listOf(
                Param.int("coils", "Coils", MIN_COILS, MAX_COILS, 70, card = Card.DATA),
                Param.float("stretch", "Hanging length", 0.6f, 1.45f, 1.2f, card = Card.DATA, unit = "m"),
                Param.float("slow", "Slow motion", 1f, 10f, 6f, card = Card.MOTION, unit = "×"),
                Param.bool("ball", "Drop a ball beside it", true, card = Card.LOOK)
            ),
            create = { SlinkyDropSim() }
        )
    }
}
