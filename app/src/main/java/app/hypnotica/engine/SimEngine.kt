package app.hypnotica.engine

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import app.hypnotica.core.audio.SoundMapping
import app.hypnotica.core.audio.SoundPreset
import app.hypnotica.core.audio.Song
import app.hypnotica.core.audio.Synth
import app.hypnotica.core.laws.Laws
import app.hypnotica.core.render.Camera
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.render.SceneRenderer
import app.hypnotica.core.safety.FlashLimiter
import app.hypnotica.core.safety.SafetySettings
import app.hypnotica.core.sim.SimCore
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.sim.TouchEvent
import app.hypnotica.core.studio.Project
import app.hypnotica.core.studio.TrackKind
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min

/**
 * Holds one running simulation and everything around it: the renderer, the
 * synth, the camera and the safety limits.
 *
 * The sim steps on the GL thread, immediately before the frame that draws it, so
 * there is no copy and no race between stepping and drawing. The synth keeps its
 * own thread, fed through the event queue.
 */
class SimEngine(initial: SimDef) : GLSurfaceView.Renderer {

    val core = SimCore(initial, System.nanoTime())
    val scene = SceneRenderer()
    val synth = Synth()
    val mapping = SoundMapping(synth)
    val flashLimiter = FlashLimiter()
    val camera = Camera()
    val safety = SafetySettings()

    /** Set by the activity; null in tests and in the offline exporter. */
    @Volatile
    var haptics: Haptics? = null

    /** Drift the sim's own parameters slowly while it plays. */
    @Volatile
    var evolve: Boolean = false

    private var evolveClock = 0f

    /** Values a person just dragged; Evolve stands off them for a moment. */
    private val driftHold =
        app.hypnotica.core.recipe.DriftHold(DRIFT_HOLD_NANOS)

    /** Set while the person is recording their moves in the player. */
    @Volatile
    var recorder: app.hypnotica.core.studio.MoveRecorder? = null

    private val pendingTouches = java.util.concurrent.ConcurrentLinkedQueue<TouchEvent>()

    /** The camera plus the shake, so the shake never touches the real one. */
    private val shaken = Camera()

    private val g = G()
    private var lastFrameNanos = 0L

    /** Frames per second, smoothed, for the performance badge. */
    @Volatile
    var fps: Float = 0f
        private set

    @Volatile
    var running: Boolean = true

    /** Set from the UI thread; applied on the next frame. */
    @Volatile
    private var pendingLoad: Pair<SimDef, Long>? = null

    @Volatile
    private var keepLookOnLoad = false

    @Volatile
    private var pendingRestart: Long? = null

    @Volatile
    private var pendingParams: Pair<Map<String, Float>, Long>? = null

    @Volatile
    var onPayoff: (() -> Unit)? = null

    @Volatile
    var onFinished: (() -> Unit)? = null

    private var payoffNotified = false

    /** The engine's own flash for the payoff frame, before the limiter sees it. */
    private var payoffFlash = 0f
    private var finishNotified = false

    /** Extra zoom applied around the payoff, the "punch-in". */
    private var punch = 0f
    private var baseZoom = 1f

    /** Slow idle zoom, so a sim that barely moves is not a still photograph. */
    private var breath = 0f

    /** Decaying offset applied after a heavy impact. */
    private var shake = 0f
    private var shakeSeed = 0
    private var shakeOffsetX = 0f
    private var shakeOffsetY = 0f

    // ------------------------------------------------------------- studio

    /** When set, the timeline drives the camera, speed, laws and look. */
    @Volatile
    var project: Project? = null
        set(value) {
            // The camera as the person left it, so Studio can give it back.
            // A timeline drives the camera every frame while it is attached,
            // and detaching used to restore the laws, the trails and the speed
            // but not the view — so after loading the orbit template and
            // scrubbing, every other screen was left upside down with no way
            // back but a two-finger twist by eye.
            if (value != null && field == null) {
                beforeProject.copyFrom(camera)
                hasCameraToRestore = true
            }
            field = value
        }

    private val beforeProject = Camera()
    private var hasCameraToRestore = false

    /**
     * True once the person has moved the view by hand in Studio, until the
     * timeline is given it back — by playing, scrubbing, or keying the pose.
     */
    @Volatile
    var cameraPosed = false
        private set

    /** Called by the gestures: from here on the pose is the person's. */
    fun poseCamera() {
        if (project != null) cameraPosed = true
    }

    /** Give the view back to the timeline. */
    fun releaseCamera() {
        cameraPosed = false
    }

    @Volatile
    var studioTime: Float = 0f

