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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Rock, paper, scissors, as armies. Every piece chases what it beats and runs
 * from what beats it; touch your prey and it joins your side. Nobody is ever
 * safe — each side is always both hunter and hunted — so the lead swings, a
 * side that looked finished comes back, and in the end only one is left.
 */
class RpsBattleSim : Sim {

    private var n = 0
    private var x = FloatArray(0)
    private var y = FloatArray(0)
    private var vx = FloatArray(0)
    private var vy = FloatArray(0)
    private var side = IntArray(0)
    private var flashT = FloatArray(0)
    private var legs = FloatArray(0)
    private var quiet = 0f
    private var frenzy = 0f
    private var frenzySide = 0
    private val count = IntArray(MAX_TEAMS)
    private var teams = 3
    private var halfW = 0.46f
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var winner = -1
    private val out = BooleanArray(MAX_TEAMS)
    private var size = 0.025f
    private var grip = 0.025f

    override fun setup(env: SimEnv) {
        val per = env.params.i("each", 40).coerceIn(3, MAX_EACH)
        // Three armies, or five: rock paper scissors lizard Spock, where
        // each side beats two and loses to two.
        teams = if (env.params.choiceIndex("game") == 1) 5 else 3
        n = per * teams
        halfW = env.halfWidth
        // 0.027 at forty a side, from 0.035: at the old size a hundred and
        // twenty pieces covered a quarter of an upright phone, and every
        // clash was a heap of shapes on top of one another.
        // Scaled by the number of sides too: five armies of the same size
        // as three started overlapping one another.
        size = (0.027f * sqrt(40f / per) * sqrt(3f / teams)).coerceIn(0.008f, 0.045f)
        // How close a catch is, kept as it was tuned: drawing them bigger
        // shouldn't make the battle shorter.
        grip = (0.025f * sqrt(40f / per)).coerceIn(0.008f, 0.04f)
        x = FloatArray(n); y = FloatArray(n); vx = FloatArray(n); vy = FloatArray(n)
        side = IntArray(n); flashT = FloatArray(n)
        // Some are quicker than others, or a chase at equal speed never ends.
        legs = FloatArray(n) { env.rng.range(LEGS_MIN, LEGS_MAX) }
        // Each side starts in its own corner of a triangle (or pentagon), so
        // the first clash is a charge rather than a scramble.
        // Packed in a sunflower spiral round its corner, with a little
        // jitter: a random scatter in a box put pieces on top of each other,
        // so the opening frames were a heap rather than three armies.
        val radius = sqrt(per.toFloat()) * size * 1.25f
        for (i in 0 until n) {
            val s = i % teams
            val k = i / teams
            side[i] = s
            // On an upright screen the triangle lies on its side, one army on
            // the right and two stacked on the left: pointing up, the two
            // bottom armies had less than the narrow width between them and
            // started already touching.
            val turn = if (halfW < 0.8f) 0.0 else PI / 2
            val a = (turn + s * 2 * PI / teams).toFloat()
            val cx = kotlin.math.cos(a) * (halfW - radius - size).coerceAtLeast(0.1f) * 0.95f
            val cy = kotlin.math.sin(a) * (if (halfW < 0.8f) 0.62f else 0.5f)
            val rr = radius * sqrt((k + 0.5f) / per)
            val th = k * GOLDEN_ANGLE
            x[i] = cx + kotlin.math.cos(th) * rr + env.rng.range(-0.15f, 0.15f) * size
            y[i] = cy + kotlin.math.sin(th) * rr + env.rng.range(-0.15f, 0.15f) * size
        }
        count.fill(0)
        for (t in 0 until teams) count[t] = per
        out.fill(false)
        elapsed = 0f
        doneAt = -1f
        payoffFrame = false
        winner = -1
        quiet = 0f
        frenzy = 0f
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        if (winner >= 0) {
            // The winners spread out over the field they have taken, rather
            // than milling in the clump the last catch left them in.
            for (i in 0 until n) {
                var sx = 0f; var sy = 0f
                val far = 4f * size
                for (j in 0 until n) {
                    if (j == i) continue
                    val dx = x[i] - x[j]; val dy = y[i] - y[j]
                    val d2 = dx * dx + dy * dy
                    if (d2 < far * far && d2 > 1e-10f) {
                        val d = sqrt(d2)
                        val k = (1f - d / far) / d
                        sx += dx * k; sy += dy * k
                    }
                }
                // The walls push too, or the spreading crowd packed itself
                // into rows along the edges.
                val wx = halfW - size; val wy = 0.86f - size
                if (x[i] < -wx + far) sx += 2f * (1f - (x[i] + wx) / far)
                if (x[i] > wx - far) sx -= 2f * (1f - (wx - x[i]) / far)
                if (y[i] < -1f + size + far) sy += 2f * (1f - (y[i] + 1f - size) / far)
                if (y[i] > wy - far) sy -= 2f * (1f - (wy - y[i]) / far)
                // The last catch's flash fades here too: this branch returns
                // before the battle's own fade, and the final convert stayed
                // a big white disc for the rest of the run.
                if (flashT[i] > 0f) flashT[i] = max(0f, flashT[i] - h * 3f)
                // Gathered towards the middle and turning slowly round it: the
                // victors' lap of honour. Spread evenly over the field by
                // their spacing alone they set into a stiff grid of shapes.
                sx += -x[i] * GATHER - y[i] * TURN
                sy += -y[i] * GATHER + x[i] * TURN
                vx[i] = vx[i] * (1f - 2f * h) + (sx * 0.6f + env.rng.range(-1f, 1f)) * h
                vy[i] = vy[i] * (1f - 2f * h) + (sy * 0.6f + env.rng.range(-1f, 1f)) * h
                move(i, h)
            }
            return
        }
        // It speeds up the longer it goes: no stand-off lasts.
        val pace = env.params.f("pace", 1f) * (1f + elapsed / RAMP_SECONDS)
        val speed = BASE_SPEED * pace
        // And as it drags on, fear fades and reach grows: sudden death.
        val late = elapsed / RAMP_SECONDS
        val reach = grip * 1.6f * (1f + 0.5f * late)
        val fearNow = if (frenzy > 0f) 0f else FEAR / (1f + late)
        var converted = 0
        var convertedSide = 0
        for (i in 0 until n) {
            val s = side[i]
            var bestPrey = Float.MAX_VALUE; var px = 0f; var py = 0f
            var bestHunter = Float.MAX_VALUE; var hx = 0f; var hy = 0f
            // Elbow room among a piece's own side: without it a side moved as
            // one heap of overlapping pieces, illegible as rocks or scissors.
            var sepX = 0f; var sepY = 0f
            val room = 2.2f * size
            for (j in 0 until n) {
                val t = side[j]
                if (t == s) {
                    if (j == i) continue
                    val dx = x[i] - x[j]; val dy = y[i] - y[j]
                    val d2 = dx * dx + dy * dy
                    if (d2 < room * room && d2 > 1e-10f) {
                        val d = sqrt(d2)
                        sepX += dx / d * (1f - d / room); sepY += dy / d * (1f - d / room)
                        // And never more than a little overlap, whatever
                        // the chase wants: a side all heading for the same
                        // prey piled into one heap otherwise. Positions only;
                        // the velocity is never read back from this.
                        val touch = 2f * size
                        if (d < touch && j > i) {
                            val push = (touch - d) * 0.5f / d
                            x[i] += dx * push; y[i] += dy * push
                            x[j] -= dx * push; y[j] -= dy * push
                        }
                    }
                    continue
                }
                val dx = x[j] - x[i]; val dy = y[j] - y[i]
                val d2 = dx * dx + dy * dy
                if (beats(s, t)) { if (d2 < bestPrey) { bestPrey = d2; px = dx; py = dy } }
                else if (d2 < bestHunter) { bestHunter = d2; hx = dx; hy = dy }
            }
            var ax = 0f; var ay = 0f
            if (bestPrey < Float.MAX_VALUE) {
                val d = sqrt(bestPrey).coerceAtLeast(1e-4f)
                ax += px / d; ay += py / d
            }
            if (bestHunter < Float.MAX_VALUE) {
                val d = sqrt(bestHunter).coerceAtLeast(1e-4f)
                // Fear fades with distance: a hunter across the screen
                // matters less than prey close by.
                val fear = fearNow * (0.35f / (d + 0.35f))
                ax -= hx / d * fear; ay -= hy / d * fear
            }
            ax += sepX * SPACING; ay += sepY * SPACING
            ax += env.rng.range(-JITTER, JITTER)
            ay += env.rng.range(-JITTER, JITTER)
            val am = sqrt(ax * ax + ay * ay).coerceAtLeast(1e-4f)
            val sp = speed * legs[i] * (if (frenzy > 0f && s == frenzySide) FRENZY_SPEED else 1f)
            vx[i] += (ax / am * sp - vx[i]) * min(1f, h * 5f)
            vy[i] += (ay / am * sp - vy[i]) * min(1f, h * 5f)
            move(i, h)
            // Caught.
            if (bestPrey < reach * reach) {
                // The nearest prey: find it again by position, it was not kept.
                for (j in 0 until n) {
                    val prey = side[j]
                    if (prey == s || !beats(s, prey)) continue
                    val dx = x[j] - x[i]; val dy = y[j] - y[i]
                    if (dx * dx + dy * dy < reach * reach) {
                        side[j] = s
                        count[prey]--; count[s]++
                        flashT[j] = 1f
                        converted++
                        convertedSide = s
                        break
                    }
                }
            }
        }
        for (i in 0 until n) if (flashT[i] > 0f) flashT[i] = max(0f, flashT[i] - h * 3f)
        // Three sides can fall into a ring, each chasing its prey round at
        // the speed its prey flees, and nobody is ever caught. A few quiet
        // seconds and a stampede breaks the ring up.
        quiet = if (converted > 0) 0f else quiet + h
        if (quiet > QUIET_SECONDS) {
            quiet = 0f
            hero(env)
            // And for a moment nobody flees, only chases: five sides in a
            // ring re-formed the ring after a stampede and circled for good.
            frenzy = FRENZY_SECONDS
            // And one side, at random, is quicker for it: with everyone
            // chasing at the same speed the ring only turned faster.
            // One still in it: a frenzy for a side already gone was wasted.
            val living = (0 until teams).filter { count[it] > 0 }
            frenzySide = if (living.isEmpty()) 0 else living[env.rng.int(0, living.size)]
        }
        frenzy = max(0f, frenzy - h)
        if (converted > 0) {
            // Each side has its own note; a burst is louder, not more notes.
            env.emit(EventKind.HIT, note(convertedSide), 0f, min(1f, 0.3f + 0.12f * converted))
        }
        // A side wiped out is a beat of its own.
        for (s in 0 until teams) if (!out[s] && count[s] == 0) {
            out[s] = true
            env.emit(EventKind.STEP, note(s), 0f, 0.8f)
        }
        val alive = (0 until teams).count { count[it] > 0 }
        if (alive == 1) {
            winner = (0 until teams).first { count[it] > 0 }
            doneAt = elapsed
            payoffFrame = true
            env.emit(EventKind.DONE, note(winner), 0f, 1f)
        }
    }

