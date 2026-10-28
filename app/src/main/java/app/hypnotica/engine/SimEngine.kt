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
    private var finishNotified = false

    /** Extra zoom applied around the payoff, the "punch-in". */
    private var punch = 0f

    /** The zoom bump on the beat's kick, 1 on the hit and easing to 0. */
    private var beat = 0f
    private var sizedOnce = false

    /** The screen shape the current run was set up for. */
    private var laidOutAspect = 0f

    /**
     * Whether a change of screen shape lays the run out again. TickTock turns
     * it off: its clips are timed against the run's clock, and starting the
     * run over mid-clip ran the clip twice over.
     */
    @Volatile
    var relayoutOnResize = true
    private var kicksSeen = 0L
    private var baseZoom = 1f
    /**
     * The sim's counters, taken on this thread a few times a second for the
     * screens to read. They read them straight off the sim from the UI
     * thread, while this thread stepped it — and a counter that walks a list
     * the step is adding to throws on the UI thread, which ends the app.
     */
    @Volatile
    var counters: List<Pair<String, String>> = emptyList()
        private set
    private var sinceCounters = 0f

    /**
     * The sim's sides and winner, snapshotted with the counters, together
     * with which run they belong to: the run number goes up on every load
     * and restart, so a pick made in one run is never settled by the next.
     * One object, so the sides and the winner always come from one frame.
     */
    @Volatile
    var pick = PickSnapshot(0L, app.hypnotica.core.sim.NO_CONTENDERS, -1)
        private set
    private var runId = 0L

    /**
     * Set by a pinch. The feed and TickTock lean the camera in on their own;
     * once the person has zoomed, the camera is theirs until the next sim.
     */
    @Volatile
    var userZoomed = false

    /** Kaleidoscope folds for what is drawn; 1 for none. Set by TickTock per clip. */
    @Volatile
    var kaleido: Int = 1

    /** The sim the current fit was worked out for; a new one is framed at once. */
    private var fitFor: Any? = null

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

    /**
     * Only records the choice: the render thread applies it at the top of
     * its next frame, the one place the sim's own tune is also set. Both
     * writing the mapping, from two threads, could each overwrite the other —
     * the person's choice lost, or the last sim's tune left playing.
     */
    fun setSong(song: Song) {
        chosenSong = if (song.notes.isEmpty()) null else song
        songDirty = true
    }

    @Volatile
    private var songDirty = false

    /** On the render thread: the chosen song, else the sim's own. */
    private fun applySongs() {
        val chosen = chosenSong
        val own = ownSong
        mapping.song = chosen ?: own
        mapping.tuneIsSims = chosen == null && own != null
        mapping.resetSong()
    }

    /** The song the person or a clip chose; null leaves it to the sim. */
    @Volatile
    private var chosenSong: Song? = null

    /** The tune the running sim asked for, if any; see [Sim.tune]. */
    @Volatile
    private var ownSong: Song? = null

    /** After every setup: the sim's own tune, unless one was chosen over it. */
    private fun applyOwnSong() {
        ownSong = core.sim.tune(core.env)?.let { Song.byName(it) }
        applySongs()
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

    /** Hero presses waiting for the GL thread, which owns the sim. */
    private val pendingHero = java.util.concurrent.atomic.AtomicInteger(0)

    /** Press the hero button on the running sim; applied between frames. */
    fun hero() {
        if (pendingHero.get() < MAX_PENDING_TOUCHES) pendingHero.incrementAndGet()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        scene.surfaceCreated()
        flashLimiter.reset()
        lastFrameNanos = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        scene.surfaceChanged(width, height)
        val aspect = width.toFloat() / max(1, height).toFloat()
        core.setAspect(aspect)
        // Turned on its side, or back: most sims lay themselves out for the
        // screen once, at setup, and carried on in the old shape — a box
        // running off the side, bars bunched in the middle. The same run
        // again, laid out afresh. Not in the studio, where the timeline owns
        // the run.
        //
        // Queued, not run here: setup can throw, and only the frame is
        // guarded — a halted sim turned on its side took the app down. And
        // measured against the shape the run was laid out for, so a resize
        // that arrives in small steps still adds up to one.
        if (sizedOnce && project == null && relayoutOnResize && halted == null && laidOutAspect > 0f &&
            kotlin.math.abs(aspect / laidOutAspect - 1f) > RELAYOUT_CHANGE &&
            pendingLoad == null && pendingParams == null && pendingRestart == null
        ) {
            pendingRestart = core.seed
        }
        sizedOnce = true
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
        if (songDirty) {
            songDirty = false
            applySongs()
        }
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
        repeat(pendingHero.getAndSet(0)) { core.hero() }

        core.env.reduceMotion = safety.reduceMotion
        flashLimiter.reduceFlashing = safety.reduceFlashing

        val proj = project
        var timelineFlash = 0f
        var frozen = false

        if (proj != null) {
            pendingSeek?.let { target ->
                pendingSeek = null
                cameraPosed = false
                // Only rebuild the run when going backwards.
                //
                // Every seek used to restart from the seed, and the scrubber
                // fires one per drag delta — sixty to a hundred and twenty a
                // second while a finger is moving. Each restart threw the run
                // back to zero and then paid off at most 900 steps a frame, so
                // a playhead at twenty seconds needed three frames to catch up
                // and the next delta arrived first: the preview sat pinned near
                // the start of the run, flickering, for the whole drag. Seeking
                // forward is just more steps from where we already are.
                val here = kotlin.math.abs(core.env.time)
                if (target < here) {
                    core.restart(core.seed)
                    resetRunState()
                    seekRemaining = (target * core.stepHz).toInt().coerceIn(0, MAX_SEEK_STEPS)
                } else {
                    seekRemaining =
                        ((target - here) * core.stepHz).toInt().coerceIn(0, MAX_SEEK_STEPS)
                }
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
            // The payoff is time, not fanfare.
            //
            // It used to be slow motion, a punch-in, a shake, a white flash
            // over the whole screen and a rising sweep from the synth. On a
            // sim with one clear ending — a sort completing, a tower falling —
            // that reads as an event. On the rest, which is most of them, a
            // screen flash and a chime arrive over a picture that has not
            // visibly done anything, and announce something that did not
            // happen. Keeping the quiet half and dropping the loud half leaves
            // a moment you notice without being told about.
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
        val kicksNow = mapping.kicks
        if (kicksNow != kicksSeen) {
            kicksSeen = kicksNow
            if (!safety.reduceMotion) beat = 1f
        }
        if (beat > 0f) beat = max(0f, beat - dt / BEAT_SECONDS)
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
            // And a small bump on every kick of the beat, so the picture
            // moves with the music — the cut-to-the-beat feel of a clip.
            camera.zoom = baseZoom * breathing * (1f + 0.045f * punchEase) * (1f + BEAT_DEPTH * beat * beat)

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
                val amount = SHAKE_WORLD * shake * shake / (camera.zoom * scene.fit).coerceAtLeast(0.05f)
                shakeOffsetX = a * amount
                shakeOffsetY = b * amount
            } else {
                shakeOffsetX = 0f
                shakeOffsetY = 0f
            }
        }

        // The pull-back that keeps a fixed-size sim on screen. A new sim
        // arrives already framed — a swipe lands on it whole, rather than
        // watching it zoom out — and a change within one sim, a slider that
        // makes the drawing reach further, glides.
        val wantFit = core.fit()
        val sameSim = fitFor === core.sim
        fitFor = core.sim
        scene.fit = if (!sameSim || kotlin.math.abs(wantFit - scene.fit) < 0.002f) wantFit
        else scene.fit + (wantFit - scene.fit) * kotlin.math.min(1f, dt * FIT_RATE)

        sinceCounters += dt
        if (sinceCounters >= COUNTERS_EVERY) {
            sinceCounters = 0f
            counters = core.sim.counters()
            pick = PickSnapshot(runId, core.sim.contenders(core.env), core.sim.winner())
        }

        g.reset()
        g.background = Palette.BG
        core.draw(g)
        // A kaleidoscope, for a sim drawn round its centre — the ones the view
        // pulls back to fit. Anything laid out to the screen's edges would
        // fold into a mess, so it is left alone.
        val folds = kaleido
        if (folds > 1 && wantFit < 0.99f) g.kaleido(folds)

        mapping.pump(core.events, dt)

        val allowed =
            flashLimiter.allow(max(g.flashRequest, timelineFlash), dt)
        // Asked for on the payoff frame only; the limiter owns the fade.
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
        laidOutAspect = core.env.aspect
        applyOwnSong()
        runId++
        pick = PickSnapshot(runId, app.hypnotica.core.sim.NO_CONTENDERS, -1)
        driftHold.clear()
        payoffNotified = false
        finishNotified = false
        punch = 0f
        beat = 0f
        shake = 0f
        shakeOffsetX = 0f
        shakeOffsetY = 0f
        flashLimiter.reset()
        synth.allOff()
        mapping.clearHeld()
    }

    fun setBaseZoom(z: Float) {
        // coerceIn lets a NaN straight through.
        if (!z.isFinite()) return
        baseZoom = z.coerceIn(0.2f, 40f)
        // With a project attached the breathing path below is skipped, so the
        // pinch would never reach the camera and zoom could not be posed.
        if (project != null) {
            camera.zoom = baseZoom
            cameraPosed = true
        }
    }

    fun baseZoom(): Float = baseZoom

    /** World-to-screen scale: the camera's zoom with the sim's fit applied. */
    fun viewScale(): Float = camera.zoom * scene.fit

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
        val scale = camera.zoom * scene.fit
        val sx = ndcX * aspect / scale
        val sy = ndcY / scale
        val c = kotlin.math.cos(camera.rotation)
        val s = kotlin.math.sin(camera.rotation)
        val x = camera.x + sx * c - sy * s
        val y = camera.y + sx * s + sy * c
        return x to y
    }

    companion object {
        /**
         * How long the punch-in takes to come back, squared on the way. Chosen
         * to settle as the slow motion eases out rather than after it.
         */
        const val PUNCH_SECONDS = 0.8f
        /** A change of screen shape this big lays the run out again. */
        const val RELAYOUT_CHANGE = 0.15f
        /** The beat bump: a percent and a half, gone in a fifth of a second. */
        const val BEAT_DEPTH = 0.015f
        const val BEAT_SECONDS = 0.2f

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
        /** How fast the view glides to a new sim's fit, per second. */
        const val FIT_RATE = 5f
        /** Seconds between counter snapshots. */
        const val COUNTERS_EVERY = 0.1f

        /** A flick can produce a lot of moves; a frame need not apply all of them. */
        const val MAX_PENDING_TOUCHES = 64

        /** A seek re-simulates from the seed, so very long ones are capped. */
        const val MAX_SEEK_STEPS = 60 * 120 * 5

        /** Steps a single frame will spend catching a seek up. */
        const val SEEK_STEPS_PER_FRAME = 900

        fun clampSpeed(v: Float): Float = min(10f, max(0.1f, v))
    }
}

/** What a run offers to bet on, and how it came out; see [SimEngine.pick]. */
class PickSnapshot(val run: Long, val sides: IntArray, val winner: Int)
