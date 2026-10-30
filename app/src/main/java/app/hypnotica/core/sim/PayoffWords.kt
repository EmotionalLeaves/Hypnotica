package app.hypnotica.core.sim

/**
 * The word popped over the picture when a sim's moment lands, for sims that
 * don't name it themselves (battles call their own KOs and winners). Short,
 * and true of every run: a sort is always sorted at its payoff, a maze always
 * solved. Sims whose moment is quiet — art, slow growth, the dimensions
 * shelf's thought experiments — have none, on purpose.
 */
object PayoffWords {

    private val words = mapOf(
        "algorithms/ball-sort" to "Sorted!",
        "algorithms/convex-hull" to "Wrapped!",
        "algorithms/flood-fill" to "Filled!",
        "algorithms/flood-it" to "One colour!",
        "algorithms/gradient-descent" to "Bottom!",
        "algorithms/graph-layout" to "Untangled!",
        "algorithms/hanoi" to "Solved!",
        "algorithms/k-means" to "Converged!",
        "algorithms/maze" to "Solved!",
        "algorithms/maze-race" to "Winner!",
        "algorithms/n-queens" to "Solution!",
        "algorithms/pathfinding" to "Path found!",
        "algorithms/perfect-pack" to "Perfect fit!",
        "algorithms/sieve" to "All primes!",
        "algorithms/snake" to "Perfect snake!",
        "algorithms/sudoku" to "Solved!",
        "algorithms/tsp-2opt" to "No crossings!",
        "algorithms/block-drop" to "Cleared!",
        "art/circle-packing" to "Packed!",
        "art/fireworks" to "Finale!",
        "art/spirograph" to "Closed!",
        "life/langtons-ant" to "Highway!",
        "life/coral" to "Reached the top!",
        "math/birthday-paradox" to "A match!",
        "math/collatz" to "Back to 1!",
        "math/dragon-curve" to "Folded!",
        "math/hilbert-curve" to "Filled!",
        "math/monte-carlo-pi" to "π!",
        "math/buffon-needle" to "π!",
        "math/monty-hall" to "Switch wins!",
        "math/riemann" to "Exact!",
        "math/mandelbrot" to "The whole set again!",
        "physics/billiards-break" to "Run out!",
        "physics/bubble-wrap" to "All popped!",
        "physics/double-pendulum" to "Chaos!",
        "physics/pour" to "Full!",
        "physics/press" to "Crushed!",
        "physics/trebuchet" to "Direct hit!",
        "physics/sandpile" to "Avalanche!",
        "physics/brachistochrone" to "The curve wins!",
        "physics/power-wash" to "Spotless!",
        "physics/traffic" to "Jam!",
        "physics/sixteenth-digit" to "Diverged!",
        "physics/metronome-sync" to "In sync!",
        "physics/pendulum-wave" to "Back in line!",
        "physics/galton-board" to "Bell curve!",
        "physics/ellipse-pool" to "Reunited!",
        "physics/wheel" to "Winner!",
        "sorting/race" to "Winner!",
    )

    fun of(id: String): String? =
        words[id] ?: if (id.startsWith("sorting/")) "Sorted!" else null
}
