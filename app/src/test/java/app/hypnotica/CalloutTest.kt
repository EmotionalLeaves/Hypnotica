package app.hypnotica

import app.hypnotica.core.sim.SimCore
import app.hypnotica.sims.SimRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Callouts are for moments, not a running commentary: over forty seconds no
 * sim may call out more than about once every two seconds, and none may
 * call out on its very first frame.
 */
class CalloutTest {

    @Test
    fun calloutsAreRare() {
        val noisy = ArrayList<String>()
        for (def in SimRegistry.all) {
            val core = SimCore(def, 91L)
            core.setAspect(0.46f)
            var frames = 0
            var maxPerSecond = 0
            var lastSerial = 0
            while (frames < 40 * 60) {
                core.advance(1f / 60f)
                frames++
                if (frames % 60 == 0) {
                    val serial = core.env.calloutSerial
                    maxPerSecond = maxOf(maxPerSecond, serial - lastSerial)
                    lastSerial = serial
                }
                if (core.finished) core.restart()
            }
            val total = core.env.calloutSerial
            if (maxPerSecond > 2 || total > 20) noisy.add("${def.id}: $total in 40 s, up to $maxPerSecond in a second")
        }
        assertTrue("too many callouts:\n" + noisy.joinToString("\n"), noisy.isEmpty())
    }
}
