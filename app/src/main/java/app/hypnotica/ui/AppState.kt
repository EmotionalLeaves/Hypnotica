package app.hypnotica.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.hypnotica.core.audio.Song
import app.hypnotica.core.director.Clip
import app.hypnotica.core.laws.Laws
import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.recipe.RecipeStore
import app.hypnotica.core.recipe.ParamType
import app.hypnotica.core.sim.SimDef
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

    /** The Studio project being edited, kept while the app is open. */
    var project by mutableStateOf<Project?>(null)

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
        }
        val remembered = prefs?.lastSimId?.let { SimRegistry.find(it) }
        load(remembered ?: currentDef, restartSeed = 1L)
    }

    fun load(def: SimDef, restartSeed: Long = System.nanoTime()) {
        currentDef = def
        paletteName = def.defaultPalette
        soundPresetName = def.defaultSound
        engine.loadSim(def, restartSeed)
        paramVersion++
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

    fun paramValue(key: String): Float = engine.core.params.f(key)

    fun setParam(p: Param, value: Float) {
        engine.core.params.set(p.key, if (p.type == ParamType.INT) Math.round(value).toFloat() else value)
        paramVersion++
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
