package app.hypnotica.sims.life

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.FastMath
import app.hypnotica.core.sim.Shelf
import app.hypnotica.core.sim.Sim
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.sim.SimEnv
import app.hypnotica.core.sim.TrapFormat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A slime mould finding the shortest network between its meals.
 *
 * Jeff Jones's agent model of Physarum polycephalum: thousands of particles,
 * each sniffing a shared chemical field at three sensors ahead of it — left,
 * centre, right — turning towards the strongest smell, stepping forward and
 * dropping a little more chemical where it lands. The field spreads and fades.
 * Nobody plans a route, yet the traffic reinforces the busiest paths and
 * starves the rest, and the mould's tubes thin down to a lean network joining
 * the food — the trick that let a real mould redraw the Tokyo rail map.
 *
 * The mould starts as a small blob on every oat flake; their veins reach out,
 * meet and fuse. The payoff is the moment one unbroken network links every
 * piece of food.
 */
class PhysarumSim : Sim {

    // The chemical field, in cells; [trail] is read, [spare] written, then swapped.
    private var gw = 0
    private var gh = 0
    private var trail = FloatArray(0)
    private var spare = FloatArray(0)
    private var cell = 0.01f
    private var x0 = 0f
    private var y0 = 0f

    private var n = 0
    private var ax = FloatArray(0)
    private var ay = FloatArray(0)
    private var ah = FloatArray(0)

    private var foods = 0
    private val fx = FloatArray(MAX_FOOD)      // in cells
    private val fy = FloatArray(MAX_FOOD)
    private val group = IntArray(MAX_FOOD)     // union-find over the food
    private val linkedAt = FloatArray(MAX_FOOD) // when each food last joined a bigger network, for its flash

    // Scratch for the connectivity flood.
    private var label = IntArray(0)
    private var queue = IntArray(0)
    private var dist = IntArray(0)              // network distance from the first food, for the finale

    private var tickCarry = 0f
    private var ticks = 0
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var groups = 0
    private var maxDist = 1
    private var veinCells = 0
    private var halfW = 0.46f
    /** The field's average at equilibrium: agents per cell × deposit ÷ decay. Levels are read against it. */
    private var mean = 1f

    override fun setup(env: SimEnv) {
        halfW = env.halfWidth
        val rows = env.params.i("res", 230).coerceIn(80, 400)
        // The dish: the whole screen less a margin, the counters' band on top.
        val top = FIELD_TOP; val bottom = FIELD_BOTTOM
        cell = (top - bottom) / rows
        gh = rows
        gw = ((2f * (halfW - MARGIN)) / cell).toInt().coerceAtLeast(20)
        x0 = -gw * cell * 0.5f
        y0 = bottom
        trail = FloatArray(gw * gh)
        spare = FloatArray(gw * gh)
        label = IntArray(gw * gh)
        queue = IntArray(gw * gh)
        dist = IntArray(gw * gh)

        // Food scattered with a minimum spacing, so every flake is a real
        // destination; the first is the one the finale's light runs out from.
        foods = env.params.i("food", 7).coerceIn(3, MAX_FOOD)
        val minGap = sqrt(gw.toFloat() * gh / foods) * 0.62f
        val edge = 10f
        var placed = 0
        var tries = 0
        while (placed < foods && tries < 4000) {
            tries++
            val x = env.rng.range(edge, gw - edge)
            val y = env.rng.range(edge, gh - edge)
            var ok = true
            for (j in 0 until placed) {
                val dx = fx[j] - x; val dy = fy[j] - y
                if (dx * dx + dy * dy < minGap * minGap * (if (tries > 2000) 0.5f else 1f)) { ok = false; break }
            }
            if (!ok) continue
            fx[placed] = x; fy[placed] = y
            placed++
        }
        foods = placed
        for (i in 0 until foods) { group[i] = i; linkedAt[i] = -10f }
        groups = foods

        // A blob of mould on every flake. As many agents as the dish's area
        // calls for, so a wide screen gets the same density as a phone.
        val perCell = env.params.f("density", 0.32f).coerceIn(0.05f, 1.2f)
        n = (gw * gh * perCell).toInt().coerceIn(500, MAX_AGENTS)
        ax = FloatArray(n); ay = FloatArray(n); ah = FloatArray(n)
        mean = n.toFloat() / (gw * gh) * DEPOSIT / DECAY
        for (i in 0 until n) {
            val f = i % foods
            val a = env.rng.range(0f, TAU)
            val r = BLOB * sqrt(env.rng.nextFloat())
            ax[i] = fx[f] + FastMath.cos(a) * r
            ay[i] = fy[f] + FastMath.sin(a) * r
            // Facing outward: the blob's edge is where it grows.
            ah[i] = a + env.rng.range(-0.6f, 0.6f)
        }
        tickCarry = 0f
        ticks = 0
        elapsed = 0f
        doneAt = -1f
        payoffFrame = false
        maxDist = 1
        veinCells = 0
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        tickCarry += h * TICKS_PER_SECOND * env.params.f("pace", 1f)
        var budget = 4
        while (tickCarry >= 1f && budget-- > 0) {
            tickCarry -= 1f
            tick(env)
        }
        if (tickCarry > 1f) tickCarry = 1f
    }

