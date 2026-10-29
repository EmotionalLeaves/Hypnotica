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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A Penrose tiling of thick and thin rhombs, grown by deflation.
 *
 * Every rhomb is two Robinson triangles, and each triangle cuts exactly into
 * smaller ones of the same two shapes, scaled down by the golden ratio. Start
 * from a wheel of ten and cut again and again: the plane fills with a tiling
 * that never repeats, yet five-fold stars and suns turn up everywhere in it,
 * and the count of thick rhombs to thin ones closes in on φ = 1.618…
 */
class PenroseTilingSim : Sim {

    private var gens = 7
    /** Per generation: type (0 thin half, 1 thick half), corners A, B, C, parent. */
    private var type = arrayOf<ByteArray>()
    private var tri = arrayOf<FloatArray>()
    private var parent = arrayOf<IntArray>()
    private var dist = arrayOf<FloatArray>()
    private var reach = 1f
    private var builtAspect = -1f

    private var gen = 0
    private var progress = 0f
    private var wait = LEAD_IN
    private var done = false
    private var payoffFrame = false
    private var sinceDone = 0f
    private var bandsSounded = 0

    /** Final generation: for each triangle, the distance of the star it belongs to, or -1. */
    private var starOf = FloatArray(0)
    private var starCount = 0
    private var starReach = 1f
    private var starBands = 0

    override fun setup(env: SimEnv) {
        gens = env.params.i("gens", 7).coerceIn(1, 8)
        build(env)
        gen = 0
        progress = 0f
        wait = LEAD_IN
        done = false
        payoffFrame = false
        sinceDone = 0f
        bandsSounded = 0
        starBands = 0
    }

    private fun build(env: SimEnv) {
        builtAspect = env.aspect
        val hw = env.halfWidth
        reach = sqrt(hw * hw + 1f)
        // The wheel's inner radius covers the screen's corners.
        val r0 = reach * 1.03f / cos(PI / 10.0).toFloat()
        type = Array(gens + 1) { ByteArray(0) }
        tri = Array(gens + 1) { FloatArray(0) }
        parent = Array(gens + 1) { IntArray(0) }
        dist = Array(gens + 1) { FloatArray(0) }
        var t = ByteArray(10)
        var v = FloatArray(60)
        for (i in 0 until 10) {
            var b = (2 * i - 1) * PI / 10.0
            var c = (2 * i + 1) * PI / 10.0
            if (i % 2 == 0) { val s = b; b = c; c = s }
            t[i] = 0
            v[i * 6] = 0f; v[i * 6 + 1] = 0f
            v[i * 6 + 2] = (r0 * cos(b)).toFloat(); v[i * 6 + 3] = (r0 * sin(b)).toFloat()
            v[i * 6 + 4] = (r0 * cos(c)).toFloat(); v[i * 6 + 5] = (r0 * sin(c)).toFloat()
        }
        type[0] = t; tri[0] = v; parent[0] = IntArray(10) { -1 }
        dist[0] = distances(v, 10)
        val inv = (1.0 / PHI).toFloat()
        for (g in 1..gens) {
            val n = t.size
            var count = 0
            for (i in 0 until n) count += if (t[i].toInt() == 0) 2 else 3
            val nt = ByteArray(count)
            val nv = FloatArray(count * 6)
            val np = IntArray(count)
            var k = 0
            fun put(ty: Int, ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, p: Int) {
                nt[k] = ty.toByte(); np[k] = p
                val o = k * 6
                nv[o] = ax; nv[o + 1] = ay; nv[o + 2] = bx; nv[o + 3] = by; nv[o + 4] = cx; nv[o + 5] = cy
                k++
            }
            for (i in 0 until n) {
                val o = i * 6
                val ax = v[o]; val ay = v[o + 1]; val bx = v[o + 2]; val by = v[o + 3]; val cx = v[o + 4]; val cy = v[o + 5]
                if (t[i].toInt() == 0) {
                    // Thin half: cut once, into a thin half and a thick half.
                    val px = ax + (bx - ax) * inv; val py = ay + (by - ay) * inv
                    put(0, cx, cy, px, py, bx, by, i)
                    put(1, px, py, cx, cy, ax, ay, i)
                } else {
                    // Thick half: cut twice, into two thick halves and a thin one.
                    val qx = bx + (ax - bx) * inv; val qy = by + (ay - by) * inv
                    val rx = bx + (cx - bx) * inv; val ry = by + (cy - by) * inv
                    put(1, rx, ry, cx, cy, ax, ay, i)
                    put(1, qx, qy, rx, ry, bx, by, i)
                    put(0, rx, ry, qx, qy, ax, ay, i)
                }
            }
            t = nt; v = nv
            type[g] = nt; tri[g] = nv; parent[g] = np
            dist[g] = distances(nv, count)
        }
        findStars(env)
    }

    private fun distances(v: FloatArray, n: Int): FloatArray = FloatArray(n) { i ->
        val o = i * 6
        hypot((v[o] + v[o + 2] + v[o + 4]) / 3f, (v[o + 1] + v[o + 3] + v[o + 5]) / 3f)
    }

