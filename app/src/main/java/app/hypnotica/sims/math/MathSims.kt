package app.hypnotica.sims.math

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.recipe.ParamInput
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.Parallel
import app.hypnotica.core.sim.Shelf
import app.hypnotica.core.sim.Sim
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.sim.SimEnv
import app.hypnotica.core.sim.TouchEvent
import app.hypnotica.core.sim.TrapFormat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Mandelbrot set, zooming forever.
 *
 * Escape times are computed across every core and drawn through the point path,
 * so a quarter of a million samples a frame stay inside the budget.
 */
class MandelbrotSim : Sim {

    /** The payoff fires once; without this the bell retriggers every step. */
    private var reached = false

    private var cols = 0
    private var rows = 0
    /**
     * Smooth escape counts (the normalised iteration count), or -1 inside the
     * set. Whole iteration counts coloured the outside in flat terraces with a
     * hard seam wherever the palette wrapped: the banding.
     */
    private var iterations = FloatArray(0)
    /**
     * Each outside cell's distance to the set, in cells, from the distance
     * estimate |z|·ln|z| / |dz/dc|. Cells within a couple of it are the
     * boundary, drawn as one continuous lit filament; by escape count alone
     * neighbouring boundary cells flickered between colours like confetti.
     */
    private var edgeDist = FloatArray(0)
    /** The lowest and highest escape counts on screen, eased between images. */
    private var lowMu = 0f
    private var highMu = 1f
    private var maxIter = 220
    /** The iterations the picture on screen was computed with. */
    private var iterNow = 220
    private var zoom = 0.6
    private var targetZoom = 0.6
    private var centreX = -0.743643887037151
    private var centreY = 0.131825904205330
    private var elapsed = 0f
    private var payoffFrame = false
    private var zoomRate = 0.22f
    private var lastSoundIter = 0
    private var sinceRecompute = 999f
    /** The zoom the current image was computed at. */
    private var computedZoom = 0.6
    /** Doublings of magnification so far, each one a note. */
    private var doublings = 0

    override fun setup(env: SimEnv) {
        val p = env.params
        val quality = p.i("resolution", 240).coerceIn(40, 460)
        refCount = 0
        refined = IntArray(0)
        // The coarse grid; boundary cells are refined 3× (see refine), so the
        // edge is drawn at twice the old 240-row resolution for about the same
        // work, the smooth outside costing less than before.
        rows = max(30, quality * 2 / 3)
        cols = max(8, (quality * env.aspect).toInt())
        iterations = FloatArray(cols * rows)
        edgeDist = FloatArray(cols * rows)
        lowMu = -1f
        highMu = 1f
        maxIter = p.i("detail", 220).coerceIn(40, 1200)
        iterNow = maxIter
        zoom = 0.6
        computedZoom = 0.6
        doublings = 0
        targetZoom = 0.6
        zoomRate = p.f("zoom", 0.22f)
        when (p.choiceIndex("target")) {
            1 -> { centreX = -0.16070135; centreY = 1.0375665 }
            // The antenna's period-3 mini-Mandelbrot, a whole copy of the set
            // out on the spike. The old point was on the edge of the main
            // bulb's neighbour, nowhere near the antenna.
            // and then on into that copy's own seahorse valley. Aimed at the
            // copy's centre the dive ended inside it, a screen of solid black.
            // The copy is the whole set scaled by 0.01951 (its cusp is at
            // exactly −7/4), so its valley sits that far along from its centre.
            2 -> { centreX = -1.7548776662466927 + 0.0195107 * -0.743643887037151; centreY = 0.0195107 * 0.131825904205330 }
            3 -> { centreX = 0.360240443437614; centreY = 0.641313061064803 }
            else -> { centreX = -0.743643887037151; centreY = 0.131825904205330 }
        }
        elapsed = 0f
        payoffFrame = false
        lastSoundIter = 0
        sinceRecompute = 999f
        reached = false
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        targetZoom *= Math.exp((zoomRate * dt).toDouble())
        zoom += (targetZoom - zoom) * min(1.0, (dt * 6f).toDouble())

        // An escape-time image costs far more than a physics step, so it is
        // recomputed on its own clock rather than once per 120 Hz step. Eight
        // images a second is past the point where a slow zoom looks continuous.
        // A note each time the magnification grows by half again: the dive's
        // own beat. The only other sound follows the iteration count at the
        // centre, which deep in a zoom can hold steady for twenty seconds. A
        // note per doubling was one every three seconds at the shipped speed.
        val nowDoublings = (ln(zoom / 0.6) / ln(1.5)).toInt()
        if (nowDoublings > doublings) {
            doublings = nowDoublings
            env.emit(EventKind.STEP, ((doublings % 12) / 12f), 0f, 0.3f)
        }

        sinceRecompute += abs(dt)
        if (sinceRecompute < 1f / REFRESH_HZ) return
        sinceRecompute = 0f
        computedZoom = zoom

        val scale = 1.4 / zoom
        val halfW = env.halfWidth
        val cs = cols
        val rs = rows
        // Deeper in, points take longer to escape: the detail grows with the
        // zoom, or every deep view flattened into one blob.
        val depth = kotlin.math.ln(max(1.0, zoom / 0.6)) / kotlin.math.ln(2.0)
        val mi = (maxIter * (1.0 + 0.12 * depth)).toInt().coerceAtMost(MAX_ITER_DEEP)
        iterNow = mi
        val cx = centreX
        val cy = centreY
        val cell = 2.0 * scale / (rs - 1)
        if (rawDist.size != cs * rs) rawDist = FloatArray(cs * rs)

        Parallel.forRange(rs) { from, to ->
            for (row in from until to) {
                val y0 = cy + (row.toDouble() / (rs - 1) * 2.0 - 1.0) * scale
                val base = row * cs
                for (col in 0 until cs) {
                    val x0 = cx + (col.toDouble() / (cs - 1) * 2.0 - 1.0) * scale * halfW
                    escape(x0, y0, mi, cell, iterations, edgeDist, base + col)
                }
            }
        }
        // Raw distances, before healSpecks/edgeGlow reuse edgeDist, pick the
        // cells that are refined below.
        System.arraycopy(edgeDist, 0, rawDist, 0, cs * rs)
        if (healedMask.size == cs * rs) java.util.Arrays.fill(healedMask, false)
        healSpecks(cs, rs)
        healSpecks(cs, rs)
        edgeGlow(cs, rs)
        refine(cs, rs, cx, cy, scale, halfW, mi, cell)
        // The range of escape counts in view sets how bright the outside is:
        // far from the set dim, close to it bright, so the boundary glows
        // rather than sitting in a flat field of colour. Eased between images
        // so the brightness does not flicker as the zoom deepens.
        var lo = Float.MAX_VALUE
        var hi = 0f
        for (v in iterations) if (v >= 0f) { if (v < lo) lo = v; if (v > hi) hi = v }
        if (lo != Float.MAX_VALUE) {
            val lLo = ln(1f + lo)
            val lHi = max(lLo + 0.5f, ln(1f + hi))
            if (lowMu < 0f) { lowMu = lLo; highMu = lHi } else {
                lowMu += (lLo - lowMu) * 0.35f
                highMu += (lHi - highMu) * 0.35f
            }
        }

        // The sound follows how deep the picture has become.
        val centreIter = iterations[(rs / 2) * cs + cs / 2].let { if (it < 0f) mi else it.toInt() }
        if (abs(centreIter - lastSoundIter) > mi / 40) {
            lastSoundIter = centreIter
            env.emit(EventKind.STEP, (centreIter.toFloat() / mi).coerceIn(0f, 1f), 0f, 0.4f)
        }
        // Deep enough that the detail on screen is a thousand times finer than
         // where it started. That is the zoom arriving, which is what the sim
         // promises, rather than eighteen seconds having gone by.
        if (!reached && zoom >= 0.6 * TARGET_DEPTH) {
            reached = true
            payoffFrame = true
            env.emit(EventKind.DONE, 0.8f, 0f, 1f)
        }
    }

    /**
     * One escape-time sample: the smooth count into [mu] (or -1 inside the
     * set) and the distance estimate, in units of [cell], into [de].
     */
    private fun escape(x0: Double, y0: Double, mi: Int, cell: Double, mu: FloatArray, de: FloatArray, idx: Int) {
        var x = 0.0
        var y = 0.0
        // dz/dc alongside z, for the distance estimate only.
        var dx = 0.0
        var dy = 0.0
        var i = 0
        while (x * x + y * y <= 4.0 && i < mi) {
            val dxt = 2.0 * (x * dx - y * dy) + 1.0
            dy = 2.0 * (x * dy + y * dx)
            dx = dxt
            val xt = x * x - y * y + x0
            y = 2.0 * x * y + y0
            x = xt
            i++
        }
        if (i >= mi) {
            mu[idx] = -1f
            de[idx] = 0f
            return
        }
        // Membership is decided above, at radius 2. Three more steps only
        // carry the escaped orbit far enough out (|z| in the hundreds) for the
        // smooth count n + 1 − log2(ln|z|) to be continuous across the bands.
        for (e in 0 until 3) {
            val dxt = 2.0 * (x * dx - y * dy) + 1.0
            dy = 2.0 * (x * dy + y * dx)
            dx = dxt
            val xt = x * x - y * y + x0
            y = 2.0 * x * y + y0
            x = xt
        }
        val lz = 0.5 * ln(x * x + y * y)
        val m = i + 3 + 1 - ln(max(1e-9, lz)) / LN2
        mu[idx] = max(0.0, m).toFloat()
        // A long orbit overflows dz to infinity; that point is as near the set
        // as can be, so distance 0, not NaN.
        val dd = sqrt(dx * dx + dy * dy)
        val dist = if (dd.isFinite() && dd > 0.0) 2.0 * Math.exp(lz) * lz / dd / cell else 0.0
        de[idx] = if (dist.isNaN()) 0f else dist.toFloat().coerceAtMost(1e6f)
    }

