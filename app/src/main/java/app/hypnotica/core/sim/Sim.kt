package app.hypnotica.core.sim

import app.hypnotica.core.laws.Laws
import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Macro
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.recipe.ParamValues
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette

/** Top-level grouping in the Library. */
/**
 * A group of laws a sim can honour, as one thing a person can be offered.
 *
 * Grouped rather than one entry per field: "Contact" is sticky and pass-through
 * together because a sim that bounces reads both, and "Pairwise force" is the
 * gravitational constant, the falloff, the mass sign and the typed force law,
 * which only mean anything together.
 */
enum class LawKnob(val title: String) {
    GRAVITY("Gravity"),
    GRAVITY_DIRECTION("Which way is down"),
    PAIRWISE_FORCE("The force between masses"),
    BOUNCE("Bounciness"),
    FRICTION("Friction"),
    CONTACT("What happens on contact"),
    LIGHT_SPEED("The speed of light"),
    CHARGE("Charge"),
    SPACE("The shape of space")
}

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
    /**
     * Written from the UI thread when a law is edited and read by the sim on
     * every step, so a step cannot see half of a change. Laws itself is
     * immutable, so publishing the new reference is the whole job.
     */
    @Volatile
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
    /**
     * Typical length of a satisfying run: long enough to contain the payoff
     * with room to breathe after it.
     *
     * Read by TickTock's pre-check, which will not offer a seed whose payoff
     * lands outside a share of this, and by Studio's templates, which place
     * their keyframes as shares of it rather than at fixed seconds. The doc
     * comment here claimed both of those for a long time while neither was
     * true — nothing read it at all — which is why the declared lengths drifted
     * far enough that one sim's payoff landed at 5% of its run and another's
     * eight seconds after the end. ArcTest is what holds them together now.
     */
    val typicalSeconds: Float = 24f,
    /**
     * The laws this sim actually reads.
     *
     * The Laws card used to be the same on every sim that showed it, so six of
     * its controls plus a formula box were no-ops on whatever was in front of
     * you — and each one renamed the universe to "Custom" while the picture
     * stayed exactly the same. A sim lists what it honours and the card offers
     * that, which is also the only way the guide's claim that changing a law
     * changes the sim can be true.
     */
    val lawKnobs: Set<LawKnob> = emptySet(),
    val create: () -> Sim
) {
    /** True when the Laws card has anything to offer on this sim. */
    val usesLaws: Boolean get() = lawKnobs.isNotEmpty()

    fun newParamValues(): ParamValues = ParamValues(params)

    /** Parameters a Simple-mode card should show. */
    fun simpleParams(card: Card): List<Param> =
        params.filter { !it.advancedOnly && it.card == card }
}
