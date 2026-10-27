package app.hypnotica

import app.hypnotica.core.render.G
import app.hypnotica.core.sim.SimCore
import app.hypnotica.core.sim.TrapFormat
import app.hypnotica.sims.SimRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * "Perfect loop" has to be true.
 *
 * TrapFormat.LOOP's own words are "It never restarts", and the clip director
 * offers it to the feed on that basis. Twenty-seven sims declared it and
 * seventeen never came back to where they started — a menu promising what the
 * code does not do, which is the rule this project is most explicit about.
 *
 * The picture is summarised as the drawn positions, and a frame counts as a
 * return when its distance from the first frame is under a tenth of how much
 * the picture normally changes in a second. Exact equality would be the wrong
 * test: floating point, and a sim whose trail is still growing can never match
 * by construction.
 */
class LoopClaimTest {

    private fun snapshot(core: SimCore): FloatArray {
        val g = G()
        g.reset()
        core.sim.draw(core.env, g, 1f)
        val n = minOf(g.instanceCount, 600)
        val out = FloatArray(n * 2 + 1)
        for (i in 0 until n) {
            out[i * 2] = g.instances[i * G.STRIDE]
            out[i * 2 + 1] = g.instances[i * G.STRIDE + 1]
        }
        out[n * 2] = g.instanceCount.toFloat() + g.pointCount.toFloat()
        return out
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return Float.MAX_VALUE
        var d = 0f
        for (i in a.indices) d += abs(a[i] - b[i])
        return d / a.size
    }

    @Test
    fun everySimClaimingAPerfectLoopComesBackToWhereItStarted() {
        val claiming = SimRegistry.all.filter { it.formats.contains(TrapFormat.LOOP) }
        assertTrue("no sim claims a perfect loop, so this proves nothing", claiming.isNotEmpty())

        val broken = ArrayList<String>()
        for (def in claiming) {
            val core = SimCore(def)
            core.setAspect(0.56f)
            core.restart(5L)
            repeat(6) { core.advance(1f / 60f) }
            val start = snapshot(core)
            repeat(60) { core.advance(1f / 60f) }
            val perSecond = distance(snapshot(core), start).coerceAtLeast(1e-6f)

            var best = Float.MAX_VALUE
            val frames = (def.typicalSeconds * 2f * 60f).toInt().coerceAtMost(7200)
            repeat(frames) { i ->
                core.advance(1f / 60f)
                if (i > 120) {
                    val d = distance(snapshot(core), start)
                    if (d < best) best = d
                }
            }
            if (best >= perSecond * 0.1f) broken.add(def.id)
        }
        assertTrue(
            "these sims advertise \"Perfect loop\" — \"It never restarts\" — and never " +
                "return to their first frame within twice their own length: $broken",
            broken.isEmpty()
        )
    }
}
