package app.hypnotica.sims.physics

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.LawKnob
import app.hypnotica.core.sim.Shelf
import app.hypnotica.core.sim.Sim
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.sim.SimEnv
import app.hypnotica.core.sim.TrapFormat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A glass filled from a tap, drop by drop, to the brim.
 *
 * The liquid is thousands of particles that push apart when they crowd and
 * drag on each other when they slide — the cheapest model that still pools,
 * sloshes and levels — so the stream splashes, the surface wobbles flat, and
 * the line creeps up the glass to the rim. Each tenth of the glass filled is a
 * note higher; a drop landing hard is a tap of sound.
 */
class PourSim : Sim {

    private var cap = 2000
    private var n = 0
    private var x = FloatArray(0)
    private var y = FloatArray(0)
    private var vx = FloatArray(0)
    private var vy = FloatArray(0)
    private var px = FloatArray(0)
    private var py = FloatArray(0)
    private var dvx = FloatArray(0)
    private var dvy = FloatArray(0)
    private var carry = 0f
    private var elapsed = 0f
    private var payoffFrame = false
    private var full = false
    private var fullAt = -1f
    private var tenths = 0
    private var halfW = 0.46f
    private var share = 0f
    private val level = IntArray(40)

    // The glass: an open box, walls and a floor, in world units.
    private var glassL = -0.3f
    private var glassR = 0.3f
    private var glassBottom = -0.85f
    private var glassTop = 0.1f

    // Neighbour grid.
    private var cellsX = 1
    private var cellsY = 1
    private var head = IntArray(0)
    private var next = IntArray(0)

