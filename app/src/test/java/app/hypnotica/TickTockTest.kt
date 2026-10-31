package app.hypnotica

import app.hypnotica.core.director.ClipDirector
import app.hypnotica.core.sim.SimCore
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TickTock's clips, played back the way the screen plays them.
 *
 * A clip is timed from its payoff check: the screen moves on at the payoff
 * over 0.7, or three seconds after it, whichever is later. So the run on
 * screen has to reach its moment when the check said, on the screen it was
 * checked for. Before the check followed the screen's shape, an arena
 * checked for a narrow phone was still undecided when its clip ended on a
 * 16:9 one.
 */
class TickTockTest {

    @Test
    fun clipsPayOffOnTheScreenTheyWereCheckedFor() {
        val wrong = ArrayList<String>()
        for (aspect in floatArrayOf(0.45f, 0.5625f)) {
            val director = ClipDirector(seed = 4242L)
            director.aspect = aspect
            repeat(CLIPS) {
                val clip = director.generate() ?: run { wrong.add("no clip at $aspect"); return@repeat }
                val core = SimCore(clip.def, clip.seed)
                core.setAspect(aspect)
                core.setLaws(clip.laws)
                for ((k, v) in clip.params) core.params.set(k, v)
                core.restart(clip.seed)
                val end = maxOf(clip.payoffSeconds / 0.7f, clip.payoffSeconds + 3f)
                var t = 0f
                var frame = 0
                // Uneven frames, as a phone gives them.
                while (t < end && !core.payoffFired) {
                    val dt = if (frame++ % 7 == 0) 1f / 40f else 1f / 60f
                    core.advance(dt)
                    t += dt
                }
                if (!core.payoffFired) wrong.add("${clip.def.id} at $aspect: checked ${clip.payoffSeconds} s, nothing by $end s")
            }
        }
        assertTrue("clips that never reach their moment on screen:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    companion object { const val CLIPS = 15 }
}
