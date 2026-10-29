package app.hypnotica.sims.life

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.recipe.ParamInput
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.Shelf
import app.hypnotica.core.sim.Sim
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.sim.SimEnv
import app.hypnotica.core.sim.TrapFormat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Truchet tiles, in Cyril Stanley Smith's version: every square carries two
 * quarter circles joining the midpoints of neighbouring sides, and has only
 * two ways to lie. Laid at random they wander into a tangle of open paths and
 * stray loops; every path is coloured as one piece, by how long it is. Then,
 * one by one in a wave from the middle, the tiles that disagree with a design
 * turn a quarter — and the tangle reorganises, loop by loop, until the whole
 * field settles into the design: nested rings, a field of circles, or waves.
 *
 * A path can only close up when every tile along it agrees, so a loop
 * appears whole, the moment its last tile lands. The payoff is the last tile.
 */
class TruchetSim : Sim {

    private var cols = 0
    private var rows = 0
    private var size = 0.1f
    private var x0 = 0f
    private var y0 = 0f
    private var halfW = 0.46f

    private var orient = ByteArray(0)    // 0: arcs at bottom-left and top-right corners; 1: the other two
    private var target = ByteArray(0)
    private var startAt = FloatArray(0)  // when each tile that must turn begins to, or −1
    private var turned = BooleanArray(0)

    // Paths: the midpoints of tile sides are the nodes, each arc an edge.
    private var parent = IntArray(0)
    private var arcsIn = IntArray(0)     // arcs per component root
    private var open = BooleanArray(0)   // component reaches the border
    private var nodeA = IntArray(0)      // the two nodes each tile's two arcs join, 4 per tile
    private var maxLoop = 1
    private var loops = 0
    private var openPaths = 0

    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var lastStart = 0f
    private var toTurn = 0
    private var landed = 0
    private var noteCarry = 0f
    private var heldNote = -1f
    private var heldPan = 0f
    private var loopCarry = 0f

    override fun setup(env: SimEnv) {
        halfW = env.halfWidth
        val across = env.params.i("tiles", 9).coerceIn(4, 30)
        // Sized by the screen's short side, so a wide screen gets more tiles
        // rather than giant ones.
        val usableW = 2f * (halfW - EDGE)
        val usableH = FIELD_H
        size = min(usableW, usableH) / across
        cols = (usableW / size).toInt().coerceAtLeast(2)
        rows = (usableH / size).toInt().coerceAtLeast(2)
        x0 = -cols * size * 0.5f
        y0 = -rows * size * 0.5f - 0.03f
        val n = cols * rows
        orient = ByteArray(n) { if (env.rng.nextFloat() < 0.5f) 0 else 1 }
        target = ByteArray(n)
        val pattern = env.params.choiceIndex("pattern")
        for (j in 0 until rows) for (i in 0 until cols) target[j * cols + i] = design(pattern, i, j).toByte()
        // The wave: out from the middle, a tile at a time in distance order,
        // with a little jitter so each ring of tiles does not land as one.
        val pace = env.params.f("pace", 1f).coerceIn(0.2f, 5f)
        val cx = (cols - 1) * 0.5f; val cy = (rows - 1) * 0.5f
        val far = sqrt(cx * cx + cy * cy).coerceAtLeast(1f)
        startAt = FloatArray(n) { -1f }
        turned = BooleanArray(n)
        toTurn = 0
        lastStart = 0f
        for (j in 0 until rows) for (i in 0 until cols) {
            val k = j * cols + i
            if (orient[k] == target[k]) continue
            val d = sqrt((i - cx) * (i - cx) + (j - cy) * (j - cy)) / far
            val t = HOLD + (d * SWEEP + env.rng.range(0f, JITTER)) / pace
            startAt[k] = t
            lastStart = max(lastStart, t)
            toTurn++
        }
        landed = 0
        // Node numbering: horizontal sides first (cols × (rows+1)), then vertical ((cols+1) × rows).
        val nodes = cols * (rows + 1) + (cols + 1) * rows
        parent = IntArray(nodes); arcsIn = IntArray(nodes); open = BooleanArray(nodes)
        nodeA = IntArray(n * 4)
        rebuild(null)
        elapsed = 0f
        doneAt = -1f
        payoffFrame = false
        noteCarry = 0f
        heldNote = -1f
        loopCarry = 0f
    }

