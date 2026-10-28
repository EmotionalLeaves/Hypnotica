package app.hypnotica.sims.dimensions

import app.hypnotica.core.nd.Nd
import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Macro
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.recipe.ParamInput
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.LawKnob
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.Shelf
import app.hypnotica.core.sim.Sim
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.sim.SimEnv
import app.hypnotica.core.sim.TouchEvent
import app.hypnotica.core.sim.TrapFormat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A hypercube turning through planes that have no three-dimensional equivalent.
 *
 * In 4D the inner cube is not inside the outer one; it is the same cube seen
 * further away along W, which is why it appears to turn itself inside out.
 */
class HypercubeSim : Sim {

    private var dims = 4
    private var shape = 0
    private var nv = 16
    private var verts = FloatArray(0)
    private var edges = IntArray(0)
    private val proj = FloatArray(3)
    private var screenX = FloatArray(0)
    private var screenY = FloatArray(0)
    private var screenD = FloatArray(0)
    private var elapsed = 0f
    private var payoffFrame = false
    private var turns = 0L
    private var paidTurn = false
    private var planeA = 0
    private var planeB = 3
    private var manualSpin = 0f

    /** Which corners are currently on the far side of the extra axis. */
    private var behind = BooleanArray(0)

    override fun setup(env: SimEnv) {
        val p = env.params
        shape = p.choiceIndex("shape").coerceIn(0, SHAPES.size - 1)
        // The 24-cell exists in four dimensions and no other.
        dims = if (shape == 3) 4 else p.i("dims", 4).coerceIn(2, Nd.MAX_DIMS)
        // Every shape the same size, measured centre to corner, so switching
        // between them compares like with like.
        val radius = 0.5f * sqrt(dims.toFloat())
        when (shape) {
            1 -> { verts = Nd.crossVertices(dims, radius); edges = Nd.crossEdges(dims) }
            2 -> { verts = Nd.simplexVertices(dims, radius); edges = Nd.simplexEdges(dims) }
            3 -> { verts = Nd.cell24Vertices(radius); edges = Nd.edgesAtDistance(verts, 4, radius) }
            else -> { verts = Nd.hypercubeVertices(dims); edges = Nd.hypercubeEdges(dims) }
        }
        nv = verts.size / dims
        val n = nv
        screenX = FloatArray(n)
        screenY = FloatArray(n)
        screenD = FloatArray(n)
        // Both sliders offer 0..MAX_DIMS-1 while only 0..dims-1 axes exist. The
        // first wraps, so every position names a real axis.
        planeA = Math.floorMod(p.i("planeA", 0), dims)
        // The slider's number is the axis, capped at the highest axis this
        // many dimensions has, and moved on one if it lands on the first axis.
        // Two earlier mappings each broke the shipped tesseract in a different
        // way. An offset over the remaining axes sent the shipped 3 to axis 1,
        // so the default turned in the flat x–y plane — a spinning cube, no
        // corner ever passing through the fourth dimension. Its fix put 4D
        // right but left 3D spinning flat in the screen plane, and in five and
        // six dimensions the spin missed the axis the notes listen on, so a
        // six-cube made one sound in a whole turn. With this rule the shipped
        // value turns x–z in 3D and x–w from 4D up, and the two ends of the
        // slider still land on different axes.
        val wanted = min(p.i("planeB", 3).coerceAtLeast(0), dims - 1)
        planeB = if (wanted == planeA) (planeA + 1) % dims else wanted
        // After planeB, which it is measured against.
        behind = BooleanArray(n) { verts[it * dims + planeB] < 0f }
        elapsed = 0f
        payoffFrame = false
        turns = 0L
        manualSpin = 0f
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        val n = nv
        val speed = env.params.f("spin", 0.55f)
        val tilt = env.params.f("tilt", 0.22f)

        Nd.rotatePlane(verts, n, dims, planeA, planeB, (speed + manualSpin) * dt)
        if (dims >= 3) Nd.rotatePlane(verts, n, dims, 0, 1, tilt * dt)
        if (dims >= 5) Nd.rotatePlane(verts, n, dims, 2, 4, tilt * 0.6f * dt)
        // Without this the sixth axis never moved, so choosing six dimensions
        // added thirty-two corners and no new motion at all.
        if (dims >= 6) Nd.rotatePlane(verts, n, dims, 3, 5, tilt * 0.45f * dt)
        manualSpin *= (1f - 2f * dt).coerceIn(0f, 1f)

        // Every corner that crosses the far side of the extra axis is a note, so
        // the sound follows the shape rather than waiting for a whole turn.
        if (dims >= 3) {
            // The axis the spin passes through, so every turn is heard. It was
            // always the last axis, which from five dimensions up the spin
            // never touches.
            val axis = planeB
            for (i in 0 until n) {
                val w = verts[i * dims + axis]
                val wasBehind = behind[i]
                val isBehind = w < 0f
                if (isBehind != wasBehind) {
                    behind[i] = isBehind
                    // Both ways through, an octave apart. Only the trip behind
                    // used to sound, and the crossings come in bunches, so half
                    // of every turn was silent — twelve seconds at a stretch,
                    // measured, on a sim that never stops moving.
                    val pitch = 0.25f + 0.6f * (i.toFloat() / n)
                    env.emit(
                        EventKind.CROSS,
                        if (isBehind) pitch else (pitch + 0.15f).coerceAtMost(1f),
                        0f,
                        if (isBehind) 0.55f else 0.4f
                    )
                }
            }
        }

        // One full turn through the chosen plane is the beat of this sim.
        val period = if (speed > 0.001f) (2f * PI.toFloat() / speed) else 1e9f
        val completed = (elapsed / period).toLong()
        if (completed > turns) {
            turns = completed
            env.emit(EventKind.CROSS, 0.6f, 0f, 0.8f)
            // A full turn brings every corner back where it started, which is
            // the moment worth waiting for. It used to want the second turn and
            // test for it with ==, so below a spin of about 0.48 the payoff
            // landed after the clip had ended and TickTock threw every seed
            // away. One turn is enough when a turn is slow.
            if (!paidTurn && (turns >= 2L || elapsed > SLOW_TURN_SECONDS)) {
                paidTurn = true
                payoffFrame = true
            }
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val n = nv
        val scale = 0.62f
        val viewer = env.params.f("viewer", 3f)
        for (i in 0 until n) {
            Nd.project(verts, i * dims, dims, viewer, proj)
            screenX[i] = proj[0] * scale
            screenY[i] = proj[1] * scale
            screenD[i] = proj[2]
        }
        // Nearness, spread over the range this frame actually spans. The raw
        // factor only varies by a fifth either side of one, so used directly it
        // gave every corner nearly the same colour; stretched, near and far are
        // plainly different at two dimensions and at six.
        var lowest = Float.MAX_VALUE
        var highest = -Float.MAX_VALUE
        for (i in 0 until n) {
            if (screenD[i] < lowest) lowest = screenD[i]
            if (screenD[i] > highest) highest = screenD[i]
        }
        val span = (highest - lowest).coerceAtLeast(1e-4f)
        fun nearness(v: Float): Float = ((v - lowest) / span).coerceIn(0f, 1f)

        var e = 0
        while (e < edges.size) {
            val a = edges[e]
            val b = edges[e + 1]
            val near = (nearness(screenD[a]) + nearness(screenD[b])) * 0.5f
            val c = palette.at(0.15f + near * 0.75f)
            g.line(
                screenX[a], screenY[a], screenX[b], screenY[b],
                0.003f + near * 0.005f, c, 0.3f + near * 0.65f
            )
            e += 2
        }
        for (i in 0 until n) {
            val near = nearness(screenD[i])
            val c = palette.at(0.15f + near * 0.75f)
            g.glow(screenX[i], screenY[i], 0.03f + 0.045f * near, c, 0.25f + 0.25f * near)
            g.disc(screenX[i], screenY[i], 0.008f + 0.011f * near, Palette.brighten(c, 0.2f))
        }
    }

    override fun onInput(env: SimEnv, e: TouchEvent) {
        if (e.kind == TouchEvent.Kind.DRAG) manualSpin = (manualSpin + e.x * 0.6f).coerceIn(-3f, 3f)
    }

    /** Drawn at a fixed size; on a phone held upright the view pulls back to fit it. */
    override fun frameHalfWidth(env: SimEnv): Float = 0.65f

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "dimensions" to dims.toString(),
        "shape" to SHAPES[shape],
        "corners" to nv.toString(),
        "edges" to (edges.size / 2).toString(),
        "plane" to Nd.planeName(planeA, planeB)
    )