    private fun tick(env: SimEnv) {
        ticks++
        val sa = env.params.f("angle", 0.5f)
        val ra = sa
        val so = SENSE
        val t = trail
        val w = gw; val hh = gh
        // Move: sense left, centre, right; turn towards the strongest; step; deposit.
        for (i in 0 until n) {
            var hd = ah[i]
            val x = ax[i]; val y = ay[i]
            val fc = sample(t, x + FastMath.cos(hd) * so, y + FastMath.sin(hd) * so)
            val fl = sample(t, x + FastMath.cos(hd + sa) * so, y + FastMath.sin(hd + sa) * so)
            val fr = sample(t, x + FastMath.cos(hd - sa) * so, y + FastMath.sin(hd - sa) * so)
            if (fc > fl && fc > fr) {
                // keep going
            } else if (fc < fl && fc < fr) {
                hd += if (env.rng.nextFloat() < 0.5f) ra else -ra
            } else if (fl > fr) {
                hd += ra
            } else if (fr > fl) {
                hd -= ra
            }
            var nx = x + FastMath.cos(hd)
            var ny = y + FastMath.sin(hd)
            // The dish wall: turned back in a new direction rather than piling up.
            if (nx < 1f || nx > w - 2f || ny < 1f || ny > hh - 2f) {
                nx = nx.coerceIn(1f, w - 2f); ny = ny.coerceIn(1f, hh - 2f)
                hd = env.rng.range(0f, TAU)
            }
            ax[i] = nx; ay[i] = ny; ah[i] = hd
            val k = ny.toInt() * w + nx.toInt()
            t[k] += DEPOSIT
        }
        // The food is a strong, steady smell.
        for (f in 0 until foods) {
            val cx = fx[f].toInt(); val cy = fy[f].toInt()
            for (dy in -2..2) for (dx in -2..2) {
                if (dx * dx + dy * dy > 5) continue
                val k = (cy + dy) * w + (cx + dx)
                t[k] += FOOD_SMELL * mean
            }
        }
        // Spread (a 3×3 mean) and fade.
        val keep = 1f - DECAY
        val o = spare
        // The dish wall soaks the chemical up (outside counts as zero), so a
        // stream that meets the wall thins out instead of running along it as
        // a tube to nowhere.
        val k9 = keep / 9f
        for (y in 0 until hh) {
            val r0 = y * w
            val rm = r0 - w; val rp = r0 + w
            val hasM = y > 0; val hasP = y < hh - 1
            for (x in 0 until w) {
                var s = 0f
                val l = x > 0; val r = x < w - 1
                if (hasM) { s += t[rm + x]; if (l) s += t[rm + x - 1]; if (r) s += t[rm + x + 1] }
                s += t[r0 + x]; if (l) s += t[r0 + x - 1]; if (r) s += t[r0 + x + 1]
                if (hasP) { s += t[rp + x]; if (l) s += t[rp + x - 1]; if (r) s += t[rp + x + 1] }
                o[r0 + x] = s * k9
            }
        }
        spare = trail; trail = o
        if (ticks % CHECK_EVERY == 0 && doneAt < 0f) checkLinks(env)
    }

