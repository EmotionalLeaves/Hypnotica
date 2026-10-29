package app.hypnotica.sims.scale

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Macro
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.LawKnob
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.FastMath
import app.hypnotica.core.sim.Parallel
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
import kotlin.math.sqrt

/**
 * The huge-count path.
 *
 * Positions and velocities live in flat float arrays, the whole field is one
 * draw call, and nothing allocates per particle per frame. This is the sim that
 * proves tens of thousands of objects stay at 60 fps.
 */
class ParticleFieldSim : Sim {

    private var n = 10000
    private var px = FloatArray(0)
    private var py = FloatArray(0)
    private var vx = FloatArray(0)
    private var vy = FloatArray(0)
    private var tint = FloatArray(0)
    /** Seconds since each particle was (re)born, and how many times it has been. */
    private var age = FloatArray(0)
    private var births = IntArray(0)
    /** The last [TRAIL] positions of each particle, a ring newest at [trailHead], or empty when off. */
    private var trailX = FloatArray(0)
    private var trailY = FloatArray(0)
    private var trail = 0
    /** The newest slot of every tail ring. */
    private var trailHead = 0
    private var stepCount = 0

    private var attractorX = FloatArray(4)
    private var attractorY = FloatArray(4)
    private var attractorK = FloatArray(4)
    private var attractors = 3
    /** Each vortex's strength × swirl, for this step. */
    private val vortexK = FloatArray(4)

    private var flow = 1f
    private var swirl = 1f
    private var drag = 0.02f
    private var elapsed = 0f
    private var payoffFrame = false
    private var dotSize = 0.0055f
    private var soundTimer = 0f
    private var flipped = false
    private val wallHits = java.util.concurrent.atomic.AtomicInteger(0)
    private val waveP = FloatArray(WAVES)
    private val waveQ = FloatArray(WAVES)
    private val waveA = FloatArray(WAVES)
    private val waveW = FloatArray(WAVES)
    private val wavePh = FloatArray(WAVES)
    /** Each particle's stream-function value: constant along a streamline, so it colours the streams. */
    private var psiNow = FloatArray(0)
    private var psiScale = 1f
    private var tailAlpha = FloatArray(0)
    private var tailSize = FloatArray(0)

    override fun setup(env: SimEnv) {
        val p = env.params
        n = p.i("count", 10000).coerceIn(100, 400_000)
        flow = p.f("flow", 1f)
        swirl = p.f("swirl", 1f)
        drag = p.f("drag", 0.02f)
        dotSize = p.f("dot", 0.0055f)
        attractors = p.i("attractors", 3).coerceIn(0, 4)

        px = FloatArray(n); py = FloatArray(n)
        vx = FloatArray(n); vy = FloatArray(n)
        tint = FloatArray(n)
        age = FloatArray(n)
        births = IntArray(n)

        val w = env.halfWidth
        for (i in 0 until n) {
            px[i] = env.rng.range(-w, w)
            py[i] = env.rng.range(-1f, 1f)
            vx[i] = 0f
            vy[i] = 0f
            tint[i] = env.rng.nextFloat()
            // Staggered, so the first rebirths trickle rather than all land
            // on one frame.
            age[i] = env.rng.nextFloat() * lifeOf(tint[i])
        }
        stepCount = 0
        trailHead = 0
        trail = if (n <= TRAIL_UP_TO) TRAIL else 0
        trailX = FloatArray(n * trail)
        trailY = FloatArray(n * trail)
        for (i in 0 until n) for (k in 0 until trail) {
            trailX[i * trail + k] = px[i]
            trailY[i * trail + k] = py[i]
        }
        for (a in 0 until 4) {
            attractorX[a] = env.rng.range(-w * 0.7f, w * 0.7f)
            attractorY[a] = env.rng.range(-0.7f, 0.7f)
            attractorK[a] = env.rng.range(0.4f, 1f) * (if (env.rng.bool(0.75f)) 1f else -1f)
        }
        // The waves: whole numbers of periods across the screen each way, the
        // biggest carrying most of the flow, each drifting slowly.
        psiNow = FloatArray(n)
        for (k in 0 until WAVES) {
            val m = WAVE_M[k]
            val nn = WAVE_N[k] * (if (env.rng.bool(0.5f)) 1f else -1f)
            waveP[k] = m * PI.toFloat() / w
            waveQ[k] = nn * PI.toFloat()
            val kk = sqrt(waveP[k] * waveP[k] + waveQ[k] * waveQ[k])
            waveA[k] = WAVE_SPEED[k] / kk
            waveW[k] = env.rng.range(0.25f, 0.6f) * (if (env.rng.bool(0.5f)) 1f else -1f)
            wavePh[k] = env.rng.range(0f, 2f * PI.toFloat())
        }
        var amp = 0f
        for (k in 0 until WAVES) amp += waveA[k]
        psiScale = PSI_BANDS / max(1e-4f, amp * flow.coerceAtLeast(0.2f))
        elapsed = 0f
        payoffFrame = false
        soundTimer = 0f
        flipped = false
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        // Live rather than cached at setup, so the field reshapes under the
        // finger instead of the four hundred thousand particles being thrown
        // back to their starting places on every release. Three map lookups
        // against a pass over the whole cloud is nothing. The attractor count
        // stays a rebuild: it sizes an array.
        flow = env.params.f("flow", 1f)
        swirl = env.params.f("swirl", 1f)
        drag = env.params.f("drag", 0.02f)
        val laws = env.laws
        val w = env.halfWidth
        val gx = laws.gravityDirX * laws.gravity * 0.01f
        val gy = -laws.gravityDirY * laws.gravity * 0.01f
        // The product is what has to be clamped. Clamping the coefficient and
        // then multiplying by dt left this above 1 whenever time ran backwards,
        // which turned drag into gain and drove every one of up to four hundred
        // thousand particles to the speed limit within a few seconds.
        val dragF = 1f - ((drag + laws.friction) * abs(dt) * 8f).coerceIn(0f, 0.6f)
        val t = elapsed * 0.35f
        val wrap = laws.space != app.hypnotica.core.laws.Space.BOX
        val restitution = if (laws.sticky) 0f else laws.bounce

        wallHits.set(0)
        stepCount++
        val sampleTrail = stepCount % TRAIL_EVERY == 0
        // The tails are rings with one head for every particle (they are all
        // sampled on the same step), so a sample overwrites the oldest slot
        // instead of every particle shifting its whole tail along.
        if (sampleTrail && trail > 0) trailHead = (trailHead + trail - 1) % trail
        for (a in 0 until attractors) vortexK[a] = attractorK[a] * swirl * VORTEX
        // Split across cores even below the pool's 20 000-item floor, which is
        // sized for loops that cost nanoseconds an item: a particle here costs
        // three waves and up to four vortices, ~100 ns, so ten thousand of
        // them is a millisecond of work. The count is scaled past the floor
        // and the slices mapped back to particles.
        val m = if (n >= Parallel.MIN_ITEMS) 1 else (Parallel.MIN_ITEMS + n - 1) / n
        if (m == 1 || n < MIN_SPLIT) {
            Parallel.forRange(n) { from, to -> integrate(from, to, dt, gx, gy, dragF, t, w, wrap, restitution, sampleTrail) }
        } else {
            Parallel.forRange(n * m) { a, b ->
                val from = (a + m - 1) / m
                val to = (b + m - 1) / m
                if (to > from) integrate(from, to, dt, gx, gy, dragF, t, w, wrap, restitution, sampleTrail)
            }
        }
        val movedFast = wallHits.get()

        // At this scale individual events would be static, so the sound follows
        // the texture: one voice whose pitch tracks how much of the field is
        // racing round the vortex cores.
        // While almost nothing is racing — the field turning over after the
        // flip, mostly — it slows to a soft pulse every 0.4 s rather than
        // stopping: it went silent for six seconds there while the whole
        // field was visibly reversing.
        soundTimer += abs(dt)
        val density = (movedFast.toFloat() / max(1, n / 50)).coerceIn(0f, 1f)
        val every = if (density > 0.02f) 0.08f else 0.4f
        if (soundTimer > every) {
            soundTimer = 0f
            env.emit(EventKind.STEP, density, 0f, (0.12f + density * 0.58f).coerceAtMost(0.8f))
        }

        // The payoff lands about three quarters of the way through a typical run:
        // the attractors flip sign and the whole field reorganises at once.
        if (!flipped && elapsed > PAYOFF_AT) {
            flipped = true
            payoffFrame = true
            for (a in 0 until 4) attractorK[a] = -attractorK[a]
        }
    }

