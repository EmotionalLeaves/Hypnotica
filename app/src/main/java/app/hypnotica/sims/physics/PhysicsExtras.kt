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
    private var accX = FloatArray(0)
    private var accY = FloatArray(0)
    private var tint = FloatArray(0)
    /** Drawn size, fixed from the mass before the disc's masses are scaled. */
    private var dotSize = FloatArray(0)
    private var trailX = FloatArray(0)
    private var trailY = FloatArray(0)
    private var trailTint = FloatArray(0)
    private var trailHead = 0
    private var trailFill = 0
    private var elapsed = 0f
    private var payoffFrame = false
    private var centralMass = 0f
    private var collapsed = false
    /** The infall level last sounded, in [LEVELS] steps. */
    private var heardLevel = -1

    /** How far round the centre each body has gone, for the arms payoff. */
    private var lastAngle = FloatArray(0)
    private var swept = FloatArray(0)

    /**
     * How far out the bulk of the system reaches, so the view can pull back to
     * keep it: the 85th percentile of the bodies' distance from the centre,
     * sampled a few times a second. Bodies flung clear of the system are let
     * go; the cluster is kept whole.
     */
    private var reach = 0f
    private var sinceReach = 0f
    private val reachSample = FloatArray(REACH_SAMPLES)

    override fun setup(env: SimEnv) {
        val p = env.params
        n = p.i("bodies", 400).coerceIn(2, 4000)
        centralMass = p.f("central", 60f)
        px = FloatArray(n); py = FloatArray(n)
        vx = FloatArray(n); vy = FloatArray(n)
        mass = FloatArray(n); tint = FloatArray(n); dotSize = FloatArray(n)
        accX = FloatArray(n); accY = FloatArray(n)
        lastAngle = FloatArray(n); swept = FloatArray(n)
        val trail = p.i("trail", 4000).coerceIn(0, 40000)
        trailX = FloatArray(max(1, trail))
        trailY = FloatArray(max(1, trail))
        trailTint = FloatArray(max(1, trail))
        trailHead = 0
        trailFill = 0

        val disc = p.choiceIndex("start") == 0
        // A round disc that fits the width. It was squashed to the screen's
        // shape — on a phone held upright, half as wide as it was tall — while
        // every body was still given the speed of a circular orbit at its
        // unsquashed radius. None of them were on the orbit they were moving
        // for, and a fifth to a quarter were thrown clean off the screen.
        val discScale = min(1f, env.halfWidth / 0.92f)
        for (i in 0 until n) {
            if (disc) {
                // A rotating disc, which is what makes spiral structure appear.
                // An annulus rather than a disc down to the centre: the
                // innermost bodies went round ten times for each outer orbit and
                // smeared any pattern into stripes before it could be seen.
                val r = (0.36f + sqrt(env.rng.nextFloat()) * 0.56f) * discScale
                val a = env.rng.range(0f, 2f * PI.toFloat())
                px[i] = cos(a) * r
                py[i] = sin(a) * r
                // Coloured by where round the disc it starts, in two matching
                // halves: the disc turns faster inside than out, and that shear
                // winds the colour bands into trailing spiral arms you can see,
                // which the speckle of random colours never showed.
                val turn = a / (2f * PI.toFloat())
                tint[i] = abs(((turn * 2f) % 2f) - 1f)
                // Speeds are set below, once every body is placed.
                vx[i] = -sin(a)
                vy[i] = cos(a)
            } else {
                px[i] = env.rng.range(-env.halfWidth * 0.9f, env.halfWidth * 0.9f)
                py[i] = env.rng.range(-0.9f, 0.9f)
                vx[i] = env.rng.range(-0.05f, 0.05f)
                vy[i] = env.rng.range(-0.05f, 0.05f)
            }
            mass[i] = env.rng.range(0.6f, 1.4f)
            if (!disc) tint[i] = env.rng.nextFloat()
            dotSize[i] = 0.007f + mass[i] * 0.003f
        }
        // A disc six times heavier than its centre is a cold self-gravitating
        // sheet: within two seconds it broke into clumps, collapsed and flung
        // a quarter of itself away, so the view pulled back to specks. Galaxy
        // discs are held by their centre; at a third of its mass the disc
        // still pulls on itself, but differential rotation shears its clumps
        // into trailing arms, which is what the sim promises.
        if (disc && centralMass > 0f) {
            var sum = 0f
            for (i in 0 until n) sum += mass[i]
            val k = min(1f, DISC_SHARE * centralMass / max(1e-3f, sum))
            for (i in 0 until n) mass[i] *= k
        }
        for (i in 0 until n) {
            lastAngle[i] = atan2(py[i], px[i])
            swept[i] = 0f
        }
        if (disc) {
            // Each body at the speed of a circle under the pull it actually
            // feels: the centre's, softened as the force is, plus the disc's
            // own mass inside its orbit — six times the centre's at the
            // defaults. Given only √(GM/r) of the centre, the disc fell in.
            val order = (0 until n).sortedBy { hypot(px[it], py[it]) }
            var inside = 0f
            for (i in order) {
                val r = hypot(px[i], py[i])
                val soft = sqrt(r * r + SOFTENING)
                var pull = 0f
                if (centralMass > 0f) pull += env.laws.pairForce(soft, centralMass, 1f) * G_SCALE * (r / soft)
                if (n <= PAIRWISE_LIMIT) pull += env.laws.pairForce(soft, inside, 1f) * G_SCALE * (r / soft)
                pull *= env.laws.massSign
                val v = sqrt(max(0f, pull) * r)
                vx[i] *= v; vy[i] *= v
                inside += mass[i]
            }
        }
        elapsed = 0f
        payoffFrame = false
        collapsed = false
        heardLevel = -1
        reach = 0f
        sinceReach = REACH_EVERY
    }

    private fun measureReach() {
        val count = min(n, REACH_SAMPLES)
        val stride = max(1, n / count)
        for (k in 0 until count) {
            val i = (k * stride).coerceAtMost(n - 1)
            reachSample[k] = max(abs(px[i]), abs(py[i]))
        }
        java.util.Arrays.sort(reachSample, 0, count)
        reach = reachSample[((count - 1) * REACH_SHARE).toInt()]
    }

    /** The cluster's reach, with a margin; it only ever asks for more room. */
    override fun frameHalfWidth(env: SimEnv): Float = reach * REACH_MARGIN
    override fun frameHalfHeight(env: SimEnv): Float = reach * REACH_MARGIN


    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        sinceReach += abs(dt)
        if (sinceReach >= REACH_EVERY) {
            sinceReach = 0f
            measureReach()
        }
        val laws = env.laws
        val softening = SOFTENING
        val sign = laws.massSign
        val fastK = laws.inverseSquareConstant() * G_SCALE * sign

        // Every pull worked out from where the bodies are now, then every
        // body moved: updated in place, a body felt some of the others where
        // they already were next step, and momentum leaked.
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
                    // Acceleration is force over the body's own mass: it was
                    // the force itself, so heavy bodies fell faster.
                    val f = laws.pairForce(r, centralMass, mass[i]) * G_SCALE * sign / mass[i]
                    ax += f * dx / r
                    ay += f * dy / r
                }
                // All pairs, but only when the count is small enough to afford it.
                if (n <= PAIRWISE_LIMIT && !fastK.isNaN()) {
                    // The same sum for plain inverse-square gravity, multiplied
                    // out: the body's own mass cancels, and r never falls under
                    // the law's floor because the softening keeps it above.
                    val xi = px[i]
                    val yi = py[i]
                    for (j in 0 until n) {
                        if (j == i) continue
                        val dx = px[j] - xi
                        val dy = py[j] - yi
                        val r2 = dx * dx + dy * dy + softening
                        val k = fastK * mass[j] / (r2 * sqrt(r2))
                        ax += k * dx
                        ay += k * dy
                    }
                } else if (n <= PAIRWISE_LIMIT) {
                    for (j in 0 until n) {
                        if (j == i) continue
                        val dx = px[j] - px[i]
                        val dy = py[j] - py[i]
                        val r2 = dx * dx + dy * dy + softening
                        val r = sqrt(r2)
                        val f = laws.pairForce(r, mass[j], mass[i]) * G_SCALE * sign / mass[i]
                        ax += f * dx / r
                        ay += f * dy / r
                    }
                }
                accX[i] = ax
                accY[i] = ay
            }
        }
        for (i in 0 until n) {
            vx[i] += accX[i] * dt
            vy[i] += accY[i] * dt
            val sp = hypot(vx[i], vy[i])
            if (sp > MAX_SPEED) {
                vx[i] *= MAX_SPEED / sp
                vy[i] *= MAX_SPEED / sp
            }
            px[i] += vx[i] * dt
            py[i] += vy[i] * dt
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
        var wound = 0
        for (i in 0 until n) {
            if (hypot(px[i], py[i]) < 0.3f) inner++
            val a = atan2(py[i], px[i])
            var d = a - lastAngle[i]
            if (d > PI.toFloat()) d -= 2f * PI.toFloat()
            if (d < -PI.toFloat()) d += 2f * PI.toFloat()
            swept[i] += abs(d)
            lastAngle[i] = a
            if (swept[i] > WOUND_ANGLE) wound++
        }
        // A note each time the core's share moves a notch, up or down: one
        // every step was 120 identical ticks a second whatever the picture did.
        val level = (inner.toFloat() / n * LEVELS).toInt()
        if (level != heardLevel) {
            if (heardLevel >= 0) env.emit(EventKind.STEP, (inner.toFloat() / n).coerceIn(0f, 1f), 0f, 0.3f)
            heardLevel = level
        }
        // Two endings: a cloud that falls together, or a disc whose bodies
        // have mostly gone one and a half times round, by which time the
        // shear has wound its clumps into arms. Measured, not timed, so a
        // still universe never claims either.
        if (!collapsed && ((elapsed > 8f && inner > n * 0.45f) || wound > n * 0.6f)) {
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
                Palette.withAlpha(palette.at(trailTint[idx]), (1f - age) * 0.22f)
            )
        }
        for (i in 0 until n) {
            g.point(px[i], py[i], dotSize[i], palette.at(tint[i]))
        }
        if (centralMass > 0f) {
            g.glow(0f, 0f, 0.16f, Palette.WARNING, 0.4f)
            g.disc(0f, 0f, 0.035f, Palette.brighten(Palette.WARNING, 0.3f))
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "bodies" to String.format(java.util.Locale.US, "%,d", n),
        // Past the pairwise limit the bodies feel only the centre: said, not hidden.
        "gravity" to if (n <= PAIRWISE_LIMIT) "every pair" else "centre only"
    )

    companion object {
        /** Notches of the core's share of bodies, each a note. */
        const val LEVELS = 40
        const val SOFTENING = 0.01f
        /** The disc's mass as a share of the centre's. */
        private const val DISC_SHARE = 0.35f
        private const val WOUND_ANGLE = (3.0 * PI).toFloat()
        /** The gravitational constant in this sim's units. */
        const val G_SCALE = 0.0016f
        private const val REACH_SAMPLES = 512
        private const val REACH_SHARE = 0.85f
        private const val REACH_MARGIN = 1.12f
        private const val REACH_EVERY = 0.25f
        private const val MAX_SPEED = 3.2f
        /** Above this, all-pairs is dropped and only the central mass pulls. */
        const val PAIRWISE_LIMIT = 900

        fun def(): SimDef = SimDef(
            id = "physics/n-body",
            title = "N-body gravity",
            shelf = Shelf.PHYSICS,
            family = "Orbits, gravity & space",
            blurb = "Every mass pulling on every other one, until arms form.",
            formats = listOf(TrapFormat.GROW, TrapFormat.SNAP),
            lawKnobs = setOf(LawKnob.GRAVITY, LawKnob.PAIRWISE_FORCE),
            heroLabel = "New galaxy",
            defaultPalette = "Ember",
            typicalSeconds = 12f,
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
        /** Tooth height either side of the pitch circle, and the pitch along it. */
        var addendum = 0f
        var pitch = 0f
        var angle = 0f
        /** Its turn at the start, set so its teeth sit in its neighbour's gaps. */
        var base = 0f
    }

    private val gears = ArrayList<Gear>()
    private var speed = 0.9f
    private var elapsed = 0f
    private var payoffFrame = false
    private var clicks = 0L
    private var lastTooth = 0
    /** True once the train has turned a full revolution: the payoff. */
    private var cycled = false
    private var cycledAt = 0f

    override fun setup(env: SimEnv) {
        val p = env.params
        gears.clear()
        cycled = false
        val count = p.i("gears", 5).coerceIn(2, 9)
        speed = p.f("speed", 0.9f)
        val toothSize = 0.055f
        // The train zigzags down the screen, then the whole of it is scaled to
        // fit. It used to run in a straight line to the right, three and a
        // third units long on a screen not one unit wide: on a phone held
        // upright, three quarters of the gears were off the edge.
        val xs = FloatArray(count)
        val ys = FloatArray(count)
        val radii = FloatArray(count)
        val teethOf = IntArray(count)
        // On a wide screen the train zigzags across instead: run downwards it
        // was a thin column in the middle of a landscape frame.
        val wide = env.halfWidth > 1f
        val along = if (wide) 0f else -PI.toFloat() / 2f
        for (i in 0 until count) {
            teethOf[i] = p.i("teeth", 14).coerceIn(6, 40) + i * p.i("step", 4).coerceIn(0, 12)
            radii[i] = teethOf[i] * toothSize / (2f * PI.toFloat()) * 2f
            if (i > 0) {
                val heading = along + (if (i % 2 == 1) ZIGZAG else -ZIGZAG)
                val d = radii[i - 1] + radii[i]
                xs[i] = xs[i - 1] + cos(heading) * d
                ys[i] = ys[i - 1] + sin(heading) * d
            }
        }
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE
        var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        // Every gear shares one tooth pitch, so every tooth is the same size.
        // They were drawn at a fixed share of each gear's radius instead —
        // big wheels with fat teeth, small ones with thin — and stuck out a
        // sixth of a radius past the pitch circle, deep into the next gear's
        // rim, so neighbours overlapped instead of meshing.
        val pitch0 = 2f * toothSize
        val addendum0 = pitch0 / PI.toFloat() * 0.85f
        for (i in 0 until count) {
            val reach = radii[i] + addendum0
            x0 = min(x0, xs[i] - reach); x1 = max(x1, xs[i] + reach)
            y0 = min(y0, ys[i] - reach); y1 = max(y1, ys[i] + reach)
        }
        // A short train on a wide screen may grow to fill it; on a phone it
        // only ever shrinks, as before.
        val fit = min(if (wide) 1.6f else 1f, min(2f * env.halfWidth * FILL / (x1 - x0), 2f * FILL / (y1 - y0)))
        val cx = (x0 + x1) * 0.5f
        val cy = (y0 + y1) * 0.5f
        var dir = 1f
        for (i in 0 until count) {
            val gear = Gear((xs[i] - cx) * fit, (ys[i] - cy) * fit, radii[i] * fit, teethOf[i], dir, i.toFloat() / count)
            gear.addendum = addendum0 * fit
            gear.pitch = pitch0 * fit
            gears.add(gear)
            dir = -dir
        }
        // Each gear turned at the start so its gaps meet its neighbour's
        // teeth where they touch. All started at zero, three of four pairs
        // ran tooth on tooth the whole way round.
        val twoPi = 2f * PI.toFloat()
        for (k in 1 until gears.size) {
            val prev = gears[k - 1]; val gear = gears[k]
            val contact = kotlin.math.atan2(gear.y - prev.y, gear.x - prev.x)
            val pitchPrev = twoPi / prev.teeth
            val pitch = twoPi / gear.teeth
            val phase = (((contact - prev.base) / pitchPrev) % 1f + 1f) % 1f
            gear.base = contact + PI.toFloat() + (phase + 0.5f) * pitch
            gear.angle = gear.base
        }
        elapsed = 0f
        payoffFrame = false
        clicks = 0
        lastTooth = Int.MIN_VALUE
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
            // Meshed wheels turn in proportion to their tooth counts, the
            // other way, from where each started.
            gear.angle = gear.base - (prev.angle - prev.base) * prev.teeth / gear.teeth
        }
        // A click each time one of the first wheel's teeth is square on the
        // line to its neighbour, which is where the mesh is drawn: counted
        // from angle zero instead, the tick fell anywhere in the tooth's pass
        // and was heard at the middle of the screen whatever the layout.
        val second = gears.getOrNull(1)
        val contact = if (second != null) kotlin.math.atan2(second.y - first.y, second.x - first.x) else 0f
        val tooth = kotlin.math.floor((first.angle - contact) / (2f * PI.toFloat()) * first.teeth).toInt()
        if (lastTooth == Int.MIN_VALUE) lastTooth = tooth
        if (tooth != lastTooth) {
            lastTooth = tooth
            clicks++
            val mx = first.x + cos(contact) * first.radius
            env.emit(EventKind.HIT, (gears.size - 1).toFloat() / max(1, gears.size), (mx / env.halfWidth).coerceIn(-1f, 1f), 0.4f)
        }
        // One full revolution of the slowest gear in the train: the last
        // wheel's every tooth back where it started (the whole train repeats
        // only after the tooth counts' least common multiple).
        val slowest = gears.lastOrNull()
        if (!cycled && slowest != null && abs(slowest.angle - slowest.base) >= 2f * PI.toFloat()) {
            cycled = true
            cycledAt = elapsed
            payoffFrame = true
            env.emit(EventKind.DONE, 0.7f, 0f, 1f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        for (gear in gears) {
            val c = palette.at(gear.tint)
            val ad = gear.addendum
            val root = gear.radius - ad
            val rim = max(gear.radius * 0.12f, ad * 1.1f)
            // Rim up to the tooth roots, with the teeth standing on it, from
            // root to tip, centred on the pitch circle where neighbours meet.
            g.ring(gear.x, gear.y, root + ad * 0.15f, rim, c, alpha = 0.95f)
            for (t in 0 until gear.teeth) {
                val a = gear.angle + t * 2f * PI.toFloat() / gear.teeth
                g.rect(
                    gear.x + cos(a) * gear.radius, gear.y + sin(a) * gear.radius,
                    ad, gear.pitch * 0.22f, c, a
                )
            }
            // Spokes and a hub, so the turning reads as a wheel, not a clock hand.
            val hub = gear.radius * 0.17f
            val inner = root + ad * 0.15f - rim * 0.5f
            for (k in 0 until SPOKES) {
                val a = gear.angle + (k + 0.5f) * 2f * PI.toFloat() / SPOKES
                g.line(
                    gear.x + cos(a) * hub * 0.8f, gear.y + sin(a) * hub * 0.8f,
                    gear.x + cos(a) * inner, gear.y + sin(a) * inner,
                    max(0.006f, gear.radius * 0.07f), c, 0.75f
                )
            }
            g.disc(gear.x, gear.y, hub, Palette.brighten(c, 0.2f))
            g.disc(gear.x, gear.y, hub * 0.4f, AXLE)
        }
        // The payoff is the last wheel's full turn, and nothing on screen
        // showed it: a mark on the driver and on the last wheel, one spoke
        // each, and round the last wheel's hub an arc filling as it turns.
        // The ratio is then something you watch: the driver's mark spins
        // round while the last one creeps, and the ring closes on the beat.
        val first = gears.firstOrNull() ?: return
        val last = gears.last()
        if (gears.size < 2) return
        for (gear in listOf(first, last)) {
            val rim = gear.radius - gear.addendum - max(gear.radius * 0.12f, gear.addendum * 1.1f) * 0.5f
            val a = gear.angle + 0.5f * 2f * PI.toFloat() / SPOKES
            g.disc(gear.x + cos(a) * rim, gear.y + sin(a) * rim, max(0.01f, gear.radius * 0.07f), MARK)
        }
        val turned = abs(last.angle - last.base)
        val share = (turned / (2f * PI.toFloat())).coerceIn(0f, 1f)
        val ringR = last.radius * 0.3f
        val ringW = max(0.006f, last.radius * 0.045f)
        g.ring(last.x, last.y, ringR, ringW, MARK, alpha = 0.18f)
        if (share >= 1f) {
            val since = elapsed - cycledAt
            val f = (1f - since / 1.2f).coerceIn(0f, 1f)
            if (f > 0f) g.glow(last.x, last.y, last.radius * (0.7f + 0.5f * (1f - f)), MARK, 0.5f * f)
            g.ring(last.x, last.y, ringR, ringW, MARK, alpha = 0.9f)
        } else if (share > 0.005f) {
            // From the top, the way the wheel turns: the arc runs with it.
            val top = (PI / 2).toFloat()
            val length = share * 2f * PI.toFloat()
            val ccw = (last.angle - last.base) > 0f
            g.ring(last.x, last.y, ringR, ringW, MARK, 2f * PI.toFloat() - length, if (ccw) top + length else top, 0.9f)
        }
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
        /** How far either side of straight down each link of the train turns. */
        private const val ZIGZAG = 0.62f
        /** A gear's teeth reach this far out, as a share of its radius. */
        private const val SPOKES = 5
        private val AXLE = 0xFF07090D.toInt()
        /** The one warm accent on the monochrome train: the marks and the turn arc. */
        private val MARK = Palette.WARNING
        private const val FILL = 0.9f

        fun def(): SimDef = SimDef(
            id = "physics/gears",
            title = "Gear train",
            shelf = Shelf.PHYSICS,
            family = "Mechanics & machines",
            blurb = "Tooth counts decide the ratio, all the way down the line.",
            formats = listOf(TrapFormat.CHAIN, TrapFormat.SONG),
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
 * distance constraints. Verlet integration keeps it stable at the steps a screen takes.
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
    /** Thread length across (the weft); [rest] is down (the warp). Equal on a phone. */
    private var restX = 0f
    private var wide = false
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
        rows = p.i("rows", 44).coerceIn(2, 200)
        val n = cols * rows
        x = FloatArray(n); y = FloatArray(n)
        ox = FloatArray(n); oy = FloatArray(n)
        pinned = BooleanArray(n)
        cut = BooleanArray(n)
        // A curtain, taller than wide: an upright phone has twice as much
        // height as width, and a square sheet hung in it as a small net in
        // the upper half with the rest of the screen empty.
        // Up to 2.7 tall: at 2.1 the sheet filled under half an upright
        // phone, with empty bands above and below it.
        rest = min(1.5f / cols, 2.7f / rows)
        restX = rest
        // On a wide screen a banner instead: a curtain laid out for a phone
        // hung as a narrow strip in the middle of a landscape frame, the
        // view pulled back to fit its hem. The weft threads are let out so
        // the sheet spans most of the width, the warp shortened so its hem
        // stays on screen.
        wide = env.halfWidth > 1.2f
        if (wide) {
            rest = min(rest, 1.3f / rows)
            restX = max(rest, min(2f * env.halfWidth * 0.78f / cols, rest * 3f))
        } else {
            // On a phone the sheet's width is set by the view (which must
            // take in its sway), and a curtain as tall as square cells allow
            // filled barely half the height: a fifth of the screen empty
            // above it and below. The warp is let out instead, up to half as
            // long again as the weft, so the curtain hangs most of the way
            // down the screen.
            rest = min(PHONE_DROP / rows, restX * 1.45f)
        }
        val startX = -restX * (cols - 1) * 0.5f
        // Hung so the sheet, once it has stretched under its own weight,
        // sits about the middle of the view.
        val startY = (rest * (rows - 1) * 0.5f + 0.12f).coerceAtLeast(0.3f)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val i = r * cols + c
                x[i] = startX + c * restX
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
        owed = 0f
        px0 = x.copyOf(); py0 = y.copyOf()
        tears = 0
        lastTears = 0
        swayClock = 0f
        payoffFrame = false
        elapsed = 0f
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        // Always in whole steps of one size, drawn between them. Verlet with
        // a fixed number of relaxation passes holds the sheet stretched by an
        // amount that depends on the step: when the payoff's slow motion
        // switched to finer steps, the stretch sprang back all at once and
        // the curtain crumpled into a jagged mess at the very moment it paid
        // off. A time-corrected velocity made it worse; the step must not change.
        owed += abs(dt)
        var taken = 0
        while (owed >= FIXED_STEP * 0.999f && taken < MAX_CATCH_UP) {
            owed -= FIXED_STEP
            System.arraycopy(x, 0, px0, 0, x.size)
            System.arraycopy(y, 0, py0, 0, y.size)
            advance(env, FIXED_STEP)
            taken++
        }
        if (taken == MAX_CATCH_UP) owed = 0f
        owed = owed.coerceAtLeast(0f)
    }

    private fun advance(env: SimEnv, dt: Float) {
        elapsed += abs(dt)
        val laws = env.laws
        // Gravity along whichever way it points: only its downward part was
        // read, so gravity turned sideways did nothing at all.
        val g = laws.gravity * 0.04f * laws.gravityDirY
        val gSide = laws.gravity * 0.04f * laws.gravityDirX
        // Air drag per second, not per step, so a faster screen doesn't
        // make the air thicker.
        val drag = Math.pow((1f - (laws.friction * 0.2f).coerceIn(0f, 0.3f)).toDouble(), (abs(dt) * 60f).toDouble()).toFloat()
        // A breeze, by default: the hem swings out by the sheet's length
        // times the wind, and at 0.08 the taller curtain swung off the edge.
        // 0.015 since the phone curtain grew longer: its hem swings out
        // further for the same breeze (the gusts come near its own swing), and
        // at 0.055 it left the screen.
        val wind = env.params.f("wind", 0.015f) *
            sin(elapsed * 1.7f) * cos(elapsed * 0.6f)

        val n = cols * rows
        for (i in 0 until n) {
            if (pinned[i] || cut[i]) continue
            val vx = (x[i] - ox[i]) * drag
            val vy = (y[i] - oy[i]) * drag
            ox[i] = x[i]
            oy[i] = y[i]
            x[i] += vx + (wind + gSide) * dt * dt * 40f
            y[i] += vy - g * dt * dt * 40f
        }

        // Several relaxation passes keep the cloth from looking like jelly.
        repeat(env.params.i("stiffness", 6).coerceIn(1, 12)) {
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val i = r * cols + c
                    if (cut[i]) continue
                    if (c < cols - 1) relax(i, i + 1, restX)
                    if (r < rows - 1) relax(i, i + cols, rest)
                }
            }
        }
        // Each thread that snaps is a note, and the sway itself is a slow pulse,
        // so the cloth is never silent while it is moving.
        if (tears > lastTears) {
            val fresh = (tears - lastTears).coerceAtMost(12)
            lastTears = tears
            // Heard from where the last thread went.
            env.emit(EventKind.DEATH, 0.25f + 0.5f * (tears % 7) / 7f, (tearX / env.halfWidth).coerceIn(-1f, 1f), min(1f, 0.4f + fresh * 0.08f))
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
                // A speed, per second: "* 60" assumed a step of 1/60 s, and
                // since the cloth went to fixed 1/120 s steps the pulse came
                // out at half its level.
                val amount = (motion / sampled / abs(dt)).coerceIn(0f, 1f)
                // Heard where the hem has swung to, which is what is seen moving.
                var hem = 0f; var hemN = 0
                for (c in 0 until cols) { val k = (rows - 1) * cols + c; if (!cut[k]) { hem += x[k]; hemN++ } }
                val pan = if (hemN == 0) 0f else (hem / hemN / max(0.1f, env.halfWidth) * 2f).coerceIn(-1f, 1f)
                if (amount > 0.04f) env.emit(EventKind.STEP, amount, pan, 0.2f + amount * 0.5f)
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
            // Calm against the sheet's own recent sway (a slow running
            // average), not against its peak: the curtain, being tall, sways
            // in the wind more than it ever moved in the drop, so measured
            // against the peak it either counted as calm at once or never.
            // A calm moment is the hem pausing at the end of a swing, or the
            // sheet simply hanging still.
            val k = (abs(dt) / CALM_WINDOW).coerceIn(0f, 1f)
            peakMotion += (perVertex - peakMotion) * k
            val calm = perVertex < peakMotion * CALM_SHARE || perVertex < STILL_MOTION
            if (calm && elapsed > EARLIEST_SETTLE) stillFor += abs(dt) else stillFor = 0f
            if (elapsed > LATEST_SETTLE) stillFor = STILL_SECONDS + 1f
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

    private var tearX = 0f
    /** Time owed to [advance], which only ever takes whole FIXED_STEPs. */
    private var owed = 0f
    /** Where every vertex was one fixed step ago, to draw between steps. */
    private var px0 = FloatArray(0)
    private var py0 = FloatArray(0)
    private var drawX = FloatArray(0)
    private var drawY = FloatArray(0)

    private fun relax(a: Int, b: Int, rest: Float) {
        if (cut[a] || cut[b]) return
        val dx = x[b] - x[a]
        val dy = y[b] - y[a]
        val d = hypot(dx, dy)
        if (d < 1e-6f) return
        if (tearing && d > rest * 4.5f) {
            cut[b] = true
            tears++
            tearX = (x[a] + x[b]) * 0.5f
            return
        }
        val diff = (d - rest) / d * 0.5f
        val mx = dx * diff
        val my = dy * diff
        if (!pinned[a]) { x[a] += mx; y[a] += my }
        if (!pinned[b]) { x[b] -= mx; y[b] -= my }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        // Between the last two fixed steps, by how far time has got.
        val n = cols * rows
        if (drawX.size != n) { drawX = FloatArray(n); drawY = FloatArray(n) }
        val f = (owed / FIXED_STEP).coerceIn(0f, 1f)
        if (px0.size == n) for (i in 0 until n) {
            drawX[i] = px0[i] + (x[i] - px0[i]) * f
            drawY[i] = py0[i] + (y[i] - py0[i]) * f
        }
        // Passed in rather than swapped into x and y, which the step owns.
        if (px0.size == n) drawSheet(env, g, drawX, drawY) else drawSheet(env, g, x, y)
    }

    private fun drawSheet(env: SimEnv, g: G, xs: FloatArray, ys: FloatArray) {
        val palette = env.palette
        val n = cols * rows
        g.reserveInstances(n * 3 + cols)
        // Shading: where the weave is bunched across, the sheet is folded
        // away from us, so it is drawn darker; where it lies flat it is lit.
        // A flat-coloured grid read as a net, not as cloth with folds in it.
        if (shade.size < n) shade = FloatArray(n)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val i = r * cols + c
                val a = if (c > 0) i - 1 else i
                val b = if (c < cols - 1) i + 1 else i
                val dx = xs[b] - xs[a]
                val len = hypot(dx, ys[b] - ys[a])
                // How square-on the weft runs: tipped threads are a sheet
                // turning away from the light.
                val face = if (len < 1e-6f) 1f else abs(dx) / len
                val f2 = face * face
                shade[i] = (0.3f + 0.7f * f2 * f2 * f2).coerceIn(0.3f, 1f)
            }
        }
        // The rail the sheet hangs from.
        var left = Float.MAX_VALUE; var right = -Float.MAX_VALUE; var railY = 0f
        for (c in 0 until cols) if (pinned[c]) { left = min(left, xs[c]); right = max(right, xs[c]); railY = ys[c] }
        if (left < right) g.line(left - 0.04f, railY + 0.012f, right + 0.04f, railY + 0.012f, 0.008f, Palette.TEXT_SECONDARY, 0.45f)
        // The fabric itself: each cell of the weave filled as a quad turned to
        // follow its threads, a touch oversized so neighbours overlap with no
        // seams. Threads alone read as a net, not as cloth.
        for (r in 0 until rows - 1) {
            val t = (r + 0.5f) / max(1, rows - 1)
            val col = palette.at(t)
            for (c in 0 until cols - 1) {
                val i00 = r * cols + c
                val i01 = i00 + 1
                val i10 = i00 + cols
                val i11 = i10 + 1
                if (cut[i00] || cut[i01] || cut[i10] || cut[i11]) continue
                val ux = (xs[i01] - xs[i00] + xs[i11] - xs[i10]) * 0.5f
                val uy = (ys[i01] - ys[i00] + ys[i11] - ys[i10]) * 0.5f
                val vx = (xs[i10] - xs[i00] + xs[i11] - xs[i01]) * 0.5f
                val vy = (ys[i10] - ys[i00] + ys[i11] - ys[i01]) * 0.5f
                val vl = hypot(vx, vy)
                if (vl < 1e-6f) continue
                // Turned along the warp (the hanging threads), so the sheet's
                // sides run straight; turned along the weft, a sheared cell
                // stuck its corners out and the sides read as a saw blade.
                val across = abs(ux * vy - uy * vx) / vl
                val sh = (shade[i00] + shade[i01] + shade[i10] + shade[i11]) * 0.25f
                val cx = (xs[i00] + xs[i01] + xs[i10] + xs[i11]) * 0.25f
                val cy = (ys[i00] + ys[i01] + ys[i10] + ys[i11]) * 0.25f
                g.rect(cx, cy, vl * 0.5f + rest * 0.06f, across * 0.5f + restX * 0.035f,
                    Palette.lerp(0xFF000000.toInt(), col, 0.14f + 0.6f * sh * sh), atan2(vy, vx))
            }
        }
        // The weave over it, faint, lit where the sheet faces us.
        for (r in 0 until rows) {
            val t = r.toFloat() / max(1, rows - 1)
            val col = palette.at(t)
            for (c in 0 until cols) {
                val i = r * cols + c
                if (cut[i]) continue
                val lit = Palette.lerp(0xFF000000.toInt(), col, shade[i])
                if (c < cols - 1 && !cut[i + 1]) {
                    g.line(xs[i], ys[i], xs[i + 1], ys[i + 1], 0.003f, lit, 0.55f)
                }
                if (r < rows - 1 && !cut[i + cols]) {
                    g.line(xs[i], ys[i], xs[i + cols], ys[i + cols], 0.003f, lit, 0.4f)
                }
            }
        }
        for (c in 0 until cols) if (pinned[c] && !cut[c]) {
            g.disc(xs[c], ys[c] + 0.012f, 0.014f, Palette.TEXT_SECONDARY)
            g.disc(xs[c], ys[c] + 0.012f, 0.007f, AXLE_DARK)
        }
    }

    private var shade = FloatArray(0)

    /**
     * "Shake it": a gust through the cloth that is hanging there, not a new
     * cloth. The button used to restart the run, so the label promised one
     * thing and the button did another. Verlet keeps velocity as the gap to
     * the previous position, so a gust is that gap widened — more at the free
     * hem than near the pins, with a little lift, so it billows.
     */
    override fun hero(env: SimEnv) {
        val side = if (env.rng.nextFloat() < 0.5f) -1f else 1f
        val strength = GUST * env.rng.range(0.7f, 1.2f)
        for (r in 0 until rows) {
            val hang = (r + 1f) / rows
            for (c in 0 until cols) {
                val i = r * cols + c
                if (pinned[i] || cut[i]) continue
                ox[i] -= side * strength * hang
                oy[i] -= strength * 0.35f * hang * kotlin.math.sin(c * 0.5f)
            }
        }
        env.emit(EventKind.HIT, 0.7f, side * 0.6f, 0.8f)
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


    /**
     * The sheet is a fixed 1.5 across, and the wind carries it further: on a
     * phone held upright, nearly twice the screen, so the pinned corners hung
     * off the edges. The view pulls back instead of the sheet being made
     * smaller, because a smaller sheet is a different sheet — it falls and
     * settles on another clock.
     */
    override fun frameHalfWidth(env: SimEnv): Float = if (wide) restX * (cols - 1) * 0.5f + 0.12f else 1.1f

    /**
     * The curtain hangs about 2.8 tall; the view pulls back to show its hem.
     * The wide banner is laid out to hang within the height.
     */
    override fun frameHalfHeight(env: SimEnv): Float = if (wide) 1f else 1.5f

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> =
        listOf("points" to (cols * rows).toString())

    companion object {
        private val AXLE_DARK = 0xFF07090D.toInt()
        /** The one step the cloth is ever advanced by, and the most it catches up in a frame. */
        const val FIXED_STEP = 1f / 120f
        const val MAX_CATCH_UP = 12
        /** How tall the curtain hangs on an upright phone, before it stretches. */
        const val PHONE_DROP = 3.3f
        /** A gust's push, in world units a step, at the hem. */
        private const val GUST = 0.012f

        /** Share of its own peak motion below which the sheet has arrived. */
        const val CALM_SHARE = 0.5f

        /** Seconds the running average of the sway looks back over. */
        const val CALM_WINDOW = 4f

        /** Per-vertex motion a step below which the sheet is simply still. */
        const val STILL_MOTION = 2e-5f

        /** The drop and the first big swings come first. */
        const val EARLIEST_SETTLE = 20f

        /** Wind that never lets up still owes the run its moment. */
        const val LATEST_SETTLE = 40f

        /** How long it has to stay calm before the cloth has settled. */
        const val STILL_SECONDS = 0.3f

        fun def(): SimDef = SimDef(
            id = "physics/cloth",
            title = "Cloth",
            shelf = Shelf.PHYSICS,
            family = "Fluids & soft bodies",
            blurb = "A hanging sheet that swings, settles and can be dragged.",
            formats = listOf(TrapFormat.CUT),
            lawKnobs = setOf(LawKnob.GRAVITY, LawKnob.GRAVITY_DIRECTION, LawKnob.FRICTION),
            heroLabel = "Shake it",
            heroRestarts = false,
            defaultPalette = "Ocean",
            typicalSeconds = 48f,
            params = listOf(
                Param.int("cols", "Columns", 3, 200, 26, card = Card.DATA, logScale = true),
                Param.int("rows", "Rows", 2, 200, 44, card = Card.DATA, logScale = true),
                Param.int("pins", "Pins", 1, 12, 5, card = Card.DATA),
                Param.bool("tear", "Let it tear", false, card = Card.DATA),
                Param.float("wind", "Wind", 0f, 1.5f, 0.015f, card = Card.MOTION),
                Param.int("stiffness", "Stiffness", 1, 12, 6, card = Card.MOTION)
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
        /** The point where it first meets the glass; before that it is white light. */
        var firstHit = -1
    }

    private val rays = ArrayList<Ray>()
    private var elapsed = 0f
    private var payoffFrame = false
    private var focused = false
    private var heardAngle = Int.MIN_VALUE
    private var tightest = Float.MAX_VALUE
    private var tightenedFor = 0f
    /** What the beam measured before the sweep went looking, for comparison. */
    private var startMeasure = Float.MAX_VALUE
    private var lastDeviation = Float.NaN
    private var deviationFalling = false
    private var sourceY = 0.35f
    private var angle = -0.35f
    /** The beam's direction as traced: the angle, or for the prism, about its best one. */
    private var aim = -0.35f
    private var shape = 0
    // The prism, scaled to fit the screen across: an equilateral one is
    // wider than an upright phone.
    private var pT = PRISM_TOP
    private var pB = PRISM_BOTTOM
    private var pH = PRISM_HALF
    // The lens, scaled: on an upright phone it is turned so the light runs
    // down the long axis and its focus lands on screen.
    private var lr = LENS_R
    private var lo = LENS_OFFSET
    private var lensTurned = false

    // Where the glass sits on screen. Rays are traced in the glass's own
    // frame and turned and moved into the world to be drawn. Upright in the
    // middle of a phone, a prism as wide as the screen sent its rainbow off
    // the right edge after a few millimetres; turned so the light leaves
    // heading down the long axis, the fan has the whole height to open in.
    private var rotC = 1f
    private var rotS = 0f
    private var offX = 0f
    private var offY = 0f
    private var viewHalfW = 0.5f

    private fun wx(x: Float, y: Float) = offX + rotC * x - rotS * y
    private fun wy(x: Float, y: Float) = offY + rotS * x + rotC * y
    private fun inView(x: Float, y: Float): Boolean {
        val a = wx(x, y); val b = wy(x, y)
        return a < viewHalfW && a > -viewHalfW * 1.02f && b < 1.05f && b > floorY
    }

    /**
     * Where the light stops. For the prism a wall across the foot of the
     * screen, so the spectrum lands somewhere and is seen as a band of
     * colour: running on off the bottom corner, its finale was cut by the
     * screen edge.
     */
    private var floorY = -1.05f

    override fun setup(env: SimEnv) {
        val p = env.params
        rays.clear()
        shape = p.choiceIndex("shape")
        viewHalfW = env.halfWidth
        val upright = env.halfWidth < 0.8f
        if (shape == 0) {
            val ps = if (upright) 0.5f else min(0.8f, env.halfWidth * 0.5f / PRISM_HALF)
            pT = PRISM_TOP * ps; pB = PRISM_BOTTOM * ps; pH = PRISM_HALF * ps
            val turn = if (upright) PRISM_TURN_UPRIGHT else PRISM_TURN_WIDE
            rotC = cos(turn); rotS = sin(turn)
            offX = if (upright) -0.05f else -env.halfWidth * 0.4f
            offY = if (upright) 0.5f else 0.3f
            floorY = WALL_Y
        } else {
            floorY = -1.05f
            pT = PRISM_TOP; pB = PRISM_BOTTOM; pH = PRISM_HALF
            rotC = 1f; rotS = 0f; offX = 0f; offY = 0f
        }
        lensTurned = shape == 1 && upright
        if (lensTurned) {
            // Side-on across a phone, a lens wide enough to see focused about
            // 0.85 past its middle, off the right edge of a 0.46-wide screen,
            // and the beam came in high, off its axis, so its edge rays
            // reflected inside the glass and left out of the bottom. Turned a
            // quarter, the light falls down the screen onto the lens's axis
            // and comes to a point in the lower half.
            lr = LENS_R * LENS_UPRIGHT_SCALE; lo = LENS_OFFSET * LENS_UPRIGHT_SCALE
            rotC = 0f; rotS = -1f; offX = 0f; offY = LENS_UPRIGHT_Y
        } else {
            lr = LENS_R; lo = LENS_OFFSET
        }
        val count = p.i("rays", 9).coerceIn(1, 60)
        for (i in 0 until count) {
            val t = if (count == 1) 0.5f else i.toFloat() / (count - 1)
            // Its refractive index: dense flint glass, which bends violet
            // (t = 0) more than red (t = 1), as all glass does. It ran the
            // other way, from 1.00 to 1.12, so violet went straight through.
            // A lens is crown glass, as lenses are: weaker, and far less
            // dispersive, so it focuses rather than splitting light.
            rays.add(Ray(t, if (shape == 1) CROWN_RED + CROWN_DISPERSION * (1f - t) else N_RED + DISPERSION * (1f - t)))
        }
        sourceY = p.f("height", 0.35f)
        // Not read at all while the sweep owns it. Reading it and then
        // overwriting it on the first step left the slider deciding the first
        // frames and the moment the focus is found, so the drawer dimmed a
        // control that still did something — which is its own kind of lie.
        angle = if (p.b("sweep", true)) -0.35f else p.f("angle", -0.35f)
        aim = prismAim(p.b("sweep", true), 0f)
        elapsed = 0f
        lastDeviation = Float.NaN
        deviationFalling = false
        payoffFrame = false
        focused = false
        heardAngle = Int.MIN_VALUE
        tightest = Float.MAX_VALUE
        tightenedFor = 0f
        trace(env)
        startMeasure = focusMeasure()
    }

    /** Where a prism's beam starts: on a straight line into the middle of the left face. */
    /**
     * How far along the prism's left face, from its middle, the beam aims.
     * The slider's whole range maps onto most of the face, either side of
     * the default: as (height - 0.35) * 0.8 most of the range aimed past
     * the corners, and along the right face's slope rather than the left's,
     * so away from the default the beam missed the glass altogether.
     */
    private fun faceAlong(): Float {
        val half = hypot(pH, pB - pT) * 0.5f * 0.8f
        val d = sourceY - 0.35f
        return if (d >= 0f) d / 0.45f * half else d / 0.95f * half
    }

    private fun prismStartY(x0: Float, dir: Float): Float {
        val faceDirX = -pH; val faceDirY = pB - pT
        val fl = hypot(faceDirX, faceDirY)
        val along = faceAlong()
        val hx = -pH * 0.5f - faceDirX / fl * along
        val hy = (pT + pB) * 0.5f - faceDirY / fl * along
        return hy + (x0 - hx) * kotlin.math.tan(dir)
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        if (env.params.b("sweep", true)) {
            angle = -0.35f + sin(elapsed * 0.4f) * 0.55f
        }
        aim = prismAim(env.params.b("sweep", true), elapsed)
        trace(env)
        // A tick each time the beam has swung a notch: one every step was a
        // 120-a-second buzz, even with the sweep off and nothing moving.
        val notch = kotlin.math.floor(angle * ANGLE_NOTCHES).toInt()
        if (notch != heardAngle) {
            if (heardAngle != Int.MIN_VALUE) {
                // Heard where the prism's light lands on the wall: the end of
                // the middle ray. A lens's or slab's rays all run off the right.
                val mid = rays.getOrNull(rays.size / 2)
                val pan = if (shape != 0 || mid == null || mid.count == 0) 0f
                    else (wx(mid.xs[mid.count - 1], mid.ys[mid.count - 1]) / max(0.1f, env.halfWidth)).coerceIn(-1f, 1f)
                env.emit(EventKind.STEP, ((angle + 1f) * 0.5f).coerceIn(0f, 1f), pan, 0.25f)
            }
            heardAngle = notch
        }
        // The focus: the moment the rays are at their tightest on the far side
        // of the glass, which is what a lens does and what the sweep is hunting
        // for. A clock at twelve seconds fired wherever the sweep happened to
        // be; an absolute threshold of 0.12 world units was the next attempt,
        // and it only fitted one ray count. Measured: at one ray and at sixty,
        // and with the beam aimed below the glass, the spread never reached
        // 0.12 and the sim never paid off at all — no slow motion, no sweep,
        // and TickTock throwing away every seed. How much tighter the beam got
        // than it started is a question that has an answer at every setting.
        if (shape == 0) {
            // A prism has no focus: its moment is least deviation, the
            // classic picture, where the beam crosses the glass parallel to
            // its base and the fan lands squarest on the wall. Spread "focus"
            // fired at the sweep's far end instead, with half the colours
            // reflected inside the glass and leaving off the left edge.
            val dev = prismDeviation()
            val rising = dev.isFinite() && lastDeviation.isFinite() && dev > lastDeviation + 1e-5f
            if (dev.isFinite() && lastDeviation.isFinite() && dev < lastDeviation - 1e-5f) deviationFalling = true
            if (!focused && elapsed > EARLIEST_FOCUS && ((deviationFalling && rising) || elapsed > FOCUS_BACKSTOP)) {
                focused = true
                payoffFrame = true
                env.emit(EventKind.DONE, 0.8f, 0f, 1f)
            }
            if (rising) deviationFalling = false
            lastDeviation = dev
            return
        }
        if (lensTurned && env.params.b("sweep", true)) {
            // Its moment is the beam crossing the axis: every ray brought to
            // one sharp point straight down the middle.
            val crossed = lastDeviation.isFinite() && (aim >= 0f) != (lastDeviation >= 0f)
            lastDeviation = aim
            if (!focused && elapsed > EARLIEST_FOCUS && (crossed || elapsed > FOCUS_BACKSTOP)) {
                focused = true
                payoffFrame = true
                env.emit(EventKind.DONE, 0.8f, 0f, 1f)
            }
            return
        }
        val measure = focusMeasure()
        if (measure.isFinite() && !startMeasure.isFinite()) startMeasure = measure
        if (measure < tightest) {
            tightest = measure
            tightenedFor = 0f
        } else {
            tightenedFor += abs(dt)
        }
        val converged = tightest.isFinite() && startMeasure.isFinite() &&
            tightest <= startMeasure * FOCUS_RATIO
        if (!focused && elapsed > EARLIEST_FOCUS &&
            ((converged && tightenedFor > 0.8f) || elapsed > FOCUS_BACKSTOP)
        ) {
            focused = true
            payoffFrame = true
            env.emit(EventKind.DONE, 0.8f, 0f, 1f)
        }
    }

    /**
     * Where the beam points. The prism is aimed about its angle of least
     * deviation, and its sweep stays between angles where every colour gets
     * out through the far face: below about 14° up into the left face violet
     * meets that face past its critical angle and reflects inside, so the
     * old sweep (down to 8°) ended its swing with a broken fan leaving off
     * the left of a phone screen.
     */
    private fun prismAim(sweep: Boolean, t: Float): Float = when {
        // About the axis, a quarter of the sweep's swing: the turned lens is
        // narrower, and tilted by the whole swing the beam missed it.
        lensTurned -> (angle + 0.35f) * 0.5f
        shape != 0 -> angle
        sweep -> PRISM_SWEEP_MID + sin(t * 0.4f) * PRISM_SWEEP_HALF
        else -> angle * PRISM_SWEEP + PRISM_AIM
    }

    /** How far the middle colour is turned from the white beam, in radians; NaN until it is out. */
    private fun prismDeviation(): Float {
        val ray = rays.getOrNull(rays.size / 2) ?: return Float.NaN
        val n = ray.count
        if (ray.firstHit < 0 || n < 4) return Float.NaN
        val outX = ray.xs[n - 1] - ray.xs[n - 2]
        val outY = ray.ys[n - 1] - ray.ys[n - 2]
        val len = hypot(outX, outY)
        if (len < 1e-6f) return Float.NaN
        val c = ((cos(aim) * outX + sin(aim) * outY) / len).coerceIn(-1f, 1f)
        return kotlin.math.acos(c)
    }

    /**
     * How converged the beam is, as a number to be made small.
     *
     * With two rays or more that is how close together they get past the glass.
     * One ray has no focus, so it is how straight the ray still is: a single
     * ray's moment is the angle where the glass bends it hardest, which is the
     * same sweep hunting for the same extreme.
     */
    private fun focusMeasure(): Float {
        if (rays.size >= 2) return tightestSpread()
        val ray = rays.firstOrNull() ?: return Float.MAX_VALUE
        if (ray.count < 4) return Float.MAX_VALUE
        val n = ray.count
        val inX = ray.xs[1] - ray.xs[0]
        val inY = ray.ys[1] - ray.ys[0]
        val outX = ray.xs[n - 1] - ray.xs[n - 2]
        val outY = ray.ys[n - 1] - ray.ys[n - 2]
        val inLen = hypot(inX, inY)
        val outLen = hypot(outX, outY)
        if (inLen < 1e-6f || outLen < 1e-6f) return Float.MAX_VALUE
        // 1 for an undeviated ray, falling towards 0 the harder it is bent.
        val cosTurn = ((inX * outX + inY * outY) / (inLen * outLen)).coerceIn(-1f, 1f)
        return (1f + cosTurn) * 0.5f
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

    /**
     * March each ray, refracting wherever it meets the glass's surface. The
     * crossing is pinned down by bisection and the surface's true normal is
     * used there — the face of the prism, the radius of the lens's circle —
     * and Snell's law is applied the right way round going in and coming out.
     */
    private fun trace(env: SimEnv) {
        val halfW = env.halfWidth
        val count = rays.size
        for ((index, ray) in rays.withIndex()) {
            var x = -halfW * 0.98f
            // A lens is shown a parallel beam, to focus; a prism or a slab one
            // narrow white beam, to split into its colours.
            var y = when (shape) {
                1 -> if (lensTurned) (sourceY - 0.35f) * 0.4f - (index - (count - 1) * 0.5f) * min(0.035f, 0.26f / max(1, count - 1))
                    else sourceY - (index - (count - 1) * 0.5f) * 0.035f
                0 -> prismStartY(x, aim)
                else -> sourceY
            }
            var dx = cos(aim)
            var dy = sin(aim)
            if (shape == 0) {
                // The prism is turned, so its beam starts wherever its line
                // into the left face enters the view: walked back from the
                // face until it leaves the screen.
                val faceDirX = -pH; val faceDirY = pB - pT
                val fl = hypot(faceDirX, faceDirY)
                val along = faceAlong()
                x = -pH * 0.5f - faceDirX / fl * along - dx * 0.02f
                y = (pT + pB) * 0.5f - faceDirY / fl * along - dy * 0.02f
                var back = 0
                while (back++ < 400 && inView(x - dx * 0.01f, y - dy * 0.01f)) { x -= dx * 0.01f; y -= dy * 0.01f }
            } else if (lensTurned) {
                // Aimed through its point just in front of the lens, then
                // walked back to the top of the screen.
                x = -(lr - lo) - 0.04f
                var back = 0
                while (back++ < 400 && inView(x - dx * 0.01f, y - dy * 0.01f)) { x -= dx * 0.01f; y -= dy * 0.01f }
            }
            ray.count = 0
            ray.firstHit = -1
            ray.xs[ray.count] = x
            ray.ys[ray.count] = y
            ray.count++
            var inside = insideGlass(x, y)
            var guard = MAX_SEGMENTS - 2
            val stepLen = 0.012f
            while (guard-- > 0 && ray.count < MAX_SEGMENTS - 2 && inView(x, y)) {
                var nx = x + dx * stepLen
                var ny = y + dy * stepLen
                if (insideGlass(nx, ny) != inside) {
                    // Where exactly it crosses.
                    var lo = 0f; var hi = 1f
                    repeat(18) {
                        val m = (lo + hi) * 0.5f
                        if (insideGlass(x + dx * stepLen * m, y + dy * stepLen * m) == inside) lo = m else hi = m
                    }
                    val bx = x + dx * stepLen * hi; val by = y + dy * stepLen * hi
                    val out = outwardNormal(bx, by)
                    // Facing the ray: the outward normal going in, the inward one going out.
                    val sign = if (inside) -1f else 1f
                    val n0 = out[0] * sign; val n1 = out[1] * sign
                    val eta = if (inside) ray.wavelength else 1f / ray.wavelength
                    val cosI = -(dx * n0 + dy * n1)
                    val sinT2 = eta * eta * (1f - cosI * cosI)
                    if (sinT2 > 1f) {
                        // Total internal reflection: it stays where it was.
                        dx += 2f * cosI * n0
                        dy += 2f * cosI * n1
                    } else {
                        val cosT = sqrt(1f - sinT2)
                        dx = eta * dx + (eta * cosI - cosT) * n0
                        dy = eta * dy + (eta * cosI - cosT) * n1
                        inside = !inside
                    }
                    val len = hypot(dx, dy); dx /= len; dy /= len
                    nx = bx + dx * 1e-4f
                    ny = by + dy * 1e-4f
                    if (ray.count >= MAX_SEGMENTS) break
                    if (ray.firstHit < 0) ray.firstHit = ray.count
                    ray.xs[ray.count] = bx
                    ray.ys[ray.count] = by
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
            if (y < pB || y > pT) false
            else {
                val t = (pT - y) / (pT - pB)
                abs(x) < t * pH
            }
        }
        1 -> hypot(x + lo, y) < lr && hypot(x - lo, y) < lr
        else -> abs(x) < SLAB_W && abs(y) < SLAB_H
    }

    /** The glass's outward normal at a point on its surface, exactly. */
    private fun outwardNormal(x: Float, y: Float): FloatArray = when (shape) {
        0 -> {
            // Whichever face it is nearest: the two slopes, or the base.
            val h = pT - pB
            val len = hypot(h, pH)
            val nx = h / len; val ny = pH / len
            val dRight = (x * nx + (y - pT) * ny)
            val dLeft = (-x * nx + (y - pT) * ny)
            val dBase = pB - y
            when {
                abs(dBase) <= abs(dRight) && abs(dBase) <= abs(dLeft) -> floatArrayOf(0f, -1f)
                abs(dRight) <= abs(dLeft) -> floatArrayOf(nx, ny)
                else -> floatArrayOf(-nx, ny)
            }
        }
        1 -> {
            // On the arc of whichever circle it sits on: away from that centre.
            val dA = hypot(x + lo, y); val dB = hypot(x - lo, y)
            if (abs(dA - lr) <= abs(dB - lr)) floatArrayOf((x + lo) / dA, y / dA)
            else floatArrayOf((x - lo) / dB, y / dB)
        }
        else -> if (SLAB_W - abs(x) < SLAB_H - abs(y)) floatArrayOf(if (x > 0f) 1f else -1f, 0f)
        else floatArrayOf(0f, if (y > 0f) 1f else -1f)
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        // The glass itself: a faint body of glass with a clear edge. A 22%
        // grey outline alone was barely there on a phone, and the lens was
        // drawn as two whole circles rather than the lens they overlap in.
        val edge = Palette.lerp(Palette.TEXT_SECONDARY, 0xFF9FD8FF.toInt(), 0.4f)
        if (shape == 0) {
            // The wall the spectrum lands on: a faint floor below a fine edge.
            // Edge to edge but no further: past the sides it failed FrameTest.
            g.rect(0f, (floorY - 1.05f) * 0.5f, viewHalfW, (floorY + 1.05f) * 0.5f, Palette.TEXT_SECONDARY, 0f, 0.05f)
            g.line(-viewHalfW, floorY, viewHalfW, floorY, 0.004f, Palette.TEXT_SECONDARY, 0.4f)
        }
        when (shape) {
            0 -> {
                // Filled in thin bands, row by row, under the edges.
                val bands = 48
                val h = (pT - pB) / bands
                val turn = atan2(rotS, rotC)
                for (k in 0 until bands) {
                    val yMid = pB + (k + 0.5f) * h
                    val half = (pT - yMid) / (pT - pB) * pH
                    g.rect(wx(0f, yMid), wy(0f, yMid), half, h * 0.5f + 0.0005f, GLASS_FILL, turn, 1f)
                }
                g.line(wx(0f, pT), wy(0f, pT), wx(-pH, pB), wy(-pH, pB), 0.007f, edge, 0.75f)
                g.line(wx(0f, pT), wy(0f, pT), wx(pH, pB), wy(pH, pB), 0.007f, edge, 0.75f)
                g.line(wx(-pH, pB), wy(-pH, pB), wx(pH, pB), wy(pH, pB), 0.007f, edge, 0.75f)
                for ((cx0, cy0) in listOf(0f to pT, -pH to pB, pH to pB)) g.disc(wx(cx0, cy0), wy(cx0, cy0), 0.0035f, edge, 0.75f)
            }
            1 -> {
                // The two arcs that bound the lens, as short chords.
                val half = kotlin.math.acos((lo / lr).coerceIn(-1f, 1f))
                val pieces = 40
                for (side in 0..1) {
                    val ccx = if (side == 0) -lo else lo
                    val mid = if (side == 0) 0f else PI.toFloat()
                    for (k in 0 until pieces) {
                        val a0 = mid - half + 2f * half * k / pieces
                        val a1 = mid - half + 2f * half * (k + 1) / pieces
                        val x0 = ccx + cos(a0) * lr; val y0 = sin(a0) * lr
                        val x1 = ccx + cos(a1) * lr; val y1 = sin(a1) * lr
                        g.line(wx(x0, y0), wy(x0, y0), wx(x1, y1), wy(x1, y1), 0.006f, edge, 0.6f)
                    }
                }
                val tall = sqrt(lr * lr - lo * lo)
                val bands = 48
                val h = 2f * tall / bands
                for (k in 0 until bands) {
                    val yMid = -tall + (k + 0.5f) * h
                    val halfW = sqrt((lr * lr - yMid * yMid).coerceAtLeast(0f)) - lo
                    if (halfW > 0f) g.rect(wx(0f, yMid), wy(0f, yMid), halfW, h * 0.5f, GLASS_FILL, atan2(rotS, rotC), 1f)
                }
            }
            else -> {
                g.rect(0f, 0f, SLAB_W, SLAB_H, GLASS_FILL, 0f, 1f)
                g.line(-SLAB_W, SLAB_H, SLAB_W, SLAB_H, 0.006f, edge, 0.6f)
                g.line(-SLAB_W, -SLAB_H, SLAB_W, -SLAB_H, 0.006f, edge, 0.6f)
                g.line(-SLAB_W, -SLAB_H, -SLAB_W, SLAB_H, 0.006f, edge, 0.6f)
                g.line(SLAB_W, -SLAB_H, SLAB_W, SLAB_H, 0.006f, edge, 0.6f)
            }
        }
        // The light before a prism or slab is white: every colour on one
        // line. It was drawn as nine coloured lines on top of each other, so
        // only the last one showed and the "white" beam came in orange. The
        // lens is shown parallel rays of one colour each, so they keep theirs.
        val shared = shape != 1
        for ((index, ray) in rays.withIndex()) {
            val split = if (ray.firstHit < 0) ray.count - 1 else ray.firstHit
            if (!shared || index > 0) continue
            strokes(g, ray, 0, split, 0.014f, Palette.TEXT_PRIMARY, 0.08f)
            strokes(g, ray, 0, split, 0.0045f, Palette.TEXT_PRIMARY, 0.95f)
        }
        // The spectrum between neighbouring rays filled in, a band at a time,
        // so the dispersed light is one continuous rainbow rather than nine
        // hairlines; only for the prism, whose rays share one white beam.
        if (shape == 0 && rays.size >= 2) {
            for (k in 0 until rays.size - 1) {
                val ra = rays[k]; val rb = rays[k + 1]
                val ca = Palette.hsv(250f - ra.tint * 240f, 0.8f, 1f)
                val cb = Palette.hsv(250f - rb.tint * 240f, 0.8f, 1f)
                val col = Palette.lerp(ca, cb, 0.5f)
                // The last straight run of each: from where it last turned to its end.
                val sa = lastTurn(ra); val sb = lastTurn(rb)
                if (sa < 0 || sb < 0) continue
                // Only neighbours that left the glass together: when one ray
                // reflects inside and its neighbour gets out, their last runs
                // start far apart and the band between them is not light.
                if (hypot(ra.xs[sa] - rb.xs[sb], ra.ys[sa] - rb.ys[sb]) > 0.06f) continue
                if (hypot(ra.xs[ra.count - 1] - rb.xs[rb.count - 1], ra.ys[ra.count - 1] - rb.ys[rb.count - 1]) > 0.35f) continue
                val pieces = 18
                for (q in 0 until pieces) {
                    val t0 = q / pieces.toFloat(); val t1 = (q + 1) / pieces.toFloat()
                    val ax0 = lerpF(ra.xs[sa], ra.xs[ra.count - 1], t0); val ay0 = lerpF(ra.ys[sa], ra.ys[ra.count - 1], t0)
                    val bx0 = lerpF(rb.xs[sb], rb.xs[rb.count - 1], t0); val by0 = lerpF(rb.ys[sb], rb.ys[rb.count - 1], t0)
                    val ax1 = lerpF(ra.xs[sa], ra.xs[ra.count - 1], t1); val ay1 = lerpF(ra.ys[sa], ra.ys[ra.count - 1], t1)
                    val bx1 = lerpF(rb.xs[sb], rb.xs[rb.count - 1], t1); val by1 = lerpF(rb.ys[sb], rb.ys[rb.count - 1], t1)
                    val mx0 = wx((ax0 + bx0) * 0.5f, (ay0 + by0) * 0.5f); val my0 = wy((ax0 + bx0) * 0.5f, (ay0 + by0) * 0.5f)
                    val mx1 = wx((ax1 + bx1) * 0.5f, (ay1 + by1) * 0.5f); val my1 = wy((ax1 + bx1) * 0.5f, (ay1 + by1) * 0.5f)
                    val wide = hypot((ax0 + ax1 - bx0 - bx1) * 0.5f, (ay0 + ay1 - by0 - by1) * 0.5f)
                    // Brighter close to the glass, and dimmer the wider the
                    // band: the same light spread over more of the wall.
                    g.line(mx0, my0, mx1, my1, wide + 0.002f, col, (0.5f - 0.2f * t0) * min(1f, 0.035f / (wide + 1e-4f)))
                }
            }
            // Where each colour meets the wall, a soft glow and a bright
            // stripe between neighbours: the spectrum cast on the wall.
            for (ray in rays) {
                if (ray.firstHit < 0) continue
                val c = Palette.hsv(250f - ray.tint * 240f, 0.8f, 1f)
                val ex = wx(ray.xs[ray.count - 1], ray.ys[ray.count - 1]); val ey = wy(ray.xs[ray.count - 1], ray.ys[ray.count - 1])
                g.glow(ex, ey, 0.07f, c, 0.22f)
            }
            for (k in 0 until rays.size - 1) {
                val ra = rays[k]; val rb = rays[k + 1]
                if (ra.firstHit < 0 || rb.firstHit < 0) continue
                val ay = wy(ra.xs[ra.count - 1], ra.ys[ra.count - 1]); val by = wy(rb.xs[rb.count - 1], rb.ys[rb.count - 1])
                // Only where both landed on the wall, side by side.
                if (ay > floorY + 0.03f || by > floorY + 0.03f) continue
                val ax = wx(ra.xs[ra.count - 1], ra.ys[ra.count - 1]); val bx = wx(rb.xs[rb.count - 1], rb.ys[rb.count - 1])
                if (abs(ax - bx) > 0.2f) continue
                val col = Palette.lerp(Palette.hsv(250f - ra.tint * 240f, 0.8f, 1f), Palette.hsv(250f - rb.tint * 240f, 0.8f, 1f), 0.5f)
                g.line(ax, floorY, bx, floorY, 0.012f, col, 0.95f)
            }
        }
        for ((index, ray) in rays.withIndex()) {
            val c = Palette.hsv(250f - ray.tint * 240f, 0.8f, 1f)
            val from = when {
                !shared -> 0
                ray.firstHit >= 0 -> ray.firstHit
                else -> ray.count
            }
            // A soft wide stroke under each, so the fan reads as light.
            strokes(g, ray, from, ray.count - 1, 0.016f, c, 0.07f)
            strokes(g, ray, from, ray.count - 1, 0.003f, c, 0.9f)
        }
    }

    /**
     * The path from point [from] to [to] as one line per straight run: the
     * march samples every 0.012, and a line per sample was hundreds of
     * overlapping pieces per ray, beaded where the soft strokes overlapped.
     */
    private fun strokes(g: G, ray: Ray, from: Int, to: Int, width: Float, color: Int, alpha: Float) {
        if (to <= from) return
        var start = from
        for (i in from + 1..to) {
            val last = i == to
            if (!last) {
                val ax = ray.xs[i] - ray.xs[i - 1]; val ay = ray.ys[i] - ray.ys[i - 1]
                val bx = ray.xs[i + 1] - ray.xs[i]; val by = ray.ys[i + 1] - ray.ys[i]
                val cross = abs(ax * by - ay * bx)
                val scale = hypot(ax, ay) * hypot(bx, by)
                if (cross <= scale * 1e-4f) continue
            }
            g.line(wx(ray.xs[start], ray.ys[start]), wy(ray.xs[start], ray.ys[start]),
                wx(ray.xs[i], ray.ys[i]), wy(ray.xs[i], ray.ys[i]), width, color, alpha)
            start = i
        }
    }

    /** The index where a ray's path last bends: the start of its final straight run, or -1. */
    private fun lastTurn(ray: Ray): Int {
        if (ray.firstHit < 0 || ray.count < 3) return -1
        var i = ray.count - 2
        while (i > ray.firstHit) {
            val ax = ray.xs[i] - ray.xs[i - 1]; val ay = ray.ys[i] - ray.ys[i - 1]
            val bx = ray.xs[i + 1] - ray.xs[i]; val by = ray.ys[i + 1] - ray.ys[i]
            if (abs(ax * by - ay * bx) > hypot(ax, ay) * hypot(bx, by) * 1e-4f) return i
            i--
        }
        return ray.firstHit
    }

    private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "rays" to rays.size.toString(),
        "angle" to String.format(java.util.Locale.US, "%.0f°", aim * 180f / PI.toFloat())
    )

    companion object {
        /** Beam-angle notches per radian of sweep, each a tick. */
        const val ANGLE_NOTCHES = 30f
        /** How close the rays have to come together to count as a focus. */
        /**
         * How much tighter than its start the beam has to get to count as
         * focused. Relative, so it means the same thing at one ray and at
         * sixty.
         */
        const val FOCUS_RATIO = 0.4f
        private val GLASS_FILL = 0x149FD8FF
        /** Dense flint glass: red, and how much more violet is bent. */
        const val N_RED = 1.62f
        /** Least deviation for a 60° prism at n ≈ 1.65: in at about 55° to the face. */
        const val PRISM_AIM = 0.42f + 0.35f * 0.5f
        const val PRISM_SWEEP = 0.5f
        /** The prism's sweep: 16° to 40° up into the left face, about least deviation (~26°). */
        const val PRISM_SWEEP_MID = 0.49f
        const val PRISM_SWEEP_HALF = 0.21f
        /** The prism's turn on screen, clockwise, upright phone and wide. */
        const val PRISM_TURN_UPRIGHT = -1.12f
        /** The wall the prism's spectrum is cast on, near the foot of the screen. */
        const val WALL_Y = -0.84f
        const val PRISM_TURN_WIDE = -0.3f
        const val DISPERSION = 0.06f
        const val CROWN_RED = 1.51f
        const val CROWN_DISPERSION = 0.015f
        const val PRISM_TOP = 0.52f
        const val PRISM_BOTTOM = -0.5f
        /** Equilateral: 60° at every corner, the classic prism. */
        const val PRISM_HALF = (PRISM_TOP - PRISM_BOTTOM) * 0.57735f
        const val LENS_R = 0.75f
        const val LENS_OFFSET = 0.45f
        const val LENS_UPRIGHT_SCALE = 0.62f
        /** The turned lens's middle, above the screen's: the focus falls below. */
        const val LENS_UPRIGHT_Y = 0.42f
        const val SLAB_W = 0.35f
        const val SLAB_H = 0.55f

        /**
         * A quarter of the sweep, which is where it first reaches an extreme.
         * The sweep is sin(elapsed * 0.4), so a full cycle is 15.7 seconds and
         * the first turning point is at 3.9 — before that, whatever the beam
         * has found it has not finished finding.
         */
        const val EARLIEST_FOCUS = 3.9f

        /** Glass the beam never meets still owes a payoff. */
        const val FOCUS_BACKSTOP = 14f

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
            typicalSeconds = 16f,
            loopSeconds = 31.42f,
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
    /** The field each tracer last felt, for the direction of its dash. */
    private var fx = FloatArray(0)
    private var fy = FloatArray(0)
    /** Still on its first trip; once most have finished one, the field is revealed. */
    private var fresh = BooleanArray(0)
    /** How far each fresh tracer has come; see [tripLength]. */
    private var travelled = FloatArray(0)
    /**
     * On a wide screen a tracer far out in the weak field takes many seconds
     * to leave a landscape frame, and the field was traced long before most
     * had: the payoff never came within the run. There a first trip is also
     * done once the tracer has followed its line this far. Unlimited on a
     * phone, where it always was leaving the screen or meeting a charge.
     */
    private var tripLength = Float.MAX_VALUE
    private var firstTrips = 0
    private var particles = 0
    /** The tracer count asked for, which sets the drawing style and dash size. */
    private var asked = 0
    private var elapsed = 0f
    private var payoffFrame = false
    private var resolved = false
    private var heardTrips = -1
    private var swallowed = 0
    private var swallowedX = 0f
    private var sinceSwallow = 0f

    override fun setup(env: SimEnv) {
        val p = env.params
        resolved = false
        heardTrips = -1
        swallowed = 0
        swallowedX = 0f
        sinceSwallow = 0f
        elapsed = 0f
        count = p.i("charges", 4).coerceIn(1, 12)
        charges = FloatArray(count)
        cx = FloatArray(count)
        cy = FloatArray(count)
        for (i in 0 until count) {
            // Kept apart: two charges dropped on top of each other read as
            // one blob and hide the field between them, which is the picture.
            var tries = 0
            do {
                cx[i] = env.rng.range(-env.halfWidth * 0.7f, env.halfWidth * 0.7f)
                cy[i] = env.rng.range(-0.7f, 0.7f)
                var near = false
                for (k in 0 until i) if (hypot(cx[i] - cx[k], cy[i] - cy[k]) < MIN_GAP) near = true
            } while (near && ++tries < 30)
            charges[i] = if (i % 2 == 0) 1f else -1f
            charges[i] *= env.rng.range(0.6f, 1.4f)
        }
        // Centred as a group: placed at random, the four default charges
        // often sat bunched to one side, the field's whole structure in one
        // half of the phone and only far-field drift in the other.
        if (count > 1) {
            val mx = cx.average().toFloat(); val my = cy.average().toFloat()
            for (i in 0 until count) {
                cx[i] = (cx[i] - mx).coerceIn(-env.halfWidth * 0.75f, env.halfWidth * 0.75f)
                cy[i] = (cy[i] - my).coerceIn(-0.75f, 0.75f)
            }
        }
        // The slider counts tracers for an upright phone's screen; a wider one
        // gets as many more as it has area, up to four times, so the field
        // reads as densely there: 2,400 over a landscape frame were a faint
        // sprinkle with the field lines lost.
        asked = p.i("tracers", 2400).coerceIn(50, 80000)
        particles = (asked * (env.halfWidth / PHONE_HALF_WIDTH).coerceIn(1f, 4f)).toInt()
        px = FloatArray(particles)
        py = FloatArray(particles)
        alive = BooleanArray(particles)
        fx = FloatArray(particles)
        fy = FloatArray(particles)
        fresh = BooleanArray(particles) { true }
        travelled = FloatArray(particles)
        tripLength = if (env.halfWidth > 0.6f) WIDE_TRIP else Float.MAX_VALUE
        firstTrips = 0
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
                fx[i] = ex * sign; fy[i] = ey * sign
                if (len > 1e-7f) {
                    // Faster where the field is stronger, but not in direct
                    // proportion: at speed ∝ |E| the tracers raced through the
                    // strong field round each charge and piled up where it is
                    // weak, so the part of the picture that matters was empty.
                    val speed = (FIELD_SPEED * Math.pow(len.toDouble(), FIELD_EXPONENT).toFloat())
                        .coerceAtMost(MAX_TRACER_SPEED)
                    px[i] += ex / len * dt * speed * sign
                    py[i] += ey / len * dt * speed * sign
                    if (fresh[i]) travelled[i] += abs(dt) * speed
                }
                if (abs(px[i]) > env.halfWidth * 1.05f || abs(py[i]) > 1.05f) alive[i] = false
                for (k in 0 until count) {
                    if (hypot(px[i] - cx[k], py[i] - cy[k]) < 0.035f) alive[i] = false
                }
            }
        }
        // A finished tracer starts again somewhere at random, straight away.
        // They used to wait until four in five were gone and then all come
        // back at once, so for most of a run the middle of the screen, where
        // the field is, was an empty hole with confetti round the edges.
        for (i in 0 until particles) {
            if (fresh[i] && travelled[i] >= tripLength) { fresh[i] = false; firstTrips++ }
            if (alive[i]) continue
            captured++
            // Swallowed by a charge (it dies within 0.035 of one) rather than
            // run off the edge of the screen, where nobody sees it go.
            for (k in 0 until count) if (hypot(px[i] - cx[k], py[i] - cy[k]) < 0.05f) { swallowed++; swallowedX += cx[k]; break }
            if (fresh[i]) { fresh[i] = false; firstTrips++ }
            px[i] = env.rng.range(-env.halfWidth, env.halfWidth)
            py[i] = env.rng.range(-1f, 1f)
            alive[i] = true
            // The field where it now is: it kept the one it felt where it
            // died, beside a charge, and was drawn for a frame as a bright
            // dash pointing the wrong way out in the weak field — a scatter
            // of stray yellow specks across the whole picture.
            var ex = 0f
            var ey = 0f
            for (k in 0 until count) {
                val dx = px[i] - cx[k]
                val dy = py[i] - cy[k]
                val r2 = dx * dx + dy * dy + 0.0008f
                val f = charges[k] * strength / (r2 * sqrt(r2))
                ex += dx * f; ey += dy * f
            }
            fx[i] = ex * sign; fy[i] = ey * sign
        }
        // The tracers a charge swallows, gathered into a soft tick every
        // SWALLOW_GAP and heard from the charges that took them. Both notes
        // used to go out every step — 240 a second of the same two sounds.
        sinceSwallow += abs(dt)
        if (swallowed > 0 && sinceSwallow >= SWALLOW_GAP) {
            env.emit(EventKind.BIRTH, 0.5f, (swallowedX / swallowed / env.halfWidth).coerceIn(-1f, 1f), min(0.6f, 0.1f + swallowed * 0.002f))
            swallowed = 0; swallowedX = 0f; sinceSwallow = 0f
        }
        val tripLevel = (firstTrips.toFloat() / particles * LEVELS).toInt()
        if (tripLevel != heardTrips) {
            if (heardTrips >= 0) env.emit(EventKind.STEP, 1f - (firstTrips.toFloat() / particles), 0f, 0.25f)
            heardTrips = tripLevel
        }
        // The moment the picture resolves: most of the tracers have finished
        // their first trip along a field line. This used to be a stopwatch at
        // ten seconds, which fires whether or not anything has happened.
        // Not before 3.5 s: with tracers respawning as they finish, most have
        // done a trip within two seconds, and a payoff that early lands
        // before anyone has looked at the field.
        if (!resolved && firstTrips > particles * 0.8f && elapsed > 3.5f) {
            resolved = true
            payoffFrame = true
            env.emit(EventKind.DONE, 0.75f, 0f, 1f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        // Each tracer is a short dash along the field where it is, like iron
        // filings, coloured by how strong the field is there: the whole field
        // is visible at once. Loose dots coloured by screen position showed
        // where the tracers were, not what the field was doing.
        val dashes = asked <= DASH_LIMIT
        if (dashes) g.reserveInstances(particles) else g.reservePoints(particles)
        val dash = (0.9f / sqrt(asked.toFloat())).coerceIn(0.008f, 0.03f)
        for (i in 0 until particles) {
            if (!alive[i]) continue
            val len = hypot(fx[i], fy[i])
            val t = ((kotlin.math.log10(max(len, 1e-6f)) + 2.8f) / 2.8f).coerceIn(0f, 1f)
            val c = palette.at(t)
            if (dashes && len > 1e-7f) {
                val ux = fx[i] / len * dash * 0.5f
                val uy = fy[i] / len * dash * 0.5f
                // The weak far field recedes, so the lines bunched between
                // the charges are what the eye lands on.
                g.line(px[i] - ux, py[i] - uy, px[i] + ux, py[i] + uy, 0.0035f, c, 0.2f + 0.75f * t * t.coerceAtLeast(0.6f))
            } else {
                g.point(px[i], py[i], 0.004f, c)
            }
        }
        for (k in 0 until count) {
            val positive = charges[k] > 0f
            val c = if (positive) Palette.DANGER else Palette.ACCENT
            g.glow(cx[k], cy[k], 0.14f * abs(charges[k]), c, 0.55f)
            g.disc(cx[k], cy[k], 0.03f, c)
            g.rect(cx[k], cy[k], 0.016f, 0.004f, Palette.TEXT_PRIMARY)
            if (positive) g.rect(cx[k], cy[k], 0.004f, 0.016f, Palette.TEXT_PRIMARY)
        }
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
        /** Notches of first-trip progress that each get a note, and the swallow tick's spacing. */
        const val LEVELS = 40
        const val SWALLOW_GAP = 0.12f
        fun def(): SimDef = SimDef(
            id = "physics/charges",
            title = "Electric field",
            shelf = Shelf.PHYSICS,
            family = "Electricity & magnetism",
            blurb = "Tracers following the field, so its shape is visible.",
            formats = listOf(TrapFormat.REVEAL, TrapFormat.COLOR_BATTLE),
            lawKnobs = setOf(LawKnob.CHARGE),
            heroLabel = "New charges",
            defaultPalette = "Aurora",
            typicalSeconds = 10f,
            params = listOf(
                Param.int("charges", "Charges", 1, 12, 4, card = Card.DATA),
                Param.int("tracers", "Tracers", 50, 80000, 2400, card = Card.DATA, logScale = true),
                Param.float("strength", "Field strength", 0.1f, 4f, 1f, card = Card.MOTION)
            ),
            create = { ChargesSim() }
        )

        /**
         * Turns field magnitude into tracer speed, as FIELD_SPEED·|E|^0.4.
         * At the shipped strength a tracer half a unit from a charge moves at
         * about the rate it always did; the slider still speeds every tracer
         * up, though ten times the strength is 2.5 times the speed, not ten.
         */
        const val FIELD_SPEED = 1.1f
        const val PHONE_HALF_WIDTH = 0.46f
        const val WIDE_TRIP = 0.35f
        const val FIELD_EXPONENT = 0.4

        /** Above this many tracers they are drawn as dots, not dashes. */
        const val DASH_LIMIT = 6000

        /** The closest two charges are placed. */
        const val MIN_GAP = 0.32f

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

/** Where the draw lists stood, so what is drawn after can be zoomed. See [zoomSince]. */
internal fun G.drawMark(): IntArray = intArrayOf(instanceCount, glowCount, pointCount)

/**
 * Zooms everything drawn since [mark] by [k] about the screen point
 * ([ax], [ay]): a scene laid out for an upright phone, a tall island in the
 * middle of a wide screen, is shown larger there without its physics
 * knowing. Shapes, glows and points move and grow alike (a ring's inner edge
 * and a gap are shares, so they hold), exactly as a camera zoom would.
 */
internal fun G.zoomSince(mark: IntArray, k: Float, ax: Float, ay: Float) {
    if (k == 1f) return
    fun scale(a: FloatArray, from: Int, to: Int) {
        for (n in from until to) {
            val b = n * G.STRIDE
            a[b] = ax + (a[b] - ax) * k
            a[b + 1] = ay + (a[b + 1] - ay) * k
            a[b + 2] *= k
            a[b + 3] *= k
        }
    }
    scale(instances, mark[0], instanceCount)
    scale(glows, mark[1], glowCount)
    for (n in mark[2] until pointCount) {
        val b = n * 3
        pointPos[b] = ax + (pointPos[b] - ax) * k
        pointPos[b + 1] = ay + (pointPos[b + 1] - ay) * k
        pointPos[b + 2] *= k
    }
}
