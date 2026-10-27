package app.hypnotica.core.sim

import app.hypnotica.core.laws.Laws
import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Macro
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.recipe.ParamValues
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette

/** Top-level grouping in the Library. */
enum class Shelf(val title: String) {
    ALGORITHMS("Algorithms"),
    PHYSICS("Physics"),
    MATH("Math"),
    DIMENSIONS("Dimensions"),
    LIFE("Life & nature"),
    ART("Art & music")
}

/** A hook wrapper that can be laid over many sims. */
enum class TrapFormat(val title: String, val hook: String) {
    ESCAPE("Will it escape?", "Will it escape?"),
    RACE("Marble race", "Pick a colour"),
    COLOR_BATTLE("Colour battle", "Which colour wins?"),
    NUMBER_UP("Number go up", "How high does it go?"),
    CHAIN("Chain reaction", "One tap, then this"),
    PERFECT_FIT("Perfect fit", "Watch it fit"),
    CUT("Cut, crush, squish", "Wait for the squish"),
    REVEAL("Clean and reveal", "What is under it?"),
    FILL("Fill to the line", "Stop at the line"),
    GROW("Grow and build", "Watch it grow"),
    SONG("Song mode", "The bounces play the tune"),
    SPLIT("Split screen", "Top or bottom?"),
    LAST_STANDING("Last one standing", "Last one standing"),
    LOOP("Perfect loop", "It never restarts"),
    GUESS("Guess the winner", "Guess before it ends"),
    SNAP("Slow build, sudden snap", "Wait for it")
}

/** Touch input handed to a sim. */
data class TouchEvent(
    val kind: Kind,
    val x: Float,
    val y: Float,
    val vx: Float = 0f,
    val vy: Float = 0f
) {
    enum class Kind { TAP, DRAG_START, DRAG, DRAG_END, FLING, LONG_PRESS }
}

/**
 * Everything a sim is allowed to see: the seeded generator, the laws of its
 * universe, its live parameters, and the event sink that drives sound and juice.
 */
class SimEnv(
    val rng: Rng,
    var laws: Laws,
    var params: ParamValues,
    val events: EventQueue
) {
    /** Simulated seconds since setup. */
    var time: Float = 0f
        internal set

    /** Aspect ratio (width / height) of the view, so sims can use the full screen. */
    var aspect: Float = 0.5f
        internal set

    var palette: Palette = Palette.SPECTRUM

    /** Set by the player; sims should keep motion gentle when true. */
    var reduceMotion: Boolean = false

    fun emit(kind: EventKind, value: Float, pan: Float = 0f, strength: Float = 1f) =
        events.emit(kind, value, pan, strength)

    /** Half-width of the visible world (half-height is always 1). */
    val halfWidth: Float get() = aspect
}

/**
 * One simulation.
 *
 * [step] advances by a fixed dt and emits events; [draw] renders the state with
 * [alpha] interpolating between the last two steps so motion stays smooth even
 * when the display rate and the step rate differ.
 */
interface Sim {
    fun setup(env: SimEnv)
    fun step(env: SimEnv, dt: Float)
    fun draw(env: SimEnv, g: G, alpha: Float)

    fun onInput(env: SimEnv, e: TouchEvent) {}

    /** True on the frame the satisfying moment lands. Drives slow-mo and the sweep. */
    fun payoff(): Boolean = false

    /** True once the run is over; the player loops, advances or resolves. */
    fun finished(): Boolean = false

    /** Short live counters shown as an overlay, e.g. "comparisons 1,204". */
    fun counters(): List<Pair<String, String>> = emptyList()
}

/** Metadata plus the factory for one sim. */
data class SimDef(
    val id: String,
    val title: String,
    val shelf: Shelf,
    val family: String,
    val blurb: String = "",
    val formats: List<TrapFormat> = emptyList(),
    val params: List<Param> = emptyList(),
    val macros: List<Macro> = emptyList(),
    /** The one big button in Simple mode. */
    val heroLabel: String = "Shuffle",
    /** True: the hero button starts a fresh run. False: it taps the sim instead. */
    val heroRestarts: Boolean = true,
    val defaultPalette: String = "Spectrum",
    val defaultSound: String = "Music box",
    /** Typical length of a satisfying run, used by TickTock and Studio templates. */
    val typicalSeconds: Float = 24f,
    val usesLaws: Boolean = false,
    val create: () -> Sim
) {
    fun newParamValues(): ParamValues = ParamValues(params)

    /** Parameters a Simple-mode card should show. */
    fun simpleParams(card: Card): List<Param> =
        params.filter { !it.advancedOnly && it.card == card }
}
