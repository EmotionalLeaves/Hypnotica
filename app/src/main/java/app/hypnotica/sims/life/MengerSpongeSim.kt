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
import app.hypnotica.core.sim.TrapFormat
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Menger sponge, carved a level at a time. Cut a cube into 27 and take
 * out the middle one and the six in the middles of its faces, leaving 20;
 * do the same to each of those, and again. After n steps there are 20ⁿ
 * cubes, the volume is (20/27)ⁿ of the original — heading to nothing — and
 * the surface is 2(20/9)ⁿ + 4(8/9)ⁿ times the face of the cube, heading to
 * infinity: in the limit, an object with no volume and endless surface, of
 * dimension log 20 / log 3 ≈ 2.73.
 *
 * Drawn as solid cubes turning in 3D, every face shaded by its angle to the
 * light and coloured by the level whose cut first exposed it, so the tunnels
 * of each generation read apart. The payoff is the last level's cut.
 */
class MengerSpongeSim : Sim {

    private var levels = 3
    /** For each level k (grid 3^k on a side): the level at which each cell is cut out, 0 if never. */
    private var cutAt = Array(0) { ByteArray(0) }

    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var yaw = 0.6f
    private var shownLevel = 0      // levels fully carved
    private var carving = 0         // level being carved now, 0 if none
    private var carveT = 0f         // 0..1 through the current carve
    private var removedNotes = 0
    private var noteCarry = 0f
    private var clock = 0f

    // View, rebuilt each draw.
    private val rot = FloatArray(9)

    override fun setup(env: SimEnv) {
        levels = env.params.i("levels", 3).coerceIn(1, MAX_LEVEL)
        cutAt = Array(levels + 1) { k ->
            val n = pow3(k)
            ByteArray(n * n * n) { idx ->
                val x = idx % n; val y = (idx / n) % n; val z = idx / (n * n)
                cutLevel(x, y, z, k).toByte()
            }
        }
        elapsed = 0f
        doneAt = -1f
        payoffFrame = false
        yaw = 0.6f
        shownLevel = 0
        carving = 0
        carveT = 0f
        removedNotes = 0
        noteCarry = 0f
        clock = 0f
    }

    private fun pow3(k: Int): Int { var r = 1; repeat(k) { r *= 3 }; return r }