    /**
     * The designs, as which way each tile lies. Alternating by the parity of
     * i + j closes every arc into a small circle round a grid corner; adding
     * the ring number (how many tiles in from the border) flips the parity
     * across each ring boundary, which turns the circles into rings nested
     * round the centre; adding the row number instead gives waves.
     */
    private fun design(p: Int, i: Int, j: Int): Int {
        val ci = (cols - 1) * 0.5f; val cj = (rows - 1) * 0.5f
        return when (p) {
            0 -> {
                // Rings counted in from the border, so the nest fills a field
                // of any shape; counted out from the middle, a tall field's
                // rings ran into its sides and the ends were open waves.
                val ring = min(min(i, cols - 1 - i), min(j, rows - 1 - j))
                (i + j + ring + 1) and 1
            }
            1 -> (i + j) and 1
            3 -> ((i / 2) + (j / 2)) and 1
            else -> ((i + j) / 2) and 1
        }
    }

    private fun hNode(i: Int, j: Int) = j * cols + i                          // side under row j
    private fun vNode(i: Int, j: Int) = cols * (rows + 1) + j * (cols + 1) + i // side left of column i

    /** The four nodes the tile's two arcs join, for orientation [o]. */
    private fun tileNodes(i: Int, j: Int, o: Int, out: IntArray, at: Int) {
        val b = hNode(i, j); val t = hNode(i, j + 1)
        val l = vNode(i, j); val r = vNode(i + 1, j)
        if (o == 0) { out[at] = b; out[at + 1] = l; out[at + 2] = t; out[at + 3] = r }
        else { out[at] = l; out[at + 1] = t; out[at + 2] = b; out[at + 3] = r }
    }

    private fun find(a: Int): Int {
        var r = a
        while (parent[r] != r) r = parent[r]
        var c = a
        while (parent[c] != r) { val nx = parent[c]; parent[c] = r; c = nx }
        return r
    }

