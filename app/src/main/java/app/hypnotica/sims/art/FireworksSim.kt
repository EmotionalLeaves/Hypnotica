package app.hypnotica.sims.art

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
import kotlin.math.sqrt

/**
 * A fireworks show. Rockets climb on a rhythm that quickens, and burst into
 * peonies, rings and willows that hang and fall; the show builds to a finale
 * where the sky fills at once. Each launch is a rising whistle of a note and
 * each burst a bang, pitched by how high it went.
 */
class FireworksSim : Sim {

    private class Rocket(var x: Float, var y: Float, var vx: Float, var vy: Float, val burstY: Float, val kind: Int, val tint: Float)

    private val rockets = ArrayList<Rocket>()
    private var px = FloatArray(0)
    private var py = FloatArray(0)
    private var pvx = FloatArray(0)
    private var pvy = FloatArray(0)
    private var life = FloatArray(0)
    private var maxLife = FloatArray(0)
    private var tint = FloatArray(0)
    private var drag = FloatArray(0)
    private var pNext = 0
    private var cap = 6000
    private var halfW = 0.46f
    private var elapsed = 0f
    private var nextLaunch = 0.3f
    private var finaleFired = false
    private var doneAt = -1f
    private var payoffFrame = false
    private var launched = 0
    private var skyFlash = 0f

    override fun setup(env: SimEnv) {
        halfW = env.halfWidth
        cap = MAX_SPARKS
        px = FloatArray(cap); py = FloatArray(cap); pvx = FloatArray(cap); pvy = FloatArray(cap)
        life = FloatArray(cap); maxLife = FloatArray(cap); tint = FloatArray(cap); drag = FloatArray(cap)
        pNext = 0
        rockets.clear()
        elapsed = 0f
        nextLaunch = 0.3f
        finaleFired = false
        doneAt = -1f
        payoffFrame = false
        launched = 0
        skyFlash = 0f
    }

    private fun launch(env: SimEnv, x: Float, burstY: Float) {
        val g = gravity(env)
        // Launched just hard enough to stop where it should burst.
        val rise = burstY - GROUND
        val vy = sqrt(2f * g * max(0.2f, rise))
        rockets.add(Rocket(x, GROUND, env.rng.range(-0.05f, 0.05f), vy, burstY, env.rng.int(0, KINDS), env.rng.nextFloat()))
        launched++
        env.emit(EventKind.STEP, 0.2f + 0.3f * ((burstY + 1f) / 2f), (x / max(0.1f, halfW)).coerceIn(-1f, 1f), 0.2f)
    }

    private fun gravity(env: SimEnv) = max(0.3f, abs(env.laws.gravity)) * GRAVITY_SCALE

