package app.hypnotica.sims.dimensions

import app.hypnotica.core.nd.Nd
import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Macro
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A hypercube turning through planes that have no three-dimensional equivalent.
 *
 * In 4D the inner cube is not inside the outer one; it is the same cube seen
 * further away along W, which is why it appears to turn itself inside out.
 */
class HypercubeSim : Sim {

    private var dims = 4
    private var verts = FloatArray(0)
    private var edges = IntArray(0)
    private val proj = FloatArray(3)
    private var screenX = FloatArray(0)
    private var screenY = FloatArray(0)
    private var screenD = FloatArray(0)
    private var elapsed = 0f
    private var payoffFrame = false
    private var turns = 0L
    private var planeA = 0
    private var planeB = 3
    private var manualSpin = 0f

    /** Which corners are currently on the far side of the extra axis. */
    private var behind = BooleanArray(0)

    override fun setup(env: SimEnv) {
        val p = env.params
        dims = p.i("dims", 4).coerceIn(2, Nd.MAX_DIMS)
        verts = Nd.hypercubeVertices(dims)
        edges = Nd.hypercubeEdges(dims)
        val n = 1 shl dims
        screenX = FloatArray(n)
        screenY = FloatArray(n)
        screenD = FloatArray(n)
        behind = BooleanArray(n) { verts[it * dims + (dims - 1)] < 0f }
        planeA = p.i("planeA", 0).coerceIn(0, dims - 1)
        planeB = p.i("planeB", min(3, dims - 1)).coerceIn(0, dims - 1)
        if (planeA == planeB) planeB = (planeA + 1) % dims
        elapsed = 0f
        payoffFrame = false
        turns = 0L
        manualSpin = 0f
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += dt
        val n = 1 shl dims
        val speed = env.params.f("spin", 0.55f)
        val tilt = env.params.f("tilt", 0.22f)

        Nd.rotatePlane(verts, n, dims, planeA, planeB, (speed + manualSpin) * dt)
        if (dims >= 3) Nd.rotatePlane(verts, n, dims, 0, 1, tilt * dt)
        if (dims >= 5) Nd.rotatePlane(verts, n, dims, 2, 4, tilt * 0.6f * dt)
        manualSpin *= (1f - 2f * dt).coerceIn(0f, 1f)

        // Every corner that crosses the far side of the extra axis is a note, so
        // the sound follows the shape rather than waiting for a whole turn.
        if (dims >= 3) {
            val axis = dims - 1
            for (i in 0 until n) {
                val w = verts[i * dims + axis]
                val wasBehind = behind[i]
                val isBehind = w < 0f
                if (isBehind != wasBehind) {
                    behind[i] = isBehind
                    if (isBehind) {
                        env.emit(EventKind.CROSS, 0.25f + 0.6f * (i.toFloat() / n), 0f, 0.55f)
                    }
                }
            }
        }

        // One full turn through the chosen plane is the beat of this sim.
        val period = if (speed > 0.001f) (2f * PI.toFloat() / speed) else 1e9f
        val completed = (elapsed / period).toLong()
        if (completed > turns) {
            turns = completed
            env.emit(EventKind.CROSS, 0.6f, 0f, 0.8f)
            if (turns == 2L) payoffFrame = true
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val n = 1 shl dims
        val scale = 0.62f
        val viewer = env.params.f("viewer", 3f)
        for (i in 0 until n) {
            Nd.project(verts, i * dims, dims, viewer, proj)
            screenX[i] = proj[0] * scale
            screenY[i] = proj[1] * scale
            screenD[i] = proj[2]
        }
        var e = 0
        while (e < edges.size) {
            val a = edges[e]
            val b = edges[e + 1]
            val depth = (screenD[a] + screenD[b]) * 0.5f
            val c = palette.at((depth * 0.7f).coerceIn(0f, 1f))
            g.line(screenX[a], screenY[a], screenX[b], screenY[b], 0.004f + depth * 0.004f, c, (0.25f + depth * 0.6f).coerceAtMost(1f))
            e += 2
        }
        for (i in 0 until n) {
            val d = screenD[i]
            val c = palette.at((d * 0.7f).coerceIn(0f, 1f))
            g.glow(screenX[i], screenY[i], 0.05f * d, c, 0.35f)
            g.disc(screenX[i], screenY[i], 0.012f * d, Palette.brighten(c, 0.2f))
        }
        if (payoffFrame) g.flash(0.3f)
    }

    override fun onInput(env: SimEnv, e: TouchEvent) {
        if (e.kind == TouchEvent.Kind.DRAG) manualSpin = (manualSpin + e.x * 0.6f).coerceIn(-3f, 3f)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "dimensions" to dims.toString(),
        "corners" to (1 shl dims).toString(),
        "plane" to Nd.planeName(planeA, planeB)
    )

    companion object {
        fun def(): SimDef = SimDef(
            id = "dimensions/hypercube",
            title = "Rotating hypercube",
            shelf = Shelf.DIMENSIONS,
            family = "Higher-dimensional objects",
            blurb = "A 4D cube turning through XW, where inside and outside swap.",
            formats = listOf(TrapFormat.LOOP, TrapFormat.GROW),
            heroLabel = "New angle",
            defaultPalette = "Aurora",
            typicalSeconds = 26f,
            params = listOf(
                Param.int("dims", "Dimensions", 2, Nd.MAX_DIMS, 4, card = Card.DATA),
                Param.int("planeA", "Spin plane, first axis", 0, Nd.MAX_DIMS - 1, 0, card = Card.MOTION, advancedOnly = true),
                Param.int("planeB", "Spin plane, second axis", 0, Nd.MAX_DIMS - 1, 3, card = Card.MOTION, advancedOnly = true),
                Param.float("spin", "Spin", 0.05f, 2f, 0.55f, card = Card.MOTION),
                Param.float("tilt", "Tilt", 0f, 1.5f, 0.22f, card = Card.MOTION),
                Param.float("viewer", "Viewer distance", 1.6f, 8f, 3f, card = Card.LOOK, advancedOnly = true)
            ),
            create = { HypercubeSim() }
        )
    }
}

/**
 * Flatland: a hypersphere drifting through a lower-dimensional world.
 *
 * The slice is all the inhabitants can see, so a whole sphere arrives as a
 * circle that appears from nothing, grows, shrinks and vanishes.
 */
class SlicingSim : Sim {

