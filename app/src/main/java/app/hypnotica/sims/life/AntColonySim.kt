package app.hypnotica.sims.life

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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The double-bridge experiment (Goss, Deneubourg and colleagues, 1989): a
 * nest and a food source joined by two bridges, one short and one winding.
 * No ant knows which is shorter. Each lays pheromone wherever it walks, and at
 * each fork picks a bridge with Deneubourg's choice rule — the odds of a
 * bridge going as (k + its pheromone)², against the other's. At first the
 * colony splits about evenly. But an ant that took the short bridge is back
 * sooner, marking it twice while the long way is still being walked once, so
 * the short bridge's scent grows faster, draws more ants, grows faster
 * still — and the colony converges on the shortest path without anyone
 * measuring anything.
 *
 * The payoff is the moment nearly every ant is choosing the short bridge.
 */
class AntColonySim : Sim {

    // The two bridges, sampled evenly by arc length, from the nest-side fork
    // (index 0) to the food-side fork.
    private val bx = Array(2) { FloatArray(SAMPLES + 1) }
    private val by = Array(2) { FloatArray(SAMPLES + 1) }
    private val len = FloatArray(2)
    private val scent = Array(2) { FloatArray(SAMPLES + 1) }

    private var nestX = 0f; private var nestY = 0f
    private var foodX = 0f; private var foodY = 0f
    private var forkAX = 0f; private var forkAY = 0f
    private var forkBX = 0f; private var forkBY = 0f
    private var trunkA = 0f   // nest to the near fork
    private var trunkB = 0f   // far fork to the food

    private var n = 0
    private var spawned = 0
    private var spawnCarry = 0f
    // Each ant: which bridge, how far from the nest along its route, which way.
    private var route = IntArray(0)
    private var dist = FloatArray(0)
    private var homeward = BooleanArray(0)
    private var side = FloatArray(0)     // its own lane offset, so they are not one file
    private var legs = FloatArray(0)
    private var excite = FloatArray(0)   // how short its last trip out was, 1 for the short bridge

    /** The last choices, newest at [recentHead]: 0 short, 1 long. */
    private val recent = IntArray(WINDOW)
    private var recentHead = 0
    private var choices = 0
    private var shortShare = 0.5f

    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var delivered = 0
    private var noteCarry = 0f
    private var heldNote = -1
    private var heldPan = 0f
    private var halfW = 0.46f
    private var wide = false

    override fun setup(env: SimEnv) {
        halfW = env.halfWidth
        wide = halfW > 0.9f
        layout(env.params.i("detour", 3).coerceIn(1, 5))
        n = env.params.i("ants", 120).coerceIn(10, MAX_ANTS)
        route = IntArray(n); dist = FloatArray(n); homeward = BooleanArray(n)
        side = FloatArray(n) { env.rng.range(-1f, 1f) }
        legs = FloatArray(n) { env.rng.range(0.9f, 1.1f) }
        excite = FloatArray(n) { 1f }
        for (b in 0..1) scent[b].fill(0f)
        spawned = 0
        spawnCarry = 0f
        recent.fill(-1); recentHead = 0; choices = 0; shortShare = 0.5f
        elapsed = 0f
        doneAt = -1f
        payoffFrame = false
        delivered = 0
        noteCarry = 0f
        heldNote = -1
    }

    /**
     * Where everything goes. The colony runs up the screen on a phone and
     * across it on a wide one; in both, the short bridge is a gentle arc and
     * the long one winds back and forth [loops] times on the other side.
     */
    private fun layout(loops: Int) {
        val along: Float
        val across: Float
        if (wide) { along = halfW * 0.86f; across = 0.66f } else { along = 0.8f; across = halfW - 0.07f }
        // Local frame: u runs nest (−1) to food (+1), v across.
        fun put(u: Float, v: Float, out: FloatArray) {
            if (wide) { out[0] = u * along; out[1] = -v * across - 0.04f }
            else { out[0] = v * across; out[1] = u * along - 0.05f }
        }
        val p = FloatArray(2)
        put(-1f, 0f, p); nestX = p[0]; nestY = p[1]
        put(1f, 0f, p); foodX = p[0]; foodY = p[1]
        put(-FORK, 0f, p); forkAX = p[0]; forkAY = p[1]
        put(FORK, 0f, p); forkBX = p[0]; forkBY = p[1]
        trunkA = hypot(forkAX - nestX, forkAY - nestY)
        trunkB = hypot(foodX - forkBX, foodY - forkBY)
        // Densely, then resampled to even spacing.
        val fine = 2000
        val fx = FloatArray(fine + 1); val fy = FloatArray(fine + 1)
        for (b in 0..1) {
            for (i in 0..fine) {
                val t = i / fine.toFloat()
                val u = -FORK + 2f * FORK * t
                val env = sin(PI.toFloat() * t).coerceAtLeast(0f)
                val v = if (b == 0) -SHORT_BOW * env
                // Out to the far side and back and forth across it: the
                // envelope keeps both ends on the forks.
                else sqrt(env) * (LONG_MID + LONG_SWING * cos(2f * PI.toFloat() * loops * t + PI.toFloat()))
                put(u, v, p); fx[i] = p[0]; fy[i] = p[1]
            }
            resample(fx, fy, fine, bx[b], by[b])
            var l = 0f
            for (i in 0 until SAMPLES) l += hypot(bx[b][i + 1] - bx[b][i], by[b][i + 1] - by[b][i])
            len[b] = l
        }
    }

