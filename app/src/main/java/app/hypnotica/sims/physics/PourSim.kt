package app.hypnotica.sims.physics

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
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
 * A glass filled from a tap, drop by drop, to the brim.
 *
 * The liquid is thousands of particles that push apart when they crowd and
 * drag on each other when they slide — the cheapest model that still pools,
 * sloshes and levels — so the stream splashes, the surface wobbles flat, and
 * the line creeps up the glass to the rim. Each tenth of the glass filled is a
 * note higher; a drop landing hard is a tap of sound.
 */
class PourSim : Sim {

    private var cap = 2000
    private var n = 0
    private var x = FloatArray(0)
    private var y = FloatArray(0)
    private var vx = FloatArray(0)
    private var vy = FloatArray(0)
    private var px = FloatArray(0)
    private var py = FloatArray(0)
    private var dvx = FloatArray(0)
    private var dvy = FloatArray(0)
    private var carry = 0f
    private var stepCarry = 0f
    private var elapsed = 0f
    private var payoffFrame = false
    private var full = false
    private var fullAt = -1f
    private var capAt = -1f
    private var tenths = 0
    private var halfW = 0.46f
    /**
     * On a wide screen the scene is drawn this much larger about the middle
     * of the floor, and laid out in the narrower, shorter world that then
     * fills the screen: at drop size a glass is a tall narrow thing, and it
     * stood as a small island in a landscape frame. 1 on a phone.
     */
    private var zoom = 1f
    /** Top of the tap, in world units: lowered with [zoom] so it stays on screen. */
    private var tapTop = 0.985f
    private var share = 0f
    private val level = IntArray(80)

    // The glasses: open boxes, walls and a floor, in world units, filled in
    // turn from left to right by a tap that glides between them.
    private var glasses = 1
    private var gL = FloatArray(1)
    private var gR = FloatArray(1)
    private var gTop = FloatArray(1)
    private var gFull = BooleanArray(1)
    private var gShare = FloatArray(1)
    private var current = 0
    private var tapX = 0f
    private var glassBottom = -0.85f
    private var flowScale = 1f
    private var poured = 0
    private var gNow = 1f

    // Neighbour grid.
    private var cellsX = 1
    private var cellsY = 1
    private var head = IntArray(0)
    private var next = IntArray(0)
    private var side = IntArray(0)

