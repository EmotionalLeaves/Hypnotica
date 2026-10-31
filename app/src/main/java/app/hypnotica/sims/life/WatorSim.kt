package app.hypnotica.sims.life

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.Shelf
import app.hypnotica.core.sim.Sim
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.sim.SimEnv
import app.hypnotica.core.sim.TouchEvent
import app.hypnotica.core.sim.TrapFormat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Wa-Tor (Dewdney, 1984): a planet of ocean with fish and sharks on it.
 * Fish swim and, every few turns, split in two. Sharks swim to a fish if one
 * is next to them and eat it; a shark that goes too long without eating
 * dies, and one that has lived long enough splits too.
 *
 * Nothing in it says "wave", yet the sea fills with them: fish bloom in open
 * water, sharks find the edge of the bloom and eat inward, the fish behind
 * them are safe because the sharks starve, and the fronts spiral. The two
 * counts below rise and fall a quarter-turn apart — the cycle the
 * Lotka–Volterra equations describe, here made of individuals. Endless.
 */
class WatorSim : Sim {

    private var w = 80
    private var h = 160
    private var cell = 0.012f
    private var kind = ByteArray(0)
    private var age = ShortArray(0)
    private var hunger = ShortArray(0)
    private var stamp = IntArray(0)
    private var turn = 0
    private val nbr = IntArray(4)
    private var fishBreed = 3
    private var sharkBreed = 10
    private var starve = 3
    private var fish = 0
    private var sharks = 0
    private var carry = 0f
    private var ema = 0f
    private var extreme = 0f
    private var falling = false
    private var peaks = 0
    private var elapsed = 0f
    private var paidAt = -1f
    private var payoffFrame = false
    private val histF = FloatArray(HIST)
    private val histS = FloatArray(HIST)
    private val hx = FloatArray(HIST)
    private val hy = FloatArray(HIST)
    private var hn = 0

    override fun setup(env: SimEnv) {
        val p = env.params
        val across = p.i("size", 120).coerceIn(30, MAX_W)
        val hw = env.halfWidth * 0.98f
        cell = 2f * hw / across
        w = across
        h = (1.5f / cell).toInt()
        fishBreed = p.i("fishBreed", 3).coerceIn(1, 20)
        sharkBreed = p.i("sharkBreed", 10).coerceIn(2, 40)
        starve = p.i("starve", 3).coerceIn(1, 20)
        val total = w * h
        kind = ByteArray(total); age = ShortArray(total); hunger = ShortArray(total); stamp = IntArray(total)
        val rng = env.rng
        for (i in 0 until total) {
            val r = rng.nextFloat()
            kind[i] = when { r < 0.25f -> FISH; r < 0.27f -> SHARK; else -> EMPTY }
            age[i] = rng.int(0, max(1, fishBreed)).toShort()
        }
        turn = 0; carry = 0f
        ema = 0f; extreme = 0f; falling = false; peaks = 0
        hn = 0
        elapsed = 0f; paidAt = -1f; payoffFrame = false
        count()
    }

    private fun count() {
        fish = 0; sharks = 0
        for (k in kind) { if (k == FISH) fish++ else if (k == SHARK) sharks++ }
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val a = abs(dt)
        elapsed += a
        val speed = env.params.f("speed", 1f).coerceIn(0.1f, 5f)
        carry += a * TURNS_PER_S * speed
        var k = 0
        while (carry >= 1f && k < 4) { carry -= 1f; chronon(env); k++ }
        if (carry > 4f) carry = 0f
    }

