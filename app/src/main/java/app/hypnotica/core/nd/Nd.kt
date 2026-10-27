package app.hypnotica.core.nd

import kotlin.math.cos
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
        var x = point[offset]
        var y = if (dims > 1) point[offset + 1] else 0f
        var depth = 1f
        var d = dims - 1
        while (d >= 2) {
            val w = point[offset + d]
            val denom = viewerDistance - w
            val k = if (denom > 0.05f) viewerDistance / denom else 20f
            x *= k
            y *= k
            depth *= (k / (1f + viewerDistance)).coerceIn(0.05f, 4f)
            d--
        }
        out[0] = x
        out[1] = y
        out[2] = depth.coerceIn(0.05f, 2.5f)
    }

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

    /** Human-readable name of a rotation plane, e.g. "XW". */
    fun planeName(a: Int, b: Int): String {
        val axes = "XYZWVU"
        val x = axes.getOrElse(a) { '?' }
        val y = axes.getOrElse(b) { '?' }
        return "$x$y"
    }
}
