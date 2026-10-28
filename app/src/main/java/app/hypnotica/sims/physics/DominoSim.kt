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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A line of dominoes snaking down the screen, seen from above, and one push.
 * Each tips, leans on the next, and lies down; the wave runs the whole snake,
 * and every domino that lands is the next note up the scale.
 *
 * Each one is an upturned pendulum: it barely moves at first and then all at
 * once, which is why a chain of them looks like it accelerates through each
 * piece and the wave seems to have a will of its own.
 */
class DominoSim : Sim {

    private var n = 120
    private var px = FloatArray(0)
    private var py = FloatArray(0)
    private var dirX = FloatArray(0)
    private var dirY = FloatArray(0)
    private var tilt = FloatArray(0)
    private var spin = FloatArray(0)
    private var started = BooleanArray(0)
    private var down = BooleanArray(0)
    private var fallen = 0
    private var length = 0.06f
    private var thick = 0.01f
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false

    override fun setup(env: SimEnv) {
        val p = env.params
        n = p.i("dominoes", 120).coerceIn(8, MAX_DOMINOES)
        val path = p.choiceIndex("path")
        // Room at the ends of each row for the last domino to fall into.
        val hw = env.halfWidth * 0.78f
        val pts = when (path) {
            1 -> spiral(n, min(hw, 0.9f))
            else -> snake(n, hw)
        }
        px = FloatArray(n) { pts[it * 2] }
        py = FloatArray(n) { pts[it * 2 + 1] }
        dirX = FloatArray(n); dirY = FloatArray(n)
        for (i in 0 until n) {
            val j = if (i + 1 < n) i + 1 else i
            val k = if (i + 1 < n) i else i - 1
            val dx = px[j] - px[k]; val dy = py[j] - py[k]
            val d = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6f)
            dirX[i] = dx / d; dirY[i] = dy / d
        }
        // A domino is a little taller than the gap to the next, or nothing falls.
        var gap = 0f
        for (i in 1 until n) gap += sqrt((px[i] - px[i - 1]).let { it * it } + (py[i] - py[i - 1]).let { it * it })
        gap /= max(1, n - 1)
        length = gap * 1.6f
        thick = gap * 0.28f
        tilt = FloatArray(n)
        spin = FloatArray(n)
        started = BooleanArray(n)
        down = BooleanArray(n)
        fallen = 0
        elapsed = 0f
        doneAt = -1f
        payoffFrame = false
        // The push.
        started[0] = true
        spin[0] = PUSH
    }

    /** Rows back and forth down the screen, with a half turn at each end. */
    private fun snake(count: Int, hw: Float): FloatArray {
        // Rows far enough apart for a fallen domino — 1.6 gaps long — to lie
        // between them: spaced by count alone, they fell across the next row
        // and the whole snake read as one solid slab.
        val gap = sqrt(1.8f * 2f * hw / (ROW_ROOM * count))
        val rows = max(2, (1.8f / (ROW_ROOM * gap)).toInt())
        val perRow = max(2, count / rows)
        val out = FloatArray(count * 2)
        val rowGap = 1.8f / rows
        for (i in 0 until count) {
            val row = i / perRow
            val k = i % perRow
            val t = k / max(1f, perRow - 1f)
            val x = -hw + 2f * hw * (if (row % 2 == 0) t else 1f - t)
            val y = 0.9f - row * rowGap - (if (k == perRow - 1) rowGap * 0.5f else 0f)
            out[i * 2] = x
            out[i * 2 + 1] = y.coerceAtLeast(-0.95f)
        }
        return out
    }

    /** From the middle outward, evenly spaced along the curve. */
    private fun spiral(count: Int, r: Float): FloatArray {
        val out = FloatArray(count * 2)
        val turns = 5f
        val b = r / (2f * PI.toFloat() * turns)
        // Spacing evenly by arc length: s grows as theta squared on a spiral.
        val total = 0.5f * b * (2f * PI.toFloat() * turns).let { it * it }
        for (i in 0 until count) {
            val s = total * (i + 6f) / (count + 6f)
            val th = sqrt(2f * s / b)
            out[i * 2] = b * th * cos(th)
            out[i * 2 + 1] = b * th * sin(th)
        }
        return out
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        // Paced so a chain of any size runs in about ten to fifteen seconds:
        // true to scale, a hundred and twenty took thirty-five and a thousand
        // nearly three minutes, and nobody waits for the end of that.
        val pace = BASE_PACE * Math.pow(n / 120.0, 0.55).toFloat()
        val whole = abs(dt) * env.params.f("pace", 1f) * pace
        // In slices fine enough for the fall to stay smooth at that pace.
        val slices = kotlin.math.ceil(whole / MAX_SLICE).toInt().coerceIn(1, 16)
        val h = whole / slices
        repeat(slices) { advance(env, h) }
    }

    private fun advance(env: SimEnv, h: Float) {
        val g = env.laws.gravity * GRAVITY_SCALE / length
        for (i in 0 until n) {
            if (!started[i] || down[i]) continue
            // An upturned pendulum, with a little friction at the foot.
            spin[i] += g * sin(tilt[i] + 0.05f) * h
            spin[i] *= 1f - 0.4f * h
            tilt[i] += spin[i] * h
            // Leaning far enough to reach the next one: it starts that one,
            // handing on some of its speed.
            if (i + 1 < n && !started[i + 1] && sin(tilt[i]) * length >= gapTo(i)) {
                started[i + 1] = true
                spin[i + 1] = spin[i] * HANDOFF
            }
            if (tilt[i] >= MAX_TILT) {
                tilt[i] = MAX_TILT
                down[i] = true
                fallen++
                // Each one landing is the next note up: the chain plays a run.
                env.emit(EventKind.HIT, (i.toFloat() / n * SCALE_SPAN) % 1f, (px[i] / max(0.1f, env.halfWidth)).coerceIn(-1f, 1f), 0.45f)
                if (fallen == n) {
                    doneAt = elapsed
                    payoffFrame = true
                    env.emit(EventKind.DONE, 0.9f, 0f, 1f)
                }
            }
        }
    }

    private fun gapTo(i: Int): Float {
        val dx = px[i + 1] - px[i]; val dy = py[i + 1] - py[i]
        return sqrt(dx * dx + dy * dy)
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        for (i in 0 until n) {
            // From above, a standing domino is its thickness; lying down, its
            // length, lying in the direction it fell.
            val lean = sin(tilt[i]) * length
            val half = thick * 0.5f + lean * 0.5f
            val cx = px[i] + dirX[i] * lean * 0.5f
            val cy = py[i] + dirY[i] * lean * 0.5f
            val angle = atan2(dirY[i], dirX[i])
            val t = i / max(1f, n - 1f)
            val c = when {
                down[i] -> palette.at(0.1f + 0.85f * t)
                started[i] -> Palette.brighten(palette.at(0.1f + 0.85f * t), 0.4f)
                else -> Palette.withAlpha(Palette.TEXT_SECONDARY, 0.7f)
            }
            g.rect(cx, cy, half, length * 0.42f, c, angle)
        }
        // The front of the wave glows.
        val front = (0 until n).firstOrNull { started[it] && !down[it] }
        if (front != null) g.glow(px[front], py[front], length * 2.5f, Palette.TEXT_PRIMARY, 0.35f)
    }

    override fun frameHalfWidth(env: SimEnv): Float =
        if (env.params.choiceIndex("path") == 1) min(env.halfWidth, 1f) * 0.9f + 0.08f else 0f

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean =
        (doneAt >= 0f && elapsed - doneAt > 2f) || (fallen < n && elapsed > STALL_SECONDS && (0 until n).none { started[it] && !down[it] && abs(spin[it]) > 0.02f }) ||
            // Without gravity a domino can come to rest half-tipped and the
            // chain stops dead; after long enough the run goes round again.
            (fallen < n && elapsed > STALL_SECONDS * 1.5f)

    override fun counters(): List<Pair<String, String>> = listOf(
        "fallen" to "$fallen / $n"
    )

    companion object {
        const val MAX_DOMINOES = 4000
        /** Row spacing in gaps along a row: room for a domino 1.6 gaps long. */
        const val ROW_ROOM = 1.9f
        const val PUSH = 1.2f
        const val HANDOFF = 0.55f
        const val GRAVITY_SCALE = 0.35f
        val MAX_TILT = (PI * 0.5).toFloat()
        /** The run climbs this many times through the scale over the whole chain. */
        const val SCALE_SPAN = 3f
        const val STALL_SECONDS = 40f
        const val BASE_PACE = 3.5f
        const val MAX_SLICE = 1f / 240f

        fun def(): SimDef = SimDef(
            id = "physics/dominoes",
            title = "Dominoes",
            shelf = Shelf.PHYSICS,
            family = "Chain reactions",
            blurb = "One push. Every domino that lands is the next note.",
            formats = listOf(TrapFormat.CHAIN, TrapFormat.SONG, TrapFormat.FILL),
            lawKnobs = setOf(LawKnob.GRAVITY),
            heroLabel = "Set them up again",
            defaultPalette = "Spectrum",
            typicalSeconds = 14f,
            params = listOf(
                Param.int("dominoes", "Dominoes", 8, MAX_DOMINOES, 120, card = Card.DATA, logScale = true),
                Param.choice("path", "Path", listOf("Snake", "Spiral"), 0, card = Card.DATA, input = ParamInput.TILES),
                Param.float("pace", "Pace", 0.3f, 4f, 1f, card = Card.MOTION)
            ),
            create = { DominoSim() }
        )
    }
}
