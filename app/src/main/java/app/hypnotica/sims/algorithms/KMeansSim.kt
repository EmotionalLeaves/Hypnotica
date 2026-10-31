package app.hypnotica.sims.algorithms

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
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lloyd's k-means: centroids dropped at random among clustered points, then
 * the two steps in turn — every point joins its nearest centroid, every
 * centroid moves to the mean of its points — until a pass changes nobody's
 * mind. That pass is the payoff: the partition is a fixed point.
 *
 * The background is each centroid's territory (the Voronoi cells of the
 * centroids, which is exactly what the assignment step computes), so the
 * borders swing as the centroids walk.
 */
class KMeansSim : Sim {

    private var k = 5
    private var n = 450
    private var px = FloatArray(0)
    private var py = FloatArray(0)
    private var owner = IntArray(0)
    private var prevOwner = IntArray(0)
    private var delay = FloatArray(0)
    private var cx = FloatArray(0)
    private var cy = FloatArray(0)
    private var fromX = FloatArray(0)
    private var fromY = FloatArray(0)
    private var toX = FloatArray(0)
    private var toY = FloatArray(0)
    private var trailX = Array(0) { FloatArray(0) }
    private var trailY = Array(0) { FloatArray(0) }
    private var trailN = 0
    private var ex = 0.4f
    private var ey = 0.86f
    private var iterSeconds = 1f
    private var passDur = FloatArray(1) { 1f }
    private val passMove = FloatArray(MAX_ITER + 1)
    private var clock = 0f
    private var vclock = 0f
    private var iteration = 0
    private var phaseStart = 0f
    private var assigning = true
    private var moved = 0
    private var converged = false
    private var doneAt = -1f
    private var payoffFrame = false
    private var inertia = 0f

    // The territory tiles.
    private var tileCols = 0
    private var tileRows = 0
    private var tiles = IntArray(0)

    override fun setup(env: SimEnv) {
        val p = env.params
        k = p.i("k", 5).coerceIn(2, 9)
        n = p.i("points", 450).coerceIn(40, 2000)
        ex = env.halfWidth * 0.86f
        ey = 0.84f
        makePoints(env)
        owner = IntArray(n) { -1 }
        prevOwner = IntArray(n) { -1 }
        delay = FloatArray(n)
        cx = FloatArray(k); cy = FloatArray(k)
        if (p.choiceIndex("init") == 1) plusPlus(env) else {
            for (j in 0 until k) {
                cx[j] = env.rng.range(-ex, ex)
                cy[j] = env.rng.range(-ey, ey)
            }
        }
        fromX = cx.copyOf(); fromY = cy.copyOf(); toX = cx.copyOf(); toY = cy.copyOf()
        trailX = Array(k) { FloatArray(MAX_ITER + 2) }
        trailY = Array(k) { FloatArray(MAX_ITER + 2) }
        for (j in 0 until k) { trailX[j][0] = cx[j]; trailY[j][0] = cy[j] }
        trailN = 1
        // Count the passes first (Lloyd's is deterministic from here), so each
        // pass gets a share of the run: three passes are shown slowly, twenty
        // briskly, and the fixed point lands where the run expects it.
        // Each pass's share goes by how far the centroids move in it: the
        // first big strides are followed slowly, the last nudges go by quickly
        // (with equal shares the second half of a run was nearly still).
        val passes = countPasses()
        var wsum = 0f
        // The floor on a pass's weight is small: the last few passes move a
        // centroid by a hair and used to hold the screen nearly still for two
        // seconds before the payoff.
        val wt = FloatArray(max(1, passes)) { 0.04f + sqrt(passMove[it] / max(1e-4f, passMove[0])) }
        for (v in wt) wsum += v
        passDur = FloatArray(wt.size) { (RUN_SECONDS * wt[it] / wsum).coerceIn(MIN_ITER_SECONDS, MAX_ITER_SECONDS) }
        iterSeconds = passDur[0]
        clock = 0f; vclock = 0f; iteration = 0
        phaseStart = INTRO_SECONDS
        assigning = true
        moved = 0; converged = false; doneAt = -1f; payoffFrame = false; inertia = 0f
        tileRows = 0
    }

