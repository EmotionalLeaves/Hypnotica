package app.hypnotica.core.nd

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Working in more than three dimensions.
 *
 * Points are flat float arrays of length dims, packed back to back, so a cloud
 * of them is one allocation and the inner loops stay free of objects.
 *
 * Rotation in N dimensions happens in a plane, not around an axis: in 3D the
 * plane XY looks like spinning about Z, and in 4D the planes XW, YW and ZW are
 * the turns that have no 3D equivalent and make a tesseract appear to fold
 * through itself.
 */
object Nd {

    const val MAX_DIMS = 6

    /** Rotate every point in [data] through the plane spanned by axes a and b. */
    fun rotatePlane(data: FloatArray, count: Int, dims: Int, a: Int, b: Int, angle: Float) {
        if (a >= dims || b >= dims || a == b) return
        val c = cos(angle)
        val s = sin(angle)
        var base = 0
        for (i in 0 until count) {
            val x = data[base + a]
            val y = data[base + b]
            data[base + a] = x * c - y * s
            data[base + b] = x * s + y * c
            base += dims
        }
    }

    /**
     * Project one point down to 2D.
     *
     * Each dimension above the second is collapsed with its own perspective
     * divide, so a vertex further away along W is drawn smaller, which is what
     * makes a 4D object read as nested rather than flat.
     *
     * [out] receives x, y and a depth factor in 0..1 for sizing and fading.
     */
    fun project(point: FloatArray, offset: Int, dims: Int, viewerDistance: Float, out: FloatArray) {
        // A true perspective, one dimension at a time: 6D to 5D to ... to 2D.
        // Each step divides by the depth along the highest axis left — and
        // that depth is itself the coordinate as the steps above have already
        // scaled it. Taking the raw coordinate, as this used to, dropped every
        // step but the first and drew a tesseract that no camera could see.
        var product = 1f
        var axes = 0
        var d = dims - 1
        while (d >= 2) {
            val w = point[offset + d] * product
            val denom = viewerDistance - w
            val k = if (denom > 0.05f) viewerDistance / denom else 20f
            product *= k
            axes++
            d--
        }
        out[0] = point[offset] * product
        out[1] = (if (dims > 1) point[offset + 1] else 0f) * product
        // The nearness cue is the average of the per-axis factors, not their
        // product. A product compounds: each extra axis multiplied it by about a
        // quarter, so a five-dimensional cube drew every corner twenty times
        // smaller than a three-dimensional one and six looked identical to five
        // once the clamp caught it — the depth cue vanished at exactly the
        // dimensions where it matters most. An average keeps the same spread of
        // nearness whatever the dimension.
        out[2] = if (axes == 0) 1f else pow(product, 1f / axes).coerceIn(0.4f, 2.2f)
    }

    /**
     * A viewer distance for points whose coordinates stay within
     * [coordMax]: far enough back that the nested perspective of [dims]
     * dimensions never magnifies a point more than about twice. Each step
     * compounds the last, so the more dimensions, the further back.
     */
    fun viewerFor(coordMax: Float, dims: Int): Float = coordMax * (2f + dims)

    private fun pow(base: Float, e: Float): Float =
        if (base <= 0f) 0f else exp(ln(base) * e)

    /** The 2^dims corners of a unit hypercube, packed into one array. */
    fun hypercubeVertices(dims: Int): FloatArray {
        val d = dims.coerceIn(1, MAX_DIMS)
        val n = 1 shl d
        val out = FloatArray(n * d)
        for (i in 0 until n) {
            for (axis in 0 until d) {
                out[i * d + axis] = if ((i shr axis) and 1 == 1) 0.5f else -0.5f
            }
        }
        return out
    }

    /** Index pairs of hypercube corners that differ in exactly one coordinate. */
    fun hypercubeEdges(dims: Int): IntArray {
        val d = dims.coerceIn(1, MAX_DIMS)
        val n = 1 shl d
        val edges = ArrayList<Int>(d * n)
        for (i in 0 until n) {
            for (axis in 0 until d) {
                val j = i xor (1 shl axis)
                if (i < j) {
                    edges.add(i)
                    edges.add(j)
                }
            }
        }
        return edges.toIntArray()
    }

    /**
     * Where a hypersphere of radius [r] centred at the origin meets the plane
     * at coordinate [at] on the given axis: a sphere one dimension down, of this
     * radius. Zero means the slice misses it entirely.
     *
     * This is the Flatland picture: a sphere passing through a plane looks like a
     * circle that grows, peaks and shrinks away.
     */
    fun sliceRadius(r: Float, at: Float): Float {
        val inside = r * r - at * at
        return if (inside <= 0f) 0f else sqrt(inside)
    }

