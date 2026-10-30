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

    /** Fractional steps owed to the offline path; see [advanceSeconds]. */
    private var offlineCarry = 0f
    private var slowMoRemaining = 0f

    /** Simulated time at which the slow motion began. */
    private var slowMoStartedAt = 0f

    /** How much the payoff slow-mo is currently scaling time, 1 = normal. */
    var timeScaleNow: Float = 1f
        private set

    init {
        env.laws = Laws.OUR_UNIVERSE
        sim.setup(env)
        beginWatch()
    }

    /**
     * Start recording what the sim reads as it runs. Called after every setup,
     * including the one in this constructor: a core that never begins watching
     * never settles, and the controls would fall back to the old card rule for
     * the whole run.
     */
    private fun beginWatch() {
        params.watchReads()
        watchFrames = WATCH_FRAMES
    }

    /** Frames left in which to record what the sim reads as it runs. */
    private var watchFrames = 0

    fun setLaws(laws: Laws) {
        env.laws = laws
    }

    fun setPalette(p: Palette) {
        env.palette = p
    }

    fun setAspect(aspect: Float) {
        env.aspect = aspect
    }

    /**
     * How much longer a finished run should be left on screen than the usual
     * resolve beat, in seconds.
     *
     * Most sims hold their finished picture for a second and a half before
     * they report finished; some — the spirograph, circle packing — report it
     * on the frame of their payoff, so their finished picture got a single
     * second and was gone. This tops the hold up to the same length for every
     * sim, counted from the payoff, and adds nothing to one that held already.
     */
    fun extraHoldSeconds(): Float {
        if (!finished) return 0f
        val since = if (payoffFired) kotlin.math.abs(env.time) - payoffAt else HOLD_SECONDS
        return (HOLD_SECONDS - since).coerceIn(0f, HOLD_SECONDS)
    }

    /**
     * How far the view pulls back so the whole sim is on screen: 1 when it
     * already fits, less when it reaches past the edges. See [Sim.frameHalfWidth].
     */
    fun fit(): Float {
        var f = 1f
        val w = sim.frameHalfWidth(env)
        if (w.isFinite() && w > env.halfWidth && env.halfWidth > 0f) f = kotlin.math.min(f, env.halfWidth / w)
        val h = sim.frameHalfHeight(env)
        if (h.isFinite() && h > 1f) f = kotlin.math.min(f, 1f / h)
        // Closing in, only for a sim that asks and needs no pulling back.
        val focus = sim.focus(env)
        if (f >= 1f && focus.isFinite() && focus > 0f) {
            f = (kotlin.math.min(env.halfWidth, 1f) / focus).coerceIn(1f, MAX_ZOOM)
        }
        return f.coerceIn(MIN_FIT, MAX_ZOOM)
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
        offlineCarry = 0f
        slowMoRemaining = 0f
        slowMoStartedAt = 0f
        timeScaleNow = 1f
        payoffFired = false
        payoffAt = -1f
        finished = false
        stepCount = 0L
        sim.setup(env)
        // From here on, what the sim reads is what it reads live. Anything it
        // only touched inside setup() above cannot be changed without building
        // the run again, and the controls need to know which is which.
        beginWatch()
    }

    /**
     * Wall-clock time a frame's steps may take, in nanoseconds; 0 for no limit.
     * Live play sets it. Anything that must reproduce a run step for step
     * leaves it at 0, because a limit tied to the clock is not reproducible.
     */
    @Volatile
    var stepBudgetNanos = 0L

    /** Advance by one display frame. */
    fun advance(frameSeconds: Float) {
        if (paused || finished) return

        // Only a frame the sim actually ran counts: a paused run reads nothing,
        // and settling on that would mark every value as setup-only.
        if (watchFrames > 0) {
            watchFrames--
            if (watchFrames == 0) params.settleReads()
        }

        val laws = env.laws
        var scale = speed * laws.timeScale * laws.timeDirection

        // How far into the slow motion we are, in SIMULATED seconds.
        //
        // This used to count down in real frame seconds. That was fine while
        // the step size was fixed, but the step size now follows it — and a
        // step size derived from wall-clock time would differ between a 60 Hz
        // phone, a 120 Hz phone and a 30 fps export, so the same seed would
        // integrate differently on each. Simulated time is the same number
        // everywhere, so the run stays reproducible.
        if (slowMoRemaining > 0f) {
            val since = kotlin.math.abs(env.time) - slowMoStartedAt
            slowMoRemaining = max(0f, SLOWMO_SIM_SECONDS - since)
            // Ease back out of slow motion instead of snapping.
            val t = 1f - (slowMoRemaining / SLOWMO_SIM_SECONDS).coerceIn(0f, 1f)
            val eased = SLOWMO_FACTOR + (1f - SLOWMO_FACTOR) * (t * t)
            scale *= eased
            timeScaleNow = eased
        } else {
            timeScaleNow = 1f
        }

        // Finer steps while time is slow, which is what makes slow motion worth
        // watching.
        //
        // The step was a fixed 1/120 of a simulated second however slowly time
        // was running, so at a quarter speed the picture only moved thirty
        // times a real second — the sim stepped on every other frame and held
        // still on the rest. The payoff, the one moment the app slows down for,
        // was the choppiest thing on screen. Scaling the step by the same
        // factor as time keeps the number of steps per REAL second constant, so
        // this is smoother at no cost: four times the detail through the
        // payoff, the same work per second as always.
        val dt = (timeScaleNow / stepHz).coerceAtLeast(MIN_STEP_SECONDS)
        accumulator += min(frameSeconds, MAX_FRAME_SECONDS) * scale

        // The budget follows what was asked for: a fixed 16 steps capped the
        // speed slider at about 8x however far it was dragged.
        val wanted = (kotlin.math.abs(accumulator) / dt).toInt() + 2
        var budget = wanted.coerceIn(MAX_STEPS_PER_FRAME, MAX_STEPS_BURST)
        val began = if (stepBudgetNanos > 0L) System.nanoTime() else 0L
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
                // How far into the run it landed. Backwards time makes env.time
                // negative, and a negative payoff time looks like "never" to the
                // clip director.
                payoffAt = kotlin.math.abs(env.time)
                slowMoStartedAt = payoffAt
                slowMoRemaining = SLOWMO_SIM_SECONDS
                events.emit(EventKind.DONE, 1f, 0f, 1f)
            }
            if (sim.finished() && heldLongEnough()) {
                finished = true
                break
            }
            // Out of time for this frame: let the run fall behind rather than
            // try to catch up. Catching up is the spiral — a heavy frame asks
            // for more steps next frame, which is heavier still — and at forty
            // thousand pendulums or a 200-square cloth it held the phone at a
            // few frames a second. Behind, it plays in slow motion at a steady
            // rate instead.
            if (stepBudgetNanos > 0L && System.nanoTime() - began > stepBudgetNanos) {
                budget = 0
                break
            }
        }
        if (budget == 0) accumulator = 0f
    }

    /**
     * Whether a run that says it is finished has had its resolve beat.
     *
     * A run is set up, builds, pays off — and the resolve, the settled picture
     * after the moment, is a fifth of it by the arc. Measured across the
     * catalogue, some runs ended the very step they paid off (circle packing)
     * or a second after, and the next began before the moment had landed. The
     * picture after a payoff is held at least [MIN_RESOLVE_SECONDS] whatever
     * the sim says; a run that ends without paying off is not held.
     */
    private fun heldLongEnough(): Boolean =
        !payoffFired || kotlin.math.abs(env.time) - payoffAt >= MIN_RESOLVE_SECONDS

    /**
     * Run exactly n steps with no rendering.
     *
     * The timestep is the same fixed one the live loop uses — a law that slows
     * time changes how many steps a second of wall clock buys, not how big each
     * step is, or the integration would follow a different trajectory from the
     * one that was previewed. [advanceSeconds] does that conversion.
     */
    fun advanceSteps(n: Int) {
        val dt = (1f / stepHz) * env.laws.timeDirection
        var i = 0
        while (i < n && !finished) {
            params.tick(env.time)
            sim.step(env, dt)
            env.time += dt
            stepCount++
            if (!payoffFired && sim.payoff()) {
                payoffFired = true
                // How far into the run it landed. Backwards time makes env.time
                // negative, and a negative payoff time looks like "never" to the
                // clip director.
                payoffAt = kotlin.math.abs(env.time)
                // The same chord the live loop emits: the offline render reads
                // this stream, and without it an exported clip loses its payoff.
                events.emit(EventKind.DONE, 1f, 0f, 1f)
            }
            if (sim.finished() && heldLongEnough()) finished = true
            i++
        }
    }

    /**
     * Advance by a span of wall-clock seconds, as the live loop would: the laws'
     * time scale and the given speed set how many fixed steps that is, and the
     * remainder is carried, so a speed under half a step a frame is not rounded
     * away to a frozen picture.
     */
    fun advanceSeconds(seconds: Float, speed: Float = 1f) {
        val laws = env.laws
        val scale = if (laws.timeScale > 0f) laws.timeScale else 1f
        offlineCarry += seconds * stepHz * speed.coerceAtLeast(0f) * scale
        val steps = offlineCarry.toInt()
        if (steps <= 0) return
        offlineCarry -= steps
        advanceSteps(steps)
    }

    fun draw(g: G) {
        val alpha = if (stepHz <= 0f) 0f else
            (kotlin.math.abs(accumulator) * stepHz).coerceIn(0f, 1f)
        sim.draw(env, g, alpha)
    }

    fun onInput(e: TouchEvent) = sim.onInput(env, e)

    fun hero() = sim.hero(env)

    companion object {
        /** The least time the picture after a payoff is held before the run may end. */
        const val MIN_RESOLVE_SECONDS = 4f

        /**
         * How many running frames to watch before deciding what a sim reads
         * live. More than one because a sim may read a value only on a tick it
         * does not take every frame, and because draw() runs after advance().
         */
        const val WATCH_FRAMES = 30
        /** The finished picture's time on screen, before the resolve beat. */
        const val HOLD_SECONDS = 1.5f
        /** No sim is pulled back further than this, whatever it asks for. */
        const val MIN_FIT = 0.2f
        /** The closest a sim's [Sim.focus] can bring the view. */
        const val MAX_ZOOM = 4f
        /**
         * How long the slow motion lasts, in simulated seconds.
         *
         * Real time is longer, because time is running slowly: about a second
         * and a half at the quarter speed it starts from, easing back to
         * normal. Measured rather than guessed — see the probe in the commit.
         */
        const val SLOWMO_SIM_SECONDS = 0.55f

        /** A floor on the step, so a very slow setting cannot stall the run. */
        const val MIN_STEP_SECONDS = 1f / 2000f
        const val SLOWMO_FACTOR = 0.25f
        const val MAX_FRAME_SECONDS = 0.05f
        const val MAX_STEPS_PER_FRAME = 16

        /** Ceiling for one frame, so a stall cannot cascade into a freeze. */
        const val MAX_STEPS_BURST = 48
    }
}
