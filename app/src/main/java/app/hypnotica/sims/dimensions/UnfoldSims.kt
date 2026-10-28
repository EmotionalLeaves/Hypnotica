package app.hypnotica.sims.dimensions

import app.hypnotica.core.nd.Nd
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A tesseract unfolded into three dimensions — the Dalí cross — and folded
 * back up again.
 *
 * The tesseract's eight cells are cubes, and two cells that touch share a
 * square. Unfolding is turning a cell a right angle about that square, which
 * in four dimensions is a rotation in the plane at right angles to it: the
 * cell swings through w and comes down flat in the same 3D space as the cube
 * it hangs from. Six cells hinge off the base cube (one per face); the
 * eighth, opposite the base, rides on one of them and then swings down past
 * it, making the long arm of the cross. Every position here is those
 * rotations applied exactly; nothing is moved by eye.
 */
class TesseractUnfoldSim : Sim {

    private var clock = 0f
    private var elapsed = 0f
    private var payoffFrame = false
    private var paid = false
    private var viewAngle = 0f
    private val landed = BooleanArray(CELLS)
    private val p4 = FloatArray(4)
    private val proj = FloatArray(3)
    private val sx = FloatArray(CELLS * 8)
    private val sy = FloatArray(CELLS * 8)
    private val sd = FloatArray(CELLS * 8)
    private val progress = FloatArray(CELLS)

    override fun setup(env: SimEnv) {
        clock = 0f
        elapsed = 0f
        payoffFrame = false
        paid = false
        viewAngle = 0.5f
        landed.fill(false)
        progress.fill(0f)
    }

    /** How far through its right-angle turn each cell is, 0 folded to 1 flat. */
    private fun schedule(t: Float) {
        val unfolding = t < CYCLE * 0.5f
        val u = if (unfolding) t else CYCLE - t
        // Arms one after another, overlapping; then the long arm's tip.
        for (k in 0 until 6) progress[ARM_ORDER[k] + 1] = ease(((u - HOLD_START - k * STAGGER) / ARM_TIME).coerceIn(0f, 1f))
        progress[LAST] = ease(((u - HOLD_START - 6 * STAGGER - ARM_TIME * 0.6f) / LAST_TIME).coerceIn(0f, 1f))
        progress[BASE] = 0f
    }

