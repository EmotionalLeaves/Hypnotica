package app.hypnotica.sims.physics

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.Shelf
import app.hypnotica.core.sim.Sim
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.sim.SimEnv
import app.hypnotica.core.sim.TouchEvent
import app.hypnotica.core.sim.TrapFormat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Chladni's plate: sand scattered on a metal plate that a bow sets ringing.
 * Where the plate shakes the sand is thrown about; along the lines where it
 * stands still — the nodes — the sand comes to rest, and the grains find
 * those lines by themselves and draw them. Change the note and the whole
 * pattern dissolves and redraws itself as another.
 *
 * The plate here is the textbook square one, whose standing waves are
 * cos(nπx)cos(mπy) ± cos(mπx)cos(nπy). Each grain is kicked at random by an
 * amount that grows with how hard the plate moves under it, so grains on a
 * node sit still and grains between nodes wander until they land on one.
 * The plate steps up through its notes, endlessly.
 */
class ChladniSim : Sim {

    private var count = 6000
    private val x = FloatArray(MAX_N)
    private val y = FloatArray(MAX_N)
    private var mode = 0
    private var n = 1
    private var m = 2
    private var prevN = 1
    private var prevM = 2
    private var sign = -1f
    private var prevSign = -1f
    /** 0 → 1 as the plate changes from the last note to this one. */
    private var blend = 1f
    private var held = 0f
    private var elapsed = 0f
    private var settled = 0f
    private var patterns = 0
    private var payoffFrame = false
    private var paid = false
    private var half = 0.45f
    /** The plate is as tall as the screen allows: a stretched square's modes. */
    private var halfH = 0.45f
    private var calm = 0f
    private var ring = 0f
    /** A tap asks for the next note at once. */
    private var bowed = false

    override fun setup(env: SimEnv) {
        count = env.params.i("count", 9000).coerceIn(500, MAX_N)
        half = min(env.halfWidth * 0.95f, 0.9f)
        halfH = min(0.9f, half * 1.9f)
        val rng = env.rng
        for (i in 0 until count) {
            x[i] = (rng.nextFloat() * 2f - 1f)
            y[i] = (rng.nextFloat() * 2f - 1f)
        }
        mode = env.params.i("start", 2).coerceIn(0, MODES.size - 1)
        n = MODES[mode][0]; m = MODES[mode][1]; sign = MODES[mode][2].toFloat()
        prevN = n; prevM = m; prevSign = sign
        blend = 1f; held = 0f; elapsed = 0f; settled = 0f; patterns = 0
        payoffFrame = false; paid = false; calm = 0f; ring = 1f; bowed = false
    }

    /** How hard the plate moves at (u, v) in [-1, 1]², for mode (a, b). */
    private fun amp(a: Int, b: Int, sg: Float, u: Float, v: Float): Float {
        val pu = (u + 1f) * 0.5f * PI.toFloat(); val pv = (v + 1f) * 0.5f * PI.toFloat()
        return abs(cos(a * pu) * cos(b * pv) + sg * cos(b * pu) * cos(a * pv)) * 0.5f
    }

    private var wf = 0f
    private var wgu = 0f
    private var wgv = 0f

    /** Displacement of mode (a, b) at (u, v) into [wf], its slope into [wgu], [wgv]. */
    private fun wave(a: Int, b: Int, sg: Float, u: Float, v: Float) {
        val h = PI.toFloat() * 0.5f
        val pu = (u + 1f) * h; val pv = (v + 1f) * h
        val cau = cos(a * pu); val cbu = cos(b * pu); val cav = cos(a * pv); val cbv = cos(b * pv)
        val sau = sin(a * pu); val sbu = sin(b * pu); val sav = sin(a * pv); val sbv = sin(b * pv)
        wf = cau * cbv + sg * cbu * cav
        wgu = h * (-a * sau * cbv - sg * b * sbu * cav)
        wgv = h * (-b * cau * sbv - sg * a * cbu * sav)
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val a = abs(dt)
        elapsed += a
        ring = max(0f, ring - a * 0.8f)
        val loud = env.params.f("loud", 1f).coerceIn(0.2f, 3f)
        blend = min(1f, blend + a / CHANGE_S)
        held += a
        val rng = env.rng
        var moving = 0f
        val kick = KICK * loud * min(a, 0.05f) * 60f
        val pull = PULL * loud * min(a, 0.05f) * 60f
        for (i in 0 until count) {
            val u = x[i]; val v = y[i]
            // The plate's displacement and its slope under the grain, blended
            // across a change of note.
            wave(n, m, sign, u, v)
            var f = wf; var gu = wgu; var gv = wgv
            if (blend < 1f) {
                wave(prevN, prevM, prevSign, u, v)
                f = f * blend + wf * (1f - blend)
                gu = gu * blend + wgu * (1f - blend)
                gv = gv * blend + wgv * (1f - blend)
            }
            val s = abs(f) * 0.5f
            // Thrown about in proportion to the shaking — a grain on a node
            // does not move at all — and bounced, on average, downhill in
            // the shaking: toward the nearest line that stands still.
            val k = s * kick
            if (k > 1e-5f) {
                val inv = 1f / (1f + (gu * gu + gv * gv) * 0.02f)
                x[i] = (u + (rng.nextFloat() * 2f - 1f) * k - f * gu * pull * inv).coerceIn(-1f, 1f)
                y[i] = (v + (rng.nextFloat() * 2f - 1f) * k - f * gv * pull * inv).coerceIn(-1f, 1f)
                moving += s
            }
        }
        calm = moving / count
        // The pattern has drawn itself when hardly any sand is still moving.
        if (blend >= 1f && calm < SETTLED) {
            settled += a
            if (settled > 0.4f && !paid) {
                paid = true; payoffFrame = true
                env.emit(EventKind.DONE, 0.8f, 0f, 0.8f)
            }
        } else settled = 0f
        // Then, after a look at it, the next note.
        if (blend >= 1f && (settled > HOLD_S || held > MAX_NOTE_S || bowed)) {
            bowed = false
            patterns++
            prevN = n; prevM = m; prevSign = sign
            mode = (mode + 1) % MODES.size
            n = MODES[mode][0]; m = MODES[mode][1]; sign = MODES[mode][2].toFloat()
            blend = 0f; held = 0f; settled = 0f; ring = 1f
            // The bow's first stroke of the new note jolts the whole plate:
            // the sand hops off its old lines a little. Without it the grains
            // gathered where old and new lines cross, and by the fifth note
            // the patterns were drawn in clumps.
            for (i in 0 until count) {
                x[i] = (x[i] + (rng.nextFloat() * 2f - 1f) * STRIKE).coerceIn(-1f, 1f)
                y[i] = (y[i] + (rng.nextFloat() * 2f - 1f) * STRIKE).coerceIn(-1f, 1f)
            }
            env.emit(EventKind.HIT, (mode.toFloat() / MODES.size), 0f, 0.7f)
        }
        // The hiss of sand on the plate, louder while it dances.
        if ((elapsed * 8f).toInt() != ((elapsed - a) * 8f).toInt() && calm > SETTLED) {
            env.emit(EventKind.STEP, 0.4f + calm, 0f, (calm * 1.6f).coerceIn(0.03f, 0.2f))
        }
    }