    private var dims = 3
    private var position = -1.4f
    private var direction = 1f
    private var payoffFrame = false
    private var passes = 0L
    private var peaked = false
    private var shape = 0

    override fun setup(env: SimEnv) {
        dims = env.params.i("dims", 3).coerceIn(2, 5)
        shape = env.params.choiceIndex("shape")
        position = -1.4f
        direction = 1f
        payoffFrame = false
        passes = 0
        peaked = false
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val speed = env.params.f("speed", 0.42f)
        position += direction * speed * dt
        if (position > 1.4f) { position = 1.4f; direction = -1f; passes++ }
        if (position < -1.4f) { position = -1.4f; direction = 1f; passes++ }

        val r = sliceSize()
        if (r > 0.001f) {
            env.emit(EventKind.STEP, (r / 0.9f).coerceIn(0f, 1f), 0f, 0.35f)
        }
        // The payoff is the widest moment: the sphere's equator passing through.
        if (!peaked && abs(position) < 0.02f) {
            peaked = true
            payoffFrame = true
            env.emit(EventKind.DONE, 0.9f, 0f, 1f)
        }
        if (abs(position) > 0.6f) peaked = false
    }

    private fun sliceSize(): Float = when (shape) {
        1 -> if (abs(position) > 0.9f) 0f else 0.9f                      // cube: constant
        2 -> max(0f, 0.9f - abs(position) * 0.64f)                       // cone-like
        else -> Nd.sliceRadius(0.9f, position)                           // hypersphere
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val r = sliceSize()
        val worldY = -0.1f

        // The flat world the slice lives in.
        g.line(-env.halfWidth * 0.95f, worldY, env.halfWidth * 0.95f, worldY, 0.004f, Palette.TEXT_SECONDARY, 0.4f)

        // The whole object, drawn faintly, so you can see what is passing through.
        val ghost = Palette.withAlpha(palette.at(0.25f), 0.22f)
        g.ring(0f, worldY - position * 0.72f, 0.9f * 0.72f, 0.004f, ghost, alpha = 0.5f)

        if (r > 0.001f) {
            val c = palette.at((r / 0.9f).coerceIn(0f, 1f))
            val halfWidthOnLine = r * 0.72f
            g.glow(0f, worldY, halfWidthOnLine * 1.6f, c, 0.45f)
            g.rect(0f, worldY, halfWidthOnLine, 0.018f, Palette.brighten(c, 0.25f))
            // What the slice would look like one dimension up.
            g.ring(0f, 0.52f, r * 0.42f, 0.006f, c, alpha = 0.9f)
        }
        if (payoffFrame) g.flash(0.35f)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "slice width" to String.format("%.2f", sliceSize()),
        "passes" to passes.toString()
    )

