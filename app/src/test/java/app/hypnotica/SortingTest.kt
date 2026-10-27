package app.hypnotica

import app.hypnotica.core.laws.Laws
import app.hypnotica.core.recipe.ParamValues
import app.hypnotica.core.sim.EventQueue
import app.hypnotica.core.sim.Rng
import app.hypnotica.core.sim.SimEnv
import app.hypnotica.core.sim.SortArray
import app.hypnotica.sims.sorting.Algorithms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every sorting algorithm must actually sort, and must terminate.
 *
 * These run on the JVM in CI, which is the only way a phone-only project finds
 * out that a state machine has an off-by-one before the APK is installed.
 */
class SortingTest {

    private fun env(seed: Long = 42L): SimEnv =
        SimEnv(Rng(seed), Laws.OUR_UNIVERSE, ParamValues(emptyList()), EventQueue(4096))

    /** A sort that needs more operations than this has a stuck state machine. */
    private val opLimit = 20_000_000L

    private fun runSort(name: String, input: FloatArray): Pair<FloatArray, Long> {
        val e = env()
        val values = input.copyOf()
        val array = SortArray(values, e)
        val algo = Algorithms.byTitle(name)
        algo.reset(values.size)
        var ops = 0L
        // Drain the queue as we go so a long sort cannot stall on a full queue.
        while (algo.advance(array)) {
            ops++
            if (ops % 2048 == 0L) e.events.drain(4096) { _, _, _, _ -> }
            if (ops > opLimit) break
        }
        return values to ops
    }

    private fun shapes(n: Int): List<Pair<String, FloatArray>> {
        val rng = Rng(7)
        return listOf(
            "random" to FloatArray(n) { rng.nextFloat() },
            "reversed" to FloatArray(n) { (n - 1 - it).toFloat() / n },
            "sorted" to FloatArray(n) { it.toFloat() / n },
            "repeats" to FloatArray(n) { ((it % 5).toFloat()) / 5f },
            "one value" to FloatArray(n) { 0.5f }
        )
    }

    @Test
    fun everyAlgorithmSorts() {
        for (name in Algorithms.NAMES) {
            for ((shapeName, input) in shapes(64)) {
                val (out, ops) = runSort(name, input)
                val expected = input.copyOf().also { it.sort() }
                assertTrue("$name did not terminate on $shapeName (ops=$ops)", ops < opLimit)
                for (i in out.indices) {
                    assertEquals(
                        "$name produced the wrong order on $shapeName at index $i",
                        expected[i], out[i], 1e-6f
                    )
                }
            }
        }
    }

    @Test
    fun algorithmsHandleTinyArrays() {
        for (name in Algorithms.NAMES) {
            for (n in 0..3) {
                val rng = Rng(3)
                val input = FloatArray(n) { rng.nextFloat() }
                val (out, _) = runSort(name, input)
                val expected = input.copyOf().also { it.sort() }
                for (i in out.indices) {
                    assertEquals("$name failed at n=$n", expected[i], out[i], 1e-6f)
                }
            }
        }
    }

    @Test
    fun largeArrayStillSorts() {
        // The fast sorts have to cope with a size a phone would actually run.
        for (name in listOf("Quicksort", "Merge sort", "Heap sort", "Radix sort (LSD)")) {
            val rng = Rng(11)
            val input = FloatArray(5000) { rng.nextFloat() }
            val (out, _) = runSort(name, input)
            for (i in 1 until out.size) {
                assertTrue("$name left $i out of order", out[i] >= out[i - 1] - 1e-6f)
            }
        }
    }
}