    private fun integrate(
        from: Int, to: Int, dt: Float, gx: Float, gy: Float, dragF: Float,
        t: Float, w: Float, wrap: Boolean, restitution: Float, sampleTrail: Boolean
    ) {
        var localHits = 0
        val relax = min(1f, RELAX * abs(dt))
        // Fields read into locals once: the JIT reloaded each array and
        // constant from the object on every particle, which with the vortex
        // loop's three arrays was most of a step's cost.
        val px = px; val py = py; val vx = vx; val vy = vy
        val age = age; val births = births; val tint = tint; val psiNow = psiNow
        val trailX = trailX; val trailY = trailY; val trail = trail; val head = trailHead
        val flow = flow; val swirl = swirl; val attractors = attractors
        val aX = attractorX; val aY = attractorY
        val aK = vortexK
        val wP0 = waveP[0]; val wP1 = waveP[1]; val wP2 = waveP[2]
        val wQ0 = waveQ[0]; val wQ1 = waveQ[1]; val wQ2 = waveQ[2]
        val wA0 = waveA[0] * flow; val wA1 = waveA[1] * flow; val wA2 = waveA[2] * flow
        val ph0 = waveW[0] * t + wavePh[0]; val ph1 = waveW[1] * t + wavePh[1]; val ph2 = waveW[2] * t + wavePh[2]
        for (i in from until to) {
            val x = px[i]
            val y = py[i]
            // The flow is the curl of a stream function: a few slow plane
            // waves, periodic across the screen so wrap-around has no seam.
            // Divergence-free, so the particles neither bunch nor thin out:
            // they ride closed streamlines. The old field was two forces
            // with nothing to stop it converging, and with inertia on top
            // every particle crossed every other — a hash of streaks.
            var psi = 0f
            var psiX = 0f
            var psiY = 0f
            if (flow > 0.001f) {
                // The WAVES (three) plane waves, unrolled.
                val th0 = wP0 * x + wQ0 * y + ph0
                val th1 = wP1 * x + wQ1 * y + ph1
                val th2 = wP2 * x + wQ2 * y + ph2
                val c0 = wA0 * fcos(th0)
                val c1 = wA1 * fcos(th1)
                val c2 = wA2 * fcos(th2)
                psi = wA0 * fsin(th0) + wA1 * fsin(th1) + wA2 * fsin(th2)
                psiX = c0 * wP0 + c1 * wP1 + c2 * wP2
                psiY = c0 * wQ0 + c1 * wQ1 + c2 * wQ2
            }
            var u: Float
            var v: Float
            var win = 1f
            if (!wrap) {
                // In a walled box the stream function is windowed to zero at
                // the walls, which makes each wall a streamline: the flow runs
                // along it instead of piling particles into it.
                val xr = x / w
                val x6 = xr * xr * xr * xr * xr * xr
                val y6 = y * y * y * y * y * y
                val wx = 1f - x6
                val wy = 1f - y6
                win = (wx * wy).coerceAtLeast(0f)
                val winX = -6f * (xr * xr * xr * xr * xr) / w * wy
                val winY = -6f * (y * y * y * y * y) * wx
                u = psiY * win + psi * winY
                v = -(psiX * win + psi * winX)
            } else {
                u = psiY
                v = -psiX
            }
            psiNow[i] = psi

            // Point vortices with soft cores, also divergence-free: the
            // attractors stir the field into eddies rather than swallowing it.
            if (swirl > 0.001f) {
                for (a in 0 until attractors) {
                    val dx = x - aX[a]
                    val dy = y - aY[a]
                    val k = aK[a] / (dx * dx + dy * dy + CORE2) * win
                    u += -dy * k
                    v += dx * k
                }
            }

            // Particles relax onto the flow, with a little inertia, so they
            // trace its streamlines; gravity still drifts them.
            var nvx = (vx[i] + (u - vx[i]) * relax + gx * dt) * dragF
            var nvy = (vy[i] + (v - vy[i]) * relax + gy * dt) * dragF

            val sp2 = nvx * nvx + nvy * nvy
            if (sp2 > MAX_SPEED2) {
                val kk = MAX_SPEED / sqrt(sp2)
                nvx *= kk; nvy *= kk
            }
            if (sp2 > FAST2) localHits++

            var nx = x + nvx * dt
            var ny = y + nvy * dt

            if (wrap) {
                if (nx > w) nx -= 2 * w else if (nx < -w) nx += 2 * w
                if (ny > 1f) ny -= 2f else if (ny < -1f) ny += 2f
            } else {
                if (nx > w) { nx = w; nvx = -nvx * restitution }
                else if (nx < -w) { nx = -w; nvx = -nvx * restitution }
                if (ny > 1f) { ny = 1f; nvy = -nvy * restitution }
                else if (ny < -1f) { ny = -1f; nvy = -nvy * restitution }
            }

            // Every particle lives a few seconds and is reborn somewhere
            // random, the way tracer dye shows a current; gravity and the
            // vortex cores would otherwise gather it in the end. Rebirth is a
            // hash of the index and the count, not env.rng: this runs on
            // several threads at once.
            var a = age[i] + abs(dt)
            val tb = i * trail
            if (sampleTrail && trail > 0) { trailX[tb + head] = x; trailY[tb + head] = y }
            // A jump across the seam would draw its tail across the screen.
            if (wrap && trail > 0 && (abs(nx - x) > 0.5f || abs(ny - y) > 0.5f)) {
                for (k in 0 until trail) { trailX[tb + k] = nx; trailY[tb + k] = ny }
            }
            if (a > lifeOf(tint[i])) {
                val b = births[i] + 1
                births[i] = b
                var h = i * -0x61c88647 + b * -0x7a143595
                h = h xor (h ushr 16); h *= -0x7a143595; h = h xor (h ushr 13); h *= -0x3d4d51cb; h = h xor (h ushr 16)
                nx = ((h and 0xFFFF) / 65535f * 2f - 1f) * w * 0.98f
                ny = (((h ushr 16) and 0xFFFF) / 65535f * 2f - 1f) * 0.98f
                nvx = 0f; nvy = 0f
                a = 0f
                for (k in 0 until trail) { trailX[tb + k] = nx; trailY[tb + k] = ny }
            }
            age[i] = a
            px[i] = nx; py[i] = ny; vx[i] = nvx; vy[i] = nvy
        }
        if (localHits > 0) wallHits.addAndGet(localHits)
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val sizeScale = if (n > 120_000) 0.6f else 1f
        // Past a few hundred thousand there are more particles than there are
        // pixels to put them in, and filling the draw list is the one part of
        // the frame that cannot be split across cores. A sample is drawn at a
        // proportionally larger size, so the field is as dense and as bright as
        // it was: all of them are still simulated, and the counter still says
        // so. The same trade the Riemann sum makes with its bars.
        val stride = if (n > DRAW_EVERY_ABOVE) 1 + n / DRAW_EVERY_ABOVE else 1
        val spread = if (stride > 1) sqrt(stride.toFloat()) else 1f
        g.reservePoints((n / stride + 1) * (1 + trail))
        // Each tail point's fade and size, the same for every particle, worked
        // out once a frame rather than per point.
        if (tailAlpha.size != trail) { tailAlpha = FloatArray(trail); tailSize = FloatArray(trail) }
        for (k in 0 until trail) {
            val f = 1f - (k + 1f) / (trail + 1f)
            tailAlpha[k] = f * 0.8f
            tailSize[k] = 0.7f + 0.3f * f
        }
        // Each particle's colour and size first, across cores (a square root,
        // a sine, a palette lookup and two blends each, the dearer half of the
        // draw), then the draw list filled in one pass, which has to be serial.
        val count = (n + stride - 1) / stride
        if (dotColour.size < count) { dotColour = IntArray(count); dotSize2 = FloatArray(count) }
        val baseSize = dotSize * sizeScale * spread
        val m = if (count >= Parallel.MIN_ITEMS) 1 else (Parallel.MIN_ITEMS + count - 1) / count
        if (count < MIN_SPLIT) colourDots(palette, 0, count, stride, baseSize)
        else Parallel.forRange(count * m) { a, b ->
            val from = (a + m - 1) / m
            val to = (b + m - 1) / m
            if (to > from) colourDots(palette, from, to, stride, baseSize)
        }
        emitDots(g, count, stride)
        // The vortex centres, small and quiet: the eddies round them show
        // where they are.
        for (a in 0 until attractors) {
            val c = if (attractorK[a] > 0) Palette.ACCENT else Palette.DANGER
            g.glow(attractorX[a], attractorY[a], 0.05f, c, 0.18f)
            g.ring(attractorX[a], attractorY[a], 0.014f, 0.003f, c, alpha = 0.5f)
        }
    }

