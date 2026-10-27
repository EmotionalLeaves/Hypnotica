package app.hypnotica.core.audio

import app.hypnotica.core.safety.SafetySettings
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.EventQueue
import kotlin.math.min

/**
 * Turns sim events into notes.
 *
 * Two things make the difference between music and static: the rate limiter,
 * which merges a burst into one louder note, and the scale, which means a fast
 * quicksort still lands on notes that belong together.
 */
class SoundMapping(private val synth: Synth) {

    var preset: SoundPreset = SoundPreset.MUSIC_BOX
        set(value) {
            field = value
            scale = Scale.byName(value.scaleName)
            synth.reverbAmount = value.reverb
            synth.muted = value.silent
        }

    var scale: Scale = Scale.byName(SoundPreset.MUSIC_BOX.scaleName)

    var baseHz: Float = SoundPreset.MUSIC_BOX.baseHz
    var octaves: Float = SoundPreset.MUSIC_BOX.octaves
    var decay: Float = SoundPreset.MUSIC_BOX.decay

    /** Notes per second ceiling. Above this, events merge instead of stacking. */
    var maxNotesPerSecond: Float = 120f

    /** Per-kind mute switches, matching the Advanced sound tab. */
    private val enabled = BooleanArray(EventKind.entries.size) { true }

    var safety: SafetySettings = SafetySettings()

    /** Optional melody: when set, each event plays the next note of the tune. */
    var song: Song? = null
    private var songIndex = 0

    private var budget = 0f
    private var mergedStrength = 0f
    private var mergedValue = 0f
    private var mergedPan = 0f
    private var mergedCount = 0

    /** Events seen in the last second, used to switch to texture sound at scale. */
    var eventRate: Float = 0f
        private set

    private var rateAccum = 0f
    private var rateWindow = 0f

    fun setEnabled(kind: EventKind, on: Boolean) {
        enabled[kind.ordinal] = on
    }

    fun isEnabled(kind: EventKind): Boolean = enabled[kind.ordinal]

    fun applyPreset(p: SoundPreset) {
        preset = p
        baseHz = p.baseHz
        octaves = p.octaves
        decay = p.decay
    }

    fun resetSong() {
        songIndex = 0
    }

    /**
     * Drain a frame's worth of events and play them.
     * Call once per frame from the render thread.
     */
    fun pump(queue: EventQueue, frameSeconds: Float) {
        budget = min(maxNotesPerSecond, budget + frameSeconds * maxNotesPerSecond)

        var seen = 0
        queue.drain(512) { kind, value, pan, strength ->
            seen++
            if (enabled[kind.ordinal]) {
                if (budget >= 1f) {
                    flushMerged()
                    budget -= 1f
                    play(kind, value, pan, strength)
                } else {
                    // Out of budget: fold this event into one louder note.
                    mergedCount++
                    mergedStrength = maxOf(mergedStrength, strength)
                    mergedValue += value
                    mergedPan += pan
                }
            }
        }

        rateAccum += seen.toFloat()
        rateWindow += frameSeconds
        if (rateWindow >= 0.25f) {
            eventRate = rateAccum / rateWindow
            rateAccum = 0f
            rateWindow = 0f
        }

        if (mergedCount > 0 && budget >= 1f) {
            budget -= 1f
            flushMerged()
        }
    }

    private fun flushMerged() {
        if (mergedCount == 0) return
        val n = mergedCount.toFloat()
        play(
            EventKind.STEP,
            mergedValue / n,
            mergedPan / n,
            (mergedStrength * min(1.6f, 0.6f + n * 0.05f)).coerceAtMost(1f)
        )
        mergedCount = 0
        mergedStrength = 0f
        mergedValue = 0f
        mergedPan = 0f
    }

    private fun play(kind: EventKind, value: Float, pan: Float, strength: Float) {
        val tune = song
        var hz = if (tune != null && tune.notes.isNotEmpty()) {
            val n = tune.notes[songIndex % tune.notes.size]
            songIndex++
            440f * Math.pow(2.0, ((n - 69) / 12.0)).toFloat()
        } else {
            scale.hz(value, baseHz, octaves)
        }
        if (hz > safety.maxHz) hz = safety.maxHz

        val type = when (kind) {
            EventKind.DONE -> VoiceType.BELL
            EventKind.HIT -> if (preset.voice == VoiceType.SINE) VoiceType.PLUCK else preset.voice
            EventKind.DEATH -> VoiceType.CLICK
            else -> preset.voice
        }
        val amp = (0.10f + 0.28f * strength.coerceIn(0f, 1f)) *
            (if (kind == EventKind.DONE) 1.8f else 1f)
        val dec = if (kind == EventKind.DONE) decay * 3.2f else decay
        synth.note(hz, amp.coerceAtMost(0.75f), pan, type, dec)
    }

    /** Play a rising sweep: the "it is finished" moment. */
    fun finalSweep(steps: Int = 14) {
        for (i in 0 until steps) {
            val t = i / (steps - 1f)
            synth.note(scale.hz(t, baseHz, octaves), 0.2f, 0f, preset.voice, decay * 1.4f)
        }
    }
}

/** A melody for Song mode: MIDI note numbers, played one per event. */
data class Song(val name: String, val notes: IntArray) {

    override fun equals(other: Any?): Boolean =
        this === other || (other is Song && name == other.name && notes.contentEquals(other.notes))

    override fun hashCode(): Int = 31 * name.hashCode() + notes.contentHashCode()

    companion object {
        val NONE = Song("Off", intArrayOf())

        val ASCENDING = Song(
            "Rising",
            intArrayOf(60, 62, 64, 67, 69, 72, 74, 76, 79, 81, 84)
        )

        val ARPEGGIO = Song(
            "Arpeggio",
            intArrayOf(60, 64, 67, 72, 67, 64, 60, 55, 60, 64, 67, 72)
        )

        val PENTATONIC_WALK = Song(
            "Pentatonic walk",
            intArrayOf(60, 63, 65, 67, 70, 72, 70, 67, 65, 63)
        )

        val ALL = listOf(NONE, ASCENDING, ARPEGGIO, PENTATONIC_WALK)
        val NAMES: List<String> = ALL.map { it.name }
        fun byIndex(i: Int): Song = ALL[i.coerceIn(0, ALL.size - 1)]
    }
}
