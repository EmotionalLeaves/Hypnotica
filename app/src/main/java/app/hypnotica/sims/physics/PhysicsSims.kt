package app.hypnotica.sims.physics

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Macro
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.recipe.ParamInput
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
    ) {
        /** Through the outer gap and flying free; drawn until it leaves the screen. */
        var out = false

        /** Recent positions, a ring buffer, for the short motion trail. */
        val trail = FloatArray(TRAIL * 2)
        var head = 0
        var filled = 0

        fun record() {
            trail[head * 2] = x; trail[head * 2 + 1] = y
            head = (head + 1) % TRAIL
            if (filled < TRAIL) filled++
        }
    }

    /** The burst where a ball broke out: where, which colour, and how far it has spread (0..1). */
    private var burstX = 0f
    private var burstY = 0f
    private var burstT = 0f
    private var burstAge = 2f

    private val balls = ArrayList<Ball>()

    /** Where the last few hits landed on a wall, and how old each is, for the spark there. */
    private val sparkX = FloatArray(SPARKS)
    private val sparkY = FloatArray(SPARKS)
    private val sparkT = FloatArray(SPARKS)
    private val sparkAge = FloatArray(SPARKS) { SPARK_SECONDS }
    private var sparkNext = 0

    private fun spark(x: Float, y: Float, t: Float) {
        sparkX[sparkNext] = x; sparkY[sparkNext] = y; sparkT[sparkNext] = t; sparkAge[sparkNext] = 0f
        sparkNext = (sparkNext + 1) % SPARKS
    }

    /** Balls born this step, appended once the loop over [balls] has finished. */
    private val spawned = ArrayList<Ball>()
    private var ringRadius = 0.88f
    private var rings = 1
    private var ringRadii = FloatArray(1)
    private var ringAngle = FloatArray(1)
    private var ringSpin = FloatArray(1)
    private var gapWidth = 0.5f
    /** 0 for round rings, else the number of sides of each polygon. */
    private var sides = 0

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
    private var runSeconds = 0f

    override fun setup(env: SimEnv) {
        val p = env.params
        balls.clear()
        spawned.clear()
        rings = p.i("rings", 1).coerceIn(1, 12)
        sides = SHAPE_SIDES[p.choiceIndex("shape").coerceIn(0, SHAPE_SIDES.size - 1)]
        gapWidth = p.f("gap", 0.42f)
        growth = 1f + p.f("growth", 2.5f) * 0.01f
        speedUp = 1f + p.f("speedup", 3f) * 0.01f
        multiply = p.b("multiply", false)
        ringRadius = 0.92f

        ringRadii = FloatArray(rings) { i -> ringRadius - i * (ringRadius * 0.62f / rings) }
        ringAngle = FloatArray(rings) { i -> env.rng.range(0f, 2f * PI.toFloat()) }
        ringSpin = FloatArray(rings) { i ->
            env.rng.range(0.25f, 0.85f) * (if (env.rng.bool()) 1f else -1f) * p.f("spin", 1f)
        }

        val count = p.i("balls", 1).coerceIn(1, 64)
        val speed = p.f("speed0", 1.2f)
        energy = speed
        // The outer gap starts overhead, away from where gravity drops the
        // ball. Started anywhere, it was under the ball one run in ten and the
        // ball fell out before its first or second bounce, before anything
        // had built.
        val up = atan2(env.laws.gravityDirY, -env.laws.gravityDirX)
        ringAngle[0] = up - gapWidth * 0.5f
        repeat(count) {
            val a = env.rng.range(0f, 2f * PI.toFloat())
            balls.add(
                Ball(
                    env.rng.range(-0.08f, 0.08f), env.rng.range(-0.08f, 0.08f),
                    cos(a) * speed, sin(a) * speed,
                    p.f("size", 0.06f), env.rng.nextFloat()
                )
            )
        }
        escaped = 0
        hits = 0L
        payoffFrame = false
        restSeconds = -1f
        runSeconds = 0f
        burstAge = 2f
        sparkAge.fill(SPARK_SECONDS)
        sparkNext = 0
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        runSeconds += abs(dt)
        burstAge += abs(dt)
        for (i in 0 until SPARKS) sparkAge[i] += abs(dt)
        // An escaped ball used to vanish the instant it crossed the ring, so
        // the payoff was an empty circle. It now sails on out of the frame.
        for (b in balls) {
            if (!b.out) continue
            b.x += b.vx * abs(dt)
            b.y += b.vy * abs(dt)
            b.record()
        }
        if (restSeconds >= 0f) {
            restSeconds -= abs(dt)
            return
        }
        val laws = env.laws
        // At a start speed of 0.7 the ball spent seventy per cent of a run on
        // slow hops that never reached a wall, three silent seconds at a time.
        // It starts at 1.2 now, with a narrower gap to keep the build.
        val gx = laws.gravityDirX * laws.gravity * GRAVITY_SCALE
        val gy = -laws.gravityDirY * laws.gravity * GRAVITY_SCALE

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

            // Forward in time whichever way the clock runs, and in steps
            // shorter than the ball is wide: a small fast ball went clean
            // through the outer ring in one stride, and with time reversed it
            // moved against its own velocity, which no wall stops.
            val h = abs(dt)
            val stride = hypot(b.vx, b.vy) * h
            val subs = kotlin.math.ceil(stride / max(1e-4f, b.r * 0.5f)).toInt().coerceIn(1, 24)
            for (sub in 0 until subs) {
                if (!b.alive) break
                b.x += b.vx * h / subs
                b.y += b.vy * h / subs
                collideRings(env, b)
            }
            b.record()
        }

        if (balls.none { it.alive } && restSeconds < 0f) restSeconds = 1.2f

        if (spawned.isNotEmpty()) {
            balls.addAll(spawned)
            spawned.clear()
        }
    }

    /**
     * The gap as it stands now: shut for the first few seconds, opening, and
     * widening if the run has gone on long.
     *
     * Open from the first frame, a ball launched towards it left at a second
     * with no bounces at all, and on an unlucky seed a ball could rattle for a
     * minute and never find it — measured, escapes ran from 1 s to never
     * across six seeds. Shut first, every run has its build; widening late,
     * every run has its ending.
     */
    private fun gapNow(): Float {
        val open = ((runSeconds - GAP_SEALED) / GAP_OPENING).coerceIn(0f, 1f)
        val eased = open * open * (3f - 2f * open)
        val wider = ((runSeconds - GAP_WIDEN_AFTER) / GAP_WIDENING).coerceIn(0f, 1f)
        return (gapWidth * eased * (1f + 2f * wider)).coerceAtMost(PI.toFloat())
    }

    private fun collideRings(env: SimEnv, b: Ball) {
            // Rings are checked outside in; the ball normally sits inside the
            // smallest one and works its way out through the gaps.
            for (ri in ringRadii.indices) {
                var rr = ringRadii[ri]
                var d = hypot(b.x, b.y)
                // A polygon: the side the ball is facing stands in for the
                // ring — its distance along that side's normal for the ball's
                // distance, the polygon's inner radius for the ring's.
                var nx = Float.NaN
                var ny = Float.NaN
                if (sides >= 3) {
                    val seg = TWO_PI / sides
                    var rel0 = atan2(b.y, b.x) - ringAngle[ri]
                    rel0 = ((rel0 % TWO_PI) + TWO_PI) % TWO_PI
                    val nAng = ringAngle[ri] + (kotlin.math.floor(rel0 / seg) + 0.5f) * seg
                    nx = cos(nAng); ny = sin(nAng)
                    d = b.x * nx + b.y * ny
                    rr *= cos(PI.toFloat() / sides)
                }
                if (d + b.r < rr) continue
                // Which part of the ring the ball is at: the gap, or wall.
                val angNow = atan2(b.y, b.x)
                var relNow = angNow - ringAngle[ri]
                relNow = ((relNow % TWO_PI) + TWO_PI) % TWO_PI
                val atGap = relNow < gapNow()
                if (ri > 0 && d >= rr) {
                    // Outside an inner ring, already through it: its wall
                    // stops the ball from this side too, or a bounce off the
                    // next ring out shoved it back through solid wall.
                    if (d - b.r < rr && !atGap) bounceOutside(env, b, rr, d, nx, ny)
                    continue
                }
                if (d < rr) {
                    // Touching from inside.
                    val ang = atan2(b.y, b.x)
                    var rel = ang - ringAngle[ri]
                    rel = ((rel % TWO_PI) + TWO_PI) % TWO_PI
                    if (rel < gapNow()) {
                        // Through the gap: this ring is spent for this ball.
                        continue
                    }
                    bounce(env, b, rr, d, nx, ny)
                    break
                } else if (ri == 0) {
                    // Outside the outer ring: escaped.
                    b.alive = false
                    b.out = true
                    if (escaped == 0) {
                        burstX = b.x; burstY = b.y; burstT = b.colorT; burstAge = 0f
                    }
                    escaped++
                    env.emit(EventKind.DONE, b.colorT, (b.x).coerceIn(-1f, 1f), 1f)
                    payoffFrame = true
                    restSeconds = 1.5f
                    break
                }
            }
    }

    /** Off the outside of an inner ring: the same bounce, the normal turned outward. */
    private fun bounceOutside(env: SimEnv, b: Ball, rr: Float, d: Float, nxIn: Float, nyIn: Float) {
        if (env.laws.passThrough) return
        val nx = if (!nxIn.isNaN()) nxIn else if (d > 1e-6f) b.x / d else 1f
        val ny = if (!nyIn.isNaN()) nyIn else if (d > 1e-6f) b.y / d else 0f
        val overlap = rr - (d - b.r)
        if (overlap > 0f) { b.x += nx * overlap * 1.02f; b.y += ny * overlap * 1.02f }
        val dot = b.vx * nx + b.vy * ny
        if (dot < 0f) {
            val e = if (env.laws.sticky) 0f else env.laws.bounce
            b.vx -= (1f + e) * dot * nx
            b.vy -= (1f + e) * dot * ny
            hits++
            env.emit(EventKind.HIT, b.colorT, nx.coerceIn(-1f, 1f) * 0.8f, min(1f, 0.3f + b.r * 6f))
        }
    }

    private fun bounce(env: SimEnv, b: Ball, rr: Float, d: Float, nxIn: Float = Float.NaN, nyIn: Float = Float.NaN) {
        // Ghosts: the rings are there to be looked at, not to stop anything.
        if (env.laws.passThrough) return
        val nx = if (!nxIn.isNaN()) nxIn else if (d > 1e-6f) b.x / d else 1f
        val ny = if (!nyIn.isNaN()) nyIn else if (d > 1e-6f) b.y / d else 0f
        val dot = b.vx * nx + b.vy * ny
        if (dot > 0f) {
            // Sticky: the ball gives up rather than bouncing — but is still
            // put back inside, or the wall it stuck to let it through.
            if (env.laws.sticky) {
                b.vx *= 0.12f
                b.vy *= 0.12f
                val over = (d + b.r) - rr
                if (over > 0f) { b.x -= nx * over * 1.02f; b.y -= ny * over * 1.02f }
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
            // Never bigger than fits between two rings, or it couldn't sit
            // in the space a gap lets it into.
            val spacing = if (ringRadii.size > 1) ringRadii[0] - ringRadii[1] else rr
            b.r = min(b.r * growth, min(rr * 0.42f, spacing * 0.45f))
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
            spark(b.x + nx * b.r, b.y + ny * b.r, b.colorT)
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
        val gap = gapNow()
        // The wall thickens and brightens as the hits pile up, so the build
        // reads even in a still frame: a thin ring was a lone dot in a hoop.
        val charge = (hits / 40f).coerceIn(0f, 1f)
        val wall = 0.02f + 0.012f * charge
        for (ri in ringRadii.indices) {
            val c = palette.at(ri.toFloat() / max(1, rings - 1))
            val r = ringRadii[ri]
            if (sides < 3) {
                // A faint floor inside, and the closed track under the wall,
                // so a gap opening reads as a door in it, not a missing piece.
                if (ri == 0) g.disc(0f, 0f, r, c, 0.045f)
                g.ring(0f, 0f, r, wall * 0.5f, c, alpha = 0.14f)
                g.ring(0f, 0f, r + wall, wall * 2.6f, c, gap, ringAngle[ri], 0.1f + 0.08f * charge)
                g.ring(0f, 0f, r, wall, c, gap, ringAngle[ri], 0.95f)
            } else {
                // Each side in short pieces, leaving out the ones in the gap.
                val seg = TWO_PI / sides
                for (e in 0 until sides) for (k in 0 until EDGE_PIECES) {
                    val a0 = ringAngle[ri] + e * seg
                    val a1 = a0 + seg
                    val t0 = k / EDGE_PIECES.toFloat(); val t1 = (k + 1) / EDGE_PIECES.toFloat()
                    val x0 = cos(a0) + (cos(a1) - cos(a0)) * t0; val y0 = sin(a0) + (sin(a1) - sin(a0)) * t0
                    val x1 = cos(a0) + (cos(a1) - cos(a0)) * t1; val y1 = sin(a0) + (sin(a1) - sin(a0)) * t1
                    var rel = atan2((y0 + y1) * 0.5f, (x0 + x1) * 0.5f) - ringAngle[ri]
                    rel = ((rel % TWO_PI) + TWO_PI) % TWO_PI
                    if (rel < gap) {
                        g.line(x0 * r, y0 * r, x1 * r, y1 * r, wall * 0.5f, c, 0.14f)
                        continue
                    }
                    g.line(x0 * r, y0 * r, x1 * r, y1 * r, wall * 2.6f, c, 0.1f + 0.08f * charge)
                    g.line(x0 * r, y0 * r, x1 * r, y1 * r, wall, c, 0.95f)
                }
            }
            // Posts either side of the gap, lit once it is open.
            if (gap > 0.01f && sides < 3) {
                val post = Palette.brighten(c, 0.35f)
                for (a in floatArrayOf(ringAngle[ri], ringAngle[ri] + gap)) {
                    val px = cos(a) * r; val py = sin(a) * r
                    g.glow(px, py, 0.07f, post, 0.5f)
                    g.disc(px, py, wall * 0.9f, post)
                }
            }
        }
        // A spark where each recent hit landed.
        for (i in 0 until SPARKS) {
            val age = sparkAge[i]
            if (age >= SPARK_SECONDS) continue
            val k = age / SPARK_SECONDS
            val c = Palette.brighten(palette.at(sparkT[i]), 0.3f)
            g.glow(sparkX[i], sparkY[i], 0.05f + 0.1f * k, c, (1f - k) * 0.6f)
            g.ring(sparkX[i], sparkY[i], 0.015f + 0.07f * k, 0.004f, c, alpha = (1f - k) * 0.8f)
        }
        if (burstAge < BURST_SECONDS) {
            val k = burstAge / BURST_SECONDS
            val c = Palette.brighten(palette.at(burstT), 0.3f)
            val ease = 1f - (1f - k) * (1f - k)
            g.ring(burstX, burstY, 0.05f + 0.4f * ease, 0.008f, c, alpha = (1f - k) * 0.9f)
            g.glow(burstX, burstY, 0.12f + 0.25f * ease, c, (1f - k) * 0.6f)
        }
        // Trails only while there are few balls; a split storm of 180 would
        // otherwise be a thousand-odd ghost segments.
        val trails = balls.size <= 24
        for (b in balls) {
            if (!b.alive && !b.out) continue
            // Gone once it crosses the side of the frame (the frame is fitted
            // to the ring's width) or well past the top or bottom.
            if (b.out && (abs(b.x) + b.r > max(env.halfWidth, ringRadius + 0.04f) || abs(b.y) > 2.2f)) continue
            val c = palette.at(b.colorT)
            if (trails && b.filled > 1) {
                // A tapering comet tail, oldest first so the newest sits on top.
                var px = Float.NaN; var py = 0f
                for (j in 0 until b.filled) {
                    val idx = (b.head - b.filled + j + TRAIL * 2) % TRAIL
                    val qx = b.trail[idx * 2]; val qy = b.trail[idx * 2 + 1]
                    val f = (j + 1f) / b.filled
                    if (!px.isNaN()) g.line(px, py, qx, qy, b.r * 1.9f * f, c, 0.04f + 0.4f * f * f)
                    px = qx; py = qy
                }
            }
            g.glow(b.x, b.y, b.r * 4.5f, c, 0.6f)
            g.disc(b.x, b.y, b.r, Palette.brighten(c, 0.2f))
            g.disc(b.x - b.r * 0.3f, b.y + b.r * 0.3f, b.r * 0.35f, Palette.brighten(c, 0.7f), 0.7f)
        }
    }

    override fun onInput(env: SimEnv, e: TouchEvent) {
        if (e.kind == TouchEvent.Kind.TAP) addBall(env, e.x * 0.5f, e.y * 0.5f)
    }

    /** "Add ball": near the middle, where a new ball has room to bounce. */
    override fun hero(env: SimEnv) {
        addBall(env, env.rng.range(-0.15f, 0.15f), env.rng.range(-0.15f, 0.15f))
    }

    private fun addBall(env: SimEnv, x: Float, y: Float) {
        if (balls.size >= 180) return
        val a = env.rng.range(0f, TWO_PI)
        balls.add(Ball(x, y, cos(a), sin(a), 0.04f, env.rng.nextFloat()))
        env.emit(EventKind.BIRTH, 0.7f, 0f, 0.8f)
    }

    /** Drawn at a fixed size; on a phone held upright the view pulls back to fit it. */
    override fun frameHalfWidth(env: SimEnv): Float = ringRadius + 0.04f

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = (restSeconds in -1000f..0f && (escaped > 0 || balls.none { it.alive })) ||
        // A ball that can't find the way out — a syrupy universe, three rings
        // — still lets the next run come.
        runSeconds > GIVE_UP

    override fun counters(): List<Pair<String, String>> =
        listOf("bounces" to hits.toString(), "balls" to balls.count { it.alive }.toString())

    companion object {
        private const val TWO_PI = (PI * 2.0).toFloat()
        private const val TRAIL = 30
        private const val SPARKS = 8
        private const val SPARK_SECONDS = 0.45f
        private const val BURST_SECONDS = 0.9f
        private const val MAX_SPEED = 7.5f
        private const val GIVE_UP = 60f

        /** Seconds the gap stays shut, so every run starts with a build. */
        const val GAP_SEALED = 3f

        /** Seconds it takes to open. */
        const val GAP_OPENING = 1.5f

        /** After this the gap starts to widen, so a run cannot go on for ever. */
        const val GAP_WIDEN_AFTER = 7f

        /** Seconds to reach three times its width. */
        const val GAP_WIDENING = 6f
        /** Sides for each Shape choice; 0 is a round ring. */
        private val SHAPE_SIDES = intArrayOf(0, 3, 4, 6)
        private const val EDGE_PIECES = 10
        private const val GRAVITY_SCALE = 0.06f

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
            typicalSeconds = 11f,
            params = listOf(
                Param.choice("shape", "Shape", listOf("Ring", "Triangle", "Square", "Hexagon"), 0,
                    card = Card.DATA, input = ParamInput.TILES),
                Param.int("balls", "Balls", 1, 64, 1, card = Card.DATA),
                // 0.045 read as a lone dot in a big hoop on a phone; escape timing
                // across seeds is unchanged at 0.06 (measured 0.8–1.0 of typical).
                Param.float("size", "Ball size", 0.012f, 0.14f, 0.06f, card = Card.DATA),
                // Defaults chosen by sweeping seeds offline: one ring with this
                // gap and speed-up escapes on every seed, around 16 s in.
                // More rings make a longer, less certain run on purpose.
                Param.int("rings", "Rings", 1, 12, 1, card = Card.DATA),
                Param.float("gap", "Gap", 0.12f, 1.6f, 0.42f, card = Card.DATA, unit = "rad"),
                Param.float("speed0", "Start speed", 0.2f, 3f, 1.2f, card = Card.MOTION),
                // 1.1% a hit grew the ball a tenth over a whole run: the "bigger every
                // bounce" the blurb promises could not be seen.
                Param.float("growth", "Growth per hit", 0f, 5f, 2.5f, card = Card.MOTION, unit = "%"),
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

    /** The cycle as the pendulums really keep it, under this world's gravity and swing. */
    override fun loopSeconds(env: SimEnv): Float = realCycle(env)

    private var count = 15
    private var angles = FloatArray(0)
    private var omegas = FloatArray(0)
    private var lengths = FloatArray(0)
    private var lastSide = BooleanArray(0)

    /** Which pendulums crossed this step; sounded in order after the slices. */
    private var crossedThisStep = BooleanArray(0)
    private var curveX = FloatArray(0)
    private var curveY = FloatArray(0)
    private var cyclePeriod = 30f
    private var swingAmplitude = 0.6f
    private var cycleNow = 30f
    /** A pendulum's period at this swing over its small-swing period. */
    private var largeSwing = 1f

    /** 2K(k)/π with k = sin(θ₀/2), K by the arithmetic–geometric mean. */
    private fun periodFactor(theta0: Float): Float {
        val k = sin(theta0 / 2.0).let { abs(it) }.coerceAtMost(0.999999)
        var a = 1.0; var b = sqrt(1.0 - k * k)
        repeat(12) { val an = (a + b) / 2; b = sqrt(a * b); a = an }
        // K = π / (2·agm), so 2K/π = 1/agm.
        return (1.0 / a).toFloat()
    }

    /** The cycle as it really runs: a weaker gravity than Earth's stretches every period. */
    private fun realCycle(env: SimEnv): Float =
        cyclePeriod * sqrt(9.81f / max(1e-3f, abs(env.laws.gravity)))
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
        swingAmplitude = abs(amplitude)
        largeSwing = periodFactor(swingAmplitude)

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
            // Shortened for the swing: a pendulum let go at θ₀ is slower than
            // the small-angle formula says, by 2K(sin(θ₀/2))/π — at the
            // default swing the whole wave realigned 0.7 s late.
            val raw = GRAVITY_SIM / (w * w) / (largeSwing * largeSwing)
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

        cycleNow = realCycle(env)
        if (!aligned && swinging && elapsed > cycleNow * 0.25f) {
            var together = true
            val first = angles[0]
            for (i in 1 until count) {
                // Relative to the swing: a fixed tenth of a radian counted a
                // small swing as aligned all the time.
                if (abs(angles[i] - first) > ALIGN_SHARE * swingAmplitude) {
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

    /**
     * Seen from above: one row per pendulum, top to bottom, each bob swinging
     * left and right from a centre line. That is the view the pendulum wave is
     * known by — the rows drift into a snake, braid, and snap back into a
     * single line.
     *
     * It was drawn from the front, every pendulum hanging from one bar with its
     * string, and on a phone held upright the bar and the swing were three
     * times wider than the screen: the ends were cut off, and fitting the
     * whole of it left a small picture in the middle of a tall screen.
     */
    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val reach = env.halfWidth * 0.86f / kotlin.math.sin(MAX_SWING)
        val top = 0.86f
        g.line(0f, top + 0.04f, 0f, -top - 0.04f, 0.004f, Palette.TEXT_SECONDARY, 0.3f)

        // Past a few hundred a bob would be thinner than a pixel, so the whole
        // curtain goes through the point path instead.
        if (count > DRAWN_AS_POINTS) {
            g.reservePoints(count)
            // In world units, as every point size is. It was 1.2 to 2.6 — taken
            // for pixels — which the renderer clamps to its largest dot, so a
            // curtain of thousands drew as a solid wall of overlapping blobs.
            val size = (top * 2f / count * 1.6f).coerceIn(0.003f, 0.012f)
            for (i in 0 until count) {
                val t = i.toFloat() / (count - 1)
                g.point(sin(angles[i]) * reach, top - t * top * 2f, size, palette.at(t))
            }
            return
        }

        val rowGap = if (count <= 1) 0f else top * 2f / (count - 1)
        val bob = (rowGap * 0.36f).coerceIn(0.008f, 0.034f)
        // A thread through the bobs, so the snake, the braid and the snap back
        // into one line read as a shape, not as loose dots. It brightens as the
        // row comes together, which makes the realignment land visibly.
        if (count >= 3) {
            if (curveX.size < count) { curveX = FloatArray(count); curveY = FloatArray(count) }
            var spread = 0f
            for (i in 0 until count) {
                curveX[i] = sin(angles[i]) * reach
                curveY[i] = top - i.toFloat() / (count - 1) * top * 2f
                spread = max(spread, abs(angles[i] - angles[0]))
            }
            val together = (1f - spread / max(1e-3f, 2f * swingAmplitude)).coerceIn(0f, 1f)
            val k = together * together * together * together
            g.polyline(curveX, curveY, count, 0.003f + 0.003f * k, Palette.TEXT_PRIMARY, 0.1f + 0.45f * k)
        }
        for (i in 0 until count) {
            val t = if (count <= 1) 0.5f else i.toFloat() / (count - 1)
            val y = if (count <= 1) 0f else top - t * top * 2f
            val bx = sin(angles[i]) * reach
            val c = palette.at(t)
            g.line(0f, y, bx, y, 0.003f, Palette.TEXT_SECONDARY, 0.3f)
            g.glow(bx, y, bob * 3.2f, c, 0.5f)
            g.disc(bx, y, bob, c)
        }
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
            "cycle" to String.format(java.util.Locale.US, "%.0f%%", (elapsed % cycleNow) / cycleNow * 100f)
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
        /** Every pendulum within this share of the swing of the first: realigned. */
        const val ALIGN_SHARE = 0.2f

        /** The longest string on screen; every other length scales with it. */
        const val MAX_LENGTH = 1.42f

        /** The curtain can be twenty thousand strings wide. */
        const val MAX_PENDULUMS = 20_000

        /** Above this, bobs and strings become points. */
        const val DRAWN_AS_POINTS = 400
        /** The Swing slider's top, which reaches the edge of the screen. */
        private const val MAX_SWING = 1.2f

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
            loopSeconds = 30f,
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
/** When the cradle pays off whether or not its clicks have kept a round count. */
private const val CRADLE_LATE = 13f

class NewtonsCradleSim : Sim {

    private var n = 5
    private val pos = FloatArray(16)
    private val vel = FloatArray(16)
    /** A spark at each contact, lit by a click and fading. */
    private val spark = FloatArray(16)
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
        radius = (0.62f / n).coerceIn(0.03f, 0.1f)
        val lift = env.params.i("lift", 1).coerceIn(1, n - 1)
        for (i in 0 until n) {
            pos[i] = 0f
            vel[i] = 0f
            spark[i] = 0f
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
        val l = STRING
        val damping = 1f - (env.laws.friction * abs(dt) * 0.1f).coerceIn(0f, 0.02f)
        for (i in 0 until n) spark[i] = max(0f, spark[i] - abs(dt) * 7f)

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
                // The world's bounce is a rubber-ball 0.88 by default. Hardened
                // steel is nearer 0.97, and at 0.88 each click through the row
                // left a remainder on every ball: by the third swing all five
                // swung together and the one-out-one-out the cradle is known by
                // was gone before the payoff. The slider still moves it.
                val e = (1f - (1f - env.laws.bounce) * 0.25f).coerceIn(0f, 1.05f)
                val vi = vel[i]
                val vj = vel[i + 1]
                if (env.laws.sticky) {
                    // Sticky cradle: the balls clump and travel together.
                    val shared = (vi + vj) * 0.5f
                    vel[i] = shared
                    vel[i + 1] = shared
                } else {
                    // Equal balls, restitution e: momentum is kept exactly and
                    // only the bounce loses energy. Swapping the speeds and
                    // scaling both by e lost 12% of the momentum at every
                    // click; this way a less bouncy cradle ends, as a real one
                    // does, with the balls swinging together.
                    val share = (1f + e) * 0.5f * (vi - vj)
                    vel[i] = vi - share
                    vel[i + 1] = vj + share
                }
                clicks++
                spark[i] = min(1f, spark[i] + abs(vi - vj) * 1.5f)
                env.emit(EventKind.HIT, i.toFloat() / max(1, n - 1), 0f, 0.8f)
                // A handful of clean transfers first, so the payoff is the rhythm
                // being established rather than the very first click.
                if (!payoffDone && elapsed > 9f && clicks % (n.toLong() * 2L) == 0L) {
                    payoffDone = true
                    payoffFrame = true
                }
            }
        }
        // A sticky cradle clumps and its clicks die away before the count
        // lands on a whole round, so it never paid off at all. Past the
        // rhythm's window, the beat comes anyway.
        if (!payoffDone && elapsed > CRADLE_LATE) {
            payoffDone = true
            payoffFrame = true
        }
    }

    /**
     * The row, plus the furthest a ball can swing out at either end: with
     * momentum kept, the end balls go out as far as the first was lifted.
     */
    override fun frameHalfWidth(env: SimEnv): Float =
        (n - 1) * radius + radius + sin(abs(env.params.f("swing", 0.55f)).coerceAtMost(PI.toFloat() / 2f)) * STRING

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val spacing = radius * 2f
        // Centred on the screen: hung from 0.9 the row sat above the middle
        // of a tall screen with all the empty space below it.
        val top = 0.45f
        val l = STRING
        val startX = -(n - 1) * spacing * 0.5f
        val dim = Palette.TEXT_SECONDARY
        // The view is as wide as the end ball's swing, so on an upright phone
        // the row can be no bigger; what fills the height is the cradle's own
        // stand — uprights, a top rail and a plinth — rather than empty space.
        val floorY = top - l - radius - 0.09f
        val reach = sin(abs(env.params.f("swing", 0.55f)).coerceAtMost(PI.toFloat() / 2f)) * l
        val postX = (n - 1) * spacing * 0.5f + radius + reach * 0.92f
        val railY = top
        val frameCol = Palette.lerp(dim, 0xFF000000.toInt(), 0.35f)
        // A spotlight from above: a soft cone widening down onto the plinth
        // and a pool of light on it. It gives the tall screen above the
        // cradle something to be, rather than a black void.
        val beamTop = 2.4f
        val bands = 120
        for (k in 0 until bands) {
            val f = (k + 0.5f) / bands
            val y = beamTop + (floorY - beamTop) * f
            val half = 0.12f + (postX + 0.05f - 0.12f) * f
            g.rect(0f, y, half, (beamTop - floorY) / bands * 0.5f + 0.001f, SPOT, 0f, 0.018f + 0.03f * f)
        }
        g.glow(0f, floorY, postX * 1.1f, SPOT, 0.12f)
        g.rect(0f, floorY - 0.05f, postX + 0.07f, 0.05f, Palette.lerp(dim, 0xFF000000.toInt(), 0.75f))
        g.line(-postX - 0.07f, floorY, postX + 0.07f, floorY, 0.006f, dim, 0.5f)
        for (sx in floatArrayOf(-1f, 1f)) {
            g.line(sx * postX, floorY, sx * postX, railY, 0.012f, frameCol, 0.9f)
            g.disc(sx * postX, railY, 0.016f, frameCol, 0.95f)
            g.rect(sx * postX, floorY + 0.01f, 0.03f, 0.01f, frameCol, 0f, 0.9f)
        }
        g.line(-postX, railY, postX, railY, 0.012f, frameCol, 0.9f)
        // The swing's reach at each end, faint, so the arc the end balls trace
        // is part of the picture even while they hang still.
        val swing = abs(env.params.f("swing", 0.55f)).coerceAtMost(PI.toFloat() / 2f)
        for (end in 0..1) {
            val ax = startX + (if (end == 0) 0 else n - 1) * spacing
            val sgn = if (end == 0) -1f else 1f
            val steps = 14
            for (k in 0 until steps) {
                if (k % 2 == 1) continue
                val a0 = sgn * swing * k / steps
                val a1 = sgn * swing * (k + 1) / steps
                g.line(ax + sin(a0) * l, top - cos(a0) * l, ax + sin(a1) * l, top - cos(a1) * l, 0.004f, dim, 0.25f)
            }
        }
        for (i in 0 until n) {
            val anchorX = startX + i * spacing
            val bx = anchorX + sin(pos[i]) * l
            val by = top - cos(pos[i]) * l
            val c = palette.at(i.toFloat() / max(1, n - 1))
            // Reflection in the polished plinth, squashed and dim.
            g.disc(bx, 2f * floorY - by, radius * 0.92f, c, 0.12f)
        }
        for (i in 0 until n) {
            val anchorX = startX + i * spacing
            val bx = anchorX + sin(pos[i]) * l
            val by = top - cos(pos[i]) * l
            val c = palette.at(i.toFloat() / max(1, n - 1))
            // Two threads in a V from the rail, as a real cradle hangs them,
            // which keeps every ball swinging in one plane.
            g.line(anchorX - radius * 0.45f, railY, bx, by + radius * 0.9f, 0.003f, dim, 0.55f)
            g.line(anchorX + radius * 0.45f, railY, bx, by + radius * 0.9f, 0.003f, dim, 0.55f)
            g.glow(bx, by, radius * 2.4f, c, 0.32f)
            g.disc(bx, by, radius, c)
            // Shade on the lower side and a highlight, so the balls read as
            // polished spheres, not flat dots.
            g.disc(bx + radius * 0.12f, by - radius * 0.12f, radius * 0.86f, Palette.lerp(c, 0xFF000000.toInt(), 0.25f), 0.5f)
            g.disc(bx - radius * 0.1f, by + radius * 0.1f, radius * 0.7f, c, 0.6f)
            g.disc(bx - radius * 0.35f, by + radius * 0.35f, radius * 0.26f, Palette.TEXT_PRIMARY, 0.5f)
        }
        for (i in 0 until n - 1) {
            if (spark[i] < 0.02f) continue
            val x0 = startX + i * spacing + sin(pos[i]) * l
            val y0 = top - cos(pos[i]) * l
            g.glow(x0 + radius, y0, radius * 1.4f, Palette.TEXT_PRIMARY, 0.6f * spark[i])
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = stillSeconds > STILL_SECONDS

    override fun counters(): List<Pair<String, String>> =
        if (stillSeconds > 0f) listOf("gravity" to "none — nothing swings")
        else listOf("clicks" to clicks.toString())

    companion object {
        /**
         * String length. It was 1.1, six ball widths: the view must take in the
         * end ball's full swing, so long strings left a small cradle in a big
         * empty screen. Real cradles hang theirs about four or five widths long.
         */
        const val STRING = 0.8f
        private val SPOT = 0xFFDDE6FF.toInt()
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
        /** Its way down: bit k set means right at peg row k. */
        var turns: Int = 0
    ) {
        /** The last peg row it has struck, so each knock is heard and seen once. */
        var row = -1

        /** Its place in the bin's stack, taken as it leaves the last peg. */
        var slot = -1
    }

    /** Only the beads still falling; settled ones live in [bins] and [binColors]. */
    private val beads = ArrayList<Bead>()
    private var bins = IntArray(21)

    /** Slots handed out per bin (a bead takes one before it lands). */
    private var reserved = IntArray(21)

    /** The colour of every settled bead, by bin and slot, so the pile keeps its strata. */
    private var binColors = Array(21) { FloatArray(0) }
    private var rows = 10

    // Layout, worked out in setup from the screen and the counts.
    private var spacing = 0.07f
    private var rowGap = 0.08f
    private var pegR = 0.007f

    /** The pitch of one bead in a bin's stack, and how many sit side by side. */
    private var cell = 0.02f
    private var cellY = 0.02f
    private var cols = 3
    private var beadR = 0.009f
    private var fallR = 0.009f

    /** Each peg lights when struck, one slot per peg, row by row. */
    private var pegFlash = FloatArray(0)

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
    private var doneGlow = 0f

    override fun setup(env: SimEnv) {
        val p = env.params
        stillSeconds = 0f
        rows = p.i("rows", 10).coerceIn(4, 20)
        total = p.i("beads", 400).coerceIn(10, 20000)
        bins = IntArray(rows + 1)
        reserved = IntArray(rows + 1)
        beads.clear()
        dropped = 0
        dropTimer = 0f
        payoffFrame = false
        peak = 1
        restSeconds = -1f
        curveDone = false
        doneGlow = 0f
        pegVoice = 0
        pegVoiceEvery = max(1, total / 400)

        rowGap = (PEG_TOP - BIN_TOP) / rows
        // On a wide screen the width alone would stretch the triangle flat;
        // the gap is held near the row pitch so the pegs stay a lattice.
        spacing = min((1.7f * env.halfWidth) / (rows + 1), rowGap * 1.3f)
        pegR = min(0.009f, spacing * 0.13f)
        pegFlash = FloatArray(rows * (rows + 1) / 2)

        // The pile used to be one column of beads at a fixed step, so the middle
        // bin of a 400-bead board towered past the pegs to the top of the screen.
        // Beads now pack side by side, and the pitch is chosen so the tallest
        // bin we expect (the centre bin, plus a 2.5 sigma margin) fills the bin.
        val binW = spacing * 0.86f
        val centre = total * centreProbability(rows)
        val expectPeak = centre + 2.5f * sqrt(centre) + 1f
        val fit = sqrt((BIN_TOP - FLOOR - 0.03f) * binW / expectPeak)
        val maxCell = min(binW * 0.5f, 0.055f)
        cols = max(1, kotlin.math.ceil(binW / min(fit, maxCell)).toInt())
        cell = binW / cols
        // Rounding the column count up leaves the stack short of the bin top,
        // so the rows are let out a little (never more than a quarter) to fill it.
        val stackRows = kotlin.math.ceil(expectPeak / cols)
        cellY = ((BIN_TOP - FLOOR - 0.03f) / stackRows).coerceIn(cell, cell * 1.25f)
        beadR = cell * 0.44f
        fallR = (cell * 0.44f).coerceIn(0.0065f, spacing * 0.24f)
        val cap = (expectPeak * 1.3f).toInt() + cols
        binColors = Array(rows + 1) { FloatArray(cap) }
    }

    /** C(n, n/2) / 2^n: the share of beads the centre bin gets. */
    private fun centreProbability(n: Int): Float {
        var pr = 1.0
        val k = n / 2
        for (i in 1..k) pr = pr * (n - k + i) / i
        for (i in 0 until n) pr *= 0.5
        return pr.toFloat()
    }

    private fun binX(i: Int): Float = (i - rows * 0.5f) * spacing

    private fun slotX(bin: Int, slot: Int): Float =
        binX(bin) - spacing * 0.43f + ((slot % cols) + 0.5f) * cell

    private fun slotY(slot: Int): Float = FLOOR + ((slot / cols) + 0.5f) * cellY

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        for (i in pegFlash.indices) pegFlash[i] = max(0f, pegFlash[i] - abs(dt) * 5f)
        if (curveDone) doneGlow = min(1f, doneGlow + abs(dt) * 2f)
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
            var turns = 0
            for (r in 0 until rows) if (env.rng.bool()) { bin++; turns = turns or (1 shl r) }
            // Coloured by when it fell, so the finished pile shows its layers.
            beads.add(Bead(0f, SPAWN_Y, -0.45f, bin, 0.9f * dropped / max(1, total - 1), turns))
            dropped++
        }

        val g = 1.6f * (env.laws.gravity / 9.81f)
        // Nothing falls without gravity, and nothing ever settles, so the run
        // would never finish and the feed would never move on.
        if (abs(g) < 1e-5f) {
            stillSeconds += abs(dt)
            return
        }
        stillSeconds = 0f
        // The bead rides on top of each peg it strikes, not through its middle.
        val contact = pegR + fallR
        var landed = false
        for (b in beads) {
            b.vy -= g * dt
            // Pegs bleed off speed on every knock, so a real bead crosses the
            // board at a roughly steady pace instead of whipping through the
            // bottom rows; the cap stands in for that.
            b.vy = b.vy.coerceIn(-MAX_FALL, MAX_FALL)
            b.y += b.vy * dt
            // A real walk: at each peg it goes half a gap left or right, as
            // its coin said, so it lands on the peg below. It used to slide
            // straight at its bin and beads bound for the middle went through
            // the pegs.
            val fallRows = (PEG_TOP + contact - b.y) / rowGap
            val rowNow = fallRows.toInt().coerceIn(0, rows)
            val frac = (fallRows - rowNow).coerceIn(0f, 1f)
            fun pegX(k: Int): Float = (Integer.bitCount(b.turns and ((1 shl k) - 1)) - k * 0.5f) * spacing
            if (fallRows < 0f) {
                b.x = 0f
            } else if (rowNow < rows) {
                val from = pegX(rowNow)
                val to = pegX(rowNow + 1)
                b.x = from + (to - from) * (frac * frac * (3f - 2f * frac))
            }
            if (fallRows >= 0f && rowNow > b.row && b.row < rows) {
                b.row = rowNow
                if (rowNow < rows) {
                    val c = Integer.bitCount(b.turns and ((1 shl rowNow) - 1))
                    pegFlash[rowNow * (rowNow + 1) / 2 + c] = 1f
                    if (pegVoice++ % pegVoiceEvery == 0) {
                        env.emit(
                            EventKind.STEP,
                            (rowNow.toFloat() / rows).coerceIn(0f, 1f),
                            b.x.coerceIn(-1f, 1f),
                            0.22f
                        )
                    }
                }
            }
            if (rowNow >= rows) {
                // Past the last peg: take a place in the bin and drift to it
                // while dropping, so the stack fills in tidy rows.
                if (b.slot < 0) b.slot = reserved[b.bin]++
                val top = PEG_TOP - rows * rowGap
                val sy = slotY(b.slot)
                val t = ((top - b.y) / max(1e-3f, top - sy)).coerceIn(0f, 1f)
                b.x = binX(b.bin) + (slotX(b.bin, b.slot) - binX(b.bin)) * t
                if (b.y <= sy) {
                    b.y = sy
                    val arr = binColors[b.bin]
                    if (b.slot >= arr.size) binColors[b.bin] = arr.copyOf(max(b.slot + 1, arr.size * 2))
                    binColors[b.bin][b.slot] = b.colorT
                    bins[b.bin]++
                    if (bins[b.bin] > peak) peak = bins[b.bin]
                    landed = true
                    b.row = Int.MAX_VALUE
                    env.emit(EventKind.HIT, b.bin.toFloat() / rows, b.x.coerceIn(-1f, 1f), 0.5f)
                }
            }
        }
        if (landed) beads.removeAll { it.row == Int.MAX_VALUE }
        // The payoff is the finished bell curve. Hold it on screen for a moment
        // before the run resolves, or nobody ever sees the shape they waited for.
        if (dropped >= total && beads.isEmpty() && !curveDone) {
            curveDone = true
            payoffFrame = true
            restSeconds = 1.5f
            env.emit(EventKind.DONE, 0.8f, 0f, 1f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val dim = Palette.TEXT_SECONDARY
        val half = (rows + 1) * spacing * 0.5f
        val top = PEG_TOP - rows * rowGap
        // The hopper the beads pour from.
        g.line(-0.07f, SPAWN_Y + 0.05f, -0.016f, SPAWN_Y - 0.03f, 0.004f, dim, 0.55f)
        g.line(0.07f, SPAWN_Y + 0.05f, 0.016f, SPAWN_Y - 0.03f, 0.004f, dim, 0.55f)
        // Pegs: dim studs that light up in the colour of the bead striking them.
        var pi = 0
        for (r in 0 until rows) {
            val y = PEG_TOP - r * rowGap
            for (c in 0..r) {
                val x = (c - r * 0.5f) * spacing
                val f = pegFlash[pi++]
                if (f > 0.02f) g.glow(x, y, spacing * 0.32f, palette.at(0.9f * dropped / max(1, total - 1)), f * 0.35f)
                g.disc(x, y, pegR, Palette.lerp(dim, Palette.TEXT_PRIMARY, f), 0.55f + 0.45f * f)
            }
        }
        // Bin walls and floor.
        for (i in 0..rows + 1) {
            val x = -half + i * spacing
            g.line(x, FLOOR, x, top, 0.003f, dim, 0.35f)
        }
        g.line(-half - 0.0015f, FLOOR - 0.0015f, half + 0.0015f, FLOOR - 0.0015f, 0.003f, dim, 0.5f)

        // The settled beads. Small beads are drawn a full row at a time as one
        // strip, so twenty thousand of them stay a few hundred shapes.
        val strips = cols >= 6
        for (i in 0..rows) {
            val n = bins[i]
            val colors = binColors[i]
            if (strips) {
                val full = n / cols
                for (rIdx in 0 until full) {
                    val y = FLOOR + (rIdx + 0.5f) * cellY
                    g.rect(binX(i), y, spacing * 0.43f, cellY * 0.42f, palette.at(colors[rIdx * cols]))
                }
                for (s in full * cols until n) g.disc(slotX(i, s), slotY(s), beadR, palette.at(colors[s]))
            } else {
                for (s in 0 until n) g.disc(slotX(i, s), slotY(s), beadR, palette.at(colors[s]))
            }
        }
        // The falling beads, each with a small halo so the stream reads.
        for (b in beads) {
            val c = palette.at(b.colorT)
            if (beads.size < 300) g.glow(b.x, b.y, fallR * 2.6f, c, 0.35f)
            g.disc(b.x, b.y, fallR, Palette.brighten(c, 0.2f))
        }
        // The bell curve the bins are heading for: total x the normal density,
        // in stacked-bead height. Faint as a target, bright once it is matched.
        val sigma = sqrt(rows.toFloat()) * 0.5f
        val samples = 64
        val xs = FloatArray(samples)
        val ys = FloatArray(samples)
        for (k in 0 until samples) {
            val u = -0.5f + (rows + 1f) * k / (samples - 1)
            val z = (u - rows * 0.5f) / sigma
            val expected = total * kotlin.math.exp(-0.5f * z * z) / (sigma * sqrt(2f * PI.toFloat()))
            xs[k] = (u - rows * 0.5f) * spacing
            ys[k] = min(top, FLOOR + expected / cols * cellY)
        }
        g.polyline(xs, ys, samples, 0.004f + 0.002f * doneGlow, Palette.TEXT_PRIMARY, 0.22f + 0.6f * doneGlow)
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
        /** The first row of pegs. */
        const val PEG_TOP = 0.80f
        /** Where the last peg row hands each bead to its bin. */
        const val BIN_TOP = -0.04f
        const val FLOOR = -0.93f
        const val SPAWN_Y = 0.92f
        /** Steady crossing speed through the pegs, in world units a second. */
        const val MAX_FALL = 1.1f

        fun def(): SimDef = SimDef(
            id = "physics/galton-board",
            title = "Galton board",
            shelf = Shelf.PHYSICS,
            family = "Bouncing & collisions",
            blurb = "Random beads, every time the same bell curve.",
            formats = listOf(TrapFormat.FILL, TrapFormat.GROW),
            lawKnobs = setOf(LawKnob.GRAVITY),
            heroLabel = "Drop again",
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
        DoublePendulumSim.def(),
        NewtonsCradleSim.def(),
        GaltonBoardSim.def(),
        SandpileSim.def(),
        WaveTankSim.def(),
        TrafficSim.def(),
        MarbleRaceSim.def(),
        ShatterSim.def(),
        DominoSim.def(),
        BalloonSim.def(),
        PourSim.def(),
        WheelSim.def(),
        PressSim.def(),
        ArenaSim.def(),
        LastOutSim.def(),
        SoapCutSim.def(),
        PowerWashSim.def(),
        CornerSim.def(),
        GlassBridgeSim.def()
    )
}