    /** The draw list for the dots and their tails: a small method of its own so G.point inlines into it. */
    private fun emitDots(g: G, count: Int, stride: Int) {
        val px = px
        val py = py
        val trailX = trailX
        val trailY = trailY
        val trail = trail
        val head = trailHead
        val tailAlpha = tailAlpha
        val tailSize = tailSize
        val dotColour = dotColour
        val dotSize2 = dotSize2
        for (j in 0 until count) {
            val i = j * stride
            val c = dotColour[j]
            val size = dotSize2[j]
            g.point(px[i], py[i], size, c)
            // The trail, fading: a point alone cannot show which way it is
            // going, and twenty thousand of them read as static noise. With a
            // short comet tail each, the streams of the field are visible.
            val tb = i * trail
            val rgb = c and 0x00FFFFFF
            val a0 = (c ushr 24).toFloat()
            // Newest first: slot (head + k) of the ring. The alpha is at most
            // 255 × 0.8, so no clamp.
            var slot = head
            for (k in 0 until trail) {
                val ak = (a0 * tailAlpha[k]).toInt()
                g.point(trailX[tb + slot], trailY[tb + slot], size * tailSize[k], (ak shl 24) or rgb)
                slot++
                if (slot == trail) slot = 0
            }
        }
    }

    private var dotColour = IntArray(0)
    private var dotSize2 = FloatArray(0)