    /**
     * Volume of the unit n-ball. It climbs to a peak at five dimensions and then
     * falls away towards nothing, which is the headline result of the
     * dimension-scaling family.
     */
    fun unitBallVolume(n: Int): Double {
        // V(n) = pi^(n/2) / gamma(n/2 + 1), built up by the recurrence
        // V(n) = V(n-2) * 2*pi / n so no gamma function is needed.
        if (n <= 0) return 1.0
        if (n == 1) return 2.0
        if (n == 2) return Math.PI
        var v = if (n % 2 == 0) Math.PI else 2.0
        var k = if (n % 2 == 0) 2 else 1
        while (k + 2 <= n) {
            k += 2
            v = v * 2.0 * Math.PI / k
        }
        return v
    }

    /** Euclidean length of a packed point. */
    fun length(data: FloatArray, offset: Int, dims: Int): Float {
        var s = 0f
        for (d in 0 until dims) {
            val v = data[offset + d]
            s += v * v
        }
        return sqrt(s)
    }

    /**
     * The cross-polytope (the 16-cell in 4D, the octahedron in 3D): a corner
     * at ±[radius] on every axis, each joined to every other but its opposite.
     */
    fun crossVertices(dims: Int, radius: Float): FloatArray {
        val out = FloatArray(2 * dims * dims)
        for (axis in 0 until dims) {
            out[(2 * axis) * dims + axis] = radius
            out[(2 * axis + 1) * dims + axis] = -radius
        }
        return out
    }

    fun crossEdges(dims: Int): IntArray {
        val n = 2 * dims
        val edges = ArrayList<Int>()
        for (i in 0 until n) for (j in i + 1 until n) if (i / 2 != j / 2) { edges.add(i); edges.add(j) }
        return edges.toIntArray()
    }

    /**
     * The regular simplex (the 5-cell in 4D, the tetrahedron in 3D): dims + 1
     * corners all the same distance apart, centred, at [radius] from the
     * centre. Built from the corners of the standard simplex one dimension up,
     * moved into the hyperplane they span by Gram–Schmidt — exact, not drawn
     * by eye.
     */
    fun simplexVertices(dims: Int, radius: Float): FloatArray {
        val m = dims + 1
        // The corners e_i - centroid, in m dimensions.
        val p = Array(m) { i -> DoubleArray(m) { j -> (if (i == j) 1.0 else 0.0) - 1.0 / m } }
        // An orthonormal basis of the hyperplane they lie in.
        val basis = ArrayList<DoubleArray>()
        for (i in 0 until m) {
            if (basis.size == dims) break
            val v = p[i].copyOf()
            for (b in basis) {
                var dot = 0.0
                for (k in 0 until m) dot += v[k] * b[k]
                for (k in 0 until m) v[k] -= dot * b[k]
            }
            var len = 0.0
            for (k in 0 until m) len += v[k] * v[k]
            len = sqrt(len.toFloat()).toDouble()
            if (len < 1e-9) continue
            for (k in 0 until m) v[k] /= len
            basis.add(v)
        }
        val circum = kotlin.math.sqrt((m - 1.0) / m)
        val out = FloatArray(m * dims)
        for (i in 0 until m) for (d in 0 until dims) {
            var dot = 0.0
            for (k in 0 until m) dot += p[i][k] * basis[d][k]
            out[i * dims + d] = (dot / circum * radius).toFloat()
        }
        return out
    }

    fun simplexEdges(dims: Int): IntArray {
        val n = dims + 1
        val edges = ArrayList<Int>()
        for (i in 0 until n) for (j in i + 1 until n) { edges.add(i); edges.add(j) }
        return edges.toIntArray()
    }

    /**
     * The 24-cell, which only four dimensions have: every arrangement of
     * (±1, ±1, 0, 0), scaled to [radius], each corner joined to the eight at
     * the shortest distance, √2 before scaling. 24 corners, 96 edges.
     */
    fun cell24Vertices(radius: Float): FloatArray {
        val out = ArrayList<Float>()
        val k = radius / sqrt(2f)
        for (a in 0 until 4) for (b in a + 1 until 4) for (sa in intArrayOf(1, -1)) for (sb in intArrayOf(1, -1)) {
            val v = FloatArray(4)
            v[a] = sa * k; v[b] = sb * k
            for (x in v) out.add(x)
        }
        return out.toFloatArray()
    }

    /** Edges between corners [edge] apart, to a small tolerance. */
    fun edgesAtDistance(verts: FloatArray, dims: Int, edge: Float): IntArray {
        val n = verts.size / dims
        val edges = ArrayList<Int>()
        for (i in 0 until n) for (j in i + 1 until n) {
            var s = 0f
            for (d in 0 until dims) { val t = verts[i * dims + d] - verts[j * dims + d]; s += t * t }
            if (abs(sqrt(s) - edge) < edge * 1e-3f) { edges.add(i); edges.add(j) }
        }
        return edges.toIntArray()
    }

    /** Human-readable name of a rotation plane, e.g. "XW". */
    fun planeName(a: Int, b: Int): String {
        val axes = "XYZWVU"
        val x = axes.getOrElse(a) { '?' }
        val y = axes.getOrElse(b) { '?' }
        return "$x$y"
    }
}
