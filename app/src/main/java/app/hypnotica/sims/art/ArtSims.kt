package app.hypnotica.sims.art

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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Polyrhythm wheels: dots going round circles at whole-number speeds, each
 * sounding a note as it crosses the line.
 *
 * They start together, drift through every combination, and come back into line
 * exactly once. That realignment is the payoff, and the pattern of notes in
 * between is the music.
 */
class PolyrhythmSim : Sim {

    private var count = 12
    private var baseLoops = 24
    private var cycleSeconds = 24f
    private var elapsed = 0f
    private var lastPhase = FloatArray(0)
    private var hitFlash = FloatArray(0)
    private var payoffFrame = false
    private var hits = 0L
    private var arcMode = true

    override fun setup(env: SimEnv) {
        val p = env.params
        count = p.i("wheels", 12).coerceIn(2, 48)
        baseLoops = p.i("loops", 24).coerceIn(2, 120)
        cycleSeconds = p.f("cycle", 24f).coerceIn(4f, 120f)
        arcMode = p.choiceIndex("layout") == 0
        lastPhase = FloatArray(count)
        hitFlash = FloatArray(count)
        elapsed = 0f
        payoffFrame = false
        hits = 0
    }

    private fun loopsFor(i: Int) = baseLoops + i

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        // The wheels turn whichever way time runs, but the arc always advances:
        // a negative elapsed sent every wheel past its own phase every step,
        // which was a thousand notes a second and no payoff at all.
        elapsed += abs(dt)
        for (i in 0 until count) {
            val phase = (elapsed / cycleSeconds) * loopsFor(i)
            val wrapped = phase - kotlin.math.floor(phase)
            if (wrapped < lastPhase[i]) {
                hits++
                hitFlash[i] = 1f
                val t = if (count <= 1) 0.5f else i.toFloat() / (count - 1)
                env.emit(EventKind.CROSS, 1f - t, (t * 2f - 1f) * 0.85f, 0.75f)
            }
            lastPhase[i] = wrapped
            hitFlash[i] = max(0f, hitFlash[i] - abs(dt) * 3.4f)
        }
        // Everything back in line at once: the end of one full cycle.
        val cyclePhase = elapsed % cycleSeconds
        if (cyclePhase < abs(dt) * 2f && elapsed > abs(dt) * 4f) {
            payoffFrame = true
            env.emit(EventKind.DONE, 1f, 0f, 1f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        if (arcMode) {
            val inner = 0.14f
            val outer = 0.93f
            for (i in 0 until count) {
                val t = if (count <= 1) 0.5f else i.toFloat() / (count - 1)
                val r = inner + (outer - inner) * (i + 1f) / count
                val c = palette.at(t)
                val flash = hitFlash[i]
                // The orbit lights up as its dot strikes the line; at rest a
                // fifth of the way there it vanished on a phone.
                g.ring(0f, 0f, r, 0.004f, Palette.withAlpha(c, 0.45f + 0.5f * flash), alpha = 0.55f + 0.45f * flash)
                val phase = (elapsed / cycleSeconds) * loopsFor(i)
                // Phase zero is the top, where the trigger line is drawn, and
                // the wheels turn like clock hands.
                //
                // This was -PI/2, which put phase zero at the BOTTOM while the
                // line points up — so every wheel made its sound a half turn
                // away from the mark it is supposed to be struck on, and the
                // dot that was glowing was always the one furthest from the
                // line. The hit itself was always right; only the picture of
                // it was upside down.
                val a = PI.toFloat() / 2f - (phase % 1f) * 2f * PI.toFloat()
                val x = cos(a) * r
                val y = sin(a) * r
                // A comet's tail behind it, back along the orbit.
                for (k in 1..TAIL) {
                    // Spaced by angle, not time: a fast orbit's tail came out
                    // as a row of separate dots.
                    val back = a + k * TAIL_STEP
                    g.point(cos(back) * r, sin(back) * r, 0.028f * (1f - k / (TAIL + 1f)), Palette.withAlpha(c, 0.55f * (1f - k / (TAIL + 1f))))
                }
                g.glow(x, y, 0.05f + flash * 0.09f, c, 0.4f + flash * 0.5f)
                g.disc(x, y, 0.024f + flash * 0.014f, Palette.brighten(c, flash * 0.5f))
            }
            g.line(0f, 0f, 0f, outer + 0.04f, 0.0025f, Palette.withAlpha(Palette.TEXT_SECONDARY, 0.5f))
        } else {
            val halfW = env.halfWidth * 0.9f
            for (i in 0 until count) {
                val t = if (count <= 1) 0.5f else i.toFloat() / (count - 1)
                val y = 0.9f - (i + 0.5f) * (1.8f / count)
                val c = palette.at(t)
                g.line(-halfW, y, halfW, y, 0.0018f, Palette.withAlpha(c, 0.3f))
                val phase = (elapsed / cycleSeconds) * loopsFor(i)
                // A bounce along the line rather than a wrap, so it reads as a pendulum.
                val x = -halfW + abs(((phase * 2f) % 2f) - 1f) * 2f * halfW
                val flash = hitFlash[i]
                g.glow(x, y, 0.05f + flash * 0.08f, c, 0.4f + flash * 0.5f)
                g.disc(x, y, 0.015f, Palette.brighten(c, flash * 0.5f))
            }
        }
    }

    /** Drawn at a fixed size; on a phone held upright the view pulls back to fit it. */
    override fun frameHalfWidth(env: SimEnv): Float = 0.95f

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "wheels" to count.toString(),
        "hits" to hits.toString(),
        "cycle" to String.format(java.util.Locale.US, "%.0f%%", (elapsed % cycleSeconds) / cycleSeconds * 100f)
    )