    /** Every path afresh, from the tiles as they lie now; [env] non-null sounds new loops. */
    private fun rebuild(env: SimEnv?) {
        for (k in parent.indices) { parent[k] = k; arcsIn[k] = 0; open[k] = false }
        for (j in 0 until rows) for (i in 0 until cols) {
            val k = j * cols + i
            tileNodes(i, j, orient[k].toInt(), nodeA, k * 4)
            for (a in 0..1) {
                val p = find(nodeA[k * 4 + a * 2]); val q = find(nodeA[k * 4 + a * 2 + 1])
                if (p != q) parent[p] = q
            }
        }
        for (j in 0 until rows) for (i in 0 until cols) {
            val k = j * cols + i
            arcsIn[find(nodeA[k * 4])]++
            arcsIn[find(nodeA[k * 4 + 2])]++
        }
        // The border's sides are path ends.
        for (i in 0 until cols) { open[find(hNode(i, 0))] = true; open[find(hNode(i, rows))] = true }
        for (j in 0 until rows) { open[find(vNode(0, j))] = true; open[find(vNode(cols, j))] = true }
        var l = 0; var o = 0; var big = 1
        for (k in parent.indices) {
            if (parent[k] != k || arcsIn[k] == 0) continue
            if (open[k]) o++ else { l++; big = max(big, arcsIn[k]) }
        }
        if (env != null && l > loops) loopCarry += (l - loops).toFloat()
        loops = l; openPaths = o; maxLoop = big
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        var changed = false
        var sumX = 0f; var count = 0; var far = 0f
        for (k in startAt.indices) {
            val s = startAt[k]
            if (s < 0f || turned[k]) continue
            if (elapsed >= s + TURN_SECONDS) {
                turned[k] = true
                orient[k] = target[k]
                landed++
                changed = true
                val i = k % cols; val j = k / cols
                sumX += x0 + (i + 0.5f) * size
                count++
                far = max(far, 1f - (startAt[k] - HOLD) / max(1e-3f, lastStart - HOLD))
            }
        }
        if (changed) {
            rebuild(env)
            heldNote = 0.25f + 0.6f * far.coerceIn(0f, 1f)
            heldPan = (sumX / count / halfW).coerceIn(-1f, 1f)
        }
        // A click as tiles land, a few a second at most; a closing loop is a
        // brighter note. The last tile's landing is the payoff, which the
        // engine sounds itself.
        noteCarry = min(1.5f, noteCarry + h * NOTES_PER_SECOND)
        val last = toTurn > 0 && landed == toTurn
        if (!last && noteCarry >= 1f) {
            if (loopCarry > 0f) {
                noteCarry -= 1f
                env.emit(EventKind.BIRTH, (0.4f + 0.1f * ln(1f + maxLoop.toFloat())).coerceAtMost(1f), heldPan, 0.6f)
                loopCarry = 0f
                heldNote = -1f
            } else if (heldNote >= 0f) {
                noteCarry -= 1f
                env.emit(EventKind.STEP, heldNote, heldPan, 0.35f)
                heldNote = -1f
            }
        }
        if (doneAt < 0f && (last || (toTurn == 0 && elapsed > HOLD))) {
            doneAt = elapsed
            payoffFrame = true
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val r = size * 0.5f
        val th = size * STROKE
        val since = if (doneAt >= 0f) elapsed - doneAt else -1f
        // Closed loops ease up to full strength once the design is complete.
        val settle = if (since >= 0f) (since / 0.8f).coerceIn(0f, 1f) else 0f
        val openCol = Palette.lerp(Palette.BG, Palette.TEXT_SECONDARY, 0.3f + 0.1f * settle)
        val logMax = ln(max(4f, maxLoop.toFloat()))
        for (j in 0 until rows) for (i in 0 until cols) {
            val k = j * cols + i
            val cx = x0 + (i + 0.5f) * size
            val cy = y0 + (j + 0.5f) * size
            val s = startAt[k]
            var turn = 0f
            if (s >= 0f && !turned[k] && elapsed > s) {
                val u = ((elapsed - s) / TURN_SECONDS).coerceIn(0f, 1f)
                turn = (u * u * (3f - 2f * u)) * (PI.toFloat() / 2f)
            }
            val moving = turn > 0f
            for (a in 0..1) {
                val root = find(nodeA[k * 4 + a * 2])
                val col = when {
                    // A tile in mid-turn belongs to no path yet: drawn white.
                    moving -> Palette.TEXT_PRIMARY
                    open[root] -> openCol
                    else -> {
                        val t = (ln(arcsIn[root].toFloat()) / logMax).coerceIn(0f, 1f)
                        val c = palette.at(0.08f + 0.84f * t)
                        Palette.lerp(Palette.lerp(Palette.BG, c, 0.82f), c, settle)
                    }
                }
                // Which corner this arc curls round, and where its quarter starts.
                val o = orient[k].toInt()
                val corner = if (o == 0) (if (a == 0) 0 else 2) else (if (a == 0) 3 else 1)
                // Corners counter-clockwise from bottom-left: 0 BL, 1 BR, 2 TR, 3 TL.
                val ox = if (corner == 0 || corner == 3) -r else r
                val oy = if (corner == 0 || corner == 1) -r else r
                val a0 = corner * (PI.toFloat() / 2f)
                val c = cos(turn); val sn = sin(turn)
                val px = cx + ox * c - oy * sn
                val py = cy + ox * sn + oy * c
                // A hair over a quarter, so neighbouring arcs overlap where they meet
                // instead of leaving an antialiased seam across every join.
                g.ring(px, py, r + th * 0.5f, th, col, gap = 1.5f * PI.toFloat() - 2f * SEAM, gapAngle = a0 + turn + PI.toFloat() / 2f + SEAM)
            }
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && elapsed - doneAt > 4f

    override fun counters(): List<Pair<String, String>> = listOf(
        "tiles turned" to "$landed/$toTurn",
        "closed loops" to loops.toString(),
        "open paths" to openPaths.toString()
    )

    override fun frameHalfWidth(env: SimEnv): Float = cols * size * 0.5f + 0.01f

    companion object {
        const val EDGE = 0.03f
        const val FIELD_H = 1.72f
        const val STROKE = 0.16f
        const val SEAM = 0.03f
        const val HOLD = 1.5f
        const val SWEEP = 9f
        const val JITTER = 1.2f
        const val TURN_SECONDS = 0.35f
        const val NOTES_PER_SECOND = 9f

        fun def(): SimDef = SimDef(
            id = "art/truchet",
            title = "Truchet tiles",
            shelf = Shelf.ART,
            family = "Generative art",
            blurb = "Quarter-circle tiles turn one by one, and a tangle of paths closes into a design.",
            formats = listOf(TrapFormat.REVEAL, TrapFormat.PERFECT_FIT),
            heroLabel = "Shuffle",
            defaultPalette = "Aurora",
            typicalSeconds = 16f,
            params = listOf(
                Param.choice("pattern", "Design", listOf("Rings", "Circles", "Waves", "Weave"), 0, card = Card.DATA, input = ParamInput.TILES),
                Param.int("tiles", "Tiles across", 4, 30, 9, card = Card.DATA),
                Param.float("pace", "Pace", 0.3f, 3f, 1f, card = Card.DATA)
            ),
            create = { TruchetSim() }
        )
    }
}
