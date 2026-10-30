package app.hypnotica.core.audio

/**
 * A beat under the sim, and the grid its notes land on.
 *
 * The clips this app is modelled on are never just the sound of the thing:
 * there is a track under them, and the marble or the bar lands on its beat.
 * Without one a sort is a stream of notes at whatever moments the algorithm
 * happens to compare, which is texture, not music.
 *
 * A groove does three things. It quantises: a sim's notes wait for the next
 * sixteenth and go out together, a chord on the grid. It harmonises: every
 * bar the whole scale moves to the next root of a four-chord loop, so the same
 * sort walks through a progression. And it plays a quiet kit and bass under
 * that — only while the sim is making sound, so a still or paused sim is
 * silent rather than a backing track with nothing on it.
 */
data class Groove(
    val name: String,
    val bpm: Float,
    /** Semitones above the base pitch for each bar's chord, looped. */
    val roots: IntArray,
    /** Which of the sixteen steps in a bar get a kick, a snare, a hat, a bass note. */
    val kick: Set<Int>,
    val snare: Set<Int>,
    val hat: Set<Int>,
    val bass: Set<Int>
) {
    /** Seconds per sixteenth note. */
    val stepSeconds: Float get() = 60f / bpm / 4f

    fun rootAt(bar: Long): Int = roots[((bar % roots.size) + roots.size).toInt() % roots.size]

    fun withTempo(bpm: Float) = copy(bpm = bpm.coerceIn(70f, 160f))

    override fun equals(other: Any?): Boolean = this === other || (other is Groove && name == other.name && bpm == other.bpm)

    override fun hashCode(): Int = name.hashCode() * 31 + bpm.hashCode()

    companion object {
        /** I–V–vi–IV: the four chords under half the songs anyone knows. */
        private val POP = intArrayOf(0, 7, 9, 5)
        /** vi–IV–I–V, the same chords started sadder. */
        private val SAD = intArrayOf(9, 5, 0, 7)
        /** I–bVII–IV–I: open, a little rock. */
        private val OPEN = intArrayOf(0, 10, 5, 0)
        /** i–VI–III–VII: the minor loop under most of what trends. */
        private val MINOR = intArrayOf(0, 8, 3, 10)

        val FOUR = Groove(
            "Four on the floor", 118f, POP,
            kick = setOf(0, 4, 8, 12), snare = setOf(4, 12), hat = setOf(2, 6, 10, 14),
            bass = setOf(0, 3, 6, 8, 11, 14)
        )
        val HALF = Groove(
            "Half-time", 84f, SAD,
            kick = setOf(0, 10), snare = setOf(8), hat = setOf(0, 2, 4, 6, 8, 10, 12, 14),
            bass = setOf(0, 7, 10)
        )
        val BREAK = Groove(
            "Breakbeat", 100f, OPEN,
            kick = setOf(0, 6, 10), snare = setOf(4, 12, 15), hat = setOf(0, 2, 4, 6, 8, 10, 12, 14),
            bass = setOf(0, 6, 10, 13)
        )
        val DRIFT = Groove(
            "Drift", 92f, POP,
            kick = setOf(0), snare = emptySet(), hat = setOf(6, 14),
            bass = setOf(0, 8)
        )

        /** Half-time kick and snare under hats on every sixteenth; the bass follows the kick. */
        val TRAP = Groove(
            "Trap", 140f, MINOR,
            kick = setOf(0, 7, 10), snare = setOf(8),
            hat = (0 until 16).toSet(),
            bass = setOf(0, 7, 10)
        )
        /** The dembow: kick on the four, the snare's tresillo pull against it. */
        val DEMBOW = Groove(
            "Dembow", 96f, SAD,
            kick = setOf(0, 4, 8, 12), snare = setOf(3, 6, 11, 14), hat = setOf(2, 10),
            bass = setOf(0, 3, 8, 11)
        )

        /**
         * Drum and bass: the two-step — kick on one and the and of three,
         * snare on two and four — at 160, its hats on the eighths.
         */
        val DNB = Groove(
            "Drum & bass", 160f, MINOR,
            kick = setOf(0, 10), snare = setOf(4, 12), hat = setOf(0, 2, 4, 6, 8, 10, 12, 14),
            bass = setOf(0, 10)
        )
        /**
         * Bossa nova: the son clave's 3–2 on the rim (steps 0, 3, 6 · 10, 12)
         * over a kick on one and three, round ii–V–I–vi.
         */
        val BOSSA = Groove(
            "Bossa", 128f, intArrayOf(2, 7, 0, 9),
            kick = setOf(0, 8), snare = setOf(0, 3, 6, 10, 12), hat = setOf(2, 6, 10, 14),
            bass = setOf(0, 6, 8, 14)
        )

        /** Lo-fi: slow and lazy, the kick late in the bar, round ii–V–I–vi. */
        val LOFI = Groove(
            "Lo-fi", 78f, intArrayOf(2, 7, 0, 9),
            kick = setOf(0, 7, 10), snare = setOf(4, 12), hat = setOf(0, 2, 4, 6, 8, 10, 12, 14),
            bass = setOf(0, 7, 10)
        )
        /** Funk: a busy, syncopated kick and the sixteenths on the hat. */
        val FUNK = Groove(
            "Funk", 104f, intArrayOf(0, 0, 5, 7),
            kick = setOf(0, 3, 10), snare = setOf(4, 12), hat = (0 until 16).toSet(),
            bass = setOf(0, 3, 6, 10, 11, 14)
        )
        /** Reggae's one-drop: nothing on one, kick and snare together on three. */
        val ONE_DROP = Groove(
            "One drop", 76f, intArrayOf(0, 5, 0, 7),
            kick = setOf(8), snare = setOf(8), hat = setOf(2, 6, 10, 14),
            bass = setOf(0, 3, 6, 10)
        )
        /** Synthwave: four on the floor, an open minor loop, the hats on the off-beats. */
        val SYNTHWAVE = Groove(
            "Synthwave", 108f, MINOR,
            kick = setOf(0, 4, 8, 12), snare = setOf(4, 12), hat = setOf(2, 6, 10, 14),
            bass = setOf(0, 2, 4, 6, 8, 10, 12, 14)
        )

        val ALL = listOf(FOUR, HALF, BREAK, DRIFT, TRAP, DEMBOW, DNB, BOSSA, LOFI, FUNK, ONE_DROP, SYNTHWAVE)
        val NAMES = listOf("Off") + ALL.map { it.name }

        fun byName(name: String): Groove? = ALL.firstOrNull { it.name == name }
    }
}