    /** One turn of the sea: every creature moves once, in a scrambled order. */
    private fun chronon(env: SimEnv) {
        turn++
        val total = w * h
        val rng = env.rng
        // A random start and a stride coprime with the size visits every cell
        // once in an order with no sweep direction for the fronts to follow.
        val start = rng.int(0, total)
        var stride = STRIDES[rng.int(0, STRIDES.size)]
        while (gcd(stride, total) != 1) stride++
        var i = start
        var born = 0; var eaten = 0; var starved = 0
        repeat(total) {
            i = (i + stride) % total
            val kd = kind[i]
            if (kd == EMPTY || stamp[i] == turn) return@repeat
            val x = i % w; val y = i / w
            nbr[0] = y * w + (x + 1) % w
            nbr[1] = y * w + (x + w - 1) % w
            nbr[2] = ((y + 1) % h) * w + x
            nbr[3] = ((y + h - 1) % h) * w + x
            if (kd == FISH) {
                val to = pick(rng, EMPTY)
                if (age[i] < 30000) age[i] = (age[i] + 1).toShort()
                if (to >= 0) {
                    move(i, to)
                    if (age[to] >= fishBreed) { age[to] = 0; kind[i] = FISH; age[i] = 0; stamp[i] = turn; born++ }
                }
            } else {
                hunger[i] = (hunger[i] + 1).toShort()
                if (age[i] < 30000) age[i] = (age[i] + 1).toShort()
                var to = pick(rng, FISH)
                if (to >= 0) { hunger[i] = 0; eaten++ } else to = pick(rng, EMPTY)
                var at = i
                if (to >= 0) {
                    move(i, to); at = to
                    if (age[to] >= sharkBreed) { age[to] = 0; kind[i] = SHARK; age[i] = 0; hunger[i] = 0; stamp[i] = turn }
                }
                if (hunger[at] > starve) { kind[at] = EMPTY; starved++ }
            }
        }
        // Never quite extinct: a sea with no sharks left gets a few back at
        // random, and one with no fish a scattering, so the cycle goes on.
        count()
        if (sharks == 0) { seed(rng, SHARK, 12); env.callout("Sharks wiped out!") }
        if (fish == 0) { seed(rng, FISH, 200); env.callout("Not a fish left!") }
        if (sharks == 0 || fish == 0) count()
        if (eaten > 0 && turn % 4 == 0) env.emit(EventKind.HIT, (0.2f + 0.6f * sharks / max(1f, total * 0.2f)).coerceIn(0f, 1f), 0f, min(0.45f, 0.1f + eaten * 0.002f))
        if (born > 0 && turn % 3 == 0) env.emit(EventKind.BIRTH, (0.3f + 0.6f * fish / max(1f, total * 0.6f)).coerceIn(0f, 1f), 0f, 0.15f)

        // A shark boom: the smoothed count turns from rising to falling by a
        // clear margin. The raw count jitters by hundreds from turn to turn.
        ema += (sharks - ema) * 0.15f
        if (!falling) {
            if (ema > extreme) extreme = ema
            if (ema < extreme * 0.85f && extreme > total * 0.01f) {
                falling = true; extreme = ema
                peaks++
                env.emit(EventKind.CROSS, 0.7f, 0f, 0.6f)
                if (peaks >= 2 && !(paidAt < 0f && peaks >= 3)) env.callout("Shark boom #${peaks - 1}!")
                // The first boom is the random start collapsing; the next
                // are the first waves the sea makes for itself.
                if (paidAt < 0f && peaks >= 3) { paidAt = elapsed; payoffFrame = true }
            }
        } else {
            if (ema < extreme) extreme = ema
            if (ema > extreme * 1.15f) { falling = false; extreme = ema }
        }

        if (turn % 2 == 0) {
            if (hn >= HIST) {
                System.arraycopy(histF, 1, histF, 0, HIST - 1)
                System.arraycopy(histS, 1, histS, 0, HIST - 1)
                hn = HIST - 1
            }
            histF[hn] = fish.toFloat() / total; histS[hn] = sharks.toFloat() / total
            hn++
        }
    }

    private fun seed(rng: app.hypnotica.core.sim.Rng, kd: Byte, n: Int) {
        repeat(n) {
            val j = rng.int(0, w * h)
            if (kind[j] == EMPTY) { kind[j] = kd; age[j] = 0; hunger[j] = 0 }
        }
    }

    /** A random neighbour of kind [want], or -1. */
    private fun pick(rng: app.hypnotica.core.sim.Rng, want: Byte): Int {
        var n = 0
        var chosen = -1
        for (d in 0 until 4) {
            val j = nbr[d]
            if (kind[j] == want) { n++; if (rng.int(0, n) == 0) chosen = j }
        }
        return chosen
    }