    companion object {
        fun def(): SimDef = SimDef(
            id = "dimensions/slicing",
            title = "Sphere through Flatland",
            shelf = Shelf.DIMENSIONS,
            family = "Slicing & Flatland",
            blurb = "A whole sphere arrives as a circle that grows and vanishes.",
            formats = listOf(TrapFormat.LOOP, TrapFormat.REVEAL, TrapFormat.CUT),
            heroLabel = "Send it through",
            defaultPalette = "Ocean",
            typicalSeconds = 22f,
            params = listOf(
                Param.choice(
                    "shape", "Object", listOf("Hypersphere", "Hypercube", "Cone"), 0,
                    card = Card.DATA, input = ParamInput.TILES
                ),
                Param.int("dims", "Dimensions", 2, Nd.MAX_DIMS, 3, card = Card.DATA),
                Param.float("speed", "Speed", 0.05f, 2f, 0.42f, card = Card.MOTION)
            ),
            macros = listOf(
                Macro(
                    "how-many-dims", "How many dimensions", Card.DATA,
                    mapOf("dims" to 2f..Nd.MAX_DIMS.toFloat()), 0.25f
                )
            ),
            create = { SlicingSim() }
        )
    }
}

/**
 * Random walks in one, two and three dimensions side by side.
 *
 * The payoff is a real theorem: a drunk walker always finds their way home on a
 * line or a grid, but in three dimensions they may wander forever.
 */
class RandomWalkSim : Sim {

    private var dims = 2
    private var walkers = 40
    private var pos = FloatArray(0)
    private var home = BooleanArray(0)
    private var steps = 0L
    private var returned = 0
    private var payoffFrame = false
    private var carry = 0f
    private var trailX = FloatArray(0)
    private var trailY = FloatArray(0)
    private var trailHead = 0
    private var trailFill = 0
    private val proj = FloatArray(3)

    override fun setup(env: SimEnv) {
        val p = env.params
        dims = p.i("dims", 2).coerceIn(1, Nd.MAX_DIMS)
        walkers = p.i("walkers", 40).coerceIn(1, 4000)
        pos = FloatArray(walkers * dims)
        home = BooleanArray(walkers)
        val trail = p.i("trail", 6000).coerceIn(500, 40000)
        trailX = FloatArray(trail)
        trailY = FloatArray(trail)
        trailHead = 0
        trailFill = 0
        steps = 0
        returned = 0
        payoffFrame = false
        paid = false
        carry = 0f
    }

    /** True once the payoff has fired, so it fires once. */
    private var paid = false