    companion object {
        /**
         * When a turn is slower than this, one is enough for the payoff.
         *
         * The slider reaches a spin of 0.05, which is four minutes a turn, so
         * waiting for the second turn put the payoff long after the clip had
         * ended and TickTock threw every seed away.
         */
        const val SLOW_TURN_SECONDS = 14f
        val SHAPES = listOf("Hypercube", "Cross-polytope", "Simplex", "24-cell")

        fun def(): SimDef = SimDef(
            id = "dimensions/hypercube",
            title = "Rotating hypercube",
            shelf = Shelf.DIMENSIONS,
            family = "Higher-dimensional objects",
            blurb = "A 4D cube turning through XW, where inside and outside swap — or its cousins: the 16-cell, the 5-cell, the 24-cell.",
            formats = listOf(TrapFormat.LOOP, TrapFormat.GROW),
            heroLabel = "New angle",
            defaultPalette = "Aurora",
            typicalSeconds = 26f,
            loopSeconds = 10.61f,
            params = listOf(
                Param.choice("shape", "Shape", SHAPES, 0, card = Card.DATA, input = ParamInput.TILES),
                // The 24-cell has four dimensions or none: the slider says so.
                Param.int("dims", "Dimensions", 2, Nd.MAX_DIMS, 4, card = Card.DATA)
                    .copy(mutedBy = "shape", mutedAt = listOf(3)),
                // Which plane turns is the most interesting thing about a
                // higher-dimensional object, and hiding it behind Advanced meant
                // raising the dimension count to six added thirty-two corners
                // and no new plane to watch them turn in. Both are integers, so
                // Evolve could not reach them either.
                Param.int("planeA", "Spin plane, first axis", 0, Nd.MAX_DIMS - 1, 0, card = Card.MOTION),
                Param.int("planeB", "Spin plane, second axis", 0, Nd.MAX_DIMS - 1, 3, card = Card.MOTION),
                Param.float("spin", "Spin", 0.05f, 2f, 0.55f, card = Card.MOTION),
                Param.float("tilt", "Tilt", 0f, 1.5f, 0.22f, card = Card.MOTION),
                Param.float("viewer", "Viewer distance", 1.6f, 8f, 3f, card = Card.LOOK)
            ),
            create = { HypercubeSim() }
        )
    }
}

/**
 * Flatland: an object of N dimensions passing through a world of N − 1.
 *
 * The flat world is the hyperplane w = h, and all its inhabitants can ever
 * see of the object is where it cuts that plane. Everything here is the real
 * cut, worked out exactly: a ball's slice is a ball one dimension down of
 * radius √(R² − h²); a polytope's is found edge by edge — wherever an edge
 * crosses the plane is a corner of the slice, and two such corners are
 * joined when their edges share a square (or triangle) face, since a plane
 * meets a flat convex face in one straight segment.
 *
 * Below, the object from outside: its height along w drawn true, so the flat
 * world is a horizontal line and the slice is exactly where the line cuts
 * it. Above, what the flat world sees: the slice itself, in its own N − 1
 * dimensions. A cube going through corner first is a point, a triangle, a
 * hexagon, a triangle, a point; a 4-cube is a tetrahedron that grows,
 * truncates into an octahedron and shrinks back to a tetrahedron the other
 * way up.
 */
class SlicingSim : Sim {

    private var dims = 3
    private var shape = 0
    private var position = -1f
    private var direction = 1f
    private var extent = 1f
    private var radius = 1f
    private var payoffFrame = false
    private var passes = 0L
    private var peaked = false
    private var elapsed = 0f
    private var viewAngle = 0f
    private var verts = FloatArray(0)
    private var edges = IntArray(0)
    /** Each 2-face as its edges, [faceSize] per face. */
    private var faces = IntArray(0)
    private var faceSize = 4
    // The slice: one corner per edge the plane crosses.
    private var crossAt = IntArray(0)
    private var sliceN = 0
    private var slice = FloatArray(0)
    private var sideX = FloatArray(0)
    private var lastCorners = -1
    private var sliceEdges = IntArray(0)
    private var sliceEdgeN = 0
    private var tmp = FloatArray(0)
    private val proj = FloatArray(3)
    private var sx = FloatArray(0)
    private var sy = FloatArray(0)
    private var sd = FloatArray(0)

    override fun setup(env: SimEnv) {
        dims = env.params.i("dims", 3).coerceIn(2, Nd.MAX_DIMS)
        shape = env.params.choiceIndex("shape").coerceIn(0, SHAPES.size - 1)
        radius = 0.5f * sqrt(dims.toFloat())
        when (shape) {
            0 -> { verts = FloatArray(0); edges = IntArray(0); faces = IntArray(0) }
            3 -> {
                verts = Nd.simplexVertices(dims, radius)
                edges = Nd.simplexEdges(dims)
                faceSize = 3
                faces = simplexFaces(dims + 1)
                // Point first: corner 0 turned onto the w axis.
                alignToW(verts, 0)
            }
            else -> {
                verts = Nd.hypercubeVertices(dims)
                edges = Nd.hypercubeEdges(dims)
                faceSize = 4
                faces = cubeFaces(dims)
                // Corner first: the long diagonal turned onto the w axis.
                if (shape == 2) alignToW(verts, (1 shl dims) - 1)
            }
        }
        val ne = edges.size / 2
        crossAt = IntArray(ne)
        slice = FloatArray(max(1, ne) * dims)
        sideX = FloatArray(max(1, ne))
        sliceEdges = IntArray(max(2, faces.size / faceSize * 2))
        tmp = FloatArray(dims)
        val nv = if (dims > 0) verts.size / dims else 0
        sx = FloatArray(max(1, nv)); sy = FloatArray(max(1, nv)); sd = FloatArray(max(1, nv))
        extent = when (shape) {
            0, 4 -> radius
            else -> {
                var m = 0f
                for (i in 0 until nv) m = max(m, abs(verts[i * dims + dims - 1]))
                m
            }
        }
        position = -extent * SWEEP
        direction = 1f
        payoffFrame = false
        passes = 0
        peaked = false
        elapsed = 0f
        viewAngle = 0f
        lastCorners = -1
        cut()
    }

    /** Every square face of the N-cube, as the four edges round it. */
    private fun cubeFaces(d: Int): IntArray {
        val index = HashMap<Long, Int>()
        for (e in 0 until edges.size / 2) index[key(edges[2 * e], edges[2 * e + 1])] = e
        val out = ArrayList<Int>()
        for (i in 0 until (1 shl d)) for (p in 0 until d) for (q in p + 1 until d) {
            if ((i shr p) and 1 == 1 || (i shr q) and 1 == 1) continue
            val a = i; val b = i or (1 shl p); val c = i or (1 shl q); val dd = b or (1 shl q)
            out.add(index[key(a, b)]!!); out.add(index[key(b, dd)]!!)
            out.add(index[key(dd, c)]!!); out.add(index[key(c, a)]!!)
        }
        return out.toIntArray()
    }

