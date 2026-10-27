package app.hypnotica.core.studio

import app.hypnotica.core.render.Camera
import kotlin.math.abs
import kotlin.math.pow

/** How a value travels between two keyframes. */
enum class Easing(val title: String) {
    LINEAR("Linear"),
    EASE_OUT("Ease out"),
    EASE_IN("Ease in"),
    EASE_IN_OUT("Smooth"),
    BOUNCE("Bounce"),
    OVERSHOOT("Overshoot"),
    HOLD("Hold");

    fun apply(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return when (this) {
            LINEAR -> x
            EASE_OUT -> 1f - (1f - x).pow(3f)
            EASE_IN -> x * x * x
            EASE_IN_OUT -> if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3f) / 2f
            OVERSHOOT -> {
                val c = 1.70158f
                1f + (c + 1f) * (x - 1f).pow(3f) + c * (x - 1f).pow(2f)
            }
            BOUNCE -> {
                var v = x
                val n = 7.5625f
                val d = 2.75f
                when {
                    v < 1f / d -> n * v * v
                    v < 2f / d -> { v -= 1.5f / d; n * v * v + 0.75f }
                    v < 2.5f / d -> { v -= 2.25f / d; n * v * v + 0.9375f }
                    else -> { v -= 2.625f / d; n * v * v + 0.984375f }
                }
            }
            HOLD -> 0f
        }
    }
}

/** What a track controls. */
enum class TrackKind(val title: String, val min: Float, val max: Float, val default: Float) {
    CAMERA_X("Camera x", -3f, 3f, 0f),
    CAMERA_Y("Camera y", -3f, 3f, 0f),
    ZOOM("Zoom", 0.25f, 8f, 1f),
    // Three turns either way: a twist gesture accumulates without stopping.
    ROTATION("Rotation", -18.9f, 18.9f, 0f),
    SPEED("Speed", 0f, 10f, 1f),
    GRAVITY("Gravity", 0f, 40f, 9.81f),
    BOUNCINESS("Bounciness", 0f, 1.3f, 0.88f),
    TRAILS("Trails", 0f, 0.96f, 0f),
    FLASH("Flash", 0f, 1f, 0f)
}

data class Keyframe(val time: Float, val value: Float, val easing: Easing = Easing.EASE_IN_OUT)

/** One animated value over the length of a project. */
class Track(val kind: TrackKind) {

    private val keys = ArrayList<Keyframe>()

    val size: Int get() = keys.size

    fun keyframes(): List<Keyframe> = keys

    fun set(time: Float, value: Float, easing: Easing = Easing.EASE_IN_OUT) {
        val t = max0(time)
        val existing = keys.indexOfFirst { abs(it.time - t) < 0.02f }
        val k = Keyframe(t, value, easing)
        if (existing >= 0) keys[existing] = k else keys.add(k)
        keys.sortBy { it.time }
    }

    fun removeNear(time: Float): Boolean {
        val i = keys.indexOfFirst { abs(it.time - time) < 0.25f }
        if (i < 0) return false
        keys.removeAt(i)
        return true
    }

    fun clear() = keys.clear()

    fun isEmpty(): Boolean = keys.isEmpty()

    /** Value at [time]; the track's default when it has no keyframes. */
    fun valueAt(time: Float): Float {
        if (keys.isEmpty()) return kind.default
        if (time <= keys.first().time) return keys.first().value
        if (time >= keys.last().time) return keys.last().value
        for (i in 0 until keys.size - 1) {
            val a = keys[i]
            val b = keys[i + 1]
            if (time in a.time..b.time) {
                val span = b.time - a.time
                if (span <= 1e-5f) return b.value
                val t = (time - a.time) / span
                return a.value + (b.value - a.value) * b.easing.apply(t)
            }
        }
        return keys.last().value
    }

    private fun max0(v: Float) = if (v < 0f) 0f else v
}

/** A text card shown over the sim for part of the clip. */
data class TextClip(val text: String, val start: Float, val end: Float, val big: Boolean = true)

/**
 * A Studio project.
 *
 * It stores the recipe, the seed, the keyframes and the text — never pixels. A
 * project is a few kilobytes, replays exactly, and can be re-rendered at any
 * size afterwards.
 */
class Project(var simId: String, var seed: Long) {

    var duration: Float = 30f
    var name: String = "Untitled"

    val tracks: Map<TrackKind, Track> = TrackKind.entries.associateWith { Track(it) }
    val texts = ArrayList<TextClip>()

    /** Seconds of simulated time that are frozen, as (start, end) pairs. */
    val freezes = ArrayList<Pair<Float, Float>>()

    fun track(kind: TrackKind): Track = tracks.getValue(kind)

    fun isFrozenAt(time: Float): Boolean = freezes.any { time >= it.first && time < it.second }

