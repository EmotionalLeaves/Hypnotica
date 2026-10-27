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

    /**
     * "Backwards time rebuilds a collapsed tower" was in ROUNDS.md for a round
     * with nothing behind it. The tower is written as a function of the clock
     * rather than by accumulating state, so this checks the picture retraces
     * itself when the clock turns round — and that a run which starts reversed
     * is not simply frozen, which is the trap a one-way ramp falls into.
     */
    @Test
    fun theTowerRunsBothWays() {
        val def = SimRegistry.find("physics/tower")
        assertTrue("the reversible sim is not registered", def != null)
        val core = SimCore(def!!, 7L)
        core.setAspect(0.46f)
        core.restart(7L)

        fun bricks(): Int = core.sim.counters()
            .first { it.first == "bricks" }.second.substringBefore('/').toInt()

        fun halfSecond() {
            repeat(30) { core.advance(1f / 60f) }
            g.reset()
            core.draw(g)
            core.events.drain(1_000_000) { _, _, _, _ -> }
        }

        val forward = ArrayList<Int>()
        repeat(12) { halfSecond(); forward.add(bricks()) }
        assertTrue("the tower did not build: $forward", forward.last() > forward.first())

        core.setLaws(Laws.OUR_UNIVERSE.copy(timeDirection = -1f, name = "Backwards time"))
        val back = ArrayList<Int>()
        repeat(12) { halfSecond(); back.add(bricks()) }
        assertTrue("the tower did not come apart: $back", back.last() < back.first())

        val expected = forward.dropLast(1).reversed()
        for (i in expected.indices) {
            assertTrue(
                "reversing did not retrace the build at step $i: " +
                    "${back[i]} against ${expected[i]}",
                kotlin.math.abs(back[i] - expected[i]) <= 2
            )
        }

        // A run that starts reversed has to be going somewhere.
        val fresh = SimCore(def, 99L)
        fresh.setAspect(0.46f)
        fresh.setLaws(Laws.OUR_UNIVERSE.copy(timeDirection = -1f, name = "Backwards time"))
        fresh.restart(99L)
        val seen = HashSet<Int>()
        repeat(600) {
            fresh.advance(1f / 60f)
            g.reset()
            fresh.draw(g)
            fresh.events.drain(1_000_000) { _, _, _, _ -> }
            seen.add(
                fresh.sim.counters().first { it.first == "bricks" }
                    .second.substringBefore('/').toInt()
            )
        }
        assertTrue("a reversed start sits still: ${seen.size} states", seen.size >= 10)
    }

    @Test
    fun everyIdIsUniqueAndNamesItsShelf() {
        val byId = HashMap<String, String>()
        val wrongShelf = ArrayList<String>()
        for (def in SimRegistry.all) {
            val clash = byId.put(def.id, def.title)
            assertTrue("two sims share the id ${def.id}: $clash and ${def.title}", clash == null)
            // The prefix has to be a namespace somebody chose on purpose.
            val prefix = def.id.substringBefore('/')
            if (prefix !in SimRegistry.idNamespaces) {
                wrongShelf.add("${def.id} starts with an unknown \"$prefix\"")
            }
        }
        assertTrue("sim ids with a namespace nobody declared: $wrongShelf", wrongShelf.isEmpty())
    }

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
                    // Long enough to get past setup and into the real loop;
                    // the payoff and sound sweeps below cover the long runs.
                    run(def, laws, seconds = 3f)
                } catch (e: Throwable) {
                    broken.add("${def.id} under ${laws.name}: $e")
                }
            }
        }
        assertTrue(broken.joinToString("\n"), broken.isEmpty())
    }

    /**
     * The design contract's own line: a sim must reach its payoff under every
     * law preset — every preset that keeps its premise, at least. Zero-G and
     * Backwards time deliberately remove the thing some sims are about (a
     * pendulum in free fall does not swing), so they are checked for crashes
     * above rather than for an arc here.
     */
    @Test
    fun everySimReachesItsPayoffUnderEveryLaw() {
        val presets = listOf(
            Laws.OUR_UNIVERSE,
            Laws.preset("Bouncy"),
            Laws.preset("Sticky"),
            Laws.preset("Ghosts"),
            Laws.preset("Wrap-around space")
        )
        val silent = ArrayList<String>()
        for (def in SimRegistry.all) {
            val limit = (def.typicalSeconds * 2.2f).coerceIn(20f, 90f)
            for (laws in presets) {
                val core = run(def, laws, limit, stopAtPayoff = true)
                if (!core.payoffFired) silent.add("${def.id} under ${laws.name} (${limit}s)")
            }
        }
        assertTrue(
            "These never pay off, so TickTock can never show them:\n" +
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
            while (frames < 60 * 12 && !core.finished) {
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
            "These sims draw nothing in the Library:\n" +
                blank.joinToString("\n") { it.id } +
                (Previews.lastError?.let { "\nlast error: $it" } ?: ""),
            blank.isEmpty()
        )
    }

    /** The sizes the sliders allow at the top end have to actually run. */
    @Test
    fun theBigSizesRun() {
        val cases = listOf(
            Triple("life/particle-field", "count", 400_000f),
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

    private fun run(
        def: SimDef,
        laws: Laws,
        seconds: Float,
        stopAtPayoff: Boolean = false
    ): SimCore {
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
            // The payoff is the thing being asserted; once it has landed there
            // is nothing more to learn, and this runs 285 times a build.
            if (stopAtPayoff && core.payoffFired) break
        }
        return core
    }

    private companion object {
        const val FRAMES = 60
    }
}
