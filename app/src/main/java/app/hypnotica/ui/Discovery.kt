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
        // A big family is dealt thinly: its first member keeps its place
        // and the rest drift later. There are thirteen sorts, and a feed
        // that met one every eight swipes was mostly bar charts.
        val spread = keyed.groupBy { it.second.family }.values.flatMap { members ->
            members.sortedBy { it.first }.mapIndexed { r, (key, def) ->
                (if (r >= 1) key + FAMILY_DRIFT * r else key) to def
            }
        }
        val pool = spread.sortedBy { it.first }.map { it.second }.toMutableList()
        // Dealt in key order, but never the same family twice running where
        // something else is left — and when one family is more than half of
        // what remains, it goes now, so the end of the deal is not a run of it.
        val left = HashMap<String, Int>()
        for (item in pool) left[item.family] = (left[item.family] ?: 0) + 1
        val sorted = ArrayList<SimDef>(pool.size)
        while (pool.isNotEmpty()) {
            val last = sorted.lastOrNull()?.family
            val crowd = left.maxByOrNull { it.value }!!
            val pick = when {
                crowd.key != last && crowd.value * 2 > pool.size + 1 -> pool.indexOfFirst { it.family == crowd.key }
                else -> pool.indexOfFirst { it.family != last }.let { if (it < 0) 0 else it }
            }
            val d = pool.removeAt(pick)
            sorted.add(d)
            left[d.family] = left.getValue(d.family) - 1
            if (left.getValue(d.family) == 0) left.remove(d.family)
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
    private const val FAMILY_DRIFT = 0.2
}