    @Volatile
    var studioPlaying: Boolean = false

    @Volatile
    private var pendingSeek: Float? = null

    /** Steps still owed to the seek in progress, spread over several frames. */
    private var seekRemaining = 0

    /** True while a scrub is still catching up, so the UI can say so. */
    @Volatile
    var seeking: Boolean = false
        private set

    /**
     * Move the playhead. The run is deterministic, so the state at any moment is
     * found by starting again from the seed and stepping forward; there is no
     * snapshot to go stale.
     */
    fun seek(seconds: Float) {
        pendingSeek = seconds.coerceAtLeast(0f)
    }

    /** Called when Studio closes, to undo anything the timeline was driving. */
    fun detachProject(laws: Laws, trails: Float, speed: Float) {
        project = null
        studioPlaying = false
        seekRemaining = 0
        seeking = false
        core.setLaws(laws)
        scene.trails = trails
        core.speed = speed
        if (hasCameraToRestore) {
            camera.copyFrom(beforeProject)
            baseZoom = beforeProject.zoom
            hasCameraToRestore = false
        }
    }

    init {
        // Every event that is heard is also felt, as far as the motor allows.
        mapping.onHaptic = { strength ->
            if (safety.haptics) {
                val h = haptics
                if (strength >= 0.65f) h?.medium() else h?.light()
            }
        }
        mapping.safety = safety
        mapping.applyPreset(SoundPreset.byName(initial.defaultSound))
        core.setPalette(Palette.byName(initial.defaultPalette))
    }

    /**
      * Queue a sim for the GL thread.
      *
      * [keepLook] is for a caller that has already chosen the palette and the
      * sound for this run — a generated clip, say. Without it the load applied
      * the sim's own defaults a frame later and quietly threw the choice away,
      * so half of TickTock's clips were meant to arrive in another palette and
      * none of them ever did.
      */
    fun loadSim(def: SimDef, seed: Long = System.nanoTime(), keepLook: Boolean = false) {
        keepLookOnLoad = keepLook
        pendingLoad = def to seed
    }

    fun restart(seed: Long = System.nanoTime()) {
        pendingRestart = seed
    }

    /**
     * Apply a whole parameter set and restart, as one atomic change on the GL
     * thread. Used when a generated clip or a saved state is loaded, so the run
     * matches exactly what was checked.
     */
    fun applyParams(values: Map<String, Float>, seed: Long) {
        pendingParams = values to seed
    }

    fun setLaws(laws: Laws) = core.setLaws(laws)

    fun setPalette(name: String) = core.setPalette(Palette.byName(name))

    fun setSoundPreset(name: String) = mapping.applyPreset(SoundPreset.byName(name))

    fun setSong(song: Song) {
        mapping.song = if (song.notes.isEmpty()) null else song
        mapping.resetSong()
    }

    fun setTrails(amount: Float) {
        scene.trails = amount.coerceIn(0f, 0.97f)
    }

    fun startAudio() = synth.start()

    fun stopAudio() = synth.stop()

    /**
     * Queue a touch. It is handed to the sim at the top of the next frame rather
     * than straight away: this is called from the UI thread, and a sim that grew
     * its arrays mid-step would be writing underneath the step that is running.
     */
    fun onTouch(e: TouchEvent) {
        if (pendingTouches.size < MAX_PENDING_TOUCHES) pendingTouches.add(e)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        scene.surfaceCreated()
        flashLimiter.reset()
        lastFrameNanos = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        scene.surfaceChanged(width, height)
        core.setAspect(width.toFloat() / max(1, height).toFloat())
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        var dt = if (lastFrameNanos == 0L) 1f / 60f else (now - lastFrameNanos) / 1e9f
        lastFrameNanos = now
        dt = dt.coerceIn(1f / 480f, 0.1f)
        fps = if (fps <= 0f) 1f / dt else fps * 0.9f + (1f / dt) * 0.1f

        // One guard over the whole frame.
        //
        // This runs on the GLSurfaceView's own thread, and an uncaught throw
        // there goes to the default handler and takes the process with it.
        // Worse, the last sim loaded is written to preferences and restored on
        // launch, so the sim that killed the app was the sim the next launch
        // opened on: one bad frame and the app could not be started again
        // except by clearing its data, which takes the favourites, the saved
        // recipes and everything else with it. A recipe is user-written Lua,
        // so that was reachable without touching this repo.
        //
        // Now the sim is halted and said so, and the run is not restored.
        if (halted != null) return
        try {
            drawFrame(dt)
        } catch (t: Throwable) {
            halted = cleanHaltMessage(t)
            onHalt?.invoke(halted ?: "")
        }
    }

