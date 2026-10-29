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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The Sieve of Eratosthenes on a grid of the numbers 1 to N.
 *
 * Take the smallest number not yet struck out — it is prime — and strike out
 * its multiples, each in that prime's colour, starting from its square (every
 * smaller multiple has a smaller factor and is already gone). Once the next
 * prime's square is past N there is nothing left to strike: every number
 * still standing is prime, and they light up together. A composite keeps the
 * colour of the first prime to reach it, its smallest factor, so the grid
 * ends as a map of smallest factors with the primes standing out.
 */
class SieveSim : Sim {

    private var n = 200
    private var cols = 10
    private var rows = 20
    private var cell = 0.08f
    private var struck = IntArray(0)     // prime index that struck it first, or -1
    private var strikeAt = FloatArray(0)
    private var flickAt = FloatArray(0)
    private var flickBy = IntArray(0)
    private var sievers = IntArray(0)   // the primes up to √N
    private var actN = IntArray(0)       // the number each action touches
    private var actP = IntArray(0)       // its prime index; negative for an announcement
    private var actAt = FloatArray(0)
    private var acts = 0
    private var played = 0
    private var current = -1
    private var currentAt = -9f
    private var finalNext = 0
    private var endAt = 0f
    private var clock = 0f
    private var vclock = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var sinceSound = 1f
    private var struckCount = 0
    private var primeRank = IntArray(0)
    private var primes = 0

    override fun setup(env: SimEnv) {
        val p = env.params
        n = p.i("n", 200).coerceIn(30, 1000)
        val halfW = env.halfWidth
        val availW = 2f * halfW * 0.94f
        val availH = 1.86f
        cols = when (p.choiceIndex("row")) {
            1 -> 6
            2 -> 10
            3 -> 30
            else -> bestCols(availW, availH)
        }
        rows = (n + cols - 1) / cols
        cell = min(availW / cols, availH / rows)
        // The sieve itself, done once to know what to play.
        val composite = BooleanArray(n + 1)
        val sv = ArrayList<Int>()
        var q = 2
        while (q * q <= n) {
            if (!composite[q]) { sv.add(q); var m = q * q; while (m <= n) { composite[m] = true; m += q } }
            q++
        }
        sievers = sv.toIntArray()
        finalNext = q
        while (finalNext <= n && composite[finalNext]) finalNext++
        primeRank = IntArray(n + 1) { -1 }
        primes = 0
        for (k in 2..n) if (!composite[k]) primeRank[k] = primes++
        // Each prime's sweep gets time by the square root of its strike count:
        // two's hundred strikes run quick, thirteen's three can be followed.
        val counts = IntArray(sievers.size) { i -> val s = sievers[i]; (n - s * s) / s + 1 }
        var weight = 0f
        for (c in counts) weight += sqrt(c.toFloat())
        val sweep = SWEEP_SECONDS - ANNOUNCE_SECONDS * sievers.size
        val total = sievers.size + counts.sum()
        actN = IntArray(total); actP = IntArray(total); actAt = FloatArray(total)
        acts = 0
        var t = INTRO_SECONDS
        for (i in sievers.indices) {
            val s = sievers[i]
            actN[acts] = s; actP[acts] = -1 - i; actAt[acts] = t; acts++
            t += ANNOUNCE_SECONDS
            val dur = sweep * sqrt(counts[i].toFloat()) / weight
            val every = dur / counts[i]
            var m = s * s
            while (m <= n) {
                actN[acts] = m; actP[acts] = i; actAt[acts] = t; acts++
                t += every
                m += s
            }
        }
        // A beat after the last strike the next prime is ringed: its square
        // is past N, and nothing more can fall.
        endAt = t + ANNOUNCE_SECONDS
        struck = IntArray(n + 1) { -1 }
        strikeAt = FloatArray(n + 1) { -9f }
        flickAt = FloatArray(n + 1) { -9f }
        flickBy = IntArray(n + 1)
        played = 0; current = -1; currentAt = -9f
        clock = 0f; vclock = 0f; doneAt = -1f; payoffFrame = false; sinceSound = 1f; struckCount = 0
    }

    /** The row width that draws the biggest cells, ten when it is a tie. */
    private fun bestCols(w: Float, h: Float): Int {
        var best = 10
        var bestCell = 0f
        for (c in intArrayOf(10, 6, 8, 12, 15, 20, 24, 30, 40, 50, 60)) {
            val r = (n + c - 1) / c
            val s = min(w / c, h / r)
            if (s > bestCell * 1.08f) { bestCell = s; best = c }
        }
        return best
    }

