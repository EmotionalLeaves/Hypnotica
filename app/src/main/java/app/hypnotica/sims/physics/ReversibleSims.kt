package app.hypnotica.sims.physics

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
import app.hypnotica.core.sim.TrapFormat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A tower that builds itself, and un-builds when time runs backwards.
 *
 * Every other sim in the app integrates forwards: a maze does not unsolve and a
 * sort does not unsort, so "Backwards time" reverses the physics and leaves the
 * rest alone. This one is written the other way round — each brick's position
 * is a function of the clock, nothing is accumulated — so reversing time
 * reverses the picture exactly, brick for brick, with no bookkeeping at all.
 *
 * The clock folds at the top, so forwards it builds and then takes itself
 * apart, and backwards it does the same in the other order. Neither direction
 * ever runs out of tower.
 */
class TowerSim : Sim {

    /** Up and down again: twice the build time. */
    override fun loopSeconds(env: SimEnv): Float = 2f * env.params.f("build", 9f).coerceIn(2f, 60f)

    private var bricks = 400
    private var perRow = 12
    private var buildSeconds = 9f
    private var brickW = 0.05f
    private var brickH = 0.03f
    private var tint = FloatArray(0)
    private var jitter = FloatArray(0)

    /** How many bricks were in place last step, so a landing can be heard. */
    private var placed = 0
    private var payoffFrame = false
    private var completedOnce = false
    private var progress = 0f

    override fun setup(env: SimEnv) {
        val p = env.params
        bricks = p.i("bricks", 400).coerceIn(4, MAX_BRICKS)
        buildSeconds = p.f("build", 9f).coerceIn(2f, 60f)
        // Forty thousand bricks twelve to a row is three thousand rows, which
        // is a smear rather than a tower: the base widens until a brick is tall
        // enough to see. The size the slider asks for is always delivered; what
        // gives is the shape.
        val asked = p.i("perrow", 12).coerceIn(2, 60)
        val tallest = (HEIGHT / MIN_BRICK_H).toInt().coerceAtLeast(1)
        perRow = max(asked, (bricks + tallest - 1) / tallest).coerceAtLeast(2)
        val rows = (bricks + perRow - 1) / perRow
        brickW = (1.7f * env.halfWidth / perRow) * 0.92f
        brickH = min(brickW * 0.6f, HEIGHT / max(1, rows))
        tint = FloatArray(bricks) { env.rng.nextFloat() }
        // A brick is dropped from a little off to one side, so the build does
        // not look like a column of pixels snapping into place.
        jitter = FloatArray(bricks) { env.rng.range(-1f, 1f) }
        placed = 0
        payoffFrame = false
        completedOnce = false
        progress = 0f
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        progress = progressAt(env.time)

        // A click for every brick that arrived or left since the last step: the
        // sound reverses with the picture, because it is read from the same
        // function rather than remembered.
        val now = (progress * bricks).toInt().coerceIn(0, bricks)
        if (now != placed) {
            val moved = abs(now - placed)
            val rising = now > placed
            // Panned to the slot of the brick that just landed (or lifted):
            // brick k touches down exactly when the count reaches k + 1 in
            // draw(), so this step is the visible contact frame.
            val k = (if (rising) now - 1 else now).coerceIn(0, bricks - 1)
            val pan = (slotX(k % perRow, env.halfWidth) / env.halfWidth).coerceIn(-1f, 1f)
            // One note for the move, not one per brick: a thousand bricks
            // arriving in a step is a click, not a thousand clicks.
            env.emit(
                if (rising) EventKind.HIT else EventKind.STEP,
                (now.toFloat() / bricks).coerceIn(0f, 1f),
                pan,
                min(1f, 0.25f + moved * 0.02f)
            )
            placed = now
        }

        if (!completedOnce && progress >= 0.999f) {
            completedOnce = true
            payoffFrame = true
            env.emit(EventKind.DONE, 1f, 0f, 1f)
        }
        if (progress < 0.9f) completedOnce = false
    }

