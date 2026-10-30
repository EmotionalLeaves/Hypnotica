package app.hypnotica.sims.algorithms

import app.hypnotica.core.sim.SimDef

/** The second wave of sims for this shelf; each sim lives in its own file. */
object AlgoMore {
    fun defs(): List<SimDef> = listOf(
        SudokuSim.def(),
        KMeansSim.def(),
        ConvexHullSim.def(),
        TspSim.def(),
        FloodFillSim.def(),
        SieveSim.def(),
        BlockDropSim.def(),
        FloodItSim.def()
    )
}
