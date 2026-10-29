package app.hypnotica.sims.math

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The birthday paradox, one room at a time.
 *
 * People walk in with birthdays drawn evenly from the year, and each is
 * strung to their day on the ring. Twenty-three people feel far too few to
 * share a birthday among 365 days, but they make 253 pairs, and the chance
 * that some pair matches is already 50.7% — the curve below. A room stops
 * on its first match; then the rooms come faster and the tally of how many
 * matched by 23 settles towards a half, the dots stacking up under the curve.
 */
class BirthdayParadoxSim : Sim {

    private var days = 365
    private var rooms = 30
    private var span = 61
    private var half = 23
    /** P(some shared birthday) with n people, n = 0..span. */
    private var chance = FloatArray(0)

    // The room now.
    private var dayOf = IntArray(0)
    private var count = 0
    private var holder = IntArray(0)
    private var flying = -1
    private var flyT = 0f
    private var flyLen = 0.35f
    private var wait = 0f
    private var matchA = -1
    private var matchB = -1
    private var sinceMatch = 0f
    private var clearing = 0f

    // Every room so far.
    private var room = 0
    private var matchedAt = IntArray(0)
    private var byHalf = 0
    private var firstMatch = false
    private var payoffFrame = false
    private var allDone = false
    private var sinceAll = 0f
    private var sinceNote = 1f

    override fun setup(env: SimEnv) {
        val p = env.params
        days = p.i("days", 365).coerceIn(12, 2000)
        rooms = p.i("rooms", 30).coerceIn(1, 500)
        span = max(8, (3.2f * sqrt(days.toFloat())).roundToInt())
        chance = FloatArray(span + 2)
        var none = 1.0
        half = span
        for (n in 0..span + 1) {
            chance[n] = (1.0 - none).toFloat()
            if (chance[n] >= 0.5f && half == span) half = n
            if (n < days) none *= 1.0 - n.toDouble() / days else none = 0.0
        }
        // Enough seats for anyone who could walk in before a match is forced.
        dayOf = IntArray(days + 1)
        holder = IntArray(days)
        matchedAt = IntArray(rooms)
        room = 0
        byHalf = 0
        firstMatch = false
        payoffFrame = false
        allDone = false
        sinceAll = 0f
        sinceNote = 1f
        newRoom()
        wait = LEAD_IN
    }

    private fun newRoom() {
        count = 0
        java.util.Arrays.fill(holder, -1)
        flying = -1
        flyT = 0f
        matchA = -1; matchB = -1
        sinceMatch = 0f
        clearing = 0f
        wait = 0f
    }

    /** People a second in room r: the first at the chosen pace, then quicker. */
    private fun rate(env: SimEnv): Float {
        val base = env.params.f("speed", 2f).coerceIn(0.2f, 50f)
        return base * min(10f, 1f + 1.4f * room)
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val a = abs(dt)
        sinceNote += a
        if (allDone) { sinceAll += a; return }
        val r = rate(env)
        if (matchA >= 0) {
            sinceMatch += a
            val hold = if (room == 0) FIRST_HOLD else max(0.5f, 2.2f / sqrt(r))
            if (sinceMatch > hold) {
                clearing += a / CLEAR_SECONDS
                if (clearing >= 1f) {
                    room++
                    if (room >= rooms) { allDone = true; clearing = 1f } else newRoom()
                }
            }
            return
        }
        if (flying >= 0) {
            flyT += a
            if (flyT >= flyLen) land(env)
            return
        }
        wait -= a
        if (wait > 0f) return
        // The next person walks in.
        if (count >= dayOf.size) return
        dayOf[count] = env.rng.int(0, days)
        flying = count
        count++
        flyLen = min(FLY_SECONDS, 0.75f / r)
        flyT = 0f
        wait = 1f / r - flyLen
    }

