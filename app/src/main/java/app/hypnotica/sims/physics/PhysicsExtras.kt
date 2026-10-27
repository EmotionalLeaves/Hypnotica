package app.hypnotica.sims.physics

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.recipe.ParamInput
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.LawKnob
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.Parallel
import app.hypnotica.core.sim.Shelf
import app.hypnotica.core.sim.Sim
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.sim.SimEnv
import app.hypnotica.core.sim.TouchEvent
import app.hypnotica.core.sim.TrapFormat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * N-body gravity: every mass pulls on every other one.
 *
 * The force law comes from the Laws table, so switching to repulsive gravity or
 * an inverse-cube falloff genuinely changes the orbits rather than just the
 * label.
 */
class NBodySim : Sim {

    private var n = 400
    private var px = FloatArray(0)
    private var py = FloatArray(0)
    private var vx = FloatArray(0)
    private var vy = FloatArray(0)
    private var mass = FloatArray(0)
    private var tint = FloatArray(0)
    private var trailX = FloatArray(0)
    private var trailY = FloatArray(0)
    private var trailTint = FloatArray(0)
    private var trailHead = 0
    private var trailFill = 0
    private var elapsed = 0f
    private var payoffFrame = false
    private var centralMass = 0f
    private var collapsed = false

    override fun setup(env: SimEnv) {
        val p = env.params
        n = p.i("bodies", 400).coerceIn(2, 4000)
        centralMass = p.f("central", 60f)
        px = FloatArray(n); py = FloatArray(n)
        vx = FloatArray(n); vy = FloatArray(n)
        mass = FloatArray(n); tint = FloatArray(n)
        val trail = p.i("trail", 4000).coerceIn(0, 40000)
        trailX = FloatArray(max(1, trail))
        trailY = FloatArray(max(1, trail))
        trailTint = FloatArray(max(1, trail))
        trailHead = 0
        trailFill = 0

        val disc = p.choiceIndex("start") == 0
        for (i in 0 until n) {
            if (disc) {
                // A rotating disc, which is what makes spiral structure appear.
                val r = 0.12f + sqrt(env.rng.nextFloat()) * 0.78f
                val a = env.rng.range(0f, 2f * PI.toFloat())
                px[i] = cos(a) * r * env.halfWidth / 0.5f * 0.5f
                py[i] = sin(a) * r
                val orbital = sqrt(centralMass * 0.0016f / max(0.05f, r))
                vx[i] = -sin(a) * orbital
                vy[i] = cos(a) * orbital
            } else {
                px[i] = env.rng.range(-env.halfWidth * 0.9f, env.halfWidth * 0.9f)
                py[i] = env.rng.range(-0.9f, 0.9f)
                vx[i] = env.rng.range(-0.05f, 0.05f)
                vy[i] = env.rng.range(-0.05f, 0.05f)
            }
            mass[i] = env.rng.range(0.6f, 1.4f)
            tint[i] = env.rng.nextFloat()
        }
        elapsed = 0f
        payoffFrame = false
        collapsed = false
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        val laws = env.laws
        val softening = 0.01f
        val sign = laws.massSign

        Parallel.forRange(n) { from, to ->
            for (i in from until to) {
                var ax = 0f
                var ay = 0f
                // The central mass stands in for the galaxy's core.
                if (centralMass > 0f) {
                    val dx = -px[i]
                    val dy = -py[i]
                    val r2 = dx * dx + dy * dy + softening
                    val r = sqrt(r2)
                    val f = laws.pairForce(r, centralMass, mass[i]) * 0.0016f * sign
                    ax += f * dx / r
                    ay += f * dy / r
                }
                // All pairs, but only when the count is small enough to afford it.
                if (n <= PAIRWISE_LIMIT) {
                    for (j in 0 until n) {
                        if (j == i) continue
                        val dx = px[j] - px[i]
                        val dy = py[j] - py[i]
                        val r2 = dx * dx + dy * dy + softening
                        val r = sqrt(r2)
                        val f = laws.pairForce(r, mass[j], mass[i]) * 0.0016f * sign
                        ax += f * dx / r
                        ay += f * dy / r
                    }
                }
                vx[i] += ax * dt
                vy[i] += ay * dt
                val sp = hypot(vx[i], vy[i])
                if (sp > MAX_SPEED) {
                    vx[i] *= MAX_SPEED / sp
                    vy[i] *= MAX_SPEED / sp
                }
                px[i] += vx[i] * dt
                py[i] += vy[i] * dt
            }
        }

        if (trailX.size > 1) {
            val sampled = min(n, 12)
            for (k in 0 until sampled) {
                val i = (k * n) / sampled
                trailX[trailHead] = px[i]
                trailY[trailHead] = py[i]
                trailTint[trailHead] = tint[i]
                trailHead = (trailHead + 1) % trailX.size
                if (trailFill < trailX.size) trailFill++
            }
        }

        var inner = 0
        for (i in 0 until n) if (hypot(px[i], py[i]) < 0.3f) inner++
        env.emit(EventKind.STEP, (inner.toFloat() / n).coerceIn(0f, 1f), 0f, 0.3f)
        if (!collapsed && elapsed > 8f && inner > n * 0.45f) {
            collapsed = true
            payoffFrame = true
            env.emit(EventKind.DONE, 0.85f, 0f, 1f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        g.reservePoints(n + trailFill)
        for (k in 0 until trailFill) {
            val idx = (trailHead - 1 - k + trailX.size * 2) % trailX.size
            val age = k.toFloat() / trailFill
            g.point(
                trailX[idx], trailY[idx], 0.0035f,
                Palette.withAlpha(palette.at(trailTint[idx]), (1f - age) * 0.4f)
            )
        }
        for (i in 0 until n) {
            g.point(px[i], py[i], 0.006f + mass[i] * 0.003f, palette.at(tint[i]))
        }
        if (centralMass > 0f) {
            g.glow(0f, 0f, 0.2f, Palette.WARNING, 0.5f)
            g.disc(0f, 0f, 0.035f, Palette.brighten(Palette.WARNING, 0.3f))
        }
        if (payoffFrame) g.flash(0.3f)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> =
        listOf("bodies" to String.format(java.util.Locale.US, "%,d", n))

    companion object {
        private const val MAX_SPEED = 3.2f
        /** Above this, all-pairs is dropped and only the central mass pulls. */
        const val PAIRWISE_LIMIT = 900

        fun def(): SimDef = SimDef(
            id = "physics/n-body",
            title = "N-body gravity",
            shelf = Shelf.PHYSICS,
            family = "Orbits, gravity & space",
            blurb = "Every mass pulling on every other one, until arms form.",
            formats = listOf(TrapFormat.GROW, TrapFormat.LOOP, TrapFormat.SNAP),
            lawKnobs = setOf(LawKnob.GRAVITY, LawKnob.PAIRWISE_FORCE),
            heroLabel = "New galaxy",
            defaultPalette = "Ember",
            typicalSeconds = 30f,
            params = listOf(
                Param.choice("start", "Starting shape", listOf("Rotating disc", "Random cloud"), 0,
                    card = Card.DATA, input = ParamInput.TILES),
                Param.int("bodies", "Bodies", 2, 4000, 400, card = Card.DATA, logScale = true),
                Param.float("central", "Central mass", 0f, 200f, 60f, card = Card.DATA),
                // Not "Trails": the Look card already carries the player's own
                // smear slider under that name, and two identical labels on one
                // card meant the dead one looked like the live one.
                Param.int("trail", "Path length", 0, 40000, 4000, card = Card.LOOK, logScale = true)
            ),
            create = { NBodySim() }
        )
    }
}

/**
 * A gear train: tooth counts decide the ratios, and the last wheel turns at a
 * speed you can work out but would never guess.
 */
class GearsSim : Sim {

    private class Gear(val x: Float, val y: Float, val radius: Float, val teeth: Int, val dir: Float, val tint: Float) {
        var angle = 0f
    }

    private val gears = ArrayList<Gear>()
    private var speed = 0.9f
    private var elapsed = 0f
    private var payoffFrame = false
    private var clicks = 0L
    private var lastTooth = 0
    /** True once the train has turned a full revolution: the payoff. */
    private var cycled = false

    override fun setup(env: SimEnv) {
        val p = env.params
        gears.clear()
        cycled = false
        val count = p.i("gears", 5).coerceIn(2, 9)
        speed = p.f("speed", 0.9f)
        val toothSize = 0.055f
        var x = -env.halfWidth * 0.72f
        var dir = 1f
        var prevRadius = 0f
        for (i in 0 until count) {
            val teeth = p.i("teeth", 14).coerceIn(6, 40) + i * p.i("step", 4).coerceIn(0, 12)
            val radius = teeth * toothSize / (2f * PI.toFloat()) * 2f
            if (i > 0) x += prevRadius + radius
            gears.add(Gear(x, sin(i * 0.9f) * 0.22f, radius, teeth, dir, i.toFloat() / count))
            dir = -dir
            prevRadius = radius
        }
        elapsed = 0f
        payoffFrame = false
        clicks = 0
        lastTooth = 0
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        if (gears.isEmpty()) return
        val drive = speed * (1f - env.laws.friction * 0.5f)
        val first = gears[0]
        first.angle += drive * dt * first.dir
        for (i in 1 until gears.size) {
            val prev = gears[i - 1]
            val gear = gears[i]
            // Meshed wheels turn in proportion to their tooth counts.
            gear.angle = -prev.angle * prev.teeth / gear.teeth
        }
        val tooth = (first.angle / (2f * PI.toFloat()) * first.teeth).toInt()
        if (tooth != lastTooth) {
            lastTooth = tooth
            clicks++
            env.emit(EventKind.HIT, (gears.size - 1).toFloat() / max(1, gears.size), 0f, 0.4f)
        }
        // One full revolution of the slowest gear in the train: every tooth is
         // back where it started, which is the moment a gear train is for.
         // Timing it at fourteen seconds said nothing about the mechanism.
        val slowest = gears.lastOrNull()
        if (!cycled && slowest != null && abs(slowest.angle) >= 2f * PI.toFloat()) {
            cycled = true
            payoffFrame = true
            env.emit(EventKind.DONE, 0.7f, 0f, 1f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        for (gear in gears) {
            val c = palette.at(gear.tint)
            g.ring(gear.x, gear.y, gear.radius, gear.radius * 0.18f, c, alpha = 0.9f)
            g.disc(gear.x, gear.y, gear.radius * 0.16f, c)
            for (t in 0 until gear.teeth) {
                val a = gear.angle + t * 2f * PI.toFloat() / gear.teeth
                val r = gear.radius * 1.06f
                g.rect(
                    gear.x + cos(a) * r, gear.y + sin(a) * r,
                    gear.radius * 0.11f, gear.radius * 0.055f, c, a
                )
            }
            // A spoke, so the rotation is actually visible.
            g.line(
                gear.x, gear.y,
                gear.x + cos(gear.angle) * gear.radius * 0.8f,
                gear.y + sin(gear.angle) * gear.radius * 0.8f,
                gear.radius * 0.06f, Palette.brighten(c, 0.3f)
            )
        }
        if (payoffFrame) g.flash(0.25f)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> {
        if (gears.size < 2) return emptyList()
        val ratio = gears.first().teeth.toFloat() / gears.last().teeth
        return listOf(
            "gears" to gears.size.toString(),
            "ratio" to String.format(java.util.Locale.US, "%.2f : 1", 1f / ratio)
        )
    }

    companion object {
        fun def(): SimDef = SimDef(
            id = "physics/gears",
            title = "Gear train",
            shelf = Shelf.PHYSICS,
            family = "Mechanics & machines",
            blurb = "Tooth counts decide the ratio, all the way down the line.",
            formats = listOf(TrapFormat.LOOP, TrapFormat.CHAIN, TrapFormat.SONG),
            lawKnobs = setOf(LawKnob.FRICTION),
            heroLabel = "New ratios",
            defaultPalette = "Mono glow",
            typicalSeconds = 20f,
            params = listOf(
                Param.int("gears", "Gears", 2, 9, 5, card = Card.DATA),
                Param.int("teeth", "First tooth count", 6, 40, 14, card = Card.DATA),
                Param.int("step", "Teeth added each gear", 0, 12, 4, card = Card.DATA),
                // Below this the gears do not visibly turn and the run is silent.
                Param.float("speed", "Drive speed", 0.2f, 4f, 0.9f, card = Card.MOTION)
            ),
            create = { GearsSim() }
        )
    }
}

/**
 * A rope and a sheet of cloth, both run as a grid of points held together by
 * distance constraints. Verlet integration keeps it stable at any step size.
 */
class ClothSim : Sim {

    private var cols = 26
    private var rows = 20
    private var x = FloatArray(0)
    private var y = FloatArray(0)
    private var ox = FloatArray(0)
    private var oy = FloatArray(0)
    private var pinned = BooleanArray(0)
    private var cut = BooleanArray(0)
    private var rest = 0f
    private var payoffFrame = false
    private var elapsed = 0f
    private var tearing = false
    private var tears = 0
    private var lastTears = 0
    private var swayClock = 0f
    private var settled = false
    private var stillFor = 0f
    private var peakMotion = 0f

    override fun setup(env: SimEnv) {
        val p = env.params
        settled = false
        stillFor = 0f
        peakMotion = 0f
        elapsed = 0f
        cols = p.i("cols", 26).coerceIn(3, 200)
        rows = p.i("rows", 20).coerceIn(2, 200)
        val n = cols * rows
        x = FloatArray(n); y = FloatArray(n)
        ox = FloatArray(n); oy = FloatArray(n)
        pinned = BooleanArray(n)
        cut = BooleanArray(n)
        rest = min(1.5f / cols, 1.4f / rows)
        val startX = -rest * (cols - 1) * 0.5f
        val startY = 0.82f
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val i = r * cols + c
                x[i] = startX + c * rest
                y[i] = startY - r * rest
                ox[i] = x[i]
                oy[i] = y[i]
            }
        }
        val pins = p.i("pins", 5).coerceIn(1, cols)
        for (k in 0 until pins) {
            val c = if (pins == 1) cols / 2 else (k * (cols - 1)) / (pins - 1)
            pinned[c] = true
        }
        tearing = p.b("tear", false)
        tears = 0
        lastTears = 0
        swayClock = 0f
        payoffFrame = false
        elapsed = 0f
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        val laws = env.laws
        val g = laws.gravity * 0.04f * laws.gravityDirY
        val drag = 1f - (laws.friction * 0.2f).coerceIn(0f, 0.3f)
        val wind = env.params.f("wind", 0.2f) *
            sin(elapsed * 1.7f) * cos(elapsed * 0.6f)

        val n = cols * rows
        for (i in 0 until n) {
            if (pinned[i] || cut[i]) continue
            val vx = (x[i] - ox[i]) * drag
            val vy = (y[i] - oy[i]) * drag
            ox[i] = x[i]
            oy[i] = y[i]
            x[i] += vx + wind * dt * dt * 40f
            y[i] += vy - g * dt * dt * 40f
        }

        // Several relaxation passes keep the cloth from looking like jelly.
        repeat(env.params.i("stiffness", 4).coerceIn(1, 12)) {
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val i = r * cols + c
                    if (cut[i]) continue
                    if (c < cols - 1) relax(i, i + 1)
                    if (r < rows - 1) relax(i, i + cols)
                }
            }
        }
        // Each thread that snaps is a note, and the sway itself is a slow pulse,
        // so the cloth is never silent while it is moving.
        if (tears > lastTears) {
            val fresh = (tears - lastTears).coerceAtMost(12)
            lastTears = tears
            env.emit(EventKind.DEATH, 0.25f + 0.5f * (tears % 7) / 7f, 0f, min(1f, 0.4f + fresh * 0.08f))
        }
        swayClock += abs(dt)
        if (swayClock > 0.22f) {
            swayClock = 0f
            var motion = 0f
            var sampled = 0
            var i = 0
            while (i < n) {
                if (!cut[i] && !pinned[i]) {
                    motion += abs(x[i] - ox[i]) + abs(y[i] - oy[i])
                    sampled++
                }
                i += maxOf(1, n / 64)
            }
            if (sampled > 0) {
                val amount = (motion / sampled * 60f).coerceIn(0f, 1f)
                if (amount > 0.04f) env.emit(EventKind.STEP, amount, 0f, 0.2f + amount * 0.5f)
            }
        }

        // The payoff is the sheet arriving: it drops, swings, and then is just
        // swaying in the wind. Measured against its own peak rather than
        // against zero, because the wind never stops — waiting for stillness
        // meant waiting for ever, and a clock at six seconds fired a quarter of
        // the way into the run over a sheet that was still falling.
        if (!settled) {
            val vertices = cols * rows
            var motion = 0f
            var sampled = 0
            var i = 0
            while (i < vertices) {
                if (!cut[i]) {
                    motion += abs(x[i] - ox[i]) + abs(y[i] - oy[i])
                    sampled++
                }
                i += maxOf(1, vertices / 64)
            }
            val perVertex = if (sampled == 0) 0f else motion / sampled
            if (perVertex > peakMotion) peakMotion = perVertex
            val calm = peakMotion > 0f && perVertex < peakMotion * CALM_SHARE
            if (calm && elapsed > 3f) stillFor += abs(dt) else stillFor = 0f
            // A tear is the louder moment when the sheet is set up to give way,
            // so it wins over the settling.
            val tore = tears > 0
            if (tore || stillFor > STILL_SECONDS) {
                settled = true
                payoffFrame = true
                env.emit(EventKind.DONE, 0.6f, 0f, 0.9f)
            }
        }
    }

    private fun relax(a: Int, b: Int) {
        if (cut[a] || cut[b]) return
        val dx = x[b] - x[a]
        val dy = y[b] - y[a]
        val d = hypot(dx, dy)
        if (d < 1e-6f) return
        if (tearing && d > rest * 4.5f) {
            cut[b] = true
            tears++
            return
        }
        val diff = (d - rest) / d * 0.5f
        val mx = dx * diff
        val my = dy * diff
        if (!pinned[a]) { x[a] += mx; y[a] += my }
        if (!pinned[b]) { x[b] -= mx; y[b] -= my }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        g.reserveInstances(cols * rows * 2)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val i = r * cols + c
                if (cut[i]) continue
                val t = r.toFloat() / max(1, rows - 1)
                val col = palette.at(t)
                if (c < cols - 1 && !cut[i + 1]) {
                    g.line(x[i], y[i], x[i + 1], y[i + 1], 0.0032f, col, 0.9f)
                }
                if (r < rows - 1 && !cut[i + cols]) {
                    g.line(x[i], y[i], x[i + cols], y[i + cols], 0.0032f, col, 0.9f)
                }
                if (pinned[i]) g.disc(x[i], y[i], 0.012f, Palette.WARNING)
            }
        }
        if (payoffFrame) g.flash(0.2f)
    }