    private fun colour(palette: Palette, i: Int): Int =
        deepTone(palette.at(if (sievers.size <= 1) 0f else i.toFloat() / (sievers.size - 1)))

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        clock += h
        sinceSound += h
        if (doneAt >= 0f) return
        vclock += h * env.params.f("speed", 1f).coerceIn(0.1f, 8f)
        while (played < acts && actAt[played] <= vclock) {
            val i = played++
            val m = actN[i]
            val pan = ((m - 1) % cols + 0.5f) / cols * 2f - 1f
            if (actP[i] < 0) {
                current = m; currentAt = clock
                // A new prime: its own note, rising through the run.
                env.emit(EventKind.BIRTH, 0.2f + 0.7f * (-1 - actP[i]) / max(1, sievers.size), pan, 0.7f)
                sinceSound = 0f
            } else {
                if (struck[m] < 0) {
                    struck[m] = actP[i]; strikeAt[m] = clock; struckCount++
                    if (sinceSound >= 1f / 30f) {
                        sinceSound = 0f
                        env.emit(EventKind.WRITE, 0.2f + 0.7f * actP[i] / max(1, sievers.size), pan, 0.35f)
                    }
                } else {
                    // Already gone to a smaller prime: a flicker, no new colour.
                    flickAt[m] = clock; flickBy[m] = actP[i]
                }
            }
        }
        if (played >= acts && current != finalNext && vclock >= endAt - ANNOUNCE_SECONDS) {
            current = finalNext; currentAt = clock
        }
        if (played >= acts && vclock >= endAt) {
            doneAt = clock
            payoffFrame = true
            current = -1
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val since = if (doneAt >= 0f) clock - doneAt else -1f
        val w = cols * cell
        val ox = -w * 0.5f
        val oy = rows * cell * 0.5f
        val k = SegDigits.digitsIn(n)
        val dh = min(cell * 0.42f, cell * 0.74f / (SegDigits.ASPECT * (1f + (k - 1) * 1.28f)))
        val digits = cell >= MIN_DIGIT_CELL
        g.reserveInstances((n + 1) * (if (digits) 2 + k * 7 else 3) + 16)
        val pc = PRIME_C
        // Once the sieve is done the struck numbers sink further back, so the
        // survivors are the only bright things on the grid.
        val sink = if (since >= 0f) ease(since / 0.8f) else 0f
        val inset = cell * 0.06f
        val hw = cell * 0.5f - inset
        for (m in 1..n) {
            val c = (m - 1) % cols; val r = (m - 1) / cols
            val x = ox + (c + 0.5f) * cell
            val y = oy - (r + 0.5f) * cell
            val s = struck[m]
            val isCur = m == current
            val prime = primeRank[m] >= 0
            // Known prime: a siever already announced, or any survivor once done.
            val shown = prime && (doneAt >= 0f || m <= lastAnnounced())
            var bg: Int
            var ink: Int
            var inkA = 1f
            if (s >= 0) {
                val col = colour(palette, s)
                val fresh = ((clock - strikeAt[m]) / 0.25f).coerceIn(0f, 1f)
                // Struck numbers recede: a dark wash of the prime's colour and a
                // dim figure, so what is left standing reads at a glance.
                // (A bright strike on a bright wash made a struck grid as loud
                // as the survivors; the fresh strike is the only loud moment.)
                bg = Palette.lerp(Palette.lerp(EMPTY, col, 0.45f), Palette.lerp(EMPTY, col, 0.11f - 0.05f * sink), fresh)
                ink = col; inkA = 0.3f - 0.12f * sink
            } else if (shown) {
                // A prime is a solid tile with dark figures: a siever in its
                // own colour, the rest in warm white, lit once the sieve is
                // done in reading order, and staying lit.
                val si = sieverIndex(m)
                val lit = if (si >= 0) 1f else ease((since - 0.6f * m / n) / 0.5f)
                val tone = if (si >= 0) Palette.brighten(colour(palette, si), 0.12f) else pc
                bg = Palette.lerp(EMPTY, tone, (if (si >= 0) 0.85f else 0.9f) * lit)
                ink = Palette.lerp(IDLE_INK, PRIME_INK, lit)
                if (since >= 0f && lit > 0.02f) g.glow(x, y, cell * 0.8f, tone, 0.16f * lit)
            } else {
                bg = EMPTY
                ink = IDLE_INK
                inkA = if (m == 1) 0.35f else 0.8f
            }
            if (isCur) {
                val col = if (m == finalNext && played >= acts) Palette.TEXT_PRIMARY else colour(palette, sieverIndex(m))
                val a = ((clock - currentAt) / 0.2f).coerceIn(0f, 1f)
                bg = Palette.lerp(bg, col, 0.85f * a)
                ink = Palette.lerp(ink, PRIME_INK, a); inkA = 1f
            }
            g.rect(x, y, hw, hw, bg)
            // The prime at work wears a ring in its colour, so the eye can
            // find whose multiples are falling.
            if (isCur) {
                val col = if (m == finalNext && played >= acts) Palette.TEXT_PRIMARY else colour(palette, sieverIndex(m))
                g.ring(x, y, cell * 0.58f, max(0.003f, cell * 0.05f), Palette.brighten(col, 0.15f),
                    alpha = ((clock - currentAt) / 0.2f).coerceIn(0f, 1f))
            }
            // A strike already struck: the later prime passes over it faintly.
            val fl = clock - flickAt[m]
            if (fl in 0f..0.25f) g.rect(x, y, hw, hw, colour(palette, flickBy[m]), alpha = 0.3f * (1f - fl / 0.25f))
            if (digits) SegDigits.number(g, x, y, dh, m, ink, inkA)
            if (s >= 0) {
                // Struck through, corner to corner, in the striking prime's colour.
                // Drawn bright as it falls, then settling to a quiet mark.
                val age = clock - strikeAt[m]
                val grow = (age / 0.12f).coerceIn(0f, 1f)
                val settle = ease((age - 0.15f) / 0.45f)
                val e = hw * 0.72f
                g.line(x - e, y - e, x - e + 2f * e * grow, y - e + 2f * e * grow, max(0.0022f, cell * 0.04f),
                    Palette.brighten(colour(palette, s), 0.15f * (1f - settle)), 1f - 0.5f * settle - 0.2f * sink)
            }
        }
    }