    /**
     * How much of the tower stands at [time], folded so the clock never runs
     * out in either direction.
     *
     * A straight ramp would stop at one end, which is the trap every other
     * reversible-looking thing in this app fell into: run time backwards from a
     * fresh start and the picture sits still. Folding means the run is
     * interesting whichever way the clock is going, from the first frame.
     */
    private fun progressAt(time: Float): Float {
        val cycle = buildSeconds * 2f
        var phase = time % cycle
        if (phase < 0f) phase += cycle
        val up = phase / buildSeconds
        return if (up <= 1f) up else 2f - up
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val rows = (bricks + perRow - 1) / perRow
        val floorY = -0.82f
        val exact = progress * bricks
        val whole = exact.toInt().coerceIn(0, bricks)
        val partial = exact - whole

        g.line(
            -env.halfWidth * 0.95f, floorY - brickH * 0.6f,
            env.halfWidth * 0.95f, floorY - brickH * 0.6f,
            0.004f, Palette.TEXT_SECONDARY, 0.4f
        )

        // Past a few thousand bricks a brick is thinner than a pixel. They were
        // drawn as dots a brick high, which on a wide row left only thin
        // vertical threads with the gaps between showing through. Whole rows
        // are drawn as one course each instead; only the row being laid is
        // drawn brick by brick.
        val courses = bricks > POINT_THRESHOLD
        val fullRows = if (courses) whole / perRow else 0
        g.reserveInstances(if (courses) fullRows + perRow + MAX_IN_FLIGHT else whole + MAX_IN_FLIGHT)
        if (courses) {
            val left = slotX(0, env.halfWidth) - brickW * 0.5f
            val right = slotX(perRow - 1, env.halfWidth) + brickW * 0.5f
            for (row in 0 until fullRows) {
                val c = palette.at(((row.toFloat() / max(1, rows)) * 0.8f + 0.1f).coerceIn(0f, 1f))
                g.rect((left + right) * 0.5f, floorY + row * brickH, (right - left) * 0.5f, brickH * 0.45f, c)
            }
        }
        var i = fullRows * perRow
        while (i < whole) {
            val col = i % perRow
            val row = i / perRow
            val x = slotX(col, env.halfWidth)
            val y = floorY + row * brickH
            val c = brickColour(palette, i, rows)
            g.rect(x, y, brickW * 0.5f, brickH * 0.42f, c)
            i++
        }

        // The bricks in flight. One at a time, each falling for a fiftieth of
        // a second, read as a flicker over the stack; now each falls for
        // FALL_SECONDS, so a shower of them is always on its way down. Still
        // a pure function of the clock: brick i starts falling when the
        // count reaches i + 1 - fall and lands exactly when it reaches i + 1.
        val fall = (FALL_SECONDS * bricks / buildSeconds).coerceIn(1f, MAX_IN_FLIGHT.toFloat())
        val last = min(bricks - 1, (exact + fall).toInt())
        for (k in whole..last) {
            val t = ((exact - (k + 1 - fall)) / fall).coerceIn(0f, 1f)
            if (t <= 0f) continue
            val col = k % perRow
            val row = k / perRow
            val restX = slotX(col, env.halfWidth)
            val restY = floorY + row * brickH
            // Dropped from rest, so it gathers speed all the way down.
            val x = restX + jitter[k] * brickW * 0.8f * (1f - t)
            val y = restY + DROP_HEIGHT * (1f - t * t)
            // The colour it will have in the wall, so it does not change hue
            // the moment it lands.
            val c = Palette.brighten(brickColour(palette, k, rows), 0.25f * (1f - t))
            g.rect(x, y, brickW * 0.5f, max(brickH * 0.42f, 0.002f), c)
        }

    }

    private fun brickColour(palette: Palette, i: Int, rows: Int): Int =
        palette.at((((i / perRow).toFloat() / max(1, rows)) * 0.8f + tint[i] * 0.2f).coerceIn(0f, 1f))

    private fun slotX(col: Int, halfWidth: Float): Float =
        -halfWidth * 0.85f + (col + 0.5f) * (1.7f * halfWidth / perRow)

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "bricks" to "$placed/$bricks",
        "time" to if (progress >= 0.999f) "complete" else String.format(java.util.Locale.US, "%.0f%%", progress * 100f)
    )

    companion object {
        /** Above this whole rows are drawn as one course each. */
        const val POINT_THRESHOLD = 3000

        /** How much of the screen the tower may fill. */
        const val HEIGHT = 1.7f

        /** A brick thinner than this is not a brick any more. */
        const val MIN_BRICK_H = 0.0025f

        const val MAX_BRICKS = 40000

        /** How long a brick takes to fall into place, and from where. */
        const val FALL_SECONDS = 0.45f
        const val DROP_HEIGHT = 0.55f
        const val MAX_IN_FLIGHT = 60

        fun def(): SimDef = SimDef(
            id = "physics/tower",
            title = "A tower that un-builds",
            shelf = Shelf.PHYSICS,
            family = "Reversible motion",
            blurb = "Every brick is a function of the clock, so backwards time takes it apart.",
            formats = listOf(TrapFormat.LOOP, TrapFormat.GROW, TrapFormat.FILL),
            heroLabel = "New bricks",
            defaultPalette = "Ocean",
            typicalSeconds = 20f,
            // Up and down again at the default build time: twice nine seconds.
            loopSeconds = 18f,
            params = listOf(
                Param.int("bricks", "Bricks", 4, MAX_BRICKS, 400, card = Card.DATA, logScale = true),
                Param.int("perrow", "Bricks per row", 2, 60, 12, card = Card.DATA),
                Param.float("build", "Build time", 2f, 60f, 9f, card = Card.MOTION, unit = "s")
            ),
            macros = listOf(
                Macro(
                    "how-big", "How big", Card.DATA,
                    mapOf(
                        "bricks" to 4f..MAX_BRICKS.toFloat(),
                        "perrow" to 2f..60f
                    )
                )
            ),
            create = { TowerSim() }
        )
    }
}

object ReversibleSims {
    fun defs(): List<SimDef> = listOf(TowerSim.def())
}
