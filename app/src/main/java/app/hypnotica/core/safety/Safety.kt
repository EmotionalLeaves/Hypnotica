package app.hypnotica.core.safety

import kotlin.math.max

/**
 * The engine enforces the safety limits itself rather than trusting each sim.
 *
 * Fast, bright, loud content is the point of the app, so these caps are not
 * optional: a Recipe cannot opt out of them, it can only ask for less.
 */
class FlashLimiter {

    /** Large flashes allowed per second. WCAG's general flash threshold is 3. */
    var maxPerSecond: Float = 3f

    /** When on, every flash is softened and the cap is tightened. */
    var reduceFlashing: Boolean = false

    private var budget: Float = 3f
    private var lastValue: Float = 0f

    /**
     * Returns the flash strength that may actually be drawn this frame.
     * Strengths under [SMALL] are not counted as flashes at all.
     */
    fun allow(request: Float, frameSeconds: Float): Float {
        // Reduce flashing both softens each flash and allows fewer of them.
        val cap = if (reduceFlashing) REDUCED_PER_SECOND else maxPerSecond
        budget = (budget + frameSeconds * cap).coerceAtMost(cap)
        var want = request.coerceIn(0f, 1f)
        if (reduceFlashing) want *= 0.25f
        if (want <= SMALL) {
            lastValue = want
            return want
        }
        if (budget < 1f) {
            // Out of budget: let the light through, but only as a gentle lift.
            val softened = max(SMALL, want * 0.18f)
            lastValue = softened
            return softened
        }
        budget -= 1f
        lastValue = want
        return want
    }

    fun reset() {
        budget = maxPerSecond
        lastValue = 0f
    }

    companion object {
        /** The cap when "Reduce flashing" is on: one large flash a second. */
        const val REDUCED_PER_SECOND = 1f
        const val SMALL = 0.08f
    }
}

/** Global, user-controlled accessibility state. Read by the player and the sims. */
class SafetySettings {
    var reduceFlashing: Boolean = false
    var reduceMotion: Boolean = false
    var haptics: Boolean = true
    /** Caps the top pitch in raw-frequency mode so it never gets piercing. */
    var maxHz: Float = 4000f
    /** Headroom below full scale, in dB. */
    var headroomDb: Float = 1f
}
