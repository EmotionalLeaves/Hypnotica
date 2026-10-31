package app.hypnotica.sims.algorithms

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.recipe.ParamInput
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.Shelf
import app.hypnotica.core.sim.Sim
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.sim.SimEnv
import app.hypnotica.core.sim.TrapFormat
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A travelling salesman's tour, untangled by 2-opt.
 *
 * The tour starts as the cities in random order, a knot of crossing roads.
 * A 2-opt move takes out two roads and reconnects the four ends the other way
 * (reversing everything between them); where two roads cross, that always
 * shortens the tour, by the triangle inequality. The best such move is made
 * each time, until none shortens it — a tour with no crossings left, which is
 * the payoff. The red rings are the crossings still to go.
 */
class TspSim : Sim {

    private var n = 40
    private var cx = FloatArray(0)
    private var cy = FloatArray(0)
    private var start = IntArray(0)
    private var tour = IntArray(0)
    private var swapI = IntArray(0)
    private var swapJ = IntArray(0)
    private var swapGain = FloatArray(0)
    private var swapAt = FloatArray(0)
    private var swaps = 0
    private var applied = 0
    private var length = 0f
    private var startLength = 1f
    private var crossX = FloatArray(MAX_CROSS)
    private var crossY = FloatArray(MAX_CROSS)
    private var crossings = 0
    private var ex = 0.4f
    private var ey = 0.86f
    private var clock = 0f
    private var vclock = 0f
    private var doneAt = -1f
    private var finalAt = 0f
    private var payoffFrame = false
    private var sinceSound = 1f
    // The move on show: the two roads out and the two in.
    private var oa = -1; private var ob = -1; private var oc = -1; private var od = -1
    private var moveAt = -9f
    private var moveDur = 0.5f

    override fun setup(env: SimEnv) {
        val p = env.params
        n = p.i("cities", 40).coerceIn(6, MAX_N)
        ex = env.halfWidth * 0.86f
        ey = 0.86f
        cx = FloatArray(n); cy = FloatArray(n)
        val rng = env.rng
        when (p.choiceIndex("layout")) {
            1 -> {
                // A ring: the best tour is the ring itself, a clean finish.
                for (i in 0 until n) {
                    val a = 6.2832f * i / n + rng.range(-0.25f, 0.25f) * 6.2832f / n
                    val r = rng.range(0.9f, 1f)
                    cx[i] = cos(a) * ex * r; cy[i] = sin(a) * ey * r
                }
            }
            2 -> {
                val groups = (3 + n / 30).coerceAtMost(6)
                val gx = FloatArray(groups) { rng.range(-ex * 0.65f, ex * 0.65f) }
                val gy = FloatArray(groups) { rng.range(-ey * 0.7f, ey * 0.7f) }
                for (i in 0 until n) {
                    val k = i % groups
                    cx[i] = (gx[k] + rng.normal() * ex * 0.2f).coerceIn(-ex, ex)
                    cy[i] = (gy[k] + rng.normal() * ey * 0.13f).coerceIn(-ey, ey)
                }
            }
            else -> {
                // Scattered, but not clumped: the best of a few candidates for
                // each city, so no two sit on top of each other.
                for (i in 0 until n) {
                    var bx = 0f; var by = 0f; var bd = -1f
                    for (t in 0 until 6) {
                        val x = rng.range(-ex, ex); val y = rng.range(-ey, ey)
                        var d = Float.MAX_VALUE
                        for (q in 0 until i) d = min(d, hypot(x - cx[q], y - cy[q]))
                        if (d > bd) { bd = d; bx = x; by = y }
                    }
                    cx[i] = bx; cy[i] = by
                }
            }
        }
        start = IntArray(n) { it }
        rng.shuffle(start)
        record()
        tour = start.copyOf()
        length = tourLength(tour)
        startLength = max(length, 1e-4f)
        // Big untanglings come first and are worth watching one at a time;
        // the last few dozen are small and can go by quickly.
        val pace = AlgoPace(max(1, swaps), RUN_SECONDS, 1.4f)
        swapAt = FloatArray(swaps) { INTRO_SECONDS + pace.timeOf(it) }
        finalAt = if (swaps > 0) swapAt[swaps - 1] else INTRO_SECONDS
        applied = 0
        clock = 0f; vclock = 0f; doneAt = -1f; payoffFrame = false; sinceSound = 1f
        oa = -1; moveAt = -9f
        cum = FloatArray(1); passed = 0
        findCrossings()
    }

