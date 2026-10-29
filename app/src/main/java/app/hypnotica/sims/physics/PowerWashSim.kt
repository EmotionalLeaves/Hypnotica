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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A grimy wall, power-washed. The nozzle sweeps it in strips and the dirt
 * blasts off to show the mural underneath, a different one every run. Some
 * patches are baked on and survive the first pass; once the sweep is done the
 * nozzle goes back for them one at a time, until the very last spot.
 */
class PowerWashSim : Sim {

    private var gx = 40
    private var gy = 80
    private var cell = 0.02f
    private var ox = 0f
    private var oy = 0f
    private var mural = IntArray(0)
    private var dirt = FloatArray(0)
    private var grain = FloatArray(0)
    private var crust = BooleanArray(0)
    private var dirty = 0
    private var nx = 0f
    private var ny = 0f
    // The sweep: strip by strip, back and forth.
    private var strip = 0
    private var strips = 1
    private var dir = 1f
    private var sweeping = true
    private var target = -1
    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false
    private var turbo = 0f
    private var radius = 0.05f
    private var speed = 0.5f
    // Spray.
    private var sx = FloatArray(SPRAY)
    private var sy = FloatArray(SPRAY)
    private var svx = FloatArray(SPRAY)
    private var svy = FloatArray(SPRAY)
    private var slife = FloatArray(SPRAY)
    private var scol = IntArray(SPRAY)
    private var sNext = 0
    private var halfW = 0.46f

    override fun setup(env: SimEnv) {
        halfW = env.halfWidth
        gx = env.params.i("detail", 56).coerceIn(10, MAX_DETAIL)
        val w = 2f * halfW * 0.92f
        cell = min(w / gx, 1.8f / 10f)
        gy = (1.8f / cell).toInt().coerceIn(10, 400)
        ox = -gx * cell * 0.5f
        oy = -gy * cell * 0.5f
        mural = IntArray(gx * gy)
        paintMural(env)
        dirt = FloatArray(gx * gy)
        // Grime: thicker in blotches, and a few baked-on patches that one
        // pass won't shift.
        val blot = Array(6) { floatArrayOf(env.rng.range(0f, gx.toFloat()), env.rng.range(0f, gy.toFloat()), env.rng.range(3f, 10f)) }
        for (k in 0 until gx * gy) {
            val c = k % gx; val r = k / gx
            var d = 0.8f + 0.25f * env.rng.nextFloat()
            for (b in blot) {
                val dx = c - b[0]; val dy = r - b[1]
                if (dx * dx + dy * dy < b[2] * b[2]) d += 0.4f
            }
            dirt[k] = d
        }
        // A little grain of its own in every cell, so the grime has texture
        // without the mural showing through it.
        grain = FloatArray(gx * gy) { env.rng.nextFloat() }
        crust = BooleanArray(gx * gy)
        repeat(env.rng.int(2, 5)) {
            val c = env.rng.int(1, gx - 1); val r = env.rng.int(1, gy - 1)
            // A crusted blob, round-cornered: a plain square of it read as a
            // hole punched through the wall.
            for (dy in -2..2) for (dx in -2..2) {
                if (abs(dx) + abs(dy) > 3) continue
                if (c + dx !in 0 until gx) continue
                val k = (r + dy) * gx + (c + dx)
                if (k in dirt.indices) { dirt[k] = STUBBORN; crust[k] = true }
            }
        }
        dirty = gx * gy
        // The jet is the same size whatever the detail — a thirteenth of the
        // wall across. Tied to the cells, at high detail it shrank to a pin
        // and swept past too fast to shift anything.
        radius = gx * cell / JETS_ACROSS
        // Strips a little narrower than the spray as it is set, so the sweep
        // overlaps: spaced for the default jet, a narrow one left whole bands.
        val stripH = radius * env.params.f("spray", 1f).coerceIn(0.3f, 3f) * 1.5f
        strips = max(1, kotlin.math.ceil(gy * cell / stripH).toInt())
        // Paced for the sweep to take about this long.
        val path = strips * gx * cell
        speed = path / SWEEP_SECONDS
        strip = 0
        dir = 1f
        nx = ox
        ny = stripY(0)
        sweeping = true
        target = -1
        elapsed = 0f
        doneAt = -1f
        payoffFrame = false
        turbo = 0f
        slife.fill(0f)
        sNext = 0
    }