    /** Every triangle of the simplex, as its three edges. */
    private fun simplexFaces(n: Int): IntArray {
        val index = HashMap<Long, Int>()
        for (e in 0 until edges.size / 2) index[key(edges[2 * e], edges[2 * e + 1])] = e
        val out = ArrayList<Int>()
        for (a in 0 until n) for (b in a + 1 until n) for (c in b + 1 until n) {
            out.add(index[key(a, b)]!!); out.add(index[key(b, c)]!!); out.add(index[key(a, c)]!!)
        }
        return out.toIntArray()
    }

    private fun key(a: Int, b: Int): Long = min(a, b).toLong() * 4096L + max(a, b)

    /**
     * Turn the whole object so corner [v] points straight along w: a
     * reflection that swaps that corner's direction with the w axis. A mirror
     * image is the same shape, and it is exact.
     */
    private fun alignToW(data: FloatArray, v: Int) {
        val d = dims
        val u = FloatArray(d) { data[v * d + it] }
        val len = sqrt(u.fold(0f) { acc, x -> acc + x * x })
        if (len < 1e-6f) return
        for (k in 0 until d) u[k] /= len
        // Householder: H = I − 2·n·nᵀ, n = (u − e_w)/|u − e_w|.
        val nrm = FloatArray(d) { u[it] }
        nrm[d - 1] -= 1f
        val nl = sqrt(nrm.fold(0f) { acc, x -> acc + x * x })
        if (nl < 1e-6f) return
        for (k in 0 until d) nrm[k] /= nl
        for (i in 0 until data.size / d) {
            var dot = 0f
            for (k in 0 until d) dot += data[i * d + k] * nrm[k]
            for (k in 0 until d) data[i * d + k] -= 2f * dot * nrm[k]
        }
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        viewAngle += h * VIEW_TURN
        val speed = env.params.f("speed", 0.42f)
        // In the object's own height a second, so every shape takes the same
        // time to pass whatever its size along w.
        position += direction * speed * extent * dt
        val reach = extent * SWEEP
        if (position > reach) { position = reach; direction = -1f; passes++ }
        if (position < -reach) { position = -reach; direction = 1f; passes++ }
        // Tumbling: the object turns through planes that take in w, so the
        // slice changes shape as well as size.
        if (shape == 4) {
            val nv = verts.size / dims
            Nd.rotatePlane(verts, nv, dims, 0, dims - 1, h * 0.23f)
            if (dims >= 3) Nd.rotatePlane(verts, nv, dims, 1, dims - 1, h * 0.17f)
        }
        cut()
        // Each corner passing through the flat world is a note: the slice
        // gaining or losing a corner is an event the flat world sees.
        if (shape != 0 && sliceN != lastCorners) {
            if (lastCorners >= 0) env.emit(EventKind.STEP, (sliceN / max(1f, edges.size / 2f)).coerceIn(0f, 1f) * 0.8f + 0.1f, 0f, 0.45f)
            lastCorners = sliceN
        }
        if (shape == 0) {
            // A ball: the note follows what the flat world measures.
            val content = sliceContent()
            val q = (content * 12f).toInt()
            if (q != lastCorners) {
                if (content > 0f && lastCorners >= 0) env.emit(EventKind.STEP, content.coerceIn(0f, 1f), 0f, 0.35f)
                lastCorners = q
            }
        }
        // The payoff: the middle of the object passing through.
        if (!peaked && abs(position) < extent * 0.02f + 0.004f) {
            peaked = true
            payoffFrame = true
            env.emit(EventKind.DONE, 0.9f, 0f, 1f)
        }
        if (abs(position) > extent * 0.6f) peaked = false
    }

    /** The slice at the current height, corners and edges, worked out exactly. */
    private fun cut() {
        sliceN = 0
        sliceEdgeN = 0
        if (shape == 0) return
        val d = dims
        val w = d - 1
        val ne = edges.size / 2
        for (e in 0 until ne) {
            crossAt[e] = -1
            val a = edges[2 * e]; val b = edges[2 * e + 1]
            val wa = verts[a * d + w] - position; val wb = verts[b * d + w] - position
            if ((wa < 0f) == (wb < 0f)) continue
            val t = wa / (wa - wb)
            for (k in 0 until d) slice[sliceN * d + k] = verts[a * d + k] + (verts[b * d + k] - verts[a * d + k]) * t
            crossAt[e] = sliceN
            sliceN++
        }
        // A face the plane crosses is cut in one segment, between the two of
        // its edges that cross.
        var f = 0
        while (f < faces.size) {
            var p = -1; var q = -1
            for (k in 0 until faceSize) {
                val c = crossAt[faces[f + k]]
                if (c < 0) continue
                if (p < 0) p = c else if (q < 0) q = c
            }
            if (p >= 0 && q >= 0) { sliceEdges[2 * sliceEdgeN] = p; sliceEdges[2 * sliceEdgeN + 1] = q; sliceEdgeN++ }
            f += faceSize
        }
    }

    private fun sliceRadius(): Float = Nd.sliceRadius(radius, position)

    /**
     * What the flat world would measure of a ball's slice, as a share of the
     * most it ever gets: its (dims − 1)-dimensional content, radius^(dims−1).
     */
    private fun sliceContent(): Float {
        val r = sliceRadius()
        if (r <= 0f) return 0f
        val f = (r / radius).coerceIn(0f, 1f)
        var v = 1f
        repeat(max(1, dims - 1)) { v *= f }
        return v
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val lineColour = palette.at(0.75f)
        val sideScale = PANEL / radius
        val worldY = SIDE_Y + position * sideScale
        // ---- Outside: the object with its true height, and the flat world.
        val ghost = Palette.withAlpha(palette.at(0.3f), 0.5f)
        if (shape == 0) {
            g.ring(0f, SIDE_Y, radius * sideScale, 0.004f, ghost)
        } else {
            val d = dims
            val nv = verts.size / d
            val sideViewer = Nd.viewerFor(radius, max(2, d - 1))
            for (i in 0 until nv) {
                // Across: the other axes in perspective. Up: w, exactly.
                for (k in 0 until d - 1) tmp[k] = verts[i * d + k]
                tmp[d - 1] = 0f
                Nd.project(tmp, 0, max(1, d - 1), sideViewer, proj)
                sx[i] = proj[0] * sideScale
                sy[i] = SIDE_Y + verts[i * d + d - 1] * sideScale
            }
            var e = 0
            while (e < edges.size) {
                val a = edges[e]; val b = edges[e + 1]
                g.line(sx[a], sy[a], sx[b], sy[b], 0.003f, ghost)
                e += 2
            }
        }
        g.line(-env.halfWidth * 0.95f, worldY, env.halfWidth * 0.95f, worldY, 0.004f, Palette.TEXT_SECONDARY, 0.55f)
        // Where it cuts, on the line.
        if (shape == 0) {
            val r = sliceRadius()
            if (r > 0f) g.line(-r * sideScale, worldY, r * sideScale, worldY, 0.008f, lineColour)
        } else {
            val d = dims
            val sideViewer = Nd.viewerFor(radius, max(2, d - 1))
            for (i in 0 until sliceN) {
                for (k in 0 until d - 1) tmp[k] = slice[i * d + k]
                Nd.project(tmp, 0, max(1, d - 1), sideViewer, proj)
                g.disc(proj[0] * sideScale, worldY, 0.009f, lineColour)
            }
        }
        // ---- Inside: what the flat world sees, in its own dimensions.
        val sliceDims = dims - 1
        val topScale = PANEL / radius
        if (shape == 0) {
            val r = sliceRadius()
            if (r > 0f) {
                val content = sliceContent()
                if (sliceDims == 1) {
                    g.line(-r * topScale, TOP_Y, r * topScale, TOP_Y, 0.01f, lineColour)
                } else {
                    // A disc in two dimensions; from three up, a ball, which
                    // seen from anywhere has a round outline of its radius.
                    g.glow(0f, TOP_Y, r * topScale * 1.2f, lineColour, 0.15f + 0.3f * content)
                    g.disc(0f, TOP_Y, r * topScale, Palette.withAlpha(lineColour, 0.35f))
                    g.ring(0f, TOP_Y, r * topScale, 0.005f, lineColour)
                    if (sliceDims >= 3) g.disc(-r * topScale * 0.35f, TOP_Y + r * topScale * 0.35f, r * topScale * 0.18f, Palette.withAlpha(Palette.TEXT_PRIMARY, 0.25f))
                }
            }
        } else if (sliceN > 0) {
            val d = dims
            val viewer = Nd.viewerFor(radius, max(2, sliceDims))
            for (i in 0 until sliceN) {
                for (k in 0 until sliceDims) tmp[k] = slice[i * d + k]
                // The flat world's own view of its slice, turning slowly so
                // its depth reads.
                if (sliceDims >= 3) {
                    Nd.rotatePlane(tmp, 1, sliceDims, 0, sliceDims - 1, viewAngle)
                    Nd.rotatePlane(tmp, 1, sliceDims, 1, 2, TILT)
                }
                Nd.project(tmp, 0, sliceDims, viewer, proj)
                sx[i] = proj[0] * topScale
                sy[i] = TOP_Y + (if (sliceDims >= 2) proj[1] else 0f) * topScale
                sd[i] = proj[2]
            }
            for (k in 0 until sliceEdgeN) {
                val a = sliceEdges[2 * k]; val b = sliceEdges[2 * k + 1]
                val near = ((sd[a] + sd[b]) * 0.5f - 0.6f).coerceIn(0f, 1f)
                g.line(sx[a], sy[a], sx[b], sy[b], 0.004f + 0.003f * near, lineColour, 0.55f + 0.45f * near)
            }
            for (i in 0 until sliceN) g.disc(sx[i], sy[i], 0.008f, Palette.brighten(lineColour, 0.2f))
        }
    }

