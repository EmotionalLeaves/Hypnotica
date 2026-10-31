package app.hypnotica.export

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Bringing an exported clip's sound up to the level videos are played at.
 *
 * The synth mixes with plenty of headroom, which is right for the live app
 * (the phone's volume does the rest) and wrong for a file: on YouTube a Short
 * sat far below everything around it. Here the whole track is measured —
 * its loudness over the stretches that make sound, not the silences — raised
 * to a target, and its peaks rounded off by a soft limiter so nothing clips.
 */
object Loudness {

    /** Loudness aimed for, as RMS in dBFS: about where Shorts sit. */
    const val TARGET_DB = -14f

    /** Never raised by more than this: a near-silent clip stays near silent. */
    const val MAX_GAIN_DB = 30f

    /** Where the limiter starts to round peaks off, and the most it lets through. */
    private const val KNEE = 0.7f
    private const val CEILING = 0.97f

    /** Blocks quieter than this don't count towards the measured level. */
    private const val GATE_DB = -55f

    /** The gain the track gets, in decibels, measured from its loud parts. */
    fun gainDb(blocks: List<FloatArray>): Float {
        var sum = 0.0
        var n = 0L
        for (b in blocks) {
            if (b.isEmpty()) continue
            var s = 0.0
            for (v in b) s += v * v
            val rms = sqrt(s / b.size)
            if (rms <= 0.0 || 20 * log10(rms) < GATE_DB) continue
            sum += s
            n += b.size
        }
        if (n == 0L) return 0f
        val level = 20 * log10(sqrt(sum / n)).toFloat()
        return (TARGET_DB - level).coerceIn(0f, MAX_GAIN_DB)
    }

    /** The track raised to [TARGET_DB], peaks limited, as 16-bit samples. */
    fun master(blocks: List<FloatArray>): List<ShortArray> {
        val gain = 10f.pow(gainDb(blocks) / 20f)
        return blocks.map { b ->
            ShortArray(b.size) { i -> (limit(b[i] * gain) * 32767f).toInt().toShort() }
        }
    }

    /** Untouched below the knee; above it, eased towards the ceiling and never past it. */
    fun limit(x: Float): Float {
        val a = abs(x)
        if (a <= KNEE) return x
        val room = CEILING - KNEE
        val y = KNEE + room * tanh(((a - KNEE) / room).toDouble()).toFloat()
        return if (x < 0f) -min(y, CEILING) else min(y, CEILING)
    }
}