    /** Share of walkers home before the payoff: above 3D, not all can return. */
    private val RETURN_SHARE = 0.45f

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        carry += abs(dt) * env.params.f("rate", 260f)
        var budget = min(carry.toInt(), 4000)
        carry -= budget
        val stepSize = 0.045f
        while (budget > 0) {
            budget--
            steps++
            for (w in 0 until walkers) {
                if (home[w]) continue
                val base = w * dims
                val axis = env.rng.int(0, dims)
                pos[base + axis] += if (env.rng.bool()) stepSize else -stepSize
                if (Nd.length(pos, base, dims) < stepSize * 0.75f) {
                    home[w] = true
                    returned++
                    env.emit(EventKind.CROSS, 0.35f + 0.5f * (returned.toFloat() / walkers), 0f, 0.7f)
                    // Above three dimensions a random walk is not recurrent, so
                    // waiting for every walker would wait forever. The payoff is
                    // the moment most of those that can come back have.
                    if (!paid && returned >= (walkers * RETURN_SHARE).toInt().coerceAtLeast(1)) {
                        paid = true
                        payoffFrame = true
                        env.emit(EventKind.DONE, 1f, 0f, 1f)
                    }
                }
            }
            // Record the first walker's path as the visible trail.
            if (walkers > 0) {
                Nd.project(pos, 0, dims, 3f, proj)
                trailX[trailHead] = proj[0] * 0.8f
                trailY[trailHead] = proj[1] * 0.8f
                trailHead = (trailHead + 1) % trailX.size
                if (trailFill < trailX.size) trailFill++
            }
        }
        if (!payoffFrame && returned > 0 && returned.toFloat() / walkers >= 0.5f && steps > 2000) {
            // Half of them home is the moment the difference between dimensions shows.
            payoffFrame = halfMarked.compareAndSet(false, true)
        }
    }

    private val halfMarked = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        g.reservePoints(trailFill + walkers)
        for (k in 0 until trailFill) {
            val idx = (trailHead - 1 - k + trailX.size * 2) % trailX.size
            val age = k.toFloat() / trailFill
            g.point(trailX[idx], trailY[idx], 0.006f * (1.1f - age), Palette.withAlpha(palette.at(0.2f + age * 0.6f), 1f - age * 0.85f))
        }
        for (w in 0 until walkers) {
            Nd.project(pos, w * dims, dims, 3f, proj)
            val c = if (home[w]) Palette.SUCCESS else palette.at(w.toFloat() / max(1, walkers))
            g.point(proj[0] * 0.8f, proj[1] * 0.8f, 0.011f, c)
        }
        g.ring(0f, 0f, 0.035f, 0.004f, Palette.SUCCESS, alpha = 0.8f)
        if (payoffFrame) g.flash(0.3f)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "dimensions" to dims.toString(),
        "home" to "$returned/$walkers",
        "steps" to steps.toString()
    )

    companion object {
        fun def(): SimDef = SimDef(
            id = "dimensions/random-walk",
            title = "Will the walker get home?",
            shelf = Shelf.DIMENSIONS,
            family = "Physics in N dimensions",
            blurb = "Always on a line or a grid. In three dimensions, maybe never.",
            formats = listOf(TrapFormat.GUESS, TrapFormat.NUMBER_UP, TrapFormat.ESCAPE),
            heroLabel = "Send them out",
            defaultPalette = "Candy",
            typicalSeconds = 30f,
            params = listOf(
                Param.int("dims", "Dimensions", 1, Nd.MAX_DIMS, 2, card = Card.DATA),
                Param.int("walkers", "Walkers", 1, 4000, 40, card = Card.DATA, logScale = true),
                Param.int("trail", "Trail length", 500, 40000, 6000, card = Card.LOOK, logScale = true),
                Param.float("rate", "Steps", 10f, 3000f, 260f, card = Card.MOTION, unit = "/s", logScale = true)
            ),
            create = { RandomWalkSim() }
        )
    }
}

/**
 * Balls bouncing in a box of N dimensions, projected down to the screen.
 *
 * More dimensions means more walls, so the same ball count rattles far more
 * often: the sound alone tells you which dimension you are in.
 */
class NdBounceSim : Sim {

