package app.hypnotica.sims.physics

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.recipe.ParamInput
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.LawKnob
import app.hypnotica.core.sim.Shelf
import app.hypnotica.core.sim.Sim
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.sim.SimEnv
import app.hypnotica.core.sim.TrapFormat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A prize wheel, spun hard and left to slow.
 *
 * Pegs round the rim knock a flapper at the top: a click per peg, fast and
 * blurred at first, then further and further apart. Each peg takes a little
 * of the wheel's speed to get past, so near the end one may not make it — the
 * wheel climbs onto the peg and is thrown back. Where it stops is drawn
 * from the spin, so nobody, the person watching included, knows until then.
 */
class WheelSim : Sim {

    private var segments = 12
    // Where each slice starts round the wheel, and how wide it is: all the
    // same, or one thin gold jackpot, or lopsided at random.
    private var starts = FloatArray(0)
    private var widths = FloatArray(0)
    private var jackpot = -1
    private var angle = 0f
    private var omega = 0f
    private var friction = 0.3f
    private var flap = 0f
    private var flapV = 0f
    private var lastPeg = 0
    private var elapsed = 0f
    private var stoppedAt = -1f
    private var payoffFrame = false
    private var winner = -1
    private var rocks = 0
    private var radius = 0.8f
    private var halfW = 0.46f
    private var cx = FloatArray(0)
    private var cy = FloatArray(0)
    private var cvx = FloatArray(0)
    private var cvy = FloatArray(0)
    private var ctint = FloatArray(0)

    override fun setup(env: SimEnv) {
        segments = env.params.i("segments", 8).coerceIn(MIN_SEGMENTS, MAX_SEGMENTS)
        val twoPi = 2f * PI.toFloat()
        val kind = env.params.choiceIndex("wheel").coerceIn(0, 2)
        val weight = FloatArray(segments) { 1f }
        jackpot = -1
        if (kind == 1) {
            jackpot = env.rng.int(0, segments)
            weight[jackpot] = JACKPOT_SHARE
        } else if (kind == 2) {
            for (k in 0 until segments) weight[k] = env.rng.range(0.35f, 1.8f)
        }
        val total = weight.sum()
        widths = FloatArray(segments) { weight[it] / total * twoPi }
        starts = FloatArray(segments)
        for (k in 1 until segments) starts[k] = starts[k - 1] + widths[k - 1]
        radius = min(env.halfWidth, 1f) * 0.86f
        halfW = env.halfWidth
        angle = env.rng.range(0f, 2f * PI.toFloat())
        // Friction alone would stop it in 11–15 s; the drag and the pegs
        // take their share too, so it stops in about 7–10.
        val seconds = env.rng.range(11f, 15f) * env.params.f("spin", 1f).coerceIn(0.3f, 3f)
        omega = env.rng.range(11f, 15f)
        friction = omega / seconds
        flap = 0f
        flapV = 0f
        lastPeg = pegIndex(angle)
        elapsed = 0f
        stoppedAt = -1f
        payoffFrame = false
        winner = -1
        rocks = 0
        cx = FloatArray(0); cy = FloatArray(0); cvx = FloatArray(0); cvy = FloatArray(0); ctint = FloatArray(0)
    }

