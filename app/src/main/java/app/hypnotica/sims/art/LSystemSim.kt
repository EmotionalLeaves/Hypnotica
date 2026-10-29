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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A plant grown from a sentence. An L-system rewrites every letter of a short
 * string by a rule, over and over, and the result read as turtle moves — F
 * forward, + and − turn, [ and ] remember and return — draws a fern, a bush,
 * a weed. A few characters of rule make thousands of branches.
 *
 * It grows outward from the root, every branch at once, at the pace of the
 * distance from the ground rather than the order the string happens to list
 * them in, so it reads as growing and not as being drawn. Tall and narrow by
 * nature, it is one of the few sims that fills an upright phone.
 */
class LSystemSim : Sim {

    private var x1 = FloatArray(0)
    private var y1 = FloatArray(0)
    private var x2 = FloatArray(0)
    private var y2 = FloatArray(0)
    /** How far along the plant, from the root, each segment ends. */
    private var reach = FloatArray(0)
    private var segments = 0
    private var stroke = 1f
    private var tallest = 1f
    private var grown = 0f
    private var elapsed = 0f
    private var fullAt = -1f
    private var payoffFrame = false
    private var nextNote = 0
    private var gustSide = true

    private class Plant(
        val name: String, val axiom: String, val rules: Map<Char, String>,
        val angle: Float, val depth: Int
    )

    override fun setup(env: SimEnv) {
        val p = env.params
        val plant = PLANTS[p.choiceIndex("plant").coerceIn(0, PLANTS.size - 1)]
        val depth = (plant.depth + p.i("growth", 0)).coerceIn(1, 9)
        val angle = (plant.angle + p.f("bend", 0f)) * (PI.toFloat() / 180f)
        build(expand(plant, depth), angle, env.halfWidth)
        grown = 0f
        elapsed = 0f
        fullAt = -1f
        payoffFrame = false
        nextNote = 0
        gustSide = true
    }

    /** Rewrite the axiom [depth] times, stopping early if it would grow past reason. */
    private fun expand(plant: Plant, depth: Int): String {
        var s = plant.axiom
        repeat(depth) {
            val out = StringBuilder(s.length * 4)
            for (c in s) out.append(plant.rules[c] ?: c.toString())
            if (out.length > MAX_LENGTH) return s
            s = out.toString()
        }
        return s
    }