    private fun ease(x: Float): Float = x * x * (3f - 2f * x)

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        viewAngle += h * env.params.f("turn", VIEW_TURN)
        clock = (clock + h * env.params.f("pace", 1f)) % CYCLE
        schedule(clock)
        for (c in 1 until CELLS) {
            val flat = progress[c] >= 1f
            if (flat && !landed[c]) env.emit(EventKind.HIT, 0.35f + 0.08f * c, 0f, 0.6f)
            if (!flat && landed[c] && progress[c] <= 0f) env.emit(EventKind.HIT, 0.25f + 0.05f * c, 0f, 0.35f)
            if (flat) landed[c] = true else if (progress[c] <= 0f) landed[c] = false
        }
        if (!paid && progress[LAST] >= 1f) {
            paid = true
            payoffFrame = true
            env.emit(EventKind.DONE, 1f, 0f, 1f)
        }
    }

    /**
     * The 4D position of corner [v] (three bits: the cell's three free axes)
     * of cell [c], after its hinge turns.
     */
    private fun corner(c: Int, v: Int, out: FloatArray) {
        val axis = AXIS[c]; val sign = SIGN[c]
        var bit = 0
        for (a in 0 until 4) {
            if (a == axis) out[a] = sign
            else { out[a] = if ((v shr bit) and 1 == 1) 1f else -1f; bit++ }
        }
        if (c == LAST) {
            // First, flat onto its host, the −y cell: a turn in the y–w plane
            // about the square they share (y = −1, w = 1).
            hinge(out, 1, 3, -1f, 1f, progress[LAST], 1f)
            // Then wherever the host goes, it goes too.
            armTurn(out, HOST, progress[HOST])
        } else if (c != BASE) {
            armTurn(out, c, progress[c])
        }
    }

    /** Cell [c]'s own hinge, about the square it shares with the base (its axis = sign, w = −1). */
    private fun armTurn(p: FloatArray, c: Int, t: Float) {
        // Turning outward: at a right angle the cell lies in w = −1 and
        // reaches from its axis = sign to sign·3.
        hinge(p, AXIS[c], 3, SIGN[c], -1f, t, -SIGN[c])
    }

    /**
     * Rotate [p] in the plane of axes a and b, about the point a = ca,
     * b = cb, through t right angles, in direction [dir].
     */
    private fun hinge(p: FloatArray, a: Int, b: Int, ca: Float, cb: Float, t: Float, dir: Float) {
        if (t <= 0f) return
        val ang = t * (PI.toFloat() / 2f) * dir
        val c = cos(ang); val s = sin(ang)
        val ra = p[a] - ca; val rb = p[b] - cb
        p[a] = ca + ra * c - rb * s
        p[b] = cb + ra * s + rb * c
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        for (c in 0 until CELLS) for (v in 0 until 8) {
            corner(c, v, p4)
            // Centre the finished cross: its long arm runs from y = −5 to 3.
            p4[1] += 1f
            // The 3D view turns slowly and tilts, so depth reads.
            val x = p4[0]; val z = p4[2]
            p4[0] = x * cos(viewAngle) - z * sin(viewAngle)
            p4[2] = x * sin(viewAngle) + z * cos(viewAngle)
            val y = p4[1]; val z2 = p4[2]
            p4[1] = y * cos(TILT) - z2 * sin(TILT)
            p4[2] = y * sin(TILT) + z2 * cos(TILT)
            // Perspective one dimension at a time: w strongly, so the folded
            // tesseract shows as the familiar cube within a cube (every
            // point stays within w = ±1 throughout, worked out from the
            // hinges), then z gently.
            val kw = VIEWER_W / (VIEWER_W - p4[3])
            val zz = p4[2] * kw
            val kz = VIEWER_Z / (VIEWER_Z - zz)
            val i = c * 8 + v
            sx[i] = p4[0] * kw * kz * SCALE; sy[i] = p4[1] * kw * kz * SCALE; sd[i] = kw * kz
        }
        for (c in 0 until CELLS) {
            val col = if (c == BASE) Palette.TEXT_PRIMARY else palette.at((c - 0.5f) / (CELLS - 1))
            val moving = progress[c] > 0f && progress[c] < 1f
            for (e in 0 until 12) {
                val a = c * 8 + CUBE_EDGES[2 * e]; val b = c * 8 + CUBE_EDGES[2 * e + 1]
                val near = ((sd[a] + sd[b]) * 0.5f - 0.8f).coerceIn(0f, 1f)
                g.line(sx[a], sy[a], sx[b], sy[b], if (moving) 0.006f else 0.0035f, col, 0.45f + 0.45f * near)
            }
        }
        if (paid && progress[LAST] >= 1f) {
            val k = ((sin(elapsed * 3f) + 1f) * 0.5f)
            g.glow(0f, 0f, 0.5f, Palette.SUCCESS, 0.08f + 0.06f * k)
        }
    }

    override fun frameHalfWidth(env: SimEnv): Float = 0.66f

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> {
        val flat = (1 until CELLS).count { progress[it] >= 1f } + 1
        val moving = (1 until CELLS).firstOrNull { progress[it] > 0f && progress[it] < 1f }
        return listOf(
            "cells in 3D" to "$flat/$CELLS",
            "hinge" to if (moving != null) String.format(java.util.Locale.US, "%.0f°", progress[moving] * 90f) else "—",
            "shape" to if (flat == CELLS) "Dalí cross" else if (flat == 1) "tesseract" else "unfolding"
        )
    }

    companion object {
        const val CELLS = 8
        const val BASE = 0
        /** Cells: the base (w = −1), then ±x, ±y, ±z, then the far cell (w = +1). */
        val AXIS = intArrayOf(3, 0, 0, 1, 1, 2, 2, 3)
        val SIGN = floatArrayOf(-1f, 1f, -1f, 1f, -1f, 1f, -1f, 1f)
        const val LAST = 7
        /** The −y cell, which the far cell rides on into the long arm. */
        const val HOST = 4
        /** The order the arms open: around, ending with the host. */
        val ARM_ORDER = intArrayOf(0, 1, 4, 5, 2, 3)
        val CUBE_EDGES = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 0, 2, 1, 3, 4, 6, 5, 7, 0, 4, 1, 5, 2, 6, 3, 7)
        const val HOLD_START = 0.8f
        const val STAGGER = 0.45f
        const val ARM_TIME = 1.1f
        const val LAST_TIME = 1.3f
        /** Folded, unfolding, held open, folding, held shut. */
        const val CYCLE = 2f * (HOLD_START + 6 * STAGGER + ARM_TIME * 0.6f + LAST_TIME + 1.6f)
        const val VIEW_TURN = 0.28f
        const val TILT = 0.38f
        const val VIEWER_W = 3.5f
        const val VIEWER_Z = 16f
        const val SCALE = 0.22f

        fun def(): SimDef = SimDef(
            id = "dimensions/tesseract-unfold",
            title = "Unfolding a tesseract",
            shelf = Shelf.DIMENSIONS,
            family = "Higher-dimensional objects",
            blurb = "Eight cubes hinge out through the fourth dimension and lie flat as a cross.",
            formats = listOf(TrapFormat.REVEAL, TrapFormat.GROW),
            heroLabel = "Again",
            defaultPalette = "Aurora",
            typicalSeconds = 9f,
            params = listOf(
                Param.float("pace", "Pace", 0.3f, 3f, 1f, card = Card.MOTION),
                Param.float("turn", "View turn", 0f, 1.5f, VIEW_TURN, card = Card.MOTION)
            ),
            create = { TesseractUnfoldSim() }
        )
    }
}

