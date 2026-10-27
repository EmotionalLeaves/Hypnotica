package app.hypnotica.core.sim

import app.hypnotica.core.laws.Laws
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import kotlin.math.max
import kotlin.math.min

/**
 * Runs one sim on a fixed timestep.
 *
 * The step rate is constant no matter what the display does, so a run is
 * reproducible from its seed. Frames in between steps are interpolated, which is
 * what keeps motion smooth on a 90 or 120 Hz screen.
 */
class SimCore(def: SimDef, seed: Long = 1L) {

    var def: SimDef = def
        private set

    var seed: Long = seed
        private set

    val events = EventQueue()
    val rng = Rng(seed)
    var params = def.newParamValues()
        private set

    var sim: Sim = def.create()
        private set

    val env = SimEnv(rng, Laws.OUR_UNIVERSE, params, events)

    /** Steps per simulated second. */
    var stepHz: Float = 120f

    /** User speed multiplier, 0.1x .. 10x. */
    var speed: Float = 1f

    var paused: Boolean = false

    /** Set true once the sim's payoff has fired; drives the slow-mo and the sweep. */
    var payoffFired: Boolean = false
        private set

    var payoffAt: Float = -1f
        private set

    var finished: Boolean = false
        private set

    /** Steps actually taken, for the determinism tests. */
    var stepCount: Long = 0L
        private set

    private var accumulator = 0f
    private var slowMoRemaining = 0f

    /** How much the payoff slow-mo is currently scaling time, 1 = normal. */
    var timeScaleNow: Float = 1f
        private set

    init {
        env.laws = Laws.OUR_UNIVERSE
        sim.setup(env)
    }

    fun setLaws(laws: Laws) {
        env.laws = laws
    }

    fun setPalette(p: Palette) {
        env.palette = p
    }

    fun setAspect(aspect: Float) {
        env.aspect = aspect
    }

    /** Swap in a different sim, keeping the player's speed and laws. */
    fun load(newDef: SimDef, newSeed: Long = seed) {
        def = newDef
        params = newDef.newParamValues()
        env.params = params
        restart(newSeed)
    }

    /**
     * Start the run again. Parameters survive on purpose: asking for a new seed
     * should not undo everything the person just set up.
     */
    fun restart(newSeed: Long = seed) {
        seed = newSeed
        rng.reseed(newSeed)
        events.clear()
        sim = def.create()
        env.time = 0f
        accumulator = 0f
        slowMoRemaining = 0f
        timeScaleNow = 1f
        payoffFired = false
        payoffAt = -1f
        finished = false
        stepCount = 0L
        sim.setup(env)
    }

    /** Advance by one display frame. */
    fun advance(frameSeconds: Float) {
        if (paused || finished) return

        val laws = env.laws
        var scale = speed * laws.timeScale * laws.timeDirection

        if (slowMoRemaining > 0f) {
            slowMoRemaining = max(0f, slowMoRemaining - frameSeconds)
            // Ease back out of slow motion instead of snapping.
            val t = 1f - (slowMoRemaining / SLOWMO_SECONDS).coerceIn(0f, 1f)
            val eased = SLOWMO_FACTOR + (1f - SLOWMO_FACTOR) * (t * t)
            scale *= eased
            timeScaleNow = eased
        } else {
            timeScaleNow = 1f
        }

        val dt = 1f / stepHz
        accumulator += min(frameSeconds, MAX_FRAME_SECONDS) * scale

        var budget = MAX_STEPS_PER_FRAME
        while (kotlin.math.abs(accumulator) >= dt && budget > 0) {
            val signedDt = if (accumulator > 0f) dt else -dt
            params.tick(env.time)
            sim.step(env, signedDt)
            env.time += signedDt
            accumulator -= signedDt
            stepCount++
            budget--

            if (!payoffFired && sim.payoff()) {
                payoffFired = true
                payoffAt = env.time
                slowMoRemaining = SLOWMO_SECONDS
                events.emit(EventKind.DONE, 1f, 0f, 1f)
            }
            if (sim.finished()) {
                finished = true
                break
            }
        }
        if (budget == 0) accumulator = 0f
    }

    /** Run n steps with no rendering. Used by the payoff pre-check and offline render. */
    fun advanceSteps(n: Int) {
        val dt = 1f / stepHz
        var i = 0
        while (i < n && !finished) {
            params.tick(env.time)
            sim.step(env, dt)
            env.time += dt
            stepCount++
            if (!payoffFired && sim.payoff()) {
                payoffFired = true
                payoffAt = env.time
            }
            if (sim.finished()) finished = true
            i++
        }
    }

    fun draw(g: G) {
        val alpha = if (stepHz <= 0f) 0f else
            (kotlin.math.abs(accumulator) * stepHz).coerceIn(0f, 1f)
        sim.draw(env, g, alpha)
    }

    fun onInput(e: TouchEvent) = sim.onInput(env, e)

    companion object {
        const val SLOWMO_SECONDS = 1.0f
        const val SLOWMO_FACTOR = 0.25f
        const val MAX_FRAME_SECONDS = 0.05f
        const val MAX_STEPS_PER_FRAME = 16
    }
}