    private fun stripY(s: Int): Float = oy + gy * cell - (s + 0.5f) * (gy * cell / strips)

    /** One of a few murals, coloured from the palette, laid out from the seed. */
    private fun paintMural(env: SimEnv) {
        val palette = env.palette
        // A mural chosen, or (by default) a different one each run.
        val pick = env.params.choiceIndex("mural").coerceIn(0, MURALS.size - 1)
        val kind = if (pick == 0) env.rng.int(0, MURALS.size - 1) else pick - 1
        val cx = gx * 0.5f; val cy = gy * 0.5f
        val f1 = env.rng.range(0.15f, 0.35f); val turn = env.rng.range(0f, 6.28f)
        // Two or three turns of the palette across the wall: more, and at one
        // colour step a cell the mural broke into stair-stepped rainbow noise.
        val bands = env.rng.range(0.7f, 1.1f)
        for (k in 0 until gx * gy) {
            val c = k % gx; val r = k / gx
            val u = (c - cx) / gx; val v = (r - cy) / gx
            val t = when (kind) {
                // Rings round the middle.
                0 -> sqrt(u * u + v * v) * bands
                // A spiral.
                1 -> (kotlin.math.atan2(v, u) / (2f * PI.toFloat()) + sqrt(u * u + v * v) * bands * 0.5f)
                // Diamonds.
                2 -> (abs(u * cos(turn) - v * sin(turn)) + abs(u * sin(turn) + v * cos(turn))) * bands
                // Waves.
                3 -> v * bands * 0.6f + 0.25f * sin(u * 30f * f1 + turn)
                // Tiles, like a bathroom wall.
                4 -> {
                    val n = (bands * 2.5f)
                    val a = kotlin.math.floor((u + 1f) * n).toInt() + kotlin.math.floor((v + 1f) * n).toInt()
                    if (a % 2 == 0) 0.2f else 0.7f + 0.1f * sin(turn + a)
                }
                // A sunburst of rays from the middle.
                else -> {
                    // A whole number of rays, so none is cut short where the
                    // angle wraps; each a step round the palette.
                    val rays = 8 + 2 * (f1 * 20f).toInt()
                    kotlin.math.floor((kotlin.math.atan2(v, u) / (2f * PI.toFloat()) + 0.5f) * rays) / rays + turn
                }
            }
            // Back and forth along the palette rather than round and round:
            // wrapped, every turn ended in a hard edge where the last colour
            // met the first, and the wall was a harsh rainbow of stripes.
            val w = ((t % 2f) + 2f) % 2f
            val tone = if (w > 1f) 2f - w else w
            // Lit from the top left and a touch muted at the edges, so the
            // mural reads as paint on a wall, not a test card.
            val light = 0.8f + 0.2f * ((1f - c.toFloat() / gx) * 0.4f + (r.toFloat() / gy) * 0.6f)
            val edge = (min(min(c, gx - 1 - c).toFloat() / gx, min(r, gy - 1 - r).toFloat() / gx) * 6f).coerceIn(0f, 1f)
            val paint = Palette.lerp(0xFF000000.toInt(), palette.at(0.08f + 0.84f * tone), light * (0.88f + 0.12f * edge))
            mural[k] = paint
        }
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        turbo = max(0f, turbo - h)
        if (doneAt >= 0f) { stepSpray(env, h); return }
        val v = speed * env.params.f("pace", 1f) * (if (sweeping) 1f else 1.6f)
        if (sweeping) {
            nx += dir * v * h
            val edge = if (dir > 0f) ox + gx * cell else ox
            if ((dir > 0f && nx >= edge) || (dir < 0f && nx <= edge)) {
                nx = edge
                strip++
                dir = -dir
                if (strip >= strips) sweeping = false else ny = stripY(strip)
            }
        } else {
            // The sweep is done: back for what it missed, nearest first.
            if (target < 0 || dirt[target] <= 0f) target = nearestDirty()
            if (target >= 0) {
                val tx = ox + (target % gx + 0.5f) * cell
                val ty = oy + (target / gx + 0.5f) * cell
                val dx = tx - nx; val dy = ty - ny
                val d = sqrt(dx * dx + dy * dy)
                if (d > 1e-4f) { val m = min(d, v * h); nx += dx / d * m; ny += dy / d * m }
            }
        }
        blast(env, h)
        stepSpray(env, h)
        if (dirty == 0 && doneAt < 0f) {
            doneAt = elapsed
            payoffFrame = true
            env.emit(EventKind.DONE, 1f, 0f, 1f)
        }
    }

