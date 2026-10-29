package app.hypnotica.sims.sorting

import app.hypnotica.core.sim.SortAlgorithm
import app.hypnotica.core.sim.SortArray
import kotlin.math.max

/**
 * Every sort here is a resumable state machine: one call, one primitive operation.
 *
 * That is what lets the same code animate 200 bars and 100,000 bars, run at 0.1x
 * or 10x, and be scrubbed in Studio, without ever materialising a trace.
 */

class BubbleSort : SortAlgorithm {
    override val title = "Bubble sort"
    private var i = 0
    private var j = 0
    private var n = 0
    private var swappedThisPass = false

    override fun reset(n: Int) {
        this.n = n; i = 0; j = 0; swappedThisPass = false
    }

    override fun advance(a: SortArray): Boolean {
        if (i >= n - 1) return false
        if (j >= n - 1 - i) {
            if (!swappedThisPass) return false
            i++; j = 0; swappedThisPass = false
            return i < n - 1
        }
        if (a.compare(j, j + 1) > 0) {
            a.swap(j, j + 1)
            swappedThisPass = true
        }
        j++
        return true
    }
}

class CocktailSort : SortAlgorithm {
    override val title = "Cocktail shaker sort"
    private var lo = 0
    private var hi = 0
    private var pos = 0
    private var forward = true
    private var swapped = false

    override fun reset(n: Int) {
        lo = 0; hi = n - 1; pos = 0; forward = true; swapped = false
    }

    override fun advance(a: SortArray): Boolean {
        if (lo >= hi) return false
        if (forward) {
            if (pos >= hi) {
                if (!swapped) return false
                hi--; forward = false; pos = hi; swapped = false
                return lo < hi
            }
            if (a.compare(pos, pos + 1) > 0) { a.swap(pos, pos + 1); swapped = true }
            pos++
        } else {
            if (pos <= lo) {
                if (!swapped) return false
                lo++; forward = true; pos = lo; swapped = false
                return lo < hi
            }
            if (a.compare(pos - 1, pos) > 0) { a.swap(pos - 1, pos); swapped = true }
            pos--
        }
        return true
    }
}

class InsertionSort : SortAlgorithm {
    override val title = "Insertion sort"
    private var i = 1
    private var j = 0
    private var n = 0
    private var holding = false
    private var value = 0f

    override fun reset(n: Int) {
        this.n = n; i = 1; j = 0; holding = false
    }

    override fun advance(a: SortArray): Boolean {
        if (i >= n) return false
        if (!holding) {
            value = a.get(i)
            j = i - 1
            holding = true
            return true
        }
        if (j >= 0 && a.compareValue(value, j) < 0) {
            a.write(j + 1, a.get(j))
            j--
            return true
        }
        a.write(j + 1, value)
        holding = false
        i++
        return true
    }
}

class SelectionSort : SortAlgorithm {
    override val title = "Selection sort"
    private var i = 0
    private var j = 0
    private var minIdx = 0
    private var n = 0

    override fun reset(n: Int) {
        this.n = n; i = 0; j = 1; minIdx = 0
    }

    override fun advance(a: SortArray): Boolean {
        if (i >= n - 1) return false
        if (j >= n) {
            if (minIdx != i) a.swap(i, minIdx)
            i++
            minIdx = i
            j = i + 1
            return i < n - 1
        }
        if (a.compare(j, minIdx) < 0) minIdx = j
        j++
        return true
    }
}

class GnomeSort : SortAlgorithm {
    override val title = "Gnome sort"
    private var pos = 1
    private var n = 0

    override fun reset(n: Int) {
        this.n = n; pos = 1
    }

    override fun advance(a: SortArray): Boolean {
        if (pos >= n) return false
        if (pos == 0) { pos = 1; return true }
        if (a.compare(pos - 1, pos) > 0) {
            a.swap(pos - 1, pos)
            pos--
        } else pos++
        return pos < n
    }
}

class CombSort : SortAlgorithm {
    override val title = "Comb sort"
    private var gap = 0
    private var i = 0
    private var n = 0
    private var swapped = false

    override fun reset(n: Int) {
        this.n = n; gap = n; i = 0; swapped = false
    }