/**
 * Every corner of an N-cube, each visited once, along its edges: the
 * reflected Gray code. Corner k is the binary number k ⊕ (k ≫ 1); one step to
 * the next flips exactly one bit, which is one edge, and after all 2ⁿ the
 * last is one edge from the first, so the tour closes. Each corner sounds
 * its binary value, so the walk is heard climbing and falling through every
 * number from 0 to 2ⁿ − 1.
 */
class GrayWalkSim : Sim {

    private var dims = 4
    private var verts = FloatArray(0)
    private var edges = IntArray(0)
    private var visitedEdge = BooleanArray(0)
    private var edgeOf = HashMap<Int, Int>()
    private var stepIndex = 0
    private var along = 0f
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var paid = false
    private var tmp = FloatArray(0)
    private val proj = FloatArray(3)
    private var sx = FloatArray(0)
    private var sy = FloatArray(0)
    private var sd = FloatArray(0)
    private var viewAngle = 0f
    private var scale = 0.6f
    private var viewer = 3f
    private var lit = BooleanArray(0)

    override fun setup(env: SimEnv) {
        dims = env.params.i("dims", 4).coerceIn(2, Nd.MAX_DIMS)
        verts = Nd.hypercubeVertices(dims)
        edges = Nd.hypercubeEdges(dims)
        val n = 1 shl dims
        edgeOf = HashMap()
        for (e in 0 until edges.size / 2) edgeOf[edges[2 * e] * 64 + edges[2 * e + 1]] = e
        visitedEdge = BooleanArray(edges.size / 2)
        lit = BooleanArray(n)
        lit[gray(0)] = true
        tmp = FloatArray(dims)
        sx = FloatArray(n); sy = FloatArray(n); sd = FloatArray(n)
        viewer = Nd.viewerFor(0.5f, dims)
        scale = 0.62f / (0.5f * sqrt(dims.toFloat()))
        stepIndex = 0
        along = 0f
        elapsed = 0f
        doneAt = -1f
        payoffFrame = false
        paid = false
        viewAngle = 0.4f
    }

    private fun gray(k: Int): Int = k xor (k shr 1)

