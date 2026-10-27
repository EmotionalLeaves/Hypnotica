package app.hypnotica

import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.SimCore
import app.hypnotica.sims.SimRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tesseract turns through the fourth dimension as it ships.
 *
 * The spin-plane slider's mapping was rewritten so its two ends could not land
 * on the same axis, and the rewrite sent the shipped value to axis 1: the
 * default hypercube turned in the flat x–y plane, a spinning cube with no
 * corner ever passing through the fourth axis — the one thing the sim is for.
 * Every test passed, because each checked a slider's ends or the sim's payoff,
 * and nothing checked what the default showed. Each corner passing through the
 * extra axis is a note, so a hypercube that is really turning in four
 * dimensions makes many of them in one turn; one turning flat makes only the
 * once-a-turn beat.
 */
class DimensionTest {

    @Test
    fun theShippedHypercubeTurnsThroughItsExtraAxis() {
        for (dims in 3..6) {
            val core = SimCore(SimRegistry.all.first { it.id == "dimensions/hypercube" }, 9L)
            core.setAspect(0.56f)
            core.params.set("dims", dims)
            core.restart(9L)
            var crossings = 0
            // One turn at the shipped spin is 2*pi/0.55, about 11.4 s.
            repeat(12 * 60) {
                core.advance(1f / 60f)
                core.events.drain(100_000) { kind, _, _, _ -> if (kind == EventKind.CROSS) crossings++ }
            }
            assertTrue(
                "at $dims dimensions only $crossings corner crossings in a turn — it is not " +
                    "turning through the extra axis",
                crossings > 8
            )
        }
    }
}