    override fun setup(env: SimEnv) {
        val p = env.params
        cap = MAX_DROPS
        glasses = p.i("glasses", 1).coerceIn(1, MAX_GLASSES)
        val base = p.f("glass", 0.8f).coerceIn(0.2f, 1.3f)
        // No more than leaves the brim of the tallest glass a clear fall
        // below the tap: the tap then sits a ninth of the glass above it.
        zoom = (env.halfWidth / 1.1f).coerceIn(1f, min(WIDE_ZOOM, 1.75f / base))
        halfW = env.halfWidth / zoom
        tapTop = -1f + 1.985f / zoom
        // Each glass in its own slot across the screen; one alone is a third
        // of the width, as it always was.
        val slot = 2f * halfW * 0.92f / glasses
        // Glass-sized however wide the screen: on a wide one a glass the
        // third of the width it is upright was a bathtub, and took a minute.
        val w = min(if (glasses == 1) halfW * 0.42f else slot * 0.34f, MAX_HALF_WIDTH)
        gL = FloatArray(glasses); gR = FloatArray(glasses); gTop = FloatArray(glasses)
        gFull = BooleanArray(glasses); gShare = FloatArray(glasses)
        // Standing on the floor, so nothing that splashes out can run under it.
        glassBottom = -1f + FLOOR_GAP
        for (k in 0 until glasses) {
            val c = if (glasses == 1) 0f else -halfW * 0.92f + slot * (k + 0.5f)
            gL[k] = c - w; gR[k] = c + w
            // The tap runs until the water line reaches the brim, however many
            // drops that takes: deep water squashes a little, so a count chosen
            // in advance never quite fills it. A row of them, of different heights.
            gTop[k] = glassBottom + if (glasses == 1) base else base * env.rng.range(0.6f, 1f)
        }
        current = 0
        tapX = (gL[0] + gR[0]) * 0.5f
        var area = 0f
        for (k in 0 until glasses) area += (gR[k] - gL[k]) * (gTop[k] - glassBottom)
        flowScale = (area / REF_AREA).coerceIn(0.5f, 4f)
        // As many drops as the glasses hold with room for the stream and a
        // spill — no more: every drop costs every frame.
        cap = (area / DROP_AREA * 1.4f + 500f).toInt().coerceIn(1200, MAX_DROPS)
        poured = 0
        gNow = 1f
        x = FloatArray(cap); y = FloatArray(cap); vx = FloatArray(cap); vy = FloatArray(cap)
        px = FloatArray(cap); py = FloatArray(cap); dvx = FloatArray(cap); dvy = FloatArray(cap)
        n = 0
        carry = 0f
        stepCarry = 0f
        elapsed = 0f
        payoffFrame = false
        full = false
        fullAt = -1f
        capAt = -1f
        tenths = 0
        share = 0f
        cellsX = max(1, (2f * halfW / D).toInt() + 2)
        cellsY = max(1, (2.2f / D).toInt() + 2)
        head = IntArray(cellsX * cellsY)
        next = IntArray(cap)
        side = IntArray(cap)
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        // Always the same fixed step, however slowly time runs. The drops'
        // speed is read back from how far the push-apart moved them, and the
        // water under its own weight sits squeezed by an amount that scales
        // with the step: the payoff's slow motion shrank the step fourfold, the
        // squeeze sprang back at once, and the full glass erupted out of its
        // own mouth. Slow motion is drawn by easing between the last two steps.
        stepCarry += abs(dt)
        while (stepCarry >= FIXED_STEP * 0.999f) {
            stepCarry -= FIXED_STEP
            tick(env, FIXED_STEP)
        }
        stepCarry = max(0f, stepCarry)
    }