    /**
     * Whether side [s] beats side [t]: in a ring of an odd number of sides,
     * each beats the half of the others that come after the halfway point —
     * scissors for rock with three; scissors and lizard with five.
     */
    private fun beats(s: Int, t: Int): Boolean = ((t - s + teams) % teams) >= (teams + 1) / 2

    /**
     * Which thing side [s] is. With five, round the ring each beats the two
     * before it only in this order — rock, Spock, paper, lizard, scissors —
     * which is exactly the game's rules.
     */
    private fun kindOf(s: Int): Int = if (teams == 3) s else FIVE_ORDER[s]

    private fun note(s: Int): Float = 0.1f + 0.8f * s / (teams - 1f)

    private fun colour(env: SimEnv, s: Int): Int = env.palette.at(0.08f + 0.84f * s / (teams - 1f))

    private fun move(i: Int, h: Float) {
        x[i] += vx[i] * h
        y[i] += vy[i] * h
        val wx = halfW - size; val wy = 0.86f - size
        if (x[i] < -wx) { x[i] = -wx; vx[i] = abs(vx[i]) }
        if (x[i] > wx) { x[i] = wx; vx[i] = -abs(vx[i]) }
        if (y[i] < -1f + size) { y[i] = -1f + size; vy[i] = abs(vy[i]) }
        if (y[i] > wy) { y[i] = wy; vy[i] = -abs(vy[i]) }
    }

