package app.hypnotica.sims.algorithms

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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The paint bucket: a flood fill spreading from one click through every
 * pixel it can reach, breadth first, so its edge is a wavefront one step
 * further on each tick.
 *
 * The picture is a pixel grid cut into walled rooms, each room a maze of its
 * own, so a fill has to snake down every corridor rather than spread as a
 * plain diamond. One bucket per room, each its own colour, clicked one after
 * another; the picture is done when the last corridor of the last room is
 * painted. A fill never crosses a wall, which is the whole of the algorithm.
 */
class FloodFillSim : Sim {

    private var gw = 23
    private var gh = 49
    private var cell = 0.04f
    private var wall = BooleanArray(0)
    private var region = IntArray(0)
    private var dist = IntArray(0)
    private var fills = 6
    private var seedCell = IntArray(0)
    private var depth = IntArray(0)
    private var startAt = FloatArray(0)
    private var layersPerSecond = 8f
    private var lastLayer = IntArray(0)
    private var frontSum = FloatArray(0)
    private var frontN = IntArray(0)
    private var complete = BooleanArray(0)
    private var started = BooleanArray(0)
    private var clock = 0f
    private var vclock = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var sinceSound = 1f
    private var painted = 0
    private var floor = 1

    override fun setup(env: SimEnv) {
        val p = env.params
        val detail = p.i("detail", 11).coerceIn(4, 32)
        fills = p.i("fills", 6).coerceIn(1, 12)
        val halfW = env.halfWidth
        val availW = 2f * halfW * 0.94f
        val availH = 1.88f
        // The detail counts rooms along the screen's short side; the long side
        // takes as many as fit at the same pixel size.
        val mw: Int; val mh: Int
        if (availW <= availH) {
            mw = detail
            cell = availW / (2 * mw + 1)
            mh = (((availH / cell) - 1f) / 2f).toInt().coerceAtLeast(2)
        } else {
            mh = detail
            cell = availH / (2 * mh + 1)
            mw = (((availW / cell) - 1f) / 2f).toInt().coerceAtLeast(2)
        }
        gw = 2 * mw + 1; gh = 2 * mh + 1
        val rng = env.rng
        val nRooms = mw * mh
        fills = min(fills, nRooms)
        // Rooms grown from spread-out seeds, each room taking one neighbouring
        // cell in turn from a random point of its own frontier: ragged,
        // organic shapes, always in one piece, of roughly even size. (Growing
        // from one shared frontier let a room be walled in at a single cell.)
        val owner = IntArray(nRooms) { -1 }
        val front = Array(fills) { ArrayList<Int>() }
        for (r in 0 until fills) {
            var best = -1; var bestD = -1
            for (t in 0 until 12) {
                val q = rng.int(0, nRooms)
                if (owner[q] >= 0) continue
                var d = Int.MAX_VALUE
                for (o in 0 until r) {
                    val s0 = front[o][0]
                    d = min(d, abs(q % mw - s0 % mw) + abs(q / mw - s0 / mw))
                }
                if (d > bestD) { bestD = d; best = q }
            }
            if (best < 0) { best = 0; while (owner[best] >= 0) best++ }
            owner[best] = r
            front[r].add(best)
        }
        val dirs = intArrayOf(0, 1, 2, 3)
        var live = true
        while (live) {
            live = false
            for (r in 0 until fills) {
                val fr = front[r]
                var grew = false
                while (!grew && fr.isNotEmpty()) {
                    val k = rng.int(0, fr.size)
                    val room = fr[k]
                    val x = room % mw; val y = room / mw
                    rng.shuffle(dirs)
                    for (d in dirs) {
                        val nx = x + DX[d]; val ny = y + DY[d]
                        if (nx < 0 || ny < 0 || nx >= mw || ny >= mh) continue
                        val q = ny * mw + nx
                        if (owner[q] >= 0) continue
                        owner[q] = r
                        fr.add(q)
                        grew = true
                        break
                    }
                    if (!grew) { fr[k] = fr[fr.size - 1]; fr.removeAt(fr.size - 1) }
                }
                if (grew) live = true
            }
        }
        // Which rooms touch, and a palette slot for each so that touching
        // rooms sit far apart on the palette (index order put neighbours on
        // neighbouring slots, and on a narrow palette like Ember they matched).
        adj = Array(fills) { BooleanArray(fills) }
        for (y in 0 until mh) for (x in 0 until mw) {
            val a = owner[y * mw + x]
            if (x + 1 < mw) { val b = owner[y * mw + x + 1]; if (a != b) { adj[a][b] = true; adj[b][a] = true } }
            if (y + 1 < mh) { val b = owner[(y + 1) * mw + x]; if (a != b) { adj[a][b] = true; adj[b][a] = true } }
        }
        slot = IntArray(fills) { -1 }
        val taken = BooleanArray(fills)
        for (r in 0 until fills) {
            var bestS = 0; var bestScore = -1
            for (c in 0 until fills) {
                if (taken[c]) continue
                var score = fills
                for (q in 0 until fills) if (adj[r][q] && slot[q] >= 0) score = min(score, abs(slot[q] - c))
                if (score > bestScore) { bestScore = score; bestS = c }
            }
            slot[r] = bestS; taken[bestS] = true
        }
        colourFor = null
        // A maze inside each room (randomised depth-first), never through a
        // room's own border, so each room is one connected corridor system.
        wall = BooleanArray(gw * gh) { true }
        val seen = BooleanArray(nRooms)
        val stack = IntArray(nRooms)
        for (s in 0 until nRooms) {
            if (seen[s]) continue
            var sp = 0
            stack[sp++] = s; seen[s] = true
            open(s % mw, s / mw)
            while (sp > 0) {
                val room = stack[sp - 1]
                val x = room % mw; val y = room / mw
                var options = 0
                val opt = IntArray(4)
                for (d in 0 until 4) {
                    val nx = x + DX[d]; val ny = y + DY[d]
                    if (nx < 0 || ny < 0 || nx >= mw || ny >= mh) continue
                    val q = ny * mw + nx
                    if (seen[q] || owner[q] != owner[room]) continue
                    opt[options++] = d
                }
                if (options == 0) { sp--; continue }
                val d = opt[rng.int(0, options)]
                val nx = x + DX[d]; val ny = y + DY[d]
                val q = ny * mw + nx
                seen[q] = true
                wall[(2 * y + 1 + DY[d]) * gw + (2 * x + 1 + DX[d])] = false
                open(nx, ny)
                stack[sp++] = q
            }
        }
        region = IntArray(gw * gh) { -1 }
        for (y in 0 until mh) for (x in 0 until mw) {
            region[(2 * y + 1) * gw + 2 * x + 1] = owner[y * mw + x]
        }
        // Passages take their room's number from the room cells beside them.
        for (i in 0 until gw * gh) {
            if (wall[i] || region[i] >= 0) continue
            val x = i % gw; val y = i / gw
            region[i] = if (x % 2 == 0) region[i - 1] else region[i - gw]
        }
        floor = 0
        for (i in 0 until gw * gh) if (!wall[i]) floor++
        // Each bucket lands on a random pixel of its room; breadth-first
        // distance from there is when the paint arrives.
        seedCell = IntArray(fills)
        val roomsOf = Array(fills) { ArrayList<Int>() }
        for (q in 0 until nRooms) roomsOf[owner[q]].add(q)
        for (r in 0 until fills) {
            val q = roomsOf[r][rng.int(0, roomsOf[r].size)]
            seedCell[r] = (2 * (q / mw) + 1) * gw + 2 * (q % mw) + 1
        }
        dist = IntArray(gw * gh) { -1 }
        depth = IntArray(fills)
        val queue = IntArray(gw * gh)
        for (r in 0 until fills) {
            var head = 0; var tail = 0
            queue[tail++] = seedCell[r]; dist[seedCell[r]] = 0
            while (head < tail) {
                val c = queue[head++]
                depth[r] = max(depth[r], dist[c])
                val x = c % gw; val y = c / gw
                for (d in 0 until 4) {
                    val nx = x + DX[d]; val ny = y + DY[d]
                    if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue
                    val q = ny * gw + nx
                    if (wall[q] || dist[q] >= 0) continue
                    dist[q] = dist[c] + 1
                    queue[tail++] = q
                }
            }
        }
        // Buckets clicked one after another; one speed for every wavefront,
        // chosen so the last room finishes on time.
        val gap = (CLICK_SPREAD / fills).coerceAtMost(0.9f)
        startAt = FloatArray(fills) { INTRO_SECONDS + it * gap }
        var need = 1f
        for (r in 0 until fills) need = max(need, depth[r] / (FILL_END - startAt[r]).coerceAtLeast(0.5f))
        layersPerSecond = need
        lastLayer = IntArray(fills) { -1 }
        frontSum = FloatArray(fills); frontN = IntArray(fills)
        complete = BooleanArray(fills)
        started = BooleanArray(fills)
        clock = 0f; vclock = 0f; doneAt = -1f; payoffFrame = false; sinceSound = 1f; painted = 0
    }