    /**
     * Which gap between pegs is under the pointer, the pointer being straight
     * up — counted on from lap to lap, so a change means a peg went by.
     */
    private fun pegIndex(a: Float): Int {
        val twoPi = 2f * PI.toFloat()
        val rel = POINTER - a
        val lap = floor(rel / twoPi)
        val within = rel - lap * twoPi
        var k = segments - 1
        for (i in 1 until segments) if (within < starts[i]) { k = i - 1; break }
        return lap.toInt() * segments + k
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        // The flapper springs back to centre.
        flapV += (-flap * FLAP_SPRING - flapV * FLAP_DAMP) * h
        flap += flapV * h
        if (stoppedAt >= 0f) {
            stepConfetti(env, h)
            return
        }
        // Friction, and a little drag, so the end is a crawl rather than a halt.
        val sign = if (omega >= 0f) 1f else -1f
        omega -= sign * (friction + DRAG * abs(omega)) * h
        if (omega * sign < 0f) omega = 0f
        val before = angle
        angle += omega * h
        val peg = pegIndex(angle)
        if (peg != lastPeg) {
            // A peg reached the flapper. Getting past costs speed; without
            // enough, the wheel is thrown back into the gap it came from.
            // Every peg is the same peg, whatever the slices between: a cost
            // that followed the slice's width made narrow slices traps, and
            // the jackpot won twice its share.
            val need = PEG_COST * (2f * PI.toFloat() / segments)
            val energy = 0.5f * omega * omega
            if (energy < need && abs(omega) < ROCK_SPEED) {
                angle = before
                omega = -omega * 0.45f
                rocks++
                flapV += 10f * sign
                env.emit(EventKind.HIT, 0.25f, 0f, 0.45f)
            } else {
                omega = sign * sqrt(max(0f, omega * omega - 2f * need * PEG_TAX))
                lastPeg = peg
                flapV -= sign * min(40f, 6f + abs(omega) * 2f)
                // The click: higher round the wheel, so a lap is a scale.
                val k = ((peg % segments) + segments) % segments
                env.emit(EventKind.HIT, 0.3f + 0.6f * k / segments, 0f, (0.25f + 0.04f * abs(omega)).coerceAtMost(0.7f))
            }
        }
        if (abs(omega) < STOP_SPEED && friction > 0f) {
            omega = 0f
            stoppedAt = elapsed
            payoffFrame = true
            winner = ((pegIndex(angle) % segments) + segments) % segments
            env.emit(EventKind.DONE, 0.4f + 0.5f * winner / segments, 0f, 1f)
            if (winner == jackpot) repeat(8) { env.emit(EventKind.HIT, env.rng.range(0.6f, 1f), env.rng.range(-0.8f, 0.8f), 0.7f) }
            burst(env)
        }
    }

    private fun burst(env: SimEnv) {
        // The jackpot pays out three times the confetti, all of it gold.
        val n = if (winner == jackpot) CONFETTI * 3 else CONFETTI
        cx = FloatArray(n); cy = FloatArray(n); cvx = FloatArray(n); cvy = FloatArray(n); ctint = FloatArray(n)
        for (i in 0 until n) {
            cx[i] = env.rng.range(-0.1f, 0.1f)
            cy[i] = radius + 0.06f
            val a = env.rng.range(0.2f, PI.toFloat() - 0.2f)
            val v = env.rng.range(0.6f, 1.8f)
            cvx[i] = cos(a) * v; cvy[i] = sin(a) * v
            ctint[i] = if (winner == jackpot) -1f else env.rng.nextFloat()
        }
    }

    private fun stepConfetti(env: SimEnv, h: Float) {
        val g = env.laws.gravity * 0.08f
        for (i in cx.indices) {
            // Paper: the air stops it sideways sooner than falling. With one
            // drag for both it flew to the screen's edges and came down there
            // in two columns, leaving the wheel bare.
            cvx[i] *= 1f - min(1f, 3.2f * h)
            cvy[i] = cvy[i] * (1f - 1.5f * h) - g * h
            cx[i] += cvx[i] * h
            cy[i] += cvy[i] * h
            // The screen's sides are walls: it piles up rather than leaving.
            if (abs(cx[i]) > halfW) { cx[i] = cx[i].coerceIn(-halfW, halfW); cvx[i] = -cvx[i] * 0.3f }
        }
    }