    /**
     * The five-fold stars of the finished tiling: points where five thick
     * rhombs meet at their sharp corners — ten thick halves, each giving 36°.
     */
    private fun findStars(env: SimEnv) {
        val t = type[gens]; val v = tri[gens]
        val n = t.size
        val side = hypot(v[2] - v[0], v[3] - v[1])
        val eps = max(1e-5f, side * 1e-3f)
        fun key(x: Float, y: Float): Long = ((x / eps).roundToLong() shl 32) xor ((y / eps).roundToLong() and 0xffffffffL)
        val counts = HashMap<Long, Int>(n)
        for (i in 0 until n) {
            if (t[i].toInt() != 1) continue
            val o = i * 6
            for (c in 1..2) {
                val k = key(v[o + c * 2], v[o + c * 2 + 1])
                counts[k] = (counts[k] ?: 0) + 1
            }
        }
        starOf = FloatArray(n) { -1f }
        val hw = env.halfWidth
        val centres = HashSet<Long>()
        starReach = 0.1f
        for (i in 0 until n) {
            if (t[i].toInt() != 1) continue
            val o = i * 6
            for (c in 1..2) {
                val x = v[o + c * 2]; val y = v[o + c * 2 + 1]
                val k = key(x, y)
                if ((counts[k] ?: 0) == 10) {
                    val d = hypot(x, y)
                    starOf[i] = if (starOf[i] < 0f) d else min(starOf[i], d)
                    if (abs(x) < hw && abs(y) < 1f && centres.add(k)) starReach = max(starReach, d)
                }
            }
        }
        starCount = centres.size
    }