    /** The frame's work. Called only from onDrawFrame, inside its guard. */
    private fun drawFrame(dt: Float) {
        pendingLoad?.let {
            pendingLoad = null
            val keepLook = keepLookOnLoad
            keepLookOnLoad = false
            core.load(it.first, it.second)
            if (!keepLook) {
                core.setPalette(Palette.byName(it.first.defaultPalette))
                mapping.applyPreset(SoundPreset.byName(it.first.defaultSound))
            }
            mapping.resetSong()
            resetRunState()
        }
        pendingParams?.let { (values, seed) ->
            pendingParams = null
            for ((k, v) in values) core.params.set(k, v)
            core.restart(seed)
            resetRunState()
        }
        pendingRestart?.let {
            pendingRestart = null
            core.restart(it)
            resetRunState()
        }

        // Touches arrive on the UI thread and are applied here, between frames.
        while (true) {
            val touch = pendingTouches.poll() ?: break
            core.onInput(touch)
        }

        core.env.reduceMotion = safety.reduceMotion
        flashLimiter.reduceFlashing = safety.reduceFlashing

        val proj = project
        var timelineFlash = 0f
        var frozen = false

        if (proj != null) {
            pendingSeek?.let { target ->
                pendingSeek = null
                cameraPosed = false
                core.restart(core.seed)
                resetRunState()
                seekRemaining = (target * core.stepHz).toInt().coerceIn(0, MAX_SEEK_STEPS)
                studioTime = target
            }
            // A seek re-simulates from the seed. Five minutes of sim is 36,000
            // steps, which cannot happen inside one frame without freezing the
            // screen, so it is paid off a slice at a time.
            if (seekRemaining > 0) {
                val chunk = min(seekRemaining, SEEK_STEPS_PER_FRAME)
                core.advanceSteps(chunk)
                core.events.clear()
                seekRemaining -= chunk
            }
            seeking = seekRemaining > 0

            if (studioPlaying) cameraPosed = false
            if (studioPlaying && !seeking) {
                studioTime += dt
                if (studioTime >= proj.duration) {
                    studioTime = 0f
                    pendingSeek = 0f
                }
            }
            frozen = proj.isFrozenAt(studioTime)
            // While the view has been posed by hand, the timeline leaves it
            // alone so the pose can be keyed. Driving the camera from the
            // tracks on every frame undid a twist before the next draw, which
            // meant the Key button could only ever re-stamp the value the
            // timeline was already producing: eight of the nine tracks could
            // not be authored by hand at all.
            if (!cameraPosed) proj.applyCamera(studioTime, camera)
            // An empty track means "leave this alone": its default would
            // otherwise overwrite the speed, trails or laws the person chose.
            proj.track(TrackKind.SPEED).let { if (!it.isEmpty()) core.speed = it.valueAt(studioTime) }
            proj.track(TrackKind.TRAILS).let { if (!it.isEmpty()) scene.trails = it.valueAt(studioTime) }
            val gravity = proj.track(TrackKind.GRAVITY)
            val bounce = proj.track(TrackKind.BOUNCINESS)
            if (!gravity.isEmpty() || !bounce.isEmpty()) {
                core.setLaws(
                    core.env.laws.copy(
                        gravity = if (gravity.isEmpty()) core.env.laws.gravity
                        else gravity.valueAt(studioTime),
                        restitution = if (bounce.isEmpty()) core.env.laws.restitution
                        else bounce.valueAt(studioTime)
                    )
                )
            }
            timelineFlash = proj.track(TrackKind.FLASH).valueAt(studioTime)
        }

        if (evolve && proj == null) drift(dt)

        if (proj == null) {
            recorder?.feed(dt, camera, core.speed, scene.trails)
        }

        if (running && !frozen && !seeking && (proj == null || studioPlaying)) core.advance(dt)

        if (core.payoffFired && !payoffNotified) {
            payoffNotified = true
            punch = if (safety.reduceMotion) 0f else 1f
            shake = if (safety.reduceMotion) 0f else 1f
            // The engine performs the payoff — DESIGN says so, and so does rule
            // 10: slow motion, a punch-in, a sweep, a flash and a heavy haptic,
            // none of which a sim implements. Four of those five were here; the
            // flash was left entirely to each sim's own request, and measured,
            // 31 of the 58 never make one. So more than half the payoffs in the
            // app were missing a fifth of what they promise. The limiter and
            // Reduce flashing still decide what reaches the screen, and a sim
            // that asks for its own is max()'d with this rather than replaced.
            payoffFlash = PAYOFF_FLASH
            mapping.finalSweep()
            if (safety.haptics) haptics?.heavy()
            onPayoff?.invoke()
        }
        if (core.finished && !finishNotified) {
            finishNotified = true
            onFinished?.invoke()
        }

        // Punch-in: a small, quick push towards the action when the payoff lands.
        // In Studio the timeline owns the camera, so the automatic push stays out.
        if (punch > 0f) {
            punch = max(0f, punch - dt / PUNCH_SECONDS)
        }
        if (shake > 0f) {
            shake = max(0f, shake - dt / SHAKE_SECONDS)
        }
        breath += dt * BREATH_HZ * 6.2832f
        if (breath > 6.2832f) breath -= 6.2832f

        if (proj == null) {
            // Camera breath: under one percent, slow enough to be felt rather
            // than seen, and off entirely when Reduce motion is on.
            val breathing =
                if (safety.reduceMotion) 1f else 1f + BREATH_DEPTH * kotlin.math.sin(breath)
            // Squared, so the zoom comes back quickly and then settles.
            //
            // This was linear, and it was the one part of the payoff that was:
            // the slow motion eases out, the shake eases out, the flash fades.
            // A constant-rate ramp is the curve the eye reads as mechanical,
            // and because it ran 1.11 s it was also the last thing still
            // moving — holding the frame for a tenth of a second after time
            // was back to normal and the sweep had finished, which is what
            // made the payoff read as three twitches and a drift rather than
            // one event. At 0.8 s squared, all five parts end inside a second.
            val punchEase = punch * punch
            camera.zoom = baseZoom * breathing * (1f + 0.045f * punchEase)

            // Screen shake: a few pixels for a moment, on the heaviest frames.
            // Added to the camera at render time rather than written into it,
            // so a pan during the shake is kept rather than snapped back.
            if (shake > 0f) {
                shakeSeed = shakeSeed * 1103515245 + 12345
                val a = ((shakeSeed shr 16) and 0xFF) / 255f - 0.5f
                val b = ((shakeSeed shr 8) and 0xFF) / 255f - 0.5f
                // Divided by the zoom, because this offset is in world units
                // and the renderer multiplies it by the zoom on the way to the
                // screen. DESIGN asks for "a few pixels of world", and at zoom
                // 1 that is about fourteen — but the pinch reaches 40x, where
                // the same constant was two hundred times the intended
                // amplitude: a lurch rather than a tick.
                val amount = SHAKE_WORLD * shake * shake / camera.zoom.coerceAtLeast(0.05f)
                shakeOffsetX = a * amount
                shakeOffsetY = b * amount
            } else {
                shakeOffsetX = 0f
                shakeOffsetY = 0f
            }
        }

        g.reset()
        g.background = Palette.BG
        core.draw(g)

        mapping.pump(core.events, dt)

        val allowed =
            flashLimiter.allow(max(max(g.flashRequest, timelineFlash), payoffFlash), dt)
        // Asked for on the payoff frame only; the limiter owns the fade.
        payoffFlash = 0f
        if (shakeOffsetX != 0f || shakeOffsetY != 0f) {
            shaken.copyFrom(camera)
            shaken.x += shakeOffsetX
            shaken.y += shakeOffsetY
            scene.render(g, shaken, allowed, dt)
        } else {
            scene.render(g, camera, allowed, dt)
        }
    }

