package app.hypnotica.sims.physics

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.LawKnob
import app.hypnotica.core.sim.NO_CONTENDERS
import app.hypnotica.core.sim.Shelf
import app.hypnotica.core.sim.Sim
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.sim.SimEnv
import app.hypnotica.core.sim.TrapFormat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A balloon, pumped. Each pump a little bigger and a note a little higher; the
 * skin starts to tremble; and at a size nobody can see coming it goes, in a
 * cloud of confetti.
 *
 * The bursting size is drawn at random for each balloon — anywhere from a
 * quarter of what it could hold to nearly all of it, by volume — so the same
 * balloon never pops at the same pump twice —
 * the whole hook is not knowing which pump it will be. With several side by
 * side, pumped together, the question is which goes first.
 */
class BalloonSim : Sim {

    private var count = 1
    private var radius = FloatArray(1)
    private var target = FloatArray(1)
    private var burstAt = FloatArray(1)
    private var tremble = FloatArray(1)
    private var poppedAt = FloatArray(1)
    private var cxs = FloatArray(1)
    private var hue = FloatArray(1)
    private var maxR = 0.8f
    private var sincePump = 0f
    private var pumps = 0
    private var first = -1
    /** The pump that popped the first one: the others are pumped on after it. */
    private var firstPump = 0
    private var elapsed = 0f
    private var payoffFrame = false
    private var halfW = 0.46f
    private var baseY = 0f
    // Confetti: each piece belongs to one balloon.
    private var cx = FloatArray(0)
    private var cy = FloatArray(0)
    private var cvx = FloatArray(0)
    private var cvy = FloatArray(0)
    private var ctint = FloatArray(0)
    private var cspin = FloatArray(0)
    private var cowner = IntArray(0)
    /** Where each piece comes to rest, and the angle it lies at once it has. */
    private var crest = FloatArray(0)
    private var cland = FloatArray(0)
    private var cdrag = FloatArray(0)

    override fun setup(env: SimEnv) {
        halfW = env.halfWidth
        count = env.params.i("balloons", 1).coerceIn(1, MAX_BALLOONS)
        // Side by side, each in its own slot; one alone fills the screen.
        val slot = 2f * halfW / count
        maxR = min(min(env.halfWidth, 1f) * 0.92f, slot * 0.46f).let { if (count > 1) min(it, 0.7f) else it }
        radius = FloatArray(count) { maxR * 0.1f }
        target = radius.copyOf()
        // By radius, 62–98% of the most it could reach: by volume, which is
        // what the pumps add, a quarter to nearly all of it.
        // With several, from a narrower, higher band (half full and up): the first of several
        // random sizes comes early, and a race should be close and late.
        burstAt = FloatArray(count) { maxR * (if (count == 1) env.rng.range(0.62f, 0.98f) else env.rng.range(0.8f, 0.99f)) }
        tremble = FloatArray(count)
        poppedAt = FloatArray(count) { -1f }
        cxs = FloatArray(count) { if (count == 1) 0f else -halfW + slot * (it + 0.5f) }
        // Several float higher on their strings; one alone fills the middle.
        baseY = if (count > 1) 0.25f else 0f
        hue = FloatArray(count) { if (count == 1) 0.75f else (it + 0.5f) / count }
        sincePump = 0f
        pumps = 0
        first = -1
        elapsed = 0f
        payoffFrame = false
        // The confetti is in each balloon from the start, visible through the
        // skin, jostled by every pump — so the pop has been promised all along.
        val n = env.params.i("confetti", 600).coerceIn(20, 20000)
        cx = FloatArray(n); cy = FloatArray(n); cvx = FloatArray(n); cvy = FloatArray(n)
        ctint = FloatArray(n); cspin = FloatArray(n); cowner = IntArray(n) { it % count }
        crest = FloatArray(n); cland = FloatArray(n) { Float.NaN }; cdrag = FloatArray(n)
        for (i in 0 until n) {
            val a = env.rng.range(0f, 2f * PI.toFloat())
            val r = sqrt(env.rng.nextFloat()) * 0.85f
            cx[i] = cos(a) * r; cy[i] = sin(a) * r
            ctint[i] = env.rng.nextFloat()
            cspin[i] = env.rng.range(0f, 6.28f)
            // A shallow, loose drift on the floor, thinning towards its top,
            // instead of every piece on one line: they landed as a solid
            // stripe along the bottom of the screen.
            val u = env.rng.nextFloat()
            crest[i] = FLOOR + DRIFT * u * u
            // Some scraps catch the air more than others, so the cloud
            // stretches out as it falls instead of coming down as one sheet.
            cdrag[i] = env.rng.range(0.8f, 1.7f)
        }
    }