    /** Blobs of different sizes and slants, kept apart so there is a right answer to find. */
    private fun makePoints(env: SimEnv) {
        val rng = env.rng
        val bx = FloatArray(k); val by = FloatArray(k)
        val sx = FloatArray(k); val sy = FloatArray(k); val rot = FloatArray(k); val w = FloatArray(k)
        val scale = min(ex, ey)
        for (j in 0 until k) {
            var best = 0f
            for (tryN in 0 until 30) {
                val x = rng.range(-ex * 0.72f, ex * 0.72f)
                val y = rng.range(-ey * 0.78f, ey * 0.78f)
                var d = Float.MAX_VALUE
                for (q in 0 until j) d = min(d, hypot((x - bx[q]) / ex, (y - by[q]) / ey))
                if (d > best) { best = d; bx[j] = x; by[j] = y }
            }
            val s = scale * rng.range(0.11f, 0.22f) * (if (k > 6) 0.8f else 1f)
            sx[j] = s * rng.range(0.8f, 1.5f)
            sy[j] = s * rng.range(0.6f, 1.1f)
            rot[j] = rng.range(0f, 3.1416f)
            w[j] = rng.range(0.6f, 1.4f)
        }
        var wsum = 0f
        for (j in 0 until k) wsum += w[j]
        px = FloatArray(n); py = FloatArray(n)
        for (i in 0 until n) {
            var u = rng.nextFloat() * wsum
            var j = 0
            while (j < k - 1 && u > w[j]) { u -= w[j]; j++ }
            val a = rng.normal() * sx[j]
            val b = rng.normal() * sy[j]
            val c = cos(rot[j]); val s = sin(rot[j])
            px[i] = (bx[j] + a * c - b * s).coerceIn(-ex, ex)
            py[i] = (by[j] + a * s + b * c).coerceIn(-ey, ey)
        }
    }

    /** k-means++: each new centroid a data point, chosen with chance by squared distance. */
    private fun plusPlus(env: SimEnv) {
        val d2 = FloatArray(n) { Float.MAX_VALUE }
        val first = env.rng.int(0, n)
        cx[0] = px[first]; cy[0] = py[first]
        for (j in 1 until k) {
            var sum = 0f
            for (i in 0 until n) {
                val dx = px[i] - cx[j - 1]; val dy = py[i] - cy[j - 1]
                d2[i] = min(d2[i], dx * dx + dy * dy)
                sum += d2[i]
            }
            var u = env.rng.nextFloat() * sum
            var pick = n - 1
            for (i in 0 until n) { u -= d2[i]; if (u <= 0f) { pick = i; break } }
            cx[j] = px[pick]; cy[j] = py[pick]
        }
    }

    private fun nearest(x: Float, y: Float, xs: FloatArray, ys: FloatArray): Int {
        var best = 0; var bd = Float.MAX_VALUE
        for (j in 0 until k) {
            val dx = x - xs[j]; val dy = y - ys[j]
            val d = dx * dx + dy * dy
            if (d < bd) { bd = d; best = j }
        }
        return best
    }