    private fun dist(a: Int, b: Int): Double = hypot((cx[a] - cx[b]).toDouble(), (cy[a] - cy[b]).toDouble())

    private fun tourLength(t: IntArray): Float {
        var s = 0.0
        for (i in 0 until n) s += dist(t[i], t[(i + 1) % n])
        return s.toFloat()
    }

    /** Best-improvement 2-opt from the start tour, every move written down. */
    private fun record() {
        val t = start.copyOf()
        val li = ArrayList<Int>(); val lj = ArrayList<Int>(); val lg = ArrayList<Float>()
        var guard = 0
        while (guard++ < n * 40) {
            var best = -1e-9
            var bi = -1; var bj = -1
            for (i in 0 until n - 1) {
                val a = t[i]; val b = t[i + 1]
                val dab = dist(a, b)
                for (j in i + 2 until n) {
                    if (i == 0 && j == n - 1) continue
                    val c = t[j]; val d = t[(j + 1) % n]
                    val delta = dist(a, c) + dist(b, d) - dab - dist(c, d)
                    if (delta < best) { best = delta; bi = i; bj = j }
                }
            }
            if (bi < 0) break
            reverse(t, bi + 1, bj)
            li.add(bi); lj.add(bj); lg.add((-best).toFloat())
        }
        swaps = li.size
        swapI = IntArray(swaps) { li[it] }
        swapJ = IntArray(swaps) { lj[it] }
        swapGain = FloatArray(swaps) { lg[it] }
    }

    private fun reverse(t: IntArray, from: Int, to: Int) {
        var a = from; var b = to
        while (a < b) { val s = t[a]; t[a] = t[b]; t[b] = s; a++; b-- }
    }

    /** Every pair of roads that cross, and where. */
    private fun findCrossings() {
        crossings = 0
        for (i in 0 until n) {
            val a = tour[i]; val b = tour[(i + 1) % n]
            for (j in i + 2 until n) {
                if (i == 0 && j == n - 1) continue
                val c = tour[j]; val d = tour[(j + 1) % n]
                val d1 = orient(a, b, c); val d2 = orient(a, b, d)
                val d3 = orient(c, d, a); val d4 = orient(c, d, b)
                if ((d1 > 0f) != (d2 > 0f) && (d3 > 0f) != (d4 > 0f)) {
                    if (crossings < MAX_CROSS) {
                        val f = d3 / (d3 - d4)
                        crossX[crossings] = cx[a] + (cx[b] - cx[a]) * f
                        crossY[crossings] = cy[a] + (cy[b] - cy[a]) * f
                    }
                    crossings++
                }
            }
        }
    }