    override fun setup(env: SimEnv) {
        val p = env.params
        cap = MAX_DROPS
        halfW = env.halfWidth
        // The glass is sized so this many drops fill it to the brim.
        val w = min(halfW * 0.42f, 0.36f)
        glassL = -w; glassR = w
        // Standing on the floor, so nothing that splashes out can run under it.
        glassBottom = -1f + FLOOR_GAP
        // The tap runs until the water line reaches the brim, however many
        // drops that takes: deep water squashes a little, so a count chosen
        // in advance never quite fills it.
        glassTop = glassBottom + p.f("glass", 0.8f).coerceIn(0.2f, 1.3f)
        x = FloatArray(cap); y = FloatArray(cap); vx = FloatArray(cap); vy = FloatArray(cap)
        px = FloatArray(cap); py = FloatArray(cap); dvx = FloatArray(cap); dvy = FloatArray(cap)
        n = 0
        carry = 0f
        elapsed = 0f
        payoffFrame = false
        full = false
        fullAt = -1f
        tenths = 0
        share = 0f
        cellsX = max(1, (2f * halfW / D).toInt() + 2)
        cellsY = max(1, (2.2f / D).toInt() + 2)
        head = IntArray(cellsX * cellsY)
        next = IntArray(cap)
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        // The tap.
        if (!full) {
            val flow = env.params.f("flow", 75f)
            carry += h * flow
            // As many lanes as keep each one's drops a drop apart.
            val lanes = kotlin.math.ceil(flow * D * 1.2f / TAP_SPEED).toInt().coerceIn(1, 9)
            while (carry >= 1f && n < cap) {
                carry -= 1f
                // In lanes across the tap, each drop set down a little below
                // the last in its lane by the share of the frame since it
                // left — spaced past the push-apart distance, or the stream
                // blows itself sideways in mid-air.
                val lane = n % lanes
                x[n] = (lane - (lanes - 1) * 0.5f) * D * 1.05f + env.rng.range(-0.001f, 0.001f)
                y[n] = 0.95f - carry * h * TAP_SPEED
                vx[n] = 0f
                vy[n] = -TAP_SPEED
                n++
            }
        }
        val g = env.laws.gravity * GRAVITY_SCALE
        // Move every drop where its speed takes it...
        for (i in 0 until n) {
            vy[i] -= g * h
            px[i] = x[i]; py[i] = y[i]
            x[i] += vx[i] * h
            y[i] += vy[i] * h
        }
        // ...then push apart every pair closer than a drop, a few passes over.
        // Water that keeps its volume, which a soft pressure doesn't: under
        // its own weight that squashed to a third and the glass never filled.
        repeat(PASSES) {
            java.util.Arrays.fill(head, -1)
            for (i in 0 until n) {
                val c = cellOf(x[i], y[i])
                next[i] = head[c]
                head[c] = i
            }
            for (i in 0 until n) {
                val cx = ((x[i] + halfW) / D).toInt().coerceIn(0, cellsX - 1)
                val cy = ((y[i] + 1.1f) / D).toInt().coerceIn(0, cellsY - 1)
                for (oy in -1..1) for (ox in -1..1) {
                    val gx = cx + ox; val gy = cy + oy
                    if (gx < 0 || gy < 0 || gx >= cellsX || gy >= cellsY) continue
                    var j = head[gy * cellsX + gx]
                    while (j >= 0) {
                        if (j > i) {
                            val dx = x[i] - x[j]; val dy = y[i] - y[j]
                            val d2 = dx * dx + dy * dy
                            if (d2 < D * D && d2 > 1e-12f) {
                                val d = sqrt(d2)
                                val push = (D - d) * 0.5f * STIFF / d
                                x[i] += dx * push; y[i] += dy * push
                                x[j] -= dx * push; y[j] -= dy * push
                            }
                        }
                        j = next[j]
                    }
                }
            }
            for (i in 0 until n) collide(i, px[i], py[i])
        }
        // Speed is how far each drop actually went; and neighbours drag on
        // each other a little, so the surface settles instead of fizzing.
        var landed = 0
        val inv = if (h > 1e-6f) 1f / h else 0f
        for (i in 0 until n) {
            val before = vy[i]
            vx[i] = (x[i] - px[i]) * inv
            vy[i] = (y[i] - py[i]) * inv
            // A drop stopped hard is a splash.
            if (before < -0.8f && vy[i] > -0.2f) landed++
        }
        java.util.Arrays.fill(head, -1)
        for (i in 0 until n) {
            val c = cellOf(x[i], y[i])
            next[i] = head[c]
            head[c] = i
        }
        val thick = env.params.f("thick", VISCOSITY).coerceIn(0f, 0.9f)
        for (i in 0 until n) {
            var sx = 0f; var sy = 0f; var w = 0f
            val cx = ((x[i] + halfW) / D).toInt().coerceIn(0, cellsX - 1)
            val cy = ((y[i] + 1.1f) / D).toInt().coerceIn(0, cellsY - 1)
            for (oy in -1..1) for (ox in -1..1) {
                val gx = cx + ox; val gy = cy + oy
                if (gx < 0 || gy < 0 || gx >= cellsX || gy >= cellsY) continue
                var j = head[gy * cellsX + gx]
                while (j >= 0) {
                    if (j != i) {
                        val dx = x[i] - x[j]; val dy = y[i] - y[j]
                        val d2 = dx * dx + dy * dy
                        if (d2 < REACH * REACH) {
                            val q = 1f - sqrt(d2) / REACH
                            sx += (vx[j] - vx[i]) * q; sy += (vy[j] - vy[i]) * q; w += q
                        }
                    }
                    j = next[j]
                }
            }
            if (w > 0f) { dvx[i] = sx / w * thick; dvy[i] = sy / w * thick } else { dvx[i] = 0f; dvy[i] = 0f }
        }
        for (i in 0 until n) { vx[i] += dvx[i]; vy[i] += dvy[i] }
        if (landed > 0) env.emit(EventKind.HIT, 0.6f + 0.3f * env.rng.nextFloat(), env.rng.range(-0.4f, 0.4f), min(1f, 0.2f + landed * 0.05f))

        // How full, the way an eye judges it: where the water line is. Rows
        // up the glass, and the highest one that's at least half water.
        val rows = level.size
        java.util.Arrays.fill(level, 0)
        val rowH = (glassTop - glassBottom) / rows
        for (i in 0 until n) {
            if (x[i] <= glassL || x[i] >= glassR || y[i] < glassBottom) continue
            val r = ((y[i] - glassBottom) / rowH).toInt()
            if (r in 0 until rows) level[r]++
        }
        val perRow = (glassR - glassL) * rowH / DROP_AREA
        var top = 0
        for (r in 0 until rows) if (level[r] >= perRow * 0.5f) top = r + 1
        share = top.toFloat() / rows
        while (tenths < 10 && share >= (tenths + 1) / 10f) {
            tenths++
            env.emit(EventKind.STEP, tenths / 10f, 0f, 0.5f)
        }
        if (!full && share >= FULL_SHARE) {
            full = true
            fullAt = elapsed
            payoffFrame = true
            env.emit(EventKind.DONE, 0.9f, 0f, 1f)
        }
    }

    private fun cellOf(px: Float, py: Float): Int {
        val cx = ((px + halfW) / D).toInt().coerceIn(0, cellsX - 1)
        val cy = ((py + 1.1f) / D).toInt().coerceIn(0, cellsY - 1)
        return cy * cellsX + cx
    }