    private fun resample(fx: FloatArray, fy: FloatArray, fine: Int, ox: FloatArray, oy: FloatArray) {
        val cum = FloatArray(fine + 1)
        for (i in 1..fine) cum[i] = cum[i - 1] + hypot(fx[i] - fx[i - 1], fy[i] - fy[i - 1])
        val total = cum[fine]
        var j = 0
        for (s in 0..SAMPLES) {
            val target = total * s / SAMPLES
            while (j < fine - 1 && cum[j + 1] < target) j++
            val seg = (cum[j + 1] - cum[j]).coerceAtLeast(1e-9f)
            val f = ((target - cum[j]) / seg).coerceIn(0f, 1f)
            ox[s] = fx[j] + (fx[j + 1] - fx[j]) * f
            oy[s] = fy[j] + (fy[j + 1] - fy[j]) * f
        }
    }

    private fun hypot(a: Float, b: Float): Float = sqrt(a * a + b * b)

    private fun routeLen(b: Int): Float = trunkA + len[b] + trunkB

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        val speed = SPEED * env.params.f("pace", 1f)
        // The colony streams out of the nest, not all at once.
        spawnCarry += h * SPAWN_PER_SECOND
        while (spawnCarry >= 1f && spawned < n) {
            spawnCarry -= 1f
            route[spawned] = -1; dist[spawned] = 0f; homeward[spawned] = false
            spawned++
        }
        if (spawned >= n) spawnCarry = 0f
        var arrivals = 0
        var lastBridge = 0
        var lastPan = 0f
        for (i in 0 until spawned) {
            val step = speed * legs[i] * h
            if (!homeward[i]) {
                val before = dist[i]
                dist[i] += step
                // At the near fork: pick a bridge.
                if (before < trunkA && dist[i] >= trunkA) route[i] = choose(env, 0)
                if (route[i] >= 0 && dist[i] >= routeLen(route[i])) {
                    // At the food: turn for home with a crumb.
                    dist[i] = routeLen(route[i])
                    homeward[i] = true
                    excite[i] = routeLen(0) / routeLen(route[i])
                    arrivals++
                    lastBridge = route[i]
                    lastPan = (foodX / halfW).coerceIn(-1f, 1f)
                }
            } else {
                val r = route[i]
                val fork = trunkA + len[r]
                val before = dist[i]
                dist[i] -= step
                // At the far fork, coming back: pick again. The distance is
                // carried over to the new bridge's own length.
                if (before > fork && dist[i] <= fork) {
                    val nb = choose(env, 1)
                    dist[i] = trunkA + len[nb] - (fork - dist[i])
                    route[i] = nb
                }
                if (dist[i] <= 0f) {
                    dist[i] = 0f
                    homeward[i] = false
                    route[i] = -1
                    delivered++
                }
            }
            // Pheromone, wherever on a bridge it is: a little on the way out,
            // and on the way home with food, more the shorter the trip that
            // found it — Dorigo's ant-colony-optimisation rule, after real
            // foragers that mark the way to a better find more heavily. Marked
            // the same both ways, the bridges' scent only follows how many
            // ants take each, and the colony as often settled on the long one.
            val r = route[i]
            if (r >= 0) {
                val amount = if (homeward[i]) HOME_MARK * excite[i] else OUT_MARK
                val s = dist[i] - trunkA
                if (s in 0f..len[r]) {
                    val k = (s / len[r] * SAMPLES).toInt().coerceIn(0, SAMPLES)
                    // Per unit of ground covered, so a long bridge is not
                    // marked more just for being sampled more coarsely.
                    scent[r][k] += amount * step / (len[r] / SAMPLES)
                }
            }
        }
        // Evaporation, and a little spreading along the trail.
        val keep = 1f - EVAPORATE * h
        for (b in 0..1) {
            val sc = scent[b]
            var prev = sc[0]
            for (k in 0..SAMPLES) {
                val cur = sc[k]
                val nxt = if (k < SAMPLES) sc[k + 1] else cur
                sc[k] = (cur * (1f - 2f * SPREAD) + (prev + nxt) * SPREAD) * keep
                prev = cur
            }
        }
        // Each crumb picked up is a note: high for the short bridge, low for
        // the long, so the colony is heard making up its mind. At most a few
        // a second — a hundred ants arrive in a stream.
        noteCarry = min(1.5f, noteCarry + h * NOTES_PER_SECOND)
        if (arrivals > 0) { heldNote = lastBridge; heldPan = lastPan }
        if (heldNote >= 0 && noteCarry >= 1f) {
            noteCarry -= 1f
            env.emit(EventKind.HIT, if (heldNote == 0) 0.75f else 0.3f, heldPan, 0.45f)
            heldNote = -1
        }
        // Only once the returning ants have had their say: before any come
        // back, a lead is only the luck of the first few outbound choices.
        if (doneAt < 0f && delivered >= MIN_DELIVERED && shortShare >= CONVERGED) {
            doneAt = elapsed
            payoffFrame = true
        }
    }

    /**
     * Deneubourg's rule at a fork: each bridge weighed by (k + its pheromone
     * just past the fork)². [end] 0 is the nest-side fork, 1 the food side.
     */
    private fun choose(env: SimEnv, end: Int): Int {
        var ps = 0f; var pl = 0f
        for (j in 0 until SNIFF) {
            val k = if (end == 0) j else SAMPLES - j
            ps += scent[0][k]; pl += scent[1][k]
        }
        ps /= SNIFF; pl /= SNIFF
        val ws = (CHOICE_K + ps) * (CHOICE_K + ps)
        val wl = (CHOICE_K + pl) * (CHOICE_K + pl)
        val b = if (env.rng.nextFloat() < ws / (ws + wl)) 0 else 1
        recent[recentHead] = b
        recentHead = (recentHead + 1) % WINDOW
        choices++
        var s = 0; var c = 0
        for (v in recent) if (v >= 0) { c++; if (v == 0) s++ }
        shortShare = if (c == 0) 0.5f else s / c.toFloat()
        return b
    }

    /** An ant's place on screen, and its heading, for distance [d] along bridge [r] (−1: on a trunk). */
    private fun place(r: Int, d: Float, out: FloatArray) {
        if (d <= trunkA || r < 0) {
            val f = (d / trunkA).coerceIn(0f, 1f)
            out[0] = nestX + (forkAX - nestX) * f; out[1] = nestY + (forkAY - nestY) * f
            out[2] = atan2(forkAY - nestY, forkAX - nestX)
            return
        }
        val s = d - trunkA
        if (s >= len[r]) {
            val f = ((s - len[r]) / trunkB).coerceIn(0f, 1f)
            out[0] = forkBX + (foodX - forkBX) * f; out[1] = forkBY + (foodY - forkBY) * f
            out[2] = atan2(foodY - forkBY, foodX - forkBX)
            return
        }
        val x = s / len[r] * SAMPLES
        val k = x.toInt().coerceIn(0, SAMPLES - 1)
        val f = x - k
        val xs = bx[r]; val ys = by[r]
        out[0] = xs[k] + (xs[k + 1] - xs[k]) * f
        out[1] = ys[k] + (ys[k + 1] - ys[k]) * f
        out[2] = atan2(ys[k + 1] - ys[k], xs[k + 1] - xs[k])
    }

    private val tmp = FloatArray(3)

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val trailCol = palette.at(TRAIL_T)
        val bed = Palette.lerp(Palette.BG, Palette.TEXT_SECONDARY, 0.16f)
        // The bridges themselves: a faint bed, so both choices are always visible.
        g.line(nestX, nestY, forkAX, forkAY, BED_W, bed)
        g.line(forkBX, forkBY, foodX, foodY, BED_W, bed)
        for (b in 0..1) for (k in 0 until SAMPLES) g.line(bx[b][k], by[b][k], bx[b][k + 1], by[b][k + 1], BED_W, bed)
        // The pheromone over them: brighter and wider where it is strong.
        var top = 1f
        for (b in 0..1) for (v in scent[b]) if (v > top) top = v
        val full = max(top, SATURATE)
        for (b in 0..1) {
            val sc = scent[b]
            for (k in 0 until SAMPLES) {
                val u = ((sc[k] + sc[k + 1]) * 0.5f / full).coerceIn(0f, 1f)
                if (u < 0.02f) continue
                g.line(bx[b][k], by[b][k], bx[b][k + 1], by[b][k + 1], BED_W + TRAIL_W * u,
                    Palette.lerp(bed, trailCol, 0.25f + 0.75f * u))
            }
        }
        // The trunks carry everyone, so they are always fully marked.
        val trunkCol = Palette.lerp(bed, trailCol, 0.6f)
        g.line(nestX, nestY, forkAX, forkAY, BED_W + TRAIL_W * 0.5f, trunkCol)
        g.line(forkBX, forkBY, foodX, foodY, BED_W + TRAIL_W * 0.5f, trunkCol)

        // Nest and food.
        val foodCol = palette.at(FOOD_T)
        g.glow(foodX, foodY, 0.09f, foodCol, 0.35f)
        for (j in 0 until 7) {
            val a = j * 2f * PI.toFloat() / 7f
            val r = if (j == 0) 0f else 0.022f
            g.disc(foodX + cos(a) * r, foodY + sin(a) * r, 0.013f, Palette.brighten(foodCol, 0.1f))
        }
        g.disc(nestX, nestY, 0.04f, Palette.lerp(Palette.BG, Palette.TEXT_SECONDARY, 0.25f))
        g.ring(nestX, nestY, 0.04f, 0.007f, Palette.TEXT_SECONDARY, alpha = 0.7f)
        g.disc(nestX, nestY, 0.014f, Palette.BG)

        // The ants: small dark-bodied dashes along their heading, each in its
        // own lane; one carrying food has a crumb in its jaws.
        val antCol = Palette.lerp(Palette.TEXT_PRIMARY, trailCol, 0.25f)
        for (i in 0 until spawned) {
            place(route[i], dist[i], tmp)
            var x = tmp[0]; var y = tmp[1]
            var a = tmp[2]
            if (homeward[i]) a += PI.toFloat()
            // Lane offset, across the heading.
            val lane = side[i] * LANE
            x += -sin(tmp[2]) * lane; y += cos(tmp[2]) * lane
            g.rect(x, y, ANT_L, ANT_W, antCol, a)
            if (homeward[i]) g.disc(x + cos(a) * ANT_L, y + sin(a) * ANT_L, ANT_W * 1.2f, foodCol)
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "short bridge" to "${(shortShare * 100f).toInt()}%",
        "long ÷ short" to String.format(java.util.Locale.US, "%.1f×", len[1] / max(1e-6f, len[0])),
        "food home" to delivered.toString()
    )

    companion object {
        const val SAMPLES = 160
        const val MAX_ANTS = 600
        const val FORK = 0.66f
        const val SHORT_BOW = 0.28f
        const val LONG_MID = 0.58f
        const val LONG_SWING = 0.4f
        const val SPEED = 0.42f
        const val SPAWN_PER_SECOND = 14f
        const val OUT_MARK = 0.3f
        const val HOME_MARK = 1.2f
        const val EVAPORATE = 0.12f
        const val SPREAD = 0.05f
        const val SNIFF = 6
        const val CHOICE_K = 50f
        const val WINDOW = 40
        const val MIN_DELIVERED = 40
        const val CONVERGED = 0.85f
        const val NOTES_PER_SECOND = 5f
        const val SATURATE = 30f
        const val TRAIL_T = 0.15f
        const val FOOD_T = 0.8f
        const val BED_W = 0.004f
        const val TRAIL_W = 0.016f
        const val LANE = 0.009f
        const val ANT_L = 0.009f
        const val ANT_W = 0.0032f

        fun def(): SimDef = SimDef(
            id = "life/ant-colony",
            title = "Ant colony",
            shelf = Shelf.LIFE,
            family = "Biology & ecology",
            blurb = "Two bridges to the food, one short, one winding. Pheromone alone finds the short one.",
            formats = listOf(TrapFormat.GUESS, TrapFormat.SNAP),
            heroLabel = "New colony",
            defaultPalette = "Aurora",
            typicalSeconds = 20f,
            params = listOf(
                Param.int("ants", "Ants", 20, MAX_ANTS, 120, card = Card.DATA),
                Param.int("detour", "Long-way loops", 1, 5, 3, card = Card.DATA),
                Param.float("pace", "Pace", 0.3f, 3f, 1f, card = Card.MOTION)
            ),
            create = { AntColonySim() }
        )
    }
}
