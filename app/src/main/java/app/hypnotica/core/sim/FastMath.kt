package app.hypnotica.core.sim

/**
 * Cheap trig for the inner loops of huge sims.
 *
 * A flow field over a hundred thousand particles calls sine four times each,
 * every step. The library version is accurate to the last bit and far too slow
 * for that; this parabolic approximation is within about 0.001 absolute, which
 * no one can see in a moving field, and runs several times faster.
 *
 * Anything a person could check against a formula (pendulums, orbits, proofs)
 * uses kotlin.math instead.
 */
object FastMath {

    private const val PI = 3.14159265f
    private const val TWO_PI = 6.2831853f
    private const val INV_TWO_PI = 1f / TWO_PI
    private const val B = 4f / PI
    private const val C = -4f / (PI * PI)
    private const val P = 0.225f

    fun sin(xIn: Float): Float {
        // Wrap into [-pi, pi] without a modulo on the slow path.
        var x = xIn - TWO_PI * kotlin.math.floor((xIn + PI) * INV_TWO_PI)
        if (x > PI) x -= TWO_PI else if (x < -PI) x += TWO_PI
        val y = B * x + C * x * (if (x < 0f) -x else x)
        return P * (y * (if (y < 0f) -y else y) - y) + y
    }

    fun cos(x: Float): Float = sin(x + PI * 0.5f)

    /** Fast inverse square root style distance falloff used by attractors. */
    fun invSqrt(x: Float): Float {
        if (x <= 0f) return 0f
        return 1f / kotlin.math.sqrt(x)
    }
}
