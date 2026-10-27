package app.hypnotica.sims.math

import app.hypnotica.core.laws.Expr
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

/**
 * Type the maths and watch it.
 *
 * Three modes cover most of what a formula can be: a curve traced from a pair
 * of parametric equations, a vector field integrated as a differential
 * equation, or a map applied over and over to its own output. No code, no
 * recipe file, just the expressions.
 */
class EquationSim : Sim {

    private enum class Mode { PARAMETRIC, ODE, MAP }

    /** The payoff fires once; without this the bell retriggers every step. */
    private var drawnEnough = false

    private var mode = Mode.PARAMETRIC
    private var exprX: Expr? = null
    private var exprY: Expr? = null
    private var error: String? = null

    private var trailX = FloatArray(0)
    private var trailY = FloatArray(0)
    private var head = 0
    private var filled = 0

    private var t = 0f
    private var x = 0f
    private var y = 0f
    private var carry = 0f
    private var payoffFrame = false
    private var laps = 0
    private var lastQuadrant = 0
    private var scale = 0.42f
    private var resets = 0

    // One reusable variable binding, so evaluation allocates nothing per step.
    private var varT = 0f
    private var varX = 0f
    private var varY = 0f
    private val vars = Expr.Vars { name ->
        when (name) {
            "t", "time" -> varT
            "x" -> varX
            "y" -> varY
            else -> Float.NaN
        }
    }

    override fun setup(env: SimEnv) {
        val p = env.params
        mode = Mode.entries[p.choiceIndex("mode").coerceIn(0, Mode.entries.size - 1)]
        val sx = p.text("fx", "sin(3 * t)")
        val sy = p.text("fy", "sin(4 * t + 1.57)")
        exprX = Expr.compileOrNull(sx)
        exprY = Expr.compileOrNull(sy)
        error = when {
            exprX == null -> "x formula does not parse"
            exprY == null -> "y formula does not parse"
            else -> null
        }
        scale = p.f("scale", 0.42f)
        val len = p.i("trail", 9000).coerceIn(100, 120000)
        trailX = FloatArray(len)
        trailY = FloatArray(len)
        head = 0
        filled = 0
        t = 0f
        x = p.f("x0", 0.4f)
        y = p.f("y0", 0.1f)
        carry = 0f
        payoffFrame = false
        laps = 0
        lastQuadrant = 0
        resets = 0
        drawnEnough = false
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val fx = exprX ?: return
        val fy = exprY ?: return
        carry += abs(dt) * env.params.f("rate", 900f)
        var budget = min(carry.toInt(), 30000)
        carry -= budget
        val h = env.params.f("step", 0.004f)

        while (budget-- > 0) {
            varT = t
            varX = x
            varY = y
            when (mode) {
                Mode.PARAMETRIC -> {
                    x = fx.eval(vars)
                    y = fy.eval(vars)
                    t += h
                }
                Mode.ODE -> {
                    // Midpoint step: cheap, and stable enough for a drawn field.
                    val k1x = fx.eval(vars)
                    val k1y = fy.eval(vars)
                    varX = x + k1x * h * 0.5f
                    varY = y + k1y * h * 0.5f
                    varT = t + h * 0.5f
                    val k2x = fx.eval(vars)
                    val k2y = fy.eval(vars)
                    x += k2x * h
                    y += k2y * h
                    t += h
                }
                Mode.MAP -> {
                    val nx = fx.eval(vars)
                    val ny = fy.eval(vars)
                    x = nx
                    y = ny
                    t += h
                }
            }

            // A formula that runs away is restarted rather than drawn off-screen.
            if (!x.isFinite() || !y.isFinite() || abs(x) > 1e6f || abs(y) > 1e6f) {
                x = env.rng.range(-0.4f, 0.4f)
                y = env.rng.range(-0.4f, 0.4f)
                t = 0f
                resets++
                env.emit(EventKind.DEATH, 0.3f, 0f, 0.5f)
                continue
            }

            trailX[head] = x * scale
            trailY[head] = y * scale
            head = (head + 1) % trailX.size
            if (filled < trailX.size) filled++

            val q = quadrant(x, y)
            if (q != lastQuadrant) {
                lastQuadrant = q
                if (q == 1) laps++
                env.emit(
                    EventKind.CROSS,
                    ((y * scale + 1f) * 0.5f).coerceIn(0f, 1f),
                    (x * scale).coerceIn(-1f, 1f),
                    0.55f
                )
            }
        }
        if (!drawnEnough && filled >= trailX.size * 7 / 10) {
            drawnEnough = true
            payoffFrame = true
            env.emit(EventKind.DONE, 0.8f, 0f, 1f)
        }
    }