    /**
     * Nudge the live parameters along a slow sine, each on its own phase.
     * Data-card values are left alone: changing those mid-run would mean
     * rebuilding the sim underneath itself.
     */
    /**
     * Why the sim stopped, or null while it is running.
     *
     * Set once, from the frame guard. Nothing retries on its own: a throw that
     * reproduces every frame would be a stream of identical failures, so the
     * engine stands still and the screen offers a way out.
     */
    @Volatile
    var halted: String? = null
        private set

    /** Told when a sim halts, so the run is not restored on the next launch. */
    var onHalt: ((String) -> Unit)? = null

    /** Start drawing again, after the caller has loaded something else. */
    fun clearHalt() {
        halted = null
    }

    /**
     * A throw, in words a person can use.
     *
     * Java class names are noise to someone who wrote a recipe in Lua, and the
     * message is the part that says what happened.
     */
    private fun cleanHaltMessage(t: Throwable): String {
        val message = t.message?.takeIf { it.isNotBlank() }
        val kind = t::class.java.simpleName
            .replace(Regex("Exception$|Error$"), "")
            .replace(Regex("(?<=.)([A-Z])"), " $1")
            .lowercase()
        val text = if (message != null) message else "an $kind"
        return text.replace(Regex("java\\.lang\\.|java\\.util\\.|kotlin\\."), "")
            .take(200)
    }