    private fun edgeBetween(a: Int, b: Int): Int = edgeOf[min(a, b) * 64 + max(a, b)] ?: -1

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        viewAngle += h * env.params.f("spin", 0.25f)
        val n = 1 shl dims
        if (stepIndex >= n) return
        // About ten seconds for the whole tour, whatever the size: more
        // corners, quicker steps.
        along += h * env.params.f("pace", 1f) * n / TOUR_SECONDS
        while (along >= 1f && stepIndex < n) {
            along -= 1f
            val from = gray(stepIndex % n)
            val to = gray((stepIndex + 1) % n)
            val e = edgeBetween(from, to)
            if (e >= 0) visitedEdge[e] = true
            lit[to] = true
            stepIndex++
            // The corner's own number, as a pitch.
            env.emit(EventKind.STEP, 0.15f + 0.8f * to / (n - 1f), 0f, 0.5f)
            if (!paid && stepIndex == n - 1) {
                // Every corner once.
                paid = true
                payoffFrame = true
                env.emit(EventKind.DONE, 1f, 0f, 1f)
            }
            if (stepIndex == n) doneAt = elapsed
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val n = 1 shl dims
        for (i in 0 until n) {
            for (k in 0 until dims) tmp[k] = verts[i * dims + k]
            if (dims >= 3) {
                Nd.rotatePlane(tmp, 1, dims, 0, dims - 1, viewAngle)
                Nd.rotatePlane(tmp, 1, dims, 1, 2, 0.4f)
                if (dims >= 4) Nd.rotatePlane(tmp, 1, dims, 2, 3, viewAngle * 0.6f)
            }
            Nd.project(tmp, 0, dims, viewer, proj)
            sx[i] = proj[0] * scale; sy[i] = proj[1] * scale; sd[i] = proj[2]
        }
        for (e in 0 until edges.size / 2) {
            val a = edges[2 * e]; val b = edges[2 * e + 1]
            if (visitedEdge[e]) {
                // Coloured by when it was walked would need a time per edge;
                // by which bit it flips reads just as well: one colour per axis.
                val axis = Integer.numberOfTrailingZeros(a xor b)
                g.line(sx[a], sy[a], sx[b], sy[b], 0.006f, palette.at((axis + 0.5f) / dims))
            } else {
                g.line(sx[a], sy[a], sx[b], sy[b], 0.0025f, Palette.TEXT_SECONDARY, 0.25f)
            }
        }
        for (i in 0 until n) if (lit[i]) g.disc(sx[i], sy[i], 0.011f, Palette.brighten(palette.at(i.toFloat() / n), 0.15f))
        // The walker, partway along its edge.
        if (stepIndex < n) {
            val from = gray(stepIndex % n); val to = gray((stepIndex + 1) % n)
            val t = along.coerceIn(0f, 1f)
            val x = sx[from] + (sx[to] - sx[from]) * t
            val y = sy[from] + (sy[to] - sy[from]) * t
            g.glow(x, y, 0.07f, Palette.TEXT_PRIMARY, 0.5f)
            g.disc(x, y, 0.018f, Palette.TEXT_PRIMARY)
        } else {
            val k = (1f - (elapsed - doneAt)).coerceIn(0f, 1f)
            if (k > 0f) g.glow(0f, 0f, 0.6f, Palette.SUCCESS, 0.35f * k)
        }
    }

    override fun frameHalfWidth(env: SimEnv): Float = 0.66f

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && elapsed - doneAt > 3f

    override fun counters(): List<Pair<String, String>> {
        val n = 1 shl dims
        val at = gray(min(stepIndex, n - 1) % n)
        val bits = Integer.toBinaryString(at).padStart(dims, '0')
        return listOf(
            "corners" to "${lit.count { it }}/$n",
            "corner" to bits,
            "dimensions" to dims.toString()
        )
    }

    companion object {
        const val TOUR_SECONDS = 10f

        fun def(): SimDef = SimDef(
            id = "dimensions/gray-walk",
            title = "Every corner once",
            shelf = Shelf.DIMENSIONS,
            family = "Higher-dimensional objects",
            blurb = "A walk round all 2ⁿ corners of an N-cube along its edges, one bit flipped at a time.",
            formats = listOf(TrapFormat.FILL, TrapFormat.SONG, TrapFormat.NUMBER_UP),
            heroLabel = "Walk again",
            defaultPalette = "Spectrum",
            typicalSeconds = 12f,
            params = listOf(
                Param.int("dims", "Dimensions", 2, Nd.MAX_DIMS, 4, card = Card.DATA),
                Param.float("pace", "Pace", 0.3f, 3f, 1f, card = Card.MOTION),
                Param.float("spin", "View turn", 0f, 1.5f, 0.25f, card = Card.MOTION)
            ),
            create = { GrayWalkSim() }
        )
    }
}
