package app.hypnotica.core.studio

import app.hypnotica.core.render.Camera
import kotlin.math.min
import kotlin.math.max
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

/**
 * How close to a key counts as being on it, when a project has not said.
 *
 * A quarter of a second of a thirty-second project is about three dp of a
 * phone-width scrub bar, which is why Remove key used to miss almost every
 * time. [Project.grab] scales it to the project instead; this is the floor.
 */
const val DEFAULT_GRAB = 0.3f

/** One animated value over the length of a project. */
class Track(val kind: TrackKind) {

    private val keys = ArrayList<Keyframe>()

    val size: Int get() = keys.size

    fun keyframes(): List<Keyframe> = keys

    /**
     * Put keys back exactly as they were, without the same-spot merging that
     * [set] does for a finger. Copying a project through [set] merged any two
     * keys closer than 0.3 s — which [moveNear] can leave — so an export could
     * be missing a key the preview still showed.
     */
    fun restore(from: List<Keyframe>) {
        keys.clear()
        keys.addAll(from.sortedBy { it.time })
    }

    /**
     * Add a key, or replace the one already at this spot.
     *
     * [within] is how close counts as the same spot, and every one of set,
     * [removeNear] and [moveNear] must be handed the same number. They used
     * 0.02 s, 0.25 s and 0.6 s respectively, which meant pressing Key twice at
     * what looked like one place made two keys a fiftieth of a second apart,
     * drawn as a single marker — and then Remove, working to a window twelve
     * times wider, could take only one of them. A key you could make was a key
     * you could not reliably unmake. [Project.grab] is the shared answer.
     */
    fun set(
        time: Float,
        value: Float,
        easing: Easing = Easing.EASE_IN_OUT,
        within: Float = DEFAULT_GRAB
    ) {
        val t = max0(time)
        val existing = nearest(t, within)
        val k = Keyframe(t, value, easing)
        if (existing >= 0) keys[existing] = k else keys.add(k)
        keys.sortBy { it.time }
    }

    fun removeNear(time: Float, within: Float = DEFAULT_GRAB): Boolean {
        val i = nearest(time, within)
        if (i < 0) return false
        keys.removeAt(i)
        return true
    }

    /** The key closest to [time] inside [within], or -1. Closest, not first. */
    private fun nearest(time: Float, within: Float): Int {
        var best = -1
        var gap = within
        for (i in keys.indices) {
            val d = abs(keys[i].time - time)
            if (d < gap) {
                gap = d
                best = i
            }
        }
        return best
    }

    fun clear() = keys.clear()

    /**
     * Slide the key nearest [from] to [to], keeping its value and easing.
     *
     * The docs have promised draggable keys since the strip was built; without
     * this a recorded move could only be wiped, never adjusted.
     */
    fun moveNear(from: Float, to: Float, within: Float = DEFAULT_GRAB): Boolean {
        var best = -1
        var bestGap = within
        for (i in keys.indices) {
            val gap = abs(keys[i].time - from)
            if (gap <= bestGap) {
                bestGap = gap
                best = i
            }
        }
        if (best < 0) return false
        val moved = keys[best].copy(time = max0(to))
        keys.removeAt(best)
        // A key dragged onto another one replaces it, the same way setting a key
        // at an occupied time does.
        val collision = keys.indexOfFirst { abs(it.time - moved.time) < 0.02f }
        if (collision >= 0) keys[collision] = moved else keys.add(moved)
        keys.sortBy { it.time }
        return true
    }

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

    /**
     * Take on everything [other] holds that the person edits — keyframes,
     * captions, freezes, length, name — keeping this object, which the engine
     * and the screen both hold on to. Undo restores an earlier copy this way.
     */
    fun restoreFrom(other: Project) {
        duration = other.duration
        name = other.name
        for (kind in TrackKind.entries) track(kind).restore(other.track(kind).keyframes().toList())
        texts.clear(); texts.addAll(other.texts)
        freezes.clear(); freezes.addAll(other.freezes)
    }

    /**
     * How close to a key counts as being on it, for this project's length.
     *
     * The scrub bar is the only way to place the playhead, so the window has to
     * be worth a usable number of pixels: about a hundredth of the length, so a
     * thirty-second project gives three dp of thumb travel either side rather
     * than the third of a dp the old 0.02 s window allowed.
     */
    val grab: Float get() = max(DEFAULT_GRAB, duration / 100f)

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

