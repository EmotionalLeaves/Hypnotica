package app.hypnotica.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.hypnotica.core.audio.Scale
import app.hypnotica.core.audio.Song
import app.hypnotica.core.director.Clip
import app.hypnotica.core.director.ClipDirector
import app.hypnotica.core.perf.Bench
import app.hypnotica.core.sim.TrapFormat
import app.hypnotica.core.laws.Laws
import app.hypnotica.core.render.Palette
import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Macro
import app.hypnotica.core.recipe.Macros
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.recipe.RecipeStore
import app.hypnotica.core.recipe.ParamType
import app.hypnotica.core.recipe.RunParse
import app.hypnotica.core.recipe.applyRunParams
import app.hypnotica.core.recipe.lawsJson
import app.hypnotica.core.recipe.runParamsJson
import app.hypnotica.core.recipe.parseRun
import app.hypnotica.core.recipe.runLaws
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.studio.MoveRecorder
import app.hypnotica.core.studio.Project
import app.hypnotica.core.studio.parseProject
import app.hypnotica.core.studio.projectJson
import app.hypnotica.engine.SimEngine
import app.hypnotica.sims.SimRegistry

enum class Screen { FEED, LIBRARY, PLAYER, SETTINGS, TICKTOCK, STUDIO, RECIPES }

/**
 * Everything the UI needs to know, in one place.
 *
 * Parameter values live in the engine, not here; [paramVersion] is bumped on every
 * change so Compose knows to read them again.
 */
/**
 * How long a finished run is left on screen before the next one starts: the
 * design doc's resolve beat — settle, breathe, and the last frame is worth a
 * screenshot. Every screen that hosts a sim waits this long, so a finished sort
 * is something you see rather than something that was already gone.
 */
internal const val RESOLVE_MS = 1800L

/** Under this share of the phone's best frame rate counts as falling behind. */
private const val SLOW_SHARE = 0.7f

/** Quarter-second checks in a row: three seconds of it before saying so. */
private const val SLOW_TICKS = 12

