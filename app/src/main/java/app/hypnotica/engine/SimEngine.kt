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

    /** Set while the person is recording their moves in the player. */
    @Volatile
    var recorder: app.hypnotica.core.studio.MoveRecorder? = null

    private val pendingTouches = java.util.concurrent.ConcurrentLinkedQueue<TouchEvent>()

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
    private var pendingRestart: Long? = null

    @Volatile
    private var pendingParams: Pair<Map<String, Float>, Long>? = null

    @Volatile
    var onPayoff: (() -> Unit)? = null

    @Volatile
    var onFinished: (() -> Unit)? = null

    private var payoffNotified = false
    private var finishNotified = false

    /** Extra zoom applied around the payoff, the "punch-in". */
    private var punch = 0f
    private var baseZoom = 1f

    // ------------------------------------------------------------- studio

    /** When set, the timeline drives the camera, speed, laws and look. */
    @Volatile
    var project: Project? = null

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

    fun loadSim(def: SimDef, seed: Long = System.nanoTime()) {
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

        pendingLoad?.let {
            pendingLoad = null
            core.load(it.first, it.second)
            core.setPalette(Palette.byName(it.first.defaultPalette))
            mapping.applyPreset(SoundPreset.byName(it.first.defaultSound))
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

            if (studioPlaying && !seeking) {
                studioTime += dt
                if (studioTime >= proj.duration) {
                    studioTime = 0f
                    pendingSeek = 0f
                }
            }
            frozen = proj.isFrozenAt(studioTime)
            proj.applyCamera(studioTime, camera)
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
            punch = max(0f, punch - dt * 0.9f)
        }
        if (proj == null) {
            camera.zoom = baseZoom * (1f + 0.045f * punch)
        }

        g.reset()
        g.background = Palette.BG
        core.draw(g)

        mapping.pump(core.events, dt)

        val allowed = flashLimiter.allow(max(g.flashRequest, timelineFlash), dt)
        scene.render(g, camera, allowed)
    }

    /**
     * Nudge the live parameters along a slow sine, each on its own phase.
     * Data-card values are left alone: changing those mid-run would mean
     * rebuilding the sim underneath itself.
     */
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
            val phase = index * 1.37f
            val wave = kotlin.math.sin(core.env.time * 0.08f + phase) * 0.5f + 0.5f
            val span = p.max - p.min
            val target = p.min + span * (0.25f + 0.5f * wave)
            val current = core.params.f(p.key, p.default)
            core.params.set(p.key, current + (target - current) * 0.06f)
        }
    }

    private fun resetRunState() {
        payoffNotified = false
        finishNotified = false
        punch = 0f
        flashLimiter.reset()
        synth.allOff()
    }

    fun setBaseZoom(z: Float) {
        baseZoom = z.coerceIn(0.2f, 40f)
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
        val x = camera.x + ndcX * aspect / camera.zoom
        val y = camera.y + ndcY / camera.zoom
        return x to y
    }

    companion object {
        /** A flick can produce a lot of moves; a frame need not apply all of them. */
        const val MAX_PENDING_TOUCHES = 64

        /** A seek re-simulates from the seed, so very long ones are capped. */
        const val MAX_SEEK_STEPS = 60 * 120 * 5

        /** Steps a single frame will spend catching a seek up. */
        const val SEEK_STEPS_PER_FRAME = 900

        fun clampSpeed(v: Float): Float = min(10f, max(0.1f, v))
    }
}
