package app.hypnotica.sims.physics

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Three point masses under Newton's gravity and nothing else, G = 1.
 *
 * Shared by the three-body sims. Classical fourth-order Runge–Kutta in double
 * precision with a step that shrinks during a close approach — the step is a
 * fixed fraction of the shortest pair's own free-fall time — so a near miss
 * is integrated finely and the long quiet swings cheaply. No softening: the
 * stars are points and may pass as close as they like, which is what the
 * Pythagorean problem needs to come out right (the lightest is thrown out,
 * the other two leave as a pair). Nothing here allocates once built.
 */
internal class ThreeBody {

    val m = DoubleArray(3)
    val x = DoubleArray(3)
    val y = DoubleArray(3)
    val vx = DoubleArray(3)
    val vy = DoubleArray(3)
    var t = 0.0
        private set

    /** Closest any two have come, for a sim that wants to show a near miss. */
    var closest = Double.MAX_VALUE
        private set

    private val sx = DoubleArray(3); private val sy = DoubleArray(3)
    private val svx = DoubleArray(3); private val svy = DoubleArray(3)
    private val kx = Array(4) { DoubleArray(3) }; private val ky = Array(4) { DoubleArray(3) }
    private val kvx = Array(4) { DoubleArray(3) }; private val kvy = Array(4) { DoubleArray(3) }
    private val ax = DoubleArray(3); private val ay = DoubleArray(3)

    fun set(i: Int, mass: Double, px: Double, py: Double, pvx: Double, pvy: Double) {
        m[i] = mass; x[i] = px; y[i] = py; vx[i] = pvx; vy[i] = pvy
    }

    fun reset() { t = 0.0; closest = Double.MAX_VALUE }

    /** Move to the centre-of-mass frame, so the picture does not drift away. */
    fun centre() {
        var mx = 0.0; var my = 0.0; var mvx = 0.0; var mvy = 0.0; var mt = 0.0
        for (i in 0..2) { mx += m[i] * x[i]; my += m[i] * y[i]; mvx += m[i] * vx[i]; mvy += m[i] * vy[i]; mt += m[i] }
        for (i in 0..2) { x[i] -= mx / mt; y[i] -= my / mt; vx[i] -= mvx / mt; vy[i] -= mvy / mt }
    }

    private fun accel(px: DoubleArray, py: DoubleArray) {
        ax.fill(0.0); ay.fill(0.0)
        for (i in 0..1) for (j in i + 1..2) {
            val dx = px[j] - px[i]; val dy = py[j] - py[i]
            val r2 = dx * dx + dy * dy + 1e-30
            val inv = 1.0 / (r2 * sqrt(r2))
            ax[i] += m[j] * dx * inv; ay[i] += m[j] * dy * inv
            ax[j] -= m[i] * dx * inv; ay[j] -= m[i] * dy * inv
        }
    }

    /** The step for now: a share of the tightest pair's free-fall time. */
    fun stepSize(eta: Double, hMax: Double): Double {
        var h = hMax
        for (i in 0..1) for (j in i + 1..2) {
            val dx = x[j] - x[i]; val dy = y[j] - y[i]
            val r = sqrt(dx * dx + dy * dy)
            closest = min(closest, r)
            h = min(h, eta * sqrt(r * r * r / (m[i] + m[j])))
        }
        return max(h, H_MIN)
    }

