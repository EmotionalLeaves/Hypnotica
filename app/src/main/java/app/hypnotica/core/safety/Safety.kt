package app.hypnotica.core.safety

import kotlin.math.max

/**
 * The engine enforces the safety limits itself rather than trusting each sim.
 *
 * Fast, bright, loud content is the point of the app, so these caps are not
 * optional: a Recipe cannot opt out of them, it can only ask for less.
 */
class FlashLimiter {

    /**
     * Large flashes allowed in any one-second window. WCAG's general flash
     * threshold is three, which is the ceiling here as well as the default: a
     * sim may ask for fewer, never for more.
     */
    var maxPerSecond: Float = HARD_CEILING
        set(value) {
            field = value.coerceIn(0f, HARD_CEILING)
        }

    /** When on, every flash is softened and the cap is tightened. */
    var reduceFlashing: Boolean = false

    /**
     * When the last few large flashes happened, on this limiter's own clock.
     * A sliding window, not a refilling budget: a budget that tops up a little
     * every frame lets a fourth flash through part-way into the same second.
     */
    private val recent = FloatArray(WINDOW_SLOTS) { Float.NEGATIVE_INFINITY }
    private var slot = 0
    private var clock = 0f

    /**
     * The brightness still on screen from the flash that was allowed. A flash
     * that lives for one frame reads as a glitch rather than a flash, so an
     * allowed one is held and faded over about an eighth of a second.
     */
    private var held = 0f

    /**
     * Returns the flash strength that may actually be drawn this frame.
     * Strengths under [SMALL] are not counted as flashes at all.
     */
    fun allow(request: Float, frameSeconds: Float): Float {
        // A paused or stalled frame must not advance the window by a huge step.
        val dt = if (frameSeconds.isFinite()) frameSeconds.coerceIn(0f, 0.25f) else 0f
        clock += dt
        held = max(0f, held - dt * FADE_PER_SECOND)

        var want = if (request.isFinite()) request.coerceIn(0f, 1f) else 0f
        if (reduceFlashing) want *= REDUCED_STRENGTH

        // Below the threshold it is a glow, not a flash, and is never counted.
        if (want <= SMALL) return max(want, held)

        val cap = if (reduceFlashing) REDUCED_PER_SECOND else maxPerSecond
        if (countInWindow() >= cap) {
            // Out of flashes: this frame gets the fading afterglow of the last
            // one and nothing new. Returning a fraction of every refused
            // request instead — which is what this used to do — turned an
            // over-eager sim into a sixty-hertz strobe at a fifth brightness,
            // which is exactly what the cap exists to prevent.
            return held
        }

        recent[slot] = clock
        slot = (slot + 1) % recent.size
        held = want
        return want
    }

    private fun countInWindow(): Int {
        var n = 0
        for (t in recent) if (clock - t < 1f) n++
        return n
    }

    fun reset() {
        for (i in recent.indices) recent[i] = Float.NEGATIVE_INFINITY
        slot = 0
        clock = 0f
        held = 0f
    }

    companion object {
        /** No setting may allow more than this many large flashes a second. */
        const val HARD_CEILING = 3f

        /** The cap when "Reduce flashing" is on: one large flash a second. */
        const val REDUCED_PER_SECOND = 1f

        /** How much of a requested flash gets through when reducing. */
        const val REDUCED_STRENGTH = 0.25f

        /** Anything at or below this is a glow, not a flash. */
        const val SMALL = 0.08f

        /** A full flash fades out in about an eighth of a second. */
        const val FADE_PER_SECOND = 8f

        /** Room for the ceiling plus a spare, so the ring never wraps early. */
        private const val WINDOW_SLOTS = 8
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