    private fun orient(a: Int, b: Int, c: Int): Float =
        (cx[b] - cx[a]) * (cy[c] - cy[a]) - (cy[b] - cy[a]) * (cx[c] - cx[a])

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        clock += h
        sinceSound += h
        if (doneAt >= 0f) {
            // The salesman sets off on the finished tour; each city ticks as
            // the light reaches it, rising as the round trip completes.
            val d = travelled()
            var k = passed
            while (k < n && cum[k + 1] <= d) k++
            if (k > passed) {
                passed = k
                if (sinceSound >= 1f / 20f) {
                    sinceSound = 0f
                    val c = tour[k % n]
                    env.emit(EventKind.STEP, 0.3f + 0.6f * k / n, (cx[c] / ex).coerceIn(-1f, 1f), 0.22f)
                }
            }
            return
        }
        vclock += h * env.params.f("speed", 1f).coerceIn(0.1f, 8f)
        var any = false
        while (applied < swaps && swapAt[applied] <= vclock) {
            val i = swapI[applied]; val j = swapJ[applied]
            oa = tour[i]; ob = tour[i + 1]; oc = tour[j]; od = tour[(j + 1) % n]
            reverse(tour, i + 1, j)
            // How long this move stays on show: until the next is due, within limits.
            val next = if (applied + 1 < swaps) swapAt[applied + 1] - swapAt[applied] else 0.6f
            moveDur = next.coerceIn(0.12f, 0.6f)
            moveAt = vclock
            any = true
            val gain = swapGain[applied]
            applied++
            if (sinceSound >= 1f / 25f) {
                sinceSound = 0f
                // A big untangling is a low note, the last small tidies high.
                val rel = (gain / startLength * 8f).coerceIn(0f, 1f)
                val mx = (cx[oa] + cx[oc] + cx[ob] + cx[od]) * 0.25f
                env.emit(EventKind.SWAP, 0.85f - 0.65f * sqrt(rel), (mx / ex).coerceIn(-1f, 1f), 0.35f + 0.5f * rel)
            }
        }
        if (any) {
            length = tourLength(tour)
            findCrossings()
        }
        // No move left once the last one has finished showing.
        if (applied >= swaps && vclock >= finalAt + (if (swaps > 0) moveDur else 0f)) {
            doneAt = clock
            payoffFrame = true
            val saved = ((1f - length / startLength) * 100f).toInt()
            env.callout(if (saved > 0) "$saved% shorter!" else "No crossings!", big = true)
            cum = FloatArray(n + 1)
            for (k in 0 until n) cum[k + 1] = cum[k] + dist(tour[k], tour[(k + 1) % n]).toFloat()
            passed = 0
        }
    }

    private var cum = FloatArray(1)
    private var passed = 0

    /** How far along the finished tour the light has run. */
    private fun travelled(): Float {
        if (doneAt < 0f) return 0f
        val u = ((clock - doneAt - TRAVEL_DELAY) / TRAVEL_SECONDS).coerceIn(0f, 1f)
        return u * u * (3f - 2f * u) * cum[n]
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val road = palette.at(ROAD_T)
        val roadC = road
        g.reserveInstances(n * 5 + min(crossings, MAX_CROSS) * 2 + 32)
        val mu = ((vclock - moveAt) / moveDur).coerceIn(0f, 1f)
        val showing = doneAt < 0f && oa >= 0 && mu < 1f
        val run = travelled()
        val lit = palette.bright(ROAD_T, 0.22f)
        for (s in 0 until n) {
            val a = tour[s]; val b = tour[(s + 1) % n]
            val fresh = showing && ((a == oa && b == oc) || (a == oc && b == oa) || (a == ob && b == od) || (a == od && b == ob))
            if (fresh) continue
            g.line(cx[a], cy[a], cx[b], cy[b], ROAD_W, roadC, 0.92f)
            // The finished tour lights up behind the travelling salesman and
            // stays lit.
            if (doneAt >= 0f && cum.size == n + 1 && run > cum[s]) {
                val f = ((run - cum[s]) / max(1e-5f, cum[s + 1] - cum[s])).coerceIn(0f, 1f)
                g.line(cx[a], cy[a], cx[a] + (cx[b] - cx[a]) * f, cy[a] + (cy[b] - cy[a]) * f, ROAD_W * 1.5f, lit)
            }
        }
        if (showing) {
            // The two roads out, red and fading; the two in, drawn bright from
            // each end towards the middle.
            val out = 1f - mu
            g.line(cx[oa], cy[oa], cx[ob], cy[ob], ROAD_W, Palette.DANGER, 0.8f * out)
            g.line(cx[oc], cy[oc], cx[od], cy[od], ROAD_W, Palette.DANGER, 0.8f * out)
            val grow = (mu * 1.6f).coerceAtMost(1f)
            val inC = Palette.lerp(Palette.TEXT_PRIMARY, roadC, mu)
            g.line(cx[oa], cy[oa], cx[oa] + (cx[oc] - cx[oa]) * grow, cy[oa] + (cy[oc] - cy[oa]) * grow, ROAD_W * 1.4f, inC)
            g.line(cx[ob], cy[ob], cx[ob] + (cx[od] - cx[ob]) * grow, cy[ob] + (cy[od] - cy[ob]) * grow, ROAD_W * 1.4f, inC)
        }
        // Crossings still to undo.
        val shown = min(crossings, MAX_CROSS)
        // A knot of a hundred crossings drew rings on rings; they shrink as
        // they crowd, back to full size once only a few are left.
        val ringR = 0.017f * sqrt(24f / max(24, shown).toFloat()).coerceAtLeast(0.6f)
        for (k in 0 until shown) {
            g.ring(crossX[k], crossY[k], ringR, 0.0042f * (0.4f + 0.6f * ringR / 0.017f), Palette.DANGER, alpha = 0.85f)
        }
        for (s in 0 until n) {
            val a = tour[s]
            // A city the salesman has reached takes a steady halo and stays lit.
            val reached = doneAt >= 0f && cum.size == n + 1 && (run >= cum[s] && (s > 0 || run > 0f))
            if (reached) g.glow(cx[a], cy[a], 0.045f, road, 0.3f)
            g.disc(cx[a], cy[a], CITY_R + 0.004f, Palette.BG)
            g.disc(cx[a], cy[a], CITY_R, if (reached) Palette.TEXT_PRIMARY else CITY_C)
        }
        // The salesman: a bright point running the tour once.
        if (doneAt >= 0f && cum.size == n + 1 && run > 0f && run < cum[n]) {
            var s = 0
            while (s < n - 1 && cum[s + 1] < run) s++
            val a = tour[s]; val b = tour[(s + 1) % n]
            val f = ((run - cum[s]) / max(1e-5f, cum[s + 1] - cum[s])).coerceIn(0f, 1f)
            val x = cx[a] + (cx[b] - cx[a]) * f; val y = cy[a] + (cy[b] - cy[a]) * f
            g.glow(x, y, 0.06f, lit, 0.5f)
            g.disc(x, y, CITY_R * 1.2f, Palette.TEXT_PRIMARY)
        }
    }

    override fun frameHalfWidth(env: SimEnv): Float = ex + 0.03f

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && clock - doneAt > 3f

    override fun counters(): List<Pair<String, String>> = listOf(
        "length" to String.format(java.util.Locale.US, "%.2f", length),
        "crossings" to "$crossings",
        "swaps" to "$applied"
    )

    companion object {
        const val MAX_N = 160
        private const val MAX_CROSS = 600
        private const val RUN_SECONDS = 8.5f
        private const val INTRO_SECONDS = 1.0f
        private const val TRAVEL_DELAY = 0.25f
        private const val TRAVEL_SECONDS = 2.3f
        private const val ROAD_T = 0.55f
        private const val ROAD_W = 0.005f
        private const val CITY_R = 0.011f
        private const val CITY_C = 0xFFD8DCE4.toInt()

        fun def(): SimDef = SimDef(
            id = "algorithms/tsp-2opt",
            title = "Travelling salesman",
            shelf = Shelf.ALGORITHMS,
            family = "Machine learning & optimization",
            blurb = "Two roads cross? Swap them. Until no road crosses another.",
            formats = listOf(TrapFormat.NUMBER_UP, TrapFormat.SNAP, TrapFormat.PERFECT_FIT),
            heroLabel = "New cities",
            defaultPalette = "Aurora",
            typicalSeconds = 12f,
            params = listOf(
                Param.int("cities", "Cities", 6, MAX_N, 40, card = Card.DATA, logScale = true),
                Param.choice("layout", "Map", listOf("Scatter", "Ring", "Towns"), 0,
                    card = Card.DATA, input = ParamInput.TILES),
                Param.float("speed", "Speed", 0.25f, 4f, 1f, card = Card.MOTION, unit = "x", logScale = true)
            ),
            create = { TspSim() }
        )
    }
}
