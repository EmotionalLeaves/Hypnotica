package app.hypnotica.sims.sorting

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.recipe.ParamInput
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.EventQueue
import app.hypnotica.core.sim.Shelf
import app.hypnotica.core.sim.Sim
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.sim.SimEnv
import app.hypnotica.core.sim.SortAlgorithm
import app.hypnotica.core.sim.SortArray
import app.hypnotica.core.sim.TrapFormat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Four sorts race on the same shuffled bars, one above the other, each in its
 * own colour. Pick one before it starts.
 *
 * Every lane gets the same number of operations a second — compares and
 * writes, counted — so the race is between the algorithms and nothing else,
 * and the order is often not the one you would bet on: on bars that are
 * nearly sorted, insertion sort beats quicksort. Lanes are stacked, so the
 * race fills an upright phone.
 */
class SortRaceSim : Sim {

    private val lanes = ArrayList<SortArray>()
    private val algos = ArrayList<SortAlgorithm>()
    private var running = BooleanArray(0)
    private var debt = LongArray(LANES)
    private var finishedAt = FloatArray(0)
    private var place = IntArray(0)
    private var placed = 0
    private var n = 80
    private var carry = 0f
    private var opsPerSecond = 100f
    private var elapsed = 0f
    private var payoffFrame = false
    private var allDoneAt = -1f
    private var firstAt = 0f

    override fun setup(env: SimEnv) {
        val p = env.params
        n = p.i("bars", 80).coerceIn(10, 400)
        debt = LongArray(LANES)
        val data = p.choiceIndex("data")
        val start = FloatArray(n) { (it + 1f) / n }
        shape(start, data, env)

        // Four different algorithms, drawn fresh each race.
        val pool = Algorithms.all().toMutableList()
        lanes.clear(); algos.clear()
        repeat(LANES) {
            val a = pool.removeAt(env.rng.int(0, pool.size))
            a.reset(n)
            algos.add(a)
            lanes.add(SortArray(start.copyOf(), env))
        }
        running = BooleanArray(LANES) { true }
        finishedAt = FloatArray(LANES) { -1f }
        place = IntArray(LANES)
        placed = 0
        carry = 0f
        elapsed = 0f
        payoffFrame = false
        allDoneAt = -1f

        // Pace it so the winner crosses the line about nine seconds in. The
        // race is run once, silently, to find out how many operations that is.
        val quiet = SimEnv(env.rng, env.laws, env.params, EventQueue(8))
        var fastest = Long.MAX_VALUE
        for (k in 0 until LANES) {
            val a = Algorithms.byTitle(algos[k].title)
            a.reset(n)
            val arr = SortArray(start.copyOf(), quiet)
            var calls = 0L
            while (calls < OPS_CAP && a.advance(arr)) calls++
            quiet.events.clear()
            // Counted the way the race is paid: compares and writes.
            val ops = arr.comparisons + arr.writes
            if (ops < fastest) fastest = ops
        }
        opsPerSecond = max(20f, fastest / WINNER_SECONDS)
    }