    private fun tick(env: SimEnv, h: Float) {
        elapsed += h
        // The tap glides to the next glass, and pours only once it's there.
        val target = if (current < glasses) (gL[current] + gR[current]) * 0.5f else tapX
        val toGo = target - tapX
        tapX += toGo.coerceIn(-TAP_GLIDE * h, TAP_GLIDE * h)
        if (abs(toGo) > 0.005f) carry = 0f
        // The tap.
        if (!full && abs(toGo) <= 0.005f) {
            // Scaled with how much glass there is to fill, so two or three, or
            // a wide one, fill in about the time one upright glass does.
            // No more lanes than fit inside the glass, and no faster than
            // they can carry with each lane's drops a drop apart: past that
            // the stream sprays sideways, or misses the glass.
            val room = ((gR[current] - gL[current] - 2f * D) / (1.05f * D)).toInt() + 1
            val maxLanes = room.coerceIn(1, 9)
            val flow = min(env.params.f("flow", 75f) * flowScale, maxLanes * TAP_SPEED / (1.2f * D))
            carry += h * flow
            // As many lanes as keep each one's drops a drop apart.
            val lanes = kotlin.math.ceil(flow * D * 1.2f / TAP_SPEED).toInt().coerceIn(1, maxLanes)
            // Out of water: the array is full, or the tap has run three
            // glasses' worth (spill drains away, so it could run for ever).
            if (n >= cap || poured >= cap * 3) { if (capAt < 0f) capAt = elapsed }
            // Room again once spill has soaked away: not out after all.
            else capAt = -1f
            while (carry >= 1f && n < cap && poured < cap * 3) {
                carry -= 1f
                poured++
                // In lanes across the tap, each drop set down a little below
                // the last in its lane by the share of the frame since it
                // left — spaced past the push-apart distance, or the stream
                // blows itself sideways in mid-air.
                val lane = n % lanes
                x[n] = tapX + (lane - (lanes - 1) * 0.5f) * D * 1.05f + env.rng.range(-0.001f, 0.001f)
                y[n] = tapTop - 0.035f - carry / flow * TAP_SPEED
                vx[n] = 0f
                vy[n] = -TAP_SPEED
                n++
            }
        }
        // Water pours down whatever the law says, or the glass never fills.
        // Not past a little over Earth's either: under heavy gravity the
        // drops crushed together to a fraction of their room, and the glass
        // took three times the water.
        val g = (abs(env.laws.gravity) * GRAVITY_SCALE).coerceIn(MIN_FALL, MAX_FALL)
        gNow = g
        // Spill lying still on the floor outside the glasses soaks away, a
        // little at a time: else it covers the floor and every frame pays
        // for it.
        var i0 = 0
        while (i0 < n) {
            val still = vx[i0] * vx[i0] + vy[i0] * vy[i0] < SETTLED * SETTLED
            if (still && y[i0] < glassBottom + 2f * D && !inAnyGlass(x[i0]) && env.rng.nextFloat() < h * DRAIN) {
                n--
                x[i0] = x[n]; y[i0] = y[n]; vx[i0] = vx[n]; vy[i0] = vy[n]
                continue
            }
            i0++
        }
        // Move every drop where its speed takes it...
        for (i in 0 until n) {
            vy[i] -= g * h
            px[i] = x[i]; py[i] = y[i]
            x[i] += vx[i] * h
            y[i] += vy[i] * h
        }
        // ...then push apart every pair closer than a drop, a few passes over.
        // Water that keeps its volume, which a soft pressure doesn't: under
        // its own weight that squashed to a third and the glass never filled.
        repeat(PASSES) {
            java.util.Arrays.fill(head, -1)
            for (i in 0 until n) {
                val c = cellOf(x[i], y[i])
                next[i] = head[c]
                head[c] = i
            }
            for (i in 0 until n) {
                val cx = ((x[i] + halfW) / D).toInt().coerceIn(0, cellsX - 1)
                val cy = ((y[i] + 1.1f) / D).toInt().coerceIn(0, cellsY - 1)
                for (oy in -1..1) for (ox in -1..1) {
                    val gx = cx + ox; val gy = cy + oy
                    if (gx < 0 || gy < 0 || gx >= cellsX || gy >= cellsY) continue
                    var j = head[gy * cellsX + gx]
                    while (j >= 0) {
                        if (j > i) {
                            val dx = x[i] - x[j]; val dy = y[i] - y[j]
                            val d2 = dx * dx + dy * dy
                            if (d2 < D * D && d2 > 1e-12f) {
                                val d = sqrt(d2)
                                val push = (D - d) * 0.5f * STIFF / d
                                x[i] += dx * push; y[i] += dy * push
                                x[j] -= dx * push; y[j] -= dy * push
                            }
                        }
                        j = next[j]
                    }
                }
            }
            for (i in 0 until n) collide(i, px[i], py[i])
        }
        // Speed is how far each drop actually went; and neighbours drag on
        // each other a little, so the surface settles instead of fizzing.
        var landed = 0
        var landedX = 0f
        val inv = if (h > 1e-6f) 1f / h else 0f
        for (i in 0 until n) {
            val before = vy[i]
            vx[i] = (x[i] - px[i]) * inv
            vy[i] = (y[i] - py[i]) * inv
            // A drop stopped hard is a splash.
            if (before < -0.8f && vy[i] > -0.2f) { landed++; landedX += x[i] }
        }
        java.util.Arrays.fill(head, -1)
        for (i in 0 until n) {
            val c = cellOf(x[i], y[i])
            next[i] = head[c]
            head[c] = i
        }
        // Never quite none: water with no thickness at all never calmed
        // enough to show a water line, splashed out as fast as it came, and
        // the glass never filled.
        val thick = env.params.f("thick", VISCOSITY).coerceIn(MIN_THICK, 0.9f)
        // Which glass each drop is in, if any: drag never reaches through a
        // wall, or spill outside clung to the still water inside and hung
        // halfway up the glass.
        for (i in 0 until n) side[i] = sideOf(x[i])
        for (i in 0 until n) {
            var sx = 0f; var sy = 0f; var w = 0f
            val cx = ((x[i] + halfW) / D).toInt().coerceIn(0, cellsX - 1)
            val cy = ((y[i] + 1.1f) / D).toInt().coerceIn(0, cellsY - 1)
            for (oy in -1..1) for (ox in -1..1) {
                val gx = cx + ox; val gy = cy + oy
                if (gx < 0 || gy < 0 || gx >= cellsX || gy >= cellsY) continue
                var j = head[gy * cellsX + gx]
                while (j >= 0) {
                    if (j != i && side[j] == side[i]) {
                        val dx = x[i] - x[j]; val dy = y[i] - y[j]
                        val d2 = dx * dx + dy * dy
                        if (d2 < REACH * REACH) {
                            val q = 1f - sqrt(d2) / REACH
                            sx += (vx[j] - vx[i]) * q; sy += (vy[j] - vy[i]) * q; w += q
                        }
                    }
                    j = next[j]
                }
            }
            if (w > 0f) { dvx[i] = sx / w * thick; dvy[i] = sy / w * thick } else { dvx[i] = 0f; dvy[i] = 0f }
        }
        for (i in 0 until n) { vx[i] += dvx[i]; vy[i] += dvy[i] }
        // Panned to where the drops stopped, not to a random place.
        if (landed > 0) env.emit(EventKind.HIT, 0.6f + 0.3f * env.rng.nextFloat(), (landedX / landed / halfW).coerceIn(-1f, 1f), min(1f, 0.2f + landed * 0.05f))

        // How full, the way an eye judges it: where the water line is. Rows
        // up each glass, and the highest one that's at least half water.
        for (k in 0 until glasses) gShare[k] = levelOf(k)
        if (current < glasses) {
            share = gShare[current]
            val glassPan = ((gL[current] + gR[current]) * 0.5f / halfW).coerceIn(-1f, 1f)
            while (tenths < 10 && share >= (tenths + 1) / 10f) {
                tenths++
                env.emit(EventKind.STEP, (current * 10 + tenths) / (glasses * 10f), glassPan, 0.5f)
            }
            if (share >= FULL_SHARE) {
                gFull[current] = true
                current++
                tenths = 0
                if (current >= glasses) {
                    full = true
                    fullAt = elapsed
                    payoffFrame = true
                    env.emit(EventKind.DONE, 0.9f, 0f, 1f)
                } else {
                    // One down: a chord, and on to the next.
                    env.emit(EventKind.HIT, 0.8f, glassPan, 0.8f)
                }
            }
        }
    }

