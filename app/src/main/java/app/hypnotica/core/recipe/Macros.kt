package app.hypnotica.core.recipe

import app.hypnotica.core.sim.SimDef

/**
 * Simple mode's big controls.
 *
 * Simple mode is not meant to be Advanced mode with things taken away: one
 * slider here should move several of the exact values at once, so that a person
 * who never opens Advanced can still reach everything the sim can do.
 *
 * A sim can declare its own hand-tuned macros. Where it does not, two are
 * derived from its parameters — how much of it there is, and how lively it is —
 * which is enough to make any sim worth playing with from the Simple cards.
 */
object Macros {

    /**
     * The macros to show for [def]: its own if it has any, otherwise derived.
     */
    fun forDef(def: SimDef): List<Macro> {
        if (def.macros.isNotEmpty()) return def.macros
        val derived = ArrayList<Macro>(2)
        size(def)?.let { derived.add(it) }
        energy(def)?.let { derived.add(it) }
        return derived
    }

    /**
     * "How much" — the parameter that decides the size of the run, mapped over
     * its whole range so one slider reaches from a handful to the tens of
     * thousands the engine is built for.
     */
    private fun size(def: SimDef): Macro? {
        // Any card: "how much" is the trail on a curve sim and the grid on a
        // life sim just as much as it is the count on a particle one.
        val candidates = def.params.filter {
            it.type == ParamType.INT && !it.advancedOnly && it.max >= it.min * 4f + 8f
        }
        val biggest = candidates.maxByOrNull { it.max } ?: return null
        return Macro(
            key = "derived-size",
            label = "How much",
            card = biggest.card,
            targets = mapOf(biggest.key to biggest.min..biggest.max)
        )
    }

    /**
     * "Energy" — every live value that controls how lively the sim is, pushed
     * together. The low end is kept above each parameter's floor, because a
     * speed of zero is not a setting anybody wants.
     */
    private fun energy(def: SimDef): Macro? {
        val plain = motionFloats(def, includeAdvanced = false)
        // A sim with one visible speed slider often keeps its real character in
        // the advanced values; one macro should still reach them.
        val targets = if (plain.size >= 2) plain else motionFloats(def, includeAdvanced = true)
        if (targets.size < 2) return null
        return Macro(key = "derived-energy", label = "Energy", card = Card.MOTION, targets = targets)
    }

    private fun motionFloats(
        def: SimDef,
        includeAdvanced: Boolean
    ): HashMap<String, ClosedFloatingPointRange<Float>> {
        val targets = HashMap<String, ClosedFloatingPointRange<Float>>()
        for (p in def.params) {
            if (p.type != ParamType.FLOAT || p.card != Card.MOTION) continue
            if (p.advancedOnly && !includeAdvanced) continue
            val span = p.max - p.min
            if (span <= 0f) continue
            targets[p.key] = (p.min + span * LOW_END)..p.max
        }
        return targets
    }

    /**
     * Where the slider should sit so that nothing moves until it is touched:
     * the position whose values match what the sim is already running.
     */
    fun positionFor(macro: Macro, values: ParamValues): Float {
        var total = 0f
        var counted = 0
        for ((key, range) in macro.targets) {
            val span = range.endInclusive - range.start
            if (span <= 0f) continue
            val current = values.f(key, range.start)
            total += ((current - range.start) / span).coerceIn(0f, 1f)
            counted++
        }
        if (counted == 0) return macro.default
        return total / counted
    }

    /** How far above its floor a parameter sits when Energy is all the way down. */
    const val LOW_END = 0.22f
}
