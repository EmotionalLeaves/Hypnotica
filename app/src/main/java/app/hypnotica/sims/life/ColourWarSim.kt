package app.hypnotica.sims.life

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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Colour war: the board split between colours, each with balls of its own
 * that roam freely through their own ground and bounce off anyone else's —
 * taking the square they hit. Borders surge and sag, a colour pinned in a
 * corner breaks out, and a colour whose ground is all taken is out.
 *
 * Every square taken is a note in the taker's register, so the battle is a
 * call and answer between the colours, on the beat.
 */
class ColourWarSim : Sim {

    private var cols = 40
    private var rows = 80
    private var cell = 0.05f
    private var teams = 4
    private var owner = ByteArray(0)
    private var bx = FloatArray(0)
    private var by = FloatArray(0)
    private var bvx = FloatArray(0)
    private var bvy = FloatArray(0)
    private var bteam = IntArray(0)
    private var alive = BooleanArray(0)
    private var counts = IntArray(0)
    private var out = BooleanArray(0)
    private var elapsed = 0f
    private var payoffFrame = false
    private var decided = false
    private var winnerTeam = -1
    private var decidedAt = -1f
    private var taken = 0L
    private var halfW = 0.46f
    private var takenBy = IntArray(0)
    private var pendingSpawn = IntArray(0)
    // How the board is split at the start: blocks, interleaved stripes, or
    // islands round a few random points per colour.
    private var layout = 0
    private var stripeRounds = 2
    private var seedC = FloatArray(0)
    private var seedR = FloatArray(0)

    override fun setup(env: SimEnv) {
        val p = env.params
        teams = p.i("colours", 4).coerceIn(2, 4)
        lostAtX.fill(0f)
        cols = p.i("squares", 28).coerceIn(12, 160)
        halfW = env.halfWidth
        cell = 2f * halfW / cols
        rows = max(8, (2f / cell).toInt())
        layout = p.choiceIndex("layout").coerceIn(0, 2)
        // Each stripe at least a few squares deep, or its balls were
        // swallowed before they'd moved: on a wide screen, fewer rounds.
        stripeRounds = (rows / (teams * MIN_STRIPE)).coerceIn(1, 2)
        owner = ByteArray(cols * rows)
        // Islands drawn again until the split is roughly fair: a colour
        // starting with half the board had won before anything moved.
        var tries = 0
        do {
            // At square centres, so the square a home is in is decided by
            // that home's own island: off-centre, a neighbour could own it
            // and the colour's balls began on enemy ground.
            seedC = FloatArray(teams * ISLANDS) { env.rng.int(0, cols) + 0.5f }
            seedR = FloatArray(teams * ISLANDS) { env.rng.int(0, rows) + 0.5f }
            for (r in 0 until rows) for (c in 0 until cols) owner[r * cols + c] = startOwner(c, r).toByte()
            val share = IntArray(teams); for (o in owner) share[o.toInt()]++
            val fair = owner.size / teams.toFloat()
            // And a little room round it: two homes a square apart had one
            // colour's balls taking the other's ground in the first frame.
            val homesOwn = (0 until teams).all { t ->
                val hc = seedC[t].toInt(); val hr = seedR[t].toInt()
                (-1..1).all { dr -> (-1..1).all { dc ->
                    val rr = (hr + dr).coerceIn(0, rows - 1); val cc = (hc + dc).coerceIn(0, cols - 1)
                    owner[rr * cols + cc].toInt() == t
                } }
            }
        } while (layout == 2 && tries++ < 80 && (!homesOwn || share.any { it < fair * 0.7f || it > fair * 1.3f }))
        val per = p.i("balls", 2).coerceIn(1, 6)
        val n = teams * per
        bx = FloatArray(n); by = FloatArray(n); bvx = FloatArray(n); bvy = FloatArray(n)
        bteam = IntArray(n) { it % teams }
        alive = BooleanArray(n) { true }
        for (i in 0 until n) {
            val (cx, cy) = homeOf(bteam[i])
            // Close in on an island's centre: a small one could leave a ball
            // dropped just outside it, swallowed before it moved.
            val j = if (layout == 2) cell * 0.3f else 0.05f
            bx[i] = cx + env.rng.range(-j, j)
            by[i] = cy + env.rng.range(-j, j)
            val a = env.rng.range(0f, 2f * PI.toFloat())
            bvx[i] = cos(a) * SPEED; bvy[i] = sin(a) * SPEED
        }
        counts = IntArray(teams)
        out = BooleanArray(teams)
        takenBy = IntArray(teams)
        pendingSpawn = IntArray(teams)
        recount()
        elapsed = 0f
        payoffFrame = false
        decided = false
        winnerTeam = -1
        decidedAt = -1f
        taken = 0L
    }

