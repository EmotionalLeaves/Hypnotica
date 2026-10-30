package app.hypnotica.ui

import app.hypnotica.core.sim.SimDef

/**
 * What has been found, and what is still out there.
 *
 * The Library holds over a hundred sims and the feed used to deal them in the
 * same order on every launch, so the same dozen came round first every time
 * and the rest were only ever met by scrolling. Pure functions over ids, so
 * the rules are tested without a phone.
 */
object Discovery {

    /** How many sims must have been opened before "new" means anything. */
    const val NEW_AFTER = 5

    /** The running totals worth saying out loud. */
    private val ROUND_COUNTS = intArrayOf(10, 25, 50, 75, 100, 150, 200)

    /**
     * The feed's order for one session: shuffled afresh each launch, with the
     * sims this phone has never opened dealt earlier and the favourites
     * dealt through it, so a person meets something new within a few swipes
     * and something they love every so often.
     *
     * Each sim gets a random key in [0, 1); unseen ones are pulled forward by
     * a fixed amount and favourites a little less. Ties cannot cluster a
     * whole shelf together because the keys are independent per sim.
     */
    fun feedOrder(
        all: List<SimDef>,
        seen: Set<String>,
        favourites: Set<String>,
        seed: Long
    ): List<SimDef> {
        if (all.size < 2) return all
        val rng = java.util.Random(seed)
        // Only once something has been seen does "unseen" sort anything: on
        // a fresh install every sim is unseen, and the bias would do nothing.
        val keyed = all.map { def ->
            var key = rng.nextDouble()
            if (seen.isNotEmpty() && def.id !in seen) key -= UNSEEN_PULL
            if (def.id in favourites) key -= FAVOURITE_PULL
            key to def
        }
        val sorted = keyed.sortedBy { it.first }.map { it.second }.toMutableList()
        // No two sims of the same family back to back where it can be helped:
        // the thirteen sorts share one bar chart and met in a row read as a
        // repeat rather than a new thing.
        for (i in 1 until sorted.size) {
            if (sorted[i].family != sorted[i - 1].family) continue
            val swap = (i + 1 until sorted.size).firstOrNull { j ->
                sorted[j].family != sorted[i - 1].family
            } ?: continue
            val t = sorted[i]; sorted[i] = sorted[swap]; sorted[swap] = t
        }
        return sorted
    }

    /**
     * The line to show when [id] is opened for the first time, or null.
     *
     * A finished shelf outranks a round number, and the whole Library
     * outranks both. [seenBefore] is the set before this sim was added.
     */
    fun milestone(seenBefore: Set<String>, id: String, all: List<SimDef>): String? {
        if (id in seenBefore) return null
        val def = all.firstOrNull { it.id == id } ?: return null
        val ids = all.mapTo(HashSet()) { it.id }
        val found = seenBefore.count { it in ids } + 1
        if (found == all.size) return "Every sim found — all ${all.size}"
        val shelf = all.filter { it.shelf == def.shelf }
        if (shelf.size > 1 && shelf.all { it.id == id || it.id in seenBefore }) {
            return "${def.shelf.title} shelf complete — all ${shelf.size} found"
        }
        if (found in ROUND_COUNTS) return "$found of ${all.size} sims found"
        return null
    }

    /**
     * The day's pick: one sim, the same all day, chosen from those this phone
     * has not opened while there are any. Decided once a day and kept (see
     * AppState), because opening it makes it seen and a pick recomputed from
     * the unseen would then jump to another.
     */
    fun dailyPick(all: List<SimDef>, seen: Set<String>, epochDay: Long): SimDef? {
        if (all.isEmpty()) return null
        val pool = all.filter { it.id !in seen }.ifEmpty { all }
        // Mixed, so consecutive days are not neighbours in registry order.
        val h = (epochDay * 0x9E3779B97F4A7C15uL.toLong()) ushr 17
        return pool[(h % pool.size).toInt()]
    }

    /** How many of [all] are in [seen]. */
    fun foundCount(seen: Set<String>, all: List<SimDef>): Int = all.count { it.id in seen }

    /** Whether a sim should be marked new: unseen, once marking means something. */
    fun isNew(id: String, seen: Set<String>): Boolean = seen.size >= NEW_AFTER && id !in seen

    private const val UNSEEN_PULL = 0.35
    private const val FAVOURITE_PULL = 0.15
}
