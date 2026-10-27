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
        if (def.macros.isNotEmpty()) {
            val own = def.macros.map { withLogs(def, anchored(def, it)) }
            // A hand-written macro used to replace the derived ones outright,
            // which left every advanced-only value on such a sim with no simple
            // route at all. The author's macros come first; Energy is added
            // after them only if it would reach something they do not.
            val covered = own.flatMap { it.targets.keys }.toSet()
            val extra = energy(def)?.takeIf { e -> e.targets.keys.any { it !in covered } }
            return if (extra == null) own else own + withLogs(def, extra)
        }
        val derived = ArrayList<Macro>(2)
        size(def)?.let { derived.add(withLogs(def, it)) }
        energy(def)?.let { derived.add(withLogs(def, it)) }
        return derived
    }

    /**
     * Mark the targets whose own slider is logarithmic so the macro sweeps them
     * the same way. A count that runs to a hundred thousand is useless on a
     * linear sweep: every value a person wants sits in the first few percent.
     */
    private fun withLogs(def: SimDef, macro: Macro): Macro {
        if (macro.logs.isNotEmpty()) return macro
        val logs = HashSet<String>(macro.targets.size)
        for (key in macro.targets.keys) {
            val p = def.params.firstOrNull { it.key == key } ?: continue
            if (p.logScale) logs.add(key)
        }
        return if (logs.isEmpty()) macro else macro.copy(logs = logs)
    }

    /**
     * Give a hand-written macro the same promise the derived ones make: the
     * middle of the slider is where the sim already is, so touching it never
     * jerks a value the person did not ask to change. The author's ranges are
     * kept; only the shape of the sweep changes.
     */
    private fun anchored(def: SimDef, macro: Macro): Macro {
        if (macro.mids.isNotEmpty()) return macro
        val mids = HashMap<String, Float>(macro.targets.size)
        for ((key, range) in macro.targets) {
            val p = def.params.firstOrNull { it.key == key } ?: continue
            midFor(range.start, range.endInclusive, p.default)?.let { mids[key] = it }
        }
        if (mids.isEmpty()) return macro
        return macro.copy(default = 0.5f, mids = mids)
    }

    /**
     * The midpoint to anchor a target at, or null to sweep end to end.
     *
     * Anchoring the middle of the slider to the shipped value is what stops the
     * first touch from jerking something nobody asked to change, and with
     * several targets it is the only position that can reproduce all of them at
     * once: averaging the per-target positions of unanchored sweeps lands in
     * the middle and moves every one of them. A default that sits on the end of
     * its range leaves that half of the travel flat for that target, which is
     * the truth — nothing is messier than the messiest — and the macro's other
     * targets still move there.
     */
    private fun midFor(start: Float, end: Float, default: Float): Float? {
        val lo = minOf(start, end)
        val hi = maxOf(start, end)
        if (hi - lo <= 0f) return null
        return if (default in lo..hi) default else null
    }

    /**
     * "How much" — the parameter that decides the size of the run, mapped over
     * its whole range so one slider reaches from a handful to the tens of
     * thousands the engine is built for.
     */
    private fun size(def: SimDef): Macro? {
        // Any card: "how much" is the trail on a curve sim and the grid on a
        // life sim just as much as it is the count on a particle one.
        // Wide enough to be worth a slider of its own. The old bar — four
        // times the minimum plus eight — left sims whose size parameter is a
        // small count, like a chess board of 4 to 16, with nothing in Simple
        // mode at all.
        val candidates = def.params.filter {
            it.type == ParamType.INT && !it.advancedOnly && it.max >= it.min * 2f + 4f
        }
        // Largest maximum is a bad proxy for "how much of it there is": on a
        // gravity sim the trail buffer runs to forty thousand and the number of
        // bodies to four thousand, so the one slider Simple mode offers was
        // moving the trail. Prefer a parameter that names the subject, then
        // anything that is not plainly a drawing detail, and only then the
        // biggest number.
        val byName = SUBJECT_KEYS.firstNotNullOfOrNull { want ->
            candidates.firstOrNull { it.key == want }
        }
        val biggest = byName
            ?: candidates.filter { it.key !in DETAIL_KEYS }.maxByOrNull { it.max }
            ?: candidates.maxByOrNull { it.max }
            ?: return null
        val targets = LinkedHashMap<String, ClosedFloatingPointRange<Float>>()
        val mids = LinkedHashMap<String, Float>()
        targets[biggest.key] = biggest.min..biggest.max
        // The middle of the slider is what the sim was tuned to ship with.
        midFor(biggest.min, biggest.max, biggest.default)?.let { mids[biggest.key] = it }

        // A grid has two axes. Moving one of them turned "more of it" into a
        // narrow ribbon down the middle of the screen with the other axis still
        // at its shipped value, so the partner comes along in proportion.
        val partner = AXIS_PARTNERS[biggest.key]?.let { other ->
            def.params.firstOrNull {
                it.key == other && it.type == ParamType.INT && !it.advancedOnly
            }
        }
        if (partner != null && biggest.default > 0f) {
            val ratio = partner.default / biggest.default
            val low = (biggest.min * ratio).coerceIn(partner.min, partner.default)
            val high = (biggest.max * ratio).coerceIn(partner.default, partner.max)
            if (high > low) {
                targets[partner.key] = low..high
                midFor(low, high, partner.default)?.let { mids[partner.key] = it }
            }
        }

        // A size that runs ahead of the pace promises a number the sim will not
        // reach: a hundred thousand tree nodes at the shipped twenty-six a
        // second is an hour of waiting, and twenty thousand beads at thirty a
        // second is eleven minutes. Where a sim has a pace to go with its size,
        // "How much" carries both, in proportion, so the top of the slider
        // actually arrives. The midpoint still reproduces the shipped values,
        // so nothing moves until the slider is touched.
        val pace = def.params.firstOrNull {
            it.key == "rate" && it.type == ParamType.FLOAT && !it.advancedOnly && it.max > it.min
        }
        if (pace != null && biggest.default > 0f) {
            val wanted = pace.default * (biggest.max / biggest.default)
            if (wanted > pace.default * PACE_LINK_AT_LEAST) {
                val top = minOf(pace.max, wanted)
                val proportionalLow = pace.default * (biggest.min / biggest.default)
                val bottom = proportionalLow.coerceIn(pace.min, pace.default)
                if (top > bottom) {
                    targets[pace.key] = bottom..top
                    midFor(bottom, top, pace.default)?.let { mids[pace.key] = it }
                }
            }
        }
        return Macro(
            key = "derived-size",
            label = "How much",
            card = biggest.card,
            targets = targets,
            default = 0.5f,
            mids = mids
        )
    }

    /**
     * "Energy" — every live value that controls how lively the sim is, pushed
     * together. The low end is kept above each parameter's floor, because a
     * speed of zero is not a setting anybody wants.
     */
    private fun energy(def: SimDef): Macro? {
        // Every live value, the advanced-only ones included. A sim's real
        // character is often in those — a flock's neighbour range, an
        // attractor's constants, a predator model's four rates — and preferring
        // the plain ones when there were two of them left ten parameters with
        // no simple route at all, four of them integers that Evolve cannot
        // reach either. One slider reaching more is the whole point of Simple
        // mode, and the midpoint still reproduces what the sim ships with, so
        // nothing moves until it is touched.
        val targets = motionFloats(def, includeAdvanced = true)
        if (targets.size < 2) return null
        val mids = HashMap<String, Float>(targets.size)
        for (p in def.params) {
            val range = targets[p.key] ?: continue
            midFor(range.start, range.endInclusive, p.default)?.let { mids[p.key] = it }
        }
        return Macro(
            key = "derived-energy",
            label = "Energy",
            card = Card.MOTION,
            targets = targets,
            default = 0.5f,
            mids = mids
        )
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
            // Never above the shipped value: a parameter whose default sits in
            // the bottom fifth of its range — a step size, a pour rate, a sort
            // speed — could not be reproduced by any slider position, so the
            // first glance at Energy moved it, on forty-two sims.
            val low = minOf(p.min + span * LOW_END, p.default).coerceAtLeast(p.min)
            targets[p.key] = low..p.max
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
            if (range.endInclusive - range.start <= 0f) continue
            total += Macro.positionOf(
                range, macro.mids[key], values.f(key, range.start), macro.logs.contains(key)
            )
            counted++
        }
        if (counted == 0) return macro.default
        return total / counted
    }

    /**
     * Only carry the pace along when proportional scaling would move it by more
     * than half again; below that the size slider can already deliver its own top
     * end and there is no reason to touch the speed.
     */
    const val PACE_LINK_AT_LEAST = 1.5f

    /** How far above its floor a parameter sits when Energy is all the way down. */
    const val LOW_END = 0.22f

    /**
     * Parameters that name the subject of a sim, best first. "How much" picks
     * the first of these a sim declares.
     */
    private val SUBJECT_KEYS = listOf(
        "n", "count", "bodies", "beads", "balls", "pendulums", "nodes", "walkers",
        "particles", "cells", "grid", "bits", "gears", "disks", "charges", "modes",
        "sides", "dims", "limit", "dots", "samples", "rows", "width"
    )

    /**
     * Parameters that decide how finely a sim is drawn rather than how much of
     * it there is. Never the first choice for "How much".
     */
    private val DETAIL_KEYS = setOf(
        "trail", "detail", "resolution", "maxbars", "addend", "teeth", "loops",
        "points", "ink", "steps", "iterations", "segments"
    )

    /** Grid axes that have to move together. */
    private val AXIS_PARTNERS = mapOf(
        "width" to "height", "height" to "width",
        "rows" to "cols", "cols" to "rows",
        "gridx" to "gridy", "gridy" to "gridx"
    )
}