    /** A second spin, right now, on top of whatever speed it has. */
    override fun hero(env: SimEnv) {
        if (stoppedAt >= 0f) return
        omega += if (omega >= 0f) 8f else -8f
        env.emit(EventKind.STEP, 0.7f, 0f, 0.8f)
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val twoPi = 2f * PI.toFloat()
        g.glow(0f, 0f, radius * 1.15f, palette.at(0.5f), 0.18f)
        for (k in 0 until segments) {
            val a0 = angle + starts[k]
            val s = widths[k]
            val c = sliceColour(palette, k)
            val won = k == winner
            val pulse = if (won) 0.5f + 0.5f * sin((elapsed - stoppedAt) * 10f) else 0f
            val col = if (won) Palette.brighten(c, 0.3f + 0.3f * pulse) else if (winner >= 0) Palette.withAlpha(c, 0.55f) else c
            // A wedge: a ring as thick as its radius with everything but this
            // segment's arc cut away.
            g.ring(0f, 0f, radius, radius, col, twoPi - s, a0 + s)
        }
        if (jackpot >= 0) {
            // The jackpot glints, so the eye keeps going back to it.
            val mid = angle + starts[jackpot] + widths[jackpot] * 0.5f
            val glint = 0.25f + 0.2f * sin(elapsed * 6f)
            g.glow(cos(mid) * radius * 0.75f, sin(mid) * radius * 0.75f, radius * 0.22f, GOLD, glint)
            // Edged in white, with a star at the rim: it can't be mistaken for
            // a yellow slice.
            for (e in 0..1) {
                val a = angle + starts[jackpot] + e * widths[jackpot]
                g.line(cos(a) * radius * 0.12f, sin(a) * radius * 0.12f, cos(a) * radius, sin(a) * radius, 0.007f, Palette.TEXT_PRIMARY)
            }
            val sx = cos(mid) * radius * 0.82f; val sy = sin(mid) * radius * 0.82f
            val star = radius * 0.08f * (1f + 0.15f * sin(elapsed * 6f))
            g.rect(sx, sy, star, star * 0.3f, Palette.TEXT_PRIMARY, mid)
            g.rect(sx, sy, star, star * 0.3f, Palette.TEXT_PRIMARY, mid + PI.toFloat() / 2f)
            g.rect(sx, sy, star * 0.7f, star * 0.21f, Palette.TEXT_PRIMARY, mid + PI.toFloat() / 4f)
            g.rect(sx, sy, star * 0.7f, star * 0.21f, Palette.TEXT_PRIMARY, mid - PI.toFloat() / 4f)
        }
        if (winner >= 0) {
            val mid = angle + starts[winner] + widths[winner] * 0.5f
            val big = winner == jackpot
            g.glow(cos(mid) * radius * 0.6f, sin(mid) * radius * 0.6f, radius * (if (big) 1.1f else 0.5f), sliceColour(palette, winner), if (big) 0.8f else 0.5f)
            // The winning slice outlined, so it stands clear of its dimmed
            // neighbours.
            val a0 = angle + starts[winner]; val sw = widths[winner]
            g.ring(0f, 0f, radius, 0.012f, Palette.TEXT_PRIMARY, twoPi - sw, a0 + sw)
            for (a in floatArrayOf(a0, a0 + sw)) g.line(0f, 0f, cos(a) * radius, sin(a) * radius, 0.01f, Palette.TEXT_PRIMARY)
        }
        // The pegs round the rim.
        for (k in 0 until segments) {
            val a = angle + starts[k]
            g.disc(cos(a) * radius * 0.97f, sin(a) * radius * 0.97f, 0.012f, Palette.TEXT_PRIMARY)
        }
        g.ring(0f, 0f, radius, 0.01f, Palette.TEXT_PRIMARY, alpha = 0.7f)
        // The hub.
        g.disc(0f, 0f, radius * 0.12f, 0xFF15171E.toInt())
        g.ring(0f, 0f, radius * 0.12f, 0.008f, Palette.TEXT_PRIMARY, alpha = 0.8f)
        // The flapper, at the top, pointing in.
        val tipX = sin(flap) * 0.09f
        val baseY = radius + 0.09f
        g.line(0f, baseY, tipX, radius - 0.02f, 0.022f, Palette.DANGER)
        g.disc(0f, baseY, 0.018f, Palette.DANGER)
        if (cx.isNotEmpty()) {
            // Scraps of paper, turning over as they fall and swaying.
            for (i in cx.indices) {
                val turn = i * 1.7f + elapsed * 7f
                val flip = 0.25f + 0.75f * abs(sin(turn * 1.3f))
                val sway = 0.012f * sin(i * 2.3f + elapsed * 4f)
                val c = if (ctint[i] < 0f) Palette.brighten(GOLD, 0.3f * abs(sin(i + elapsed * 9f))) else palette.at(ctint[i])
                g.rect(cx[i] + sway, cy[i], 0.009f, 0.0045f * flip + 0.001f, c, turn)
            }
        }
    }

