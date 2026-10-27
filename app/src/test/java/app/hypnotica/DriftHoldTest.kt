package app.hypnotica

import app.hypnotica.core.recipe.DriftHold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Evolve standing off a value a person just moved.
 *
 * Without this, dragging a slider with Evolve on meant watching the value creep
 * back over the next second, which from arm's length is a slider that does not
 * work. The clock is a parameter so the six-second window can be measured
 * rather than waited out.
 */
class DriftHoldTest {

    private val second = 1_000_000_000L

    private fun hold() = DriftHold(6 * second)

    @Test
    fun untouchedValuesBelongToEvolve() {
        assertFalse(hold().holds("rate", 0L))
    }

    @Test
    fun aHandTakesTheValueForSixSeconds() {
        val h = hold()
        h.touch("rate", 10 * second)
        assertTrue("held the moment it is let go", h.holds("rate", 10 * second))
        assertTrue("still held a second later", h.holds("rate", 11 * second))
        assertTrue("held to the last moment", h.holds("rate", 10 * second + 5_900_000_000L))
        assertFalse("free once the window passes", h.holds("rate", 16 * second))
    }

    @Test
    fun onlyTheValueThatWasTouched() {
        val h = hold()
        h.touch("rate", 20 * second)
        assertFalse(h.holds("count", 20 * second))
    }

    @Test
    fun draggingAgainExtendsTheHold() {
        val h = hold()
        h.touch("rate", 20 * second)
        h.touch("rate", 25 * second)
        assertTrue("the second drag restarts the window", h.holds("rate", 30 * second))
    }

    @Test
    fun restartHandsEverythingBack() {
        val h = hold()
        h.touch("rate", 20 * second)
        h.clear()
        assertFalse(h.holds("rate", 20 * second))
    }

    /** nanoTime can step backwards across cores; a value must not pin for ever. */
    @Test
    fun aBackwardsClockStillExpires() {
        val h = hold()
        h.touch("x", 100 * second)
        assertTrue("held while the clock is behind", h.holds("x", 50 * second))
        assertFalse("and expires from where it was last seen", h.holds("x", 57 * second))
    }

    /** A long session touches many values; the table must drain, not grow. */
    @Test
    fun expiredEntriesAreDropped() {
        val h = hold()
        for (i in 0 until 500) h.touch("k$i", 0L)
        assertEquals(500, h.size())
        for (i in 0 until 500) h.holds("k$i", 10 * second)
        assertEquals("every expired entry is gone", 0, h.size())
    }
}
