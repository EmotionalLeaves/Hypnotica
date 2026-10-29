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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The convex hull of a point cloud: the shape a string takes pulled tight
 * round the outside.
 *
 * Gift wrapping (Jarvis march) holds the string at the last corner and swings
 * its free end across every other point, keeping whichever lies furthest
 * round, until it lies flat against the next corner. Graham scan sorts the
 * points by angle round the lowest one and walks them in that order, keeping
 * a chain that only ever turns left; a right turn means the last corner was
 * inside after all, and the chain snaps back past it — the string tightening.
 * Either way the payoff is the string closing on its first corner.
 */
class ConvexHullSim : Sim {

    private var n = 70
    private var px = FloatArray(0)
    private var py = FloatArray(0)
    private var graham = false
    private var ex = 0.4f
    private var ey = 0.8f

    // The recorded run: what each action was and when it plays.
    private var kind = IntArray(0)
    private var argA = IntArray(0)
    private var argB = IntArray(0)
    private var argC = IntArray(0)
    private var at = FloatArray(0)
    private var count = 0

    // Live state rebuilt by playing the actions.
    private var played = 0
    private var hull = IntArray(0)
    private var hullN = 0
    private var cur = -1
    private var cand = -1
    private var probe = -1
    private var probeAt = -9f
    private var popped = -1
    private var poppedAt = -9f
    private var pivot = -1
    private var order = IntArray(0)
    private var snapAt = -9f
    private val onHull = BooleanArray(MAX_N)
    private var tests = 0
    private var clock = 0f
    private var vclock = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var sinceSound = 1f
    private var runSeconds = 9f

    override fun setup(env: SimEnv) {
        val p = env.params
        n = p.i("points", 70).coerceIn(8, MAX_N)
        graham = p.choiceIndex("method") == 1
        ex = env.halfWidth * 0.84f
        ey = 0.8f
        val shape = p.choiceIndex("shape")
        px = FloatArray(n); py = FloatArray(n)
        for (i in 0 until n) {
            when (shape) {
                1 -> {
                    // Even over an oval: many points near the rim, a long hull.
                    val r = sqrt(env.rng.nextFloat()); val a = env.rng.range(0f, 6.2832f)
                    px[i] = r * cos(a) * ex; py[i] = r * sin(a) * ey
                }
                2 -> { px[i] = env.rng.range(-ex, ex); py[i] = env.rng.range(-ey, ey) }
                else -> {
                    px[i] = (env.rng.normal() * ex * 0.42f).coerceIn(-ex, ex)
                    py[i] = (env.rng.normal() * ey * 0.42f).coerceIn(-ey, ey)
                }
            }
        }
        kind = IntArray(16); argA = IntArray(16); argB = IntArray(16); argC = IntArray(16); at = FloatArray(16)
        count = 0
        if (graham) recordGraham() else recordJarvis()
        hull = IntArray(n + 2); hullN = 0
        onHull.fill(false)
        played = 0; cur = -1; cand = -1; probe = -1; probeAt = -9f; popped = -1; poppedAt = -9f
        snapAt = -9f; tests = 0
        clock = 0f; vclock = 0f; doneAt = -1f; payoffFrame = false; sinceSound = 1f
    }

    private fun add(k: Int, a: Int, b: Int, c: Int) {
        if (count == kind.size) {
            val m = kind.size * 2
            kind = kind.copyOf(m); argA = argA.copyOf(m); argB = argB.copyOf(m); argC = argC.copyOf(m); at = at.copyOf(m)
        }
        kind[count] = k; argA[count] = a; argB[count] = b; argC[count] = c
        count++
    }

    /** Twice the signed area of a-b-c: positive for a left (anticlockwise) turn. */
    private fun cross(a: Int, b: Int, c: Int): Float =
        (px[b] - px[a]) * (py[c] - py[a]) - (py[b] - py[a]) * (px[c] - px[a])

    private fun d2(a: Int, b: Int): Float { val dx = px[a] - px[b]; val dy = py[a] - py[b]; return dx * dx + dy * dy }

