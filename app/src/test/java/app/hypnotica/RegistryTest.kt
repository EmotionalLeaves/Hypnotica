package app.hypnotica

import app.hypnotica.core.laws.Laws
import app.hypnotica.core.preview.Previews
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.recipe.Macros
import app.hypnotica.core.sim.SimCore
import app.hypnotica.core.sim.SimDef
import app.hypnotica.sims.SimRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry smoke test.
 *
 * Every sim is stepped headlessly here, under several sets of laws, looking for
 * crashes and for a missing payoff. This is the test that catches a sim which
 * runs forever in silence — the kind of fault that a build which only compiles
 * would ship happily.
 */
class RegistryTest {

    private val g = G()

    @Test
    fun everySimRunsUnderEveryLaw() {
        val presets = listOf(
            Laws.OUR_UNIVERSE,
            Laws.preset("Zero-G"),
            Laws.preset("Backwards time"),
            Laws.preset("Bouncy"),
            Laws.preset("Sticky"),
            Laws.preset("Ghosts"),
            Laws.preset("Wrap-around space")
        )
        val broken = ArrayList<String>()
        for (def in SimRegistry.all) {
            for (laws in presets) {
                try {
                    run(def, laws, seconds = 6f)
                } catch (e: Throwable) {
                    broken.add("${def.id} under ${laws.name}: $e")
                }
            }
        }
        assertTrue(broken.joinToString("\n"), broken.isEmpty())
    }

    @Test
    fun everySimReachesItsPayoff() {
        val silent = ArrayList<String>()
        for (def in SimRegistry.all) {
            val limit = (def.typicalSeconds * 2f).coerceIn(20f, 60f)
            val core = run(def, Laws.OUR_UNIVERSE, limit)
            if (!core.payoffFired) silent.add("${def.id} (no payoff in ${limit}s)")
        }
        assertTrue(
            "These sims never pay off, so TickTock can never show them:\n" +
                silent.joinToString("\n"),
            silent.isEmpty()
        )
    }

    @Test
    fun everySimMakesSound() {
        val silent = ArrayList<String>()
        for (def in SimRegistry.all) {
            var events = 0
            val core = SimCore(def, 12345L)
            core.setAspect(0.46f)
            core.setPalette(Palette.byName(def.defaultPalette))
            var frames = 0
            while (frames < 60 * 20 && !core.finished) {
                core.advance(1f / 60f)
                g.reset()
                core.draw(g)
                core.events.drain(10_000) { _, _, _, _ -> events++ }
                frames++
            }
            if (events == 0) silent.add(def.id)
        }
        assertTrue("These sims make no sound at all:\n" + silent.joinToString("\n"), silent.isEmpty())
    }

    @Test
    fun everySimHasASimpleModeControl() {
        val bare = SimRegistry.all.filter { Macros.forDef(it).isEmpty() }
        assertTrue(
            "Simple mode would have nothing to offer for:\n" + bare.joinToString("\n") { it.id },
            bare.isEmpty()
        )
    }

    @Test
    fun everySimHasAPreview() {
        val blank = SimRegistry.all.filter { Previews.build(it).isEmpty }
        assertTrue(
            "These sims draw nothing in the Library:\n" + blank.joinToString("\n") { it.id },
            blank.isEmpty()
        )
    }

    /** The sizes the sliders allow at the top end have to actually run. */
    @Test
    fun theBigSizesRun() {
        val cases = listOf(
            Triple("scale/particle-field", "count", 400_000f),
            Triple("life/game-of-life", "grid", 1024f),
            Triple("sorting/quicksort", "n", 200_000f),
            Triple("sorting/bubble-sort", "n", 200_000f),
            Triple("math/lorenz", "trail", 60_000f),
            Triple("physics/galton-board", "beads", 20_000f),
            Triple("physics/pendulum-wave", "count", 20_000f),
            // n-body is O(n squared) and deliberately stops at 4,000.
            Triple("physics/n-body", "bodies", 4_000f),
            Triple("math/riemann", "maxbars", 200_000f),
            Triple("algorithms/binary-tree", "nodes", 100_000f)
        )
        for ((id, key, value) in cases) {
            val def = SimRegistry.find(id)
            assertTrue("missing sim $id", def != null)
            val core = SimCore(def!!, 7L)
            core.setAspect(0.46f)
            val param = def.params.firstOrNull { it.key == key }
            assertTrue("$id has no parameter $key", param != null)
            assertTrue(
                "$id can only reach ${param!!.max} $key, not $value",
                param.max >= value
            )
            core.params.set(key, value)
            core.restart(7L)
            val started = System.nanoTime()
            repeat(FRAMES) {
                core.advance(1f / 60f)
                g.reset()
                core.draw(g)
                core.events.drain(100_000) { _, _, _, _ -> }
            }
            val ms = (System.nanoTime() - started) / 1e6 / FRAMES
            println(
                String.format(
                    "%-28s %s=%-8.0f %6.2f ms/frame  shapes=%d points=%d",
                    id, key, value, ms, g.instanceCount, g.pointCount
                )
            )
        }
    }

    private fun run(def: SimDef, laws: Laws, seconds: Float): SimCore {
        val core = SimCore(def, 12345L)
        core.setAspect(0.46f)
        core.setLaws(laws)
        core.setPalette(Palette.byName(def.defaultPalette))
        var frames = 0
        val limit = (seconds * 60f).toInt()
        while (frames < limit && !core.finished) {
            core.advance(1f / 60f)
            g.reset()
            core.draw(g)
            core.events.drain(10_000) { _, _, _, _ -> }
            frames++
        }
        return core
    }

    private companion object {
        const val FRAMES = 60
    }
}