    /** Colour and size of drawn dots [from, to) (particle j * stride). */
    private fun colourDots(palette: Palette, from: Int, to: Int, stride: Int, baseSize: Float) {
        // Colour follows speed, with only a little per-particle variety, so
        // a stream reads as one ribbon. Half the colour was a random tint per
        // particle, and every stream was salt-and-pepper grain. Slow ones are
        // dimmer: what gravity and the field herd into a corner sits there
        // quietly instead of being the brightest thing on screen.
        for (j in from until to) {
            val i = j * stride
            val speed = min(1f, sqrt(vx[i] * vx[i] + vy[i] * vy[i]) * 1.6f)
            // Faded in at birth and out before rebirth, so no particle pops.
            val life = lifeOf(tint[i])
            val fade = min(1f, min(age[i], life - age[i]) * 2.5f).coerceAtLeast(0f)
            // Hue from the stream function, which is constant along a
            // streamline: each stream is one ribbon of colour and the field's
            // structure shows as bands. Brightness from speed.
            val band = 0.5f + 0.5f * fsin(psiNow[i] * psiScale)
            dotColour[j] = Palette.withAlpha(
                Palette.brighten(palette.at((0.12f + 0.76f * band + (tint[i] - 0.5f) * 0.04f).coerceIn(0f, 1f)), 0.25f * speed),
                (0.45f + 0.55f * speed) * fade
            )
            dotSize2[j] = baseSize * (0.85f + speed * 0.3f)
        }
    }