    /**
     * Adaptive resolution. A uniform grid fine enough for the boundary was
     * either too slow or, at the old 240 rows, a picture of visible square
     * tiles whose edges stair-stepped. Away from the set the colour changes
     * slowly and a coarse cell is invisible; only cells on or next to the
     * boundary are resampled on a 3×3 sub-grid (its centre is the coarse
     * sample, reused). The sub-samples are exact escape times of their own
     * points: nothing is interpolated except the base hue.
     */
    private fun refine(cs: Int, rs: Int, cx: Double, cy: Double, scale: Double, halfW: Float, mi: Int, cell: Double) {
        val n = cs * rs
        if (refined.size != n) refined = IntArray(n)
        var count = 0
        for (row in 0 until rs) {
            val base = row * cs
            for (col in 0 until cs) {
                val i = base + col
                val inside = rawMu(i) < 0f
                var need = !inside && rawDist[i] < REFINE_CELLS
                // Where the colour itself changes fast between cells (the
                // bands close to the set), flat tiles stepped visibly.
                if (!need && !inside) {
                    val lm = ln(1f + smooth[i])
                    if (col + 1 < cs && smooth[i + 1] >= 0f && abs(ln(1f + smooth[i + 1]) - lm) > STEP_LM) need = true
                    else if (col > 0 && smooth[i - 1] >= 0f && abs(ln(1f + smooth[i - 1]) - lm) > STEP_LM) need = true
                    else if (row + 1 < rs && smooth[i + cs] >= 0f && abs(ln(1f + smooth[i + cs]) - lm) > STEP_LM) need = true
                    else if (row > 0 && smooth[i - cs] >= 0f && abs(ln(1f + smooth[i - cs]) - lm) > STEP_LM) need = true
                }
                if (!need) {
                    loop@ for (oy in -1..1) for (ox in -1..1) {
                        val r = row + oy
                        val c = col + ox
                        if (r < 0 || r >= rs || c < 0 || c >= cs) continue
                        if ((rawMu(r * cs + c) < 0f) != inside) { need = true; break@loop }
                    }
                }
                refined[i] = if (need) count++ else -1
            }
        }
        refCount = count
        if (System.getenv("MANDEL_DEBUG") != null) println("refined $count of $n")
        if (refIdx.size < count) refIdx = IntArray(count + count / 2)
        if (subMu.size < count * 9) { subMu = FloatArray(count * 9 + count * 4); subDe = FloatArray(subMu.size) }
        for (i in 0 until n) if (refined[i] >= 0) refIdx[refined[i]] = i
        val fine = cell / 3.0
        val stepX = 2.0 * scale * halfW / (cs - 1)
        val stepY = 2.0 * scale / (rs - 1)
        Parallel.forRange(count * 9) { from, to ->
            for (s in from until to) {
                val ci = refIdx[s / 9]
                val sub = s % 9
                val col = ci % cs
                val row = ci / cs
                val x0 = cx + (col.toDouble() / (cs - 1) * 2.0 - 1.0) * scale * halfW + ((sub % 3) - 1) * stepX / 3.0
                val y0 = cy + (row.toDouble() / (rs - 1) * 2.0 - 1.0) * scale + ((sub / 3) - 1) * stepY / 3.0
                escape(x0, y0, mi, fine, subMu, subDe, s)
            }
        }
        // A sub-sample that never escaped, in a block where most did, is a
        // filament finer than a sub-cell (see healSpecks): drawn as boundary.
        for (r in 0 until count) {
            val b = r * 9
            var out = 0
            var sum = 0f
            for (k in 0 until 9) if (subMu[b + k] >= 0f) { out++; sum += subMu[b + k] }
            if (out >= 6) for (k in 0 until 9) if (subMu[b + k] < 0f) { subMu[b + k] = sum / out; subDe[b + k] = 0f }
        }
    }

    /** The escape count before any healing; healed cells are -1 here. */
    private fun rawMu(i: Int): Float = if (healedMask.size == iterations.size && healedMask[i]) -1f else iterations[i]

    private var rawDist = FloatArray(0)
    private var healedMask = BooleanArray(0)
    /** Per coarse cell, its index among the refined cells, or -1. */
    private var refined = IntArray(0)
    private var refIdx = IntArray(0)
    private var refCount = 0
    private var subMu = FloatArray(0)
    private var subDe = FloatArray(0)
    /** Each coarse cell's crowding dimmer from edgeGlow, applied to its sub-cells too. */
    private var crowdK = FloatArray(0)

    /**
     * A cell that has not escaped within the iteration budget but is mostly
     * surrounded by cells that have is not a piece of the set's body: it is a
     * filament finer than a cell whose orbit is just slow. Left black, those
     * cells peppered every lit band with dark specks (the "swiss cheese").
     * Such a cell is drawn as boundary (distance 0) at its neighbours' escape
     * count. Cells with fewer escaped neighbours — the edges and insides of
     * bulbs and mini-sets — stay black, so real interior is untouched.
     */
    private fun healSpecks(cs: Int, rs: Int) {
        if (heal.size != cs * rs) heal = FloatArray(cs * rs)
        for (row in 0 until rs) {
            val base = row * cs
            for (col in 0 until cs) {
                heal[base + col] = -2f
                if (iterations[base + col] >= 0f) continue
                var outside = 0
                var sum = 0f
                for (oy in -1..1) for (ox in -1..1) {
                    if (ox == 0 && oy == 0) continue
                    val r = row + oy
                    val c = col + ox
                    if (r < 0 || r >= rs || c < 0 || c >= cs) continue
                    val v = iterations[r * cs + c]
                    if (v >= 0f) { outside++; sum += v }
                }
                if (outside >= 5) heal[base + col] = sum / outside
            }
        }
        if (healedMask.size != cs * rs) healedMask = BooleanArray(cs * rs)
        for (i in 0 until cs * rs) if (heal[i] > -2f) { iterations[i] = heal[i]; edgeDist[i] = 0f; healedMask[i] = true }
    }

    private var heal = FloatArray(0)

    /**
     * How strongly each outside cell takes the edge colour. The core is a
     * smooth falloff with the distance estimate (in cells): full on the set,
     * gone a cell out, so the boundary is a line one cell wide, never a band.
     * Where filaments are packed tighter than the grid (a whole neighbourhood
     * of edge cells), each is lit less, so a tangle reads as a textured
     * mid-tone instead of a solid slab of yellow — an isolated filament keeps
     * its full brightness.
     */
    private fun edgeGlow(cs: Int, rs: Int) {
        if (glow.size != cs * rs) glow = FloatArray(cs * rs)
        if (smooth.size != cs * rs) smooth = FloatArray(cs * rs)
        if (crowdK.size != cs * rs) crowdK = FloatArray(cs * rs)
        java.util.Arrays.fill(crowdK, 1f)
        System.arraycopy(iterations, 0, smooth, 0, cs * rs)
        for (i in 0 until cs * rs) {
            glow[i] = if (iterations[i] < 0f) 0f else {
                val d = edgeDist[i] / EDGE_CELLS
                1f / (1f + d * d * d)
            }
        }
        for (row in 0 until rs) {
            val base = row * cs
            for (col in 0 until cs) {
                val e = glow[base + col]
                if (e < 0.02f) { edgeDist[base + col] = 0f; continue }
                var sum = 0f
                var cnt = 0
                for (oy in -1..1) for (ox in -1..1) {
                    if (ox == 0 && oy == 0) continue
                    val r = row + oy
                    val c = col + ox
                    if (r < 0 || r >= rs || c < 0 || c >= cs) continue
                    sum += glow[r * cs + c]; cnt++
                }
                val crowd = if (cnt == 0) 0f else sum / cnt
                // Near the set neighbouring escape counts jump about, and a hue
                // per cell was confetti; there the colour comes from the
                // neighbourhood's mean count. Far from the set nothing changes.
                var ms = 0f
                var mc = 0
                for (oy in -1..1) for (ox in -1..1) {
                    val r = row + oy
                    val c = col + ox
                    if (r < 0 || r >= rs || c < 0 || c >= cs) continue
                    val v = iterations[r * cs + c]
                    if (v >= 0f) { ms += v; mc++ }
                }
                smooth[base + col] = ms / mc
                // edgeDist is reused to hold the final mix; it is not read again.
                crowdK[base + col] = 1f - 0.55f * crowd * crowd
                edgeDist[base + col] = e * crowdK[base + col]
            }
        }
    }