class AppState(
    val engine: SimEngine,
    private val prefs: Prefs? = null,
    val recipeStore: RecipeStore? = null
) {

    var screen by mutableStateOf(Screen.FEED)
    var currentDef by mutableStateOf(SimRegistry.defaultSim())
        private set

    var controlsOpen by mutableStateOf(false)
    private var advancedState by mutableStateOf(false)
    var advanced: Boolean
        get() = advancedState
        set(value) {
            advancedState = value
            applySafety()
        }
    var card by mutableStateOf(Card.DATA)
    var advancedTab by mutableStateOf(AdvancedTab.DATA)
    var paramVersion by mutableIntStateOf(0)

    /**
     * Bumped a few times a second while the player is on screen.
     *
     * Everything the engine knows but Compose cannot see needs this. Evolve
     * drifts the parameter values from the GL thread, which must not touch
     * snapshot state, so without it the picture changed while every slider sat
     * frozen. The same goes for which event kinds a run has actually produced,
     * which decides whether a sound switch can do anything.
     */
    /**
     * Bumped whenever a sound value changes, so the Sound tab redraws.
     *
     * SoundMapping is plain fields read by the audio thread, not snapshot
     * state; without this the sliders that write them never showed their own
     * effect, and a preset tap silently replaced four of them.
     *
     * Declared up here with the other counters for a reason: init() calls
     * load(), load() calls soundChanged(), and Kotlin creates delegates in
     * declaration order — so sitting below the init block this one did not
     * exist yet when it was written to, and constructing AppState threw. The
     * app would not launch at all.
     */
    var soundVersion by mutableIntStateOf(0)
        private set

    fun soundChanged() {
        soundVersion++
    }

    var liveTick by mutableIntStateOf(0)
        private set

    fun tickLive() {
        liveTick++
        // The core is the truth about whether the sim is running; this is the
        // copy the Play / Pause label reads. Anything that swaps or rebuilds the
        // core starts it running, and three of those paths — loading another
        // sim, opening a TickTock clip, entering Studio — reset the core and
        // not this, so the player said "Play" over a sim that was running, and
        // one tap later paused it.
        if (paused != engine.core.paused) paused = engine.core.paused

        // Whether the phone is keeping up, measured rather than guessed. The
        // engine has smoothed its frame rate since the first build and nothing
        // read it, so a size slider could take a sim past what the phone draws
        // smoothly with nothing saying so. Judged against the best this phone
        // has managed this session, so a 30 Hz battery-saver cap or a 120 Hz
        // screen are each their own normal.
        val f = engine.fps
        if (!paused && f > 1f) {
            if (f > bestFps) bestFps = f
            slowTicks = if (f < bestFps * SLOW_SHARE) slowTicks + 1 else 0
        }
        val now = slowTicks >= SLOW_TICKS
        if (struggling != now) struggling = now
    }

    /** True while the sim has been drawing well under this phone's best for a few seconds. */
    var struggling by mutableStateOf(false)
        private set
    private var bestFps = 0f
    private var slowTicks = 0

    /** Where each Simple-mode macro slider sits, per sim. */
    private val macroPositions = mutableStateMapOf<String, Float>()

    /**
     * The parameter revision each cached macro position was true for.
     *
     * The cache used to be written once and never invalidated, so after a value
     * was changed in Advanced — or drifted by Evolve — the Simple slider still
     * sat where it had been left, and the next touch of it snapped everything
     * back to a position that no longer described the sim.
     */
    private val macroRevisions = mutableStateMapOf<String, Int>()

    /**
     * Bumped only when a formula changes from outside the keyboard, so a text
     * field knows to reload without being yanked out from under the typist.
     */
    var exprEpoch by mutableIntStateOf(0)
        private set

    private var speedState by mutableFloatStateOf(1f)
    private var trailsState by mutableFloatStateOf(0f)

    /** Playback speed, 0.1x to 10x. Setting it reaches straight into the core. */
    var speed: Float
        get() = speedState
        set(value) {
            speedState = value.coerceIn(0.1f, 10f)
            engine.core.speed = speedState
        }

    /** 0 = no trails, 0.96 = long smeary ones. */
    var trails: Float
        get() = trailsState
        set(value) {
            trailsState = value.coerceIn(0f, 0.96f)
            engine.setTrails(trailsState)
        }

    var paused by mutableStateOf(false)
    var paletteName by mutableStateOf("Spectrum")

    /**
     * A palette and sound the person picked themselves, which outrank each
     * sim's own default until they clear them. Without this, picking a palette
     * in Settings looked like it worked and was then thrown away by the next
     * sim change — and the Settings screen has no preview, so the pill lit up
     * and nothing visible happened at all.
     */
    private var chosenPalette: String? = null
    private var chosenSound: String? = null

    /**
     * Where the person is in the feed.
     *
     * It used to be a `remember` inside FeedScreen, which the navigation
     * `when` disposes: leaving the feed and coming back reset it to zero and
     * reloaded the first sim of the shuffle. So you lost your place every time
     * you opened the player, the palette you had just picked went with it, and
     * the "pick up where you left off" that the saved sim id promises was
     * overwritten one frame after launch — permanently, on disk.
     */
    var feedIndex by mutableIntStateOf(0)

    /**
     * Why the sim stopped, or null. Mirrored from the engine, which sets it
     * from the GL thread and cannot touch snapshot state itself.
     */
    var halted by mutableStateOf<String?>(null)
    var soundPresetName by mutableStateOf("Music box")
    var songIndex by mutableIntStateOf(0)
    // These three write themselves through. Three screens changed them without
    // calling applySafety(), which is the only thing that saved them, so they
    // came back off after a force quit.
    private var showCountersState by mutableStateOf(true)
    var showCounters: Boolean
        get() = showCountersState
        set(value) {
            showCountersState = value
            applySafety()
        }

    /** 0 = off. Minutes between TickTock's "you have been scrolling" card. */
    private var timeCheckState by mutableIntStateOf(0)
    var timeCheckMinutes: Int
        get() = timeCheckState
        set(value) {
            timeCheckState = value
            applySafety()
        }

    /**
     * The synth's volume. Saved by the caller when the gesture ends.
     *
     * Mirrored into snapshot state rather than read straight off the synth,
     * which is the convention speed and trails already follow. Read directly,
     * the slider had nothing to recompose on: the volume really changed, and
     * the thumb stayed where it was — and since the drag offset kept
     * accumulating against a thumb that never moved, by the second drag the
     * finger and the thumb were in different places entirely.
     */
    private var volumeState by mutableFloatStateOf(0.8f)

    var volume: Float
        get() = volumeState
        set(value) {
            volumeState = value
            engine.synth.volume = value
        }

    private var evolveState by mutableStateOf(false)

    /**
     * Evolve: the sim's own settings drift slowly while it plays, so a loop
     * never settles into exactly the same thing twice.
     *
     * A property with a side effect rather than a setX() function, because a
     * `var` and a `fun setX()` compile to the same JVM method.
     */
    var evolve: Boolean
        get() = evolveState
        set(value) {
            evolveState = value
            engine.evolve = value
        }

    private var cameraDragState by mutableStateOf(false)

    /**
     * When on, one finger pans the view instead of reaching into the sim.
     *
     * The surface field is written only by SimCanvas's own `cameraMode`
     * parameter now. It used to be written from here through a callback as
     * well, and the player called SimCanvas without the parameter — so every
     * recomposition wrote the default `false` back over the person's choice.
     * The switch stayed on and the gesture went back to poking the sim.
     */
    var cameraDrag: Boolean
        get() = cameraDragState
        set(value) {
            cameraDragState = value
            prefs?.cameraDrag = value
        }

    /**
     * TickTock's generator. It lives here rather than in the screen so the
     * learned weighting survives leaving TickTock and coming back.
     */
    val director = ClipDirector()

    /** What the benchmark found, shown in Settings. */
    var benchNote by mutableStateOf<String?>(null)
        private set

    /**
     * Measure what this phone can carry, once, and tell the clip generator. Call
     * from a background thread: it runs the heaviest sim for about a second.
     */
    fun benchmarkIfNeeded() {
        val p = prefs
        val remembered = Bench.tierByName(p?.deviceTier)
        if (remembered != null) {
            director.sizeScale = remembered.sizeScale
            benchNote = p?.benchNote
            return
        }
        val result = Bench.run()
        director.sizeScale = result.tier.sizeScale
        benchNote = result.describe()
        // A failed measurement is not remembered, so the next launch tries again.
        if (result.measured) {
            p?.deviceTier = result.tier.name
            p?.benchNote = result.describe()
        }
    }

    /** Remember what the person liked, so the feed is still theirs next launch. */
    fun likeClip(clip: Clip) {
        director.like(clip)
        prefs?.taste = director.exportTaste()
    }

    fun skipClip(clip: Clip) {
        director.skip(clip)
        prefs?.taste = director.exportTaste()
    }

    /** Mirrors the director's muted set so Compose can see it change. */
    var mutedFormats by mutableStateOf<Set<TrapFormat>>(emptySet())
        private set

    fun setFormatMuted(format: TrapFormat, muted: Boolean) {
        // Turning the last one off would leave the feed with nothing to show.
        // This used to add it and then quietly take it back, so the pill stayed
        // selected and the tap looked like a dead control; the guard was right,
        // its silence was the bug.
        if (muted && director.mutedFormats.size >= TrapFormat.entries.size - 1) {
            notice = "At least one kind has to stay on, or the feed has nothing to show."
            return
        }
        if (muted) director.mutedFormats.add(format) else director.mutedFormats.remove(format)
        notice = null
        mutedFormats = director.mutedFormats.toSet()
        prefs?.mutedFormats = director.exportMuted()
    }

    /** True when [format] is the last one still showing, so its pill can say so. */
    fun isLastShowingFormat(format: TrapFormat): Boolean =
        format !in director.mutedFormats &&
            director.mutedFormats.size >= TrapFormat.entries.size - 1

    fun resetTaste() {
        director.resetTaste()
        prefs?.taste = ""
    }

    /** The Studio project being edited, kept while the app is open. */
    var project by mutableStateOf<Project?>(null)

    /** The project Studio edits, made on the spot the first time it is asked for. */
    fun projectForStudio(): Project {
        val existing = project
        if (existing != null) {
            // The project was cached for the life of the app while every visit
            // to the Feed re-seeded the engine, so the preview re-simulated one
            // run and the export re-simulated another: same sim, different
            // collisions, keyframes landing on the wrong moments. Worse, after
            // changing sims the file held the new sim with the old sim's seed.
            // The keyframes are the person's work and are kept; what the run is
            // follows the sim actually on screen.
            existing.simId = currentDef.id
            existing.seed = engine.core.seed
            return existing
        }
        // The sim's own length, so the template's payoff keyframes land on
        // this sim's payoff rather than always at twenty-six seconds.
        // A project saved before the app was last killed comes back first.
        // It follows the sim on screen, as one held in memory always has.
        prefs?.studioProject?.let { parseProject(it) }?.let { restored ->
            restored.simId = currentDef.id
            restored.seed = engine.core.seed
            project = restored
            return restored
        }
        val made = Project.template(
            "Build-up to payoff", currentDef.id, engine.core.seed, currentDef.typicalSeconds
        )
        project = made
        return made
    }

    /**
     * Write the Studio project down. It lived only in memory, and Android
     * reclaims a backgrounded app whenever it likes — a phone call was enough to
     * take a session's keyframes. Cheap: a project is a few kilobytes.
     */
    fun saveProject() {
        val p = project ?: return
        prefs?.studioProject = projectJson(p)
    }

    /** Record moves: capture the camera and the controls, then edit in Studio. */
    var recording by mutableStateOf(false)
        private set

    fun startRecording() {
        val rec = MoveRecorder(currentDef.id, engine.core.seed)
        engine.recorder = rec
        recording = true
    }

    /** Stop and open the recording in Studio, where it can be trimmed. */
    /** Set when something the person did could not be carried out, cleared on read. */
    var notice by mutableStateOf<String?>(null)

    fun stopRecording() {
        val rec = engine.recorder ?: run { recording = false; return }
        engine.recorder = null
        recording = false
        if (rec.seconds < 1.5f) {
            // Tapping start and stop in quick succession used to do nothing at
            // all, with the recording already thrown away and no word about it.
            notice = "That was too short to edit — hold the move for a second or two."
            return
        }
        val built = rec.finish()
        project = built
        saveProject()
        screen = Screen.STUDIO
    }

    /** The live rules of the universe. Every physics sim reads these. */
    var laws by mutableStateOf(Laws.OUR_UNIVERSE)
        private set

    val lawsName: String get() = laws.name
    var reduceFlashing by mutableStateOf(false)
    var reduceMotion by mutableStateOf(false)
    var haptics by mutableStateOf(true)

    /** Favourites, kept in memory for now; persisted in the storage round. */
    val favourites = mutableStateOf(setOf<String>())

    init {
        prefs?.let { p ->
            favourites.value = p.favourites
            reduceFlashing = p.reduceFlashing
            reduceMotion = p.reduceMotion
            haptics = p.haptics
            showCountersState = p.showCounters
            timeCheckState = p.timeCheckMinutes
            advancedState = p.advanced
            // Through the property, so the slider shows the restored value;
            // writing the synth directly leaves the mirrored state at its
            // default and the thumb in the wrong place.
            volume = p.volume
            chosenPalette = p.paletteName
            chosenSound = p.soundPreset
            cameraDragState = p.cameraDrag
            applySafety()
            director.importTaste(p.taste)
            director.importMuted(p.mutedFormats)
            mutedFormats = director.mutedFormats.toSet()
        }
        engine.onHalt = { why ->
            halted = why
            // So the next launch does not open straight back onto the sim that
            // stopped. Without this a sim that throws on its own default values
            // could not be escaped except by clearing the app's data.
            prefs?.lastSimId = null
        }
        val remembered = prefs?.lastSimId?.let { SimRegistry.find(it) }
        load(remembered ?: currentDef, restartSeed = 1L)
    }

    /** Pick up after a halt: load something else, or try the same thing again. */
    fun recoverFrom(def: SimDef?) {
        halted = null
        engine.clearHalt()
        if (def != null) load(def) else restart(newSeed = true)
    }

    fun load(def: SimDef, restartSeed: Long = System.nanoTime()) {
        currentDef = def
        // A new sim starts running, and says so.
        paused = false
        slowTicks = 0
        struggling = false
        val palette = chosenPalette ?: def.defaultPalette
        val sound = chosenSound ?: def.defaultSound
        paletteName = palette
        soundPresetName = sound
        // keepLook stops the engine re-applying the sim's own defaults on the
        // next GL frame, which is what used to undo the person's choice.
        engine.loadSim(def, restartSeed, keepLook = true)
        engine.setPalette(palette)
        engine.setSoundPreset(sound)
        // The sound mapping is rebuilt by the preset, so the Sound tab's
        // sliders are describing the previous sim until this says otherwise.
        soundChanged()
        macroPositions.clear()
        macroRevisions.clear()
        paramVersion++
        exprEpoch++
        prefs?.lastSimId = def.id
    }

    fun restart(newSeed: Boolean = true) {
        engine.restart(if (newSeed) System.nanoTime() else engine.core.seed)
        paramVersion++
    }

    fun togglePause() {
        paused = !paused
        engine.core.paused = paused
    }

    fun setPalette(name: String) {
        paletteName = name
        engine.setPalette(name)
        chosenPalette = name
        prefs?.paletteName = name
    }

    fun setSoundPreset(name: String) {
        soundPresetName = name
        engine.setSoundPreset(name)
        chosenSound = name
        prefs?.soundPreset = name
        soundChanged()
    }

    fun setSong(index: Int) {
        songIndex = index
        engine.setSong(Song.byIndex(index))
        // The only sound writer that used to skip this. Harmless while nothing
        // on the tab depended on it; load-bearing now that the scale, pitch and
        // range controls dim themselves when a song is playing.
        soundChanged()
    }

    fun setLaws(name: String) {
        laws = Laws.preset(name)
        engine.setLaws(laws)
    }

    /** Change one rule, keeping the rest. The name becomes "Custom" once edited. */
    fun editLaws(block: (Laws) -> Laws) {
        val edited = block(laws)
        laws = if (edited == laws) edited else edited.copy(name = "Custom")
        engine.setLaws(laws)
    }

    fun applySafety() {
        engine.safety.reduceFlashing = reduceFlashing
        engine.safety.reduceMotion = reduceMotion
        engine.safety.haptics = haptics
        engine.haptics?.enabled = haptics
        prefs?.let {
            it.reduceFlashing = reduceFlashing
            it.reduceMotion = reduceMotion
            it.haptics = haptics
            it.showCounters = showCountersState
            it.timeCheckMinutes = timeCheckState
            it.advanced = advancedState
            it.volume = engine.synth.volume
        }
    }

    /** Go back one level; returns false when there is nowhere left to go. */
    fun goBack(): Boolean = when {
        controlsOpen -> {
            controlsOpen = false
            true
        }
        screen == Screen.FEED -> false
        screen == Screen.PLAYER -> {
            screen = Screen.FEED
            true
        }
        else -> {
            screen = Screen.FEED
            true
        }
    }

    // ------------------------------------------------------------- params

    fun params(): List<Param> = currentDef.params

    /**
     * Simple mode's big controls for one card. A macro moves several exact
     * values at once, which is what keeps Simple mode powerful rather than
     * merely shorter than Advanced.
     */
    fun macros(card: Card): List<Macro> = Macros.forDef(currentDef).filter { it.card == card }

    fun macroPosition(macro: Macro): Float {
        val revision = engine.core.params.revision
        val cached = macroPositions[macro.key]
        if (cached != null && macroRevisions[macro.key] == revision) return cached
        val fresh = Macros.positionFor(macro, engine.core.params)
        macroPositions[macro.key] = fresh
        macroRevisions[macro.key] = revision
        return fresh
    }

    fun setMacro(macro: Macro, position: Float) {
        val clamped = position.coerceIn(0f, 1f)
        engine.core.params.applyMacro(macro, clamped)
        // Recorded after the write, so this macro's own change does not look
        // like somebody else's and send it back to be recomputed mid-drag.
        macroPositions[macro.key] = clamped
        macroRevisions[macro.key] = engine.core.params.revision
        paramVersion++
    }

    /** True when a typed formula is driving [key], so the UI can say so. */
    fun formulaOn(key: String): String? = engine.core.params.expressionSource(key)

    /**
     * The text of a typed formula parameter.
     *
     * Not [formulaOn]: that reads the map `setExpression` writes, and an EXPR
     * parameter goes through `setText` instead, so asking it about fx or fy
     * always answered null — which is how the equation preset chips came to
     * compute a selected index that was always -1, the very literal the
     * computation replaced.
     */
    /**
     * Drive a value from a formula in t, or clear it when the box is emptied.
     *
     * The Advanced formula box used to write the engine directly, so nothing
     * bumped the version and nothing committed: on a value the run only reads
     * while being built, typing a formula did nothing whatsoever. Returns
     * whether the text parses, which is what colours the box.
     */
    /**
     * Whether Evolve has anything it is allowed to move on this sim.
     *
     * The drift skips Data-card values and anything that is not a float, so on
     * the n-body sim (whose only float is on the Data card) and curved space
     * the switch flipped on, stayed on, and nothing ever drifted.
     */
    val canEvolve: Boolean
        get() = currentDef.params.any {
            it.card != Card.DATA && it.type == ParamType.FLOAT
        }

    fun setParamFormula(p: Param, text: String): Boolean {
        val ok = if (text.isBlank()) {
            engine.core.params.clearExpression(p.key)
            true
        } else {
            engine.core.params.setExpression(p.key, text)
        }
        if (ok) paramVersion++
        return ok
    }

    fun paramText(key: String, fallback: String = ""): String =
        engine.core.params.text(key, fallback)

    /**
     * A macro rebuilds the run when any value it moves would.
     *
     * Card alone was not enough: every target of boids' Chaos, ball-escape's
     * Chaos and the derived Energy macro is a Motion value its sim reads only
     * at setup, so the headline control of Simple mode moved four numbers and
     * changed nothing at all.
     */
    fun commitMacro(macro: Macro) {
        val rebuild = macro.card == Card.DATA ||
            macro.targets.keys.any { needsRestart(macro.card, it) }
        if (rebuild) restart(newSeed = false)
    }

    fun paramValue(key: String): Float = engine.core.params.f(key)

    fun setParam(p: Param, value: Float) {
        // Dragging a slider by hand replaces a formula on the same value — but
        // only when a person does it, never when Evolve or a macro moves it.
        engine.core.params.clearExpression(p.key)
        // And a hand on the slider outranks Evolve for a few seconds, the same
        // way a typed formula does. Otherwise you drag a value, let go, and
        // watch the drift pull it back — which looks exactly like a slider
        // that does not work.
        engine.holdFromDrift(p.key)
        engine.core.params.set(p.key, if (p.type == ParamType.INT) Math.round(value).toFloat() else value)
        paramVersion++
    }

    /**
     * Store a typed formula. Nothing restarts yet: a restart on every keystroke
     * would mean the sim rebuilding itself while the person is still mid-word.
     */
    fun setParamText(p: Param, value: String): Boolean =
        engine.core.params.setText(p.key, value)

    /** Apply a typed formula, once the person is done typing it. */
    fun commitParamText(p: Param) {
        paramVersion++
        if (needsRestart(p)) restart(newSeed = false)
    }

    /**
     * Set a formula from somewhere other than the keyboard — a preset, a clip, a
     * saved state. [exprEpoch] tells the text fields to pick the new value up.
     */
    fun applyParamText(key: String, value: String) {
        val p = currentDef.params.firstOrNull { it.key == key } ?: return
        if (engine.core.params.setText(key, value)) {
            exprEpoch++
            paramVersion++
            if (needsRestart(p)) restart(newSeed = false)
        }
    }

    fun setParamChoice(p: Param, index: Int) {
        engine.core.params.set(p.key, index.toFloat())
        paramVersion++
        if (needsRestart(p)) restart(newSeed = false)
    }

    fun setParamBool(p: Param, on: Boolean) {
        engine.core.params.set(p.key, on)
        paramVersion++
        if (needsRestart(p)) restart(newSeed = false)
    }

    /**
     * Data-card parameters shape what gets built in setup(), so they land on the
     * next run rather than mid-flight. Everything else applies live.
     */
    /**
     * Whether applying [p] means building the run again.
     *
     * This used to be "is it on the Data card", and that answer was wrong for
     * about a quarter of every control in the app: some fifty values that the
     * sims copy into a field in setup() and never look at again. Dragging one
     * moved the number, bumped the version, and left the picture untouched —
     * whole cards of them, boids' Separation/Alignment/Cohesion/Speed, the
     * sorting sims' Algorithm chip, Mandelbrot's Resolution and Detail — and
     * the Chaos and Energy macros that Simple mode leans hardest on were inert
     * on those sims for the same reason.
     *
     * The sim is asked rather than told: [ParamValues] records which keys are
     * read while a run is stepping, so a value nothing reads live can only be
     * applied by rebuilding. Until a run has been watched long enough to know,
     * the old rule stands, so the answer is never worse than it was.
     */
    fun needsRestart(p: Param): Boolean = needsRestart(p.card, p.key)

    private fun needsRestart(card: Card, key: String): Boolean {
        if (card == Card.DATA) return true
        val params = engine.core.params
        return params.readsSettled && !params.readLive(key)
    }

    fun commitParam(p: Param) {
        if (needsRestart(p)) restart(newSeed = false)
    }

    /** Load and start a generated clip exactly as the director checked it. */
    fun playClip(clip: Clip) {
        currentDef = clip.def
        paletteName = clip.palette
        soundPresetName = clip.soundPreset
        laws = clip.laws
        // A clip was checked for its payoff at normal speed with no smear, and
        // it inherited whatever the player had been left on: at six times speed
        // the director's four-to-forty-second window becomes under seven
        // seconds, so clips resolved before the hook had been read. A pause
        // carried over too, which showed a still frame with no way to clear it.
        engine.core.paused = false
        paused = false
        engine.core.speed = 1f
        speed = 1f
        trails = clip.trails
        engine.scene.trails = clip.trails
        engine.loadSim(clip.def, clip.seed, keepLook = true)
        engine.setLaws(clip.laws)
        engine.setPalette(clip.palette)
        engine.setSoundPreset(clip.soundPreset)
        engine.applyParams(clip.params, clip.seed)
        paramVersion++
        exprEpoch++
    }

    /** Hand the clip on screen to the normal player, controls and all. */
    fun openClipInPlayer(clip: Clip) {
        playClip(clip)
        controlsOpen = true
        screen = Screen.PLAYER
    }

    /**
     * The whole run as one small object: recipe, seed, parameters, laws, look and
     * sound. This is what a replay link or a Studio project stores, and it is
     * enough to reproduce any frame exactly.
     */
    fun stateJson(): String {
        val core = engine.core
        val sb = StringBuilder(512)
        sb.append("{\n")
        sb.append("  \"recipe\": \"").append(currentDef.id).append("\",\n")
        sb.append("  \"seed\": ").append(core.seed).append(",\n")
        sb.append("  \"params\": {")
        sb.append(runParamsJson(currentDef.params, core.params))
        sb.append("\n  },\n")
        // lawsJson, not the name: Laws.preset has no entry called "Custom", so
        // a hand-edited universe written down as its name came back as our
        // universe. The writer and the reader live in one file with a round-trip
        // test over them (RunStateTest).
        sb.append("  \"laws\": ").append(lawsJson(laws)).append(",\n")
        sb.append("  \"look\": { \"palette\": \"").append(paletteName)
            .append("\", \"trails\": ").append(String.format(java.util.Locale.US, "%.2f", trails)).append(" },\n")
        sb.append("  \"sound\": { \"preset\": \"").append(soundPresetName)
            .append("\", \"scale\": \"").append(engine.mapping.scale.name).append("\" }\n")
        sb.append("}")
        return sb.toString()
    }

    /**
     * Apply a run printed by [stateJson]. Returns null when it worked, or a
     * sentence saying why not.
     *
     * The panel has called that text "everything needed to replay it" since the
     * day it was written, with nothing in the app able to read it back. This is
     * the half that makes the sentence true, and the reason a run is worth
     * copying at all: send one to someone and they get your exact picture, down
     * to the seed.
     *
     * Everything is written straight into the core and the run restarted once
     * at the end, rather than going through the per-control setters: those each
     * restart when the value they changed needs it, so a run with a dozen setup
     * values rebuilt the sim a dozen times on the way in.
     */
    fun applyRun(text: String): String? {
        val run = when (val parsed = parseRun(text)) {
            is RunParse.Bad -> return parsed.why
            is RunParse.Ok -> parsed.run
        }
        val def = SimRegistry.find(run.recipe)
            ?: return if (run.recipe.startsWith("recipe/")) {
                "This phone does not have the recipe \"${run.recipe.removePrefix("recipe/")}\" " +
                    "yet. Add it in the recipe editor first, then paste this again."
            } else {
                "There is no sim called \"${run.recipe}\" in this build."
            }

        val seed = run.seed ?: System.nanoTime()
        load(def, seed)

        val unknown = applyRunParams(run, def.params, engine.core.params)

        runLaws(run)?.let {
            laws = it
            engine.setLaws(it)
        }
        // byName falls back to Spectrum rather than returning null, so an
        // unknown palette has to be recognised before it is applied or it would
        // quietly repaint the sim.
        run.palette?.let { name -> if (Palette.ALL.any { it.name == name }) setPalette(name) }
        run.trails?.let { trails = it }
        run.soundPreset?.let { setSoundPreset(it) }
        // After the preset, which rebuilds the mapping and would overwrite it.
        run.scale?.let { engine.mapping.scale = Scale.byName(it) }
        soundChanged()

        // One restart, with the run's own seed, so the picture is the one that
        // was written down rather than the same recipe from somewhere else.
        engine.restart(seed)
        paramVersion++
        exprEpoch++
        if (unknown.isEmpty()) return null
        val named = unknown.take(3).joinToString(", ") { "\"$it\"" }
        val rest = if (unknown.size > 3) " and ${unknown.size - 3} more" else ""
        return "Loaded. This build has no " +
            (if (unknown.size == 1) "setting called " else "settings called ") + named + rest + "."
    }

    fun toggleFavourite(id: String) {
        val cur = favourites.value
        val next = if (id in cur) cur - id else cur + id
        favourites.value = next
        prefs?.favourites = next
    }

    fun isFavourite(id: String): Boolean = id in favourites.value
}
