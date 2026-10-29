package app.hypnotica.sims.physics

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.LawKnob
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

/**
 * A pane of glass struck again and again in the same place. Each strike runs
 * the cracks further — straight ones out from the centre, then rings joining
 * them, the spider's web every broken window has — until one strike is one
 * too many and the whole thing comes apart in shards.
 *
 * The shards are the cells of that web: each the piece of ring between two
 * cracks, so the pieces that fall are the pieces that were drawn.
 */
class ShatterSim : Sim {

    private var rays = 12
    private var rings = 6
    private var radius = 0.42f
    private var rayAngle = FloatArray(0)
    private var ringR = FloatArray(0)
    private var hitsToBreak = 7
    private var hits = 0
    private var crackReach = 0f
    private var ringsShown = 0
    private var sinceStrike = 0f
    private var strikeFlash = 0f
    private var broken = false
    private var brokenAt = -1f
    private var elapsed = 0f
    private var payoffFrame = false

    // Shards, after the break: which ring and which gap between cracks each
    // came from, and where it has got to.
    private var shardRing = IntArray(0)
    private var shardA0 = FloatArray(0)
    private var shardA1 = FloatArray(0)
    private var sx = FloatArray(0)
    private var sy = FloatArray(0)
    private var svx = FloatArray(0)
    private var svy = FloatArray(0)
    private var spin = FloatArray(0)
    private var turn = FloatArray(0)
    private var stuck = BooleanArray(0)

    override fun setup(env: SimEnv) {
        val p = env.params
        rays = p.i("cracks", 12).coerceIn(5, 40)
        rings = p.i("rings", 6).coerceIn(2, 14)
        hitsToBreak = p.i("hits", 7).coerceIn(1, 30)
        // Capped on a wide screen, where 0.9 of the height ran the pane and its
        // flying pieces off the top and bottom.
        radius = min(env.halfWidth * 0.9f, 0.74f)
        rayAngle = FloatArray(rays) { (it + env.rng.range(-0.3f, 0.3f)) * 2f * PI.toFloat() / rays }
        rayAngle.sort()
        // Rings closer together near the centre, where a pane cracks finest.
        ringR = FloatArray(rings) { i -> radius * ((i + 1f) / rings).let { it * (0.6f + 0.4f * it) } }
        hits = 0
        crackReach = 0f
        ringsShown = 0
        sinceStrike = 0f
        strikeFlash = 0f
        broken = false
        brokenAt = -1f
        elapsed = 0f
        payoffFrame = false
        shardRing = IntArray(0)
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        strikeFlash = max(0f, strikeFlash - h * 4f)
        if (!broken) {
            sinceStrike += h * env.params.f("pace", 1f)
            if (sinceStrike >= STRIKE_EVERY) {
                sinceStrike = 0f
                strike(env)
            }
            // The cracks run out from the centre after each strike rather than
            // appearing whole.
            val target = radius * (hits.toFloat() / hitsToBreak).coerceIn(0f, 1f)
            if (crackReach < target) {
                crackReach = min(target, crackReach + h * radius * 1.5f)
                // A ring crack forms as the straight ones pass it.
                while (ringsShown < rings - 1 && ringR[ringsShown] < crackReach * 0.92f) {
                    ringsShown++
                    env.emit(EventKind.CROSS, 0.4f + 0.6f * ringsShown / rings, 0f, 0.45f)
                }
            }
        } else {
            val g = env.laws.gravity * GRAVITY_SCALE
            for (i in sx.indices) {
                if (stuck[i]) continue
                val was = sy[i]
                svy[i] -= g * h
                sx[i] += svx[i] * h
                sy[i] += svy[i] * h
                turn[i] += spin[i] * h
                // Each piece tinkles as it falls past the bottom of the frame,
                // so the fall is heard as well as seen.
                // Where the piece itself is: the pane's centre plus its own place in it.
                val r = shardRing[i]
                val rm = (ringR[r] + (if (r == 0) 0f else ringR[r - 1])) * 0.5f
                val py = sin((shardA0[i] + shardA1[i]) * 0.5f) * rm
                if (was + py >= -radius && sy[i] + py < -radius) {
                    // Panned by the piece's own place too, as py is: by its
                    // drift alone a piece from the pane's edge sounded mid-screen.
                    val px = cos((shardA0[i] + shardA1[i]) * 0.5f) * rm
                    env.emit(EventKind.HIT, 0.55f + 0.45f * ((i * 37) % 11) / 10f,
                        ((sx[i] + px) / (radius + 0.04f)).coerceIn(-1f, 1f), 0.35f)
                }
            }
        }
    }

