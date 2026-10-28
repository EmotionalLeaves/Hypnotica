package app.hypnotica.core.sim

/**
 * An array that reports every read, compare and write.
 *
 * A sorting Recipe is written as ordinary code against this; the visuals and the
 * sound come out of it for free, which is why adding a new sort is a few dozen
 * lines rather than a new sim.
 */
class SortArray(val values: FloatArray, private val env: SimEnv) {

    val size: Int get() = values.size

    var comparisons = 0L
        private set
    var writes = 0L
        private set

    /** Reads that are not comparisons: a radix sort's digit look-ups. */
    var reads = 0L
        private set

    /** Everything this array has been asked to do, for paying a race fairly. */
    val operations: Long get() = comparisons + writes + reads

    /** Indices touched by the last operation; the renderer highlights them. */
    var lastA = -1
        private set
    var lastB = -1
        private set

    /** Index the verify sweep has reached, or -1 while sorting. */
    var sweep = -1

    private fun pan(i: Int): Float =
        if (size <= 1) 0f else (i.toFloat() / (size - 1) * 2f - 1f) * 0.7f

    fun get(i: Int): Float = values[i]

    /** A read that is worth seeing and hearing but is not a comparison. */
    fun read(i: Int): Float {
        reads++
        lastA = i
        env.emit(EventKind.COMPARE, values[i], pan(i), 0.25f)
        return values[i]
    }

    fun compare(i: Int, j: Int): Int {
        comparisons++
        lastA = i
        lastB = j
        env.emit(EventKind.COMPARE, values[i], pan(i), 0.35f)
        return values[i].compareTo(values[j])
    }

    /** Compare a value already in hand against a slot, as insertion sort does. */
    fun compareValue(value: Float, j: Int): Int {
        comparisons++
        lastB = j
        env.emit(EventKind.COMPARE, value, pan(j), 0.3f)
        return value.compareTo(values[j])
    }

    fun swap(i: Int, j: Int) {
        val t = values[i]
        values[i] = values[j]
        values[j] = t
        writes += 2
        lastA = i
        lastB = j
        env.emit(EventKind.SWAP, values[i], pan(i), 0.7f)
    }

    fun write(i: Int, v: Float) {
        values[i] = v
        writes++
        lastA = i
        env.emit(EventKind.WRITE, v, pan(i), 0.5f)
    }

    fun clearHighlight() {
        lastA = -1
        lastB = -1
    }

    fun isSorted(): Boolean {
        for (i in 1 until size) if (values[i] < values[i - 1]) return false
        return true
    }
}

/**
 * A sorting algorithm driven one primitive operation at a time, so a sort of any
 * size can be paused, slowed down, reversed out of and resumed.
 */
interface SortAlgorithm {
    val title: String
    fun reset(n: Int)
    /** Perform one comparison or one swap. Returns false when the array is sorted. */
    fun advance(a: SortArray): Boolean
    /** Rough operation count, used to pace a run to a watchable length. */
    fun estimatedOps(n: Int): Long = n.toLong() * n.toLong() / 2L
}