    /** A stampede: everyone flung outward, the battle lines scrambled. */
    override fun hero(env: SimEnv) {
        if (winner >= 0) return
        for (i in 0 until n) {
            val d = sqrt(x[i] * x[i] + y[i] * y[i]).coerceAtLeast(0.05f)
            vx[i] += x[i] / d * 1.4f + env.rng.range(-0.5f, 0.5f)
            vy[i] += y[i] / d * 1.4f + env.rng.range(-0.5f, 0.5f)
        }
        env.emit(EventKind.STEP, 0.5f, 0f, 0.9f)
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val colors = IntArray(teams) { colour(env, it) }
        for (i in 0 until n) {
            val s = side[i]
            // A catch flashes, briefly and not to white: at full strength, in
            // a big five-sided battle a third of the field was pale blobs.
            val c = if (flashT[i] > 0f) Palette.brighten(colors[s], 0.35f * flashT[i]) else colors[s]
            val r = size * (1f + 0.35f * flashT[i])
            when (kindOf(s)) {
                // Rock: a stone.
                0 -> g.disc(x[i], y[i], r, c)
                // Paper: a sheet, a little tilted with its heading.
                // Each its own tilt as well, or a side of sheets lined up
                // like tiles.
                1 -> g.rect(x[i], y[i], r * 0.8f, r, c, (vx[i] * 1.5f).coerceIn(-0.4f, 0.4f) + (legs[i] - 1f) * 4f)
                // Lizard: a ring.
                3 -> g.ring(x[i], y[i], r, r * 0.35f, c)
                // Spock: a diamond.
                4 -> g.rect(x[i], y[i], r * 0.75f, r * 0.75f, c, (PI / 4).toFloat())
                // Scissors: two crossed blades.
                else -> {
                    g.rect(x[i], y[i], r * 1.1f, r * 0.22f, c, 0.6f)
                    g.rect(x[i], y[i], r * 1.1f, r * 0.22f, c, -0.6f)
                    g.ring(x[i] - r * 0.7f, y[i] - r * 0.55f, r * 0.32f, r * 0.12f, c)
                    g.ring(x[i] + r * 0.7f, y[i] - r * 0.55f, r * 0.32f, r * 0.12f, c)
                }
            }
        }
        // The tug of war across the top: who holds how much of the field.
        val total = max(1, count.sum()).toFloat()
        val barW = halfW * 0.9f
        var left = -barW
        for (s in 0 until teams) {
            val w = 2f * barW * count[s] / total
            if (w > 0f) g.rect(left + w * 0.5f, 0.93f, w * 0.5f, 0.018f, colors[s])
            left += w
        }
        // The victory: a ripple out of every winning piece, in a wave from
        // the middle of the field outwards. It was one dim glow the size of
        // the screen, which in a dark colour read as a brown smudge.
        if (winner >= 0) {
            val since = elapsed - doneAt
            for (i in 0 until n) {
                val delay = sqrt(x[i] * x[i] + y[i] * y[i]) * 0.5f
                val k = (since - delay) / 0.9f
                if (k <= 0f || k >= 1f) continue
                g.ring(x[i], y[i], size * (1.3f + 3.5f * k), size * 0.18f, colors[winner], alpha = (1f - k) * 0.8f)
            }
        }
    }

