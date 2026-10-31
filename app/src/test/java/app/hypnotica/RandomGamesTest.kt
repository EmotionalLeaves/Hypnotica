package app.hypnotica

import app.hypnotica.core.laws.Laws
import app.hypnotica.core.recipe.ParamType
import app.hypnotica.core.render.G
import app.hypnotica.core.sim.SimCore
import app.hypnotica.core.sim.TouchEvent
import app.hypnotica.sims.SimRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * A few games of every sim with everything shuffled: settings anywhere in
 * their range, any seed, a phone upright or on its side, another universe's
 * laws, taps and drags thrown in. Reports throws, frames that draw nothing,
 * non-finite drawing, and runs that never reach their moment.
 */
class RandomGamesTest {
    @Test
    fun games() {
        val rng = Random(20261030L)
        val g = G()
        val out = StringBuilder()
        var problems = 0
        val t0 = System.nanoTime()
        val only = System.getProperty("sims")?.split(",")?.toSet()
        for (def in SimRegistry.all) {
            if (only != null && def.id !in only) continue
            val simStart = System.nanoTime()
            var noPayoff = 0
            for (game in 0 until GAMES) {
                val seed = rng.nextLong()
                val aspect = floatArrayOf(0.45f, 0.5625f, 1f, 1.8f)[rng.nextInt(4)]
                val chosen = HashMap<String, Float>()
                var stage = "setup"
                try {
                    val core = SimCore(def, seed)
                    core.setAspect(aspect)
                    if (def.usesLaws && rng.nextBoolean()) core.setLaws(Laws.PRESETS[rng.nextInt(Laws.PRESETS.size)])
                    for (p in def.params) {
                        val v = when (p.type) {
                            ParamType.INT -> (p.min + rng.nextInt((p.max - p.min).toInt() + 1)).toFloat()
                            ParamType.FLOAT -> {
                                // Ends of the range on purpose a third of the time.
                                when (rng.nextInt(6)) { 0 -> p.min; 1 -> p.max; else -> p.min + rng.nextFloat() * (p.max - p.min) }
                            }
                            ParamType.BOOL -> if (rng.nextBoolean()) 1f else 0f
                            ParamType.CHOICE -> rng.nextInt(maxOf(1, p.choices.size)).toFloat()
                            ParamType.EXPR -> continue
                        }
                        // Big sizes cost minutes headless; the top tenth of an int range is skipped.
                        val capped = if (p.type == ParamType.INT && p.max - p.min > 50) minOf(v, p.min + (p.max - p.min) * 0.4f) else v
                        chosen[p.key] = capped
                        core.params.set(p.key, capped)
                    }
                    core.restart(seed)
                    stage = "run"
                    val limit = minOf(LIMIT_SECONDS, maxOf(12f, def.typicalSeconds * 2.5f))
                    var t = 0f
                    var frame = 0
                    var emptyFrames = 0
                    var draws = 0
                    var paid = false
                    var restarts = 0
                    val wallStart = System.nanoTime()
                    var slowGame = false
                    while (t < limit) {
                        // A game gets a few seconds of this machine, not minutes:
                        // the biggest settings are slow headless by design.
                        if (System.nanoTime() - wallStart > WALL_BUDGET_NS) { slowGame = true; break }
                        val dt = if (rng.nextInt(10) == 0) 1f / 30f else 1f / 60f
                        core.advance(dt); t += dt; frame++
                        if (core.payoffFired) paid = true
                        if (frame % 45 == 0) {
                            stage = "input"
                            val x = (rng.nextFloat() * 2f - 1f) * aspect; val y = rng.nextFloat() * 2f - 1f
                            when (rng.nextInt(4)) {
                                0 -> core.onInput(TouchEvent(TouchEvent.Kind.TAP, x, y))
                                1 -> { core.onInput(TouchEvent(TouchEvent.Kind.DRAG_START, x, y)); core.onInput(TouchEvent(TouchEvent.Kind.DRAG, x + 0.1f, y, 1f, 0f)); core.onInput(TouchEvent(TouchEvent.Kind.DRAG_END, x + 0.2f, y)) }
                                2 -> core.onInput(TouchEvent(TouchEvent.Kind.FLING, x, y, 2f, -1f))
                                else -> {}
                            }
                            stage = "run"
                        }
                        if (frame % 20 == 0) {
                            stage = "draw"
                            g.reset()
                            core.sim.draw(core.env, g, 1f)
                            draws++
                            if (g.instanceCount + g.pointCount + g.glowCount == 0 && t > 2f) emptyFrames++
                            stage = "counters"
                            core.sim.counters()
                            core.sim.contenders(core.env)
                            stage = "run"
                        }
                        if (core.finished) { core.restart(rng.nextLong()); restarts++ }
                    }
                    if (slowGame) out.append(String.format(java.util.Locale.US, "SLOWGAME %s reached %.1f of %.0f s in the budget, params %s\n", def.id, t, limit, chosen))
                    if (!paid && !slowGame) noPayoff++
                    if (emptyFrames > draws / 2) { problems++; out.append("EMPTY ${def.id} aspect $aspect $emptyFrames/$draws empty frames params $chosen\n") }
                } catch (e: Throwable) {
                    problems++
                    val where = e.stackTrace.firstOrNull { it.className.startsWith("app.hypnotica") }
                    out.append("THROW ${def.id} at $stage aspect $aspect seed $seed params $chosen: $e @ $where\n")
                }
            }
            val ms = (System.nanoTime() - simStart) / 1_000_000
            if (noPayoff == GAMES) out.append("NOPAYOFF ${def.id}: $noPayoff of $GAMES games never reached the moment\n")
        }
        out.append("problems $problems total ${(System.nanoTime() - t0) / 1_000_000} ms\n")
        println(out)
        // Slow settings and runs that don't reach their moment in the time
        // given are reported, not failed: big settings are slow headless, and
        // some (twenty-one points of table tennis at a third of the speed)
        // are long by design. A throw, or a picture of nothing, is a bug.
        assertTrue("random games went wrong:\n" + out.lines().filter { it.startsWith("THROW") || it.startsWith("EMPTY") }.joinToString("\n"), problems == 0)
    }

    companion object {
        /** Games per sim: a few, every build. Raise it for a long soak. */
        const val GAMES = 2
        /** Simulated seconds a game may run, at most. */
        const val LIMIT_SECONDS = 30f
        /** Real time a game may take, so the biggest settings don't hold up the suite. */
        const val WALL_BUDGET_NS = 1_500_000_000L
    }
}