    override fun frameHalfWidth(env: SimEnv): Float = 0.46f

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> {
        val d = dims - 1
        return if (shape == 0) listOf(
            "slice" to "${d}D ball",
            "radius" to String.format(java.util.Locale.US, "%.3f", sliceRadius()),
            // What the flat world measures: a (dims − 1)-dimensional content.
            "content" to String.format(java.util.Locale.US, "%.3f", sliceContent()),
            "passes" to passes.toString()
        ) else listOf(
            "slice" to "${d}D, $sliceN corners",
            "edges" to sliceEdgeN.toString(),
            "cut at w" to String.format(java.util.Locale.US, "%+.2f", position),
            "passes" to passes.toString()
        )
    }

    companion object {
        val SHAPES = listOf("Hypersphere", "Cube, face first", "Cube, corner first", "Simplex, point first", "Tumbling cube")
        /** Half the height of each panel, in world units, at the object's widest. */
        const val PANEL = 0.36f
        const val TOP_Y = 0.5f
        const val SIDE_Y = -0.45f
        /** How far past the object the flat world sweeps, so it's seen arriving. */
        const val SWEEP = 1.12f
        const val VIEW_TURN = 0.3f
        const val TILT = 0.4f

        fun def(): SimDef = SimDef(
            id = "dimensions/slicing",
            title = "Sphere through Flatland",
            shelf = Shelf.DIMENSIONS,
            family = "Slicing & Flatland",
            blurb = "What a flat world sees as a shape from one dimension up passes through it — worked out exactly.",
            formats = listOf(TrapFormat.LOOP, TrapFormat.REVEAL, TrapFormat.CUT),
            heroLabel = "Send it through",
            defaultPalette = "Ocean",
            typicalSeconds = 8f,
            loopSeconds = 13.25f,
            params = listOf(
                Param.choice("shape", "Object", SHAPES, 0, card = Card.DATA, input = ParamInput.CHIPS),
                Param.int("dims", "Dimensions", 2, Nd.MAX_DIMS, 3, card = Card.DATA),
                Param.float("speed", "Speed", 0.05f, 2f, 0.42f, card = Card.MOTION)
            ),
            macros = listOf(
                Macro(
                    "how-many-dims", "How many dimensions", Card.DATA,
                    mapOf("dims" to 2f..Nd.MAX_DIMS.toFloat()), 0.25f
                )
            ),
            create = { SlicingSim() }
        )
    }
}

/**
 * Random walks in one, two and three dimensions side by side.
 *
 * The payoff is a real theorem: a drunk walker always finds their way home on a
 * line or a grid, but in three dimensions they may wander forever.
 */
class RandomWalkSim : Sim {

    private var dims = 2
    private var walkers = 40
    private var pos = FloatArray(0)
    private var home = BooleanArray(0)
    private var steps = 0L
    private var returned = 0
    private var payoffFrame = false
    private var carry = 0f
    private var trailX = FloatArray(0)
    private var trailY = FloatArray(0)
    private var trailHead = 0
    private var trailFill = 0
    private val proj = FloatArray(3)

    override fun setup(env: SimEnv) {
        val p = env.params
        dims = p.i("dims", 2).coerceIn(1, Nd.MAX_DIMS)
        walkers = p.i("walkers", 40).coerceIn(1, 4000)
        pos = FloatArray(walkers * dims)
        home = BooleanArray(walkers)
        wandered = BooleanArray(walkers)
        val trail = p.i("trail", 6000).coerceIn(500, 40000)
        trailX = FloatArray(trail)
        trailY = FloatArray(trail)
        trailHead = 0
        trailFill = 0
        steps = 0
        returned = 0
        payoffFrame = false
        paid = false
        elapsed = 0f
        lastReturned = -1
        settledFor = 0f
        viewScale = 1f
        viewer = VIEWER
        carry = 0f
        wandered = BooleanArray(walkers)
        textureClock = 0f
    }

    /** True once the payoff has fired, so it fires once. */
    private var paid = false

    /**
     * The payoff is the verdict, not a quota.
     *
     * A share of walkers home cannot be the gate: Polya's theorem says a walk
     * on a line or a grid comes home with probability one and a walk in three
     * dimensions with probability 0.34 — in infinite time. Measured over a
     * thirty-second run, two and a half per cent of three-dimensional walkers
     * get home and none at all above four, so a 35% gate meant the sim named
     * "Will the walker get home?" could never pay off at the dimension it is
     * named after. What is worth waiting for is the answer settling: the count
     * stops climbing, and that is the result.
     */
    private val SETTLED_SECONDS = 4.5f

    /** Nothing is settled before this much of the run has been seen. */
    private val EARLIEST_PAYOFF = 8f

    /** How far out a walker must get before its return is a return. */
    private val AWAY_STEPS = 5f

    /** Which walkers have been far enough from the origin to count. */
    private var wandered = BooleanArray(0)

    private var textureClock = 0f

