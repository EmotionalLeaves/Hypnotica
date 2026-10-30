package app.hypnotica.sims.physics

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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A sheet of bubble wrap and a few fingertips working across it.
 *
 * Each bubble swells under the press for a moment, then gives: a snap, a
 * puff of flecks, and a flat crinkled ring where it was. Each fingertip makes
 * for the nearest bubble still whole, so the sheet empties in trails that
 * cross and meet, and the last one left is hunted down. Tap the sheet to
 * press a bubble yourself.
 */
class BubbleWrapSim : Sim {

    private var count = 0
    private var bx = FloatArray(0)
    private var by = FloatArray(0)
    private var r = 0.03f
    private var state = IntArray(0)
    private var pressT = FloatArray(0)
    private var poppedAt = FloatArray(0)
    private var remaining = 0

    private var fingers = 3
    private val fx = FloatArray(MAX_FINGERS)
    private val fy = FloatArray(MAX_FINGERS)
    private val fvx = FloatArray(MAX_FINGERS)
    private val fvy = FloatArray(MAX_FINGERS)
    private val fTarget = IntArray(MAX_FINGERS)

    private val px = FloatArray(FLECKS)
    private val py = FloatArray(FLECKS)
    private val pvx = FloatArray(FLECKS)
    private val pvy = FloatArray(FLECKS)
    private val plife = FloatArray(FLECKS)
    private val pcol = FloatArray(FLECKS)
    private var pNext = 0

    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var finale = 0f