    override fun advance(a: SortArray): Boolean {
        if (n < 2) return false
        if (i + gap >= n) {
            // End of a pass: shrink the gap, and stop once a gap-1 pass is clean.
            if (gap <= 1 && !swapped) return false
            gap = max(1, (gap / 1.3f).toInt())
            i = 0
            swapped = false
            return true
        }
        if (a.compare(i, i + gap) > 0) { a.swap(i, i + gap); swapped = true }
        i++
        return true
    }

    override fun estimatedOps(n: Int): Long = n.toLong() * 14L
}

class ShellSort : SortAlgorithm {
    override val title = "Shell sort"
    private var gap = 0
    private var i = 0
    private var j = 0
    private var n = 0
    private var holding = false
    private var value = 0f

    override fun reset(n: Int) {
        this.n = n
        gap = 1
        while (gap < n / 3) gap = gap * 3 + 1
        i = gap
        holding = false
    }

    override fun advance(a: SortArray): Boolean {
        if (gap < 1) return false
        if (i >= n) {
            gap /= 3
            if (gap < 1) return false
            i = gap
            holding = false
            return true
        }
        if (!holding) {
            value = a.get(i)
            j = i
            holding = true
            return true
        }
        if (j >= gap && a.compareValue(value, j - gap) < 0) {
            a.write(j, a.get(j - gap))
            j -= gap
            return true
        }
        a.write(j, value)
        holding = false
        i++
        return true
    }

    override fun estimatedOps(n: Int): Long = n.toLong() * 20L
}

class OddEvenSort : SortAlgorithm {
    override val title = "Odd-even sort"
    private var pos = 0
    private var n = 0
    private var phaseOdd = false
    private var swappedInRound = false
    private var round = 0

    override fun reset(n: Int) {
        this.n = n; pos = 0; phaseOdd = false; swappedInRound = false; round = 0
    }

    override fun advance(a: SortArray): Boolean {
        if (n < 2 || round >= n) return false
        if (pos + 1 >= n) {
            if (phaseOdd) {
                // An even pass and an odd pass with no swap means it is sorted.
                if (!swappedInRound) return false
                round++
                swappedInRound = false
                phaseOdd = false
                pos = 0
            } else {
                phaseOdd = true
                pos = 1
            }
            return round < n
        }
        if (a.compare(pos, pos + 1) > 0) {
            a.swap(pos, pos + 1)
            swappedInRound = true
        }
        pos += 2
        return true
    }
}

class QuickSort : SortAlgorithm {
    override val title = "Quicksort"
    private val stackLo = IntArray(512)
    private val stackHi = IntArray(512)
    private var sp = 0
    private var lo = 0
    private var hi = -1
    private var i = 0
    private var j = 0
    private var partitioning = false
    private var ties = 0

    override fun reset(n: Int) {
        sp = 0
        partitioning = false
        if (n > 1) {
            stackLo[sp] = 0; stackHi[sp] = n - 1; sp++
        }
        lo = 0; hi = -1
    }

    override fun advance(a: SortArray): Boolean {
        if (!partitioning) {
            if (sp == 0) return false
            sp--
            lo = stackLo[sp]
            hi = stackHi[sp]
            if (lo >= hi) return true
            // Median of three for the pivot, moved to the end: the last
            // element alone made already-sorted and reversed input quadratic,
            // and "Almost sorted" took three minutes.
            if (hi - lo >= 2) {
                val mid = (lo + hi) ushr 1
                if (a.compare(mid, lo) < 0) a.swap(mid, lo)
                if (a.compare(hi, lo) < 0) a.swap(hi, lo)
                if (a.compare(hi, mid) < 0) a.swap(hi, mid)
                a.swap(mid, hi)
            }
            i = lo - 1
            j = lo
            ties = 0
            partitioning = true
            return true
        }
        if (j < hi) {
            // Lomuto partition against the last element. Keys equal to the
            // pivot go left and right by turns, so a run of repeats is split
            // in half rather than all piled on one side.
            val c = a.compare(j, hi)
            if (c < 0 || (c == 0 && (ties++ and 1) == 0)) {
                i++
                if (i != j) a.swap(i, j)
            }
            j++
            return true
        }
        i++
        if (i != hi) a.swap(i, hi)
        partitioning = false
        val p = i
        val leftSize = p - 1 - lo
        val rightSize = hi - (p + 1)
        // Push the larger side first and pop the smaller one next, so the stack
        // stays O(log n) deep even on an unlucky pivot run.
        if (leftSize >= rightSize) {
            pushRange(lo, p - 1)
            pushRange(p + 1, hi)
        } else {
            pushRange(p + 1, hi)
            pushRange(lo, p - 1)
        }
        return true
    }