    override fun contenders(env: SimEnv): IntArray =
        if (segments > MAX_PICKS) app.hypnotica.core.sim.NO_CONTENDERS
        else IntArray(segments) { sliceColour(env.palette, it) }

    /** Alternate light and dark so neighbours never blur together; the jackpot is gold. */
    private fun sliceColour(palette: Palette, k: Int): Int {
        if (k == jackpot) return GOLD
        val base = palette.at((k + 0.5f) / segments)
        return if (k % 2 == 0) base else Palette.brighten(base, 0.25f)
    }

    override fun winner(): Int = if (segments > MAX_PICKS) -1 else winner

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = stoppedAt >= 0f && elapsed - stoppedAt > 3f

    override fun counters(): List<Pair<String, String>> =
        if (winner >= 0) listOf("landed on" to (if (winner == jackpot) "JACKPOT!" else (winner + 1).toString()), "rocked back" to rocks.toString())
        else listOf("speed" to String.format(java.util.Locale.US, "%.1f", abs(omega)), "rocked back" to rocks.toString())

    companion object {
        const val MIN_SEGMENTS = 4
        const val MAX_SEGMENTS = 40
        val POINTER = (PI / 2).toFloat()
        const val FLAP_SPRING = 900f
        const val FLAP_DAMP = 18f
        const val DRAG = 0.05f
        /** What a peg asks of the wheel's energy, per radian of segment. */
        const val PEG_COST = 0.9f
        /** How much of that a peg actually takes when it is passed. */
        const val PEG_TAX = 0.15f
        /** Below this speed a peg can throw the wheel back. */
        const val ROCK_SPEED = 2.5f
        const val STOP_SPEED = 0.02f
        const val CONFETTI = 400
        const val MAX_PICKS = 8
        /** The jackpot slice, against an ordinary one. */
        const val JACKPOT_SHARE = 0.4f
        const val GOLD = 0xFFFFC23A.toInt()

        fun def(): SimDef = SimDef(
            id = "physics/wheel",
            title = "Spin the wheel",
            shelf = Shelf.PHYSICS,
            family = "Chance",
            blurb = "Click, click, click… click. Where does it stop? Will it hit the jackpot?",
            formats = listOf(TrapFormat.GUESS, TrapFormat.SNAP),
            // The spray and the confetti fall under the universe's gravity.
            lawKnobs = setOf(LawKnob.GRAVITY),
            heroLabel = "Spin harder",
            heroRestarts = false,
            defaultPalette = "Spectrum",
            typicalSeconds = 11f,
            params = listOf(
                Param.choice("wheel", "Wheel", listOf("Even", "Jackpot", "Lopsided"), 0, card = Card.DATA, input = ParamInput.TILES),
                Param.int("segments", "Segments", MIN_SEGMENTS, MAX_SEGMENTS, 8, card = Card.DATA),
                Param.float("spin", "Spin length", 0.3f, 3f, 1f, card = Card.MOTION)
            ),
            create = { WheelSim() }
        )
    }
}
