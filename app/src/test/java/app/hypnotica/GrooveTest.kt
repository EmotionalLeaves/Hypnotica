package app.hypnotica

import app.hypnotica.core.audio.Groove
import app.hypnotica.core.audio.SoundMapping
import app.hypnotica.core.audio.SoundPreset
import app.hypnotica.core.audio.Synth
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.EventQueue
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The beat has to keep playing when it changes.
 *
 * The groove's clock was seconds divided by the step length. Under "Auto"
 * every sim gets its own tempo, and moving to a slower one made that number
 * jump backwards: nothing was released until it caught up, and since every note
 * waits for the grid, the app went silent — five minutes in, for about three.
 */
class GrooveTest {

    @Test
    fun aSlowerGrooveStillSoundsAtOnce() {
        val rate = 22050
        val synth = Synth(rate)
        val mapping = SoundMapping(synth)
        mapping.applyPreset(SoundPreset.EIGHT_BIT)
        mapping.groove = Groove.FOUR.withTempo(150f)
        val queue = EventQueue()
        val frame = 1f / 60f
        val buf = FloatArray(rate / 60 * 2)
        // Five minutes of a busy sim at a fast tempo.
        repeat(5 * 60 * 60) {
            queue.emit(EventKind.HIT, 0.5f, 0f, 0.6f)
            mapping.pump(queue, frame)
            synth.renderOffline(buf, rate / 60)
        }
        // Then a slow one, as the next sim would get.
        mapping.groove = Groove.HALF.withTempo(72f)
        var loudest = 0f
        repeat(60) {
            queue.emit(EventKind.HIT, 0.5f, 0f, 0.6f)
            mapping.pump(queue, frame)
            java.util.Arrays.fill(buf, 0f)
            synth.renderOffline(buf, rate / 60)
            // The second half only: the first is the old notes ringing out.
            if (it >= 30) for (v in buf) loudest = maxOf(loudest, abs(v))
        }
        assertTrue("half a second after the tempo slowed, nothing was sounding (peak $loudest)", loudest > 0.01f)
    }
}
