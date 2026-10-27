package app.hypnotica.core.audio

import app.hypnotica.core.safety.SafetySettings
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.EventQueue
import kotlin.math.ln
import kotlin.math.min

/**
 * Turns sim events into notes.
 *
 * Two things make the difference between music and static: the rate limiter,
 * which merges a burst into one louder note, and the scale, which means a fast
 * quicksort still lands on notes that belong together.
 */
class SoundMapping(private val synth: Synth) {

    var preset: SoundPreset = SoundPreset.EIGHT_BIT
        set(value) {
            field = value
            scale = Scale.byName(value.scaleName)
            synth.reverbAmount = value.reverb
            synth.muted = value.silent
        }

    // The sound tab writes these from the UI thread while pump() reads them on
    // the sim thread. They are independent scalars, so volatile is enough.
    @Volatile
    var scale: Scale = Scale.byName(SoundPreset.EIGHT_BIT.scaleName)

    @Volatile
    var baseHz: Float = SoundPreset.EIGHT_BIT.baseHz

    @Volatile
    var octaves: Float = SoundPreset.EIGHT_BIT.octaves

    @Volatile
    var decay: Float = SoundPreset.EIGHT_BIT.decay

    /** Notes per second ceiling. Above this, events merge instead of stacking. */
    @Volatile
    var maxNotesPerSecond: Float = 120f

    /** Per-kind mute switches, matching the Advanced sound tab. */
    private val enabled =
        java.util.concurrent.atomic.AtomicIntegerArray(IntArray(EventKind.entries.size) { 1 })

    var safety: SafetySettings = SafetySettings()

    /**
     * Called for events worth feeling, with a strength of 0..1. The engine turns
     * that into a motor tick, rate-limited there rather than here.
     */
    @Volatile
    var onHaptic: ((Float) -> Unit)? = null

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
        enabled.set(kind.ordinal, if (on) 1 else 0)
    }

    fun isEnabled(kind: EventKind): Boolean = enabled.get(kind.ordinal) != 0

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
        // The rate follows how long each note rings, so the number sounding at
        // once stays the same whatever the preset. A flat 120 a second was
        // about fourteen overlapping notes on 8-bit, whose notes last 0.12 s —
        // clean — and sixty-six on Music box at 0.55 s, which is every voice
        // the synth has, stolen and restolen into a wash of clashing bells.
        // 8-bit is the preset that sounded right, so its overlap is the one
        // every preset now keeps.
        val rate = min(maxNotesPerSecond, OVERLAP / decay.coerceAtLeast(0.02f))
        budget = min(rate, budget + frameSeconds * rate)

        var seen = 0
        queue.drain(512) { kind, value, pan, strength ->
            seen++
            if (enabled.get(kind.ordinal) != 0) {
                if (budget >= 1f) {
                    flushMerged()
                    budget -= 1f
                    play(kind, value, pan, strength)
                    if (kind in FELT) onHaptic?.invoke(strength)
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

    /** Nothing below this is a note; the transposer stops here. */
    private val MIN_HZ = 40f

    private val LN2 = ln(2f)

    /** Notes sounding at once, at most: what 8-bit's 120 a second at 0.12 s gives. */
    private val OVERLAP = 14.4f

    /**
     * The kinds that get a tick. A comparison in a sort does not: a thousand a
     * second would be a buzz, not a feeling.
     */
    private val FELT = setOf(EventKind.HIT, EventKind.CROSS, EventKind.DEATH, EventKind.DONE)

    private fun flushMerged() {
        if (mergedCount == 0) return
        val n = mergedCount.toFloat()
        play(
            EventKind.STEP,
            mergedValue / n,
            mergedPan / n,
            (mergedStrength * min(1.6f, 0.6f + n * 0.05f)).coerceAtMost(1f),
            advanceSong = true
        )
        mergedCount = 0
        mergedStrength = 0f
        mergedValue = 0f
        mergedPan = 0f
    }

    private fun play(
        kind: EventKind,
        value: Float,
        pan: Float,
        strength: Float,
        advanceSong: Boolean = true
    ) {
        val tune = song
        var hz = if (tune != null && tune.notes.isNotEmpty()) {
            val n = tune.notes[songIndex % tune.notes.size]
            // A merged burst is one note, not one per event it swallowed: the
            // melody used to run away at the merge rate under load.
            if (advanceSong) songIndex++
            440f * Math.pow(2.0, ((n - 69) / 12.0)).toFloat()
        } else {
            scale.hz(value, baseHz, fittedOctaves())
        }
        // Transposing, not clamping: clamping turned the top of the range into
        // a single pitch, so every loud event played the same note.
        while (hz > safety.maxHz) hz *= 0.5f
        if (hz < MIN_HZ) hz = MIN_HZ

        // The end of a run is one more note in the run's own voice. It used to
        // be a bell whatever the preset, at nearly twice the volume and three
        // times the decay — a chime dropped into the middle of an 8-bit
        // stream, the "ding" at the payoff, on sims where nothing had visibly
        // happened to deserve one.
        val type = when (kind) {
            EventKind.HIT -> if (preset.voice == VoiceType.SINE) VoiceType.PLUCK else preset.voice
            EventKind.DEATH -> VoiceType.CLICK
            else -> preset.voice
        }
        val amp = 0.10f + 0.28f * strength.coerceIn(0f, 1f)
        val dec = decay
        synth.note(hz, amp.coerceAtMost(0.75f), pan, type, dec)
    }

    /**
     * How many octaves actually fit above the base pitch before the safety
     * ceiling, which is usually fewer than the Range slider offers: at the
     * default pitch about four of its six, and at the top of the pitch slider
     * about two. The slider kept reading "6.0 octaves" either way, so this is
     * public for the label to tell the truth with.
     */
    fun octavesThatFit(): Float = fittedOctaves()

    private fun fittedOctaves(): Float {
        val room = ln(safety.maxHz / baseHz.coerceAtLeast(MIN_HZ)) / LN2
        return octaves.coerceAtMost(room.coerceAtLeast(1f))
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