    private var dims = 4
    private var count = 24
    private var pos = FloatArray(0)
    private var vel = FloatArray(0)
    private var tint = FloatArray(0)
    private val proj = FloatArray(3)
    private var hits = 0L
    private var payoffFrame = false
    private var elapsed = 0f
    private var spin = 0f

    override fun setup(env: SimEnv) {
        val p = env.params
        dims = p.i("dims", 4).coerceIn(2, Nd.MAX_DIMS)
        count = p.i("balls", 24).coerceIn(1, 600)
        pos = FloatArray(count * dims)
        vel = FloatArray(count * dims)
        tint = FloatArray(count)
        val speed = p.f("speed", 0.7f)
        for (i in 0 until count) {
            val base = i * dims
            for (d in 0 until dims) {
                pos[base + d] = env.rng.range(-0.7f, 0.7f)
                vel[base + d] = env.rng.range(-speed, speed)
            }
            tint[i] = env.rng.nextFloat()
        }
        hits = 0
        payoffFrame = false
        elapsed = 0f
        spin = 0f
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        val laws = env.laws
        val e = if (laws.sticky) 0f else laws.bounce
        val wall = 0.85f
        var hitsThisStep = 0
        for (i in 0 until count) {
            val base = i * dims
            // Gravity acts along the second axis, the one that reads as "down".
            if (dims > 1) vel[base + 1] -= laws.gravityDirY * laws.gravity * 0.02f * dt
            for (d in 0 until dims) {
                pos[base + d] += vel[base + d] * dt
                if (pos[base + d] > wall) {
                    pos[base + d] = wall; vel[base + d] = -vel[base + d] * e; hitsThisStep++
                } else if (pos[base + d] < -wall) {
                    pos[base + d] = -wall; vel[base + d] = -vel[base + d] * e; hitsThisStep++
                }
            }
        }
        if (hitsThisStep > 0) {
            hits += hitsThisStep
            env.emit(
                EventKind.HIT,
                (hitsThisStep.toFloat() / max(1, count)).coerceIn(0f, 1f),
                0f,
                min(1f, 0.3f + hitsThisStep * 0.04f)
            )
        }
        spin += env.params.f("spin", 0.4f) * dt
        if (dims >= 4) Nd.rotatePlane(pos, count, dims, 0, 3, env.params.f("spin", 0.4f) * dt)
        if (!payoffFrame && elapsed > 16f && elapsed < 16f + abs(dt) * 1.5f) payoffFrame = true
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val box = 0.85f * 0.72f
        g.rect(0f, 0f, box, box, Palette.TEXT_SECONDARY, 0f, 0.0f)
        g.line(-box, -box, box, -box, 0.003f, Palette.TEXT_SECONDARY, 0.35f)
        g.line(-box, box, box, box, 0.003f, Palette.TEXT_SECONDARY, 0.35f)
        g.line(-box, -box, -box, box, 0.003f, Palette.TEXT_SECONDARY, 0.35f)
        g.line(box, -box, box, box, 0.003f, Palette.TEXT_SECONDARY, 0.35f)
        for (i in 0 until count) {
            Nd.project(pos, i * dims, dims, 3f, proj)
            val d = proj[2]
            val c = palette.at(tint[i])
            g.glow(proj[0] * 0.72f, proj[1] * 0.72f, 0.06f * d, c, 0.35f)
            g.disc(proj[0] * 0.72f, proj[1] * 0.72f, 0.022f * d, c)
        }
        if (payoffFrame) g.flash(0.3f)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "dimensions" to dims.toString(),
        "walls" to (dims * 2).toString(),
        "bounces" to hits.toString()
    )