    private fun recordJarvis() {
        var start = 0
        for (i in 1 until n) if (px[i] < px[start] || (px[i] == px[start] && py[i] < py[start])) start = i
        pivot = start
        var c = start
        val edgeStarts = ArrayList<Int>()
        var guard = 0
        do {
            edgeStarts.add(count)
            var best = if (c == 0) 1 else 0
            add(K_FROM, c, best, 0)
            for (q in 0 until n) {
                if (q == c || q == best) continue
                // Anticlockwise wrap: q replaces the candidate when it lies to
                // the right of the string, or on it but further out.
                val x = cross(c, best, q)
                val take = x < 0f || (x == 0f && d2(c, q) > d2(c, best))
                if (take) best = q
                add(K_TEST, c, q, if (take) 1 else 0)
            }
            add(K_EDGE, c, best, 0)
            c = best
            guard++
        } while (c != start && guard <= n)
        // Every edge gets the same share of the run, so a big hull wraps
        // briskly and a small one is followed sweep by sweep.
        val edges = edgeStarts.size
        val te = (RUN_SECONDS / edges).coerceIn(0.22f, 1.6f)
        runSeconds = te * edges
        for (e in 0 until edges) {
            val from = edgeStarts[e]
            val to = if (e + 1 < edges) edgeStarts[e + 1] else count
            val t0 = INTRO_SECONDS + e * te
            val m = to - from
            for (i in from until to) {
                val j = i - from
                at[i] = when (kind[i]) {
                    K_FROM -> t0
                    K_EDGE -> t0 + te * 0.86f
                    else -> t0 + te * (0.06f + 0.72f * j / max(1, m - 1))
                }
            }
        }
    }

