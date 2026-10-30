package app.hypnotica.sims.life

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.Shelf
import app.hypnotica.core.sim.Sim
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.sim.SimEnv
import app.hypnotica.core.sim.TouchEvent
import app.hypnotica.core.sim.TrapFormat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Diffusion-limited aggregation. Specks drift down out of the dark on
 * completely random walks, and the first time one touches the reef it
 * sticks there. That is the whole rule — and the reef grows into branching
 * coral, because a speck wandering in is far likelier to bump into a tip
 * than to find its way down into a crevice between two branches. The tall
 * branches shade the short ones and win; a forest becomes a few great trees.
 *
 * Each speck is coloured by when it arrived, so the growth shows in bands
 * like tree rings. Witten and Sander, 1981.
 */
class CoralSim : Sim {

    private var w = 160
    private var h = 340
    private var cell = 0.01f
    /** 0 for open water, 1 for the floor, else 2 + the growth band it stuck in. */
    private var stuck = IntArray(0)
    private var stuckCount = 0
    private var top = 0
    private var nw = 300
    private val wx = IntArray(MAX_WALKERS)
    private val wy = IntArray(MAX_WALKERS)
    private var carry = 0f
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var voice = 0
    private var bands = IntArray(BANDS)

    override fun setup(env: SimEnv) {
        val across = env.params.i("size", 150).coerceIn(40, MAX_W)
        cell = 2f * env.halfWidth / across
        w = across
        h = max(20, (1.96f / cell).toInt())
        stuck = IntArray(w * h)
        stuckCount = 0
        // The sea floor: the bottom row is reef from the start.
        for (x in 0 until w) stuck[x] = 1
        stuckCount = 1
        top = 0
        nw = env.params.i("specks", 300).coerceIn(10, MAX_WALKERS)
        for (i in 0 until nw) spawn(env, i)
        carry = 0f; elapsed = 0f; doneAt = -1f; payoffFrame = false; voice = 0
    }

    /** Row 0 is the floor; walkers start a little above the highest branch. */
    private fun spawn(env: SimEnv, i: Int) {
        wx[i] = env.rng.int(0, w)
        wy[i] = min(h - 1, top + SPAWN_ABOVE + env.rng.int(0, SPAWN_ABOVE))
    }

    private fun touches(x: Int, y: Int): Boolean {
        if (y <= 0) return true
        val l = if (x == 0) w - 1 else x - 1
        val r = if (x == w - 1) 0 else x + 1
        val row = y * w
        return stuck[row - w + x] != 0 || stuck[row + l] != 0 || stuck[row + r] != 0 ||
            (y < h - 1 && stuck[row + w + x] != 0)
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val a = abs(dt)
        elapsed += a
        if (doneAt >= 0f) return
        carry += a * STEPS_PER_S * env.params.f("speed", 1f).coerceIn(0.1f, 5f)
        var steps = min(carry.toInt(), MAX_STEPS)
        carry -= steps
        if (carry > MAX_STEPS) carry = 0f
        val rng = env.rng
        var arrived = 0
        while (steps-- > 0 && doneAt < 0f) {
            for (i in 0 until nw) {
                // A random step: left, right, up or down, the sides wrapping.
                when (rng.int(0, 4)) {
                    0 -> wx[i] = if (wx[i] == 0) w - 1 else wx[i] - 1
                    1 -> wx[i] = if (wx[i] == w - 1) 0 else wx[i] + 1
                    2 -> wy[i]++
                    else -> wy[i]--
                }
                val y = wy[i]
                // Wandered off far above the reef: let it go, send a new one.
                if (y >= h || y > top + SPAWN_ABOVE * 3) { spawn(env, i); continue }
                if (y < 1) wy[i] = 1
                val x = wx[i]
                val idx = wy[i] * w + x
                if (stuck[idx] == 0 && touches(x, wy[i])) {
                    stuckCount++
                    // Coloured by how tall the reef was when it arrived: a
                    // fixed band, so the rings stay put as the reef grows.
                    stuck[idx] = 2 + top * (BANDS - 1) / h
                    arrived++
                    if (wy[i] > top) top = wy[i]
                    spawn(env, i)
                    if (top >= h - 2 - SPAWN_ABOVE && doneAt < 0f) {
                        doneAt = elapsed
                        payoffFrame = true
                        env.emit(EventKind.DONE, 0.85f, 0f, 1f)
                    }
                }
            }
        }
        if (arrived > 0) {
            voice++
            if (voice % 3 == 0) {
                val height = top.toFloat() / h
                env.emit(EventKind.BIRTH, (0.15f + 0.8f * height).coerceIn(0f, 1f), 0f, min(0.35f, 0.06f + arrived * 0.01f))
            }
        }
    }

    override fun onInput(env: SimEnv, e: TouchEvent) {
        if (e.kind != TouchEvent.Kind.TAP || doneAt >= 0f) return
        // A tap drops a grain of reef in the water: a new place to grow from.
        val x = ((e.x + w * cell * 0.5f) / cell).toInt()
        val y = ((e.y + 0.98f) / cell).toInt()
        if (x !in 0 until w || y !in 1 until h - 1) return
        stuck[y * w + x] = 2 + top * (BANDS - 1) / h
        stuckCount++
        if (y > top) top = y
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        // Growth bands: the colour steps through the palette in rings of
        // arrival order, each band a run of equal colour so rows merge.
        for (k in 0 until BANDS) bands[k] = pal.at(0.05f + 0.9f * k / (BANDS - 1f))
        val x0 = -w * cell * 0.5f
        val floorY = -0.98f
        for (y in 0..min(h - 1, top)) {
            val row = y * w
            val yc = floorY + (y + 0.5f) * cell
            g.cellRow(x0, yc, cell, w) { x ->
                val s = stuck[row + x]
                if (s == 0) 0 else bands[min(BANDS - 1, s - 1)]
            }
        }
        // The specks still wandering: faint motes in the water above.
        if (doneAt < 0f) {
            val mote = Palette.withAlpha(Palette.TEXT_SECONDARY, 0.55f)
            g.reservePoints(nw)
            for (i in 0 until nw) g.point(x0 + (wx[i] + 0.5f) * cell, floorY + (wy[i] + 0.5f) * cell, cell * 0.9f, mote)
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && elapsed - doneAt > 5f

    override fun counters(): List<Pair<String, String>> = listOf(
        "specks stuck" to String.format(java.util.Locale.US, "%,d", stuckCount - 1),
        "height" to String.format(java.util.Locale.US, "%.0f%%", 100f * top / h)
    )

    companion object {
        const val MAX_W = 320
        const val MAX_WALKERS = 2000
        const val SPAWN_ABOVE = 6
        const val STEPS_PER_S = 1500f
        const val MAX_STEPS = 90
        const val BANDS = 24

        fun def(): SimDef = SimDef(
            id = "life/coral",
            title = "Random coral",
            shelf = Shelf.LIFE,
            family = "Fractals & growth",
            blurb = "Specks wander at random and stick where they touch. They build a coral reef.",
            formats = listOf(TrapFormat.GROW, TrapFormat.FILL),
            heroLabel = "New reef",
            defaultPalette = "Aurora",
            typicalSeconds = 22f,
            params = listOf(
                Param.int("size", "Reef width", 40, MAX_W, 150, card = Card.DATA, logScale = true),
                Param.int("specks", "Specks at once", 10, MAX_WALKERS, 300, card = Card.DATA, logScale = true),
                Param.float("speed", "Speed", 0.2f, 5f, 1f, card = Card.MOTION, logScale = true)
            ),
            create = { CoralSim() }
        )
    }
}
