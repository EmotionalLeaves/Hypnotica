package app.hypnotica.core.perf

import app.hypnotica.core.render.G
import app.hypnotica.core.sim.SimCore
import app.hypnotica.sims.SimRegistry

/**
 * What this phone can actually carry.
 *
 * "Tens of thousands" is a promise about the engine, not about every handset, so
 * the app measures rather than guesses: it runs the particle field — the heaviest
 * thing it ships — for a fixed number of steps and times it. The answer sets the
 * ceiling the clip generator may randomise up to, and is shown in Settings.
 */
object Bench {

    enum class Tier(val label: String, val sizeScale: Float) {
        LOW("Modest", 0.3f),
        MID("Capable", 0.65f),
        HIGH("Fast", 1f)
    }

    data class Result(val tier: Tier, val particles: Int, val msPerFrame: Float) {
        /** What Settings shows. */
        fun describe(): String =
            "${tier.label} — $particles particles at ${format(msPerFrame)} ms a frame"

        private fun format(v: Float): String {
            val tenths = Math.round(v * 10f)
            return "${tenths / 10}.${tenths % 10}"
        }
    }

    const val PARTICLES = 40_000
    private const val STEPS = 90
    private const val HEAVY_SIM = "scale/particle-field"

    /**
     * Measure, on whatever thread calls this — never the UI one. Takes about a
     * second on a slow phone and is run once, on first launch.
     */
    fun run(): Result {
        val def = SimRegistry.find(HEAVY_SIM) ?: SimRegistry.defaultSim()
        return try {
            val core = SimCore(def, 7L)
            core.setAspect(0.46f)
            if (core.params.has("count")) core.params.set("count", PARTICLES)
            core.restart(7L)
            val g = G()
            // One warm pass, so allocation and JIT are not counted as frame cost.
            core.advanceSteps(8)
            core.events.clear()
            g.reset()
            core.draw(g)

            val started = System.nanoTime()
            var frames = 0
            while (frames < STEPS) {
                core.advanceSteps(2)
                core.events.clear()
                g.reset()
                core.draw(g)
                frames++
            }
            val ms = (System.nanoTime() - started) / 1e6f / frames
            Result(tierFor(ms), PARTICLES, ms)
        } catch (e: Throwable) {
            Result(Tier.MID, PARTICLES, 0f)
        }
    }

    /** 16.7 ms is one frame at 60 Hz; the bands leave room for the renderer. */
    fun tierFor(msPerFrame: Float): Tier = when {
        msPerFrame <= 0f -> Tier.MID
        msPerFrame < 8f -> Tier.HIGH
        msPerFrame < 22f -> Tier.MID
        else -> Tier.LOW
    }

    fun tierByName(name: String?): Tier? =
        Tier.entries.firstOrNull { it.name == name }
}
