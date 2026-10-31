package app.hypnotica.sims.physics

import app.hypnotica.core.audio.Song
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
import app.hypnotica.core.sim.TouchEvent
import app.hypnotica.core.sim.TrapFormat
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One more bounce: a ball dropped into a ring, which grows a little every
 * time it hits the wall and plays the next note of a tune. The bigger it
 * gets the less room it has, so the bounces come faster and faster — the
 * tune speeds up into a drum roll — until there is no room left at all.
 *
 * Every bounce leaves a thread from where it hit to the ball, so the ring
 * fills with a string-art fan that swings round after it. The format of a
 * thousand satisfying shorts, done with the physics straight: elastic hits,
 * real gravity, nothing scripted.
 */
class GrowBounceSim : Sim {

    private var ringR = 0.45f
    private var r = 0.02f
    private var px = 0f
    private var py = 0f
    private var vx = 0f
    private var vy = 0f
    private var growth = 0.012f
    private var bounces = 0
    private val hitX = FloatArray(MAX_HITS)
    private val hitY = FloatArray(MAX_HITS)
    private var hits = 0
    private var flash = 0f
    private var flashAng = 0f
    private val trailX = FloatArray(TRAIL)
    private val trailY = FloatArray(TRAIL)
    private val trailR = FloatArray(TRAIL)
    private var trailHead = 0
    private var trailN = 0
    private var trailClock = 0f
    private var elapsed = 0f
    private var doneAt = -1f
    private var lastMark = 0
    private var payoffFrame = false

    override fun setup(env: SimEnv) {
        ringR = min(env.halfWidth * 0.94f, 0.9f)
        r = ringR * START_SHARE
        growth = env.params.f("growth", 1f).coerceIn(0.3f, 3f) * GROW_SHARE * ringR
        // Dropped from high on one side with a little sideways push, so its
        // path is a lopsided loop rather than a straight up-and-down.
        px = -ringR * 0.25f + env.rng.nextFloat() * ringR * 0.1f
        py = ringR * 0.45f
        vx = ringR * (0.9f + env.rng.nextFloat() * 0.4f)
        vy = 0f
        bounces = 0; hits = 0; flash = 0f; lastMark = 0
        trailHead = 0; trailN = 0; trailClock = 0f
        elapsed = 0f; doneAt = -1f; payoffFrame = false
    }

