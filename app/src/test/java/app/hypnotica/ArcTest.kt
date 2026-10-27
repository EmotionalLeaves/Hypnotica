package app.hypnotica

import app.hypnotica.core.director.ClipDirector
import app.hypnotica.core.sim.SimCore
import app.hypnotica.sims.SimRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The payoff has to land inside the run it belongs to.
 *
 * The design doc lays out a four-beat arc — setup 10%, build 60%, payoff 10%,
 * resolve 20% — so the moment belongs around 70% of the way through. Nothing
 * held the sims to it, because `typicalSeconds` governed nothing: it is
 * documented as "used by TickTock and Studio templates" and was read by
 * neither, so the declared lengths drifted for as long as the app has existed.
 * Measured across the set, the payoff landed anywhere from 5% of the declared
 * length to 142% of it — at one end slow motion, a punch-in and a fourteen-note
 * sweep 1.2 seconds in with twenty-five seconds left and nothing to reach; at
 * the other a sim whose declared run is over eight seconds before the thing it
 * was building to.
 *
 * It governs two real things now: the window TickTock's pre-check accepts a
 * seed in, and where Studio's "Build-up to payoff" template puts its keyframes.
 * This test is what stops it drifting again. The band is the director's own, so
 * the test cannot disagree with the app.
 */
class ArcTest {

    @Test
    fun everySimPaysOffInsideItsOwnWindow() {
        val director = ClipDirector(1L)
        val wrong = ArrayList<String>()
        for (def in SimRegistry.all) {
            val window = director.payoffWindow(def)
            val hits = ArrayList<Float>()
            for (s in 0 until SEEDS) {
                val seed = 4000L + s * 6361L
                val core = SimCore(def, seed)
                core.setAspect(0.46f)
                var guard = 0
                // Three times the declared length: a payoff that needs longer
                // than that is "never" as far as someone watching is concerned.
                val limit = def.typicalSeconds * 3f
                while (core.env.time < limit && !core.payoffFired && guard < GUARD) {
                    core.advance(1f / 60f)
                    guard++
                }
                if (core.payoffFired) hits.add(core.payoffAt)
            }
            // One seed in five may genuinely not get there — Game of Life
            // stabilises after three seconds on one seed and fifty on another,
            // and throwing that seed away is exactly what TickTock's pre-check
            // is for. Never getting there at all is RegistryTest's business.
            if (hits.size <= SEEDS / 2) {
                wrong.add("${def.id}: only ${hits.size}/$SEEDS seeds paid off")
                continue
            }
            // The median, not the worst: one unlucky seed landing outside the
            // window is what the pre-check is for, and TickTock throws it away.
            val median = hits.sorted()[hits.size / 2]
            if (median !in window) {
                wrong.add(
                    String.format(
                        java.util.Locale.US,
                        "%s: pays off at %.1fs (%.0f%% of the %.0fs it declares), " +
                            "outside %.1f-%.1fs",
                        def.id, median, median / def.typicalSeconds * 100f,
                        def.typicalSeconds, window.start, window.endInclusive
                    )
                )
            }
        }
        assertTrue(
            "The payoff is in the wrong place on ${wrong.size} sim(s). Either the " +
                "sim's payoff condition is wrong or its typicalSeconds is:\n" +
                wrong.joinToString("\n"),
            wrong.isEmpty()
        )
    }

    private companion object {
        const val SEEDS = 5
        const val GUARD = 500_000
    }
}