    /** Arrange the bars: shuffled, nearly sorted, backwards, or a few values repeated. */
    private fun shape(v: FloatArray, data: Int, env: SimEnv) {
        when (data) {
            1 -> repeat(max(1, v.size / 12)) {
                val i = env.rng.int(0, v.size); val j = env.rng.int(0, v.size)
                val t = v[i]; v[i] = v[j]; v[j] = t
            }
            2 -> v.reverse()
            3 -> {
                for (i in v.indices) v[i] = (1 + (i * 5 / v.size)) / 5f
                for (i in v.size - 1 downTo 1) {
                    val j = env.rng.int(0, i + 1); val t = v[i]; v[i] = v[j]; v[j] = t
                }
            }
            else -> for (i in v.size - 1 downTo 1) {
                val j = env.rng.int(0, i + 1); val t = v[i]; v[i] = v[j]; v[j] = t
            }
        }
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        // Once there is a winner the rest are hurried home, faster and
        // faster, so the race ends with every lane sorted rather than with an
        // n-squared sort still a tenth of the way through.
        val hurry = if (placed == 0) 1f else min(MAX_HURRY, Math.pow(HURRY, (elapsed - firstAt).toDouble()).toFloat())
        carry += abs(dt) * opsPerSecond * env.params.f("pace", 1f) * hurry
        val ops = min(carry.toInt(), 200_000)
        carry -= ops
        for (k in 0 until LANES) {
            if (!running[k]) continue
            // Paid in primitive operations — compares and writes — not in
            // calls: one heap-sort call is a whole sift level and one merge
            // call a single copy, so counting calls rigged the race.
            val lane = lanes[k]
            val goal = lane.comparisons + lane.writes + ops - debt[k]
            var calls = 0
            while (lane.comparisons + lane.writes < goal && calls++ < ops * 4 + 16) {
                if (!algos[k].advance(lane)) {
                    running[k] = false
                    finishedAt[k] = elapsed
                    lanes[k].clearHighlight()
                    place[k] = ++placed
                    // Each finisher is a note, the winner the loudest.
                    env.emit(EventKind.HIT, 1f - (placed - 1) / LANES.toFloat(), (k / (LANES - 1f)) * 1.6f - 0.8f,
                        if (placed == 1) 1f else 0.6f)
                    if (placed == 1) {
                        firstAt = elapsed
                        payoffFrame = true
                        env.emit(EventKind.DONE, 0.9f, 0f, 1f)
                    }
                    break
                }
            }
            // Overshoot is owed from the next frame's share.
            debt[k] = max(0L, lane.comparisons + lane.writes - goal)
        }
        if (placed == LANES && allDoneAt < 0f) allDoneAt = elapsed
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val halfW = env.halfWidth * 0.94f
        val gap = 0.06f
        val laneH = (2f * 0.94f - gap * (LANES - 1)) / LANES
        val barW = 2f * halfW / n
        for (k in 0 until LANES) {
            val top = 0.94f - k * (laneH + gap)
            val bottom = top - laneH
            val tint = k / (LANES - 1f)
            val colour = palette.at(0.08f + 0.84f * tint)
            val arr = lanes[k]
            val done = !running[k]
            if (done && place[k] == 1) g.glow(0f, bottom + laneH * 0.5f, laneH * 0.9f, colour, 0.25f)
            for (i in 0 until n) {
                val h = arr.get(i) * laneH * 0.92f
                val hot = i == arr.lastA || i == arr.lastB
                val c = when {
                    hot -> Palette.brighten(colour, 0.5f)
                    done -> colour
                    else -> Palette.withAlpha(colour, 0.75f)
                }
                g.rect(-halfW + (i + 0.5f) * barW, bottom + h * 0.5f, max(barW * 0.42f, 0.0015f), h * 0.5f, c)
            }
            // The finishing place, as dots at the lane's end: one for first.
            if (done) {
                for (d in 0 until place[k]) {
                    g.disc(halfW - d * 0.03f, top - 0.025f, 0.01f, if (place[k] == 1) Palette.SUCCESS else colour)
                }
            }
        }
    }

    override fun payoff(): Boolean = payoffFrame

    /** Everyone home and a moment to see the order; or the stragglers are let go. */
    override fun finished(): Boolean =
        (allDoneAt >= 0f && elapsed - allDoneAt > 2f) || (placed > 0 && elapsed > MAX_SECONDS)

    override fun contenders(env: SimEnv): IntArray = IntArray(LANES) { env.palette.at(0.08f + 0.84f * it / (LANES - 1f)) }

    override fun winner(): Int = place.indexOfFirst { it == 1 }

    override fun counters(): List<Pair<String, String>> {
        val order = (0 until LANES).sortedWith(compareBy({ if (place[it] == 0) 99 else place[it] }, { it }))
        return order.map { k ->
            val tag = if (place[k] > 0) "#${place[k]}" else "…"
            tag to algos[k].title
        }
    }

    companion object {
        const val LANES = 4
        const val WINNER_SECONDS = 9f
        const val MAX_SECONDS = 30f
        const val OPS_CAP = 400_000L
        const val HURRY = 1.6
        const val MAX_HURRY = 200f

        fun def(): SimDef = SimDef(
            id = "sorting/race",
            title = "Sorting race",
            shelf = Shelf.ALGORITHMS,
            family = "Sorting algorithms",
            blurb = "Four sorts, the same bars, the same speed. Pick one.",
            formats = listOf(TrapFormat.RACE, TrapFormat.GUESS, TrapFormat.SONG),
            heroLabel = "New race",
            defaultPalette = "Spectrum",
            typicalSeconds = 13f,
            params = listOf(
                Param.int("bars", "Bars", 10, 400, 80, card = Card.DATA, logScale = true),
                Param.choice("data", "Starting order", listOf("Shuffled", "Nearly sorted", "Backwards", "Few values"), 0,
                    card = Card.DATA, input = ParamInput.TILES),
                Param.float("pace", "Pace", 0.3f, 4f, 1f, card = Card.MOTION)
            ),
            create = { SortRaceSim() }
        )
    }
}
