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

/**
 * A marbled block, sliced. The knife comes down, crumbs spill from the cut,
 * and the slice tips over and lands on the last one — again and again until
 * the block is a row of fallen slices.
 *
 * The block is a grid of little cells coloured by a swirl drawn fresh for
 * each run, so every block is marbled differently, and the slice carries its
 * cells with it as it falls.
 */
class SoapCutSim : Sim {

    private var slices = 10
    private var gridX = 40
    private var gridY = 24
    private var blockW = 0.8f
    private var blockH = 0.5f
    private var left = -0.4f
    private var colors = IntArray(0)
    /** The phase of the knife on the current slice, in seconds. */
    private var phase = 0f
    private var cut = 0
    private var tilt = FloatArray(0)
    private var spin = FloatArray(0)
    private var landed = BooleanArray(0)
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var halfW = 0.46f
    // Swirl.
    private var fx = 3f
    private var fy = 2f
    private var ph = 0f
    private var warp = 0.6f
    // Crumbs.
    private var cx = FloatArray(0)
    private var cy = FloatArray(0)
    private var cvx = FloatArray(0)
    private var cvy = FloatArray(0)
    private var ccol = IntArray(0)
    private var cn = 0
    private var crumbCarry = 0f

    override fun setup(env: SimEnv) {
        halfW = env.halfWidth
        slices = env.params.i("slices", 10).coerceIn(3, MAX_SLICES)
        // A slice falls over to the right and lies its whole height long, so
        // the block leaves that much room on its right: it fell off the side
        // of an upright phone.
        blockH = min(0.5f, 2f * halfW * 0.36f)
        blockW = min(2f * halfW - blockH - 2f * MARGIN, 1.3f)
        left = -halfW + MARGIN + ((2f * halfW - blockH - 2f * MARGIN) - blockW) * 0.5f
        gridX = (slices * CELLS_PER_SLICE).coerceAtMost(240)
        gridY = 40
        fx = env.rng.range(2f, 6f); fy = env.rng.range(1.5f, 4f)
        ph = env.rng.range(0f, 6.28f); warp = env.rng.range(0.3f, 1.1f)
        val palette = env.palette
        val pattern = env.params.choiceIndex("pattern").coerceIn(0, 3)
        // Drawn only for the patterns that use them, so a marbled block's
        // seed gives the same block it always did.
        val layers = if (pattern == 1) env.rng.int(4, 8) else 5
        val base = if (pattern >= 2) env.rng.nextFloat() else 0f
        colors = IntArray(gridX * gridY) { k ->
            val u = (k % gridX + 0.5f) / gridX; val v = (k / gridX + 0.5f) / gridY
            val t = when (pattern) {
                // Layers bent by a sine swirl: marbled, and never the same twice.
                0 -> v + warp * 0.18f * sin(u * fx * 6.28f + ph + sin(v * fy * 6.28f) * warp)
                // Flat layers poured one on another, each its own colour.
                1 -> floor(v * layers) / layers + 0.04f * sin(u * fx * 6.28f + ph)
                // One colour, flecked with bits of the others.
                2 -> if (env.rng.nextFloat() < 0.12f) env.rng.nextFloat() else base + 0.05f * v
                // Fading from one colour at the bottom to another at the top.
                else -> base + v * 0.6f + 0.02f * sin(u * 40f + ph)
            }
            palette.at(((t * 1.3f) % 1f + 1f) % 1f)
        }
        phase = 0f
        cut = 0
        tilt = FloatArray(slices); spin = FloatArray(slices); landed = BooleanArray(slices)
        elapsed = 0f
        doneAt = -1f
        payoffFrame = false
        cx = FloatArray(MAX_CRUMBS); cy = FloatArray(MAX_CRUMBS); cvx = FloatArray(MAX_CRUMBS); cvy = FloatArray(MAX_CRUMBS)
        ccol = IntArray(MAX_CRUMBS); cn = 0
        crumbCarry = 0f
    }

