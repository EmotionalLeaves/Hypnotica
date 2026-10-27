package app.hypnotica.sims

import app.hypnotica.core.sim.Shelf
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.sim.TrapFormat
import app.hypnotica.sims.algorithms.MazeSims
import app.hypnotica.sims.art.ArtSims
import app.hypnotica.sims.dimensions.DimensionSims
import app.hypnotica.sims.life.LifeSims
import app.hypnotica.sims.math.MathSims
import app.hypnotica.sims.physics.PhysicsSims
import app.hypnotica.sims.scale.ScaleSims
import app.hypnotica.sims.sorting.SortingSim

/** Everything the Library, the Feed and TickTock can reach. */
object SimRegistry {

    val all: List<SimDef> by lazy {
        buildList {
            addAll(SortingSim.defs())
            addAll(MazeSims.defs())
            addAll(PhysicsSims.defs())
            addAll(MathSims.defs())
            addAll(LifeSims.defs())
            addAll(DimensionSims.defs())
            addAll(ArtSims.defs())
            addAll(ScaleSims.defs())
        }
    }

    val byId: Map<String, SimDef> by lazy { all.associateBy { it.id } }

    fun shelves(): List<Shelf> = Shelf.entries.filter { shelf -> all.any { it.shelf == shelf } }

    fun inShelf(shelf: Shelf): List<SimDef> = all.filter { it.shelf == shelf }

    fun families(shelf: Shelf): List<String> =
        inShelf(shelf).map { it.family }.distinct()

    fun withFormat(format: TrapFormat): List<SimDef> = all.filter { format in it.formats }

    fun find(id: String): SimDef? = byId[id]

    fun search(query: String): List<SimDef> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all
        return all.filter {
            it.title.lowercase().contains(q) ||
                it.family.lowercase().contains(q) ||
                it.blurb.lowercase().contains(q) ||
                it.shelf.title.lowercase().contains(q)
        }
    }

    /** A sensible first sim: something moving within a second of opening the app. */
    fun defaultSim(): SimDef = find("physics/pendulum-wave") ?: all.first()
}
