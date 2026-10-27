package app.hypnotica.sims.dimensions

import app.hypnotica.core.nd.Nd
import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Macro
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.recipe.ParamInput
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.LawKnob
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
    private var paidTurn = false
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
        // Both sliders offer 0..MAX_DIMS-1 while only 0..dims-1 axes exist. The
        // first wraps, so every position names a real axis.
        planeA = Math.floorMod(p.i("planeA", 0), dims)
        // The slider's number is the axis, capped at the highest axis this
        // many dimensions has, and moved on one if it lands on the first axis.
        // Two earlier mappings each broke the shipped tesseract in a different
        // way. An offset over the remaining axes sent the shipped 3 to axis 1,
        // so the default turned in the flat x–y plane — a spinning cube, no
        // corner ever passing through the fourth dimension. Its fix put 4D
        // right but left 3D spinning flat in the screen plane, and in five and
        // six dimensions the spin missed the axis the notes listen on, so a
        // six-cube made one sound in a whole turn. With this rule the shipped
        // value turns x–z in 3D and x–w from 4D up, and the two ends of the
        // slider still land on different axes.
        val wanted = min(p.i("planeB", 3).coerceAtLeast(0), dims - 1)
        planeB = if (wanted == planeA) (planeA + 1) % dims else wanted
        // After planeB, which it is measured against.
        behind = BooleanArray(n) { verts[it * dims + planeB] < 0f }
        elapsed = 0f
        payoffFrame = false
        turns = 0L
        manualSpin = 0f
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        elapsed += abs(dt)
        val n = 1 shl dims
        val speed = env.params.f("spin", 0.55f)
        val tilt = env.params.f("tilt", 0.22f)

        Nd.rotatePlane(verts, n, dims, planeA, planeB, (speed + manualSpin) * dt)
        if (dims >= 3) Nd.rotatePlane(verts, n, dims, 0, 1, tilt * dt)
        if (dims >= 5) Nd.rotatePlane(verts, n, dims, 2, 4, tilt * 0.6f * dt)
        // Without this the sixth axis never moved, so choosing six dimensions
        // added thirty-two corners and no new motion at all.
        if (dims >= 6) Nd.rotatePlane(verts, n, dims, 3, 5, tilt * 0.45f * dt)
        manualSpin *= (1f - 2f * dt).coerceIn(0f, 1f)

        // Every corner that crosses the far side of the extra axis is a note, so
        // the sound follows the shape rather than waiting for a whole turn.
        if (dims >= 3) {
            // The axis the spin passes through, so every turn is heard. It was
            // always the last axis, which from five dimensions up the spin
            // never touches.
            val axis = planeB
            for (i in 0 until n) {
                val w = verts[i * dims + axis]
                val wasBehind = behind[i]
                val isBehind = w < 0f
                if (isBehind != wasBehind) {
                    behind[i] = isBehind
                    // Both ways through, an octave apart. Only the trip behind
                    // used to sound, and the crossings come in bunches, so half
                    // of every turn was silent — twelve seconds at a stretch,
                    // measured, on a sim that never stops moving.
                    val pitch = 0.25f + 0.6f * (i.toFloat() / n)
                    env.emit(
                        EventKind.CROSS,
                        if (isBehind) pitch else (pitch + 0.15f).coerceAtMost(1f),
                        0f,
                        if (isBehind) 0.55f else 0.4f
                    )
                }
            }
        }

        // One full turn through the chosen plane is the beat of this sim.
        val period = if (speed > 0.001f) (2f * PI.toFloat() / speed) else 1e9f
        val completed = (elapsed / period).toLong()
        if (completed > turns) {
            turns = completed
            env.emit(EventKind.CROSS, 0.6f, 0f, 0.8f)
            // A full turn brings every corner back where it started, which is
            // the moment worth waiting for. It used to want the second turn and
            // test for it with ==, so below a spin of about 0.48 the payoff
            // landed after the clip had ended and TickTock threw every seed
            // away. One turn is enough when a turn is slow.
            if (!paidTurn && (turns >= 2L || elapsed > SLOW_TURN_SECONDS)) {
                paidTurn = true
                payoffFrame = true
            }
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
        // Nearness, spread over the range this frame actually spans. The raw
        // factor only varies by a fifth either side of one, so used directly it
        // gave every corner nearly the same colour; stretched, near and far are
        // plainly different at two dimensions and at six.
        var lowest = Float.MAX_VALUE
        var highest = -Float.MAX_VALUE
        for (i in 0 until n) {
            if (screenD[i] < lowest) lowest = screenD[i]
            if (screenD[i] > highest) highest = screenD[i]
        }
        val span = (highest - lowest).coerceAtLeast(1e-4f)
        fun nearness(v: Float): Float = ((v - lowest) / span).coerceIn(0f, 1f)

        var e = 0
        while (e < edges.size) {
            val a = edges[e]
            val b = edges[e + 1]
            val near = (nearness(screenD[a]) + nearness(screenD[b])) * 0.5f
            val c = palette.at(0.15f + near * 0.75f)
            g.line(
                screenX[a], screenY[a], screenX[b], screenY[b],
                0.003f + near * 0.005f, c, 0.3f + near * 0.65f
            )
            e += 2
        }
        for (i in 0 until n) {
            val near = nearness(screenD[i])
            val c = palette.at(0.15f + near * 0.75f)
            g.glow(screenX[i], screenY[i], 0.03f + 0.045f * near, c, 0.25f + 0.25f * near)
            g.disc(screenX[i], screenY[i], 0.008f + 0.011f * near, Palette.brighten(c, 0.2f))
        }
    }

    override fun onInput(env: SimEnv, e: TouchEvent) {
        if (e.kind == TouchEvent.Kind.DRAG) manualSpin = (manualSpin + e.x * 0.6f).coerceIn(-3f, 3f)
    }

    /** Drawn at a fixed size; on a phone held upright the view pulls back to fit it. */
    override fun frameHalfWidth(env: SimEnv): Float = 0.65f

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "dimensions" to dims.toString(),
        "corners" to (1 shl dims).toString(),
        "plane" to Nd.planeName(planeA, planeB)
    )

    companion object {
        /**
         * When a turn is slower than this, one is enough for the payoff.
         *
         * The slider reaches a spin of 0.05, which is four minutes a turn, so
         * waiting for the second turn put the payoff long after the clip had
         * ended and TickTock threw every seed away.
         */
        const val SLOW_TURN_SECONDS = 14f

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
            loopSeconds = 10.61f,
            params = listOf(
                Param.int("dims", "Dimensions", 2, Nd.MAX_DIMS, 4, card = Card.DATA),
                // Which plane turns is the most interesting thing about a
                // higher-dimensional object, and hiding it behind Advanced meant
                // raising the dimension count to six added thirty-two corners
                // and no new plane to watch them turn in. Both are integers, so
                // Evolve could not reach them either.
                Param.int("planeA", "Spin plane, first axis", 0, Nd.MAX_DIMS - 1, 0, card = Card.MOTION),
                Param.int("planeB", "Spin plane, second axis", 0, Nd.MAX_DIMS - 1, 3, card = Card.MOTION),
                Param.float("spin", "Spin", 0.05f, 2f, 0.55f, card = Card.MOTION),
                Param.float("tilt", "Tilt", 0f, 1.5f, 0.22f, card = Card.MOTION),
                Param.float("viewer", "Viewer distance", 1.6f, 8f, 3f, card = Card.LOOK)
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
 *
 * The dimension is the point of the sim, so it has to be visible. The radius of
 * the slice happens not to depend on it — the slice of an n-ball at distance d
 * is an (n-1)-ball of radius sqrt(R^2 - d^2) whatever n is — but what the
 * inhabitants would actually measure is the content of that slice, which is
 * proportional to radius^(n-1). In two dimensions the thing arrives gently; in
 * six it is nothing at all until the last moment and then blooms. That curve,
 * the wireframe of the object passing through, and the number of corners it has
 * are all driven from [dims] here.
 */
class SlicingSim : Sim {

    private var dims = 3
    private var position = -1.4f
    private var direction = 1f
    private var payoffFrame = false
    private var passes = 0L
    private var peaked = false
    private var shape = 0
    private var spin = 0f
    private var verts = FloatArray(0)
    private var edges = IntArray(0)
    private var proj = FloatArray(3)

    override fun setup(env: SimEnv) {
        dims = env.params.i("dims", 3).coerceIn(2, Nd.MAX_DIMS)
        shape = env.params.choiceIndex("shape")
        spin = 0f
        // The object itself, in full: more dimensions, visibly more structure.
        verts = Nd.hypercubeVertices(dims)
        edges = Nd.hypercubeEdges(dims)
        proj = FloatArray(maxOf(3, dims))
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

        spin += dt * 0.35f
        if (verts.isNotEmpty()) {
            Nd.rotatePlane(verts, 1 shl dims, dims, 0, dims - 1, dt * 0.35f)
        }

        // The note follows what the flat world measures, not the radius, so a
        // six-dimensional pass is heard as a sudden arrival too.
        val content = sliceContent()
        if (content > 0.001f) {
            env.emit(EventKind.STEP, content.coerceIn(0f, 1f), 0f, 0.35f)
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

    /**
     * What the flat world would measure, as a fraction of the widest it ever
     * gets: the (dims - 1)-dimensional content of the slice. This is where the
     * dimension shows itself — the same radius means far less content the
     * higher the dimension, so the arrival sharpens into a spike.
     */
    private fun sliceContent(): Float {
        val r = sliceSize()
        if (r <= 0f) return 0f
        val n = (dims - 1).coerceAtLeast(1)
        val fraction = (r / 0.9f).coerceIn(0f, 1f)
        var v = fraction
        for (k in 2..n) v *= fraction
        return v
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val r = sliceSize()
        val content = sliceContent()
        val worldY = -0.1f
        val centreY = worldY - position * 0.72f

        // The flat world the slice lives in.
        g.line(-env.halfWidth * 0.95f, worldY, env.halfWidth * 0.95f, worldY, 0.004f, Palette.TEXT_SECONDARY, 0.4f)

        // The whole object, drawn faintly, so you can see what is passing
        // through. A hypercube is drawn as its own wireframe, so its corners
        // and edges multiply as the dimension rises; a round object as nested
        // shells, one per dimension.
        val ghost = Palette.withAlpha(palette.at(0.25f), 0.22f)
        if (shape == 1) {
            drawWireframe(g, centreY, ghost)
        } else {
            for (d in 1 until dims) {
                val shell = 0.9f * 0.72f * (1f - (d - 1) * 0.16f)
                if (shell > 0.02f) g.ring(0f, centreY, shell, 0.004f, ghost, alpha = 0.45f)
            }
        }

        if (r > 0.001f) {
            val c = palette.at(content.coerceIn(0f, 1f))
            val halfWidthOnLine = r * 0.72f
            // The glow and the thickness follow the content, the length follows
            // the radius: the slice is as wide as the geometry says and as
            // bright as the flat world would find it.
            g.glow(0f, worldY, halfWidthOnLine * (0.6f + content), c, 0.2f + content * 0.5f)
            g.rect(
                0f, worldY, halfWidthOnLine, 0.008f + 0.02f * content,
                Palette.brighten(c, 0.25f)
            )
            // What the slice would look like one dimension up.
            g.ring(0f, 0.52f, r * 0.42f, 0.006f, c, alpha = 0.35f + 0.6f * content)
        }
    }

    private fun drawWireframe(g: G, centreY: Float, colour: Int) {
        if (verts.isEmpty() || edges.isEmpty()) return
        val n = 1 shl dims
        val scale = 0.42f
        var i = 0
        while (i + 1 < edges.size) {
            val a = edges[i]
            val b = edges[i + 1]
            i += 2
            if (a >= n || b >= n) continue
            Nd.project(verts, a * dims, dims, 3f, proj)
            val ax = proj[0] * scale
            val ay = proj[1] * scale
            Nd.project(verts, b * dims, dims, 3f, proj)
            g.line(ax, centreY + ay, proj[0] * scale, centreY + proj[1] * scale, 0.003f, colour)
        }
    }

    /** The widest shell is 0.65 across; the object travels up and down through the frame on purpose. */
    override fun frameHalfWidth(env: SimEnv): Float = 0.68f

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "dimensions" to dims.toString(),
        "slice width" to String.format(java.util.Locale.US, "%.2f", sliceSize()),
        // What the flat world measures: a (dims - 1)-dimensional content.
        "slice seen" to String.format(java.util.Locale.US, "%.3f", sliceContent()),
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
            typicalSeconds = 8f,
            loopSeconds = 13.25f,
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
        wandered = BooleanArray(walkers)
        val trail = p.i("trail", 6000).coerceIn(500, 40000)
        trailX = FloatArray(trail)
        trailY = FloatArray(trail)
        trailHead = 0
        trailFill = 0
        steps = 0
        returned = 0
        payoffFrame = false
        paid = false
        elapsed = 0f
        lastReturned = -1
        settledFor = 0f
        viewScale = 1f
        viewer = VIEWER
        carry = 0f
        wandered = BooleanArray(walkers)
        textureClock = 0f
    }

    /** True once the payoff has fired, so it fires once. */
    private var paid = false

    /**
     * The payoff is the verdict, not a quota.
     *
     * A share of walkers home cannot be the gate: Polya's theorem says a walk
     * on a line or a grid comes home with probability one and a walk in three
     * dimensions with probability 0.34 — in infinite time. Measured over a
     * thirty-second run, two and a half per cent of three-dimensional walkers
     * get home and none at all above four, so a 35% gate meant the sim named
     * "Will the walker get home?" could never pay off at the dimension it is
     * named after. What is worth waiting for is the answer settling: the count
     * stops climbing, and that is the result.
     */
    private val SETTLED_SECONDS = 4.5f

    /** Nothing is settled before this much of the run has been seen. */
    private val EARLIEST_PAYOFF = 8f

    /** How far out a walker must get before its return is a return. */
    private val AWAY_STEPS = 5f

    /** Which walkers have been far enough from the origin to count. */
    private var wandered = BooleanArray(0)

    private var textureClock = 0f

    /** Micro-steps between texture notes. */
    private val TEXTURE_EVERY = 90f

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        updateView()
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
                val distance = Nd.length(pos, base, dims)
                // A walker has to get properly lost before coming back counts.
                // Every walker starts at the origin, so without this the first
                // wobble back through zero was "the drunk walker got home" —
                // the payoff landed a tenth of a second into a thirty-second run.
                if (!wandered[w]) {
                    if (distance > stepSize * AWAY_STEPS) wandered[w] = true
                    continue
                }
                if (distance < stepSize * 0.75f) {
                    home[w] = true
                    returned++
                    env.emit(EventKind.CROSS, 0.35f + 0.5f * (returned.toFloat() / walkers), 0f, 0.7f)
                }
            }
            // The swarm spreading is itself the sound, so the run is never
            // silent between returns.
            textureClock += 1f
            if (textureClock >= TEXTURE_EVERY) {
                textureClock = 0f
                var spread = 0f
                var sampled = 0
                var k = 0
                while (k < walkers) {
                    spread += Nd.length(pos, k * dims, dims)
                    sampled++
                    k += maxOf(1, walkers / 24)
                }
                if (sampled > 0) {
                    val mean = (spread / sampled / 1.6f).coerceIn(0f, 1f)
                    env.emit(EventKind.STEP, mean, 0f, 0.22f + mean * 0.3f)
                }
            }

            // Record the first walker's path as the visible trail. Stored in
            // world units at the scale of the moment, so the trail keeps its
            // shape as the view pulls back.
            if (walkers > 0) {
                Nd.project(pos, 0, dims, viewer, proj)
                // Stored as projected, unscaled: the view pulls back as the
                // cloud grows, and a trail written at an older scale would fly
                // off the edges as it did.
                trailX[trailHead] = proj[0]
                trailY[trailHead] = proj[1]
                trailHead = (trailHead + 1) % trailX.size
                if (trailFill < trailX.size) trailFill++
            }
        }
        // The verdict: once the count has stopped climbing for a few seconds,
        // the answer is in, and what it is depends entirely on the dimension —
        // all of them on a line, most of them on a grid, hardly any in three.
        // That contrast is the whole sim, and it is what gets the payoff.
        elapsed += abs(dt)
        if (returned != lastReturned) {
            lastReturned = returned
            settledFor = 0f
        } else {
            settledFor += abs(dt)
        }
        if (!paid && elapsed > EARLIEST_PAYOFF && settledFor > SETTLED_SECONDS) {
            paid = true
            payoffFrame = true
            env.emit(EventKind.DONE, 1f, 0f, 1f)
        }
    }

    private var elapsed = 0f
    private var lastReturned = -1
    private var settledFor = 0f

    /**
     * How far from the origin the cloud has spread, so the view can hold it.
     *
     * A walk is unbounded and [Nd.project] divides by a fixed viewer distance,
     * so once a coordinate passed the viewer every point was multiplied by
     * twenty: three quarters of the walkers left the screen in three dimensions
     * and the furthest reached fourteen hundred screen-widths out in six. The
     * cloud is scaled to fit instead, which is also the only way to see what it
     * is doing.
     */
    private var viewScale = 1f
    private var viewer = VIEWER

    /**
     * Pull the camera back as the cloud grows, then scale what comes out to fit.
     *
     * Scaling the world coordinates alone is not enough: the projection divides
     * by (viewer - w), so the moment a walker passed the fixed viewer distance
     * every point was multiplied by twenty and left the screen however the
     * world was scaled. Keeping the viewer two and a half times the cloud's
     * radius bounds that factor at 1.7, and then one scale holds the whole
     * cloud in frame.
     */
    private fun updateView() {
        var furthest = 0f
        var w = 0
        val stride = maxOf(1, walkers / 64)
        while (w < walkers) {
            val d = Nd.length(pos, w * dims, dims)
            if (d > furthest) furthest = d
            w += stride
        }
        val radius = furthest.coerceAtLeast(0.2f)
        viewer = maxOf(VIEWER, radius * VIEWER_AHEAD)
        val target = 0.85f / (radius * PROJECTION_GAIN)
        // Eases towards the target so the view does not jump every frame.
        viewScale += (target.coerceIn(0.01f, 4f) - viewScale) * 0.06f
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        g.reservePoints(trailFill + walkers)
        for (k in 0 until trailFill) {
            val idx = (trailHead - 1 - k + trailX.size * 2) % trailX.size
            val age = k.toFloat() / trailFill
            g.point(
                trailX[idx] * viewScale, trailY[idx] * viewScale,
                0.006f * (1.1f - age),
                Palette.withAlpha(palette.at(0.2f + age * 0.6f), 1f - age * 0.85f)
            )
        }
        for (w in 0 until walkers) {
            Nd.project(pos, w * dims, dims, viewer, proj)
            val c = if (home[w]) Palette.SUCCESS else palette.at(w.toFloat() / max(1, walkers))
            g.point(proj[0] * viewScale, proj[1] * viewScale, 0.011f, c)
        }
        // Home, at the same scale as everything else.
        g.ring(0f, 0f, (0.035f * viewScale).coerceIn(0.012f, 0.1f), 0.004f, Palette.SUCCESS, alpha = 0.8f)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "dimensions" to dims.toString(),
        "home" to "$returned/$walkers",
        "steps" to steps.toString()
    )

    companion object {
        /**
         * Far enough back that a projection never divides by nearly zero. The
         * walk is scaled into view separately, so this only sets how strong the
         * perspective is.
         */
        const val VIEWER = 6f

        /** The viewer sits this many cloud radii back, which bounds the gain. */
        const val VIEWER_AHEAD = 2.5f

        /** The most the projection can magnify once the viewer keeps ahead. */
        const val PROJECTION_GAIN = 1.7f

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

    /**
     * Which of the 2*dims walls each ball has struck, as a bit per wall.
     *
     * It used to be one set of flags for the whole box, and the payoff was
     * "every wall struck by anything" — which with the default two dozen balls
     * is over in 1.2 seconds, a fifth of the way through the setup beat, and
     * barely later in six dimensions than in two. The comment below claimed the
     * moment arrives later the more dimensions there are; with that many balls
     * it did not. One ball having reached all twelve walls does, and it is a
     * fact about the sim's subject rather than about how many balls there are.
     */
    private var visited = IntArray(0)
    private var axesToured = 0
    private var paidOff = false
    /** Bounces per second, smoothed, and what the build settled at. */
    private var rateNow = 0f
    private var buildRate = 0f
    private var buildSamples = 0

    override fun setup(env: SimEnv) {
        val p = env.params
        dims = p.i("dims", 4).coerceIn(2, Nd.MAX_DIMS)
        count = p.i("balls", 24).coerceIn(1, 600)
        visited = IntArray(count)
        axesToured = 0
        paidOff = false
        rateNow = 0f
        buildRate = 0f
        buildSamples = 0
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
                    visited[i] = visited[i] or (1 shl d)
                } else if (pos[base + d] < -wall) {
                    pos[base + d] = -wall; vel[base + d] = -vel[base + d] * e; hitsThisStep++
                    visited[i] = visited[i] or (1 shl d)
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

        // One ball having struck every wall: in two dimensions that is four
        // walls, in six it is twelve, so the moment arrives later the more
        // dimensions there are — which is the point of the sim. A stopwatch at
        // sixteen seconds said nothing about whether anything had happened.
        // The payoff is the rhythm changing decisively from the one the build
        // established — thinning out as the bounces damp away, or thickening if
        // the laws are returning more energy than they take.
        //
        // Two earlier conditions were measured and thrown away. "Every wall
        // struck at least once by anything" was over in 1.2 seconds with the
        // default two dozen balls, a twentieth of the way into a run the design
        // doc puts the payoff at 70% of — and barely later in six dimensions
        // than in two, despite the comment that used to sit here claiming that
        // was the point. "The fullest beat so far" sounds right for a rhythm
        // and cannot work in a damped box: the default bounce returns 88% of
        // the energy, so the busiest beat is always an early one and a late
        // step has nothing left to beat it with. Of forty-seven runs that fell
        // through to the backstop, forty-two were still bouncing happily.
        //
        // A decisive change in the rate is the event this sim actually has, it
        // arrives on its own schedule, and it holds in both directions.
        val dtAbs = abs(dt)
        if (dtAbs > 1e-6f) {
            val instant = hitsThisStep / dtAbs
            // About a third of a second of smoothing: enough that one busy step
            // is not an event, little enough that the thinning is not hidden.
            rateNow += (instant - rateNow) * min(1f, dtAbs * 3f)
        }
        if (!paidOff) {
            when {
                // The first second is the drop into the box, not the rhythm.
                elapsed < SETTLE_SECONDS -> Unit
                elapsed < BUILD_SECONDS -> {
                    buildRate += rateNow
                    buildSamples++
                }
                else -> {
                    val established = if (buildSamples > 0) buildRate / buildSamples else 0f
                    val thinned = established > 0.5f && rateNow < established * THINNED
                    val thickened = established > 0.5f && rateNow > established * THICKENED
                    if (thinned || thickened || elapsed > BUILD_SECONDS + BACKSTOP_SECONDS) {
                        paidOff = true
                        payoffFrame = true
                        env.emit(EventKind.DONE, 0.8f, 0f, 1f)
                    }
                }
            }
            val full = (1 shl dims) - 1
            var toured = 0
            for (i in 0 until count) if (visited[i] == full) toured++
            axesToured = toured
        }
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
    }

    /**
     * "Shake the box": a kick, in every dimension, to the balls already in it.
     * The button used to restart the run — new balls, the label promising one
     * thing and the button doing another. A box whose balls have settled under
     * gravity is the one that most wants shaking.
     */
    override fun hero(env: SimEnv) {
        val kick = env.params.f("speed", 0.7f) * SHAKE
        for (i in 0 until count * dims) vel[i] += env.rng.range(-kick, kick)
        env.emit(EventKind.HIT, 0.8f, 0f, 0.9f)
    }

    /** Drawn at a fixed size; on a phone held upright the view pulls back to fit it. */
    override fun frameHalfWidth(env: SimEnv): Float = 0.74f

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> = listOf(
        "dimensions" to dims.toString(),
        "all axes" to "$axesToured/$count balls",
        "bounces a second" to String.format(java.util.Locale.US, "%.0f", rateNow),
        "bounces" to hits.toString()
    )

    companion object {
        /** A shake's kick, as a share of the speed slider. */
        private const val SHAKE = 1.6f

        /** The drop into the box, before there is a rhythm to measure. */
        private const val SETTLE_SECONDS = 1.5f
        /** Long enough for the rhythm to establish what normal is. */
        private const val BUILD_SECONDS = 14f
        /** A box that never changes its mind still owes a payoff. */
        private const val BACKSTOP_SECONDS = 9f
        /** Thinned to this share of the established rate. */
        private const val THINNED = 0.45f
        /** Or thickened to this much of it, for a box that gains energy. */
        private const val THICKENED = 1.8f

        fun def(): SimDef = SimDef(
            id = "dimensions/nd-bounce",
            title = "Bouncing in N dimensions",
            shelf = Shelf.DIMENSIONS,
            family = "Physics in N dimensions",
            blurb = "More dimensions, more walls, a busier rhythm.",
            formats = listOf(TrapFormat.SONG, TrapFormat.ESCAPE),
            lawKnobs = setOf(LawKnob.GRAVITY, LawKnob.GRAVITY_DIRECTION, LawKnob.BOUNCE, LawKnob.CONTACT),
            heroLabel = "Shake the box",
            heroRestarts = false,
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
        // Each bar rises while its dimension is being counted, rather than
        // appearing whole once it has been: the first bar used to arrive a
        // second and a half in, over an empty screen.
        for (d in 1..min(upTo, kotlin.math.ceil(shown).toInt())) {
            val v = Nd.unitBallVolume(d).toFloat()
            val rise = (shown - (d - 1)).coerceIn(0f, 1f)
            val eased = 1f - (1f - rise) * (1f - rise)
            val h = (v / maxV) * 1.5f * eased
            val x = -halfW + (d - 0.5f) * barW
            val isPeak = d == 5
            val c = if (isPeak) Palette.SUCCESS else palette.at(d.toFloat() / upTo)
            g.bar(x, -0.85f, barW * 0.4f, h, c)
            if (isPeak) g.glow(x, -0.85f + h, barW * 2.4f, Palette.SUCCESS, 0.5f)
        }
        g.line(-halfW, -0.85f, halfW, -0.85f, 0.004f, Palette.TEXT_SECONDARY, 0.5f)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = shown >= upTo

    override fun counters(): List<Pair<String, String>> = listOf(
        // The dimension whose bar is rising, so it never reads zero.
        "dimension" to max(1, kotlin.math.ceil(shown).toInt()).toString(),
        "volume" to String.format(java.util.Locale.US, "%.3f", Nd.unitBallVolume(max(1, kotlin.math.ceil(shown).toInt())))
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
                // The peak at five dimensions is the moment this sim exists
                // for, and at 2.2 dimensions a second it arrived 2.3 seconds
                // in — a sixth of the way into the run, with the blurb's
                // "then falls to nothing" over before anyone had read the
                // first number. A bar and a bit a second is a pace you can
                // follow, and the slider still goes to twelve.
                Param.float("rate", "Speed", 0.4f, 12f, 0.7f, card = Card.MOTION, unit = "/s")
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
