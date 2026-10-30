package app.hypnotica.sims.math

import app.hypnotica.core.sim.SimDef

/** The second wave of sims for this shelf; each sim lives in its own file. */
object MathMore {
    fun defs(): List<SimDef> = listOf(
        PhyllotaxisSim.def(),
        TimesTableSim.def(),
        HilbertCurveSim.def(),
        DragonCurveSim.def(),
        BirthdayParadoxSim.def(),
        PenroseTilingSim.def(),
        LissajousTableSim.def(),
        MontyHallSim.def(),
        BuffonNeedleSim.def()
    )
}
