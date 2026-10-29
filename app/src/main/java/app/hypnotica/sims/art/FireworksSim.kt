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

    private class Rocket(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        val burstY: Float, val kind: Int, val tint: Float, val scale: Float
    )

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

    /** How far a burst of [scale] reaches from where it broke, in world units. */
    private fun reachOf(env: SimEnv, scale: Float): Float =
        BURST_REACH * scale * env.params.f("size", 1f).coerceIn(0.4f, 2f) * min(0.62f, halfW)

    /**
     * A rocket somewhere in the sky: [u] across the whole width (-1..1) and
     * [v] up the upper two thirds (0..1), kept in so its burst stays on
     * screen. Rockets used to go up within the middle 40% of the width and
     * every burst piled into one column.
     */
    private fun launchAt(env: SimEnv, u: Float, v: Float, scale: Float) {
        val reach = reachOf(env, scale)
        val xMax = max(0f, halfW - reach * 1.02f)
        val top = min(SKY_TOP, 0.95f - reach)
        val y = SKY_BOTTOM + (top - SKY_BOTTOM) * v.coerceIn(0f, 1f)
        launch(env, xMax * u.coerceIn(-1f, 1f), y, scale)
    }

    private fun launch(env: SimEnv, x: Float, burstY: Float, scale: Float = 1f) {
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
        rockets.add(Rocket(x, GROUND, env.rng.range(-0.03f, 0.03f), vy, burstY, kind, env.rng.nextFloat(), scale))
        launched++
        env.emit(EventKind.STEP, 0.2f + 0.3f * ((burstY + 1f) / 2f), (x / max(0.1f, halfW)).coerceIn(-1f, 1f), 0.2f)
    }

    private fun gravity(env: SimEnv) = max(0.3f, abs(env.laws.gravity)) * GRAVITY_SCALE

    private fun burst(env: SimEnv, r: Rocket, finale: Boolean) {
        // A ring is one line of stars, and at a shell's full count it drew
        // as a solid neon hoop: half as many read as sparks.
        val share = if (r.kind == RING) 0.5f else 1f
        val n = (SPARKS_PER_BURST * share * (0.5f + 0.5f * r.scale) * env.params.f("size", 1f)).toInt().coerceIn(20, 1200)
        // Sized to the screen's width: a burst made for a wide sky spread
        // past both sides of an upright phone. Thrown out fast against a
        // strong drag, so a shell blooms open in a fraction of a second and
        // then hangs, as real stars do — slow and steady, a fresh burst sat
        // for a moment as a solid white disc. Stars travel speed / drag.
        val reach = reachOf(env, r.scale)
        val speed = reach * PEONY_DRAG
        // A ring is a flat disc of stars at some tilt to us: an ellipse
        // turned to any angle. Face-on every time, the big circles looked
        // drawn with a compass.
        val tilt = env.rng.range(0.35f, 1f)
        val spin = env.rng.range(0f, PI.toFloat())
        val cs = cos(spin); val sn = sin(spin)
        for (k in 0 until n) {
            val i = pNext; pNext = (pNext + 1) % cap
            px[i] = r.x; py[i] = r.y
            val a = 2f * PI.toFloat() * k / n + env.rng.range(-0.05f, 0.05f)
            if (r.kind == HEART || r.kind == STAR) {
                // A shape: each spark's speed is a point round the outline, so
                // the burst grows as that shape and holds it while it spreads.
                val t = k.toFloat() / n
                val (ox, oy) = if (r.kind == HEART) heart(t) else star(t)
                pvx[i] = ox * speed * 0.85f + r.vx; pvy[i] = oy * speed * 0.85f
            } else {
                if (r.kind == RING) {
                    // A ring: every spark the same speed, in the sky's plane.
                    val ex = cos(a) * speed * 0.8f
                    val ey = sin(a) * speed * 0.8f * tilt
                    pvx[i] = ex * cs - ey * sn + r.vx; pvy[i] = ex * sn + ey * cs
                } else {
                    // A peony or a willow is a shell: every star thrown out at
                    // nearly one speed in every direction in 3D, seen from the
                    // side — a sphere, thick at its rim. Spread through a
                    // flat disc, it read as a blob of dots.
                    val z = env.rng.range(-1f, 1f)
                    val across = sqrt(1f - z * z)
                    // A willow is thrown softer and drags harder than a peony
                    // but droops much further: the same reach, a long fall.
                    val s = (if (r.kind == WILLOW) reach * WILLOW_DRAG * 0.8f else speed) * env.rng.range(0.88f, 1f)
                    pvx[i] = cos(a) * across * s + r.vx; pvy[i] = sin(a) * across * s
                }
            }
            // The finale's shells hang longer, so the sky is still full of
            // it when the show ends; it used to be black a second after.
            // Not so long that they sink to the ground: at 1.8x and a gentle
            // drag, the finale fell into one dense heap at the bottom.
            val hang = if (finale) 1.3f else 1f
            maxLife[i] = hang * (if (r.kind == WILLOW) env.rng.range(1.9f, 2.7f) else env.rng.range(1.1f, 1.8f))
            life[i] = maxLife[i]
            drag[i] = if (r.kind == WILLOW) WILLOW_DRAG else PEONY_DRAG
            fall[i] = if (r.kind == WILLOW) 0.32f else 0.16f
            // Shapes hang together: no stray colours, so the outline reads
            // as one thing while it lasts.
            // Mostly the rocket's own colour, a few strays.
            tint[i] = if (r.kind >= HEART || env.rng.nextFloat() < 0.85f) r.tint else env.rng.nextFloat()
        }
        skyFlash = max(skyFlash, if (finale) 0.3f else 0.22f)
        flashX[flashNext] = r.x; flashY[flashNext] = r.y; flashT[flashNext] = r.tint; flashAge[flashNext] = 0f
        flashNext = (flashNext + 1) % FLASHES
        val height = ((r.y + 1f) / 2f).coerceIn(0f, 1f)
        env.emit(EventKind.HIT, 0.15f + 0.6f * height, (r.x / max(0.1f, halfW)).coerceIn(-1f, 1f), (0.45f + 0.3f * r.scale + if (finale) 0.15f else 0f).coerceAtMost(1f))
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
                // Quicker and quicker as the show goes on: three at a time at
                // the end, at heights from the middle of the sky to the top,
                // so the bursts fill it rather than stacking in one band.
                val progress = (elapsed / showLength).coerceIn(0f, 1f)
                val volley = 1 + (progress * 2.6f).toInt()
                // A volley shares the width out, one slot each, so its
                // shells go up apart rather than on top of one another.
                val slot0 = env.rng.nextFloat()
                repeat(volley) { k ->
                    val u = ((slot0 + k.toFloat() / volley) % 1f) * 2f - 1f
                    launchAt(env, u + env.rng.range(-0.15f, 0.15f), env.rng.range(0.1f, 1f), env.rng.range(0.7f, 1.15f))
                }
                nextLaunch = (0.85f - 0.55f * progress) / pace.coerceAtLeast(0.2f) * env.rng.range(0.7f, 1.3f)
            }
            if (elapsed >= showLength) {
                finaleFired = true
                finaleWave = 0
                finaleClock = 0f
            }
        }
        // The finale: volleys that come quicker and climb, each spread
        // across the whole sky at staggered heights, ending on the biggest
        // shells highest up — a crescendo that fills the sky. Launched in a
        // wall from the middle at one height, it piled up low on one side.
        if (finaleFired && finaleWave < FINALE_WAVES) {
            finaleClock -= h
            if (finaleClock <= 0f) {
                val last = finaleWave == FINALE_WAVES - 1
                val count = if (last) 3 else FINALE - (finaleWave % 2)
                val climb = finaleWave / (FINALE_WAVES - 1f)
                val offset = if (finaleWave % 2 == 0) 0f else 0.5f
                for (k in 0 until count) {
                    val u = ((k + 0.5f + offset * 0.5f) / count) * 2f - 1f
                    // Alternate high and low across the row, rising wave by wave.
                    val v = if (last) 0.85f + 0.15f * env.rng.nextFloat()
                    else (0.15f + 0.45f * climb + (if (k % 2 == 0) 0.35f else 0f) + env.rng.range(-0.1f, 0.1f))
                    val scale = if (last) 1.25f else env.rng.range(0.65f, 1.05f)
                    launchAt(env, u + env.rng.range(-0.08f, 0.08f), v, scale)
                }
                finaleWave++
                finaleClock = FINALE_GAP * (1f - 0.4f * climb)
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
                burst(env, r, finaleFired)
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
        const val FINALE = 6
        const val FINALE_WAVES = 6
        /** Seconds between the finale's first volleys; they quicken. */
        const val FINALE_GAP = 0.55f
        /** Where in the sky shells break: the upper two thirds. */
        const val SKY_BOTTOM = -0.3f
        const val SKY_TOP = 0.75f
        /** A burst's reach, as a share of the screen's half-width. */
        const val BURST_REACH = 0.48f
        const val PEONY_DRAG = 2.6f
        const val WILLOW_DRAG = 3.2f
        const val ROCKET_TAIL = 6
        const val FLASHES = 24
        /** Points in each spark's streak, and the seconds of flight between them. */
        const val TAIL = 5
        const val TAIL_STEP = 0.018f
        const val FLASH_SECONDS = 0.35f
        const val SPARKS_PER_BURST = 150f
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
        launchAt(env, env.rng.range(-1f, 1f), env.rng.range(0.35f, 1f), env.rng.range(0.8f, 1.1f))
    }
}