    private var glow = FloatArray(0)
    /** Escape counts for colouring, smoothed near the set (see edgeGlow). */
    private var smooth = FloatArray(0)

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        g.reserveInstances(cols * rows + refCount * 8)
        val halfW = env.halfWidth
        val dx = 2f * halfW / cols
        val dy = 2f / rows
        // The image is recomputed eight times a second and the zoom is
        // continuous, so in between the last image is drawn grown by however
        // far the zoom has gone since — about the centre, which is where the
        // zoom is aimed. It used to sit still for an eighth of a second and
        // then jump 2.8%, eight times a second: a zoom that stuttered, on the
        // one sim whose whole subject is a smooth dive.
        val k = (zoom / computedZoom).toFloat().coerceIn(0.5f, 4f)
        // Square cells that tile, a fifth oversized so no seam shows between
        // them. Round point sprites left a dark gap at every corner, which on
        // a phone read as a grid of beads rather than a picture.
        val hw = dx * 0.5f * k * 1.18f
        val hh = dy * 0.5f * k * 1.18f
        val span = max(0.5f, highMu - lowMu)
        val edgeColour = brightestOf(palette)
        val shw = hw / 3f
        val shh = hh / 3f
        val sdx = dx / 3f * k
        val sdy = dy / 3f * k
        val hasSubs = refined.size == cols * rows
        for (row in 0 until rows) {
            val y = (-1f + (row + 0.5f) * dy) * k
            if (abs(y) - hh > 1f) continue
            val base = row * cols
            for (col in 0 until cols) {
                val x = (-halfW + (col + 0.5f) * dx) * k
                if (abs(x) - hw > halfW) continue
                val ref = if (hasSubs) refined[base + col] else -1
                val mu = smooth[base + col]
                if (ref < 0) {
                    if (iterations[base + col] < 0f) continue
                    // The lit filament, as weighted by edgeGlow(). A hard
                    // "within 1.2 cells" threshold switched whole cells on and
                    // off along sub-cell filaments: a thick, ragged band.
                    val edge = edgeDist[base + col]
                    val c = baseColour(palette, mu, span)
                    g.rect(x, y, hw, hh, if (edge > 0.02f) Palette.lerp(c, edgeColour, 0.9f * edge) else c)
                    continue
                }
                // A boundary cell, drawn as its nine exact sub-samples. Their
                // base colour is the neighbourhood's (a hue per sample was
                // confetti); the lit edge comes from each one's own distance.
                val ck = crowdK[base + col]
                val centre = if (mu >= 0f) mu else neighbourMu(row, col)
                val b = ref * 9
                for (sub in 0 until 9) {
                    if (subMu[b + sub] < 0f) continue
                    val cBase = baseColour(palette, subBase(row, col, sub % 3 - 1, sub / 3 - 1, centre), span)
                    val d = subDe[b + sub] / EDGE_CELLS
                    val edge = ck / (1f + d * d * d)
                    val sx = x + ((sub % 3) - 1) * sdx
                    val sy = y + ((sub / 3) - 1) * sdy
                    g.rect(sx, sy, shw, shh, if (edge > 0.02f) Palette.lerp(cBase, edgeColour, 0.9f * edge) else cBase)
                }
            }
        }
    }

    private fun baseColour(palette: Palette, mu: Float, span: Float): Int {
        val lm = ln(1f + max(0f, mu))
        // Hue cycles smoothly with the log of the escape count — a cosine, so
        // the palette runs out and back with no seam — over most of the
        // palette, leaving its far end to the edge.
        val hue = HUE_SPAN * (0.5f - 0.5f * cos(lm * HUE_CYCLES * 2f * PI.toFloat()))
        // Brightness rises towards the boundary.
        val near = ((lm - lowMu) / span).coerceIn(0f, 1f)
        val lit = 0.17f + 0.77f * near * sqrt(near)
        return Palette.lerp(Palette.BG, palette.at(hue), lit)
    }

    /**
     * The base count at a sub-sample a third of a cell off centre, blended
     * bilinearly towards the neighbouring cells: a flat colour per coarse cell
     * showed the coarse grid as squares inside the refined boundary.
     */
    private fun subBase(row: Int, col: Int, ox: Int, oy: Int, centre: Float): Float {
        if (ox == 0 && oy == 0) return centre
        val c2 = (col + ox).coerceIn(0, cols - 1)
        val r2 = (row + oy).coerceIn(0, rows - 1)
        val a = centre
        val bx = smooth[row * cols + c2].let { if (it >= 0f) it else a }
        val by = smooth[r2 * cols + col].let { if (it >= 0f) it else a }
        val bxy = smooth[r2 * cols + c2].let { if (it >= 0f) it else a }
        val wx = if (ox == 0) 0f else 1f / 3f
        val wy = if (oy == 0) 0f else 1f / 3f
        return (a * (1 - wx) + bx * wx) * (1 - wy) + (by * (1 - wx) + bxy * wx) * wy
    }

    /** An inside cell's escaped neighbours' mean count, for its escaped sub-samples. */
    private fun neighbourMu(row: Int, col: Int): Float {
        var s = 0f
        var n = 0
        for (oy in -1..1) for (ox in -1..1) {
            val r = row + oy
            val c = col + ox
            if (r < 0 || r >= rows || c < 0 || c >= cols) continue
            val v = smooth[r * cols + c]
            if (v >= 0f) { s += v; n++ }
        }
        return if (n == 0) highMu else s / n
    }

    override fun onInput(env: SimEnv, e: TouchEvent) {
        if (e.kind == TouchEvent.Kind.TAP) {
            val scale = 1.4 / zoom
            centreX += (e.x / env.halfWidth) * scale * env.halfWidth
            centreY += e.y * scale
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "zoom" to String.format(java.util.Locale.US, "%.0fx", zoom / 0.6),
        "detail" to iterNow.toString()
    )

    companion object {
        const val MAX_ITER_DEEP = 1500
        private val LN2 = ln(2.0)
        /** Colour cycles per e-fold of escape count: two or three bands across a view. */
        private const val HUE_CYCLES = 0.6f
        /** Coarse cells within this distance of the set are resampled 3×3. */
        private const val REFINE_CELLS = 1.6f
        /** A jump in log escape count between neighbours that shows as a step. */
        private const val STEP_LM = 0.06f
        /** How many cells out from the set the lit edge reaches. */
        private const val EDGE_CELLS = 0.35f
        private const val HUE_SPAN = 0.78f

        /** The palette's lightest colour, for the edge of the set: white on Mono, yellow on Viridis. */
        fun brightestOf(p: Palette): Int {
            var best = p.at(0f)
            var bestL = -1
            for (k in 0..8) {
                val c = p.at(k / 8f)
                val l = 2 * ((c ushr 16) and 255) + 5 * ((c ushr 8) and 255) + (c and 255)
                if (l > bestL) { bestL = l; best = c }
            }
            return best
        }
        /**
         * How much finer the detail has to get before the zoom has arrived.
         * A thousandfold is plainly deeper than where it started and is reached
         * at the default zoom rate inside a normal run.
         */
        const val TARGET_DEPTH = 1000.0

        /** Full images a second. The zoom still reads as smooth below this. */
        const val REFRESH_HZ = 8f

        fun def(): SimDef = SimDef(
            id = "math/mandelbrot",
            title = "Mandelbrot zoom",
            shelf = Shelf.MATH,
            family = "Fractals & iteration",
            blurb = "One equation, infinite depth. Tap to steer.",
            formats = listOf(TrapFormat.GROW, TrapFormat.REVEAL),
            heroLabel = "New target",
            defaultPalette = "Aurora",
            typicalSeconds = 30f,
            params = listOf(
                Param.choice(
                    "target", "Where to dive",
                    listOf("Seahorse valley", "Spiral", "Antenna", "Misiurewicz"), 0,
                    card = Card.DATA, input = ParamInput.CHIPS
                ),
                Param.int("resolution", "Resolution", 40, 460, 240, card = Card.LOOK),
                Param.int("detail", "Detail", 40, 1200, 220, card = Card.LOOK, logScale = true),
                Param.float("zoom", "Zoom speed", 0.02f, 1.2f, 0.22f, card = Card.MOTION)
            ),
            create = { MandelbrotSim() }
        )
    }
}

/**
 * The chaos game: drop a point at random, repeatedly jump halfway to a randomly
 * chosen corner, and a Sierpinski triangle appears out of pure noise.
 */
class ChaosGameSim : Sim {

    /** The payoff fires once; without this the bell retriggers every step. */
    private var formed = false

    private var corners = 3
    private var ratio = 0.5f
    private var px = 0f
    private var py = 0f
    private var points = FloatArray(0)
    private var tints = FloatArray(0)
    /** Every point ever plotted. The buffer holds the newest [capacity]. */
    private var count = 0L
    private var capacity = 0
    /** Slow phase for the breathing that starts once the shape has formed. */
    private var breath = 0f
    private var carry = 0f
    private var payoffFrame = false
    private val cornerX = FloatArray(12)
    private val cornerY = FloatArray(12)
    private var noRepeat = false
    private var lastCorner = -1

