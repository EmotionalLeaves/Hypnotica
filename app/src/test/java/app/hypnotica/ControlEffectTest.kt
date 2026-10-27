package app.hypnotica

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.recipe.ParamType
import app.hypnotica.core.render.G
import app.hypnotica.core.sim.SimCore
import app.hypnotica.sims.SimRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every control changes the picture.
 *
 * The user's report was "not all buttons and sliders work", and it was right:
 * eighty-eight values across the sims were copied into a field in setup() and
 * never looked at again, so the slider moved, the number changed and the
 * picture did not. Whole cards of them — boids' entire Motion card, the sorting
 * sims' Algorithm chip, Mandelbrot's Resolution and Detail — and the Chaos and
 * Energy macros that Simple mode leans on hardest were inert for the same
 * reason.
 *
 * The fix was not a list: ParamValues records which keys a sim reads while it
 * is running, and a value nothing reads live is applied by rebuilding the run,
 * which is what the Data card has always done. This is the test that says the
 * rebuild actually shows: for each such value, set it low, draw; set it high,
 * draw; the frames must differ. Nothing here trusts the mechanism's own
 * bookkeeping — it compares what would be on screen.
 */
class ControlEffectTest {

    /** Long enough that a trail has something to draw; shorter runs lie. */
    private val frames = 250

    /** Everything a frame would put on screen, as one number. */
    private fun digest(core: SimCore): Long {
        val g = G()
        g.reset()
        core.sim.draw(core.env, g, 1f)
        var h = 1125899906842597L
        h = h * 31 + g.instanceCount
        h = h * 31 + g.pointCount
        h = h * 31 + g.glowCount
        for (i in 0 until g.instanceCount * G.STRIDE) h = h * 31 + g.instances[i].toRawBits()
        for (i in 0 until g.pointCount * 3) h = h * 31 + g.pointPos[i].toRawBits()
        for (i in 0 until g.pointCount * 4) h = h * 31 + g.pointColor[i]
        return h
    }

    /** A fresh run at one value of one parameter. Fresh, so nothing carries over. */
    private fun frameAt(id: String, key: String, value: Float): Long {
        val def = SimRegistry.all.first { it.id == id }
        val core = SimCore(def)
        core.setAspect(0.56f)
        core.params.set(key, value)
        core.restart()
        repeat(frames) { core.advance(1f / 60f) }
        return digest(core)
    }

    /** The values a sim only reads while being built, as it reports them itself. */
    private fun setupOnly(id: String): List<Param> {
        val def = SimRegistry.all.first { it.id == id }
        val core = SimCore(def)
        core.setAspect(0.56f)
        repeat(40) { core.advance(1f / 60f) }
        assertTrue("$id never settled, so it cannot say what it reads", core.params.readsSettled)
        return def.params.filter {
            it.card != Card.DATA &&
                it.type != ParamType.EXPR &&
                it.mutedBy == null &&
                it.min < it.max &&
                !core.params.readLive(it.key)
        }
    }

    @Test
    fun everySetupOnlyControlChangesTheFrameOnceTheRunIsRebuilt() {
        val inert = ArrayList<String>()
        var checked = 0
        for (def in SimRegistry.all) {
            for (p in setupOnly(def.id)) {
                checked++
                if (frameAt(def.id, p.key, p.min) == frameAt(def.id, p.key, p.max)) {
                    inert.add("${def.id}/${p.key} (${p.label})")
                }
            }
        }
        assertTrue("nothing was checked, so this test proves nothing", checked > 0)
        assertTrue(
            "these controls still draw an identical frame at both ends of their " +
                "range, so moving them does nothing: $inert",
            inert.isEmpty()
        )
    }

    /**
     * A control taken over by another setting has to say which one, so the
     * person can find the switch instead of concluding the slider is broken.
     */
    @Test
    fun everyMutedControlNamesAnOwnerThatExists() {
        for (def in SimRegistry.all) {
            for (p in def.params) {
                val owner = p.mutedBy ?: continue
                val target = def.params.firstOrNull { it.key == owner }
                assertTrue(
                    "${def.id}/${p.key} says it is set by '$owner', which it does not declare",
                    target != null
                )
                assertTrue(
                    "${def.id}/${p.key} is set by '$owner', which is not a switch",
                    target!!.type == ParamType.BOOL
                )
            }
        }
    }
}