    private fun open(x: Int, y: Int) { wall[(2 * y + 1) * gw + 2 * x + 1] = false }

    private var adj = Array(0) { BooleanArray(0) }
    private var slot = IntArray(0)
    private var colourFor: Palette? = null
    private var roomColours = IntArray(0)

    /**
     * Each room's paint: its palette slot, and where that still sits too close
     * to a touching room's colour (a narrow palette), a lighter or darker
     * shade of it, whichever stands furthest from its neighbours.
     */
    private fun colours(palette: Palette): IntArray {
        if (colourFor === palette && roomColours.size == fills) return roomColours
        val out = IntArray(fills)
        for (r in 0 until fills) {
            val base = deepTone(palette.at((slot[r] + 0.5f) / fills * 0.92f))
            val options = intArrayOf(base, Palette.lerp(base, 0xFFFFFFFF.toInt(), 0.38f), Palette.lerp(base, Palette.BG, 0.42f))
            var best = base; var bestD = -1f
            for ((o, c) in options.withIndex()) {
                var d = Float.MAX_VALUE
                for (q in 0 until r) if (adj[r][q]) d = min(d, colourDistance(c, out[q]))
                // The plain slot colour wins unless it is genuinely too close.
                val score = if (o == 0 && d >= SAME_COLOUR) Float.MAX_VALUE else d
                if (score > bestD) { bestD = score; best = c }
            }
            out[r] = best
        }
        roomColours = out
        colourFor = palette
        return out
    }