    companion object {
        const val TAIL = 10
        /** Radians between the tail's dots, which overlap into a streak. */
        const val TAIL_STEP = 0.035f
        fun def(): SimDef = SimDef(
            id = "art/polyrhythm",
            title = "Polyrhythm wheels",
            shelf = Shelf.ART,
            family = "Music math",
            blurb = "Whole-number speeds drifting apart and snapping back together.",
            formats = listOf(TrapFormat.SONG, TrapFormat.LOOP),
            heroLabel = "Restart the cycle",
            defaultPalette = "Aurora",
            defaultSound = "Marimba",
            typicalSeconds = 26f,
            loopSeconds = 24.0f,
            params = listOf(
                Param.choice("layout", "Layout", listOf("Rings", "Lines"), 0, card = Card.LOOK, input = ParamInput.TILES),
                Param.int("wheels", "Wheels", 2, 48, 12, card = Card.DATA),
                Param.int("loops", "Base loops per cycle", 2, 120, 24, card = Card.MOTION),
                Param.float("cycle", "Cycle length", 4f, 120f, 24f, card = Card.MOTION, unit = "s")
            ),
            create = { PolyrhythmSim() }
        )
    }
}

/**
 * A harmonograph: two damped pendulums driving a pen. Nearly-equal frequencies
 * make the figure precess, so the line never quite repeats as it decays.
 */
class HarmonographSim : Sim {

    private var trailX = FloatArray(0)
    private var trailY = FloatArray(0)
    private var head = 0
    private var filled = 0
    private var t = 0.0
    private var carry = 0f
    private var payoffFrame = false
    private val f = FloatArray(4)
    private val p = FloatArray(4)
    private val amp = FloatArray(4)
    private val decay = FloatArray(4)
    private var settled = false