    private fun lastAnnounced(): Int {
        var best = 1
        for (i in 0 until played) if (actP[i] < 0) best = actN[i]
        return best
    }

    private fun sieverIndex(m: Int): Int {
        for (i in sievers.indices) if (sievers[i] == m) return i
        return -1
    }

    private fun ease(t: Float): Float { val u = t.coerceIn(0f, 1f); return u * u * (3f - 2f * u) }

    override fun frameHalfWidth(env: SimEnv): Float = cols * cell * 0.5f + 0.02f

    override fun frameHalfHeight(env: SimEnv): Float = rows * cell * 0.5f + 0.02f

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && clock - doneAt > 3f

    override fun counters(): List<Pair<String, String>> = listOf(
        "struck out" to "$struckCount",
        "sieving with" to if (current > 0 && doneAt < 0f) "$current" else "-",
        "primes" to if (doneAt >= 0f) "$primes" else "?"
    )

    companion object {
        private const val INTRO_SECONDS = 0.8f
        private const val ANNOUNCE_SECONDS = 0.5f
        private const val SWEEP_SECONDS = 8.6f
        private const val PRIME_C = 0xFFF4EEDC.toInt()
        private const val PRIME_INK = 0xFF0B0E15.toInt()
        private const val MIN_DIGIT_CELL = 0.05f
        private const val EMPTY = 0xFF141924.toInt()
        private const val IDLE_INK = 0xFF8C94A5.toInt()

        fun def(): SimDef = SimDef(
            id = "algorithms/sieve",
            title = "Sieve of Eratosthenes",
            shelf = Shelf.ALGORITHMS,
            family = "Numbers",
            blurb = "Each prime strikes out its multiples. Whatever is left standing is prime.",
            formats = listOf(TrapFormat.REVEAL, TrapFormat.LAST_STANDING, TrapFormat.SNAP),
            heroLabel = "Sieve again",
            defaultPalette = "Aurora",
            typicalSeconds = 12f,
            params = listOf(
                Param.int("n", "Up to", 30, 1000, 200, card = Card.DATA, logScale = true),
                Param.choice("row", "Row width", listOf("Fit", "6", "10", "30"), 0,
                    card = Card.DATA, input = ParamInput.TILES),
                Param.float("speed", "Speed", 0.25f, 4f, 1f, card = Card.MOTION, unit = "x", logScale = true)
            ),
            create = { SieveSim() }
        )
    }
}
