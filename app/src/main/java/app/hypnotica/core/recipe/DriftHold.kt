package app.hypnotica.core.recipe

import java.util.concurrent.ConcurrentHashMap

/**
 * Which values Evolve must leave alone, because a person just moved them.
 *
 * Evolve drifts every live parameter along a slow sine. Without this, dragging
 * a slider while Evolve was on meant watching the value creep back over the
 * next second — indistinguishable, from the far side of the screen, from a
 * slider that does not work. A typed formula already outranked the drift; a
 * hand on the control should too.
 *
 * The clock is passed in rather than read here so the behaviour can be measured
 * against a fake one instead of waiting six seconds in a test.
 */
class DriftHold(private val holdNanos: Long) {

    private val touched = ConcurrentHashMap<String, Long>()

    /** A person moved [key] by hand at [now]. */
    fun touch(key: String, now: Long) {
        touched[key] = now
    }

    /**
     * True while [key] still belongs to the hand that moved it. Expired entries
     * are dropped as they are found, so a long session does not accumulate one
     * entry per value ever touched.
     */
    fun holds(key: String, now: Long): Boolean {
        val at = touched[key] ?: return false
        // A clock that jumped backwards (and nanoTime may, across cores) must
        // not pin a value forever.
        if (now < at) {
            touched[key] = now
            return true
        }
        if (now - at < holdNanos) return true
        touched.remove(key)
        return false
    }

    /** A restart hands every value back to Evolve. */
    fun clear() {
        touched.clear()
    }

    /** How many values are currently held. For tests and the counters. */
    fun size(): Int = touched.size
}