    private fun pushRange(l: Int, h: Int) {
        if (h > l && sp < stackLo.size) {
            stackLo[sp] = l
            stackHi[sp] = h
            sp++
        }
    }

    override fun estimatedOps(n: Int): Long {
        var l = 1
        var v = n
        while (v > 1) { v /= 2; l++ }
        return n.toLong() * l.toLong() * 2L
    }
}

class HeapSort : SortAlgorithm {
    override val title = "Heap sort"
    private var n = 0
    private var heapSize = 0
    private var buildIndex = 0
    private var building = true
    private var sifting = false
    private var siftRoot = 0

    override fun reset(n: Int) {
        this.n = n
        heapSize = n
        buildIndex = n / 2 - 1
        building = true
        sifting = false
    }

    override fun advance(a: SortArray): Boolean {
        if (sifting) {
            val l = 2 * siftRoot + 1
            val r = l + 1
            var largest = siftRoot
            if (l < heapSize && a.compare(l, largest) > 0) largest = l
            if (r < heapSize && a.compare(r, largest) > 0) largest = r
            if (largest != siftRoot) {
                a.swap(siftRoot, largest)
                siftRoot = largest
            } else sifting = false
            return true
        }
        if (building) {
            if (buildIndex < 0) { building = false; return true }
            siftRoot = buildIndex
            sifting = true
            buildIndex--
            return true
        }
        if (heapSize <= 1) return false
        a.swap(0, heapSize - 1)
        heapSize--
        siftRoot = 0
        sifting = true
        return true
    }

    override fun estimatedOps(n: Int): Long {
        var l = 1
        var v = n
        while (v > 1) { v /= 2; l++ }
        return n.toLong() * l.toLong() * 3L
    }
}

class MergeSort : SortAlgorithm {
    override val title = "Merge sort"

    private companion object {
        const val SELECT = 0
        const val MERGE = 1
        const val COPY = 2
    }

    private var n = 0
    private var width = 1
    private var lo = 0
    private var mid = 0
    private var hi = 0
    private var i = 0
    private var j = 0
    private var k = 0
    private var copyIndex = 0
    private var state = SELECT
    private var buffer = FloatArray(0)

    override fun reset(n: Int) {
        this.n = n
        width = 1
        lo = 0
        state = SELECT
        buffer = FloatArray(maxOf(1, n))
    }

    override fun advance(a: SortArray): Boolean {
        if (n < 2 || width >= n) return false
        when (state) {
            SELECT -> {
                if (lo >= n) {
                    width *= 2
                    lo = 0
                    return width < n
                }
                mid = minOf(lo + width, n)
                hi = minOf(lo + 2 * width, n)
                if (mid >= hi) {
                    // Nothing to merge in this window.
                    lo += 2 * width
                    return true
                }
                i = lo; j = mid; k = 0
                state = MERGE
            }
            MERGE -> {
                if (i < mid && j < hi) {
                    if (a.compare(i, j) <= 0) buffer[k++] = a.get(i++)
                    else buffer[k++] = a.get(j++)
                } else if (i < mid) {
                    buffer[k++] = a.get(i++)
                } else if (j < hi) {
                    buffer[k++] = a.get(j++)
                } else {
                    state = COPY
                    copyIndex = 0
                }
            }
            COPY -> {
                if (copyIndex >= k) {
                    lo += 2 * width
                    state = SELECT
                    return true
                }
                a.write(lo + copyIndex, buffer[copyIndex])
                copyIndex++
            }
        }
        return true
    }

    override fun estimatedOps(n: Int): Long {
        var l = 1
        var v = n
        while (v > 1) { v /= 2; l++ }
        return n.toLong() * l.toLong() * 3L
    }
}

class RadixLsdSort : SortAlgorithm {
    override val title = "Radix sort (LSD)"

    private companion object {
        const val SNAPSHOT = 0
        const val COUNT = 1
        const val PREFIX = 2
        const val WRITE_BACK = 4
    }

