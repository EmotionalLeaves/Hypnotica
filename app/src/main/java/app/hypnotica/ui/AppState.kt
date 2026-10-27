package app.hypnotica.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.hypnotica.core.audio.Song
import app.hypnotica.core.director.Clip
import app.hypnotica.core.director.ClipDirector
import app.hypnotica.core.perf.Bench
import app.hypnotica.core.sim.TrapFormat
import app.hypnotica.core.laws.Laws
import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Macro
import app.hypnotica.core.recipe.Macros
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.recipe.RecipeStore
import app.hypnotica.core.recipe.ParamType
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.studio.MoveRecorder
import app.hypnotica.core.studio.Project
import app.hypnotica.engine.SimEngine
import app.hypnotica.sims.SimRegistry

enum class Screen { FEED, LIBRARY, PLAYER, SETTINGS, TICKTOCK, STUDIO, RECIPES }

/**
 * Everything the UI needs to know, in one place.
 *
 * Parameter values live in the engine, not here; [paramVersion] is bumped on every
 * change so Compose knows to read them again.
 */
class AppState(
    val engine: SimEngine,
    private val prefs: Prefs? = null,
    val recipeStore: RecipeStore? = null
) {

    var screen by mutableStateOf(Screen.FEED)
    var currentDef by mutableStateOf(SimRegistry.defaultSim())
        private set

    var controlsOpen by mutableStateOf(false)
    var advanced by mutableStateOf(false)
    var card by mutableStateOf(Card.DATA)
    var advancedTab by mutableStateOf(AdvancedTab.DATA)
    var paramVersion by mutableIntStateOf(0)

    /** Where each Simple-mode macro slider sits, per sim. */
    private val macroPositions = mutableStateMapOf<String, Float>()

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
    var soundPresetName by mutableStateOf("Music box")
    var songIndex by mutableIntStateOf(0)
    var showCounters by mutableStateOf(true)

    /** 0 = off. Minutes between TickTock's "you have been scrolling" card. */
    var timeCheckMinutes by mutableIntStateOf(0)

    /**
     * Evolve: the sim's own settings drift slowly while it plays, so a loop
     * never settles into exactly the same thing twice.
     */
    var evolve by mutableStateOf(false)
        private set

    /** When on, one finger pans the view instead of reaching into the sim. */
    var cameraDrag by mutableStateOf(false)
        private set

    fun setCameraDrag(on: Boolean) {
        cameraDrag = on
        onCameraDrag?.invoke(on)
    }

    /** Set by the activity, which owns the surface the gesture lives on. */
    var onCameraDrag: ((Boolean) -> Unit)? = null

    fun setEvolve(on: Boolean) {
        evolve = on
        engine.evolve = on
    }

    /**
     * TickTock's generator. It lives here rather than in the screen so the
     * learned weighting survives leaving TickTock and coming back.
     */
    val director = ClipDirector()

    /** What the benchmark found, shown in Settings. */
    var benchNote by mutableStateOf<String?>(null)
        private set

    /** The gesture card, shown once on the first launch. */
    var showFirstRun by mutableStateOf(false)
        private set

    fun dismissFirstRun() {
        showFirstRun = false
        prefs?.seenFirstLaunch = true
    }

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
        p?.deviceTier = result.tier.name
        p?.benchNote = result.describe()
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
        if (muted) director.mutedFormats.add(format) else director.mutedFormats.remove(format)
        // Muting everything would leave the feed with nothing to show.
        if (director.mutedFormats.size >= TrapFormat.entries.size) {
            director.mutedFormats.remove(format)
        }
        mutedFormats = director.mutedFormats.toSet()
        prefs?.mutedFormats = director.exportMuted()
    }

    fun resetTaste() {
        director.resetTaste()
        prefs?.taste = ""
    }

    /** The Studio project being edited, kept while the app is open. */
    var project by mutableStateOf<Project?>(null)

    /** Record moves: capture the camera and the controls, then edit in Studio. */
    var recording by mutableStateOf(false)
        private set

    fun startRecording() {
        val rec = MoveRecorder(currentDef.id, engine.core.seed)
        engine.recorder = rec
        recording = true
    }

    /** Stop and open the recording in Studio, where it can be trimmed. */
    fun stopRecording() {
        val rec = engine.recorder ?: run { recording = false; return }
        engine.recorder = null
        recording = false
        if (rec.seconds < 1.5f) return
        val built = rec.finish()
        project = built
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
            showCounters = p.showCounters
            timeCheckMinutes = p.timeCheckMinutes
            engine.synth.volume = p.volume
            applySafety()
            showFirstRun = !p.seenFirstLaunch
            director.importTaste(p.taste)
            director.importMuted(p.mutedFormats)
            mutedFormats = director.mutedFormats.toSet()
        }
        val remembered = prefs?.lastSimId?.let { SimRegistry.find(it) }
        load(remembered ?: currentDef, restartSeed = 1L)
    }

    fun load(def: SimDef, restartSeed: Long = System.nanoTime()) {
        currentDef = def
        paletteName = def.defaultPalette
        soundPresetName = def.defaultSound
        engine.loadSim(def, restartSeed)
        macroPositions.clear()
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
        prefs?.paletteName = name
    }

    fun setSoundPreset(name: String) {
        soundPresetName = name
        engine.setSoundPreset(name)
        prefs?.soundPreset = name
    }

    fun setSong(index: Int) {
        songIndex = index
        engine.setSong(Song.byIndex(index))
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
            it.showCounters = showCounters
            it.timeCheckMinutes = timeCheckMinutes
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

    fun macroPosition(macro: Macro): Float =
        macroPositions[macro.key] ?: Macros.positionFor(macro, engine.core.params).also {
            macroPositions[macro.key] = it
        }

    fun setMacro(macro: Macro, position: Float) {
        macroPositions[macro.key] = position.coerceIn(0f, 1f)
        engine.core.params.applyMacro(macro, position)
        paramVersion++
    }

    /** A macro that touches the Data card rebuilds the run, like its sliders do. */
    fun commitMacro(macro: Macro) {
        if (macro.card == Card.DATA) restart(newSeed = false)
    }

    fun paramValue(key: String): Float = engine.core.params.f(key)

    fun setParam(p: Param, value: Float) {
        // Dragging a slider by hand replaces a formula on the same value — but
        // only when a person does it, never when Evolve or a macro moves it.
        engine.core.params.clearExpression(p.key)
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
    fun needsRestart(p: Param): Boolean = p.card == Card.DATA

    fun commitParam(p: Param) {
        if (needsRestart(p)) restart(newSeed = false)
    }

    /** Load and start a generated clip exactly as the director checked it. */
    fun playClip(clip: Clip) {
        currentDef = clip.def
        paletteName = clip.palette
        soundPresetName = clip.soundPreset
        laws = clip.laws
        engine.loadSim(clip.def, clip.seed)
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
        val entries = currentDef.params
        entries.forEachIndexed { i, p ->
            if (i > 0) sb.append(",")
            sb.append("\n    \"").append(p.key).append("\": ")
            val expr = core.params.expressionSource(p.key)
            if (expr != null) {
                sb.append('"').append(expr.replace("\"", "'")).append('"')
            } else if (p.type == ParamType.CHOICE) {
                sb.append('"').append(core.params.choice(p.key)).append('"')
            } else if (p.type == ParamType.INT) {
                sb.append(core.params.i(p.key))
            } else if (p.type == ParamType.BOOL) {
                sb.append(core.params.b(p.key))
            } else {
                sb.append(String.format("%.4f", core.params.f(p.key)))
            }
        }
        sb.append("\n  },\n")
        sb.append("  \"laws\": \"").append(laws.name).append("\",\n")
        sb.append("  \"look\": { \"palette\": \"").append(paletteName)
            .append("\", \"trails\": ").append(String.format("%.2f", trails)).append(" },\n")
        sb.append("  \"sound\": { \"preset\": \"").append(soundPresetName)
            .append("\", \"scale\": \"").append(engine.mapping.scale.name).append("\" }\n")
        sb.append("}")
        return sb.toString()
    }

    fun toggleFavourite(id: String) {
        val cur = favourites.value
        val next = if (id in cur) cur - id else cur + id
        favourites.value = next
        prefs?.favourites = next
    }

    fun isFavourite(id: String): Boolean = id in favourites.value
}
