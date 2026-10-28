package app.hypnotica.sims.sorting

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Macro
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.Shelf
import app.hypnotica.core.sim.Sim
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.sim.SimEnv
import app.hypnotica.core.sim.SortArray
import app.hypnotica.core.sim.SortAlgorithm
import app.hypnotica.core.sim.TrapFormat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** The shapes the Data card offers, in the order the tiles appear. */
enum class DataShape(val title: String) {
    RANDOM("Random"), REVERSED("Reversed"), ALMOST("Almost sorted"),
    REPEATS("Repeats"), WAVE("Wave"), SAWTOOTH("Sawtooth")
}

enum class SortView(val title: String) {
    BARS("Bars"), DOTS("Dots"), WHEEL("Colour wheel"), SPIRAL("Spiral"), DISPARITY("Disparity")
}

class SortingSim(private val algorithmIndex: Int?) : Sim {

    private lateinit var array: SortArray
    private lateinit var algo: SortAlgorithm
    private var values = FloatArray(0)
    private var n = 0

    private var done = false
    private var payoffFrame = false
    private var sweepPos = -1f
    private var sweepSpeed = 0f
    private var restSeconds = -1f
    private var opsCarry = 0f
    private var opsPerSecond = 600f
    private var view = SortView.BARS

    override fun setup(env: SimEnv) {
        n = env.params.i("n", 220).coerceIn(8, 200_000)
        val shapeIdx = env.params.choiceIndex("shape")
        val shape = DataShape.entries[shapeIdx.coerceIn(0, DataShape.entries.size - 1)]
        val messiness = env.params.f("messiness", 1f)

        values = FloatArray(n)
        buildShape(shape, messiness, env)

        array = SortArray(values, env)
        algo = if (algorithmIndex != null) Algorithms.byIndex(algorithmIndex)
        else Algorithms.byIndex(env.params.choiceIndex("algorithm"))
        algo.reset(n)

        view = SortView.entries[env.params.choiceIndex("view").coerceIn(0, SortView.entries.size - 1)]

        // Pace the run so it lands in a watchable window whatever the size.
        val target = env.params.f("duration", 26f).coerceIn(4f, 180f)
        opsPerSecond = (algo.estimatedOps(n).toFloat() / target).coerceIn(30f, 4_000_000f)
        opsCarry = 0f
        done = false
        payoffFrame = false
        sweepPos = -1f
        restSeconds = -1f
        array.sweep = -1
        sweepSpeed = max(n / 1.1f, 60f)
    }

