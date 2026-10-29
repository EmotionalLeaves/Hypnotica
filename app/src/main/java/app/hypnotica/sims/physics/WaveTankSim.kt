package app.hypnotica.sims.physics

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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A ripple tank with slits: straight waves roll down from the top, squeeze
 * through gaps in a wall, and spread out the far side into fans that cross,
 * cancel and reinforce. Along the bottom, a screen keeps a running total of
 * how much wave has landed where, and the stripes of the interference pattern
 * build up on it — the double-slit experiment, done with water.
 *
 * The wave equation on a grid, one cell per screen step, with a soft sponge
 * round the edges so nothing bounces back to muddy the pattern. A tap drops a
 * stone of your own in.
 */
class WaveTankSim : Sim {

    private var w = 1
    private var h = 1
    private var u = FloatArray(0)
    private var prev = FloatArray(0)
    private var next = FloatArray(0)
    private var wall = BooleanArray(0)
    private var sponge = FloatArray(0)
    private var screen = FloatArray(0)
    private var wallRow = 0
    private var screenRow = 0
    private var carry = 0f
    private var updates = 0L
    private var phase = 0f
    private var arrivedAt = -1L
    private var payoffFrame = false
    private var formed = false
    private var lastCrest = 0L

    override fun setup(env: SimEnv) {
        val p = env.params
        h = ROWS
        w = (env.halfWidth * h).roundToInt().coerceIn(24, MAX_COLS)
        val n = w * h
        u = FloatArray(n); prev = FloatArray(n); next = FloatArray(n)
        wall = BooleanArray(n)
        sponge = FloatArray(n)
        screen = FloatArray(w)
        wallRow = (h * 0.36f).toInt()
        // Well clear of the sponge, whose own faint reflections would stripe
        // the tally with a pattern that is not the slits'.
        screenRow = h - SPONGE - SCREEN_CLEARANCE

        // The wall, with its slits, centred and evenly spaced.
        val slits = p.i("slits", 2).coerceIn(1, 5)
        // Every slit inside the tank's calm middle, and narrower than the wall
        // between them: at the ends of the sliders the outer slits landed in
        // the sponge, and wide slits merged into one opening.
        val usable = w - 2f * SPONGE
        val spacing = min(p.f("gap", 0.3f).coerceIn(0.08f, 0.7f) * w, if (slits > 1) usable * 0.9f / (slits - 1) else usable)
        val width = max(3, min((p.f("wavelength", 7f) * 0.6f).roundToInt(), (spacing * 0.6f).toInt().coerceAtLeast(3)))
        for (row in wallRow until wallRow + WALL_ROWS) {
            for (x in 0 until w) wall[row * w + x] = true
        }
        for (s in 0 until slits) {
            val centre = w / 2f + (s - (slits - 1) / 2f) * spacing
            val from = (centre - width / 2f).roundToInt().coerceIn(0, w - 1)
            for (x in from until min(w, from + width)) {
                for (row in wallRow until wallRow + WALL_ROWS) wall[row * w + x] = false
            }
        }

        // The sponge: a gentle loss rising towards the sides and the bottom,
        // so waves leave the tank instead of reflecting back across it.
        for (y in 0 until h) for (x in 0 until w) {
            val edge = min(min(x, w - 1 - x), h - 1 - y)
            // Rising as a square, not a straight line: an abrupt change in
            // loss reflects waves of its own, which is what the sponge is for
            // preventing.
            val depth = 1f - edge / SPONGE.toFloat()
            sponge[y * w + x] = if (edge >= SPONGE) 1f else 1f - SPONGE_LOSS * depth * depth
        }
        carry = 0f
        updates = 0L
        phase = 0f
        arrivedAt = -1L
        payoffFrame = false
        formed = false
        lastCrest = 0L
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        carry += abs(dt) * UPDATES_PER_SECOND * env.params.f("pace", 1f)
        val lambda = env.params.f("wavelength", 7f).coerceIn(6f, 40f)
        // The wave moves half a cell an update, so one period is 2·λ updates.
        val omega = (PI / lambda).toFloat()
        var todo = min(carry.toInt(), 8)
        carry -= todo
        while (todo-- > 0) {
            phase += omega
            update(phase)
            updates++
            if (phase / (2f * PI.toFloat()) >= lastCrest + 1) {
                lastCrest++
                // A soft beat at the source, and once the pattern is landing,
                // a note for where on the screen it is brightest.
                if (arrivedAt < 0) {
                    env.emit(EventKind.STEP, 0.3f, 0f, 0.25f)
                } else {
                    val peak = brightestColumn()
                    env.emit(EventKind.CROSS, peak.toFloat() / (w - 1), (peak.toFloat() / (w - 1)) * 1.6f - 0.8f, 0.55f)
                }
            }
        }

        // The pattern has formed once the waves through the slits have been
        // landing on the screen for a few periods.
        if (!formed && arrivedAt >= 0 && updates - arrivedAt > (FORMING_PERIODS * 2f * lambda).toLong()) {
            formed = true
            payoffFrame = true
            env.emit(EventKind.DONE, 0.85f, 0f, 1f)
        }
    }

