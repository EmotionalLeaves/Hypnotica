package app.hypnotica.sims.life

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.render.G
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.Shelf
import app.hypnotica.core.sim.Sim
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.sim.SimEnv
import app.hypnotica.core.sim.TouchEvent
import app.hypnotica.core.sim.TrapFormat
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * The Ising model: a magnet as a grid of tiny arrows, each pointing up or
 * down, each preferring to agree with its four neighbours, and heat
 * jostling them all at random. Start them scattered, as if the magnet had
 * just been plunged from a furnace, and hold it a little below the critical
 * temperature: neighbourhoods agree, the agreeing patches swallow their
 * neighbours, and the speckle coarsens into great marbled continents whose
 * coastlines smooth and shrink. Near 2.27 — the critical point — it hangs
 * between order and chaos, in islands within islands at every size. Above,
 * it boils.
 *
 * Metropolis updates on a checkerboard, on a torus. Endless.
 */
class IsingSim : Sim {

    private var w = 120
    private var h = 260
    private var spin = ByteArray(0)
    private var cell = 0.01f
    private var temp = 2.0f
    private var lastTemp = -1f
    /** Acceptance for flipping against a field of 4 and 8 (dE = 4, 8 in units of J). */
    private var accept4 = 0f
    private var accept8 = 0f
    private var carry = 0f
    private var sweeps = 0
    private var walls = 0
    private var magnet = 0
    private var elapsed = 0f
    private var payoffFrame = false
    private var paid = false
    private var startWalls = 1
    private var voice = 0f
    private var winner = -1

    override fun setup(env: SimEnv) {
        val across = env.params.i("size", 140).coerceIn(30, MAX_W)
        val hw = env.halfWidth
        cell = 2f * hw / across
        w = across
        h = max(8, (2f / cell).toInt())
        spin = ByteArray(w * h)
        val rng = env.rng
        for (i in spin.indices) spin[i] = if (rng.nextFloat() < 0.5f) 1 else -1
        carry = 0f; sweeps = 0; elapsed = 0f
        payoffFrame = false; paid = false; voice = 0f; winner = -1
        lastTemp = -1f
        measure()
        startWalls = max(1, walls)
    }

    private fun measure() {
        var wl = 0; var m = 0
        for (y in 0 until h) {
            val row = y * w
            val dn = ((y + 1) % h) * w
            for (x in 0 until w) {
                val s = spin[row + x]
                m += s
                if (s != spin[row + (x + 1) % w]) wl++
                if (s != spin[dn + x]) wl++
            }
        }
        walls = wl; magnet = m
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val a = abs(dt)
        elapsed += a
        temp = env.params.f("temp", 1.7f).coerceIn(0.5f, 5f)
        if (temp != lastTemp) {
            lastTemp = temp
            accept4 = exp(-4f / temp); accept8 = exp(-8f / temp)
        }
        val rate = env.params.f("speed", 1f).coerceIn(0.1f, 4f) * SWEEPS_PER_S
        carry += a * rate
        var k = 0
        val rng = env.rng
        while (carry >= 1f && k < 6) {
            carry -= 1f; k++
            sweeps++
            for (parity in 0..1) {
                for (y in 0 until h) {
                    val row = y * w
                    val up = ((y + h - 1) % h) * w
                    val dn = ((y + 1) % h) * w
                    var x = (y + parity) and 1
                    while (x < w) {
                        val s = spin[row + x]
                        val sum = spin[row + (x + 1) % w] + spin[row + (x + w - 1) % w] + spin[up + x] + spin[dn + x]
                        // dE = 2 s Σ: flip freely downhill, uphill with the Boltzmann odds.
                        val de = s * sum
                        val flip = when {
                            de <= 0 -> true
                            de == 2 -> rng.nextFloat() < accept4
                            else -> rng.nextFloat() < accept8
                        }
                        if (flip) spin[row + x] = (-s).toByte()
                        x += 2
                    }
                }
            }
        }
        if (carry > 6f) carry = 0f
        if (k > 0) measure()
        // The coastlines' total length, against where they started: the
        // payoff is the speckle having become continents.
        val share = walls.toFloat() / startWalls
        if (!paid && share < PAYOFF_WALLS && elapsed > 2f) {
            paid = true; payoffFrame = true
            // Whichever way more of the magnet points once the speckle has
            // become continents: that colour has won the board.
            winner = if (magnet >= 0) 0 else 1
            env.emit(EventKind.DONE, 0.7f, 0f, 0.8f)
        }
        voice += a
        if (voice > 0.25f) {
            voice = 0f
            val mag = magnet.toFloat() / (w * h)
            env.emit(EventKind.STEP, (0.5f + 0.45f * mag).coerceIn(0f, 1f), mag.coerceIn(-1f, 1f), (0.06f + 0.3f * min(1f, share)).coerceAtMost(0.3f))
        }
    }

    override fun onInput(env: SimEnv, e: TouchEvent) {
        if (e.kind != TouchEvent.Kind.TAP && e.kind != TouchEvent.Kind.DRAG) return
        // A finger is a little magnet: the arrows under it turn up.
        val cx = ((e.x + w * cell * 0.5f) / cell).toInt()
        val cy = ((1f - e.y) / cell).toInt()
        for (dy in -4..4) for (dx in -4..4) {
            if (dx * dx + dy * dy > 16) continue
            val x = ((cx + dx) % w + w) % w; val y = ((cy + dy) % h + h) % h
            spin[y * w + x] = 1
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        val upC = pal.at(0.12f)
        val downC = pal.at(0.78f)
        val x0 = -w * cell * 0.5f
        for (y in 0 until h) {
            val row = y * w
            val yc = 1f - (y + 0.5f) * cell
            g.cellRow(x0, yc, cell, w) { x -> if (spin[row + x] > 0) upC else downC }
        }
    }

    override fun contenders(env: SimEnv): IntArray = intArrayOf(env.palette.at(0.12f), env.palette.at(0.78f))

    override fun winner(): Int = winner

    override fun payoff(): Boolean = payoffFrame

    /** Endless: the coastlines keep moving for as long as anyone watches. */
    override fun finished(): Boolean = false

    override fun counters(): List<Pair<String, String>> = listOf(
        "temperature" to String.format(java.util.Locale.US, "%.2f", temp),
        "magnetised" to String.format(java.util.Locale.US, "%.0f%%", 100f * abs(magnet) / (w * h).toFloat()),
        "sweeps" to "$sweeps"
    )

    companion object {
        const val MAX_W = 300
        const val SWEEPS_PER_S = 12f
        /** Coastline down to this share of the starting speckle's. */
        const val PAYOFF_WALLS = 0.075f

        fun def(): SimDef = SimDef(
            id = "life/ising",
            title = "Magnet domains",
            shelf = Shelf.LIFE,
            family = "Cellular automata",
            blurb = "Tiny magnets that want to agree with their neighbours. The speckle becomes continents.",
            formats = listOf(TrapFormat.COLOR_BATTLE, TrapFormat.GROW),
            heroLabel = "Quench",
            defaultPalette = "Ocean",
            typicalSeconds = 12f,
            params = listOf(
                Param.int("size", "Grid width", 30, MAX_W, 140, card = Card.DATA, logScale = true),
                Param.float("temp", "Temperature", 0.5f, 5f, 1.7f, card = Card.MOTION),
                Param.float("speed", "Speed", 0.2f, 4f, 1f, card = Card.MOTION, logScale = true)
            ),
            create = { IsingSim() }
        )
    }
}
