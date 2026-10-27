package app.hypnotica.core.studio

import app.hypnotica.core.render.Camera
import kotlin.math.abs

/**
 * Records what the person does to a running sim — pinch, drag, twist, speed,
 * trails — and turns it into a Studio project.
 *
 * Sampling is cheap and dumb: a value every [INTERVAL] seconds. The interesting
 * part is [thin], which throws away every sample that the two around it already
 * predict, so a ten-second recording becomes a handful of keyframes that can be
 * dragged afterwards rather than a thousand that cannot.
 */
class MoveRecorder(simId: String, seed: Long) {

    val project = Project(simId, seed).also { it.name = "Recorded" }

    /** Seconds recorded so far. */
    var seconds: Float = 0f
        private set

    var full: Boolean = false
        private set

    private val samples = HashMap<TrackKind, ArrayList<Keyframe>>()
    private var sinceSample = INTERVAL

    /** Call once per frame while recording. */
    fun feed(dt: Float, camera: Camera, speed: Float, trails: Float) {
        if (full) return
        seconds += dt
        if (seconds >= MAX_SECONDS) {
            full = true
            return
        }
        sinceSample += dt
        if (sinceSample < INTERVAL) return
        sinceSample = 0f
        put(TrackKind.CAMERA_X, camera.x)
        put(TrackKind.CAMERA_Y, camera.y)
        put(TrackKind.ZOOM, camera.zoom)
        put(TrackKind.ROTATION, camera.rotation)
        put(TrackKind.SPEED, speed)
        put(TrackKind.TRAILS, trails)
    }

    private fun put(kind: TrackKind, value: Float) {
        samples.getOrPut(kind) { ArrayList() }
            .add(Keyframe(seconds, value.coerceIn(kind.min, kind.max)))
    }

    /**
     * Finish the recording and hand back the project. A track whose value never
     * really moved is dropped, so a recording where only the zoom changed does
     * not leave five flat tracks in the way.
     */
    fun finish(): Project {
        project.duration = maxOf(2f, kotlin.math.ceil(seconds.toDouble()).toFloat())
        for ((kind, list) in samples) {
            if (list.size < 2) continue
            val span = (list.maxOf { it.value } - list.minOf { it.value })
            val range = kind.max - kind.min
            if (span < range * 0.004f) continue
            val kept = thin(list, range * TOLERANCE)
            val track = project.track(kind)
            track.clear()
            for (k in kept) track.set(k.time, k.value, Easing.EASE_IN_OUT)
        }
        return project
    }

    /**
     * Douglas-Peucker over (time, value): keep the sample furthest from the line
     * between the endpoints, recurse on both halves, stop when everything left
     * is within [tolerance].
     */
    private fun thin(points: List<Keyframe>, tolerance: Float): List<Keyframe> {
        if (points.size <= 2) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        simplify(points, 0, points.size - 1, tolerance, keep)
        val out = ArrayList<Keyframe>()
        for (i in points.indices) if (keep[i]) out.add(points[i])
        // A recording should still be editable, so cap the keyframe count. The
        // last sample is always kept: it is where the move ended.
        if (out.size <= MAX_KEYS) return out
        val last = out.size - 1
        val stride = last.toFloat() / (MAX_KEYS - 1)
        val capped = ArrayList<Keyframe>(MAX_KEYS)
        var picked = -1
        for (i in 0 until MAX_KEYS - 1) {
            val index = (i * stride).toInt().coerceIn(0, last)
            if (index == picked) continue
            picked = index
            capped.add(out[index])
        }
        if (picked != last) capped.add(out[last])
        return capped
    }

    private fun simplify(
        p: List<Keyframe>,
        from: Int,
        to: Int,
        tolerance: Float,
        keep: BooleanArray
    ) {
        if (to <= from + 1) return
        val t0 = p[from].time
        val v0 = p[from].value
        val span = p[to].time - t0
        val rise = p[to].value - v0
        var worst = -1f
        var worstAt = -1
        for (i in from + 1 until to) {
            val predicted = if (span <= 1e-5f) v0 else v0 + rise * ((p[i].time - t0) / span)
            val error = abs(p[i].value - predicted)
            if (error > worst) {
                worst = error
                worstAt = i
            }
        }
        if (worst < tolerance || worstAt < 0) return
        keep[worstAt] = true
        simplify(p, from, worstAt, tolerance, keep)
        simplify(p, worstAt, to, tolerance, keep)
    }

    companion object {
        const val INTERVAL = 1f / 12f
        const val MAX_SECONDS = 60f

        /** Fraction of a track's range that counts as "the same value". */
        const val TOLERANCE = 0.012f
        const val MAX_KEYS = 40
    }
}