    /** A thread reaches its day: the moment a person is in the room for good. */
    private fun land(env: SimEnv) {
        val i = flying
        flying = -1
        val d = dayOf[i]
        val (sx, _) = slot(d, layoutFor(env))
        val pan = (sx / env.halfWidth).coerceIn(-1f, 1f)
        val other = holder[d]
        if (other >= 0) {
            matchA = other; matchB = i
            matchedAt[room] = count
            if (count <= half) byHalf++
            if (!firstMatch) {
                firstMatch = true
                payoffFrame = true
            } else {
                env.emit(EventKind.HIT, (count.toFloat() / span).coerceIn(0f, 1f), pan, 0.7f)
            }
            return
        }
        holder[d] = i
        // Each arrival sounds its day, but no closer together than the ear
        // can follow once the rooms speed up.
        if (sinceNote >= MIN_NOTE_GAP) {
            sinceNote = 0f
            env.emit(EventKind.WRITE, d.toFloat() / days, pan, 0.32f)
        }
    }

    // ------------------------------------------------------------ drawing

    private class Layout(
        val rx: Float, val ry: Float, val rr: Float,
        val left: Float, val right: Float, val axisY: Float, val top: Float, val dotBottom: Float
    )

    private var lay: Layout? = null
    private var layAspect = -1f

    private fun layoutFor(env: SimEnv): Layout {
        val cached = lay
        if (cached != null && layAspect == env.aspect) return cached
        val hw = env.halfWidth
        val l = if (hw < 0.9f) {
            val rr = min(hw * 0.86f, 0.5f)
            val ry = 0.95f - rr - 0.03f
            val top = ry - rr - 0.16f
            Layout(0f, ry, rr, -hw * 0.86f, hw * 0.86f, -0.66f, top, -0.95f)
        } else {
            val rr = 0.8f
            val rx = -hw + rr + 0.12f
            Layout(rx, 0.02f, rr, rx + rr + 0.3f, hw - 0.12f, -0.42f, 0.82f, -0.86f)
        }
        lay = l; layAspect = env.aspect
        return l
    }

    /** Where day d sits on the ring: January at the top, running clockwise. */
    private fun angleOf(d: Float): Float = (PI / 2.0 - 2.0 * PI * (d + 0.5f) / days).toFloat()

    private fun slot(d: Int, l: Layout): Pair<Float, Float> {
        val a = angleOf(d.toFloat())
        return (l.rx + l.rr * cos(a)) to (l.ry + l.rr * sin(a))
    }

    /** Person i's seat in the room: packed like seeds, so any number fit. */
    private fun seat(i: Int, l: Layout): Pair<Float, Float> {
        val rIn = l.rr * 0.66f
        val r = rIn * sqrt((i + 0.5f) / max(span, count))
        val a = i * 2.39996f + PI.toFloat() * 0.5f
        return (l.rx + r * cos(a)) to (l.ry + r * sin(a))
    }

    private fun dayColour(pal: Palette, d: Int): Int = pal.at(0.04f + 0.7f * d / days)

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        val l = layoutFor(env)
        g.reserveInstances(days + count * 4 + span * 4 + rooms * 2 + 64)
        val fade = 1f - clearing.coerceIn(0f, 1f)
        val matched = matchA >= 0
        val glint = if (matched) min(1f, sinceMatch / 0.35f) else 0f

        // The year: a faint tick per day, a brighter one at each month.
        g.disc(l.rx, l.ry, l.rr * 1.04f, Palette.lerp(Palette.BG, pal.at(0.4f), 0.05f))
        val tickW = max(0.0016f, (2f * PI.toFloat() * l.rr / days) * 0.35f)
        for (d in 0 until days) {
            val a = angleOf(d.toFloat())
            val c = cos(a); val s = sin(a)
            val inner = l.rr - 0.012f
            g.line(l.rx + inner * c, l.ry + inner * s, l.rx + l.rr * c, l.ry + l.rr * s, tickW, Palette.TEXT_SECONDARY, 0.22f)
        }
        if (days == 365) {
            var start = 0
            for (m in MONTH_DAYS) {
                val a = (PI / 2.0 - 2.0 * PI * start / days).toFloat()
                val c = cos(a); val s = sin(a)
                g.line(l.rx + (l.rr - 0.035f) * c, l.ry + (l.rr - 0.035f) * s, l.rx + (l.rr + 0.012f) * c, l.ry + (l.rr + 0.012f) * s, 0.003f, Palette.TEXT_SECONDARY, 0.6f)
                start += m
            }
        }
        g.ring(l.rx, l.ry, l.rr + 0.004f, 0.0025f, Palette.TEXT_SECONDARY, alpha = 0.35f)