    fun toggleFreeze(at: Float, length: Float = 2f) {
        val existing = freezes.indexOfFirst { at >= it.first && at < it.second }
        if (existing >= 0) freezes.removeAt(existing) else freezes.add(at to (at + length))
    }

    /** Push the camera tracks into a camera for the given moment. */
    /**
     * Drive the camera from the timeline. A track with no keyframes is left to
     * whatever the person set by hand, rather than snapped back to its default.
     */
    fun applyCamera(time: Float, camera: Camera) {
        track(TrackKind.CAMERA_X).let { if (!it.isEmpty()) camera.x = it.valueAt(time) }
        track(TrackKind.CAMERA_Y).let { if (!it.isEmpty()) camera.y = it.valueAt(time) }
        track(TrackKind.ZOOM).let {
            if (!it.isEmpty()) camera.zoom = it.valueAt(time).coerceIn(0.2f, 40f)
        }
        track(TrackKind.ROTATION).let { if (!it.isEmpty()) camera.rotation = it.valueAt(time) }
    }

    fun hasAnyKeyframes(): Boolean = tracks.values.any { !it.isEmpty() }

    fun textAt(time: Float): TextClip? = texts.firstOrNull { time >= it.start && time < it.end }

    /**
     * Drop a caption at [start]. Captions do not overlap: one already running at
     * that moment is replaced, so tapping twice corrects rather than stacks.
     */
    fun addText(text: String, start: Float, big: Boolean = true, length: Float = 3.5f) {
        val from = start.coerceIn(0f, maxOf(0f, duration - 0.5f))
        val to = minOf(duration, from + length)
        texts.removeAll { from < it.end && to > it.start }
        texts.add(TextClip(text, from, to, big))
        texts.sortBy { it.start }
    }

    /** Ready-made starting points so a project is never a blank timeline. */
    companion object {
        fun template(name: String, simId: String, seed: Long): Project {
            val p = Project(simId, seed)
            p.name = name
            when (name) {
                "Slow orbit" -> {
                    p.duration = 20f
                    p.track(TrackKind.ROTATION).set(0f, 0f, Easing.LINEAR)
                    p.track(TrackKind.ROTATION).set(20f, 6.283f, Easing.LINEAR)
                    p.track(TrackKind.ZOOM).set(0f, 0.9f)
                    p.track(TrackKind.ZOOM).set(20f, 1.15f)
                }
                "Build-up to payoff" -> {
                    p.duration = 30f
                    p.track(TrackKind.SPEED).set(0f, 0.6f)
                    p.track(TrackKind.SPEED).set(22f, 2.2f)
                    p.track(TrackKind.SPEED).set(26f, 0.35f, Easing.EASE_OUT)
                    p.track(TrackKind.SPEED).set(30f, 1f)
                    p.track(TrackKind.ZOOM).set(22f, 1f)
                    p.track(TrackKind.ZOOM).set(26f, 1.25f, Easing.OVERSHOOT)
                    p.track(TrackKind.FLASH).set(25.8f, 0f, Easing.HOLD)
                    p.track(TrackKind.FLASH).set(26f, 0.5f)
                    p.track(TrackKind.FLASH).set(26.6f, 0f)
                    p.texts.add(TextClip("Watch what happens", 0.5f, 4f))
                }
                "Gravity flip" -> {
                    p.duration = 24f
                    p.track(TrackKind.GRAVITY).set(0f, 9.81f, Easing.HOLD)
                    p.track(TrackKind.GRAVITY).set(12f, 9.81f)
                    p.track(TrackKind.GRAVITY).set(13f, 0f, Easing.EASE_IN_OUT)
                    p.track(TrackKind.GRAVITY).set(14f, 24f, Easing.OVERSHOOT)
                    p.track(TrackKind.TRAILS).set(0f, 0f)
                    p.track(TrackKind.TRAILS).set(13f, 0.8f)
                    p.track(TrackKind.TRAILS).set(20f, 0.2f)
                    p.texts.add(TextClip("Gravity flips at 12 s", 10f, 13f, big = false))
                }
                "Perfect loop" -> {
                    p.duration = 16f
                    p.track(TrackKind.ZOOM).set(0f, 1f, Easing.EASE_IN_OUT)
                    p.track(TrackKind.ZOOM).set(8f, 1.1f, Easing.EASE_IN_OUT)
                    p.track(TrackKind.ZOOM).set(16f, 1f, Easing.EASE_IN_OUT)
                    p.track(TrackKind.TRAILS).set(0f, 0.55f)
                }
                else -> {
                    p.duration = 20f
                }
            }
            return p
        }

        val TEMPLATES = listOf("Build-up to payoff", "Slow orbit", "Gravity flip", "Perfect loop", "Empty")
    }
}
