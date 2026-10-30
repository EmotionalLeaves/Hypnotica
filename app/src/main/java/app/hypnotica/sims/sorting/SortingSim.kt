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
                // No DONE here: the payoff already sounded as the sort finished
                // and the sweep set off, and a second ending a second later, as
                // the sweep reached the top, played the payoff twice. The
                // sweep's own rising run is the cadence; its top note, louder.
                env.emit(EventKind.WRITE, 1f, 0.8f, 0.9f)
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
            SortView.WHEEL -> drawWheel(g, palette, sweepIndex, usePoints, roundScale(env))
            SortView.SPIRAL -> drawSpiral(g, palette, sweepIndex, usePoints, roundScale(env))
            SortView.DISPARITY -> drawDisparity(g, palette, sweepIndex, usePoints, roundScale(env))
        }

    }

    /**
     * A verified element keeps its own palette colour, lit a little. Recolouring
     * the finished array green made every sort end on the same flat green
     * staircase; the sorted colour ramp is the payoff picture, and green now
     * lives only on the sweep's leading edge.
     */
    private fun verified(c: Int): Int = Palette.brighten(c, 0.12f)

    /**
     * Colour of element [i]: its palette colour, settled once verified, and a
     * short green flash on the few elements the verify sweep has just passed,
     * in every view (it used to exist only on bars).
     */
    private fun shade(i: Int, base: Int, sweepIndex: Int): Int {
        if (i >= sweepIndex) return base
        val settled = verified(base)
        if (sweepIndex >= n) return settled
        val lead = max(2, n / 40)
        val back = sweepIndex - 1 - i
        if (back >= lead) return settled
        val k = 1f - back.toFloat() / lead
        return Palette.lerp(settled, Palette.brighten(Palette.SUCCESS, 0.5f), k)
    }

    /**
     * The round views are laid out to a radius of 0.9 and scaled to fit the
     * screen's narrow side. Unscaled they were twice the width of a phone,
     * the wheel and the disparity ring cut off at both edges.
     */
    private fun roundScale(env: SimEnv): Float = min(env.halfWidth, 1f) * 0.96f / 0.9f

    /** A dot's radius on a round view, by how much track each dot has. */
    private fun roundDot(s: Float): Float = max(2.2f * s / n, 0.0045f).coerceAtMost(0.02f)

    /**
     * An element's colour. A wheel palette (Spectrum) comes back round to its
     * start at 1, so the shortest and the tallest bar of the sorted ramp were
     * the same red; on a wheel the ramp stops a sixth short of the full turn.
     */
    private fun col(palette: Palette, v: Float): Int {
        if (palette !== lastPalette) {
            lastPalette = palette
            span = if (palette.at(0f) == palette.at(1f)) 0.84f else 1f
        }
        return palette.at(v * span)
    }
    private var lastPalette: Palette? = null
    private var span = 1f

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
                val c = shade(i, col(palette, v), sweepIndex)
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
            var c = shade(i, col(palette, v), sweepIndex)
            if (highlight(i)) c = Palette.brighten(c, 0.55f)
            g.bar(x, baseY, hw, h, c)
        }
        if (n <= 400 && sweepIndex < 0) {
            if (array.lastA in 0 until n) g.glow(
                -halfW + (array.lastA + 0.5f) * barW,
                baseY + values[array.lastA] * 1.78f,
                barW * 4f, col(palette, values[array.lastA]), 0.7f
            )
        }
        // The verify sweep's leading edge is lit by shade(). It used to be a
        // full-height line with a 0.12 glow, which read as a green blob.
    }

    private fun drawDots(g: G, palette: Palette, halfW: Float, sweepIndex: Int, usePoints: Boolean) {
        val step = 2f * halfW / n
        // Big enough to see on a phone: at 0.4 of a step the shipped 220 were
        // specks a pixel or two across.
        val r = max(step * 0.55f, 0.0045f).coerceAtMost(0.02f)
        if (usePoints) {
            g.reservePoints(n)
            for (i in 0 until n) {
                val v = values[i]
                val c = shade(i, col(palette, v), sweepIndex)
                g.point(-halfW + (i + 0.5f) * step, v * 1.8f - 0.9f, r * 2.2f, c)
            }
            return
        }
        g.reserveInstances(n)
        for (i in 0 until n) {
            val v = values[i]
            var c = shade(i, col(palette, v), sweepIndex)
            if (highlight(i)) c = Palette.brighten(c, 0.6f)
            g.disc(-halfW + (i + 0.5f) * step, v * 1.8f - 0.9f, r, c)
        }
    }

    private fun drawWheel(g: G, palette: Palette, sweepIndex: Int, usePoints: Boolean, s: Float) {
        val radius = 0.86f * s
        if (usePoints) g.reservePoints(n) else g.reserveInstances(n)
        for (i in 0 until n) {
            val v = values[i]
            val ang = (i.toFloat() / n) * 2f * PI.toFloat()
            val rr = radius * (0.35f + 0.65f * v)
            val x = cos(ang) * rr
            val y = sin(ang) * rr
            var c = shade(i, Palette.hsv(v * 360f, 0.75f, 1f), sweepIndex)
            if (!usePoints && highlight(i)) c = Palette.brighten(c, 0.6f)
            if (usePoints) g.point(x, y, 0.01f, c) else g.disc(x, y, roundDot(s), c)
        }
    }

    private fun drawSpiral(g: G, palette: Palette, sweepIndex: Int, usePoints: Boolean, s: Float) {
        if (usePoints) g.reservePoints(n) else g.reserveInstances(n)
        val turns = 5f
        for (i in 0 until n) {
            val t = if (n <= 1) 0f else i.toFloat() / (n - 1)
            val v = values[i]
            val ang = t * turns * 2f * PI.toFloat()
            val rr = (0.12f + 0.78f * sqrt(t) * (0.55f + 0.45f * v)) * s
            val x = cos(ang) * rr
            val y = sin(ang) * rr
            var c = shade(i, col(palette, v), sweepIndex)
            if (!usePoints && highlight(i)) c = Palette.brighten(c, 0.6f)
            if (usePoints) g.point(x, y, 0.009f, c) else g.disc(x, y, roundDot(s), c)
        }
    }

    private fun drawDisparity(g: G, palette: Palette, sweepIndex: Int, usePoints: Boolean, s: Float) {
        if (usePoints) g.reservePoints(n) else g.reserveInstances(n)
        for (i in 0 until n) {
            val t = if (n <= 1) 0f else i.toFloat() / (n - 1)
            val v = values[i]
            val ang = t * 2f * PI.toFloat()
            // Radius shows how far a value is from where it belongs.
            val err = kotlin.math.abs(v - t)
            val rr = (0.9f - err * 0.78f) * s
            val x = cos(ang) * rr
            val y = sin(ang) * rr
            var c = shade(i, col(palette, v), sweepIndex)
            if (!usePoints && highlight(i)) c = Palette.brighten(c, 0.6f)
            if (usePoints) g.point(x, y, 0.009f, c) else g.disc(x, y, roundDot(s), c)
        }
        g.ring(0f, 0f, 0.9f * s, 0.004f, Palette.TEXT_SECONDARY, alpha = 0.35f)
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

        private fun sharedParams(view: Int = 0): List<Param> = listOf(
            Param.choice(
                "shape", "Data shape", DataShape.entries.map { it.title }, 0,
                card = Card.DATA, input = app.hypnotica.core.recipe.ParamInput.TILES
            ),
            Param.float("messiness", "Messiness", 0f, 1f, 1f, card = Card.DATA),
            Param.int("n", "Size", 8, 200_000, 220, card = Card.DATA, unit = "bars", logScale = true),
            Param.choice(
                "view", "View", SortView.entries.map { it.title }, view,
                card = Card.LOOK, input = app.hypnotica.core.recipe.ParamInput.TILES
            ),
            Param.float("duration", "Run length", 4f, 180f, 26f, card = Card.MOTION, unit = "s"),
            Param.float("effort", "Effort", 0.1f, 6f, 1f, card = Card.MOTION, advancedOnly = true)
        )

        /** Default views, in [Algorithms.all] order. */
        private val VIEWS = intArrayOf(0, 2, 4, 0, 1, 3, 1, 0, 2, 4, 3, 2)

        /** What each one does, in a line, in [Algorithms.all] order. */
        private val BLURBS = listOf(
            "Pick a pivot, split around it, repeat: the halves sort themselves.",
            "Sort the halves, then zip them together. Order spreads in widening blocks.",
            "Heap it up, then lift the biggest off the top, one at a time.",
            "Swap neighbours until nothing moves. The big ones float to the end.",
            "Take the next one and slide it back into place, like a hand of cards.",
            "Find the smallest left and put it next. Slow, but never a step back.",
            "Bubble sort that sweeps both ways, so small ones sink as fast as big ones rise.",
            "One gnome, one pot at a time: step forward, or swap and step back.",
            "Bubble sort with a shrinking gap: the stragglers leap instead of crawl.",
            "Insertion sort over shrinking gaps: rough order first, then fine.",
            "Odd pairs, then even pairs, all at once: a sort built for many hands.",
            "Never compares two numbers: deals them into buckets, digit by digit."
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
                        blurb = BLURBS.getOrElse(index) { "Watch $name find the order." },
                        formats = listOf(TrapFormat.GUESS, TrapFormat.SONG, TrapFormat.RACE),
                        // Each its own look by default: twelve sorts all opening
                        // as the same bar chart read as one sim twelve times.
                        params = sharedParams(VIEWS.getOrElse(index) { 0 }),
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