    companion object {
        fun def(): SimDef = SimDef(
            id = "dimensions/nd-bounce",
            title = "Bouncing in N dimensions",
            shelf = Shelf.DIMENSIONS,
            family = "Physics in N dimensions",
            blurb = "More dimensions, more walls, a busier rhythm.",
            formats = listOf(TrapFormat.SONG, TrapFormat.LOOP, TrapFormat.ESCAPE),
            usesLaws = true,
            heroLabel = "Shake the box",
            defaultPalette = "Ember",
            typicalSeconds = 26f,
            params = listOf(
                Param.int("dims", "Dimensions", 2, Nd.MAX_DIMS, 4, card = Card.DATA),
                Param.int("balls", "Balls", 1, 600, 24, card = Card.DATA, logScale = true),
                Param.float("speed", "Speed", 0.1f, 2.5f, 0.7f, card = Card.MOTION),
                Param.float("spin", "View spin", 0f, 2f, 0.4f, card = Card.MOTION)
            ),
            macros = listOf(
                Macro("chaos", "Chaos", Card.MOTION, mapOf("speed" to 0.2f..2.5f), 0.3f)
            ),
            create = { NdBounceSim() }
        )
    }
}

/**
 * The volume of a unit ball against its dimension: it climbs to a peak at five
 * and then falls away to almost nothing, which is the most surprising fact in
 * high-dimensional geometry and makes a clean twenty-second story.
 */
class BallVolumeSim : Sim {

    private var upTo = 20
    private var shown = 0f
    private var payoffFrame = false
    private var peakAnnounced = false

    override fun setup(env: SimEnv) {
        upTo = env.params.i("upto", 20).coerceIn(4, 40)
        shown = 0f
        payoffFrame = false
        peakAnnounced = false
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val before = shown.toInt()
        shown = min(upTo.toFloat(), shown + env.params.f("rate", 2.2f) * abs(dt))
        val after = shown.toInt()
        if (after > before) {
            val v = Nd.unitBallVolume(after).toFloat()
            val t = (v / 5.3f).coerceIn(0f, 1f)
            env.emit(EventKind.WRITE, t, 0f, 0.6f)
            if (!peakAnnounced && after >= 5) {
                peakAnnounced = true
                payoffFrame = true
                env.emit(EventKind.DONE, 1f, 0f, 1f)
            }
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val halfW = env.halfWidth * 0.92f
        val barW = 2f * halfW / upTo
        val maxV = 5.264f
        for (d in 1..min(upTo, shown.toInt())) {
            val v = Nd.unitBallVolume(d).toFloat()
            val h = (v / maxV) * 1.5f
            val x = -halfW + (d - 0.5f) * barW
            val isPeak = d == 5
            val c = if (isPeak) Palette.SUCCESS else palette.at(d.toFloat() / upTo)
            g.bar(x, -0.85f, barW * 0.4f, h, c)
            if (isPeak) g.glow(x, -0.85f + h, barW * 2.4f, Palette.SUCCESS, 0.5f)
        }
        g.line(-halfW, -0.85f, halfW, -0.85f, 0.004f, Palette.TEXT_SECONDARY, 0.5f)
        if (payoffFrame) g.flash(0.3f)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = shown >= upTo

    override fun counters(): List<Pair<String, String>> = listOf(
        "dimension" to shown.toInt().toString(),
        "volume" to String.format("%.3f", Nd.unitBallVolume(max(1, shown.toInt())))
    )

    companion object {
        fun def(): SimDef = SimDef(
            id = "dimensions/ball-volume",
            title = "The ball that shrinks",
            shelf = Shelf.DIMENSIONS,
            family = "Dimension-scaling math",
            blurb = "Unit ball volume peaks at five dimensions, then falls to nothing.",
            formats = listOf(TrapFormat.NUMBER_UP, TrapFormat.GROW),
            heroLabel = "Count up",
            defaultPalette = "Colour-safe",
            typicalSeconds = 14f,
            params = listOf(
                Param.int("upto", "Up to dimension", 4, 40, 20, card = Card.DATA),
                Param.float("rate", "Speed", 0.4f, 12f, 2.2f, card = Card.MOTION, unit = "/s")
            ),
            create = { BallVolumeSim() }
        )
    }
}

object DimensionSims {
    fun defs(): List<SimDef> = listOf(
        HypercubeSim.def(),
        SlicingSim.def(),
        RandomWalkSim.def(),
        NdBounceSim.def(),
        BallVolumeSim.def()
    )
}