    override fun setup(env: SimEnv) {
        val params = env.params
        val len = params.i("trail", 14000).coerceIn(500, 120000)
        trailX = FloatArray(len)
        trailY = FloatArray(len)
        head = 0
        filled = 0
        t = 0.0
        carry = 0f
        payoffFrame = false
        settled = false
        val detune = params.f("detune", 0.004f)
        val base = params.f("base", 2f)
        val ratio = params.f("ratio", 3f)
        f[0] = base
        f[1] = base + detune
        f[2] = ratio
        f[3] = ratio + detune * 1.7f
        val d = params.f("damping", 0.012f)
        for (i in 0 until 4) {
            p[i] = env.rng.range(0f, 2f * PI.toFloat())
            amp[i] = env.rng.range(0.32f, 0.47f)
            decay[i] = d * env.rng.range(0.7f, 1.3f)
        }
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        carry += abs(dt) * env.params.f("rate", 900f)
        var budget = min(carry.toInt(), 20000)
        carry -= budget
        val h = 0.0016
        while (budget-- > 0) {
            t += h
            val tf = t.toFloat()
            val x = amp[0] * sin(tf * f[0] + p[0]) * exp(-decay[0] * tf) +
                amp[1] * sin(tf * f[1] + p[1]) * exp(-decay[1] * tf)
            val y = amp[2] * sin(tf * f[2] + p[2]) * exp(-decay[2] * tf) +
                amp[3] * sin(tf * f[3] + p[3]) * exp(-decay[3] * tf)
            trailX[head] = x
            trailY[head] = y
            head = (head + 1) % trailX.size
            if (filled < trailX.size) filled++
        }
        val envelope = exp(-decay[0] * t.toFloat())
        env.emit(EventKind.STEP, envelope.coerceIn(0f, 1f), 0f, 0.25f)
        if (!settled && envelope < 0.3f) {
            settled = true
            payoffFrame = true
            env.emit(EventKind.DONE, 0.7f, 0f, 1f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        g.reservePoints(filled)
        val size = env.params.f("dot", 0.0035f)
        for (k in 0 until filled) {
            val idx = (head - 1 - k + trailX.size * 2) % trailX.size
            val age = k.toFloat() / filled
            g.point(
                trailX[idx], trailY[idx], size,
                Palette.withAlpha(palette.at((1f - age) * 0.8f), (1f - age * 0.75f))
            )
        }
    }

    /** Drawn at a fixed size; on a phone held upright the view pulls back to fit it. */
    override fun frameHalfWidth(env: SimEnv): Float = 0.56f

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> =
        listOf("ink" to String.format(java.util.Locale.US, "%,d", filled))

    companion object {
        fun def(): SimDef = SimDef(
            id = "art/harmonograph",
            title = "Harmonograph",
            shelf = Shelf.ART,
            family = "Generative art",
            blurb = "Two pendulums and a pen, drawing a figure that never repeats.",
            formats = listOf(TrapFormat.GROW, TrapFormat.REVEAL),
            heroLabel = "New figure",
            defaultPalette = "Candy",
            typicalSeconds = 26f,
            params = listOf(
                Param.float("base", "First frequency", 0.5f, 8f, 2f, card = Card.DATA),
                Param.float("ratio", "Second frequency", 0.5f, 8f, 3f, card = Card.DATA),
                Param.float("detune", "Detune", 0f, 0.05f, 0.004f, card = Card.DATA),
                Param.float("damping", "Damping", 0.004f, 0.2f, 0.05f, card = Card.MOTION),
                Param.int("trail", "Trail", 500, 120000, 14000, card = Card.LOOK, logScale = true),
                Param.float("dot", "Line weight", 0.001f, 0.01f, 0.0035f, card = Card.LOOK),
                Param.float("rate", "Speed", 100f, 8000f, 900f, card = Card.MOTION, logScale = true)
            ),
            create = { HarmonographSim() }
        )
    }
}

/**
 * Circle packing: drop a circle, grow it until it touches something, repeat.
 * The gaps filling in is the whole satisfaction.
 */
class CirclePackingSim : Sim {

    /** The payoff fires once; without this the bell retriggers every step. */
    private var packed = false
    private var spawnCarry = 0f

    private var cx = FloatArray(0)
    private var cy = FloatArray(0)
    private var cr = FloatArray(0)
    private var tint = FloatArray(0)
    private var growing = BooleanArray(0)
    private var count = 0
    private var target = 900
    private var maxRadius = 0.3f
    private var payoffFrame = false
    private var attempts = 0
    private var coverage = 0f

    override fun setup(env: SimEnv) {
        val p = env.params
        target = p.i("circles", 900).coerceIn(10, 20000)
        maxRadius = p.f("maxsize", 0.25f)
        cx = FloatArray(target); cy = FloatArray(target)
        cr = FloatArray(target); tint = FloatArray(target)
        growing = BooleanArray(target)
        count = 0
        attempts = 0
        payoffFrame = false
        coverage = 0f
        packed = false
        spawnCarry = 0f
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val halfW = env.halfWidth
        val growth = env.params.f("growth", 0.45f) * abs(dt)
        val spawn = env.params.f("spawn", 26f)

        // Grow whatever is still growing.
        var stillGrowing = 0
        for (i in 0 until count) {
            if (!growing[i]) continue
            val next = cr[i] + growth
            if (next > maxRadius || touchesEdge(cx[i], cy[i], next, halfW) || touchesAny(i, next)) {
                growing[i] = false
                env.emit(EventKind.HIT, (cr[i] / maxRadius).coerceIn(0f, 1f), (cx[i] / halfW).coerceIn(-1f, 1f), 0.5f)
            } else {
                cr[i] = next
                stillGrowing++
            }
        }

        // Drop new seeds into whatever gaps remain.
        // Accumulated, not truncated: at 120 steps a second, spawn * dt is under
        // one for every rate below 120, so the lower two-thirds of the slider
        // used to spawn nothing and the fallback did all the work.
        spawnCarry += spawn * abs(dt)
        var toSpawn = spawnCarry.toInt() + if (stillGrowing == 0) 1 else 0
        spawnCarry -= (spawnCarry.toInt()).toFloat()
        var guard = 400
        while (toSpawn > 0 && count < target && guard-- > 0) {
            val x = env.rng.range(-halfW * 0.98f, halfW * 0.98f)
            val y = env.rng.range(-0.98f, 0.98f)
            attempts++
            if (!insideAny(x, y)) {
                cx[count] = x; cy[count] = y; cr[count] = 0.004f
                tint[count] = env.rng.nextFloat()
                growing[count] = true
                count++
                toSpawn--
            }
        }

        var area = 0f
        for (i in 0 until count) area += cr[i] * cr[i] * PI.toFloat()
        coverage = (area / (4f * halfW)).coerceIn(0f, 1f)

        if (!packed && (count >= target || coverage > 0.78f)) {
            packed = true
            payoffFrame = true
            env.emit(EventKind.DONE, 0.85f, 0f, 1f)
        }
    }

    private fun touchesEdge(x: Float, y: Float, r: Float, halfW: Float): Boolean =
        x - r < -halfW || x + r > halfW || y - r < -1f || y + r > 1f

    private fun touchesAny(self: Int, r: Float): Boolean {
        for (j in 0 until count) {
            if (j == self) continue
            val dx = cx[j] - cx[self]
            val dy = cy[j] - cy[self]
            if (dx * dx + dy * dy < (r + cr[j]) * (r + cr[j])) return true
        }
        return false
    }

    private fun insideAny(x: Float, y: Float): Boolean {
        for (j in 0 until count) {
            val dx = cx[j] - x
            val dy = cy[j] - y
            if (dx * dx + dy * dy < cr[j] * cr[j]) return true
        }
        return false
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        g.reserveInstances(count * 2)
        for (i in 0 until count) {
            val c = palette.at(tint[i])
            if (growing[i]) g.glow(cx[i], cy[i], cr[i] * 2.2f, c, 0.4f)
            g.ring(cx[i], cy[i], cr[i], max(cr[i] * 0.22f, 0.0022f), c, alpha = 0.95f)
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = count >= target

    override fun counters(): List<Pair<String, String>> = listOf(
        "circles" to count.toString(),
        "coverage" to String.format(java.util.Locale.US, "%.0f%%", coverage * 100f)
    )

    companion object {
        fun def(): SimDef = SimDef(
            id = "art/circle-packing",
            title = "Circle packing",
            shelf = Shelf.ART,
            family = "Generative art",
            blurb = "Each circle grows until it touches. The gaps fill themselves.",
            formats = listOf(TrapFormat.PERFECT_FIT, TrapFormat.FILL, TrapFormat.GROW),
            heroLabel = "Pack again",
            defaultPalette = "Ocean",
            typicalSeconds = 26f,
            params = listOf(
                Param.int("circles", "Circles", 10, 20000, 900, card = Card.DATA, logScale = true),
                // To 0.25, the largest any circle gets: new seeds keep landing
                // in the gaps, so a circle meets a neighbour long before it
                // meets this limit. Measured, every value above 0.25 drew the
                // identical picture — the shipped 0.3 included, so 0.25 ships
                // the same packing — which was the top three fifths of the
                // slider doing nothing.
                Param.float("maxsize", "Largest circle", 0.03f, 0.25f, 0.25f, card = Card.DATA),
                Param.float("growth", "Growth rate", 0.05f, 2f, 0.45f, card = Card.MOTION),
                Param.float("spawn", "New circles", 1f, 400f, 26f, card = Card.MOTION, unit = "/s", logScale = true)
            ),
            create = { CirclePackingSim() }
        )
    }
}

/**
 * Spirograph: a small wheel rolling inside a big one, with a pen in a hole.
 * The ratio of the wheels decides how many points the rosette has.
 */
class SpirographSim : Sim {

    private var trailX = FloatArray(0)
    private var trailY = FloatArray(0)
    private var count = 0
    private var theta = 0.0

    /** The turn at which the pen comes back to its starting point. */
    private var closingTheta = 0.0
    private var bigR = 0.9f
    private var smallR = 0.27f
    private var penOffset = 0.2f
    private var payoffFrame = false
    private var closedAt = -1f
    private var restAfterClose = 0f

    /** Seconds spent looking at the closed curve before the run ends. */
    private val REST_AFTER_CLOSE = 5f
    private var carry = 0f

    override fun setup(env: SimEnv) {
        val p = env.params
        bigR = 0.9f
        smallR = p.f("wheel", 0.27f)
        penOffset = p.f("pen", 0.2f)
        val len = p.i("points", 30000).coerceIn(500, 300000)
        trailX = FloatArray(len)
        trailY = FloatArray(len)
        count = 0
        theta = 0.0
        payoffFrame = false
        closedAt = -1f
        restAfterClose = 0f
        carry = 0f

        // R and r in lowest terms: the curve closes after r / gcd(R - r, r)
        // revolutions. Scaled to whole numbers first, because the radii are
        // slider values rather than integers.
        val bigSteps = Math.round(bigR * 1000f).toInt()
        val smallSteps = Math.round(smallR * 1000f).toInt().coerceAtLeast(1)
        var a = bigSteps - smallSteps
        var b = smallSteps
        while (b != 0) { val t = b; b = a % b; a = t }
        val gcd = if (a == 0) 1 else kotlin.math.abs(a)
        val turns = (smallSteps / gcd).coerceIn(1, 400)
        closingTheta = turns * 2.0 * PI
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        carry += abs(dt) * env.params.f("rate", 900f)
        var budget = min(carry.toInt(), 20000)
        carry -= budget
        val step = 0.004
        val k = ((bigR - smallR) / smallR).toDouble()
        while (budget-- > 0 && count < trailX.size) {
            // theta is a Double: a Float accumulated in 0.004 steps three
            // hundred thousand times has lost the precision exactly where the
            // curve is supposed to close on itself.
            theta += step
            val x = ((bigR - smallR) * cos(theta) + penOffset * cos(k * theta)).toFloat()
            val y = ((bigR - smallR) * sin(theta) - penOffset * sin(k * theta)).toFloat()
            trailX[count] = x
            trailY[count] = y
            count++
            if (count % 160 == 0) {
                env.emit(EventKind.STEP, ((y + 1f) * 0.5f).coerceIn(0f, 1f), (x).coerceIn(-1f, 1f) * 0.7f, 0.3f)
            }
        }
        // The curve closes when the pen returns to where it started, which
        // happens after a whole number of turns set by the wheel ratio — not
        // when the buffer happens to be seven tenths full.
        if (closedAt < 0f && theta >= closingTheta) {
            closedAt = abs(env.time)
            payoffFrame = true
            env.emit(EventKind.DONE, 0.8f, 0f, 1f)
        }
        // Once it has closed, the drawing is the drawing: a few seconds to look
        // at it and the run is done.
        if (closedAt >= 0f) restAfterClose += abs(dt)
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        g.reservePoints(count)
        val size = env.params.f("dot", 0.0032f)
        for (i in 0 until count) {
            g.point(trailX[i], trailY[i], size, palette.at((i.toFloat() / max(1, count)) % 1f))
        }
        // The rolling wheel, so the mechanism is visible, not just the result.
        val cxw = ((bigR - smallR) * cos(theta)).toFloat()
        val cyw = ((bigR - smallR) * sin(theta)).toFloat()
        g.ring(0f, 0f, bigR, 0.003f, Palette.withAlpha(Palette.TEXT_SECONDARY, 0.4f))
        g.ring(cxw, cyw, smallR, 0.003f, Palette.withAlpha(Palette.ACCENT, 0.7f))
        if (count > 0) {
            g.glow(trailX[count - 1], trailY[count - 1], 0.055f, Palette.SUCCESS, 0.7f)
        }
    }

    /** Drawn at a fixed size; on a phone held upright the view pulls back to fit it. */
    override fun frameHalfWidth(env: SimEnv): Float = 0.92f

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean =
        count >= trailX.size || (closedAt >= 0f && restAfterClose > REST_AFTER_CLOSE)

    override fun counters(): List<Pair<String, String>> =
        listOf("ink" to String.format(java.util.Locale.US, "%,d", count))

    companion object {
        fun def(): SimDef = SimDef(
            id = "art/spirograph",
            title = "Spirograph",
            shelf = Shelf.ART,
            family = "Generative art",
            blurb = "A wheel inside a wheel, and the ratio decides the rosette.",
            formats = listOf(TrapFormat.GROW),
            heroLabel = "New ratio",
            defaultPalette = "Spectrum",
            typicalSeconds = 10f,
            params = listOf(
                Param.float("wheel", "Inner wheel", 0.05f, 0.6f, 0.27f, card = Card.DATA),
                Param.float("pen", "Pen offset", 0.02f, 0.5f, 0.2f, card = Card.DATA),
                Param.int("points", "Ink", 500, 300000, 30000, card = Card.LOOK, logScale = true),
                Param.float("dot", "Line weight", 0.001f, 0.01f, 0.0032f, card = Card.LOOK),
                // Reaches what the ink slider offers: three hundred thousand
                // points at nine hundred a second is five and a half minutes.
                Param.float("rate", "Speed", 100f, 20000f, 900f, card = Card.MOTION, logScale = true)
            ),
            create = { SpirographSim() }
        )
    }
}

object ArtSims {
    fun defs(): List<SimDef> = listOf(
        PolyrhythmSim.def(),
        HarmonographSim.def(),
        CirclePackingSim.def(),
        SpirographSim.def(),
        LSystemSim.def(),
        MarbleMusicSim.def()
    )
}