    private fun nearestDirty(): Int {
        var best = -1; var bd = Float.MAX_VALUE
        for (k in 0 until gx * gy) {
            if (dirt[k] <= 0f) continue
            val dx = ox + (k % gx + 0.5f) * cell - nx; val dy = oy + (k / gx + 0.5f) * cell - ny
            val d = dx * dx + dy * dy
            if (d < bd) { bd = d; best = k }
        }
        return best
    }

    private fun blast(env: SimEnv, h: Float) {
        val r = radius * (if (turbo > 0f) 1.8f else 1f) * env.params.f("spray", 1f).coerceIn(0.3f, 3f)
        val c0 = ((nx - r - ox) / cell).toInt().coerceIn(0, gx - 1)
        val c1 = ((nx + r - ox) / cell).toInt().coerceIn(0, gx - 1)
        val r0 = ((ny - r - oy) / cell).toInt().coerceIn(0, gy - 1)
        val r1 = ((ny + r - oy) / cell).toInt().coerceIn(0, gy - 1)
        var cleaned = 0
        var tone = 0
        // A narrower jet means more strips, so a faster sweep as well as a
        // smaller spot: time over each cell goes with the square of the width.
        val narrow = env.params.f("spray", 1f).coerceIn(0.3f, 1f)
        val boost = env.params.f("pace", 1f).coerceAtLeast(1f) / (narrow * narrow)
        for (row in r0..r1) for (c in c0..c1) {
            val k = row * gx + c
            if (dirt[k] <= 0f) continue
            val dx = ox + (c + 0.5f) * cell - nx; val dy = oy + (row + 0.5f) * cell - ny
            val d = sqrt(dx * dx + dy * dy)
            if (d > r) continue
            // Hardest at the middle of the jet.
            // Faster passes blast harder, so each sweep shifts the same dirt
            // whatever the pace: at four times the speed it shifted none.
            // And a narrower jet blasts harder for the shorter time it's over a cell.
            dirt[k] -= h * BLAST * boost * (1f - d / r * 0.6f)
            if (env.rng.nextFloat() < h * 25f) spray(env, ox + (c + 0.5f) * cell, oy + (row + 0.5f) * cell, if (env.rng.bool()) GRIME_SPRAY else WATER)
            if (dirt[k] <= 0f) {
                dirt[k] = 0f
                dirty--
                cleaned++
                tone = k
            }
        }
        if (cleaned > 0) {
            // Each clean patch is a note, pitched by the colour it shows.
            val c = mural[tone]
            val lum = (((c shr 16) and 255) + ((c shr 8) and 255) + (c and 255)) / 765f
            env.emit(EventKind.HIT, (0.2f + 0.7f * lum).coerceIn(0f, 1f), ((nx / max(0.1f, halfW))).coerceIn(-1f, 1f), (0.15f + 0.03f * cleaned).coerceAtMost(0.5f))
        }
    }

    private fun spray(env: SimEnv, x: Float, y: Float, color: Int) {
        val i = sNext; sNext = (sNext + 1) % SPRAY
        sx[i] = x; sy[i] = y
        val a = env.rng.range(0f, 2f * PI.toFloat())
        val s = env.rng.range(0.2f, 0.9f)
        svx[i] = cos(a) * s; svy[i] = sin(a) * s
        slife[i] = env.rng.range(0.2f, 0.6f)
        scol[i] = color
    }

    private fun stepSpray(env: SimEnv, h: Float) {
        val g = max(0.5f, abs(env.laws.gravity)) * 0.15f
        for (i in 0 until SPRAY) {
            if (slife[i] <= 0f) continue
            slife[i] -= h
            svy[i] -= g * h
            sx[i] += svx[i] * h; sy[i] += svy[i] * h
        }
    }