    private fun sideOf(px: Float): Int {
        for (k in 0 until glasses) if (px > gL[k] && px < gR[k]) return k
        return -1
    }

    private fun inAnyGlass(px: Float): Boolean {
        for (k in 0 until glasses) if (px > gL[k] - D && px < gR[k] + D) return true
        return false
    }

    private fun levelOf(k: Int): Float {
        val rows = ((gTop[k] - glassBottom) / D).toInt().coerceIn(4, level.size)
        java.util.Arrays.fill(level, 0)
        val rowH = (gTop[k] - glassBottom) / rows
        var inside = 0
        for (i in 0 until n) {
            if (x[i] <= gL[k] || x[i] >= gR[k] || y[i] < glassBottom) continue
            if (y[i] < gTop[k]) inside++
            // Only water that has settled: the stream and the splash are
            // not the water line, and counting them called the brim early.
            // The falling stream is left out by its speed downward; the pool
            // itself counts even while it churns. Thin water under heavy
            // gravity never fell below a plain still-water speed, so its line
            // was never counted and the glass ran over uncalled.
            val settled = SETTLED * max(1f, gNow / REF_FALL)
            if (vy[i] < -STREAM_CUT * settled) continue
            if (vx[i] * vx[i] + vy[i] * vy[i] > CHURN * CHURN * settled * settled) continue
            val r = ((y[i] - glassBottom) / rowH).toInt()
            if (r in 0 until rows) level[r]++
        }
        val perRow = (gR[k] - gL[k]) * rowH / DROP_AREA
        // Up from the bottom, stopping at the first gap (one missing row
        // forgiven): a band of spill sliding across the mouth of an empty
        // glass read as a full one.
        var top = 0
        var miss = 0
        for (r in 0 until rows) {
            if (level[r] >= perRow * 0.5f) { top = r + 1; miss = 0 } else if (++miss > 1) break
        }
        // Or by sheer amount: a glass holding more than its own volume of
        // water below the rim is full, however hard it is churning — a fast
        // thin stream kept hollows in the pool and the line never read whole.
        val volume = inside * DROP_AREA / ((gR[k] - gL[k]) * (gTop[k] - glassBottom))
        if (volume >= OVERFULL) return 1f
        // A heap reaching the brim isn't a full glass: in a short glass the
        // mound under the stream touched the top row with a third still
        // empty. The line counts as the brim only once there's about a
        // glassful of water under it.
        val line = top.toFloat() / rows
        if (line >= FULL_SHARE && volume < MIN_VOLUME) return min(line, FULL_SHARE - 0.01f)
        return line
    }