    /** Called when a person moves a value by hand: Evolve stands off it. */
    fun holdFromDrift(key: String) {
        driftHold.touch(key, System.nanoTime())
    }

    private fun drift(dt: Float) {
        evolveClock += dt
        if (evolveClock < 0.25f) return
        evolveClock = 0f
        val schedule = core.def.params
        for ((index, p) in schedule.withIndex()) {
            if (p.card == app.hypnotica.core.recipe.Card.DATA) continue
            if (p.type != app.hypnotica.core.recipe.ParamType.FLOAT) continue
            // A formula the person typed outranks the drift.
            if (core.params.hasExpression(p.key)) continue
            // So does a hand that just moved this one.
            if (driftHold.holds(p.key, System.nanoTime())) continue
            val phase = index * 1.37f
            val wave = kotlin.math.sin(core.env.time * 0.08f + phase) * 0.5f + 0.5f
            val span = p.max - p.min
            val target = p.min + span * (0.25f + 0.5f * wave)
            val current = core.params.f(p.key, p.default)
            core.params.set(p.key, current + (target - current) * 0.06f)
        }
    }

    private fun resetRunState() {
        driftHold.clear()
        payoffNotified = false
        finishNotified = false
        punch = 0f
        shake = 0f
        shakeOffsetX = 0f
        shakeOffsetY = 0f
        flashLimiter.reset()
        synth.allOff()
    }

    fun setBaseZoom(z: Float) {
        baseZoom = z.coerceIn(0.2f, 40f)
        // With a project attached the breathing path below is skipped, so the
        // pinch would never reach the camera and zoom could not be posed.
        if (project != null) {
            camera.zoom = baseZoom
            cameraPosed = true
        }
    }

    fun baseZoom(): Float = baseZoom

    fun release() {
        synth.stop()
        scene.release()
    }

    /** Pixels-per-world, used to turn a drag in pixels into sim coordinates. */
    fun worldFromScreen(px: Float, py: Float): Pair<Float, Float> {
        val w = scene.viewportWidth.toFloat()
        val h = scene.viewportHeight.toFloat()
        if (w <= 1f || h <= 1f) return 0f to 0f
        val ndcX = px / w * 2f - 1f
        val ndcY = 1f - py / h * 2f
        val aspect = w / h
        // Undo the camera's own transform in reverse: scale, then rotate back.
        // The rotation was missing, so once the view was twisted every tap,
        // drag and long-press landed somewhere else — most of a screen away at
        // half a radian.
        val sx = ndcX * aspect / camera.zoom
        val sy = ndcY / camera.zoom
        val c = kotlin.math.cos(camera.rotation)
        val s = kotlin.math.sin(camera.rotation)
        val x = camera.x + sx * c - sy * s
        val y = camera.y + sx * s + sy * c
        return x to y
    }

    companion object {
        /**
         * How hard the engine flashes at a payoff, before the limiter and the
         * person's Reduce flashing setting have their say. Well above the 0.08
         * that counts as a glow rather than a flash, and below full white.
         */
        const val PAYOFF_FLASH = 0.55f

        /**
         * How long the punch-in takes to come back, squared on the way. Chosen
         * to finish with the synth sweep rather than after it.
         */
        const val PUNCH_SECONDS = 0.8f

        /**
         * How long Evolve stands off a value after a hand moves it. Long enough
         * to let go, look at it and move it again; short enough that Evolve is
         * still Evolve.
         */
        const val DRIFT_HOLD_NANOS = 6_000_000_000L

        /** How deep the idle zoom breathes, and how slowly. Under one percent. */
        const val BREATH_DEPTH = 0.008f
        const val BREATH_HZ = 0.07f

        /** Screen shake: a few pixels of world, gone inside 150 ms. */
        const val SHAKE_SECONDS = 0.15f
        const val SHAKE_WORLD = 0.012f

        /** A flick can produce a lot of moves; a frame need not apply all of them. */
        const val MAX_PENDING_TOUCHES = 64

        /** A seek re-simulates from the seed, so very long ones are capped. */
        const val MAX_SEEK_STEPS = 60 * 120 * 5

        /** Steps a single frame will spend catching a seek up. */
        const val SEEK_STEPS_PER_FRAME = 900

        fun clampSpeed(v: Float): Float = min(10f, max(0.1f, v))
    }
}
