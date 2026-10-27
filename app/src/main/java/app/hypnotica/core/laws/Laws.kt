package app.hypnotica.core.laws

import kotlin.math.abs
import kotlin.math.pow

/** How space wraps at the edges of the world. */
enum class Space { BOX, TORUS, MOBIUS, SPHERE, ENDLESS }

/**
 * The rules of the universe a physics sim runs in.
 *
 * Sims never hard-code gravity or bounciness; they read them from here, which is
 * what lets one Laws preset change every physics sim at once.
 */
data class Laws(
    /** Downward acceleration, m/s^2 in sim units. */
    val gravity: Float = 9.81f,
    /** Unit direction of that acceleration (y is down on screen when positive). */
    val gravityDirX: Float = 0f,
    val gravityDirY: Float = 1f,
    /** Pairwise attraction constant (n-body). */
    val gravityConstant: Float = 1f,
    /** Exponent of r in the pairwise force. 2 = inverse square. */
    val falloff: Float = 2f,
    /** Optional force law in r, m1, m2; overrides [gravityConstant] / [falloff]. */
    val forceExpr: String? = null,
    /** 1 = normal mass, -1 = negative mass. */
    val massSign: Float = 1f,
    /** Bounce energy kept per collision. Above 1 gains energy. */
    val restitution: Float = 0.88f,
    /** 0 = frictionless, 1 = syrup. */
    val friction: Float = 0.02f,
    /** Objects merge instead of bouncing. */
    val sticky: Boolean = false,
    /** Objects pass through each other. */
    val passThrough: Boolean = false,
    val conserveEnergy: Boolean = true,
    val conserveMomentum: Boolean = true,
    /** Speed limit in sim units per second. Low values make relativity visible. */
    val lightSpeed: Float = 1e9f,
    /** +1 forward, -1 backwards. */
    val timeDirection: Float = 1f,
    /** Multiplier on simulated time. */
    val timeScale: Float = 1f,
    /** +1 = like charges repel (our universe), -1 = like charges attract. */
    val chargeSign: Float = 1f,
    val electricStrength: Float = 0f,
    val space: Space = Space.BOX,
    /** Spatial dimensions, 1..6. */
    val dims: Int = 2,
    val name: String = "Our universe"
) {

    private val compiledForce: Expr? by lazy {
        forceExpr?.let { Expr.compileOrNull(it) }
    }

    /** Magnitude of the pairwise force between two masses at distance r. */
    fun pairForce(r: Float, m1: Float, m2: Float): Float {
        val rr = if (r < MIN_R) MIN_R else r
        val custom = compiledForce
        if (custom != null) {
            return custom.eval { name ->
                when (name) {
                    "r" -> rr
                    "m1" -> m1
                    "m2" -> m2
                    "g" -> gravityConstant
                    else -> Float.NaN
                }
            }
        }
        val denom = rr.toDouble().pow(falloff.toDouble()).toFloat()
        if (abs(denom) < 1e-20f) return 0f
        return gravityConstant * m1 * m2 / denom
    }

    val isReversed: Boolean get() = timeDirection < 0f

    companion object {
        const val MIN_R = 0.004f

        val OUR_UNIVERSE = Laws()

        val PRESETS: List<Laws> = listOf(
            OUR_UNIVERSE,
            Laws(gravity = 1.62f, name = "Moon"),
            Laws(gravity = 0f, friction = 0f, name = "Zero-G"),
            Laws(gravityDirY = -1f, name = "Upside-down"),
            Laws(restitution = 1.06f, friction = 0f, conserveEnergy = false, name = "Bouncy"),
            Laws(restitution = 0.05f, sticky = true, name = "Sticky"),
            Laws(friction = 0.5f, restitution = 0.2f, name = "Syrup"),
            Laws(lightSpeed = 2.2f, name = "Slow light"),
            Laws(timeDirection = -1f, name = "Backwards time"),
            Laws(gravityConstant = -1f, name = "Repulsive gravity"),
            Laws(massSign = -1f, name = "Negative mass"),
            Laws(space = Space.TORUS, name = "Wrap-around space"),
            Laws(dims = 4, name = "Four dimensions"),
            Laws(gravity = 24.5f, restitution = 0.98f, name = "Heavy & bouncy")
        )

        fun preset(name: String): Laws =
            PRESETS.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: OUR_UNIVERSE
    }
}