    private fun colourDistance(a: Int, b: Int): Float {
        val dr = ((a ushr 16) and 0xFF) - ((b ushr 16) and 0xFF)
        val dg = ((a ushr 8) and 0xFF) - ((b ushr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return kotlin.math.sqrt((dr * dr + dg * dg + db * db).toFloat())
    }

    private fun front(r: Int): Float = (vclock - startAt[r]) * layersPerSecond

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        clock += h
        sinceSound += h
        if (doneAt >= 0f) return
        vclock += h * env.params.f("speed", 1f).coerceIn(0.1f, 8f)
        var all = true
        var total = 0
        // Where each wavefront is across the screen, for the pan of its sound.
        frontSum.fill(0f); frontN.fill(0)
        for (i in 0 until gw * gh) {
            if (wall[i]) continue
            val r = region[i]
            if (r < 0 || dist[i] < 0) continue
            val f = front(r)
            if (dist[i] <= f) {
                total++
                if (dist[i] > f - 1.5f) { frontSum[r] += (i % gw).toFloat(); frontN[r]++ }
            }
        }
        for (r in 0 until fills) {
            val f = front(r)
            val sx = seedCell[r] % gw
            val seedPan = ((sx + 0.5f) / gw * 2f - 1f)
            val pan = if (frontN[r] > 0) ((frontSum[r] / frontN[r] + 0.5f) / gw * 2f - 1f) else seedPan
            if (!started[r] && f >= 0f) {
                started[r] = true
                // The click of the bucket.
                env.emit(EventKind.HIT, (r + 0.5f) / fills, seedPan, 0.7f)
                sinceSound = 0f
            }
            val layer = f.toInt().coerceAtMost(depth[r])
            if (started[r] && !complete[r] && layer >= depth[r]) {
                complete[r] = true
                env.emit(EventKind.BIRTH, 0.4f + 0.5f * (r + 0.5f) / fills, pan, 0.55f)
                sinceSound = 0f
            } else if (started[r] && !complete[r] && layer / 3 != lastLayer[r] / 3 && sinceSound >= 1f / 24f) {
                // A soft tick as the wave runs on, rising as the room fills.
                env.emit(EventKind.STEP, 0.3f + 0.6f * layer / max(1, depth[r]), pan, 0.25f)
                sinceSound = 0f
            }
            if (started[r]) lastLayer[r] = layer
            if (!complete[r]) all = false
        }
        painted = total
        if (all) { doneAt = clock; payoffFrame = true }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val since = if (doneAt >= 0f) clock - doneAt else -1f
        val finale = if (since >= 0f) { val u = (since / 0.8f).coerceIn(0f, 1f); u * u * (3f - 2f * u) } else 0f
        g.reserveInstances(gw * gh + 4)
        val ox = -gw * cell * 0.5f
        val oy = gh * cell * 0.5f
        // The walls darken once the picture is complete, so the painted mazes
        // stand on their own: eased in once and held.
        val wallC = Palette.lerp(WALL, Palette.BG, 0.55f * finale)
        g.rect(0f, 0f, gw * cell * 0.5f + cell * 0.15f, gh * cell * 0.5f + cell * 0.15f, wallC)
        val colours = colours(palette)
        val fronts = FloatArray(fills) { front(it) }
        val hw = cell * 0.5f
        for (y in 0 until gh) {
            val cy = oy - (y + 0.5f) * cell
            for (x in 0 until gw) {
                val i = y * gw + x
                if (wall[i]) continue
                val cx = ox + (x + 0.5f) * cell
                val r = region[i]
                val d = dist[i]
                val f = if (r >= 0) fronts[r] else -1f
                val c = if (r < 0 || d < 0 || d > f) FLOOR else {
                    // The wavefront: the newest three layers burn bright and
                    // cool to the room's colour behind.
                    val behind = f - d
                    // (The run stops at the payoff, so the last room's front
                    // stayed white-hot for good; it cools with the finale.)
                    val hot = (1f - behind / 3f).coerceIn(0f, 1f) * (1f - finale)
                    Palette.lerp(colours[r], Palette.brighten(colours[r], 0.45f), hot * hot)
                }
                // Overlapping a hair, so neighbouring pixels of one colour join.
                g.rect(cx, cy, hw * 1.02f, hw * 1.02f, c)
            }
        }
        // Where each bucket was clicked, a white pixel that fades into its paint.
        for (r in 0 until fills) {
            val f = fronts[r]
            if (f < 0f) continue
            val a = (1f - f / (layersPerSecond * 0.8f)).coerceIn(0f, 1f) * (1f - finale)
            if (a <= 0f) continue
            val s = seedCell[r]
            g.rect(ox + (s % gw + 0.5f) * cell, oy - (s / gw + 0.5f) * cell, hw, hw, Palette.TEXT_PRIMARY, alpha = a)
        }
    }

    override fun frameHalfWidth(env: SimEnv): Float = gw * cell * 0.5f + cell * 0.2f

    override fun frameHalfHeight(env: SimEnv): Float = gh * cell * 0.5f + cell * 0.2f

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && clock - doneAt > 3f

    override fun counters(): List<Pair<String, String>> {
        var done = 0
        for (r in 0 until fills) if (complete[r]) done++
        return listOf(
            "painted" to "${painted * 100 / max(1, floor)}%",
            "rooms done" to "$done / $fills"
        )
    }

    companion object {
        private val DX = intArrayOf(1, 0, -1, 0)
        private val DY = intArrayOf(0, 1, 0, -1)
        private const val INTRO_SECONDS = 0.8f
        private const val CLICK_SPREAD = 4.5f
        private const val FILL_END = 9.5f
        /** Two paints closer than this (RGB distance) read as one room. */
        private const val SAME_COLOUR = 110f
        private const val WALL = 0xFF262D3D.toInt()
        private const val FLOOR = 0xFF0C0F16.toInt()

        fun def(): SimDef = SimDef(
            id = "algorithms/flood-fill",
            title = "Flood fill",
            shelf = Shelf.ALGORITHMS,
            family = "Pathfinding & search",
            blurb = "The paint bucket, breadth first: every corridor it can reach, and not one more.",
            formats = listOf(TrapFormat.FILL, TrapFormat.REVEAL),
            heroLabel = "New picture",
            defaultPalette = "Spectrum",
            typicalSeconds = 12f,
            params = listOf(
                Param.int("fills", "Buckets", 1, 12, 6, card = Card.DATA),
                Param.int("detail", "Detail", 4, 32, 11, card = Card.DATA),
                Param.float("speed", "Speed", 0.25f, 4f, 1f, card = Card.MOTION, unit = "x", logScale = true)
            ),
            create = { FloodFillSim() }
        )
    }
}
