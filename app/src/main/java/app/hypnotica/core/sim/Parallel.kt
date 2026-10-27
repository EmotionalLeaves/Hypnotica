package app.hypnotica.core.sim

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Splits one tight loop across the phone's cores.
 *
 * A particle field is the one place where the work is big enough and the
 * per-item work independent enough for this to pay: each worker owns a slice of
 * the arrays and never touches another slice, so there is no locking and the
 * result is still deterministic.
 *
 * Below [MIN_ITEMS] the loop runs inline, because handing work to a pool costs
 * more than the loop does.
 */
object Parallel {

    const val MIN_ITEMS = 20_000

    private val workers: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)

    private val pool by lazy {
        Executors.newFixedThreadPool(maxOf(1, workers - 1)) { r ->
            Thread(r, "hypnotica-sim").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY + 1
            }
        }
    }

    private val futures = ArrayList<Future<Unit>>(8)

    /** True when a range of this size is worth splitting up. */
    fun worthwhile(count: Int): Boolean = workers > 1 && count >= MIN_ITEMS

    /**
     * Runs [body] over contiguous slices of [0, count). Blocks until every slice
     * is done, so the caller sees the same state it would from a plain loop.
     */
    fun forRange(count: Int, body: (from: Int, to: Int) -> Unit) {
        if (!worthwhile(count)) {
            body(0, count)
            return
        }
        val slices = workers
        val per = (count + slices - 1) / slices
        futures.clear()
        var start = per
        while (start < count) {
            val from = start
            val to = minOf(count, start + per)
            futures.add(pool.submit(Callable { body(from, to) }))
            start += per
        }
        // The calling thread takes the first slice instead of idling.
        body(0, minOf(per, count))
        for (f in futures) {
            try {
                f.get(2, TimeUnit.SECONDS)
            } catch (e: Exception) {
                // A worker that fails must not take the frame down; the slice it
                // owned simply does not advance this step.
            }
        }
        futures.clear()
    }
}