    /** Halves, thirds or quarters of the board. */
    private fun startOwner(c: Int, r: Int): Int = when {
        layout == 1 -> (r * teams * stripeRounds / rows) % teams
        layout == 2 -> {
            var best = 0; var bestD = Float.MAX_VALUE
            for (k in seedC.indices) {
                val dx = c + 0.5f - seedC[k]; val dy = r + 0.5f - seedR[k]
                val d = dx * dx + dy * dy
                if (d < bestD) { bestD = d; best = k }
            }
            best % teams
        }
        teams == 2 -> if (r < rows / 2) 0 else 1
        teams == 3 -> min(2, r * 3 / rows)
        else -> (if (r < rows / 2) 0 else 2) + (if (c < cols / 2) 0 else 1)
    }

    /** The middle of a team's starting ground, in world units. */
    private fun homeOf(t: Int): Pair<Float, Float> {
        val (c, r) = if (layout == 1) {
            // The middle of the colour's first stripe.
            cols / 2f to (t + 0.5f) * rows / (teams * stripeRounds).toFloat()
        } else if (layout == 2) {
            // Its first island's centre, which is always its own.
            seedC[t] to seedR[t]
        } else when (teams) {
            2 -> cols / 2f to (if (t == 0) rows * 0.25f else rows * 0.75f)
            3 -> cols / 2f to rows * (t + 0.5f) / 3f
            else -> (if (t % 2 == 0) cols * 0.25f else cols * 0.75f) to (if (t < 2) rows * 0.25f else rows * 0.75f)
        }
        return -halfW + c * cell to 1f - r * cell
    }

