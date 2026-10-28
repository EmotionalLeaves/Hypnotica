package app.hypnotica.core.sim

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * PCG32 pseudo random generator.
 *
 * Deterministic and portable: the same seed produces the same stream on every
 * device, which is what makes a replay link or an offline render reproduce a run
 * exactly. Never use kotlin.random inside a sim.
 */
class Rng(seed: Long = 1L) {

    private var state: Long = 0L
    private var inc: Long = 0L

    init {
        reseed(seed)
    }

    fun reseed(seed: Long) {
        state = 0L
        inc = (seed shl 1) or 1L
        nextInt()
        state += seed
        nextInt()
    }

    /** Raw 32 bits. */
    fun nextInt(): Int {
        val old = state
        state = old * 6364136223846793005L + inc
        val xorshifted = (((old ushr 18) xor old) ushr 27).toInt()
        val rot = (old ushr 59).toInt()
        return (xorshifted ushr rot) or (xorshifted shl ((-rot) and 31))
    }

    /** Uniform in [0, 1). */
    fun nextFloat(): Float =
        // Clamped: the largest ints round up to exactly 1 as a float, and
        // [0, 1) is the promise every range() relies on.
        (((nextInt().toLong() and 0xFFFFFFFFL).toFloat()) / 4294967296f).coerceAtMost(0.99999994f)

    /** Uniform in [a, b). */
    fun range(a: Float, b: Float): Float = a + (b - a) * nextFloat()

    /** Uniform integer in [a, b). */
    fun int(a: Int, b: Int): Int {
        if (b <= a) return a
        val span = (b - a).toLong()
        val v = (nextInt().toLong() and 0xFFFFFFFFL) % span
        return (a + v).toInt()
    }

    fun bool(pTrue: Float = 0.5f): Boolean = nextFloat() < pTrue

    /** Standard normal, Box-Muller. */
    fun normal(): Float {
        var u = nextFloat()
        if (u < 1e-7f) u = 1e-7f
        val v = nextFloat()
        return (sqrt(-2.0 * ln(u.toDouble())) * cos(2.0 * Math.PI * v)).toFloat()
    }

    /** Fisher-Yates, using this stream so shuffles replay identically. */
    fun shuffle(a: FloatArray) {
        for (i in a.size - 1 downTo 1) {
            val j = int(0, i + 1)
            val t = a[i]; a[i] = a[j]; a[j] = t
        }
    }

    fun shuffle(a: IntArray) {
        for (i in a.size - 1 downTo 1) {
            val j = int(0, i + 1)
            val t = a[i]; a[i] = a[j]; a[j] = t
        }
    }
}
