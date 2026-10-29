package app.hypnotica.sims.physics

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.recipe.ParamInput
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.LawKnob
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
 * A hydraulic press, coming down on a block.
 *
 * The block is a lattice of masses on springs, so it squashes, bulges out at
 * the sides and pushes back. Every spring has a limit: stretched past it, it
 * snaps. A tough block flattens into a pancake; a brittle one holds, holds,
 * and then goes all at once. The creak climbs as the strain does, so the ear
 * hears it coming before the eye sees it.
 */
class PressSim : Sim {

    private var side = 14
    private var n = 0
    private var x = FloatArray(0)
    private var y = FloatArray(0)
    private var px = FloatArray(0)
    private var py = FloatArray(0)
    private var sa = IntArray(0)
    private var sb = IntArray(0)
    private var rest = FloatArray(0)
    private var alive = BooleanArray(0)
    private var axial = BooleanArray(0)
    private var lastDh = 0f
    private var springs = 0
    private var broken = 0
    private var plate = 0.2f
    private var plateStart = 0.2f
    private var plateStop = -0.8f
    private var touched = false
    private var elapsed = 0f
    private var payoffFrame = false
    private var gaveAt = -1f
    private var bottomAt = -1f
    private var creak = 0f
    private var halfW = 0.46f
    private var size = 0.5f
    private var limit = 0.3f
    private var stiff = 1f
    private var maxStrain = 0f
    private var bite = 0.7f