    private fun burst(env: SimEnv, r: Rocket, big: Float) {
        val n = (SPARKS_PER_BURST * big * env.params.f("size", 1f)).toInt().coerceIn(20, 1200)
        // Sized to the screen's width: a burst made for a wide sky spread
        // past both sides of an upright phone.
        val speed = (0.5f + 0.25f * big) * env.params.f("size", 1f).coerceIn(0.4f, 2f) * min(1f, halfW)
        for (k in 0 until n) {
            val i = pNext; pNext = (pNext + 1) % cap
            px[i] = r.x; py[i] = r.y
            val a = 2f * PI.toFloat() * k / n + env.rng.range(-0.05f, 0.05f)
            val s = when (r.kind) {
                // A ring: every spark the same speed.
                RING -> speed
                // A willow: slow, heavy, long trails down.
                WILLOW -> speed * 0.7f * sqrt(env.rng.nextFloat())
                // A peony: a full ball.
                else -> speed * sqrt(env.rng.nextFloat())
            }
            pvx[i] = cos(a) * s + r.vx; pvy[i] = sin(a) * s
            maxLife[i] = if (r.kind == WILLOW) env.rng.range(2.2f, 3.2f) else env.rng.range(1.0f, 1.8f)
            life[i] = maxLife[i]
            drag[i] = if (r.kind == WILLOW) 1.6f else 1.1f
            // Mostly the rocket's own colour, a few strays.
            tint[i] = if (env.rng.nextFloat() < 0.85f) r.tint else env.rng.nextFloat()
        }
        skyFlash = max(skyFlash, 0.25f * big)
        val height = ((r.y + 1f) / 2f).coerceIn(0f, 1f)
        env.emit(EventKind.HIT, 0.15f + 0.6f * height, (r.x / max(0.1f, halfW)).coerceIn(-1f, 1f), (0.5f + 0.3f * big).coerceAtMost(1f))
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        skyFlash = max(0f, skyFlash - h * 2f)
        val pace = env.params.f("pace", 1f)
        val showLength = SHOW_SECONDS / pace.coerceAtLeast(0.2f)
        if (!finaleFired) {
            nextLaunch -= h
            if (nextLaunch <= 0f) {
                // Quicker and quicker as the show goes on: three at a time at the end.
                val progress = (elapsed / showLength).coerceIn(0f, 1f)
                val volley = 1 + (progress * 2.5f).toInt()
                repeat(volley) {
                    launch(env, env.rng.range(-halfW * SPREAD, halfW * SPREAD), env.rng.range(0.15f, 0.75f))
                }
                nextLaunch = (1.1f - 0.75f * progress) / pace.coerceAtLeast(0.2f) * env.rng.range(0.7f, 1.3f)
            }
            if (elapsed >= showLength) {
                // The finale: a wall of them, all at once, all the way across.
                finaleFired = true
                val count = FINALE
                for (k in 0 until count) {
                    val x = -halfW * SPREAD + 2f * SPREAD * halfW * (k + 0.5f) / count
                    launch(env, x, 0.35f + 0.4f * sin(k.toFloat() / (count - 1) * PI.toFloat()))
                }
            }
        }
        val g = gravity(env)
        val it = rockets.iterator()
        var burstNow = 0
        while (it.hasNext()) {
            val r = it.next()
            r.vy -= g * h
            r.x += r.vx * h; r.y += r.vy * h
            if (r.vy <= 0f || r.y >= r.burstY) {
                burst(env, r, if (finaleFired) 1.5f else 1f)
                burstNow++
                it.remove()
            }
        }
        if (finaleFired && rockets.isEmpty() && doneAt < 0f) {
            doneAt = elapsed
            payoffFrame = true
            env.emit(EventKind.DONE, 0.9f, 0f, 1f)
        }
        for (i in 0 until cap) {
            if (life[i] <= 0f) continue
            life[i] -= h
            val d = 1f - drag[i] * h
            pvx[i] *= d; pvy[i] = pvy[i] * d - g * 0.25f * h
            px[i] += pvx[i] * h; py[i] += pvy[i] * h
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        if (skyFlash > 0f) g.glow(0f, 0.4f, 1.6f, Palette.TEXT_PRIMARY, skyFlash * 0.25f)
        // The ground.
        g.rect(0f, GROUND - 0.03f, halfW, 0.03f, 0xFF10121A.toInt())
        for (r in rockets) {
            g.point(r.x, r.y, 0.012f, 0xFFFFE0A0.toInt())
            g.point(r.x - r.vx * 0.03f, r.y - r.vy * 0.03f, 0.008f, 0x99FFB060.toInt())
        }
        g.reservePoints(cap)
        for (i in 0 until cap) {
            if (life[i] <= 0f) continue
            val k = (life[i] / maxLife[i]).coerceIn(0f, 1f)
            // White-hot at first, its colour as it spreads, fading as it falls.
            val c = if (k > 0.85f) Palette.brighten(palette.at(tint[i]), 0.5f) else palette.at(tint[i])
            val twinkle = if (k < 0.3f && ((i + (elapsed * 30f).toInt()) % 3 == 0)) 0.4f else 1f
            g.point(px[i], py[i], 0.011f * (0.6f + 0.6f * k), Palette.withAlpha(c, k * twinkle))
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && elapsed - doneAt > 3.5f

    override fun counters(): List<Pair<String, String>> = listOf("rockets" to launched.toString())

    companion object {
        const val MAX_SPARKS = 12000
        const val GROUND = -0.92f
        const val GRAVITY_SCALE = 0.35f
        const val SHOW_SECONDS = 12f
        const val FINALE = 9
        /** How far out, as a share of the half-width, rockets go up. */
        const val SPREAD = 0.45f
        const val SPARKS_PER_BURST = 140f
        const val KINDS = 3
        const val PEONY = 0
        const val RING = 1
        const val WILLOW = 2

        fun def(): SimDef = SimDef(
            id = "art/fireworks",
            title = "Fireworks",
            shelf = Shelf.ART,
            family = "Shows",
            blurb = "Peonies, rings and willows, faster and faster, to a finale that fills the sky.",
            formats = listOf(TrapFormat.SNAP, TrapFormat.GROW),
            lawKnobs = setOf(LawKnob.GRAVITY),
            heroLabel = "Launch",
            heroRestarts = false,
            defaultPalette = "Spectrum",
            typicalSeconds = 16f,
            params = listOf(
                Param.float("pace", "Pace", 0.4f, 3f, 1f, card = Card.MOTION),
                Param.float("size", "Burst size", 0.4f, 2f, 1f, card = Card.MOTION)
            ),
            create = { FireworksSim() }
        )
    }

    /** One more, right now. */
    override fun hero(env: SimEnv) {
        if (finaleFired) return
        launch(env, env.rng.range(-halfW * 0.7f, halfW * 0.7f), env.rng.range(0.3f, 0.7f))
    }
}