    override fun setup(env: SimEnv) {
        val p = env.params
        corners = p.i("corners", 3).coerceIn(3, 12)
        // Halfway suits a triangle and no other shape: a square at a half
        // just fills in. The jump that makes an n-gon's copies touch without
        // overlapping is 1 − 1/(2·(1 + Σ cos(2πk/n), k = 1..⌊n/4⌋)) — a half
        // for three, the golden 0.618 for five, two thirds for six.
        val fit = p.b("fit", true)
        ratio = if (fit) fittedRatio(corners) else p.f("ratio", 0.5f)
        // The square is the one shape the formula cannot help: its copies at a
        // half tile it exactly, and the "fractal" was an evenly filled square.
        // The classic fix is a rule rather than a ratio — never the same
        // corner twice running — which carves the square into a fractal at
        // the same half.
        noRepeat = fit && corners == 4
        lastCorner = -1
        capacity = p.i("dots", 120000).coerceIn(1000, 400000)
        points = FloatArray(capacity * 2)
        tints = FloatArray(capacity)
        count = 0L
        breath = 0f
        carry = 0f
        payoffFrame = false
        var lowY = 0f
        var highY = 0f
        for (i in 0 until corners) {
            val a = -PI.toFloat() / 2f + i * 2f * PI.toFloat() / corners
            cornerX[i] = cos(a) * 0.92f
            cornerY[i] = sin(a) * 0.92f
            lowY = min(lowY, cornerY[i]); highY = max(highY, cornerY[i])
        }
        // Centred on its own height: an odd polygon round the origin sat a
        // quarter of its size low, the triangle hanging under an empty band.
        val shift = -(lowY + highY) * 0.5f
        for (i in 0 until corners) cornerY[i] += shift
        var wide = 0f
        for (i in 0 until corners) wide = max(wide, abs(cornerX[i]))
        reach = max(wide, (highY - lowY) * 0.5f) + 0.03f
        px = 0f
        py = 0f
        formed = false
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        carry += abs(dt) * env.params.f("rate", 6000f)
        var budget = min(carry.toInt(), 60000)
        carry -= budget
        // Once the shape has formed it does not stop: the jump ratio breathes a
        // few per cent either side of the slider, and because the newest points
        // overwrite the oldest, the fractal's gaps slowly open and close while
        // the dots keep landing. It used to report finished the moment the
        // buffer was full, which in the feed and in TickTock meant the sim was
        // thrown away the instant it had finished becoming worth looking at.
        if (formed) breath += abs(dt) * BREATH_HZ * 2f * PI.toFloat()
        val r = ratio * (1f + BREATH_DEPTH * sin(breath))
        val before = count
        while (budget-- > 0) {
            var c = env.rng.int(0, corners)
            if (noRepeat && c == lastCorner) c = (c + 1 + env.rng.int(0, corners - 1)) % corners
            lastCorner = c
            px += (cornerX[c] - px) * r
            py += (cornerY[c] - py) * r
            val slot = (count % capacity).toInt()
            points[slot * 2] = px
            points[slot * 2 + 1] = py
            tints[slot] = c.toFloat() / corners
            count++
        }
        // A note every time another block of dots lands. The old test — the
        // count landing in a narrow window — only caught that window when a
        // frame's batch happened to straddle it, so the notes came in random
        // clumps with three-second silences between.
        if (count / NOTE_EVERY != before / NOTE_EVERY) {
            val level = if (formed) 0.5f + 0.5f * sin(breath)
            else (count.toFloat() / capacity).coerceIn(0f, 1f)
            env.emit(EventKind.WRITE, level, 0f, 0.35f)
        }
        // The payoff is the moment the shape is unmistakable.
        val formAt = capacity * 6L / 10L
        if (!formed && count >= formAt && count - formAt < 2000) {
            formed = true
            payoffFrame = true
            env.emit(EventKind.DONE, 0.85f, 0f, 1f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val shown = min(count, capacity.toLong()).toInt()
        g.reservePoints(shown)
        val size = env.params.f("dot", 0.0035f)
        for (i in 0 until shown) {
            g.point(points[i * 2], points[i * 2 + 1], size, palette.at(tints[i]))
        }
        for (i in 0 until corners) {
            g.glow(cornerX[i], cornerY[i], 0.07f, Palette.ACCENT, 0.4f)
        }
    }

    /**
     * Drawn at a fixed size; on a phone held upright the view pulls back to
     * fit it. Measured from the corners: a fixed 0.82 suited the triangle and
     * cut the sides off a pentagon, which reaches 0.875.
     */
    private var reach = 0.82f
    override fun frameHalfWidth(env: SimEnv): Float = reach
    override fun frameHalfHeight(env: SimEnv): Float = reach

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = false

    override fun counters(): List<Pair<String, String>> =
        listOf("dots" to String.format(java.util.Locale.US, "%,d", count))

    private fun fittedRatio(n: Int): Float {
        var sum = 0.0
        for (k in 1..n / 4) sum += kotlin.math.cos(2.0 * PI * k / n)
        return (1.0 - 1.0 / (2.0 * (1.0 + sum))).toFloat()
    }

    companion object {
        /** One slow breath every twenty-five seconds. */
        private const val BREATH_HZ = 0.04f
        private const val NOTE_EVERY = 2048L

        /** How far the jump ratio breathes either side of the slider's value. */
        private const val BREATH_DEPTH = 0.06f

        fun def(): SimDef = SimDef(
            id = "math/chaos-game",
            title = "Chaos game",
            shelf = Shelf.MATH,
            family = "Fractals & iteration",
            blurb = "Random jumps that draw a fractal: Sierpinski's triangle, or any polygon's own snowflake.",
            formats = listOf(TrapFormat.GROW, TrapFormat.REVEAL),
            heroLabel = "Start again",
            defaultPalette = "Candy",
            typicalSeconds = 20f,
            params = listOf(
                Param.int("corners", "Corners", 3, 12, 3, card = Card.DATA),
                Param.bool("fit", "Fit the jump to the shape", true, card = Card.DATA),
                Param.float("ratio", "Jump fraction", 0.2f, 0.8f, 0.5f, card = Card.DATA, mutedBy = "fit"),
                Param.int("dots", "Dots", 20000, 400000, 120000, card = Card.DATA, logScale = true),
                Param.float("rate", "Speed", 200f, 120000f, 6000f, card = Card.MOTION, logScale = true),
                Param.float("dot", "Dot size", 0.001f, 0.012f, 0.0035f, card = Card.LOOK)
            ),
            create = { ChaosGameSim() }
        )
    }
}

/**
 * The Ulam spiral: whole numbers wound into a square spiral, primes lit up. The
 * diagonal streaks nobody can fully explain are the payoff.
 */
class PrimeSpiralSim : Sim {

    /** The payoff fires once; without this the bell retriggers every step. */
    private var spiralled = false
    private var restSeconds = -1f

    private var limit = 40000
    private var drawn = 0
    private var isPrime = BooleanArray(0)
    private var xs = FloatArray(0)
    private var ys = FloatArray(0)
    private var carry = 0f
    private var payoffFrame = false
    private var primeCount = 0
    private var sacks = false

    /**
     * Per diagonal of the Ulam square — x + y and x - y, offset to index
     * from zero — the numbers drawn on it so far and how many were prime.
     * A prime is drawn as bright as the diagonals through it are rich: the
     * famous streaks are exactly the diagonals with far more primes than
     * their share, and drawn all one white they were lost in the lattice of
     * the rest. Nothing is moved or added; only the emphasis follows the
     * counts, so the picture is still Ulam's.
     */
    private var diagOffset = 0
    private var cellsSum = IntArray(0)
    private var primesSum = IntArray(0)
    private var cellsDiff = IntArray(0)
    private var primesDiff = IntArray(0)

    override fun setup(env: SimEnv) {
        val p = env.params
        limit = p.i("limit", 40000).coerceIn(200, 400000)
        sacks = p.choiceIndex("layout") == 1
        isPrime = sieve(limit)
        xs = FloatArray(limit)
        ys = FloatArray(limit)
        buildLayout()
        diagOffset = (sqrt(limit.toFloat()) + 4f).toInt()
        val span = 2 * diagOffset + 1
        cellsSum = IntArray(span); primesSum = IntArray(span)
        cellsDiff = IntArray(span); primesDiff = IntArray(span)
        drawn = 0
        carry = 0f
        payoffFrame = false
        primeCount = 0
    }

    private fun sieve(n: Int): BooleanArray {
        val flags = BooleanArray(n + 1) { it >= 2 }
        var i = 2
        while (i.toLong() * i <= n) {
            if (flags[i]) {
                var j = i * i
                while (j <= n) {
                    flags[j] = false
                    j += i
                }
            }
            i++
        }
        return flags
    }

    private fun buildLayout() {
        if (sacks) {
            // Sacks spiral: radius grows with the square root, angle with it too.
            for (n in 0 until limit) {
                val r = sqrt(n.toFloat())
                val a = r * 2f * PI.toFloat()
                xs[n] = cos(a) * r
                ys[n] = sin(a) * r
            }
        } else {
            var x = 0
            var y = 0
            var dx = 1
            var dy = 0
            var segment = 1
            var stepsInSegment = 0
            var turns = 0
            // Ulam's spiral starts at 1 in the middle; 0, never prime, sits
            // under it. It started at 0, which put every prime a square off
            // its place in Ulam's picture.
            xs[0] = 0f; ys[0] = 0f
            for (n in 1 until limit) {
                xs[n] = x.toFloat()
                ys[n] = y.toFloat()
                x += dx
                y += dy
                stepsInSegment++
                if (stepsInSegment == segment) {
                    stepsInSegment = 0
                    val t = dx
                    dx = -dy
                    dy = t
                    turns++
                    if (turns % 2 == 0) segment++
                }
            }
        }
        spiralled = false
        restSeconds = -1f
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        if (restSeconds >= 0f) {
            restSeconds -= abs(dt)
            return
        }
        carry += abs(dt) * env.params.f("rate", 2600f)
        var budget = min(carry.toInt(), 80000)
        carry -= budget
        val before = drawn
        drawn = min(limit, drawn + budget)
        for (n in before until drawn) {
            val prime = isPrime[n]
            if (prime) primeCount++
            if (!sacks && n >= 1) {
                val x = xs[n].toInt(); val y = ys[n].toInt()
                val dS = x + y + diagOffset
                val dD = x - y + diagOffset
                cellsSum[dS]++; cellsDiff[dD]++
                if (prime) { primesSum[dS]++; primesDiff[dD]++ }
            }
        }
        if (drawn > before && primeCount > 0) {
            env.emit(EventKind.WRITE, (drawn.toFloat() / limit).coerceIn(0f, 1f), 0f, 0.3f)
        }
        if (!spiralled && drawn >= limit) {
            spiralled = true
            payoffFrame = true
            // The finished spiral is the thing worth looking at, so the run
            // holds on it instead of ending on the same step as its payoff.
            restSeconds = 1.5f
            env.emit(EventKind.DONE, 0.9f, 0f, 1f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        g.reservePoints(min(drawn, limit))
        // The finished spiral fills the screen's longer side and is cropped
        // to the shorter: on an upright phone a square fitted to the width
        // used under half the screen, a small grey patch in the middle of a
        // tall black one. The diagonals the sim is about run on through the
        // crop just the same.
        val halfW = env.halfWidth
        val halfH = 1f
        val fill = max(halfW, halfH) * 0.98f
        val finalExtent = max(1f, if (sacks) sqrt(limit.toFloat()) else sqrt(limit.toFloat()) * 0.5f)
        val nowExtent = max(MIN_EXTENT, if (sacks) sqrt(drawn.toFloat()) else sqrt(drawn.toFloat()) * 0.5f)
        // While it is small the view holds it across the narrow side, so the
        // first numbers land as large dots and the camera pulls back as the
        // spiral grows, until it settles at the finished scale.
        val scale = max(fill / finalExtent, min(halfW, halfH) * 0.96f / nowExtent)
        val cellSize = if (sacks) scale * 0.8f else scale
        val size = min(MAX_DOT, max(0.0022f, cellSize * 0.82f))
        val reachX = halfW + size
        val reachY = halfH + size
        // The share of drawn ODD numbers that are prime, the yardstick a
        // diagonal is measured against. The spiral is a checkerboard of odd
        // and even, so every diagonal is all one or all the other: against
        // the share of all numbers, every odd diagonal read as twice as rich
        // as it is, and every prime lit up alike.
        val avg = max(1e-3f, 2f * primeCount.toFloat() / max(1, drawn))
        val shrink = DIAG_PRIOR * avg
        for (n in 2 until drawn) {
            if (!isPrime[n]) continue
            val x = xs[n] * scale
            val y = ys[n] * scale
            if (abs(x) > reachX || abs(y) > reachY) continue
            if (sacks) {
                // Colour runs out from the centre over the first part of the
                // palette, whose far end is near-black on some palettes.
                g.point(x, y, size, palette.at(0.08f + 0.6f * n.toFloat() / limit))
                continue
            }
            val dS = xs[n].toInt() + ys[n].toInt() + diagOffset
            val dD = xs[n].toInt() - ys[n].toInt() + diagOffset
            // Shrunk towards the average, so a short diagonal with two
            // primes on it does not count as a streak.
            val rS = (primesSum[dS] + shrink) / (cellsSum[dS] + DIAG_PRIOR)
            val rD = (primesDiff[dD] + shrink) / (cellsDiff[dD] + DIAG_PRIOR)
            val rich = ((max(rS, rD) / avg - 1f) / DIAG_RICH).coerceIn(0f, 1f)
            val c = Palette.withAlpha(palette.at(0.68f * (1f - rich)), 0.6f + 0.4f * rich)
            g.point(x, y, size * (0.8f + 0.3f * rich), c)
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = drawn >= limit && restSeconds in -1000f..0f

    override fun counters(): List<Pair<String, String>> = listOf(
        "numbers" to String.format(java.util.Locale.US, "%,d", drawn),
        "primes" to String.format(java.util.Locale.US, "%,d", primeCount)
    )

    companion object {
        /** The smallest spiral, in cells from the centre, the view closes in on. */
        private const val MIN_EXTENT = 9f
        private const val MAX_DOT = 0.045f
        /** Numbers' worth of the average a diagonal is shrunk towards. */
        private const val DIAG_PRIOR = 24f
        /** How many times the average share counts as fully rich, less one. */
        private const val DIAG_RICH = 0.9f
        fun def(): SimDef = SimDef(
            id = "math/prime-spiral",
            title = "Prime spiral",
            shelf = Shelf.MATH,
            family = "Number theory",
            blurb = "Primes on a spiral line up in diagonal streaks — prime-rich quadratics, still not fully explained.",
            formats = listOf(TrapFormat.GROW, TrapFormat.REVEAL),
            heroLabel = "Draw again",
            // Ember, from Mono glow: its gold end marks the prime-rich
            // diagonals and its deep red the rest, where in greys the streaks
            // could only be told by brightness.
            defaultPalette = "Ember",
            typicalSeconds = 16f,
            params = listOf(
                Param.choice("layout", "Layout", listOf("Ulam square", "Sacks"), 0, card = Card.DATA, input = ParamInput.TILES),
                Param.int("limit", "Numbers", 8000, 400000, 40000, card = Card.DATA, logScale = true),
                Param.float("rate", "Speed", 200f, 50000f, 2600f, card = Card.MOTION, logScale = true)
            ),
            create = { PrimeSpiralSim() }
        )
    }
}

/**
 * Monte Carlo pi: throw darts at a square, count how many land in the circle.
 * The estimate wobbling in on 3.14159 is the whole story.
 */
class MonteCarloPiSim : Sim {

    // Longs: this runs for as long as anyone watches, and at five thousand
    // darts a second an Int count wraps in under five days.
    private var total = 0L
    private var inside = 0L
    private var capacity = 60000
    private var xs = FloatArray(0)
    private var ys = FloatArray(0)
    private var hit = BooleanArray(0)
    private var stored = 0
    private var carry = 0f
    private var payoffFrame = false
    private var converged = false

    /**
     * The estimate as it went, sampled at counts spaced evenly on a log scale,
     * for the trace under the square on a tall screen: the wobble narrowing
     * onto pi is the whole story, and the square alone cannot show it.
     */
    private val historyAt = LongArray(HISTORY)
    private val historyPi = FloatArray(HISTORY)
    private var historyCount = 0
    private var nextSample = 1L

    override fun setup(env: SimEnv) {
        capacity = env.params.i("dots", 60000).coerceIn(500, 400000)
        xs = FloatArray(capacity)
        ys = FloatArray(capacity)
        hit = BooleanArray(capacity)
        total = 0L
        inside = 0L
        stored = 0
        carry = 0f
        payoffFrame = false
        converged = false
        historyCount = 0
        nextSample = 1L
    }

    private fun estimate(): Float = if (total == 0L) 0f else (4.0 * inside / total).toFloat()

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        carry += abs(dt) * env.params.f("rate", 5000f)
        var budget = min(carry.toInt(), 50000)
        carry -= budget
        // It never stops. The estimate is the whole of every dart ever thrown,
        // so it goes on tightening as 1/sqrt(n) for as long as anyone watches;
        // the picture keeps the newest [capacity] of them, overwriting the
        // oldest, so the square keeps glittering instead of freezing full.
        // It used to report finished the moment the buffer filled, and the
        // feed and TickTock threw it away at that instant — mid-convergence.
        val before = total
        while (budget-- > 0) {
            val x = env.rng.range(-1f, 1f)
            val y = env.rng.range(-1f, 1f)
            val isIn = x * x + y * y <= 1f
            if (isIn) inside++
            val slot = (total % capacity).toInt()
            total++
            xs[slot] = x
            ys[slot] = y
            hit[slot] = isIn
            if (stored < capacity) stored++
            if (total >= nextSample && historyCount < HISTORY) {
                historyAt[historyCount] = total
                historyPi[historyCount] = estimate()
                historyCount++
                nextSample = max(total + 1, (total * HISTORY_STEP).toLong())
            }
        }
        // A note each time another block of darts lands; the old test caught
        // a narrow window only when a frame's batch straddled it.
        if (total / NOTE_EVERY != before / NOTE_EVERY) {
            env.emit(EventKind.STEP, (estimate() / 4f).coerceIn(0f, 1f), 0f, 0.3f)
        }
        // The estimate is only ever as good as the sample is large — the error
        // falls off as 1/sqrt(n) — so the tolerance follows the count instead
        // of being a fixed 0.006 that a five-hundred-dart run can never meet
        // and that four seeds in twenty-four never met even at full size.
        val tolerance = max(0.006f, 2.4f / sqrt(max(1L, total).toFloat()))
        if (!converged && total > (capacity * 0.6f).toLong() &&
            abs(estimate() - PI.toFloat()) < tolerance
        ) {
            converged = true
            payoffFrame = true
            env.emit(EventKind.DONE, 0.9f, 0f, 1f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val r = 0.86f
        // On a tall screen the view pulls back to fit the square's width, and
        // the room that leaves above and below goes to the trace: the square
        // moves up, the estimate's history runs underneath.
        val visibleHalf = max(1f, frameHalfWidth(env) / env.halfWidth)
        val room = visibleHalf * 2f - 2f * r - GAP * 3f
        val traced = room > MIN_TRACE
        val cy = if (traced) visibleHalf - GAP - r else 0f
        g.reservePoints(stored + FRESH)
        // Hits in the palette's bright colour, misses in a dim warm coral, and
        // each grain big enough that by the default count they tile into a
        // solid lit coin in a darker square: the area ratio itself, visible.
        // As 2 px grains in grey the split read as faint noise. Grains shrink
        // with the count so a big run does not fill to a flat slab.
        val inC = Palette.withAlpha(Palette.brighten(palette.at(HIT_T), 0.1f), 0.78f)
        val outC = Palette.withAlpha(Palette.lerp(Palette.BG, MISS, 0.7f), 0.7f)
        val grain = (0.0075f * sqrt(60000f / max(20000, capacity).toFloat())).coerceIn(0.0035f, 0.009f)
        for (i in 0 until stored) {
            g.point(xs[i] * r, cy + ys[i] * r, grain, if (hit[i]) inC else outC)
        }
        // The newest darts, larger and bright, so the throwing is visible.
        val fresh = min(FRESH.toLong(), total).toInt()
        for (k in 0 until fresh) {
            val slot = ((total - 1 - k) % capacity).toInt()
            val fade = 1f - k.toFloat() / FRESH
            val c = if (hit[slot]) Palette.brighten(palette.at(HIT_T), 0.35f) else Palette.brighten(MISS, 0.2f)
            g.point(xs[slot] * r, cy + ys[slot] * r, 0.009f + 0.006f * fade, Palette.withAlpha(c, 0.35f + 0.65f * fade))
        }
        // The square's outline, so the board the darts land on is drawn.
        val edge = Palette.withAlpha(Palette.TEXT_SECONDARY, 0.5f)
        g.rect(0f, cy + r, r + 0.003f, 0.003f, edge); g.rect(0f, cy - r, r + 0.003f, 0.003f, edge)
        g.rect(-r, cy, 0.003f, r, edge); g.rect(r, cy, 0.003f, r, edge)
        g.ring(0f, cy, r, 0.006f, Palette.brighten(palette.at(HIT_T), 0.45f), alpha = 0.95f)
        if (traced) drawTrace(g, palette, -visibleHalf + GAP, cy - r - GAP, r)
    }

    /** The estimate against the number of darts, on a log scale, with pi drawn across. */
    private fun drawTrace(g: G, palette: Palette, bottom: Float, top: Float, halfW: Float) {
        val mid = (bottom + top) * 0.5f
        val half = (top - bottom) * 0.5f
        val lineC = Palette.brighten(palette.at(HIT_T), 0.2f)
        // The frame the trace lives in, faint.
        val frame = Palette.withAlpha(Palette.TEXT_SECONDARY, 0.18f)
        g.rect(0f, top, halfW, 0.002f, frame); g.rect(0f, bottom, halfW, 0.002f, frame)
        // The first few darts swing the estimate between 0 and 4; drawn, they
        // were spikes pinned to the frame's edges. The trace starts once the
        // count is large enough for the estimate to mean something.
        var first = 0
        while (first < historyCount && historyAt[first] < TRACE_FROM) first++
        val lo = kotlin.math.ln(TRACE_FROM.toFloat())
        val hi = max(lo + 1f, kotlin.math.ln(max(total, TRACE_MIN_SPAN).toFloat()))
        // A tick at every power of ten darts, on the log axis.
        var decade = 100.0
        while (kotlin.math.ln(decade) <= hi) {
            val tx = -halfW + 2f * halfW * (kotlin.math.ln(decade).toFloat() - lo) / (hi - lo)
            if (tx > -halfW) g.rect(tx, bottom + 0.018f, 0.002f, 0.018f, Palette.withAlpha(Palette.TEXT_SECONDARY, 0.4f))
            decade *= 10.0
        }
        // Two standard errors either side of pi, 2·4·sqrt(p(1−p)/n) with
        // p = pi/4: the funnel an honest estimate stays inside 95% of the
        // time, filled softly so the closing-in reads at a glance.
        val sigma1 = 4.0 * sqrt((PI / 4) * (1 - PI / 4))
        var ex = 0f; var eu = 0f; var el = 0f
        val steps = 64
        val fill = Palette.withAlpha(lineC, 0.09f)
        for (j in 0..steps) {
            val lnN = lo + (hi - lo) * j / steps
            val band = (2.0 * sigma1 / Math.exp(lnN.toDouble()).let { sqrt(it) }).toFloat() / TRACE_RANGE
            val x = -halfW + 2f * halfW * j / steps
            val bc = band.coerceAtMost(1f)
            val u = mid + bc * half
            val l = mid - bc * half
            if (j > 0) {
                // Abutting, not overlapping: overlaps doubled the alpha in stripes.
                g.rect((ex + x) * 0.5f, mid, (x - ex) * 0.5f, (bc * half + (eu - mid)) * 0.5f, fill)
                // Edges only where the band fits inside the frame; clamped,
                // they ran along the frame's edge as a flat line.
                if (band < 1f) {
                    g.line(ex, eu, x, u, 0.003f, Palette.withAlpha(lineC, 0.4f))
                    g.line(ex, el, x, l, 0.003f, Palette.withAlpha(lineC, 0.4f))
                }
            }
            ex = x; eu = u; el = l
        }
        g.line(-halfW, mid, halfW, mid, 0.004f, Palette.WARNING, 0.8f)
        if (historyCount - first < 2) return
        var px = 0f; var py = 0f
        for (k in first until historyCount) {
            val x = -halfW + 2f * halfW * (kotlin.math.ln(historyAt[k].toFloat()) - lo) / (hi - lo)
            val off = ((historyPi[k] - PI.toFloat()) / TRACE_RANGE).coerceIn(-1f, 1f)
            val y = mid + off * half
            // Older history fainter and thinner, the recent estimate bold.
            val t = (k - first).toFloat() / (historyCount - first)
            if (k > first) g.line(px, py, x, y, 0.004f + 0.005f * t, Palette.lerp(palette.at(0.15f), lineC, 0.4f + 0.6f * t), 0.6f + 0.4f * t)
            px = x; py = y
        }
        g.glow(px, py, 0.06f, lineC, 0.5f)
        g.disc(px, py, 0.016f, Palette.brighten(lineC, 0.3f))
    }

    /** Drawn at a fixed size; on a phone held upright the view pulls back to fit it. */
    override fun frameHalfWidth(env: SimEnv): Float = 0.89f

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = false

    override fun counters(): List<Pair<String, String>> = listOf(
        "pi" to String.format(java.util.Locale.US, "%.5f", estimate()),
        "darts" to String.format(java.util.Locale.US, "%,d", total)
    )

    companion object {
        private const val HISTORY = 600
        private const val HISTORY_STEP = 1.04
        private const val NOTE_EVERY = 512L
        /** Space between the square, the trace and the screen's edges. */
        private const val GAP = 0.08f
        /** Below this much spare height the trace is not drawn. */
        private const val MIN_TRACE = 0.3f
        /** The trace spans pi plus or minus this. */
        private const val TRACE_RANGE = 0.25f
        /** The trace's time axis runs to at least this many darts. */
        private const val TRACE_MIN_SPAN = 100_000L
        /** The trace starts at this many darts, past the first wild swings. */
        private const val TRACE_FROM = 60L
        /** The newest darts drawn large. */
        private const val FRESH = 40
        /** Where on the palette the hits take their colour. */
        private const val HIT_T = 0.4f
        /** The misses' colour: a warm coral against any cool palette. */
        private val MISS = 0xFFE0705A.toInt()
        fun def(): SimDef = SimDef(
            id = "math/monte-carlo-pi",
            title = "Finding pi with darts",
            shelf = Shelf.MATH,
            family = "Probability & statistics",
            blurb = "Random throws that settle on 3.14159.",
            formats = listOf(TrapFormat.NUMBER_UP, TrapFormat.FILL),
            heroLabel = "Throw again",
            defaultPalette = "Ocean",
            typicalSeconds = 18f,
            params = listOf(
                Param.int("dots", "Darts", 20000, 400000, 60000, card = Card.DATA, logScale = true),
                Param.float("rate", "Speed", 100f, 80000f, 5000f, card = Card.MOTION, logScale = true)
            ),
            create = { MonteCarloPiSim() }
        )
    }
}

/**
 * Fourier epicycles: a chain of spinning circles whose tip traces a shape —
 * the sum of the circles, term by term. The notes follow the tip's height.
 */
class FourierSim : Sim {

    private var terms = 24
    private var amps = FloatArray(0)
    private var freqs = IntArray(0)
    private var phases = FloatArray(0)
    private var traceX = FloatArray(0)
    private var traceY = FloatArray(0)
    private var traceCount = 0
    private var head = 0
    private var theta = 0f
    private var payoffFrame = false
    private var loops = 0

    override fun setup(env: SimEnv) {
        val p = env.params
        terms = p.i("terms", 24).coerceIn(1, 120)
        amps = FloatArray(terms)
        freqs = IntArray(terms)
        phases = FloatArray(terms)
        val shape = p.choiceIndex("shape")
        if (shape >= FIRST_PICTURE) picture(shape)
        else for (k in 0 until terms) {
            val n = 2 * k + 1
            when (shape) {
                0 -> { freqs[k] = n; amps[k] = 4f / (PI.toFloat() * n); phases[k] = 0f }          // square
                1 -> { freqs[k] = n; amps[k] = 8f / (PI.toFloat() * PI.toFloat() * n * n); phases[k] = if (k % 2 == 0) 0f else PI.toFloat() } // triangle
                2 -> { freqs[k] = k + 1; amps[k] = 2f / (PI.toFloat() * (k + 1)); phases[k] = 0f } // sawtooth
                else -> {
                    freqs[k] = k + 1
                    amps[k] = (if (k == 0) 0.8f else 0.5f / (k + 1))
                    phases[k] = env.rng.range(0f, 2f * PI.toFloat())
                }
            }
        }
        val trail = p.i("trail", 1400).coerceIn(100, 20000)
        traceX = FloatArray(trail)
        traceY = FloatArray(trail)
        traceCount = 0
        head = 0
        theta = 0f
        payoffFrame = false
        loops = 0
        // How far the traced curve reaches, sampled once round the loop, so
        // the view can pull back to hold all of it. A square wave of many
        // terms reached 1.7 either side, on a screen under 0.5 wide.
        var reach = 0f
        for (step in 0 until 720) {
            val th = step * (2f * PI.toFloat() / 720f)
            var x = 0f
            var y = 0f
            for (k in 0 until terms) {
                // The circles too, not only the path: each reaches its own
                // radius past where the arm has got to.
                reach = max(reach, max(abs(x), abs(y)) + abs(amps[k]))
                val a = freqs[k] * th + phases[k]
                x += amps[k] * cos(a)
                y += amps[k] * sin(a)
            }
            reach = max(reach, max(abs(x), abs(y)))
        }
        extent = reach * 0.55f + 0.05f
    }

    /**
     * A closed picture drawn by circles: the path sampled round its length,
     * its discrete Fourier transform taken, and the [terms] biggest circles
     * kept, biggest first — so the arm draws the shape, and with few circles
     * a soft blob of it.
     */
    private fun picture(shape: Int) {
        val n = PICTURE_SAMPLES
        val px = FloatArray(n); val py = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toFloat() / n
            val pt = pathAt(shape, t)
            px[i] = pt.first; py[i] = pt.second
        }
        val count = n
        val re = FloatArray(count); val im = FloatArray(count); val fr = IntArray(count)
        for (k in 0 until count) {
            val f = if (k <= count / 2) k else k - count
            var sr = 0.0; var si = 0.0
            for (i in 0 until n) {
                val a = -2.0 * PI * f * i / n
                val c = kotlin.math.cos(a); val sn = kotlin.math.sin(a)
                sr += px[i] * c - py[i] * sn
                si += px[i] * sn + py[i] * c
            }
            re[k] = (sr / n).toFloat(); im[k] = (si / n).toFloat(); fr[k] = f
        }
        val byAmp = (0 until count).sortedByDescending { re[it] * re[it] + im[it] * im[it] }
        for (j in 0 until terms) {
            val k = byAmp[j]
            freqs[j] = fr[k]
            amps[j] = kotlin.math.sqrt(re[k] * re[k] + im[k] * im[k])
            phases[j] = kotlin.math.atan2(im[k], re[k])
        }
    }

    /** A point round each picture, [t] from 0 to 1, about two units across. */
    private fun pathAt(shape: Int, t: Float): Pair<Float, Float> {
        val a = t * 2f * PI.toFloat()
        return when (shape) {
            FIRST_PICTURE -> {
                // The heart curve.
                val sn = sin(a)
                val x = 16f * sn * sn * sn
                val y = 13f * cos(a) - 5f * cos(2f * a) - 2f * cos(3f * a) - cos(4f * a)
                (x / 13f) to (y / 13f + 0.1f)
            }
            FIRST_PICTURE + 1 -> {
                // A five-pointed star, walked evenly along its edges.
                val e = t * 10f
                val i = e.toInt() % 10
                val f = e - kotlin.math.floor(e)
                fun corner(k: Int): Pair<Float, Float> {
                    val r = if (k % 2 == 0) 1.2f else 0.5f
                    val ang = PI.toFloat() / 2f + k * PI.toFloat() / 5f
                    return (r * cos(ang)) to (r * sin(ang))
                }
                val (x0, y0) = corner(i); val (x1, y1) = corner((i + 1) % 10)
                (x0 + (x1 - x0) * f) to (y0 + (y1 - y0) * f)
            }
            FIRST_PICTURE + 2 -> {
                val r = 0.75f + 0.4f * cos(5f * a)
                (r * cos(a)) to (r * sin(a))
            }
            else -> {
                // A figure of eight.
                val d = 1f + sin(a) * sin(a)
                (1.3f * cos(a) / d) to (1.3f * sin(a) * cos(a) / d)
            }
        }
    }

    private var extent = 1f
    override fun frameHalfWidth(env: SimEnv): Float = extent
    override fun frameHalfHeight(env: SimEnv): Float = extent

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val speed = env.params.f("speed", 0.9f)
        theta += speed * abs(dt)
        if (theta > 2f * PI.toFloat()) {
            theta -= 2f * PI.toFloat()
            loops++
            // The payoff is the second time round: the shape traced once and
            // then confirmed, closing on itself again.
            if (loops == 2) {
                payoffFrame = true
                env.emit(EventKind.DONE, 0.8f, 0f, 1f)
            }
        }
        var x = 0f
        var y = 0f
        for (k in 0 until terms) {
            val a = freqs[k] * theta + phases[k]
            x += amps[k] * cos(a)
            y += amps[k] * sin(a)
        }
        traceX[head] = x * 0.55f
        traceY[head] = y * 0.55f
        head = (head + 1) % traceX.size
        if (traceCount < traceX.size) traceCount++
        env.emit(EventKind.STEP, ((y + 1.3f) / 2.6f).coerceIn(0f, 1f), 0f, 0.3f)
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        // Strokes keep their on-screen weight when a wide shape makes the
        // view pull back; the square wave's curve was drawn half as thick.
        val ws = max(1f, extent / 0.72f)
        // On a tall phone the shape is held by the screen's width and left
        // a third of the height empty above and below. There the drawing
        // moves up and the room beneath shows the tip's x against time,
        // running down the screen: the same motion as a wave, hung from the
        // pen by a thread. (The view's half-height mirrors SimCore.fit.)
        val vh = max(1f, max(extent / env.halfWidth, extent))
        val spare = vh - extent
        val panel = spare > PANEL_MIN
        val oy = if (panel) vh - extent - 0.02f else 0f
        var x = 0f
        var y = 0f
        for (k in 0 until terms) {
            val a = freqs[k] * theta + phases[k]
            val nx = x + amps[k] * cos(a)
            val ny = y + amps[k] * sin(a)
            // Circles faint and arms a little stronger, both thin and the same
            // width at any size: the machinery, under the curve it draws.
            val c = Palette.TEXT_SECONDARY
            if (abs(amps[k]) * 0.55f > 0.004f) g.ring(x * 0.55f, oy + y * 0.55f, abs(amps[k]) * 0.55f, 0.003f * ws, c, alpha = 0.22f)
            g.line(x * 0.55f, oy + y * 0.55f, nx * 0.55f, oy + ny * 0.55f, 0.004f * ws, c, 0.55f)
            x = nx
            y = ny
        }
        // The curve as joined strokes, newest brightest. Drawn as separate
        // dots it was a dotted line wherever the pen moved fast.
        var px = 0f
        var py = 0f
        for (k in 0 until traceCount) {
            val idx = (head - 1 - k + traceX.size * 2) % traceX.size
            val age = k.toFloat() / traceCount
            if (k > 0) {
                g.line(px, py, traceX[idx], oy + traceY[idx], (0.009f - 0.004f * age) * ws,
                    palette.at(0.1f + 0.85f * (1f - age)), 0.25f + 0.75f * (1f - age))
            }
            px = traceX[idx]; py = oy + traceY[idx]
        }
        val tipX = x * 0.55f
        val tipY = oy + y * 0.55f
        if (panel) {
            val top = oy - extent + 0.02f
            val bottom = -vh + 0.06f
            val len = top - bottom
            g.line(tipX, tipY, tipX, top, 0.003f * ws, Palette.TEXT_SECONDARY, 0.3f)
            g.rect(0f, top, extent - 0.05f, 0.0015f * ws, Palette.withAlpha(Palette.TEXT_SECONDARY, 0.25f))
            g.rect(0f, (top + bottom) * 0.5f, 0.0015f * ws, len * 0.5f, Palette.withAlpha(Palette.TEXT_SECONDARY, 0.12f))
            val stride = max(1, traceCount / 1200)
            var qx = 0f
            var qy = 0f
            var k = 0
            while (k < traceCount) {
                val idx = (head - 1 - k + traceX.size * 2) % traceX.size
                val age = k.toFloat() / traceX.size
                val wy = top - age * len
                if (k > 0) g.line(qx, qy, traceX[idx], wy, (0.007f - 0.003f * age) * ws,
                    palette.at(0.1f + 0.85f * (1f - age)), 0.2f + 0.7f * (1f - age))
                qx = traceX[idx]; qy = wy
                k += stride
            }
            g.disc(tipX, top, 0.008f * ws, Palette.brighten(palette.at(0.95f), 0.3f))
        }
        g.glow(tipX, tipY, 0.045f * ws, Palette.brighten(palette.at(0.95f), 0.2f), 0.7f)
        g.disc(tipX, tipY, 0.011f * ws, Palette.brighten(palette.at(0.95f), 0.4f))
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "circles" to terms.toString(),
        "loops" to loops.toString()
    )

    companion object {
        /** The first of the shapes that are pictures rather than waves. */
        const val FIRST_PICTURE = 4
        const val PICTURE_SAMPLES = 256
        /** Spare height, above and below the shape, before the wave panel is drawn. */
        const val PANEL_MIN = 0.3f
        fun def(): SimDef = SimDef(
            id = "math/fourier",
            title = "Fourier epicycles",
            shelf = Shelf.MATH,
            family = "Waves & fields",
            blurb = "Spinning circles, each riding the last, that add up to a heart.",
            formats = listOf(TrapFormat.SONG),
            heroLabel = "New shape",
            defaultPalette = "Aurora",
            typicalSeconds = 22f,
            params = listOf(
                Param.choice("shape", "Shape", listOf("Square", "Triangle", "Sawtooth", "Random", "Heart", "Star", "Flower", "Infinity"), FIRST_PICTURE, card = Card.DATA, input = ParamInput.TILES),
                Param.int("terms", "Circles", 1, 120, 24, card = Card.DATA, logScale = true),
                Param.int("trail", "Trail", 100, 20000, 1400, card = Card.LOOK, logScale = true),
                Param.float("speed", "Speed", 0.1f, 4f, 0.9f, card = Card.MOTION)
            ),
            create = { FourierSim() }
        )
    }
}

/**
 * The Collatz conjecture: halve it if even, triple and add one if odd, and every
 * number tried so far eventually reaches one. Nobody has proved it must.
 */
class CollatzSim : Sim {

    private var start = 27
    private var sequence = LongArray(0)

    /** The longest Collatz trajectory the sim will draw. */
    private val MAX_TRAJECTORY = 2048
    private var length = 0
    private var shown = 0f
    private var payoffFrame = false
    private var peak = 0L
    private var autoAdvance = true
    private var ghost = LongArray(0)
    private var ghostLength = 0
    private var ghostLogPeak = 1f
    private var ghostAge = 99f

    override fun setup(env: SimEnv) {
        start = env.params.i("start", 27).coerceIn(2, 2_000_000)
        autoAdvance = env.params.b("auto", true)
        buildSequence(start)
        shown = 0f
        payoffFrame = false
        ghostLength = 0
        ghostAge = 99f
    }

    /**
     * A Collatz trajectory climbs far above its starting number — from 1.7
     * million it passes two billion — so it is kept in longs. Truncating it to
     * Int made negative values, and the logarithm of a negative number is NaN,
     * which reached both the draw list and the synth and stayed there.
     */
    private fun buildSequence(from: Int) {
        // Written into a buffer that is allocated once: auto-advance calls this
        // from step() every time a number finishes, and a fresh list each time
        // would be garbage made inside the loop.
        if (sequence.size < MAX_TRAJECTORY) sequence = LongArray(MAX_TRAJECTORY)
        var n = from.toLong()
        var at = 0
        peak = from.toLong()
        while (n != 1L && at < MAX_TRAJECTORY - 1) {
            sequence[at++] = n
            n = if (n % 2L == 0L) n / 2 else 3 * n + 1
            if (n > peak) peak = n
        }
        sequence[at++] = 1L
        length = at
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val before = shown.toInt()
        shown += env.params.f("rate", 11f) * abs(dt)
        val after = min(length, shown.toInt())
        for (i in before until after) {
            val v = sequence[i]
            env.emit(
                EventKind.WRITE,
                (ln(1f + v.toFloat()) / logPeak()).coerceIn(0f, 1f),
                (i.toFloat() / length) * 2f - 1f,
                0.55f
            )
        }
        if (after >= length && before < length) {
            payoffFrame = true
            env.emit(EventKind.DONE, 1f, 0f, 1f)
        }
        if (autoAdvance && shown > length + 12) {
            // The finished run stays behind, fading, while the next one grows
            // over it; it used to vanish in one frame, leaving three thin bars
            // on an empty screen.
            if (ghost.size < MAX_TRAJECTORY) ghost = LongArray(MAX_TRAJECTORY)
            System.arraycopy(sequence, 0, ghost, 0, length)
            ghostLength = length
            ghostLogPeak = logPeak()
            ghostAge = 0f
            start += 1
            buildSequence(start)
            shown = 0f
        }
        ghostAge += abs(dt)
    }

    /** Never zero, so nothing divides by it. */
    private fun logPeak(): Float = max(1e-3f, ln(1f + peak.toFloat()))

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val halfW = env.halfWidth * 0.92f
        val fade = (1f - ghostAge / GHOST_SECONDS).coerceIn(0f, 1f)
        if (fade > 0f && ghostLength > 1) {
            val gx = 2f * halfW / (ghostLength - 1)
            for (i in 0 until ghostLength) {
                val t = ln(1f + ghost[i].toFloat()) / ghostLogPeak
                g.bar(-halfW + i * gx, BASE, max(gx * 0.32f, 0.0015f), t * TALL, palette.at(t * 0.85f), 0.28f * fade)
            }
        }
        val n = min(length, max(1, shown.toInt()))
        val stepX = 2f * halfW / max(1, length - 1)
        val logPeak = logPeak()
        val halfBar = max(stepX * 0.32f, 0.0015f)
        val cap = min(0.012f, max(0.004f, stepX * 0.5f))
        for (i in 0 until n) {
            val v = sequence[i]
            val t = ln(1f + v.toFloat()) / logPeak
            val x = -halfW + i * stepX
            val h = t * TALL
            val reached = i == length - 1
            // The palette's first 85%: the far end is near-black on Ember,
            // which put the darkest colour on the tallest bars.
            val c = if (reached) Palette.SUCCESS else palette.at(t * 0.85f)
            g.bar(x, BASE, halfBar, h, c, 0.75f)
            // A bright cap on each bar, so the climb and fall read as one line.
            g.rect(x, BASE + h, max(halfBar, cap * 0.5f), cap * 0.5f, Palette.brighten(c, 0.35f))
            if (v == peak && !reached) g.glow(x, BASE + h, 0.05f, Palette.brighten(c, 0.3f), 0.6f)
        }
        if (n >= length) {
            // One, reached: a small ring on the last bar, not a blob over the bars.
            val x = -halfW + (length - 1) * stepX
            val h = ln(2f) / logPeak * TALL
            g.glow(x, BASE + h, 0.045f, Palette.SUCCESS, 0.7f)
            g.ring(x, BASE + h, 0.022f, 0.005f, Palette.SUCCESS)
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "start" to start.toString(),
        // Steps, not terms: 27 takes 111 steps to reach 1, and the run has 112 numbers.
        "steps" to max(0, length - 1).toString(),
        "peak" to String.format(java.util.Locale.US, "%,d", peak)
    )

    companion object {
        private const val BASE = -0.82f
        private const val TALL = 1.6f
        private const val GHOST_SECONDS = 1.6f
        fun def(): SimDef = SimDef(
            id = "math/collatz",
            title = "Collatz: always back to one",
            shelf = Shelf.MATH,
            family = "Number theory",
            blurb = "Halve or triple-plus-one. It always ends at one; nobody knows why.",
            formats = listOf(TrapFormat.NUMBER_UP, TrapFormat.SONG, TrapFormat.SNAP),
            heroLabel = "Next number",
            defaultPalette = "Ember",
            typicalSeconds = 14f,
            params = listOf(
                Param.int("start", "Starting number", 2, 2_000_000, 27, card = Card.DATA, logScale = true),
                Param.bool("auto", "Walk through numbers", true, card = Card.DATA),
                Param.float("rate", "Speed", 1f, 120f, 11f, card = Card.MOTION, unit = "/s", logScale = true)
            ),
            create = { CollatzSim() }
        )
    }
}

object MathSims {
    fun defs(): List<SimDef> = listOf(
        MandelbrotSim.def(),
        ChaosGameSim.def(),
        PrimeSpiralSim.def(),
        MonteCarloPiSim.def(),
        FourierSim.def(),
        CollatzSim.def()
    )
}