    /**
     * The glass's walls and floor, the screen's edges, and the floor below.
     * Tested by crossing, from where the drop was to where it is, so a drop
     * shoved hard by the crowd can't step clean through a wall in one frame.
     */
    private fun collide(i: Int, ox: Float, oy: Float) {
        val r = SPACING * 0.5f
        val wasIn = ox > glassL && ox < glassR
        val below = oy < glassTop
        if (below && oy > glassBottom - r) {
            if (wasIn) {
                // Inside: keep it inside.
                if (x[i] < glassL + r) { x[i] = glassL + r; vx[i] = abs(vx[i]) * BOUNCE }
                if (x[i] > glassR - r) { x[i] = glassR - r; vx[i] = -abs(vx[i]) * BOUNCE }
                if (y[i] < glassBottom + r) { y[i] = glassBottom + r; vy[i] = abs(vy[i]) * BOUNCE; vx[i] *= 0.98f }
            } else {
                // Outside, beside the glass: keep it out.
                if (ox <= glassL && x[i] > glassL - r) { x[i] = glassL - r; vx[i] = -abs(vx[i]) * BOUNCE }
                if (ox >= glassR && x[i] < glassR + r) { x[i] = glassR + r; vx[i] = abs(vx[i]) * BOUNCE }
            }
        } else if (oy >= glassTop && y[i] < glassTop && x[i] > glassL - r && x[i] < glassR + r && !(x[i] > glassL + r && x[i] < glassR - r)) {
            // Landing on the rim itself: it tips to whichever side it's on.
            y[i] = glassTop
            vy[i] = abs(vy[i]) * BOUNCE
            vx[i] += if (abs(x[i] - glassL) < abs(x[i] - glassR)) (if (x[i] < glassL) -0.1f else 0.1f) else (if (x[i] > glassR) 0.1f else -0.1f)
        }
        // What spills runs off the screen's floor and edges.
        if (y[i] < -1f + r) { y[i] = -1f + r; vy[i] = abs(vy[i]) * BOUNCE }
        if (x[i] < -halfW + r) { x[i] = -halfW + r; vx[i] = abs(vx[i]) * BOUNCE }
        if (x[i] > halfW - r) { x[i] = halfW - r; vx[i] = -abs(vx[i]) * BOUNCE }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val wallC = Palette.withAlpha(Palette.TEXT_SECONDARY, 0.8f)
        g.line(glassL, glassBottom, glassL, glassTop, 0.008f, wallC)
        g.line(glassR, glassBottom, glassR, glassTop, 0.008f, wallC)
        g.line(glassL, glassBottom, glassR, glassBottom, 0.008f, wallC)
        // The brim, marked, so the target is there to be watched.
        g.line(glassL - 0.02f, glassTop, glassL + 0.04f, glassTop, 0.004f, Palette.SUCCESS, 0.7f)
        g.line(glassR - 0.04f, glassTop, glassR + 0.02f, glassTop, 0.004f, Palette.SUCCESS, 0.7f)
        g.reservePoints(n)
        for (i in 0 until n) {
            val sp = sqrt(vx[i] * vx[i] + vy[i] * vy[i])
            // Still water deep, moving water bright: the stream and the
            // splash read against the pool.
            g.point(x[i], y[i], SPACING * 1.5f, palette.at((0.25f + sp * 0.35f).coerceIn(0f, 1f)))
        }
        if (!full) g.rect(0f, 0.97f, TAP_WIDTH * 2f, 0.02f, Palette.TEXT_SECONDARY, alpha = 0.8f)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = full && elapsed - fullAt > 3f

    override fun counters(): List<Pair<String, String>> = listOf(
        "drops" to n.toString(),
        "full" to if (full) "brim" else "${(share * 100f).toInt()}%"
    )

    companion object {
        /** A drop's width: none come closer. */
        const val D = 0.022f
        const val SPACING = D
        const val PASSES = 4
        const val STIFF = 0.9f
        const val REACH = D * 1.6f
        /** How much of the gap to its neighbours' speed a drop closes each frame. */
        const val VISCOSITY = 0.08f
        const val BOUNCE = 0.1f
        const val GRAVITY_SCALE = 0.25f
        const val TAP_WIDTH = 0.04f
        const val TAP_SPEED = 2.2f
        const val FULL_SHARE = 0.975f
        const val MAX_DROPS = 3500
        const val FLOOR_GAP = 0.01f
        /** The area one drop takes up once settled, measured. */
        const val DROP_AREA = D * D * 0.95f

        fun def(): SimDef = SimDef(
            id = "physics/pour",
            title = "Fill the glass",
            shelf = Shelf.PHYSICS,
            family = "Fluids",
            blurb = "A tap, a glass, a thousand drops. Right to the brim. Or pour honey.",
            formats = listOf(TrapFormat.FILL, TrapFormat.GROW, TrapFormat.SNAP),
            lawKnobs = setOf(LawKnob.GRAVITY),
            heroLabel = "Pour again",
            defaultPalette = "Ocean",
            typicalSeconds = 14f,
            params = listOf(
                Param.float("glass", "Glass height", 0.2f, 1.3f, 0.8f, card = Card.DATA),
                Param.float("flow", "Flow", 20f, 800f, 75f, card = Card.MOTION, logScale = true),
                Param.float("thick", "Thickness", 0f, 0.9f, VISCOSITY, card = Card.MOTION)
            ),
            create = { PourSim() }
        )
    }
}
