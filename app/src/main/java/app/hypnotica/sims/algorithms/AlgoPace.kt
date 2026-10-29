package app.hypnotica.sims.algorithms

import kotlin.math.exp

/**
 * A run of [total] discrete actions laid out over [seconds], starting at
 * [startRate] actions a second and quickening exponentially to land on time.
 *
 * Searches like a Sudoku solver cost anything from fifty steps to a quarter
 * of a million depending on the puzzle and the order of its cells, so no fixed
 * rate both lets the first tries be watched one by one and finishes inside a
 * run. The new-wave algorithm sims count their steps in setup (the algorithm
 * is deterministic, so the count is exact) and play them back on this curve:
 * slow enough at the start to follow, bearing down at the end, and done when
 * the run says it will be.
 */
internal class AlgoPace(val total: Int, val seconds: Float, startRate: Float) {

    private val r0 = startRate.coerceAtLeast(0.1f)

    /** Growth constant; zero means a steady rate of total / seconds. */
    private val a: Double

    init {
        a = if (total <= r0 * seconds * 1.05f || seconds <= 0f) 0.0 else solve()
    }

    private fun solve(): Double {
        // r0 * (e^(aD) - 1) / a = total, increasing in a; bisect on a log scale.
        var lo = 1e-6
        var hi = 60.0 / seconds
        repeat(80) {
            val mid = kotlin.math.sqrt(lo * hi)
            val v = r0 * (exp(mid * seconds) - 1.0) / mid
            if (v < total) lo = mid else hi = mid
        }
        return hi
    }

    /** Actions due by [t] seconds into the run, clamped to [total]. */
    fun due(t: Float): Int {
        if (t <= 0f) return 0
        if (t >= seconds) return total
        val v = if (a == 0.0) total.toDouble() * t / seconds else r0 * (exp(a * t) - 1.0) / a
        return v.toInt().coerceIn(0, total)
    }

    /** Actions a second at [t]: steady, or r0·e^(at). */
    fun rate(t: Float): Float =
        if (a == 0.0) total / seconds.coerceAtLeast(1e-3f) else (r0 * exp(a * t.coerceIn(0f, seconds))).toFloat()

    /** When action [i] (0-based) falls due: the inverse of [due]. */
    fun timeOf(i: Int): Float {
        val n = i + 1
        if (n >= total) return seconds
        return if (a == 0.0) seconds * n / total.coerceAtLeast(1)
        else (kotlin.math.ln(1.0 + a * n / r0) / a).toFloat().coerceAtMost(seconds)
    }
}
