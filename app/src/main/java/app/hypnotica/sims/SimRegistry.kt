package app.hypnotica.sims

import app.hypnotica.core.sim.Shelf
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.sim.TrapFormat
import app.hypnotica.sims.algorithms.MazeSims
import app.hypnotica.sims.algorithms.StructureSims
import app.hypnotica.sims.art.ArtSims
import app.hypnotica.sims.dimensions.DimensionSims
import app.hypnotica.sims.life.LifeSims
import app.hypnotica.sims.math.EquationSim
import app.hypnotica.sims.math.GeometrySims
import app.hypnotica.sims.math.MathSims
import app.hypnotica.sims.physics.PhysicsExtras
import app.hypnotica.sims.physics.PhysicsSims
import app.hypnotica.sims.scale.ScaleSims
import app.hypnotica.sims.sorting.SortingSim

/**
 * Everything the Library, the Feed and TickTock can reach.
 *
 * Built-in sims are fixed; recipes are added at runtime and sit alongside them
 * with no second-class treatment, which is the point of having recipes at all.
 */
object SimRegistry {

    private val builtIn: List<SimDef> by lazy {
        buildList {
            addAll(SortingSim.defs())
            addAll(MazeSims.defs())
            addAll(StructureSims.defs())
            addAll(PhysicsSims.defs())
            addAll(PhysicsExtras.defs())
            addAll(MathSims.defs())
            addAll(GeometrySims.defs())
            add(EquationSim.def())
            addAll(LifeSims.defs())
            addAll(ArtSims.defs())
            addAll(DimensionSims.defs())
            addAll(ScaleSims.defs())
        }
    }

    @Volatile
    private var recipes: List<SimDef> = emptyList()

    @Volatile
    private var cached: List<SimDef> = emptyList()

    @Volatile
    private var cachedIndex: Map<String, SimDef> = emptyMap()

    val all: List<SimDef>
        get() {
            if (cached.isEmpty() || cached.size != builtIn.size + recipes.size) rebuild()
            return cached
        }

    /**
     * Ids that have moved, so saved favourites and shared replay links keep
     * working. The particle field was declared "scale/particle-field" while its
     * shelf is Life, and "scale" is not one of the six shelves.
     */
    private val renamed = mapOf("scale/particle-field" to "life/particle-field")

    /**
     * The namespaces a sim id may start with.
     *
     * An id's prefix is a category a person can see in a shared link, so it has
     * to mean something. Every shelf is one; "sorting" is the thirteen sorts,
     * which share one file and one shelf; "recipe" is anything written in Lua.
     * The particle field once used "scale", which matched nothing at all, and
     * nothing noticed — hence the invariant test.
     */
    val idNamespaces: Set<String> =
        Shelf.entries.map { it.name.lowercase() }.toSet() + setOf("sorting", "recipe")

    private fun rebuild() {
        val merged = builtIn + recipes
        cached = merged
        // associateBy drops duplicates silently, which made the size check in
        // the getters permanently false and rebuilt the whole index on every
        // single lookup. Two recipes sharing an id is the only way to get here,
        // and the first one declared wins.
        val index = HashMap<String, SimDef>(merged.size * 2)
        for (def in merged) index.putIfAbsent(def.id, def)
        cachedIndex = index
        indexedCount = merged.size
    }

    @Volatile
    private var indexedCount = 0

    /** Replace the recipe-backed sims. Called whenever the recipe store changes. */
    fun setRecipes(defs: List<SimDef>) {
        recipes = defs
        rebuild()
    }

    fun recipeCount(): Int = recipes.size

    fun isRecipe(id: String): Boolean = recipes.any { it.id == id }

    val byId: Map<String, SimDef>
        get() {
            if (cachedIndex.isEmpty() || indexedCount != builtIn.size + recipes.size) rebuild()
            return cachedIndex
        }

    fun shelves(): List<Shelf> = Shelf.entries.filter { shelf -> all.any { it.shelf == shelf } }

    fun inShelf(shelf: Shelf): List<SimDef> = all.filter { it.shelf == shelf }

    fun families(shelf: Shelf): List<String> = inShelf(shelf).map { it.family }.distinct()

    fun withFormat(format: TrapFormat): List<SimDef> = all.filter { format in it.formats }

    fun find(id: String): SimDef? = byId[id] ?: renamed[id]?.let { byId[it] }

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