    private fun recount() {
        java.util.Arrays.fill(counts, 0)
        for (o in owner) counts[o.toInt()]++
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        val base = abs(dt) * env.params.f("pace", 1.8f)
        tickCarry = min(1.5f, tickCarry + abs(dt) * 6f)
        takeCarry = min(2f, takeCarry + abs(dt) * 30f)
        for (i in bx.indices) {
            if (!alive[i]) continue
            val t = bteam[i]
            // Momentum: the more ground a colour holds, the faster its balls
            // move. Left even, this battle settles at a quarter each and
            // stays there — every border defended exactly as hard as it is
            // attacked — and nobody watches a draw.
            val share = counts[t].toFloat() * teams / max(1, owner.size)
            val whole = base * share.coerceIn(0.2f, 2.5f).let { it * it }
            // In slices no longer than a third of a square, so a fast ball
            // cannot step clean over the square it should have hit.
            val sp0 = sqrt(bvx[i] * bvx[i] + bvy[i] * bvy[i])
            val slices = kotlin.math.ceil(sp0 * whole / (cell * 0.33f)).toInt().coerceIn(1, 32)
            val h = whole / slices
            repeat(slices) {
            if (!alive[i]) return@repeat
            // Swallowed: the square under the ball has been taken. It is out
            // of the game, and if it was its colour's last, so is the colour.
            if (ownerAt(bx[i], by[i]) != t) {
                alive[i] = false
                lostAtX[t] = bx[i]
                return@repeat
            }
            // Across, then down, each axis checked on its own so a corner
            // hit takes the square in the way rather than one diagonally off.
            val nx = bx[i] + bvx[i] * h
            val edgeX = nx + (if (bvx[i] > 0f) BALL_R else -BALL_R)
            if (edgeX < -halfW || edgeX > halfW) bvx[i] = -bvx[i]
            else if (ownerAt(edgeX, by[i]) != t) { take(env, edgeX, by[i], t); bvx[i] = -bvx[i] }
            else bx[i] = nx
            val ny = by[i] + bvy[i] * h
            val edgeY = ny + (if (bvy[i] > 0f) BALL_R else -BALL_R)
            if (edgeY > 1f || edgeY < 1f - rows * cell) bvy[i] = -bvy[i]
            else if (ownerAt(bx[i], edgeY) != t) { take(env, bx[i], edgeY, t); bvy[i] = -bvy[i] }
            else by[i] = ny
            // A whisker of noise in the heading, so a ball does not lock into
            // one bounce path forever.
            val a = env.rng.range(-WOBBLE, WOBBLE)
            val c = cos(a); val s = sin(a)
            val vx = bvx[i] * c - bvy[i] * s
            val vy = bvx[i] * s + bvy[i] * c
            bvx[i] = vx; bvy[i] = vy
            // Never too flat, or a ball skims a border for a minute.
            val sp = sqrt(bvx[i] * bvx[i] + bvy[i] * bvy[i])
            if (abs(bvy[i]) < sp * 0.2f) bvy[i] = (if (bvy[i] >= 0f) 1f else -1f) * sp * 0.2f
            }
        }
        for (t in 0 until teams) {
            while (pendingSpawn[t] > 0) {
                pendingSpawn[t]--
                spawn(env, t)
            }
        }
        for (t in 0 until teams) {
            if (!out[t] && (counts[t] == 0 || bx.indices.none { alive[it] && bteam[it] == t })) {
                out[t] = true
                // Heard where its last ball was swallowed.
                env.emit(EventKind.DEATH, t / max(1f, teams - 1f), (lostAtX[t] / halfW).coerceIn(-1f, 1f), 0.9f)
                // A colour with ground but no balls left keeps its ground: it
                // just cannot win any more. The payoff is the first one out.
            }
        }
        // But not in the opening seconds: stripes and islands put a colour's
        // balls in narrow ground, and one swallowed at once ended the war
        // before it was a war. Its ground stays on the board meanwhile.
        if (!decided && elapsed > EARLIEST && out.any { it }) decide(env)
        // Whatever the clock: one colour holding the whole board has won — it
        // used to end there with no verdict at all. And a war on a big board
        // that would take minutes is called for whoever holds most.
        if (!decided && (counts.any { it == owner.size } || elapsed > DECIDE_BY)) decide(env)
        // Or a colour holding over half the board.
        if (!decided && elapsed > EARLIEST) {
            // Twice a fair share: half the board of four, two thirds of three.
            val dominant = min(0.8f, 2f / teams)
            for (t in 0 until teams) if (counts[t] > owner.size * dominant) { decide(env); break }
        }
    }

    /** A new ball for [t], from one of its live balls, heading off at random. */
    private fun spawn(env: SimEnv, t: Int) {
        val from = bx.indices.filter { alive[it] && bteam[it] == t }
        if (from.isEmpty() || from.size >= MAX_BALLS_EACH) return
        val src = from[env.rng.int(0, from.size)]
        val a = env.rng.range(0f, 2f * PI.toFloat())
        bx = bx + bx[src]; by = by + by[src]
        bvx = bvx + cos(a) * SPEED; bvy = bvy + sin(a) * SPEED
        bteam = bteam + t
        alive = alive + true
        env.emit(EventKind.BIRTH, (t + 0.5f) / teams, (bx[src] / halfW).coerceIn(-1f, 1f), 0.6f)
    }