    /** Update passes until an assignment changes nothing. */
    private fun countPasses(): Int {
        val x = cx.copyOf(); val y = cy.copyOf()
        val own = IntArray(n) { -1 }
        val sumX = FloatArray(k); val sumY = FloatArray(k); val cnt = IntArray(k)
        passMove.fill(0f)
        for (pass in 0 until MAX_ITER) {
            var changed = 0
            for (i in 0 until n) {
                val j = nearest(px[i], py[i], x, y)
                if (j != own[i]) { own[i] = j; changed++ }
            }
            if (changed == 0) return pass
            sumX.fill(0f); sumY.fill(0f); cnt.fill(0)
            for (i in 0 until n) { val j = own[i]; sumX[j] += px[i]; sumY[j] += py[i]; cnt[j]++ }
            for (j in 0 until k) if (cnt[j] > 0) {
                val nx = sumX[j] / cnt[j]; val ny = sumY[j] / cnt[j]
                passMove[pass] = max(passMove[pass], hypot(nx - x[j], ny - y[j]))
                x[j] = nx; y[j] = ny
            }
        }
        return MAX_ITER
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        clock += h
        if (converged) return
        vclock += h * env.params.f("speed", 1f).coerceIn(0.1f, 8f)
        val assignShare = ASSIGN_SHARE * iterSeconds
        if (assigning) {
            if (vclock < phaseStart) return
            // The assignment step, all at once in the data; the picture spreads
            // it out as a ripple from each centroid over the next moments.
            moved = 0
            var far = 1e-4f
            for (i in 0 until n) {
                prevOwner[i] = owner[i]
                val j = nearest(px[i], py[i], cx, cy)
                if (j != owner[i]) { owner[i] = j; moved++ }
                delay[i] = hypot(px[i] - cx[j], py[i] - cy[j])
                far = max(far, delay[i])
            }
            for (i in 0 until n) delay[i] /= far
            inertia = 0f
            for (i in 0 until n) { val j = owner[i]; val dx = px[i] - cx[j]; val dy = py[i] - cy[j]; inertia += dx * dx + dy * dy }
            if (moved == 0 && iteration > 0) {
                converged = true
                doneAt = clock
                payoffFrame = true
                env.callout(if (iteration == 1) "Converged at once!" else "Converged in $iteration steps!", big = true)
                return
            }
            // The recolouring is the sound of this step: louder the more
            // points changed side.
            val share = moved.toFloat() / n
            env.emit(EventKind.CROSS, 0.35f + 0.5f * (1f - share), 0f, (0.3f + share).coerceAtMost(0.9f))
            // The update, computed now and walked to over the rest of the pass.
            val sumX = FloatArray(k); val sumY = FloatArray(k); val cnt = IntArray(k)
            for (i in 0 until n) { val j = owner[i]; sumX[j] += px[i]; sumY[j] += py[i]; cnt[j]++ }
            for (j in 0 until k) {
                fromX[j] = cx[j]; fromY[j] = cy[j]
                toX[j] = if (cnt[j] > 0) sumX[j] / cnt[j] else cx[j]
                toY[j] = if (cnt[j] > 0) sumY[j] / cnt[j] else cy[j]
            }
            assigning = false
            phaseStart += assignShare
            return
        }
        val u = ((vclock - phaseStart) / (iterSeconds - assignShare)).coerceIn(0f, 1f)
        if (u < 0f) return
        val e = u * u * (3f - 2f * u)
        for (j in 0 until k) {
            cx[j] = fromX[j] + (toX[j] - fromX[j]) * e
            cy[j] = fromY[j] + (toY[j] - fromY[j]) * e
        }
        if (u >= 1f) {
            iteration++
            if (trailN < MAX_ITER + 2) {
                for (j in 0 until k) { trailX[j][trailN] = cx[j]; trailY[j][trailN] = cy[j] }
                trailN++
            }
            // Each centroid that travelled sounds as it settles, at its own
            // pitch and where it is.
            for (j in 0 until k) {
                val dist = hypot(toX[j] - fromX[j], toY[j] - fromY[j])
                if (dist > 0.01f) {
                    env.emit(EventKind.HIT, (j + 0.5f) / k, (cx[j] / max(ex, 1e-3f)).coerceIn(-1f, 1f), (0.25f + dist * 2f).coerceAtMost(0.8f))
                }
            }
            assigning = true
            phaseStart += iterSeconds - assignShare
            iterSeconds = passDur[min(iteration, passDur.size - 1)]
        }
    }

