package app.hypnotica.sims.life

import app.hypnotica.core.sim.SimDef

/** The second wave of sims for this shelf; each sim lives in its own file. */
object LifeMore {
    fun defs(): List<SimDef> = listOf(
        PhysarumSim.def(),
        ForestFireSim.def()
    )
}