    private var n = 0
    private var maxValue = 0
    private var exp = 1
    private var state = SNAPSHOT
    private var idx = 0
    private val counts = IntArray(10)
    private var output = IntArray(0)
    private var outputValues = FloatArray(0)
    private var keys = IntArray(0)
    private var originals = FloatArray(0)

    override fun reset(n: Int) {
        this.n = n
        exp = 1
        state = SNAPSHOT
        idx = 0
        output = IntArray(maxOf(1, n))
        outputValues = FloatArray(maxOf(1, n))
        keys = IntArray(maxOf(1, n))
        originals = FloatArray(maxOf(1, n))
        maxValue = 0
        java.util.Arrays.fill(counts, 0)
    }

    override fun advance(a: SortArray): Boolean {
        if (n < 2) return false
        when (state) {
            SNAPSHOT -> {
                // Keys drive the digit passes; the original floats ride along so
                // the array ends up permuted, never rounded.
                //
                // The key is the raw bit pattern of the (shifted, non-negative)
                // float, which orders exactly like the float itself. Scaling to a
                // fixed number of buckets instead would make distinct values
                // collide and leave the array only nearly sorted.
                var minValue = Float.MAX_VALUE
                for (p in 0 until n) {
                    val value = a.get(p)
                    originals[p] = value
                    if (value < minValue) minValue = value
                }
                val shift = if (minValue < 0f) -minValue else 0f
                var minKey = Int.MAX_VALUE
                for (p in 0 until n) {
                    val key = java.lang.Float.floatToIntBits(originals[p] + shift) and 0x7FFFFFFF
                    keys[p] = key
                    if (key < minKey) minKey = key
                }
                // Measured from the smallest key, which keeps the order: every
                // float in 0.001..1 has a bit pattern near 1.0e9, so the top
                // digit or two were the same for all of them and the last
                // passes rewrote an already sorted array — a fifth of the run
                // spent on a finished picture before the payoff.
                for (p in 0 until n) {
                    keys[p] -= minKey
                    if (keys[p] > maxValue) maxValue = keys[p]
                }
                if (maxValue == 0) return false
                state = COUNT
                idx = 0
            }
            COUNT -> {
                if (idx >= n) {
                    state = PREFIX
                    idx = 1
                    return true
                }
                counts[(keys[idx] / exp) % 10]++
                a.read(idx)
                idx++
            }
            PREFIX -> {
                if (idx >= 10) {
                    // Placing into the scratch buffer touches nothing on
                    // screen, so it is done at once: as n steps of its own it
                    // was a third of every pass with the bars frozen and no
                    // sound, the whole array waiting on work you cannot see.
                    for (p in n - 1 downTo 0) {
                        val d = (keys[p] / exp) % 10
                        counts[d]--
                        output[counts[d]] = keys[p]
                        outputValues[counts[d]] = originals[p]
                    }
                    state = WRITE_BACK
                    idx = 0
                    return true
                }
                counts[idx] += counts[idx - 1]
                idx++
            }
            WRITE_BACK -> {
                if (idx >= n) {
                    exp *= 10
                    if (exp > maxValue) return false
                    java.util.Arrays.fill(counts, 0)
                    state = COUNT
                    idx = 0
                    return true
                }
                keys[idx] = output[idx]
                originals[idx] = outputValues[idx]
                a.write(idx, outputValues[idx])
                idx++
            }
        }
        return true
    }

    /**
     * A read and a write per element per pass: eight passes for random floats
     * in 0..1, ten when the data includes zero (reversed, sawtooth).
     */
    override fun estimatedOps(n: Int): Long = n.toLong() * 20L
}

object Algorithms {
    fun all(): List<SortAlgorithm> = listOf(
        QuickSort(), MergeSort(), HeapSort(), BubbleSort(), InsertionSort(),
        SelectionSort(), CocktailSort(), GnomeSort(), CombSort(), ShellSort(),
        OddEvenSort(), RadixLsdSort()
    )

    val NAMES: List<String> = all().map { it.title }

    fun byIndex(i: Int): SortAlgorithm = all()[i.coerceIn(0, NAMES.size - 1)]

    fun byTitle(t: String): SortAlgorithm =
        all().firstOrNull { it.title == t } ?: QuickSort()
}