    private fun colour(palette: Palette, j: Int): Int = deepTone(palette.at((j + 0.5f) / k * 0.92f))

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val since = if (doneAt >= 0f) clock - doneAt else -1f
        val finale = if (since >= 0f) (since / 0.6f).coerceIn(0f, 1f) else 0f
        drawTerritory(env, g, palette, finale)
        // How far through the recolour ripple of the current pass.
        val ripple = if (!assigning) ((vclock - (phaseStart - ASSIGN_SHARE * iterSeconds)) / (ASSIGN_SHARE * iterSeconds * 0.9f)) else 2f
        g.reserveInstances(n * 3 + k * (MAX_ITER + 10))
        // Spokes: each point's line to the centroid it just joined, fading as
        // the ripple passes. They say what the assignment step is.
        if (finale > 0f) {
            // The fixed point: every point joined to its centre for good, a
            // star per cluster, eased in once and held.
            for (i in 0 until n) {
                val j = owner[i]
                if (j >= 0) g.line(px[i], py[i], cx[j], cy[j], 0.0018f, colour(palette, j), 0.2f * finale)
            }
        } else if (!assigning && ripple < 1.6f) {
            for (i in 0 until n) {
                val j = owner[i]
                val a = (1f - abs(ripple - delay[i]) * 2.2f).coerceIn(0f, 1f)
                if (a <= 0.02f) continue
                g.line(px[i], py[i], cx[j], cy[j], 0.0022f, colour(palette, j), 0.32f * a)
            }
        }
        val r = POINT_R
        for (i in 0 until n) {
            val j = owner[i]
            val pj = prevOwner[i]
            val t = if (assigning || ripple >= 2f) 1f else ((ripple - delay[i]) * 6f).coerceIn(0f, 1f)
            val now = if (j >= 0) colour(palette, j) else UNASSIGNED
            val before = if (pj >= 0) colour(palette, pj) else UNASSIGNED
            var c = if (t >= 1f) now else Palette.lerp(before, now, t)
            if (finale > 0f) c = Palette.brighten(c, 0.12f * finale)
            g.disc(px[i], py[i], r, c)
            // A point that changed sides this pass is ringed once the ripple
            // reaches it: late in a run these few are the whole story.
            if (!assigning && pj >= 0 && pj != j && t > 0f && finale <= 0f) {
                g.ring(px[i], py[i], r * 2.3f, 0.0028f, Palette.brighten(now, 0.2f), alpha = 0.9f * t)
            }
        }
        // Each centroid's road: every place it has stood, faint.
        for (j in 0 until k) {
            val col = colour(palette, j)
            // Where it was dropped: a small hollow ring, the road's origin.
            g.ring(trailX[j][0], trailY[j][0], 0.011f, 0.0025f, col, alpha = 0.55f)
            for (s in 1 until trailN) {
                g.line(trailX[j][s - 1], trailY[j][s - 1], trailX[j][s], trailY[j][s], 0.004f, col, 0.45f)
                g.disc(trailX[j][s - 1], trailY[j][s - 1], 0.006f, col, 0.6f)
            }
            if (trailN >= 1) g.line(trailX[j][trailN - 1], trailY[j][trailN - 1], cx[j], cy[j], 0.004f, col, 0.45f)
        }
        val intro = (clock / 0.5f).coerceIn(0f, 1f)
        for (j in 0 until k) {
            val col = Palette.brighten(colour(palette, j), 0.25f)
            val rr = CENTROID_R * (0.4f + 0.6f * intro)
            if (finale > 0f) {
                // The fixed point: a halo eases in once and stays.
                g.glow(cx[j], cy[j], rr * 3.2f, col, 0.45f * finale)
            }
            g.disc(cx[j], cy[j], rr + 0.008f, Palette.BG, 0.9f)
            g.ring(cx[j], cy[j], rr, 0.0075f, col)
            g.rect(cx[j], cy[j], rr * 0.55f, 0.0028f, col)
            g.rect(cx[j], cy[j], 0.0028f, rr * 0.55f, col)
        }
    }

    /**
     * Every point of the field shaded by its nearest centroid, dark, with the
     * exact borders drawn as lines over the tile steps.
     */
    private fun drawTerritory(env: SimEnv, g: G, palette: Palette, finale: Float) {
        val halfW = env.halfWidth
        val rows = TILE_ROWS
        val cols = max(8, (rows * halfW).toInt())
        if (cols != tileCols || rows != tileRows) { tileCols = cols; tileRows = rows; tiles = IntArray(cols * rows) }
        if (iteration == 0 && assigning) return
        val dx = 2f * halfW / cols; val dy = 2f / rows
        g.reserveInstances(cols * rows + k * 8)
        val lit = 0.11f + 0.07f * finale
        val shades = IntArray(k) { Palette.lerp(Palette.BG, colour(palette, it), lit) }
        for (rr in 0 until rows) {
            val y = -1f + (rr + 0.5f) * dy
            for (c in 0 until cols) {
                val x = -halfW + (c + 0.5f) * dx
                g.rect(x, y, dx * 0.6f, dy * 0.6f, shades[nearest(x, y, cx, cy)])
            }
        }
        drawBorders(g, halfW, palette, finale)
    }

    private var qx1 = FloatArray(32); private var qy1 = FloatArray(32)
    private var qx2 = FloatArray(32); private var qy2 = FloatArray(32)
    private var qt1 = IntArray(32); private var qt2 = IntArray(32)

    /** Each centroid's cell, the screen clipped by its bisectors; each border drawn once. */
    private fun drawBorders(g: G, halfW: Float, palette: Palette, finale: Float) {
        for (i in 0 until k) {
            var ax = qx1; var ay = qy1; var at = qt1
            var bx = qx2; var by = qy2; var bt = qt2
            var m = 4
            ax[0] = -halfW; ay[0] = -1f; ax[1] = halfW; ay[1] = -1f
            ax[2] = halfW; ay[2] = 1f; ax[3] = -halfW; ay[3] = 1f
            for (q in 0 until 4) at[q] = -1
            for (j in 0 until k) {
                if (j == i || m == 0) continue
                val nx = cx[j] - cx[i]; val ny = cy[j] - cy[i]
                if (abs(nx) + abs(ny) < 1e-6f) continue
                val mx = (cx[i] + cx[j]) * 0.5f; val my = (cy[i] + cy[j]) * 0.5f
                var o = 0
                for (q in 0 until m) {
                    val q2 = (q + 1) % m
                    val da = (ax[q] - mx) * nx + (ay[q] - my) * ny
                    val db = (ax[q2] - mx) * nx + (ay[q2] - my) * ny
                    if (o + 2 >= bx.size) break
                    if (da <= 0f) { bx[o] = ax[q]; by[o] = ay[q]; bt[o] = at[q]; o++ }
                    if ((da <= 0f) != (db <= 0f)) {
                        val f = da / (da - db)
                        bx[o] = ax[q] + (ax[q2] - ax[q]) * f
                        by[o] = ay[q] + (ay[q2] - ay[q]) * f
                        bt[o] = if (da <= 0f) j else at[q]
                        o++
                    }
                }
                val sx = ax; ax = bx; bx = sx
                val sy = ay; ay = by; by = sy
                val st = at; at = bt; bt = st
                m = o
            }
            for (q in 0 until m) {
                val j = at[q]
                if (j <= i) continue
                val q2 = (q + 1) % m
                val edge = Palette.lerp(colour(palette, i), colour(palette, j), 0.5f)
                g.line(ax[q], ay[q], ax[q2], ay[q2], 2f / TILE_ROWS * 1.3f, Palette.BG, 0.9f)
                g.line(ax[q], ay[q], ax[q2], ay[q2], 0.0035f, edge, 0.45f + 0.3f * finale)
            }
        }
    }

    override fun frameHalfWidth(env: SimEnv): Float = ex + CENTROID_R + 0.02f

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && clock - doneAt > 3f

    override fun counters(): List<Pair<String, String>> = listOf(
        "pass" to "$iteration",
        "points moved" to "$moved",
        "spread" to String.format(java.util.Locale.US, "%.2f", sqrt(inertia / max(1, n)) * 10f)
    )

    companion object {
        private const val MAX_ITER = 40
        private const val RUN_SECONDS = 9.5f
        private const val INTRO_SECONDS = 0.9f
        private const val MIN_ITER_SECONDS = 0.28f
        private const val MAX_ITER_SECONDS = 2.4f
        private const val ASSIGN_SHARE = 0.42f
        private const val TILE_ROWS = 140
        private const val POINT_R = 0.0085f
        private const val CENTROID_R = 0.034f
        private const val UNASSIGNED = 0xFF5A6070.toInt()

        fun def(): SimDef = SimDef(
            id = "algorithms/k-means",
            title = "k-means",
            shelf = Shelf.ALGORITHMS,
            family = "Machine learning & optimization",
            blurb = "Join the nearest centre, move to the middle, repeat until nobody switches.",
            formats = listOf(TrapFormat.COLOR_BATTLE, TrapFormat.SNAP, TrapFormat.GUESS),
            heroLabel = "New points",
            defaultPalette = "Aurora",
            typicalSeconds = 12f,
            params = listOf(
                Param.int("k", "Clusters", 2, 9, 5, card = Card.DATA),
                Param.int("points", "Points", 40, 2000, 450, card = Card.DATA, logScale = true),
                Param.choice("init", "Start", listOf("Random", "k-means++"), 0,
                    card = Card.DATA, input = ParamInput.TILES),
                Param.float("speed", "Speed", 0.25f, 4f, 1f, card = Card.MOTION, unit = "x", logScale = true)
            ),
            create = { KMeansSim() }
        )
    }
}
