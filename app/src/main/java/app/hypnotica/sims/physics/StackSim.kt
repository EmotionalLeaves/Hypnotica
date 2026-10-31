package app.hypnotica.sims.physics

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.Shelf
import app.hypnotica.core.sim.Sim
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.sim.SimEnv
import app.hypnotica.core.sim.TrapFormat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The stacking game, played by a steady hand that is not quite steady enough.
 *
 * A slab slides in from the side and drops; whatever hangs over the edge of
 * the one below is sliced off and falls away, and the next slab is only as
 * wide as what was left. Land one dead on and nothing is cut — and a run of
 * those wins a little width back. The tower climbs past a finish line; miss
 * badly enough to have nothing left to stand on, and it starts again.
 */
class StackSim : Sim {

    private var target = 30
    private var baseW = 0.3f
    private var range = 0.4f
    private var lx = FloatArray(0)
    private var lw = FloatArray(0)
    private var layers = 0

    private var curX = 0f
    private var curW = 0.3f
    private var dir = 1f
    private var aimErr = 0f
    private var dropping = false
    private var dropY = 0f

    private var streak = 0
    private var best = 0
    private var longestStreak = 0
    private var perfectGlow = 0f
    private var attempts = 1
    private var failT = -1f
    private var scroll = 0f

    private val cutX = FloatArray(CUTS)
    private val cutY = FloatArray(CUTS)
    private val cutW = FloatArray(CUTS)
    private val cutVx = FloatArray(CUTS)
    private val cutVy = FloatArray(CUTS)
    private val cutRot = FloatArray(CUTS)
    private val cutSpin = FloatArray(CUTS)
    private val cutLife = FloatArray(CUTS)
    private val cutCol = FloatArray(CUTS)
    private var cutNext = 0

    private var elapsed = 0f
    private var doneAt = -1f
    private var payoffFrame = false

    override fun setup(env: SimEnv) {
        target = env.params.i("height", 30).coerceIn(5, MAX_LAYERS - 1)
        range = env.halfWidth * 0.92f
        baseW = min(range * 0.75f, 0.34f)
        lx = FloatArray(MAX_LAYERS)
        lw = FloatArray(MAX_LAYERS)
        cutLife.fill(0f)
        attempts = 1
        best = 0
        elapsed = 0f; doneAt = -1f; payoffFrame = false
        startTower(env)
    }

    private fun startTower(env: SimEnv) {
        layers = 1
        lx[0] = 0f; lw[0] = baseW
        streak = 0
        perfectGlow = 0f
        longestStreak = 0
        failT = -1f
        scroll = 0f
        nextSlab(env)
    }

    private fun nextSlab(env: SimEnv) {
        curW = lw[layers - 1]
        // From alternate sides, as the game does.
        dir = if (layers % 2 == 0) 1f else -1f
        curX = -dir * range
        dropping = false
        // How far off this one will be: dead on, some of the time; otherwise
        // off by an amount that shrinks as the hand gets better. Read at each
        // slab, so the skill slider steers the tower being built.
        val skill = env.params.f("skill", 0.85f).coerceIn(0f, 1f)
        aimErr = if (env.rng.nextFloat() < skill * PERFECT_SHARE) 0f
        else env.rng.normal() * (1f - skill) * 0.4f * curW + (if (env.rng.bool()) 1f else -1f) * 0.004f
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val a = abs(dt)
        elapsed += a
        perfectGlow = max(0f, perfectGlow - a * 2f)
        val speed = env.params.f("speed", 1f).coerceIn(0.1f, 6f)

        for (k in 0 until CUTS) {
            if (cutLife[k] <= 0f) continue
            cutVy[k] -= FALL_G * a
            cutX[k] += cutVx[k] * a
            cutY[k] += cutVy[k] * a
            cutRot[k] += cutSpin[k] * a
            cutLife[k] -= a * 0.45f
        }

        // Keep the top of the tower in the upper middle of the screen.
        val want = max(0f, BASE_Y + (layers + 1) * SLAB_H - VIEW_TOP)
        scroll += (want - scroll) * min(1f, a * 4f)

        if (failT >= 0f) {
            failT += a
            if (failT > FAIL_S) { attempts++; startTower(env) }
            return
        }

        if (!dropping) {
            val prev = lx[layers - 1]
            val before = curX - prev - aimErr
            curX += dir * SLIDE * speed * a
            val after = curX - prev - aimErr
            if (before * after <= 0f && before != 0f) {
                curX = prev + aimErr
                dropping = true
                dropY = SLAB_H * 1.5f
            } else if (abs(curX) > range) {
                // Overshot without dropping (a big slider jump): turn round.
                curX = curX.coerceIn(-range, range)
                dir = -dir
            }
        } else {
            dropY -= a * speed * DROP_SPEED
            if (dropY <= 0f) land(env)
        }
    }