    private fun cellOf(px: Float, py: Float): Int {
        val cx = ((px + halfW) / D).toInt().coerceIn(0, cellsX - 1)
        val cy = ((py + 1.1f) / D).toInt().coerceIn(0, cellsY - 1)
        return cy * cellsX + cx
    }

    /**
     * The glass's walls and floor, the screen's edges, and the floor below.
     * Tested by crossing, from where the drop was to where it is, so a drop
     * shoved hard by the crowd can't step clean through a wall in one frame.
     */
    private fun collide(i: Int, ox: Float, oy: Float) {
        for (k in 0 until glasses) collideGlass(i, ox, oy, gL[k], gR[k], gTop[k])
        val r = SPACING * 0.5f
        // What spills runs off the screen's floor and edges.
        if (y[i] < -1f + r) { y[i] = -1f + r; vy[i] = abs(vy[i]) * BOUNCE }
        if (x[i] < -halfW + r) { x[i] = -halfW + r; vx[i] = abs(vx[i]) * BOUNCE }
        if (x[i] > halfW - r) { x[i] = halfW - r; vx[i] = -abs(vx[i]) * BOUNCE }
    }

    private fun collideGlass(i: Int, ox: Float, oy: Float, glassL: Float, glassR: Float, glassTop: Float) {
        val r = SPACING * 0.5f
        val wasIn = ox > glassL && ox < glassR
        val below = oy < glassTop
        if (below && oy > glassBottom - r) {
            if (wasIn) {
                // Inside: keep it inside.
                if (x[i] < glassL + r) { x[i] = glassL + r; vx[i] = abs(vx[i]) * BOUNCE }
                if (x[i] > glassR - r) { x[i] = glassR - r; vx[i] = -abs(vx[i]) * BOUNCE }
                if (y[i] < glassBottom + r) { y[i] = glassBottom + r; vy[i] = abs(vy[i]) * BOUNCE; vx[i] *= 0.98f }
            } else {
                // Outside, beside the glass: keep it out.
                if (ox <= glassL && x[i] > glassL - r) { x[i] = glassL - r; vx[i] = -abs(vx[i]) * BOUNCE }
                if (ox >= glassR && x[i] < glassR + r) { x[i] = glassR + r; vx[i] = abs(vx[i]) * BOUNCE }
            }
        } else if (oy >= glassTop && y[i] < glassTop && x[i] > glassL - r && x[i] < glassR + r && !(x[i] > glassL + r && x[i] < glassR - r)) {
            // Landing on the rim itself: it tips to whichever side it's on.
            y[i] = glassTop
            vy[i] = abs(vy[i]) * BOUNCE
            vx[i] += if (abs(x[i] - glassL) < abs(x[i] - glassR)) (if (x[i] < glassL) -0.1f else 0.1f) else (if (x[i] > glassR) 0.1f else -0.1f)
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val palette = env.palette
        val mark = g.drawMark()
        val wallC = Palette.withAlpha(Palette.TEXT_SECONDARY, 0.85f)
        for (k in 0 until glasses) {
            val glassL = gL[k]; val glassR = gR[k]; val glassTop = gTop[k]
            g.line(glassL, glassBottom, glassL, glassTop, WALL, wallC)
            g.line(glassR, glassBottom, glassR, glassTop, WALL, wallC)
            g.line(glassL - WALL * 0.5f, glassBottom, glassR + WALL * 0.5f, glassBottom, WALL, wallC)
            // The brim, marked, so the target is there to be watched; a full
            // glass lights its brim right across. A glow the glass's own size
            // sat over it as a dim green fog.
            if (gFull[k]) {
                g.line(glassL - 0.02f, glassTop, glassR + 0.02f, glassTop, 0.006f, Palette.SUCCESS)
                g.glow(glassL, glassTop, 0.05f, Palette.SUCCESS, 0.6f)
                g.glow(glassR, glassTop, 0.05f, Palette.SUCCESS, 0.6f)
            } else {
                g.line(glassL - 0.02f, glassTop, glassL + 0.04f, glassTop, 0.005f, Palette.SUCCESS, 0.8f)
                g.line(glassR - 0.04f, glassTop, glassR + 0.02f, glassTop, 0.005f, Palette.SUCCESS, 0.8f)
            }
        }
        // Between the last two fixed steps, when time runs slow.
        val t = (stepCarry / FIXED_STEP).coerceIn(0f, 1f)
        g.reservePoints(n)
        // Which end of the palette is the light one: moving water goes that
        // way. Taken as rising t, it went dark on a light-to-dark palette like
        // the default Ocean, and the falling stream was a navy thread on black.
        fun lum(c: Int) = ((c shr 16) and 255) * 0.3f + ((c shr 8) and 255) * 0.59f + (c and 255) * 0.11f
        val lightUp = lum(palette.at(0.9f)) > lum(palette.at(0.1f))
        for (i in 0 until n) {
            val sp = sqrt(vx[i] * vx[i] + vy[i] * vy[i])
            // Still water deep, moving water bright: the stream and the
            // splash read against the pool. Just wide enough to close the
            // gaps between packed drops: wider, and the water drew over the
            // glass's own walls.
            var dx = px[i] + (x[i] - px[i]) * t
            var dy = py[i] + (y[i] - py[i]) * t
            // Drawn clear of the glass: points are drawn over every shape, so
            // a drop touching a wall hid it, and a full glass lost its sides.
            val k = side[i]
            if (k >= 0 && dy < gTop[k]) {
                dx = dx.coerceIn(gL[k] + DRAW_INSET, gR[k] - DRAW_INSET)
                if (dy > glassBottom - D) dy = max(dy, glassBottom + DRAW_INSET)
            } else if (k < 0) {
                for (q in 0 until glasses) if (dy < gTop[q] && dx > gL[q] - DRAW_INSET && dx < gR[q] + DRAW_INSET) {
                    dx = if (dx < (gL[q] + gR[q]) * 0.5f) gL[q] - DRAW_INSET else gR[q] + DRAW_INSET
                }
            }
            val tone = (0.25f + sp * 0.35f).coerceIn(0f, 1f)
            g.point(dx, dy, D * 1.3f, palette.at(if (lightUp) tone else 0.6f - tone * 0.6f))
        }
        if (!full) {
            // The tap: a spout, and the pipe it hangs from.
            g.rect(tapX, tapTop, TAP_WIDTH * 1.6f, 0.012f, Palette.TEXT_SECONDARY, alpha = 0.85f)
            g.rect(tapX, tapTop - 0.023f, TAP_WIDTH * 0.7f, 0.014f, Palette.TEXT_SECONDARY, alpha = 0.85f)
        }
        g.zoomSince(mark, zoom, 0f, -1f)
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = (full && elapsed - fullAt > 3f) ||
        // Out of water short of the brim: let the next run come.
        (capAt >= 0f && elapsed - capAt > 5f) ||
        // However it goes, never minutes.
        elapsed > GIVE_UP

    override fun counters(): List<Pair<String, String>> = listOf(
        "drops" to n.toString(),
        "full" to if (full) "brim" else if (glasses > 1) "glass ${current + 1} of $glasses · ${(share * 100f).toInt()}%" else "${(share * 100f).toInt()}%"
    )

    companion object {
        /** A drop's width: none come closer. */
        const val D = 0.022f
        const val WIDE_ZOOM = 1.6f
        const val SPACING = D
        const val PASSES = 4
        const val STIFF = 0.9f
        const val REACH = D * 1.6f
        /** How much of the gap to its neighbours' speed a drop closes each frame. */
        const val VISCOSITY = 0.08f
        const val BOUNCE = 0.1f
        const val GRAVITY_SCALE = 0.25f
        const val TAP_WIDTH = 0.04f
        const val WALL = 0.012f
        /** How far a drop's centre is drawn from a wall's centre line: half a wall and a drop's drawn radius. */
        const val DRAW_INSET = WALL * 0.5f + D * 0.65f
        const val TAP_SPEED = 2.2f
        const val FULL_SHARE = 0.975f
        const val MAX_DROPS = 4500
        const val MAX_GLASSES = 3
        const val MAX_HALF_WIDTH = 0.2f
        /** One glass on an upright phone at the default height: what the flow was tuned on. */
        const val REF_AREA = 2f * 0.193f * 0.8f
        /** How fast the tap glides to the next glass. */
        const val TAP_GLIDE = 0.5f
        const val MIN_FALL = 0.8f
        const val SETTLED = 0.5f
        /** The fall under Earth's gravity, which SETTLED was tuned on. */
        const val REF_FALL = 9.81f * GRAVITY_SCALE
        /** How fast spill on the floor soaks away, as a share per second. */
        const val DRAIN = 0.6f
        const val GIVE_UP = 60f
        /** The step the water was tuned on: the app's own, at full speed. */
        const val FIXED_STEP = 1f / 120f
        const val MIN_THICK = 0.03f
        const val OVERFULL = 1.08f
        const val MIN_VOLUME = 0.88f
        const val MAX_FALL = REF_FALL * 1.4f
        /** Falling faster than this many still-water speeds: the stream, not the pool. */
        const val STREAM_CUT = 2f
        /** Moving faster than this many, any way: splash, not the pool. */
        const val CHURN = 4f
        const val FLOOR_GAP = 0.01f
        /** The area one drop takes up once settled, measured. */
        const val DROP_AREA = D * D * 0.95f

        fun def(): SimDef = SimDef(
            id = "physics/pour",
            title = "Fill the glass",
            shelf = Shelf.PHYSICS,
            family = "Fluids",
            blurb = "A tap, a glass, a thousand drops. Right to the brim. Or pour honey.",
            formats = listOf(TrapFormat.FILL, TrapFormat.GROW, TrapFormat.SNAP),
            lawKnobs = setOf(LawKnob.GRAVITY),
            heroLabel = "Pour again",
            defaultPalette = "Ocean",
            typicalSeconds = 14f,
            params = listOf(
                Param.float("glass", "Glass height", 0.2f, 1.3f, 0.8f, card = Card.DATA),
                Param.int("glasses", "Glasses", 1, MAX_GLASSES, 1, card = Card.DATA),
                Param.float("flow", "Flow", 20f, 800f, 75f, card = Card.MOTION, logScale = true),
                Param.float("thick", "Thickness", 0f, 0.9f, VISCOSITY, card = Card.MOTION)
            ),
            create = { PourSim() }
        )
    }
}