    private fun decide(env: SimEnv) {
        decided = true
        // Whoever holds the most ground when it is settled.
        winnerTeam = (0 until teams).maxByOrNull { counts[it] } ?: -1
        decidedAt = elapsed
        payoffFrame = true
    }

    private var tickCarry = 0f
    private var takeCarry = 0f
    private val lostAtX = FloatArray(8)

    private fun ownerAt(x: Float, y: Float): Int {
        val c = ((x + halfW) / cell).toInt().coerceIn(0, cols - 1)
        val r = ((1f - y) / cell).toInt().coerceIn(0, rows - 1)
        return owner[r * cols + c].toInt()
    }

    private fun take(env: SimEnv, x: Float, y: Float, t: Int) {
        // Once it's called, the borders hold: squares kept changing hands
        // after the verdict, and the colour named could end up behind.
        if (decided) {
            // The balls go on bouncing off the frozen borders, and that is
            // heard, softly and sparsely: silent, the twelve seconds after the
            // verdict were a moving picture with no sound at all.
            if (tickCarry >= 1f) {
                tickCarry -= 1f
                val t0 = (t + 0.5f) / teams
                env.emit(EventKind.HIT, t0, (x / halfW).coerceIn(-1f, 1f), 0.15f)
            }
            return
        }
        val c = ((x + halfW) / cell).toInt().coerceIn(0, cols - 1)
        val r = ((1f - y) / cell).toInt().coerceIn(0, rows - 1)
        val i = r * cols + c
        val was = owner[i].toInt()
        if (was == t) return
        counts[was]--
        counts[t]++
        owner[i] = t.toByte()
        taken++
        // Winning ground wins reinforcements: every so many squares, another
        // ball. A lead feeds itself, so the war swings instead of sitting at
        // a quarter each for ever.
        takenBy[t]++
        if (takenBy[t] % REINFORCE_EVERY == 0) pendingSpawn[t]++
        // Each colour has its own register, so the battle sounds like voices.
        // Paced to about thirty a second: in the opening scramble squares
        // change hands three hundred times a second, one merged wash. Panned
        // to the square's column across the board.
        if (takeCarry >= 1f) {
            takeCarry -= 1f
            val register = (t + 0.5f) / teams
            env.emit(EventKind.WRITE, (register * 0.7f + 0.3f * (c.toFloat() / cols)).coerceIn(0f, 1f),
                ((c + 0.5f) / cols) * 2f - 1f, 0.4f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val colours = IntArray(teams) { palette.at(0.08f + 0.84f * it / max(1f, teams - 1f)) }
        // The ground as solid territory, a rect per run of one owner, with a
        // dark seam where two owners meet. Each square used to be a round
        // dot, so the board was a dot grid and a border was only a change of
        // dot colour. Toned a little towards the background, so the balls,
        // in the full colour and ringed in black, stand out on their own
        // ground; much further and a yellow went to mud.
        val glowK = if (decided) (1f - (elapsed - decidedAt) / 1.2f).coerceIn(0f, 1f) else 0f
        val fill = IntArray(teams) {
            // 0.64, from 0.78: at 0.78 the four slabs filled an upright phone
            // edge to edge at near full strength and shouted over the balls,
            // the fight itself; a step darker the balls lead and a yellow
            // still reads as yellow.
            val base = Palette.lerp(Palette.BG, colours[it], 0.64f)
            // The verdict: the winner's ground lights up, and fades back. Up
            // to its own full colour: brightened past it, half the board
            // flashed a raw neon that swamped the balls.
            if (it == winnerTeam && glowK > 0f) Palette.lerp(base, colours[it], glowK * 0.8f) else base
        }
        for (r in 0 until rows) {
            val y = 1f - (r + 0.5f) * cell
            val row = r * cols
            g.cellRow(-halfW, y, cell, cols) { c -> fill[owner[row + c].toInt()] }
        }
        val seam = Palette.withAlpha(Palette.BG, 0.85f)
        val sw = max(cell * 0.12f, 0.003f)
        for (r in 0 until rows) {
            val row = r * cols
            val yTop = 1f - r * cell
            for (c in 0 until cols) {
                val o = owner[row + c]
                // Seams on the right and below; the edges of the board are
                // the screen's.
                if (c + 1 < cols && owner[row + c + 1] != o) {
                    val x = -halfW + (c + 1) * cell
                    g.rect(x, yTop - cell * 0.5f, sw * 0.5f, cell * 0.5f + sw * 0.5f, seam)
                }
                if (r + 1 < rows && owner[row + cols + c] != o) {
                    val x = -halfW + (c + 0.5f) * cell
                    g.rect(x, yTop - cell, cell * 0.5f + sw * 0.5f, sw * 0.5f, seam)
                }
            }
        }
        for (i in bx.indices) {
            if (!alive[i]) continue
            // Each ball wears the colour of the ground it fights for, bright
            // against its own territory.
            val c = colours[bteam[i]]
            g.glow(bx[i], by[i], BALL_R * 3f, c, 0.45f)
            g.disc(bx[i], by[i], BALL_R * 1.2f, Palette.BG)
            g.disc(bx[i], by[i], BALL_R, c)
            g.disc(bx[i], by[i], BALL_R * 0.4f, Palette.TEXT_PRIMARY)
        }
    }

    override fun payoff(): Boolean = payoffFrame

    /** A while after the first one goes out, or someone takes the whole board. */
    override fun finished(): Boolean =
        (decided && elapsed - decidedAt > AFTER_SECONDS) || counts.any { it == owner.size }

    override fun contenders(env: SimEnv): IntArray =
        IntArray(teams) { env.palette.at(0.08f + 0.84f * it / max(1f, teams - 1f)) }

    override fun winner(): Int = winnerTeam

    override fun counters(): List<Pair<String, String>> = (0 until teams).map { t ->
        val share = counts[t] * 100f / max(1, owner.size)
        "colour ${t + 1}" to if (out[t] && counts[t] == 0) "out" else String.format(java.util.Locale.US, "%.0f%%", share)
    }

    companion object {
        const val ISLANDS = 3
        /** No verdict before this: the opening is the show. */
        const val EARLIEST = 8f
        /** No war runs on longer than this before it is called. */
        const val DECIDE_BY = 70f
        const val MIN_STRIPE = 5
        const val BALL_R = 0.018f
        const val SPEED = 1.3f
        const val WOBBLE = 0.004f
        const val REINFORCE_EVERY = 30
        const val MAX_BALLS_EACH = 12
        const val AFTER_SECONDS = 12f

        fun def(): SimDef = SimDef(
            id = "life/colour-war",
            title = "Colour war",
            shelf = Shelf.LIFE,
            family = "Territory & competition",
            blurb = "Each colour's balls take the squares they bounce off. Who holds the board?",
            formats = listOf(TrapFormat.COLOR_BATTLE, TrapFormat.LAST_STANDING, TrapFormat.SONG),
            heroLabel = "New war",
            defaultPalette = "Aurora",
            typicalSeconds = 24f,
            params = listOf(
                Param.choice("layout", "Battlefield", listOf("Blocks", "Stripes", "Islands"), 0, card = Card.DATA, input = ParamInput.TILES),
                Param.int("colours", "Colours", 2, 4, 4, card = Card.DATA),
                Param.int("squares", "Squares across", 12, 160, 28, card = Card.DATA),
                Param.int("balls", "Balls each", 1, 6, 2, card = Card.DATA),
                Param.float("pace", "Pace", 0.3f, 4f, 1.8f, card = Card.MOTION)
            ),
            create = { ColourWarSim() }
        )
    }
}
