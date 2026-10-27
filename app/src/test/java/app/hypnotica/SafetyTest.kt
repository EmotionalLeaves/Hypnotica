package app.hypnotica

import app.hypnotica.core.safety.FlashLimiter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flash cap, measured rather than assumed.
 *
 * This is a safety limit, so it is tested against the worst case the app can
 * produce: a sim that asks for a full-strength flash on every single frame. An
 * earlier version answered that by letting a fifth of every request through,
 * every frame, which is a sixty-hertz strobe — exactly what the cap exists to
 * prevent, and the reason this test counts flashes in a sliding window instead
 * of trusting the limiter's own bookkeeping.
 */
class SafetyTest {

    /** Everything clearly above the glow threshold counts as a flash. */
    private fun isFlash(v: Float) = v > FlashLimiter.SMALL * 1.5f

    private fun worstSecond(times: List<Float>): Int {
        var worst = 0
        for (i in times.indices) {
            var n = 0
            for (j in i until times.size) if (times[j] - times[i] < 1f) n++
            if (n > worst) worst = n
        }
        return worst
    }

    private fun drive(limiter: FlashLimiter, fps: Int, seconds: Int, request: Float): List<Float> {
        val dt = 1f / fps
        val times = ArrayList<Float>()
        var previous = 0f
        for (i in 0 until fps * seconds) {
            val got = limiter.allow(request, dt)
            // A flash is a rise. The fade after an allowed flash is the same
            // light still leaving the screen, not a second flash.
            if (isFlash(got) && got > previous + 1e-4f) times.add(i * dt)
            previous = got
        }
        return times
    }

    @Test
    fun aSimAskingEveryFrameStillGetsOnlyThreeFlashesASecond() {
        for (fps in intArrayOf(24, 30, 60, 90, 120)) {
            val limiter = FlashLimiter()
            limiter.reset()
            val worst = worstSecond(drive(limiter, fps, 10, 1f))
            assertTrue(
                "at $fps fps a full flash every frame produced $worst in one second",
                worst <= 3
            )
        }
    }

    @Test
    fun reduceFlashingAllowsOneASecondAndSoftensIt() {
        val limiter = FlashLimiter()
        limiter.reduceFlashing = true
        limiter.reset()
        val first = limiter.allow(1f, 1f / 60f)
        assertTrue("a reduced flash should be softened, got $first", first <= 0.26f)
        val worst = worstSecond(drive(limiter, 60, 10, 1f))
        assertTrue("reduce flashing let $worst through in one second", worst <= 1)
    }

    @Test
    fun aRecipeCannotRaiseTheCap() {
        val limiter = FlashLimiter()
        limiter.maxPerSecond = 60f
        assertEquals(FlashLimiter.HARD_CEILING, limiter.maxPerSecond, 1e-6f)
        limiter.reset()
        val worst = worstSecond(drive(limiter, 60, 10, 1f))
        assertTrue("asking for sixty a second produced $worst", worst <= 3)
    }

    @Test
    fun aRecipeCanAskForFewer() {
        val limiter = FlashLimiter()
        limiter.maxPerSecond = 1f
        limiter.reset()
        val worst = worstSecond(drive(limiter, 60, 10, 1f))
        assertTrue("a cap of one let $worst through in one second", worst <= 1)
    }

    @Test
    fun anAllowedFlashIsHeldLongEnoughToSee() {
        val limiter = FlashLimiter()
        limiter.reset()
        val dt = 1f / 60f
        assertEquals(1f, limiter.allow(1f, dt), 1e-6f)
        // Nothing asked for after this, so what remains is the fade.
        var frames = 0
        while (limiter.allow(0f, dt) > FlashLimiter.SMALL && frames < 60) frames++
        val seconds = frames * dt
        assertTrue("a flash vanished in ${seconds}s, too fast to read", seconds >= 0.05f)
        assertTrue("a flash lingered for ${seconds}s", seconds <= 0.3f)
    }

    @Test
    fun aGlowIsNotCountedAsAFlash() {
        val limiter = FlashLimiter()
        limiter.reset()
        // Thousands of sub-threshold requests must never exhaust the budget.
        repeat(600) { limiter.allow(FlashLimiter.SMALL * 0.9f, 1f / 60f) }
        assertEquals(1f, limiter.allow(1f, 1f / 60f), 1e-6f)
    }

    @Test
    fun nonsenseInputCannotLeakThrough() {
        val limiter = FlashLimiter()
        limiter.reset()
        assertEquals(0f, limiter.allow(Float.NaN, 1f / 60f), 1e-6f)
        assertEquals(0f, limiter.allow(-5f, Float.NaN), 1e-6f)
        assertEquals(1f, limiter.allow(5f, 1f / 60f), 1e-6f)
    }

    @Test
    fun aLongStallDoesNotOpenTheWindow() {
        val limiter = FlashLimiter()
        limiter.reset()
        // Three allowed, then a frame claiming a huge delta: the clock must not
        // jump a whole second forward and hand out three more.
        repeat(3) { limiter.allow(1f, 1f / 60f) }
        val afterStall = limiter.allow(1f, 30f)
        assertTrue("a thirty-second frame reopened the window", afterStall <= FlashLimiter.SMALL)
    }
}