    private fun land(env: SimEnv) {
        val prevX = lx[layers - 1]
        val prevW = lw[layers - 1]
        val off = curX - prevX
        val y = BASE_Y + (layers + 0.5f) * SLAB_H
        if (abs(off) <= PERFECT) {
            // Dead on: snapped, nothing cut; a run of them earns width back.
            streak++
            longestStreak = max(longestStreak, streak)
            perfectGlow = 1f
            val grow = if (streak >= 3) min(baseW, prevW * 1.06f) else prevW
            lx[layers] = prevX; lw[layers] = grow
            env.emit(EventKind.CROSS, (0.3f + 0.08f * streak).coerceAtMost(1f), 0f, 0.7f)
            if (streak == 3 || streak == 5 || streak % 10 == 0) env.callout("Perfect ×$streak", big = streak >= 5)
        } else {
            streak = 0
            val keep = prevW - abs(off)
            if (keep <= MIN_W) {
                // Nothing left to stand on: it all comes down.
                spawnCut(curX, y, curW, off, layers)
                failT = 0f
                env.emit(EventKind.DEATH, 0.2f, 0f, 0.9f)
                return
            }
            val newX = prevX + off * 0.5f
            lx[layers] = newX; lw[layers] = keep
            // The overhang, sliced off, falls away on its side.
            val side = if (off > 0f) 1f else -1f
            val cw = abs(off)
            spawnCut(newX + side * (keep + cw) * 0.5f, y, cw, side, layers)
        }
        layers++
        best = max(best, layers - 1)
        env.emit(EventKind.HIT, ((layers - 1).toFloat() / target * 0.85f + 0.1f).coerceAtMost(1f), (lx[layers - 1] / range).coerceIn(-1f, 1f), 0.5f)
        if (layers - 1 >= target && doneAt < 0f) {
            doneAt = elapsed
            payoffFrame = true
            env.callout("Tower done!", big = true)
            perfectGlow = 1f
            env.emit(EventKind.DONE, 0.9f, 0f, 1f)
        }
        // Past the finish line it keeps climbing; at the very top of what it
        // can hold, it starts a fresh tower rather than stopping.
        if (layers >= MAX_LAYERS - 1) { startTower(env); return }
        nextSlab(env)
    }

    private fun spawnCut(x: Float, y: Float, w: Float, side: Float, layer: Int) {
        val k = cutNext
        cutNext = (cutNext + 1) % CUTS
        cutX[k] = x; cutY[k] = y; cutW[k] = w
        cutVx[k] = 0.15f * (if (side >= 0f) 1f else -1f)
        cutVy[k] = 0.1f
        cutRot[k] = 0f
        cutSpin[k] = -2.2f * (if (side >= 0f) 1f else -1f)
        cutLife[k] = 1f
        cutCol[k] = layer.toFloat()
    }

    private fun colourOf(env: SimEnv, layer: Float): Int = env.palette.at((0.04f + layer * 0.031f) % 1f)

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val sy = -scroll
        // The finish line, once it is in view.
        val finishY = BASE_Y + (target + 1) * SLAB_H + sy
        if (finishY < 1.05f) {
            var x = -range
            while (x < range) {
                g.line(x, finishY, min(range, x + 0.03f), finishY, 0.004f, Palette.SUCCESS, 0.55f)
                x += 0.05f
            }
        }
        // Ground.
        g.rect(0f, BASE_Y - 0.5f + sy, range * 1.2f, 0.5f, GROUND)