    override fun onInput(env: SimEnv, e: TouchEvent) {
        if (e.kind == TouchEvent.Kind.TAP || e.kind == TouchEvent.Kind.DRAG) {
            if (attractors > 0) {
                attractorX[0] = e.x
                attractorY[0] = e.y
                attractorK[0] = abs(attractorK[0])
            }
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> =
        listOf("particles" to String.format(java.util.Locale.US, "%,d", n))

    companion object {
        /**
         * Above this many particles the draw samples rather than drawing every
         * one. Chosen so a 1080p screen still gets a point for every few
         * pixels, which is the point at which more of them stop being visible.
         */
        const val DRAW_EVERY_ABOVE = 200_000

        private const val MAX_SPEED = 1.6f

        /** A particle's lifetime, from its tint, in seconds. */
        private fun lifeOf(tint: Float): Float = 3.5f + 5f * tint

        /** Plane waves in the stream function: periods across the width and height, and peak speed. */
        // Three: a fourth, finer wave added little to see and a third more
        // trig per particle at four hundred thousand.
        private const val WAVES = 3
        private val WAVE_M = floatArrayOf(1f, 2f, 1f)
        private val WAVE_N = floatArrayOf(1f, 1f, 2f)
        private val WAVE_SPEED = floatArrayOf(0.27f, 0.15f, 0.13f)
        /** How quickly a particle takes up the flow's velocity, per second. */
        private const val RELAX = 4f
        /** Vortex strength and soft-core radius squared. */
        private const val VORTEX = 0.05f
        private const val CORE2 = 0.006f
        /** Speed counted as racing, for the sound. */
        private const val FAST2 = 0.5f * 0.5f
        /** Radians of colour band across the stream function's full range. */
        private const val PSI_BANDS = 3.2f
        /** Trail points per particle, sampled every [TRAIL_EVERY] steps, and the count above which there are none. */
        private const val TRAIL = 8
        private const val TRAIL_EVERY = 3
        private const val TRAIL_UP_TO = 60_000
        private const val MAX_SPEED2 = MAX_SPEED * MAX_SPEED
        private const val PAYOFF_AT = 18f
        /** Below this many particles a step is too little work to hand to other cores. */
        private const val MIN_SPLIT = 3000

        /**
         * FastMath.sin with its two sign branches written as abs(): the same
         * parabola and the same numbers, but the branches on a random sign
         * mispredicted half the time, six calls a particle, and that was a
         * large share of the step.
         */
        private fun fsin(xIn: Float): Float {
            var x = xIn - F_TWO_PI * kotlin.math.floor((xIn + F_PI) * (1f / F_TWO_PI))
            if (x > F_PI) x -= F_TWO_PI else if (x < -F_PI) x += F_TWO_PI
            val y = F_B * x + F_C * x * abs(x)
            return F_P * (y * abs(y) - y) + y
        }

        private fun fcos(x: Float): Float = fsin(x + F_PI * 0.5f)

        private const val F_PI = 3.14159265f
        private const val F_TWO_PI = 6.2831853f
        private const val F_B = 4f / F_PI
        private const val F_C = -4f / (F_PI * F_PI)
        private const val F_P = 0.225f

        fun def(): SimDef = SimDef(
            id = "life/particle-field",
            title = "Particle field",
            shelf = Shelf.LIFE,
            family = "Particles & flocking",
            blurb = "Up to 400,000 particles in one flow field, one draw call.",
            formats = listOf(TrapFormat.GROW),
            lawKnobs = setOf(LawKnob.GRAVITY, LawKnob.GRAVITY_DIRECTION, LawKnob.BOUNCE,
                LawKnob.FRICTION, LawKnob.CONTACT, LawKnob.SPACE),
            heroLabel = "New field",
            defaultPalette = "Aurora",
            typicalSeconds = 30f,
            params = listOf(
                // Ten thousand with comet tails, not twenty thousand bare
                // dots: twice the count filled every gap and the streams
                // that show the flow were lost in the carpet.
                Param.int("count", "Particles", 100, 400_000, 10000, card = Card.DATA, logScale = true),
                Param.float("flow", "Flow", 0f, 3f, 1f, card = Card.MOTION),
                Param.float("swirl", "Swirl", 0f, 3f, 1f, card = Card.MOTION),
                Param.int("attractors", "Attractors", 0, 4, 3, card = Card.MOTION),
                Param.float("drag", "Drag", 0f, 0.2f, 0.02f, card = Card.MOTION, advancedOnly = true),
                Param.float("dot", "Dot size", 0.001f, 0.02f, 0.0055f, card = Card.LOOK)
            ),
            macros = listOf(
                Macro("size", "Size", Card.DATA, mapOf("count" to 2000f..400000f), 0.05f),
                Macro("chaos", "Chaos", Card.MOTION, mapOf("flow" to 0.2f..3f, "swirl" to 0.1f..3f), 0.35f)
            ),
            create = { ParticleFieldSim() }
        )
    }
}

/**
 * Conway's Game of Life on a grid of up to a million cells, stepped with flat
 * byte arrays and drawn as points.
 */
class GameOfLifeSim : Sim {

    private var w = 72
    private var h = 72
    private var cells = ByteArray(0)
    private var next = ByteArray(0)
    private var births = 0
    private var deaths = 0
    private var generation = 0
    private var stepTimer = 0f
    private var payoffFrame = false
    private var alive = 0
    private var birthRule = 0b000001000
    private var surviveRule = 0b000001100
    private var quietGenerations = 0
    private var settled = false
    private var lastAlive = -1
    private var peakAlive = 0
    private val history = IntArray(HISTORY)

    override fun setup(env: SimEnv) {
        val p = env.params
        java.util.Arrays.fill(history, 0)
        val side = p.i("grid", 72).coerceIn(16, 1024)
        // Square cells on any screen, with as many cells as a side × side
        // grid. A square grid stretched over an upright phone drew every cell
        // 2.2 times taller than wide, and every glider and blinker squashed
        // with it. The count is kept, not the side: fewer cells settled the
        // soup differently and its payoff moved out of its window.
        val aspect = env.aspect.coerceIn(0.1f, 10f)
        val root = sqrt(aspect)
        w = max(8, kotlin.math.round(side * root).toInt())
        h = max(8, kotlin.math.round(side / root).toInt())
        cells = ByteArray(w * h)
        next = ByteArray(w * h)
        val density = p.f("density", 0.32f)
        for (i in cells.indices) cells[i] = if (env.rng.nextFloat() < density) 1 else 0
        generation = 0
        stepTimer = 0f
        payoffFrame = false
        births = 0
        deaths = 0
        quietGenerations = 0
        settled = false
        lastAlive = -1
        peakAlive = 0
        applyRule(p.choice("rule", "Life B3/S23"))
        countAlive()
    }

    private fun applyRule(name: String) {
        when (name) {
            "HighLife B36/S23" -> { birthRule = (1 shl 3) or (1 shl 6); surviveRule = (1 shl 2) or (1 shl 3) }
            "Seeds B2/S" -> { birthRule = (1 shl 2); surviveRule = 0 }
            "Day & night B3678/S34678" -> {
                birthRule = (1 shl 3) or (1 shl 6) or (1 shl 7) or (1 shl 8)
                surviveRule = (1 shl 3) or (1 shl 4) or (1 shl 6) or (1 shl 7) or (1 shl 8)
            }
            "Maze B3/S12345" -> {
                birthRule = (1 shl 3)
                surviveRule = (1 shl 1) or (1 shl 2) or (1 shl 3) or (1 shl 4) or (1 shl 5)
            }
            else -> { birthRule = (1 shl 3); surviveRule = (1 shl 2) or (1 shl 3) }
        }
    }

    private fun countAlive() {
        var a = 0
        for (c in cells) if (c.toInt() != 0) a++
        alive = a
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val rate = env.params.f("rate", 12f)
        stepTimer += abs(dt) * rate
        var guard = 4
        while (stepTimer >= 1f && guard > 0) {
            stepTimer -= 1f
            guard--
            generation++
            var b = 0
            var d = 0
            var bSum = 0L
            var dSum = 0L
            for (y in 0 until h) {
                val yUp = if (y == 0) h - 1 else y - 1
                val yDn = if (y == h - 1) 0 else y + 1
                val rowC = y * w
                val rowU = yUp * w
                val rowD = yDn * w
                for (x in 0 until w) {
                    val xL = if (x == 0) w - 1 else x - 1
                    val xR = if (x == w - 1) 0 else x + 1
                    val count = cells[rowU + xL] + cells[rowU + x] + cells[rowU + xR] +
                        cells[rowC + xL] + cells[rowC + xR] +
                        cells[rowD + xL] + cells[rowD + x] + cells[rowD + xR]
                    val wasAlive = cells[rowC + x].toInt() != 0
                    val nowAlive = if (wasAlive) (surviveRule shr count) and 1 == 1
                    else (birthRule shr count) and 1 == 1
                    next[rowC + x] = if (nowAlive) 1 else 0
                    if (nowAlive && !wasAlive) { b++; bSum += x }
                    if (!nowAlive && wasAlive) { d++; dSum += x }
                }
            }
            val t = cells; cells = next; next = t
            births = b
            deaths = d
            countAlive()
            val activity = ((b + d).toFloat() / max(1, w * h / 40)).coerceIn(0f, 1f)
            // Panned to where on the board the births and the deaths were (a
            // fixed -0.3 / +0.3 said nothing about the picture).
            if (b > 0) env.emit(EventKind.BIRTH, activity, ((bSum.toFloat() / b + 0.5f) / w * 2f - 1f), (0.2f + activity * 0.5f).coerceAtMost(0.8f))
            if (d > 0) env.emit(EventKind.DEATH, 1f - activity, ((dSum.toFloat() / d + 0.5f) / w * 2f - 1f), (0.15f + activity * 0.4f).coerceAtMost(0.7f))

            // The payoff is the moment the soup stops boiling: the population
            // stops falling and what is left is structure, not noise. Waiting for
            // perfect stillness would take thousands of generations nobody sees.
            if (peakAlive == 0) peakAlive = alive
            // Settled when the population's trend has flattened: the mean of
            // the last ten generations against the ten before. A per-generation
            // test (|alive - last| within 2%) only passed on big grids, where
            // the blinkers' and gliders' flicker is small beside the total; on
            // a grid with cells big enough to see, that flicker alone kept the
            // soup "boiling" for minutes after it had plainly calmed down.
            history[generation % HISTORY] = alive
            if (generation >= HISTORY) {
                var recent = 0
                var before = 0
                for (k in 0 until HISTORY / 2) {
                    recent += history[(generation - k) % HISTORY]
                    before += history[(generation - HISTORY / 2 - k) % HISTORY]
                }
                val half = HISTORY / 2f
                val flat = abs(recent - before) / half <= max(2f, recent / half * SETTLED_DRIFT)
                if (flat) quietGenerations++ else quietGenerations = 0
            }
            lastAlive = alive
            if (!settled && quietGenerations >= 6 && generation > 40) {
                settled = true
                payoffFrame = true
            }
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val halfW = env.halfWidth
        val cellW = (2f * halfW) / w
        val cellH = 2f / h
        val size = max(cellW, cellH)
        // Crisp square cells with a hairline gap while they are big enough to
        // show one; points past that, where a cell is a pixel or two.
        val squares = max(w, h) <= SQUARES_UP_TO
        if (squares) g.reserveInstances(alive * 2) else g.reservePoints(alive)
        val hwC = cellW * 0.43f
        val hhC = cellH * 0.43f
        // A faint board under the cells while they are big enough to have
        // one: the empty cells then read as cells, and the ash of blocks and
        // blinkers sits on a board instead of floating in black.
        if (max(w, h) <= BOARD_UP_TO) {
            val lc = Palette.withAlpha(palette.at(0.5f), 0.07f)
            val lw = min(cellW, cellH) * 0.06f
            for (x in 0..w) g.rect(-halfW + x * cellW, 0f, lw, 1f, lc)
            for (y in 0..h) g.rect(0f, -1f + y * cellH, halfW, lw, lc)
        }
        // After a step [next] holds the generation before, so each cell can
        // tell whether it was just born — drawn brighter — or just died,
        // left as a faint ghost for one generation: the motion shows.
        for (y in 0 until h) {
            val row = y * w
            val cy = -1f + (y + 0.5f) * cellH
            for (x in 0 until w) {
                val now = cells[row + x].toInt() != 0
                val was = generation > 0 && next[row + x].toInt() != 0
                if (!now && !was) continue
                val cx = -halfW + (x + 0.5f) * cellW
                // The ramp's darkest fifth left out: Ocean's navy end sat on
                // the black background at a contrast the eye barely caught.
                val base = palette.at(0.32f + 0.68f * (x + y).toFloat() / (w + h))
                val c = when {
                    now && !was -> Palette.brighten(base, 0.3f)
                    now -> base
                    else -> Palette.withAlpha(base, 0.22f)
                }
                if (squares) g.rect(cx, cy, hwC, hhC, c) else g.point(cx, cy, size, c)
            }
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "generation" to generation.toString(),
        "alive" to String.format(java.util.Locale.US, "%,d", alive)
    )

    companion object {
        /** Grids up to this many cells along the long side draw as squares. */
        private const val SQUARES_UP_TO = 420
        /** Grids up to this many cells along the long side get a board drawn. */
        private const val BOARD_UP_TO = 200
        /** Generations of population kept for the settling test. */
        private const val HISTORY = 20
        /** How far the ten-generation mean may move and still count as flat. */
        private const val SETTLED_DRIFT = 0.02f
        fun def(): SimDef = SimDef(
            id = "life/game-of-life",
            title = "Game of Life",
            shelf = Shelf.LIFE,
            family = "Cellular automata",
            blurb = "Four rules, a million cells, endless structure.",
            formats = listOf(TrapFormat.GROW, TrapFormat.COLOR_BATTLE),
            heroLabel = "Reseed",
            defaultPalette = "Ocean",
            typicalSeconds = 22f,
            params = listOf(
                // 72, not 160: at 160 a cell was a few pixels on a phone and
                // the soup read as specks, not as gliders and blinkers.
                Param.int("grid", "Grid size", 16, 1024, 72, card = Card.DATA, logScale = true),
                Param.float("density", "Start density", 0.02f, 0.9f, 0.32f, card = Card.DATA),
                Param.choice(
                    "rule", "Rule",
                    listOf("Life B3/S23", "HighLife B36/S23", "Seeds B2/S", "Day & night B3678/S34678", "Maze B3/S12345"),
                    0, card = Card.DATA
                ),
                Param.float("rate", "Generations", 0.5f, 60f, 12f, card = Card.MOTION, unit = "/s", logScale = true)
            ),
            macros = listOf(
                Macro("size", "Size", Card.DATA, mapOf("grid" to 48f..512f), 0.3f)
            ),
            create = { GameOfLifeSim() }
        )
    }
}

/** The Lorenz attractor, drawn as the trail it carves. */
class LorenzSim : Sim {

    private var runFor = 0f
    private var settled = false
    private var settleClock = 0f
    private var lastX = 0.0
    private var lastZ = 0.0

    /** How long before the "it has settled" test starts, and how often it runs. */
    private val SETTLE_AFTER = 6f
    private val SETTLE_SAMPLE = 1.5f
    private val SETTLE_SPEED = 0.02

    private var trailX = FloatArray(0)
    private var trailY = FloatArray(0)
    private var head = 0
    private var filled = 0
    private var x = 0.0
    private var y = 1.0
    private var z = 1.05
    private var sigma = 10.0
    private var rho = 28.0
    private var beta = 8.0 / 3.0
    private var carry = 0f
    private var payoffFrame = false
    private var lobeSwaps = 0L
    private var lastLobe = 1
    private var prevZ = 0.0
    private var rising = false

    override fun setup(env: SimEnv) {
        val p = env.params
        val len = p.i("trail", 9000).coerceIn(200, 60000)
        trailX = FloatArray(len)
        trailY = FloatArray(len)
        head = 0
        filled = 0
        x = env.rng.range(-1f, 1f).toDouble()
        y = env.rng.range(-1f, 1f).toDouble()
        z = 1.0 + env.rng.range(0f, 2f)
        sigma = p.f("sigma", 10f).toDouble()
        rho = p.f("rho", 28f).toDouble()
        beta = p.f("beta", 2.667f).toDouble()
        carry = 0f
        payoffFrame = false
        runFor = 0f
        lobeSwaps = 0
        // The side it starts on: always 1 counted a swap on the first step for
        // every run that began with x below zero, about half of them.
        lastLobe = if (x > 0.0) 1 else -1
        prevZ = z
        rising = false
        settled = false
        settleClock = 0f
        lastX = x
        lastZ = z
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        settleClock += abs(dt)
        val speed = env.params.f("rate", 1200f)
        carry += abs(dt) * speed
        var steps = min(carry.toInt(), 6000)
        carry -= steps
        val h = 0.0016
        while (steps > 0) {
            steps--
            // Classic RK4; the attractor is sensitive enough that Euler drifts.
            val k1x = sigma * (y - x); val k1y = x * (rho - z) - y; val k1z = x * y - beta * z
            val x2 = x + k1x * h / 2; val y2 = y + k1y * h / 2; val z2 = z + k1z * h / 2
            val k2x = sigma * (y2 - x2); val k2y = x2 * (rho - z2) - y2; val k2z = x2 * y2 - beta * z2
            val x3 = x + k2x * h / 2; val y3 = y + k2y * h / 2; val z3 = z + k2z * h / 2
            val k3x = sigma * (y3 - x3); val k3y = x3 * (rho - z3) - y3; val k3z = x3 * y3 - beta * z3
            val x4 = x + k3x * h; val y4 = y + k3y * h; val z4 = z + k3z * h
            val k4x = sigma * (y4 - x4); val k4y = x4 * (rho - z4) - y4; val k4z = x4 * y4 - beta * z4
            x += h / 6 * (k1x + 2 * k2x + 2 * k3x + k4x)
            y += h / 6 * (k1y + 2 * k2y + 2 * k3y + k4y)
            z += h / 6 * (k1z + 2 * k2z + 2 * k3z + k4z)

            trailX[head] = (x * 0.034).toFloat()
            trailY[head] = (z * 0.034 - 0.86).toFloat()
            head = (head + 1) % trailX.size
            if (filled < trailX.size) filled++

            // A quiet note at the top of every loop, pitched by how high it
            // went: the Lorenz map, heard. Crossings alone left the sim silent
            // for five seconds at a time while it circled one wing.
            if (z < prevZ && rising && z > LOOP_FLOOR) {
                env.emit(EventKind.STEP, ((z - 5.0) / 40.0).toFloat().coerceIn(0f, 1f),
                    (x * 0.03).toFloat().coerceIn(-0.6f, 0.6f), 0.3f)
            }
            rising = z > prevZ
            prevZ = z

            val lobe = if (x > 0) 1 else -1
            if (lobe != lastLobe) {
                lastLobe = lobe
                lobeSwaps++
                // The payoff is the attractor's shape being complete: both wings
                // fully drawn, which takes a good few crossings.
                if (lobeSwaps == PAYOFF_SWAPS && !settled) {
                    payoffFrame = true
                }
                env.emit(EventKind.CROSS, ((z - 5.0) / 40.0).toFloat().coerceIn(0f, 1f), lobe * 0.6f, 0.7f)
            }
        }

        // Below rho about 24.7 the attractor has no wings to swap between: the
        // trajectory spirals into a fixed point and stays there. That settling
        // is the thing to watch at those values, so it is the payoff rather
        // than a run that never pays off at all.
        // Drawn at the slider's slowest, the wings take minutes to fill: by
        // this long, what's drawn is the picture.
        runFor += abs(dt)
        if (!settled && lobeSwaps < PAYOFF_SWAPS && runFor > BACKSTOP_SECONDS) {
            settled = true
            payoffFrame = true
        }
        if (!settled && settleClock > SETTLE_AFTER) {
            val speedNow = kotlin.math.abs(x - lastX) + kotlin.math.abs(z - lastZ)
            if (speedNow < SETTLE_SPEED) {
                settled = true
                payoffFrame = true
            }
            lastX = x
            lastZ = z
            settleClock = SETTLE_AFTER - SETTLE_SAMPLE
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val size = env.params.f("dot", 0.006f)
        // The trail as joined strokes, oldest first so the newest lies on
        // top. As separate dots the fast outer loops broke into dotted lines
        // and the slow inner ones clumped into beads.
        // The newest stretch also gets a wide, faint underlay drawn first: a
        // halo that makes the fresh loops glow. A single hairline stroke, with
        // the view pulled back to fit a phone, read thin and dim.
        val haloN = min(filled - 1, HALO_SEGMENTS)
        g.reserveInstances(filled + haloN)
        for (k in haloN downTo 1) {
            val a = (head - 1 - k + trailX.size * 2) % trailX.size
            val b = (a + 1) % trailX.size
            val fresh = 1f - k.toFloat() / (haloN + 1)
            g.line(trailX[a], trailY[a], trailX[b], trailY[b], size * 4f,
                palette.at(0.15f), 0.10f * fresh)
        }
        for (k in filled - 1 downTo 1) {
            val a = (head - 1 - k + trailX.size * 2) % trailX.size
            val b = (a + 1) % trailX.size
            val age = k.toFloat() / filled
            // A gentler fade than linear, so the older loops still describe
            // the attractor's shape instead of vanishing into the background.
            val keep = 1f - age
            g.line(trailX[a], trailY[a], trailX[b], trailY[b], size * (1.15f - age * 0.6f),
                Palette.brighten(palette.at(0.15f + age * 0.7f), 0.25f * keep),
                0.18f + 0.82f * sqrt(keep))
        }
        if (filled > 0) {
            val last = (head - 1 + trailX.size) % trailX.size
            val pen = Palette.brighten(palette.at(0.15f), 0.45f)
            g.glow(trailX[last], trailY[last], 0.06f, pen, 0.7f)
            g.disc(trailX[last], trailY[last], size * 1.6f, pen)
        }
    }

    /**
     * Drawn at a fixed size; on a phone held upright the view pulls back to
     * fit it. The wings reach x = ±20 or so, 0.68 on screen: at 0.6 the
     * outer loops of both wings were cut off at the sides.
     */
    override fun frameHalfWidth(env: SimEnv): Float = 0.72f

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> =
        listOf("lobe swaps" to lobeSwaps.toString())

    companion object {
        const val BACKSTOP_SECONDS = 40f
        private const val PAYOFF_SWAPS = 26L
        /** How much of the newest trail carries the glowing underlay. */
        private const val HALO_SEGMENTS = 2500
        /** Below this a z peak is the spiral into a fixed point, not a loop. */
        private const val LOOP_FLOOR = 8.0

        fun def(): SimDef = SimDef(
            id = "math/lorenz",
            title = "Lorenz attractor",
            shelf = Shelf.MATH,
            family = "Chaos & dynamical systems",
            blurb = "Two lobes, never the same path twice.",
            formats = listOf(TrapFormat.GROW),
            heroLabel = "New start",
            defaultPalette = "Aurora",
            typicalSeconds = 28f,
            params = listOf(
                // 9000: at 4000 only the last two or three loops were on screen,
                // a sparse sketch rather than the full butterfly.
                Param.int("trail", "Trail length", 200, 60000, 9000, card = Card.LOOK, logScale = true),
                Param.float("dot", "Dot size", 0.002f, 0.02f, 0.006f, card = Card.LOOK),
                Param.float("rate", "Speed", 100f, 8000f, 1200f, card = Card.MOTION, logScale = true),
                Param.float("sigma", "Sigma", 1f, 20f, 10f, card = Card.MOTION, advancedOnly = true),
                Param.float("rho", "Rho", 1f, 60f, 28f, card = Card.MOTION, advancedOnly = true),
                Param.float("beta", "Beta", 0.5f, 6f, 2.667f, card = Card.MOTION, advancedOnly = true)
            ),
            create = { LorenzSim() }
        )
    }
}

object ScaleSims {
    fun defs(): List<SimDef> = listOf(
        ParticleFieldSim.def(),
        GameOfLifeSim.def(),
        LorenzSim.def()
    )
}