    /** Micro-steps between texture notes. */
    private val TEXTURE_EVERY = 90f

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        updateView()
        carry += abs(dt) * env.params.f("rate", 260f)
        var budget = min(carry.toInt(), 4000)
        carry -= budget
        val stepSize = 0.045f
        while (budget > 0) {
            budget--
            steps++
            for (w in 0 until walkers) {
                if (home[w]) continue
                val base = w * dims
                val axis = env.rng.int(0, dims)
                pos[base + axis] += if (env.rng.bool()) stepSize else -stepSize
                val distance = Nd.length(pos, base, dims)
                // A walker has to get properly lost before coming back counts.
                // Every walker starts at the origin, so without this the first
                // wobble back through zero was "the drunk walker got home" —
                // the payoff landed a tenth of a second into a thirty-second run.
                if (!wandered[w]) {
                    if (distance > stepSize * AWAY_STEPS) wandered[w] = true
                    continue
                }
                if (distance < stepSize * 0.75f) {
                    home[w] = true
                    returned++
                    env.emit(EventKind.CROSS, 0.35f + 0.5f * (returned.toFloat() / walkers), 0f, 0.7f)
                }
            }
            // The swarm spreading is itself the sound, so the run is never
            // silent between returns.
            textureClock += 1f
            if (textureClock >= TEXTURE_EVERY) {
                textureClock = 0f
                var spread = 0f
                var sampled = 0
                var k = 0
                while (k < walkers) {
                    spread += Nd.length(pos, k * dims, dims)
                    sampled++
                    k += maxOf(1, walkers / 24)
                }
                if (sampled > 0) {
                    val mean = (spread / sampled / 1.6f).coerceIn(0f, 1f)
                    env.emit(EventKind.STEP, mean, 0f, 0.22f + mean * 0.3f)
                }
            }

            // Record the first walker's path as the visible trail. Stored in
            // world units at the scale of the moment, so the trail keeps its
            // shape as the view pulls back.
            if (walkers > 0) {
                Nd.project(pos, 0, dims, viewer, proj)
                // Stored as projected, unscaled: the view pulls back as the
                // cloud grows, and a trail written at an older scale would fly
                // off the edges as it did.
                trailX[trailHead] = proj[0]
                trailY[trailHead] = proj[1]
                trailHead = (trailHead + 1) % trailX.size
                if (trailFill < trailX.size) trailFill++
            }
        }
        // The verdict: once the count has stopped climbing for a few seconds,
        // the answer is in, and what it is depends entirely on the dimension —
        // all of them on a line, most of them on a grid, hardly any in three.
        // That contrast is the whole sim, and it is what gets the payoff.
        elapsed += abs(dt)
        if (returned != lastReturned) {
            lastReturned = returned
            settledFor = 0f
        } else {
            settledFor += abs(dt)
        }
        if (!paid && elapsed > EARLIEST_PAYOFF && settledFor > SETTLED_SECONDS) {
            paid = true
            payoffFrame = true
            env.emit(EventKind.DONE, 1f, 0f, 1f)
        }
    }

    private var elapsed = 0f
    private var lastReturned = -1
    private var settledFor = 0f

    /**
     * How far from the origin the cloud has spread, so the view can hold it.
     *
     * A walk is unbounded and [Nd.project] divides by a fixed viewer distance,
     * so once a coordinate passed the viewer every point was multiplied by
     * twenty: three quarters of the walkers left the screen in three dimensions
     * and the furthest reached fourteen hundred screen-widths out in six. The
     * cloud is scaled to fit instead, which is also the only way to see what it
     * is doing.
     */
    private var viewScale = 1f
    private var viewer = VIEWER

    /**
     * Pull the camera back as the cloud grows, then scale what comes out to fit.
     *
     * Scaling the world coordinates alone is not enough: the projection divides
     * by (viewer - w), so the moment a walker passed the fixed viewer distance
     * every point was multiplied by twenty and left the screen however the
     * world was scaled. Keeping the viewer back by [Nd.viewerFor] bounds that
     * factor at about two, and then one scale holds the whole cloud in frame.
     */
    private fun updateView() {
        var furthest = 0f
        var w = 0
        val stride = maxOf(1, walkers / 64)
        while (w < walkers) {
            val d = Nd.length(pos, w * dims, dims)
            if (d > furthest) furthest = d
            w += stride
        }
        val radius = furthest.coerceAtLeast(0.2f)
        viewer = maxOf(VIEWER, Nd.viewerFor(radius, dims))
        val target = 0.85f / (radius * PROJECTION_GAIN)
        // Eases towards the target so the view does not jump every frame.
        viewScale += (target.coerceIn(0.01f, 4f) - viewScale) * 0.06f
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        g.reservePoints(trailFill + walkers)
        for (k in 0 until trailFill) {
            val idx = (trailHead - 1 - k + trailX.size * 2) % trailX.size
            val age = k.toFloat() / trailFill
            g.point(
                trailX[idx] * viewScale, trailY[idx] * viewScale,
                0.012f * (1.1f - age),
                Palette.withAlpha(palette.at(0.2f + age * 0.6f), 1f - age * 0.85f)
            )
        }
        for (w in 0 until walkers) {
            Nd.project(pos, w * dims, dims, viewer, proj)
            val c = if (home[w]) Palette.SUCCESS else palette.at(w.toFloat() / max(1, walkers))
            // Big enough to follow on a phone: at a hundredth of the screen
            // they were specks in an empty frame.
            g.point(proj[0] * viewScale, proj[1] * viewScale, WALKER_SIZE, c)
        }
        // Home, at the same scale as everything else.
        g.ring(0f, 0f, (0.035f * viewScale).coerceIn(0.012f, 0.1f), 0.004f, Palette.SUCCESS, alpha = 0.8f)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "dimensions" to dims.toString(),
        "home" to "$returned/$walkers",
        "steps" to steps.toString()
    )

    companion object {
        const val WALKER_SIZE = 0.024f
        /**
         * Far enough back that a projection never divides by nearly zero. The
         * walk is scaled into view separately, so this only sets how strong the
         * perspective is.
         */
        const val VIEWER = 6f

        /** The most the projection can magnify once the viewer keeps ahead. */
        const val PROJECTION_GAIN = 2f

        fun def(): SimDef = SimDef(
            id = "dimensions/random-walk",
            title = "Will the walker get home?",
            shelf = Shelf.DIMENSIONS,
            family = "Physics in N dimensions",
            blurb = "Always on a line or a grid. In three dimensions, maybe never.",
            formats = listOf(TrapFormat.GUESS, TrapFormat.NUMBER_UP, TrapFormat.ESCAPE),
            heroLabel = "Send them out",
            defaultPalette = "Candy",
            typicalSeconds = 30f,
            params = listOf(
                Param.int("dims", "Dimensions", 1, Nd.MAX_DIMS, 2, card = Card.DATA),
                Param.int("walkers", "Walkers", 1, 4000, 40, card = Card.DATA, logScale = true),
                Param.int("trail", "Trail length", 500, 40000, 6000, card = Card.LOOK, logScale = true),
                Param.float("rate", "Steps", 10f, 3000f, 260f, card = Card.MOTION, unit = "/s", logScale = true)
            ),
            create = { RandomWalkSim() }
        )
    }
}

/**
 * Balls bouncing in a box of N dimensions, projected down to the screen.
 *
 * More dimensions means more walls, so the same ball count rattles far more
 * often: the sound alone tells you which dimension you are in.
 */
class NdBounceSim : Sim {

    private var dims = 4
    private var count = 24
    private var pos = FloatArray(0)
    private var vel = FloatArray(0)
    private var tint = FloatArray(0)
    private val proj = FloatArray(3)
    private var hits = 0L
    private var payoffFrame = false
    private var elapsed = 0f
    private var spin = 0f
    // The box's corners and edges, for drawing it as what it is: an N-cube.
    private var boxVerts = FloatArray(0)
    private var boxEdges = IntArray(0)
    private var tmp = FloatArray(0)
    private var viewer = 3f
    private var drawScale = 0.72f

