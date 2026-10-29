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
 * and the slice tips over against a bench stop or the last one — again and again until
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
    private var floorY = FLOOR
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
        // Slices fall over to the right, so the block leaves room on its
        // right for them: they fell off the side of an upright phone.
        // The first slice now leans on a bench stop instead of lying flat, so
        // the fallen slices take about 0.62 of the block's height in width,
        // not all of it — and the block can be half as tall again. Lying
        // flat, the block was a small square low on an upright phone.
        val avail = 2f * halfW - 2f * MARGIN
        blockH = min(MAX_BLOCK_H, avail * 0.5f / LEAN_REACH)
        floorY = if (halfW < 0.8f) FLOOR_UPRIGHT else FLOOR
        val sw0 = min(avail - blockH * LEAN_REACH, 1.3f) / slices
        val reach = blockH * sin(LEAN) + sw0 * cos(LEAN)
        blockW = min(avail - reach - 0.01f, 1.3f)
        left = -halfW + MARGIN + ((avail - reach) - blockW) * 0.5f
        // The stop: its top corner touches the first slice's face a little
        // below its top when the slice leans at [LEAN].
        stopX = left + blockW + STOP_CONTACT * blockH * sin(LEAN) - 0.002f
        stopH = STOP_CONTACT * blockH * cos(LEAN)
        stopW = min(0.06f, blockH * 0.12f)
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
        // Where each slice comes to rest, worked out from the geometry: the
        // first lies flat on the table; each after it tips until it meets the
        // one before. All stopped at one angle, the first hung in mid-air and
        // each overlapped its neighbour by most of its thickness.
        rest = FloatArray(slices)
        for (k in 0 until slices) {
            var lo = 0f; var hi = (PI / 2).toFloat()
            repeat(28) {
                val m = (lo + hi) * 0.5f
                val hit = if (k == 0) { corners(0, m, qa); stopCorners(qb); overlapsQ() } else overlaps(k, m, k - 1, rest[k - 1])
                if (hit) hi = m else lo = m
            }
            rest[k] = lo
        }
        elapsed = 0f
        doneAt = -1f
        payoffFrame = false
        cx = FloatArray(MAX_CRUMBS); cy = FloatArray(MAX_CRUMBS); cvx = FloatArray(MAX_CRUMBS); cvy = FloatArray(MAX_CRUMBS)
        ccol = IntArray(MAX_CRUMBS); cn = 0
        crumbCarry = 0f
    }

    private val sliceW get() = blockW / slices

    private var rest = FloatArray(0)

    /** Slice [k]'s four corners, tipped by [tilt] about its far bottom corner. */
    private fun corners(k: Int, tilt: Float, out: FloatArray) {
        val px = cutX(k) + sliceW
        val c = cos(-tilt); val s = sin(-tilt)
        var i = 0
        for ((lx, ly) in arrayOf(-sliceW to 0f, 0f to 0f, 0f to blockH, -sliceW to blockH)) {
            out[i++] = px + lx * c - ly * s
            out[i++] = floorY + lx * s + ly * c
        }
    }

    private val qa = FloatArray(8)
    private val qb = FloatArray(8)

    /** Whether two tipped slices overlap: separating axes over both rectangles' sides. */
    private fun overlaps(a: Int, ta: Float, b: Int, tb: Float): Boolean {
        corners(a, ta, qa); corners(b, tb, qb)
        return overlapsQ()
    }

    private var stopX = 0f
    private var stopH = 0f
    private var stopW = 0.05f

    private fun stopCorners(out: FloatArray) {
        out[0] = stopX; out[1] = floorY
        out[2] = stopX + stopW; out[3] = floorY
        out[4] = stopX + stopW; out[5] = floorY + stopH
        out[6] = stopX; out[7] = floorY + stopH
    }

    /** Whether the quads in [qa] and [qb] overlap. */
    private fun overlapsQ(): Boolean {
        for (poly in arrayOf(qa, qb)) for (e in 0 until 4) {
            val x1 = poly[2 * e]; val y1 = poly[2 * e + 1]
            val x2 = poly[(2 * e + 2) % 8]; val y2 = poly[(2 * e + 3) % 8]
            val ax = -(y2 - y1); val ay = x2 - x1
            var minA = Float.MAX_VALUE; var maxA = -Float.MAX_VALUE
            var minB = Float.MAX_VALUE; var maxB = -Float.MAX_VALUE
            for (v in 0 until 4) {
                val pa = qa[2 * v] * ax + qa[2 * v + 1] * ay; minA = min(minA, pa); maxA = max(maxA, pa)
                val pb = qb[2 * v] * ax + qb[2 * v + 1] * ay; minB = min(minB, pb); maxB = max(maxB, pb)
            }
            // A hair of give, so touching isn't overlapping.
            if (maxA <= minB + 1e-5f || maxB <= minA + 1e-5f) return false
        }
        return true
    }
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
                    crumb(env, cutX(cut), floorY + blockH * (1f - depth))
                }
                if (env.rng.nextFloat() < h * 30f * amount) env.emit(EventKind.HIT, env.rng.range(0.7f, 1f), (cutX(cut) / halfW).coerceIn(-1f, 1f), 0.12f)
            }
            if (before < DOWN && phase >= DOWN) {
                // Through: the slice is free and starts to tip.
                spin[cut] = 0.5f
                env.emit(EventKind.STEP, 0.3f + 0.6f * cut / slices, (cutX(cut) / halfW).coerceIn(-1f, 1f), 0.5f)
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
            if (tilt[k] >= rest[k]) {
                tilt[k] = rest[k]
                landed[k] = true
                // A puff of crumbs where its top knocks against what it lands
                // on, and the knock heard from there (it was pinned at 0.4).
                corners(k, tilt[k], qa)
                env.emit(EventKind.HIT, 0.15f + 0.1f * env.rng.nextFloat(), (qa[4] / halfW).coerceIn(-1f, 1f), 0.7f)
                repeat(12) { crumb(env, qa[4], qa[5]) }
                if (k == slices - 1) {
                    doneAt = elapsed
                    payoffFrame = true
                    env.emit(EventKind.DONE, 0.8f, 0f, 1f)
                }
            }
        }
        val cg = max(0.5f, abs(env.laws.gravity)) * 0.25f
        for (i in 0 until cn) {
            if (cy[i] <= floorY + 0.003f && cvy[i] == 0f) continue
            cvy[i] -= cg * h
            cx[i] += cvx[i] * h; cy[i] += cvy[i] * h
            if (cy[i] < floorY + 0.003f) { cy[i] = floorY + 0.003f; cvy[i] = 0f; cvx[i] = 0f }
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
        val v = ((y - floorY) / blockH).coerceIn(0f, 0.999f)
        ccol[i] = colors[(v * gridY).toInt() * gridX + (u * gridX).toInt()]
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        // The table: its top edge, and the table itself below it.
        g.rect(0f, (floorY - 1f) * 0.5f, halfW, (floorY + 1f) * 0.5f, TABLE)
        g.rect(0f, floorY - 0.006f, halfW, 0.006f, 0xFF2A2E38.toInt())
        val cw = blockW / gridX
        val ch = blockH / gridY
        val perSlice = gridX / slices
        // What's left of the block: every column left of the next cut.
        // Not counting a slice the knife is through: it is drawn tipping,
        // and was drawn upright as well until the knife came back up.
        val standing = (slices - cut - (if (cut < slices && phase >= DOWN) 1 else 0)) * perSlice
        g.reserveInstances(gridX * gridY * 3 / 2)
        // The block mirrored in the polished table, at half the resolution and
        // fading with depth: it grounds the block and fills the lower half,
        // which was a flat empty slab.
        val rr = 2
        for (c in 0 until standing.coerceAtMost(gridX)) for (r in 0 until gridY / rr) {
            val k = r * rr
            val fade = 0.22f * (1f - k.toFloat() / gridY)
            g.rect(left + (c + 0.5f) * cw, floorY - (k + rr * 0.5f) * ch, cw * 0.5f + PAD, ch * rr * 0.5f + PAD,
                Palette.lerp(TABLE, colors[k * gridX + c], fade))
        }
        for (k in 0 until min(cut + 1, slices)) {
            if (k == cut && (cut >= slices || phase < DOWN)) continue
            val c0 = gridX - (k + 1) * perSlice
            val px = cutX(k) + sliceW
            val a = -tilt[k]
            val ca = cos(a); val sa = sin(a)
            for (c in c0 until c0 + perSlice) for (r in 0 until gridY / rr) {
                val kk = r * rr
                val lx = left + (c + 0.5f) * cw - px
                val ly = (kk + rr * 0.5f) * ch
                val yy = lx * sa + ly * ca
                // Mixed into the table's colour rather than drawn see-through:
                // overlapping translucent cells showed the grid as dark seams.
                g.rect(px + lx * ca - ly * sa, floorY - yy, cw * 0.5f + PAD, ch * rr * 0.5f + PAD,
                    Palette.lerp(TABLE, colors[kk * gridX + c], 0.22f * (1f - (yy / blockH).coerceIn(0f, 1f))), -a)
            }
        }
        g.rect(0f, floorY - 0.006f, halfW, 0.006f, 0xFF2A2E38.toInt())
        // The bench stop the first slice leans on, and its dim reflection.
        g.rect(stopX + stopW * 0.5f, floorY - stopH * 0.5f, stopW * 0.5f, stopH * 0.5f, Palette.lerp(TABLE, STOP, 0.25f))
        g.rect(stopX + stopW * 0.5f, floorY + stopH * 0.5f, stopW * 0.5f, stopH * 0.5f, STOP)
        g.rect(stopX + stopW * 0.5f, floorY + stopH - 0.003f, stopW * 0.5f, 0.003f, Palette.brighten(STOP, 0.12f))
        for (c in 0 until standing.coerceAtMost(gridX)) for (r in 0 until gridY) {
            g.rect(left + (c + 0.5f) * cw, floorY + (r + 0.5f) * ch, cw * 0.5f + PAD, ch * 0.5f + PAD, colors[r * gridX + c])
        }
        // The cut slices: each pivots on its far bottom corner.
        for (k in 0 until min(cut + 1, slices)) {
            if (k == cut && (cut >= slices || phase < DOWN)) continue
            val c0 = gridX - (k + 1) * perSlice
            val px = cutX(k) + sliceW; val py = floorY
            val a = -tilt[k]
            val ca = cos(a); val sa = sin(a)
            for (c in c0 until c0 + perSlice) for (r in 0 until gridY) {
                val lx = left + (c + 0.5f) * cw - px
                val ly = (r + 0.5f) * ch
                g.rect(px + lx * ca - ly * sa, py + lx * sa + ly * ca, cw * 0.5f + PAD, ch * 0.5f + PAD, colors[r * gridX + c], a)
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
            val tip = floorY + blockH * (1f - d)
            // Shorter than it was, so over the taller block the handle stays
            // on screen at the top of the stroke.
            // A blade with a bright cutting edge and a handle with a lit
            // side: two flat rectangles read as a stick and a box.
            g.rect(x, tip + 0.17f, 0.007f, 0.17f, 0xFF9AA1AE.toInt())
            g.rect(x - 0.0045f, tip + 0.17f, 0.0025f, 0.17f, 0xFFE6EAF0.toInt())
            g.rect(x, tip + 0.345f, 0.012f, 0.006f, 0xFF5A606E.toInt())
            g.rect(x, tip + 0.4f, 0.018f, 0.06f, 0xFF2E333E.toInt())
            g.rect(x - 0.012f, tip + 0.4f, 0.004f, 0.056f, 0xFF4A5060.toInt())
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && elapsed - doneAt > 2.5f

    override fun counters(): List<Pair<String, String>> = listOf("slices" to "${landed.count { it }} / $slices")

    companion object {
        const val MAX_SLICES = 40
        private val TABLE = 0xFF0D0F14.toInt()
        /**
         * How far each cell overlaps the next. At 0.0005 that was a fifth of
         * a pixel, two anti-aliased edges met inside it, and every row of the
         * soap showed as a faint dark line.
         */
        const val PAD = 0.0016f
        const val CELLS_PER_SLICE = 6
        /**
         * The table top. Low on the screen, so the block and the knife over
         * it sit in the middle: at -0.3 the bottom third was empty table.
         */
        const val FLOOR = -0.45f
        /** On an upright phone: the block in the middle and the table's sheen below it. */
        const val FLOOR_UPRIGHT = -0.4f
        /** How far over, from upright, the first slice leans on the stop. */
        const val LEAN = 0.62f
        /** Width the leaning slices take, as a share of the block's height (about sin LEAN). */
        const val LEAN_REACH = 0.62f
        /** Where up the first slice's face the stop touches it. */
        const val STOP_CONTACT = 0.45f
        const val MAX_BLOCK_H = 0.7f
        private val STOP = 0xFF3B3F4C.toInt()
        const val MARGIN = 0.03f
        const val DOWN = 1.0f
        const val UP = 0.35f
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
