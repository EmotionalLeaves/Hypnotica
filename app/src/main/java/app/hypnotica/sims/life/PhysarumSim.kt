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
 * A slime mould finding a lean network between its meals.
 *
 * Jeff Jones's agent model of Physarum polycephalum: thousands of particles,
 * each sniffing a shared chemical field at three sensors ahead of it — left,
 * centre, right — turning towards the strongest smell, stepping forward and
 * dropping a little more chemical where it lands. The field spreads and fades.
 * Nobody plans a route, yet the traffic reinforces the busiest paths and
 * starves the rest, and the mould's tubes thin down to a lean network joining
 * the food — the trick that let a real mould redraw the Tokyo rail map.
 *
 * The mould starts as a blob on every oat flake; their veins reach out, meet
 * and fuse. The payoff is the moment one unbroken network links every flake.
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
    private var fed = FloatArray(0)     // 1 just after passing food, fading as the agent wanders
    private var home = ByteArray(0)     // the flake each agent last fed at

    private var foods = 0
    private val fx = FloatArray(MAX_FOOD)      // in cells
    private val fy = FloatArray(MAX_FOOD)
    private val group = IntArray(MAX_FOOD)     // union-find over the flakes
    private val linkedAt = FloatArray(MAX_FOOD) // when each flake first joined another, for its ring

    /** Which flake, if any, each cell is next to: an agent there is fed. */
    private var foodAt = ByteArray(0)
    /** Each flake's own smell through the agar, falling off with distance. */
    private var scentOf = Array(0) { FloatArray(0) }
    /** For each flake's network: the smell of every flake NOT in it. */
    private var lure = Array(0) { FloatArray(0) }

    private var queue = IntArray(0)
    private var dist = IntArray(0)             // network steps from the first flake, for the finale
    private var compOf = IntArray(0)

    private var tickCarry = 0f
    private var ticks = 0
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var groups = 0
    private var maxDist = 1
    private var halfW = 0.46f
    /** The field's average at equilibrium: agents per cell × deposit ÷ decay. Levels are read against it. */
    private var mean = 1f

    override fun setup(env: SimEnv) {
        halfW = env.halfWidth
        // The same number of cells on any screen: the mould's reach is set in
        // cells, and on a wide screen at the phone's cell size the flakes
        // stood so far apart, in cells, that the blobs never met.
        val shape = (2f * halfW) / (FIELD_TOP - FIELD_BOTTOM)
        val rows = (env.params.i("res", 230) * sqrt(min(1f, PHONE_SHAPE / shape))).toInt().coerceIn(60, 400)
        // The dish: the whole screen less a margin, the counters' band on top.
        cell = (FIELD_TOP - FIELD_BOTTOM) / rows
        gh = rows
        gw = ((2f * (halfW - MARGIN)) / cell).toInt().coerceAtLeast(20)
        x0 = -gw * cell * 0.5f
        y0 = FIELD_BOTTOM
        val cells = gw * gh
        trail = FloatArray(cells)
        spare = FloatArray(cells)
        queue = IntArray(cells)
        dist = IntArray(cells)
        compOf = IntArray(cells)

        // Flakes scattered with a minimum spacing, so each is a real
        // destination; kept off the wall, or a flake's tube ran into it.
        foods = env.params.i("food", 7).coerceIn(3, MAX_FOOD)
        val minGap = sqrt(cells.toFloat() / foods) * 0.62f
        val edge = 24f
        var placed = 0
        var tries = 0
        while (placed < foods && tries < 4000) {
            tries++
            val x = env.rng.range(edge, gw - edge)
            val y = env.rng.range(edge, gh - edge)
            val gap = minGap * (if (tries > 2000) 0.7f else 1f)
            var ok = true
            for (j in 0 until placed) {
                val dx = fx[j] - x; val dy = fy[j] - y
                if (dx * dx + dy * dy < gap * gap) { ok = false; break }
            }
            if (!ok) continue
            fx[placed] = x; fy[placed] = y
            placed++
        }
        foods = placed
        // The finale's light starts from the flake nearest the middle.
        var mid = 0; var bestD = Float.MAX_VALUE
        for (f in 0 until foods) {
            val dx = fx[f] - gw * 0.5f; val dy = fy[f] - gh * 0.5f
            if (dx * dx + dy * dy < bestD) { bestD = dx * dx + dy * dy; mid = f }
        }
        val tx = fx[0]; val ty = fy[0]; fx[0] = fx[mid]; fy[0] = fy[mid]; fx[mid] = tx; fy[mid] = ty

        foodAt = ByteArray(cells) { -1 }
        for (f in 0 until foods) for (dy in -4..4) for (dx in -4..4) {
            if (dx * dx + dy * dy > 16) continue
            val xx = fx[f].toInt() + dx; val yy = fy[f].toInt() + dy
            if (xx in 0 until gw && yy in 0 until gh) foodAt[yy * gw + xx] = f.toByte()
        }
        scentOf = Array(foods) { f ->
            FloatArray(cells).also { s ->
                for (y in 0 until gh) for (x in 0 until gw) {
                    val dx = x - fx[f]; val dy = y - fy[f]
                    s[y * gw + x] = exp(-sqrt(dx * dx + dy * dy) / SCENT_REACH)
                }
            }
        }
        lure = Array(foods) { FloatArray(cells) }
        for (i in 0 until foods) { group[i] = i; linkedAt[i] = -1f }
        groups = foods
        rebuildLures()

        // A blob of mould on every flake. As many agents as the dish's area
        // calls for, so a wide screen gets the same density as a phone.
        val perCell = env.params.f("density", 0.32f).coerceIn(0.05f, 1.2f)
        n = (cells * perCell).toInt().coerceIn(500, MAX_AGENTS)
        ax = FloatArray(n); ay = FloatArray(n); ah = FloatArray(n)
        fed = FloatArray(n) { 1f }; home = ByteArray(n)
        mean = n.toFloat() / cells * DEPOSIT / DECAY
        for (i in 0 until n) {
            val f = i % foods
            val a = env.rng.range(0f, TAU)
            val r = BLOB * sqrt(env.rng.nextFloat())
            ax[i] = fx[f] + FastMath.cos(a) * r
            ay[i] = fy[f] + FastMath.sin(a) * r
            // Facing outward: the blob's edge is where it grows.
            ah[i] = a + env.rng.range(-0.6f, 0.6f)
            home[i] = f.toByte()
        }
        tickCarry = 0f
        ticks = 0
        elapsed = 0f
        doneAt = -1f
        payoffFrame = false
        maxDist = 1
    }

    /**
     * The smell that draws each network's agents on: every flake outside it.
     * A network's own flakes stop luring it once it holds them, and once all
     * are joined nothing lures at all and the network relaxes into its
     * leanest shape on the trail alone. Without the lure, a flake the young
     * network passed by was invisible from more than a few cells away and
     * could sit alone for half a minute. Rebuilt only when two networks fuse.
     */
    private fun rebuildLures() {
        for (f in 0 until foods) {
            val out = lure[f]
            java.util.Arrays.fill(out, 0f)
            val rf = find(f)
            for (j in 0 until foods) {
                if (find(j) == rf) continue
                val s = scentOf[j]
                for (k in out.indices) out[k] += s[k]
            }
        }
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
        val hunger = Math.pow(HUNGER.toDouble(), RATE.toDouble()).toFloat()
        // Move: sense left, centre, right; turn towards the strongest; step; deposit.
        for (i in 0 until n) {
            var hd = ah[i]
            val x = ax[i]; val y = ay[i]
            val lu = lure[home[i].toInt()]
            val fc = sample(t, lu, x + FastMath.cos(hd) * so, y + FastMath.sin(hd) * so)
            val fl = sample(t, lu, x + FastMath.cos(hd + sa) * so, y + FastMath.sin(hd + sa) * so)
            val fr = sample(t, lu, x + FastMath.cos(hd - sa) * so, y + FastMath.sin(hd - sa) * so)
            if (fc > fl && fc > fr) {
                // keep going
            } else if (fc < fl && fc < fr) {
                hd += if (env.rng.nextFloat() < 0.5f) ra else -ra
            } else if (fl > fr) {
                hd += ra
            } else if (fr > fl) {
                hd -= ra
            }
            var nx = x + FastMath.cos(hd) * RATE
            var ny = y + FastMath.sin(hd) * RATE
            // The dish wall turns an agent back the way it came. With the
            // nutrient below, what returns from the wall lays little chemical,
            // so a tube that only leads to the wall starves away.
            if (nx < 1f || nx > w - 2f || ny < 1f || ny > hh - 2f) {
                nx = x; ny = y
                hd += PI.toFloat() + env.rng.range(-0.4f, 0.4f)
            }
            ax[i] = nx; ay[i] = ny; ah[i] = hd
            val k = ny.toInt() * w + nx.toInt()
            // Nutrient: an agent lays most chemical just after it has been at
            // food, less the longer it wanders. Tubes between flakes are fed
            // from both ends and hold; a tube to nowhere starves and withdraws,
            // as a real mould retracts its fruitless branches. Without it the
            // spare mould wore dead-end tubes out to the dish wall.
            var fd = fed[i] * hunger
            val at = foodAt[k]
            if (at >= 0) { fd = 1f; home[i] = at }
            fed[i] = fd
            t[k] += DEPOSIT * RATE * (FED_FLOOR + (1f - FED_FLOOR) * fd)
        }
        // Each flake is a strong, steady source of the chemical.
        for (f in 0 until foods) {
            val cx = fx[f].toInt(); val cy = fy[f].toInt()
            for (dy in -2..2) for (dx in -2..2) {
                if (dx * dx + dy * dy > 5) continue
                t[(cy + dy) * w + (cx + dx)] += FOOD_SMELL * RATE * mean
            }
        }
        // Spread (a 3×3 mean) and fade. The dish wall soaks the chemical up
        // (outside counts as zero), so a stream that meets the wall thins out
        // instead of running along it.
        val o = spare
        // Each tick is RATE of one of Jones's steps — half a cell of travel,
        // half the deposit, half the spread, the matching share of the fade —
        // so the mould grows at a watchable pace while the field still
        // changes smoothly from frame to frame; fewer whole steps a second
        // made it visibly jump.
        val keep = Math.pow(1.0 - DECAY, RATE.toDouble()).toFloat()
        val k9 = keep * RATE / 9f
        val k1 = keep * (1f - RATE)
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
                o[r0 + x] = s * k9 + t[r0 + x] * k1
            }
        }
        spare = trail; trail = o
        if (ticks % CHECK_EVERY == 0 && doneAt < 0f) checkLinks(env)
    }

    private fun sample(t: FloatArray, lu: FloatArray, x: Float, y: Float): Float {
        val ix = x.toInt(); val iy = y.toInt()
        if (ix < 0 || iy < 0 || ix >= gw || iy >= gh) return -mean
        val k = iy * gw + ix
        val v = t[k] + lu[k] * mean * LURE
        // Near the dish wall the smell reads weaker than it is: without it the
        // agents a wall turned back slid along it and wore a tube round the
        // edge of the dish that led to no food.
        val edge = min(min(ix, gw - 1 - ix), min(iy, gh - 1 - iy))
        if (edge < WALL_ZONE) {
            val u = edge / WALL_ZONE.toFloat()
            return v * u - mean * WALL_PUSH * (1f - u)
        }
        return v
    }

    /**
     * Which flakes one network of veins already joins: a flood over the cells
     * whose chemical is above the vein level, from every flake in turn. When
     * two networks fuse it is heard, panned to where they met; the payoff is
     * the fusion that leaves one.
     */
    private fun checkLinks(env: SimEnv) {
        java.util.Arrays.fill(compOf, -1)
        val th = VEIN * mean
        val comp = IntArray(foods) { -1 }
        for (f in 0 until foods) {
            val start = fy[f].toInt() * gw + fx[f].toInt()
            if (compOf[start] >= 0) { comp[f] = compOf[start]; continue }
            if (trail[start] <= th) continue
            flood(start, th, compOf, f)
            comp[f] = f
        }
        var merged = false
        for (a in 0 until foods) for (b in a + 1 until foods) {
            if (comp[a] < 0 || comp[a] != comp[b]) continue
            val ra = find(a); val rb = find(b)
            if (ra == rb) continue
            group[max(ra, rb)] = min(ra, rb)
            groups--
            merged = true
            if (linkedAt[a] < 0f) linkedAt[a] = elapsed
            if (linkedAt[b] < 0f) linkedAt[b] = elapsed
            // The last fusion is the payoff, which the engine sounds itself.
            if (groups > 1) {
                val mx = (fx[a] + fx[b]) * 0.5f * cell + x0
                env.emit(
                    EventKind.HIT, 0.2f + 0.7f * (foods - groups) / max(1, foods - 1),
                    (mx / halfW).coerceIn(-1f, 1f), 0.75f
                )
            }
        }
        if (!merged) return
        rebuildLures()
        if (groups == 1) {
            // Joined, every flake goes on drawing the mould gently, so the
            // network can thin to its leanest without letting go of one.
            for (f in 0 until foods) {
                val out = lure[f]
                for (j in 0 until foods) { val sc = scentOf[j]; for (k in out.indices) out[k] += sc[k] * HELD }
            }
            doneAt = elapsed
            payoffFrame = true
            java.util.Arrays.fill(dist, -1)
            val start = fy[0].toInt() * gw + fx[0].toInt()
            maxDist = max(1, flood(start, th, dist, -1))
        }
    }

    private fun find(a: Int): Int {
        var r = a
        while (group[r] != r) r = group[r]
        return r
    }

    /**
     * Breadth-first over vein cells, 8-connected. With [id] ≥ 0 it labels the
     * cells with it; with −1 it writes each cell's step count. Returns the
     * farthest step.
     */
    private fun flood(start: Int, th: Float, d: IntArray, id: Int): Int {
        var head = 0; var tail = 0
        queue[tail++] = start
        d[start] = if (id >= 0) id else 0
        var far = 0
        while (head < tail) {
            val k = queue[head++]
            val x = k % gw; val y = k / gw
            val next = if (id >= 0) id else d[k] + 1
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val xx = x + dx; val yy = y + dy
                if (xx < 0 || yy < 0 || xx >= gw || yy >= gh) continue
                val j = yy * gw + xx
                if (d[j] >= 0 || trail[j] <= th) continue
                d[j] = next
                if (next > far) far = next
                queue[tail++] = j
            }
        }
        return far
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val since = if (doneAt >= 0f) elapsed - doneAt else -1f
        // The finale: light runs out along the finished network from the
        // middle flake and stays — every vein it reaches is lit for good, and
        // once it has reached the far end the whole mould is.
        val front = if (since >= 0f) since / GLINT_SECONDS * maxDist else -1f
        val allLit = since >= 0f && front >= maxDist * 1.2f + 4f
        val levels = IntArray(LEVELS + 1) { lv ->
            if (lv == 0) 0 else {
                val u = lv / LEVELS.toFloat()
                // Faint chemical a dim, deep shade; thick veins bright.
                Palette.lerp(Palette.BG, palette.at(0.72f - 0.67f * u), 0.2f + 0.8f * u)
            }
        }
        // Lit in a few steps behind the front, so its edge is soft.
        val lit = Array(LIT_STEPS) { s ->
            val amt = (s + 1f) / LIT_STEPS
            IntArray(LEVELS + 1) { lv -> if (lv == 0) 0 else Palette.brighten(levels[lv], amt * (0.08f + 0.22f * lv / LEVELS)) }
        }
        val band = max(4f, maxDist * 0.18f)
        for (y in 0 until gh) {
            val row = y * gw
            val yc = y0 + (y + 0.5f) * cell
            g.cellRow(x0, yc, cell, gw) { x ->
                val lv = level(trail[row + x])
                if (lv == 0) 0
                else if (allLit) lit[LIT_STEPS - 1][lv]
                else {
                    val d = dist[row + x]
                    if (front < 0f || d < 0 || d > front) levels[lv]
                    else lit[((front - d) / band * LIT_STEPS).toInt().coerceIn(0, LIT_STEPS - 1)][lv]
                }
            }
        }
        // The flakes, each ringed in white once its network has reached
        // another — eased in, then steady.
        val accent = palette.at(0.05f)
        for (f in 0 until foods) {
            val x = x0 + fx[f] * cell; val y = y0 + fy[f] * cell
            val on = if (linkedAt[f] >= 0f) ((elapsed - linkedAt[f]) / 0.5f).coerceIn(0f, 1f) else 0f
            g.glow(x, y, FOOD_R * (2.6f + 1.4f * on), accent, 0.3f + 0.25f * on)
            g.disc(x, y, FOOD_R, Palette.TEXT_PRIMARY)
            g.ring(x, y, FOOD_R * 1.75f, FOOD_R * 0.28f, Palette.lerp(accent, Palette.TEXT_PRIMARY, on), alpha = 0.45f + 0.5f * on)
        }
    }

    private fun level(v: Float): Int {
        if (v < FAINT * mean) return 0
        val u = 1f - exp(-v / (BRIGHT * mean))
        return (u * LEVELS).roundToInt().coerceIn(1, LEVELS)
    }

    /** Flakes in the biggest network so far. */
    private fun largest(): Int {
        var best = 1
        for (f in 0 until foods) {
            val r = find(f)
            var c = 0
            for (j in 0 until foods) if (find(j) == r) c++
            if (c > best) best = c
        }
        return best
    }

    override fun payoff(): Boolean = payoffFrame

    /** Endless: the network goes on reshaping itself. */
    override fun finished(): Boolean = false

    override fun counters(): List<Pair<String, String>> = listOf(
        "food linked" to "${largest()}/$foods",
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
        const val TICKS_PER_SECOND = 11f
        const val RATE = 0.5f
        const val SENSE = 7f
        const val DEPOSIT = 5f
        /** Per tick: an agent's nutrient halves in about twenty ticks away from food. */
        const val HUNGER = 0.965f
        const val FED_FLOOR = 0.12f
        const val FOOD_SMELL = 3f
        const val WALL_ZONE = 14
        const val WALL_PUSH = 3f
        const val SCENT_REACH = 28f
        /** How strongly the flakes a network has not yet reached draw its agents, against the mean field. */
        const val LURE = 2f
        const val DECAY = 0.1f
        const val BLOB = 9f
        const val CHECK_EVERY = 6
        /** Chemical level that counts as a vein for the network test, against the mean field. */
        const val VEIN = 0.8f
        const val FAINT = 0.15f
        const val BRIGHT = 3f
        const val LEVELS = 14
        const val FOOD_R = 0.016f
        const val GLINT_SECONDS = 1.6f
        const val LIT_STEPS = 5
        /** Width over height of an upright phone's dish, which the default resolution is set for. */
        const val PHONE_SHAPE = 0.48f
        /** Each flake's pull once all are joined, as a share of the lure. */
        const val HELD = 0.5f

        fun def(): SimDef = SimDef(
            id = "life/physarum",
            title = "Slime mould network",
            shelf = Shelf.LIFE,
            family = "Biology & ecology",
            blurb = "Thousands of blind agents follow each other's trail and grow a lean network linking all the food.",
            formats = listOf(TrapFormat.GROW, TrapFormat.CHAIN),
            heroLabel = "New dish",
            defaultPalette = "Ember",
            typicalSeconds = 9f,
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