    private fun move(from: Int, to: Int) {
        kind[to] = kind[from]; age[to] = age[from]; hunger[to] = hunger[from]
        stamp[to] = turn
        kind[from] = EMPTY
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    override fun onInput(env: SimEnv, e: TouchEvent) {
        if (e.kind != TouchEvent.Kind.TAP && e.kind != TouchEvent.Kind.DRAG) return
        // A finger drops a little school of fish.
        val cx = ((e.x + w * cell * 0.5f) / cell).toInt()
        val cy = ((TOP - e.y) / cell).toInt()
        for (dy in -3..3) for (dx in -3..3) {
            val x = cx + dx; val y = cy + dy
            if (x !in 0 until w || y !in 0 until h) continue
            val j = y * w + x
            if (kind[j] == EMPTY) { kind[j] = FISH; age[j] = 0 }
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        val cFish = pal.at(0.45f)
        val cShark = pal.at(0.05f)
        val x0 = -w * cell * 0.5f
        // Runs of one kind along a row merged into one rect, so open water is
        // a few long strips rather than thousands of squares.
        val hc = cell * 0.5f
        for (y in 0 until h) {
            val yc = TOP - (y + 0.5f) * cell
            val row = y * w
            var x = 0
            while (x < w) {
                val kd = kind[row + x]
                var e = x + 1
                while (e < w && kind[row + e] == kd) e++
                if (kd != EMPTY) {
                    g.rect(x0 + (x + e) * hc, yc, (e - x) * hc, hc * 1.02f, if (kd == FISH) cFish else cShark)
                }
                x = e
            }
        }
        // The two counts, rising and falling a quarter-turn apart.
        val gy0 = TOP - h * cell - 0.06f
        val gy1 = -0.97f
        val gx0 = x0; val gx1 = -x0
        if (hn >= 2) {
            for (i in 0 until hn) { hx[i] = gx0 + (gx1 - gx0) * i / (HIST - 1f); hy[i] = gy1 + (gy0 - gy1) * min(1f, histF[i] / 1f) }
            g.polyline(hx, hy, hn, 0.005f, cFish)
            for (i in 0 until hn) hy[i] = gy1 + (gy0 - gy1) * min(1f, histS[i] / 0.5f)
            g.polyline(hx, hy, hn, 0.005f, cShark)
        }
    }

    override fun payoff(): Boolean = payoffFrame

    /** Endless: the cycle goes on for as long as anyone watches. */
    override fun finished(): Boolean = false

    override fun counters(): List<Pair<String, String>> = listOf(
        "fish" to "$fish",
        "sharks" to "$sharks",
        "shark booms" to "$peaks"
    )

    companion object {
        const val MAX_W = 220
        const val EMPTY: Byte = 0
        const val FISH: Byte = 1
        const val SHARK: Byte = 2
        const val TURNS_PER_S = 22f
        const val HIST = 240
        const val TOP = 0.98f
        private val STRIDES = intArrayOf(7919, 104729, 1299709, 15485863)

        fun def(): SimDef = SimDef(
            id = "life/wator",
            title = "Fish and sharks",
            shelf = Shelf.LIFE,
            family = "Ecosystems",
            blurb = "Fish multiply, sharks eat them or starve. Nobody plans the waves that spiral across the sea.",
            formats = listOf(TrapFormat.CHAIN, TrapFormat.GROW),
            heroLabel = "New sea",
            defaultPalette = "Ocean",
            typicalSeconds = 12f,
            params = listOf(
                Param.int("size", "Sea width", 30, MAX_W, 120, card = Card.DATA, logScale = true),
                Param.int("fishBreed", "Fish breed every", 1, 20, 3, card = Card.DATA),
                Param.int("sharkBreed", "Sharks breed every", 2, 40, 10, card = Card.DATA),
                Param.int("starve", "Sharks starve after", 1, 20, 3, card = Card.DATA),
                Param.float("speed", "Speed", 0.3f, 4f, 1f, card = Card.MOTION, logScale = true)
            ),
            create = { WatorSim() }
        )
    }
}