        // Threads from each person to their day, then the people and days.
        val seatR = (l.rr * 0.66f / sqrt(max(span, count).toFloat()) * 0.5f).coerceIn(0.006f, 0.018f)
        val landed = if (flying >= 0) count - 1 else count
        for (i in 0 until count) {
            val d = dayOf[i]
            val (px, py) = seat(i, l)
            val (sx, sy) = slot(d, l)
            val inPair = matched && (i == matchA || i == matchB)
            val dim = if (matched && !inPair) 1f - 0.65f * glint else 1f
            val col = dayColour(pal, d)
            if (i < landed || (matched && i == matchB)) {
                g.line(px, py, sx, sy, if (inPair) 0.004f else 0.0022f,
                    if (inPair) Palette.lerp(col, Palette.TEXT_PRIMARY, glint) else col,
                    fade * (if (inPair) 0.95f else 0.42f * dim))
            } else if (i == flying) {
                val u = (flyT / flyLen).coerceIn(0f, 1f)
                val e = u * u * (3f - 2f * u)
                g.line(px, py, px + (sx - px) * e, py + (sy - py) * e, 0.003f, col, 0.75f * fade)
            }
            val grow = if (i == flying) min(1f, flyT / (flyLen * 0.4f)) else 1f
            g.disc(px, py, seatR * grow * 1.3f, Palette.BG, fade)
            g.disc(px, py, seatR * grow, if (inPair) Palette.lerp(col, Palette.TEXT_PRIMARY, glint) else col, fade * dim)
        }
        for (i in 0 until landed) {
            val d = dayOf[i]
            val (sx, sy) = slot(d, l)
            g.disc(sx, sy, 0.0105f, Palette.BG, fade)
            g.disc(sx, sy, 0.0075f, dayColour(pal, d), fade)
        }
        if (matched) {
            val (sx, sy) = slot(dayOf[matchB], l)
            g.ring(sx, sy, 0.03f, 0.005f, Palette.TEXT_PRIMARY, alpha = glint * fade)
            g.disc(sx, sy, 0.011f, Palette.TEXT_PRIMARY, glint * fade)
        }
        drawChart(g, pal, l, landed)
    }

    /**
     * The chance of a shared birthday against the number of people: the
     * exact curve, where this room has got to on it, and — once rooms have
     * finished — the share that had matched by each size, which is the same
     * curve measured instead of worked out.
     */
    private fun drawChart(g: G, pal: Palette, l: Layout, landed: Int) {
        val w = l.right - l.left
        val h = l.top - l.axisY
        fun xOf(n: Float) = l.left + w * (n / span)
        fun yOf(p: Float) = l.axisY + h * p
        val axis = Palette.TEXT_SECONDARY
        g.line(l.left, l.axisY, l.right, l.axisY, 0.003f, axis, 0.5f)
        g.line(l.left, l.axisY, l.left, l.top, 0.003f, axis, 0.5f)
        // Half way, and the size of room that gets there.
        g.line(l.left, yOf(0.5f), l.right, yOf(0.5f), 0.002f, axis, 0.22f)
        g.line(xOf(half.toFloat()), l.axisY, xOf(half.toFloat()), l.top, 0.002f, axis, 0.3f)
        g.rect(xOf(half.toFloat()), l.axisY, 0.003f, 0.016f, Palette.TEXT_PRIMARY, alpha = 0.7f)
        val curve = Palette.brighten(pal.at(0.06f), 0.15f)
        for (n in 1..span) {
            g.line(xOf(n - 1f), yOf(chance[n - 1]), xOf(n.toFloat()), yOf(chance[n]), 0.0055f, curve, 0.95f)
        }
        // The measured share, once there is anything to measure.
        val finished = min(room + (if (matchA >= 0) 1 else 0), rooms)
        if (finished >= 2) {
            val emp = Palette.TEXT_PRIMARY
            var prev = 0f
            for (n in 1..span) {
                var k = 0
                for (r in 0 until finished) if (matchedAt[r] in 1..n) k++
                val v = k.toFloat() / finished
                g.line(xOf(n - 1f), yOf(prev), xOf(n.toFloat()), yOf(prev), 0.003f, emp, 0.6f)
                if (v != prev) g.line(xOf(n.toFloat()), yOf(prev), xOf(n.toFloat()), yOf(v), 0.003f, emp, 0.6f)
                prev = v
            }
        }
        // Each finished room as a dot under the axis at the size it matched,
        // stacking into the distribution.
        val dotR = (w / span * 0.42f).coerceIn(0.0065f, 0.009f)
        val stack = IntArray(span + 2)
        for (r in 0 until finished) {
            val n = matchedAt[r].coerceIn(0, span + 1)
            if (n <= 0) continue
            val y = l.axisY - 0.03f - stack[n] * dotR * 2.3f
            stack[n]++
            if (y < l.dotBottom) continue
            val col = if (n <= half) Palette.brighten(pal.at(0.06f), 0.15f) else pal.at(0.55f)
            g.disc(xOf(n.toFloat()), y, dotR, col)
        }
        // This room, riding the curve.
        val n = landed.coerceIn(0, span)
        val mx = xOf(n.toFloat()); val my = yOf(chance[n])
        g.line(mx, l.axisY, mx, my, 0.002f, curve, 0.35f)
        g.glow(mx, my, 0.05f, curve, 0.45f)
        g.disc(mx, my, 0.012f, Palette.TEXT_PRIMARY)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = allDone && sinceAll > HOLD_SECONDS

    override fun counters(): List<Pair<String, String>> {
        val n = if (flying >= 0) count - 1 else count
        val finished = min(room + (if (matchA >= 0) 1 else 0), rooms)
        return listOf(
            "people" to n.toString(),
            "pairs" to String.format(java.util.Locale.US, "%,d", n * (n - 1) / 2),
            "chance" to String.format(java.util.Locale.US, "%.1f%%", chance[n.coerceIn(0, span)] * 100f),
            "matched by $half" to "$byHalf / $finished rooms"
        )
    }

    companion object {
        private val MONTH_DAYS = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        private const val LEAD_IN = 0.5f
        private const val FLY_SECONDS = 0.35f
        private const val FIRST_HOLD = 2.6f
        private const val CLEAR_SECONDS = 0.35f
        private const val MIN_NOTE_GAP = 0.12f
        private const val HOLD_SECONDS = 3f

        fun def(): SimDef = SimDef(
            id = "math/birthday-paradox",
            title = "The birthday paradox",
            shelf = Shelf.MATH,
            family = "Probability & statistics",
            blurb = "How many people before two share a birthday? Far fewer than it feels like.",
            formats = listOf(TrapFormat.GUESS, TrapFormat.SNAP),
            heroLabel = "New rooms",
            defaultPalette = "Ocean",
            typicalSeconds = 17f,
            params = listOf(
                Param.int("days", "Days in the year", 12, 2000, 365, card = Card.DATA, logScale = true),
                Param.int("rooms", "Rooms", 1, 200, 30, card = Card.DATA, logScale = true),
                Param.float("speed", "People a second", 0.5f, 12f, 2f, card = Card.MOTION, logScale = true)
            ),
            create = { BirthdayParadoxSim() }
        )
    }
}