    private fun recordGraham() {
        var p0 = 0
        for (i in 1 until n) if (py[i] < py[p0] || (py[i] == py[p0] && px[i] < px[p0])) p0 = i
        pivot = p0
        val rest = (0 until n).filter { it != p0 }
            .sortedWith(compareBy<Int> { atan2(py[it] - py[p0], px[it] - px[p0]) }.thenBy { d2(p0, it) })
        order = rest.toIntArray()
        val stack = IntArray(n + 1)
        var sp = 0
        stack[sp++] = p0
        add(K_PUSH, p0, 0, 0)
        for (q in rest) {
            while (sp >= 2 && cross(stack[sp - 2], stack[sp - 1], q) <= 0f) {
                add(K_POP, stack[sp - 1], q, 0)
                sp--
            }
            stack[sp++] = q
            add(K_PUSH, q, 0, 0)
        }
        add(K_EDGE, stack[sp - 1], p0, 0)
        // One push or pop at a time, slow enough at the start to read and
        // quickening, as every new-wave search here does.
        val pace = AlgoPace(count, RUN_SECONDS, 3.5f)
        for (i in 0 until count) at[i] = INTRO_SECONDS + SORT_SECONDS + pace.timeOf(i)
        runSeconds = RUN_SECONDS + SORT_SECONDS
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        clock += h
        sinceSound += h
        if (doneAt >= 0f) return
        vclock += h * env.params.f("speed", 1f).coerceIn(0.1f, 8f)
        var sound = 0
        var soundPoint = -1
        while (played < count && at[played] <= vclock) {
            val i = played++
            val a = argA[i]; val b = argB[i]
            when (kind[i]) {
                K_FROM -> {
                    cur = a; cand = b; probe = -1
                    if (hullN == 0) { hull[hullN++] = a; onHull[a] = true }
                }
                K_TEST -> {
                    tests++
                    probe = b; probeAt = clock
                    if (argC[i] == 1) { cand = b; if (sound < 1) { sound = 1; soundPoint = b } }
                    else if (sound < 1) { sound = 0; soundPoint = b }
                }
                K_PUSH -> {
                    hull[hullN++] = a; onHull[a] = true
                    tests++
                    if (sound < 2) { sound = 2; soundPoint = a }
                }
                K_POP -> {
                    hullN--; onHull[a] = false
                    tests++
                    popped = a; poppedAt = clock
                    sound = 3; soundPoint = a
                }
                K_EDGE -> {
                    snapAt = clock
                    if (graham) {
                        doneAt = clock; payoffFrame = true
                    } else if (b == pivot) {
                        cur = -1; cand = -1; probe = -1
                        doneAt = clock; payoffFrame = true
                    } else {
                        hull[hullN++] = b; onHull[b] = true
                        cur = b; cand = -1; probe = -1
                        sound = 4; soundPoint = b
                    }
                }
            }
        }
        if (doneAt >= 0f || soundPoint < 0) return
        // A corner fixed always sounds; the sweep's tests tick at most thirty
        // times a second, so a cloud of hundreds is a texture, not a wash.
        val pan = (px[soundPoint] / max(ex, 1e-3f)).coerceIn(-1f, 1f)
        val pitch = (atan2(py[soundPoint], px[soundPoint]) / 6.2832f + 0.5f)
        when (sound) {
            4 -> { env.emit(EventKind.HIT, (0.25f + 0.04f * hullN).coerceAtMost(0.92f), pan, 0.7f); sinceSound = 0f }
            3 -> { env.emit(EventKind.DEATH, 0.3f, pan, 0.5f); sinceSound = 0f }
            else -> if (sinceSound >= 1f / 30f) {
                sinceSound = 0f
                when (sound) {
                    2 -> env.emit(EventKind.WRITE, pitch, pan, 0.45f)
                    1 -> env.emit(EventKind.STEP, pitch, pan, 0.35f)
                    else -> env.emit(EventKind.COMPARE, pitch, pan, 0.18f)
                }
            }
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val since = if (doneAt >= 0f) clock - doneAt else -1f
        val finale = if (since >= 0f) (since / 0.7f).coerceIn(0f, 1f) else 0f
        val str = palette.bright(STRING_T, 0.12f)
        g.reserveInstances(n * 3 + 64)
        if (finale > 0f) drawFill(g, palette, finale)
        // Graham's sort: a ray swept once round the pivot in angle order
        // before the scan, the order the scan will visit the points in.
        if (graham && doneAt < 0f && vclock > INTRO_SECONDS * 0.5f && vclock < INTRO_SECONDS + SORT_SECONDS && order.isNotEmpty()) {
            val u = ((vclock - INTRO_SECONDS * 0.5f) / (INTRO_SECONDS * 0.5f + SORT_SECONDS)).coerceIn(0f, 1f)
            val q = order[(u * (order.size - 1)).toInt()]
            g.line(px[pivot], py[pivot], px[q], py[q], 0.003f, Palette.TEXT_SECONDARY, 0.5f)
        }
        val intro = (clock / 0.6f).coerceIn(0f, 1f)
        for (i in 0 until n) {
            if (onHull[i]) continue
            // Inside points recede once the hull is closed: they never mattered.
            val a = (0.85f - 0.4f * finale) * intro
            g.disc(px[i], py[i], POINT_R, POINT_C, a)
        }
        // The probe: the string's free end tried against one point.
        if (probe >= 0 && cur >= 0 && doneAt < 0f && clock - probeAt < 0.25f) {
            g.line(px[cur], py[cur], px[probe], py[probe], 0.0025f, Palette.TEXT_SECONDARY, 0.45f)
        }
        // The string so far.
        val w = STRING_W
        for (s in 1 until hullN) {
            g.line(px[hull[s - 1]], py[hull[s - 1]], px[hull[s]], py[hull[s]], w, str)
        }
        if (doneAt >= 0f && hullN >= 2) {
            g.line(px[hull[hullN - 1]], py[hull[hullN - 1]], px[hull[0]], py[hull[0]], w, str)
        }
        // Jarvis: the free end, swinging to the current best.
        if (!graham && cur >= 0 && cand >= 0 && doneAt < 0f) {
            g.line(px[cur], py[cur], px[cand], py[cand], STRING_W * 0.7f, str, 0.7f)
            g.ring(px[cand], py[cand], POINT_R * 2.2f, 0.004f, str, alpha = 0.8f)
        }
        // Graham: the corner just found to be inside, struck off in red.
        if (graham && popped >= 0 && clock - poppedAt < 0.4f && doneAt < 0f) {
            val f = 1f - (clock - poppedAt) / 0.4f
            g.disc(px[popped], py[popped], POINT_R * 1.8f, Palette.DANGER, f)
        }
        for (s in 0 until hullN) {
            val v = hull[s]
            val newest = s == hullN - 1 && doneAt < 0f
            val c = if (newest) Palette.brighten(str, 0.2f) else str
            if (finale > 0f) g.glow(px[v], py[v], 0.05f, str, 0.35f * finale)
            g.disc(px[v], py[v], POINT_R * (if (newest) 1.8f else 1.5f), c)
        }
        if (pivot >= 0) g.ring(px[pivot], py[pivot], POINT_R * 2.6f, 0.004f, Palette.TEXT_PRIMARY, alpha = 0.75f * intro)
    }

    /** The finished hull's inside, tinted tile by tile (a convex test per tile). */
    private fun drawFill(g: G, palette: Palette, finale: Float) {
        if (hullN < 3) return
        var minX = 9f; var maxX = -9f; var minY = 9f; var maxY = -9f
        for (s in 0 until hullN) { val v = hull[s]; minX = min(minX, px[v]); maxX = max(maxX, px[v]); minY = min(minY, py[v]); maxY = max(maxY, py[v]) }
        val step = 0.02f
        val tint = Palette.lerp(Palette.BG, palette.at(STRING_T), 0.13f)
        var y = minY + step * 0.5f
        while (y < maxY) {
            var x = minX + step * 0.5f
            // One run of tiles per row: a convex shape is one span wide.
            var x0 = Float.NaN; var x1 = Float.NaN
            while (x < maxX) {
                if (inside(x, y)) { if (x0.isNaN()) x0 = x; x1 = x }
                x += step
            }
            if (!x0.isNaN()) g.rect((x0 + x1) * 0.5f, y, (x1 - x0) * 0.5f + step * 0.5f, step * 0.55f, tint, alpha = finale)
            y += step
        }
    }

    private fun inside(x: Float, y: Float): Boolean {
        for (s in 0 until hullN) {
            val a = hull[s]; val b = hull[(s + 1) % hullN]
            val c = (px[b] - px[a]) * (y - py[a]) - (py[b] - py[a]) * (x - px[a])
            if (c < 0f) return false
        }
        return true
    }

    override fun frameHalfWidth(env: SimEnv): Float = ex + 0.03f

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && clock - doneAt > 3f

    override fun counters(): List<Pair<String, String>> = listOf(
        "turn tests" to String.format(java.util.Locale.US, "%,d", tests),
        "corners" to "$hullN",
        "points" to "$n"
    )

    companion object {
        const val MAX_N = 600
        private const val K_FROM = 0
        private const val K_TEST = 1
        private const val K_EDGE = 2
        private const val K_PUSH = 3
        private const val K_POP = 4
        private const val RUN_SECONDS = 8.5f
        private const val INTRO_SECONDS = 0.8f
        private const val SORT_SECONDS = 0.9f
        private const val STRING_T = 0.1f
        private const val STRING_W = 0.0065f
        private const val POINT_R = 0.0085f
        private const val POINT_C = 0xFF8A93A6.toInt()

        fun def(): SimDef = SimDef(
            id = "algorithms/convex-hull",
            title = "Convex hull",
            shelf = Shelf.ALGORITHMS,
            family = "Geometry",
            blurb = "Pull a string tight round the outside. Only the corners touch it.",
            formats = listOf(TrapFormat.PERFECT_FIT, TrapFormat.SNAP, TrapFormat.GUESS),
            heroLabel = "New points",
            defaultPalette = "Ember",
            typicalSeconds = 12f,
            params = listOf(
                Param.choice("method", "Method", listOf("Gift wrapping", "Graham scan"), 0,
                    card = Card.DATA, input = ParamInput.TILES),
                Param.int("points", "Points", 8, MAX_N, 70, card = Card.DATA, logScale = true),
                Param.choice("shape", "Cloud", listOf("Cluster", "Oval", "Box"), 0,
                    card = Card.DATA, input = ParamInput.TILES),
                Param.float("speed", "Speed", 0.25f, 4f, 1f, card = Card.MOTION, unit = "x", logScale = true)
            ),
            create = { ConvexHullSim() }
        )
    }
}
