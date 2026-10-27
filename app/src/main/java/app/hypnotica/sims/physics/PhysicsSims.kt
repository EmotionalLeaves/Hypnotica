package app.hypnotica.sims.physics

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Macro
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.LawKnob
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.Shelf
import app.hypnotica.core.sim.Parallel
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
 * The ball-escape format: a ball rattles inside a ring with one gap, growing and
 * speeding up on every hit, until it finds the way out.
 */
class BallEscapeSim : Sim {

    private class Ball(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var r: Float, var colorT: Float, var alive: Boolean = true
    )

    private val balls = ArrayList<Ball>()

    /** Balls born this step, appended once the loop over [balls] has finished. */
    private val spawned = ArrayList<Ball>()
    private var ringRadius = 0.88f
    private var rings = 1
    private var ringRadii = FloatArray(1)
    private var ringAngle = FloatArray(1)
    private var ringSpin = FloatArray(1)
    private var gapWidth = 0.5f

    private var escaped = 0
    private var hits = 0L
    private var payoffFrame = false
    private var restSeconds = -1f
    private var growth = 1.012f
    private var speedUp = 1.012f
    private var multiply = false
    /**
     * A speed floor that rises on every hit.
     *
     * Reflecting with restitution alone bleeds energy away faster than a few
     * percent a bounce puts back, so the ball settles at the bottom and the run
     * has no payoff at all. Driving a floor upward instead guarantees the build:
     * the ball always ends up livelier than it started, and finding the gap
     * becomes a matter of when, not if.
     */
    private var energy = 1f

    override fun setup(env: SimEnv) {
        val p = env.params
        balls.clear()
        spawned.clear()
        rings = p.i("rings", 1).coerceIn(1, 12)
        gapWidth = p.f("gap", 0.75f)
        growth = 1f + p.f("growth", 1.1f) * 0.01f
        speedUp = 1f + p.f("speedup", 3f) * 0.01f
        multiply = p.b("multiply", false)
        ringRadius = 0.92f

        ringRadii = FloatArray(rings) { i -> ringRadius - i * (ringRadius * 0.62f / rings) }
        ringAngle = FloatArray(rings) { i -> env.rng.range(0f, 2f * PI.toFloat()) }
        ringSpin = FloatArray(rings) { i ->
            env.rng.range(0.25f, 0.85f) * (if (env.rng.bool()) 1f else -1f) * p.f("spin", 1f)
        }

        val count = p.i("balls", 1).coerceIn(1, 64)
        val speed = p.f("speed0", 0.7f)
        energy = speed
        repeat(count) {
            val a = env.rng.range(0f, 2f * PI.toFloat())
            balls.add(
                Ball(
                    env.rng.range(-0.08f, 0.08f), env.rng.range(-0.08f, 0.08f),
                    cos(a) * speed, sin(a) * speed,
                    p.f("size", 0.045f), env.rng.nextFloat()
                )
            )
        }
        escaped = 0
        hits = 0L
        payoffFrame = false
        restSeconds = -1f
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        if (restSeconds >= 0f) {
            restSeconds -= abs(dt)
            return
        }
        val laws = env.laws
        val gx = laws.gravityDirX * laws.gravity * 0.06f
        val gy = -laws.gravityDirY * laws.gravity * 0.06f

        for (i in ringAngle.indices) ringAngle[i] += ringSpin[i] * dt

        for (b in balls) {
            if (!b.alive) continue
            b.vx += gx * dt
            b.vy += gy * dt
            // abs: a negative dt made the product negative, which the clamp
            // turned into no friction at all — so the slider and the "No
            // friction" pill did nothing whenever time ran backwards.
            val drag = 1f - (laws.friction * abs(dt)).coerceIn(0f, 0.2f)
            b.vx *= drag
            b.vy *= drag

            val speed = hypot(b.vx, b.vy)
            if (speed > laws.lightSpeed) {
                val k = laws.lightSpeed / speed
                b.vx *= k; b.vy *= k
            }
            if (speed > MAX_SPEED) {
                val k = MAX_SPEED / speed
                b.vx *= k; b.vy *= k
            }

            b.x += b.vx * dt
            b.y += b.vy * dt

            // Rings are checked outside in; the ball normally sits inside the
            // smallest one and works its way out through the gaps.
            for (ri in ringRadii.indices) {
                val rr = ringRadii[ri]
                val d = hypot(b.x, b.y)
                if (d + b.r < rr) continue
                if (d < rr) {
                    // Touching from inside.
                    val ang = atan2(b.y, b.x)
                    var rel = ang - ringAngle[ri]
                    rel = ((rel % TWO_PI) + TWO_PI) % TWO_PI
                    if (rel < gapWidth) {
                        // Through the gap: this ring is spent for this ball.
                        continue
                    }
                    bounce(env, b, rr, d)
                    break
                } else if (ri == 0) {
                    // Outside the outer ring: escaped.
                    b.alive = false
                    escaped++
                    env.emit(EventKind.DONE, b.colorT, (b.x).coerceIn(-1f, 1f), 1f)
                    payoffFrame = true
                    restSeconds = 1.6f
                    break
                }
            }
        }

        if (balls.none { it.alive } && restSeconds < 0f) restSeconds = 1.2f

        if (spawned.isNotEmpty()) {
            balls.addAll(spawned)
            spawned.clear()
        }
    }

