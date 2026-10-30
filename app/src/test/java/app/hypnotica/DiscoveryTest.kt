package app.hypnotica

import app.hypnotica.sims.SimRegistry
import app.hypnotica.ui.Discovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The feed's per-launch order, NEW marks and milestones. */
class DiscoveryTest {

    private val all = SimRegistry.all

    @Test
    fun feedOrderIsAPermutationThatChangesBetweenLaunches() {
        val a = Discovery.feedOrder(all, emptySet(), emptySet(), 1L)
        val b = Discovery.feedOrder(all, emptySet(), emptySet(), 2L)
        assertEquals(all.map { it.id }.toSet(), a.map { it.id }.toSet())
        assertEquals(all.size, a.size)
        assertNotEquals("two launches dealt the same order", a.map { it.id }, b.map { it.id })
        // Same seed, same order: the feed's place survives a revisit.
        assertEquals(a, Discovery.feedOrder(all, emptySet(), emptySet(), 1L))
    }

    @Test
    fun unseenSimsComeFirstOnAverage() {
        // Half the library seen: the first twenty of the deal should lean unseen.
        val seen = all.filterIndexed { i, _ -> i % 2 == 0 }.map { it.id }.toSet()
        var unseenFirst = 0
        var total = 0
        for (seed in 1L..40L) {
            val order = Discovery.feedOrder(all, seen, emptySet(), seed).take(20)
            unseenFirst += order.count { it.id !in seen }
            total += order.size
        }
        assertTrue("only $unseenFirst of $total early sims were unseen", unseenFirst > total * 0.65)
    }

    @Test
    fun feedRarelyRepeatsAFamilyBackToBack() {
        for (seed in 1L..20L) {
            val order = Discovery.feedOrder(all, emptySet(), emptySet(), seed)
            val repeats = order.zipWithNext().count { (x, y) -> x.family == y.family }
            // Only possible at the very end, when one family is all that is left.
            assertTrue("seed $seed: $repeats back-to-back repeats", repeats <= 3)
        }
    }

    @Test
    fun aBigFamilyIsDealtThinlyAtTheStart() {
        val biggest = all.groupBy { it.family }.maxByOrNull { it.value.size }!!
        var early = 0
        val seeds = 1L..30L
        for (seed in seeds) {
            early += Discovery.feedOrder(all, emptySet(), emptySet(), seed).take(20).count { it.family == biggest.key }
        }
        // An even share of twenty would be 20 * size / total; it should be well under that.
        val even = 20.0 * biggest.value.size / all.size * seeds.count()
        assertTrue("${biggest.key}: $early early, even share ${even.toInt()}", early < even * 0.75)
    }

    @Test
    fun newMeansSomethingOnlyOnceAFewHaveBeenOpened() {
        val id = all.first().id
        assertTrue(!Discovery.isNew(id, emptySet()))
        val five = all.drop(1).take(Discovery.NEW_AFTER).map { it.id }.toSet()
        assertTrue(Discovery.isNew(id, five))
        assertTrue(!Discovery.isNew(id, five + id))
    }

    @Test
    fun finishingAShelfIsAMilestone() {
        val shelf = all.first().shelf
        val members = all.filter { it.shelf == shelf }
        val last = members.last()
        val before = members.dropLast(1).map { it.id }.toSet()
        val line = Discovery.milestone(before, last.id, all)
        assertTrue(line, line!!.contains(shelf.title) && line.contains("${members.size}"))
        // Opening it again says nothing.
        assertNull(Discovery.milestone(before + last.id, last.id, all))
    }

    @Test
    fun roundNumbersAndTheWholeLibrary() {
        // Nine opened from across the shelves, the tenth is a round number
        // unless it happens to finish a shelf.
        val spread = all.groupBy { it.shelf }.values.flatMap { it.take(2) }.take(9)
        val tenth = all.first { it !in spread && all.count { o -> o.shelf == it.shelf } > 3 }
        val line = Discovery.milestone(spread.map { it.id }.toSet(), tenth.id, all)
        assertEquals("10 of ${all.size} sims found", line)

        val allButOne = all.dropLast(1).map { it.id }.toSet()
        assertEquals("Every sim found — all ${all.size}", Discovery.milestone(allButOne, all.last().id, all))
    }
}
