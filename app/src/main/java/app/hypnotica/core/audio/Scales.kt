package app.hypnotica.core.audio

import kotlin.math.pow

/** Note sets that keep a fast sim musical instead of shrill. */
class Scale(val name: String, val semitones: IntArray) {

    /** Map 0..1 onto a pitch, spanning [octaves] starting at [baseHz]. */
    fun hz(t: Float, baseHz: Float, octaves: Float): Float {
        if (semitones.isEmpty()) {
            // Raw frequency: the classic "sound of sorting" sweep.
            return baseHz * 2f.pow(t.coerceIn(0f, 1f) * octaves)
        }
        val steps = (semitones.size * octaves).toInt().coerceAtLeast(1)
        val idx = (t.coerceIn(0f, 1f) * steps).toInt().coerceIn(0, steps - 1)
        val octave = idx / semitones.size
        val degree = idx % semitones.size
        val semi = semitones[degree] + 12 * octave
        return baseHz * 2f.pow(semi / 12f)
    }

    companion object {
        val MINOR_PENTATONIC = Scale("Minor pentatonic", intArrayOf(0, 3, 5, 7, 10))
        val MAJOR_PENTATONIC = Scale("Major pentatonic", intArrayOf(0, 2, 4, 7, 9))
        val MAJOR = Scale("Major", intArrayOf(0, 2, 4, 5, 7, 9, 11))
        val MINOR = Scale("Minor", intArrayOf(0, 2, 3, 5, 7, 8, 10))
        val DORIAN = Scale("Dorian", intArrayOf(0, 2, 3, 5, 7, 9, 10))
        val WHOLE_TONE = Scale("Whole tone", intArrayOf(0, 2, 4, 6, 8, 10))
        val CHROMATIC = Scale("Chromatic", IntArray(12) { it })
        val HARMONICS = Scale("Harmonics", intArrayOf(0, 12, 19, 24, 28, 31))
        val RAW = Scale("Raw frequency", intArrayOf())

        val ALL = listOf(
            MINOR_PENTATONIC, MAJOR_PENTATONIC, MAJOR, MINOR, DORIAN,
            WHOLE_TONE, CHROMATIC, HARMONICS, RAW
        )

        val NAMES: List<String> = ALL.map { it.name }

        fun byName(n: String): Scale = ALL.firstOrNull { it.name == n } ?: MINOR_PENTATONIC
        fun byIndex(i: Int): Scale = ALL[i.coerceIn(0, ALL.size - 1)]
    }
}

/** Waveform of one voice. */
enum class VoiceType { SINE, TRIANGLE, SQUARE, PLUCK, BELL, CLICK, NOISE }

/** A ready-made mapping from events to a sound character. */
data class SoundPreset(
    val name: String,
    val voice: VoiceType,
    val scaleName: String,
    val baseHz: Float,
    val octaves: Float,
    val decay: Float,
    val reverb: Float = 0.18f,
    val silent: Boolean = false,
    /**
     * Level relative to the voice's raw output, so that every preset plays at
     * about the loudness 8-bit does. Measured over four sims through the real
     * synth, Music box played 2.3 times louder than 8-bit and Ambient pad 2.9
     * times — switching preset was a jump in volume, and the louder ones were
     * the ones that read as grating. Equal loudness lets the timbre be judged.
     */
    val gain: Float = 1f
) {
    companion object {
        val CLASSIC = SoundPreset("Classic beeps", VoiceType.SQUARE, "Raw frequency", 120f, 4f, 0.09f, 0.05f, gain = 1.18f)
        val MUSIC_BOX = SoundPreset("Music box", VoiceType.BELL, "Minor pentatonic", 261.6f, 3f, 0.55f, 0.3f, gain = 0.43f)
        val MARIMBA = SoundPreset("Marimba", VoiceType.PLUCK, "Major pentatonic", 196f, 3f, 0.4f, 0.2f, gain = 0.97f)
        val AMBIENT = SoundPreset("Ambient pad", VoiceType.SINE, "Dorian", 130.8f, 3f, 1.4f, 0.5f, gain = 0.34f)
        val EIGHT_BIT = SoundPreset("8-bit", VoiceType.SQUARE, "Major pentatonic", 220f, 3f, 0.12f, 0.0f)
        val TICKS = SoundPreset("Ticks", VoiceType.CLICK, "Chromatic", 440f, 2f, 0.05f, 0.0f, gain = 3f)
        val SILENT = SoundPreset("Silent + haptics", VoiceType.SINE, "Minor pentatonic", 220f, 2f, 0.1f, 0f, silent = true)

        val ALL = listOf(MUSIC_BOX, CLASSIC, MARIMBA, AMBIENT, EIGHT_BIT, TICKS, SILENT)
        val NAMES: List<String> = ALL.map { it.name }

        /** The musical ones, for a remix to choose among: never silence or ticks. */
        val MUSICAL: List<String> = listOf(MUSIC_BOX, MARIMBA, AMBIENT, EIGHT_BIT).map { it.name }

        fun byName(n: String): SoundPreset = ALL.firstOrNull { it.name == n } ?: EIGHT_BIT
        fun byIndex(i: Int): SoundPreset = ALL[i.coerceIn(0, ALL.size - 1)]
    }
}