    private fun bounce(env: SimEnv, b: Ball, rr: Float, d: Float) {
        // Ghosts: the rings are there to be looked at, not to stop anything.
        if (env.laws.passThrough) return
        val nx = if (d > 1e-6f) b.x / d else 1f
        val ny = if (d > 1e-6f) b.y / d else 0f
        val dot = b.vx * nx + b.vy * ny
        if (dot > 0f) {
            // Sticky: the ball gives up rather than bouncing.
            if (env.laws.sticky) {
                b.vx *= 0.12f
                b.vy *= 0.12f
                env.emit(EventKind.HIT, b.colorT, b.x, 0.5f)
                return
            }
            val e = env.laws.bounce
            b.vx -= (1f + e) * dot * nx
            b.vy -= (1f + e) * dot * ny
            // Push out so it cannot stick to the wall.
            val overlap = (d + b.r) - rr
            if (overlap > 0f) {
                b.x -= nx * overlap * 1.02f
                b.y -= ny * overlap * 1.02f
            }
            b.r = min(b.r * growth, rr * 0.42f)
            energy = min(energy * speedUp, MAX_SPEED * 0.8f)
            val sp = hypot(b.vx, b.vy)
            val target = max(sp, energy)
            if (sp > 1e-5f) {
                val k = target / sp
                b.vx *= k
                b.vy *= k
            }
            b.colorT = (b.colorT + 0.013f) % 1f
            hits++
            env.emit(EventKind.HIT, b.colorT, nx.coerceIn(-1f, 1f) * 0.8f, min(1f, 0.4f + b.r * 6f))
            if (multiply && balls.size + spawned.size < 180) {
                val a = env.rng.range(0f, TWO_PI)
                val sp = hypot(b.vx, b.vy)
                // Queued, not added: bounce() runs inside the loop over balls,
                // and adding to the list there throws.
                spawned.add(
                    Ball(b.x * 0.4f, b.y * 0.4f, cos(a) * sp, sin(a) * sp, b.r * 0.8f, env.rng.nextFloat())
                )
                env.emit(EventKind.BIRTH, env.rng.nextFloat(), 0f, 0.5f)
            }
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        for (ri in ringRadii.indices) {
            val c = palette.at(ri.toFloat() / max(1, rings - 1))
            g.ring(0f, 0f, ringRadii[ri], 0.012f, c, gapWidth, ringAngle[ri], 0.95f)
        }
        for (b in balls) {
            if (!b.alive) continue
            val c = palette.at(b.colorT)
            g.glow(b.x, b.y, b.r * 3.2f, c, 0.55f)
            g.disc(b.x, b.y, b.r, Palette.brighten(c, 0.15f))
        }
        if (payoffFrame) g.flash(0.65f)
    }

    override fun onInput(env: SimEnv, e: TouchEvent) {
        if (e.kind == TouchEvent.Kind.TAP && balls.size < 180) {
            val a = env.rng.range(0f, TWO_PI)
            balls.add(Ball(e.x * 0.5f, e.y * 0.5f, cos(a), sin(a), 0.04f, env.rng.nextFloat()))
            env.emit(EventKind.BIRTH, 0.7f, 0f, 0.8f)
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = restSeconds in -1000f..0f && (escaped > 0 || balls.none { it.alive })

    override fun counters(): List<Pair<String, String>> =
        listOf("bounces" to hits.toString(), "balls" to balls.count { it.alive }.toString())

    companion object {
        private const val TWO_PI = (PI * 2.0).toFloat()
        private const val MAX_SPEED = 7.5f

        fun def(): SimDef = SimDef(
            id = "physics/ball-escape",
            title = "Will it escape?",
            shelf = Shelf.PHYSICS,
            family = "Bouncing & collisions",
            blurb = "Every bounce makes it bigger and faster. One gap out.",
            formats = listOf(TrapFormat.ESCAPE, TrapFormat.SONG, TrapFormat.NUMBER_UP),
            lawKnobs = setOf(LawKnob.GRAVITY, LawKnob.GRAVITY_DIRECTION, LawKnob.BOUNCE,
                LawKnob.FRICTION, LawKnob.CONTACT, LawKnob.LIGHT_SPEED),
            heroLabel = "Add ball",
            heroRestarts = false,
            typicalSeconds = 34f,
            params = listOf(
                Param.int("balls", "Balls", 1, 64, 1, card = Card.DATA),
                Param.float("size", "Ball size", 0.012f, 0.14f, 0.045f, card = Card.DATA),
                // Defaults chosen by sweeping seeds offline: one ring with this
                // gap and speed-up escapes on every seed, around 16 s in.
                // More rings make a longer, less certain run on purpose.
                Param.int("rings", "Rings", 1, 12, 1, card = Card.DATA),
                Param.float("gap", "Gap", 0.12f, 1.6f, 0.55f, card = Card.DATA, unit = "rad"),
                Param.float("speed0", "Start speed", 0.2f, 3f, 0.7f, card = Card.MOTION),
                Param.float("growth", "Growth per hit", 0f, 5f, 1.1f, card = Card.MOTION, unit = "%"),
                Param.float("speedup", "Speed-up per hit", 0f, 5f, 3f, card = Card.MOTION, unit = "%"),
                Param.float("spin", "Ring spin", 0f, 3f, 1f, card = Card.MOTION),
                Param.bool("multiply", "Split on every hit", false, card = Card.MOTION)
            ),
            macros = listOf(
                Macro(
                    "chaos", "Chaos", Card.MOTION,
                    mapOf("speed0" to 0.4f..2.4f, "growth" to 0.2f..3.5f, "speedup" to 0.2f..3f), 0.4f
                )
            ),
            create = { BallEscapeSim() }
        )
    }
}

/**
 * Pendulum wave: lengths are tuned so the whole row falls out of phase and then
 * snaps back into line. The realignment is the payoff and the polyrhythm is free.
 */
class PendulumWaveSim : Sim {

    private var count = 15
    private var angles = FloatArray(0)
    private var omegas = FloatArray(0)
    private var lengths = FloatArray(0)
    private var lastSide = BooleanArray(0)

    /** Which pendulums crossed this step; sounded in order after the slices. */
    private var crossedThisStep = BooleanArray(0)
    private var cyclePeriod = 30f
    private var elapsed = 0f
    private var payoffFrame = false
    private var crossings = 0L
    private var swinging = false
    private var stillSeconds = 0f
    private var aligned = false

    /** Lengths and gravity are scaled together, so the periods are unchanged. */
    private var gravityScale = 1f

    override fun setup(env: SimEnv) {
        val p = env.params
        count = p.i("count", 15).coerceIn(3, MAX_PENDULUMS)
        cyclePeriod = p.f("period", 30f).coerceIn(6f, 120f)
        val baseOscillations = p.f("oscillations", 13f)
        val amplitude = p.f("amplitude", 0.6f)

        angles = FloatArray(count)
        omegas = FloatArray(count)
        lengths = FloatArray(count)
        lastSide = BooleanArray(count)
        crossedThisStep = BooleanArray(count)

        // The wave only comes back into line because the lengths are exactly
        // L = g/w^2 for harmonically related w. Clamping them to fit the screen
        // broke that — most of the "Cycle length" slider collapsed into one
        // length, with nothing to realign. Instead the whole system is scaled:
        // multiplying every length and the gravity they fall under by the same
        // number leaves each period untouched, so it fits the screen and still
        // realigns.
        var longest = 0f
        for (i in 0 until count) {
            val osc = baseOscillations + i
            val period = cyclePeriod / osc
            val w = 2f * PI.toFloat() / period
            val raw = GRAVITY_SIM / (w * w)
            lengths[i] = raw
            if (raw > longest) longest = raw
        }
        gravityScale = if (longest <= 1e-6f) 1f else MAX_LENGTH / longest
        for (i in 0 until count) {
            lengths[i] = lengths[i] * gravityScale
            angles[i] = amplitude
            omegas[i] = 0f
            lastSide[i] = true
        }
        elapsed = 0f
        payoffFrame = false
        aligned = false
        crossings = 0L
        swinging = false
        stillSeconds = 0f
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        val g = GRAVITY_SIM * gravityScale * (env.laws.gravity / 9.81f)
        val damping = 1f - (env.laws.friction * abs(dt) * 0.4f).coerceIn(0f, 0.05f)

        // Each pendulum is independent, so twenty thousand of them split cleanly
        // across cores. Only a sample of them is allowed to make a sound: ten
        // thousand crossings a second is a hiss, not a wave.
        val voiceEvery = maxOf(1, count / VOICES)
        // Counted per slice and summed afterwards: a monitor per crossing inside
        // a parallel loop is both a lock and a boxed allocation in step().
        val crossedPerSlice = java.util.concurrent.atomic.AtomicInteger(0)
        Parallel.forRange(count) { from, to ->
            var localCrossed = 0
            for (i in from until to) {
                val l = lengths[i]
                // Exact pendulum, not the small-angle approximation.
                val acc = -(g / l) * sin(angles[i])
                omegas[i] = (omegas[i] + acc * dt) * damping
                angles[i] += omegas[i] * dt

                val side = angles[i] >= 0f
                if (side != lastSide[i]) {
                    lastSide[i] = side
                    localCrossed++
                    // Marked here, sounded below: emitting from a worker makes
                    // the order of the notes depend on which thread got there
                    // first, and a run has to sound the same every time.
                    if (i % voiceEvery == 0) crossedThisStep[i] = true
                }
            }
            crossedPerSlice.addAndGet(localCrossed)
        }
        crossings += crossedPerSlice.get()

        var voice = 0
        while (voice < count) {
            if (crossedThisStep[voice]) {
                crossedThisStep[voice] = false
                val t = if (count <= 1) 0.5f else voice.toFloat() / (count - 1)
                env.emit(EventKind.CROSS, t, (t * 2f - 1f) * 0.85f, 0.75f)
            }
            voice += voiceEvery
        }

        // Realignment is the payoff, so it is measured rather than timed: a
        // stopwatch fired on a frozen screen under Zero-G, and never at all
        // when time ran backwards.
        //
        // Measuring alignment was not enough on its own. With no gravity every
        // pendulum stays exactly where it started, which is perfect alignment,
        // so the sim declared its payoff — slow motion, a punch-in, a chord and
        // a flash — over a picture that had never moved. A wave that has not
        // swung has not realigned.
        var fastest = 0f
        for (i in 0 until count) {
            val w = abs(omegas[i])
            if (w > fastest) fastest = w
        }
        swinging = fastest > STILL_OMEGA
        if (!swinging) stillSeconds += abs(dt) else stillSeconds = 0f

        if (!aligned && swinging && elapsed > cyclePeriod * 0.25f) {
            var together = true
            val first = angles[0]
            for (i in 1 until count) {
                if (abs(angles[i] - first) > ALIGN_RADIANS) {
                    together = false
                    break
                }
            }
            if (together) {
                aligned = true
                payoffFrame = true
            }
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val spread = env.halfWidth * 0.86f
        val pivotY = 0.92f
        g.line(-spread, pivotY, spread, pivotY, 0.008f, Palette.TEXT_SECONDARY, 0.45f)

        // Past a few hundred, a bob and its string would be thinner than a pixel,
        // so the whole curtain goes through the point path instead.
        if (count > DRAWN_AS_POINTS) {
            g.reservePoints(count)
            val size = (2.6f - count / 12000f).coerceIn(1.2f, 2.6f)
            for (i in 0 until count) {
                val t = i.toFloat() / (count - 1)
                val px = -spread + t * spread * 2f
                val l = lengths[i]
                g.point(
                    px + sin(angles[i]) * l,
                    pivotY - cos(angles[i]) * l,
                    size,
                    palette.at(t)
                )
            }
            if (payoffFrame) g.flash(0.35f)
            return
        }

        for (i in 0 until count) {
            val t = if (count <= 1) 0.5f else i.toFloat() / (count - 1)
            val px = -spread + t * spread * 2f
            val l = lengths[i]
            val bx = px + sin(angles[i]) * l
            val by = pivotY - cos(angles[i]) * l
            val c = palette.at(t)
            g.line(px, pivotY, bx, by, 0.004f, Palette.TEXT_SECONDARY, 0.35f)
            g.glow(bx, by, 0.09f, c, 0.5f)
            g.disc(bx, by, 0.028f, c)
        }
        if (payoffFrame) g.flash(0.35f)
    }

    override fun payoff(): Boolean = payoffFrame

    /**
     * A wave with no gravity to swing it will never do anything. Rather than
     * hanging on a still picture until the person gives up and swipes, the run
     * ends and the counter says why, so the feed moves on by itself.
     */
    override fun finished(): Boolean = stillSeconds > STILL_SECONDS

    override fun counters(): List<Pair<String, String>> = listOf(
        "crossings" to crossings.toString(),
        if (swinging || stillSeconds <= 0f) {
            "cycle" to String.format(java.util.Locale.US, "%.0f%%", (elapsed % cyclePeriod) / cyclePeriod * 100f)
        } else {
            "gravity" to "none — nothing to swing it"
        }
    )

    companion object {
        private const val GRAVITY_SIM = 9.81f

        /** Below this the wave is standing still, not swinging slowly. */
        const val STILL_OMEGA = 0.004f

        /** How long a still picture is allowed to last before the run gives up. */
        const val STILL_SECONDS = 2.5f

        /** How close every pendulum must be for the wave to count as back in line. */
        const val ALIGN_RADIANS = 0.12f

        /** The longest string on screen; every other length scales with it. */
        const val MAX_LENGTH = 1.42f

        /** The curtain can be twenty thousand strings wide. */
        const val MAX_PENDULUMS = 20_000

        /** Above this, bobs and strings become points. */
        const val DRAWN_AS_POINTS = 400

        /** How many of them are allowed to make a sound. */
        const val VOICES = 48

        fun def(): SimDef = SimDef(
            id = "physics/pendulum-wave",
            title = "Pendulum wave",
            shelf = Shelf.PHYSICS,
            family = "Pendulums & oscillators",
            blurb = "Fifteen pendulums drift apart, then snap back into one line.",
            formats = listOf(TrapFormat.LOOP, TrapFormat.SONG),
            lawKnobs = setOf(LawKnob.GRAVITY, LawKnob.FRICTION),
            heroLabel = "Drop again",
            typicalSeconds = 38f,
            params = listOf(
                Param.int("count", "Pendulums", 3, 20000, 15, card = Card.DATA, logScale = true),
                Param.float("period", "Cycle length", 6f, 120f, 30f, card = Card.MOTION, unit = "s"),
                Param.float("oscillations", "Base swings per cycle", 6f, 40f, 13f, card = Card.MOTION, advancedOnly = true),
                Param.float("amplitude", "Swing", 0.1f, 1.2f, 0.6f, card = Card.MOTION, unit = "rad")
            ),
            macros = listOf(
                Macro("chaos", "Chaos", Card.MOTION, mapOf("amplitude" to 0.15f..1.2f), 0.4f)
            ),
            create = { PendulumWaveSim() }
        )
    }
}

/** Newton's cradle: equal masses in a row, momentum passing straight through. */
class NewtonsCradleSim : Sim {

    private var n = 5
    private val pos = FloatArray(16)
    private val vel = FloatArray(16)
    private var radius = 0.075f
    private var payoffFrame = false
    private var clicks = 0L
    private var elapsed = 0f
    private var payoffDone = false
    private var stillSeconds = 0f

    override fun setup(env: SimEnv) {
        elapsed = 0f
        payoffDone = false
        stillSeconds = 0f
        n = env.params.i("count", 5).coerceIn(3, 9)
        radius = (0.62f / n).coerceIn(0.03f, 0.09f)
        val lift = env.params.i("lift", 1).coerceIn(1, n - 1)
        for (i in 0 until n) {
            pos[i] = 0f
            vel[i] = 0f
        }
        for (i in 0 until lift) {
            pos[i] = -env.params.f("swing", 0.55f)
            vel[i] = 0f
        }
        payoffFrame = false
        clicks = 0L
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        val g = 9.81f * (env.laws.gravity / 9.81f)
        // With no gravity the lifted ball never swings down, so there is never
        // a click, never a payoff and never an end. Better to say so and let
        // the feed move on than to hold a still picture indefinitely.
        if (abs(g) < 1e-5f) {
            stillSeconds += abs(dt)
            return
        }
        stillSeconds = 0f
        val l = 1.1f
        val damping = 1f - (env.laws.friction * abs(dt) * 0.1f).coerceIn(0f, 0.02f)

        for (i in 0 until n) {
            val acc = -(g / l) * sin(pos[i])
            vel[i] = (vel[i] + acc * dt) * damping
            pos[i] += vel[i] * dt
        }
        // Collisions along the row: equal masses exchange velocities.
        val spacing = radius * 2f
        for (i in 0 until n - 1) {
            val xi = pos[i] * l
            val xj = pos[i + 1] * l
            val gap = (xj + (i + 1) * spacing) - (xi + i * spacing)
            if (gap < spacing * 0.999f && vel[i] > vel[i + 1]) {
                val e = env.laws.bounce.coerceIn(0f, 1.05f)
                val vi = vel[i]
                val vj = vel[i + 1]
                if (env.laws.sticky) {
                    // Sticky cradle: the balls clump and travel together.
                    val shared = (vi + vj) * 0.5f
                    vel[i] = shared
                    vel[i + 1] = shared
                } else {
                    vel[i] = vj * e
                    vel[i + 1] = vi * e
                }
                clicks++
                env.emit(EventKind.HIT, i.toFloat() / max(1, n - 1), 0f, 0.8f)
                // A handful of clean transfers first, so the payoff is the rhythm
                // being established rather than the very first click.
                if (!payoffDone && elapsed > 9f && clicks % (n.toLong() * 2L) == 0L) {
                    payoffDone = true
                    payoffFrame = true
                }
            }
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val spacing = radius * 2f
        val top = 0.9f
        val l = 1.1f
        val startX = -(n - 1) * spacing * 0.5f
        g.line(startX - 0.1f, top, startX + (n - 1) * spacing + 0.1f, top, 0.008f, Palette.TEXT_SECONDARY, 0.4f)
        for (i in 0 until n) {
            val anchorX = startX + i * spacing
            val bx = anchorX + sin(pos[i]) * l
            val by = top - cos(pos[i]) * l
            val c = palette.at(i.toFloat() / max(1, n - 1))
            g.line(anchorX, top, bx, by, 0.003f, Palette.TEXT_SECONDARY, 0.4f)
            g.glow(bx, by, radius * 2.6f, c, 0.4f)
            g.disc(bx, by, radius, c)
        }
        if (payoffFrame) g.flash(0.28f)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = stillSeconds > STILL_SECONDS

    override fun counters(): List<Pair<String, String>> =
        if (stillSeconds > 0f) listOf("gravity" to "none — nothing swings")
        else listOf("clicks" to clicks.toString())

    companion object {
        /** How long a still cradle is allowed to last before the run gives up. */
        const val STILL_SECONDS = 2.5f

        fun def(): SimDef = SimDef(
            id = "physics/newtons-cradle",
            title = "Newton's cradle",
            shelf = Shelf.PHYSICS,
            family = "Pendulums & oscillators",
            blurb = "Momentum passes straight through the middle.",
            formats = listOf(TrapFormat.CHAIN),
            lawKnobs = setOf(LawKnob.GRAVITY, LawKnob.BOUNCE, LawKnob.FRICTION, LawKnob.CONTACT),
            heroLabel = "Lift and drop",
            typicalSeconds = 20f,
            params = listOf(
                Param.int("count", "Balls", 3, 9, 5, card = Card.DATA),
                Param.int("lift", "Lifted", 1, 4, 1, card = Card.DATA),
                Param.float("swing", "Swing", 0.1f, 1f, 0.55f, card = Card.MOTION, unit = "rad")
            ),
            macros = listOf(
                Macro(
                    "cradle", "How many", Card.DATA,
                    mapOf("count" to 3f..9f, "lift" to 1f..4f), 0.25f
                )
            ),
            create = { NewtonsCradleSim() }
        )
    }
}

/** A Galton board: beads fall through pegs and pile into a bell curve. */
class GaltonBoardSim : Sim {

    private class Bead(
        var x: Float,
        var y: Float,
        var vy: Float,
        var bin: Int,
        var colorT: Float,
        var settled: Boolean
    ) {
        /** Which peg row it has passed, so each one can be heard and seen. */
        var row = 0

        /** Decays after a peg, for the visual knock. */
        var bounce = 0f
    }

    private val beads = ArrayList<Bead>()
    private var bins = IntArray(21)
    private var rows = 10

    /** Height of one bead in the pile, scaled so a full board fits on screen. */
    private var pileStep = 0.012f

    /** Only a sample of peg knocks becomes a note. */
    private var pegVoice = 0
    private var pegVoiceEvery = 1
    private var dropped = 0
    private var total = 400
    private var dropTimer = 0f
    private var payoffFrame = false
    private var peak = 1
    private var restSeconds = -1f
    private var curveDone = false
    private var stillSeconds = 0f

    override fun setup(env: SimEnv) {
        val p = env.params
        stillSeconds = 0f
        rows = p.i("rows", 10).coerceIn(4, 20)
        total = p.i("beads", 400).coerceIn(10, 20000)
        bins = IntArray(rows + 1)
        beads.clear()
        dropped = 0
        dropTimer = 0f
        payoffFrame = false
        peak = 1
        restSeconds = -1f
        curveDone = false
        // A tall board with twenty thousand beads would pile far off screen,
        // and twenty thousand knocks a second is a hiss, so both are scaled.
        val expectedPerBin = max(1f, total.toFloat() / max(1, rows + 1))
        pileStep = (0.5f / expectedPerBin).coerceIn(0.0012f, 0.02f)
        pegVoice = 0
        pegVoiceEvery = max(1, total / 400)
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        if (restSeconds >= 0f) {
            restSeconds -= abs(dt)
            return
        }
        val rate = env.params.f("rate", 30f)
        // abs: with time reversed this ran the timer away from the threshold
        // instead of towards it, so not one bead was ever released and the
        // board sat empty with no events and no ending.
        dropTimer += abs(dt) * rate
        while (dropTimer >= 1f && dropped < total) {
            dropTimer -= 1f
            var bin = 0
            for (r in 0 until rows) if (env.rng.bool()) bin++
            beads.add(Bead(0f, 0.95f, 0f, bin, env.rng.nextFloat(), false))
            dropped++
        }

        val targetSpacing = (1.7f * env.halfWidth) / (rows + 1)
        val g = 1.6f * (env.laws.gravity / 9.81f)
        // Nothing falls without gravity, and nothing ever settles, so the run
        // would never finish and the feed would never move on.
        if (abs(g) < 1e-5f) {
            stillSeconds += abs(dt)
            return
        }
        stillSeconds = 0f
        var active = 0
        for (b in beads) {
            if (b.settled) continue
            active++
            b.vy -= g * dt
            b.y += b.vy * dt
            // Drift sideways towards the chosen bin as it falls, one peg row at
            // a time: a bead that slid along a straight line never touched a peg,
            // which is the whole picture the board is meant to show.
            val targetX = (b.bin - rows * 0.5f) * targetSpacing
            val fall = ((0.95f - b.y) / 1.5f).coerceIn(0f, 1f)
            val rowNow = (fall * rows).toInt().coerceIn(0, rows)
            b.x = targetX * (rowNow.toFloat() / rows)
            if (rowNow > b.row) {
                b.row = rowNow
                b.bounce = 1f
                if (pegVoice++ % pegVoiceEvery == 0) {
                    env.emit(
                        EventKind.STEP,
                        (rowNow.toFloat() / rows).coerceIn(0f, 1f),
                        b.x.coerceIn(-1f, 1f),
                        0.22f
                    )
                }
            }
            b.bounce = max(0f, b.bounce - abs(dt) * 6f)
            // The pile is scaled so a full board still fits on screen.
            val floor = -0.78f + bins[b.bin] * pileStep
            if (b.y <= floor) {
                b.y = floor
                b.settled = true
                bins[b.bin]++
                if (bins[b.bin] > peak) peak = bins[b.bin]
                env.emit(EventKind.HIT, b.bin.toFloat() / rows, (b.x).coerceIn(-1f, 1f), 0.5f)
            }
        }
        // The payoff is the finished bell curve. Hold it on screen for a moment
        // before the run resolves, or nobody ever sees the shape they waited for.
        if (dropped >= total && active == 0 && !curveDone) {
            curveDone = true
            payoffFrame = true
            restSeconds = 2f
            env.emit(EventKind.DONE, 0.8f, 0f, 1f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val spacing = (1.7f * env.halfWidth) / (rows + 1)
        // Pegs
        for (r in 0 until rows) {
            val y = 0.82f - r * (1.5f / rows)
            for (c in 0..r) {
                val x = (c - r * 0.5f) * spacing
                g.disc(x, y, 0.006f, Palette.TEXT_SECONDARY, 0.5f)
            }
        }
        for (b in beads) {
            val c = palette.at(b.colorT)
            // A bead that has just passed a peg flashes and spreads, so the
            // knock is seen as well as heard.
            if (b.bounce > 0.01f) {
                g.glow(b.x, b.y, 0.03f + b.bounce * 0.05f, Palette.brighten(c, 0.3f), b.bounce * 0.7f)
            }
            g.disc(b.x, b.y, 0.012f + b.bounce * 0.004f, c)
        }
        // The curve the bins are making.
        for (i in bins.indices) {
            if (bins[i] == 0) continue
            val x = (i - rows * 0.5f) * spacing
            val h = (bins[i].toFloat() / max(1, peak)) * 0.5f
            g.bar(x, -0.95f, spacing * 0.42f, h, Palette.withAlpha(palette.at(i.toFloat() / rows), 0.45f))
        }
        if (payoffFrame) g.flash(0.3f)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean =
        (curveDone && restSeconds in -1000f..0f) || stillSeconds > STILL_SECONDS

    override fun counters(): List<Pair<String, String>> =
        if (stillSeconds > 0f) {
            listOf("beads" to dropped.toString(), "gravity" to "none — nothing falls")
        } else {
            listOf("beads" to dropped.toString(), "tallest bin" to peak.toString())
        }

    companion object {
        /** How long a still board is allowed to last before the run gives up. */
        const val STILL_SECONDS = 2.5f

        fun def(): SimDef = SimDef(
            id = "physics/galton-board",
            title = "Galton board",
            shelf = Shelf.PHYSICS,
            family = "Bouncing & collisions",
            blurb = "Random beads, every time the same bell curve.",
            formats = listOf(TrapFormat.FILL, TrapFormat.GROW),
            lawKnobs = setOf(LawKnob.GRAVITY),
            heroLabel = "Drop more",
            typicalSeconds = 24f,
            params = listOf(
                Param.int("rows", "Peg rows", 4, 20, 10, card = Card.DATA),
                Param.int("beads", "Beads", 10, 20000, 400, card = Card.DATA, logScale = true),
                // Reaches what the bead slider offers: twenty thousand beads at the
                // shipped thirty a second is eleven minutes of waiting.
                Param.float("rate", "Drop rate", 1f, 4000f, 30f, card = Card.MOTION, unit = "/s", logScale = true)
            ),
            create = { GaltonBoardSim() }
        )
    }
}

object PhysicsSims {
    fun defs(): List<SimDef> = listOf(
        BallEscapeSim.def(),
        PendulumWaveSim.def(),
        NewtonsCradleSim.def(),
        GaltonBoardSim.def()
    )
}