    override fun tune(env: SimEnv): String = Song.TUNES[env.params.choiceIndex("tune").coerceIn(0, Song.TUNES.size - 1)].name

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h0 = abs(dt) * env.params.f("speed", 1f).coerceIn(0.2f, 3f)
        elapsed += h0
        flash = max(0f, flash - abs(dt) * 3f)
        trailClock += h0
        if (trailClock > 0.03f) {
            trailClock = 0f
            trailX[trailHead] = px; trailY[trailHead] = py; trailR[trailHead] = r
            trailHead = (trailHead + 1) % TRAIL
            trailN = min(TRAIL, trailN + 1)
        }
        if (doneAt >= 0f) return
        val grav = env.params.f("gravity", 1f).coerceIn(0f, 3f) * G0 * ringR
        val sub = 8
        val h = h0 / sub
        repeat(sub) {
            vy -= grav * h
            px += vx * h; py += vy * h
            val d = sqrt(px * px + py * py)
            val room = ringR - r
            if (d >= room && d > 1e-6f) {
                val nx = px / d; val ny = py / d
                val vn = vx * nx + vy * ny
                if (vn > 0f) {
                    // Elastic: the speed along the wall's normal is reversed.
                    vx -= 2f * vn * nx; vy -= 2f * vn * ny
                    bounce(env, nx, ny)
                }
                val nr = ringR - r
                px = nx * nr; py = ny * nr
                if (doneAt >= 0f) return
            }
        }
    }

    override fun onInput(env: SimEnv, e: TouchEvent) {
        if (e.kind != TouchEvent.Kind.TAP || doneAt >= 0f) return
        // A flick away from the finger.
        val dx = px - e.x; val dy = py - e.y
        val d = max(0.05f, sqrt(dx * dx + dy * dy))
        vx += dx / d * ringR * 0.8f; vy += dy / d * ringR * 0.8f
    }

    private fun bounce(env: SimEnv, nx: Float, ny: Float) {
        bounces++
        if (hits < MAX_HITS) { hitX[hits] = nx * ringR; hitY[hits] = ny * ringR; hits++ }
        flash = 1f
        flashAng = atan2(ny, nx)
        r = min(ringR * END_SHARE, r + growth)
        val full = r / ringR
        // The fill named as it passes each half and three quarters.
        if (full >= 0.5f && lastMark < 50) { lastMark = 50; env.callout("Half full!") }
        else if (full >= 0.75f && lastMark < 75) { lastMark = 75; env.callout("No room left…") }
        else if (bounces % 25 == 0) env.callout("$bounces bounces")
        env.emit(EventKind.HIT, full, nx.coerceIn(-1f, 1f), (0.45f + 0.4f * full).coerceAtMost(0.9f))
        if (r >= ringR * END_SHARE) {
            doneAt = elapsed
            payoffFrame = true
            env.emit(EventKind.DONE, 1f, 0f, 1f)
            env.callout("FULL!", big = true)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        val c = pal.at((bounces % 40) / 40f)
        // The threads: from every place it hit, to where the ball is now.
        val thread = 0.0045f
        for (k in 0 until hits) {
            val col = pal.at((k % 40) / 40f)
            g.line(hitX[k], hitY[k], px, py, thread, col, 0.35f + 0.3f * k / max(1, hits))
        }
        // The ring, lit where it was just struck.
        g.ring(0f, 0f, ringR, 0.008f, Palette.withAlpha(Palette.TEXT_PRIMARY, 0.75f))
        if (flash > 0f) {
            val fx = ringR * kotlin.math.cos(flashAng); val fy = ringR * kotlin.math.sin(flashAng)
            g.glow(fx, fy, 0.06f + r * 0.4f, c, flash * 0.8f)
        }
        // Its recent path as fading ghosts, then the ball, its rim in the
        // colour of its latest note.
        for (j in 0 until trailN) {
            val idx = (trailHead - trailN + j + TRAIL) % TRAIL
            val f = (j + 1f) / trailN
            g.ring(trailX[idx], trailY[idx], trailR[idx], 0.003f, c, alpha = 0.12f * f)
        }
        g.disc(px, py, r, Palette.lerp(Palette.BG, c, 0.35f))
        g.ring(px, py, r, max(0.006f, r * 0.08f), Palette.brighten(c, 0.25f))
        if (doneAt >= 0f) g.glow(0f, 0f, ringR * 1.1f, c, (1f - (elapsed - doneAt) / 2f).coerceIn(0f, 1f) * 0.5f)
    }

    override fun frameHalfWidth(env: SimEnv): Float = ringR + 0.01f

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && elapsed - doneAt > 4f

    override fun scoreForBest(): Float = bounces.toFloat()
    override fun scoreForBestText(value: Float): String = "${value.toInt()} bounces to fill it"
    override fun liveBest(): Boolean = true

    override fun counters(): List<Pair<String, String>> = listOf(
        "bounces" to "$bounces",
        "size" to String.format(java.util.Locale.US, "%.0f%%", 100f * r / ringR)
    )

    companion object {
        const val MAX_HITS = 400
        const val TRAIL = 14
        const val START_SHARE = 0.05f
        const val END_SHARE = 0.96f
        /** Growth per bounce, as a share of the ring's radius. */
        const val GROW_SHARE = 0.016f
        /** Gravity, in ring radii per second squared. */
        const val G0 = 3.0f

        fun def(): SimDef = SimDef(
            id = "physics/grow-bounce",
            title = "One more bounce",
            shelf = Shelf.PHYSICS,
            family = "Bouncing & collisions",
            blurb = "It grows a little every time it bounces, and plays the next note. Soon there's no room left.",
            formats = listOf(TrapFormat.FILL, TrapFormat.SONG, TrapFormat.NUMBER_UP),
            heroLabel = "Drop again",
            defaultPalette = "Spectrum",
            typicalSeconds = 24f,
            params = listOf(
                Param.choice("tune", "Tune", Song.TUNES.map { it.name }, 0, card = Card.DATA, input = ParamInput.CHIPS),
                Param.float("growth", "Growth per bounce", 0.3f, 3f, 1f, card = Card.DATA, logScale = true),
                Param.float("gravity", "Gravity", 0f, 3f, 1f, card = Card.MOTION),
                Param.float("speed", "Speed", 0.2f, 3f, 1f, card = Card.MOTION, logScale = true)
            ),
            create = { GrowBounceSim() }
        )
    }
}