    private fun update(phase: Float) {
        val c2 = C2
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                if (wall[i]) {
                    next[i] = 0f
                    continue
                }
                val lap = u[i - 1] + u[i + 1] + u[i - w] + u[i + w] - 4f * u[i]
                next[i] = (2f * u[i] - prev[i] + c2 * lap) * sponge[i]
            }
        }
        // The source: a straight line of wave driven along the top.
        val drive = sin(phase) * SOURCE_AMPLITUDE
        for (x in 0 until w) next[SOURCE_ROW * w + x] = drive
        val t = prev; prev = u; u = next; next = t

        // The screen keeps the total; the first wave to reach it is the arrival.
        val base = screenRow * w
        var any = false
        for (x in SPONGE until w - SPONGE) {
            val v = u[base + x]
            screen[x] += v * v
            if (abs(v) > ARRIVAL) any = true
        }
        if (arrivedAt < 0 && any) arrivedAt = updates
    }

    private fun brightestColumn(): Int {
        var best = 0
        for (x in 1 until w) if (screen[x] > screen[best]) best = x
        return best
    }

    override fun onInput(env: SimEnv, e: TouchEvent) {
        if (e.kind != TouchEvent.Kind.TAP) return
        val x = ((e.x / env.halfWidth + 1f) * 0.5f * w).toInt()
        val y = ((1f - e.y) * 0.5f * h).toInt()
        if (x !in 2 until w - 2 || y !in 2 until h - 2) return
        for (dy in -2..2) for (dx in -2..2) {
            val i = (y + dy) * w + (x + dx)
            if (!wall[i]) u[i] += STONE * (1f - (dx * dx + dy * dy) / 9f).coerceAtLeast(0f)
        }
        env.emit(EventKind.HIT, 1f - y.toFloat() / h, (x.toFloat() / w) * 2f - 1f, 0.8f)
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val cell = 2f / h
        val left = -env.halfWidth
        // The water, down to the screen: past it is the screen's own business.
        // Each cell a dot wide enough to merge with its neighbours — a dot one
        // cell across left a lattice of dark gaps that beat against the
        // wavefronts into moiré.
        g.reservePoints(w * (screenRow + 1))
        val crest = palette.at(0.75f)
        val trough = palette.at(0.3f)
        for (y in 0 until screenRow) {
            val wy = 1f - (y + 0.5f) * cell
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                if (wall[i]) continue
                val q = u[i] / SHOW_AMPLITUDE
                // Crests bright, troughs a dim second colour: the ripples read
                // as lines of light, the way a ripple tank throws them.
                val a = if (q > 0f) min(1f, q) else min(1f, -q) * TROUGH
                if (a < 0.03f) continue
                // Opaque, faded toward the background instead: see-through
                // dots doubled up where they overlap and the field went grainy.
                g.point(left + (x + 0.5f) * cell, wy, cell * 1.45f, Palette.lerp(Palette.BG, if (q > 0f) crest else trough, a))
            }
        }
        // The wall, drawn solid.
        val wallY = 1f - (wallRow + WALL_ROWS * 0.5f) * cell
        var x = 0
        while (x < w) {
            if (!wall[wallRow * w + x]) { x++; continue }
            val from = x
            while (x < w && wall[wallRow * w + x]) x++
            val cx = left + (from + x) * 0.5f * cell
            g.rect(cx, wallY, (x - from) * 0.5f * cell, WALL_ROWS * 0.5f * cell, Palette.TEXT_SECONDARY, alpha = 0.8f)
        }
        // The screen: a strip lit where the waves land hardest — the fringes —
        // and under it, below the water, the tally as a bar chart. The bars
        // stood in the water before and tangled with the waves.
        var peak = 1e-6f
        for (v in screen) if (v > peak) peak = v
        val screenY = 1f - (screenRow + 0.5f) * cell
        g.line(left, screenY, -left, screenY, cell * 0.6f, Palette.TEXT_SECONDARY, 0.5f)
        val barBase = -1f + cell
        val room = screenY - cell * 4f - barBase
        for (c in 0 until w) {
            // Intensity is the wave squared, which is what the screen totals.
            val lit = screen[c] / peak
            if (lit < 0.01f) continue
            val col = palette.at(0.75f)
            g.rect(left + (c + 0.5f) * cell, screenY, cell * 0.55f, cell * 1.5f, col, alpha = lit)
            val hgt = lit * room
            g.rect(left + (c + 0.5f) * cell, barBase + hgt * 0.5f, cell * 0.56f, hgt * 0.5f, col, alpha = 0.35f + 0.5f * lit)
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "waves" to lastCrest.toString(),
        if (arrivedAt < 0) "screen" to "waiting" else "brightest at" to String.format(java.util.Locale.US, "%.0f%%", brightestColumn() * 100f / (w - 1))
    )

    companion object {
        const val ROWS = 220
        const val MAX_COLS = 640
        const val SOURCE_ROW = 3
        const val WALL_ROWS = 3
        const val SPONGE = 24
        const val SPONGE_LOSS = 0.12f
        /** Courant number squared: the wave moves half a cell an update. */
        const val C2 = 0.25f
        const val UPDATES_PER_SECOND = 70f
        const val SOURCE_AMPLITUDE = 1f
        const val SHOW_AMPLITUDE = 0.7f
        /** How bright a trough is next to a crest of the same height. */
        const val TROUGH = 0.4f
        const val ARRIVAL = 0.02f
        const val FORMING_PERIODS = 6f
        const val STONE = 2.5f
        const val SCREEN_HEIGHT = 0.16f
        const val SCREEN_CLEARANCE = 16

        fun def(): SimDef = SimDef(
            id = "physics/wave-tank",
            title = "Ripple tank",
            shelf = Shelf.PHYSICS,
            family = "Waves & optics",
            blurb = "Waves through two slits cross and cancel. Stripes build up on the screen.",
            formats = listOf(TrapFormat.REVEAL, TrapFormat.SONG),
            heroLabel = "Start again",
            defaultPalette = "Ocean",
            typicalSeconds = 11f,
            params = listOf(
                Param.int("slits", "Slits", 1, 5, 2, card = Card.DATA),
                Param.float("gap", "Slit spacing", 0.08f, 0.7f, 0.3f, card = Card.DATA),
                Param.float("wavelength", "Wavelength", 6f, 40f, 7f, card = Card.MOTION),
                Param.float("pace", "Pace", 0.3f, 2.5f, 1f, card = Card.MOTION)
            ),
            create = { WaveTankSim() }
        )
    }
}
