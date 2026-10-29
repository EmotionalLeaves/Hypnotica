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
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The Mandelbrot set, zooming forever.
 *
 * Escape times are computed across every core and drawn through the point path,
 * so a quarter of a million samples a frame stay inside the budget.
 *
 * Why it can go on for ever: a double runs out of digits near a zoom of 1e13,
 * where the picture went to blocks and the dive stalled. Past [PERTURB_ZOOM]
 * every pixel is iterated as a small difference δ from one reference orbit
 * (perturbation: δ' = 2Zδ + δ² + δc, in doubles), and only that one orbit is
 * worked out in big fixed-point numbers, with as many bits as the zoom needs.
 * The dive is aimed at a Misiurewicz point — a point of the boundary whose
 * orbit lands on a repelling cycle — found by Newton's method. The set is
 * self-similar about such a point at every scale, so the frame never runs out
 * of detail (aimed anywhere else, a long dive ends inside a mini-set: a black
 * screen). Its orbit is exactly periodic after k steps, so the reference is a
 * short array repeated, as long as any pixel needs it, for free.
 *
 * At [HANDOFF_ZOOM] (doubles themselves end near 1e308) the dive dissolves
 * into a fresh one at another Misiurewicz point, so it is endless to watch.
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
    /**
     * The escape count the colours are measured from. Deep in, every count on
     * screen is in the thousands and ln(1 + count) spans a sliver: one flat
     * colour. Counted from [COLOUR_HEADROOM] under the lowest count in view, a
     * deep frame is coloured like a shallow one (dark far field, bright edge).
     * Shallow, the lowest count is under that and this is 0: the look there is
     * unchanged.
     */
    private var muBase = 0f
    private var maxIter = 220
    /** The iterations the picture on screen was computed with. */
    private var iterNow = 220
    private var zoom = 0.6
    private var targetZoom = 0.6
    private var elapsed = 0f
    private var payoffFrame = false
    private var zoomRate = 0.22f
    private var lastSoundIter = 0
    private var sinceRecompute = 999f
    /** The zoom the current image was computed at. */
    private var computedZoom = 0.6
    /** Doublings of magnification so far, each one a note. */
    private var doublings = 0
    /** Log-magnification of the dives before this one, so the notes keep counting across a hand-off. */
    private var logBase = 0.0
    /** The lowest escape count in the last image, which deep in sets the iteration budget. */
    private var lastLowCount = 0f

    // ---- Where the view is -------------------------------------------------

    /** The current reference: the Misiurewicz point being dived into, and its orbit. */
    private var ref: Ref? = null
    /** The view centre minus the reference point, in doubles (it is never more than a few views wide). */
    private var offX = 0.0
    private var offY = 0.0
    /** The centre the image on screen was computed at, the same way. */
    private var imgOffX = 0.0
    private var imgOffY = 0.0
    private var imgScale = 1.4 / 0.6
    /** Big-number work in progress: a sharper copy of the target, or the target a tap found. */
    private var job: RefJob? = null
    /** The job is a new target (found by a tap), not a refinement of the current one. */
    private var jobRetargets = false
    private var gliding = false
    private var glideT = 0f
    private var glideFromX = 0.0
    private var glideFromY = 0.0
    private var glideToX = 0.0
    private var glideToY = 0.0
    private var seek: Seek? = null
    /** Index into [CATALOGUE] of the dive in progress, for picking a different one next. */
    private var diveIndex = 0
    /** Images in a row that were nearly all one value (see finishImage). */
    private var flatImages = 0
    private var handoffWanted = false
    /** Zoom held still because the reference is not yet precise enough (a safety net; should not happen). */
    private var stalled = false

    // ---- The dissolve between dives ---------------------------------------

    /** The old dive is still on screen while the new one's first image is made. */
    private var handoffWait = false
    /** Images are being cross-faded with [oldGrid]. */
    private var fading = false
    private var fadeT = 0f
    private var oldGrid = IntArray(0)
    private var oldCs = 0
    private var oldRs = 0
    private var oldZoomNow = 1.0
    private var oldComputedZoom = 1.0
    private var oldPanX = 0f
    private var oldPanY = 0f
    /** The colours of the image on screen, one per coarse cell (0 = inside), for the dissolve. */
    private var shownGrid = IntArray(0)

    override fun setup(env: SimEnv) {
        val p = env.params
        val quality = p.i("resolution", 240).coerceIn(40, 460)
        refCount = 0
        refined = IntArray(0)
        phase = IDLE
        hasImage = false
        dN = 0
        bN = 0
        // The coarse grid; boundary cells are refined 3× (see refine), so the
        // edge is drawn at twice the old 240-row resolution for about the same
        // work, the smooth outside costing less than before.
        rows = max(30, quality * 2 / 3)
        cols = max(8, (quality * env.aspect).toInt())
        iterations = FloatArray(cols * rows)
        edgeDist = FloatArray(cols * rows)
        lowMu = -1f
        highMu = 1f
        muBase = 0f
        maxIter = p.i("detail", 220).coerceIn(40, 1200)
        iterNow = maxIter
        zoomRate = p.f("zoom", 0.22f)
        doublings = 0
        logBase = 0.0
        lastLowCount = 0f
        diveIndex = p.choiceIndex("target").coerceIn(0, 3)
        startDive(diveIndex, 0.6)
        elapsed = 0f
        payoffFrame = false
        lastSoundIter = 0
        pendingNote = -1f
        sinceRecompute = 999f
        reached = false
        flatImages = 0
        handoffWanted = false
        handoffWait = false
        fading = false
        hasImage = false
    }

    /** Aim at catalogue entry [i] from zoom [z0]: its point, sharpened enough for the start of the dive. */
    private fun startDive(i: Int, z0: Double) {
        val t = CATALOGUE[i]
        val bits = bitsFor(z0 * 1e9)
        val j = RefJob(Fix.parse(t.re, bits), Fix.parse(t.im, bits), bits, t.k, t.p, null)
        // A few hundred bits and k + p of a few dozen: well under a millisecond,
        // so the first reference is made at once rather than over steps.
        while (!j.done && !j.failed) j.work(1 shl 20)
        ref = if (j.done) j.result() else Ref.fromDoubles(t)
        job = null
        jobRetargets = false
        seek = null
        gliding = false
        offX = 0.0; offY = 0.0
        imgOffX = 0.0; imgOffY = 0.0
        zoom = z0
        targetZoom = z0
        computedZoom = z0
        imgScale = 1.4 / z0
        stalled = false
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        val r = ref!!
        // The reference must carry more bits than the zoom resolves, or the
        // picture dissolves into noise; it is sharpened well ahead (below), so
        // this hold is only a net under a job that has fallen behind.
        stalled = zoom >= PERTURB_ZOOM && r.bits < bitsNeeded(targetZoom * exp((zoomRate * 0.5f).toDouble()))
        if (!stalled) targetZoom *= exp((zoomRate * dt).toDouble())
        zoom += (targetZoom - zoom) * min(1.0, (dt * 6f).toDouble())

        if (gliding) {
            glideT = min(1f, glideT + abs(dt) / GLIDE_SECONDS)
            val s = glideT * glideT * (3f - 2f * glideT)
            offX = glideFromX + (glideToX - glideFromX) * s
            offY = glideFromY + (glideToY - glideFromY) * s
            if (glideT >= 1f) gliding = false
        }

        // A note each time the magnification grows by half again: the dive's
        // own beat. The only other sound follows the iteration count at the
        // centre, which deep in a zoom can hold steady for twenty seconds. A
        // note per doubling was one every three seconds at the shipped speed.
        // Counted across dives, so a hand-off neither repeats nor skips notes.
        val nowDoublings = ((logBase + ln(zoom / 0.6)) / ln(1.5)).toInt()
        if (nowDoublings > doublings) {
            doublings = nowDoublings
            env.emit(EventKind.STEP, ((doublings % 12) / 12f), 0f, 0.3f)
        }

        if (handoffWait || fading) {
            oldZoomNow *= exp((zoomRate * dt).toDouble())
            if (fading) {
                fadeT += abs(dt) / FADE_SECONDS
                if (fadeT >= 1f) { fading = false; fadeT = 1f }
            }
        }

        runJobs(dt)

        if (!handoffWait && !fading && (handoffWanted || zoom >= HANDOFF_ZOOM)) {
            beginHandoff(env)
        }

        sinceRecompute += abs(dt)
        if (phase == IDLE) {
            if (sinceRecompute < 1f / REFRESH_HZ) return
            sinceRecompute = 0f
            beginImage(env)
            // Nothing on screen yet (a fresh start or a new resolution): the
            // first image is made whole, at once, rather than a blank screen
            // for the dozen steps the slices take.
            if (!hasImage) { while (phase != IDLE) work(env, true); return }
        }
        work(env, false)
    }

    /** Bits the reference needs at zoom [z]: what the pixels resolve, plus a margin. */
    private fun bitsNeeded(z: Double): Int = (ln(max(1.0, z)) / LN2).toInt() + 64

    /** Bits to sharpen to, from zoom [z]: well past need, so a job is rare. */
    private fun bitsFor(z: Double): Int = (bitsNeeded(z) + 192 + 31) / 32 * 32

    /** The big-number job and the tap search, a bounded share each step. */
    private fun runJobs(dt: Float) {
        val r = ref!!
        val s = seek
        if (s != null) {
            s.work(SEEK_ITERS_PER_STEP)
            if (s.finished) {
                seek = null
                val found = s.best
                if (DEBUG) println("mandel seek done: " + (found?.let { "k=${it.k} p=${it.p} dist=${it.dist}" } ?: "nothing"))
                if (found == null) {
                    // Nothing repelling within reach of the tap: the dive goes
                    // on elsewhere rather than toward a point that runs dry.
                    handoffWanted = true
                } else {
                    glideFromX = offX; glideFromY = offY
                    glideToX = found.dx; glideToY = found.dy
                    glideT = 0f
                    gliding = true
                    val bits = max(r.bits, bitsFor(targetZoom))
                    val cx = r.cx.shiftLeft(bits - r.bits).add(Fix.fromDouble(found.dx, bits))
                    val cy = r.cy.shiftLeft(bits - r.bits).add(Fix.fromDouble(found.dy, bits))
                    job = RefJob(cx, cy, bits, found.k, found.p, Fix.fromDouble(s.radius * 1e-6, bits))
                    jobRetargets = true
                }
            }
            return
        }
        var j = job
        if (j == null && zoom >= PERTURB_ZOOM * 1e-3 && r.bits < bitsNeeded(targetZoom) + 96) {
            val bits = bitsFor(targetZoom)
            j = RefJob(r.cx.shiftLeft(bits - r.bits), r.cy.shiftLeft(bits - r.bits), bits, r.k, r.p,
                BigInteger.ONE.shiftLeft(bits - r.bits + 48))
            job = j
            jobRetargets = false
        }
        if (j == null) return
        // Work counted in iterations, never in clock time, so a run replays the
        // same everywhere. Each iteration is ~7 multiplies of (bits/32)-word
        // numbers; this is about a millisecond a step on a phone at any depth.
        val words = j.bits / 32
        j.work(max(4, BIG_WORK_PER_STEP / (words * words)))
        if (j.failed) {
            if (DEBUG) println("mandel job failed retarget=$jobRetargets bits=${j.bits}")
            job = null
            // A refinement that wanders off its point, or a tapped target that
            // will not sharpen: move on to a dive that is known to be sound.
            handoffWanted = true
        } else if (j.done) {
            job = null
            val nr = j.result()
            if (jobRetargets) {
                // A tap's point, now exact: offsets are re-measured from it,
                // and the glide ends on it exactly. Ending on the double the
                // search found would leave the centre ~1e-16 of a view off the
                // point for good, and a dive 1e16 deeper would run dry there.
                val ddx = Fix.toDouble(r.cx.shiftLeft(nr.bits - r.bits).subtract(nr.cx), nr.bits)
                val ddy = Fix.toDouble(r.cy.shiftLeft(nr.bits - r.bits).subtract(nr.cy), nr.bits)
                offX += ddx; offY += ddy
                imgOffX += ddx; imgOffY += ddy
                glideFromX += ddx; glideFromY += ddy
                glideToX = 0.0; glideToY = 0.0
                if (!gliding) { offX = 0.0; offY = 0.0 }
                // An image half made is measured from the old point: it is told
                // where that is from the new one when it goes on screen.
                jAdjX += ddx; jAdjY += ddy
            }
            // A sharper copy of the same point moves it by less than the old
            // copy's last bit: the centre follows it, so it stays on the point.
            // (Keeping the view where it was kept the old copy's error, and at
            // 1e89 the dive ran off the boundary into a flat field.)
            ref = nr
        }
    }

    // ---- Hand-off ----------------------------------------------------------

    private fun beginHandoff(env: SimEnv) {
        handoffWanted = false
        flatImages = 0
        if (hasImage && shownGrid.size == cols * rows) {
            if (oldGrid.size != shownGrid.size) oldGrid = IntArray(shownGrid.size)
            System.arraycopy(shownGrid, 0, oldGrid, 0, shownGrid.size)
            oldCs = cols
            oldRs = rows
            oldZoomNow = zoom
            oldComputedZoom = computedZoom
            oldPanX = ((offX - imgOffX) / imgScale).toFloat()
            oldPanY = ((offY - imgOffY) / imgScale).toFloat()
            handoffWait = true
        }
        // A different entry each time, picked by the run's own generator so a
        // seed replays the same journey.
        var next = env.rng.int(0, CATALOGUE.size - 1)
        if (next == diveIndex) next = (next + 1) % CATALOGUE.size
        val prevLog = logBase + ln(zoom / 0.6)
        diveIndex = next
        // Not from the whole set: a few hundred to a few thousand times in, so
        // the new dive already looks like the deep one it replaces.
        val z0 = 0.6 * Math.pow(10.0, 2.3 + 1.2 * env.rng.nextFloat())
        startDive(next, z0)
        logBase = prevLog - ln(z0 / 0.6)
        lastLowCount = 0f
        // The counts of the new place are unrelated: the colours snap to it.
        lowMu = -1f
        phase = IDLE
        sinceRecompute = 999f
    }

    /*
     * The image is made in slices across steps, into back buffers, and swapped
     * in whole when done. Made in one step it cost up to 40 ms eight times a
     * second, on a desktop: a dropped frame on every refresh on a phone. The
     * slices finish inside one refresh period, and the zoom between images is
     * still continuous because the shown image is scaled by zoom / its own
     * zoom (see draw), so the extra eighth of a second of latency never shows.
     */
    private var phase = IDLE
    private var hasImage = false
    private var cursor = 0
    private var jCx = 0.0
    private var jCy = 0.0
    private var jScale = 1.0
    private var jHalfW = 1f
    private var jMi = 220
    private var jCell = 0.01
    private var jZoom = 0.6
    private var jOffX = 0.0
    private var jOffY = 0.0
    /** How far the reference moved while the image was being made (see runJobs). */
    private var jAdjX = 0.0
    private var jAdjY = 0.0
    private var jRefX = 0.0
    private var jRefY = 0.0
    /** The image is a dissolve frame: every cell drawn, no merged runs (see buildRows). */
    private var jFade = false
    /** Perturbation, not plain doubles. */
    private var jPert = false
    private var jZr = DoubleArray(0)
    private var jZi = DoubleArray(0)
    /** Iterations every pixel skips, by the series below. */
    private var jSkip = 0
    private var jInvR = 1.0
    private var jCellOverR = 1.0
    private var jAx = 0.0; private var jAy = 0.0
    private var jBx = 0.0; private var jBy = 0.0
    private var jC3x = 0.0; private var jC3y = 0.0

    private fun beginImage(env: SimEnv) {
        val r = ref!!
        jZoom = zoom
        jScale = 1.4 / zoom
        jHalfW = env.halfWidth
        jOffX = offX
        jOffY = offY
        jAdjX = 0.0
        jAdjY = 0.0
        jRefX = r.cxD
        jRefY = r.cyD
        // Deeper in, points take longer to escape: the detail grows with the
        // zoom, or every deep view flattened into one blob. Shallow, as it
        // always was; deep, a budget above the lowest count in view, which
        // about a Misiurewicz point climbs steadily with the depth.
        val depth = ln(max(1.0, zoom / 0.6)) / LN2
        val shallow = (maxIter * (1.0 + 0.12 * depth)).toInt().coerceAtMost(SHALLOW_ITER_CAP)
        jPert = zoom >= PERTURB_ZOOM
        jMi = if (!jPert) shallow else max(shallow, (lastLowCount + maxIter * (2.0 + depth / 40.0)).toInt())
        if (jMi > MAX_ITER_CAP) { jMi = MAX_ITER_CAP; handoffWanted = true }
        jCx = r.cxD + offX
        jCy = r.cyD + offY
        jCell = 2.0 * jScale / (rows - 1)
        jFade = handoffWait || fading
        if (jPert) prepareReference(r)
        if (rawDist.size != cols * rows) rawDist = FloatArray(cols * rows)
        phase = COARSE
        cursor = 0
    }

    /**
     * The repeated reference orbit, and a series that stands in for the first
     * stretch of every pixel's iteration. Near the reference the δ of every
     * pixel follows δ_n ≈ A_n δc + B_n δc² + C_n δc³ for a long while (the
     * orbits have not yet parted); the coefficients are iterated once, and
     * each pixel starts where the series stops being exact. Deep in a dive
     * that is nearly all of the iterations.
     */
    private fun prepareReference(r: Ref) {
        r.ensure(jMi + 8)
        jZr = r.zr
        jZi = r.zi
        // hypot, not sqrt(x² + y²): past 1e154 the squares underflow to 0.
        val rad = 1.05 * Math.hypot(abs(jOffX) + jScale * jHalfW, abs(jOffY) + jScale)
        jInvR = 1.0 / rad
        // Scaled by the radius (a = A·R, b = B·R², …): unscaled, B and C
        // overflow long before 1e300, where A·δc is still an ordinary number.
        var ax = 0.0; var ay = 0.0; var bx = 0.0; var by = 0.0
        var cx = 0.0; var cy = 0.0; var dx = 0.0; var dy = 0.0
        var n = 0
        jSkip = 0
        jAx = 0.0; jAy = 0.0; jBx = 0.0; jBy = 0.0; jC3x = 0.0; jC3y = 0.0
        val limit = jMi - 2
        val zr = jZr
        val zi = jZi
        while (n < limit) {
            val tx = 2.0 * zr[n]
            val ty = 2.0 * zi[n]
            val nax = tx * ax - ty * ay + rad
            val nay = tx * ay + ty * ax
            val nbx = tx * bx - ty * by + (ax * ax - ay * ay)
            val nby = tx * by + ty * bx + 2.0 * ax * ay
            val ncx = tx * cx - ty * cy + 2.0 * (ax * bx - ay * by)
            val ncy = tx * cy + ty * cx + 2.0 * (ax * by + ay * bx)
            val ndx = tx * dx - ty * dy + 2.0 * (ax * cx - ay * cy) + (bx * bx - by * by)
            val ndy = tx * dy + ty * dx + 2.0 * (ax * cy + ay * cx) + 2.0 * bx * by
            ax = nax; ay = nay; bx = nbx; by = nby; cx = ncx; cy = ncy; dx = ndx; dy = ndy
            n++
            // The first term left out, |d|·|u|⁴, against the answer, ~|a|·|u|,
            // for the farthest pixel (|u| = 1): a part in a million is far
            // below a pixel. The orbit's own size bounds it too, or a pixel
            // could start already past the escape radius. Compared as |x|+|y|,
            // not squared: past 1e154, |a|² underflows to 0, 0 ≤ 0 held for
            // ever and every pixel "skipped" to the budget — a black frame.
            val an = abs(ax) + abs(ay)
            val dn = abs(dx) + abs(dy)
            if (!(dn <= SA_TOL * an) || an > 1e-2) break
            jSkip = n
            jAx = ax; jAy = ay; jBx = bx; jBy = by; jC3x = cx; jC3y = cy
        }
        jCellOverR = jCell * jInvR
    }

    /** One slice of the image in progress, or all of it when [all]. */
    private fun work(env: SimEnv, all: Boolean) {
        val cs = cols
        val rs = rows
        when (phase) {
            COARSE -> {
                // Strided like the refinement (slice j takes rows j, j + 4…), so
                // a slice is never all slow rows across a minibrot's edge.
                val perR = (rs + COARSE_SLICES - 1) / COARSE_SLICES
                val total = perR * COARSE_SLICES
                val to = if (all) total else min(total, cursor + perR)
                val from = cursor
                val cx = jCx
                val cy = jCy
                val ox = jOffX
                val oy = jOffY
                val scale = jScale
                val halfW = jHalfW
                val mi = jMi
                val cell = jCell
                val pert = jPert
                heavyRange(to - from) { a, b ->
                    for (p in from + a until from + b) {
                        val row = (p % perR) * COARSE_SLICES + p / perR
                        if (row >= rs) continue
                        val fy = (row.toDouble() / (rs - 1) * 2.0 - 1.0) * scale
                        val base = row * cs
                        if (pert) {
                            var col = 0
                            while (col + 1 < cs) {
                                val fa = (col.toDouble() / (cs - 1) * 2.0 - 1.0) * scale * halfW
                                val fb = ((col + 1).toDouble() / (cs - 1) * 2.0 - 1.0) * scale * halfW
                                escapePairP(ox + fa, oy + fy, base + col, ox + fb, oy + fy, base + col + 1, mi, cell, iterations, edgeDist)
                                col += 2
                            }
                            if (col < cs) {
                                val fx = (col.toDouble() / (cs - 1) * 2.0 - 1.0) * scale * halfW
                                escapeP(ox + fx, oy + fy, mi, cell, iterations, edgeDist, base + col)
                            }
                            continue
                        }
                        val y0 = cy + fy
                        // Neighbouring columns in pairs (see escapePair).
                        var col = 0
                        while (col + 1 < cs) {
                            val xa = cx + (col.toDouble() / (cs - 1) * 2.0 - 1.0) * scale * halfW
                            val xb = cx + ((col + 1).toDouble() / (cs - 1) * 2.0 - 1.0) * scale * halfW
                            escapePair(xa, y0, base + col, xb, y0, base + col + 1, mi, cell, iterations, edgeDist, true)
                            col += 2
                        }
                        if (col < cs) {
                            val x0 = cx + (col.toDouble() / (cs - 1) * 2.0 - 1.0) * scale * halfW
                            escape(x0, y0, mi, cell, iterations, edgeDist, base + col, true)
                        }
                    }
                }
                cursor = to
                if (cursor >= total) phase = POST
            }
            POST -> {
                // Raw distances, before healSpecks/edgeGlow reuse edgeDist, pick the
                // cells that are refined below.
                System.arraycopy(edgeDist, 0, rawDist, 0, cs * rs)
                if (healedMask.size == cs * rs) java.util.Arrays.fill(healedMask, false)
                healSpecks(cs, rs)
                healSpecks(cs, rs)
                setColourBase()
                edgeGlow(cs, rs)
                selectRefine(cs, rs)
                phase = REFINE
                cursor = 0
            }
            REFINE -> {
                // Slice j takes refined cells j, j + 7, j + 14…: an even spread
                // over the picture, so each step gets its share of the slow
                // boundary instead of one step drawing a whole minibrot's edge.
                val per = (refCount + REFINE_SLICES - 1) / REFINE_SLICES
                val total = per * REFINE_SLICES * 9
                val to = if (all) total else min(total, cursor + per * 9)
                refineSlice(cs, cursor, to)
                cursor = to
                if (cursor >= total) {
                    healSubs()
                    finishImage(env)
                    phase = BUILD
                    cursor = 0
                }
            }
            BUILD -> {
                val to = if (all) rs else min(rs, cursor + (rs + BUILD_SLICES - 1) / BUILD_SLICES)
                buildRows(env.palette, cursor, to)
                cursor = to
                if (cursor >= rs) {
                    // The finished image goes on screen whole.
                    val tx = dX; dX = bX; bX = tx
                    val ty = dY; dY = bY; bY = ty
                    val tw = dW; dW = bW; bW = tw
                    val th = dH; dH = bH; bH = th
                    val tc = dC; dC = bC; bC = tc
                    dN = bN
                    computedZoom = jZoom
                    imgScale = jScale
                    imgOffX = jOffX + jAdjX
                    imgOffY = jOffY + jAdjY
                    iterNow = jMi
                    hasImage = true
                    phase = IDLE
                    recordShown()
                    // The new dive's first picture is in: the dissolve begins.
                    if (handoffWait) { handoffWait = false; fading = true; fadeT = 0f }
                    if (pendingNote >= 0f) {
                        env.emit(EventKind.STEP, pendingNote, 0f, 0.4f)
                        pendingNote = -1f
                    }
                    // Deep enough that the detail on screen is a thousand times finer than
                    // where it started. That is the zoom arriving, which is what the sim
                    // promises, rather than eighteen seconds having gone by.
                    if (!reached && jZoom >= 0.6 * TARGET_DEPTH) {
                        reached = true
                        payoffFrame = true
                    }
                }
            }
        }
    }

    /** The coarse colours of the image just swapped in, kept for a later dissolve. */
    private fun recordShown() {
        val n = cols * rows
        if (shownGrid.size != n) shownGrid = IntArray(n)
        val hasSubs = refined.size == n
        for (i in 0 until n) {
            val ref = if (hasSubs) refined[i] else -1
            shownGrid[i] = if (ref < 0) cellColour[i] else subColour[ref * 9 + 4]
        }
    }

    /**
     * Parallel.forRange over [n] items that each cost hundreds of iterations.
     * A slice is a few thousand samples, under the pool's 20 000-item floor
     * (sized for particles that cost nanoseconds each), so it ran on one core;
     * the count is scaled up past the floor and the slices mapped back.
     */
    private inline fun heavyRange(n: Int, crossinline body: (from: Int, to: Int) -> Unit) {
        if (n <= 0) return
        val m = (Parallel.MIN_ITEMS + n - 1) / n
        if (m <= 1) { Parallel.forRange(n) { a, b -> body(a, b) }; return }
        Parallel.forRange(n * m) { a, b ->
            val from = (a + m - 1) / m
            val to = (b + m - 1) / m
            if (to > from) body(from, to)
        }
    }

    /** Where the colours are counted from (see [muBase]), from the coarse counts. */
    private fun setColourBase() {
        var lo = Float.MAX_VALUE
        for (v in iterations) if (v >= 0f && v < lo) lo = v
        if (lo == Float.MAX_VALUE) return
        val want = max(0f, lo - COLOUR_HEADROOM)
        // Eased like the brightness range, so the colours drift with the dive
        // rather than stepping each image; snapped on a new dive.
        muBase = if (lowMu < 0f) want else muBase + (want - muBase) * 0.35f
    }

    /** The brightness range and the sound, once every sample of an image is in. */
    private fun finishImage(env: SimEnv) {
        val cs = cols
        val rs = rows
        val mi = jMi
        // The range of escape counts in view sets how bright the outside is:
        // far from the set dim, close to it bright, so the boundary glows
        // rather than sitting in a flat field of colour. Eased between images
        // so the brightness does not flicker as the zoom deepens.
        var lo = Float.MAX_VALUE
        var hi = 0f
        var inside = 0
        for (v in iterations) if (v >= 0f) { if (v < lo) lo = v; if (v > hi) hi = v } else inside++
        if (lo != Float.MAX_VALUE) {
            lastLowCount = lo
            val lLo = ln(1f + max(0f, lo - muBase))
            val lHi = max(lLo + 0.5f, ln(1f + max(0f, hi - muBase)))
            if (lowMu < 0f) { lowMu = lLo; highMu = lHi } else {
                lowMu += (lLo - lowMu) * 0.35f
                highMu += (lHi - highMu) * 0.35f
            }
        }
        // A frame that is nearly all inside, or has next to no boundary in it,
        // is a dive that has run dry (the black screen). It cannot happen about
        // a Misiurewicz point, but a tap or a bug must not leave it on screen:
        // after a few such images the dive hands off to a fresh one.
        val n = cs * rs
        val flat = inside > n * 0.9f || refCount < n / 400
        flatImages = if (flat && hasImage && !handoffWait && !fading) flatImages + 1 else 0
        if (flatImages >= 3) handoffWanted = true
        if (DEBUG) println(String.format(java.util.Locale.US,
            "mandel t=%.1f zoom=%.3e pert=%b mi=%d skip=%d bits=%d k=%d p=%d lo=%.0f hi=%.0f inside=%d ref=%d dive=%d",
            elapsed, jZoom, jPert, mi, jSkip, ref!!.bits, ref!!.k, ref!!.p, lo, hi, inside, refCount, diveIndex))
        // The sound follows how deep the picture has become.
        val centreIter = iterations[(rs / 2) * cs + cs / 2].let { if (it < 0f) mi else it.toInt() }
        // Held until the image is swapped in (end of BUILD): sounded here it
        // came the BUILD slices' worth of steps before the picture it
        // describes was on screen. Measured from the colour base, so deep in
        // the note still moves with the picture.
        val rel = (centreIter - muBase.toInt()).coerceAtLeast(0)
        val span = max(1, mi - muBase.toInt())
        if (abs(rel - lastSoundIter) > span / 40) {
            lastSoundIter = rel
            pendingNote = (rel.toFloat() / span).coerceIn(0f, 1f)
        }
    }

    /** The centre note of the image being built, or -1; sounded as it goes on screen. */
    private var pendingNote = -1f

    /**
     * One escape-time sample: the smooth count into [mu] (or -1 inside the
     * set) and the distance estimate, in units of [cell], into [de].
     */
    private fun escape(x0: Double, y0: Double, mi: Int, cell: Double, mu: FloatArray, de: FloatArray, idx: Int, periodic: Boolean) {
        // Points in the main cardioid or the period-2 bulb never escape: tested
        // in closed form instead of iterated to the budget. Interior is most of
        // the screen for much of the dive and was most of the cost.
        if (inCardioidOrBulb(x0, y0)) {
            mu[idx] = -1f
            de[idx] = 0f
            return
        }
        var x = 0.0
        var y = 0.0
        // dz/dc alongside z, for the distance estimate only.
        var dx = 0.0
        var dy = 0.0
        var i = 0
        // Periodicity (Brent): an orbit that comes back to within 1e-13 of an
        // earlier point has fallen onto an attracting cycle and never escapes,
        // so it is inside without running out the budget. Anything still
        // moving that slowly near a parabolic point moves ~1/n^2 per return,
        // orders of magnitude above this, so no escaping point is misread.
        // The check costs a quarter of the loop, so the boundary sub-samples,
        // whose interior ones are all slow to settle and almost never caught,
        // run without it: same answers, less work.
        var px = 0.0
        var py = 0.0
        var check = 8
        var inside = false
        if (periodic) while (x * x + y * y <= 4.0 && i < mi) {
            val dxt = 2.0 * (x * dx - y * dy) + 1.0
            dy = 2.0 * (x * dy + y * dx)
            dx = dxt
            val xt = x * x - y * y + x0
            y = 2.0 * x * y + y0
            x = xt
            i++
            if (abs(x - px) < PERIOD_EPS && abs(y - py) < PERIOD_EPS) { inside = true; break }
            if (i == check) { px = x; py = y; check += check }
        } else while (x * x + y * y <= 4.0 && i < mi) {
            val dxt = 2.0 * (x * dx - y * dy) + 1.0
            dy = 2.0 * (x * dy + y * dx)
            dx = dxt
            val xt = x * x - y * y + x0
            y = 2.0 * x * y + y0
            x = xt
            i++
        }
        if (inside || i >= mi) {
            mu[idx] = -1f
            de[idx] = 0f
            return
        }
        settle(x, y, dx, dy, i, x0, y0, 1.0, cell, mu, de, idx)
    }

    /**
     * One sample by perturbation, at [dcx], [dcy] from the reference point.
     * The derivative is carried pre-multiplied by the cell size (dz/dc · cell):
     * plain, it reaches 1e300 and overflows; this way it stays an ordinary
     * number and the distance comes out in cells directly.
     */
    private fun escapeP(dcx: Double, dcy: Double, mi: Int, cell: Double, mu: FloatArray, de: FloatArray, idx: Int) {
        val zr = jZr
        val zi = jZi
        val ux = dcx * jInvR
        val uy = dcy * jInvR
        // δ at the skip, from the series: ((c·u + b)·u + a)·u.
        val t1x = jC3x * ux - jC3y * uy + jBx
        val t1y = jC3x * uy + jC3y * ux + jBy
        val t2x = t1x * ux - t1y * uy + jAx
        val t2y = t1x * uy + t1y * ux + jAy
        var dx = t2x * ux - t2y * uy
        var dy = t2x * uy + t2y * ux
        // And its derivative, (a + 2bu + 3cu²) · cell / R.
        val u2x = ux * ux - uy * uy
        val u2y = 2.0 * ux * uy
        val k = cell * jInvR
        var ddx = (jAx + 2.0 * (jBx * ux - jBy * uy) + 3.0 * (jC3x * u2x - jC3y * u2y)) * k
        var ddy = (jAy + 2.0 * (jBx * uy + jBy * ux) + 3.0 * (jC3x * u2y + jC3y * u2x)) * k
        var n = jSkip
        var i = jSkip
        var x = 0.0
        var y = 0.0
        var escaped = false
        while (i < mi) {
            x = zr[n] + dx
            y = zi[n] + dy
            val r2 = x * x + y * y
            if (r2 > 4.0) { escaped = true; break }
            val nddx = 2.0 * (x * ddx - y * ddy) + cell
            ddy = 2.0 * (x * ddy + y * ddx)
            ddx = nddx
            // Rebasing (Zhuoran): once the pixel's own orbit is nearer 0 than
            // its distance from the reference, carry on from the start of the
            // reference instead. Without it δ swamps Z and the doubles lose the
            // pixel (the classic perturbation "glitch" blots). Z_0 = 0, so the
            // pixel's z simply becomes its δ.
            if (r2 < dx * dx + dy * dy) { dx = x; dy = y; n = 0 }
            val ax = 2.0 * zr[n] + dx
            val ay = 2.0 * zi[n] + dy
            val ndx = ax * dx - ay * dy + dcx
            dy = ax * dy + ay * dx + dcy
            dx = ndx
            n++
            i++
        }
        if (!escaped) {
            mu[idx] = -1f
            de[idx] = 0f
            return
        }
        settle(x, y, ddx, ddy, i, jRefX + dcx, jRefY + dcy, cell, 1.0, mu, de, idx)
    }

    /**
     * Two perturbation samples in one loop, for the same reason as
     * [escapePair]: two independent chains of multiplies fill the core where
     * one leaves it idle. Each result is exactly what [escapeP] gives alone.
     */
    private fun escapePairP(
        acx: Double, acy: Double, ia: Int, bcx: Double, bcy: Double, ib: Int,
        mi: Int, cell: Double, mu: FloatArray, de: FloatArray
    ) {
        val zr = jZr
        val zi = jZi
        val k = cell * jInvR
        val sAx = jAx; val sAy = jAy; val sBx = jBx; val sBy = jBy; val sCx = jC3x; val sCy = jC3y
        // Series start for each (see escapeP).
        val aux = acx * jInvR; val auy = acy * jInvR
        var t1x = sCx * aux - sCy * auy + sBx; var t1y = sCx * auy + sCy * aux + sBy
        var t2x = t1x * aux - t1y * auy + sAx; var t2y = t1x * auy + t1y * aux + sAy
        var adx = t2x * aux - t2y * auy; var ady = t2x * auy + t2y * aux
        var u2x = aux * aux - auy * auy; var u2y = 2.0 * aux * auy
        var aDx = (sAx + 2.0 * (sBx * aux - sBy * auy) + 3.0 * (sCx * u2x - sCy * u2y)) * k
        var aDy = (sAy + 2.0 * (sBx * auy + sBy * aux) + 3.0 * (sCx * u2y + sCy * u2x)) * k
        val bux = bcx * jInvR; val buy = bcy * jInvR
        t1x = sCx * bux - sCy * buy + sBx; t1y = sCx * buy + sCy * bux + sBy
        t2x = t1x * bux - t1y * buy + sAx; t2y = t1x * buy + t1y * bux + sAy
        var bdx = t2x * bux - t2y * buy; var bdy = t2x * buy + t2y * bux
        u2x = bux * bux - buy * buy; u2y = 2.0 * bux * buy
        var bDx = (sAx + 2.0 * (sBx * bux - sBy * buy) + 3.0 * (sCx * u2x - sCy * u2y)) * k
        var bDy = (sAy + 2.0 * (sBx * buy + sBy * bux) + 3.0 * (sCx * u2y + sCy * u2x)) * k
        var an = jSkip; var ai = jSkip; var ax = 0.0; var ay = 0.0; var aOut = false
        var bn = jSkip; var bi = jSkip; var bx = 0.0; var by = 0.0; var bOut = false
        var aOn = ai < mi
        var bOn = bi < mi
        while (aOn || bOn) {
            if (aOn) {
                ax = zr[an] + adx; ay = zi[an] + ady
                val r2 = ax * ax + ay * ay
                if (r2 > 4.0) { aOut = true; aOn = false } else {
                    val t = 2.0 * (ax * aDx - ay * aDy) + cell
                    aDy = 2.0 * (ax * aDy + ay * aDx); aDx = t
                    if (r2 < adx * adx + ady * ady) { adx = ax; ady = ay; an = 0 }
                    val px = 2.0 * zr[an] + adx
                    val py = 2.0 * zi[an] + ady
                    val nx = px * adx - py * ady + acx
                    ady = px * ady + py * adx + acy
                    adx = nx
                    an++; ai++
                    if (ai >= mi) aOn = false
                }
            }
            if (bOn) {
                bx = zr[bn] + bdx; by = zi[bn] + bdy
                val r2 = bx * bx + by * by
                if (r2 > 4.0) { bOut = true; bOn = false } else {
                    val t = 2.0 * (bx * bDx - by * bDy) + cell
                    bDy = 2.0 * (bx * bDy + by * bDx); bDx = t
                    if (r2 < bdx * bdx + bdy * bdy) { bdx = bx; bdy = by; bn = 0 }
                    val px = 2.0 * zr[bn] + bdx
                    val py = 2.0 * zi[bn] + bdy
                    val nx = px * bdx - py * bdy + bcx
                    bdy = px * bdy + py * bdx + bcy
                    bdx = nx
                    bn++; bi++
                    if (bi >= mi) bOn = false
                }
            }
        }
        if (aOut) settle(ax, ay, aDx, aDy, ai, jRefX + acx, jRefY + acy, cell, 1.0, mu, de, ia) else { mu[ia] = -1f; de[ia] = 0f }
        if (bOut) settle(bx, by, bDx, bDy, bi, jRefX + bcx, jRefY + bcy, cell, 1.0, mu, de, ib) else { mu[ib] = -1f; de[ib] = 0f }
    }

    private fun inCardioidOrBulb(x0: Double, y0: Double): Boolean {
        val xq = x0 - 0.25
        val q = xq * xq + y0 * y0
        return q * (q + xq) <= 0.25 * y0 * y0 || (x0 + 1.0) * (x0 + 1.0) + y0 * y0 <= 0.0625
    }

    /**
     * Two samples (neighbouring cells or sub-cells) iterated in one loop. One orbit is a chain of
     * dependent multiplies that leaves most of the core idle; two independent
     * chains side by side run in nearly the time of one. Each result is
     * exactly what [escape] gives for that point on its own.
     */
    private fun escapePair(
        ax0: Double, ay0: Double, ia: Int, bx0: Double, by0: Double, ib: Int,
        mi: Int, cell: Double, mu: FloatArray, de: FloatArray, periodic: Boolean
    ) {
        val aIn = inCardioidOrBulb(ax0, ay0)
        val bIn = inCardioidOrBulb(bx0, by0)
        if (aIn || bIn) {
            if (aIn) { mu[ia] = -1f; de[ia] = 0f } else escape(ax0, ay0, mi, cell, mu, de, ia, periodic)
            if (bIn) { mu[ib] = -1f; de[ib] = 0f } else escape(bx0, by0, mi, cell, mu, de, ib, periodic)
            return
        }
        var ax = 0.0; var ay = 0.0; var adx = 0.0; var ady = 0.0; var ai = 0
        var bx = 0.0; var by = 0.0; var bdx = 0.0; var bdy = 0.0; var bi = 0
        // The periodicity check of escape(), per orbit, in the same order.
        var apx = 0.0; var apy = 0.0; var ac = 8; var aCyc = false
        var bpx = 0.0; var bpy = 0.0; var bc = 8; var bCyc = false
        var aOn = true
        var bOn = true
        while (aOn && bOn) {
            val adxt = 2.0 * (ax * adx - ay * ady) + 1.0
            ady = 2.0 * (ax * ady + ay * adx); adx = adxt
            val axt = ax * ax - ay * ay + ax0
            ay = 2.0 * ax * ay + ay0; ax = axt; ai++
            val bdxt = 2.0 * (bx * bdx - by * bdy) + 1.0
            bdy = 2.0 * (bx * bdy + by * bdx); bdx = bdxt
            val bxt = bx * bx - by * by + bx0
            by = 2.0 * bx * by + by0; bx = bxt; bi++
            if (periodic) {
                if (abs(ax - apx) < PERIOD_EPS && abs(ay - apy) < PERIOD_EPS) aCyc = true
                else if (ai == ac) { apx = ax; apy = ay; ac += ac }
                if (abs(bx - bpx) < PERIOD_EPS && abs(by - bpy) < PERIOD_EPS) bCyc = true
                else if (bi == bc) { bpx = bx; bpy = by; bc += bc }
            }
            aOn = !aCyc && ax * ax + ay * ay <= 4.0 && ai < mi
            bOn = !bCyc && bx * bx + by * by <= 4.0 && bi < mi
        }
        while (aOn) {
            val adxt = 2.0 * (ax * adx - ay * ady) + 1.0
            ady = 2.0 * (ax * ady + ay * adx); adx = adxt
            val axt = ax * ax - ay * ay + ax0
            ay = 2.0 * ax * ay + ay0; ax = axt; ai++
            if (periodic) {
                if (abs(ax - apx) < PERIOD_EPS && abs(ay - apy) < PERIOD_EPS) aCyc = true
                else if (ai == ac) { apx = ax; apy = ay; ac += ac }
            }
            aOn = !aCyc && ax * ax + ay * ay <= 4.0 && ai < mi
        }
        while (bOn) {
            val bdxt = 2.0 * (bx * bdx - by * bdy) + 1.0
            bdy = 2.0 * (bx * bdy + by * bdx); bdx = bdxt
            val bxt = bx * bx - by * by + bx0
            by = 2.0 * bx * by + by0; bx = bxt; bi++
            if (periodic) {
                if (abs(bx - bpx) < PERIOD_EPS && abs(by - bpy) < PERIOD_EPS) bCyc = true
                else if (bi == bc) { bpx = bx; bpy = by; bc += bc }
            }
            bOn = !bCyc && bx * bx + by * by <= 4.0 && bi < mi
        }
        if (aCyc || ai >= mi) { mu[ia] = -1f; de[ia] = 0f } else settle(ax, ay, adx, ady, ai, ax0, ay0, 1.0, cell, mu, de, ia)
        if (bCyc || bi >= mi) { mu[ib] = -1f; de[ib] = 0f } else settle(bx, by, bdx, bdy, bi, bx0, by0, 1.0, cell, mu, de, ib)
    }

    /**
     * An escaped orbit's smooth count and distance estimate, from where it
     * crossed radius 2. The derivative grows by [inc] a step and the distance
     * is divided by [div]: 1 and the cell for a plain dz/dc, the cell and 1
     * for one carried pre-scaled (see escapeP).
     */
    private fun settle(
        x1: Double, y1: Double, dx1: Double, dy1: Double, i: Int,
        x0: Double, y0: Double, inc: Double, div: Double, mu: FloatArray, de: FloatArray, idx: Int
    ) {
        var x = x1
        var y = y1
        var dx = dx1
        var dy = dy1
        // Membership is decided above, at radius 2. Three more steps only
        // carry the escaped orbit far enough out (|z| in the hundreds) for the
        // smooth count n + 1 − log2(ln|z|) to be continuous across the bands.
        for (e in 0 until 3) {
            val dxt = 2.0 * (x * dx - y * dy) + inc
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
        val dist = if (dd.isFinite() && dd > 0.0) 2.0 * exp(lz) * lz / dd / div else 0.0
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
    private fun selectRefine(cs: Int, rs: Int) {
        val n = cs * rs
        // Each cell's log count once, not five times over as each neighbour's
        // neighbour; the colouring reuses it too.
        if (lnSmooth.size != n) lnSmooth = FloatArray(n)
        for (i in 0 until n) lnSmooth[i] = ln(1f + max(0f, smooth[i] - muBase))
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
                    val lm = lnSmooth[i]
                    if (col + 1 < cs && smooth[i + 1] >= 0f && abs(lnSmooth[i + 1] - lm) > STEP_LM) need = true
                    else if (col > 0 && smooth[i - 1] >= 0f && abs(lnSmooth[i - 1] - lm) > STEP_LM) need = true
                    else if (row + 1 < rs && smooth[i + cs] >= 0f && abs(lnSmooth[i + cs] - lm) > STEP_LM) need = true
                    else if (row > 0 && smooth[i - cs] >= 0f && abs(lnSmooth[i - cs] - lm) > STEP_LM) need = true
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
        if (refIdx.size < count) refIdx = IntArray(count + count / 2)
        if (subMu.size < count * 9) { subMu = FloatArray(count * 9 + count * 4); subDe = FloatArray(subMu.size) }
        for (i in 0 until n) if (refined[i] >= 0) refIdx[refined[i]] = i
    }

    /** Sub-samples [from, to) of the refined cells, nine to a cell. */
    private fun refineSlice(cs: Int, from: Int, to: Int) {
        val rs = rows
        val count = refCount
        val per = (count + REFINE_SLICES - 1) / REFINE_SLICES
        val cx = jCx
        val cy = jCy
        val ox = jOffX
        val oy = jOffY
        val scale = jScale
        val halfW = jHalfW
        val mi = jMi
        val fine = jCell / 3.0
        val stepX = 2.0 * scale * halfW / (cs - 1)
        val stepY = 2.0 * scale / (rs - 1)
        val pert = jPert
        heavyRange(to - from) { a, b ->
            var pend = -1
            var px0 = 0.0
            var py0 = 0.0
            for (p in from + a until from + b) {
                val r = (p / 9 % per) * REFINE_SLICES + p / 9 / per
                if (r >= count) continue
                val sub = p % 9
                val s = r * 9 + sub
                val ci = refIdx[r]
                if (sub == 4) {
                    // The centre sub-sample is the coarse sample itself, already
                    // computed: its raw count, and its distance in sub-cells.
                    subMu[s] = rawMu(ci)
                    subDe[s] = (rawDist[ci] * 3f).coerceAtMost(1e6f)
                    continue
                }
                val col = ci % cs
                val row = ci / cs
                val fx = (col.toDouble() / (cs - 1) * 2.0 - 1.0) * scale * halfW + ((sub % 3) - 1) * stepX / 3.0
                val fy = (row.toDouble() / (rs - 1) * 2.0 - 1.0) * scale + ((sub / 3) - 1) * stepY / 3.0
                val x0 = if (pert) ox + fx else cx + fx
                val y0 = if (pert) oy + fy else cy + fy
                if (pend < 0) { pend = s; px0 = x0; py0 = y0; continue }
                if (pert) escapePairP(px0, py0, pend, x0, y0, s, mi, fine, subMu, subDe)
                else escapePair(px0, py0, pend, x0, y0, s, mi, fine, subMu, subDe, false)
                pend = -1
            }
            if (pend >= 0) {
                if (pert) escapeP(px0, py0, mi, fine, subMu, subDe, pend)
                else escape(px0, py0, mi, fine, subMu, subDe, pend, false)
            }
        }
    }

    private fun healSubs() {
        val count = refCount
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
    /** ln(1 + smooth − muBase), per cell. */
    private var lnSmooth = FloatArray(0)
    /** Escape counts for colouring, smoothed near the set (see edgeGlow). */
    private var smooth = FloatArray(0)

    /*
     * The tiles of the image on screen (d*) and of the one being built (b*),
     * at zoom ratio 1: centre, half size, colour. The colours only change when
     * a new image arrives, so they are worked out once per image instead of
     * fifty thousand logs and cosines every frame.
     */
    private var dX = FloatArray(0)
    private var dY = FloatArray(0)
    private var dW = FloatArray(0)
    private var dH = FloatArray(0)
    private var dC = IntArray(0)
    private var dN = 0
    private var bX = FloatArray(0)
    private var bY = FloatArray(0)
    private var bW = FloatArray(0)
    private var bH = FloatArray(0)
    private var bC = IntArray(0)
    private var bN = 0

    private fun pushTile(x: Float, y: Float, w: Float, h: Float, c: Int) {
        if (bN == bX.size) {
            val n = max(1024, bN * 3 / 2)
            bX = bX.copyOf(n); bY = bY.copyOf(n); bW = bW.copyOf(n); bH = bH.copyOf(n); bC = bC.copyOf(n)
        }
        bX[bN] = x; bY[bN] = y; bW[bN] = w; bH[bN] = h; bC[bN] = c
        bN++
    }

    /** Per coarse cell its colour (0 = inside, not drawn), and per sub-sample. */
    private var cellColour = IntArray(0)
    private var subColour = IntArray(0)

    /** Rows [from, to) of the image being built into tiles. */
    private fun buildRows(palette: Palette, from: Int, to: Int) {
        if (from == 0) bN = 0
        val cs = cols
        if (cellColour.size != cs * rows) cellColour = IntArray(cs * rows)
        if (subColour.size < subMu.size) subColour = IntArray(subMu.size)
        val span = max(0.5f, highMu - lowMu)
        // The filament is white-hot with only a breath of the palette's
        // lightest colour: lerped at 90 % into the bands, Aurora's yellow edge
        // turned every teal band green and the picture into rainbow confetti.
        val edgeColour = Palette.lerp(brightestOf(palette), Palette.TEXT_PRIMARY, 0.6f)
        val hasSubs = refined.size == cs * rows
        // The colours, across cores: a log, a cosine and a palette lookup per
        // tile were the dearest part of the image left after the sampling.
        heavyRange(to - from) { a, b ->
            for (row in from + a until from + b) {
                val base = row * cs
                for (col in 0 until cs) {
                    val ref = if (hasSubs) refined[base + col] else -1
                    val mu = smooth[base + col]
                    if (ref < 0) {
                        if (iterations[base + col] < 0f) { cellColour[base + col] = 0; continue }
                        // The lit filament, as weighted by edgeGlow(). A hard
                        // "within 1.2 cells" threshold switched whole cells on and
                        // off along sub-cell filaments: a thick, ragged band.
                        val edge = edgeDist[base + col]
                        val c = colourAt(palette, lnSmooth[base + col], span)
                        cellColour[base + col] = if (edge > 0.02f) Palette.lerp(c, edgeColour, 0.9f * edge) else c
                        continue
                    }
                    // A boundary cell, drawn as its nine exact sub-samples. Their
                    // base colour is the neighbourhood's (a hue per sample was
                    // confetti); the lit edge comes from each one's own distance.
                    val ck = crowdK[base + col]
                    val centre = if (mu >= 0f) mu else neighbourMu(row, col)
                    val b9 = ref * 9
                    for (sub in 0 until 9) {
                        if (subMu[b9 + sub] < 0f) { subColour[b9 + sub] = 0; continue }
                        val cBase = baseColour(palette, subBase(row, col, sub % 3 - 1, sub / 3 - 1, centre), span)
                        val d = subDe[b9 + sub] / EDGE_CELLS
                        val edge = ck / (1f + d * d * d)
                        subColour[b9 + sub] = if (edge > 0.02f) Palette.lerp(cBase, edgeColour, 0.9f * edge) else cBase
                    }
                }
            }
        }
        val halfW = jHalfW
        val dx = 2f * halfW / cs
        val dy = 2f / rows
        // Square cells that tile, a fifth oversized so no seam shows between
        // them. Round point sprites left a dark gap at every corner, which on
        // a phone read as a grid of beads rather than a picture.
        val hw = dx * 0.5f * 1.18f
        val hh = dy * 0.5f * 1.18f
        val shw = hw / 3f
        val shh = hh / 3f
        val sdx = dx / 3f
        val sdy = dy / 3f
        // A dissolve frame keeps every cell its own tile, the inside too (as
        // background): draw() mixes each tile with the old dive's colour at
        // its spot, and a merged run or a missing tile would show as a stripe
        // or a hole in the old picture.
        val fade = jFade
        val bg = Palette.BG
        for (row in from until to) {
            val y = -1f + (row + 0.5f) * dy
            val base = row * cs
            // A run of plain tiles of one colour is one long rect: same pixels
            // (each tile is drawn over the one before it), fewer primitives.
            var runC = 0
            var runFirst = 0f
            var runLast = 0f
            for (col in 0 until cs) {
                val x = -halfW + (col + 0.5f) * dx
                val ref = if (hasSubs) refined[base + col] else -1
                val c = if (ref < 0) cellColour[base + col] else 0
                if (fade) {
                    if (ref < 0) { pushTile(x, y, hw, hh, if (c == 0) bg else c); continue }
                    val b9 = ref * 9
                    for (sub in 0 until 9) {
                        val sc = subColour[b9 + sub]
                        pushTile(x + ((sub % 3) - 1) * sdx, y + ((sub / 3) - 1) * sdy, shw, shh, if (sc == 0) bg else sc)
                    }
                    continue
                }
                if (runC != 0 && c == runC) { runLast = x; continue }
                if (runC != 0) pushTile((runFirst + runLast) * 0.5f, y, (runLast - runFirst) * 0.5f + hw, hh, runC)
                runC = c; runFirst = x; runLast = x
                if (ref < 0) continue
                val b9 = ref * 9
                for (sub in 0 until 9) {
                    val sc = subColour[b9 + sub]
                    if (sc == 0) continue
                    pushTile(x + ((sub % 3) - 1) * sdx, y + ((sub / 3) - 1) * sdy, shw, shh, sc)
                }
            }
            if (runC != 0) pushTile((runFirst + runLast) * 0.5f, y, (runLast - runFirst) * 0.5f + hw, hh, runC)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val n = dN
        g.reserveInstances(n)
        val halfW = env.halfWidth
        // The image is recomputed eight times a second and the zoom is
        // continuous, so in between the last image is drawn grown by however
        // far the zoom has gone since — about the centre, which is where the
        // zoom is aimed. It used to sit still for an eighth of a second and
        // then jump 2.8%, eight times a second: a zoom that stuttered, on the
        // one sim whose whole subject is a smooth dive. While a tap glides the
        // centre, the image is slid by the same amount.
        val k: Float
        val px: Float
        val py: Float
        if (handoffWait) {
            // Still the old dive's picture, carrying on its own zoom.
            k = (oldZoomNow / oldComputedZoom).toFloat().coerceIn(0.5f, 8f)
            px = oldPanX
            py = oldPanY
        } else {
            k = (zoom / computedZoom).toFloat().coerceIn(0.5f, 4f)
            px = ((offX - imgOffX) / imgScale).toFloat()
            py = ((offY - imgOffY) / imgScale).toFloat()
        }
        val xs = dX
        val ys = dY
        val ws = dW
        val hs = dH
        val cs = dC
        val blend = fading && !handoffWait
        val t = if (blend) fadeT * fadeT * (3f - 2f * fadeT) else 1f
        val kOld = (oldZoomNow / oldComputedZoom).toFloat().coerceIn(0.5f, 8f)
        val ocs = oldCs
        val ors = oldRs
        val oDx = 2f * halfW / max(1, ocs)
        val oDy = 2f / max(1, ors)
        for (i in 0 until n) {
            val y = (ys[i] - py) * k
            val h = hs[i] * k
            if (abs(y) - h > 1f) continue
            val x = (xs[i] - px) * k
            val w = ws[i] * k
            if (abs(x) - w > halfW) continue
            var c = cs[i]
            if (blend) {
                // The old dive's colour under this tile, from its last picture
                // grown by its own zoom: one opaque layer, so no seams.
                val oc = ((x / kOld + oldPanX + halfW) / oDx).toInt().coerceIn(0, ocs - 1)
                val or = ((y / kOld + oldPanY + 1f) / oDy).toInt().coerceIn(0, ors - 1)
                val old = oldGrid[or * ocs + oc].let { if (it == 0) Palette.BG else it }
                c = Palette.lerp(old, c, t)
            }
            g.rect(x, y, w, h, c)
        }
    }

    private fun baseColour(palette: Palette, mu: Float, span: Float): Int =
        colourAt(palette, ln(1f + max(0f, mu - muBase)), span)

    /** [baseColour] from the log count lm = ln(1 + mu − muBase). */
    private fun colourAt(palette: Palette, lm: Float, span: Float): Int {
        // Hue cycles smoothly with the log of the escape count — a cosine, so
        // the palette runs out and back with no seam — over most of the
        // palette, leaving its far end to the edge.
        val hue = HUE_SPAN * (0.5f - 0.5f * cos(lm * HUE_CYCLES * 2f * PI.toFloat()))
        // Brightness rises towards the boundary.
        val near = ((lm - lowMu) / span).coerceIn(0f, 1f)
        // Far field nearly black: at 17 % its violet read as a muddy haze
        // behind everything, and the set's edge had no darkness to glow from.
        val lit = 0.07f + 0.85f * near * sqrt(near)
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

    /**
     * A tap steers: the dive turns toward a Misiurewicz point near where the
     * finger landed, so it keeps its detail all the way down. It used to move
     * the centre to the tapped point itself, and a dive aimed at an arbitrary
     * point sooner or later ends inside a mini-set — the black screen.
     */
    override fun onInput(env: SimEnv, e: TouchEvent) {
        if (e.kind != TouchEvent.Kind.TAP) return
        if (handoffWait || fading) return
        val scale = 1.4 / zoom
        val r = ref!!
        val tx = offX + e.x * scale
        val ty = offY + e.y * scale
        job = null
        seek = Seek(r, tx, ty, scale, guessEscape(r, tx, ty))
    }

    /** The iteration a point at offset (tx, ty) from the reference escapes at, or the budget. */
    private fun guessEscape(r: Ref, tx: Double, ty: Double): Int {
        val mi = max(jMi, 64)
        r.ensure(mi + 8)
        var dx = 0.0; var dy = 0.0; var n = 0
        for (i in 0 until mi) {
            val x = r.zr[n] + dx
            val y = r.zi[n] + dy
            val r2 = x * x + y * y
            if (r2 > 4.0) return i
            if (r2 < dx * dx + dy * dy) { dx = x; dy = y; n = 0 }
            val ax = 2.0 * r.zr[n] + dx
            val ay = 2.0 * r.zi[n] + dy
            val ndx = ax * dx - ay * dy + tx
            dy = ax * dy + ay * dx + ty
            dx = ndx
            n++
        }
        return max(8, lastLowCount.toInt())
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> {
        val m = zoom / 0.6
        val z = if (m < 1e6) String.format(java.util.Locale.US, "%.0fx", m) else {
            val e = kotlin.math.floor(kotlin.math.log10(m)).toInt()
            String.format(java.util.Locale.US, "%.1fe%dx", m / Math.pow(10.0, e.toDouble()), e)
        }
        return listOf("zoom" to z, "detail" to iterNow.toString())
    }

    // ---- Big numbers -------------------------------------------------------

    /**
     * Fixed-point numbers: a BigInteger holding value · 2^bits. Every value
     * here is a point or orbit of the Mandelbrot set (|z| ≤ 2), so a fixed
     * point needs no exponent, and a binary shift rounds where BigDecimal's
     * decimal rounding cost a division per multiply.
     */
    object Fix {
        fun parse(s: String, bits: Int): BigInteger =
            BigDecimal(s).multiply(BigDecimal(BigInteger.ONE.shiftLeft(bits))).setScale(0, RoundingMode.HALF_EVEN).toBigIntegerExact()

        fun fromDouble(v: Double, bits: Int): BigInteger =
            BigDecimal(v).multiply(BigDecimal(BigInteger.ONE.shiftLeft(bits))).setScale(0, RoundingMode.HALF_EVEN).toBigIntegerExact()

        fun toDouble(v: BigInteger, bits: Int): Double {
            val bl = v.bitLength()
            return if (bl <= 62) Math.scalb(v.toLong().toDouble(), -bits)
            else Math.scalb(v.shiftRight(bl - 62).toLong().toDouble(), bl - 62 - bits)
        }
    }

    /** A dive's target: a Misiurewicz point given to double precision, preperiod k, period p. */
    class Target(val re: String, val im: String, val k: Int, val p: Int)

    /**
     * A reference point, exact to [bits], and its orbit z_0 … z_{k+p} in
     * doubles. From z_k on the orbit repeats with period p, so [ensure] lays
     * it out to any length by copying — the reference never runs out.
     */
    class Ref(val cx: BigInteger, val cy: BigInteger, val bits: Int, val k: Int, val p: Int, orbit: DoubleArray) {
        val cxD = Fix.toDouble(cx, bits)
        val cyD = Fix.toDouble(cy, bits)
        private val head = orbit
        var zr = DoubleArray(0); private set
        var zi = DoubleArray(0); private set

        fun ensure(len: Int) {
            if (zr.size >= len) return
            val n = max(len, zr.size * 3 / 2)
            val ar = DoubleArray(n)
            val ai = DoubleArray(n)
            val kp = k + p
            for (i in 0 until n) {
                val j = if (i < kp) i else k + (i - k) % p
                ar[i] = head[2 * j]
                ai[i] = head[2 * j + 1]
            }
            zr = ar
            zi = ai
        }

        companion object {
            /** The catalogue's own double value, if Newton ever failed on it; good to ~1e13. */
            fun fromDoubles(t: Target): Ref {
                val bits = 64
                val cx = Fix.parse(t.re, bits)
                val cy = Fix.parse(t.im, bits)
                val x0 = t.re.toDouble()
                val y0 = t.im.toDouble()
                val o = DoubleArray(2 * (t.k + t.p + 1))
                var x = 0.0; var y = 0.0
                for (n in 0..t.k + t.p) {
                    o[2 * n] = x; o[2 * n + 1] = y
                    val xt = x * x - y * y + x0
                    y = 2 * x * y + y0; x = xt
                }
                return Ref(cx, cy, bits, t.k, t.p, o)
            }
        }
    }

    /**
     * Newton's method for a Misiurewicz point, g(c) = z_{k+p}(c) − z_k(c) = 0,
     * in fixed point at [bits], resumable in slices of iterations. Started from
     * a point already near the root (a coarser copy, or a tap's find), it
     * doubles its correct bits each step. Fails if it wanders more than
     * [maxMove] from the start — it found a different root — or escapes.
     */
    class RefJob(cx0: BigInteger, cy0: BigInteger, val bits: Int, private val k: Int, private val p: Int, private val maxMove: BigInteger?) {
        private var cx = cx0
        private var cy = cy0
        private val sx = cx0
        private val sy = cy0
        private val one = BigInteger.ONE.shiftLeft(bits)
        private val escape = bits + 4
        private var zx = BigInteger.ZERO
        private var zy = BigInteger.ZERO
        private var dx = BigInteger.ZERO
        private var dy = BigInteger.ZERO
        private var zkx = BigInteger.ZERO
        private var zky = BigInteger.ZERO
        private var dkx = BigInteger.ZERO
        private var dky = BigInteger.ZERO
        private var n = 0
        private var steps = 0
        /** Converged: one last pass lays down the orbit at the final point. */
        private var last = false
        private val orbit = DoubleArray(2 * (k + p + 1))
        var done = false; private set
        var failed = false; private set

        fun result(): Ref = Ref(cx, cy, bits, k, p, orbit)

        fun work(iters: Int) {
            var left = iters
            while (left > 0 && !done && !failed) {
                if (n < k + p) {
                    orbit[2 * n] = Fix.toDouble(zx, bits)
                    orbit[2 * n + 1] = Fix.toDouble(zy, bits)
                    if (!last) {
                        val ndx = zx.multiply(dx).subtract(zy.multiply(dy)).shiftRight(bits - 1).add(one)
                        val ndy = zx.multiply(dy).add(zy.multiply(dx)).shiftRight(bits - 1)
                        dx = ndx; dy = ndy
                    }
                    val x2 = zx.multiply(zx)
                    val y2 = zy.multiply(zy)
                    val xy = zx.multiply(zy)
                    zx = x2.subtract(y2).shiftRight(bits).add(cx)
                    zy = xy.shiftRight(bits - 1).add(cy)
                    n++
                    if (n == k) { zkx = zx; zky = zy; dkx = dx; dky = dy }
                    if (zx.bitLength() > escape || zy.bitLength() > escape) { failed = true; return }
                    left--
                    continue
                }
                orbit[2 * n] = Fix.toDouble(zx, bits)
                orbit[2 * n + 1] = Fix.toDouble(zy, bits)
                if (last) { done = true; return }
                // Newton: c −= g / g'.
                val gx = zx.subtract(zkx)
                val gy = zy.subtract(zky)
                val hx = dx.subtract(dkx)
                val hy = dy.subtract(dky)
                val den = hx.multiply(hx).add(hy.multiply(hy))
                if (den.signum() == 0) { failed = true; return }
                val nx = gx.multiply(hx).add(gy.multiply(hy))
                val ny = gy.multiply(hx).subtract(gx.multiply(hy))
                val stepX = nx.shiftLeft(bits).divide(den)
                val stepY = ny.shiftLeft(bits).divide(den)
                cx = cx.subtract(stepX)
                cy = cy.subtract(stepY)
                steps++
                if (maxMove != null && (cx.subtract(sx).abs() > maxMove || cy.subtract(sy).abs() > maxMove)) { failed = true; return }
                // Within a few dozen bits of the last: as exact as rounding allows.
                if (stepX.bitLength() < 40 && stepY.bitLength() < 40) last = true
                else if (steps > 60) { failed = true; return }
                zx = BigInteger.ZERO; zy = BigInteger.ZERO
                dx = BigInteger.ZERO; dy = BigInteger.ZERO
                n = 0
            }
        }
    }

    /** A tap's find: offset from the reference, preperiod and period. */
    private class Found(val dx: Double, val dy: Double, val k: Int, val p: Int, val dist: Double)

    /**
     * The search a tap starts, entirely in doubles: Newton for z_{k+p} = z_k
     * with the orbit as the reference plus a perturbation, so it works at any
     * depth with no big numbers. Candidates are preperiods just before the
     * tapped point escapes (where its orbit sweeps the plane fast enough to
     * hit a cycle) and small periods. The nearest repelling root within
     * reach of the tap wins. Resumable, counted in iterations.
     */
    private class Seek(val r: Ref, val tx: Double, val ty: Double, val radius: Double, escapeAt: Int) {
        private val cands = ArrayList<IntArray>()
        init {
            val periods = linkedSetOf(r.p, 1, 2, 3)
            for (pp in periods) for (dk in 0..9) {
                val kk = escapeAt - dk
                if (kk >= 1) cands.add(intArrayOf(kk, pp))
            }
            val longest = (cands.maxOfOrNull { it[0] + it[1] } ?: 0) + 4
            r.ensure(longest)
        }
        private var ci = 0
        private var ux = 0.0
        private var uy = 0.0
        private var step = 0
        private var n = 0
        private var dx = 0.0; private var dy = 0.0
        private var ex = 0.0; private var ey = 0.0
        private var zkx = 0.0; private var zky = 0.0
        private var ekx = 0.0; private var eky = 0.0
        private var mx = 1.0; private var my = 0.0
        var best: Found? = null; private set
        var finished = false; private set

        private fun nextCand() { ci++; step = 0; n = 0; ux = 0.0; uy = 0.0; resetPass() }
        private fun resetPass() { n = 0; dx = 0.0; dy = 0.0; ex = 0.0; ey = 0.0; mx = 1.0; my = 0.0 }

        fun work(iters: Int) {
            var left = iters
            if (ci == 0 && step == 0 && n == 0) { ux = tx / radius; uy = ty / radius }
            while (left > 0 && !finished) {
                if (ci >= cands.size || (best != null && best!!.dist < 0.12)) { finished = true; return }
                val kk = cands[ci][0]
                val pp = cands[ci][1]
                if (n == 0 && step == 0) { ux = tx / radius; uy = ty / radius }
                val dcx = ux * radius
                val dcy = uy * radius
                if (n < kk + pp) {
                    val zx = r.zr[n] + dx
                    val zy = r.zi[n] + dy
                    if (zx * zx + zy * zy > 1e4) { nextCand(); continue }
                    if (n >= kk) { val t = mx * zx - my * zy; my = 2.0 * (mx * zy + my * zx); mx = 2.0 * t }
                    val nex = 2.0 * (zx * ex - zy * ey) + radius
                    ey = 2.0 * (zx * ey + zy * ex)
                    ex = nex
                    val ax = 2.0 * r.zr[n] + dx
                    val ay = 2.0 * r.zi[n] + dy
                    val ndx = ax * dx - ay * dy + dcx
                    dy = ax * dy + ay * dx + dcy
                    dx = ndx
                    n++
                    if (n == kk) { zkx = r.zr[n] + dx; zky = r.zi[n] + dy; ekx = ex; eky = ey }
                    left--
                    continue
                }
                val gx = (r.zr[n] + dx) - zkx
                val gy = (r.zi[n] + dy) - zky
                val hx = ex - ekx
                val hy = ey - eky
                val den = hx * hx + hy * hy
                if (!(den > 0.0)) { nextCand(); continue }
                val sx = (gx * hx + gy * hy) / den
                val sy = (gy * hx - gx * hy) / den
                ux -= sx; uy -= sy
                step++
                val moved = sqrt((ux - tx / radius).let { it * it } + (uy - ty / radius).let { it * it })
                if (sx * sx + sy * sy < 1e-24) {
                    // A repelling cycle (|multiplier| > 1) is a Misiurewicz
                    // point; 0 is a mini-set's centre, the black we are avoiding.
                    val mult = sqrt(mx * mx + my * my)
                    if (moved < SEEK_REACH && mult > 1.001 && (best == null || moved < best!!.dist))
                        best = Found(ux * radius, uy * radius, kk, pp, moved)
                    nextCand(); continue
                }
                if (step > 30 || moved > 4.0) { nextCand(); continue }
                resetPass()
            }
        }
    }

    companion object {
        private const val PERIOD_EPS = 1e-13
        private const val IDLE = 0
        private const val COARSE = 1
        private const val REFINE = 2
        private const val BUILD = 3
        private const val POST = 4
        /**
         * Steps each stage of an image is spread over: with one for the
         * healing pass, the 15 of a refresh at 120 Hz. The boundary samples
         * cost the most, so they get the most steps.
         */
        private const val COARSE_SLICES = 4
        private const val REFINE_SLICES = 7
        private const val BUILD_SLICES = 3
        private val LN2 = ln(2.0)
        /** Colour cycles per e-fold of escape count: two or three bands across a view. */
        private const val HUE_CYCLES = 0.6f
        /** Coarse cells within this distance of the set are resampled 3×3. */
        private const val REFINE_CELLS = 1.6f
        /** A jump in log escape count between neighbours that shows as a step. */
        private const val STEP_LM = 0.06f
        /** How many cells out from the set the lit edge reaches. */
        private const val EDGE_CELLS = 0.35f
        /**
         * How much of the palette the bands use: a related run of hues (teal,
         * blue, violet on Aurora) rather than the whole ramp, which cycled
         * through five colours every e-fold.
         */
        private const val HUE_SPAN = 0.55f
        /** Counts kept below the lowest in view when colouring (see muBase). */
        private const val COLOUR_HEADROOM = 40f

        /** The shallow iteration budget's ceiling, as it always was. */
        private const val SHALLOW_ITER_CAP = 1500
        /** Past this many iterations a frame costs too much: the dive hands off. */
        const val MAX_ITER_CAP = 120_000
        /** Where doubles stop and perturbation takes over; doubles are exact to ~1e13. */
        const val PERTURB_ZOOM = 1e7
        /**
         * Where a dive ends. Pixel steps are ~1e-262 here; doubles go
         * subnormal (and lose digits) below 1e-308, so this leaves room.
         */
        const val HANDOFF_ZOOM = 1e260
        /** The series' error bound (see prepareReference). */
        private const val SA_TOL = 1e-6
        private const val FADE_SECONDS = 2.2f
        private const val GLIDE_SECONDS = 1.2f
        /** How far from the tap (in view half-heights) a found point may be. */
        private const val SEEK_REACH = 0.5
        private const val SEEK_ITERS_PER_STEP = 250_000
        /** Big-number work a step, in iterations · words²: ~1 ms on a phone. */
        private const val BIG_WORK_PER_STEP = 200_000
        private val DEBUG = System.getenv("MANDEL_DEBUG") != null

        /**
         * Misiurewicz points, each found by Newton's method (preperiod k,
         * period p) right beside the spot the dive was always aimed at, so a
         * shallow dive looks as it did. The first four are the "Where to
         * dive" choices; all of them are the dives a hand-off goes on to.
         */
        val CATALOGUE = listOf(
            // Seahorse valley.
            Target("-0.7432918908524302", "0.13124055230879758", 24, 2),
            // Spiral.
            Target("-0.16070133631282532", "1.0375664986665432", 57, 8),
            // Out on the antenna, beside its period-3 copy of the set.
            Target("-1.7695736211088844", "0.0035409946307072364", 52, 3),
            // Misiurewicz.
            Target("0.3602495197782322", "0.6413502891821017", 56, 5),
            // Further seahorse-valley and dendrite points for later dives.
            Target("-0.745950170346325", "0.132750204860054", 17, 7),
            Target("-0.746791980247121", "0.132818988954767", 19, 5),
            Target("0.41964337760708054", "0.6062907292071994", 3, 2),
            Target("0", "1", 2, 2)
        )

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