    /** Walk the string as a turtle, then fit the drawing to the screen. */
    private fun build(s: String, angle: Float, halfWidth: Float) {
        var count = 0
        for (c in s) if (c == 'F') count++
        x1 = FloatArray(count); y1 = FloatArray(count)
        x2 = FloatArray(count); y2 = FloatArray(count)
        reach = FloatArray(count)
        val sx = FloatArray(MAX_STACK); val sy = FloatArray(MAX_STACK)
        val sa = FloatArray(MAX_STACK); val sd = FloatArray(MAX_STACK)
        var sp = 0
        var x = 0f; var y = 0f; var a = PI.toFloat() / 2f; var d = 0f
        var n = 0
        for (c in s) when (c) {
            'F' -> {
                val nx = x + cos(a)
                val ny = y + sin(a)
                x1[n] = x; y1[n] = y; x2[n] = nx; y2[n] = ny
                d += 1f
                reach[n] = d
                n++
                x = nx; y = ny
            }
            '+' -> a += angle
            '-' -> a -= angle
            '[' -> if (sp < MAX_STACK) { sx[sp] = x; sy[sp] = y; sa[sp] = a; sd[sp] = d; sp++ }
            ']' -> if (sp > 0) { sp--; x = sx[sp]; y = sy[sp]; a = sa[sp]; d = sd[sp] }
        }
        segments = n
        // Fewer, longer strokes are drawn bolder, so a plant of a few hundred
        // reads as solidly as one of thousands instead of as a wire sketch.
        stroke = kotlin.math.sqrt(STROKE_REFERENCE / max(1, n).toFloat()).coerceIn(0.6f, 2.2f)
        tallest = 1f
        for (i in 0 until n) if (reach[i] > tallest) tallest = reach[i]

        // Fit: the whole plant inside the width and the height, its root on
        // the ground at the bottom of the screen.
        var minX = 0f; var maxX = 0f; var minY = 0f; var maxY = 0f
        for (i in 0 until n) {
            minX = min(minX, min(x1[i], x2[i])); maxX = max(maxX, max(x1[i], x2[i]))
            minY = min(minY, min(y1[i], y2[i])); maxY = max(maxY, max(y1[i], y2[i]))
        }
        // The top held a little lower than the root's margin: on a wide screen,
        // where the height decides, the tips touched the top edge.
        val scale = min(2f * halfWidth * FILL / max(1e-3f, maxX - minX), (FILL + TOP) / max(1e-3f, maxY - minY))
        val cx = (minX + maxX) * 0.5f
        val base = -FILL
        for (i in 0 until n) {
            x1[i] = (x1[i] - cx) * scale; x2[i] = (x2[i] - cx) * scale
            y1[i] = base + (y1[i] - minY) * scale; y2[i] = base + (y2[i] - minY) * scale
        }
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        if (grown < tallest) {
            // Paced so a plant grows in about the same time whatever its size:
            // a fixed number of steps of the tallest branch a second.
            grown = min(tallest, grown + abs(dt) * tallest / GROW_SECONDS * env.params.f("pace", 1f))
            // A note each time the growing tips pass another fortieth of the
            // way up: the plant climbs a scale as it climbs the screen.
            val share = grown / tallest
            while (nextNote < NOTES && share >= (nextNote + 1f) / NOTES) {
                nextNote++
                env.emit(EventKind.WRITE, nextNote.toFloat() / NOTES, 0f, 0.35f)
            }
            if (grown >= tallest) {
                fullAt = elapsed
                payoffFrame = true
                env.emit(EventKind.DONE, 0.9f, 0f, 1f)
            }
        } else if (env.params.f("sway", 0.35f) > 0.01f) {
            // Grown, it was silent for six seconds while it swayed. Each turn
            // of the wind rustles: a soft note as the gust changes side.
            val side = gust(elapsed) >= 0f
            if (side != gustSide) {
                gustSide = side
                env.emit(EventKind.STEP, if (side) 0.75f else 0.55f, if (side) 0.4f else -0.4f, 0.25f)
            }
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val sway = env.params.f("sway", 0.35f)
        val t = elapsed
        // Wind: every point leans by an amount growing with its height above
        // the ground, so the trunk barely moves and the tips wave.
        val gust = gust(t)
        val lean = sway * gust * SWAY
        for (i in 0 until segments) {
            if (reach[i] - 1f > grown) continue
            // A segment still growing is drawn part-way.
            val f = (grown - (reach[i] - 1f)).coerceIn(0f, 1f)
            val h1 = (y1[i] + FILL) * (y1[i] + FILL)
            val h2 = (y2[i] + FILL) * (y2[i] + FILL)
            val ax = x1[i] + lean * h1
            val bx = x2[i] + lean * h2
            val ex = ax + (bx - ax) * f
            val ey = y1[i] + (y2[i] - y1[i]) * f
            val share = reach[i] / tallest
            val width = (stroke * TRUNK * (1f - share) + stroke * TIP).coerceAtLeast(TIP)
            // Started half a width back along itself, so it overlaps the
            // stroke it grows from: butt-ended strokes meeting end to end
            // left hairline seams across the trunk, every segment a band.
            val len = kotlin.math.hypot(ex - ax, ey - y1[i])
            val back = if (len > 1e-6f) width * 0.5f / len else 0f
            g.line(ax - (ex - ax) * back, y1[i] - (ey - y1[i]) * back, ex, ey, width, palette.at(0.15f + 0.8f * share))
        }
    }

    private fun gust(t: Float): Float = sin(t * 0.8f) * 0.6f + sin(t * 1.9f + 1.3f) * 0.25f

    override fun payoff(): Boolean = payoffFrame

    /** Grown, then a while in the wind before the next one. */
    override fun finished(): Boolean = fullAt >= 0f && elapsed - fullAt > HOLD_SECONDS

    override fun counters(): List<Pair<String, String>> = listOf(
        "strokes" to String.format(java.util.Locale.US, "%,d", segments),
        "grown" to String.format(java.util.Locale.US, "%.0f%%", grown / tallest * 100f)
    )

    companion object {
        private val PLANTS = listOf(
            Plant("Fern", "X", mapOf('X' to "F+[[X]-X]-F[-FX]+X", 'F' to "FF"), 25f, 5),
            // Three rounds, not four. Each round of this rule multiplies the
            // strokes by eight, where the others multiply by three to five, so
            // from four the slider's top two rounds both hit the length cap and
            // drew the same bush, and the one below it was 32,768 strokes.
            // From three, every round is its own picture and the most is that.
            Plant("Bush", "F", mapOf('F' to "FF+[+F-F-F]-[-F+F+F]"), 22.5f, 3),
            Plant("Weed", "F", mapOf('F' to "F[+F]F[-F]F"), 25.7f, 4),
            Plant("Tree", "X", mapOf('X' to "F[+X][-X]FX", 'F' to "FF"), 25.7f, 6),
            Plant("Grass", "F", mapOf('F' to "F[+F]F[-F][F]"), 20f, 4)
        )
        private const val MAX_LENGTH = 400_000
        private const val MAX_STACK = 256
        private const val FILL = 0.92f
        private const val TOP = 0.86f
        private const val GROW_SECONDS = 9f
        private const val HOLD_SECONDS = 6f
        private const val NOTES = 40
        private const val SWAY = 0.035f
        private const val TRUNK = 0.012f
        private const val TIP = 0.0022f
        /** The stroke count drawn at the base widths; fewer are drawn bolder. */
        private const val STROKE_REFERENCE = 1500f

        fun def(): SimDef = SimDef(
            id = "art/l-system",
            title = "Plants from rules",
            shelf = Shelf.ART,
            family = "Fractals & growth",
            blurb = "A sentence rewritten again and again becomes a fern.",
            formats = listOf(TrapFormat.GROW, TrapFormat.REVEAL),
            heroLabel = "Grow another",
            defaultPalette = "Aurora",
            typicalSeconds = 13f,
            params = listOf(
                Param.choice("plant", "Plant", PLANTS.map { it.name }, 0, card = Card.DATA, input = ParamInput.TILES),
                Param.int("growth", "Extra rounds", -2, 2, 0, card = Card.DATA),
                Param.float("bend", "Bend", -10f, 10f, 0f, card = Card.DATA, unit = "°"),
                Param.float("pace", "Pace", 0.3f, 3f, 1f, card = Card.MOTION),
                Param.float("sway", "Wind", 0f, 1f, 0.35f, card = Card.MOTION)
            ),
            create = { LSystemSim() }
        )
    }
}