    private fun sample(t: FloatArray, x: Float, y: Float): Float {
        val ix = x.toInt(); val iy = y.toInt()
        if (ix < 0 || iy < 0 || ix >= gw || iy >= gh) return -mean
        // Near the dish wall the smell reads weaker than it is: without it the
        // agents a wall turned back slid along it and wore a tube round the
        // edge of the dish that led to no food.
        val edge = min(min(ix, gw - 1 - ix), min(iy, gh - 1 - iy))
        if (edge < WALL_ZONE) return t[iy * gw + ix] - mean * WALL_PUSH * (1f - edge / WALL_ZONE.toFloat())
        return t[iy * gw + ix]
    }

    /**
     * Which flakes one network of veins already joins: a flood over the cells
     * whose chemical is above the vein level, from every flake in turn. When
     * two networks fuse it is heard, panned to where they met, and the payoff
     * is the flood that reaches them all.
     */
    private fun checkLinks(env: SimEnv) {
        java.util.Arrays.fill(label, -1)
        val th = VEIN * mean
        var veins = 0
        for (k in trail.indices) if (trail[k] > th) veins++
        veinCells = veins
        val compOf = IntArray(foods) { -1 }
        for (f in 0 until foods) {
            val start = fy[f].toInt() * gw + fx[f].toInt()
            if (label[start] >= 0) { compOf[f] = label[start]; continue }
            if (trail[start] <= th) continue
            flood(start, f, th, null)
            compOf[f] = f
        }
        val before = groups
        // Union the flakes that share a component.
        for (a in 0 until foods) for (b in a + 1 until foods) {
            if (compOf[a] < 0 || compOf[a] != compOf[b]) continue
            val ra = find(a); val rb = find(b)
            if (ra == rb) continue
            group[max(ra, rb)] = min(ra, rb)
            groups--
            linkedAt[a] = elapsed; linkedAt[b] = elapsed
            val mx = (fx[a] + fx[b]) * 0.5f * cell + x0
            env.emit(
                EventKind.HIT, 0.25f + 0.6f * (foods - groups) / max(1, foods - 1),
                (mx / halfW).coerceIn(-1f, 1f), 0.75f
            )
        }
        if (groups == 1 && before > 1) {
            doneAt = elapsed
            payoffFrame = true
            // Network distance from the first flake, for the light that runs
            // along the finished network.
            java.util.Arrays.fill(dist, -1)
            val start = fy[0].toInt() * gw + fx[0].toInt()
            maxDist = max(1, flood(start, 0, th, dist))
        }
    }

    private fun find(a: Int): Int {
        var r = a
        while (group[r] != r) r = group[r]
        return r
    }