    override fun onInput(env: SimEnv, e: TouchEvent) {
        if (e.kind == TouchEvent.Kind.TAP) bowed = true
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        // The plate: dark metal with a rim, struck bright as each note starts.
        g.rect(0f, 0f, half, halfH, Palette.lerp(Palette.BG, pal.at(0.15f), 0.12f + 0.1f * ring))
        val rim = Palette.withAlpha(pal.at(0.3f), 0.5f)
        g.line(-half, -halfH, half, -halfH, 0.005f, rim)
        g.line(-half, halfH, half, halfH, 0.005f, rim)
        g.line(-half, -halfH, -half, halfH, 0.005f, rim)
        g.line(half, -halfH, half, halfH, 0.005f, rim)
        // The sand, warm where it rests and paler where it is still being thrown.
        g.reservePoints(count)
        val rest = pal.at(0.75f)
        val dance = pal.at(0.35f)
        val size = (0.012f - count / 1_200_000f).coerceIn(0.005f, 0.012f)
        for (i in 0 until count) {
            val u = x[i]; val v = y[i]
            val s = amp(n, m, sign, u, v)
            val c = if (s < 0.06f) rest else Palette.lerp(rest, dance, min(1f, s * 3f))
            g.point(u * half, v * halfH, size, c)
        }
    }

    override fun payoff(): Boolean = payoffFrame

    /** Endless: the plate goes on through its notes. */
    override fun finished(): Boolean = false

    override fun counters(): List<Pair<String, String>> = listOf(
        "note" to "n=$n, m=$m",
        "patterns" to "${patterns + 1}",
        "grains" to String.format(java.util.Locale.US, "%,d", count)
    )

    companion object {
        const val MAX_N = 20000
        /** How far a grain at an antinode can be thrown per frame, in plate half-widths. */
        const val KICK = 0.02f
        /** How fast a shaken grain drifts down the slope of the shaking. */
        const val PULL = 0.0016f
        const val CHANGE_S = 1.2f
        const val STRIKE = 0.14f
        const val SETTLED = 0.012f
        const val HOLD_S = 3.5f
        const val MAX_NOTE_S = 14f
        /** The notes, low to high: pairs (n, m) of the square plate's modes. */
        // Alternate signs: the minus form has the diagonal as a node in
        // every mode, and over a few notes it hoarded all the sand.
        val MODES = arrayOf(
            intArrayOf(1, 2, -1), intArrayOf(1, 4, 1), intArrayOf(2, 5, -1), intArrayOf(3, 4, 1),
            intArrayOf(1, 6, -1), intArrayOf(3, 5, 1), intArrayOf(4, 7, -1), intArrayOf(2, 7, 1),
            intArrayOf(5, 8, -1), intArrayOf(3, 8, 1), intArrayOf(6, 11, -1)
        )

        fun def(): SimDef = SimDef(
            id = "physics/chladni",
            title = "Singing sand",
            shelf = Shelf.PHYSICS,
            family = "Waves",
            blurb = "Sand on a ringing plate runs to the lines that stand still — and draws the note.",
            formats = listOf(TrapFormat.REVEAL, TrapFormat.GROW, TrapFormat.SONG),
            heroLabel = "Scatter",
            defaultPalette = "Ember",
            typicalSeconds = 6f,
            params = listOf(
                Param.int("count", "Grains", 500, MAX_N, 9000, card = Card.DATA, logScale = true),
                Param.int("start", "First note", 0, 10, 2, card = Card.DATA),
                Param.float("loud", "Bow pressure", 0.2f, 3f, 1f, card = Card.MOTION, logScale = true)
            ),
            create = { ChladniSim() }
        )
    }
}
