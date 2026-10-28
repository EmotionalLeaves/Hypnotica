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
    private var decidedAt = -1f
    private var taken = 0L
    private var halfW = 0.46f
    private var takenBy = IntArray(0)
    private var pendingSpawn = IntArray(0)

    override fun setup(env: SimEnv) {
        val p = env.params
        teams = p.i("colours", 4).coerceIn(2, 4)
        cols = p.i("squares", 28).coerceIn(12, 160)
        halfW = env.halfWidth
        cell = 2f * halfW / cols
        rows = max(8, (2f / cell).toInt())
        owner = ByteArray(cols * rows)
        for (r in 0 until rows) for (c in 0 until cols) owner[r * cols + c] = startOwner(c, r).toByte()
        val per = p.i("balls", 2).coerceIn(1, 6)
        val n = teams * per
        bx = FloatArray(n); by = FloatArray(n); bvx = FloatArray(n); bvy = FloatArray(n)
        bteam = IntArray(n) { it % teams }
        alive = BooleanArray(n) { true }
        for (i in 0 until n) {
            val (cx, cy) = homeOf(bteam[i])
            bx[i] = cx + env.rng.range(-0.05f, 0.05f)
            by[i] = cy + env.rng.range(-0.05f, 0.05f)
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
        decidedAt = -1f
        taken = 0L
    }

    /** Halves, thirds or quarters of the board. */
    private fun startOwner(c: Int, r: Int): Int = when (teams) {
        2 -> if (r < rows / 2) 0 else 1
        3 -> min(2, r * 3 / rows)
        else -> (if (r < rows / 2) 0 else 2) + (if (c < cols / 2) 0 else 1)
    }

    /** The middle of a team's starting ground, in world units. */
    private fun homeOf(t: Int): Pair<Float, Float> {
        val (c, r) = when (teams) {
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
                env.emit(EventKind.DEATH, t / max(1f, teams - 1f), 0f, 0.9f)
                // A colour with ground but no balls left keeps its ground: it
                // just cannot win any more. The payoff is the first one out.
                if (!decided) decide(env)
            }
        }
        // Or a colour holding over half the board.
        if (!decided && elapsed > 3f) {
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
        env.emit(EventKind.BIRTH, (t + 0.5f) / teams, 0f, 0.6f)
    }

    private fun decide(env: SimEnv) {
        decided = true
        decidedAt = elapsed
        payoffFrame = true
        env.emit(EventKind.DONE, 0.9f, 0f, 1f)
    }

    private fun ownerAt(x: Float, y: Float): Int {
        val c = ((x + halfW) / cell).toInt().coerceIn(0, cols - 1)
        val r = ((1f - y) / cell).toInt().coerceIn(0, rows - 1)
        return owner[r * cols + c].toInt()
    }

    private fun take(env: SimEnv, x: Float, y: Float, t: Int) {
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
        val register = (t + 0.5f) / teams
        env.emit(EventKind.WRITE, (register * 0.7f + 0.3f * (c.toFloat() / cols)).coerceIn(0f, 1f),
            (c.toFloat() / (cols - 1)) * 1.6f - 0.8f, 0.4f)
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val colours = IntArray(teams) { palette.at(0.08f + 0.84f * it / max(1f, teams - 1f)) }
        val dark = IntArray(teams) { Palette.withAlpha(colours[it], 0.85f) }
        g.reservePoints(cols * rows)
        for (r in 0 until rows) {
            val y = 1f - (r + 0.5f) * cell
            for (c in 0 until cols) {
                val o = owner[r * cols + c].toInt()
                g.point(-halfW + (c + 0.5f) * cell, y, cell * 0.92f, dark[o])
            }
        }
        for (i in bx.indices) {
            if (!alive[i]) continue
            // Each ball wears the colour of the ground it fights for, bright
            // against its own territory.
            val c = Palette.brighten(colours[bteam[i]], 0.55f)
            g.glow(bx[i], by[i], BALL_R * 3.5f, c, 0.55f)
            g.disc(bx[i], by[i], BALL_R, Palette.TEXT_PRIMARY)
            g.disc(bx[i], by[i], BALL_R * 0.6f, c)
        }
    }

    override fun payoff(): Boolean = payoffFrame

    /** A while after the first one goes out, or someone takes the whole board. */
    override fun finished(): Boolean =
        (decided && elapsed - decidedAt > AFTER_SECONDS) || counts.any { it == owner.size }

    override fun counters(): List<Pair<String, String>> = (0 until teams).map { t ->
        val share = counts[t] * 100f / max(1, owner.size)
        "colour ${t + 1}" to if (out[t] && counts[t] == 0) "out" else String.format(java.util.Locale.US, "%.0f%%", share)
    }

    companion object {
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
            defaultPalette = "Candy",
            typicalSeconds = 24f,
            params = listOf(
                Param.int("colours", "Colours", 2, 4, 4, card = Card.DATA),
                Param.int("squares", "Squares across", 12, 160, 28, card = Card.DATA),
                Param.int("balls", "Balls each", 1, 6, 2, card = Card.DATA),
                Param.float("pace", "Pace", 0.3f, 4f, 1.8f, card = Card.MOTION)
            ),
            create = { ColourWarSim() }
        )
    }
}