    /** Turbo: a wider jet for a couple of seconds. */
    override fun hero(env: SimEnv) {
        if (doneAt >= 0f) return
        turbo = 2.5f
        env.emit(EventKind.STEP, 0.8f, 0f, 0.7f)
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        g.reserveInstances(gx * gy + 8)
        // Overlapping a little, so no seam shows between cells.
        val half = cell * 0.54f
        for (k in 0 until gx * gy) {
            val d = dirt[k].coerceIn(0f, 1f)
            // Grime is opaque until it is nearly gone: blended by its depth,
            // the mural's rainbow showed through every cell of it as noise.
            val c = if (d <= 0f) mural[k] else {
                // Thicker grime darker; only the baked-on spots their own crust.
                val baked = crust[k]
                val base = if (baked) BAKED else Palette.lerp(GRIME, GRIME_DARK, ((dirt[k] - 1f) / 0.8f).coerceIn(0f, 1f))
                val muck = Palette.lerp(base, GRIME_LIGHT, grain[k] * (if (baked) 0.15f else 0.35f))
                val show = (1f - d) * (1f - d)
                Palette.lerp(muck, mural[k], show)
            }
            g.rect(ox + (k % gx + 0.5f) * cell, oy + (k / gx + 0.5f) * cell, half, half, c)
        }
        for (i in 0 until SPRAY) {
            if (slife[i] <= 0f) continue
            g.point(sx[i], sy[i], 0.008f, Palette.withAlpha(scol[i], min(1f, slife[i] * 3f) * 0.8f))
        }
        if (doneAt < 0f) {
            // As wide as what it actually cleans, spray setting and all.
            val r = radius * (if (turbo > 0f) 1.8f else 1f) * env.params.f("spray", 1f).coerceIn(0.3f, 3f)
            g.glow(nx, ny, r * 1.6f, 0xFFBFE6FF.toInt(), 0.5f)
            g.ring(nx, ny, r, 0.004f, Palette.TEXT_PRIMARY, alpha = 0.6f)
            // The lance, from below the screen to the nozzle.
            g.line(nx + 0.1f, -1.2f, nx, ny - r * 0.3f, 0.012f, 0xFF3A3F4B.toInt())
        } else {
            val k = ((elapsed - doneAt) * 0.8f).coerceIn(0f, 1f)
            val sweepX = ox + gx * cell * (k * 1.4f - 0.2f)
            g.glow(sweepX, 0f, 0.3f, Palette.TEXT_PRIMARY, 0.35f * (1f - k))
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = doneAt >= 0f && elapsed - doneAt > 3f

    override fun counters(): List<Pair<String, String>> = listOf(
        "clean" to "${(100 - dirty * 100 / max(1, gx * gy)).coerceIn(0, 100)}%",
        "spots left" to dirty.toString()
    )

    companion object {
        val MURALS = listOf("Surprise", "Rings", "Spiral", "Diamonds", "Waves", "Tiles", "Sunburst")
        const val MAX_DETAIL = 120
        const val SWEEP_SECONDS = 12f
        const val BLAST = 22f
        const val JETS_ACROSS = 13f
        const val STUBBORN = 2.6f
        const val SPRAY = 900
        val GRIME = 0xFF4A4034.toInt()
        val BAKED = 0xFF5A3A22.toInt()
        val GRIME_LIGHT = 0xFF6A5C48.toInt()
        val GRIME_DARK = 0xFF2E2820.toInt()
        val GRIME_SPRAY = 0xFF8A7860.toInt()
        val WATER = 0xFFBFE6FF.toInt()

        fun def(): SimDef = SimDef(
            id = "physics/power-wash",
            title = "Power wash",
            shelf = Shelf.PHYSICS,
            family = "Cleaning",
            blurb = "Strip by strip, the grime comes off. What's underneath? And that last spot…",
            formats = listOf(TrapFormat.REVEAL, TrapFormat.FILL, TrapFormat.SNAP),
            // The spray and the confetti fall under the universe's gravity.
            lawKnobs = setOf(LawKnob.GRAVITY),
            heroLabel = "Turbo",
            heroRestarts = false,
            // Aurora: a gradient, so the mural shades from colour to colour;
            // the spectrum wheel put every hue on the wall at full strength.
            defaultPalette = "Aurora",
            typicalSeconds = 19f,
            params = listOf(
                Param.choice("mural", "Under the dirt", MURALS, 0, card = Card.DATA, input = ParamInput.CHIPS),
                Param.int("detail", "Detail", 10, MAX_DETAIL, 56, card = Card.DATA),
                Param.float("pace", "Pace", 0.3f, 4f, 1f, card = Card.MOTION),
                Param.float("spray", "Spray width", 0.5f, 2.5f, 1f, card = Card.MOTION)
            ),
            create = { PowerWashSim() }
        )
    }
}