    // ------------------------------------------------------------ running

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val a = abs(dt)
        // The tiles are laid out to cover the screen's corners, so a screen
        // of another shape needs them laid out again.
        if (env.aspect != builtAspect) build(env)
        val speed = env.params.f("speed", 1f).coerceIn(0.05f, 10f)
        if (done) {
            sinceDone += a
            // The stars light in a wave outward; a note as it passes each band.
            val band = ((sinceDone / STAR_SECONDS) * BANDS).toInt().coerceAtMost(BANDS)
            if (band > starBands) {
                starBands = band
                if (starCount > 0 && band < BANDS) env.emit(EventKind.HIT, 0.55f + 0.4f * band / BANDS, 0f, 0.32f)
            }
            return
        }
        if (wait > 0f) { wait -= a * speed; return }
        val before = progress
        progress = min(1f, progress + a * speed / CUT_SECONDS)
        // A note each time the cutting front passes another ring, rising
        // through the generations.
        val band = (progress * BANDS).toInt()
        if (band > (before * BANDS).toInt() && band < BANDS) {
            env.emit(EventKind.WRITE, ((gen + band.toFloat() / BANDS) / gens).coerceIn(0f, 1f), 0f, 0.3f)
        }
        if (progress >= 1f) {
            gen++
            progress = 0f
            if (gen >= gens) {
                done = true
                payoffFrame = true
            } else {
                wait = HOLD
            }
        }
    }

    /** How far the cut has got at distance d from the centre, 0..1. */
    private fun localCut(d: Float): Float {
        val w = reach * FRONT
        return ((progress * (reach + w) - d) / w).coerceIn(0f, 1f)
    }

    /** Thin rhombs from one end of the palette, thick from the other: two clear hues. */
    private fun colour(pal: Palette, ty: Int): Int = if (ty == 0) pal.at(0.04f) else pal.at(0.64f)

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        val hw = env.halfWidth
        val cutting = !done && progress > 0f
        val show = if (cutting) gen + 1 else min(gen, gens)
        val t = type[show]; val v = tri[show]; val par = parent[show]
        val n = t.size
        val side = hypot(v[2] - v[0], v[3] - v[1])
        val grout = (side * 0.11f).coerceIn(0.003f, 0.009f)
        val lineW = grout * 0.42f
        g.reserveInstances(n * 6)

        val cutOf = FloatArray(n)
        val visible = BooleanArray(n)
        for (i in 0 until n) {
            val o = i * 6
            val minX = min(v[o], min(v[o + 2], v[o + 4])); val maxX = max(v[o], max(v[o + 2], v[o + 4]))
            val minY = min(v[o + 1], min(v[o + 3], v[o + 5])); val maxY = max(v[o + 1], max(v[o + 3], v[o + 5]))
            visible[i] = maxX > -hw && minX < hw && maxY > -1f && minY < 1f
            cutOf[i] = if (cutting && par[i] >= 0) localCut(dist[show - 1][par[i]]) else 1f
        }
        val stars = if (done) sinceDone / STAR_SECONDS else 0f

        // Glass first.
        for (i in 0 until n) {
            if (!visible[i]) continue
            val ty = t[i].toInt()
            val u = smooth(cutOf[i])
            val from = if (cutting) colour(pal, type[show - 1][par[i]].toInt()) else colour(pal, ty)
            var c = Palette.lerp(from, colour(pal, ty), u)
// Deep glass, so the leading carries the pattern; the stars light up
            // out of it once the tiling is finished, and stay lit.
            var lit = 0.34f
            if (done && starOf[i] >= 0f) {
                val s = smooth((stars * (starReach + 0.4f) - starOf[i]) / 0.4f)
                lit += 0.5f * s
                c = Palette.lerp(c, Palette.TEXT_PRIMARY, 0.15f * s)
            }
            fill(g, v, i, Palette.lerp(Palette.BG, c, lit), side)
        }
        // Then the leading: the dark came, and a thin lit edge in it.
        for (pass in 0..1) {
            if (cutting) {
                // The old edges fade as each region is cut; the new ones come in.
                val pt = type[show - 1]; val pv = tri[show - 1]
                for (j in pt.indices) {
                    val u = localCut(dist[show - 1][j])
                    val a = 1f - u * u
                    if (a <= 0.01f) continue
                    edges(g, pv, j, if (pass == 0) Palette.BG else edgeColour(pal, pt[j].toInt()), if (pass == 0) grout * 1.25f else lineW * 1.25f, a, hw)
                }
            }
            for (i in 0 until n) {
                if (!visible[i]) continue
                val a = smooth(cutOf[i])
                if (a <= 0.01f) continue
                edges(g, v, i, if (pass == 0) Palette.BG else edgeColour(pal, t[i].toInt()), if (pass == 0) grout else lineW, a, hw)
            }
        }
    }

    private fun edgeColour(pal: Palette, ty: Int): Int = Palette.lerp(colour(pal, ty), Palette.TEXT_PRIMARY, 0.25f)

    /** The two outer sides of a half-rhomb; the base is the rhomb's own diagonal. */
    private fun edges(g: G, v: FloatArray, i: Int, c: Int, w: Float, a: Float, hw: Float) {
        val o = i * 6
        val ax = v[o]; val ay = v[o + 1]
        if (abs(ax) > hw + 0.3f && abs(v[o + 2]) > hw + 0.3f) return
        g.line(ax, ay, v[o + 2], v[o + 3], w, c, a)
        g.line(ax, ay, v[o + 4], v[o + 5], w, c, a)
    }

    /**
     * A triangle filled with strips parallel to its base. The renderer has no
     * triangle, and a strip's square end overshoots the slanted side by a
     * little; strips are made thin enough that the leading drawn over every
     * edge covers it.
     */
    private fun fill(g: G, v: FloatArray, i: Int, c: Int, side: Float) {
        val o = i * 6
        val ax = v[o]; val ay = v[o + 1]; val bx = v[o + 2]; val by = v[o + 3]; val cx = v[o + 4]; val cy = v[o + 5]
        val mx = (bx + cx) * 0.5f; val my = (by + cy) * 0.5f
        val h = hypot(ax - mx, ay - my)
        val base = hypot(cx - bx, cy - by)
        // Sharp base angles (the thick halves') need finer strips.
        val thick = base > side * 1.2f
        // No low cap: the first generations' few big tiles need many strips, or
        // the overshoot shows past the leading as a row of dots.
        val k = (h / (if (thick) 0.004f else 0.012f)).toInt().coerceIn(1, 160)
        val rot = atan2(cy - by, cx - bx)
        val band = h / k
        for (j in 0 until k) {
            val f = (j + 0.5f) / k
            val px = mx + (ax - mx) * f; val py = my + (ay - my) * f
            val half = base * (1f - f) * 0.5f
            g.rect(px, py, half, band * 0.75f, c, rot)
        }
    }

    private fun smooth(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = done && sinceDone > HOLD_AFTER

    override fun counters(): List<Pair<String, String>> {
        val g = min(gen, gens)
        val t = type[g]
        var thin = 0; var thick = 0
        for (b in t) if (b.toInt() == 0) thin++ else thick++
        return listOf(
            "generation" to "$g / $gens",
            "thick : thin" to if (thin > 0) String.format(java.util.Locale.US, "%.4f", thick.toFloat() / thin) else "—",
            "five-fold stars" to if (done) starCount.toString() else "—"
        )
    }

    companion object {
        private const val PHI = 1.618033988749895
        private const val LEAD_IN = 0.9f
        private const val CUT_SECONDS = 1.45f
        private const val HOLD = 0.35f
        /** Width of the cutting front, as a share of the screen's reach. */
        private const val FRONT = 0.35f
        private const val BANDS = 6
        private const val STAR_SECONDS = 1.8f
        private const val HOLD_AFTER = 4f

        fun def(): SimDef = SimDef(
            id = "math/penrose-tiling",
            title = "Penrose tiling",
            shelf = Shelf.MATH,
            family = "Geometry & topology",
            blurb = "Two rhombs, cut again and again by the golden ratio: a pattern that never repeats.",
            formats = listOf(TrapFormat.GROW, TrapFormat.PERFECT_FIT),
            heroLabel = "Cut again",
            defaultPalette = "Aurora",
            typicalSeconds = 19f,
            params = listOf(
                Param.int("gens", "Generations", 1, 7, 7, card = Card.DATA),
                Param.float("speed", "Speed", 0.3f, 4f, 1f, card = Card.MOTION, logScale = true)
            ),
            create = { PenroseTilingSim() }
        )
    }
}
