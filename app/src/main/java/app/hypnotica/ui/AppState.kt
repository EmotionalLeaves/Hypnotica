package app.hypnotica.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.hypnotica.core.audio.Song
import app.hypnotica.core.laws.Laws
import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.recipe.ParamType
import app.hypnotica.core.sim.SimDef
import app.hypnotica.engine.SimEngine
import app.hypnotica.sims.SimRegistry

enum class Screen { FEED, LIBRARY, PLAYER, SETTINGS }

/**
 * Everything the UI needs to know, in one place.
 *
 * Parameter values live in the engine, not here; [paramVersion] is bumped on every
 * change so Compose knows to read them again.
 */
class AppState(val engine: SimEngine) {

    var screen by mutableStateOf(Screen.FEED)
    var currentDef by mutableStateOf(SimRegistry.defaultSim())
        private set

    var controlsOpen by mutableStateOf(false)
    var advanced by mutableStateOf(false)
    var card by mutableStateOf(Card.DATA)
    var paramVersion by mutableIntStateOf(0)
        private set

    var speed by mutableFloatStateOf(1f)
    var paused by mutableStateOf(false)
    var paletteName by mutableStateOf("Spectrum")
    var soundPresetName by mutableStateOf("Music box")
    var songIndex by mutableIntStateOf(0)
    var lawsName by mutableStateOf("Our universe")
    var trails by mutableFloatStateOf(0f)
    var showCounters by mutableStateOf(true)
    var reduceFlashing by mutableStateOf(false)
    var reduceMotion by mutableStateOf(false)
    var haptics by mutableStateOf(true)

    /** Favourites, kept in memory for now; persisted in the storage round. */
    val favourites = mutableStateOf(setOf<String>())

    init {
        load(currentDef, restartSeed = 1L)
    }

    fun load(def: SimDef, restartSeed: Long = System.nanoTime()) {
        currentDef = def
        paletteName = def.defaultPalette
        soundPresetName = def.defaultSound
        engine.loadSim(def, restartSeed)
        paramVersion++
    }

    fun restart(newSeed: Boolean = true) {
        engine.restart(if (newSeed) System.nanoTime() else engine.core.seed)
        paramVersion++
    }

    fun togglePause() {
        paused = !paused
        engine.core.paused = paused
    }

    fun setSpeed(v: Float) {
        speed = v.coerceIn(0.1f, 10f)
        engine.core.speed = speed
    }

    fun setPalette(name: String) {
        paletteName = name
        engine.setPalette(name)
    }

    fun setSoundPreset(name: String) {
        soundPresetName = name
        engine.setSoundPreset(name)
    }

    fun setSong(index: Int) {
        songIndex = index
        engine.setSong(Song.byIndex(index))
    }

    fun setLaws(name: String) {
        lawsName = name
        engine.setLaws(Laws.preset(name))
    }

    fun setTrails(v: Float) {
        trails = v
        engine.setTrails(v)
    }

    fun applySafety() {
        engine.safety.reduceFlashing = reduceFlashing
        engine.safety.reduceMotion = reduceMotion
        engine.safety.haptics = haptics
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

    fun toggleFavourite(id: String) {
        val cur = favourites.value
        favourites.value = if (id in cur) cur - id else cur + id
    }

    fun isFavourite(id: String): Boolean = id in favourites.value
}
