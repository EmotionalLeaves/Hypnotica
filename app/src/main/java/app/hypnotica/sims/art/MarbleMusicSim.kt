package app.hypnotica.sims.art

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
import app.hypnotica.core.sim.TrapFormat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A marble music machine. Marbles drop from the top, each timed to land on
 * one xylophone bar on the beat, and together they play a tune everyone
 * knows — the bar each one hits is the note you hear.
 *
 * The bars are the tune's own notes, low on the left and high on the right,
 * longest to shortest as on the real thing. Every marble is released exactly
 * one fall-time before its note, so the picture and the melody can't drift.
 */
class MarbleMusicSim : Sim {

    private class Marble(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        val bar: Int, val hitAt: Float, var hit: Boolean, val tint: Float
    )

    private var song: Song = Song.TWINKLE
    private var pitches = IntArray(0)
    private var barOf = IntArray(0)
    private var flash = FloatArray(0)
    private var marbles = ArrayList<Marble>()
    private var next = 0
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var halfW = 0.46f
    private var spacing = 0.1f
    private var interval = 0.3f
    private var fall = 1f

    override fun setup(env: SimEnv) {
        halfW = env.halfWidth
        song = Song.TUNES[env.params.choiceIndex("tune").coerceIn(0, Song.TUNES.size - 1)]
        pitches = song.notes.distinct().sorted().toIntArray()
        barOf = IntArray(song.notes.size) { pitches.indexOf(song.notes[it]) }
        flash = FloatArray(pitches.size)
        // A real xylophone's spacing at most: on a wide screen the bars were slabs.
        spacing = min(2f * halfW * 0.9f / pitches.size, MAX_SPACING)
        interval = BEAT / env.params.f("tempo", 1f).coerceIn(0.3f, 3f)
        fall = sqrt(2f * (DROP_Y - BAR_TOP) / GRAVITY)
        marbles = ArrayList()
        next = 0
        elapsed = 0f
        doneAt = -1f
        payoffFrame = false
    }

    override fun tune(env: SimEnv): String = Song.TUNES[env.params.choiceIndex("tune").coerceIn(0, Song.TUNES.size - 1)].name