    /**
     * Which of the 2*dims walls each ball has struck, as a bit per wall.
     *
     * It used to be one set of flags for the whole box, and the payoff was
     * "every wall struck by anything" — which with the default two dozen balls
     * is over in 1.2 seconds, a fifth of the way through the setup beat, and
     * barely later in six dimensions than in two. The comment below claimed the
     * moment arrives later the more dimensions there are; with that many balls
     * it did not. One ball having reached all twelve walls does, and it is a
     * fact about the sim's subject rather than about how many balls there are.
     */
    private var visited = IntArray(0)
    private var sx = FloatArray(0)
    private var sy = FloatArray(0)
    private var axesToured = 0
    private var paidOff = false
    /** Bounces per second, smoothed, and what the build settled at. */
    private var rateNow = 0f
    private var buildRate = 0f
    private var buildSamples = 0

    override fun setup(env: SimEnv) {
        val p = env.params
        dims = p.i("dims", 4).coerceIn(2, Nd.MAX_DIMS)
        count = p.i("balls", 24).coerceIn(1, 600)
        visited = IntArray(count)
        axesToured = 0
        paidOff = false
        rateNow = 0f
        buildRate = 0f
        buildSamples = 0
        pos = FloatArray(count * dims)
        vel = FloatArray(count * dims)
        tint = FloatArray(count)
        val speed = p.f("speed", 0.7f)
        for (i in 0 until count) {
            val base = i * dims
            for (d in 0 until dims) {
                pos[base + d] = env.rng.range(-0.7f, 0.7f)
                vel[base + d] = env.rng.range(-speed, speed)
            }
            tint[i] = env.rng.nextFloat()
        }
        hits = 0
        payoffFrame = false
        elapsed = 0f
        spin = 0f
        boxVerts = Nd.hypercubeVertices(dims).also { for (k in it.indices) it[k] *= 2f * WALL }
        boxEdges = Nd.hypercubeEdges(dims)
        tmp = FloatArray(dims)
        viewer = Nd.viewerFor(WALL, dims)
        // Scaled once, so the box at its widest over a whole turn of the view
        // fills the frame: found by turning it, not guessed.
        var extent = 0.1f
        for (step in 0 until 48) {
            val a = step * 2f * PI.toFloat() / 48
            for (v in 0 until (1 shl dims)) {
                viewOf(boxVerts, v * dims, a)
                extent = max(extent, max(abs(proj[0]), abs(proj[1])))
            }
        }
        drawScale = BOX_SCREEN / extent
    }

    /**
     * Where a point is seen from: the view turns through the plane of the
     * first axis and the last, and tilts a little through the second and
     * third so the depth reads. Only the view turns; the box and the balls in
     * it don't. Turning the positions themselves, as this used to, carried
     * balls straight through walls their velocities knew nothing about.
     */
    private fun viewOf(src: FloatArray, offset: Int, angle: Float) {
        for (d in 0 until dims) tmp[d] = src[offset + d]
        if (dims >= 3) {
            Nd.rotatePlane(tmp, 1, dims, 0, dims - 1, angle)
            Nd.rotatePlane(tmp, 1, dims, 1, 2, TILT)
        }
        Nd.project(tmp, 0, dims, viewer, proj)
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        val laws = env.laws
        val e = if (laws.sticky) 0f else laws.bounce
        val wall = WALL
        var hitsThisStep = 0
        for (i in 0 until count) {
            val base = i * dims
            // Gravity acts along the second axis, the one that reads as "down".
            if (dims > 1) vel[base + 1] -= laws.gravityDirY * laws.gravity * 0.02f * dt
            for (d in 0 until dims) {
                pos[base + d] += vel[base + d] * dt
                if (pos[base + d] > wall) {
                    pos[base + d] = wall; vel[base + d] = -vel[base + d] * e; hitsThisStep++
                    visited[i] = visited[i] or (1 shl d)
                } else if (pos[base + d] < -wall) {
                    pos[base + d] = -wall; vel[base + d] = -vel[base + d] * e; hitsThisStep++
                    visited[i] = visited[i] or (1 shl d)
                }
            }
        }
        if (hitsThisStep > 0) {
            hits += hitsThisStep
            env.emit(
                EventKind.HIT,
                (hitsThisStep.toFloat() / max(1, count)).coerceIn(0f, 1f),
                0f,
                min(1f, 0.3f + hitsThisStep * 0.04f)
            )
        }
        spin += env.params.f("spin", 0.4f) * dt

        // One ball having struck every wall: in two dimensions that is four
        // walls, in six it is twelve, so the moment arrives later the more
        // dimensions there are — which is the point of the sim. A stopwatch at
        // sixteen seconds said nothing about whether anything had happened.
        // The payoff is the rhythm changing decisively from the one the build
        // established — thinning out as the bounces damp away, or thickening if
        // the laws are returning more energy than they take.
        //
        // Two earlier conditions were measured and thrown away. "Every wall
        // struck at least once by anything" was over in 1.2 seconds with the
        // default two dozen balls, a twentieth of the way into a run the design
        // doc puts the payoff at 70% of — and barely later in six dimensions
        // than in two, despite the comment that used to sit here claiming that
        // was the point. "The fullest beat so far" sounds right for a rhythm
        // and cannot work in a damped box: the default bounce returns 88% of
        // the energy, so the busiest beat is always an early one and a late
        // step has nothing left to beat it with. Of forty-seven runs that fell
        // through to the backstop, forty-two were still bouncing happily.
        //
        // A decisive change in the rate is the event this sim actually has, it
        // arrives on its own schedule, and it holds in both directions.
        val dtAbs = abs(dt)
        if (dtAbs > 1e-6f) {
            val instant = hitsThisStep / dtAbs
            // About a third of a second of smoothing: enough that one busy step
            // is not an event, little enough that the thinning is not hidden.
            rateNow += (instant - rateNow) * min(1f, dtAbs * 3f)
        }
        if (!paidOff) {
            when {
                // The first second is the drop into the box, not the rhythm.
                elapsed < SETTLE_SECONDS -> Unit
                elapsed < BUILD_SECONDS -> {
                    buildRate += rateNow
                    buildSamples++
                }
                else -> {
                    val established = if (buildSamples > 0) buildRate / buildSamples else 0f
                    val thinned = established > 0.5f && rateNow < established * THINNED
                    val thickened = established > 0.5f && rateNow > established * THICKENED
                    if (thinned || thickened || elapsed > BUILD_SECONDS + BACKSTOP_SECONDS) {
                        paidOff = true
                        payoffFrame = true
                        env.emit(EventKind.DONE, 0.8f, 0f, 1f)
                    }
                }
            }
            val full = (1 shl dims) - 1
            var toured = 0
            for (i in 0 until count) if (visited[i] == full) toured++
            axesToured = toured
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        // The box: all 2·dims walls of it, as an N-cube seen in perspective.
        val nv = 1 shl dims
        if (sx.size != nv) { sx = FloatArray(nv); sy = FloatArray(nv) }
        for (v in 0 until nv) {
            viewOf(boxVerts, v * dims, spin)
            sx[v] = proj[0] * drawScale; sy[v] = proj[1] * drawScale
        }
        var e = 0
        while (e < boxEdges.size) {
            val a = boxEdges[e]; val b = boxEdges[e + 1]
            g.line(sx[a], sy[a], sx[b], sy[b], 0.003f, Palette.TEXT_SECONDARY, 0.3f)
            e += 2
        }
        for (i in 0 until count) {
            viewOf(pos, i * dims, spin)
            val d = proj[2]
            val c = palette.at(tint[i])
            g.glow(proj[0] * drawScale, proj[1] * drawScale, 0.06f * d, c, 0.35f)
            g.disc(proj[0] * drawScale, proj[1] * drawScale, 0.022f * d, c)
        }
    }

    /**
     * "Shake the box": a kick, in every dimension, to the balls already in it.
     * The button used to restart the run — new balls, the label promising one
     * thing and the button doing another. A box whose balls have settled under
     * gravity is the one that most wants shaking.
     */
    override fun hero(env: SimEnv) {
        val kick = env.params.f("speed", 0.7f) * SHAKE
        for (i in 0 until count * dims) vel[i] += env.rng.range(-kick, kick)
        env.emit(EventKind.HIT, 0.8f, 0f, 0.9f)
    }

    /** Drawn at a fixed size; on a phone held upright the view pulls back to fit it. */
    override fun frameHalfWidth(env: SimEnv): Float = 0.74f

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "dimensions" to dims.toString(),
        "all axes" to "$axesToured/$count balls",
        "bounces a second" to String.format(java.util.Locale.US, "%.0f", rateNow),
        "bounces" to hits.toString()
    )

