package app.hypnotica.core.sim

/**
 * What a sim reports as it runs. Everything audible and every bit of impact juice
 * starts life as one of these, so picture and sound can never drift apart.
 */
enum class EventKind {
    COMPARE, SWAP, WRITE, HIT, CROSS, BIRTH, DEATH, STEP, DONE;

    companion object {
        fun fromName(name: String): EventKind =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: STEP
    }
}

/** One sound/juice event. Values are plain floats so the queue never allocates. */
class EventQueue(private val capacity: Int = 512) {

    private val kinds = IntArray(capacity)
    private val values = FloatArray(capacity)
    private val pans = FloatArray(capacity)
    private val strengths = FloatArray(capacity)
    private var head = 0
    private var tail = 0
    private val lock = Any()

    /** Events dropped because the queue was full; sims use it to fall back to texture sound. */
    @Volatile
    var dropped: Int = 0
        private set

    /** Total emitted since the last reset, used to drive density-based sound at scale. */
    @Volatile
    var emitted: Long = 0L
        private set

    fun emit(kind: EventKind, value: Float, pan: Float = 0f, strength: Float = 1f) {
        synchronized(lock) {
            emitted++
            val next = (tail + 1) % capacity
            if (next == head) {
                dropped++
                return
            }
            kinds[tail] = kind.ordinal
            values[tail] = value
            pans[tail] = pan
            strengths[tail] = strength
            tail = next
        }
    }

    /**
     * Drain into the given consumer. Called from the audio thread.
     * Returns the number of events handed over.
     */
    fun drain(max: Int, out: (EventKind, Float, Float, Float) -> Unit): Int {
        var n = 0
        synchronized(lock) {
            while (head != tail && n < max) {
                val k = EventKind.entries[kinds[head]]
                out(k, values[head], pans[head], strengths[head])
                head = (head + 1) % capacity
                n++
            }
        }
        return n
    }

    fun clear() {
        synchronized(lock) {
            head = 0
            tail = 0
            dropped = 0
            emitted = 0L
        }
    }
}
