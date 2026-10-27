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

    /** Long enough for any real slice, short enough not to freeze a frame. */
    const val SLICE_TIMEOUT_SECONDS = 2L

    private val workers: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)

    private val pool by lazy {
        Executors.newFixedThreadPool(maxOf(1, workers - 1)) { r ->
            Thread(r, "hypnotica-sim").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY + 1
            }
        }
    }

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
        // Local, not shared: an offline export steps a second sim on another
        // thread while the live one is mid-frame.
        val futures = ArrayList<Future<Unit>>(slices)
        val ranges = ArrayList<Int>(slices * 2)
        var start = per
        while (start < count) {
            val from = start
            val to = minOf(count, start + per)
            futures.add(pool.submit(Callable { body(from, to) }))
            ranges.add(from)
            ranges.add(to)
            start += per
        }
        // The calling thread takes the first slice instead of idling.
        body(0, minOf(per, count))

        var failed: ArrayList<Int>? = null
        for (i in futures.indices) {
            try {
                futures[i].get(SLICE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (e: Exception) {
                // A slice that never ran would make the step quietly different
                // from the same step elsewhere, and determinism is what the
                // offline render and the payoff pre-check both rely on. Only
                // the slices that actually failed are re-run: re-running a span
                // that covers a slice which did finish would apply it twice.
                futures[i].cancel(true)
                val list = failed ?: ArrayList<Int>(4).also { failed = it }
                list.add(i)
            }
        }
        val retries = failed
        if (retries != null) {
            // Nothing may still be writing into a span about to be redone.
            for (f in futures) {
                if (f.isDone) continue
                f.cancel(true)
                try {
                    f.get(SLICE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    // Already cancelled; there is nothing further to wait for.
                }
            }
            for (i in retries) body(ranges[i * 2], ranges[i * 2 + 1])
        }
    }
}