    private fun rk4(h: Double) {
        for (i in 0..2) { sx[i] = x[i]; sy[i] = y[i]; svx[i] = vx[i]; svy[i] = vy[i] }
        for (s in 0..3) {
            val f = if (s == 0) 0.0 else if (s == 3) h else h * 0.5
            val px = sx; val py = sy
            // Stage positions and velocities, written into x/y/vx/vy as scratch.
            for (i in 0..2) {
                x[i] = px[i] + (if (s == 0) 0.0 else f * kx[s - 1][i])
                y[i] = py[i] + (if (s == 0) 0.0 else f * ky[s - 1][i])
                vx[i] = svx[i] + (if (s == 0) 0.0 else f * kvx[s - 1][i])
                vy[i] = svy[i] + (if (s == 0) 0.0 else f * kvy[s - 1][i])
            }
            accel(x, y)
            for (i in 0..2) { kx[s][i] = vx[i]; ky[s][i] = vy[i]; kvx[s][i] = ax[i]; kvy[s][i] = ay[i] }
        }
        val w = h / 6.0
        for (i in 0..2) {
            x[i] = sx[i] + w * (kx[0][i] + 2 * kx[1][i] + 2 * kx[2][i] + kx[3][i])
            y[i] = sy[i] + w * (ky[0][i] + 2 * ky[1][i] + 2 * ky[2][i] + ky[3][i])
            vx[i] = svx[i] + w * (kvx[0][i] + 2 * kvx[1][i] + 2 * kvx[2][i] + kvx[3][i])
            vy[i] = svy[i] + w * (kvy[0][i] + 2 * kvy[1][i] + 2 * kvy[2][i] + kvy[3][i])
        }
        t += h
    }

    /**
     * Integrate until [target], at most [maxSteps] steps (a guard for a
     * pathological collision course). Returns the steps taken.
     */
    fun advanceTo(target: Double, eta: Double = ETA, hMax: Double = H_MAX, maxSteps: Int = 200_000): Int {
        var n = 0
        while (t < target && n < maxSteps) {
            val h = min(stepSize(eta, hMax), target - t)
            rk4(h)
            n++
        }
        return n
    }

    /**
     * Step this and [twin] together, on this one's step sizes, so the only
     * difference between them is the one they started with — not two
     * different sequences of rounding.
     */
    fun advanceWith(twin: ThreeBody, target: Double, eta: Double = ETA, hMax: Double = H_MAX, maxSteps: Int = 200_000): Int {
        var n = 0
        while (t < target && n < maxSteps) {
            val h = min(stepSize(eta, hMax), target - t)
            rk4(h)
            twin.rk4(h)
            n++
        }
        return n
    }

    /**
     * The star that has been thrown out, or -1: unbound from the other two,
     * which are bound to each other, moving away, and further than [rEsc]
     * from them.
     */
    fun ejected(rEsc: Double): Int {
        for (i in 0..2) {
            val j = (i + 1) % 3; val k = (i + 2) % 3
            val mjk = m[j] + m[k]
            // The pair on its own.
            val bdx = x[k] - x[j]; val bdy = y[k] - y[j]
            val bdvx = vx[k] - vx[j]; val bdvy = vy[k] - vy[j]
            val br = sqrt(bdx * bdx + bdy * bdy)
            val eb = 0.5 * (bdvx * bdvx + bdvy * bdvy) - mjk / br
            if (eb >= 0) continue
            val cx = (m[j] * x[j] + m[k] * x[k]) / mjk; val cy = (m[j] * y[j] + m[k] * y[k]) / mjk
            val cvx = (m[j] * vx[j] + m[k] * vx[k]) / mjk; val cvy = (m[j] * vy[j] + m[k] * vy[k]) / mjk
            val dx = x[i] - cx; val dy = y[i] - cy
            val dvx = vx[i] - cvx; val dvy = vy[i] - cvy
            val r = sqrt(dx * dx + dy * dy)
            if (r < rEsc || dx * dvx + dy * dvy <= 0) continue
            val e = 0.5 * (dvx * dvx + dvy * dvy) - (m[i] + mjk) / r
            if (e > 0) return i
        }
        return -1
    }

    fun energy(): Double {
        var e = 0.0
        for (i in 0..2) e += 0.5 * m[i] * (vx[i] * vx[i] + vy[i] * vy[i])
        for (i in 0..1) for (j in i + 1..2) {
            val dx = x[j] - x[i]; val dy = y[j] - y[i]
            e -= m[i] * m[j] / sqrt(dx * dx + dy * dy)
        }
        return e
    }

    companion object {
        const val ETA = 0.02
        const val H_MAX = 0.02
        const val H_MIN = 1e-9
    }
}