    override fun contenders(env: SimEnv): IntArray = IntArray(teams) { colour(env, it) }

    override fun winner(): Int = winner

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = (winner >= 0 && elapsed - doneAt > 3f) || elapsed > GIVE_UP_SECONDS

    override fun counters(): List<Pair<String, String>> =
        if (winner >= 0) listOf("winner" to NAMES[kindOf(winner)])
        else (0 until teams).map { NAMES[kindOf(it)] to count[it].toString() }

    companion object {
        const val MAX_EACH = 200
        const val LEGS_MIN = 0.9f
        const val LEGS_MAX = 1.1f
        const val BASE_SPEED = 0.16f
        const val FEAR = 0.9f
        const val JITTER = 0.35f
        /** How hard a piece keeps clear of its own side. */
        const val SPACING = 2.5f
        const val RAMP_SECONDS = 20f
        const val GIVE_UP_SECONDS = 60f
        const val QUIET_SECONDS = 3f
        const val FRENZY_SECONDS = 2.5f
        const val FRENZY_SPEED = 1.7f
        const val MAX_TEAMS = 5
        const val GOLDEN_ANGLE = 2.3999632f
        /** The victory lap: pull to the middle, and the turn round it. */
        const val GATHER = 1.2f
        const val TURN = 1.5f
        val NAMES = arrayOf("rock", "paper", "scissors", "lizard", "Spock")
        /** Sides 0 to 4 as kinds: rock, Spock, paper, lizard, scissors. */
        val FIVE_ORDER = intArrayOf(0, 4, 1, 3, 2)

        fun def(): SimDef = SimDef(
            id = "life/rps-battle",
            title = "Rock paper scissors",
            shelf = Shelf.LIFE,
            family = "Battles",
            blurb = "Armies that each chase what they beat and flee what beats them. Pick your side.",
            formats = listOf(TrapFormat.GUESS, TrapFormat.LAST_STANDING, TrapFormat.COLOR_BATTLE),
            heroLabel = "Stampede",
            heroRestarts = false,
            defaultPalette = "Spectrum",
            typicalSeconds = 15f,
            params = listOf(
                Param.choice("game", "Game", listOf("Rock paper scissors", "+ lizard, Spock"), 0, card = Card.DATA, input = ParamInput.TILES),
                Param.int("each", "Per side", 3, MAX_EACH, 40, card = Card.DATA, logScale = true),
                Param.float("pace", "Pace", 0.3f, 4f, 1f, card = Card.MOTION)
            ),
            create = { RpsBattleSim() }
        )
    }
}