    override fun setup(env: SimEnv) {
        val p = env.params
        side = p.i("detail", 14).coerceIn(4, MAX_SIDE)
        halfW = env.halfWidth
        size = min(halfW * 1.1f, 0.6f)
        val material = p.choiceIndex("material")
        // How far a spring stretches before it snaps, and how hard it pushes back.
        limit = MATERIAL_LIMIT[material.coerceIn(0, MATERIAL_LIMIT.size - 1)]
        stiff = MATERIAL_STIFF[material.coerceIn(0, MATERIAL_STIFF.size - 1)]
        // Harder things are squeezed slower: glass gives after a little, so
        // the little is drawn out.
        bite = MATERIAL_BITE[material.coerceIn(0, MATERIAL_BITE.size - 1)]
        // The lattice, cut to the object's shape: a block, a ball, a pyramid.
        val shape = p.choiceIndex("object")
        val index = IntArray(side * side) { -1 }
        var kept = 0
        for (r in 0 until side) for (c in 0 until side) {
            if (inShape(shape, c, r)) index[r * side + c] = kept++
        }
        n = kept
        x = FloatArray(n); y = FloatArray(n)
        val step = size / (side - 1)
        val floor = FLOOR + PARTICLE
        // A ball rests on its lowest point, not on the lattice's bottom row.
        var lowest = side
        for (r in 0 until side) for (c in 0 until side) if (index[r * side + c] >= 0) lowest = min(lowest, r)
        for (r in 0 until side) for (c in 0 until side) {
            val i = index[r * side + c]
            if (i < 0) continue
            x[i] = -size * 0.5f + c * step
            y[i] = floor + (r - lowest) * step
        }
        px = x.copyOf(); py = y.copyOf()
        // Along rows and columns, and both diagonals, so it holds its shape.
        val links = ArrayList<IntArray>()
        fun link(a: Int, b: Int) { val ia = index[a]; val ib = index[b]; if (ia >= 0 && ib >= 0) links.add(intArrayOf(ia, ib)) }
        for (r in 0 until side) for (c in 0 until side) {
            val i = r * side + c
            if (c + 1 < side) link(i, i + 1)
            if (r + 1 < side) link(i, i + side)
            if (c + 1 < side && r + 1 < side) link(i, i + side + 1)
            if (c > 0 && r + 1 < side) link(i, i + side - 1)
        }
        springs = links.size
        sa = IntArray(springs) { links[it][0] }
        sb = IntArray(springs) { links[it][1] }
        rest = FloatArray(springs) {
            val dx = x[sa[it]] - x[sb[it]]; val dy = y[sa[it]] - y[sb[it]]
            sqrt(dx * dx + dy * dy)
        }
        alive = BooleanArray(springs) { true }
        axial = BooleanArray(springs) { rest[it] < step * 1.2f }
        lastDh = 0f
        broken = 0
        var top = floor
        for (i in 0 until n) top = max(top, y[i])
        plateStart = top + GAP
        plate = plateStart
        plateStop = FLOOR + PLATE_STOP
        touched = false
        elapsed = 0f
        payoffFrame = false
        gaveAt = -1f
        bottomAt = -1f
        creak = 0f
        maxStrain = 0f
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        // The press: steady, slowing a little once it bites, so the last of
        // the squeeze is drawn out.
        // The slow squeeze on glass, times the slowest setting, took two
        // minutes to give; never slower than a fifth of the base speed.
        val factor = env.params.f("speed", 1f) * (if (touched && gaveAt < 0f) bite else if (touched) 1f else 1.6f)
        val speed = PLATE_SPEED * factor.coerceAtLeast(MIN_FACTOR)
        if (bottomAt < 0f) {
            plate = max(plateStop, plate - speed * h)
            if (plate <= plateStop) {
                bottomAt = elapsed
                env.emit(EventKind.STEP, 0.1f, 0f, 0.9f)
            }
        }
        val sub = SUBSTEPS
        val dh = h / sub
        val g = env.laws.gravity * GRAVITY_SCALE
        var strainSum = 0f
        var snapped = 0
        var snappedX = 0f
        val wasTouched = touched
        // Verlet keeps a velocity as the last step's move; when the step
        // changes size (the payoff's slow motion) that move is rescaled to
        // the new step, or the block ran on at full speed while the press
        // slowed, and lurched when time came back.
        val rescale = if (lastDh > 0f) dh / lastDh else 1f
        lastDh = dh
        repeat(sub) { pass ->
            val carry = DAMP * (if (pass == 0) rescale else 1f)
            // Verlet: carry on as before, plus gravity.
            for (i in 0 until n) {
                val vx = (x[i] - px[i]) * carry
                val vy = (y[i] - py[i]) * carry
                px[i] = x[i]; py[i] = y[i]
                x[i] += vx
                y[i] += vy - g * dh * dh
            }
            // The springs, a pass at a time.
            repeat(PASSES) {
                for (s in 0 until springs) {
                    if (!alive[s]) continue
                    val a = sa[s]; val b = sb[s]
                    val dx = x[b] - x[a]; val dy = y[b] - y[a]
                    val d = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6f)
                    val strain = (d - rest[s]) / rest[s]
                    if (strain > limit) {
                        alive[s] = false
                        broken++
                        snapped++
                        snappedX += (x[a] + x[b]) * 0.5f
                        continue
                    }
                    val k = stiff * 0.5f * (d - rest[s]) / d
                    x[a] += dx * k; y[a] += dy * k
                    x[b] -= dx * k; y[b] -= dy * k
                }
            }
            // The plate above, the floor below, the screen's sides.
            for (i in 0 until n) {
                if (y[i] > plate - PARTICLE) {
                    y[i] = plate - PARTICLE
                    touched = true
                    // The plate drags what it holds a little: no squirting
                    // sideways off a frictionless face.
                    x[i] = px[i] + (x[i] - px[i]) * 0.6f
                }
                if (y[i] < FLOOR + PARTICLE) {
                    y[i] = FLOOR + PARTICLE
                    x[i] = px[i] + (x[i] - px[i]) * 0.6f
                }
                val wx = halfW - PARTICLE
                if (x[i] < -wx) x[i] = -wx
                if (x[i] > wx) x[i] = wx
            }
        }
        var worst = 0f
        for (s in 0 until springs) {
            if (!alive[s]) continue
            val a = sa[s]; val b = sb[s]
            val dx = x[b] - x[a]; val dy = y[b] - y[a]
            // Stretch, which is what snaps a spring; squeezing doesn't.
            val st = max(0f, sqrt(dx * dx + dy * dy) - rest[s]) / rest[s]
            strainSum += st
            if (st > worst) worst = st
        }
        maxStrain = worst
        // The creak: pitch with the worst strain, a note when it climbs.
        val level = (worst / limit).coerceIn(0f, 1f)
        if (touched && level > creak + CREAK_STEP) {
            creak = level
            env.emit(EventKind.STEP, 0.15f + 0.8f * level, 0f, 0.2f + 0.35f * level)
        } else if (level < creak - CREAK_STEP * 2f) creak = level
        // The plate meeting the block: it came down for seconds in silence and
        // bit without a sound.
        if (touched && !wasTouched) env.emit(EventKind.HIT, 0.2f, 0f, 0.6f)
        if (snapped > 0) {
            // Heard where the links broke, not at a random place.
            env.emit(EventKind.HIT, env.rng.range(0.6f, 1f), (snappedX / snapped / halfW).coerceIn(-1f, 1f), min(1f, 0.3f + snapped * 0.04f))
        }
        // It gave: a tenth of the springs gone, or the press bottomed out on
        // a block too tough to break.
        if (gaveAt < 0f && (broken > springs * GIVE_SHARE || bottomAt >= 0f)) {
            gaveAt = elapsed
            payoffFrame = true
            // The breaking chord only when it broke: jelly that bottoms out
            // with a few links gone is squashed, not snapped.
            env.emit(EventKind.DONE, if (broken > springs * GIVE_SHARE) 0.95f else 0.3f, 0f, 1f)
        }
    }

    private fun inShape(shape: Int, c: Int, r: Int): Boolean {
        val m = (side - 1) * 0.5f
        return when (shape) {
            // A ball: the lattice inside a circle.
            1 -> { val dx = c - m; val dy = r - m; dx * dx + dy * dy <= (m + 0.3f) * (m + 0.3f) }
            // A pyramid: narrowing to a point at the top — a point braced by
            // the row of three under it, so an odd size's tip doesn't flop.
            2 -> abs(c - m) <= (side - 1 - r) * 0.5f + 0.8f
            else -> true
        }
    }

    /** Hit the block from above: the press slams down a step. */
    override fun hero(env: SimEnv) {
        if (bottomAt >= 0f) return
        plate = max(plateStop, plate - 0.06f)
        env.emit(EventKind.HIT, 0.2f, 0f, 0.8f)
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val mark = g.drawMark()
        // The floor.
        g.rect(0f, FLOOR - 0.03f, halfW, 0.03f, 0xFF1C1F27.toInt())
        // The block: each spring coloured by how hard it is working. Only
        // the rows and columns are drawn; the diagonal braces still hold it,
        // but drawn as well they cross-hatched the block into a mesh that,
        // crushed, was a tangle. Cracks show as gaps in the grid.
        g.reserveInstances(springs)
        for (s in 0 until springs) {
            if (!alive[s] || !axial[s]) continue
            val a = sa[s]; val b = sb[s]
            val dx = x[b] - x[a]; val dy = y[b] - y[a]
            val st = (abs(sqrt(dx * dx + dy * dy) - rest[s]) / rest[s] / limit).coerceIn(0f, 1f)
            val c = if (st > 0.75f) Palette.brighten(palette.at(0.2f + 0.75f * st), (st - 0.75f) * 2f) else palette.at(0.2f + 0.75f * st)
            g.line(x[a], y[a], x[b], y[b], size / side * 0.42f, c)
        }
        g.reservePoints(n)
        val pc = Palette.withAlpha(palette.at(0.25f), 0.9f)
        for (i in 0 until n) g.point(x[i], y[i], size / side * 0.5f, pc)
        // The press: a plate and the ram above it.
        val plateC = 0xFF8A93A6.toInt()
        g.rect(0f, plate + 0.03f, min(halfW * 0.95f, size * 0.9f), 0.03f, plateC)
        g.rect(0f, (plate + 0.06f + 1.1f) * 0.5f, 0.06f, (1.1f - plate - 0.06f) * 0.5f, 0xFF5C6373.toInt())
        if (gaveAt >= 0f && broken > 0) {
            val k = (1f - (elapsed - gaveAt) * 2f).coerceIn(0f, 1f)
            if (k > 0f) g.glow(0f, (plate + FLOOR) * 0.5f, size, Palette.TEXT_PRIMARY, 0.5f * k)
        }
        // On a wide screen the whole press is shown larger, standing on the
        // floor: the block, capped in size so its springs behave the same,
        // was a small island low in a landscape frame. The ram runs off the
        // top either way. 1 on a phone.
        g.zoomSince(mark, (env.halfWidth / 1.1f).coerceIn(1f, WIDE_ZOOM), 0f, FLOOR)
    }

    override fun frameHalfWidth(env: SimEnv): Float = 0f

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = bottomAt >= 0f && elapsed - bottomAt > 2.5f

    override fun counters(): List<Pair<String, String>> = listOf(
        "pressure" to "${(min(1f, maxStrain / limit) * 100f).toInt()}%",
        "snapped" to "$broken"
    )

    companion object {
        const val MAX_SIDE = 40
        const val FLOOR = -0.85f
        const val WIDE_ZOOM = 1.6f
        const val PARTICLE = 0.008f
        const val GAP = 0.18f
        const val PLATE_STOP = 0.06f
        const val PLATE_SPEED = 0.06f
        const val SUBSTEPS = 6
        const val PASSES = 3
        const val DAMP = 0.995f
        const val GRAVITY_SCALE = 0.3f
        const val CREAK_STEP = 0.08f
        const val GIVE_SHARE = 0.1f
        const val MIN_FACTOR = 0.2f
        val MATERIAL_LIMIT = floatArrayOf(0.2f, 0.35f, 0.75f)
        val MATERIAL_STIFF = floatArrayOf(1f, 0.6f, 0.25f)
        val MATERIAL_BITE = floatArrayOf(0.2f, 0.7f, 1f)

        fun def(): SimDef = SimDef(
            id = "physics/press",
            title = "Hydraulic press",
            shelf = Shelf.PHYSICS,
            family = "Pressure & bursting",
            blurb = "It squeezes, it bulges, it creaks… and then it gives.",
            formats = listOf(TrapFormat.CUT, TrapFormat.SNAP),
            lawKnobs = setOf(LawKnob.GRAVITY),
            heroLabel = "Slam",
            heroRestarts = false,
            defaultPalette = "Ember",
            typicalSeconds = 12f,
            params = listOf(
                Param.choice("material", "Material", listOf("Glass", "Wood", "Jelly"), 1, card = Card.DATA, input = ParamInput.TILES),
                Param.choice("object", "Object", listOf("Block", "Ball", "Pyramid"), 0, card = Card.DATA, input = ParamInput.TILES),
                Param.int("detail", "Detail", 4, MAX_SIDE, 14, card = Card.DATA),
                Param.float("speed", "Press speed", 0.2f, 5f, 1f, card = Card.MOTION)
            ),
            create = { PressSim() }
        )
    }
}
