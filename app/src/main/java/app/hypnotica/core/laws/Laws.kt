package app.hypnotica.core.laws

import kotlin.math.abs
import kotlin.math.pow

/**
 * How space behaves at the edges of the world. Only these two are honoured by
 * the sims, so only these two exist: a mode nothing reads is a lie in a menu.
 */
enum class Space { BOX, TORUS }

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
    /** When true, a bounce can never return more energy than it received. */
    val conserveEnergy: Boolean = true,
    /** Speed limit in sim units per second. Low values make relativity visible. */
    val lightSpeed: Float = 1e9f,
    /** +1 forward, -1 backwards. */
    val timeDirection: Float = 1f,
    /** Multiplier on simulated time. */
    val timeScale: Float = 1f,
    /** +1 = like charges repel (our universe), -1 = like charges attract. */
    val chargeSign: Float = 1f,
    /** Multiplier on the electrostatic force. 1 = as the sim intends. */
    val electricStrength: Float = 1f,
    val space: Space = Space.BOX,
    val name: String = "Our universe"
) {

    private val compiledForce: Expr? by lazy {
        forceExpr?.let { compileCached(it) }
    }

    /**
     * Magnitude of the pairwise force between two masses at distance r.
     *
     * The Gravity slider scales this as well as the downward kind. Without that
     * the one sim actually named after gravity ignored the Gravity law
     * entirely, so Zero-G, Moon and Heavy-and-bouncy did nothing to it and
     * Studio's shipped gravity-flip template exported a clip where nothing
     * flipped.
     */
    fun pairForce(r: Float, m1: Float, m2: Float): Float {
        val rr = if (r < MIN_R) MIN_R else r
        val scale = gravity / EARTH_GRAVITY
        val custom = compiledForce
        if (custom != null) {
            // One holder per thread: pairForce runs inside the parallel loop,
            // so a single shared holder would have threads overwriting each
            // other's r and m between the write and the read.
            return scale * custom.eval(vars.get().with(rr, m1, m2))
        }
        val denom = rr.toDouble().pow(falloff.toDouble()).toFloat()
        if (abs(denom) < 1e-20f) return 0f
        return scale * gravityConstant * m1 * m2 / denom
    }

    /**
     * The variable lookup a typed force law is evaluated against, created once
     * per Laws rather than once per force pair.
     *
     * A capturing lambda here was a fresh heap object on every call, and this is
     * called up to eight hundred thousand times a step inside the parallel
     * loop: a garbage collection pause is a visible stutter.
     */
    private val vars: ThreadLocal<ForceVars> = ThreadLocal.withInitial { ForceVars(this) }

    private class ForceVars(private val laws: Laws) : Expr.Vars {
        private var r = 0f
        private var m1 = 0f
        private var m2 = 0f

        fun with(r: Float, m1: Float, m2: Float): Expr.Vars {
            this.r = r
            this.m1 = m1
            this.m2 = m2
            return this
        }

        override fun value(name: String): Float = when (name) {
            "r" -> r
            "m1" -> m1
            "m2" -> m2
            "g" -> laws.gravityConstant
            else -> Float.NaN
        }
    }

    /**
     * The restitution a collision may actually use. With [conserveEnergy] on, a
     * bounce cannot hand back more than it was given, however high the slider.
     */
    val bounce: Float get() = if (conserveEnergy) restitution.coerceAtMost(1f) else restitution

    companion object {
        const val MIN_R = 0.004f

        /** What the Gravity slider reads as "our universe". */
        const val EARTH_GRAVITY = 9.81f

        // Dragging a law slider builds a new Laws every frame, and each one
        // would parse the typed force law again on its first force pair —
        // a full parse inside step(). The formula only changes when somebody
        // types, so one remembered compile is enough.
        private var cachedSource: String? = null
        private var cachedExpr: Expr? = null

        @Synchronized
        private fun compileCached(source: String): Expr? {
            if (source == cachedSource) return cachedExpr
            val compiled = Expr.compileOrNull(source)
            cachedSource = source
            cachedExpr = compiled
            return compiled
        }

        val OUR_UNIVERSE = Laws()

        val PRESETS: List<Laws> = listOf(
            OUR_UNIVERSE,
            Laws(gravity = 1.62f, name = "Moon"),
            Laws(gravity = 0f, friction = 0f, name = "Zero-G"),
            Laws(gravityDirY = -1f, name = "Upside-down"),
            Laws(restitution = 1.06f, friction = 0f, conserveEnergy = false, name = "Bouncy"),
            Laws(restitution = 0.05f, sticky = true, name = "Sticky"),
            Laws(passThrough = true, name = "Ghosts"),
            Laws(friction = 0.5f, restitution = 0.2f, name = "Syrup"),
            Laws(lightSpeed = 2.2f, name = "Slow light"),
            Laws(timeDirection = -1f, name = "Backwards time"),
            Laws(gravityConstant = -1f, name = "Repulsive gravity"),
            Laws(massSign = -1f, name = "Negative mass"),
            Laws(space = Space.TORUS, name = "Wrap-around space"),
            Laws(electricStrength = 4f, name = "Strong charges"),
            Laws(gravity = 24.5f, restitution = 0.98f, name = "Heavy & bouncy")
        )

        fun preset(name: String): Laws =
            PRESETS.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: OUR_UNIVERSE
    }
}