    companion object {
        /** A shake's kick, as a share of the speed slider. */
        private const val SHAKE = 1.6f
        private const val WALL = 0.85f
        private const val TILT = 0.35f
        /** How far out the box reaches on screen, at its widest. */
        private const val BOX_SCREEN = 0.68f

        /** The drop into the box, before there is a rhythm to measure. */
        private const val SETTLE_SECONDS = 1.5f
        /** Long enough for the rhythm to establish what normal is. */
        private const val BUILD_SECONDS = 14f
        /** A box that never changes its mind still owes a payoff. */
        private const val BACKSTOP_SECONDS = 9f
        /** Thinned to this share of the established rate. */
        private const val THINNED = 0.45f
        /** Or thickened to this much of it, for a box that gains energy. */
        private const val THICKENED = 1.8f

        fun def(): SimDef = SimDef(
            id = "dimensions/nd-bounce",
            title = "Bouncing in N dimensions",
            shelf = Shelf.DIMENSIONS,
            family = "Physics in N dimensions",
            blurb = "More dimensions, more walls, a busier rhythm.",
            formats = listOf(TrapFormat.SONG, TrapFormat.ESCAPE),
            lawKnobs = setOf(LawKnob.GRAVITY, LawKnob.GRAVITY_DIRECTION, LawKnob.BOUNCE, LawKnob.CONTACT),
            heroLabel = "Shake the box",
            heroRestarts = false,
            defaultPalette = "Ember",
            typicalSeconds = 26f,
            params = listOf(
                Param.int("dims", "Dimensions", 2, Nd.MAX_DIMS, 4, card = Card.DATA),
                Param.int("balls", "Balls", 1, 600, 24, card = Card.DATA, logScale = true),
                Param.float("speed", "Speed", 0.1f, 2.5f, 0.7f, card = Card.MOTION),
                Param.float("spin", "View spin", 0f, 2f, 0.4f, card = Card.MOTION)
            ),
            macros = listOf(
                Macro("chaos", "Chaos", Card.MOTION, mapOf("speed" to 0.2f..2.5f), 0.3f)
            ),
            create = { NdBounceSim() }
        )
    }
}

/**
 * The unit ball against its dimension, measured four ways, every number
 * exact:
 * - its volume, π^(n/2)/Γ(n/2 + 1), which climbs to a peak at five and then
 *   falls away to almost nothing;
 * - its surface, n times that, which peaks later, at seven;
 * - the share of the cube round it that it fills, V(n)/2ⁿ: all of it on a
 *   line, a little over half in three dimensions, and under one percent by
 *   nine — the cube is nearly all corners;
 * - the share of it within a tenth of the skin, 1 − 0.9ⁿ: past half by seven
 *   dimensions, so a high-dimensional orange is nearly all peel.
 */
class BallVolumeSim : Sim {

    private var upTo = 20
    private var shown = 0f
    private var payoffFrame = false
    private var peakAnnounced = false
    private var measure = 0
    private var milestone = 5
    private var top = 1.0

    override fun setup(env: SimEnv) {
        upTo = env.params.i("upto", 20).coerceIn(4, 40)
        measure = env.params.choiceIndex("measure").coerceIn(0, MEASURES.size - 1)
        shown = 0f
        payoffFrame = false
        peakAnnounced = false
        top = (1..upTo).maxOf { value(it) }
        milestone = when (measure) {
            // The peaks, found, not assumed.
            0, 1 -> (1..upTo).maxByOrNull { value(it) } ?: 5
            // The first dimension under one percent of the cube.
            2 -> (1..upTo).firstOrNull { value(it) < 0.01 } ?: upTo
            // The first with over half of it in the peel.
            else -> (1..upTo).firstOrNull { value(it) > 0.5 } ?: upTo
        }
    }

