package app.hypnotica

import app.hypnotica.core.render.G
import app.hypnotica.core.sim.SimCore
import app.hypnotica.sims.SimRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Every sim has to fit on a phone held upright.
 *
 * The app fills the screen, and on an upright phone the screen is under half a
 * unit wide. Most sims lay themselves out in [app.hypnotica.core.sim.SimEnv.halfWidth];
 * a dozen drew at a fixed size as if the screen were square, and measured at
 * that shape they had a quarter to three quarters of what they drew off the
 * sides: the ball-escape ring cut to two arcs, the gear train off to the right,
 * the Monte Carlo square missing its corners. Those now say how far they reach
 * and the view pulls back to fit, or they lay themselves out to the width.
 *
 * The test draws each sim at three moments and takes how far out the bulk of
 * it reaches — the 98th percentile, so a lone particle flung away does not
 * count — against the screen after the fit.
 */
class FrameTest {

    @Test
    fun everySimFitsAnUprightPhone() {
        val aspect = 0.46f
        val g = G()
        val over = ArrayList<String>()
        for (def in SimRegistry.all) {
            // Bodies flung out of an n-body system are meant to leave.
            if (def.id in LEAVES_ON_PURPOSE) continue
            val core = SimCore(def, 11L)
            core.setAspect(aspect)
            var t = 0f
            var worst = 0f
            for (target in floatArrayOf(3f, 10f, 20f)) {
                while (t < target && !core.finished) {
                    core.advance(1f / 60f)
                    t += 1f / 60f
                }
                g.reset()
                core.sim.draw(core.env, g, 1f)
                val n = g.instanceCount + g.pointCount
                if (n == 0) continue
                val xs = FloatArray(n)
                for (i in 0 until g.instanceCount) {
                    val b = i * G.STRIDE
                    val r = g.instances[b + 4]
                    xs[i] = abs(g.instances[b]) + abs(g.instances[b + 2] * cos(r)) + abs(g.instances[b + 3] * sin(r))
                }
                for (j in 0 until g.pointCount) xs[g.instanceCount + j] = abs(g.pointPos[j * 3])
                xs.sort()
                val reach = xs[((n - 1) * 0.98f).toInt()]
                worst = maxOf(worst, reach * core.fit() / core.env.halfWidth)
            }
            if (worst > LIMIT) over.add("${def.id}: reaches ${"%.2f".format(java.util.Locale.US, worst)} of the screen's half-width")
        }
        assertTrue(
            "Drawn past the sides of an upright phone:\n" + over.joinToString("\n"),
            over.isEmpty()
        )
    }

    /**
     * A point's size is in world units, like everything else drawn. Two sims
     * passed 1.2 to 2.6 and 1.6 — sizes in pixels, as if — which is most of
     * the screen; the renderer clamps a dot at 96 pixels, so their big-count
     * modes drew as walls of overlapping blobs. Checked at a large count,
     * which is where sims switch to points.
     */
    @Test
    fun everyPointIsSizedInWorldUnits() {
        val g = G()
        val big = setOf("count", "n", "bodies", "dots", "points", "walkers", "beads", "tracers", "bricks", "circles", "limit", "trail", "nodes", "balls")
        val wrong = ArrayList<String>()
        for (def in SimRegistry.all) {
            val core = SimCore(def, 3L)
            core.setAspect(0.46f)
            for (p in def.params) if (p.logScale && p.key in big) core.params.set(p.key, minOf(p.max, 20000f))
            core.restart(3L)
            var t = 0f
            while (t < 3f) {
                core.advance(1f / 60f)
                t += 1f / 60f
            }
            g.reset()
            core.sim.draw(core.env, g, 1f)
            var largest = 0f
            for (j in 0 until g.pointCount) largest = maxOf(largest, g.pointPos[j * 3 + 2])
            if (largest > MAX_POINT) wrong.add("${def.id}: a point ${"%.2f".format(java.util.Locale.US, largest)} across")
        }
        assertTrue("Points sized as if in pixels:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    companion object {
        /** A point this large is a disc's job, or a mistake. */
        const val MAX_POINT = 0.05f

        /** A little past the edge is a glow or a stroke; more is cut off. */
        const val LIMIT = 1.15f
        val LEAVES_ON_PURPOSE = setOf("physics/n-body")
    }
}
