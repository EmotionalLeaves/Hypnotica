package app.hypnotica.core.audio

import app.hypnotica.core.safety.SafetySettings
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.EventQueue
import kotlin.math.ln
import kotlin.math.max
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

    /**
     * The beat under the sim, or null for none. See [Groove]. Written from the
     * UI thread, read here; a reference swap, so volatile is enough.
     */
    @Volatile
    var groove: Groove? = Groove.FOUR

    // The groove's clock and the notes waiting for the next sixteenth.
    /**
     * Where the groove is, in sixteenths. Counted in steps, not seconds: a
     * clock in seconds divided by the step length jumped backwards whenever
     * the tempo slowed — every new sim under Auto — and nothing played until
     * it caught up, minutes later.
     */
    private var stepPos = 0.0

    /** Kicks played so far: the picture pulses on each one it sees go up. */
    @Volatile
    var kicks = 0L
        private set
    private var nextStep = 0L
    private var activity = 0f
    private var quietFor = 10f
    private val heldHz = FloatArray(MAX_HELD)
    private val heldPan = FloatArray(MAX_HELD)
    private val heldStrength = FloatArray(MAX_HELD)
    private val heldType = arrayOfNulls<VoiceType>(MAX_HELD)
    private var held = 0

    /** Start the groove's bar over, as a new run starts. */
    fun resetGroove() {
        stepPos = 0.0
        nextStep = 0L
        held = 0
    }

    /** Drop notes waiting for the beat, as a new run starts: they belong to the old one. */
    fun clearHeld() {
        held = 0
    }

    /** Optional melody: when set, each event plays the next note of the tune. */
    @Volatile
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

        val g = groove
        if (g != null) {
            // The kit plays while the sim does: in within a third of a second
            // of the first note, out over a couple once the notes stop.
            if (seen > 0) quietFor = 0f else quietFor += frameSeconds
            val target = if (quietFor < QUIET_AFTER) 1f else 0f
            val rate = if (target > activity) 3f else 0.6f
            activity += (target - activity) * min(1f, frameSeconds * rate)
            stepPos += frameSeconds / g.stepSeconds
            val due = stepPos.toLong()
            var guard = 0
            while (nextStep <= due && guard < 4) {
                onStep(g, nextStep)
                nextStep++
                guard++
            }
            if (nextStep <= due) nextStep = due + 1
        } else if (held > 0) {
            releaseHeld(0)
        }
    }

    /** One sixteenth of the groove: the kit, the bass, and the notes that waited for it. */
    private fun onStep(g: Groove, step: Long) {
        val inBar = (step % 16).toInt()
        val bar = step / 16
        val root = g.rootAt(bar)
        releaseHeld(root, step)
        val level = activity * preset.gain * GROOVE_LEVEL
        if (level < 0.002f || preset.silent) return
        if (inBar in g.kick) {
            synth.note(KICK_HZ, 0.55f * level, 0f, VoiceType.SINE, 0.11f)
            kicks++
        }
        if (inBar in g.snare) synth.note(SNARE_HZ, 0.22f * level, 0f, VoiceType.NOISE, 0.07f)
        if (inBar in g.hat) synth.note(HAT_HZ, 0.08f * level, if (inBar % 4 == 2) 0.3f else -0.3f, VoiceType.NOISE, 0.022f)
        if (inBar in g.bass) {
            var hz = baseHz * 0.5f * Math.pow(2.0, root / 12.0).toFloat()
            while (hz > BASS_CEILING) hz *= 0.5f
            while (hz < MIN_HZ * 1.5f) hz *= 2f
            synth.note(hz, 0.30f * level, 0f, VoiceType.TRIANGLE, if (inBar == 0) 0.32f else 0.16f)
        }
    }

    /**
     * Play what was held for this step, a chord of at most [PER_STEP] notes: the
     * strongest, with the rest folded into their loudness rather than lost.
     */
    private fun releaseHeld(root: Int, step: Long = 0L) {
        if (held == 0) return
        val tune = song
        if (tune != null && tune.notes.isNotEmpty() && heldHz[0] == SONG_NOTE) {
            // A tune is one note an eighth, its own key — the chord loop would
            // bend a melody everyone knows into one nobody does.
            if (step % 2L != 0L) return
            var strength = 0f
            var pan = 0f
            for (i in 0 until held) { strength = max(strength, heldStrength[i]); pan += heldPan[i] }
            val n = tune.notes[songIndex % tune.notes.size]
            songIndex++
            var hz = 440f * Math.pow(2.0, ((n - 69) / 12.0)).toFloat()
            while (hz > safety.maxHz) hz *= 0.5f
            val amp = (0.14f + 0.3f * strength) * preset.gain
            synth.note(hz, amp.coerceAtMost(0.75f), pan / held, preset.voice, max(decay, 0.18f))
            held = 0
            return
        }
        val shift = Math.pow(2.0, root / 12.0).toFloat()
        var spare = 0f
        var played = 0
        while (played < PER_STEP && played < held) {
            var best = played
            for (i in played + 1 until held) if (heldStrength[i] > heldStrength[best]) best = i
            swapHeld(played, best)
            played++
        }
        for (i in played until held) spare += heldStrength[i] * 0.15f
        for (i in 0 until played) {
            // A tune's placeholder, left over from a song switched off between
            // steps, is not a pitch.
            if (heldHz[i] < 0f) continue
            var hz = heldHz[i] * shift
            while (hz > safety.maxHz) hz *= 0.5f
            if (hz < MIN_HZ) hz = MIN_HZ
            val strength = (heldStrength[i] + spare / played).coerceAtMost(1f)
            val amp = (0.10f + 0.28f * strength) * preset.gain
            synth.note(hz, amp.coerceAtMost(0.75f), heldPan[i], heldType[i] ?: preset.voice, decay)
        }
        held = 0
    }

    /** Held for the next sixteenth. A full hand folds into its loudest. */
    private fun hold(hz: Float, pan: Float, strength: Float, type: VoiceType) {
        if (held < MAX_HELD) {
            heldHz[held] = hz
            heldPan[held] = pan
            heldStrength[held] = strength.coerceIn(0f, 1f)
            heldType[held] = type
            held++
        } else {
            var loudest = 0
            for (i in 1 until held) if (heldStrength[i] > heldStrength[loudest]) loudest = i
            heldStrength[loudest] = (heldStrength[loudest] + 0.05f).coerceAtMost(1f)
        }
    }

    private fun swapHeld(a: Int, b: Int) {
        if (a == b) return
        val h = heldHz[a]; heldHz[a] = heldHz[b]; heldHz[b] = h
        val p = heldPan[a]; heldPan[a] = heldPan[b]; heldPan[b] = p
        val s = heldStrength[a]; heldStrength[a] = heldStrength[b]; heldStrength[b] = s
        val t = heldType[a]; heldType[a] = heldType[b]; heldType[b] = t
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
        if (groove != null && tune != null && tune.notes.isNotEmpty()) {
            // On a beat, a tune advances once per step it sounds on, not once
            // per event: a sort makes dozens of events a step, and the melody
            // ran through itself in a blur. The event only says "a note now".
            hold(SONG_NOTE, pan, strength, preset.voice)
            return
        }
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
        if (groove != null) {
            hold(hz, pan, strength, type)
            return
        }
        val amp = (0.10f + 0.28f * strength.coerceIn(0f, 1f)) * preset.gain
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

    private companion object {
        const val MAX_HELD = 32
        /** A held note that stands for "the tune's next note". */
        const val SONG_NOTE = -1f
        /** Notes released on one sixteenth, at most: a chord, not a cluster. */
        const val PER_STEP = 3
        /** The kit's level against the sim's own notes. */
        const val GROOVE_LEVEL = 0.55f
        /** Seconds without a note before the kit starts to fade. */
        const val QUIET_AFTER = 1.5f
        const val KICK_HZ = 58f
        const val SNARE_HZ = 1800f
        const val HAT_HZ = 6000f
        const val BASS_CEILING = 220f
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

        // Tunes everyone knows, all long out of copyright: a sim playing one
        // note per event is the oldest trick in the clips this app is built
        // on, and a melody the ear recognises is what makes it land.
        val ODE_TO_JOY = Song(
            "Ode to Joy",
            intArrayOf(
                64, 64, 65, 67, 67, 65, 64, 62, 60, 60, 62, 64, 64, 62, 62,
                64, 64, 65, 67, 67, 65, 64, 62, 60, 60, 62, 64, 62, 60, 60,
                62, 62, 64, 60, 62, 64, 65, 64, 60, 62, 64, 65, 64, 62, 60, 62, 55,
                64, 64, 65, 67, 67, 65, 64, 62, 60, 60, 62, 64, 62, 60, 60
            )
        )
        val TWINKLE = Song(
            "Twinkle twinkle",
            intArrayOf(
                60, 60, 67, 67, 69, 69, 67, 65, 65, 64, 64, 62, 62, 60,
                67, 67, 65, 65, 64, 64, 62, 67, 67, 65, 65, 64, 64, 62,
                60, 60, 67, 67, 69, 69, 67, 65, 65, 64, 64, 62, 62, 60
            )
        )
        val FUR_ELISE = Song(
            "Für Elise",
            intArrayOf(
                76, 75, 76, 75, 76, 71, 74, 72, 69, 60, 64, 69, 71, 64, 68, 71, 72, 64,
                76, 75, 76, 75, 76, 71, 74, 72, 69, 60, 64, 69, 71, 64, 72, 71, 69
            )
        )
        val KOROBEINIKI = Song(
            "Korobeiniki",
            intArrayOf(
                76, 71, 72, 74, 72, 71, 69, 69, 72, 76, 74, 72, 71, 72, 74, 76, 72, 69, 69,
                74, 77, 81, 79, 77, 76, 72, 76, 74, 72, 71, 71, 72, 74, 76, 72, 69, 69
            )
        )
        val GREENSLEEVES = Song(
            "Greensleeves",
            intArrayOf(69, 72, 74, 76, 77, 76, 74, 71, 67, 69, 71, 72, 69, 69, 68, 69, 71, 68, 64)
        )
        val CANON = Song(
            "Canon",
            intArrayOf(78, 76, 74, 73, 71, 69, 71, 73, 74, 73, 71, 69, 67, 66, 67, 64)
        )

        /** The tunes a clip may be given, not counting the scale walks. */
        // Grieg, 1875: the theme that climbs and climbs — made for a build.
        val MOUNTAIN_KING = Song(
            "Mountain King",
            intArrayOf(
                69, 71, 72, 74, 76, 72, 76, 75, 71, 75, 74, 70, 74,
                69, 71, 72, 74, 76, 72, 76, 81, 79, 76, 72, 76, 79
            )
        )
        // Mozart, 1783.
        val TURKISH_MARCH = Song(
            "Turkish March",
            intArrayOf(71, 69, 68, 69, 72, 74, 72, 71, 72, 76, 77, 76, 75, 76, 83, 81, 80, 81, 83, 81, 80, 81, 84)
        )
        // Joplin, 1902.
        val ENTERTAINER = Song(
            "The Entertainer",
            intArrayOf(74, 75, 76, 84, 76, 84, 76, 84, 84, 86, 87, 88, 84, 86, 88, 83, 86, 84)
        )
        // Pierpont, 1857.
        val JINGLE_BELLS = Song(
            "Jingle Bells",
            intArrayOf(76, 76, 76, 76, 76, 76, 76, 79, 72, 74, 76, 77, 77, 77, 77, 77, 76, 76, 76, 76, 74, 74, 76, 74, 79)
        )

        val TUNES = listOf(
            ODE_TO_JOY, TWINKLE, FUR_ELISE, KOROBEINIKI, GREENSLEEVES, CANON,
            MOUNTAIN_KING, TURKISH_MARCH, ENTERTAINER, JINGLE_BELLS
        )

        val ALL = listOf(NONE, ASCENDING, ARPEGGIO, PENTATONIC_WALK) + TUNES
        val NAMES: List<String> = ALL.map { it.name }
        fun byIndex(i: Int): Song = ALL[i.coerceIn(0, ALL.size - 1)]
        fun byName(name: String): Song? = ALL.firstOrNull { it.name == name }
    }
}