    private fun value(n: Int): Double = when (measure) {
        1 -> n * Nd.unitBallVolume(n)
        2 -> Nd.unitBallVolume(n) / Math.pow(2.0, n.toDouble())
        3 -> 1.0 - Math.pow(0.9, n.toDouble())
        else -> Nd.unitBallVolume(n)
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val before = shown.toInt()
        shown = min(upTo.toFloat(), shown + env.params.f("rate", 0.7f) * abs(dt))
        val after = shown.toInt()
        if (after > before) {
            val t = (value(after) / top).toFloat().coerceIn(0f, 1f)
            env.emit(EventKind.WRITE, t, 0f, 0.6f)
            if (!peakAnnounced && after >= milestone) {
                peakAnnounced = true
                payoffFrame = true
                env.emit(EventKind.DONE, 1f, 0f, 1f)
            }
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val halfW = env.halfWidth * 0.92f
        val barW = 2f * halfW / upTo
        // Each bar rises while its dimension is being counted, rather than
        // appearing whole once it has been: the first bar used to arrive a
        // second and a half in, over an empty screen.
        for (d in 1..min(upTo, kotlin.math.ceil(shown).toInt())) {
            val v = (value(d) / top).toFloat()
            val rise = (shown - (d - 1)).coerceIn(0f, 1f)
            val eased = 1f - (1f - rise) * (1f - rise)
            val h = v * 1.5f * eased
            val x = -halfW + (d - 0.5f) * barW
            val isPeak = d == milestone
            val c = if (isPeak) Palette.SUCCESS else palette.at(d.toFloat() / upTo)
            g.bar(x, -0.85f, barW * 0.4f, h, c)
            if (isPeak) g.glow(x, -0.85f + h, barW * 2.4f, Palette.SUCCESS, 0.5f)
        }
        // For the shares, the line where the story turns: one percent, or half.
        if (measure == 2 || measure == 3) {
            val y = -0.85f + 1.5f * ((if (measure == 2) 0.01 else 0.5) / top).toFloat()
            g.line(-halfW, y, halfW, y, 0.003f, Palette.WARNING, 0.5f)
        }
        g.line(-halfW, -0.85f, halfW, -0.85f, 0.004f, Palette.TEXT_SECONDARY, 0.5f)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = shown >= upTo

    override fun counters(): List<Pair<String, String>> {
        val d = max(1, kotlin.math.ceil(shown).toInt())
        val v = value(d)
        return listOf(
            // The dimension whose bar is rising, so it never reads zero.
            "dimension" to d.toString(),
            NAMES[measure] to if (measure >= 2) String.format(java.util.Locale.US, "%.2f%%", v * 100) else String.format(java.util.Locale.US, "%.3f", v)
        )
    }

    companion object {
        val MEASURES = listOf("Volume", "Surface", "Share of the cube", "Share in the peel")
        val NAMES = listOf("volume", "surface", "of the cube", "in the outer tenth")

        fun def(): SimDef = SimDef(
            id = "dimensions/ball-volume",
            title = "The ball that shrinks",
            shelf = Shelf.DIMENSIONS,
            family = "Dimension-scaling math",
            blurb = "Unit ball volume peaks at five dimensions, then falls to nothing. The surface peaks at seven; the cube is all corners; the orange is all peel.",
            formats = listOf(TrapFormat.NUMBER_UP, TrapFormat.GROW),
            heroLabel = "Count up",
            defaultPalette = "Colour-safe",
            typicalSeconds = 14f,
            params = listOf(
                Param.choice("measure", "Measure", MEASURES, 0, card = Card.DATA, input = ParamInput.CHIPS),
                Param.int("upto", "Up to dimension", 4, 40, 20, card = Card.DATA),
                // The peak at five dimensions is the moment this sim exists
                // for, and at 2.2 dimensions a second it arrived 2.3 seconds
                // in — a sixth of the way into the run, with the blurb's
                // "then falls to nothing" over before anyone had read the
                // first number. A bar and a bit a second is a pace you can
                // follow, and the slider still goes to twelve.
                Param.float("rate", "Speed", 0.4f, 12f, 0.7f, card = Card.MOTION, unit = "/s")
            ),
            create = { BallVolumeSim() }
        )
    }
}

/**
 * The curse of dimensionality, measured: in many dimensions every point is
 * about as far from every other.
 *
 * Thousands of pairs of random points in a unit cube, and the distance
 * between each pair. Each new dimension gives every point one more random
 * coordinate, so it is the same pairs all along, in more and more room. The
 * histogram is of distance over the average distance: on a line it is spread
 * from nothing to double; by a hundred dimensions it is a spike at one, the
 * nearest and the farthest pair nearly the same. For many dimensions its
 * width falls as 0.59/√d — 3·√(7/180) — and the counters show it doing so.
 */
class ConcentrationSim : Sim {

    private var pairs = 4000
    private var sumSq = DoubleArray(0)
    private var dims = 0
    private var maxDims = 1000
    private var clock = 0f
    private var elapsed = 0f
    private var payoffFrame = false
    private var paid = false
    private var doneAt = -1f
    private val bins = IntArray(BINS)
    private var shownBins = FloatArray(BINS)
    private var spread = 1f
    private var ratio = 1f

    override fun setup(env: SimEnv) {
        pairs = env.params.i("pairs", 4000).coerceIn(200, 20000)
        maxDims = env.params.i("upto", 1000).coerceIn(10, 5000)
        sumSq = DoubleArray(pairs)
        dims = 0
        clock = 0f
        elapsed = 0f
        payoffFrame = false
        paid = false
        doneAt = -1f
        shownBins = FloatArray(BINS)
        addDimensions(env, 1)
    }

    /** One more coordinate for every point: each pair's squared distance gains (u − v)². */
    private fun addDimensions(env: SimEnv, k: Int) {
        repeat(k) {
            for (i in 0 until pairs) {
                val t = env.rng.nextFloat() - env.rng.nextFloat()
                sumSq[i] += (t * t).toDouble()
            }
        }
        dims += k
        // Distances over their mean, binned; and the spread and range of them.
        var mean = 0.0
        var lo = Double.MAX_VALUE
        var hi = 0.0
        for (i in 0 until pairs) {
            val d = Math.sqrt(sumSq[i])
            mean += d
            if (d < lo) lo = d
            if (d > hi) hi = d
        }
        mean /= pairs
        var v = 0.0
        java.util.Arrays.fill(bins, 0)
        for (i in 0 until pairs) {
            val d = Math.sqrt(sumSq[i])
            v += (d - mean) * (d - mean)
            val b = ((d / mean) / RANGE * BINS).toInt()
            if (b in 0 until BINS) bins[b]++
        }
        spread = (Math.sqrt(v / pairs) / mean).toFloat()
        ratio = if (lo > 1e-9) (hi / lo).toFloat() else Float.POSITIVE_INFINITY
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        if (dims < maxDims) {
            clock += h * env.params.f("pace", 1f)
            if (clock >= TICK) {
                clock = 0f
                // One at a time at first, where each one changes the picture;
                // then by a growing step, up into the hundreds.
                val k = min(maxDims - dims, max(1, (dims * GROWTH).toInt()))
                addDimensions(env, k)
                env.emit(EventKind.STEP, (1f - spread / 0.6f).coerceIn(0f, 1f), 0f, 0.45f)
                if (!paid && spread < PAYOFF_SPREAD) {
                    paid = true
                    paidAt = elapsed
                    payoffFrame = true
                    env.emit(EventKind.DONE, 1f, 0f, 1f)
                }
                if (dims >= maxDims) doneAt = elapsed
            }
        }
        // The bars ease to the new counts.
        val most = max(1, bins.max())
        for (b in 0 until BINS) shownBins[b] += (bins[b].toFloat() / most - shownBins[b]) * min(1f, h * 8f)
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val halfW = env.halfWidth * 0.92f
        val base = -0.6f
        val barW = 2f * halfW / BINS
        for (b in 0 until BINS) {
            val x = -halfW + (b + 0.5f) * barW
            val c = palette.at(0.15f + 0.8f * b / BINS)
            g.bar(x, base, barW * 0.42f, shownBins[b] * 1.3f, c)
        }
        g.line(-halfW, base, halfW, base, 0.004f, Palette.TEXT_SECONDARY, 0.5f)
        // The average distance, where everything ends up.
        val mx = -halfW + (1f / RANGE) * 2f * halfW
        g.line(mx, base - 0.03f, mx, base + 1.35f, 0.003f, Palette.WARNING, 0.6f)
        // Nearest and farthest pair, as lengths: they close on each other.
        val y = base - 0.15f
        val far = halfW * 1.8f
        val near = if (ratio.isFinite()) far / ratio else 0f
        g.rect(-halfW + far * 0.5f, y, far * 0.5f, 0.012f, palette.at(0.9f), 0f, 0.8f)
        g.rect(-halfW + near * 0.5f, y - 0.05f, max(0.002f, near * 0.5f), 0.012f, palette.at(0.2f), 0f, 0.9f)
        if (paid) {
            val k = (1f - (elapsed - paidAt) * 0.8f).coerceIn(0f, 1f)
            if (k > 0f) g.glow(mx, base + 0.6f, 0.4f, Palette.SUCCESS, 0.4f * k)
        }
    }

    private var paidAt = -1f

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && elapsed - doneAt > 3f

    override fun counters(): List<Pair<String, String>> = listOf(
        "dimensions" to dims.toString(),
        "spread" to String.format(java.util.Locale.US, "±%.1f%%", spread * 100f),
        "farthest ÷ nearest" to if (ratio.isFinite() && ratio < 1e5f) String.format(java.util.Locale.US, "%.2f", ratio) else "∞"
    )

    companion object {
        const val BINS = 48
        /** The histogram covers distance from zero to this many times the average. */
        const val RANGE = 2f
        const val TICK = 0.45f
        const val GROWTH = 0.12f
        /** Spread under ten percent: every pair within a tenth of the average. */
        const val PAYOFF_SPREAD = 0.1f

        fun def(): SimDef = SimDef(
            id = "dimensions/concentration",
            title = "Everything is equally far",
            shelf = Shelf.DIMENSIONS,
            family = "Dimension-scaling math",
            blurb = "Random points in more and more dimensions: the nearest and the farthest end up the same distance apart.",
            formats = listOf(TrapFormat.NUMBER_UP, TrapFormat.GROW),
            heroLabel = "Again",
            defaultPalette = "Spectrum",
            typicalSeconds = 16f,
            params = listOf(
                Param.int("upto", "Up to dimension", 10, 5000, 1000, card = Card.DATA, logScale = true),
                Param.int("pairs", "Pairs of points", 200, 20000, 4000, card = Card.DATA, logScale = true),
                Param.float("pace", "Pace", 0.3f, 4f, 1f, card = Card.MOTION)
            ),
            create = { ConcentrationSim() }
        )
    }
}

object DimensionSims {
    fun defs(): List<SimDef> = listOf(
        HypercubeSim.def(),
        SlicingSim.def(),
        RandomWalkSim.def(),
        NdBounceSim.def(),
        BallVolumeSim.def(),
        ConcentrationSim.def()
    )
}