    private fun quadrant(px: Float, py: Float): Int =
        if (px >= 0f) (if (py >= 0f) 1 else 2) else (if (py >= 0f) 4 else 3)

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        if (error != null) {
            // The formula is wrong: say so plainly instead of drawing nothing.
            g.ring(0f, 0f, 0.3f, 0.01f, Palette.DANGER, alpha = 0.8f)
            return
        }
        g.reservePoints(filled)
        val dot = env.params.f("dot", 0.004f)
        for (k in 0 until filled) {
            val idx = (head - 1 - k + trailX.size * 2) % trailX.size
            val age = k.toFloat() / filled
            g.point(
                trailX[idx], trailY[idx], dot * (1.15f - age * 0.5f),
                Palette.withAlpha(palette.at((1f - age) * 0.85f), (1f - age) * 0.85f + 0.15f)
            )
        }
        if (filled > 0) {
            val last = (head - 1 + trailX.size) % trailX.size
            g.glow(trailX[last], trailY[last], 0.06f, Palette.SUCCESS, 0.7f)
        }
        // Axes, so the scale is readable.
        val halfW = env.halfWidth
        g.line(-halfW, 0f, halfW, 0f, 0.0015f, Palette.withAlpha(Palette.TEXT_SECONDARY, 0.25f))
        g.line(0f, -1f, 0f, 1f, 0.0015f, Palette.withAlpha(Palette.TEXT_SECONDARY, 0.25f))
        if (payoffFrame) g.flash(0.25f)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> {
        val err = error
        if (err != null) return listOf("formula" to err)
        return listOf(
            "points" to String.format(java.util.Locale.US, "%,d", filled),
            "laps" to laps.toString()
        ) + if (resets > 0) listOf("restarts" to resets.toString()) else emptyList()
    }

    companion object {
        fun def(): SimDef = SimDef(
            id = "math/equation",
            title = "Equation plotter",
            shelf = Shelf.MATH,
            family = "Equations",
            blurb = "Type a pair of formulas and watch them draw.",
            formats = listOf(TrapFormat.GROW, TrapFormat.LOOP, TrapFormat.REVEAL),
            heroLabel = "Restart",
            defaultPalette = "Aurora",
            typicalSeconds = 24f,
            params = listOf(
                Param.choice(
                    "mode", "What the formulas mean",
                    listOf("Curve x(t), y(t)", "Field x' = f, y' = g", "Map x,y -> f,g"), 0,
                    card = Card.DATA, input = ParamInput.CHIPS
                ),
                Param.expr("fx", "x formula", "sin(3 * t)", card = Card.DATA),
                Param.expr("fy", "y formula", "sin(4 * t + 1.57)", card = Card.DATA),
                // Where the trajectory starts is the subject of an ODE sim,
                // not an expert detail, and neither is an integer Evolve can
                // drift — so hiding them left no simple route at all.
                Param.float("x0", "Start x", -2f, 2f, 0.4f, card = Card.DATA),
                Param.float("y0", "Start y", -2f, 2f, 0.1f, card = Card.DATA),
                Param.float("scale", "Scale", 0.02f, 2f, 0.42f, card = Card.LOOK, logScale = true),
                Param.float("dot", "Line weight", 0.001f, 0.012f, 0.004f, card = Card.LOOK),
                Param.int("trail", "Trail", 100, 120000, 9000, card = Card.LOOK, logScale = true),
                Param.float("step", "Step size", 0.0002f, 0.08f, 0.004f, card = Card.MOTION, logScale = true),
                Param.float("rate", "Speed", 50f, 20000f, 900f, card = Card.MOTION, logScale = true)
            ),
            create = { EquationSim() }
        )

        /**
         * One ready-made formula set. A preset carries its mode and its pacing
         * as well as the two formulas, because a vector field and a parametric
         * curve want very different step sizes.
         */
        data class Preset(
            val name: String,
            val mode: Int,
            val fx: String,
            val fy: String,
            val step: Float,
            val rate: Float
        )

        /** Offered as chips under the equation fields. */
        val PRESETS: List<Preset> = listOf(
            Preset("Lissajous", 0, "sin(3 * t)", "sin(4 * t + 1.57)", 0.004f, 900f),
            Preset("Rose", 0, "cos(5 * t) * cos(t)", "cos(5 * t) * sin(t)", 0.004f, 900f),
            Preset("Spiral", 0, "t * cos(6 * t) / 12", "t * sin(6 * t) / 12", 0.003f, 1400f),
            Preset(
                "Butterfly", 0,
                "sin(t) * (exp(cos(t)) - 2 * cos(4 * t))",
                "cos(t) * (exp(cos(t)) - 2 * cos(4 * t))",
                0.003f, 900f
            ),
            Preset("Circle field", 1, "0 - y", "x", 0.01f, 700f),
            Preset("Spiral field", 1, "0 - y - x / 8", "x - y / 8", 0.01f, 700f),
            Preset("Pendulum", 1, "y", "0 - sin(x) - y / 8", 0.012f, 700f),
            Preset("Henon map", 2, "1 - 1.4 * x * x + y", "0.3 * x", 0.004f, 500f),
            Preset("Logistic map", 2, "3.9 * x * (1 - x)", "x", 0.004f, 500f)
        )
    }
}
