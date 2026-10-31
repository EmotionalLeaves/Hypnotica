package app.hypnotica

import app.hypnotica.export.Loudness
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** An exported clip's sound comes out at the level videos play at, and never clips. */
class LoudnessTest {

    private fun tone(amp: Float, blocks: Int, frames: Int = 735): List<FloatArray> =
        List(blocks) { b -> FloatArray(frames * 2) { i -> amp * sin((b * frames + i / 2) * 0.06).toFloat() } }

    private fun rmsDb(blocks: List<ShortArray>): Double {
        var s = 0.0; var n = 0
        for (b in blocks) for (v in b) { val x = v / 32768.0; s += x * x; n++ }
        return 20 * log10(sqrt(s / n))
    }

    @Test
    fun aQuietTrackIsBroughtUp() {
        val out = Loudness.master(tone(0.01f, 300))
        val db = rmsDb(out)
        assertTrue("came out at $db dBFS", db > -17 && db < -11)
    }

    @Test
    fun peaksNeverClip() {
        val out = Loudness.master(tone(0.9f, 100) + tone(0.002f, 100))
        val peak = out.maxOf { b -> b.maxOf { abs(it.toInt()) } } / 32768f
        assertTrue("peak $peak", peak <= 0.98f)
    }

    @Test
    fun silenceStaysSilent() {
        val out = Loudness.master(List(50) { FloatArray(1470) })
        assertTrue(out.all { b -> b.all { it.toInt() == 0 } })
    }
}