        val fade = if (failT >= 0f) (1f - failT / FAIL_S).coerceIn(0f, 1f) else 1f
        val shake = if (failT in 0f..0.3f) (0.3f - failT) * 0.03f else 0f
        for (i in 0 until layers) {
            val y = BASE_Y + (i + 0.5f) * SLAB_H + sy
            if (y < -1.1f) continue
            val wob = if (shake > 0f) shake * (if (i % 2 == 0) 1f else -1f) * (i.toFloat() / layers) else 0f
            g.rect(lx[i] + wob, y, lw[i] * 0.5f, SLAB_H * 0.47f, colourOf(env, i.toFloat()), 0f, fade)
        }
        if (perfectGlow > 0f && layers > 1) {
            val t = layers - 1
            val y = BASE_Y + (t + 0.5f) * SLAB_H + sy
            val grow = 1f + (1f - perfectGlow) * 0.25f
            g.rect(lx[t], y, lw[t] * 0.5f * grow + 0.01f, SLAB_H * 0.5f * grow + 0.008f, Palette.TEXT_PRIMARY, 0f, perfectGlow * 0.35f)
            g.glow(lx[t], y, lw[t] * 0.8f, Palette.TEXT_PRIMARY, perfectGlow * 0.3f)
        }
        // The slab in play.
        if (failT < 0f) {
            val y = BASE_Y + (layers + 0.5f) * SLAB_H + (if (dropping) dropY else SLAB_H * 1.5f) + sy
            g.rect(curX, y, curW * 0.5f, SLAB_H * 0.47f, Palette.brighten(colourOf(env, layers.toFloat()), 0.25f))
        }
        for (k in 0 until CUTS) {
            if (cutLife[k] <= 0f) continue
            g.rect(cutX[k], cutY[k] + sy, cutW[k] * 0.5f, SLAB_H * 0.47f, colourOf(env, cutCol[k]), cutRot[k], cutLife[k].coerceIn(0f, 1f))
        }
        if (doneAt >= 0f && elapsed - doneAt < 2f) {
            val t = layers - 1
            g.glow(lx[t], BASE_Y + (t + 0.5f) * SLAB_H + sy, 0.35f, Palette.SUCCESS, 0.35f * (1f - (elapsed - doneAt) / 2f))
        }
    }

    override fun frameHalfWidth(env: SimEnv): Float = 0f

    override fun payoff(): Boolean = payoffFrame

    /** Endless: the tower keeps climbing past the finish line. */
    override fun finished(): Boolean = false

    override fun scoreForBest(): Float = longestStreak.toFloat()
    override fun scoreForBestText(value: Float): String = "${value.toInt()} perfect in a row"
    override fun liveBest(): Boolean = true

    override fun counters(): List<Pair<String, String>> = buildList {
        add("height" to if (doneAt < 0f) "${layers - 1} / $target" else "${layers - 1}")
        if (best > target) add("highest" to "$best")
        if (streak >= 2) add("perfect in a row" to "$streak")
        if (attempts > 1) add("attempt" to "$attempts")
    }

    companion object {
        const val MAX_LAYERS = 202
        const val CUTS = 24
        const val SLAB_H = 0.05f
        const val BASE_Y = -0.8f
        const val VIEW_TOP = 0.35f
        const val SLIDE = 0.75f
        const val DROP_SPEED = 2.2f
        const val FALL_G = 3.2f
        const val PERFECT = 0.0045f
        const val PERFECT_SHARE = 0.4f
        const val MIN_W = 0.012f
        const val FAIL_S = 1.4f
        val GROUND = 0xFF14161C.toInt()

        fun def(): SimDef = SimDef(
            id = "physics/stack",
            title = "Stack it",
            shelf = Shelf.PHYSICS,
            family = "Towers & stacking",
            blurb = "Every overhang is cut off. Every dead-on drop is a note higher.",
            formats = listOf(TrapFormat.PERFECT_FIT, TrapFormat.GROW, TrapFormat.NUMBER_UP),
            heroLabel = "Start again",
            defaultPalette = "Aurora",
            typicalSeconds = 22f,
            params = listOf(
                Param.int("height", "Finish line", 5, MAX_LAYERS - 2, 30, card = Card.DATA, logScale = true),
                Param.float("skill", "Steadiness", 0.3f, 1f, 0.85f, card = Card.MOTION),
                Param.float("speed", "Speed", 0.3f, 4f, 1f, card = Card.MOTION, logScale = true)
            ),
            create = { StackSim() }
        )
    }
}
