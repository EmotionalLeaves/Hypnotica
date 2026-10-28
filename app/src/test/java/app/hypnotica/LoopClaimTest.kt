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
        // Shapes and points both. It used to read only the shape buffer, so a
        // sim drawn in points — Mandelbrot, the particle field — was compared
        // on nothing but how many points it drew, and any frame with the same
        // count passed as a return. Both claimed a perfect loop that way, and
        // neither ever comes back.
        val n = minOf(g.instanceCount, 600)
        val m = minOf(g.pointCount, 600)
        val out = FloatArray(n * 2 + m * 2 + 1)
        for (i in 0 until n) {
            out[i * 2] = g.instances[i * G.STRIDE]
            out[i * 2 + 1] = g.instances[i * G.STRIDE + 1]
        }
        // Spread across the whole buffer rather than its first 600, which for
        // a big sim is one corner.
        val stride = maxOf(1, g.pointCount / maxOf(1, m))
        for (j in 0 until m) {
            out[n * 2 + j * 2] = g.pointPos[j * stride * 3]
            out[n * 2 + j * 2 + 1] = g.pointPos[j * stride * 3 + 1]
        }
        out[n * 2 + m * 2] = g.instanceCount.toFloat() + g.pointCount.toFloat()
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

    /**
     * The declared period is the period.
     *
     * Studio's "Perfect loop" template lays out whole cycles of loopSeconds, so
     * a wrong number there is a clip whose last frame does not meet its first —
     * the defect this field was added to fix. Every sim that loops declares
     * one, nothing else does, and one cycle on the picture is back where it
     * started by the same measure as the claim itself.
     */
    @Test
    fun theDeclaredLoopPeriodIsWhereThePictureComesBack() {
        val wrong = ArrayList<String>()
        for (def in SimRegistry.all) {
            val claims = def.formats.contains(TrapFormat.LOOP)
            val period = def.loopSeconds
            if (claims && period == null) { wrong.add("${def.id} loops but declares no period"); continue }
            if (!claims && period != null) { wrong.add("${def.id} declares a period and does not claim to loop"); continue }
            if (period == null) continue
            val core = SimCore(def)
            core.setAspect(0.56f)
            core.restart(5L)
            repeat(6) { core.advance(1f / 60f) }
            val start = snapshot(core)
            val t0 = core.env.time
            repeat(60) { core.advance(1f / 60f) }
            val perSecond = distance(snapshot(core), start).coerceAtLeast(1e-6f)
            // Within a third of a second either side of the declared period.
            var best = Float.MAX_VALUE
            while (core.env.time - t0 < period + 0.35f) {
                core.advance(1f / 60f)
                if (core.env.time - t0 > period - 0.35f) {
                    val d = distance(snapshot(core), start)
                    if (d < best) best = d
                }
            }
            if (best >= perSecond * 0.1f) wrong.add("${def.id} is not back at ${period}s")
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    /**
     * The period at the settings in use is the period.
     *
     * Studio lays out a perfect loop from it, and a slider that sets a cycle
     * length — the tower's build time, the polyrhythm's cycle — used to leave
     * the loop at the default's eighteen or twenty-four seconds whatever it
     * said. The hypercube is left out: its spin and tilt are two independent
     * turns, which come back together only near the shipped pair.
     */
    @Test
    fun thePeriodFollowsTheSettings() {
        val wrong = ArrayList<String>()
        for (def in SimRegistry.all) {
            if (def.loopSeconds == null || def.id in QUASI_PERIODIC) continue
            for (p in def.params) {
                if (p.type != app.hypnotica.core.recipe.ParamType.FLOAT || p.advancedOnly) continue
                for (f in listOf(0.6f, 1.5f)) {
                    val v = (p.default * f).coerceIn(p.min, p.max)
                    if (v == p.default) continue
                    val core = SimCore(def)
                    core.setAspect(0.56f)
                    core.params.set(p.key, v)
                    core.restart(5L)
                    repeat(6) { core.advance(1f / 60f) }
                    val start = snapshot(core)
                    val t0 = core.env.time
                    val period = core.sim.loopSeconds(core.env) ?: def.loopSeconds!!
                    repeat(60) { core.advance(1f / 60f) }
                    val perSecond = distance(snapshot(core), start).coerceAtLeast(1e-6f)
                    var best = Float.MAX_VALUE
                    while (core.env.time - t0 < period + 0.35f) {
                        core.advance(1f / 60f)
                        if (core.env.time - t0 > period - 0.35f) {
                            val d = distance(snapshot(core), start)
                            if (d < best) best = d
                        }
                    }
                    if (best >= perSecond * 0.1f) wrong.add("${def.id} with ${p.key}=$v is not back at ${period}s")
                }
            }
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    private companion object {
        val QUASI_PERIODIC = setOf("dimensions/hypercube")
    }
}