    private fun barX(b: Int) = spacing * (b + 0.5f - pitches.size * 0.5f)

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        // Release each marble one fall before its beat.
        while (next < song.notes.size && elapsed >= LEAD + next * interval - fall) {
            val b = barOf[next]
            marbles.add(Marble(barX(b), DROP_Y, 0f, 0f, b, LEAD + next * interval, false, b / max(1f, pitches.size - 1f)))
            next++
        }
        val bounce = env.params.f("bounce", 1f)
        val it = marbles.iterator()
        while (it.hasNext()) {
            val m = it.next()
            m.vy -= GRAVITY * h
            m.x += m.vx * h
            m.y += m.vy * h
            if (!m.hit && m.y <= BAR_TOP + RADIUS) {
                m.y = BAR_TOP + RADIUS
                m.hit = true
                flash[m.bar] = 1f
                // Off the bar and away to the side, out of the way of the next.
                m.vy = 1.1f * bounce
                m.vx = (if (m.bar % 2 == 0) -1f else 1f) * (0.1f + 0.1f * env.rng.nextFloat()) * bounce
                env.emit(EventKind.HIT, m.tint, (m.x / max(0.1f, halfW)).coerceIn(-1f, 1f), 0.6f)
                if (marbles.none { !it.hit } && next >= song.notes.size && doneAt < 0f) {
                    doneAt = elapsed
                    payoffFrame = true
                    env.emit(EventKind.DONE, 0.9f, 0f, 1f)
                }
            }
            if (m.y < -1.3f || abs(m.x) > halfW + RADIUS) it.remove()
        }
        for (b in flash.indices) flash[b] = max(0f, flash[b] - h * 3f)
    }

    private fun barLength(t: Float) = 0.5f - 0.26f * t

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val n = pitches.size
        // The frame the bars hang on.
        g.line(-halfW * 0.95f, FRAME_Y, halfW * 0.95f, FRAME_Y, 0.01f, 0xFF3A2E25.toInt())
        // The chute along the top they drop from, and under it a faint lane
        // down to each bar, so every marble's path is laid out before it falls.
        g.line(-halfW * 0.95f, DROP_Y + 0.04f, halfW * 0.95f, DROP_Y + 0.04f, 0.008f, 0xFF5C6373.toInt())
        for (b in 0 until n) {
            val x = barX(b)
            g.line(x, DROP_Y + 0.02f, x, BAR_TOP + 0.03f, 0.004f, palette.at(b / max(1f, n - 1f)), 0.1f)
            g.disc(x, DROP_Y + 0.04f, 0.01f, 0xFF5C6373.toInt())
        }
        // Marbles that have struck their bar bounce back into the machine,
        // behind the bars, and fade: they used to bounce up and fall straight
        // back through the bar they had just hit, in front of it.
        for (m in marbles) if (m.hit) drawMarble(g, palette, m)
        // The cords threaded through the pins, one along each row, as on a
        // real xylophone: the bars hang from them rather than floating.
        for (row in 0..1) {
            val share = if (row == 0) 0.15f else 0.85f
            for (b in 0 until n - 1) {
                val t0 = b / max(1f, n - 1f); val t1 = (b + 1) / max(1f, n - 1f)
                g.line(barX(b), BAR_TOP - barLength(t0) * share, barX(b + 1), BAR_TOP - barLength(t1) * share, 0.004f, 0xFF8A8F9C.toInt(), 0.7f)
            }
        }
        for (b in 0 until n) {
            val t = b / max(1f, n - 1f)
            // Longest on the left, as on a real xylophone.
            val len = barLength(t)
            val x = barX(b)
            val c = palette.at(t)
            val f = flash[b]
            if (f > 0f) g.glow(x, BAR_TOP - len * 0.4f, spacing * 1.4f, c, 0.6f * f)
            val dip = 0.03f * f * f
            g.rect(x, BAR_TOP - len * 0.5f - dip, spacing * 0.38f, len * 0.5f, Palette.brighten(c, 0.4f * f))
            // A lit edge along the top, where the marble strikes.
            g.rect(x, BAR_TOP - 0.006f - dip, spacing * 0.38f, 0.006f, Palette.brighten(c, 0.35f + 0.4f * f))
            // The two pins it hangs from.
            g.disc(x, BAR_TOP - len * 0.15f - dip, spacing * 0.07f, 0xFFB8BCC6.toInt())
            g.disc(x, BAR_TOP - len * 0.85f - dip, spacing * 0.07f, 0xFFB8BCC6.toInt())
        }
        for (m in marbles) if (!m.hit) drawMarble(g, palette, m)
        if (doneAt >= 0f) {
            val k = (1f - (elapsed - doneAt) * 0.7f).coerceIn(0f, 1f)
            if (k > 0f) for (b in pitches.indices) g.glow(barX(b), BAR_TOP - 0.15f, spacing * 1.2f, palette.at(b / max(1f, pitches.size - 1f)), 0.5f * k)
        }
    }

    private fun drawMarble(g: G, palette: Palette, m: Marble) {
        val c = palette.at(m.tint)
        if (!m.hit) {
            // A short streak above it, the faster it falls the longer.
            for (k in 1..3) {
                val back = k * 0.018f
                g.disc(m.x, m.y - m.vy * back, RADIUS * (1f - 0.2f * k), c, 0.3f - 0.08f * k)
            }
            g.disc(m.x, m.y, RADIUS, Palette.brighten(c, 0.2f))
            g.disc(m.x - RADIUS * 0.3f, m.y + RADIUS * 0.3f, RADIUS * 0.3f, Palette.withAlpha(Palette.TEXT_PRIMARY, 0.6f))
        } else {
            // Away from the viewer: smaller and dimmer the longer since the hit.
            val since = (elapsed - m.hitAt).coerceAtLeast(0f)
            val fade = (1f - since * 0.9f).coerceIn(0.15f, 1f)
            val r = RADIUS * (0.6f + 0.4f * fade)
            g.disc(m.x, m.y, r, Palette.brighten(c, 0.1f), 0.85f * fade)
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && elapsed - doneAt > 2.5f

    override fun counters(): List<Pair<String, String>> = listOf(
        "tune" to song.name,
        "notes" to "${marbles.count { it.hit } + (next - marbles.size).coerceAtLeast(0)} / ${song.notes.size}"
    )

    companion object {
        const val GRAVITY = 3f
        const val MAX_SPACING = 0.13f
        const val DROP_Y = 0.92f
        const val BAR_TOP = -0.2f
        const val RADIUS = 0.022f
        /** The beam the bars' frame stands on, under the longest bar. */
        const val FRAME_Y = BAR_TOP - 0.58f
        /** Seconds between notes at tempo 1. */
        const val BEAT = 0.3f
        /** When the first note lands. */
        const val LEAD = 1.2f

        fun def(): SimDef = SimDef(
            id = "art/marble-music",
            title = "Marble music",
            shelf = Shelf.ART,
            family = "Music machines",
            blurb = "Every marble lands on the beat. Together they play a tune you know.",
            formats = listOf(TrapFormat.CHAIN, TrapFormat.GUESS),
            heroLabel = "Play again",
            defaultPalette = "Spectrum",
            defaultSound = "Music box",
            typicalSeconds = 17f,
            params = listOf(
                Param.choice("tune", "Tune", Song.TUNES.map { it.name }, 1, card = Card.DATA, input = ParamInput.CHIPS),
                Param.float("tempo", "Tempo", 0.5f, 2f, 1f, card = Card.MOTION),
                Param.float("bounce", "Bounce", 0.3f, 2f, 1f, card = Card.MOTION)
            ),
            create = { MarbleMusicSim() }
        )
    }
}