    private val sliceW get() = blockW / slices
    private fun cutX(k: Int) = left + blockW - sliceW * (k + 1)

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        // However many slices, the block is gone in about the same time.
        val pace = env.params.f("pace", 1f) * max(1f, slices * (DOWN + UP) / TARGET_SECONDS)
        if (cut < slices) {
            // The last slice is what's left of the block: nothing to cut, it
            // just goes over. The knife used to come down its outside edge.
            if (cut == slices - 1 && phase < DOWN) phase = DOWN - 1e-4f
            val before = phase
            phase += h * pace
            val depth = knifeDepth(phase)
            // Crumbs off the blade while it is in the block.
            if (depth > 0f && depth < 1f && phase < DOWN) {
                val amount = env.params.f("crumble", 1f)
                // Fractions carried over: a frame's share of a crumb rounded
                // down to nothing, and the cut barely crumbled.
                crumbCarry += h * CRUMBS_PER_SECOND * amount * env.rng.range(0.5f, 1.5f)
                while (crumbCarry >= 1f) {
                    crumbCarry -= 1f
                    crumb(env, cutX(cut), FLOOR + blockH * (1f - depth))
                }
                if (env.rng.nextFloat() < h * 30f * amount) env.emit(EventKind.HIT, env.rng.range(0.7f, 1f), 0.2f, 0.12f)
            }
            if (before < DOWN && phase >= DOWN) {
                // Through: the slice is free and starts to tip.
                spin[cut] = 0.5f
                env.emit(EventKind.STEP, 0.3f + 0.6f * cut / slices, 0.3f, 0.5f)
            }
            if (phase >= DOWN + UP) {
                phase = 0f
                cut++
            }
        }
        // Slices tipping: falling faster the further over, to where they
        // rest against the one before.
        val g = max(0.5f, abs(env.laws.gravity)) * 0.6f
        for (k in 0 until slices) {
            if (spin[k] == 0f || landed[k]) continue
            spin[k] += g * sin(tilt[k] + 0.08f) * h * 6f
            tilt[k] += spin[k] * h
            if (tilt[k] >= REST) {
                tilt[k] = REST
                landed[k] = true
                env.emit(EventKind.HIT, 0.15f + 0.1f * env.rng.nextFloat(), 0.4f, 0.7f)
                repeat(12) { crumb(env, cutX(k) + sliceW + blockH * 0.8f, FLOOR + 0.02f) }
                if (k == slices - 1) {
                    doneAt = elapsed
                    payoffFrame = true
                    env.emit(EventKind.DONE, 0.8f, 0f, 1f)
                }
            }
        }
        val cg = max(0.5f, abs(env.laws.gravity)) * 0.25f
        for (i in 0 until cn) {
            if (cy[i] <= FLOOR + 0.003f && cvy[i] == 0f) continue
            cvy[i] -= cg * h
            cx[i] += cvx[i] * h; cy[i] += cvy[i] * h
            if (cy[i] < FLOOR + 0.003f) { cy[i] = FLOOR + 0.003f; cvy[i] = 0f; cvx[i] = 0f }
            if (abs(cx[i]) > halfW) { cx[i] = cx[i].coerceIn(-halfW, halfW); cvx[i] = 0f }
        }
    }

    /** 0 above the block, 1 at the table; down, then back up. */
    private fun knifeDepth(p: Float): Float =
        if (p < DOWN) {
            val t = p / DOWN
            // In fast, then slow through the middle, then quick at the end.
            (t + 0.12f * sin(t * 2f * PI.toFloat())).coerceIn(0f, 1f) * 1.25f - 0.25f
        } else 1f - (p - DOWN) / UP * 1.25f

    private fun crumb(env: SimEnv, x: Float, y: Float) {
        if (cn >= MAX_CRUMBS) return
        val i = cn++
        cx[i] = x + env.rng.range(-0.01f, 0.01f); cy[i] = y
        cvx[i] = env.rng.range(-0.15f, 0.35f); cvy[i] = env.rng.range(0f, 0.25f)
        val u = ((x - left) / blockW).coerceIn(0f, 0.999f)
        val v = ((y - FLOOR) / blockH).coerceIn(0f, 0.999f)
        ccol[i] = colors[(v * gridY).toInt() * gridX + (u * gridX).toInt()]
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        g.rect(0f, FLOOR - 0.02f, halfW, 0.02f, 0xFF20232C.toInt())
        val cw = blockW / gridX
        val ch = blockH / gridY
        val perSlice = gridX / slices
        // What's left of the block: every column left of the next cut.
        // Not counting a slice the knife is through: it is drawn tipping,
        // and was drawn upright as well until the knife came back up.
        val standing = (slices - cut - (if (cut < slices && phase >= DOWN) 1 else 0)) * perSlice
        g.reserveInstances(gridX * gridY)
        for (c in 0 until standing.coerceAtMost(gridX)) for (r in 0 until gridY) {
            g.rect(left + (c + 0.5f) * cw, FLOOR + (r + 0.5f) * ch, cw * 0.5f + 0.0005f, ch * 0.5f + 0.0005f, colors[r * gridX + c])
        }
        // The cut slices: each pivots on its far bottom corner.
        for (k in 0 until min(cut + 1, slices)) {
            if (k == cut && (cut >= slices || phase < DOWN)) continue
            val c0 = gridX - (k + 1) * perSlice
            val px = cutX(k) + sliceW; val py = FLOOR
            val a = -tilt[k]
            val ca = cos(a); val sa = sin(a)
            for (c in c0 until c0 + perSlice) for (r in 0 until gridY) {
                val lx = left + (c + 0.5f) * cw - px
                val ly = (r + 0.5f) * ch
                g.rect(px + lx * ca - ly * sa, py + lx * sa + ly * ca, cw * 0.5f + 0.0005f, ch * 0.5f + 0.0005f, colors[r * gridX + c], a)
            }
        }
        if (cn > 0) {
            g.reservePoints(cn)
            for (i in 0 until cn) g.point(cx[i], cy[i], 0.008f, ccol[i])
        }
        // The knife.
        if (cut < slices - 1) {
            val d = knifeDepth(phase)
            val x = cutX(cut)
            val tip = FLOOR + blockH * (1f - d)
            g.rect(x, tip + 0.22f, 0.006f, 0.22f, 0xFFC9CED8.toInt())
            g.rect(x, tip + 0.5f, 0.018f, 0.07f, 0xFF3A3F4B.toInt())
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && elapsed - doneAt > 2.5f

    override fun counters(): List<Pair<String, String>> = listOf("slices" to "${landed.count { it }} / $slices")

    companion object {
        const val MAX_SLICES = 40
        const val CELLS_PER_SLICE = 6
        const val FLOOR = -0.3f
        const val MARGIN = 0.03f
        const val DOWN = 1.0f
        const val UP = 0.35f
        val REST = (PI * 0.5 - 0.22).toFloat()
        const val MAX_CRUMBS = 3000
        const val CRUMBS_PER_SECOND = 90f
        const val TARGET_SECONDS = 13.5f

        fun def(): SimDef = SimDef(
            id = "physics/soap-cut",
            title = "Soap cutting",
            shelf = Shelf.PHYSICS,
            family = "Pressure & bursting",
            blurb = "Slice, crumble, tip, thud. Every block marbled differently.",
            formats = listOf(TrapFormat.CUT, TrapFormat.REVEAL),
            lawKnobs = setOf(LawKnob.GRAVITY),
            heroLabel = "New block",
            defaultPalette = "Candy",
            typicalSeconds = 15f,
            params = listOf(
                Param.choice("pattern", "Soap", listOf("Marbled", "Layered", "Speckled", "Ombré"), 0, card = Card.DATA, input = ParamInput.TILES),
                Param.int("slices", "Slices", 3, MAX_SLICES, 10, card = Card.DATA),
                Param.float("pace", "Pace", 0.3f, 4f, 1f, card = Card.MOTION),
                Param.float("crumble", "Crumble", 0f, 4f, 1f, card = Card.MOTION)
            ),
            create = { SoapCutSim() }
        )
    }
}
