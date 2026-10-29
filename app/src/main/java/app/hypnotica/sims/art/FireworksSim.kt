package app.hypnotica.sims.art

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.recipe.ParamInput
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
    /** How hard gravity pulls each spark: willows droop, peonies hang. */
    private var fall = FloatArray(0)
    // The flash where each recent shell broke.
    private val flashX = FloatArray(FLASHES)
    private val flashY = FloatArray(FLASHES)
    private val flashT = FloatArray(FLASHES)
    private val flashAge = FloatArray(FLASHES) { 1f }
    private var flashNext = 0
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
    private var finaleWave = 0
    private var finaleClock = 0f

    override fun setup(env: SimEnv) {
        halfW = env.halfWidth
        cap = MAX_SPARKS
        px = FloatArray(cap); py = FloatArray(cap); pvx = FloatArray(cap); pvy = FloatArray(cap)
        life = FloatArray(cap); maxLife = FloatArray(cap); tint = FloatArray(cap); drag = FloatArray(cap); fall = FloatArray(cap)
        flashAge.fill(1f); flashNext = 0
        pNext = 0
        rockets.clear()
        elapsed = 0f
        nextLaunch = 0.3f
        finaleFired = false
        finaleWave = 0
        finaleClock = 0f
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
        // What kind of shell: any of the classic three, or the shapes the
        // show is set to, now and then a classic among them.
        val kind = when (env.params.choiceIndex("shells")) {
            1 -> if (env.rng.bool(0.75f)) HEART else env.rng.int(0, KINDS)
            2 -> if (env.rng.bool(0.75f)) STAR else env.rng.int(0, KINDS)
            3 -> env.rng.int(0, KINDS + 2)
            else -> env.rng.int(0, KINDS)
        }
        rockets.add(Rocket(x, GROUND, env.rng.range(-0.05f, 0.05f), vy, burstY, kind, env.rng.nextFloat()))
        launched++
        env.emit(EventKind.STEP, 0.2f + 0.3f * ((burstY + 1f) / 2f), (x / max(0.1f, halfW)).coerceIn(-1f, 1f), 0.2f)
    }

    private fun gravity(env: SimEnv) = max(0.3f, abs(env.laws.gravity)) * GRAVITY_SCALE

    private fun burst(env: SimEnv, r: Rocket, big: Float) {
        val n = (SPARKS_PER_BURST * big * env.params.f("size", 1f)).toInt().coerceIn(20, 1200)
        // Sized to the screen's width: a burst made for a wide sky spread
        // past both sides of an upright phone. Up to about a third of the
        // width across on a phone, which a show of them fills; they were a
        // fifth, and the sky stayed mostly empty.
        val speed = (0.6f + 0.25f * big) * env.params.f("size", 1f).coerceIn(0.4f, 2f) * min(0.62f, halfW) * 1.05f
        for (k in 0 until n) {
            val i = pNext; pNext = (pNext + 1) % cap
            px[i] = r.x; py[i] = r.y
            val a = 2f * PI.toFloat() * k / n + env.rng.range(-0.05f, 0.05f)
            if (r.kind == HEART || r.kind == STAR) {
                // A shape: each spark's speed is a point round the outline, so
                // the burst grows as that shape and holds it while it spreads.
                val t = k.toFloat() / n
                val (ox, oy) = if (r.kind == HEART) heart(t) else star(t)
                pvx[i] = ox * speed + r.vx; pvy[i] = oy * speed
            } else {
                if (r.kind == RING) {
                    // A ring: every spark the same speed, in the sky's plane.
                    pvx[i] = cos(a) * speed + r.vx; pvy[i] = sin(a) * speed
                } else {
                    // A peony or a willow is a shell: every star thrown out at
                    // nearly one speed in every direction in 3D, seen from the
                    // side — a sphere, thick at its rim. Spread through a
                    // flat disc, it read as a blob of dots.
                    val z = env.rng.range(-1f, 1f)
                    val across = sqrt(1f - z * z)
                    val s = speed * (if (r.kind == WILLOW) 0.7f else 1f) * env.rng.range(0.88f, 1f)
                    pvx[i] = cos(a) * across * s + r.vx; pvy[i] = sin(a) * across * s
                }
            }
            // The finale's shells hang longer, so the sky is still full of
            // it when the show ends; it used to be black a second after.
            val hang = if (big > 1f) 1.8f else 1f
            maxLife[i] = hang * (if (r.kind == WILLOW) env.rng.range(2.2f, 3.2f) else env.rng.range(1.1f, 1.9f))
            life[i] = maxLife[i]
            drag[i] = if (r.kind == WILLOW) 1.6f else 1.1f
            fall[i] = if (r.kind == WILLOW) 0.3f else 0.13f
            // Shapes hang together: no stray colours, so the outline reads
            // as one thing while it lasts.
            // Mostly the rocket's own colour, a few strays.
            tint[i] = if (r.kind >= HEART || env.rng.nextFloat() < 0.85f) r.tint else env.rng.nextFloat()
        }
        skyFlash = max(skyFlash, 0.25f * big)
        flashX[flashNext] = r.x; flashY[flashNext] = r.y; flashT[flashNext] = r.tint; flashAge[flashNext] = 0f
        flashNext = (flashNext + 1) % FLASHES
        val height = ((r.y + 1f) / 2f).coerceIn(0f, 1f)
        env.emit(EventKind.HIT, 0.15f + 0.6f * height, (r.x / max(0.1f, halfW)).coerceIn(-1f, 1f), (0.5f + 0.3f * big).coerceAtMost(1f))
    }

    /** The heart curve, [t] from 0 to 1 round it, about one unit across. */
    private fun heart(t: Float): Pair<Float, Float> {
        val a = t * 2f * PI.toFloat()
        val sn = sin(a)
        return (16f * sn * sn * sn / 17f) to ((13f * cos(a) - 5f * cos(2f * a) - 2f * cos(3f * a) - cos(4f * a)) / 17f + 0.1f)
    }

    /** A five-pointed star's outline, [t] from 0 to 1. */
    private fun star(t: Float): Pair<Float, Float> {
        val e = t * 10f
        val i = e.toInt() % 10
        val f = e - kotlin.math.floor(e)
        fun corner(k: Int): Pair<Float, Float> {
            val rr = if (k % 2 == 0) 1f else 0.42f
            val ang = PI.toFloat() / 2f + k * PI.toFloat() / 5f
            return (rr * cos(ang)) to (rr * sin(ang))
        }
        val (x0, y0) = corner(i); val (x1, y1) = corner((i + 1) % 10)
        return (x0 + (x1 - x0) * f) to (y0 + (y1 - y0) * f)
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        skyFlash = max(0f, skyFlash - h * 2f)
        for (k in 0 until FLASHES) flashAge[k] += h
        val pace = env.params.f("pace", 1f)
        val showLength = SHOW_SECONDS / pace.coerceAtLeast(0.2f)
        if (!finaleFired) {
            nextLaunch -= h
            if (nextLaunch <= 0f) {
                // Quicker and quicker as the show goes on: four at a time at
                // the end, at heights from the middle of the sky to the top,
                // so the bursts fill it rather than stacking in one band.
                val progress = (elapsed / showLength).coerceIn(0f, 1f)
                val volley = 1 + (progress * 3.2f).toInt()
                repeat(volley) {
                    launch(env, env.rng.range(-halfW * SPREAD, halfW * SPREAD), env.rng.range(0f, 0.68f))
                }
                nextLaunch = (0.85f - 0.55f * progress) / pace.coerceAtLeast(0.2f) * env.rng.range(0.7f, 1.3f)
            }
            if (elapsed >= showLength) {
                finaleFired = true
                finaleWave = 0
                finaleClock = 0f
            }
        }
        // The finale: a wall of them all the way across, then two more
        // between and above, half a second apart. One volley of nine was over
        // in a blink, and the sky the show built to was sparse.
        if (finaleFired && finaleWave < FINALE_WAVES) {
            finaleClock -= h
            if (finaleClock <= 0f) {
                val count = FINALE - (finaleWave % 2) * 2
                for (k in 0 until count) {
                    val u = (k + 0.5f) / count
                    val x = -halfW * SPREAD + 2f * SPREAD * halfW * u
                    val arch = sin(u * PI.toFloat())
                    launch(env, x, 0.05f + 0.14f * finaleWave + 0.25f * arch)
                }
                finaleWave++
                finaleClock = FINALE_GAP
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
        if (finaleFired && finaleWave >= FINALE_WAVES && rockets.isEmpty() && doneAt < 0f) {
            doneAt = elapsed
            payoffFrame = true
            env.emit(EventKind.DONE, 0.9f, 0f, 1f)
        }
        for (i in 0 until cap) {
            if (life[i] <= 0f) continue
            life[i] -= h
            val d = 1f - drag[i] * h
            pvx[i] *= d; pvy[i] = pvy[i] * d - g * fall[i] * h
            px[i] += pvx[i] * h; py[i] += pvy[i] * h
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        if (skyFlash > 0f) g.glow(0f, 0.4f, 1.6f, Palette.TEXT_PRIMARY, skyFlash * 0.25f)
        // The break itself: a hot flash where the shell burst, gone in a blink.
        for (k in 0 until FLASHES) {
            val age = flashAge[k]
            if (age >= FLASH_SECONDS) continue
            val f = 1f - age / FLASH_SECONDS
            g.glow(flashX[k], flashY[k], 0.06f + 0.16f * (1f - f * f), Palette.brighten(palette.at(flashT[k]), 0.25f), 0.35f * f)
        }
        // The ground.
        g.rect(0f, GROUND - 0.03f, halfW, 0.03f, 0xFF10121A.toInt())
        g.reservePoints(cap * (TAIL + 1) + rockets.size * ROCKET_TAIL)
        for (r in rockets) {
            // A fading spark trail down the climb, not a pair of dots.
            for (k in ROCKET_TAIL - 1 downTo 1) {
                val back = k * 0.012f
                val f = 1f - k.toFloat() / ROCKET_TAIL
                g.point(r.x - r.vx * back, r.y - r.vy * back, 0.009f * (0.5f + 0.5f * f), Palette.withAlpha(0xFFFFB060.toInt(), 0.7f * f))
            }
            g.point(r.x, r.y, 0.013f, 0xFFFFE8B0.toInt())
        }
        for (i in 0 until cap) {
            if (life[i] <= 0f) continue
            val k0 = (life[i] / maxLife[i]).coerceIn(0f, 1f)
            // Bright for most of its life and fading at the end, as a star
            // burns: faded in a straight line, a burst was half gone at once.
            val k = sqrt(k0)
            // White-hot at first, its colour as it spreads, fading as it falls.
            // White-hot only for the first instant: longer, a fresh shell read as a
            // solid pale disc before it had opened.
            val c = if (k0 > 0.93f) Palette.brighten(palette.at(tint[i]), 0.35f) else palette.at(tint[i])
            val twinkle = if (k0 < 0.3f && ((i + (elapsed * 30f).toInt()) % 3 == 0)) 0.4f else 1f
            val size = 0.013f * (0.6f + 0.6f * k)
            // Each spark streaks back along its flight, so a burst reads as
            // lines of fire radiating out rather than a scatter of dots.
            for (t in TAIL downTo 1) {
                val back = t * TAIL_STEP
                val f = 1f - t / (TAIL + 1f)
                g.point(px[i] - pvx[i] * back, py[i] - pvy[i] * back, size * (0.4f + 0.5f * f), Palette.withAlpha(c, k * twinkle * 0.6f * f * f))
            }
            g.point(px[i], py[i], size, Palette.withAlpha(c, k * twinkle))
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
        const val FINALE_WAVES = 4
        /** Seconds between the finale's volleys. */
        const val FINALE_GAP = 0.5f
        const val ROCKET_TAIL = 6
        /** How far out, as a share of the half-width, rockets go up. */
        const val SPREAD = 0.4f
        const val FLASHES = 24
        /** Points in each spark's streak, and the seconds of flight between them. */
        const val TAIL = 5
        const val TAIL_STEP = 0.018f
        const val FLASH_SECONDS = 0.35f
        const val SPARKS_PER_BURST = 190f
        const val KINDS = 3
        const val PEONY = 0
        const val RING = 1
        const val WILLOW = 2
        const val HEART = 3
        const val STAR = 4

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
                Param.choice("shells", "Shells", listOf("Classic", "Hearts", "Stars", "Everything"), 0, card = Card.DATA, input = ParamInput.TILES),
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
