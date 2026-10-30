package app.hypnotica.sims.physics

import app.hypnotica.core.sim.SimDef

/** The second wave of sims for this shelf; each sim lives in its own file. */
object PhysicsMore {
    fun defs(): List<SimDef> = listOf(
        SlinkyDropSim.def(),
        BrachistochroneSim.def(),
        MagnetPendulumSim.def(),
        MetronomeSyncSim.def(),
        BilliardsSim.def(),
        TrebuchetSim.def(),
        BrickBreakerSim.def(),
        StackSim.def(),
        RallySim.def(),
        BubbleWrapSim.def(),
        ThreeStarsSim.def(),
        TwinStarsSim.def(),
        SafeZonesSim.def()
    )
}