    override fun setup(env: SimEnv) {
        val want = env.params.i("bubbles", 160).coerceIn(12, MAX_BUBBLES)
        fingers = env.params.i("fingers", 3).coerceIn(1, MAX_FINGERS)
        // Packed in offset rows to fill the screen, about [want] of them.
        val w = 2f * env.halfWidth * 0.94f
        val h = 1.86f
        val spacing = sqrt(w * h / (want * 0.866f))
        val cols = max(2, (w / spacing).toInt())
        val rows = max(2, (h / (spacing * 0.866f)).toInt())
        r = spacing * 0.42f
        count = cols * rows
        bx = FloatArray(count); by = FloatArray(count)
        val ox = -(cols - 1) * spacing * 0.5f
        val oy = -(rows - 1) * spacing * 0.866f * 0.5f
        var k = 0
        for (row in 0 until rows) for (c in 0 until cols) {
            val shift = if (row % 2 == 1) spacing * 0.5f else 0f
            bx[k] = (ox + c * spacing + shift).coerceAtMost(-ox + spacing * 0.25f)
            by[k] = oy + row * spacing * 0.866f
            k++
        }
        state = IntArray(count)
        pressT = FloatArray(count)
        poppedAt = FloatArray(count) { -1f }
        remaining = count
        for (i in 0 until fingers) {
            fx[i] = env.rng.range(-w * 0.4f, w * 0.4f)
            fy[i] = env.rng.range(-0.8f, 0.8f)
            fvx[i] = 0f; fvy[i] = 0f
            fTarget[i] = -1
        }
        plife.fill(0f)
        elapsed = 0f; doneAt = -1f; payoffFrame = false; finale = 0f
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val a = abs(dt)
        elapsed += a
        finale = max(0f, finale - a * 0.6f)
        val speed = env.params.f("speed", 1f).coerceIn(0.1f, 6f)

        for (k in 0 until FLECKS) {
            if (plife[k] <= 0f) continue
            pvy[k] -= 1.6f * a
            pvx[k] *= 1f - 1.5f * a
            px[k] += pvx[k] * a
            py[k] += pvy[k] * a
            plife[k] -= a * 1.8f
        }

        // Bubbles under a press swell, then give.
        for (i in 0 until count) {
            if (state[i] != PRESSED) continue
            pressT[i] += a * speed
            if (pressT[i] >= SWELL_S) pop(env, i)
        }

        if (remaining == 0) return
        for (f in 0 until fingers) {
            var t = fTarget[f]
            if (t < 0 || state[t] != WHOLE) {
                t = nearest(fx[f], fy[f], f)
                fTarget[f] = t
            }
            if (t < 0) continue
            // Steered, not teleported: a fingertip with a little momentum, so
            // its path between bubbles curves.
            val dx = bx[t] - fx[f]; val dy = by[t] - fy[f]
            val d = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-5f)
            val top = FINGER_SPEED * speed
            fvx[f] += (dx / d * top - fvx[f]) * min(1f, a * 9f)
            fvy[f] += (dy / d * top - fvy[f]) * min(1f, a * 9f)
            fx[f] += fvx[f] * a
            fy[f] += fvy[f] * a
            if (d < r * 0.6f && state[t] == WHOLE) {
                state[t] = PRESSED
                pressT[t] = 0f
            }
        }
    }

    /** The closest whole bubble no other fingertip is already heading for. */
    private fun nearest(x: Float, y: Float, self: Int): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in 0 until count) {
            if (state[i] != WHOLE) continue
            var taken = false
            for (o in 0 until fingers) if (o != self && fTarget[o] == i) { taken = true; break }
            if (taken && remaining > fingers) continue
            val dx = bx[i] - x; val dy = by[i] - y
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    private fun pop(env: SimEnv, i: Int) {
        state[i] = POPPED
        poppedAt[i] = elapsed
        remaining--
        // Pitched by where it sat, left to right, a little different every time.
        val note = ((bx[i] + 0.5f) * 0.7f + env.rng.range(-0.08f, 0.08f)).coerceIn(0f, 1f)
        env.emit(EventKind.HIT, note, (bx[i] * 2f).coerceIn(-1f, 1f), env.rng.range(0.35f, 0.6f))
        for (j in 0 until 5) {
            val k = pNext
            pNext = (pNext + 1) % FLECKS
            val ang = env.rng.range(0f, 6.2831855f)
            val sp = env.rng.range(0.15f, 0.5f)
            px[k] = bx[i]; py[k] = by[i]
            pvx[k] = cos(ang) * sp; pvy[k] = sin(ang) * sp + 0.2f
            plife[k] = 1f
            pcol[k] = hue(i)
        }
        if (remaining == 0 && doneAt < 0f) {
            doneAt = elapsed
            payoffFrame = true
            finale = 1f
            env.emit(EventKind.DONE, 0.9f, 0f, 1f)
        }
    }

    override fun onInput(env: SimEnv, e: TouchEvent) {
        if (e.kind != TouchEvent.Kind.TAP && e.kind != TouchEvent.Kind.DRAG) return
        for (i in 0 until count) {
            if (state[i] != WHOLE) continue
            val dx = bx[i] - e.x; val dy = by[i] - e.y
            if (dx * dx + dy * dy <= r * r * 1.4f) { state[i] = PRESSED; pressT[i] = 0f }
        }
    }

    private fun hue(i: Int): Float {
        val t = atan2(by[i], bx[i]) / 6.2831855f + 0.5f
        return (t + sqrt(bx[i] * bx[i] + by[i] * by[i]) * 0.6f) % 1f
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        // The sheet itself, faintly.
        for (i in 0 until count) {
            val c = pal.at(hue(i))
            when (state[i]) {
                WHOLE -> {
                    g.disc(bx[i], by[i], r, Palette.lerp(c, Palette.BG, 0.55f), 0.9f)
                    // A highlight up and to the left: it reads as a dome.
                    g.disc(bx[i] - r * 0.32f, by[i] + r * 0.32f, r * 0.28f, Palette.TEXT_PRIMARY, 0.22f)
                    g.ring(bx[i], by[i], r, r * 0.12f, c, 0f, 0f, 0.55f)
                }
                PRESSED -> {
                    val p = (pressT[i] / SWELL_S).coerceIn(0f, 1f)
                    val rr = r * (1f + 0.18f * p)
                    g.disc(bx[i], by[i], rr, Palette.lerp(c, Palette.BG, 0.4f - 0.3f * p))
                    g.disc(bx[i] - rr * 0.32f, by[i] + rr * 0.32f, rr * 0.28f, Palette.TEXT_PRIMARY, 0.3f + 0.3f * p)
                    g.ring(bx[i], by[i], rr, rr * 0.14f, Palette.brighten(c, 0.3f * p), 0f, 0f, 0.8f)
                }
                else -> {
                    val since = elapsed - poppedAt[i]
                    if (since < 0.25f) g.glow(bx[i], by[i], r * 2.2f, Palette.brighten(c, 0.4f), (0.25f - since) * 2f)
                    // Flat and crinkled where it was.
                    g.ring(bx[i], by[i], r * 0.82f, r * 0.08f, c, 0.9f, (i * 1.7f) % 6.28f, 0.35f)
                }
            }
        }
        for (k in 0 until FLECKS) {
            if (plife[k] <= 0f) continue
            g.disc(px[k], py[k], r * 0.12f, pal.at(pcol[k]), plife[k].coerceIn(0f, 1f))
        }
        // The fingertips: soft pale prints.
        if (remaining > 0) for (f in 0 until fingers) {
            g.glow(fx[f], fy[f], r * 2.4f, Palette.TEXT_PRIMARY, 0.18f)
            g.disc(fx[f], fy[f], r * 0.55f, Palette.TEXT_PRIMARY, 0.35f)
        }
        if (finale > 0f) g.glow(0f, 0f, 1.2f, Palette.TEXT_PRIMARY, finale * 0.25f)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && elapsed - doneAt > 3f

    override fun counters(): List<Pair<String, String>> = listOf(
        "popped" to "${count - remaining} / $count"
    )

    companion object {
        const val MAX_BUBBLES = 2000
        const val MAX_FINGERS = 12
        const val FLECKS = 240
        const val WHOLE = 0
        const val PRESSED = 1
        const val POPPED = 2
        const val SWELL_S = 0.09f
        const val FINGER_SPEED = 1.0f

        fun def(): SimDef = SimDef(
            id = "physics/bubble-wrap",
            title = "Bubble wrap",
            shelf = Shelf.PHYSICS,
            family = "Pressure & bursting",
            blurb = "Every bubble swells, then gives. Tap to pop one yourself.",
            formats = listOf(TrapFormat.SONG, TrapFormat.LAST_STANDING, TrapFormat.CUT),
            heroLabel = "Fresh sheet",
            defaultPalette = "Ocean",
            typicalSeconds = 20f,
            params = listOf(
                Param.int("bubbles", "Bubbles", 12, MAX_BUBBLES, 160, card = Card.DATA, logScale = true),
                Param.int("fingers", "Fingertips", 1, MAX_FINGERS, 3, card = Card.DATA),
                Param.float("speed", "Speed", 0.3f, 4f, 1f, card = Card.MOTION, logScale = true)
            ),
            create = { BubbleWrapSim() }
        )
    }
}