    /**
     * A snapshot the offline render can own.
     *
     * The exporter runs for minutes on another thread while Studio is still
     * open, and a caption typed or a keyframe dragged in the meantime would be
     * a list changing underneath the frame loop.
     */
    fun copy(): Project {
        val made = Project(simId, seed)
        made.duration = duration
        made.name = name
        for ((kind, track) in tracks) made.track(kind).restore(track.keyframes())
        made.texts.addAll(texts)
        made.freezes.addAll(freezes)
        return made
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
        /**
         * Build a starting project, laid out around the length of the sim it is
         * for.
         *
         * [seconds] is the sim's own typical length. Every keyframe below is
         * placed as a share of it rather than at a fixed second, because the
         * same numbers cannot be right for a sim that resolves in ten seconds
         * and one that takes forty: "Build-up to payoff" used to put its slow
         * motion, its punch-in and its flash at 26 seconds whatever it was
         * opened on, which is eight seconds after the payoff of one sim in the
         * app and twenty-three seconds after another's. The whole point of the
         * template is that those three land together on the moment.
         */
        fun template(
            name: String,
            simId: String,
            seed: Long,
            seconds: Float = 30f,
            loopSeconds: Float? = null
        ): Project {
            val p = Project(simId, seed)
            p.name = name
            // A project shorter than a few seconds cannot be keyframed usefully.
            val d = seconds.coerceIn(8f, 120f)
            // Where the design doc puts the payoff: setup 10%, build 60%,
            // payoff 10%, resolve 20%.
            val moment = d * 0.7f
            when (name) {
                "Slow orbit" -> {
                    p.duration = d
                    p.track(TrackKind.ROTATION).set(0f, 0f, Easing.LINEAR)
                    p.track(TrackKind.ROTATION).set(d, 6.283f, Easing.LINEAR)
                    p.track(TrackKind.ZOOM).set(0f, 0.9f)
                    p.track(TrackKind.ZOOM).set(d, 1.15f)
                }
                "Build-up to payoff" -> {
                    p.duration = d
                    // The run-up is a share of the build, not a fixed four
                    // seconds, so a short sim does not spend half of itself
                    // accelerating.
                    val runUp = (d * 0.13f).coerceIn(1.5f, 5f)
                    p.track(TrackKind.SPEED).set(0f, 0.6f)
                    p.track(TrackKind.SPEED).set(moment - runUp, 2.2f)
                    p.track(TrackKind.SPEED).set(moment, 0.35f, Easing.EASE_OUT)
                    p.track(TrackKind.SPEED).set(d, 1f)
                    p.track(TrackKind.ZOOM).set(moment - runUp, 1f)
                    p.track(TrackKind.ZOOM).set(moment, 1.25f, Easing.OVERSHOOT)
                    p.track(TrackKind.FLASH).set(moment - 0.2f, 0f, Easing.HOLD)
                    p.track(TrackKind.FLASH).set(moment, 0.5f)
                    p.track(TrackKind.FLASH).set(moment + 0.6f, 0f)
                    p.texts.add(TextClip("Watch what happens", 0.5f, min(4f, d * 0.2f)))
                }
                "Gravity flip" -> {
                    p.duration = d
                    val flip = d * 0.5f
                    p.track(TrackKind.GRAVITY).set(0f, 9.81f, Easing.HOLD)
                    p.track(TrackKind.GRAVITY).set(flip - 1f, 9.81f)
                    p.track(TrackKind.GRAVITY).set(flip, 0f, Easing.EASE_IN_OUT)
                    p.track(TrackKind.GRAVITY).set(flip + 1f, 24f, Easing.OVERSHOOT)
                    p.track(TrackKind.TRAILS).set(0f, 0f)
                    p.track(TrackKind.TRAILS).set(flip, 0.8f)
                    p.track(TrackKind.TRAILS).set(min(d, flip + 7f), 0.2f)
                    // The caption names the second it happens, so it has to be
                    // the second it happens.
                    p.texts.add(
                        TextClip(
                            "Gravity flips at ${flip.toInt()} s",
                            max(0f, flip - 3f), flip, big = false
                        )
                    )
                }
                "Perfect loop" -> {
                    // Whole cycles of the sim's own loop, as near twenty
                    // seconds as they come, so the last frame meets the first.
                    // The zoom peaks halfway, so the camera loops with it.
                    p.duration = if (loopSeconds != null && loopSeconds > 0f) {
                        loopSeconds * max(1f, kotlin.math.round(LOOP_TARGET / loopSeconds))
                    } else {
                        d
                    }
                    val d = p.duration
                    p.track(TrackKind.ZOOM).set(0f, 1f, Easing.EASE_IN_OUT)
                    p.track(TrackKind.ZOOM).set(d * 0.5f, 1.1f, Easing.EASE_IN_OUT)
                    p.track(TrackKind.ZOOM).set(d, 1f, Easing.EASE_IN_OUT)
                    p.track(TrackKind.TRAILS).set(0f, 0.55f)
                }
                else -> {
                    p.duration = d
                }
            }
            return p
        }

        val TEMPLATES = listOf("Build-up to payoff", "Slow orbit", "Gravity flip", "Perfect loop", "Empty")

        /** About how long a "Perfect loop" clip runs, before rounding to whole cycles. */
        private const val LOOP_TARGET = 20f
    }
}
