package app.hypnotica.engine

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The third channel.
 *
 * The design rule is that every event is seen, heard and felt, but a sim can emit
 * thousands of events a second and a phone's motor cannot keep up with that, nor
 * would anyone want it to. So the big moments get a tick and the rest do not,
 * with a hard ceiling on how often the motor is asked to do anything.
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (e: Exception) {
        null
    }

    var enabled: Boolean = true

    @Volatile
    private var lastAt = 0L

    val available: Boolean get() = vibrator?.hasVibrator() == true

    /** A light tap for a UI action or a small event. */
    fun light() = buzz(12, 60, MIN_GAP_MS)

    /** A firmer tap for a collision. */
    fun medium() = buzz(18, 120, MIN_GAP_MS)

    /** The payoff. */
    fun heavy() = buzz(34, 220, 220)

    /**
     * A verdict: felt even straight after the payoff's own buzz, which comes
     * a moment before it and would otherwise swallow it.
     */
    fun verdict() = buzz(45, 255, 0)

    private fun buzz(millis: Long, amplitude: Int, minGapMs: Long) {
        val v = vibrator ?: return
        if (!enabled || !v.hasVibrator()) return
        val now = System.currentTimeMillis()
        if (now - lastAt < minGapMs) return
        lastAt = now
        try {
            v.vibrate(VibrationEffect.createOneShot(millis, amplitude.coerceIn(1, 255)))
        } catch (e: Exception) {
            // A phone that refuses to buzz is not a reason to stop the sim.
        }
    }

    private companion object {
        /** At most thirty ticks a second, as the design contract requires. */
        const val MIN_GAP_MS = 33L
    }
}