    override fun onInput(env: SimEnv, e: TouchEvent) {
        // Drag the cloth about; it settles again on its own.
        val n = cols * rows
        for (i in 0 until n) {
            if (pinned[i] || cut[i]) continue
            val dx = x[i] - e.x
            val dy = y[i] - e.y
            val d2 = dx * dx + dy * dy
            if (d2 < 0.02f) {
                x[i] = e.x + dx * 0.3f
                y[i] = e.y + dy * 0.3f
            }
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> =
        listOf("points" to (cols * rows).toString())

    companion object {
        /** Share of its own peak motion below which the sheet has arrived. */
        const val CALM_SHARE = 0.22f

        /** How long it has to stay calm before the cloth has settled. */
        const val STILL_SECONDS = 0.8f

        fun def(): SimDef = SimDef(
            id = "physics/cloth",
            title = "Cloth",
            shelf = Shelf.PHYSICS,
            family = "Fluids & soft bodies",
            blurb = "A hanging sheet that swings, settles and can be dragged.",
            formats = listOf(TrapFormat.LOOP, TrapFormat.CUT),
            lawKnobs = setOf(LawKnob.GRAVITY, LawKnob.GRAVITY_DIRECTION, LawKnob.FRICTION),
            heroLabel = "Shake it",
            defaultPalette = "Ocean",
            typicalSeconds = 24f,
            params = listOf(
                Param.int("cols", "Columns", 3, 200, 26, card = Card.DATA, logScale = true),
                Param.int("rows", "Rows", 2, 200, 20, card = Card.DATA, logScale = true),
                Param.int("pins", "Pins", 1, 12, 5, card = Card.DATA),
                Param.bool("tear", "Let it tear", false, card = Card.DATA),
                Param.float("wind", "Wind", 0f, 1.5f, 0.2f, card = Card.MOTION),
                Param.int("stiffness", "Stiffness", 1, 12, 4, card = Card.MOTION)
            ),
            create = { ClothSim() }
        )
    }
}

/**
 * Light through glass: rays refract by Snell's law, bend through a prism and
 * split by wavelength, which is where a rainbow actually comes from.
 */
class OpticsSim : Sim {

    private class Ray(val tint: Float, val wavelength: Float) {
        val xs = FloatArray(MAX_SEGMENTS)
        val ys = FloatArray(MAX_SEGMENTS)
        var count = 0
    }

    private val rays = ArrayList<Ray>()
    private var elapsed = 0f
    private var payoffFrame = false
    private var focused = false
    private var tightest = Float.MAX_VALUE
    private var tightenedFor = 0f
    private var sourceY = 0.35f
    private var angle = -0.35f
    private var shape = 0

    override fun setup(env: SimEnv) {
        val p = env.params
        rays.clear()
        shape = p.choiceIndex("shape")
        val count = p.i("rays", 9).coerceIn(1, 60)
        for (i in 0 until count) {
            val t = if (count == 1) 0.5f else i.toFloat() / (count - 1)
            rays.add(Ray(t, 1f + 0.12f * t))
        }
        sourceY = p.f("height", 0.35f)
        angle = p.f("angle", -0.35f)
        elapsed = 0f
        payoffFrame = false
        focused = false
        tightest = Float.MAX_VALUE
        tightenedFor = 0f
        trace(env)
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        if (env.params.b("sweep", true)) {
            angle = -0.35f + sin(elapsed * 0.4f) * 0.55f
        }
        trace(env)
        env.emit(EventKind.STEP, ((angle + 1f) * 0.5f).coerceIn(0f, 1f), 0f, 0.25f)
        // The focus: the moment the rays are at their tightest on the far
         // side of the glass, which is what a lens does and what the sweep is
         // hunting for. A clock at twelve seconds fired wherever the sweep
         // happened to be.
        val spread = tightestSpread()
        if (spread < tightest) {
            tightest = spread
            tightenedFor = 0f
        } else {
            tightenedFor += abs(dt)
        }
        if (!focused && tightest < FOCUS_SPREAD && tightenedFor > 0.4f && elapsed > 2f) {
            focused = true
            payoffFrame = true
            env.emit(EventKind.DONE, 0.8f, 0f, 1f)
        }
    }

    /**
     * The tightest the beam gets anywhere along its path.
     *
     * Every ray marches the same step length, so segment [i] is the same
     * distance along for all of them: the narrowest spread across those
     * samples is the focus. Measuring where the rays leave the picture instead
     * misses it entirely — past the focus they have already spread out again,
     * which is why the focus was never found.
     */
    private fun tightestSpread(): Float {
        if (rays.size < 2) return Float.MAX_VALUE
        var shortest = Int.MAX_VALUE
        for (ray in rays) if (ray.count < shortest) shortest = ray.count
        if (shortest < 3) return Float.MAX_VALUE
        var best = Float.MAX_VALUE
        // Only past the glass: before it the rays start out side by side.
        var i = shortest / 3
        while (i < shortest) {
            var lowest = Float.MAX_VALUE
            var highest = -Float.MAX_VALUE
            for (ray in rays) {
                val y = ray.ys[i]
                if (y < lowest) lowest = y
                if (y > highest) highest = y
            }
            val spread = highest - lowest
            if (spread < best) best = spread
            i++
        }
        return best
    }

    /** March each ray, refracting whenever it crosses into or out of the glass. */
    private fun trace(env: SimEnv) {
        val halfW = env.halfWidth
        for ((index, ray) in rays.withIndex()) {
            var x = -halfW * 0.98f
            var y = sourceY - index * 0.035f
            var dx = cos(angle)
            var dy = sin(angle)
            ray.count = 0
            ray.xs[ray.count] = x
            ray.ys[ray.count] = y
            ray.count++
            var inside = false
            var guard = MAX_SEGMENTS - 2
            val stepLen = 0.012f
            while (guard-- > 0 && ray.count < MAX_SEGMENTS - 2 &&
                x < halfW && x > -halfW * 1.02f && abs(y) < 1.05f
            ) {
                val nx = x + dx * stepLen
                val ny = y + dy * stepLen
                val nowInside = insideGlass(nx, ny)
                if (nowInside != inside) {
                    val normal = glassNormal(nx, ny)
                    val n1 = if (inside) ray.wavelength else 1f
                    val n2 = if (inside) 1f else ray.wavelength
                    val refracted = refract(dx, dy, normal[0], normal[1], n1 / n2)
                    if (refracted == null) {
                        // Total internal reflection.
                        val dot = dx * normal[0] + dy * normal[1]
                        dx -= 2f * dot * normal[0]
                        dy -= 2f * dot * normal[1]
                    } else {
                        dx = refracted[0]
                        dy = refracted[1]
                        inside = nowInside
                    }
                    if (ray.count >= MAX_SEGMENTS) break
                    ray.xs[ray.count] = nx
                    ray.ys[ray.count] = ny
                    ray.count++
                }
                x = nx
                y = ny
                if (ray.count >= MAX_SEGMENTS) break
                ray.xs[ray.count] = x
                ray.ys[ray.count] = y
                ray.count++
            }
        }
    }

    private fun insideGlass(x: Float, y: Float): Boolean = when (shape) {
        0 -> {  // prism: a triangle
            val top = 0.52f
            val bottom = -0.5f
            if (y < bottom || y > top) false
            else {
                val t = (top - y) / (top - bottom)
                abs(x) < t * 0.42f
            }
        }
        1 -> {  // lens: two overlapping circles
            val r = 0.75f
            hypot(x + 0.45f, y) < r && hypot(x - 0.45f, y) < r
        }
        else -> abs(x) < 0.35f && abs(y) < 0.55f  // slab
    }

    private fun glassNormal(x: Float, y: Float): FloatArray {
        // Numerical normal: the gradient of "inside" over a small box.
        val e = 0.01f
        val dx = (if (insideGlass(x + e, y)) 1f else 0f) - (if (insideGlass(x - e, y)) 1f else 0f)
        val dy = (if (insideGlass(x, y + e)) 1f else 0f) - (if (insideGlass(x, y - e)) 1f else 0f)
        val len = hypot(dx, dy)
        return if (len < 1e-6f) floatArrayOf(1f, 0f) else floatArrayOf(-dx / len, -dy / len)
    }

    private fun refract(dx: Float, dy: Float, nx: Float, ny: Float, eta: Float): FloatArray? {
        val cosI = -(dx * nx + dy * ny)
        val sinT2 = eta * eta * (1f - cosI * cosI)
        if (sinT2 > 1f) return null
        val cosT = sqrt(1f - sinT2)
        return floatArrayOf(eta * dx + (eta * cosI - cosT) * nx, eta * dy + (eta * cosI - cosT) * ny)
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        // The glass itself.
        val glassColour = Palette.withAlpha(Palette.TEXT_SECONDARY, 0.22f)
        when (shape) {
            0 -> {
                g.line(0f, 0.52f, -0.42f, -0.5f, 0.006f, glassColour)
                g.line(0f, 0.52f, 0.42f, -0.5f, 0.006f, glassColour)
                g.line(-0.42f, -0.5f, 0.42f, -0.5f, 0.006f, glassColour)
            }
            1 -> {
                g.ring(-0.45f, 0f, 0.75f, 0.005f, glassColour, alpha = 0.5f)
                g.ring(0.45f, 0f, 0.75f, 0.005f, glassColour, alpha = 0.5f)
            }
            else -> g.rect(0f, 0f, 0.35f, 0.55f, glassColour, 0f, 0.35f)
        }
        for (ray in rays) {
            val c = Palette.hsv(250f - ray.tint * 240f, 0.8f, 1f)
            for (i in 0 until ray.count - 1) {
                g.line(ray.xs[i], ray.ys[i], ray.xs[i + 1], ray.ys[i + 1], 0.0026f, c, 0.85f)
            }
        }
        if (payoffFrame) g.flash(0.25f)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "rays" to rays.size.toString(),
        "angle" to String.format(java.util.Locale.US, "%.0f°", angle * 180f / PI.toFloat())
    )

    companion object {
        /** How close the rays have to come together to count as a focus. */
        const val FOCUS_SPREAD = 0.12f

        const val MAX_SEGMENTS = 320

        fun def(): SimDef = SimDef(
            id = "physics/optics",
            title = "Light through glass",
            shelf = Shelf.PHYSICS,
            family = "Optics & light",
            blurb = "Snell's law, a prism, and where a rainbow comes from.",
            formats = listOf(TrapFormat.REVEAL, TrapFormat.LOOP),
            heroLabel = "New angle",
            defaultPalette = "Spectrum",
            typicalSeconds = 24f,
            params = listOf(
                Param.choice("shape", "Glass", listOf("Prism", "Lens", "Slab"), 0, card = Card.DATA, input = ParamInput.TILES),
                Param.int("rays", "Rays", 1, 60, 9, card = Card.DATA),
                Param.float("height", "Beam height", -0.6f, 0.8f, 0.35f, card = Card.DATA),
                Param.float(
                    "angle", "Angle", -1.2f, 1.2f, -0.35f, card = Card.MOTION, unit = "rad",
                    mutedBy = "sweep"
                ),
                Param.bool("sweep", "Sweep the angle", true, card = Card.MOTION)
            ),
            create = { OpticsSim() }
        )
    }
}

/**
 * Electric field lines: charges laid out on the screen, with test particles
 * tracing the field so its shape is visible rather than described.
 */
class ChargesSim : Sim {

    private var charges = FloatArray(0)
    private var cx = FloatArray(0)
    private var cy = FloatArray(0)
    private var count = 0
    private var px = FloatArray(0)
    private var py = FloatArray(0)
    private var alive = BooleanArray(0)
    private var particles = 0
    private var elapsed = 0f
    private var payoffFrame = false
    private var resolved = false

    override fun setup(env: SimEnv) {
        val p = env.params
        resolved = false
        elapsed = 0f
        count = p.i("charges", 4).coerceIn(1, 12)
        charges = FloatArray(count)
        cx = FloatArray(count)
        cy = FloatArray(count)
        for (i in 0 until count) {
            cx[i] = env.rng.range(-env.halfWidth * 0.7f, env.halfWidth * 0.7f)
            cy[i] = env.rng.range(-0.7f, 0.7f)
            charges[i] = if (i % 2 == 0) 1f else -1f
            charges[i] *= env.rng.range(0.6f, 1.4f)
        }
        particles = p.i("tracers", 2400).coerceIn(50, 80000)
        px = FloatArray(particles)
        py = FloatArray(particles)
        alive = BooleanArray(particles)
        respawnAll(env)
        elapsed = 0f
        payoffFrame = false
    }

    private fun respawnAll(env: SimEnv) {
        for (i in 0 until particles) {
            px[i] = env.rng.range(-env.halfWidth, env.halfWidth)
            py[i] = env.rng.range(-1f, 1f)
            alive[i] = true
        }
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        val sign = env.laws.chargeSign
        // The law scales what the slider asks for, so "Strong charges" is felt
        // here rather than being a name with nothing behind it.
        val strength = env.params.f("strength", 1f) * 0.0025f *
            env.laws.electricStrength.coerceIn(0.05f, 20f)
        var captured = 0
        Parallel.forRange(particles) { from, to ->
            for (i in from until to) {
                if (!alive[i]) continue
                var ex = 0f
                var ey = 0f
                for (k in 0 until count) {
                    val dx = px[i] - cx[k]
                    val dy = py[i] - cy[k]
                    val r2 = dx * dx + dy * dy + 0.0008f
                    val f = charges[k] * strength / r2
                    ex += dx * f / sqrt(r2)
                    ey += dy * f / sqrt(r2)
                }
                // A test particle follows the field line it sits on, and how
                // fast it goes follows the strength of the field there. The old
                // version divided the field by its own length and multiplied by
                // a constant, which cancelled the strength out exactly: the
                // slider, the law and the "Strong charges" preset all moved
                // every tracer at the same speed. The cap is what stops a
                // particle sitting on top of a charge from crossing the screen
                // in a single step.
                val len = hypot(ex, ey)
                if (len > 1e-7f) {
                    val speed = (len * FIELD_SPEED).coerceAtMost(MAX_TRACER_SPEED)
                    px[i] += ex / len * dt * speed * sign
                    py[i] += ey / len * dt * speed * sign
                }
                if (abs(px[i]) > env.halfWidth * 1.05f || abs(py[i]) > 1.05f) alive[i] = false
                for (k in 0 until count) {
                    if (hypot(px[i] - cx[k], py[i] - cy[k]) < 0.035f) alive[i] = false
                }
            }
        }
        for (i in 0 until particles) if (!alive[i]) captured++
        if (captured > particles * 0.8f) {
            respawnAll(env)
            env.emit(EventKind.BIRTH, 0.5f, 0f, 0.6f)
        }
        env.emit(EventKind.STEP, 1f - (captured.toFloat() / particles), 0f, 0.25f)
        // The moment the picture resolves: most of the tracers have found a
        // charge and the field lines are drawn. This used to be a stopwatch at
        // ten seconds, which fires whether or not anything has happened.
        if (!resolved && captured > particles * 0.7f && elapsed > 1.5f) {
            resolved = true
            payoffFrame = true
            env.emit(EventKind.DONE, 0.75f, 0f, 1f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        g.reservePoints(particles)
        for (i in 0 until particles) {
            if (!alive[i]) continue
            g.point(px[i], py[i], 0.004f, palette.at(((px[i] + py[i]) * 0.5f + 0.5f).coerceIn(0f, 1f)))
        }
        for (k in 0 until count) {
            val positive = charges[k] > 0f
            val c = if (positive) Palette.DANGER else Palette.ACCENT
            g.glow(cx[k], cy[k], 0.14f * abs(charges[k]), c, 0.55f)
            g.disc(cx[k], cy[k], 0.03f, c)
            g.rect(cx[k], cy[k], 0.016f, 0.004f, Palette.TEXT_PRIMARY)
            if (positive) g.rect(cx[k], cy[k], 0.004f, 0.016f, Palette.TEXT_PRIMARY)
        }
        if (payoffFrame) g.flash(0.25f)
    }

    override fun onInput(env: SimEnv, e: TouchEvent) {
        if (e.kind == TouchEvent.Kind.TAP && count > 0) {
            cx[0] = e.x
            cy[0] = e.y
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "charges" to count.toString(),
        "tracers" to String.format(java.util.Locale.US, "%,d", particles)
    )

    companion object {
        fun def(): SimDef = SimDef(
            id = "physics/charges",
            title = "Electric field",
            shelf = Shelf.PHYSICS,
            family = "Electricity & magnetism",
            blurb = "Tracers following the field, so its shape is visible.",
            formats = listOf(TrapFormat.REVEAL, TrapFormat.LOOP, TrapFormat.COLOR_BATTLE),
            lawKnobs = setOf(LawKnob.CHARGE),
            heroLabel = "New charges",
            defaultPalette = "Aurora",
            typicalSeconds = 24f,
            params = listOf(
                Param.int("charges", "Charges", 1, 12, 4, card = Card.DATA),
                Param.int("tracers", "Tracers", 50, 80000, 2400, card = Card.DATA, logScale = true),
                Param.float("strength", "Field strength", 0.1f, 4f, 1f, card = Card.MOTION)
            ),
            create = { ChargesSim() }
        )

        /**
         * Turns field magnitude into tracer speed. Chosen so the shipped
         * strength of one moves a tracer at about the rate it always did, and
         * the slider's hundredfold range is a hundredfold range of speed.
         */
        const val FIELD_SPEED = 12f

        /** World units a second, so nothing steps over a charge. */
        const val MAX_TRACER_SPEED = 2.5f
    }
}

object PhysicsExtras {
    fun defs(): List<SimDef> = listOf(
        NBodySim.def(),
        GearsSim.def(),
        ClothSim.def(),
        OpticsSim.def(),
        ChargesSim.def()
    )
}