    private fun popped(k: Int) = poppedAt[k] >= 0f

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        if ((0 until count).any { !popped(it) }) {
            sincePump += h * env.params.f("pace", 1f)
            if (sincePump >= PUMP_EVERY) {
                sincePump = 0f
                pumps++
                for (k in 0 until count) if (!popped(k)) pump(k, 1f)
                val share = (target.max() / maxR).coerceIn(0f, 1f)
                env.emit(EventKind.STEP, share, 0f, 0.35f + 0.4f * share)
            }
        }
        for (k in 0 until count) {
            if (popped(k)) continue
            radius[k] += (target[k] - radius[k]) * min(1f, h * 8f)
            // The skin trembles as it nears its limit — the only warning there is.
            val near = ((radius[k] - burstAt[k] * 0.75f) / (burstAt[k] * 0.25f)).coerceIn(0f, 1f)
            tremble[k] = near * near
            if (radius[k] >= burstAt[k]) pop(env, k)
        }
        val g = env.laws.gravity * 0.06f
        for (i in cx.indices) {
            if (!popped(cowner[i])) continue
            // Paper: air stops it sideways far sooner than it stops it
            // falling. With one drag for both, the burst carried most of it
            // to the screen's edges, and it came down there in two columns.
            cvx[i] *= 1f - min(1f, 4f * h)
            if (!cland[i].isNaN()) continue
            cvy[i] = cvy[i] * (1f - cdrag[i] * h) - g * h
            val was = cy[i]
            cx[i] += cvx[i] * h
            cy[i] += cvy[i] * h
            // The pitter-patter of it landing, one piece in twenty heard.
            if (i % 20 == 0 && was >= crest[i] && cy[i] < crest[i]) {
                env.emit(EventKind.HIT, 0.6f + 0.4f * ctint[i], (cx[i] / max(0.1f, halfW)).coerceIn(-1f, 1f), 0.25f)
            }
            // And it lands: the floor the sound said it hit is the bottom of
            // the screen, where it now lies instead of falling on out of view.
            if (cy[i] < crest[i] && cvy[i] < 0f) {
                cy[i] = crest[i]; cvy[i] = 0f; cvx[i] = 0f
                // It lies where it fell, flat, at whatever angle it came down:
                // still spinning on the floor, the drift flickered.
                cx[i] += 0.012f * sin(cspin[i] * 3f + elapsed * 4f) * swayOf(i)
                cland[i] = (cspin[i] + elapsed * 7f) % PI.toFloat()
            }
            // The screen's edges are walls; confetti piles against them
            // rather than leaving.
            if (abs(cx[i]) > halfW) { cx[i] = cx[i].coerceIn(-halfW, halfW); cvx[i] = -cvx[i] * 0.3f }
        }
    }

    /** Air in: the radius grows by less as it gets bigger, since volume is what goes in. */
    private fun pump(k: Int, amount: Float) {
        val r3 = target[k] * target[k] * target[k] + amount * PUMP_VOLUME * maxR * maxR * maxR
        target[k] = Math.cbrt(r3.toDouble()).toFloat()
    }

    private fun pop(env: SimEnv, k: Int) {
        poppedAt[k] = elapsed
        if (first < 0) {
            first = k
            firstPump = pumps
            payoffFrame = true
            env.emit(EventKind.DONE, 1f, 0f, 1f)
        }
        // The bang is heard where the balloon was, spread a little by its size;
        // a random pan across the whole field put a lone balloon's pop everywhere.
        val at = cxs[k] / max(0.1f, halfW)
        val spread = radius[k] / max(0.1f, halfW)
        repeat(POP_NOTES) { env.emit(EventKind.HIT, env.rng.range(0.4f, 1f), (at + env.rng.range(-spread, spread)).coerceIn(-1f, 1f), env.rng.range(0.4f, 0.9f)) }
        // Out from where each piece was inside the balloon.
        for (i in cx.indices) {
            if (cowner[i] != k) continue
            val ux = cx[i]; val uy = cy[i]
            cx[i] = cxs[k] + ux * radius[k]; cy[i] = baseY + uy * radius[k]
            // Faster the further out it was, as a burst flings things: the
            // cloud swells as a whole. The same speed for every piece left
            // the middle empty, and the pop read as a ring.
            val v = env.rng.range(0.8f, 1.2f) * (if (count > 1) 0.7f else 1f)
            cvx[i] = ux * v; cvy[i] = uy * v + 0.3f
        }
    }

    /** A big pump, now: three pumps' worth of air at once, into every one still whole. */
    override fun hero(env: SimEnv) {
        if ((0 until count).none { !popped(it) }) return
        for (k in 0 until count) if (!popped(k)) pump(k, 3f)
        pumps++
        env.emit(EventKind.STEP, (target.max() / maxR).coerceIn(0f, 1f), 0f, 0.8f)
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        for (k in 0 until count) {
            if (popped(k)) continue
            val wob = tremble[k] * 0.012f
            val ox = cxs[k] + sin(elapsed * 47f + k) * wob
            val oy = baseY + cos(elapsed * 53f + k) * wob
            val skin = palette.at(hue[k])
            val r = radius[k]
            // The string and the knot under it.
            g.line(ox, oy - r, ox + 0.02f, -0.98f, 0.003f, Palette.TEXT_SECONDARY, 0.6f)
            g.rect(ox, oy - r - 0.012f, 0.014f, 0.012f, skin, PI.toFloat() / 4f)
            g.glow(ox, oy, r * 1.25f, skin, 0.25f + 0.35f * tremble[k])
            // Thinner, so paler, as it stretches. Shaded as a sphere lit from
            // the upper left — a darker limb, the body, a lit cap toward the
            // shine — in see-through layers: one flat disc read as a paper
            // circle, not a skin full of air.
            val thin = r / maxR
            val limb = Palette.lerp(skin, Palette.BG, 0.35f)
            g.disc(ox, oy, r, Palette.withAlpha(limb, 0.9f - 0.25f * thin))
            g.disc(ox - r * 0.07f, oy + r * 0.07f, r * 0.88f, Palette.withAlpha(skin, 0.75f - 0.25f * thin))
            g.disc(ox - r * 0.2f, oy + r * 0.22f, r * 0.55f, Palette.withAlpha(Palette.brighten(skin, 0.12f), 0.35f))
        }
        val paper = cx.size <= PAPER_MAX
        if (!paper) g.reservePoints(cx.size)
        for (i in cx.indices) {
            val k = cowner[i]
            if (!popped(k)) {
                // The confetti inside, jostling, dimmed by the skin it's behind.
                val wob = tremble[k] * 0.012f
                val ox = cxs[k] + sin(elapsed * 47f + k) * wob
                val oy = baseY + cos(elapsed * 53f + k) * wob
                val j = 0.04f * sin(cspin[i] + elapsed * (2f + 6f * tremble[k]))
                g.point(ox + (cx[i] + j) * radius[k], oy + (cy[i] - j) * radius[k], 0.01f, Palette.withAlpha(palette.at(ctint[i]), 0.55f))
            } else {
                val down = !cland[i].isNaN()
                val turn = if (down) cland[i] else cspin[i] + elapsed * 7f
                // Swaying as it falls, the way paper does; still once it lands.
                val sway = if (!down) 0.012f * sin(cspin[i] * 3f + elapsed * 4f) * swayOf(i) else 0f
                // Landed pieces a shade dimmer, so the drift sits back and
                // the falling ones stay the subject.
                val col = if (down) Palette.lerp(palette.at(ctint[i]), Palette.BG, 0.25f) else palette.at(ctint[i])
                if (paper) {
                    // A scrap of paper, turning over: its width flickers as it
                    // shows its edge. Lying flat, it shows its face.
                    val flip = if (down) 0.8f else 0.25f + 0.75f * abs(sin(turn * 1.3f))
                    g.rect(cx[i] + sway, cy[i], 0.009f, 0.0045f * flip + 0.001f, col, turn)
                } else {
                    g.point(cx[i] + sway, cy[i], 0.012f * (if (down) 0.8f else 0.6f + 0.4f * abs(sin(turn))), col)
                }
            }
        }
        // The shine, over the confetti showing through.
        for (k in 0 until count) {
            if (popped(k)) continue
            val r = radius[k]
            // On the trembling skin, not left behind where it started.
            val wob = tremble[k] * 0.012f
            val ox = cxs[k] + sin(elapsed * 47f + k) * wob
            val oy = baseY + cos(elapsed * 53f + k) * wob
            g.disc(ox - r * 0.38f, oy + r * 0.42f, r * 0.11f, Palette.withAlpha(Palette.TEXT_PRIMARY, 0.55f))
            g.disc(ox - r * 0.22f, oy + r * 0.55f, r * 0.045f, Palette.withAlpha(Palette.TEXT_PRIMARY, 0.45f))
            // A thin bright rim on the shadowed side: light through the skin
            // at its edge, which is what makes a balloon look inflated.
            g.ring(ox, oy, r, max(0.003f, r * 0.025f), Palette.brighten(skin(env, k), 0.25f), PI.toFloat(), PI.toFloat() * 0.25f, 0.55f)
        }
    }

    private fun skin(env: SimEnv, k: Int): Int = env.palette.at(hue[k])

    /** How widely a piece sways as it falls: the light ones flutter more. */
    private fun swayOf(i: Int): Float = 0.6f + 0.9f * (cdrag[i] - 0.8f) / 0.9f

    override fun frameHalfWidth(env: SimEnv): Float = 0f

    override fun contenders(env: SimEnv): IntArray =
        if (count < 2) NO_CONTENDERS else IntArray(count) { env.palette.at(hue[it]) }

    override fun winner(): Int = if (count < 2) -1 else first

    override fun payoff(): Boolean = payoffFrame

    // Over once the first has popped and its confetti has come down; with
    // several, the rest keep growing (and going) meanwhile.
    override fun finished(): Boolean = first >= 0 && elapsed - poppedAt[first] > FALL_SECONDS

    override fun counters(): List<Pair<String, String>> = listOf(
        "pumps" to pumps.toString(),
        if (first >= 0) "popped at" to (if (count > 1) "pump $firstPump · #${first + 1}" else "pump $firstPump")
        else "size" to String.format(java.util.Locale.US, "%.0f%%", radius.max() / maxR * 100f)
    )

    companion object {
        const val MAX_BALLOONS = 4
        const val PUMP_EVERY = 0.55f
        const val PUMP_VOLUME = 0.035f
        const val POP_NOTES = 10
        /** Long enough for most of it to land: at 3 s the run ended with the cloud hanging mid-screen. */
        const val FALL_SECONDS = 4.5f
        const val FLOOR = -0.97f
        /** How deep the drift of landed confetti is. */
        const val DRIFT = 0.07f
        /** Up to this many pieces, confetti is drawn as turning scraps; past it, as dots. */
        const val PAPER_MAX = 3000

        fun def(): SimDef = SimDef(
            id = "physics/balloon",
            title = "Pump the balloon",
            shelf = Shelf.PHYSICS,
            family = "Pressure & bursting",
            blurb = "Every pump a little bigger. Which one pops it? With several, which goes first?",
            formats = listOf(TrapFormat.SNAP, TrapFormat.GUESS, TrapFormat.CUT),
            lawKnobs = setOf(LawKnob.GRAVITY),
            heroLabel = "Big pump",
            heroRestarts = false,
            defaultPalette = "Candy",
            typicalSeconds = 12f,
            params = listOf(
                Param.int("balloons", "Balloons", 1, MAX_BALLOONS, 1, card = Card.DATA),
                Param.int("confetti", "Confetti", 20, 20000, 600, card = Card.LOOK, logScale = true),
                Param.float("pace", "Pumping speed", 0.3f, 3f, 1f, card = Card.MOTION)
            ),
            create = { BalloonSim() }
        )
    }
}
