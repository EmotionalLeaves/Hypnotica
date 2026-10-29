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
        // The slider is how far back the viewer sits, in units of the
        // distance that keeps this many nested perspectives tame: a raw
        // distance of 3 was fine for a tesseract and threw a 6-cube's
        // corners off the screen, and to infinity at the slider's end.
        val radius = 0.5f * sqrt(dims.toFloat())
        val viewer = Nd.viewerFor(radius, dims) * env.params.f("viewer", 3f) / 3f
        val scale = SCREEN_REACH / radius
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

        // Far edges first, so a near edge always crosses over a far one
        // rather than whichever the edge list happened to name last.
        val ne = edges.size / 2
        if (order.size != ne) { order = IntArray(ne) { it }; depthKey = FloatArray(ne) }
        for (k in 0 until ne) depthKey[k] = screenD[edges[2 * k]] + screenD[edges[2 * k + 1]]
        sortByKey(order, depthKey)
        // A six-cube has 64 corners: at a tesseract's glow each they ran
        // together into one haze, so the glow thins as the corners multiply.
        val glowShare = sqrt(16f / max(16, n).toFloat())
        for (k in 0 until ne) {
            val e = order[k] * 2
            val a = edges[e]
            val b = edges[e + 1]
            val near = (nearness(screenD[a]) + nearness(screenD[b])) * 0.5f
            val c = palette.at(0.15f + near * 0.75f)
            g.line(
                screenX[a], screenY[a], screenX[b], screenY[b],
                0.0045f + near * 0.0065f, c, 0.4f + near * 0.6f
            )
        }
        for (i in 0 until n) {
            val near = nearness(screenD[i])
            val c = palette.at(0.15f + near * 0.75f)
            g.glow(screenX[i], screenY[i], 0.035f + 0.04f * near, c, (0.2f + 0.3f * near) * glowShare)
            g.disc(screenX[i], screenY[i], 0.009f + 0.012f * near, Palette.brighten(c, 0.2f))
        }
    }

    override fun onInput(env: SimEnv, e: TouchEvent) {
        if (e.kind == TouchEvent.Kind.DRAG) manualSpin = (manualSpin + e.x * 0.6f).coerceIn(-3f, 3f)
    }

    private var order = IntArray(0)
    private var depthKey = FloatArray(0)

    /**
     * Drawn at a fixed size; on a phone held upright the view pulls back to
     * fit it. Just the corners' reach and a corner's own radius: 0.65 left a
     * fifth of the width empty and the shape small in an upright phone.
     */
    override fun frameHalfWidth(env: SimEnv): Float = SCREEN_REACH + 0.04f

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
        /** The shape's corners reach about this far on screen, at their widest. */
        const val SCREEN_REACH = 0.52f

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
                Param.float("viewer", "Viewer distance", 2.4f, 8f, 3f, card = Card.LOOK)
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

    /** There and back: four sweeps of the reach, at the speed set. */
    override fun loopSeconds(env: SimEnv): Float =
        4f * SWEEP / env.params.f("speed", 0.42f).coerceAtLeast(0.01f)

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
    // For each slice corner, the edge it lies on and how far along (a vertex
    // on the plane is its own edge, a to a): so the side view can put it on
    // the edge as drawn.
    private var sliceA = IntArray(0)
    private var sliceB = IntArray(0)
    private var sliceT = FloatArray(0)
    private var vertSlice = IntArray(0)
    private val seen = HashSet<Long>()
    /** The sweep's ends and middle, along w. */
    private var lo = -1f
    private var hi = 1f
    private var mid = 0f
    /** Where the cut is drawn this frame: the plane's height, or exactly the middle on the frame nearest it. */
    private var cutPos = 0f
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
        val nv = if (dims > 0) verts.size / dims else 0
        crossAt = IntArray(ne)
        val most = max(1, ne + nv)
        slice = FloatArray(most * dims)
        sideX = FloatArray(most)
        sliceA = IntArray(most); sliceB = IntArray(most); sliceT = FloatArray(most)
        vertSlice = IntArray(max(1, nv))
        sliceEdges = IntArray(max(2, faces.size / faceSize * 2 + ne * 2))
        tmp = FloatArray(dims)
        sx = FloatArray(max(1, nv)); sy = FloatArray(max(1, nv)); sd = FloatArray(max(1, nv))
        // From the object's lowest point to its highest along w — not
        // symmetric for a simplex standing on its point.
        if (shape == 0 || shape == 4) { lo = -radius; hi = radius } else {
            lo = Float.MAX_VALUE; hi = -Float.MAX_VALUE
            for (i in 0 until nv) { val w = verts[i * dims + dims - 1]; lo = min(lo, w); hi = max(hi, w) }
        }
        mid = (lo + hi) * 0.5f
        extent = (hi - lo) * 0.5f
        position = mid - extent * SWEEP
        cutPos = position
        direction = 1f
        payoffFrame = false
        passes = 0
        peaked = false
        elapsed = 0f
        viewAngle = 0f
        lastCorners = -1
        cut(cutPos)
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
        if (position > mid + reach) { position = mid + reach; direction = -1f; passes++ }
        if (position < mid - reach) { position = mid - reach; direction = 1f; passes++ }
        // On the one frame nearest the middle, the cut is drawn exactly
        // there, so the slice it promises — the octahedron in a 4-cube
        // going corner first — is the one seen, not one a hair off it.
        val half = speed * extent * h * 0.5f
        cutPos = if (abs(position - mid) <= half) mid else position
        // Tumbling: the object turns through planes that take in w, so the
        // slice changes shape as well as size.
        if (shape == 4) {
            val nv = verts.size / dims
            Nd.rotatePlane(verts, nv, dims, 0, dims - 1, h * 0.23f)
            if (dims >= 3) Nd.rotatePlane(verts, nv, dims, 1, dims - 1, h * 0.17f)
        }
        cut(cutPos)
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
        if (!peaked && cutPos == mid) {
            peaked = true
            payoffFrame = true
            env.emit(EventKind.DONE, 0.9f, 0f, 1f)
        }
        if (abs(position - mid) > extent * 0.6f) peaked = false
    }

    /**
     * The slice at height [at], corners and edges, worked out exactly. A
     * corner of the object lying on the plane is one corner of the slice,
     * however many edges meet there; it used to count once per edge, and the
     * octahedron came out as twelve corners with six edges of no length.
     */
    private fun cut(at: Float) {
        sliceN = 0
        sliceEdgeN = 0
        if (shape == 0) return
        val d = dims
        val w = d - 1
        val ne = edges.size / 2
        val nv = verts.size / d
        val eps = 1e-5f * radius
        for (v in 0 until nv) {
            vertSlice[v] = -1
            if (abs(verts[v * d + w] - at) < eps) {
                for (k in 0 until d) slice[sliceN * d + k] = verts[v * d + k]
                sliceA[sliceN] = v; sliceB[sliceN] = v; sliceT[sliceN] = 0f
                vertSlice[v] = sliceN++
            }
        }
        for (e in 0 until ne) {
            crossAt[e] = -1
            val a = edges[2 * e]; val b = edges[2 * e + 1]
            val onA = vertSlice[a]; val onB = vertSlice[b]
            if (onA >= 0 && onB >= 0) { crossAt[e] = IN_PLANE; continue }
            if (onA >= 0) { crossAt[e] = onA; continue }
            if (onB >= 0) { crossAt[e] = onB; continue }
            val wa = verts[a * d + w] - at; val wb = verts[b * d + w] - at
            if ((wa < 0f) == (wb < 0f)) continue
            val t = wa / (wa - wb)
            for (k in 0 until d) slice[sliceN * d + k] = verts[a * d + k] + (verts[b * d + k] - verts[a * d + k]) * t
            sliceA[sliceN] = a; sliceB[sliceN] = b; sliceT[sliceN] = t
            crossAt[e] = sliceN
            sliceN++
        }
        // A face the plane cuts across meets it in one segment, between its
        // two distinct crossing corners; a face's edge lying in the plane is
        // an edge of the slice outright.
        seen.clear()
        var f = 0
        while (f < faces.size) {
            var p = -1; var q = -1
            for (k in 0 until faceSize) {
                val e = faces[f + k]
                val c = crossAt[e]
                if (c == IN_PLANE) {
                    addEdge(vertSlice[edges[2 * e]], vertSlice[edges[2 * e + 1]])
                    continue
                }
                if (c < 0) continue
                if (p < 0) p = c else if (c != p && q < 0) q = c
            }
            if (p >= 0 && q >= 0) addEdge(p, q)
            f += faceSize
        }
    }

    private fun addEdge(p: Int, q: Int) {
        if (p == q || p < 0 || q < 0) return
        if (!seen.add(min(p, q).toLong() * 100000L + max(p, q))) return
        sliceEdges[2 * sliceEdgeN] = p; sliceEdges[2 * sliceEdgeN + 1] = q
        sliceEdgeN++
    }

    private fun sliceRadius(): Float = Nd.sliceRadius(radius, cutPos)

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
        // A ball of three or more dimensions is drawn as a lit solid seen a
        // little from above (orthographic, elevation VIEW_ELEV), so heights
        // along w are foreshortened by its cosine and the cut through it is
        // an ellipse. Seen dead level it was a flat disc with a line across:
        // two circles, no depth.
        val solid = shape == 0 && dims >= 3
        val elevCos = if (solid) kotlin.math.cos(VIEW_ELEV) else 1f
        val elevSin = if (solid) kotlin.math.sin(VIEW_ELEV) else 0f
        val worldY = SIDE_Y + cutPos * sideScale * elevCos
        val reachX = env.halfWidth * 0.95f
        // ---- Outside: the object with its true height, and the flat world.
        // The object from outside was a hairline at half strength, and with
        // the slice a line across it the frame read as two thin circles in
        // the dark: drawn now as a solid thing, the flat world as a lit sheet.
        val body = palette.at(0.3f)
        val ghost = Palette.withAlpha(body, 0.8f)
        if (solid) {
            shadedBall(g, 0f, SIDE_Y, radius * sideScale, body)
            // The cross-section itself, inside the ball: what the flat world
            // will see, drawn where it is.
            val r = sliceRadius()
            if (r > 0f) {
                val a = r * sideScale
                val b = a * elevSin
                val strips = 20
                for (k in 0 until strips) {
                    val v0 = -1f + 2f * k / strips
                    val v1 = v0 + 2f / strips
                    val vm = (v0 + v1) * 0.5f
                    val half = a * sqrt(max(0f, 1f - vm * vm))
                    g.rect(0f, worldY + vm * b, half, b / strips, Palette.withAlpha(lineColour, 0.32f))
                }
                // Its far half, behind the front of the ball, faint.
                ellipseArc(g, 0f, worldY, a, b, 0f, PI.toFloat(), 0.004f, Palette.withAlpha(lineColour, 0.45f))
            }
        } else if (shape == 0) {
            g.disc(0f, SIDE_Y, radius * sideScale, Palette.withAlpha(body, 0.1f))
            g.ring(0f, SIDE_Y, radius * sideScale, 0.006f, ghost)
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
                g.line(sx[a], sy[a], sx[b], sy[b], 0.004f, ghost)
                e += 2
            }
            for (i in 0 until nv) g.disc(sx[i], sy[i], 0.006f, ghost)
        }
        // The flat world: a soft band with the plane through it.
        g.rect(0f, worldY, reachX, 0.022f, Palette.withAlpha(lineColour, 0.07f))
        g.line(-reachX, worldY, reachX, worldY, 0.004f, Palette.TEXT_SECONDARY, 0.7f)
        // Where it cuts, on the line.
        if (shape == 0) {
            val r = sliceRadius()
            if (r > 0f) {
                val half = r * sideScale
                if (solid) ellipseArc(g, 0f, worldY, half, half * elevSin, PI.toFloat(), 2f * PI.toFloat(), 0.009f, lineColour)
                else g.line(-half, worldY, half, worldY, 0.012f, lineColour)
                g.disc(-half, worldY, 0.012f, Palette.brighten(lineColour, 0.25f))
                g.disc(half, worldY, 0.012f, Palette.brighten(lineColour, 0.25f))
                // The cut and what the flat world sees of it are drawn to one
                // scale, so the chord's ends rise straight to the slice's
                // edge: the measurement carried up, not just asserted.
                g.line(-half, worldY + 0.02f, -half, TOP_Y, 0.003f, lineColour, 0.4f)
                g.line(half, worldY + 0.02f, half, TOP_Y, 0.003f, lineColour, 0.4f)
            }
        } else {
            // On its edge as drawn: the side view's mapping doesn't keep
            // lines straight, so the corner goes where the drawn edge
            // meets the line, the same share of the way along.
            for (i in 0 until sliceN) {
                val a = sliceA[i]; val b = sliceB[i]
                val x = sx[a] + (sx[b] - sx[a]) * sliceT[i]
                g.glow(x, worldY, 0.035f, lineColour, 0.4f)
                g.disc(x, worldY, 0.011f, Palette.brighten(lineColour, 0.2f))
            }
        }
        // ---- Inside: what the flat world sees, in its own dimensions.
        val sliceDims = dims - 1
        val topScale = PANEL / radius
        if (shape == 0) {
            // The largest the slice ever gets, faintly, so the panel is never
            // an empty black square while the ball is out of the world and
            // the slice is seen growing into it.
            if (sliceDims >= 2) g.ring(0f, TOP_Y, radius * topScale, 0.004f, Palette.withAlpha(lineColour, 0.3f))
            else g.line(-radius * topScale, TOP_Y, radius * topScale, TOP_Y, 0.004f, Palette.withAlpha(lineColour, 0.3f))
            val r = sliceRadius()
            if (r > 0f) {
                val content = sliceContent()
                if (sliceDims == 1) {
                    g.line(-r * topScale, TOP_Y, r * topScale, TOP_Y, 0.014f, lineColour)
                    g.disc(-r * topScale, TOP_Y, 0.012f, Palette.brighten(lineColour, 0.25f))
                    g.disc(r * topScale, TOP_Y, 0.012f, Palette.brighten(lineColour, 0.25f))
                } else {
                    // A disc in two dimensions; from three up, a ball, which
                    // seen from anywhere has a round outline of its radius.
                    // The glow stays inside the outline's reach: at 1.2 times
                    // it smeared the edge the flat world is measuring.
                    g.glow(0f, TOP_Y, r * topScale * 1.1f, lineColour, 0.12f + 0.2f * content)
                    if (sliceDims >= 3) {
                        // A ball: lit as a solid, like the object below.
                        shadedBall(g, 0f, TOP_Y, r * topScale, lineColour)
                    } else {
                        // A disc: flat, as it is, but filled from a soft
                        // centre outwards so it reads as a surface.
                        val rr = r * topScale
                        for (k in 0 until 14) {
                            val f = 1f - k / 14f
                            g.disc(0f, TOP_Y, rr * f, Palette.withAlpha(lineColour, if (k == 0) 0.2f else 0.04f))
                        }
                    }
                    g.ring(0f, TOP_Y, r * topScale, 0.007f, Palette.brighten(lineColour, 0.1f))
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
            g.glow(0f, TOP_Y, PANEL * 1.1f, lineColour, 0.08f)
            for (k in 0 until sliceEdgeN) {
                val a = sliceEdges[2 * k]; val b = sliceEdges[2 * k + 1]
                val near = ((sd[a] + sd[b]) * 0.5f - 0.6f).coerceIn(0f, 1f)
                g.line(sx[a], sy[a], sx[b], sy[b], 0.006f + 0.004f * near, lineColour, 0.6f + 0.4f * near)
            }
            for (i in 0 until sliceN) g.disc(sx[i], sy[i], 0.011f, Palette.brighten(lineColour, 0.25f))
        }
    }

    /**
     * A ball lit from the upper left: nested discs stepping towards the
     * highlight, dark at the limb, so it reads as a solid. The outline is the
     * ball's exact radius.
     */
    private fun shadedBall(g: G, cx: Float, cy: Float, r: Float, c: Int) {
        val steps = 28
        for (k in 0 until steps) {
            val t = k / (steps - 1f)
            val rk = r * (1f - 0.86f * t)
            val off = r * 0.36f * t
            val shade = Palette.lerp(Palette.lerp(Palette.BG, c, 0.2f), Palette.brighten(c, 0.18f), t * sqrt(t))
            g.disc(cx - off, cy + off, rk, shade)
        }
        g.ring(cx, cy, r, 0.005f, Palette.withAlpha(Palette.brighten(c, 0.2f), 0.85f))
    }

    /** An axis-aligned ellipse's arc from angle [a0] to [a1], as joined strokes. */
    private fun ellipseArc(g: G, cx: Float, cy: Float, a: Float, b: Float, a0: Float, a1: Float, width: Float, c: Int) {
        val n = 32
        var px = cx + a * kotlin.math.cos(a0)
        var py = cy + b * kotlin.math.sin(a0)
        for (k in 1..n) {
            val th = a0 + (a1 - a0) * k / n
            val x = cx + a * kotlin.math.cos(th)
            val y = cy + b * kotlin.math.sin(th)
            g.line(px, py, x, y, width, c)
            px = x; py = y
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
            "cut at w" to String.format(java.util.Locale.US, "%+.2f", cutPos),
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
        /** How far above level the solid ball is seen from, in radians. */
        const val VIEW_ELEV = 0.32f
        private const val IN_PLANE = -2

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
            // There and back at the default speed: 4 × SWEEP / 0.42.
            loopSeconds = 10.6667f,
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
        // Every walker's own recent path, sharing the trail budget. One trail,
        // the first walker's, was the only line on screen, and that walker is
        // as likely as any to get home early and stop: the trail shrank to a
        // knot at the origin and the run was forty loose dots.
        trailLen = (trail / walkers).coerceIn(MIN_TRAIL, MAX_TRAIL)
        trailX = FloatArray(trailLen * walkers)
        trailY = FloatArray(trailLen * walkers)
        trailHead = 0
        trailFill = 0
        nextTrailAt = 0L
        steps = 0
        returned = 0
        payoffFrame = false
        paid = false
        elapsed = 0f
        lastReturned = -1
        settledFor = 0f
        viewScale = 1f
        viewCx = 0f
        viewCy = 0f
        viewRot = 0f
        upright = false
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
        updateView(env.halfWidth)
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

            // Record every walker's path. Stored as projected, unscaled: the
            // view pulls back as the cloud grows, and a trail written at an
            // older scale would fly off the edges as it did.
            if (steps >= nextTrailAt) {
                // Spaced so a trail always spans about the latter half of the
                // walk so far: at a fixed spacing it covered under two seconds,
                // and as the view pulled back to hold the spreading cloud every
                // trail shrank to a blob.
                nextTrailAt = steps + max(2L, steps / (2L * trailLen))
                for (w in 0 until walkers) {
                    Nd.project(pos, w * dims, dims, viewer, proj)
                    val o = w * trailLen + trailHead
                    trailX[o] = proj[0]
                    trailY[o] = proj[1]
                }
                trailHead = (trailHead + 1) % trailLen
                if (trailFill < trailLen) trailFill++
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
    private fun updateView(halfWidth: Float) {
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
        // Fitted to what is actually drawn — the projected walkers, their
        // trails and home — across and up separately, and centred on that box
        // rather than on home. Fitting the furthest walker's radius about the
        // origin into the screen's narrow side (with the projection's
        // worst-case factor of two) left the cloud a small tangle in the middle
        // of a tall screen, most of it unused.
        var x0 = 0f; var x1 = 0f; var y0 = 0f; var y1 = 0f
        w = 0
        while (w < walkers) {
            Nd.project(pos, w * dims, dims, viewer, proj)
            x0 = min(x0, proj[0]); x1 = max(x1, proj[0]); y0 = min(y0, proj[1]); y1 = max(y1, proj[1])
            w += stride
        }
        val total = trailFill * walkers
        val tStride = maxOf(1, total / 6000)
        var k = 0
        while (k < total) {
            val idx = (k / trailFill) * trailLen + k % trailFill
            x0 = min(x0, trailX[idx]); x1 = max(x1, trailX[idx])
            y0 = min(y0, trailY[idx]); y1 = max(y1, trailY[idx])
            k += tStride
        }
        if (dims == 1) { y0 = 0f; y1 = 0f }
        val spanX = max(0.05f, (x1 - x0) * 0.5f)
        val spanY = max(0.05f, (y1 - y0) * 0.5f)
        // A plane walk's cloud is as often wide as tall, and a wide one on an
        // upright phone is held to the narrow side. The walk is the same
        // turned a quarter-turn (the grid's axes map onto each other), so the
        // view turns it upright when that fits it markedly larger, then holds.
        val flat = min(halfWidth * 0.9f / spanX, 0.9f / spanY)
        val turned = min(halfWidth * 0.9f / spanY, 0.9f / spanX)
        if (dims >= 2) {
            if (!upright && turned > flat * 1.3f) upright = true
            else if (upright && flat > turned * 1.3f) upright = false
        }
        val goal = if (upright) 0.5f * PI.toFloat() else 0f
        viewRot += (goal - viewRot) * 0.05f
        val turning = abs(goal - viewRot) > 0.03f
        val target = when {
            dims == 1 -> halfWidth * 0.9f / spanX
            // Mid-turn the cloud is diagonal and reaches further: room for that.
            turning -> min(flat, turned) * 0.72f
            upright -> turned
            else -> flat
        }
        // Pulling back is quicker than closing in, so a walker striding out
        // is caught before it reaches the edge of the now tighter frame.
        val tgt = target.coerceIn(0.01f, 4f)
        val ease = if (tgt < viewScale) 0.25f else 0.06f
        viewScale += (tgt - viewScale) * ease
        viewCx += ((x0 + x1) * 0.5f - viewCx) * ease
        viewCy += ((y0 + y1) * 0.5f - viewCy) * ease
    }

    /** The centre of the drawn cloud, in projected units; home is the origin. */
    private var viewCx = 0f
    private var viewCy = 0f
    /** The view's turn, eased, and whether it is turning or turned upright. */
    private var viewRot = 0f
    private var upright = false

    /** Projected units to screen: centred on the cloud, turned, scaled. */
    private fun screenX(x: Float, y: Float): Float {
        val c = cos(viewRot); val s = sin(viewRot)
        return ((x - viewCx) * c - (y - viewCy) * s) * viewScale
    }
    private fun screenY(x: Float, y: Float): Float {
        val c = cos(viewRot); val s = sin(viewRot)
        return ((x - viewCx) * s + (y - viewCy) * c) * viewScale
    }

    /**
     * On a line every walker would sit on one row, on top of each other: there
     * each has a row of its own, stacked up the screen, and home is the
     * vertical through the middle. The walk itself is untouched.
     */
    private fun rowY(w: Int): Float =
        if (dims == 1 && walkers > 1) (w.toFloat() / (walkers - 1) - 0.5f) * 1.7f / max(viewScale, 1e-3f) else 0f

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val oneD = dims == 1 && walkers > 1
        g.reservePoints(trailFill * walkers + walkers)
        // Trails thin as walkers multiply, so a thousand of them are a cloud
        // of paths rather than a solid blot.
        val dot = TRAIL_DOT * (if (walkers > 200) 0.6f else 1f)
        // Joined up while there are few enough segments to draw as lines;
        // past that, a dot per point, which a thousand walkers' trails fill in.
        val asLines = trailFill * walkers <= LINE_BUDGET
        if (asLines) g.reserveInstances(trailFill * walkers)
        for (w in 0 until walkers) {
            val c = if (home[w]) Palette.SUCCESS else palette.at(w.toFloat() / max(1, walkers))
            val rowFix = if (oneD) rowY(w) else 0f
            var lx = 0f; var ly = 0f
            for (k in 0 until trailFill) {
                val idx = w * trailLen + (trailHead - 1 - k + trailLen * 2) % trailLen
                val age = k.toFloat() / trailLen
                // Rows are laid out at the scale of the moment, not the one
                // the point was stored at.
                val x = if (oneD) (trailX[idx] - viewCx) * viewScale else screenX(trailX[idx], trailY[idx])
                val y = if (oneD) rowFix * viewScale else screenY(trailX[idx], trailY[idx])
                val a = (0.8f - 0.72f * age) * (if (home[w]) 0.45f else 1f)
                if (!asLines) g.point(x, y, dot * (1.15f - 0.6f * age), Palette.withAlpha(c, a))
                else if (k > 0) g.line(lx, ly, x, y, dot * (0.6f - 0.3f * age), c, a)
                lx = x; ly = y
            }
        }
        for (w in 0 until walkers) {
            Nd.project(pos, w * dims, dims, viewer, proj)
            val c = if (home[w]) Palette.SUCCESS else palette.at(w.toFloat() / max(1, walkers))
            // Big enough to follow on a phone: at a hundredth of the screen
            // they were specks in an empty frame.
            val sxw = if (oneD) (proj[0] - viewCx) * viewScale else screenX(proj[0], proj[1])
            val syw = if (oneD) rowY(w) * viewScale else screenY(proj[0], proj[1])
            g.point(sxw, syw, if (walkers > 200) WALKER_SIZE * 0.6f else WALKER_SIZE, c)
        }
        // Home, at the same scale as everything else: on a line, the column
        // every row returns to.
        val hx = if (oneD) -viewCx * viewScale else screenX(0f, 0f)
        val hy = if (oneD) 0f else screenY(0f, 0f)
        if (oneD) {
            g.line(hx, -0.9f, hx, 0.9f, 0.004f, Palette.SUCCESS, 0.5f)
        } else {
            val r = (0.035f * viewScale).coerceIn(0.02f, 0.1f)
            g.glow(hx, hy, r * 3f, Palette.SUCCESS, 0.25f + 0.35f * returned / max(1, walkers))
            g.ring(hx, hy, r, 0.005f, Palette.SUCCESS, alpha = 0.9f)
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "dimensions" to dims.toString(),
        "home" to "$returned/$walkers",
        "steps" to steps.toString()
    )

    private var trailLen = 150
    /** The step at which the next trail point is taken. */
    private var nextTrailAt = 0L

    companion object {
        const val WALKER_SIZE = 0.024f
        const val TRAIL_DOT = 0.009f
        /** The most trail segments drawn as lines in a frame. */
        const val LINE_BUDGET = 9000
        const val MIN_TRAIL = 8
        const val MAX_TRAIL = 400
        /**
         * Far enough back that a projection never divides by nearly zero. The
         * walk is scaled into view separately, so this only sets how strong the
         * perspective is.
         */
        const val VIEWER = 6f

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
     * How much taller than wide the box is: its "down" axis is stretched to
     * use the height of the screen. A cube on an upright phone filled the
     * width and left half the height empty, a smallish box in a big frame.
     * Only that one wall pair moves; the walls are still 2·dims flat walls.
     */
    private var tall = 1f

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
        tall = tallFor(env)
        for (i in 0 until count) {
            val base = i * dims
            for (d in 0 until dims) {
                val reach = if (d == 1) 0.7f * tall else 0.7f
                pos[base + d] = env.rng.range(-reach, reach)
                vel[base + d] = env.rng.range(-speed, speed)
            }
            tint[i] = env.rng.nextFloat()
        }
        hits = 0
        payoffFrame = false
        elapsed = 0f
        spin = 0f
        boxVerts = Nd.hypercubeVertices(dims).also {
            for (k in it.indices) it[k] *= 2f * WALL * (if (k % dims == 1) tall else 1f)
        }
        boxEdges = Nd.hypercubeEdges(dims)
        tmp = FloatArray(dims)
        viewer = Nd.viewerFor(WALL, dims)
        // Scaled once, so the box at its widest over a whole turn of the view
        // fills the frame — across, and up to the visible height: found by
        // turning it, not guessed.
        val (ex, ey) = turnedExtent()
        drawScale = min(BOX_SCREEN / ex, visibleHalfHeight(env) * 0.93f / ey)
    }

    /**
     * Where a point is seen from: the view turns through the plane of the
     * first axis and the last, and tilts a little through the second and
     * third so the depth reads. Only the view turns; the box and the balls in
     * it don't. Turning the positions themselves, as this used to, carried
     * balls straight through walls their velocities knew nothing about.
     */
    /** The box's half extents across and up over a whole turn of the view. */
    private fun turnedExtent(): Pair<Float, Float> {
        var ex = 0.1f
        var ey = 0.1f
        for (step in 0 until 48) {
            val a = step * 2f * PI.toFloat() / 48
            for (v in 0 until (1 shl dims)) {
                viewOf(boxVerts, v * dims, a)
                ex = max(ex, abs(proj[0])); ey = max(ey, abs(proj[1]))
            }
        }
        return ex to ey
    }

    /** Half the screen's height in world units once the view has fitted [frameHalfWidth]. */
    private fun visibleHalfHeight(env: SimEnv): Float =
        max(1f, (BOX_SCREEN + 0.035f) / max(env.halfWidth, 1e-3f))

    /** As tall as the screen allows, measured on a cube and scaled up. */
    private fun tallFor(env: SimEnv): Float {
        tall = 1f
        boxVerts = Nd.hypercubeVertices(dims).also { for (k in it.indices) it[k] *= 2f * WALL }
        tmp = FloatArray(dims)
        viewer = Nd.viewerFor(WALL, dims)
        val (ex, ey) = turnedExtent()
        val scale = BOX_SCREEN / ex
        return (visibleHalfHeight(env) * 0.9f / (ey * scale)).coerceIn(1f, MAX_TALL)
    }

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
                val wd = if (d == 1) wall * tall else wall
                pos[base + d] += vel[base + d] * dt
                if (pos[base + d] > wd) {
                    pos[base + d] = wd; vel[base + d] = -vel[base + d] * e; hitsThisStep++
                    visited[i] = visited[i] or (1 shl d)
                } else if (pos[base + d] < -wd) {
                    pos[base + d] = -wd; vel[base + d] = -vel[base + d] * e; hitsThisStep++
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
        // Each edge coloured by the axis it runs along: an N-cube's edges
        // come in N directions, one per pair of walls, so the box shows how
        // many dimensions it has at a glance. A grey hairline at a third
        // strength was barely there on a phone.
        var e = 0
        while (e < boxEdges.size) {
            val a = boxEdges[e]; val b = boxEdges[e + 1]
            val axis = Integer.numberOfTrailingZeros(a xor b)
            val c = Palette.lerp(Palette.TEXT_SECONDARY, palette.at((axis + 0.5f) / dims), 0.6f)
            g.line(sx[a], sy[a], sx[b], sy[b], 0.005f, c, 0.7f)
            e += 2
        }
        for (v in 0 until nv) g.disc(sx[v], sy[v], 0.006f, Palette.TEXT_SECONDARY, 0.6f)
        // Far balls first, so a near one passes in front.
        if (ballOrder.size != count) { ballOrder = IntArray(count) { it }; ballX = FloatArray(count); ballY = FloatArray(count); ballD = FloatArray(count) }
        for (i in 0 until count) {
            viewOf(pos, i * dims, spin)
            ballX[i] = proj[0] * drawScale; ballY[i] = proj[1] * drawScale; ballD[i] = proj[2]
        }
        sortByKey(ballOrder, ballD)
        val glowShare = if (count > 60) 0.4f else 1f
        for (k in 0 until count) {
            val i = ballOrder[k]
            val d = ballD[i]
            val c = palette.at(tint[i])
            g.glow(ballX[i], ballY[i], 0.07f * d, c, 0.35f * glowShare)
            g.disc(ballX[i], ballY[i], BALL * d, c)
            g.disc(ballX[i] - BALL * d * 0.3f, ballY[i] + BALL * d * 0.3f, BALL * d * 0.3f, Palette.withAlpha(Palette.TEXT_PRIMARY, 0.45f))
        }
    }

    private var ballOrder = IntArray(0)
    private var ballX = FloatArray(0)
    private var ballY = FloatArray(0)
    private var ballD = FloatArray(0)

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

    /**
     * Drawn at a fixed size; on a phone held upright the view pulls back to
     * fit it — to the box's own reach and a ball's radius, where 0.74 kept a
     * margin a fifth of the screen wide.
     */
    override fun frameHalfWidth(env: SimEnv): Float = BOX_SCREEN + 0.035f

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
        /** A ball's radius on screen, before perspective. */
        private const val BALL = 0.027f
        /** The tallest the box gets, as a multiple of its width. */
        private const val MAX_TALL = 1.9f

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
        upTo = env.params.i("upto", 20).coerceIn(4, MAX_UP_TO)
        measure = env.params.choiceIndex("measure").coerceIn(0, MEASURES.size - 1)
        shown = 0f
        payoffFrame = false
        peakAnnounced = false
        top = (1..upTo).maxOf { value(it) }
        // Found over the whole range, not just the bars shown: counting only
        // to four, the last bar is the tallest, and it is no peak.
        milestone = when (measure) {
            // The peaks, found, not assumed.
            0, 1 -> (1..MAX_UP_TO).maxByOrNull { value(it) } ?: 5
            // The first dimension under one percent of the cube.
            2 -> (1..MAX_UP_TO).firstOrNull { value(it) < 0.01 } ?: MAX_UP_TO
            // The first with over half of it in the peel.
            else -> (1..MAX_UP_TO).firstOrNull { value(it) > 0.5 } ?: MAX_UP_TO
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
            if (!peakAnnounced && after >= min(milestone, upTo)) {
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
        val half = barW * 0.38f
        // Every dimension still to come has its slot marked on the axis, so
        // the first seconds are a chart waiting to fill, not two bars in a
        // corner of an empty screen.
        val first = kotlin.math.ceil(shown).toInt() + 1
        for (d in max(1, first)..upTo) {
            val x = -halfW + (d - 0.5f) * barW
            g.rect(x, BASE - 0.012f, half, 0.006f, Palette.TEXT_SECONDARY, 0f, 0.3f)
        }
        // Once the milestone bar has risen, its height stays marked across the
        // chart, so every bar after it is seen falling short of it.
        if (milestone <= upTo && shown >= milestone) {
            val v = (value(milestone) / top).toFloat()
            val y = BASE + v * HEIGHT
            g.line(-halfW, y, halfW, y, 0.003f, Palette.SUCCESS, 0.35f)
        }
        // Each bar rises while its dimension is being counted, rather than
        // appearing whole once it has been: the first bar used to arrive a
        // second and a half in, over an empty screen.
        for (d in 1..min(upTo, kotlin.math.ceil(shown).toInt())) {
            val v = (value(d) / top).toFloat()
            val rise = (shown - (d - 1)).coerceIn(0f, 1f)
            val eased = 1f - (1f - rise) * (1f - rise)
            val h = v * HEIGHT * eased
            val x = -halfW + (d - 0.5f) * barW
            val isPeak = d == milestone
            val c = if (isPeak) Palette.SUCCESS else palette.at(d.toFloat() / upTo)
            // A rounded top, its crown exactly at the value: the bar stops
            // short by the cap's radius and the cap finishes it.
            if (h > 2f * half) {
                g.bar(x, BASE, half, h - half, c)
                g.disc(x, BASE + h - half, half, c)
            } else {
                g.bar(x, BASE, half, h, c)
            }
            // A small light at the crown of the milestone bar. It was a glow
            // two and a half bars wide, a flat green disc behind the chart.
            if (isPeak) {
                g.glow(x, BASE + h, barW * 1.1f, Palette.SUCCESS, 0.45f)
                g.disc(x, BASE + h + half * 1.6f, half * 0.45f, Palette.brighten(Palette.SUCCESS, 0.3f))
            }
        }
        // For the shares, the line where the story turns: one percent, or half.
        if (measure == 2 || measure == 3) {
            val y = BASE + HEIGHT * ((if (measure == 2) 0.01 else 0.5) / top).toFloat()
            g.line(-halfW, y, halfW, y, 0.003f, Palette.WARNING, 0.5f)
        }
        g.line(-halfW, BASE, halfW, BASE, 0.004f, Palette.TEXT_SECONDARY, 0.5f)
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
        const val MAX_UP_TO = 40
        const val BASE = -0.85f
        /** The tallest bar's height: up to 0.8, where it stopped at 0.65 with the top fifth empty. */
        const val HEIGHT = 1.65f
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
 * from nothing to three times; by a hundred dimensions it is a spike at one, the
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
        val base = -0.5f
        val barW = 2f * halfW / BINS
        // After the payoff the bars brighten for a moment: the histogram
        // itself is what changed. A glow eight tenths of a unit across used
        // to sit behind it, a flat green disc over the chart.
        val lit = if (paid) (1f - (elapsed - paidAt) * 0.8f).coerceIn(0f, 1f) else 0f
        for (b in 0 until BINS) {
            val h = shownBins[b] * HIST_HEIGHT
            if (h < 0.002f) continue
            val x = -halfW + (b + 0.5f) * barW
            val c = palette.at(0.15f + 0.8f * b / BINS)
            g.bar(x, base, barW * 0.4f, h, Palette.brighten(c, 0.35f * lit))
        }
        g.line(-halfW, base, halfW, base, 0.004f, Palette.TEXT_SECONDARY, 0.5f)
        // The average distance, where everything ends up.
        val mx = -halfW + (1f / RANGE) * 2f * halfW
        g.line(mx, base - 0.03f, mx, base + HIST_HEIGHT + 0.05f, 0.003f, Palette.WARNING, 0.6f)
        g.disc(mx, base + HIST_HEIGHT + 0.05f, 0.008f, Palette.WARNING)
        if (lit > 0f) g.glow(mx, base + HIST_HEIGHT + 0.05f, 0.08f, Palette.WARNING, 0.6f * lit)
        // Nearest and farthest pair, as lengths from one end: they close on
        // each other. Capped at both ends so each reads as a measured span.
        val far = halfW * 1.8f
        val near = if (ratio.isFinite()) far / ratio else 0f
        val y = base - 0.17f
        val farC = palette.at(0.9f)
        val nearC = palette.at(0.2f)
        g.rect(-halfW + far * 0.5f, y, far * 0.5f, 0.014f, farC, 0f, 0.85f)
        g.rect(-halfW + far, y, 0.004f, 0.026f, farC)
        g.rect(-halfW + max(0.002f, near) * 0.5f, y - 0.07f, max(0.002f, near * 0.5f), 0.014f, nearC, 0f, 0.9f)
        g.rect(-halfW + near, y - 0.07f, 0.004f, 0.026f, nearC)
        g.rect(-halfW, y - 0.035f, 0.004f, 0.06f, Palette.TEXT_SECONDARY, 0f, 0.7f)
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
        /** The fullest bin's height. */
        const val HIST_HEIGHT = 1.3f
        /**
         * The histogram covers distance from zero to this many times the
         * average: on a line the farthest pair is three times it.
         */
        const val RANGE = 3f
        const val TICK = 0.45f
        const val GROWTH = 0.12f
        /** Spread (one standard deviation) under a tenth of the average. */
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
        ConcentrationSim.def(),
        TesseractUnfoldSim.def(),
        GrayWalkSim.def()
    )
}

/** Insertion sort of [order] by ascending [key][order]: small lists, nearly sorted frame to frame. */
internal fun sortByKey(order: IntArray, key: FloatArray) {
    for (i in 1 until order.size) {
        val v = order[i]
        val k = key[v]
        var j = i - 1
        while (j >= 0 && key[order[j]] > k) { order[j + 1] = order[j]; j-- }
        order[j + 1] = v
    }
}