    /**
     * The first level (1 = the coarsest) at which cell (x, y, z) of a 3^k grid
     * is cut out: the first base-3 digit position, from the top, at which at
     * least two of its three coordinates are in the middle third.
     */
    private fun cutLevel(x: Int, y: Int, z: Int, k: Int): Int {
        var div = pow3(k - 1)
        for (p in 1..k) {
            val dx = (x / div) % 3; val dy = (y / div) % 3; val dz = (z / div) % 3
            var ones = 0
            if (dx == 1) ones++
            if (dy == 1) ones++
            if (dz == 1) ones++
            if (ones >= 2) return p
            div /= 3
        }
        return 0
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        yaw += h * env.params.f("turn", 0.35f)
        // A clock of its own, so moving the pace slider speeds the carving
        // from here on instead of jumping it to another level.
        clock += h * env.params.f("pace", 1f).coerceIn(0.3f, 3f)
        val per = CARVE + HOLD
        val t = clock - FIRST_HOLD
        val wasShown = shownLevel
        if (t < 0f) { carving = 0; carveT = 0f; shownLevel = 0 }
        else {
            val k = (t / per).toInt()
            val inK = t - k * per
            if (k >= levels) { carving = 0; carveT = 0f; shownLevel = levels }
            else if (inK < CARVE) { carving = k + 1; carveT = inK / CARVE; shownLevel = k }
            else { carving = 0; carveT = 0f; shownLevel = k + 1 }
        }
        // Pops as the cubes go, a few a second, climbing through the cut.
        noteCarry = min(1.5f, noteCarry + h * NOTES_PER_SECOND)
        if (carving > 0 && carveT in 0.15f..0.85f && noteCarry >= 1f) {
            noteCarry -= 1f
            env.emit(EventKind.STEP, 0.2f + 0.15f * carving + 0.25f * carveT, 0f, 0.3f)
        }
        // Each finished level lands with a hit; the last one is the payoff,
        // which the engine sounds itself.
        if (shownLevel > wasShown) {
            if (shownLevel < levels) env.emit(EventKind.HIT, 0.3f + 0.2f * shownLevel, 0f, 0.8f)
            else if (doneAt < 0f) { doneAt = elapsed; payoffFrame = true }
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        // The grid this frame is drawn on: the level being carved, or the
        // last one finished.
        val k = if (carving > 0) carving else shownLevel
        val n = pow3(k)
        val cells = cutAt[k]
        val cell = SIZE / n
        val half = cell * 0.5f
        // View: turn about the vertical, then tip towards the viewer.
        val cy = cos(yaw); val sy = sin(yaw)
        val cp = cos(PITCH); val sp = sin(PITCH)
        // rot rows: screen x, screen y, depth (towards the viewer).
        rot[0] = cy; rot[1] = 0f; rot[2] = sy
        rot[3] = sy * sp; rot[4] = cp; rot[5] = -cy * sp
        rot[6] = -sy * cp; rot[7] = sp; rot[8] = cy * cp
        // Which side of each axis faces the viewer, and so which way to walk
        // the grid: far cells first, the painter's order. For equal cells on
        // a grid, walking every axis from far to near is exact — a cell that
        // can hide another is never farther along any axis.
        val facing = IntArray(3) { a -> if (rot[6 + a] >= 0f) 1 else -1 }
        // Shading per face direction: the light from the upper left, in
        // front, fixed to the viewer so the turning sponge catches it.
        val shade = FloatArray(3) { a ->
            val nx = rot[a] * facing[a]; val ny = rot[3 + a] * facing[a]; val nz = rot[6 + a] * facing[a]
            (AMBIENT + (1f - AMBIENT) * max(0f, nx * LX + ny * LY + nz * LZ)).coerceIn(0f, 1f)
        }
        val levelCols = IntArray(levels + 1) { lv -> palette.at(0.12f + 0.76f * lv / max(1, levels)) }
        val cutCol = Palette.TEXT_PRIMARY
        // How far along the cut each cell is: the holes open in a sweep from
        // one corner to the other.
        val origin = -SIZE * 0.5f
        for (zi in (if (facing[2] > 0) 0 until n else n - 1 downTo 0)) {
            for (yi in (if (facing[1] > 0) 0 until n else n - 1 downTo 0)) {
                for (xi in (if (facing[0] > 0) 0 until n else n - 1 downTo 0)) {
                    val idx = (zi * n + yi) * n + xi
                    val cl = cells[idx].toInt()
                    val scale = presence(cl, xi, yi, zi, n)
                    if (scale <= 0f) continue
                    val px = origin + (xi + 0.5f) * cell
                    val py = origin + (yi + 0.5f) * cell
                    val pz = origin + (zi + 0.5f) * cell
                    val shrinking = scale < 1f
                    val hh = half * scale
                    for (a in 0..2) {
                        val f = facing[a]
                        // Only a face whose neighbour is not solid is seen.
                        var exposed = 0
                        if (!shrinking) {
                            val ni = xi + if (a == 0) f else 0
                            val nj = yi + if (a == 1) f else 0
                            val nk = zi + if (a == 2) f else 0
                            if (ni in 0 until n && nj in 0 until n && nk in 0 until n) {
                                val ncl = cells[(nk * n + nj) * n + ni].toInt()
                                if (presence(ncl, ni, nj, nk, n) >= 1f) continue
                                exposed = ncl
                            }
                        }
                        val base = if (shrinking) Palette.lerp(levelCols[min(levels, carving)], cutCol, 0.6f) else levelCols[exposed]
                        val col = Palette.lerp(Palette.BG, base, shade[a])
                        face(g, px, py, pz, a, f, hh, col)
                    }
                }
            }
        }
    }

    /**
     * How much of cell (cut level [cl]) stands: 1 whole, 0 gone, between
     * while it is shrinking away in the current cut.
     */
    private fun presence(cl: Int, x: Int, y: Int, z: Int, n: Int): Float {
        if (cl == 0) return 1f
        if (cl <= shownLevel) return 0f
        if (cl != carving) return 1f
        // The sweep: each cell starts at its place along the diagonal.
        val start = (x + y + z) / (3f * max(1, n - 1)) * SWEEP
        val u = ((carveT - start) / (1f - SWEEP)).coerceIn(0f, 1f)
        return 1f - u * u * (3f - 2f * u)
    }

    /**
     * One face of a cube centred at (x, y, z) with half-size [h]: the face on
     * the [f] side of axis [a]. A turned square projects to a parallelogram,
     * which the draw list cannot hold as one shape, so it is laid down as a
     * few overlapping strips along its longer side — as few as keep the
     * staircase at its ends under a pixel or two.
     */
    private fun face(g: G, x: Float, y: Float, z: Float, a: Int, f: Int, h: Float, col: Int) {
        // Face centre and its two edge vectors, in model space.
        val b = (a + 1) % 3; val c = (a + 2) % 3
        val cx = x + (if (a == 0) f * h else 0f)
        val cyy = y + (if (a == 1) f * h else 0f)
        val cz = z + (if (a == 2) f * h else 0f)
        val sx = rot[0] * cx + rot[1] * cyy + rot[2] * cz
        val sy = rot[3] * cx + rot[4] * cyy + rot[5] * cz
        var ux = rot[b] * 2f * h; var uy = rot[3 + b] * 2f * h
        var vx = rot[c] * 2f * h; var vy = rot[3 + c] * 2f * h
        var lu = sqrt(ux * ux + uy * uy); var lv = sqrt(vx * vx + vy * vy)
        if (lv > lu) {
            var t = ux; ux = vx; vx = t
            t = uy; uy = vy; vy = t
            t = lu; lu = lv; lv = t
        }
        if (lu < 1e-6f) return
        // Height of the parallelogram across the strips.
        val across = abs(ux * vy - uy * vx) / lu
        if (across < 1e-5f) return
        val strips = ceil(across / STRIP).toInt().coerceIn(1, MAX_STRIPS)
        val ang = atan2(uy, ux)
        val hw = lu * 0.5f + OVERLAP
        val hh = across / (2f * strips) + OVERLAP
        for (s in 0 until strips) {
            val t = (s + 0.5f) / strips - 0.5f
            g.rect(sx + vx * t, sy + vy * t, hw, hh, col, ang)
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && elapsed - doneAt > 4f

    override fun counters(): List<Pair<String, String>> {
        val k = shownLevel
        val vol = (20.0 / 27.0).pow(k)
        val surf = (2.0 * (20.0 / 9.0).pow(k) + 4.0 * (8.0 / 9.0).pow(k)) / 6.0
        return listOf(
            "level" to "$k/$levels",
            "cubes" to "%,d".format(java.util.Locale.US, 20.0.pow(k).toLong()),
            "volume" to String.format(java.util.Locale.US, "%.1f%%", vol * 100.0),
            "surface" to String.format(java.util.Locale.US, "×%.2f", surf)
        )
    }

    // A turning cube reaches out to half its space diagonal.
    override fun frameHalfWidth(env: SimEnv): Float = SIZE * 0.5f * sqrt(3f) + 0.04f

    override fun frameHalfHeight(env: SimEnv): Float = SIZE * 0.5f * sqrt(3f) + 0.08f

    companion object {
        const val MAX_LEVEL = 3
        const val SIZE = 1f
        const val PITCH = 0.52f
        const val FIRST_HOLD = 1.6f
        const val CARVE = 2.2f
        const val HOLD = 1.6f
        /** Share of a cut over which the sweep starts, corner to corner. */
        const val SWEEP = 0.55f
        const val AMBIENT = 0.32f
        const val LX = -0.45f
        const val LY = 0.62f
        const val LZ = 0.64f
        const val STRIP = 0.0045f
        const val MAX_STRIPS = 160
        const val OVERLAP = 0.0006f
        const val NOTES_PER_SECOND = 7f

        fun def(): SimDef = SimDef(
            id = "dimensions/menger-sponge",
            title = "Menger sponge",
            shelf = Shelf.DIMENSIONS,
            family = "Fractals & growth",
            blurb = "Carve a cube into 27 and keep 20, again and again: volume heads to nothing, surface to infinity.",
            formats = listOf(TrapFormat.GROW, TrapFormat.REVEAL),
            heroLabel = "Again",
            defaultPalette = "Ocean",
            typicalSeconds = 15f,
            params = listOf(
                Param.int("levels", "Levels", 1, MAX_LEVEL, 3, card = Card.DATA),
                Param.float("pace", "Pace", 0.3f, 3f, 1f, card = Card.MOTION),
                Param.float("turn", "Turn", 0f, 1.2f, 0.35f, card = Card.MOTION)
            ),
            create = { MengerSpongeSim() }
        )
    }
}