    private fun buildShape(shape: DataShape, messiness: Float, env: SimEnv) {
        val rng = env.rng
        for (i in 0 until n) {
            val t = if (n == 1) 0.5f else i.toFloat() / (n - 1)
            values[i] = when (shape) {
                DataShape.RANDOM -> rng.nextFloat()
                DataShape.REVERSED -> 1f - t
                DataShape.ALMOST -> t
                // Eight levels; t = 1 used to make a ninth, alone and taller than the rest.
                DataShape.REPEATS -> (min(7, (t * 8f).toInt()) / 8f) + 0.0625f
                DataShape.WAVE -> 0.5f + 0.45f * sin(t * PI.toFloat() * 4f)
                DataShape.SAWTOOTH -> (t * 5f) % 1f
            }
        }
        val mess = messiness.coerceIn(0f, 1f)
        when (shape) {
            DataShape.RANDOM -> {
                // Messiness pulls a random array back towards sorted.
                if (mess < 1f) {
                    values.sort()
                    val swaps = (n * mess * 0.6f).toInt()
                    repeat(swaps) {
                        val i = rng.int(0, n)
                        val j = rng.int(0, n)
                        val t = values[i]; values[i] = values[j]; values[j] = t
                    }
                }
            }
            DataShape.ALMOST -> {
                val swaps = (n * mess * 0.05f).toInt().coerceAtLeast(1)
                repeat(swaps) {
                    val i = rng.int(0, n - 1)
                    val t = values[i]; values[i] = values[i + 1]; values[i + 1] = t
                }
            }
            else -> {
                val swaps = (n * mess * 0.25f).toInt()
                repeat(swaps) {
                    val i = rng.int(0, n)
                    val j = rng.int(0, n)
                    val t = values[i]; values[i] = values[j]; values[j] = t
                }
            }
        }
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        if (restSeconds >= 0f) {
            // Let the resolve breathe before the run is declared over.
            restSeconds -= kotlin.math.abs(dt)
            return
        }

        if (!done) {
            val speedBoost = env.params.f("effort", 1f)
            opsCarry += opsPerSecond * speedBoost * kotlin.math.abs(dt)
            var budget = min(opsCarry.toInt(), MAX_OPS_PER_STEP)
            opsCarry -= budget
            while (budget > 0) {
                if (!algo.advance(array)) {
                    done = true
                    payoffFrame = true
                    sweepPos = 0f
                    array.clearHighlight()
                    break
                }
                budget--
            }
            return
        }

        // The final sweep: run a verify pass across the array with rising notes.
        if (sweepPos < n) {
            val before = sweepPos.toInt()
            sweepPos += sweepSpeed * kotlin.math.abs(dt)
            val after = min(sweepPos.toInt(), n)
            var i = before
            var emitted = 0
            while (i < after && emitted < 24) {
                val t = if (n <= 1) 0f else i.toFloat() / (n - 1)
                env.emit(EventKind.WRITE, t, (t * 2f - 1f) * 0.8f, 0.5f)
                i += max(1, (after - before) / 24)
                emitted++
            }
            array.sweep = after
            if (sweepPos >= n) {
                restSeconds = 1.4f
                env.emit(EventKind.DONE, 1f, 0f, 1f)
            }
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val halfW = env.halfWidth * 0.94f
        val sweepIndex = array.sweep
        val usePoints = n > POINT_THRESHOLD

        when (view) {
            SortView.BARS -> drawBars(g, palette, halfW, sweepIndex, usePoints)
            SortView.DOTS -> drawDots(g, palette, halfW, sweepIndex, usePoints)
            SortView.WHEEL -> drawWheel(g, palette, sweepIndex, usePoints)
            SortView.SPIRAL -> drawSpiral(g, palette, sweepIndex, usePoints)
            SortView.DISPARITY -> drawDisparity(g, palette, sweepIndex, usePoints)
        }

    }

    private fun highlight(index: Int): Boolean = index == array.lastA || index == array.lastB

    private fun drawBars(g: G, palette: Palette, halfW: Float, sweepIndex: Int, usePoints: Boolean) {
        val barW = 2f * halfW / n
        val baseY = -0.92f
        if (usePoints) {
            g.reservePoints(n)
            val size = max(barW, 0.0015f)
            for (i in 0 until n) {
                val v = values[i]
                val x = -halfW + (i + 0.5f) * barW
                val c = if (i < sweepIndex) Palette.SUCCESS else palette.at(v)
                g.point(x, baseY + v * 1.78f * 0.5f, size * 2f, c)
            }
            return
        }
        g.reserveInstances(n)
        val hw = max(barW * 0.42f, 0.0008f)
        for (i in 0 until n) {
            val v = values[i]
            val x = -halfW + (i + 0.5f) * barW
            val h = v * 1.78f
            var c = if (i < sweepIndex) Palette.SUCCESS else palette.at(v)
            if (highlight(i)) c = Palette.brighten(c, 0.55f)
            g.bar(x, baseY, hw, h, c)
        }
        if (n <= 400) {
            if (array.lastA in 0 until n) g.glow(
                -halfW + (array.lastA + 0.5f) * barW,
                baseY + values[array.lastA] * 1.78f,
                barW * 4f, palette.at(values[array.lastA]), 0.7f
            )
        }
        if (sweepIndex in 1 until n) {
            val x = -halfW + sweepIndex * barW
            g.rect(x, 0f, barW * 0.6f, 1f, Palette.SUCCESS, 0f, 0.5f)
            g.glow(x, 0f, 0.12f, Palette.SUCCESS, 0.8f)
        }
    }

    private fun drawDots(g: G, palette: Palette, halfW: Float, sweepIndex: Int, usePoints: Boolean) {
        val step = 2f * halfW / n
        val r = max(step * 0.4f, 0.002f)
        if (usePoints) {
            g.reservePoints(n)
            for (i in 0 until n) {
                val v = values[i]
                val c = if (i < sweepIndex) Palette.SUCCESS else palette.at(v)
                g.point(-halfW + (i + 0.5f) * step, v * 1.8f - 0.9f, r * 2.2f, c)
            }
            return
        }
        g.reserveInstances(n)
        for (i in 0 until n) {
            val v = values[i]
            var c = if (i < sweepIndex) Palette.SUCCESS else palette.at(v)
            if (highlight(i)) c = Palette.brighten(c, 0.6f)
            g.disc(-halfW + (i + 0.5f) * step, v * 1.8f - 0.9f, r, c)
        }
    }

    private fun drawWheel(g: G, palette: Palette, sweepIndex: Int, usePoints: Boolean) {
        val radius = 0.86f
        if (usePoints) g.reservePoints(n) else g.reserveInstances(n)
        for (i in 0 until n) {
            val v = values[i]
            val ang = (i.toFloat() / n) * 2f * PI.toFloat()
            val rr = radius * (0.35f + 0.65f * v)
            val x = cos(ang) * rr
            val y = sin(ang) * rr
            var c = if (i < sweepIndex) Palette.SUCCESS else Palette.hsv(v * 360f, 0.75f, 1f)
            if (!usePoints && highlight(i)) c = Palette.brighten(c, 0.6f)
            if (usePoints) g.point(x, y, 0.01f, c) else g.disc(x, y, max(0.9f / n, 0.004f), c)
        }
    }

    private fun drawSpiral(g: G, palette: Palette, sweepIndex: Int, usePoints: Boolean) {
        if (usePoints) g.reservePoints(n) else g.reserveInstances(n)
        val turns = 5f
        for (i in 0 until n) {
            val t = if (n <= 1) 0f else i.toFloat() / (n - 1)
            val v = values[i]
            val ang = t * turns * 2f * PI.toFloat()
            val rr = 0.12f + 0.78f * sqrt(t) * (0.55f + 0.45f * v)
            val x = cos(ang) * rr
            val y = sin(ang) * rr
            var c = if (i < sweepIndex) Palette.SUCCESS else palette.at(v)
            if (!usePoints && highlight(i)) c = Palette.brighten(c, 0.6f)
            if (usePoints) g.point(x, y, 0.009f, c) else g.disc(x, y, max(0.8f / n, 0.0045f), c)
        }
    }

    private fun drawDisparity(g: G, palette: Palette, sweepIndex: Int, usePoints: Boolean) {
        if (usePoints) g.reservePoints(n) else g.reserveInstances(n)
        for (i in 0 until n) {
            val t = if (n <= 1) 0f else i.toFloat() / (n - 1)
            val v = values[i]
            val ang = t * 2f * PI.toFloat()
            // Radius shows how far a value is from where it belongs.
            val err = kotlin.math.abs(v - t)
            val rr = 0.9f - err * 0.78f
            val x = cos(ang) * rr
            val y = sin(ang) * rr
            var c = if (i < sweepIndex) Palette.SUCCESS else palette.at(v)
            if (!usePoints && highlight(i)) c = Palette.brighten(c, 0.6f)
            if (usePoints) g.point(x, y, 0.009f, c) else g.disc(x, y, max(0.8f / n, 0.0045f), c)
        }
        g.ring(0f, 0f, 0.9f, 0.004f, Palette.TEXT_SECONDARY, alpha = 0.35f)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = done && sweepPos >= n && restSeconds in -1000f..0f

    override fun counters(): List<Pair<String, String>> = listOf(
        "comparisons" to formatCount(array.comparisons),
        "writes" to formatCount(array.writes)
    )

    private fun formatCount(v: Long): String =
        if (v < 10_000) v.toString() else String.format(java.util.Locale.US, "%,d", v)

    companion object {
        const val MAX_OPS_PER_STEP = 400_000
        const val POINT_THRESHOLD = 6_000

        private fun sharedParams(): List<Param> = listOf(
            Param.choice(
                "shape", "Data shape", DataShape.entries.map { it.title }, 0,
                card = Card.DATA, input = app.hypnotica.core.recipe.ParamInput.TILES
            ),
            Param.float("messiness", "Messiness", 0f, 1f, 1f, card = Card.DATA),
            Param.int("n", "Size", 8, 200_000, 220, card = Card.DATA, unit = "bars", logScale = true),
            Param.choice(
                "view", "View", SortView.entries.map { it.title }, 0,
                card = Card.LOOK, input = app.hypnotica.core.recipe.ParamInput.TILES
            ),
            Param.float("duration", "Run length", 4f, 180f, 26f, card = Card.MOTION, unit = "s"),
            Param.float("effort", "Effort", 0.1f, 6f, 1f, card = Card.MOTION, advancedOnly = true)
        )

        private fun macros(): List<Macro> = listOf(
            Macro(
                "chaos", "Chaos", Card.MOTION,
                mapOf("messiness" to 0f..1f, "effort" to 0.4f..3f), 0.6f
            ),
            Macro("size", "Size", Card.DATA, mapOf("n" to 24f..20000f), 0.02f)
        )

        /** One SimDef per algorithm, plus a "pick your own" entry. */
        fun defs(): List<SimDef> {
            val list = ArrayList<SimDef>()
            list.add(
                SimDef(
                    id = "sorting/choose",
                    title = "Sorting lab",
                    shelf = Shelf.ALGORITHMS,
                    family = "Sorting algorithms",
                    blurb = "Any of twelve sorts, five views, one array.",
                    formats = listOf(TrapFormat.GUESS, TrapFormat.SONG),
                    params = listOf(
                        Param.choice("algorithm", "Algorithm", Algorithms.NAMES, 0, card = Card.MOTION)
                    ) + sharedParams(),
                    macros = macros(),
                    heroLabel = "Shuffle",
                    typicalSeconds = 28f,
                    create = { SortingSim(null) }
                )
            )
            Algorithms.NAMES.forEachIndexed { index, name ->
                list.add(
                    SimDef(
                        id = "sorting/" + name.lowercase().replace(" ", "-").replace("(", "").replace(")", ""),
                        title = name,
                        shelf = Shelf.ALGORITHMS,
                        family = "Sorting algorithms",
                        blurb = "Watch $name find the order.",
                        formats = listOf(TrapFormat.GUESS, TrapFormat.SONG, TrapFormat.RACE),
                        params = sharedParams(),
                        macros = macros(),
                        heroLabel = "Shuffle",
                        typicalSeconds = 26f,
                        create = { SortingSim(index) }
                    )
                )
            }
            list.add(SortRaceSim.def())
            return list
        }
    }
}