    private fun strike(env: SimEnv) {
        hits++
        strikeFlash = 1f
        env.emit(EventKind.HIT, 0.15f + 0.5f * hits / hitsToBreak, 0f, 0.7f + 0.3f * hits / hitsToBreak)
        if (hits >= hitsToBreak) shatter(env)
    }

    /** The extra strike, from the button. */
    override fun hero(env: SimEnv) {
        // On top of the regular strikes, not instead of the next one.
        if (!broken) strike(env)
    }

    private fun shatter(env: SimEnv) {
        broken = true
        brokenAt = elapsed
        payoffFrame = true
        // DONE comes from the engine on this payoff frame; a second, fixed
        // pitch one here only doubled it.
        val n = rings * rays
        shardRing = IntArray(n); shardA0 = FloatArray(n); shardA1 = FloatArray(n)
        sx = FloatArray(n); sy = FloatArray(n); svx = FloatArray(n); svy = FloatArray(n)
        spin = FloatArray(n); turn = FloatArray(n); stuck = BooleanArray(n)
        var k = 0
        for (r in 0 until rings) for (j in 0 until rays) {
            val a0 = rayAngle[j]
            val a1 = if (j + 1 < rays) rayAngle[j + 1] else rayAngle[0] + 2f * PI.toFloat()
            shardRing[k] = r; shardA0[k] = a0; shardA1[k] = a1
            // Blown outward from the strike, the inner pieces hardest.
            val mid = (a0 + a1) * 0.5f
            val push = (1.2f - 0.7f * r / rings) * env.rng.range(0.6f, 1.2f)
            svx[k] = cos(mid) * push
            svy[k] = sin(mid) * push + 0.4f
            spin[k] = env.rng.range(-6f, 6f)
            // Some of the outermost pieces stay in the frame, as they do in a
            // broken window: the run used to end on an empty ring.
            stuck[k] = r == rings - 1 && env.rng.nextFloat() < STUCK_SHARE
            k++
        }
        // Behind a piece that stayed in the frame, sometimes the piece inside
        // it stays too: a jagged edge of glass, as in a real broken window,
        // rather than a neat band of identical arcs round the rim.
        if (rings >= 2) for (j in 0 until rays) {
            val outer = (rings - 1) * rays + j
            if (stuck[outer] && env.rng.nextFloat() < 0.45f) stuck[outer - rays] = true
        }
        // The burst: a scatter of bright notes as the pieces go.
        repeat(SHATTER_NOTES) {
            env.emit(EventKind.HIT, env.rng.range(0.5f, 1f), env.rng.range(-0.8f, 0.8f), env.rng.range(0.3f, 0.8f))
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val glass = palette.at(0.55f)
        val crack = Palette.TEXT_PRIMARY
        if (!broken) {
            g.disc(0f, 0f, radius, Palette.withAlpha(glass, 0.18f))
            g.ring(0f, 0f, radius, 0.012f, Palette.withAlpha(glass, 0.9f))
            // A sheen at the top left: the cue that says glass, not a flat disc.
            val sheen = 0.9f
            g.ring(0f, 0f, radius * 0.86f, 0.006f, Palette.TEXT_PRIMARY, 2f * PI.toFloat() - sheen, 1.85f - (2f * PI.toFloat() - sheen), 0.22f)
            // The straight cracks, out as far as the strikes have driven them.
            for (j in 0 until rays) {
                val a = rayAngle[j]
                val wobble = 0.9f + 0.1f * ((j * 7) % 5) / 4f
                g.line(0f, 0f, cos(a) * crackReach * wobble, sin(a) * crackReach * wobble, 0.004f, crack, 0.85f)
            }
            for (r in 0 until ringsShown) g.ring(0f, 0f, ringR[r], 0.003f, crack, alpha = 0.7f)
            if (strikeFlash > 0f) g.glow(0f, 0f, 0.12f + 0.1f * strikeFlash, Palette.TEXT_PRIMARY, 0.6f * strikeFlash)
            g.disc(0f, 0f, 0.02f, crack, 0.9f)
        } else {
            // The empty frame the glass was in.
            g.ring(0f, 0f, radius, 0.012f, Palette.withAlpha(glass, 0.75f))
            for (k in sx.indices) {
                // Gone once it has fallen out of the frame.
                if (abs(sx[k]) > radius * 1.8f || abs(sy[k]) > radius * 1.8f + 1f) continue
                val r = shardRing[k]
                val outer = ringR[r]
                val inner = if (r == 0) 0f else ringR[r - 1]
                val width = shardA1[k] - shardA0[k]
                val gap = 2f * PI.toFloat() - width
                // An annular sector, ring-drawn: the shader's gap runs from the
                // ring's rotation, so the visible arc starts where [gap] ends.
                val rot = shardA0[k] - gap + turn[k]
                // Glass, all of it, a shade lighter ring by ring: each piece
                // a colour of its own made a confetti of the break.
                val c = Palette.lerp(glass, Palette.TEXT_PRIMARY, 0.1f + 0.25f * r / max(1, rings - 1))
                // Turned about the piece's own middle, not the pane's: drawn
                // about the pane's centre, an outer shard spinning at speed
                // swung round in a circle and rose against gravity.
                val mid = (shardA0[k] + shardA1[k]) * 0.5f
                val rm = (outer + inner) * 0.5f
                val c0x = cos(mid) * rm; val c0y = sin(mid) * rm
                val ct = cos(turn[k]); val st = sin(turn[k])
                val ox = sx[k] + c0x - (c0x * ct - c0y * st)
                val oy = sy[k] + c0y - (c0x * st + c0y * ct)
                g.ring(ox, oy, outer, outer - inner, Palette.withAlpha(c, 0.45f), gap, rot)
                // Its edges catch the light: the outer one, and the crack it
                // broke along on one side.
                g.ring(ox, oy, outer, 0.004f, Palette.withAlpha(c, 0.95f), gap, rot)
                val ea = shardA0[k] + turn[k]
                g.line(ox + cos(ea) * inner, oy + sin(ea) * inner, ox + cos(ea) * outer, oy + sin(ea) * outer, 0.004f, c, 0.8f)
                // And the other crack, fainter, so a piece left in the frame
                // reads as a closed shard instead of an open bracket.
                val eb = shardA1[k] + turn[k]
                g.line(ox + cos(eb) * inner, oy + sin(eb) * inner, ox + cos(eb) * outer, oy + sin(eb) * outer, 0.003f, c, 0.5f)
            }
        }
    }

    override fun frameHalfWidth(env: SimEnv): Float = radius + 0.04f

    override fun payoff(): Boolean = payoffFrame

    /** Once the pieces have fallen away. */
    override fun finished(): Boolean = broken && elapsed - brokenAt > FALL_SECONDS

    override fun counters(): List<Pair<String, String>> = listOf(
        "strikes" to "$hits / $hitsToBreak",
        if (broken) "pieces" to (rings * rays).toString() else "cracks" to rays.toString()
    )

    companion object {
        const val STRIKE_EVERY = 1.1f
        const val GRAVITY_SCALE = 0.25f
        const val SHATTER_NOTES = 12
        const val FALL_SECONDS = 2.6f
        const val STUCK_SHARE = 0.4f

        fun def(): SimDef = SimDef(
            id = "physics/shatter",
            title = "Glass, struck",
            shelf = Shelf.PHYSICS,
            family = "Fracture & breaking",
            blurb = "Strike after strike, the cracks run further. Then one strike too many.",
            formats = listOf(TrapFormat.CUT, TrapFormat.SNAP, TrapFormat.GUESS),
            lawKnobs = setOf(LawKnob.GRAVITY),
            heroLabel = "Strike",
            heroRestarts = false,
            defaultPalette = "Ocean",
            typicalSeconds = 11f,
            params = listOf(
                Param.int("hits", "Strikes to break", 1, 30, 7, card = Card.DATA),
                Param.int("cracks", "Cracks", 5, 40, 12, card = Card.DATA),
                Param.int("rings", "Rings", 2, 14, 6, card = Card.DATA),
                Param.float("pace", "Pace", 0.3f, 3f, 1f, card = Card.MOTION)
            ),
            create = { ShatterSim() }
        )
    }
}