    /** Breadth-first over vein cells, 8-connected; returns the farthest step. */
    private fun flood(start: Int, id: Int, th: Float, d: IntArray?): Int {
        var head = 0; var tail = 0
        queue[tail++] = start
        label[start] = id
        d?.set(start, 0)
        var far = 0
        while (head < tail) {
            val k = queue[head++]
            val x = k % gw; val y = k / gw
            val dk = d?.get(k) ?: 0
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val xx = x + dx; val yy = y + dy
                if (xx < 0 || yy < 0 || xx >= gw || yy >= gh) continue
                val j = yy * gw + xx
                if (d != null) {
                    if (d[j] >= 0 || trail[j] <= th) continue
                    d[j] = dk + 1
                    if (dk + 1 > far) far = dk + 1
                } else {
                    if (label[j] >= 0 || trail[j] <= th) continue
                    label[j] = id
                }
                queue[tail++] = j
            }
        }
        return far
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val since = if (doneAt >= 0f) elapsed - doneAt else -1f
        // The finale: a pulse of light running out along the finished network
        // from the first flake, once.
        val front = if (since in 0f..GLINT_SECONDS) since / GLINT_SECONDS * (maxDist + GLINT_BAND) else -1f
        val levels = IntArray(LEVELS + 1) { lv ->
            if (lv == 0) 0 else {
                val u = lv / LEVELS.toFloat()
                // Faint chemical a dim, cool shade; thick veins bright.
                Palette.lerp(Palette.BG, palette.at(0.72f - 0.67f * u), 0.2f + 0.8f * u)
            }
        }
        val glint = IntArray(LEVELS + 1) { lv -> if (lv == 0) 0 else Palette.brighten(levels[lv], 0.45f) }
        for (y in 0 until gh) {
            val row = y * gw
            val yc = y0 + (y + 0.5f) * cell
            g.cellRow(x0, yc, cell, gw) { x ->
                val v = trail[row + x]
                val lv = level(v)
                if (lv == 0) 0
                else if (front >= 0f && dist[row + x] >= 0 && abs(dist[row + x] - front) < GLINT_BAND) glint[lv]
                else levels[lv]
            }
        }
        // The food: an oat flake, ringed once its network has reached another.
        val accent = palette.at(0.05f)
        for (f in 0 until foods) {
            val x = x0 + fx[f] * cell; val y = y0 + fy[f] * cell
            val linked = find(f).let { r -> (0 until foods).any { it != f && find(it) == r } }
            val flash = (1f - (elapsed - linkedAt[f]) / 0.8f).coerceIn(0f, 1f)
            val done = if (since >= 0f) (1f - since / 2.5f).coerceIn(0f, 1f) else 0f
            g.glow(x, y, FOOD_R * (2.6f + 2f * flash + 2f * done), accent, 0.35f + 0.4f * flash + 0.4f * done)
            g.disc(x, y, FOOD_R, Palette.TEXT_PRIMARY)
            g.ring(x, y, FOOD_R * 1.75f, FOOD_R * 0.28f, if (linked) Palette.TEXT_PRIMARY else accent, alpha = if (linked) 0.9f else 0.45f)
        }
    }

    private fun level(v: Float): Int {
        if (v < FAINT * mean) return 0
        val u = 1f - exp(-v / (BRIGHT * mean))
        return (u * LEVELS).roundToInt().coerceIn(1, LEVELS)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && elapsed - doneAt > 5f

    override fun counters(): List<Pair<String, String>> = listOf(
        "food linked" to "${foods - groups + 1}/$foods",
        "networks" to groups.toString(),
        "agents" to "%,d".format(java.util.Locale.US, n)
    )

    companion object {
        const val TAU = (2.0 * PI).toFloat()
        const val MAX_FOOD = 12
        const val MAX_AGENTS = 160_000
        const val FIELD_TOP = 0.86f
        const val FIELD_BOTTOM = -0.96f
        const val MARGIN = 0.02f
        const val TICKS_PER_SECOND = 14f
        const val SENSE = 7f
        const val DEPOSIT = 5f
        const val FOOD_SMELL = 3f
        const val WALL_ZONE = 8
        const val WALL_PUSH = 1.5f
        const val DECAY = 0.1f
        const val BLOB = 9f
        const val CHECK_EVERY = 8
        /** Chemical level that counts as a vein for the network test. */
        const val VEIN = 2.5f
        const val FAINT = 0.15f
        const val BRIGHT = 3f
        const val LEVELS = 14
        const val FOOD_R = 0.016f
        const val GLINT_SECONDS = 1.6f
        const val GLINT_BAND = 6f

        fun def(): SimDef = SimDef(
            id = "life/physarum",
            title = "Slime mould network",
            shelf = Shelf.LIFE,
            family = "Biology & ecology",
            blurb = "Thousands of blind agents follow their own trail and build the shortest network between the food.",
            formats = listOf(TrapFormat.GROW, TrapFormat.CHAIN),
            heroLabel = "New dish",
            defaultPalette = "Ember",
            typicalSeconds = 22f,
            params = listOf(
                Param.int("food", "Food", 3, MAX_FOOD, 7, card = Card.DATA),
                Param.float("density", "Mould", 0.1f, 1f, 0.32f, card = Card.DATA),
                Param.int("res", "Resolution", 100, 360, 230, card = Card.DATA, advancedOnly = true),
                Param.float("angle", "Sensor angle", 0.2f, 1.2f, 0.5f, card = Card.MOTION, unit = "rad"),
                Param.float("pace", "Pace", 0.3f, 2.5f, 1f, card = Card.MOTION)
            ),
            create = { PhysarumSim() }
        )
    }
}
